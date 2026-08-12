#!/usr/bin/env python3

import argparse
import json
import pathlib
import sys


def main():
    parser = argparse.ArgumentParser(
        description="Check the exact synthesized port contract of a Yosys module"
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument("module")
    parser.add_argument(
        "--port",
        action="append",
        required=True,
        metavar="NAME:DIRECTION:WIDTH",
        help="expected port; repeat for every port in the module",
    )
    args = parser.parse_args()

    expected_ports = {}
    for specification in args.port:
        fields = specification.split(":")
        if len(fields) != 3:
            parser.error("--port must use NAME:DIRECTION:WIDTH")
        name, direction, width_text = fields
        if not name:
            parser.error("--port name must not be empty")
        if name in expected_ports:
            parser.error("duplicate --port name: {}".format(name))
        if direction not in {"input", "output", "inout"}:
            parser.error("invalid port direction: {}".format(direction))
        try:
            width = int(width_text)
        except ValueError:
            parser.error("port width must be an integer: {}".format(width_text))
        if width <= 0:
            parser.error("port width must be positive: {}".format(width))
        expected_ports[name] = (direction, width)

    with args.netlist.open("r", encoding="utf-8") as handle:
        netlist = json.load(handle)

    module = netlist.get("modules", {}).get(args.module)
    if module is None:
        print("Yosys JSON is missing module {}".format(args.module), file=sys.stderr)
        return 1

    actual_ports = module.get("ports", {})
    if set(actual_ports) != set(expected_ports):
        missing = sorted(set(expected_ports).difference(actual_ports))
        unexpected = sorted(set(actual_ports).difference(expected_ports))
        if missing:
            print("Yosys JSON is missing ports: {}".format(", ".join(missing)), file=sys.stderr)
        if unexpected:
            print("Yosys JSON has unexpected ports: {}".format(", ".join(unexpected)), file=sys.stderr)
        return 1

    for name, (expected_direction, expected_width) in sorted(expected_ports.items()):
        port = actual_ports[name]
        if port.get("direction") != expected_direction:
            print(
                "Port {} direction is {}, expected {}".format(
                    name, port.get("direction"), expected_direction
                ),
                file=sys.stderr,
            )
            return 1
        actual_width = len(port.get("bits", []))
        if actual_width != expected_width:
            print(
                "Port {} width is {}, expected {}".format(
                    name, actual_width, expected_width
                ),
                file=sys.stderr,
            )
            return 1

    print(
        "Yosys module {} has the expected {}-port contract".format(
            args.module, len(expected_ports)
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
