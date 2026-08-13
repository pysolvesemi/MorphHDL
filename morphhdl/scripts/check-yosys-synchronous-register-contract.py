#!/usr/bin/env python3

import argparse
import json
import pathlib
import sys


def fail(message):
    print("Yosys synchronous-register contract: " + message, file=sys.stderr)
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


def parameter_integer(value):
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


def require_parameter(parameters, name, expected):
    if name not in parameters:
        return "missing parameter {}".format(name)
    actual = parameter_integer(parameters.get(name))
    if actual != expected:
        return "parameter {} is {!r}, expected {}".format(
            name, parameters.get(name), expected
        )
    return None


def require_connections(cell_name, connections, expected):
    if set(connections) != set(expected):
        return "{} ports are {}, expected {}".format(
            cell_name, sorted(connections), sorted(expected)
        )
    for name, bits in sorted(expected.items()):
        if connections.get(name) != bits:
            return "{} port {} is not connected bit-for-bit".format(
                cell_name, name
            )
    return None


def check_sdff(name, cell, ports, width):
    expected_connections = {
        "CLK": ports["clk"].get("bits", []),
        "D": ports["data_in"].get("bits", []),
        "Q": ports["data_out"].get("bits", []),
        "SRST": ports["reset"].get("bits", []),
    }
    problem = require_connections(name, cell.get("connections", {}), expected_connections)
    if problem is not None:
        return problem

    parameters = cell.get("parameters", {})
    for parameter_name, expected in (
        ("WIDTH", width),
        ("CLK_POLARITY", 1),
        ("SRST_POLARITY", 1),
        ("SRST_VALUE", 0),
    ):
        problem = require_parameter(parameters, parameter_name, expected)
        if problem is not None:
            return "{} {}".format(name, problem)
    return None


def check_dff_and_mux(dff_name, dff, mux_name, mux, ports, width):
    dff_connections = dff.get("connections", {})
    if set(dff_connections) != {"CLK", "D", "Q"}:
        return "{} ports are {}, expected ['CLK', 'D', 'Q']".format(
            dff_name, sorted(dff_connections)
        )
    if dff_connections.get("CLK") != ports["clk"].get("bits", []):
        return "{} CLK is not connected bit-for-bit to clk".format(dff_name)
    if dff_connections.get("Q") != ports["data_out"].get("bits", []):
        return "{} Q is not connected bit-for-bit to data_out".format(dff_name)
    if len(dff_connections.get("D", [])) != width:
        return "{} D width is {}, expected {}".format(
            dff_name, len(dff_connections.get("D", [])), width
        )
    for parameter_name, expected in (("WIDTH", width), ("CLK_POLARITY", 1)):
        problem = require_parameter(dff.get("parameters", {}), parameter_name, expected)
        if problem is not None:
            return "{} {}".format(dff_name, problem)

    expected_mux_connections = {
        "A": ports["data_in"].get("bits", []),
        "B": ["0"] * width,
        "S": ports["reset"].get("bits", []),
        "Y": dff_connections.get("D", []),
    }
    problem = require_connections(
        mux_name, mux.get("connections", {}), expected_mux_connections
    )
    if problem is not None:
        return problem
    problem = require_parameter(mux.get("parameters", {}), "WIDTH", width)
    if problem is not None:
        return "{} {}".format(mux_name, problem)
    return None


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Check SynchronousRegister is one positive-edge flip-flop with "
            "active-high synchronous reset-to-zero"
        )
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument("--width", required=True, type=int)
    args = parser.parse_args()

    if args.width < 1:
        return fail("expected width must be positive")

    with args.netlist.open("r", encoding="utf-8") as handle:
        modules = json.load(handle).get("modules", {})

    top = modules.get("SynchronousRegister")
    if top is None:
        return fail("missing top module SynchronousRegister")

    ports = top.get("ports", {})
    expected_ports = {
        "clk": ("input", 1),
        "data_in": ("input", args.width),
        "data_out": ("output", args.width),
        "reset": ("input", 1),
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
    latches = {
        name: cell.get("type")
        for name, cell in cells.items()
        if "latch" in cell.get("type", "").lower()
    }
    if latches:
        return fail("unexpected latch cells: {}".format(latches))

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

    sequential_name, sequential_cell = sequential[0]
    sequential_type = sequential_cell.get("type")
    if sequential_type == "$sdff":
        unexpected = {
            name: cell.get("type")
            for name, cell in cells.items()
            if name != sequential_name
        }
        if unexpected:
            return fail("unexpected cells beside $sdff: {}".format(unexpected))
        problem = check_sdff(
            sequential_name, sequential_cell, ports, args.width
        )
    elif sequential_type == "$dff":
        muxes = [
            (name, cell)
            for name, cell in cells.items()
            if cell.get("type") == "$mux"
        ]
        unexpected = {
            name: cell.get("type")
            for name, cell in cells.items()
            if name != sequential_name and cell.get("type") != "$mux"
        }
        if unexpected or len(muxes) != 1:
            return fail(
                "expected exactly one reset mux beside $dff; unexpected cells are {} and mux count is {}".format(
                    unexpected, len(muxes)
                )
            )
        mux_name, mux = muxes[0]
        problem = check_dff_and_mux(
            sequential_name,
            sequential_cell,
            mux_name,
            mux,
            ports,
            args.width,
        )
    else:
        return fail(
            "flip-flop type is {}, expected $sdff or $dff".format(
                sequential_type
            )
        )

    if problem is not None:
        return fail(problem)

    print(
        "Yosys SynchronousRegister is one {}-bit positive-edge register with active-high synchronous reset-to-zero".format(
            args.width
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
