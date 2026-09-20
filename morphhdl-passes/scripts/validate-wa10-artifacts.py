#!/usr/bin/env python3
"""Validate the *published* WA-10 MorphVerilog artifacts, without rewriting HDL.

The simulation builds each original artifact separately against an independent
width-explicit oracle, then compares deterministic four-state output traces.
Yosys performs its module renaming on parsed designs, not generated source text.
"""

import argparse
import ast
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


def without_comments(text):
    return re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)


def constant_range(value):
    """Read emitted constant geometry without evaluating arbitrary Python/HDL."""
    def integer(node):
        if isinstance(node, ast.Constant) and type(node.value) is int:
            return node.value
        if isinstance(node, ast.BinOp) and isinstance(node.op, (ast.Add, ast.Sub)):
            left, right = integer(node.left), integer(node.right)
            return left + right if isinstance(node.op, ast.Add) else left - right
        raise AssertionError("nonconstant or unsupported truncation geometry: " + value)
    parts = value.split(":")
    require(len(parts) == 2, "invalid truncation range: " + value)
    try:
        high, low = [integer(ast.parse(part.strip(), mode="eval").body) for part in parts]
    except SyntaxError as error:
        raise AssertionError("invalid truncation geometry: " + value) from error
    require(high >= low >= 0, "invalid truncation bounds: " + value)
    return high, low


def unsigned_declaration(text, name, kind):
    # Function-local inputs and commented declarations are not module carriers.
    module = re.sub(r"\bfunction\b.*?\bendfunction\b", "", without_comments(text), flags=re.S)
    matches = re.findall(r"^\s*" + kind + r"\s+\[([^\]]+)\]\s+" +
                         re.escape(name) + r"\s*;", module, flags=re.M)
    require(len(matches) == 1, f"missing unique unsigned {kind} declaration: {name}")
    high, low = constant_range(matches[0])
    require(low == 0, "nonzero carrier declaration base: " + name)
    return high + 1


def validate_register_truncation(before, after, receiver):
    """Prove one emitted register slice boundary against its disabled graph.

    Accept either a full-width wire selection or a pure Verilog-2001 function.
    Names carry no authority. Widths come from the original declarations; every
    modeled source wire must be uniquely driven, unsigned and equally wide.
    The enabled source itself must be the complete expanded original expression,
    so a replacement alias or a changed operator cannot hide behind a helper.
    """
    before, after = without_comments(before), without_comments(after)
    width = unsigned_declaration(before, receiver, "reg")
    require(unsigned_declaration(after, receiver, "reg") == width,
            "register truncation receiver width changed: " + receiver)
    writes = lambda text: re.findall(r"\b" + re.escape(receiver) + r"\s*<=\s*([^;]+);", text)
    old_writes, new_writes = writes(before), writes(after)
    candidates = [(i, re.fullmatch(r"(\w+)\[([^\]]+)\]", compact(rhs)))
                  for i, rhs in enumerate(old_writes)]
    candidates = [(i, match) for i, match in candidates if match is not None]
    require(len(candidates) == 1 and len(old_writes) == len(new_writes),
            "missing unique original truncation or changed register writes: " + receiver)
    index, selected = candidates[0]
    require(constant_range(selected.group(2)) == (width - 1, 0),
            "original register source is not an exact low-bit truncation")
    for i, (old, new) in enumerate(zip(old_writes, new_writes)):
        require(i == index or compact(old) == compact(new),
                "other register assignment changed: " + receiver)
    source_width = unsigned_declaration(before, selected.group(1), "wire")
    require(source_width > width, "original source is not wider than its receiver")
    drivers = re.findall(r"\bassign\s+(\w+)\s*=\s*([^;]+);", before)
    graph = {}
    for name, rhs in drivers:
        graph.setdefault(name, []).append(rhs)
    visits = 0

    def expand(name, active):
        nonlocal visits
        if name not in graph:
            return name
        visits += 1
        require(visits <= 256 and name not in active, "cyclic or excessive original truncation graph")
        require(len(graph[name]) == 1, "multiply driven original truncation carrier: " + name)
        require(unsigned_declaration(before, name, "wire") == source_width,
                "original truncation graph crosses a different width or protected boundary: " + name)
        return re.sub(r"(?<![\w'$])\b[A-Za-z_]\w*\b",
                      lambda match: expand(match.group(), active | {name}), graph[name][0])

    expected = compact(expand(selected.group(1), set()))
    rhs = compact(new_writes[index])
    direct = re.fullmatch(r"(\w+)\[([^\]]+)\]", rhs)
    if direct is not None:
        name = direct.group(1)
        require(constant_range(direct.group(2)) == (width - 1, 0), "wrong carrier truncation slice")
        require(unsigned_declaration(after, name, "wire") == source_width,
                "truncation carrier no longer holds the full original width")
        values = re.findall(r"\bassign\s+" + re.escape(name) + r"\s*=\s*([^;]+);", after)
        require(len(values) == 1, "missing unique truncation carrier driver")
        expression = values[0]
    else:
        call = re.fullmatch(r"(\w+)\((.+)\)", rhs)
        require(call is not None, "required final truncation boundary missing")
        name, expression = call.groups()
        definitions = [body for body in re.findall(r"\bfunction\b(.*?)\bendfunction\b", after, re.S)
                       if re.match(r"\s*(?:\[[^\]]+\]\s*)?" + re.escape(name) + r"\s*;", body)]
        require(len(definitions) == 1, "missing unique truncation function: " + name)
        function = re.fullmatch(
            r"\s*\[([^\]]+)\]\s+" + re.escape(name) +
            r"\s*;\s*input\s+\[([^\]]+)\]\s+(\w+)\s*;\s*begin\s*" +
            re.escape(name) + r"\s*=\s*(\w+)\s*\[([^\]]+)\]\s*;\s*end\s*", definitions[0])
        require(function is not None, "truncation function is not a pure unsigned one-input slice")
        output_range, input_range, argument, returned, slice_range = function.groups()
        require(constant_range(output_range) == (width - 1, 0), "wrong truncation function output width")
        require(constant_range(input_range) == (source_width - 1, 0),
                "truncation function does not accept the full original input width")
        require(argument == returned and constant_range(slice_range) == (width - 1, 0),
                "truncation function does not return the exact original low bits")
    require(compact(expression) == expected, "truncation source differs from the complete original expression")
    return expression


def truncation_self_test():
    before = """wire [17:0] saved;
reg [12:0] state;
assign saved = (left + right);
always @(posedge clk) begin
  if (reset) state <= 13'h0;
  else state <= saved[12:0];
end
"""
    definition = """function [12:0] arbitrary_name;
input [18-1:0] argument;
begin arbitrary_name = argument[12:0]; end
endfunction
"""
    after = before.replace("wire [17:0] saved;", definition).replace(
        "assign saved = (left + right);", "").replace("saved[12:0]", "arbitrary_name((left + right))")
    validate_register_truncation(before, before, "state")
    validate_register_truncation(before, after, "state")
    validate_register_truncation(before, after.replace("arbitrary_name", "other_17").replace(
        "argument", "full_input"), "state")
    changes = (
        ("input [18-1:0]", "input [13-1:0]"),
        ("input [18-1:0]", "input [19-1:0]"),
        ("input [18-1:0]", "input signed [18-1:0]"),
        ("function [12:0]", "function [13:0]"),
        ("argument[12:0]", "argument[13:1]"),
        ("argument[12:0]", "argument[11:0]"),
        ("argument[12:0]", "unrelated[12:0]"),
        ("argument[12:0]", "argument[12:0] ^ 13'd1"),
        ("begin arbitrary_name", "begin argument = 0; arbitrary_name"),
        ("begin arbitrary_name", "input extra; begin arbitrary_name"),
        ("arbitrary_name((left + right))", "arbitrary_name((left - right))"),
        ("arbitrary_name((left + right))", "arbitrary_name((left[12:0] + right[12:0]))"),
        ("arbitrary_name((left + right))", "arbitrary_name(left, right)"),
        ("arbitrary_name((left + right))", "missing((left + right))"),
        ("reg [12:0] state", "reg [13:0] state"),
        ("13'h0", "13'h1"),
        (definition, definition + definition),
        (definition, "/*" + definition + "*/"),
    )
    for old, new in changes:
        require(old in after, "missing truncation mutation fixture")
        try:
            validate_register_truncation(before, after.replace(old, new, 1), "state")
        except AssertionError:
            pass
        else:
            raise AssertionError("accepted truncation mutation: " + new)
    for bad_before in (before.replace("(left + right)", "saved"),
                       before.replace("assign saved", "assign saved = left;\nassign saved")):
        try:
            validate_register_truncation(bad_before, after, "state")
        except AssertionError:
            pass
        else:
            raise AssertionError("accepted ambiguous original truncation graph")
    print("TRUNCATION_BOUNDARY_CONTROLS_PASS rejected=20")


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
        expression = validate_register_truncation(before, after, "vSyncEnd")
        for signal in ("io_vActive", "io_vFront", "io_vSync"):
            require("{2'd0," + signal + "}" in compact(expression),
                    f"timing: avoidable extension/add wrapper remains below truncation: {expression}")
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
    truncation_self_test()
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
