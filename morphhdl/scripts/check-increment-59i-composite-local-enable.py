#!/usr/bin/env python3
"""Simulate emitted parameterized local-enable hardware against native Spinal.

The only adapters are port wiring. Python generates deterministic stimulus; it
does not emulate reductions, registers, enables, resets, or an expected datapath.
Both split and consolidated candidates run against the same independent native
reference. Semantic candidate mutations must produce a functional mismatch.
"""
from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import random
import re
import shutil
import subprocess
from pathlib import Path

SCOPE = "59i-local-enable-native-hardware"
PASS = "59I-LOCAL-ENABLE-HARDWARE-PASS"
FAIL = "59I-LOCAL-ENABLE-HARDWARE-MISMATCH"
SAT_PASS = "SAT proof finished - no model found: SUCCESS!"
SAT_FAIL = "SAT proof finished - model found: FAIL!"
SHAPES = {(u, s, b, n) for (u, s, b), n in itertools.product(
    ((3, 4, 2), (5, 7, 3), (8, 3, 6)), (1, 2, 3, 5))}
PROFILES = {f"{kind}_{polarity}_{edge}" for kind, polarity, edge in itertools.product(
    ("sync", "async"), ("high", "low"), ("rising", "falling"))}
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def checked_file(root: Path, name: str) -> Path:
    path = (root / name).resolve()
    require(root in path.parents and path.is_file(), f"invalid artifact: {name}")
    return path


def validate(manifest: dict) -> None:
    require(set(manifest) == {"schema", "scope", "candidates", "mutations", "cases"}
            and manifest["schema"] == 1 and manifest["scope"] == SCOPE, "wrong manifest schema/scope")
    candidates, cases, mutations = (manifest[k] for k in ("candidates", "cases", "mutations"))
    candidate_keys = {"profile", "split", "mutation", "module", "file"}
    require(all(set(c) == candidate_keys for c in candidates + mutations), "wrong candidate fields")
    require(len(candidates) == 16 and {(c["profile"], c["split"]) for c in candidates} ==
            set(itertools.product(PROFILES, (False, True))), "incomplete emission/profile matrix")
    require(all(type(c["split"]) is bool and c["mutation"] == "none" for c in candidates),
            "non-Boolean split mode or mutated positive candidate")
    require(len(mutations) == 2 and {c["mutation"] for c in mutations} == {"enable-identity", "reset-value"}
            and all(c["profile"] == "sync_high_rising" and c["split"] is False for c in mutations),
            "missing semantic enable/reset mutation")
    case_keys = {"id", "profile", "asynchronous", "reset_low", "falling", "enable_low",
                 "uw", "sw", "bw", "count", "module", "file"}
    require(all(set(c) == case_keys for c in cases), "wrong native case fields")
    require(len(cases) == 96 and {(c["profile"], tuple(c[k] for k in ("uw", "sw", "bw", "count")))
            for c in cases} == set(itertools.product(PROFILES, SHAPES)), "incomplete native shape matrix")
    for case in cases:
        require(all(type(case[k]) is int for k in ("uw", "sw", "bw", "count")), "noninteger geometry")
        for key, expected in (("asynchronous", case["profile"].startswith("async_")),
                              ("reset_low", "_low_" in case["profile"]),
                              ("falling", case["profile"].endswith("_falling")),
                              ("enable_low", case["profile"].endswith("_falling"))):
            require(type(case[key]) is bool and case[key] == expected, f"wrong {key} profile")
    for key in ("module", "file"):
        entries = candidates + mutations + cases
        require(len({c[key] for c in entries}) == len(entries), f"reused {key}")
    require(all(IDENTIFIER.fullmatch(c["module"]) for c in candidates + mutations + cases),
            "invalid module identity")
    require(len({c["id"] for c in cases}) == len(cases), "reused case ID")


def ports(case: dict) -> dict[str, int]:
    return {"unsigned": case["uw"], "signed": case["sw"], "bitsValue": case["bw"], "valid": 1}


def instances(case: dict, candidates: list[dict]) -> list[str]:
    lines = []
    for index, entry in enumerate([case] + candidates):
        prefix = "g" if index == 0 else f"c{index - 1}"
        bindings = [f".{name}({name})" for name in ("clk", "reset", "enable", "values")]
        for field, width in ports(case).items():
            lines.append(f"wire [{width - 1}:0] {prefix}_{field};")
            bindings.append(f".result_{field}({prefix}_{field})")
        params = "" if index == 0 else " #(" + ", ".join(
            f".{name.upper()}({case[name]})" for name in ("uw", "sw", "bw", "count")) + ")"
        lines.append(f"{entry['module']}{params} {prefix}_dut(" + ", ".join(bindings) + ");")
    return lines


def miter(case: dict, candidates: list[dict]) -> str:
    width = sum(ports(case).values()) * case["count"]
    lines = ["module miter(input wire clk, reset, enable,",
             f"input wire [{width - 1}:0] values, output wire bad);"]
    lines += instances(case, candidates)
    lines += ["assign bad = " + " | ".join(
        f"(|(g_{field} ^ c{index}_{field}))" for index in range(len(candidates))
        for field in ports(case)) + ";", "endmodule", ""]
    return "\n".join(lines)


def stimulus(case: dict) -> list[int]:
    """First leaf is least significant, and each lane occupies a whole record."""
    fields = tuple(ports(case).values())
    stride = sum(fields)
    rng = random.Random(0x59_10_CA_FE + case["count"] * 131 + stride)
    result = []
    # Alternating original/current values expose the two-register identity bug.
    # One active lane isolates that distinction from sum/XOR cancellation.
    directed = ((0, 0, 0, 0), (1, 0, 0, 0), (0, 0, 0, 1),
                (1 << (fields[0] - 1), 1 << (fields[1] - 1), 1, 0),
                ((1 << fields[0]) - 1, (1 << fields[1]) - 1, (1 << fields[2]) - 1, 1),
                (2, 1, 2, 0), (3, 2, 1, 1))
    for iteration in range(384):
        packed = 0
        for lane in range(case["count"]):
            if iteration < 56:
                values = directed[iteration % len(directed)] if lane == 0 else (0, 0, 0, 0)
            elif iteration < 112:
                values = directed[(iteration + lane) % len(directed)]
            else:
                values = tuple(rng.getrandbits(width) for width in fields)
            offset = lane * stride
            for width, value in zip(fields, values):
                packed |= value << offset
                offset += width
        result.append(packed)
    return result


def bench(case: dict, candidates: list[dict]) -> str:
    width = sum(ports(case).values()) * case["count"]
    active_clock, idle_clock = int(not case["falling"]), int(case["falling"])
    active_reset, idle_reset = int(not case["reset_low"]), int(case["reset_low"])
    active_enable, idle_enable = int(not case["enable_low"]), int(case["enable_low"])
    lines = ["`timescale 1ns/1ps", "module tb;", "reg clk, reset, enable;",
             f"reg [{width - 1}:0] values;", "integer sample; integer checks;"]
    lines += instances(case, candidates)
    for field, bits in ports(case).items():
        lines.append(f"reg [{bits - 1}:0] previous_{field};")
    lines += ["task compare; begin", "checks = checks + 1;"]
    for index in range(len(candidates)):
        for field in ports(case):
            lines += [f"if (g_{field} !== c{index}_{field}) begin",
                      f'$display("{FAIL} sample=%0d candidate={index} field={field} native=%h candidate=%h", '
                      f"sample, g_{field}, c{index}_{field}); $finish; end"]
    lines += ["end endtask", "task save_state; begin"]
    for field in ports(case):
        lines.append(f"previous_{field} = g_{field};")
    lines += ["end endtask", "task held; begin"]
    if case["count"] > 1:
        for field in ports(case):
            lines += [f"if (g_{field} !== previous_{field}) begin",
                      f'$display("{FAIL} native-hold sample=%0d field={field}", sample); $finish; end']
    lines += ["end endtask", "task reset_values; begin"]
    if case["count"] > 1:
        reset_values = {"unsigned": 3, "signed": (1 << case["sw"]) - 2, "bitsValue": 1, "valid": 0}
        for field, value in reset_values.items():
            bits = ports(case)[field]
            lines += [f"if (g_{field} !== {bits}'h{value:x}) begin",
                      f'$display("{FAIL} native-reset sample=%0d field={field}", sample); $finish; end']
    lines += ["compare; end endtask", "task pulse_reset; begin",
              f"clk = 1'b{idle_clock}; enable = 1'b{idle_enable}; reset = 1'b{idle_reset}; #2; save_state;",
              f"reset = 1'b{active_reset}; #2;"]
    lines += ["reset_values;"] if case["asynchronous"] else ["if (sample >= 0) begin compare; held; end"]
    lines += [f"clk = 1'b{active_clock}; #2;"]
    # Native Spinal's SYNC reset is inside its domain clock-enable guard;
    # ASYNC assertion resets even while CE is disabled. Establish initial
    # state with an enabled reset, and explicitly exercise both priorities.
    lines += ["reset_values;"] if case["asynchronous"] else ["if (sample >= 0) begin compare; held; end"]
    lines += [f"clk = 1'b{idle_clock}; #2; enable = 1'b{active_enable}; #2;",
              f"clk = 1'b{active_clock}; #2; reset_values; save_state;",
              f"clk = 1'b{idle_clock}; #2; compare; held;",
              f"reset = 1'b{idle_reset}; #2; compare; held; end endtask",
              "initial begin", "sample = -1; checks = 0; values = 0;",
              f"clk = 1'b{idle_clock}; reset = 1'b{idle_reset}; enable = 1'b{idle_enable};",
              "pulse_reset;"]
    for index, value in enumerate(stimulus(case)):
        # First 56 cycles run uninterrupted for deterministic identity mutation.
        enabled = index < 56 or index % 11 not in (0, 1, 5, 9)
        lines += [f"sample = {index}; values = {width}'h{value:x}; enable = 1'b{active_enable if enabled else idle_enable};",
                  "#2; compare; save_state;", f"clk = 1'b{active_clock}; #2; compare;"]
        if not enabled:
            lines.append("held;")
        lines += ["save_state;", f"clk = 1'b{idle_clock}; #2; compare; held;"]
        if index in (97, 251):
            lines.append("pulse_reset;")
    lines += [f'$display("{PASS} checks=%0d", checks); $finish;', "end", "endmodule", ""]
    return "\n".join(lines)


def run(args: list[str], log: Path) -> str:
    try:
        result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                check=False, timeout=180)
    except (OSError, subprocess.TimeoutExpired) as error:
        log.write_text(str(error) + "\n")
        raise RuntimeError(f"tool did not complete: {log}") from error
    log.write_text(result.stdout)
    require(result.returncode == 0, f"tool exited {result.returncode}: {log}")
    return result.stdout


def quoted(path: Path) -> str:
    return '"' + str(path).replace("\\", "\\\\").replace('"', '\\"') + '"'


def formal_setup(paths: list[Path]) -> str:
    # async2sync is an active-edge model only. The unmodified emitted RTL also
    # runs strict lint/synthesis and native comparisons between clock edges.
    return ("read_verilog " + " ".join(quoted(path) for path in paths) +
            "\nhierarchy -check -top miter\nproc\nflatten\nopt_expr\nopt_clean\n"
            "async2sync\ndffunmap\ncheck -assert\n")


def require_counterexample(trace: Path) -> None:
    require(trace.is_file(), "formal mutation lacks a counterexample VCD")
    lines = trace.read_text().splitlines()
    identifiers = [words[3] for line in lines if len(words := line.split()) >= 6
                   and words[0] == "$var" and words[4].lstrip("\\") == "bad"]
    require(len(identifiers) == 1 and any(line.strip() in
            ("1" + identifiers[0], "b1 " + identifiers[0]) for line in lines),
            "formal mutation trace does not show bad=1")


def strict_tools_and_formal(root: Path, output: Path, case: dict, candidates: list[dict],
                            mutation: bool = False) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    top = output / "miter.v"
    top.write_text(miter(case, candidates))
    sources = [checked_file(root, entry["file"]) for entry in [case] + candidates] + [top]
    run(["verilator", "--lint-only", "--language", "1364-2001", "--top-module", "miter",
         *map(str, sources)], output / "lint.log")
    synthesis = output / "synthesis.ys"
    synthesis.write_text("read_verilog " + " ".join(quoted(path) for path in sources) +
                         "\nhierarchy -check -top miter\nsynth -top miter\ncheck -assert\nstat\n")
    run(["yosys", "-Q", "-T", "-s", str(synthesis)], output / "synthesis.log")
    result = {"lint": "pass", "synthesis": "pass", "formal": "not-selected"}
    # All width/count/profile combinations are simulated and tool-checked.
    # Formal selects one unequal-width shape across every count and profile;
    # reset at step 1 establishes all initialized state without assigning any
    # DUT registers. CE/reset/data are unconstrained after that reset edge.
    selected = tuple(case[k] for k in ("uw", "sw", "bw")) == (5, 7, 3)
    if selected or mutation:
        trace = output / "counterexample.vcd"
        trace.unlink(missing_ok=True)
        proof = output / "equivalence.ys"
        command = (f"sat -seq 18 -set-def-inputs -set-at 1 reset {int(not case['reset_low'])} "
                   f"-set-at 1 enable {int(not case['enable_low'])} "
                   "-prove bad 0 -prove-skip 1 -timeout 120 ")
        command += ("-show-inputs -show-outputs -dump_vcd " + quoted(trace)) if mutation else "-verify"
        proof.write_text(formal_setup(sources) + command + "\n")
        log = run(["yosys", "-Q", "-T", "-s", str(proof)], output / "formal.log")
        if mutation:
            require(SAT_FAIL in log and SAT_PASS not in log,
                    f"semantic mutant produced no formal mismatch: {output}")
            require_counterexample(trace)
            result["formal"] = "counterexample"
        else:
            require(SAT_PASS in log and SAT_FAIL not in log,
                    f"bounded native formal equivalence failed: {output}")
            result["formal"] = "pass-18-steps"
    return result


def simulate(root: Path, output: Path, case: dict, candidates: list[dict], mutation: bool) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    testbench = output / "tb.v"
    testbench.write_text(bench(case, candidates))
    sources = [checked_file(root, entry["file"]) for entry in [case] + candidates]
    executable = output / "tb.vvp"
    run(["iverilog", "-g2001", "-Wall", "-Wimplicit", "-s", "tb", "-o", str(executable),
         *map(str, sources), str(testbench)], output / "compile.log")
    log = run(["vvp", str(executable)], output / "simulate.log")
    if mutation:
        require(FAIL in log and PASS not in log and "candidate=0" in log,
                f"semantic mutation was not rejected by candidate/native comparison: {output}")
    else:
        require(log.count(PASS) == 1 and FAIL not in log, f"native equivalence failed: {output}")
    return {"id": case["id"], "candidates": [c["module"] for c in candidates],
            "result": "counterexample" if mutation else "pass", "log": str(output / "simulate.log"),
            "rtl_sha256": {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                           for path in sources},
            "testbench_sha256": hashlib.sha256(testbench.read_bytes()).hexdigest()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    root, output = args.artifacts.resolve(), args.output.resolve()
    # Never preserve a prior successful receipt if a rerun fails midway.
    (output / "receipt.json").unlink(missing_ok=True)
    manifest = json.loads((root / "manifest.json").read_text())
    validate(manifest)
    for tool in ("iverilog", "vvp", "verilator", "yosys"):
        require(shutil.which(tool) is not None, f"required simulator missing: {tool}")
    output.mkdir(parents=True, exist_ok=True)
    results = []
    for case in manifest["cases"]:
        candidates = [c for c in manifest["candidates"] if c["profile"] == case["profile"]]
        work = output / case["id"]
        result = simulate(root, work, case, candidates, False)
        result.update(strict_tools_and_formal(root, work, case, candidates))
        results.append(result)
    anchor = next(c for c in manifest["cases"] if c["profile"] == "sync_high_rising"
                  and tuple(c[k] for k in ("uw", "sw", "bw", "count")) == (5, 7, 3, 2))
    mutations = []
    for mutant in manifest["mutations"]:
        work = output / ("mutation-" + mutant["mutation"])
        result = simulate(root, work, anchor, [mutant], True)
        result.update(strict_tools_and_formal(root, work, anchor, [mutant], mutation=True))
        mutations.append(result)
    receipt = {"schema": 1, "scope": SCOPE, "result": "pass", "native_cases": len(results),
               "candidate_comparisons": 2 * len(results), "cycles_per_case": 384,
               "formal": {"kind": "bounded-active-edge-equivalence", "steps": 18,
                          "cases": sum(r["formal"] == "pass-18-steps" for r in results),
                          "initial_state": "unconstrained; enabled native reset at step 1",
                          "after_reset": "unconstrained input, reset, and global enable",
                          "async": "async2sync edge model; raw RTL asynchronous timing covered by simulation",
                          "unbounded_induction": "not-run"},
               "results": results, "mutations": mutations}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"{PASS}: {2 * len(results)} native comparisons; {len(mutations)} semantic mutations rejected")


if __name__ == "__main__":
    main()
