#!/usr/bin/env python3
"""Generate strict-tool external-module stubs from an Increment 59 artifact."""

from __future__ import annotations

import argparse
import re
from pathlib import Path

MODULES = ("TypedExternalLeaf", "TypedParameterOnlyExternal")


def instance_ports(verilog: str, module: str) -> list[str]:
    pattern = re.compile(
        rf"(?ms)^\s*{re.escape(module)}\s*#\s*\(.*?\)\s+"
        rf"[A-Za-z_][A-Za-z0-9_$]*\s*\((.*?)^\s*\);"
    )
    result: list[str] = []
    for match in pattern.finditer(verilog):
        for name in re.findall(r"\.([A-Za-z_][A-Za-z0-9_$]*)\s*\(", match.group(1)):
            if name not in result:
                result.append(name)
    return result


def render_module(module: str, ports: list[str]) -> str:
    if not ports:
        raise SystemExit(f"no emitted port associations found for {module}")
    declarations = ",\n".join(f"  inout wire [WIDTH-1:0] {name}" for name in ports)
    return f'''(* blackbox *)
module {module} #(
  parameter LABEL = "",
  parameter integer WIDTH = 8,
  parameter integer DEPTH = 4,
  parameter integer DOUBLE_WIDTH = 16,
  parameter integer LATENCY = 2,
  parameter CONCRETE_ENABLE = 1'b1,
  parameter ENABLED = 1'b1
) (
{declarations}
);
endmodule
'''


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact")
    parser.add_argument("output")
    arguments = parser.parse_args()

    artifact = Path(arguments.artifact)
    output = Path(arguments.output)
    verilog = artifact.read_text(encoding="utf-8")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        "\n".join(render_module(module, instance_ports(verilog, module)) for module in MODULES),
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
