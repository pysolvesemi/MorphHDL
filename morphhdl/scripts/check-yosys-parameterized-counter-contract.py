#!/usr/bin/env python3

import argparse
import json
import pathlib
import sys


def fail(message):
    print("Yosys parameterized-counter contract: " + message, file=sys.stderr)
    return 1


def integer(value):
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        text = value.strip()
        if text and set(text.lower()) <= {"0", "1"}:
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


def is_zero(bits):
    return bool(bits) and all(bit in ("0", 0) for bit in bits)


def is_one(bits):
    return (
        bool(bits)
        and bits[0] in ("1", 1)
        and all(bit in ("0", 0) for bit in bits[1:])
    )


def constant_value(bits):
    if not bits or any(bit not in ("0", "1", 0, 1) for bit in bits):
        return None
    return sum((1 << index) for index, bit in enumerate(bits) if bit in ("1", 1))


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Check ParameterizedCounter is one positive-edge, active-high "
            "enabled modulo-up counter with synchronous reset-to-zero"
        )
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument("--limit", required=True, type=int)
    args = parser.parse_args()

    if args.limit < 2:
        return fail("structural checker requires LIMIT >= 2")
    width = max(1, (args.limit - 1).bit_length())

    with args.netlist.open("r", encoding="utf-8") as handle:
        modules = json.load(handle).get("modules", {})
    top = modules.get("ParameterizedCounter")
    if top is None:
        return fail("missing top module ParameterizedCounter")

    ports = top.get("ports", {})
    expected_ports = {
        "clk": ("input", 1),
        "count": ("output", width),
        "enable": ("input", 1),
        "reset": ("input", 1),
    }
    if set(ports) != set(expected_ports):
        return fail("ports are {}, expected {}".format(sorted(ports), sorted(expected_ports)))
    for name, (direction, port_width) in sorted(expected_ports.items()):
        problem = require_port(ports, name, direction, port_width)
        if problem is not None:
            return fail(problem)

    cells = top.get("cells", {})
    expected_types = {"$add": 1, "$eq": 1, "$mux": 1, "$sdffe": 1}
    actual_types = {}
    for cell in cells.values():
        cell_type = cell.get("type")
        actual_types[cell_type] = actual_types.get(cell_type, 0) + 1
    if actual_types != expected_types:
        return fail("cell types are {}, expected {}".format(actual_types, expected_types))

    by_type = {
        cell_type: next(
            (name, cell)
            for name, cell in cells.items()
            if cell.get("type") == cell_type
        )
        for cell_type in expected_types
    }

    register_name, register = by_type["$sdffe"]
    register_connections = register.get("connections", {})
    if set(register_connections) != {"CLK", "D", "EN", "Q", "SRST"}:
        return fail("{} has unexpected ports {}".format(register_name, sorted(register_connections)))
    for name, expected in (
        ("CLK", ports["clk"]["bits"]),
        ("EN", ports["enable"]["bits"]),
        ("Q", ports["count"]["bits"]),
        ("SRST", ports["reset"]["bits"]),
    ):
        if register_connections.get(name) != expected:
            return fail("{} {} is not connected bit-for-bit".format(register_name, name))
    for parameter, expected in (
        ("WIDTH", width),
        ("CLK_POLARITY", 1),
        ("EN_POLARITY", 1),
        ("SRST_POLARITY", 1),
        ("SRST_VALUE", 0),
    ):
        problem = require_parameter(
            register_name, register.get("parameters", {}), parameter, expected
        )
        if problem is not None:
            return fail(problem)

    mux_name, mux = by_type["$mux"]
    mux_connections = mux.get("connections", {})
    if set(mux_connections) != {"A", "B", "S", "Y"}:
        return fail("{} has unexpected ports {}".format(mux_name, sorted(mux_connections)))
    if mux_connections.get("Y") != register_connections.get("D"):
        return fail("{} does not solely feed the counter register".format(mux_name))
    if not is_zero(mux_connections.get("B", [])):
        return fail("{} terminal branch is not zero".format(mux_name))
    problem = require_parameter(mux_name, mux.get("parameters", {}), "WIDTH", width)
    if problem is not None:
        return fail(problem)

    add_name, add = by_type["$add"]
    add_connections = add.get("connections", {})
    if add_connections.get("A") != ports["count"]["bits"]:
        return fail("{} A is not connected directly to count".format(add_name))
    if not is_one(add_connections.get("B", [])):
        return fail("{} B is not exactly positive one".format(add_name))
    if add_connections.get("Y") != mux_connections.get("A"):
        return fail("{} result does not feed the nonterminal mux branch".format(add_name))
    for parameter, expected in (
        ("A_SIGNED", 0),
        ("B_SIGNED", 0),
        ("A_WIDTH", width),
        ("B_WIDTH", 1),
        ("Y_WIDTH", width),
    ):
        problem = require_parameter(add_name, add.get("parameters", {}), parameter, expected)
        if problem is not None:
            return fail(problem)

    comparison_name, comparison = by_type["$eq"]
    comparison_connections = comparison.get("connections", {})
    comparison_a = comparison_connections.get("A", [])
    if comparison_a[:width] != ports["count"]["bits"] or not is_zero(comparison_a[width:]):
        return fail("{} A is not zero-extended count".format(comparison_name))
    if constant_value(comparison_connections.get("B", [])) != args.limit - 1:
        return fail("{} B is not LIMIT - 1".format(comparison_name))
    if comparison_connections.get("Y") != mux_connections.get("S"):
        return fail("{} result does not select terminal wrap".format(comparison_name))
    for parameter, expected in (
        ("A_SIGNED", 0),
        ("B_SIGNED", 0),
        ("Y_WIDTH", 1),
    ):
        problem = require_parameter(
            comparison_name, comparison.get("parameters", {}), parameter, expected
        )
        if problem is not None:
            return fail(problem)

    print(
        "Yosys ParameterizedCounter LIMIT={} is one {}-bit positive-edge "
        "active-high enabled modulo-up counter with synchronous reset-to-zero".format(
            args.limit, width
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
