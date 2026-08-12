#!/usr/bin/env python3

import argparse
import json
import pathlib
import sys


def main():
    parser = argparse.ArgumentParser(
        description="Check synthesized ParameterizedWire port widths"
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument("expected_width", type=int)
    args = parser.parse_args()

    with args.netlist.open("r", encoding="utf-8") as handle:
        netlist = json.load(handle)

    module = netlist.get("modules", {}).get("ParameterizedWire")
    if module is None:
        print("Yosys JSON is missing module ParameterizedWire", file=sys.stderr)
        return 1

    expected_directions = {"din": "input", "dout": "output"}
    for name, expected_direction in expected_directions.items():
        port = module.get("ports", {}).get(name)
        if port is None:
            print("Yosys JSON is missing port {}".format(name), file=sys.stderr)
            return 1
        if port.get("direction") != expected_direction:
            print(
                "Port {} direction is {}, expected {}".format(
                    name, port.get("direction"), expected_direction
                ),
                file=sys.stderr,
            )
            return 1
        actual_width = len(port.get("bits", []))
        if actual_width != args.expected_width:
            print(
                "Port {} width is {}, expected {}".format(
                    name, actual_width, args.expected_width
                ),
                file=sys.stderr,
            )
            return 1

    print(
        "Yosys ParameterizedWire ports have expected width {}".format(
            args.expected_width
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
