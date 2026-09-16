#!/usr/bin/env python3
"""Qualify unchanged native timer/geometry artifacts at independent overrides."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def require(ok, detail):
    if not ok:
        raise RuntimeError("compact timeout validation: " + detail)


def execute(command, log: Path, expect_success=True):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, timeout=120)
    log.write_text(result.stdout)
    if expect_success:
        require(result.returncode == 0, repr(command) + "\n" + result.stdout)
    return result


def inventory(directory):
    return {p.relative_to(directory).as_posix(): p.read_bytes()
            for p in directory.rglob("*") if p.is_file() and p.suffix in (".v", ".sv")}


def timer_tb(timeout, minimum=32, invalid=False):
    if invalid:
        return f'''module tb;
CompactDeadline #(.CLOCK_TIMEOUT({timeout}), .MIN_TIMEOUT({minimum})) dut();
initial begin #2; $finish; end
endmodule
'''
    return f'''module tb;
reg clk=0, reset=1, reload=0, preload=0, enable=0;
reg [24:0] preloadValue=0;
wire [24:0] remaining; wire busy, expired;
CompactDeadline #(.CLOCK_TIMEOUT({timeout}), .MIN_TIMEOUT({minimum})) dut(
.clk(clk), .reset(reset), .reload(reload), .preload(preload), .enable(enable),
.preloadValue(preloadValue), .remaining(remaining), .busy(busy), .expired(expired));
task tick; begin #5; clk=1; #5; clk=0; #1; end endtask
task state; input [24:0] count; input event_value; begin
if(remaining !== count || busy !== (count != 0) || expired !== event_value)
  $fatal(1,"timer state count=%0d got=%0d event=%0b",count,remaining,expired);
end endtask
initial begin
repeat(3) tick; reset=0; tick; state(0,0);
reload=1; preload=1; preloadValue=2; tick; reload=0; preload=0;
if(remaining !== 25'd{timeout}) $fatal(1,"reload mismatch"); state(25'd{timeout},0);
repeat(2) begin tick; state(25'd{timeout},0); end
enable=1; tick; state(25'd{timeout - 1},0); enable=0;
preload=1; preloadValue=2; tick; preload=0; state(2,0);
enable=1; tick; state(1,0); tick; state(0,1); tick; state(0,0);
repeat(2) begin tick; state(0,0); end
enable=0; reload=1; tick; reload=0; state(25'd{timeout},0);
reset=1; tick; reset=0; tick; state(0,0);
$display("COMPACT_TIMER_PASS T={timeout} MIN={minimum}"); $finish;
end
endmodule
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out", type=Path)
    parser.add_argument("repeat", type=Path)
    parser.add_argument("results", type=Path)
    args = parser.parse_args()
    args.results.mkdir(parents=True, exist_ok=True)
    receipt = args.results / "summary.json"
    receipt.unlink(missing_ok=True)
    original = inventory(args.out)
    require(set(original) == {"timer/CompactDeadline.v", "geometry/CompactDeadlineGeometry.v"},
            "missing or unexpected generated HDL files")
    require(original == inventory(args.repeat), "independent native generations differ")
    timer = args.out / "timer/CompactDeadline.v"
    geometry = args.out / "geometry/CompactDeadlineGeometry.v"
    text = timer.read_text()
    require("parameter integer CLOCK_TIMEOUT" in text and "parameter integer MIN_TIMEOUT" in text,
            "independent parameter declarations missing")
    require("$fatal" in text and "$error" not in text and "`ifndef SYNTHESIS" in text,
            "mixed requirement is not simulation-only fatal")
    require("PROFILE" not in text, "unexpected profile selector")
    for top, rtl in (("CompactDeadline", timer), ("CompactDeadlineGeometry", geometry)):
        execute(["iverilog", "-g2005", "-s", top, "-o", str(args.results/(top+"-strict.vvp")), str(rtl)],
                args.results/(top+"-strict.log"))
    def simulate(rtl, tb_text, tag, expected, invalid=False):
        tb = args.results/(tag+".sv")
        binary = args.results/(tag+".vvp")
        tb.write_text(tb_text)
        execute(["iverilog", "-g2012", "-s", "tb", "-o", str(binary), str(rtl), str(tb)],
                args.results/(tag+"-compile.log"))
        result = execute(["vvp", str(binary)], args.results/(tag+"-simulation.log"), not invalid)
        if invalid:
            require(result.returncode != 0 and expected in result.stdout, "wrong invalid-override diagnostic: " + tag)
        else:
            require(expected in result.stdout, "simulation success marker missing: " + tag)
        return result
    simulations = 0
    syntheses = 0
    for timeout in (32, 1024, 65536, 16777216):
        tag = "timer-" + str(timeout)
        simulate(timer, timer_tb(timeout), tag, "COMPACT_TIMER_PASS")
        print("COMPACT_TIMER_OVERRIDE_PASS", timeout)
        simulations += 1
        width = timeout.bit_length()
        tb = f'''module tb;
reg [{width-1}:0] din; wire [{width-1}:0] dout; integer i;
CompactDeadlineGeometry #(.CLOCK_TIMEOUT({timeout})) dut(.din(din),.dout(dout));
initial begin
if($bits(dut.din) != {width} || $bits(dut.dout) != {width}) $fatal(1,"compact geometry");
for(i=0;i<{width};i=i+1) begin din=0; din[i]=1; #1; if(dout !== din) $fatal(1,"geometry data");
din=~din; #1; if(dout !== din) $fatal(1,"inverse geometry data"); end
$display("COMPACT_GEOMETRY_PASS T={timeout} W={width}"); $finish;
end endmodule
'''
        simulate(geometry, tb, "geometry-"+str(timeout), "COMPACT_GEOMETRY_PASS")
        simulations += 1
        for top, rtl in (("CompactDeadline", timer), ("CompactDeadlineGeometry", geometry)):
            script = f"read_verilog -D SYNTHESIS {rtl}; chparam -set CLOCK_TIMEOUT {timeout} {top}; hierarchy -check -top {top}; proc; opt; check -assert; stat"
            execute(["yosys", "-Q", "-T", "-p", script], args.results/(top+"-"+str(timeout)+"-yosys.log"))
            syntheses += 1
    simulate(timer, timer_tb(32, 64, invalid=True), "invalid-32-64", "CLOCK_TIMEOUT must be >= MIN_TIMEOUT", invalid=True)
    simulate(timer, timer_tb(1024, 64), "valid-1024-64", "COMPACT_TIMER_PASS")
    simulations += 1
    preprocessed = args.results/"synthesis.v"
    execute(["iverilog", "-g2012", "-E", "-DSYNTHESIS", "-o", str(preprocessed), str(timer)], args.results/"synthesis-preprocess.log")
    require("$fatal" not in preprocessed.read_text() and "$error" not in preprocessed.read_text(), "simulation assertions survived synthesis preprocessing")

    # Mutants are disposable copies, never replacements for production output.
    changed, count = re.subn(r"(assign\s+timeout_reload_value\s*=\s*)[^;]+;", r"\g<1>25'd0;", text, count=1)
    require(count == 1, "native reload assignment mutation is missing or ambiguous")
    mutant = args.results/"wrong-reload.v"
    mutant.write_text(changed)
    tb = args.results/"wrong-reload.sv"; tb.write_text(timer_tb(32))
    binary = args.results/"wrong-reload.vvp"
    execute(["iverilog", "-g2012", "-s", "tb", "-o", str(binary), str(mutant), str(tb)], args.results/"wrong-reload-compile.log")
    result = execute(["vvp", str(binary)], args.results/"wrong-reload-simulation.log", False)
    require(result.returncode != 0 and "reload mismatch" in result.stdout, "wrong reload mutation not detected behaviorally")
    mutant = args.results/"nonfatal-require.v"
    mutant.write_text(text.replace("$fatal(1,", "$display("))
    tb = args.results/"nonfatal-require.sv"; tb.write_text(timer_tb(32, 64, True))
    binary = args.results/"nonfatal-require.vvp"
    execute(["iverilog", "-g2012", "-s", "tb", "-o", str(binary), str(mutant), str(tb)], args.results/"nonfatal-require-compile.log")
    result = execute(["vvp", str(binary)], args.results/"nonfatal-require-simulation.log", False)
    require(result.returncode == 0, "nonfatal mutation did not exercise the rejection detector")
    require(original == inventory(args.out), "validation changed production HDL")
    value = {"status": "passed", "simulations": simulations, "synthesis": syntheses,
             "invalid_overrides": 1, "deterministic_files": len(original), "behavioral_mutations": 2,
             "rtl_sha256": {p: hashlib.sha256(raw).hexdigest() for p, raw in sorted(original.items())}}
    receipt.write_text(json.dumps(value, indent=2)+"\n")
    print("COMPACT_TIMEOUT_VALIDATION_PASS " + json.dumps(value, sort_keys=True))


if __name__ == "__main__":
    main()
