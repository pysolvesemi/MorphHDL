#!/usr/bin/env python3
"""Validate the *published* WA-10 MorphVerilog artifacts, without rewriting HDL.

The simulation builds each original artifact separately against an independent
width-explicit oracle, then compares deterministic four-state output traces.
Yosys performs its module renaming on parsed designs, not generated source text.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


FIXTURES = {
    "timing": ("TimingExpressionExample", "timing_expression_tb.v", "WA10_TIMING_PASS"),
    "general": ("GeneralExpressionInlining", "general_expression_tb.v", "WA10_GENERAL_PASS"),
}
MODES = ("default", "enabled", "disabled")
EXPECTED_SAMPLES = {"timing": 848, "general": 784}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def compact(value):
    return re.sub(r"\s+", "", value)


def assignments(text):
    return dict(re.findall(r"\bassign\s+(\w+)\s*=\s*([^;]+);", text))


def inventory(text):
    rows = assignments(text)
    declarations = re.findall(r"^\s*(?:\(\*.*?\*\)\s*)?(wire|reg)\s+.*?\b(\w+)\s*;", text, re.M)
    return {
        "sha256": hashlib.sha256(text.encode()).hexdigest(),
        "continuous_assignments": len(rows),
        "internal_declarations": len(declarations),
        "registers": [name for kind, name in declarations if kind == "reg"],
        "assignments": rows,
    }


def structure(fixture, before, after):
    old, new = assignments(before), assignments(after)
    require(before.split("\n);", 1)[0] == after.split("\n);", 1)[0],
            f"{fixture}: port or native parameter interface changed")
    require("parameter integer PPC4 = 0" in after, f"{fixture}: PPC4 was specialized away")
    require(len(new) < len(old), f"{fixture}: final emitted assignments did not decrease")
    if fixture == "general":
        expected = {
            "constantOK": "16'h0001",
            "constantMux": "16'h0001",
            "extensionOK": "{2'd0,word}",
            "extensionSum": "{2'd0,word}",
            "subtractionOK": "(total-13'h0001)",
            "subtractionMux": "(total-13'h0001)",
        }
        for receiver, expression in expected.items():
            require(expression in compact(new[receiver]),
                    f"{fixture}: eligible expression not in final {receiver}: {new[receiver]}")
        # Find the actual old carriers by RHS expression, not identifier shape.
        for expression in ("16'h0001", "{2'd0,word}"):
            carriers = [name for name, rhs in old.items() if compact(rhs) == expression]
            require(carriers, f"{fixture}: disabled source no longer reproduces {expression}")
            for name in carriers:
                require(re.search(r"\b" + re.escape(name) + r"\b", after) is None,
                        f"{fixture}: unused carrier declaration/reference remains: {name}")
        # The same subtraction also intentionally feeds protected and selected
        # controls. Identify the eligible old carrier from its actual receiver,
        # rather than insisting every identical subtraction driver disappears.
        subtraction_carrier = re.fullmatch(r"\(\s*index\s*==\s*(\w+)\s*\)", old["subtractionOK"])
        require(subtraction_carrier is not None, "disabled subtraction no longer reproduces a shared carrier")
        subtraction_name = subtraction_carrier.group(1)
        require(compact(old[subtraction_name]) == "(total-13'h0001)",
                "disabled subtraction carrier has an unexpected source")
        require(re.search(r"\b" + re.escape(subtraction_name) + r"\b", after) is None,
                "eligible subtraction carrier still has a declaration, driver, or reference")
        require(re.search(r"\(\* keep \*\).*\bprotectedDifference\s*;", after),
                "keep attribute/declaration was removed")
        for retained in ("protectedDifference", "vitalDifference"):
            require(retained in new, f"protected expression {retained} was eliminated")
        require(re.search(r"\breg\s+\[11:0\]\s+state\s*;", after), "state register disappeared")
        require("if(load)" in compact(after), "conditional register scope disappeared")
    else:
        rhs = compact(new["io_finalGroup"])
        for expression in ("(io_hTotal-13'h0001)", "(io_vTotal-12'h001)"):
            require(expression in rhs, f"timing: same-width subtraction still wrapped: {rhs}")
        selected = re.search(r"vSyncEnd\s*<=\s*(\w+)\[11:0\]", after)
        require(selected is not None, "timing: required final truncation fence missing")
        receiver = selected.group(1)
        require(receiver in new, "timing: truncation selects an undriven carrier")
        for signal in ("io_vActive", "io_vFront", "io_vSync"):
            require("{2'd0," + signal + "}" in compact(new[receiver]),
                    f"timing: avoidable extension/add wrapper remains below {receiver}: {new[receiver]}")
        require(re.search(r"\breg\s+\[11:0\]\s+vSyncEnd\s*;", after), "vSyncEnd register disappeared")
        require("if(io_cfgLoad)" in compact(after), "timing: cfgLoad scope disappeared")


def run(argv, log, *, expect_failure=False):
    log.parent.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(list(map(str, argv)), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    log.write_text(result.stdout)
    command_log = log.with_suffix(log.suffix + ".command.json")
    command_log.write_text(json.dumps(list(map(str, argv)), indent=2) + "\n")
    if expect_failure:
        require(result.returncode != 0 and "mutation detected" in result.stdout,
                f"Mutation gate did not reject deliberately wrong underflow oracle: {log}")
    else:
        require(result.returncode == 0, f"Command failed ({result.returncode}): {log}\n{result.stdout[-2500:]}")
    return result.stdout


def proof_script(module, before, after, ppc4):
    # Generated internal names are not correspondence evidence. Hide internal
    # names in each parsed design so equiv_make matches ports, not unrelated
    # temporaries that happen to receive the same backend spelling.
    return f"""read_verilog {before}
chparam -set PPC4 {ppc4} {module}
rename {module} gold
proc
memory
opt_clean
async2sync
rename -hide
design -stash reference
read_verilog {after}
chparam -set PPC4 {ppc4} {module}
rename {module} gate
proc
memory
opt_clean
async2sync
rename -hide
design -copy-from reference -as gold gold
equiv_make gold gate equiv
hierarchy -check -top equiv
equiv_simple -seq 5
equiv_induct -undef -seq 5
equiv_status -assert
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", type=Path, help="contains timing/ and general/ artifact writer results")
    parser.add_argument("--baseline", type=Path, help="optional pristine baseline to check exact disabled legacy bytes")
    parser.add_argument("--semantics-only", action="store_true", help="validate a pristine baseline before implementing structural optimization")
    args = parser.parse_args()
    artifact_root = args.artifacts.resolve()
    repo = Path(__file__).resolve().parents[2]
    test_root = repo / "morphhdl-passes/tests/wa10"
    checks = artifact_root / "checks"
    checks.mkdir(parents=True, exist_ok=True)
    result = {"schema_version": 1, "status": "running", "structure_checked": not args.semantics_only,
              "fixtures": {}, "legacy_baseline_checked": args.baseline is not None}
    result_path = checks / "results.json"
    result_path.write_text(json.dumps(result, indent=2) + "\n")
    for executable in ("iverilog", "vvp", "yosys"):
        version_flag = "-V" if executable != "yosys" else "-V"
        run([executable, version_flag], checks / (executable + "-version.log"))

    for fixture, (module, testbench, pass_marker) in FIXTURES.items():
        texts, files = {}, {}
        entry = {"modes": {}, "parameters": {}, "deterministic": False}
        result["fixtures"][fixture] = entry
        for mode in MODES:
            generated = artifact_root / fixture / "first" / mode / (module + ".v")
            repeated = artifact_root / fixture / "repeat" / mode / (module + ".v")
            require(generated.is_file() and repeated.is_file(), f"Missing generated artifact: {generated} or {repeated}")
            require(generated.read_bytes() == repeated.read_bytes(), f"Nondeterministic {fixture} {mode}")
            texts[mode], files[mode] = generated.read_text(), generated
            entry["modes"][mode] = inventory(texts[mode])
        require(texts["default"] == texts["enabled"], f"{fixture}: default and explicit enabled diverged")
        if args.baseline:
            previous = args.baseline.resolve() / fixture / "first/disabled" / (module + ".v")
            require(previous.read_bytes() == files["disabled"].read_bytes(),
                    f"{fixture}: explicitly disabled output differs from pristine legacy baseline")
        if not args.semantics_only:
            structure(fixture, texts["disabled"], texts["enabled"])
        entry["deterministic"] = True
        diagnostics = artifact_root / "diagnostics"
        if diagnostics.exists():
            diagnostic_verilog = diagnostics / fixture / "diagnostic.v"
            diagnostic_report = diagnostics / fixture / "native.tsv"
            require(diagnostic_verilog.read_bytes() == files["enabled"].read_bytes(),
                    f"{fixture}: observer pipeline emitted different or stale Verilog")
            require("pre-emission" in diagnostic_report.read_text(),
                    f"{fixture}: incomplete native-boundary diagnostic report")
            entry["diagnostic_bytes_equal_production_enabled"] = True

        for ppc4 in (0, 1):
            parameter_dir = checks / fixture / ("PPC4-" + str(ppc4))
            parameter_dir.mkdir(parents=True, exist_ok=True)
            traces = {}
            for mode in MODES:
                output = parameter_dir / mode
                output.mkdir(parents=True, exist_ok=True)
                vvp = output / "simulation.vvp"
                run(["iverilog", "-g2001", "-s", module + "Tb", "-P" + module + "Tb.PPC4=" + str(ppc4),
                     "-o", vvp, files[mode], test_root / testbench], output / "compile.log")
                log = run(["vvp", vvp], output / "simulation.log")
                require(pass_marker + " PPC4=" + str(ppc4) in log and "WA10_FAIL" not in log,
                        f"Missing successful simulation marker: {output}")
                traces[mode] = "\n".join(line for line in log.splitlines() if line.startswith("TRACE ")) + "\n"
                require(traces[mode].count("\n") == EXPECTED_SAMPLES[fixture],
                        f"{fixture} {mode}: incomplete directed/random/four-state test execution")
                (output / "trace.txt").write_text(traces[mode])
            require(traces["disabled"] == traces["enabled"] == traces["default"],
                    f"{fixture} PPC4={ppc4}: four-state simulation outputs differ")

            mutation = parameter_dir / "mutation.vvp"
            run(["iverilog", "-g2001", "-DWA10_MUTATE_REFERENCE", "-s", module + "Tb",
                 "-P" + module + "Tb.PPC4=" + str(ppc4), "-o", mutation,
                 files["enabled"], test_root / testbench], parameter_dir / "mutation-compile.log")
            run(["vvp", mutation], parameter_dir / "mutation-rejection.log", expect_failure=True)

            formal = parameter_dir / "equivalence.ys"
            formal.write_text(proof_script(module, files["disabled"], files["enabled"], ppc4))
            formal_log = run(["yosys", "-Q", "-s", formal], parameter_dir / "formal.log")
            require("Equivalence successfully proven!" in formal_log, f"No completed equivalence proof: {formal}")
            entry["parameters"][str(ppc4)] = {
                "iverilog_verilog_2001": "pass", "four_state_oracle": "pass",
                "four_state_trace_equivalence": "pass", "trace_samples": traces["enabled"].count("\n"),
                "underflow_oracle_mutation_rejected": True, "yosys_sequential_equivalence": "pass",
            }
            result_path.write_text(json.dumps(result, indent=2) + "\n")

    result["status"] = "pass"
    result_path.write_text(json.dumps(result, indent=2) + "\n")
    print("WA10_ARTIFACT_VALIDATION_PASS: deterministic public artifacts; PPC4=0/1; width-explicit numeric and four-state simulation; sequential equivalence; mutation gates")
    print(result_path)


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, FileNotFoundError) as error:
        print("WA10_ARTIFACT_VALIDATION_FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
