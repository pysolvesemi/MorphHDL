#!/usr/bin/env python3

import argparse
import json
import pathlib
import sys


def fail(message):
    print("Yosys Boolean-local routing contract: " + message, file=sys.stderr)
    return 1


def require_ports(owner, ports, expected):
    if set(ports) != set(expected):
        return "{} ports are {}, expected {}".format(
            owner, sorted(ports), sorted(expected)
        )
    for name, (direction, width) in sorted(expected.items()):
        definition = ports[name]
        if definition.get("direction") != direction:
            return "{}.{} direction is {}, expected {}".format(
                owner, name, definition.get("direction"), direction
            )
        if len(definition.get("bits", [])) != width:
            return "{}.{} width is {}, expected {}".format(
                owner, name, len(definition.get("bits", [])), width
            )
    return None


def require_direct_binding(cell, child_port, parent_bits, direction):
    if cell.get("port_directions", {}).get(child_port) != direction:
        return "cell port {} direction is {}, expected {}".format(
            child_port, cell.get("port_directions", {}).get(child_port), direction
        )
    if cell.get("connections", {}).get(child_port) != parent_bits:
        return "cell port {} is not connected bit-for-bit to its parent port".format(
            child_port
        )
    return None


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Check the exact two-level hierarchy selected by a Boolean local "
            "used in both generate-if and a child Boolean binding"
        )
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument("--branch", required=True, choices=("high", "low"))
    args = parser.parse_args()

    with args.netlist.open("r", encoding="utf-8") as handle:
        modules = json.load(handle).get("modules", {})

    top = modules.get("BooleanLocalRouting")
    if top is None:
        return fail("missing top module BooleanLocalRouting")

    expected_ports = {
        "high_in": ("input", 8),
        "low_in": ("input", 8),
        "dout": ("output", 8),
    }
    top_ports = top.get("ports", {})
    problem = require_ports("BooleanLocalRouting", top_ports, expected_ports)
    if problem is not None:
        return fail(problem)

    route_name = "g_local_{}.route_inst".format(args.branch)
    top_cells = top.get("cells", {})
    if set(top_cells) != {route_name}:
        return fail(
            "top cells are {}, expected only {}".format(
                sorted(top_cells), route_name
            )
        )
    route_cell = top_cells[route_name]
    route_type = route_cell.get("type")
    if not isinstance(route_type, str) or "BooleanLocalRoute" not in route_type:
        return fail(
            "{} targets {}, expected BooleanLocalRoute specialization".format(
                route_name, route_type
            )
        )
    route = modules.get(route_type)
    if route is None:
        return fail("{} targets missing module {}".format(route_name, route_type))
    route_ports = route.get("ports", {})
    problem = require_ports(route_type, route_ports, expected_ports)
    if problem is not None:
        return fail(problem)
    if set(route_cell.get("connections", {})) != set(expected_ports):
        return fail(
            "{} connections are {}, expected {}".format(
                route_name,
                sorted(route_cell.get("connections", {})),
                sorted(expected_ports),
            )
        )
    for name, (direction, _) in sorted(expected_ports.items()):
        problem = require_direct_binding(
            route_cell, name, top_ports[name].get("bits", []), direction
        )
        if problem is not None:
            return fail("{} {}".format(route_name, problem))

    selected_name = "g_{}.selected_inst".format(args.branch)
    route_cells = route.get("cells", {})
    if set(route_cells) != {selected_name}:
        return fail(
            "{} cells are {}, expected only {}".format(
                route_type, sorted(route_cells), selected_name
            )
        )
    selected = route_cells[selected_name]
    expected_leaf_type = (
        "BooleanLocalHighRoute"
        if args.branch == "high"
        else "BooleanLocalLowRoute"
    )
    if selected.get("type") != expected_leaf_type:
        return fail(
            "{} targets {}, expected {}".format(
                selected_name, selected.get("type"), expected_leaf_type
            )
        )

    leaf = modules.get(expected_leaf_type)
    if leaf is None:
        return fail("selected leaf module {} is missing".format(expected_leaf_type))
    leaf_input = "high_in" if args.branch == "high" else "low_in"
    leaf_output = "high_out" if args.branch == "high" else "low_out"
    expected_leaf_ports = {
        leaf_input: ("input", 8),
        leaf_output: ("output", 8),
    }
    problem = require_ports(expected_leaf_type, leaf.get("ports", {}), expected_leaf_ports)
    if problem is not None:
        return fail(problem)
    if set(selected.get("connections", {})) != set(expected_leaf_ports):
        return fail(
            "{} connections are {}, expected {}".format(
                selected_name,
                sorted(selected.get("connections", {})),
                sorted(expected_leaf_ports),
            )
        )

    parent_input = "high_in" if args.branch == "high" else "low_in"
    for child_port, parent_port, direction in (
        (leaf_input, parent_input, "input"),
        (leaf_output, "dout", "output"),
    ):
        problem = require_direct_binding(
            selected, child_port, route_ports[parent_port].get("bits", []), direction
        )
        if problem is not None:
            return fail("{} {}".format(selected_name, problem))

    print(
        "Yosys BooleanLocalRouting selects {} through {} and {} with exact bindings".format(
            expected_leaf_type, route_name, selected_name
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
