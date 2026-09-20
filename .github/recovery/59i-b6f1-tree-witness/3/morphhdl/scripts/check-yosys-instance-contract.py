#!/usr/bin/env python3

import argparse
import json
import pathlib
import sys


def fail(message):
    print("Yosys instance contract: " + message, file=sys.stderr)
    return 1


def parse_binding(parser, specification):
    fields = specification.split(":")
    if len(fields) != 4:
        parser.error("--binding must use CHILD_PORT:TOP_PORT:DIRECTION:WIDTH")
    child_port, top_port, direction, width_text = fields
    if not child_port or not top_port:
        parser.error("binding port names must not be empty")
    if direction not in {"input", "output", "inout"}:
        parser.error("invalid binding direction: {}".format(direction))
    try:
        width = int(width_text)
    except ValueError:
        parser.error("binding width must be an integer: {}".format(width_text))
    if width <= 0:
        parser.error("binding width must be positive: {}".format(width))
    return child_port, top_port, direction, width


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Check one exact named Yosys hierarchy instance and its named port bindings"
        )
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument("top_module")
    parser.add_argument("instance")
    parser.add_argument(
        "--child-type",
        help="expected exact elaborated child module type",
    )
    parser.add_argument(
        "--binding",
        action="append",
        required=True,
        metavar="CHILD_PORT:TOP_PORT:DIRECTION:WIDTH",
        help="expected direct child-to-top port binding; repeat for every child port",
    )
    args = parser.parse_args()

    expected_bindings = {}
    expected_top_ports = {}
    for specification in args.binding:
        child_port, top_port, direction, width = parse_binding(parser, specification)
        if child_port in expected_bindings:
            parser.error("duplicate child port binding: {}".format(child_port))
        if top_port in expected_top_ports:
            parser.error("duplicate top port binding: {}".format(top_port))
        expected_bindings[child_port] = (top_port, direction, width)
        expected_top_ports[top_port] = (direction, width)

    with args.netlist.open("r", encoding="utf-8") as handle:
        netlist = json.load(handle)

    modules = netlist.get("modules", {})
    top = modules.get(args.top_module)
    if top is None:
        return fail("missing top module {}".format(args.top_module))

    top_ports = top.get("ports", {})
    if set(top_ports) != set(expected_top_ports):
        return fail(
            "top ports are {}, expected {}".format(
                sorted(top_ports), sorted(expected_top_ports)
            )
        )

    cells = top.get("cells", {})
    if set(cells) != {args.instance}:
        return fail(
            "top cells are {}, expected only {}".format(
                sorted(cells), args.instance
            )
        )

    instance = cells[args.instance]
    child_type = instance.get("type")
    if not isinstance(child_type, str) or not child_type:
        return fail("instance {} has no module type".format(args.instance))
    if child_type == args.top_module:
        return fail("instance {} recursively targets the top module".format(args.instance))
    if args.child_type is not None and child_type != args.child_type:
        return fail(
            "instance {} targets {}, expected {}".format(
                args.instance, child_type, args.child_type
            )
        )

    child = modules.get(child_type)
    if child is None:
        return fail(
            "instance {} targets missing elaborated module {}".format(
                args.instance, child_type
            )
        )

    child_ports = child.get("ports", {})
    cell_directions = instance.get("port_directions", {})
    connections = instance.get("connections", {})
    expected_child_ports = set(expected_bindings)
    for label, actual in (
        ("child ports", child_ports),
        ("instance port directions", cell_directions),
        ("instance connections", connections),
    ):
        if set(actual) != expected_child_ports:
            return fail(
                "{} are {}, expected {}".format(
                    label, sorted(actual), sorted(expected_child_ports)
                )
            )

    for child_port, (top_port, direction, width) in sorted(expected_bindings.items()):
        top_definition = top_ports[top_port]
        child_definition = child_ports[child_port]
        actual_direction = child_definition.get("direction")
        if actual_direction != direction:
            return fail(
                "child port {} direction is {}, expected {}".format(
                    child_port, actual_direction, direction
                )
            )
        cell_direction = cell_directions.get(child_port)
        if cell_direction != direction:
            return fail(
                "instance port {} direction is {}, expected {}".format(
                    child_port, cell_direction, direction
                )
            )
        top_direction = top_definition.get("direction")
        if top_direction != direction:
            return fail(
                "top port {} direction is {}, expected {}".format(
                    top_port, top_direction, direction
                )
            )

        top_bits = top_definition.get("bits", [])
        child_bits = child_definition.get("bits", [])
        connection_bits = connections.get(child_port, [])
        for label, bits in (
            ("top port {}".format(top_port), top_bits),
            ("child port {}".format(child_port), child_bits),
            ("instance connection {}".format(child_port), connection_bits),
        ):
            if len(bits) != width:
                return fail(
                    "{} width is {}, expected {}".format(label, len(bits), width)
                )
        if connection_bits != top_bits:
            return fail(
                "instance port {} is not connected bit-for-bit to top port {}".format(
                    child_port, top_port
                )
            )

    print(
        "Yosys instance {}.{} targets {} with {} exact bindings".format(
            args.top_module, args.instance, child_type, len(expected_bindings)
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
