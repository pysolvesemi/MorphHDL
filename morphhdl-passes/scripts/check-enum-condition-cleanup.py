#!/usr/bin/env python3
"""Qualify recursive enum-condition cleanup with HDL tools and exact artifacts."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess


FSM_TOPS = [
    "EnumConditionRepro",
    "EnumConditionBinaryCoverage",
    "EnumConditionOneHotCoverage",
    "EnumConditionCustomWidth3Coverage",
    "EnumConditionCustomWidth4Coverage",
]
OBSERVATION_TOPS = [
    ("EnumObservationBinaryRepro", 2, "binary"),
    ("EnumObservationOneHotRepro", 3, "onehot"),
    ("EnumObservationCustomWidth3Repro", 3, "custom3"),
    ("EnumObservationCustomWidth4Repro", 4, "custom4"),
]
ALL_TOPS = FSM_TOPS + [top for top, _, _ in OBSERVATION_TOPS] + [
    "DistinctEnumIdentityRepro", "EnumConditionNegativeControls"
]


def run(work: Path, command: list[str], label: str) -> str:
    result = subprocess.run(command, cwd=work, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=300)
    (work / (label + ".log")).write_text(result.stdout)
    assert result.returncode == 0, f"{label}: {' '.join(command)}\n{result.stdout}"
    return result.stdout


def carrier_assignments(source: str) -> list[str]:
    return re.findall(r"(?m)^\s*assign\s+(?:when_[A-Za-z0-9_$]*|_zz_when[A-Za-z0-9_$]*)\s*=.*;$", source)


def structural_check(root: Path, expected_head: str) -> dict:
    expected_files = {top + ".v" for top in ALL_TOPS}
    report: dict[str, object] = {}
    for mode in ("enabled", "disabled", "repeat"):
        files = {path.name for path in (root / mode).glob("*.v")}
        assert files == expected_files, (mode, files ^ expected_files)
    for top in ALL_TOPS:
        enabled = (root / "enabled" / (top + ".v")).read_text()
        disabled = (root / "disabled" / (top + ".v")).read_text()
        repeat = (root / "repeat" / (top + ".v")).read_text()
        assert enabled == repeat, f"nondeterministic fixed-source artifact: {top}"
        for source in (enabled, disabled):
            assert f"git head : {expected_head}" in source, (top, expected_head)
            assert f"Git hash  : {expected_head}" in source, (top, expected_head)
            assert "parameter integer WIDTH = 8" in source, top
        report[top] = {
            "enabled_bytes": len(enabled.encode()),
            "disabled_bytes": len(disabled.encode()),
            "enabled_condition_carriers": len(carrier_assignments(enabled)),
            "disabled_condition_carriers": len(carrier_assignments(disabled)),
        }

    exact_on = (root / "enabled" / "EnumConditionRepro.v").read_text()
    exact_off = (root / "disabled" / "EnumConditionRepro.v").read_text()
    assert not carrier_assignments(exact_on), exact_on
    assert len(carrier_assignments(exact_off)) == 2, exact_off
    assert exact_on.count("if((fsm_stateReg == FSM_ACTIVE))") == 2, exact_on
    assert "reg        [0:0]    fsm_stateReg;" in exact_on
    assert "fsm_stateReg <= FSM_IDLE;" in exact_on

    for top in FSM_TOPS[1:]:
        enabled = (root / "enabled" / (top + ".v")).read_text()
        disabled = (root / "disabled" / (top + ".v")).read_text()
        assert not carrier_assignments(enabled), top
        assert carrier_assignments(disabled), top
        assert "fsm_stateReg <= FSM_IDLE;" in enabled, top

    onehot = (root / "enabled" / "EnumObservationOneHotRepro.v").read_text()
    assert "assign io_equalIdle = (io_value[STATE_IDLE_OH_ID]);" in onehot
    assert "assign io_notActive = (! io_value[STATE_ACTIVE_OH_ID]);" in onehot
    assert "assign io_equalPeer = ((io_value & io_peer) != 3'b000);" in onehot
    negative = (root / "enabled" / "EnumConditionNegativeControls.v").read_text()
    assert "when_user_generated_l900" in negative
    assert "(* keep , syn_keep *)" in negative
    assert "_zz_io_protectedResult" in negative and "_zz_io_noMergeResult" in negative
    assert "morphhdl_" not in negative
    diagnostics = (root / "native-diagnostics.tsv").read_text().splitlines()
    assert diagnostics[0] == "fixture\tindex\torigin\tsourceIntent\tsourceClass\tbeforeReason\tsurvived"
    exact = [line.split("\t") for line in diagnostics[1:] if line.startswith("exact\t")]
    retained = [line.split("\t") for line in diagnostics[1:] if line.startswith("negative\t")]
    assert any(row[2] == "Generated" and row[5] == "ELIGIBLE" and row[6] == "false"
               for row in exact if row[1] != "remaining-eligible"), exact
    assert any(row[2] == "Unnamed" and row[5] == "ELIGIBLE" and row[6] == "false"
               for row in exact if row[1] != "remaining-eligible"), exact
    assert any("PRESERVATION" in row[5] and row[6] == "true"
               for row in retained if row[1] != "remaining-eligible"), retained
    assert [row[2] for row in exact if row[1] == "remaining-eligible"] == ["0"]
    assert [row[2] for row in retained if row[1] == "remaining-eligible"] == ["0"]
    report["native_diagnostics"] = {
        "exact_candidates": len(exact) - 1,
        "negative_candidates": len(retained) - 1,
        "remaining_eligible": 0,
    }
    return report


def renamed(source: str, old: str, new: str) -> str:
    result, count = re.subn(r"(?m)^module\s+" + re.escape(old) + r"\b",
                            "module " + new, source)
    assert count == 1, old
    return result


def fsm_bench(width: int) -> str:
    return f"""module EnumConditionBench;
localparam integer WIDTH={width};
reg clk,reset,enable;
reg [WIDTH-1:0] data;
wire gate_active,gold_active;
wire [WIDTH-1:0] gate_result,gold_result;
reg oracle_active;
reg [WIDTH-1:0] oracle_result;
integer n;
EnumConditionReproGate #(.WIDTH(WIDTH)) gate(.io_enable(enable),.io_data(data),
  .io_active(gate_active),.io_result(gate_result),.clk(clk),.reset(reset));
EnumConditionReproGold #(.WIDTH(WIDTH)) gold(.io_enable(enable),.io_data(data),
  .io_active(gold_active),.io_result(gold_result),.clk(clk),.reset(reset));
task cycle;
begin
  #1; clk=1; #1;
  if(reset) begin oracle_active=0; oracle_result={{WIDTH{{1'b0}}}}; end
  else if(enable) begin
    if(oracle_active) oracle_result=data;
    oracle_active=!oracle_active;
  end
  if(gate_active !== oracle_active || gate_result !== oracle_result)
    $fatal(1,"independent two-state FSM oracle");
  if(gate_active !== gold_active || gate_result !== gold_result)
    $fatal(1,"enabled/disabled FSM mismatch");
  clk=0; #1;
end endtask
initial begin
  clk=0; reset=0; enable=0; data=0; oracle_active=0; oracle_result=0;
  #1; reset=1; cycle; reset=0;
  for(n=0;n<160;n=n+1) begin
    enable=(n%5)!=2; data=n*17+3; cycle;
  end
  enable=0; data={{WIDTH{{1'b1}}}}; cycle; cycle;
  enable=1; cycle; data=0; cycle; data={{WIDTH{{1'b1}}}}; cycle;
  reset=1; cycle; reset=0; cycle;
  $display("ENUM_FSM_PASS WIDTH=%0d", WIDTH); $finish;
end
endmodule
"""


def fsm_oracle() -> str:
    return """module EnumConditionRepro #(
  parameter integer WIDTH = 8
) (
  input wire io_enable,
  input wire [WIDTH-1:0] io_data,
  output wire io_active,
  output wire [WIDTH-1:0] io_result,
  input wire clk,
  input wire reset
);
reg active;
reg [WIDTH-1:0] held;
assign io_active = active;
assign io_result = held;
always @(posedge clk or posedge reset) begin
  if(reset) begin
    active <= 1'b0;
    held <= {WIDTH{1'b0}};
  end else if(io_enable) begin
    if(active) held <= io_data;
    active <= !active;
  end
end
endmodule
"""


def observation_oracle(kind: str) -> tuple[str, list[str]]:
    if kind == "onehot":
        return ("value[0]", ["!value[1]", "((value & peer) != 0)",
                "((value[1] && gate_in) || !value[0])"])
    codes = {
        "binary": ("2'd0", "2'd1"),
        "custom3": ("3'd1", "3'd5"),
        "custom4": ("4'd0", "4'd2"),
    }[kind]
    return (f"(value == {codes[0]})", [f"(value != {codes[1]})",
            "(value == peer)", f"(((value == {codes[1]}) && gate_in) || (value != {codes[0]}))"])


def observation_bench(top: str, bits: int, kind: str) -> str:
    eq_idle, rest = observation_oracle(kind)
    return f"""module EnumObservationBench;
localparam integer WIDTH=8;
reg [{bits-1}:0] value,peer;
reg gate_in;
reg [WIDTH-1:0] witness;
wire ge,gn,gp,gx,oe,on,op,ox;
wire [WIDTH-1:0] gw,ow;
integer n;
{top}Gate #(.WIDTH(WIDTH)) gate(.io_value(value),.io_peer(peer),.io_gate(gate_in),
  .io_witnessIn(witness),.io_equalIdle(ge),.io_notActive(gn),.io_equalPeer(gp),
  .io_nested(gx),.io_witnessOut(gw));
{top}Gold #(.WIDTH(WIDTH)) gold(.io_value(value),.io_peer(peer),.io_gate(gate_in),
  .io_witnessIn(witness),.io_equalIdle(oe),.io_notActive(on),.io_equalPeer(op),
  .io_nested(ox),.io_witnessOut(ow));
task check;
begin
  #1;
  if(ge !== ({eq_idle}) || gn !== ({rest[0]}) || gp !== ({rest[1]}) || gx !== ({rest[2]}))
    $fatal(1,"independent encoded-observation oracle");
  if({{ge,gn,gp,gx,gw}} !== {{oe,on,op,ox,ow}})
    $fatal(1,"enabled/disabled four-state mismatch");
end endtask
initial begin
  value=0; peer=0; gate_in=0; witness=0; check;
  for(n=0;n<(1<<{bits});n=n+1) begin value=n; peer=(n*3); gate_in=n[0]; witness=n; check; end
  value={bits}'bx; peer=0; gate_in=0; check;
  value={bits}'bz; peer={bits}'bx; gate_in=1; check;
  value={bits}'b1x; peer={bits}'bz; gate_in=1'bx; check;
  value={bits}'b0z; peer={bits}'b1x; gate_in=1'bz; check;
  $display("ENUM_FOUR_STATE_PASS {top}"); $finish;
end
endmodule
"""


def simulate_fsm(root: Path, width: int) -> None:
    work = root / "qualification" / f"fsm-{width}"
    work.mkdir(parents=True, exist_ok=True)
    top = "EnumConditionRepro"
    for mode, suffix in (("enabled", "Gate"), ("disabled", "Gold")):
        source = (root / mode / (top + ".v")).read_text()
        (work / (mode + ".v")).write_text(renamed(source, top, top + suffix))
    (work / "bench.v").write_text(fsm_bench(width))
    run(work, ["iverilog", "-g2012", "-s", "EnumConditionBench", "-o", "bench.vvp",
               "enabled.v", "disabled.v", "bench.v"], "compile")
    output = run(work, ["vvp", "bench.vvp"], "simulate")
    assert "ENUM_FSM_PASS" in output


def simulate_observation(root: Path, top: str, bits: int, kind: str) -> None:
    work = root / "qualification" / top
    work.mkdir(parents=True, exist_ok=True)
    for mode, suffix in (("enabled", "Gate"), ("disabled", "Gold")):
        source = (root / mode / (top + ".v")).read_text()
        (work / (mode + ".v")).write_text(renamed(source, top, top + suffix))
    (work / "bench.v").write_text(observation_bench(top, bits, kind))
    run(work, ["iverilog", "-g2012", "-s", "EnumObservationBench", "-o", "bench.vvp",
               "enabled.v", "disabled.v", "bench.v"], "compile")
    output = run(work, ["vvp", "bench.vvp"], "simulate")
    assert "ENUM_FOUR_STATE_PASS" in output


def formal_and_synthesis(root: Path, top: str, width: int) -> dict:
    work = root / "qualification" / f"formal-{top}-{width}"
    work.mkdir(parents=True, exist_ok=True)
    for mode in ("enabled", "disabled"):
        shutil.copyfile(root / mode / (top + ".v"), work / (mode + ".v"))
        run(work, ["iverilog", "-g2012", "-s", top, "-tnull", f"-P{top}.WIDTH={width}",
                   mode + ".v"], mode + "-strict-compile")
    def prove(gold_file: str, gate_file: str, label: str) -> None:
        lines: list[str] = []
        for source, name in ((gold_file, "gold"), (gate_file, "gate")):
            lines += [f"read_verilog -formal -D SYNTHESIS {source}",
                  f"chparam -set WIDTH {width} {top}", f"hierarchy -check -top {top}",
                  "proc", "flatten", "memory_map", "opt_clean", "async2sync", "opt_clean",
                  "rename -hide", f"rename {top} {name}", f"design -stash {name}"]
        lines += ["design -reset", "design -copy-from gold -as gold gold",
                  "design -copy-from gate -as gate gate", "equiv_make gold gate equiv",
                  "hierarchy -check -top equiv", "equiv_simple -undef -seq 8",
                  "equiv_induct -undef -seq 8", "equiv_status -assert"]
        script = work / (label + ".ys")
        script.write_text("\n".join(lines) + "\n")
        run(work, ["yosys", "-Q", "-s", script.name], label)

    prove("disabled.v", "enabled.v", "mode-equivalence")
    oracle_proved = False
    if top == "EnumConditionRepro":
        (work / "oracle.v").write_text(fsm_oracle())
        prove("oracle.v", "enabled.v", "enabled-oracle-equivalence")
        prove("oracle.v", "disabled.v", "disabled-oracle-equivalence")
        oracle_proved = True
    synthesis = {}
    for mode in ("enabled", "disabled"):
        script = (f"read_verilog -D SYNTHESIS {mode}.v; chparam -set WIDTH {width} {top}; "
                  f"hierarchy -check -top {top}; synth -top {top}; check -assert; stat")
        output = run(work, ["yosys", "-Q", "-p", script], mode + "-synthesis")
        cells = re.findall(r"Number of cells:\s+(\d+)", output)
        assert cells, output
        synthesis[mode] = int(cells[-1])
    return {"cells": synthesis, "mode_equivalence": "passed",
            "independent_oracle": "passed" if oracle_proved else "not-applicable"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", type=Path)
    parser.add_argument("--head", required=True)
    args = parser.parse_args()
    root = args.artifacts.resolve()
    for tool in ("iverilog", "vvp", "yosys"):
        assert shutil.which(tool), f"missing required HDL tool: {tool}"
    result: dict[str, object] = {"head": args.head,
        "structure": structural_check(root, args.head), "fsm": [], "four_state": [], "formal": []}
    for width in (1, 8, 32):
        simulate_fsm(root, width)
        result["fsm"].append({"width": width, "oracle": "passed", "mode_equality": "passed"})
        result["formal"].append({"top": "EnumConditionRepro", "width": width,
            "synthesis_cells": formal_and_synthesis(root, "EnumConditionRepro", width)})
    for top, bits, kind in OBSERVATION_TOPS:
        simulate_observation(root, top, bits, kind)
        result["four_state"].append({"top": top, "invalid_x_z": "passed"})
        result["formal"].append({"top": top, "width": 8,
            "synthesis_cells": formal_and_synthesis(root, top, 8)})
    for top in FSM_TOPS[1:] + ["DistinctEnumIdentityRepro"]:
        result["formal"].append({"top": top, "width": 8,
            "synthesis_cells": formal_and_synthesis(root, top, 8)})
    (root / "qualification.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(f"ENUM_CONDITION_QUALIFICATION_PASS formal={len(result['formal'])} "
          f"four_state={len(result['four_state'])} widths=3")


if __name__ == "__main__":
    main()
