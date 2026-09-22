#!/usr/bin/env python3
"""Qualify emitted WIRE-TRUNC-01 artifacts, never rewrite their HDL.

The same file in each mode is reused for all overrides. Four-state simulation
uses a separate 64-bit mathematical reference plus a 16-bit overflow fence.
Formal equivalence compares the unchanged module against that independent
reference; synthesis and deterministic byte checks are separate obligations.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

WIDTHS = (1, 12, 13, 17, 18)
MODES = ("default", "enabled", "disabled", "repeat")
TOP = "LowBitTruncationFixture"


def run(command: list[str], log: Path, timeout: int = 180) -> str:
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout, check=False)
    log.write_text(result.stdout)
    if result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}): {command!r}; see {log}")
    return result.stdout


def require_shape(text: str) -> None:
    for target, operator in (("comb", "="), ("blocking", "="), ("sampled", "<=")):
        pattern = rf"\b{target}\s*{re.escape(operator)}\s*([^;]+);"
        expressions = re.findall(pattern, text)
        direct = [rhs for rhs in expressions if all(re.search(rf"\b{port}\b", rhs)
                                                    for port in ("a", "b", "c"))]
        if not direct:
            raise AssertionError(f"{target}: no assignment directly contains all three sum operands")
        if any("[12:0]" in rhs for rhs in direct):
            raise AssertionError(f"{target}: a default-sized low slice survived")
    if not re.search(r"\bparameter\b[^;]*\bOUT_WIDTH\b", text):
        raise AssertionError("missing OUT_WIDTH parameter")


ORACLE = r'''
module LowBitTruncationOracle #(parameter OUT_WIDTH=13)(
  input [15:0] a,b,c, input load,stall,clear,clk,reset,
  output [OUT_WIDTH-1:0] comb,wrappedComb,bitwiseComb,blocking,
  output reg [OUT_WIDTH-1:0] sampled
);
  wire [63:0] mathematicalSum = {48'b0,a} + {48'b0,b} + {48'b0,c};
  wire [15:0] wrappedPair = a + b;
  wire [63:0] mathematicalWrapped = {48'b0,wrappedPair} + {48'b0,c};
  assign comb = mathematicalSum;
  assign wrappedComb = mathematicalWrapped;
  assign bitwiseComb = {2'b0,a} ^ {2'b0,b} ^ {2'b0,c};
  assign blocking = load ? mathematicalSum : 64'b0;
  initial sampled=0;
  always @(posedge clk) begin
    if(reset) sampled <= 0;
    else if(!stall) begin
      if(load) sampled <= mathematicalSum;
      if(clear) sampled <= 0;
    end
  end
endmodule
'''

TB = r'''
module tb;
  parameter OUT_WIDTH=13;
  reg [15:0] a,b,c;
  reg load,stall,clear,clk,reset;
  wire [OUT_WIDTH-1:0] comb,wrappedComb,bitwiseComb,blocking,sampled;
  wire [OUT_WIDTH-1:0] goldComb,goldWrapped,goldBits,goldBlocking,goldSampled;
  integer i,j,seed,checks;
  LowBitTruncationFixture #(.OUT_WIDTH(OUT_WIDTH)) dut(.*);
  LowBitTruncationOracle #(.OUT_WIDTH(OUT_WIDTH)) oracle(
    .a(a),.b(b),.c(c),.load(load),.stall(stall),.clear(clear),.clk(clk),.reset(reset),
    .comb(goldComb),.wrappedComb(goldWrapped),.bitwiseComb(goldBits),
    .blocking(goldBlocking),.sampled(goldSampled));
  task step;
    begin
      #2;
      if({comb,wrappedComb,bitwiseComb,blocking} !==
         {goldComb,goldWrapped,goldBits,goldBlocking})
        $fatal(1,"combinational mismatch W=%0d case=%0d",OUT_WIDTH,checks);
      clk=1; #2;
      if(sampled !== goldSampled)
        $fatal(1,"sequential mismatch W=%0d case=%0d",OUT_WIDTH,checks);
      clk=0; #1; checks=checks+1;
    end
  endtask
  initial begin
    clk=0; reset=1; load=0; stall=0; clear=0; a=0; b=0; c=0; checks=0;
    step(); reset=0; load=1; a=16'hffff; b=16'hffff; c=16'hffff; step();
    seed=314159;
    for(i=0;i<2048;i=i+1) begin
      a=$random(seed); b=$random(seed); c=$random(seed);
      load=(i%3)!=0; stall=(i%5)==0; clear=(i%7)==0; reset=(i%37)==0;
      step();
    end
    reset=0;
    for(i=0;i<96;i=i+1) begin
      a=16'h55aa; b=16'haa55; c=16'hf00f;
      j=i%16;
      case(i/16)
        0: a[j]=1'bx;
        1: b[j]=1'bz;
        2: c[j]=1'bx;
        3: a[j]=1'bz;
        4: b[j]=1'bx;
        5: c[j]=1'bz;
      endcase
      load=(i%3)!=0; stall=(i%5)==0; clear=(i%7)==0;
      step();
    end
    $display("WIRE_TRUNC_01 PASS W=%0d checks=%0d",OUT_WIDTH,checks);
    $finish;
  end
endmodule
'''

# A structurally direct fixture used only to prove that the qualification is
# behavioral, not a regex gate. Every mutant below still satisfies require_shape.
MUTATION_DUT = r'''
module LowBitTruncationFixture #(parameter OUT_WIDTH=13)(
  input [15:0] a,b,c, input load,stall,clear,clk,reset,
  output [OUT_WIDTH-1:0] comb,wrappedComb,bitwiseComb,
  output reg [OUT_WIDTH-1:0] blocking,
  output reg [OUT_WIDTH-1:0] sampled
);
  wire [15:0] wrappedPair = a + b;
  assign comb = a + b + c;
  assign wrappedComb = wrappedPair + c;
  assign bitwiseComb = {2'b0,a} ^ {2'b0,b} ^ {2'b0,c};
  always @* begin
    blocking = 0;
    if(load) blocking = a + b + c;
  end
  initial sampled=0;
  always @(posedge clk) begin
    if(reset) sampled <= 0;
    else if(!stall) begin
      if(load) sampled <= a + b + c;
      if(clear) sampled <= 0;
    end
  end
endmodule
'''


def qualify(root: Path) -> dict:
    manifest = json.loads((root / "fixture.json").read_text())
    if manifest != {"fixture": TOP, "default_width": 13, "domain": [1, 18],
                    "override_widths": list(WIDTHS), "modes": list(MODES),
                    "per_override_reelaboration": False}:
        raise AssertionError("incorrect artifact matrix")
    artifacts: dict[str, Path] = {}
    fingerprints: dict[str, str] = {}
    for mode in MODES:
        candidates = sorted((root / mode).glob("*.v"))
        if len(candidates) != 1 or candidates[0].name != TOP + ".v" or candidates[0].is_symlink():
            raise AssertionError(f"unexpected module inventory in {mode}")
        artifacts[mode] = candidates[0]
        fingerprints[mode] = hashlib.sha256(candidates[0].read_bytes()).hexdigest()
    if len({fingerprints[mode] for mode in ("default", "enabled", "repeat")}) != 1:
        raise AssertionError("default/enabled/repeat are not byte-identical")
    require_shape(artifacts["enabled"].read_text())
    if fingerprints["enabled"] == fingerprints["disabled"]:
        raise AssertionError("fixture did not exercise the enabled transformation")
    logs = root / "qualification-logs"
    logs.mkdir(exist_ok=True)
    oracle = logs / "oracle.v"
    tb = logs / "tb.sv"
    oracle.write_text(ORACLE)
    tb.write_text(TB)
    matrix = []
    for mode in MODES:
        dut = artifacts[mode]
        for width in WIDTHS:
            prefix = logs / f"{mode}-w{width}"
            binary = str(prefix) + ".vvp"
            run(["iverilog", "-g2012", "-s", "tb", f"-Ptb.OUT_WIDTH={width}",
                 "-o", binary, str(dut), str(oracle), str(tb)], Path(str(prefix) + "-compile.log"))
            result = run(["vvp", binary], Path(str(prefix) + "-four-state.log"))
            expected = f"WIRE_TRUNC_01 PASS W={width} checks=2146"
            if expected not in result:
                raise AssertionError(f"incomplete simulation: {mode}, {width}")
            script = logs / f"{mode}-w{width}-equivalence.ys"
            script.write_text(f'''read_verilog -formal {oracle}
chparam -set OUT_WIDTH {width} LowBitTruncationOracle
rename LowBitTruncationOracle gold
read_verilog -formal {dut}
chparam -set OUT_WIDTH {width} {TOP}
rename {TOP} gate
proc
opt
equiv_make gold gate equiv
hierarchy -check -top equiv
equiv_simple
equiv_induct -seq 6
equiv_status -assert
''')
            run(["yosys", "-Q", "-s", str(script)], Path(str(prefix) + "-equivalence.log"))
            synthesis = logs / f"{mode}-w{width}-synthesis.ys"
            synthesis.write_text(f'''read_verilog {dut}
chparam -set OUT_WIDTH {width} {TOP}
hierarchy -check -top {TOP}
synth -top {TOP}
check -assert
stat
''')
            run(["yosys", "-Q", "-s", str(synthesis)], Path(str(prefix) + "-synthesis.log"))
            matrix.append({"mode": mode, "width": width, "four_state": "passed",
                           "equivalence": "passed", "synthesis": "passed", "checks": 2146,
                           "artifact_sha256": fingerprints[mode]})
        if hashlib.sha256(dut.read_bytes()).hexdigest() != fingerprints[mode]:
            raise AssertionError("the input artifact was modified during qualification")
    report = {"schema_version": 1, "matrix": matrix, "byte_identical": True,
              "artifact_sha256": fingerprints, "per_override_reelaboration": False}
    (root / "qualification.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def functional_mutation_controls() -> int:
    require_shape(MUTATION_DUT)
    mutations = (
        MUTATION_DUT.replace("assign comb = a + b + c;",
                             "assign comb = a + b + (c & 16'h0000);"),
        MUTATION_DUT.replace("assign wrappedComb = wrappedPair + c;",
                             "assign wrappedComb = a + b + c;"),
        MUTATION_DUT.replace("{2'b0,a} ^ {2'b0,b} ^ {2'b0,c}",
                             "{2'b0,a} | {2'b0,b} | {2'b0,c}"),
        MUTATION_DUT.replace("if(load) sampled <= a + b + c;\n      if(clear) sampled <= 0;",
                             "if(clear) sampled <= 0;\n      if(load) sampled <= a + b + c;"),
    )
    with tempfile.TemporaryDirectory(prefix="wire-trunc-mutations-") as directory:
        root = Path(directory)
        oracle = root / "oracle.v"
        tb = root / "tb.sv"
        oracle.write_text(ORACLE)
        tb.write_text(TB)
        rejected = 0
        for index, mutation in enumerate(mutations):
            # These controls intentionally keep the structural requirement true;
            # only independent behavioral qualification is allowed to reject them.
            require_shape(mutation)
            dut = root / f"mutant-{index}.v"
            binary = root / f"mutant-{index}.vvp"
            dut.write_text(mutation)
            compile_result = subprocess.run(
                ["iverilog", "-g2012", "-s", "tb", "-Ptb.OUT_WIDTH=17",
                 "-o", str(binary), str(dut), str(oracle), str(tb)],
                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                timeout=60, check=False)
            if compile_result.returncode:
                raise AssertionError(f"functional mutant {index} did not compile: {compile_result.stdout}")
            simulation = subprocess.run(["vvp", str(binary)], text=True,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                        timeout=60, check=False)
            if simulation.returncode == 0:
                raise AssertionError(f"functional mutant {index} escaped the independent oracle")
            rejected += 1
        return rejected


def self_test() -> None:
    good = "parameter OUT_WIDTH=13; assign comb=a+b+c; blocking = a+b+c; sampled <= a+b+c;"
    require_shape(good)
    rejected = 0
    for mutation in (good.replace("assign comb=a+b+c", "assign comb=carrier"),
                     good.replace("blocking = a+b+c", "blocking = (a+b+c)[12:0]"),
                     good.replace("OUT_WIDTH", "REMOVED_PARAMETER"),
                     good.replace("sampled <= a+b+c", "sampled <= helper")):
        try:
            require_shape(mutation)
        except AssertionError:
            rejected += 1
    assert rejected == 4
    functional = functional_mutation_controls()
    assert functional == 4
    # Independent modular identity and intermediate-overflow counterexample.
    for width in WIDTHS:
        mask = (1 << width) - 1
        for a, b, c in ((0, 0, 0), (65535, 65535, 65535), (32768, 32768, 7), (1, 2, 3)):
            assert (((a + b + c) & ((1 << 18) - 1)) & mask) == ((a + b + c) & mask)
    assert (((65535 + 1) & 65535) + 7) != (65535 + 1 + 7)
    print("WIRE_TRUNC_01 checker self-test: 4 malformed-shape and 4 functional RTL mutations rejected")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    if args.root is not None:
        result = qualify(args.root.resolve(strict=True))
        print(f"WIRE_TRUNC_01 qualified {len(result['matrix'])} unchanged-artifact override cases")
    elif not args.self_test:
        parser.error("ROOT or --self-test is required")


if __name__ == "__main__":
    main()
