#!/usr/bin/env python3

import argparse
import json
import pathlib
import sys


def fail(message):
    print("Yosys case-routing contract: " + message, file=sys.stderr)
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
        description="Check the exact CaseRouting choice/default hierarchy"
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument(
        "--branch",
        required=True,
        choices=("zero", "one", "default"),
        help="expected selected generate-case branch",
    )
    args = parser.parse_args()

    with args.netlist.open("r", encoding="utf-8") as handle:
        modules = json.load(handle).get("modules", {})

    top = modules.get("CaseRouting")
    if top is None:
        return fail("missing top module CaseRouting")
    expected_top_ports = {"din": ("input", 8), "dout": ("output", 8)}
    top_ports = top.get("ports", {})
    problem = require_ports("CaseRouting", top_ports, expected_top_ports)
    if problem is not None:
        return fail(problem)

    selected_name = "g_{}.selected_inst".format(args.branch)
    cells = top.get("cells", {})
    if set(cells) != {selected_name}:
        return fail(
            "top cells are {}, expected only {}".format(
                sorted(cells), selected_name
            )
        )
    selected = cells[selected_name]
    route_names = {
        "zero": ("CaseZeroRoute", "zero_in", "zero_out"),
        "one": ("CaseOneRoute", "one_in", "one_out"),
        "default": ("CaseDefaultRoute", "default_in", "default_out"),
    }
    expected_type, input_port, output_port = route_names[args.branch]
    if selected.get("type") != expected_type:
        return fail(
            "{} targets {}, expected {}".format(
                selected_name, selected.get("type"), expected_type
            )
        )
    leaf = modules.get(expected_type)
    if leaf is None:
        return fail("selected route module {} is missing".format(expected_type))
    expected_leaf_ports = {
        input_port: ("input", 8),
        output_port: ("output", 8),
    }
    problem = require_ports(expected_type, leaf.get("ports", {}), expected_leaf_ports)
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
    for child_port, parent_port, direction in (
        (input_port, "din", "input"),
        (output_port, "dout", "output"),
    ):
        problem = require_direct_binding(
            selected, child_port, top_ports[parent_port].get("bits", []), direction
        )
        if problem is not None:
            return fail("{} {}".format(selected_name, problem))

    print(
        "Yosys CaseRouting selects {} through {} with exact bindings".format(
            expected_type, selected_name
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
