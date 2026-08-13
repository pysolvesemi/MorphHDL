#!/usr/bin/env python3

import argparse
import json
import pathlib
import sys


def fail(message):
    print("Yosys runtime-mux contract: " + message, file=sys.stderr)
    return 1


def require_port(ports, name, direction, width):
    definition = ports.get(name)
    if definition is None:
        return "missing port {}".format(name)
    if definition.get("direction") != direction:
        return "port {} direction is {}, expected {}".format(
            name, definition.get("direction"), direction
        )
    if len(definition.get("bits", [])) != width:
        return "port {} width is {}, expected {}".format(
            name, len(definition.get("bits", [])), width
        )
    return None


def main():
    parser = argparse.ArgumentParser(
        description="Check RuntimeMux is one complete combinational mux without storage"
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument("--width", required=True, type=int)
    args = parser.parse_args()

    if args.width < 1:
        return fail("expected width must be positive")

    with args.netlist.open("r", encoding="utf-8") as handle:
        modules = json.load(handle).get("modules", {})

    top = modules.get("RuntimeMux")
    if top is None:
        return fail("missing top module RuntimeMux")
    ports = top.get("ports", {})
    expected_ports = {
        "data_false": ("input", args.width),
        "data_true": ("input", args.width),
        "result": ("output", args.width),
        "sel": ("input", 1),
    }
    if set(ports) != set(expected_ports):
        return fail(
            "ports are {}, expected {}".format(
                sorted(ports), sorted(expected_ports)
            )
        )
    for name, (direction, width) in sorted(expected_ports.items()):
        problem = require_port(ports, name, direction, width)
        if problem is not None:
            return fail(problem)

    cells = top.get("cells", {})
    storage = {
        name: cell.get("type")
        for name, cell in cells.items()
        if "latch" in cell.get("type", "").lower()
        or "dff" in cell.get("type", "").lower()
    }
    if storage:
        return fail("unexpected storage cells: {}".format(storage))

    muxes = [
        (name, cell)
        for name, cell in cells.items()
        if cell.get("type") == "$mux"
    ]
    if len(muxes) != 1:
        return fail(
            "found {} $mux cells, expected exactly one; cells are {}".format(
                len(muxes),
                {name: cell.get("type") for name, cell in sorted(cells.items())},
            )
        )

    mux_name, mux = muxes[0]
    connections = mux.get("connections", {})
    expected_connections = {
        "A": ports["data_false"].get("bits", []),
        "B": ports["data_true"].get("bits", []),
        "S": ports["sel"].get("bits", []),
        "Y": ports["result"].get("bits", []),
    }
    if set(connections) != set(expected_connections):
        return fail(
            "{} ports are {}, expected {}".format(
                mux_name, sorted(connections), sorted(expected_connections)
            )
        )
    for name, expected in sorted(expected_connections.items()):
        if connections.get(name) != expected:
            return fail("{} port {} is not connected bit-for-bit".format(mux_name, name))

    if mux.get("parameters", {}).get("WIDTH") not in (
        args.width,
        format(args.width, "b"),
        format(args.width, "032b"),
    ):
        # Yosys JSON encodes parameters differently across releases. Exact
        # connection widths above remain the normative portable check.
        pass

    print(
        "Yosys RuntimeMux is one {}-bit combinational mux with no storage".format(
            args.width
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
