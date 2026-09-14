#!/usr/bin/env python3
"""Native AST/publication and legality matrix for Icarus, Verilator, and Yosys.

DUTs are generated twice by MorphHDL, then never modified. Only independently
written testbenches/oracles change between parameter overrides. A synthesis
check of an illegal tuple tests the safe physical range, not legal acceptance.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
import resource
import shutil
import subprocess
import sys
from typing import Any

from check import Case, Fixture, digest, execute, require_unchanged, run_fixture


@dataclass(frozen=True)
class SymbolicFixture(Fixture):
    def width(self, case: Case) -> int:
        super().width(case)  # Validate arity/positive root witnesses.
        a, b = case.values
        if self.scenario in ("overlap", "child"):
            return (a + b) % (a + 1) + 1
        if self.scenario == "difference":
            return max(a - b, 1)
        if self.scenario == "require-only":
            return 3
        return a + b


def fixture(scenario: str, pairs: tuple[tuple[int, int], ...], defaults: bool = True) -> SymbolicFixture:
    cases = tuple(Case(pair) for pair in pairs)
    if defaults:
        cases += (Case((32, 2), defaults=True),)
    return SymbolicFixture("SymbolicPublication_" + scenario.replace("-", "_"),
                           "repro.SymbolicPublicationRepro", ("A", "B"), cases,
                           scenario=scenario, child_module="SymbolicWidthChild" if scenario == "child" else None)


LARGE = ((32, 2), (1, 1), (1, 2048), (2048, 1), (2048, 2048), (120, 8), (17, 32))
FIXTURES = (
    fixture("overlap", LARGE), fixture("child", LARGE),
    fixture("message-format", ((3, 2),)),
    fixture("difference", ((32, 2), (2, 1), (2048, 1), (2048, 2047), (120, 8), (33, 17))),
    fixture("mixed", ((32, 2), (1, 1), (2048, 2), (2048, 2048), (120, 8), (17, 17))),
    fixture("default-invalid-require", ((1, 2), (1, 2048), (17, 32)), defaults=False),
    fixture("require-only", ((32, 2), (1, 1), (2048, 2), (2048, 2048))),
)


def check_guard(source: Path, required: bool) -> None:
    text = source.read_text()
    if not required:
        if "`ifndef SYNTHESIS" in text:
            raise RuntimeError("positive publication gained an unnecessary safe-width diagnostic")
        return
    blocks = re.findall(r"`ifndef SYNTHESIS\s*(.*?)`endif", text, flags=re.S)
    def code_only(raw: str) -> str:
        # Ignore strings and comments: a message mentioning '$error' is not a task.
        return re.sub(r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/', " ", raw, flags=re.S)
    if not blocks or not all(re.search(r"\$fatal\s*\(\s*1\s*,", code_only(block)) for block in blocks):
        raise RuntimeError("required fatal diagnostic is not protected from synthesis")
    if any(re.search(r"\$error\b", code_only(block)) for block in blocks):
        raise RuntimeError("a require must use fatal only, not a recoverable or duplicate error report")
    hardware = re.sub(r"`ifndef SYNTHESIS\s*.*?`endif", "", text, flags=re.S)
    if re.search(r"\$(error|fatal)\b", code_only(hardware)):
        raise RuntimeError("a simulation task escaped the synthesis guard")
    if not all(word in hardware for word in ("input", "observed", "assign")):
        raise RuntimeError("the synthesis guard removed hardware")


def failed_simulation(command: list[str], directory: Path, log: Path,
                      marker: str, commands: list[dict[str, Any]]) -> None:
    result = subprocess.run(command, cwd=directory, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, timeout=180)
    log.write_text(result.stdout)
    commands.append({"argv": command, "cwd": str(directory), "returncode": result.returncode,
                     "expected": "invalid-parameter runtime rejection", "log": str(log)})
    if result.returncode == 0 or marker not in result.stdout or "ILLEGAL_EXECUTION_CONTINUED" in result.stdout:
        raise RuntimeError(f"invalid tuple was not rejected by its legality diagnostic: {command}; {result.stdout}")


def check_message(item: SymbolicFixture, output: str) -> None:
    if output.count("MorphHDL parameter legality failed:") != 1:
        raise RuntimeError("failed require did not produce exactly one original diagnostic")
    if item.scenario == "message-format":
        for literal in ('100% literal %d %m', 'quoted "A"', 'backslash \\;', '\nnext line'):
            if literal not in output:
                raise RuntimeError("fatal interpreted user message as a format string: " + repr(literal))


def icarus_illegal(item: SymbolicFixture, source: Path, values: tuple[int, int], directory: Path,
                   commands: list[dict[str, Any]]) -> None:
    executable = directory / "illegal.vvp"
    width = item.width(Case(values))
    wrapper = directory / "invalid_tb.v"
    wrapper.write_text(f"""module InvalidPolicyTop;
  wire [{width-1}:0] din = {width}'b0;
  wire observed;
  {item.module} #(.A({values[0]}), .B({values[1]})) dut(.din(din), .observed(observed));
  initial begin
    #1;
    $display("ILLEGAL_EXECUTION_CONTINUED");
    if ($bits(dut.din) != {width} || observed !== 1'b0)
      $fatal(1, "synthesis-guard physical-width check failed");
    $display("SYNTHESIS_GUARD_BYPASS_PASS");
    $finish;
  end
endmodule
""")
    compile_command = ["iverilog", "-g2001", "-s", "InvalidPolicyTop",
                       "-o", str(executable), str(source), str(wrapper)]
    execute(compile_command, directory, directory / "icarus-compile.log", 180, commands)
    failed_simulation(["vvp", str(executable)], directory, directory / "icarus-run.log",
                      "MorphHDL parameter legality failed: ", commands)
    check_message(item, (directory / "icarus-run.log").read_text())
    execute(compile_command[:1] + ["-DSYNTHESIS"] + compile_command[1:],
            directory, directory / "icarus-synthesis-compile.log", 180, commands)
    output = execute(["vvp", str(executable)], directory, directory / "icarus-synthesis-run.log", 180, commands)
    if "SYNTHESIS_GUARD_BYPASS_PASS" not in output or "MorphHDL parameter legality failed" in output:
        raise RuntimeError("SYNTHESIS did not remove the simulation diagnostic")


def verilator_case(item: SymbolicFixture, source: Path, values: tuple[int, int], illegal: bool,
                    directory: Path, commands: list[dict[str, Any]]) -> None:
    width = item.width(Case(values))
    if width > 31:
        raise ValueError("C++ oracle deliberately uses scalar-sized probe cases")
    wrapper = directory / "top.sv"
    wrapper.write_text(f"""module PolicyTop(input wire [{width-1}:0] din, output wire observed,
  output wire [31:0] actual_width);
  {item.module} #(.A({values[0]}),.B({values[1]})) dut(.din(din),.observed(observed));
  assign actual_width = $bits(dut.din);
endmodule
""")
    cpp = directory / "sim.cpp"
    cpp.write_text(f"""#include "VPolicy.h"
#include "verilated.h"
#include <cstdint>
#include <cstdio>
double sc_time_stamp() {{ return 0; }}
int main(int argc, char** argv) {{
  Verilated::commandArgs(argc, argv);
  VPolicy dut;
  dut.din=0; dut.eval();
  if(dut.actual_width!={width} || dut.observed!=0) return 2;
  for(unsigned i=0;i<{width};++i) {{
    dut.din=std::uint32_t(1)<<i; dut.eval(); if(dut.observed!=1) return 3;
    dut.din=0; dut.eval(); if(dut.observed!=0) return 4;
  }}
  dut.final(); std::puts("VERILATOR_ALL_BITS_PASS"); return 0;
}}
""")
    obj = directory / "obj"
    execute(["verilator", "--cc", "--exe", "--build", "--assert", "--top-module", "PolicyTop",
             "--prefix", "VPolicy", "--Mdir", str(obj), str(source), str(wrapper), str(cpp)],
            directory, directory / "build.log", 600, commands)
    command = [str(obj / "VPolicy")]
    if illegal:
        marker = "MorphHDL parameter legality failed: "
        failed_simulation(command, directory, directory / "run.log", marker, commands)
    else:
        result = execute(command, directory, directory / "run.log", 180, commands)
        if "VERILATOR_ALL_BITS_PASS" not in result:
            raise RuntimeError("Verilator did not finish its all-bit oracle")


def yosys_case(item: SymbolicFixture, source: Path, values: tuple[int, int], directory: Path,
               commands: list[dict[str, Any]]) -> None:
    width = item.width(Case(values))
    netlist = directory / "synthesized.json"
    script = directory / "physical.ys"
    script.write_text(f'''read_verilog -DSYNTHESIS "{source}"
chparam -set A {values[0]} -set B {values[1]} {item.module}
hierarchy -top {item.module}
proc
opt
check -assert
write_json "{netlist}"
''')
    execute(["yosys", "-s", str(script)], directory, directory / "physical.log", 180, commands)
    ports = json.loads(netlist.read_text())["modules"][item.module]["ports"]
    if len(ports["din"]["bits"]) != width or len(ports["observed"]["bits"]) != 1:
        raise RuntimeError("synthesis changed the admitted physical port width")
    oracle = directory / "oracle.v"
    oracle.write_text(f'''module Oracle(input wire [{width-1}:0] din, output wire ok);
  wire observed;
  {item.module} #(.A({values[0]}),.B({values[1]})) dut(.din(din),.observed(observed));
  assign ok = observed == (|din);
endmodule
''')
    proof = directory / "proof.ys"
    proof.write_text(f'''read_verilog -DSYNTHESIS "{source}" "{oracle}"
prep -top Oracle -flatten
sat -verify -prove ok 1 -show-inputs
''')
    log = execute(["yosys", "-s", str(proof)], directory, directory / "proof.log", 180, commands)
    if "SUCCESS" not in log:
        raise RuntimeError("Yosys did not report the reduction proof successful")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-classpath")
    parser.add_argument("--results", type=Path)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    import tempfile
    (here / "results").mkdir(exist_ok=True)
    results = (args.results or Path(tempfile.mkdtemp(prefix="symbolic-policy-", dir=here / "results"))).resolve()
    results.mkdir(parents=True, exist_ok=True)
    summary: dict[str, Any] = {"status": "not_run", "commands": [], "fixtures": [], "tool_cases": []}
    try:
        required = ("java" if args.java_classpath else "sbt", "iverilog", "vvp", "verilator", "yosys", "make", "g++")
        missing = [name for name in required if shutil.which(name) is None]
        if missing:
            raise RuntimeError("BLOCKED: missing tools: " + ", ".join(missing))
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
        for tool, argument in (("iverilog","-V"),("verilator","--version"),("yosys","-V")):
            execute([tool,argument], here, results / (tool+"-version.log"), 30, summary["commands"])
        sources = {}
        for item in FIXTURES:
            record = run_fixture(item, here, results, 1800, summary["commands"], args.java_classpath)
            summary["fixtures"].append(record)
            source = Path(record["generated_source"])
            sources[item.scenario] = source
            check_guard(source, item.scenario not in ("overlap", "child"))
        # Each tool uses the same immutable published DUT; only the wrapper or
        # elaboration overrides change. Zero and negative raw widths are both tested.
        for item in FIXTURES:
            source = sources[item.scenario]
            sha = digest(source)
            probes = [(3,2)]
            if item.scenario == "default-invalid-require": probes = [(1,2)]
            invalid = ((2,2),(2,5)) if item.scenario == "difference" else \
                      ((1,2),) if item.scenario in ("mixed","require-only","message-format") else \
                      ((3,2),) if item.scenario == "default-invalid-require" else ()
            for values in probes + list(invalid):
                illegal = values in invalid
                directory = results / (item.scenario + "-" + "-".join(map(str,values)))
                directory.mkdir()
                if illegal:
                    icarus_illegal(item, source, values, directory, summary["commands"])
                verilator_case(item, source, values, illegal, directory, summary["commands"])
                yosys_case(item, source, values, directory, summary["commands"])
                if illegal:
                    check_message(item, (directory / "run.log").read_text())
                require_unchanged(source, sha)
                summary["tool_cases"].append({"scenario": item.scenario, "A": values[0], "B": values[1],
                    "physical_width": item.width(Case(values)), "illegal_tuple": illegal,
                    "normal_simulation_rejected": illegal, "yosys_physical_width_and_reduction": "passed"})
        summary["status"] = "SYMBOLIC_PUBLICATION_POLICY_PASS"
        print(summary["status"])
        return 0
    except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        summary["status"] = "failed"
        summary["error"] = str(error)
        print("SYMBOLIC_PUBLICATION_POLICY_FAILED: " + str(error), file=sys.stderr)
        return 1
    finally:
        (results / "summary.json").write_text(json.dumps(summary,indent=2)+"\n")


if __name__ == "__main__":
    raise SystemExit(main())
