#!/usr/bin/env python3

import argparse
import json
import pathlib
import sys


def fail(message):
    print("Yosys Boolean-forwarding contract: " + message, file=sys.stderr)
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
        bits = definition.get("bits", [])
        if len(bits) != width:
            return "{}.{} width is {}, expected {}".format(
                owner, name, len(bits), width
            )
    return None


def require_direct_binding(cell, child_port, parent_bits, direction):
    cell_directions = cell.get("port_directions", {})
    connections = cell.get("connections", {})
    if cell_directions.get(child_port) != direction:
        return "cell port {} direction is {}, expected {}".format(
            child_port, cell_directions.get(child_port), direction
        )
    if connections.get(child_port) != parent_bits:
        return "cell port {} is not connected bit-for-bit to its parent port".format(
            child_port
        )
    return None


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Check the exact two-level BooleanForwarding hierarchy selected by "
            "one forwarded Boolean parameter"
        )
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument(
        "--branch",
        required=True,
        choices=("high", "low"),
        help="expected selected BooleanRoute generate-if branch",
    )
    args = parser.parse_args()

    with args.netlist.open("r", encoding="utf-8") as handle:
        netlist = json.load(handle)

    modules = netlist.get("modules", {})
    top = modules.get("BooleanForwarding")
    if top is None:
        return fail("missing top module BooleanForwarding")

    top_ports = top.get("ports", {})
    expected_top_ports = {
        "high_in": ("input", 8),
        "low_in": ("input", 8),
        "dout": ("output", 8),
    }
    problem = require_ports("BooleanForwarding", top_ports, expected_top_ports)
    if problem is not None:
        return fail(problem)

    top_cells = top.get("cells", {})
    if set(top_cells) != {"route_inst"}:
        return fail(
            "top cells are {}, expected only route_inst".format(sorted(top_cells))
        )
    route_cell = top_cells["route_inst"]
    route_type = route_cell.get("type")
    route = modules.get(route_type)
    if route is None:
        return fail("route_inst targets missing module {}".format(route_type))
    route_ports = route.get("ports", {})
    problem = require_ports(route_type, route_ports, expected_top_ports)
    if problem is not None:
        return fail(problem)
    if set(route_cell.get("connections", {})) != set(expected_top_ports):
        return fail(
            "route_inst connections are {}, expected {}".format(
                sorted(route_cell.get("connections", {})), sorted(expected_top_ports)
            )
        )
    for name, (direction, _) in sorted(expected_top_ports.items()):
        problem = require_direct_binding(
            route_cell, name, top_ports[name].get("bits", []), direction
        )
        if problem is not None:
            return fail("route_inst " + problem)

    branch_prefix = "g_{}".format(args.branch)
    selected_name = "{}.selected_inst".format(branch_prefix)
    route_cells = route.get("cells", {})
    if set(route_cells) != {selected_name}:
        return fail(
            "{} cells are {}, expected only {}".format(
                route_type, sorted(route_cells), selected_name
            )
        )
    selected = route_cells[selected_name]
    expected_leaf_type = (
        "BooleanHighRoute" if args.branch == "high" else "BooleanLowRoute"
    )
    selected_type = selected.get("type")
    if selected_type != expected_leaf_type:
        return fail(
            "{} targets {}, expected {}".format(
                selected_name, selected_type, expected_leaf_type
            )
        )
    leaf = modules.get(selected_type)
    if leaf is None:
        return fail("selected leaf module {} is missing".format(selected_type))
    leaf_input = "high_in" if args.branch == "high" else "low_in"
    leaf_output = "high_out" if args.branch == "high" else "low_out"
    expected_leaf_ports = {
        leaf_input: ("input", 8),
        leaf_output: ("output", 8),
    }
    problem = require_ports(selected_type, leaf.get("ports", {}), expected_leaf_ports)
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

    selected_input = "high_in" if args.branch == "high" else "low_in"
    for child_port, parent_port, direction in (
        (leaf_input, selected_input, "input"),
        (leaf_output, "dout", "output"),
    ):
        problem = require_direct_binding(
            selected, child_port, route_ports[parent_port].get("bits", []), direction
        )
        if problem is not None:
            return fail("{} {}".format(selected_name, problem))

    print(
        "Yosys BooleanForwarding selects {} through {} with exact bindings".format(
            selected_type, selected_name
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
