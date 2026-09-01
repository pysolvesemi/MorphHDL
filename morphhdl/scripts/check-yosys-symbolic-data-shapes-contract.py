#!/usr/bin/env python3

import argparse
import json
import pathlib
import sys


def fail(message):
    print(message, file=sys.stderr)
    return 1


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


def require_parameter(cell_name, parameters, name, expected):
    if name not in parameters:
        return "{} is missing parameter {}".format(cell_name, name)
    actual = parameter_integer(parameters.get(name))
    if actual != expected:
        return "{} parameter {} is {!r}, expected {}".format(
            cell_name, name, parameters.get(name), expected
        )
    return None


def main():
    parser = argparse.ArgumentParser(
        description="Check SymbolicDataShapes wiring and register shape in Yosys JSON"
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument("--width", type=int, required=True)
    args = parser.parse_args()
    if args.width <= 0:
        parser.error("--width must be positive")

    with args.netlist.open("r", encoding="utf-8") as handle:
        netlist = json.load(handle)
    module = netlist.get("modules", {}).get("SymbolicDataShapes")
    if module is None:
        return fail("Yosys JSON is missing SymbolicDataShapes")

    ports = module.get("ports", {})
    netnames = module.get("netnames", {})
    cells = module.get("cells", {})

    if len(cells) != 3 or any(cell.get("type") != "$dff" for cell in cells.values()):
        return fail("expected exactly three cells and all must be $dff")

    def bits(container, name):
        value = container.get(name)
        if value is None:
            raise ValueError("missing signal {}".format(name))
        result = value.get("bits", [])
        if len(result) == 0:
            raise ValueError("signal {} has no bits".format(name))
        return result

    try:
        for leaf in ("bits", "uint", "sint"):
            internal_name = "internal_payload_{}".format(leaf)
            internal_bits = bits(netnames, internal_name)
            if len(internal_bits) != args.width:
                return fail(
                    "{} width is {}, expected {}".format(
                        internal_name, len(internal_bits), args.width
                    )
                )
            if internal_bits != bits(ports, "bundle_in_{}".format(leaf)):
                return fail("{} is not driven by its Bundle input".format(internal_name))
            if internal_bits != bits(ports, "bundle_out_{}".format(leaf)):
                return fail("{} does not directly drive its Bundle output".format(internal_name))

        direct_pairs = (
            ("bits_out", "bits_in"),
            ("uint_out", "uint_in"),
            ("sint_out", "sint_in"),
            ("vec_out", "vec_in"),
            ("stream_out_valid", "stream_in_valid"),
            ("stream_in_ready", "stream_out_ready"),
            ("flow_out_valid", "flow_in_valid"),
        )
        payload_prefix_pairs = (
            ("stream_out_payload", "stream_in_payload"),
            ("flow_out_payload", "flow_in_payload"),
        )
        for vec_port in ("vec_in", "vec_out"):
            vec_bits = bits(ports, vec_port)
            if len(vec_bits) != 6 * args.width:
                return fail(
                    "{} width is {}, expected {} (two three-leaf elements)".format(
                        vec_port, len(vec_bits), 6 * args.width
                    )
                )
        for target, source in direct_pairs:
            if bits(ports, target) != bits(ports, source):
                return fail("{} is not a direct alias of {}".format(target, source))
        for target_prefix, source_prefix in payload_prefix_pairs:
            for leaf in ("bits", "uint", "sint"):
                target = "{}_{}".format(target_prefix, leaf)
                source = "{}_{}".format(source_prefix, leaf)
                if bits(ports, target) != bits(ports, source):
                    return fail("{} is not a direct alias of {}".format(target, source))

        clock_bits = bits(ports, "clk")
        for leaf in ("bits", "uint", "sint"):
            register_name = "payload_register_{}".format(leaf)
            register_bits = bits(netnames, register_name)
            if len(register_bits) != args.width:
                return fail(
                    "{} width is {}, expected {}".format(
                        register_name, len(register_bits), args.width
                    )
                )
            if register_bits != bits(ports, "register_out_{}".format(leaf)):
                return fail("{} does not directly drive its output".format(register_name))

            matching = []
            for cell_name, cell in cells.items():
                if cell.get("type") != "$dff":
                    continue
                connections = cell.get("connections", {})
                if connections.get("Q") == register_bits:
                    matching.append((cell_name, cell))
            if len(matching) != 1:
                return fail("{} does not have exactly one $dff".format(register_name))
            cell_name, cell = matching[0]
            connections = cell.get("connections", {})
            if set(connections) != {"CLK", "D", "Q"}:
                return fail(
                    "{} ports are {}, expected ['CLK', 'D', 'Q']".format(
                        cell_name, sorted(connections)
                    )
                )
            if connections.get("CLK") != clock_bits:
                return fail("{} is not clocked directly by clk".format(register_name))
            if connections.get("D") != bits(ports, "bundle_in_{}".format(leaf)):
                return fail("{} does not capture its Bundle input".format(register_name))
            for parameter_name, expected in (
                ("WIDTH", args.width),
                ("CLK_POLARITY", 1),
            ):
                problem = require_parameter(
                    cell_name,
                    cell.get("parameters", {}),
                    parameter_name,
                    expected,
                )
                if problem is not None:
                    return fail(problem)
    except ValueError as error:
        return fail(str(error))

    print(
        "Yosys SymbolicDataShapes width {} retains direct aggregate wiring and one Bundle register".format(
            args.width
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
