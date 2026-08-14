#!/usr/bin/env python3

import argparse
import json
import pathlib
import re
import sys


def fail(message):
    print("Yosys synchronous-stream-FIFO contract: " + message, file=sys.stderr)
    return 1


def integer(value):
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        text = value.strip()
        if text and set(text.lower()) <= {"0", "1", "x", "z"}:
            if set(text.lower()) <= {"0", "1"}:
                return int(text, 2)
            return None
        try:
            return int(text, 0)
        except ValueError:
            return None
    return None


def derived_width(value):
    return max(1, (value - 1).bit_length())


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
    actual_width = len(port.get("bits", []))
    if actual_width != width:
        return "port {} width is {}, expected {}".format(name, actual_width, width)
    return None


def replicated_signal(bits, width):
    if len(bits) != width or not bits:
        return None
    signal = bits[0]
    if signal in ("0", "1", "x", "z", 0, 1):
        return None
    if any(bit != signal for bit in bits):
        return None
    return signal


def require_source_contract(path):
    source = path.read_text(encoding="utf-8")
    required_once = (
        "localparam integer POINTER_WIDTH = clog2(DEPTH, 1);",
        "localparam integer OCCUPANCY_WIDTH = clog2(DEPTH + 1, 1);",
        "reg [WIDTH-1:0] memory [0:DEPTH-1];",
        "reg [POINTER_WIDTH-1:0] read_pointer;",
        "reg [POINTER_WIDTH-1:0] write_pointer;",
        "reg [OCCUPANCY_WIDTH-1:0] occupancy;",
        "assign push_ready = occupancy < DEPTH;",
        "assign push_fire = push_valid && push_ready;",
        "assign pop_fire = pop_valid && pop_ready;",
        "always @(posedge clk) begin : p_fifo",
        "if (reset == 1'b1) begin",
        "memory[write_pointer] <= push_data;",
        "if (write_pointer == DEPTH - 1) begin",
        "if ((pop_valid == 1'b0 && occupancy > 0) || (pop_fire == 1'b1 && occupancy > 1)) begin",
        "end else if (pop_fire == 1'b1) begin",
        "if (push_fire != pop_fire) begin",
        "occupancy <= occupancy + 1'b1;",
        "occupancy <= occupancy - 1'b1;",
    )
    for fragment in required_once:
        if source.count(fragment) != 1:
            return "source contains {} copies of {!r}, expected one".format(
                source.count(fragment), fragment
            )
    if source.count("pop_data <= memory[read_pointer];") != 1:
        return "source does not contain exactly one registered memory read"
    if source.count("if (read_pointer == DEPTH - 1) begin") != 1:
        return "source does not contain exactly one read-pointer wrap test"
    if source.count("read_pointer <= {POINTER_WIDTH{1'b0}};") != 2:
        return "source does not contain exactly reset plus one read-pointer wrap clear"
    if source.count("read_pointer <= read_pointer + 1'b1;") != 1:
        return "source does not contain exactly one read-pointer increment"
    if source.count("write_pointer <= {POINTER_WIDTH{1'b0}};") != 2:
        return "source does not contain exactly reset plus one write-pointer wrap clear"
    if source.count("write_pointer <= write_pointer + 1'b1;") != 1:
        return "source does not contain exactly one write-pointer increment"
    if source.count("occupancy <= {OCCUPANCY_WIDTH{1'b0}};") != 1:
        return "source reset does not clear occupancy exactly once"
    if source.count("pop_valid <= 1'b0;") != 2:
        return "source does not have exactly reset and empty-transition pop_valid clears"
    if len(re.findall(r"\balways\s*@", source)) != 1:
        return "source does not contain exactly one sequential process"
    if re.search(r"\b(initial|negedge)\b|always_(comb|ff|latch)|always\s*@\*", source):
        return "source contains initialization, falling-edge, or SystemVerilog process syntax"
    try:
        reset_body = source.split("if (reset == 1'b1) begin", 1)[1].split(
            "end else begin", 1
        )[0]
    except IndexError:
        return "source reset branch is not canonical"
    for assignment in (
        "read_pointer <= {POINTER_WIDTH{1'b0}};",
        "write_pointer <= {POINTER_WIDTH{1'b0}};",
        "occupancy <= {OCCUPANCY_WIDTH{1'b0}};",
        "pop_valid <= 1'b0;",
    ):
        if reset_body.count(assignment) != 1:
            return "source reset branch does not contain exactly one {!r}".format(
                assignment
            )
    if "memory" in reset_body or "pop_data" in reset_body:
        return "source reset initializes payload storage"
    return None


def find_named_bits(top, name, expected_width):
    net = top.get("netnames", {}).get(name)
    if net is None:
        return None, "missing named net {}".format(name)
    bits = net.get("bits", [])
    if len(bits) != expected_width:
        return None, "net {} width is {}, expected {}".format(
            name, len(bits), expected_width
        )
    return bits, None


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Check the strict Verilog-2001 bounded synchronous Stream FIFO "
            "source and its retained Yosys process/memory netlist"
        )
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument("--source", required=True, type=pathlib.Path)
    parser.add_argument("--width", required=True, type=int)
    parser.add_argument("--depth", required=True, type=int)
    args = parser.parse_args()

    if args.width < 1 or args.depth < 1:
        return fail("expected width and depth must be positive")
    source_problem = require_source_contract(args.source)
    if source_problem is not None:
        return fail(source_problem)

    with args.netlist.open("r", encoding="utf-8") as handle:
        modules = json.load(handle).get("modules", {})
    top = modules.get("SynchronousStreamFifo")
    if top is None:
        return fail("missing top module SynchronousStreamFifo")

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

    pointer_width = derived_width(args.depth)
    occupancy_width = derived_width(args.depth + 1)
    named_bits = {}
    for name, width in (
        ("read_pointer", pointer_width),
        ("write_pointer", pointer_width),
        ("occupancy", occupancy_width),
        ("push_fire", 1),
        ("pop_fire", 1),
    ):
        bits, problem = find_named_bits(top, name, width)
        if problem is not None:
            return fail(problem)
        named_bits[name] = bits

    cells = top.get("cells", {})
    cell_types = {name: cell.get("type", "") for name, cell in cells.items()}
    if any("latch" in cell_type.lower() for cell_type in cell_types.values()):
        return fail("unexpected latch cell")
    if any("adff" in cell_type.lower() for cell_type in cell_types.values()):
        return fail("unexpected asynchronous-reset state cell")

    combinational_drivers = {}
    combinational_driver_cells = {}
    for cell_name, cell in cells.items():
        cell_type = cell.get("type", "").lower()
        if "dff" in cell_type or cell_type == "$mem_v2":
            continue
        cell_connections = cell.get("connections", {})
        outputs = cell_connections.get("Y")
        if outputs is None:
            continue
        inputs = []
        for port_name, bits in cell_connections.items():
            if port_name != "Y":
                inputs.extend(bits)
        for bit in outputs:
            if bit not in ("0", "1", "x", "z", 0, 1):
                combinational_drivers[bit] = tuple(inputs)
                combinational_driver_cells[bit] = (cell_name, cell)

    state_source_bits = set(
        named_bits["read_pointer"]
        + named_bits["write_pointer"]
        + named_bits["occupancy"]
        + ports["pop_valid"]["bits"]
    )
    primary_source_bits = set()
    for port_name, port in ports.items():
        if port.get("direction") == "input":
            primary_source_bits.update(port.get("bits", []))
    source_bits = state_source_bits | primary_source_bits

    def fanin_sources(bits):
        found = set()
        pending = list(bits)
        visited = set()
        while pending:
            bit = pending.pop()
            if bit in visited or bit in ("0", "1", "x", "z", 0, 1):
                continue
            visited.add(bit)
            if bit in source_bits:
                found.add(bit)
                continue
            pending.extend(combinational_drivers.get(bit, ()))
        return found

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
        ("ABITS", pointer_width),
        ("RD_PORTS", 1),
        ("WR_PORTS", 1),
        ("RD_CLK_ENABLE", 1),
        ("RD_CLK_POLARITY", 1),
        ("WR_CLK_ENABLE", 1),
        ("WR_CLK_POLARITY", 1),
        ("RD_TRANSPARENCY_MASK", 0),
        ("RD_COLLISION_X_MASK", 1 if args.depth == 1 else 0),
        ("WR_PRIORITY_MASK", 0),
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
    if connections["RD_ADDR"] != named_bits["read_pointer"]:
        return fail("memory read address is not the wrapped read_pointer")
    if connections["WR_ADDR"] != named_bits["write_pointer"]:
        return fail("memory write address is not the wrapped write_pointer")
    if connections["WR_CLK"] != ports["clk"]["bits"]:
        return fail("memory write clock is not connected directly to clk")
    if connections["RD_CLK"] != ports["clk"]["bits"]:
        return fail("memory read clock is not connected directly to clk")
    if connections["RD_DATA"] != ports["pop_data"]["bits"]:
        return fail("registered memory read does not solely drive pop_data")
    if connections["WR_DATA"] != ports["push_data"]["bits"]:
        return fail("memory write data is not connected bit-for-bit to push_data")
    write_enable = replicated_signal(connections["WR_EN"], args.width)
    if write_enable is None:
        return fail("memory write enable is not one nonconstant whole-word signal")
    write_enable_driver = combinational_driver_cells.get(write_enable)
    if write_enable_driver is None:
        return fail("memory write enable has no retained combinational guard")
    write_enable_name, write_enable_cell = write_enable_driver
    write_enable_connections = write_enable_cell.get("connections", {})
    if (
        write_enable_cell.get("type") != "$mux"
        or write_enable_connections.get("Y") != [write_enable]
        or write_enable_connections.get("A") != named_bits["push_fire"]
        or write_enable_connections.get("B") not in (["0"], [0])
        or write_enable_connections.get("S") != ports["reset"]["bits"]
    ):
        return fail(
            "{} is not the exact active-high-reset guard for push_fire".format(
                write_enable_name
            )
        )
    problem = require_parameter(
        write_enable_name, write_enable_cell.get("parameters", {}), "WIDTH", 1
    )
    if problem is not None:
        return fail(problem)
    write_enable_sources = fanin_sources([write_enable])
    expected_write_sources = (
        set(ports["reset"]["bits"])
        | set(ports["push_valid"]["bits"])
        | set(named_bits["occupancy"])
    )
    if write_enable_sources != expected_write_sources:
        return fail(
            "memory write enable sources are {}, expected reset, push_valid and "
            "every occupancy bit {}".format(
                sorted(write_enable_sources, key=str),
                sorted(expected_write_sources, key=str),
            )
        )
    if len(connections["RD_EN"]) != 1 or connections["RD_EN"][0] in (
        "0",
        "1",
        "x",
        "z",
        0,
        1,
    ):
        return fail("registered memory read lacks one conditional fetch enable")
    read_enable = connections["RD_EN"][0]
    read_enable_driver = combinational_driver_cells.get(read_enable)
    if read_enable_driver is None:
        return fail("registered memory read enable has no combinational driver")
    read_enable_name, read_enable_cell = read_enable_driver
    read_enable_connections = read_enable_cell.get("connections", {})
    if (
        read_enable_cell.get("type") != "$reduce_and"
        or read_enable_connections.get("Y") != [read_enable]
        or len(read_enable_connections.get("A", [])) != 2
    ):
        return fail(
            "{} is not the exact two-input fetch/reset conjunction".format(
                read_enable_name
            )
        )
    problem = require_parameter(
        read_enable_name, read_enable_cell.get("parameters", {}), "A_WIDTH", 2
    )
    if problem is not None:
        return fail(problem)
    problem = require_parameter(
        read_enable_name, read_enable_cell.get("parameters", {}), "Y_WIDTH", 1
    )
    if problem is not None:
        return fail(problem)
    reset_inverters = []
    for inverter_name, inverter in cells.items():
        inverter_connections = inverter.get("connections", {})
        if (
            inverter.get("type") == "$not"
            and inverter_connections.get("A") == ports["reset"]["bits"]
            and len(inverter_connections.get("Y", [])) == 1
        ):
            reset_inverters.append(
                (inverter_name, inverter_connections["Y"][0])
            )
    if len(reset_inverters) != 1:
        return fail(
            "found {} exact reset inverters for the read enable, expected one".format(
                len(reset_inverters)
            )
        )
    reset_inverter_name, inactive_reset = reset_inverters[0]
    if read_enable_connections["A"].count(inactive_reset) != 1:
        return fail(
            "{} does not gate the fetch with {}".format(
                read_enable_name, reset_inverter_name
            )
        )
    read_enable_sources = fanin_sources(connections["RD_EN"])
    expected_read_sources = (
        set(ports["reset"]["bits"])
        | set(ports["pop_valid"]["bits"])
        | set(ports["pop_ready"]["bits"])
        | set(named_bits["occupancy"])
    )
    if read_enable_sources != expected_read_sources:
        return fail(
            "registered read enable sources are {}, expected reset, pop_valid, "
            "pop_ready and every occupancy bit {}".format(
                sorted(read_enable_sources, key=str),
                sorted(expected_read_sources, key=str),
            )
        )
    for connection in ("RD_ARST", "RD_SRST"):
        if connections[connection] not in (["0"], [0]):
            return fail("memory {} is not one inactive reset bit".format(connection))

    comparisons = []
    for name, cell in cells.items():
        if cell.get("type") != "$lt":
            continue
        cell_connections = cell.get("connections", {})
        if cell_connections.get("A") != named_bits["occupancy"]:
            continue
        constant = cell_connections.get("B", [])
        if len(constant) != 32 or any(bit not in ("0", "1") for bit in constant):
            continue
        value = sum(1 << index for index, bit in enumerate(constant) if bit == "1")
        if value == args.depth and cell_connections.get("Y") == ports["push_ready"]["bits"]:
            comparisons.append((name, cell))
    if len(comparisons) != 1:
        return fail("found {} exact occupancy < DEPTH ready comparators, expected one".format(
            len(comparisons)
        ))
    comparison_name, comparison = comparisons[0]
    for parameter, expected in (
        ("A_SIGNED", 0),
        ("A_WIDTH", occupancy_width),
        ("B_SIGNED", 0),
        ("B_WIDTH", 32),
        ("Y_WIDTH", 1),
    ):
        problem = require_parameter(
            comparison_name, comparison.get("parameters", {}), parameter, expected
        )
        if problem is not None:
            return fail(problem)

    def exact_logic_and(output, left, right, purpose):
        matches = []
        for name, cell in cells.items():
            if cell.get("type") not in ("$and", "$logic_and"):
                continue
            cell_connections = cell.get("connections", {})
            if cell_connections.get("Y") != output:
                continue
            if (
                cell_connections.get("A") == left
                and cell_connections.get("B") == right
            ) or (
                cell_connections.get("A") == right
                and cell_connections.get("B") == left
            ):
                matches.append((name, cell))
        if len(matches) != 1:
            return "found {} exact {} logic gates, expected one".format(
                len(matches), purpose
            )
        return None

    problem = exact_logic_and(
        named_bits["push_fire"],
        ports["push_valid"]["bits"],
        ports["push_ready"]["bits"],
        "push_valid && push_ready",
    )
    if problem is not None:
        return fail(problem)
    problem = exact_logic_and(
        named_bits["pop_fire"],
        ports["pop_valid"]["bits"],
        ports["pop_ready"]["bits"],
        "pop_valid && pop_ready",
    )
    if problem is not None:
        return fail(problem)

    clock_bits = ports["clk"]["bits"]
    state_cells = [
        (name, cell)
        for name, cell in cells.items()
        if "dff" in cell.get("type", "").lower()
    ]
    if not state_cells:
        return fail("no retained positive-edge FIFO state")
    for name, cell in state_cells:
        cell_type = cell.get("type", "").lower()
        if "adff" in cell_type:
            return fail("{} is asynchronous state".format(name))
        cell_connections = cell.get("connections", {})
        if cell_connections.get("CLK") != clock_bits:
            return fail("{} is not clocked directly from clk".format(name))
        polarity = cell.get("parameters", {}).get("CLK_POLARITY")
        if polarity is not None and integer(polarity) != 1:
            return fail("{} is not positive-edge state".format(name))

    for state_name, state_bits in (
        ("read_pointer", named_bits["read_pointer"]),
        ("write_pointer", named_bits["write_pointer"]),
        ("occupancy", named_bits["occupancy"]),
        ("pop_valid", ports["pop_valid"]["bits"]),
    ):
        drivers = [
            name
            for name, cell in state_cells
            if cell.get("connections", {}).get("Q") == state_bits
        ]
        if len(drivers) != 1:
            return fail(
                "{} is driven by {} positive-edge state cells, expected one".format(
                    state_name, len(drivers)
                )
            )
        driver = next(cell for name, cell in state_cells if name == drivers[0])
        if driver.get("type") != "$sdffe":
            return fail(
                "{} state uses {}, expected reset-priority $sdffe".format(
                    state_name, driver.get("type")
                )
            )
        driver_connections = driver.get("connections", {})
        driver_parameters = driver.get("parameters", {})
        if integer(driver_parameters.get("EN_POLARITY")) != 1:
            return fail("{} state enable is not active high".format(state_name))
        if driver_connections.get("SRST") != ports["reset"]["bits"]:
            return fail("{} state reset is not connected directly to reset".format(state_name))
        if integer(driver_parameters.get("SRST_POLARITY")) != 1:
            return fail("{} state reset is not active high".format(state_name))
        reset_value = driver_parameters.get("SRST_VALUE")
        if (
            not isinstance(reset_value, str)
            or len(reset_value) != len(state_bits)
            or set(reset_value) != {"0"}
        ):
            return fail(
                "{} state reset value is {!r}, expected {} zero bits".format(
                    state_name, reset_value, len(state_bits)
                )
            )

    print(
        "Yosys SynchronousStreamFifo is one uninitialized {}x{} RAM with one "
        "positive-edge ready/valid state machine and exact capacity {}".format(
            args.depth, args.width, args.depth
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
