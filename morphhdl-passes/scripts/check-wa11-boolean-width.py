#!/usr/bin/env python3
"""Compile immutable production WA-11 artifacts and check every legal override.

Run after morph/Test/runMain nativeapplication.BooleanWidthNormalizationArtifactWriter.
The Verilog under test is read directly; only the independent testbench is written.
"""

from __future__ import annotations

import argparse
import hashlib
from itertools import product
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


CASES = {
    "direct": ("Wa11BooleanWidthDirect", "PPC4", {0: 1, 1: 4}),
    "true-default": ("Wa11BooleanWidthTrueDefault", "PPC4", {0: 1, 1: 4}),
    "negated": ("Wa11BooleanWidthNegated", "PPC4", {0: 4, 1: 1}),
    "frontend-negated": ("Wa11BooleanWidthFrontendNegated", "PPC4", {0: 4, 1: 1}),
    "repeated": ("Wa11BooleanWidthRepeated", "PPC4", {0: 1, 1: 4}),
    "compound": ("Wa11BooleanWidthCompound", "MODE", {0: 1, 1: 4, 2: 4, 3: 1}),
    "frontend-compound": ("Wa11BooleanWidthFrontendCompound", "MODE", {0: 1, 1: 4, 2: 4, 3: 1}),
    "zero-roundtrip": ("Wa11BooleanWidthZeroRoundtrip", "PPC4", {0: 1, 1: 4}),
    "integer-domain": ("Wa11BooleanWidthIntegerDomain", "INTEGER_VALUE", {0: 1, 1: 4, 2: 1, 3: 1}),
    "signed-arithmetic": ("Wa11BooleanWidthSignedArithmetic", "PPC4", {0: 1, 1: 4}),
    "signed-compare": ("Wa11BooleanWidthSignedCompare", "PPC4", {0: 4, 1: 4}),
    "signed-minimum": ("Wa11BooleanWidthSignedMinimum", "PPC4", {0: 1, 1: 4}),
    "signed-maximum": ("Wa11BooleanWidthSignedMaximum", "PPC4", {0: 1, 1: 4}),
    "integer-sizing": ("Wa11BooleanWidthIntegerSizing", "MODE", {0: 1, 1: 1, 2: 4, 3: 4}),
    "child": ("Wa11BooleanWidthChildTop", "PPC4", {0: 1, 1: 4}),
}

# Independently reproduced on the pre-WA-11 source: a frontend addressWidth
# predicate reaches the direct emitter as clog2 without its function definition.
# Keep the generated artifact and require that exact failure; this is never
# counted as a successful compilation or a simulated parameter case.
EXPECTED_UNSUPPORTED = {
    "frontend-address-helper": ("Wa11BooleanWidthFrontendAddressHelper", "DEPTH", {1: 1, 2: 1, 3: 4, 4: 4}),
}


def run(arguments: list[str], expected: str | None = None) -> str:
    result = subprocess.run(arguments, capture_output=True, text=True, check=False, timeout=60)
    if result.returncode or (expected is not None and expected not in result.stdout):
        raise RuntimeError(f"{arguments!r}\n{result.stdout}\n{result.stderr}")
    if "warning:" in result.stderr.lower():
        raise RuntimeError(f"unexpected compile warning for {arguments!r}:\n{result.stderr}")
    return result.stdout


def bench(module: str, parameter: str, widths: dict[int, int], child: bool) -> str:
    declarations = ["`timescale 1ns/1ps", "module tb;", "  integer pattern;"]
    checks = []
    for index, (value, width) in enumerate(widths.items()):
        declarations.extend([
            f"  reg [{width - 1}:0] data_{index};",
            f"  wire [{width - 1}:0] result_{index};",
            f"  {module} #(.{parameter}({value})) dut_{index} (.dataIn(data_{index}), .dataOut(result_{index}));",
        ])
        for signal in ["dataIn", "dataOut"] + (["child.dataIn", "child.dataOut"] if child else []):
            checks.append(f'    if ($bits(dut_{index}.{signal}) != {width}) begin $display("FAIL width {parameter}={value} {signal}"); $finish; end')
    declarations.append("  initial begin")
    declarations.extend(checks)
    declarations.append("    for (pattern = 0; pattern < 16; pattern = pattern + 1) begin")
    for index in range(len(widths)):
        declarations.append(f"      data_{index} = pattern;")
    declarations.append("      #1;")
    for index, (value, _) in enumerate(widths.items()):
        declarations.append(f'      if (result_{index} !== data_{index}) begin $display("FAIL data {parameter}={value}"); $finish; end')
    declarations.extend(["    end", '    $display("PASS WA11");', "    $finish;", "  end", "endmodule", ""])
    return "\n".join(declarations)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", type=Path)
    parser.add_argument("--reference-artifacts", type=Path,
                        help="optional same fixture emitted by the immutable pre-fix compiler")
    parser.add_argument("--reproduction", type=Path, help="actual user reproduction BooleanWidthExample.v")
    parser.add_argument("--reference-reproduction", type=Path, help="actual pre-fix user reproduction BooleanWidthExample.v")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    for tool in ("iverilog", "vvp"):
        if shutil.which(tool) is None:
            raise RuntimeError(f"required tool missing: {tool}; no simulation was performed")
    results = []
    known_rejections = []
    datasets = [("candidate", args.artifacts.resolve())]
    if args.reference_artifacts:
        datasets.append(("reference", args.reference_artifacts.resolve()))
    with tempfile.TemporaryDirectory(prefix="wa11-boolean-width-") as temporary:
        temporary = Path(temporary)
        for (dataset, root), passes in product(datasets, ("default", "disabled")):
            for name, (module, parameter, widths) in {**CASES, **EXPECTED_UNSUPPORTED}.items():
                path = root / "first" / passes / name / "generated.v"
                repeated = root / "repeat" / passes / name / "generated.v"
                rtl = path.read_bytes()
                if rtl != repeated.read_bytes():
                    raise RuntimeError(f"nondeterministic generated artifact: {path}")
                if passes == "disabled" and rtl != (root / "first" / "default" / name / "generated.v").read_bytes():
                    raise RuntimeError(f"canonical width normalization depends on hardware pass enable: {path}")
                source = rtl.decode("utf-8")
                if dataset == "candidate" and name in ("direct", "true-default", "repeated", "child") and "?" in source:
                    raise RuntimeError(f"redundant Boolean encoding in direct width/binding: {path}")
                testbench = temporary / "tb.v"
                executable = temporary / "tb.out"
                testbench.write_text(bench(module, parameter, widths, name == "child"), encoding="utf-8")
                command = ["iverilog", "-g2001", "-s", "tb", "-o", str(executable), str(path), str(testbench)]
                if name in EXPECTED_UNSUPPORTED:
                    failure = subprocess.run(command, capture_output=True, text=True, check=False, timeout=60)
                    missing_function = "No function named `clog2' found in this context"
                    errors = [line.split("error: ", 1)[1] for line in failure.stderr.splitlines() if "error: " in line]
                    if (failure.returncode == 0 or missing_function not in failure.stderr or not errors or
                            any(missing_function not in error and error != "Dimensions must be constant." for error in errors)):
                        raise RuntimeError(f"expected only the preexisting undefined clog2 failure: {path}\n{failure.stdout}\n{failure.stderr}")
                    known_rejections.append({
                        "dataset": dataset,
                        "artifact": str(path.relative_to(root)),
                        "sha256": hashlib.sha256(rtl).hexdigest(),
                        "diagnostic_category": "undefined-portable-clog2-function",
                        "diagnostic": missing_function,
                        "compiler_exit_code": failure.returncode,
                        "error_count": len(errors),
                        "deterministic": True,
                        "simulated": False,
                    })
                    continue
                run(command)
                run(["vvp", str(executable)], expected="PASS WA11")
                results.append({
                    "dataset": dataset,
                    "artifact": str(path.relative_to(root)),
                    "sha256": hashlib.sha256(rtl).hexdigest(),
                    "overrides": {str(key): value for key, value in widths.items()},
                    "patterns_per_override": 16,
                    "deterministic": True,
                    "iverilog_g2001": "passed",
                    "port_widths_and_data": "passed",
                })
        reproductions = [("candidate-reproduction", args.reproduction),
                         ("reference-reproduction", args.reference_reproduction)]
        for dataset, path in reproductions:
            if path is None:
                continue
            path = path.resolve()
            rtl = path.read_bytes()
            if dataset == "candidate-reproduction" and "?" in rtl.decode("utf-8"):
                raise RuntimeError(f"redundant Boolean encoding in user reproduction: {path}")
            widths = {0: 1, 1: 4}
            testbench = temporary / "tb.v"
            executable = temporary / "tb.out"
            testbench.write_text(bench("BooleanWidthExample", "PPC4", widths, False), encoding="utf-8")
            run(["iverilog", "-g2001", "-s", "tb", "-o", str(executable), str(path), str(testbench)])
            run(["vvp", str(executable)], expected="PASS WA11")
            results.append({
                "dataset": dataset,
                "artifact": str(path),
                "sha256": hashlib.sha256(rtl).hexdigest(),
                "overrides": {str(key): value for key, value in widths.items()},
                "patterns_per_override": 16,
                "iverilog_g2001": "passed",
                "port_widths_and_data": "passed",
            })
    report = {
        "check": "WA-11 Boolean parameter width production artifacts",
        "artifacts": len(results),
        "parameter_overrides": sum(len(result["overrides"]) for result in results),
        "pattern_checks": sum(len(result["overrides"]) * 16 for result in results),
        "results": results,
        "known_preexisting_rejections": known_rejections,
        "preexisting_rejections_checked_against_reference": args.reference_artifacts is not None,
    }
    serialized = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(serialized, encoding="utf-8")
    print(serialized)


if __name__ == "__main__":
    main()
