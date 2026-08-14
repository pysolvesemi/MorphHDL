#!/usr/bin/env python3

import argparse
import json
import pathlib
import sys


def fail(message):
    print("Yosys single-port-memory contract: " + message, file=sys.stderr)
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


def address_width(depth):
    return max(1, (depth - 1).bit_length())


def all_zero(bits):
    return bool(bits) and all(bit in ("0", 0) for bit in bits)


def one_signal_replicated(bits, width):
    if len(bits) != width or not bits:
        return None
    signal = bits[0]
    if signal in ("0", "1", "x", "z", 0, 1):
        return None
    if any(bit != signal for bit in bits):
        return None
    return signal


def inputs_match(cell, left, right):
    connections = cell.get("connections", {})
    actual = (connections.get("A", []), connections.get("B", []))
    return actual == (left, right) or actual == (right, left)


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Check SinglePortMemory is one guarded positive-edge synchronous "
            "read-first whole-word memory"
        )
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument("--width", required=True, type=int)
    parser.add_argument("--depth", required=True, type=int)
    args = parser.parse_args()

    if args.width < 1 or args.depth < 1:
        return fail("expected width and depth must be positive")
    expected_address_width = address_width(args.depth)
    full_address_domain = args.depth == (1 << expected_address_width)

    with args.netlist.open("r", encoding="utf-8") as handle:
        modules = json.load(handle).get("modules", {})
    top = modules.get("SinglePortMemory")
    if top is None:
        return fail("missing top module SinglePortMemory")

    ports = top.get("ports", {})
    expected_ports = {
        "address": ("input", expected_address_width),
        "clk": ("input", 1),
        "read_data": ("output", args.width),
        "write_data": ("input", args.width),
        "write_enable": ("input", 1),
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

    memories = [
        (name, cell)
        for name, cell in cells.items()
        if cell.get("type") == "$mem_v2"
    ]
    if len(memories) != 1:
        return fail(
            "found {} $mem_v2 cells, expected exactly one; cells are {}".format(
                len(memories),
                {name: cell.get("type") for name, cell in sorted(cells.items())},
            )
        )
    memory_name, memory = memories[0]
    parameters = memory.get("parameters", {})
    for parameter, expected in (
        ("WIDTH", args.width),
        ("SIZE", args.depth),
        ("OFFSET", 0),
        ("ABITS", expected_address_width),
        ("RD_PORTS", 1),
        ("WR_PORTS", 1),
        ("RD_CLK_ENABLE", 1 if full_address_domain else 0),
        ("RD_CLK_POLARITY", 1 if full_address_domain else 0),
        ("RD_CE_OVER_SRST", 0),
        ("WR_CLK_ENABLE", 1),
        ("WR_CLK_POLARITY", 1),
        ("RD_TRANSPARENCY_MASK", 0),
        ("RD_COLLISION_X_MASK", 0),
        ("RD_WIDE_CONTINUATION", 0),
        ("WR_PRIORITY_MASK", 0),
        ("WR_WIDE_CONTINUATION", 0),
    ):
        problem = require_parameter(memory_name, parameters, parameter, expected)
        if problem is not None:
            return fail(problem)

    init = parameters.get("INIT")
    expected_init_width = args.width * args.depth
    if (
        not isinstance(init, str)
        or len(init) != expected_init_width
        or set(init.lower()) != {"x"}
    ):
        return fail(
            "{} INIT metadata is {!r}, expected exactly {} uninitialized bits".format(
                memory_name, init, expected_init_width
            )
        )

    for parameter in ("RD_ARST_VALUE", "RD_INIT_VALUE", "RD_SRST_VALUE"):
        value = parameters.get(parameter)
        if not isinstance(value, str) or len(value) != args.width or set(value.lower()) != {"x"}:
            return fail(
                "{} parameter {} is {!r}, expected {} uninitialized bits".format(
                    memory_name, parameter, value, args.width
                )
            )

    connections = memory.get("connections", {})
    for connection in (
        "RD_ADDR",
        "RD_ARST",
        "RD_CLK",
        "RD_DATA",
        "RD_EN",
        "RD_SRST",
        "WR_ADDR",
        "WR_CLK",
        "WR_DATA",
        "WR_EN",
    ):
        if connection not in connections:
            return fail("{} is missing connection {}".format(memory_name, connection))
    for connection in ("RD_ARST", "RD_SRST"):
        if connections[connection] not in (["0"], [0]):
            return fail(
                "{} read reset {} must be exactly one permanently inactive bit".format(
                    memory_name, connection
                )
            )
    if connections["RD_ADDR"] != ports["address"]["bits"]:
        return fail("{} read address is not connected directly to address".format(memory_name))
    if connections["WR_ADDR"] != ports["address"]["bits"]:
        return fail("{} write address is not connected directly to address".format(memory_name))
    if connections["WR_CLK"] != ports["clk"]["bits"]:
        return fail("{} write clock is not connected directly to clk".format(memory_name))
    if connections["WR_DATA"] != ports["write_data"]["bits"]:
        return fail("{} write data is not connected bit-for-bit".format(memory_name))
    if connections["RD_EN"] not in (["1"], [1]):
        return fail("{} read enable must be exactly one active bit".format(memory_name))
    if len(connections["RD_DATA"]) != args.width:
        return fail("{} read data width is not {}".format(memory_name, args.width))

    write_guard = one_signal_replicated(connections["WR_EN"], args.width)
    if write_guard is None:
        return fail("{} write enable is not one active-high whole-word guard".format(memory_name))

    comparisons = []
    for name, cell in cells.items():
        if cell.get("type") != "$lt":
            continue
        comparison_connections = cell.get("connections", {})
        if comparison_connections.get("A") != ports["address"]["bits"]:
            continue
        constant = comparison_connections.get("B", [])
        if len(constant) != 32 or any(
            not isinstance(bit, str) or bit not in {"0", "1"} for bit in constant
        ):
            continue
        if sum((1 << index) for index, bit in enumerate(constant) if bit == "1") != args.depth:
            continue
        comparisons.append((name, cell))
    if len(comparisons) != 1:
        return fail("found {} exact address < DEPTH guards, expected one".format(len(comparisons)))
    comparison_name, comparison = comparisons[0]
    in_range = comparison.get("connections", {}).get("Y", [])
    if len(in_range) != 1:
        return fail("{} result is not one in-range bit".format(comparison_name))
    for parameter, expected in (
        ("A_SIGNED", 0),
        ("A_WIDTH", expected_address_width),
        ("B_SIGNED", 0),
        ("B_WIDTH", 32),
        ("Y_WIDTH", 1),
    ):
        problem = require_parameter(
            comparison_name, comparison.get("parameters", {}), parameter, expected
        )
        if problem is not None:
            return fail(problem)

    registers = [
        (name, cell)
        for name, cell in cells.items()
        if "dff" in cell.get("type", "").lower()
    ]
    if full_address_domain:
        if registers:
            return fail(
                "full-domain synchronous read has redundant storage beside memory: {}".format(
                    {name: cell.get("type") for name, cell in registers}
                )
            )
        if connections["RD_CLK"] != ports["clk"]["bits"]:
            return fail("{} synchronous read clock is not connected directly to clk".format(memory_name))
        if connections["RD_DATA"] != ports["read_data"]["bits"]:
            return fail("{} synchronous read data does not solely drive read_data".format(memory_name))
    else:
        if len(connections["RD_CLK"]) != 1 or connections["RD_CLK"][0] not in ("x", "X"):
            return fail(
                "{} asynchronous read clock must be exactly one inactive x bit".format(
                    memory_name
                )
            )
        if len(registers) != 1 or registers[0][1].get("type") != "$dff":
            return fail(
                "storage beside the memory is {}, expected exactly one $dff".format(
                    {name: cell.get("type") for name, cell in registers}
                )
            )
        register_name, register = registers[0]
        register_connections = register.get("connections", {})
        if register_connections.get("CLK") != ports["clk"]["bits"]:
            return fail("{} clock is not connected directly to clk".format(register_name))
        if register_connections.get("Q") != ports["read_data"]["bits"]:
            return fail("{} does not solely drive read_data".format(register_name))
        for parameter, expected in (("WIDTH", args.width), ("CLK_POLARITY", 1)):
            problem = require_parameter(
                register_name, register.get("parameters", {}), parameter, expected
            )
            if problem is not None:
                return fail(problem)

        read_muxes = []
        for name, cell in cells.items():
            if cell.get("type") != "$mux":
                continue
            mux_connections = cell.get("connections", {})
            if mux_connections.get("Y") != register_connections.get("D"):
                continue
            false_value = mux_connections.get("A", [])
            true_value = mux_connections.get("B", [])
            if (
                all_zero(false_value)
                and true_value == connections["RD_DATA"]
                and mux_connections.get("S") == in_range
            ):
                read_muxes.append((name, cell))
        if len(read_muxes) != 1:
            return fail(
                "found {} exact in-range ? memory : zero muxes feeding read_data $dff, expected one".format(
                    len(read_muxes)
                )
            )

    write_guards = []
    for name, cell in cells.items():
        guard_connections = cell.get("connections", {})
        if guard_connections.get("Y") != [write_guard]:
            continue
        if (
            cell.get("type") in {"$and", "$logic_and"}
            and inputs_match(cell, ports["write_enable"]["bits"], in_range)
        ):
            write_guards.append((name, cell))
        elif (
            cell.get("type") == "$mux"
            and all_zero(guard_connections.get("A", []))
            and guard_connections.get("B") == ports["write_enable"]["bits"]
            and guard_connections.get("S") == in_range
        ):
            write_guards.append((name, cell))
    if len(write_guards) != 1:
        return fail(
            "found {} exact write_enable && in_range guards, expected one".format(
                len(write_guards)
            )
        )

    print(
        "Yosys SinglePortMemory is one {}x{} guarded positive-edge synchronous read-first whole-word memory".format(
            args.depth, args.width
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
