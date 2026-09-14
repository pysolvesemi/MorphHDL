#!/usr/bin/env python3
"""Native integration regression driver; never synthesizes or edits DUT Verilog.

Run in a real MorphHDL checkout at repro/independent-parameters/check.py.
Missing tools are BLOCKED, never PASS. Default execution uses SBT; an explicit
precompiled classpath can be used for offline native verification.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shlex
import shutil
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence
from uuid import uuid4


@dataclass(frozen=True)
class Case:
    values: tuple[int, ...]
    defaults: bool = False


@dataclass(frozen=True)
class Fixture:
    module: str
    main: str
    parameters: tuple[str, ...]
    cases: tuple[Case, ...]
    metadata_bits: int = 0
    scenario: str | None = None
    child_module: str | None = None
    projected: bool = False

    def width(self, case: Case) -> int:
        if len(case.values) != len(self.parameters):
            raise ValueError("case arity does not match the parameter schema")
        if any(value <= 0 for value in case.values):
            raise ValueError("positive parameter values are required")
        return sum(case.values) + self.metadata_bits


WIDTH = Fixture(
    module="RecordWidth",
    main="repro.IndependentParameterRepro",
    parameters=("DATA_BITS", "GENERATION_BITS"),
    cases=tuple(Case(pair) for pair in (
        (32, 32), (1, 2), (1, 64), (2048, 2), (2048, 64),
        (120, 8), (17, 32),
    )) + (Case((32, 32), defaults=True),),
)
RECORD = Fixture(
    module="IndependentRecord",
    main="repro.IndependentRecordRepro",
    parameters=("DATA_BITS", "GENERATION_BITS", "LANES"),
    cases=tuple(Case(triple) for triple in (
        (32, 32, 4), (1, 2, 1), (1, 64, 16), (2048, 2, 1),
        (2048, 64, 16), (120, 8, 3), (17, 32, 2),
    )) + (Case((32, 32, 4), defaults=True),),
    metadata_bits=7 + 16 + 1,
)


PARENT = Fixture("IndependentParent", "repro.IndependentAdvancedRepro", WIDTH.parameters,
                 WIDTH.cases, scenario="parent", child_module="IndependentChild")
PROJECTION = Fixture("IndependentProjection", "repro.IndependentAdvancedRepro", WIDTH.parameters,
    WIDTH.cases[:-1] + (Case((1,32), defaults=True),), scenario="projection", projected=True)
ALWAYS = Fixture("IndependentLegality", "repro.IndependentAdvancedRepro", ("DATA_BITS", "LIVE_LANES"),
    tuple(Case((a,b)) for a in range(1,9) for b in range(1,5)) + (Case((2,2), defaults=True),), scenario="always")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require_identical(first: Path, second: Path) -> str:
    left, right = first.read_bytes(), second.read_bytes()
    if not left:
        raise RuntimeError(f"empty Verilog file: {first}")
    if left != right:
        raise RuntimeError(f"published Verilog differs byte-for-byte: {first} / {second}")
    return hashlib.sha256(left).hexdigest()


def require_unchanged(path: Path, expected: str) -> None:
    if digest(path) != expected:
        raise RuntimeError(f"published DUT Verilog changed during checking: {path}")


def require_single_module(path: Path, module: str) -> None:
    require_modules(path, (module,))


def require_modules(path: Path, modules: tuple[str, ...]) -> None:
    # This is a fixture-level structural guard, not a Verilog parser. The real
    # iverilog -g2001 step below is the syntax/compilation gate.
    text = path.read_text(encoding="utf-8")
    # Strip quoted diagnostics and comments as tokens, so a diagnostic saying
    # "module Foo" or a path containing // is not a second declaration.
    text = re.sub(r'"(?:\\.|[^"\\])*"|/\*[\s\S]*?\*/|//[^\n]*',
                  lambda match: "\n" * match.group(0).count("\n"), text)
    names = re.findall(r"\bmodule\s+([A-Za-z_][A-Za-z_0-9$]*)\b", text)
    if sorted(names) != sorted(modules):
        raise RuntimeError(f"expected exactly modules {modules}, found {names}")


def fixture_ports(path: Path, module: str | None = None) -> tuple[str, str]:
    """Resolve the two fixture ports as published; never rename the DUT.

    Native naming can suffix a Scala field (for example record_1). Reject
    ambiguity rather than guessing. This only parses these simple fixtures;
    Icarus performs the actual language validation.
    """
    text = path.read_text(encoding="utf-8")
    if module is not None:
        headers = re.findall(rf"\bmodule\s+{re.escape(module)}\b([\s\S]*?);", text)
        if len(headers) != 1:
            raise RuntimeError(f"expected exactly one header for {module}")
        text = headers[0] + ");"
    found = []
    for direction in ("input", "output"):
        pattern = (rf"\b{direction}\s+(?:(?:wire|reg)\s+)?(?:signed\s+)?"
                   r"(?:\[[^\]\n]+\]\s*)?([A-Za-z_][A-Za-z_0-9$]*)\s*[,)]")
        names = re.findall(pattern, text)
        if len(names) != 1:
            raise RuntimeError(f"expected one {direction} fixture port, found {names}")
        found.append(names[0])
    return found[0], found[1]


def make_testbench(fixture: Fixture, input_port: str = "record",
                   output_port: str = "observed") -> str:
    """All cases instantiate one and the same published module/file."""
    if not fixture.cases:
        raise ValueError("at least one test case is required")
    lines = [
        "// TESTBENCH ONLY. DUT Verilog must come from MorphVerilog, unchanged.",
        "`timescale 1ns/1ps",
        "module tb;",
        f"  reg [{len(fixture.cases)-1}:0] done;",
    ]
    for index, case in enumerate(fixture.cases):
        width = fixture.width(case)
        overrides = ", ".join(
            f".{name}({value})" for name, value in zip(fixture.parameters, case.values)
        )
        specialization = "" if case.defaults else f" #({overrides})"
        lines.extend([
            f"  localparam integer WIDTH_{index} = {width};",
            f"  reg [WIDTH_{index}-1:0] record_{index};",
            f"  wire observed_{index};",
            f"  integer bit_{index};",
            f"  {fixture.module}{specialization} dut_{index} (",
            f"    .{input_port}(record_{index}), .{output_port}(observed_{index})",
            "  );",
            f"  initial begin : case_{index}",
            f"    done[{index}] = 1'b0;",
            f"    record_{index} = {{WIDTH_{index}{{1'b0}}}};",
            "    #1;",
            # Hierarchical $bits measures the actual DUT port, not TB stimulus.
            f"    if ($bits(dut_{index}.{input_port}) != WIDTH_{index})",
            f'      $fatal(1, "CASE {index}: DUT port width is %0d; expected %0d",',
            f"             $bits(dut_{index}.{input_port}), WIDTH_{index});",
        ])
        for name, value in zip(fixture.parameters, case.values):
            lines.extend([
                f"    if (dut_{index}.{name} !== {value})",
                f'      $fatal(1, "CASE {index}: parameter {name} mismatch");',
            ])
        if fixture.child_module:
            lines.extend([
                f"    if ($bits(dut_{index}.child.din) != WIDTH_{index})",
                f'      $fatal(1, "CASE {index}: child width mismatch");',
            ])
            for name, value in zip(fixture.parameters, case.values):
                lines.extend([
                    f"    if (dut_{index}.child.{name} !== {value})",
                    f'      $fatal(1, "CASE {index}: child parameter {name} mismatch");',
                ])
        expected_bit = "1'b1"
        if fixture.projected and case.values[0] > 1:
            expected_bit = f"((bit_{index} < WIDTH_{index}-1) ? 1'b1 : 1'b0)"
        lines.extend([
            f"    if (observed_{index} !== 1'b0)",
            f'      $fatal(1, "CASE {index}: zero input did not reduce to zero");',
            f"    for (bit_{index}=0; bit_{index}<WIDTH_{index}; bit_{index}=bit_{index}+1) begin",
            f"      record_{index} = {{WIDTH_{index}{{1'b0}}}};",
            f"      record_{index}[bit_{index}] = 1'b1;",
            "      #1;",
            f"      if (dut_{index}.{input_port} !== record_{index})",
            f'        $fatal(1, "CASE {index}: truncated/changed input at bit %0d", bit_{index});',
            f"      if (observed_{index} !== {expected_bit})",
            f'        $fatal(1, "CASE {index}: incorrect one-hot reduction at bit %0d", bit_{index});',
            f"      record_{index} = {{WIDTH_{index}{{1'b0}}}};",
            "      #1;",
            f"      if (observed_{index} !== 1'b0)",
            f'        $fatal(1, "CASE {index}: failed return to zero after bit %0d", bit_{index});',
            "    end",
            f'    $display("CASE_PASS {index} width=%0d all_bits_checked=%0d", WIDTH_{index}, WIDTH_{index});',
            f"    done[{index}] = 1'b1;",
            "  end",
        ])
    lines.extend([
        "  initial begin",
        "    wait (&done);",
        "    #1;",
        f'    $display("NATIVE_MATRIX_PASS {fixture.module} cases={len(fixture.cases)}");',
        "    $finish;",
        "  end",
        "  initial begin",
        "    #1000000;",
        '    $fatal(1, "simulation watchdog expired");',
        "  end",
        "endmodule",
        "",
    ])
    return "\n".join(lines)


def execute(command: Sequence[str], cwd: Path, log: Path,
            timeout: int, commands: list[dict[str, Any]],
            expected_diagnostic: str | None = None) -> str:
    record: dict[str, Any] = {
        "argv": list(command), "cwd": str(cwd), "log": str(log),
    }
    commands.append(record)
    print("+ " + shlex.join(command), flush=True)
    try:
        result = subprocess.run(
            list(command), cwd=cwd, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
            timeout=timeout, check=False,
        )
    except subprocess.TimeoutExpired as error:
        record["status"] = "timeout"
        output = error.stdout or b""
        if isinstance(output, bytes):
            output = output.decode("utf-8", errors="replace")
        log.write_text(output, encoding="utf-8")
        raise RuntimeError(f"command timed out; see {log}") from error
    except OSError as error:
        record["status"] = "could_not_start"
        record["error"] = str(error)
        raise RuntimeError(f"could not start command: {error}") from error
    log.write_text(result.stdout, encoding="utf-8")
    record["exit_code"] = result.returncode
    record["status"] = "passed" if result.returncode == 0 else "failed"
    if expected_diagnostic is not None:
        if result.returncode == 0 or expected_diagnostic not in result.stdout:
            raise RuntimeError(f"expected failed generation with {expected_diagnostic}; see {log}")
        record["status"] = "passed_expected_rejection"
        record["expected_diagnostic"] = expected_diagnostic
        return result.stdout
    if result.returncode:
        raise RuntimeError(f"command exited {result.returncode}; see {log}")
    return result.stdout


def sbt_main(main: str, output: Path) -> str:
    escaped = str(output.resolve()).replace("\\", "\\\\").replace('"', '\\"')
    return f'runMain {main} "{escaped}"'


def run_fixture(fixture: Fixture, here: Path, results: Path,
                timeout: int, commands: list[dict[str, Any]],
                java_classpath: str | None = None) -> dict[str, Any]:
    root = results / fixture.module
    root.mkdir()
    outputs = [root / "first", root / "second"]
    for number, output in enumerate(outputs, start=1):
        # A distinct SBT invocation means a fresh forked generator JVM.
        main_arguments = ([fixture.scenario] if fixture.scenario else []) + [str(output)]
        command = (["java", "-Xmx4g", "-XX:ActiveProcessorCount=2", "-cp", java_classpath,
                    fixture.main] + main_arguments if java_classpath else
                   ["sbt", "-batch", sbt_main(fixture.main + (" " + fixture.scenario if fixture.scenario else ""), output)])
        execute(command, here, root / f"generation-{number}.log", timeout, commands)
    first, second = (output / f"{fixture.scenario or fixture.module}.v" for output in outputs)
    sha = require_identical(first, second)
    require_modules(first, (fixture.module,) + ((fixture.child_module,) if fixture.child_module else ()))
    execute(["iverilog", "-g2001", "-s", fixture.module, "-o",
             str(root / "strict.vvp"), str(first)], here,
            root / "strict-verilog-2001.log", timeout, commands)
    bench = root / "tb.sv"
    bench.write_text(make_testbench(fixture, *fixture_ports(first, fixture.module)), encoding="utf-8")
    executable = root / "matrix.vvp"
    execute(["iverilog", "-g2012", "-s", "tb", "-o", str(executable),
             str(first), str(bench)], here,
            root / "matrix-compile.log", timeout, commands)
    output = execute(["vvp", str(executable)], here,
                     root / "matrix-simulation.log", timeout, commands)
    marker = f"NATIVE_MATRIX_PASS {fixture.module} cases={len(fixture.cases)}"
    if marker not in output:
        raise RuntimeError(f"simulation exited without required success marker: {marker}")
    for index, case in enumerate(fixture.cases):
        width = fixture.width(case)
        if f"CASE_PASS {index} width={width} all_bits_checked={width}" not in output:
            raise RuntimeError(f"case {index} did not report full-width coverage")
    require_unchanged(first, sha)
    require_unchanged(second, sha)
    return {
        "module": fixture.module,
        "status": "passed",
        "generated_source": str(first),
        "sha256": sha,
        "byte_identical_generations": True,
        "strict_verilog_2001": True,
        "child_bindings_checked": fixture.child_module is not None,
        "branch_projection_checked": fixture.projected,
        "one_hot_positions_checked": sum(fixture.width(case) for case in fixture.cases),
        "cases": [{"parameters": dict(zip(fixture.parameters, case.values)),
                   "width": fixture.width(case), "default_instance": case.defaults}
                  for case in fixture.cases],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preflight-only", action="store_true")
    parser.add_argument("--java-classpath", help="explicit precompiled fixture/compiler classpath; no SBT invocation")
    parser.add_argument("--emit-testbenches-only", action="store_true")
    parser.add_argument("--width-only", action="store_true",
                        help="run only the exact two-parameter reproducer")
    parser.add_argument("--command-timeout-seconds", type=int, default=1800)
    args = parser.parse_args()
    if args.command_timeout_seconds <= 0:
        parser.error("--command-timeout-seconds must be positive")
    here = Path(__file__).resolve().parent
    repository = here.parents[1]
    label = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid4().hex[:8]
    results = here / "results" / label
    results.mkdir(parents=True)
    summary: dict[str, Any] = {
        "status": "not_run", "results": str(results),
        "commands": [], "fixtures": [],
        "scope": "new regression fixtures only; not the existing native safety/test matrix",
        "generation_mode": "precompiled-java" if args.java_classpath else "sbt",
    }
    try:
        if args.emit_testbenches_only:
            for fixture in (WIDTH, RECORD):
                (results / f"{fixture.module}_tb.sv").write_text(
                    make_testbench(fixture), encoding="utf-8")
            summary["status"] = "testbenches_prepared_not_executed"
            return 0
        missing = [tool for tool in ("git", "java" if args.java_classpath else "sbt", "iverilog", "vvp") if shutil.which(tool) is None]
        required = ("build.sbt", "core", "frontend", "morphhdl")
        absent = [name for name in required if not (repository / name).exists()]
        summary["missing_tools"] = missing
        summary["missing_repository_entries"] = absent
        if missing or absent:
            summary["status"] = "blocked"
            print("BLOCKED: " + json.dumps({"missing_tools": missing,
                  "missing_repository_entries": absent}), file=sys.stderr)
            return 2
        head = execute(["git", "rev-parse", "HEAD"], repository,
                       results / "head.log", 30, summary["commands"]).strip()
        dirty = execute(["git", "status", "--porcelain"], repository,
                        results / "worktree.log", 30, summary["commands"])
        summary["checkout_head"] = head
        summary["worktree_status"] = dirty
        summary["clean_worktree"] = not bool(dirty.strip())
        if args.preflight_only:
            summary["status"] = "preflight_passed_native_tests_not_run"
            return 0
        fixtures = (WIDTH,) if args.width_only else (WIDTH, RECORD, PARENT, PROJECTION, ALWAYS)
        for fixture in fixtures:
            summary["fixtures"].append(run_fixture(
                fixture, here, results, args.command_timeout_seconds, summary["commands"], args.java_classpath))
        if not args.width_only:
            output = results / "typed-domain-checks"
            command = (["java", "-Xmx4g", "-XX:ActiveProcessorCount=2", "-cp", args.java_classpath,
                        "repro.IndependentDomainChecks", str(output)] if args.java_classpath else
                       ["sbt", "-batch", sbt_main("repro.IndependentDomainChecks", output)])
            execute(command,
                    here, results / "typed-domain-checks.log", args.command_timeout_seconds,
                    summary["commands"])
            domain_source = output / "IndependentDomainChecks.v"
            require_single_module(domain_source, "IndependentDomainChecks")
            execute(["iverilog", "-g2001", "-s", "IndependentDomainChecks", "-o",
                     str(results / "typed-domain-checks.vvp"), str(domain_source)],
                    here, results / "typed-domain-verilog-2001.log",
                    args.command_timeout_seconds, summary["commands"])
            summary["typed_domain_fixture"] = "elaboration_and_verilog_compile_passed"
            for scenario, diagnostic in (
                ("never", "SPINAL-ELAB-REQUIRE-ALWAYS-FALSE"),
                ("mixed", "SPINAL-ELAB-REQUIRE-DOMAIN-UNPROVEN"),
                ("joint-branch", "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING"),
            ):
                rejected_output = results / scenario
                command = (["java", "-Xmx4g", "-XX:ActiveProcessorCount=2", "-cp", args.java_classpath,
                            "repro.IndependentAdvancedRepro", scenario, str(rejected_output)] if args.java_classpath else
                           ["sbt", "-batch", sbt_main("repro.IndependentAdvancedRepro " + scenario, rejected_output)])
                execute(command, here, results / (scenario + ".log"), args.command_timeout_seconds,
                        summary["commands"], expected_diagnostic=diagnostic)
                if list(rejected_output.glob("*.v")):
                    raise RuntimeError(f"failed {scenario} generation published Verilog")
            summary["negative_native_boundaries"] = "always-false, mixed-validity, unsupported joint structural branch rejected"
        summary["status"] = "new_fixture_checks_passed_NOT_full_compiler_qualification"
        return 0
    except (RuntimeError, OSError, UnicodeError) as error:
        summary["status"] = "failed"
        summary["error"] = str(error)
        print("FAIL: " + str(error), file=sys.stderr)
        return 1
    finally:
        (results / "summary.json").write_text(
            json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        print(f"Evidence: {results / 'summary.json'}", flush=True)


if __name__ == "__main__":
    raise SystemExit(main())
