#!/usr/bin/env python3
"""CDC-WIRE-01 emitted-artifact qualification with independent four-state oracles.

Run CdcWireCleanupArtifactWriter first. Every external tool is mandatory: this
gate never turns missing simulation/formal infrastructure into a passing skip.
The source fixtures are generic native HDL, and structure is checked using the
declared graph rather than interpreting a generated identifier as provenance.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess


MATRICES = {
    "CdcSliceCompareRepro": ("DATA_BITS", (1, 32, 120, 2048)),
    "CdcGrayChainRepro": ("FIFO_LOG_DEPTH", (2, 3, 4, 16)),
    "CdcWhenPredicateRepro": ("GENERATION_BITS", (2, 32, 64)),
    "CdcWireControls": ("CONTROL_BITS", (4, 8, 16)),
}
IDENTIFIER = r"[A-Za-z_][A-Za-z0-9_$]*"


def run(work: Path, command: list[str], label: str, expect_failure: bool = False) -> str:
    result = subprocess.run(command, cwd=work, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=300)
    (work / (label + ".log")).write_text(result.stdout)
    if expect_failure:
        assert result.returncode != 0, f"mutation survived: {label}\n{result.stdout}"
    else:
        assert result.returncode == 0, f"{label}: {' '.join(command)}\n{result.stdout}"
    return result.stdout


def internal_wires(source: str) -> set[str]:
    return set(re.findall(r"(?m)^\s*(?:\(\*[^\n]*?\*\)\s*)?wire\s+"
                          r"(?:signed\s+)?(?:\[[^\]]+\]\s+)?(" + IDENTIFIER + r")\s*;", source))


def interface(source: str) -> list[tuple[str, str]]:
    return re.findall(r"(?m)^\s*(input|output)\s+(?:wire|reg)\s+(?:signed\s+)?"
                      r"(?:\[[^\]]+\]\s+)?(" + IDENTIFIER + r")\s*[,)]", source)


def parameter_bindings(source: str) -> set[str]:
    """Named typed elaboration-value boundaries are not runtime wire chains."""
    parameters = set(re.findall(r"\bparameter\s+integer\s+(" + IDENTIFIER + r")\s*=", source))
    bindings = set()
    for name in internal_wires(source):
        drivers = re.findall(r"(?m)^\s*assign\s+" + re.escape(name) + r"\s*=\s*(.*?);", source)
        if len(drivers) != 1:
            continue
        expression = re.sub(r"\b[0-9]+'[sS]?[bBoOdDhH][0-9a-fA-F_]+", "0", drivers[0])
        references = set(re.findall(IDENTIFIER, expression))
        if references and references <= parameters and re.fullmatch(r"[A-Za-z0-9_$\s()+*/%<>&|^~?:-]+", expression):
            bindings.add(name)
    return bindings


def structural_check(root: Path) -> dict:
    report = {}
    for top in MATRICES:
        enabled = (root / "enabled" / (top + ".v")).read_text()
        disabled = (root / "disabled" / (top + ".v")).read_text()
        repeat = (root / "repeat" / (top + ".v")).read_text()
        assert enabled == repeat, f"nondeterministic emission: {top}"
        assert interface(enabled) == interface(disabled), f"public interface changed: {top}"
        fixed_point = json.loads((root / "enabled" / (top + ".fixed-point.json")).read_text())
        assert fixed_point["second_invocation_changes"] == 0, top
        old_wires, new_wires = internal_wires(disabled), internal_wires(enabled)
        retained_parameters = parameter_bindings(enabled)
        if top != "CdcWireControls":
            assert not (new_wires - retained_parameters), f"{top} retained runtime carriers: {sorted(new_wires - retained_parameters)}"
            assert old_wires - parameter_bindings(disabled), f"disabled negative control lost its chain: {top}"
        else:
            protected = {"protectedKeep", "protectedGuard", "protectedDebug", "protectedCdc",
                         "protectedGeometry", "when_user_l42"}
            assert protected <= new_wires, ("protection erased", protected - new_wires)
            assert "async_reg" in enabled, "CDC attribute disappeared"
            assert re.search(r"if\s*\(when_user_l42\)", enabled), "explicit user predicate identity erased"
        if top == "CdcWhenPredicateRepro":
            for register in ("writeCount", "readCount", "writeGray", "readGray"):
                assert re.search(r"\b" + register + r"\s*<=", enabled), register
            assert enabled.count("always @") == disabled.count("always @"), "process structure changed"
        report[top] = {
            "before_internal_wires": len(old_wires), "after_internal_wires": len(new_wires),
            "retained_parameter_bindings": sorted(retained_parameters),
            "before_bytes": len(disabled.encode()), "after_bytes": len(enabled.encode()),
            "before_operators": len(re.findall(r">>>|>>|<<|!=|==|&&|\|\||[+^~]", disabled)),
            "after_operators": len(re.findall(r">>>|>>|<<|!=|==|&&|\|\||[+^~]", enabled)),
            "native_fixed_point": fixed_point,
        }
    return report


def instance(top: str, parameter: str, value: int, ports: str, suffix: str) -> str:
    names = ports.split()
    return f"{top}{suffix} #(.{parameter}({value})) {suffix or 'dut'} (" + ",".join(
        f".{name}({suffix + '_' if name in OUTPUTS[top] else ''}{name})" for name in names) + ");"


OUTPUTS = {
    "CdcSliceCompareRepro": "valid stale consume".split(),
    "CdcGrayChainRepro": "decoded fill".split(),
    "CdcWhenPredicateRepro": "writeCount readCount writeGray readGray".split(),
    "CdcWireControls": "left right kept guardedValue debugged synchronized geometryGuarded arithmetic logical widenedSlice widenedSum sumEqualsWide namedPredicate state".split(),
}
PORTS = {
    "CdcSliceCompareRepro": "payload activeGeneration enabled ready valid stale consume",
    "CdcGrayChainRepro": "gray readCount decoded fill",
    "CdcWhenPredicateRepro": "bypass sourceValid sourceUp full destinationUp empty ready recordGeneration activeGeneration writeCount readCount writeGray readGray clk reset",
    "CdcWireControls": "a b signedValue wideValue wideReference shift choose advance left right kept guardedValue debugged synchronized geometryGuarded arithmetic logical widenedSlice widenedSum sumEqualsWide namedPredicate state clk reset",
}


def pair(top: str, parameter: str, value: int) -> str:
    return "\n".join(instance(top, parameter, value, PORTS[top], suffix) for suffix in ("gate", "gold"))


def compare(top: str) -> str:
    return "\n".join(f'if (gate_{name} !== gold_{name}) $fatal(1, "enabled/disabled {name}");'
                     for name in OUTPUTS[top])


def slice_bench(value: int) -> str:
    top = "CdcSliceCompareRepro"
    return f"""module CdcWireBench;
localparam integer DATA_BITS={value}, W=DATA_BITS+57, OFFSET=DATA_BITS+7;
reg [W-1:0] payload;
reg [31:0] activeGeneration;
reg enabled,ready;
wire gate_valid,gate_stale,gate_consume,gold_valid,gold_stale,gold_consume;
reg [W-1:0] shifted;
reg [31:0] generation;
reg mismatch;
integer n,j;
{pair(top, 'DATA_BITS', value)}
task check;
begin
  #1;
  shifted=payload >> OFFSET;
  generation=shifted[31:0];
  mismatch=(generation != activeGeneration);
  if (gate_valid !== (enabled && !mismatch)) $fatal(1,"truncated valid oracle");
  if (gate_stale !== (enabled && mismatch)) $fatal(1,"truncated stale oracle");
  if (gate_consume !== (enabled && (mismatch || ready))) $fatal(1,"truncated consume oracle");
  {compare(top)}
end endtask
initial begin
  enabled=1; ready=0; activeGeneration=0; payload=0;
  // The roadmap vector: DATA_BITS=32 gives exactly 89'h800000000000000000.
  payload[OFFSET+32]=1'b1;
  check;
  if (gate_valid !== 1'b1 || gate_stale !== 1'b0 || gate_consume !== 1'b0)
    $fatal(1,"discarded bit 32 incorrectly influenced comparison");
  // Discarded metadata, including unknowns, must not contaminate low 32 bits.
  payload={{W{{1'b1}}}}; payload[OFFSET +: 32]=0; check;
  payload[OFFSET+32]=1'bx; check;
  payload[OFFSET+33]=1'bz; check;
  for (n=0;n<160;n=n+1) begin
    for(j=0;j<W;j=j+1) payload[j]=$random;
    activeGeneration=$random; enabled=n[0]; ready=n[1];
    case(n%8)
      0: payload=0;
      1: payload={{W{{1'b1}}}};
      2: activeGeneration=payload[OFFSET +: 32];
      3: payload[OFFSET+n%32]=1'bx;
      4: payload[OFFSET+n%32]=1'bz;
      5: activeGeneration=32'bx;
      6: enabled=1'bx;
      7: ready=1'bz;
    endcase
    check;
  end
  $display("CDC_WIRE_ORACLE_PASS slice"); $finish;
end
endmodule
"""


def gray_bench(value: int) -> str:
    top = "CdcGrayChainRepro"
    return f"""module CdcWireBench;
localparam integer W={value}+2;
reg [W-1:0] gray,readCount;
wire [W-1:0] gate_decoded,gate_fill,gold_decoded,gold_fill;
reg [W-1:0] oracle,expectedFill;
integer n,j;
{pair(top, 'FIFO_LOG_DEPTH', value)}
task check;
begin
  #1;
  // Independent linear prefix-XOR oracle, not the library's shift/XOR tree.
  // XOR with zero intentionally converts a Z input to X, as the original
  // parameterized library's XOR stages do even at the most significant bit.
  oracle[W-1]=gray[W-1]^1'b0;
  for(j=W-2;j>=0;j=j-1) oracle[j]=oracle[j+1]^gray[j];
  expectedFill=oracle-readCount;
  if(gate_decoded !== oracle) $fatal(1,"independent prefix-XOR oracle");
  if(gate_fill !== expectedFill) $fatal(1,"modular fill oracle");
  {compare(top)}
end endtask
initial begin
  gray=0; readCount=0; check;
  gray={{W{{1'b1}}}}; check;
  for(n=0;n<W;n=n+1) begin
    gray=0; gray[n]=1'b1; readCount=0; check;
    readCount={{W{{1'b1}}}}; check;
    gray[n]=1'bx; check;
    gray[n]=1'bz; check;
  end
  gray=0; readCount=1; check;
  gray=0; readCount={{W{{1'b1}}}}; check;
  for(n=0;n<256;n=n+1) begin
    gray=$random; readCount=$random; check;
  end
  gray={{W{{1'bx}}}}; readCount=0; check;
  gray={{W{{1'bz}}}}; check;
  gray=0; readCount={{W{{1'bx}}}}; check;
  $display("CDC_WIRE_ORACLE_PASS gray"); $finish;
end
endmodule
"""


def predicate_bench(value: int) -> str:
    top = "CdcWhenPredicateRepro"
    return f"""module CdcWireBench;
localparam integer W={value};
reg bypass,sourceValid,sourceUp,full,destinationUp,empty,ready,clk,reset;
reg [W-1:0] recordGeneration,activeGeneration;
wire [7:0] gate_writeCount,gate_readCount,gate_writeGray,gate_readGray;
wire [7:0] gold_writeCount,gold_readCount,gold_writeGray,gold_readGray;
reg [7:0] oracleWrite,oracleRead,oracleWriteGray,oracleReadGray;
integer n,e;
{pair(top, 'GENERATION_BITS', value)}
task cycle;
begin
  #1;
  if(reset) begin oracleWrite=0; oracleRead=0; end
  else begin
    if(!bypass && sourceValid && sourceUp && !full) oracleWrite=oracleWrite+8'd1;
    if(!bypass && destinationUp && !empty && ((recordGeneration!=activeGeneration)||ready))
      oracleRead=oracleRead+8'd1;
  end
  oracleWriteGray=oracleWrite^(oracleWrite>>1);
  oracleReadGray=oracleRead^(oracleRead>>1);
  clk=1; #1;
  if(gate_writeCount !== oracleWrite || gate_readCount !== oracleRead ||
     gate_writeGray !== oracleWriteGray || gate_readGray !== oracleReadGray)
    $fatal(1,"sequential predicate/count/Gray oracle");
  {compare(top)}
  clk=0; #1;
end endtask
initial begin
  clk=0; reset=1; bypass=0; sourceValid=0; sourceUp=0; full=0;
  destinationUp=0; empty=1; ready=0; recordGeneration=0; activeGeneration=0;
  cycle; reset=0;
  for(e=0;e<2;e=e+1) begin
    recordGeneration=e;
    for(n=0;n<128;n=n+1) begin
      {{bypass,sourceValid,sourceUp,full,destinationUp,empty,ready}}=n;
      cycle;
    end
  end
  // Successive simultaneous updates cross the eight-bit count-wrap boundary.
  bypass=0; sourceValid=1; sourceUp=1; full=0; destinationUp=1; empty=0; ready=1;
  for(n=0;n<520;n=n+1) cycle;
  for(n=0;n<7;n=n+1) begin
    {{bypass,sourceValid,sourceUp,full,destinationUp,empty,ready}}=7'b0110101;
    case(n)
      0: bypass=1'bx; 1: sourceValid=1'bx; 2: sourceUp=1'bz;
      3: full=1'bz; 4: destinationUp=1'bx; 5: empty=1'bx; 6: ready=1'bz;
    endcase
    recordGeneration={{W{{1'bx}}}}; cycle;
    recordGeneration={{W{{1'bz}}}}; cycle;
  end
  // A dominating false control must suppress unknown inner predicates.
  bypass=1; cycle; reset=1; cycle; reset=0; cycle;
  $display("CDC_WIRE_ORACLE_PASS predicate"); $finish;
end
endmodule
"""


def controls_bench(value: int) -> str:
    top = "CdcWireControls"
    return f"""module CdcWireBench;
localparam integer W={value};
reg [W-1:0] a,b;
reg signed [11:0] signedValue;
reg [47:0] wideValue,wideShifted;
reg [W:0] wideReference;
reg [3:0] shift;
reg choose,advance,clk,reset;
wire [W-1:0] gate_left,gate_right,gate_kept,gate_guardedValue,gate_debugged,gate_synchronized,gate_geometryGuarded;
wire [W-1:0] gold_left,gold_right,gold_kept,gold_guardedValue,gold_debugged,gold_synchronized,gold_geometryGuarded;
wire signed [11:0] gate_arithmetic,gold_arithmetic;
wire [11:0] gate_logical,gold_logical;
wire [11:0] gate_widenedSlice,gold_widenedSlice;
wire [W:0] gate_widenedSum,gold_widenedSum;
wire gate_sumEqualsWide,gold_sumEqualsWide;
wire gate_namedPredicate,gold_namedPredicate;
wire [7:0] gate_state,gold_state;
reg [W-1:0] expectedLeft;
reg signed [11:0] expectedArithmetic;
reg [11:0] expectedLogical;
reg [11:0] expectedSlice;
reg [W-1:0] modularSum;
reg [W:0] extendedSum;
reg [7:0] expectedState;
integer n;
{pair(top, 'CONTROL_BITS', value)}
task cycle;
begin
  wideReference={{1'b0,a}}+{{1'b0,b}};
  #1;
  expectedLeft=choose ? ~(a^b) : a;
  expectedArithmetic=$signed(signedValue) >>> shift;
  expectedLogical=$unsigned(signedValue) >> shift;
  wideShifted=wideValue >> shift;
  expectedSlice={{1'b0,wideShifted[10:0]}};
  modularSum=a+b;
  extendedSum={{1'b0,a}}+{{1'b0,b}};
  if(reset) expectedState=0;
  else if(advance) begin if(a==b) expectedState=expectedState+8'd1; end
  clk=1; #1;
  if(gate_left !== expectedLeft || gate_right !== (expectedLeft^b)) $fatal(1,"fanout oracle");
  if(gate_kept !== (a^b) || gate_guardedValue !== ~(a^b) || gate_debugged !== (a^b) ||
     gate_synchronized !== ~(a^b) || gate_geometryGuarded !== (a^b)) $fatal(1,"protected carrier oracle");
  if(gate_arithmetic !== expectedArithmetic || gate_logical !== expectedLogical)
    $fatal(1,"arithmetic/logical shift oracle");
  if(gate_widenedSlice !== expectedSlice) $fatal(1,"nested slice extension and Z-preservation oracle");
  if(gate_widenedSum !== {{1'b0,modularSum}} || gate_sumEqualsWide !== ({{1'b0,modularSum}}==extendedSum))
    $fatal(1,"symbolic modular-add widening fence oracle");
  if(gate_namedPredicate !== (a==b) || gate_state !== expectedState) $fatal(1,"nested explicit predicate oracle");
  {compare(top)}
  clk=0; #1;
end endtask
initial begin
  a=0; b=0; signedValue=-1; wideValue=48'hffffffffffff; shift=1; choose=0; advance=0; clk=0; reset=1;
  cycle; reset=0;
  if(gate_arithmetic !== 12'hfff || gate_logical !== 12'h7ff) $fatal(1,"shift signedness negative control");
  for(n=0;n<512;n=n+1) begin
    a=$random; b=$random; signedValue=$random; wideValue={{$random,$random}};
    shift=n%16; choose=n[0]; advance=n[1];
    case(n%16)
      0: b=a;
      1: a={{W{{1'bx}}}};
      2: b={{W{{1'bz}}}};
      3: choose=1'bx;
      4: choose=1'bz;
      5: signedValue=12'bx;
      6: shift=4'bz;
      7: advance=1'bx;
      8: wideValue=48'bz;
      9: wideValue=48'bx;
      10: begin a={{W{{1'b1}}}}; b={{W{{1'b1}}}}; end
    endcase
    cycle;
  end
  reset=1; cycle;
  $display("CDC_WIRE_ORACLE_PASS controls"); $finish;
end
endmodule
"""


BENCHES = {
    "CdcSliceCompareRepro": slice_bench,
    "CdcGrayChainRepro": gray_bench,
    "CdcWhenPredicateRepro": predicate_bench,
    "CdcWireControls": controls_bench,
}


def simulate(work: Path, top: str, parameter: str, value: int, *, mutation: bool = False) -> None:
    for mode, suffix in (("enabled", "gate"), ("disabled", "gold")):
        source = (work / (mode + ".v")).read_text()
        source, count = re.subn(r"(?m)^module\s+" + top + r"\b", "module " + top + suffix, source)
        assert count == 1, top
        (work / (suffix + ".v")).write_text(source)
    (work / "bench.v").write_text(BENCHES[top](value))
    run(work, ["iverilog", "-g2012", "-s", "CdcWireBench", "-o", "bench.vvp",
               "gate.v", "gold.v", "bench.v"], "simulation-compile")
    output = run(work, ["vvp", "bench.vvp"], "simulation", expect_failure=mutation)
    if mutation:
        expected = {"CdcSliceCompareRepro": "truncated valid oracle",
                    "CdcGrayChainRepro": "independent prefix-XOR oracle"}[top]
        assert re.search(r"FATAL: bench\.v:\d+: " + re.escape(expected), output), output
    else:
        assert "CDC_WIRE_ORACLE_PASS" in output, output


def formal(work: Path, top: str, parameter: str, value: int, *, mutation: bool = False) -> None:
    lines = []
    # Generated internal names can acquire a different role after cleanup.
    # Compare every unchanged public port, not coincidentally equal private
    # spellings. Yosys rename -hide explicitly leaves all module ports intact.
    for mode, suffix in (("disabled", "gold"), ("enabled", "gate")):
        lines += [f"read_verilog {mode}.v", f"chparam -set {parameter} {value} {top}",
                  f"hierarchy -check -top {top}", "proc", "flatten", "memory_map", "opt_clean",
                  "async2sync", "opt_clean", "rename -hide",
                  f"rename {top} {suffix}", f"design -stash {suffix}"]
    lines += ["design -reset", "design -copy-from gold -as gold gold",
              "design -copy-from gate -as gate gate", "equiv_make gold gate equiv",
              "hierarchy -check -top equiv", "equiv_simple -undef -seq 8",
              "equiv_induct -undef -seq 8", "equiv_status -assert"]
    (work / "equivalence.ys").write_text("\n".join(lines) + "\n")
    output = run(work, ["yosys", "-Q", "-s", "equivalence.ys"], "equivalence", expect_failure=mutation)
    if mutation:
        assert re.search(r"ERROR: Found [1-9][0-9]* unproven \$equiv cells", output), output


def synthesis(work: Path, top: str, parameter: str, value: int) -> dict:
    report = {}
    for mode in ("disabled", "enabled"):
        script = (f"read_verilog {mode}.v; chparam -set {parameter} {value} {top}; "
                  f"hierarchy -check -top {top}; synth -top {top}; "
                  f"tee -o {mode}-synthesis.json stat -json")
        run(work, ["yosys", "-Q", "-p", script], mode + "-synthesis")
        stats = json.loads((work / (mode + "-synthesis.json")).read_text())
        module = stats["modules"]["\\" + top]
        report[mode] = {key: module[key] for key in ("num_cells", "num_wire_bits", "num_wires")}
    return report


def qualification(root: Path) -> dict:
    for tool in ("iverilog", "vvp", "yosys"):
        assert shutil.which(tool), f"required qualification tool is missing: {tool}"
    result = {"structure": structural_check(root), "matrix": []}
    for top, (parameter, values) in MATRICES.items():
        for value in values:
            work = root / "qualification" / f"{top}-{value}"
            work.mkdir(parents=True, exist_ok=True)
            for mode in ("enabled", "disabled"):
                shutil.copyfile(root / mode / (top + ".v"), work / (mode + ".v"))
                run(work, ["iverilog", "-g2001", "-s", top, "-tnull",
                           f"-P{top}.{parameter}={value}", mode + ".v"], mode + "-strict-verilog2001")
            simulate(work, top, parameter, value)
            formal(work, top, parameter, value)
            result["matrix"].append({"top": top, parameter: value, "four_state": "passed",
                                      "formal": "passed", "synthesis": synthesis(work, top, parameter, value)})
            print(f"CDC_WIRE_MATRIX_PASS {top} {parameter}={value}", flush=True)
    # Executable negative controls prove that the independent semantic gate sees
    # precisely the upper-bit truncation and Gray errors this increment can cause.
    mutations = (
        ("CdcSliceCompareRepro", "DATA_BITS", 32, "valid",
         "enabled && !((payload >> (DATA_BITS + 7)) != activeGeneration)"),
        ("CdcGrayChainRepro", "FIFO_LOG_DEPTH", 3, "decoded", "gray"),
    )
    result["mutations"] = []
    for top, parameter, value, target, expression in mutations:
        work = root / "qualification" / (top + "-mutation")
        work.mkdir(parents=True, exist_ok=True)
        source = (root / "enabled" / (top + ".v")).read_text()
        source, count = re.subn(r"(?m)^\s*assign\s+" + target + r"\s*=.*?;",
                               f"  assign {target} = {expression};", source)
        assert count == 1, (top, target)
        (work / "enabled.v").write_text(source)
        shutil.copyfile(root / "disabled" / (top + ".v"), work / "disabled.v")
        simulate(work, top, parameter, value, mutation=True)
        formal(work, top, parameter, value, mutation=True)
        result["mutations"].append({"top": top, "four_state": "rejected", "formal": "rejected"})
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", type=Path)
    args = parser.parse_args()
    root = args.artifacts.resolve()
    result = qualification(root)
    (root / "qualification.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f"CDC_WIRE_QUALIFICATION_PASS cases={len(result['matrix'])} mutations={len(result['mutations'])}")


if __name__ == "__main__":
    main()
