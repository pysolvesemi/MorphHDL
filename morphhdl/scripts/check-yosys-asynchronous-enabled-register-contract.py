#!/usr/bin/env python3

import argparse
import json
import pathlib
import sys


def fail(message):
    print("Yosys asynchronous-enabled-register contract: " + message, file=sys.stderr)
    return 1


def integer(value):
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        text = value.strip()
        if text and set(text) <= {"0", "1"}:
            return int(text, 2)
        try:
            return int(text, 0)
        except ValueError:
            return None
    return None


def require_parameter(cell_name, parameters, name, expected):
    if name not in parameters:
        return "{} is missing parameter {}".format(cell_name, name)
    if integer(parameters[name]) != expected:
        return "{} parameter {} is {!r}, expected {}".format(
            cell_name, name, parameters[name], expected
        )
    return None


def require_connections(cell_name, connections, expected):
    if set(connections) != set(expected):
        return "{} ports are {}, expected {}".format(
            cell_name, sorted(connections), sorted(expected)
        )
    for name, bits in sorted(expected.items()):
        if connections.get(name) != bits:
            return "{} port {} is not connected bit-for-bit".format(cell_name, name)
    return None


def require_port(ports, name, direction, width):
    port = ports.get(name)
    if port is None:
        return "missing port {}".format(name)
    if port.get("direction") != direction:
        return "port {} direction is {}, expected {}".format(
            name, port.get("direction"), direction
        )
    if len(port.get("bits", [])) != width:
        return "port {} width is {}, expected {}".format(
            name, len(port.get("bits", [])), width
        )
    return None


def check_adffe(name, cell, ports, width):
    problem = require_connections(
        name,
        cell.get("connections", {}),
        {
            "ARST": ports["reset"]["bits"],
            "CLK": ports["clk"]["bits"],
            "D": ports["data_in"]["bits"],
            "EN": ports["enable"]["bits"],
            "Q": ports["data_out"]["bits"],
        },
    )
    if problem is not None:
        return problem
    parameters = cell.get("parameters", {})
    for parameter, expected in (
        ("WIDTH", width),
        ("CLK_POLARITY", 1),
        ("EN_POLARITY", 1),
        ("ARST_POLARITY", 1),
        ("ARST_VALUE", 0),
    ):
        problem = require_parameter(name, parameters, parameter, expected)
        if problem is not None:
            return problem
    return None


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Check AsynchronousEnabledRegister is one positive-edge enabled "
            "register with active-high asynchronous reset-to-zero"
        )
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument("--width", required=True, type=int)
    args = parser.parse_args()

    if args.width < 1:
        return fail("expected width must be positive")
    with args.netlist.open("r", encoding="utf-8") as handle:
        modules = json.load(handle).get("modules", {})
    top = modules.get("AsynchronousEnabledRegister")
    if top is None:
        return fail("missing top module AsynchronousEnabledRegister")

    ports = top.get("ports", {})
    expected_ports = {
        "clk": ("input", 1),
        "data_in": ("input", args.width),
        "data_out": ("output", args.width),
        "enable": ("input", 1),
        "reset": ("input", 1),
    }
    if set(ports) != set(expected_ports):
        return fail("ports are {}, expected {}".format(sorted(ports), sorted(expected_ports)))
    for name, (direction, width) in sorted(expected_ports.items()):
        problem = require_port(ports, name, direction, width)
        if problem is not None:
            return fail(problem)

    cells = top.get("cells", {})
    if any("latch" in cell.get("type", "").lower() for cell in cells.values()):
        return fail("unexpected latch cell")
    sequential = [
        (name, cell)
        for name, cell in cells.items()
        if "dff" in cell.get("type", "").lower()
    ]
    if len(sequential) != 1:
        return fail(
            "found {} flip-flop cells, expected exactly one; cells are {}".format(
                len(sequential),
                {name: cell.get("type") for name, cell in sorted(cells.items())},
            )
        )

    name, cell = sequential[0]
    if cell.get("type") == "$adffe":
        unexpected = {
            cell_name: other.get("type")
            for cell_name, other in cells.items()
            if cell_name != name
        }
        if unexpected:
            return fail("unexpected cells beside $adffe: {}".format(unexpected))
        problem = check_adffe(name, cell, ports, args.width)
        if problem is not None:
            return fail(problem)
    else:
        return fail(
            "flip-flop type is {}, expected $adffe after proc/opt_dff".format(
                cell.get("type")
            )
        )

    print(
        "Yosys AsynchronousEnabledRegister is one {}-bit positive-edge active-high enabled register with active-high asynchronous reset-to-zero".format(
            args.width
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
