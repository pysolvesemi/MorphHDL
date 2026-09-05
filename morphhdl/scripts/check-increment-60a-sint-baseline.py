#!/usr/bin/env python3
"""Qualify the frozen Increment 60a oracle. Text inspection is test-only."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path
from sint_cast_nesting import has_nested_signed_cast, self_test

TOP = "SIntCastHeavyBaseline"
FILES = ("sint_cast_heavy_fixed.v", "sint_cast_heavy_parameterized.v")
NESTED = "sint_cast_heavy_nested.v"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def run(command: list[str], directory: Path, label: str,
        expected_failure: str | None = None) -> str:
    result = subprocess.run(command, cwd=directory, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=180)
    (directory / (label + ".log")).write_text(result.stdout, encoding="utf-8")
    require(result.returncode == 0, label + " failed:\n" + result.stdout[-12000:])
    if expected_failure is not None:
        require(expected_failure in result.stdout,
                label + " did not produce the required functional failure:\n" + result.stdout[-12000:])
    return result.stdout


def verify_hashes(root: Path, output: Path) -> None:
    self_test()
    manifest = json.loads((root / "morphhdl/contracts/increment-60a-sint-baseline.json").read_text())
    for name in FILES + (NESTED,):
        data = (output / name).read_bytes()
        actual = hashlib.sha256(data).hexdigest()
        require(actual == manifest["sha256"][name], f"immutable oracle changed: {name}: {actual}")
        text = data.decode("utf-8")
        require("$signed(" in text, f"missing signed casts in {name}")
        require(not re.search(r"\b(?:wire|reg)\s+signed\b", text), f"baseline declarations became signed: {name}")
        require("signed_memory" in text, f"missing native memory: {name}")
        require("SIntCastHeavyExternal" in text, f"missing external instance: {name}")
        require(not re.search(r"\bmodule\s+SIntCastHeavyExternal\b", text), "external module ownership changed")
        # Preserve the actual captured behavior, not the earlier mistaken claim:
        # native emission places inner SInt operations in temporary wires.
        require(not has_nested_signed_cast(text), f"captured printer unexpectedly acquired nested casts: {name}")
    probe = root / "morphhdl/examples/contracts/sint-baseline/nested_signed_cast_reproducer.v"
    require(has_nested_signed_cast(probe.read_text()), "focused semantic reproducer lost its nested cast")
    require(re.search(r"parameter\s+integer\s+WIDTH\s*=\s*8", (output / FILES[1]).read_text()) is not None,
            "parameterized oracle lost WIDTH")


def simulation(output: Path, design: str, label: str, mutant: bool = False) -> None:
    run(["iverilog", "-g2001", "-s", "BaselineTb", "-o", label + ".vvp",
         design, "external.v", "baseline_tb.v"], output, label + "-compile")
    result = run(["vvp", label + ".vvp"], output, label + "-simulate",
                 "FAIL:NEGATIVE_RESULT" if mutant else None)
    if not mutant:
        require("BASELINE_OK" in result and "FAIL:" not in result,
                label + " simulation did not pass: " + result)


def equivalence(output: Path) -> None:
    commands = []
    for name, role in zip(FILES, ("gold", "gate")):
        commands += [f"read_verilog {name} external.v", f"hierarchy -check -top {TOP}",
                     "proc", "flatten", "memory_map", "opt", f"rename {TOP} {role}",
                     f"design -stash {role}"]
    commands += ["design -copy-from gold -as gold gold", "design -copy-from gate -as gate gate",
                 "equiv_make gold gate equiv", "hierarchy -check -top equiv", "equiv_simple",
                 "equiv_induct -undef -seq 4", "equiv_status -assert"]
    (output / "equivalence.ys").write_text("\n".join(commands) + "\n")
    result = run(["yosys", "-s", "equivalence.ys"], output, "baseline-equivalence")
    require("Equivalence successfully proven" in result, "missing positive equivalence result")


def qualify(root: Path, output: Path) -> None:
    verify_hashes(root, output)
    for tool in ("iverilog", "vvp", "verilator", "yosys"):
        require(shutil.which(tool) is not None, f"required tool is missing: {tool}")
    probe = "nested_signed_cast_reproducer.v"
    shutil.copyfile(root / "morphhdl/examples/contracts/sint-baseline" / probe, output / probe)
    run(["iverilog", "-g2001", "-s", "SignedCastNestingTb", "-o", "cast-probe.vvp", probe], output, "cast-probe-compile")
    result = run(["vvp", "cast-probe.vvp"], output, "cast-probe-simulate")
    require("NESTED_SIGNED_CAST_OK" in result and "FAIL:" not in result, "nested cast semantic probe failed")
    result = run(["yosys", "-p", f"read_verilog -D SYNTHESIS {probe}; prep -top SignedCastNesting; sat -verify -prove equal_result 1 -show-inputs"], output, "cast-probe-formal")
    require("SUCCESS" in result, "nested cast semantic proof did not pass")
    (output / "external.v").write_text('''module SIntCastHeavyExternal #(parameter integer WIDTH = 8)(
  input wire [WIDTH-1:0] din, output wire [WIDTH-1:0] dout);
  assign dout = din;
endmodule
''')
    (output / "baseline_tb.v").write_text('''module BaselineTb;
  reg clk, enable, choose_left, write_enable;
  reg [1:0] address;
  reg [7:0] left, right, third, divisor, memory_write_data;
  wire [7:0] negative_out, shifted_out, sum_out, difference_out;
  wire [7:0] quotient_out, remainder_out, nested_cast_out, child_out, blackbox_out;
  wire [7:0] register_out, procedural_out, memory_out;
  wire [15:0] product_out;
  SIntCastHeavyBaseline dut(
    .clk(clk), .enable(enable), .choose_left(choose_left), .write_enable(write_enable),
    .address(address), .left(left), .right(right), .third(third), .divisor(divisor),
    .memory_write_data(memory_write_data), .negative_out(negative_out),
    .shifted_out(shifted_out), .sum_out(sum_out), .difference_out(difference_out),
    .product_out(product_out), .quotient_out(quotient_out), .remainder_out(remainder_out),
    .nested_cast_out(nested_cast_out), .child_out(child_out), .blackbox_out(blackbox_out),
    .register_out(register_out), .procedural_out(procedural_out), .memory_out(memory_out));
  initial begin
    clk=0; enable=1; choose_left=1; write_enable=1; address=0;
    left=8'hfd; right=2; third=8'hf9; divisor=2; memory_write_data=8'hfd;
    #2;
    if (negative_out !== 8'h03) begin $display("FAIL:NEGATIVE_RESULT"); $finish; end
    if ({shifted_out,sum_out,difference_out,product_out,quotient_out,remainder_out,
         nested_cast_out,child_out,blackbox_out} !==
        {8'hff,8'hff,8'hfb,16'hfffa,8'hff,8'hff,8'hf4,8'hf6,8'hfd}) begin
      $display("FAIL:ARITHMETIC"); $finish;
    end
    clk=1; #1; clk=0; write_enable=0; #1; clk=1; #1;
    if ({register_out,procedural_out,memory_out} !== {8'hfd,8'hf4,8'hfd}) begin
      $display("FAIL:SEQUENTIAL"); $finish;
    end
    $display("BASELINE_OK"); $finish;
  end
endmodule
''')
    for name, label in zip(FILES + (NESTED,), ("fixed", "parameterized", "nested")):
        simulation(output, name, label)
        run(["verilator", "--lint-only", "--language", "1364-2001", "-Wno-fatal",
             "--top-module", TOP, name, "external.v"], output, label + "-lint")
        run(["yosys", "-p", f"read_verilog {name} external.v; hierarchy -check -top {TOP}; synth -top {TOP}; check -assert"],
            output, label + "-synthesis")
    candidate = (output / FILES[1]).read_text()
    changed, count = re.subn(r"\bassign\s+negative_out\s*=\s*[^;]+;",
                            "assign negative_out = 8'h00;", candidate)
    require(count == 1, "mutation must change exactly one output assignment")
    (output / "negative-mutant.v").write_text(changed)
    simulation(output, "negative-mutant.v", "negative-mutant", mutant=True)
    equivalence(output)
    print("Increment 60a: immutable hashes, strict parsing, simulation, lint, synthesis, baseline equivalence and live negative mutation PASS")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--hashes-only", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    if args.hashes_only:
        verify_hashes(root, output)
    else:
        qualify(root, output)


if __name__ == "__main__":
    main()
