#!/usr/bin/env python3

import argparse
import json
import pathlib
import sys


def fail(message):
    print("Yosys generate contract: " + message, file=sys.stderr)
    return 1


def require_port(module, module_name, port_name, direction, width):
    port = module.get("ports", {}).get(port_name)
    if port is None:
        raise ValueError("module {} is missing port {}".format(module_name, port_name))
    if port.get("direction") != direction:
        raise ValueError(
            "module {} port {} direction is {}, expected {}".format(
                module_name, port_name, port.get("direction"), direction
            )
        )
    bits = port.get("bits", [])
    if len(bits) != width:
        raise ValueError(
            "module {} port {} width is {}, expected {}".format(
                module_name, port_name, len(bits), width
            )
        )
    return bits


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Check an elaborated homogeneous generate-for hierarchy without "
            "depending on Yosys $paramod or generated-instance names"
        )
    )
    parser.add_argument("netlist", type=pathlib.Path)
    parser.add_argument("top_module")
    parser.add_argument("--lanes", type=int, required=True)
    parser.add_argument("--data-width", type=int, required=True)
    args = parser.parse_args()

    if args.lanes <= 0:
        parser.error("--lanes must be positive")
    if args.data_width <= 0:
        parser.error("--data-width must be positive")

    with args.netlist.open("r", encoding="utf-8") as handle:
        netlist = json.load(handle)

    modules = netlist.get("modules", {})
    top = modules.get(args.top_module)
    if top is None:
        return fail("missing top module {}".format(args.top_module))

    flat_width = args.lanes * args.data_width
    try:
        top_input = require_port(top, args.top_module, "data_in", "input", flat_width)
        top_output = require_port(top, args.top_module, "data_out", "output", flat_width)
    except ValueError as error:
        return fail(str(error))
    if set(top.get("ports", {})) != {"data_in", "data_out"}:
        return fail(
            "top ports are {}, expected data_in and data_out".format(
                sorted(top.get("ports", {}))
            )
        )

    cells = top.get("cells", {})
    if len(cells) != args.lanes:
        return fail(
            "top has {} cells, expected one generated cell per lane ({})".format(
                len(cells), args.lanes
            )
        )

    observed_lanes = set()
    for cell_name, cell in sorted(cells.items()):
        child_type = cell.get("type")
        if not isinstance(child_type, str) or not child_type:
            return fail("cell {} has no module type".format(cell_name))
        if child_type == args.top_module:
            return fail("cell {} recursively targets the top module".format(cell_name))
        child = modules.get(child_type)
        if child is None:
            return fail(
                "cell {} targets missing elaborated module {}".format(
                    cell_name, child_type
                )
            )

        directions = cell.get("port_directions", {})
        connections = cell.get("connections", {})
        expected_ports = {"data_in", "data_out"}
        if set(directions) != expected_ports or set(connections) != expected_ports:
            return fail(
                "cell {} ports are directions={} connections={}, expected {}".format(
                    cell_name,
                    sorted(directions),
                    sorted(connections),
                    sorted(expected_ports),
                )
            )
        if directions != {"data_in": "input", "data_out": "output"}:
            return fail("cell {} has incorrect port directions".format(cell_name))

        try:
            require_port(child, child_type, "data_in", "input", args.data_width)
            require_port(child, child_type, "data_out", "output", args.data_width)
        except ValueError as error:
            return fail(str(error))
        if set(child.get("ports", {})) != expected_ports:
            return fail(
                "child type {} has unexpected ports {}".format(
                    child_type, sorted(child.get("ports", {}))
                )
            )

        input_bits = connections["data_in"]
        output_bits = connections["data_out"]
        if len(input_bits) != args.data_width or len(output_bits) != args.data_width:
            return fail(
                "cell {} slice widths are input={} output={}, expected {}".format(
                    cell_name,
                    len(input_bits),
                    len(output_bits),
                    args.data_width,
                )
            )

        matching_lanes = [
            lane
            for lane in range(args.lanes)
            if input_bits
            == top_input[lane * args.data_width : (lane + 1) * args.data_width]
            and output_bits
            == top_output[lane * args.data_width : (lane + 1) * args.data_width]
        ]
        if len(matching_lanes) != 1:
            return fail(
                "cell {} does not connect the matching indexed input/output slice".format(
                    cell_name
                )
            )
        lane = matching_lanes[0]
        if lane in observed_lanes:
            return fail("lane {} is driven by more than one generated cell".format(lane))
        observed_lanes.add(lane)

    expected_lanes = set(range(args.lanes))
    if observed_lanes != expected_lanes:
        return fail(
            "observed lanes are {}, expected {}".format(
                sorted(observed_lanes), sorted(expected_lanes)
            )
        )

    print(
        "Yosys module {} has {} exact generated {}-bit lane connections".format(
            args.top_module, args.lanes, args.data_width
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
