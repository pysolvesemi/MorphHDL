#!/usr/bin/env python3

import argparse
import json
import pathlib
import re
import sys


def fail(message):
    print("Yosys synchronous-stream-m2s-pipe contract: " + message, file=sys.stderr)
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


def require_source_contract(path):
    source = path.read_text(encoding="utf-8")
    canonical_process = """  always @(posedge clk) begin : p_m2s_pipe
    if (reset == 1'b1) begin
      pop_valid <= 1'b0;
    end else if (push_ready == 1'b1) begin
      pop_valid <= push_valid;
    end
    if (push_ready == 1'b1) begin
      pop_data <= push_data;
    end
  end"""
    required_once = (
        "parameter integer WIDTH = 8",
        "assign push_ready = pop_ready || !pop_valid;",
        canonical_process,
    )
    for fragment in required_once:
        count = source.count(fragment)
        if count != 1:
            return "source contains {} copies of {!r}, expected one".format(
                count, fragment
            )
    if len(re.findall(r"\balways\s*@", source)) != 1:
        return "source does not contain exactly one sequential process"
    if re.search(r"\b(initial|negedge)\b|always_(comb|ff|latch)|always\s*@\*", source):
        return "source contains initialization, falling-edge, or SystemVerilog process syntax"
    if re.search(r"\b(memory|localparam|function|occupancy|pointer|flush)\b", source):
        return "source contains forbidden memory, helper, status, or pointer state"
    try:
        reset_body = source.split("if (reset == 1'b1) begin", 1)[1].split(
            "end else if", 1
        )[0]
    except IndexError:
        return "source reset branch is not canonical"
    if reset_body.count("pop_valid <= 1'b0;") != 1:
        return "source reset does not clear pop_valid exactly once"
    if "pop_data" in reset_body:
        return "source reset illegally initializes or gates pop_data"
    if source.count("pop_valid <=") != 2:
        return "source does not contain exactly reset and enabled pop_valid assignments"
    if source.count("pop_data <= push_data;") != 1:
        return "source does not contain exactly one independent payload capture"
    return None


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Check the strict Verilog-2001 one-entry synchronous Stream m2s "
            "pipeline source and retained Yosys process netlist"
        )
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument("--source", required=True, type=pathlib.Path)
    parser.add_argument("--width", required=True, type=int)
    args = parser.parse_args()

    if args.width < 1:
        return fail("expected width must be positive")
    source_problem = require_source_contract(args.source)
    if source_problem is not None:
        return fail(source_problem)

    with args.netlist.open("r", encoding="utf-8") as handle:
        modules = json.load(handle).get("modules", {})
    top = modules.get("SynchronousStreamM2sPipe")
    if top is None:
        return fail("missing top module SynchronousStreamM2sPipe")

    ports = top.get("ports", {})
    expected_ports = {
        "clk": ("input", 1),
        "pop_data": ("output", args.width),
        "pop_ready": ("input", 1),
        "pop_valid": ("output", 1),
        "push_data": ("input", args.width),
        "push_ready": ("output", 1),
        "push_valid": ("input", 1),
        "reset": ("input", 1),
    }
    if set(ports) != set(expected_ports):
        return fail("ports are {}, expected {}".format(sorted(ports), sorted(expected_ports)))
    for name, (direction, width) in sorted(expected_ports.items()):
        problem = require_port(ports, name, direction, width)
        if problem is not None:
            return fail(problem)

    cells = top.get("cells", {})
    by_type = {}
    for name, cell in cells.items():
        by_type.setdefault(cell.get("type"), []).append((name, cell))
    expected_cell_types = {"$sdffe": 1, "$dffe": 1, "$logic_not": 1, "$logic_or": 1}
    actual_cell_types = {cell_type: len(values) for cell_type, values in by_type.items()}
    if actual_cell_types != expected_cell_types:
        return fail(
            "cell inventory is {}, expected {}".format(
                dict(sorted(actual_cell_types.items())), expected_cell_types
            )
        )

    valid_name, valid = by_type["$sdffe"][0]
    problem = require_connections(
        valid_name,
        valid.get("connections", {}),
        {
            "CLK": ports["clk"]["bits"],
            "D": ports["push_valid"]["bits"],
            "EN": ports["push_ready"]["bits"],
            "Q": ports["pop_valid"]["bits"],
            "SRST": ports["reset"]["bits"],
        },
    )
    if problem is not None:
        return fail(problem)
    for parameter, expected in (
        ("WIDTH", 1),
        ("CLK_POLARITY", 1),
        ("EN_POLARITY", 1),
        ("SRST_POLARITY", 1),
        ("SRST_VALUE", 0),
    ):
        problem = require_parameter(valid_name, valid.get("parameters", {}), parameter, expected)
        if problem is not None:
            return fail(problem)

    data_name, data = by_type["$dffe"][0]
    problem = require_connections(
        data_name,
        data.get("connections", {}),
        {
            "CLK": ports["clk"]["bits"],
            "D": ports["push_data"]["bits"],
            "EN": ports["push_ready"]["bits"],
            "Q": ports["pop_data"]["bits"],
        },
    )
    if problem is not None:
        return fail(problem)
    for parameter, expected in (
        ("WIDTH", args.width),
        ("CLK_POLARITY", 1),
        ("EN_POLARITY", 1),
    ):
        problem = require_parameter(data_name, data.get("parameters", {}), parameter, expected)
        if problem is not None:
            return fail(problem)

    not_name, not_cell = by_type["$logic_not"][0]
    not_output = not_cell.get("connections", {}).get("Y", [])
    if len(not_output) != 1:
        return fail("{} does not produce exactly one ready-complement bit".format(not_name))
    problem = require_connections(
        not_name,
        not_cell.get("connections", {}),
        {"A": ports["pop_valid"]["bits"], "Y": not_output},
    )
    if problem is not None:
        return fail(problem)

    or_name, or_cell = by_type["$logic_or"][0]
    problem = require_connections(
        or_name,
        or_cell.get("connections", {}),
        {
            "A": ports["pop_ready"]["bits"],
            "B": not_output,
            "Y": ports["push_ready"]["bits"],
        },
    )
    if problem is not None:
        return fail(problem)

    print(
        "Yosys SynchronousStreamM2sPipe is one {}-bit registered Stream stage with bubble-free replacement, stall hold and valid-only synchronous reset".format(
            args.width
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
