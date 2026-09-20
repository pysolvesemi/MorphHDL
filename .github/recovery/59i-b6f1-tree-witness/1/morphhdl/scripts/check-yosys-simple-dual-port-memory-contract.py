#!/usr/bin/env python3

import argparse
import json
import pathlib
import sys


def fail(message):
    print("Yosys simple-dual-port-memory contract: " + message, file=sys.stderr)
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


def exact_comparison(cells, address_bits, depth, width):
    matches = []
    for name, cell in cells.items():
        if cell.get("type") != "$lt":
            continue
        connections = cell.get("connections", {})
        if connections.get("A") != address_bits:
            continue
        constant = connections.get("B", [])
        if len(constant) != 32 or any(
            not isinstance(bit, str) or bit not in {"0", "1"} for bit in constant
        ):
            continue
        value = sum((1 << index) for index, bit in enumerate(constant) if bit == "1")
        if value != depth:
            continue
        matches.append((name, cell))
    if len(matches) != 1:
        return None, "found {} exact address < DEPTH guards for one address, expected one".format(
            len(matches)
        )
    name, cell = matches[0]
    result = cell.get("connections", {}).get("Y", [])
    if len(result) != 1:
        return None, "{} result is not one in-range bit".format(name)
    for parameter, expected in (
        ("A_SIGNED", 0),
        ("A_WIDTH", width),
        ("B_SIGNED", 0),
        ("B_WIDTH", 32),
        ("Y_WIDTH", 1),
    ):
        problem = require_parameter(name, cell.get("parameters", {}), parameter, expected)
        if problem is not None:
            return None, problem
    return (name, cell, result), None


def exact_active_high_guard(cells, output, enable_bits, in_range):
    matches = []
    for name, cell in cells.items():
        connections = cell.get("connections", {})
        if connections.get("Y") != [output]:
            continue
        if cell.get("type") in {"$and", "$logic_and"} and (
            (
                connections.get("A") == enable_bits
                and connections.get("B") == in_range
            )
            or (
                connections.get("A") == in_range
                and connections.get("B") == enable_bits
            )
        ):
            matches.append((name, cell))
        elif (
            cell.get("type") == "$mux"
            and connections.get("A") in (["0"], [0])
            and connections.get("B") == enable_bits
            and connections.get("S") == in_range
        ):
            matches.append((name, cell))
    if len(matches) != 1:
        return None, "found {} exact active-high enable && in-range guards, expected one".format(
            len(matches)
        )
    name, cell = matches[0]
    if cell.get("type") == "$mux":
        problem = require_parameter(name, cell.get("parameters", {}), "WIDTH", 1)
        if problem is not None:
            return None, problem
    else:
        for parameter, expected in (
            ("A_SIGNED", 0),
            ("A_WIDTH", 1),
            ("B_SIGNED", 0),
            ("B_WIDTH", 1),
            ("Y_WIDTH", 1),
        ):
            problem = require_parameter(name, cell.get("parameters", {}), parameter, expected)
            if problem is not None:
                return None, problem
    return (name, cell), None


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Check SimpleDualPortMemory is one independent-address, independently "
            "enabled, positive-edge synchronous read-first 1R1W whole-word memory"
        )
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument("--width", required=True, type=int)
    parser.add_argument("--depth", required=True, type=int)
    args = parser.parse_args()

    if args.width < 1 or args.depth < 1:
        return fail("expected width and depth must be positive")
    expected_address_width = address_width(args.depth)

    with args.netlist.open("r", encoding="utf-8") as handle:
        modules = json.load(handle).get("modules", {})
    top = modules.get("SimpleDualPortMemory")
    if top is None:
        return fail("missing top module SimpleDualPortMemory")

    ports = top.get("ports", {})
    expected_ports = {
        "clk": ("input", 1),
        "read_address": ("input", expected_address_width),
        "read_data": ("output", args.width),
        "read_enable": ("input", 1),
        "write_address": ("input", expected_address_width),
        "write_data": ("input", args.width),
        "write_enable": ("input", 1),
    }
    if set(ports) != set(expected_ports):
        return fail("ports are {}, expected {}".format(sorted(ports), sorted(expected_ports)))
    for name, (direction, width) in sorted(expected_ports.items()):
        problem = require_port(ports, name, direction, width)
        if problem is not None:
            return fail(problem)
    if ports["read_address"]["bits"] == ports["write_address"]["bits"]:
        return fail("read and write address ports collapse to the same signal")

    cells = top.get("cells", {})
    if any("latch" in cell.get("type", "").lower() for cell in cells.values()):
        return fail("unexpected latch cell")
    memories = [
        (name, cell) for name, cell in cells.items() if cell.get("type") == "$mem_v2"
    ]
    if len(memories) != 1:
        return fail("found {} $mem_v2 cells, expected exactly one".format(len(memories)))
    memory_name, memory = memories[0]
    parameters = memory.get("parameters", {})
    for parameter, expected in (
        ("WIDTH", args.width),
        ("SIZE", args.depth),
        ("OFFSET", 0),
        ("ABITS", expected_address_width),
        ("RD_PORTS", 1),
        ("WR_PORTS", 1),
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
            "{} INIT is {!r}, expected exactly {} uninitialized bits".format(
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
    required_connections = {
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
    }
    if not required_connections.issubset(connections):
        return fail(
            "{} is missing connections {}".format(
                memory_name, sorted(required_connections - set(connections))
            )
        )
    for connection in ("RD_ARST", "RD_SRST"):
        if connections[connection] not in (["0"], [0]):
            return fail("{} {} is not one inactive reset bit".format(memory_name, connection))
    if connections["RD_ADDR"] != ports["read_address"]["bits"]:
        return fail("{} RD_ADDR is not connected directly to read_address".format(memory_name))
    if connections["WR_ADDR"] != ports["write_address"]["bits"]:
        return fail("{} WR_ADDR is not connected directly to write_address".format(memory_name))
    if connections["WR_CLK"] != ports["clk"]["bits"]:
        return fail("{} write clock is not connected directly to clk".format(memory_name))
    if connections["WR_DATA"] != ports["write_data"]["bits"]:
        return fail("{} write data is not connected bit-for-bit".format(memory_name))
    if len(connections["RD_DATA"]) != args.width:
        return fail("{} read data width is not {}".format(memory_name, args.width))

    full_address_domain = args.depth == (1 << expected_address_width)

    def optional_comparison(port_name):
        address_bits = ports[port_name]["bits"]
        candidates = [
            cell
            for cell in cells.values()
            if cell.get("type") == "$lt"
            and cell.get("connections", {}).get("A") == address_bits
        ]
        if not candidates:
            return None, None
        comparison, comparison_problem = exact_comparison(
            cells, address_bits, args.depth, expected_address_width
        )
        return comparison, comparison_problem

    if full_address_domain:
        read_comparison, problem = optional_comparison("read_address")
        if problem is not None:
            return fail("read " + problem)
        write_comparison, problem = optional_comparison("write_address")
        if problem is not None:
            return fail("write " + problem)
    else:
        read_comparison, problem = exact_comparison(
            cells, ports["read_address"]["bits"], args.depth, expected_address_width
        )
        if problem is not None:
            return fail("read " + problem)
        write_comparison, problem = exact_comparison(
            cells, ports["write_address"]["bits"], args.depth, expected_address_width
        )
        if problem is not None:
            return fail("write " + problem)

    if read_comparison is not None and write_comparison is not None:
        if read_comparison[0] == write_comparison[0] or read_comparison[2] == write_comparison[2]:
            return fail("read and write range guards are not independent")

    write_guard_signal = one_signal_replicated(connections["WR_EN"], args.width)
    if write_guard_signal is None:
        return fail("{} write enable is not one active-high whole-word signal".format(memory_name))
    write_guard_name = None
    if [write_guard_signal] == ports["write_enable"]["bits"]:
        if not full_address_domain:
            return fail("surplus-domain write lacks its independent in-range guard")
    else:
        if write_comparison is None:
            return fail("write enable is neither direct nor protected by an exact retained guard")
        write_guard_cell, problem = exact_active_high_guard(
            cells,
            write_guard_signal,
            ports["write_enable"]["bits"],
            write_comparison[2],
        )
        if problem is not None:
            return fail("write " + problem)
        write_guard_name = write_guard_cell[0]

    comparison_names = {
        comparison[0]
        for comparison in (read_comparison, write_comparison)
        if comparison is not None
    }
    common_expected_cells = {memory_name} | comparison_names
    if write_guard_name is not None:
        common_expected_cells.add(write_guard_name)

    rd_clock_enable = integer(parameters.get("RD_CLK_ENABLE"))
    rd_clock_polarity = integer(parameters.get("RD_CLK_POLARITY"))
    if rd_clock_enable == 1:
        if not full_address_domain:
            return fail("synchronous memory read absorption is only legal for a full address domain")
        if rd_clock_polarity != 1:
            return fail("absorbed synchronous read is not positive-edge")
        if connections["RD_CLK"] != ports["clk"]["bits"]:
            return fail("absorbed read clock is not connected directly to clk")
        if connections["RD_DATA"] != ports["read_data"]["bits"]:
            return fail("absorbed read port does not solely drive read_data")
        if connections["RD_EN"] != ports["read_enable"]["bits"]:
            return fail("absorbed read enable is not connected directly to read_enable")
        if any("dff" in cell.get("type", "").lower() for cell in cells.values()):
            return fail("absorbed read retains unexpected external state")
        if set(cells) != common_expected_cells:
            return fail(
                "absorbed-read cells are {}, expected only {}".format(
                    {name: cell.get("type") for name, cell in sorted(cells.items())},
                    sorted(common_expected_cells),
                )
            )
    elif rd_clock_enable == 0:
        if rd_clock_polarity != 0:
            return fail("asynchronous raw read has nonzero clock polarity")
        if connections["RD_EN"] not in (["1"], [1]):
            return fail("raw asynchronous read enable is not exactly one active bit")
        if len(connections["RD_CLK"]) != 1 or connections["RD_CLK"][0] not in ("x", "X"):
            return fail("raw asynchronous read clock is not exactly one inactive x bit")

        registers = [
            (name, cell)
            for name, cell in cells.items()
            if "dff" in cell.get("type", "").lower()
        ]
        if len(registers) != 1 or registers[0][1].get("type") != "$dff":
            return fail(
                "external read state is {}, expected exactly one $dff".format(
                    {name: cell.get("type") for name, cell in registers}
                )
            )
        register_name, register = registers[0]
        register_connections = register.get("connections", {})
        if register_connections.get("CLK") != ports["clk"]["bits"]:
            return fail("{} clock is not connected directly to clk".format(register_name))
        if register_connections.get("Q") != ports["read_data"]["bits"]:
            return fail("{} does not solely drive read_data".format(register_name))
        if len(register_connections.get("D", [])) != args.width:
            return fail("{} input width is not {}".format(register_name, args.width))
        for parameter, expected in (("WIDTH", args.width), ("CLK_POLARITY", 1)):
            problem = require_parameter(
                register_name, register.get("parameters", {}), parameter, expected
            )
            if problem is not None:
                return fail(problem)

        valid_muxes = []
        surplus_muxes = []
        for name, cell in cells.items():
            if cell.get("type") != "$mux":
                continue
            mux = cell.get("connections", {})
            if (
                mux.get("A") == ports["read_data"]["bits"]
                and mux.get("B") == connections["RD_DATA"]
                and mux.get("S") == ports["read_enable"]["bits"]
            ):
                valid_muxes.append((name, cell))
            if (
                mux.get("A") == ports["read_data"]["bits"]
                and len(mux.get("B", [])) == args.width
                and all_zero(mux.get("B", []))
                and mux.get("S") == ports["read_enable"]["bits"]
            ):
                surplus_muxes.append((name, cell))
        if len(valid_muxes) != 1:
            return fail("found {} enabled memory-or-hold muxes, expected one".format(
                len(valid_muxes)
            ))
        valid_name, valid_mux = valid_muxes[0]
        problem = require_parameter(valid_name, valid_mux.get("parameters", {}), "WIDTH", args.width)
        if problem is not None:
            return fail(problem)

        expected_cells = set(common_expected_cells) | {register_name, valid_name}
        if read_comparison is None:
            if not full_address_domain:
                return fail("surplus-domain read lost its in-range comparator")
            if surplus_muxes:
                return fail("full-domain folded read retains a surplus-zero mux")
            if valid_mux.get("connections", {}).get("Y") != register_connections.get("D"):
                return fail("full-domain enabled memory-or-hold mux does not feed the output dff")
        else:
            if len(surplus_muxes) != 1:
                return fail("found {} surplus-zero enabled-hold muxes, expected one".format(
                    len(surplus_muxes)
                ))
            surplus_name, surplus_mux = surplus_muxes[0]
            problem = require_parameter(
                surplus_name, surplus_mux.get("parameters", {}), "WIDTH", args.width
            )
            if problem is not None:
                return fail(problem)
            address_muxes = []
            for name, cell in cells.items():
                if cell.get("type") != "$mux":
                    continue
                mux = cell.get("connections", {})
                if (
                    mux.get("A") == surplus_mux.get("connections", {}).get("Y")
                    and mux.get("B") == valid_mux.get("connections", {}).get("Y")
                    and mux.get("S") == read_comparison[2]
                    and mux.get("Y") == register_connections.get("D")
                ):
                    address_muxes.append((name, cell))
            if len(address_muxes) != 1:
                return fail("found {} exact range-selected read paths, expected one".format(
                    len(address_muxes)
                ))
            address_name, address_mux = address_muxes[0]
            problem = require_parameter(
                address_name, address_mux.get("parameters", {}), "WIDTH", args.width
            )
            if problem is not None:
                return fail(problem)
            expected_cells |= {surplus_name, address_name}

        if set(cells) != expected_cells:
            return fail(
                "external-read cells are {}, expected only {}".format(
                    {name: cell.get("type") for name, cell in sorted(cells.items())},
                    sorted(expected_cells),
                )
            )
    else:
        return fail(
            "{} RD_CLK_ENABLE is {!r}, expected zero or one".format(
                memory_name, parameters.get("RD_CLK_ENABLE")
            )
        )

    print(
        "Yosys SimpleDualPortMemory is one {}x{} independent-address positive-edge synchronous read-first 1R1W whole-word memory".format(
            args.depth, args.width
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
