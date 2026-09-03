#!/usr/bin/env python3
"""Static and mutation-tested contract guard for WA-03 gate infrastructure."""

from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


@dataclass(frozen=True)
class TextRule:
    code: str
    message: str
    pattern: re.Pattern[str]


GENERIC_RULES: tuple[TextRule, ...] = (
    TextRule(
        "WA03-COMPONENT-SPECIAL-CASE",
        "gate implementation must not recognize a library component or shared witness class",
        re.compile(r"\b(?:StreamFifo(?:CC)?|ParameterizedStreamFifo)\b"),
    ),
    TextRule(
        "WA03-MODULE-NAME-RECOGNITION",
        "gate implementation must not inspect canonical logical module names",
        re.compile(r"\blogicalName\b"),
    ),
    TextRule(
        "WA03-SPINAL-IMPLEMENTATION-COUPLING",
        "gate implementation must consume canonical MorphHDL IR rather than Spinal implementation classes",
        re.compile(r"\bspinal\."),
    ),
    TextRule(
        "WA03-EMITTED-NAME-RECOGNITION",
        "gate implementation must not recognize an emitted temporary identifier",
        re.compile(r"_zz_"),
    ),
)

TRANSFORM_RULES: tuple[TextRule, ...] = (
    TextRule(
        "WA03-PREMATURE-DECLARATION-REWRITE",
        "WA-03 may analyze declarations but must not publish a rewritten declaration collection",
        re.compile(r"\b(?:module|design)\.copy\s*\([\s\S]{0,500}\bdeclarations\s*=", re.MULTILINE),
    ),
    TextRule(
        "WA03-PREMATURE-DRIVER-REWRITE",
        "WA-03 may analyze drivers but must not publish a rewritten driver collection",
        re.compile(r"\b(?:module|design)\.copy\s*\([\s\S]{0,500}\bdrivers\s*=", re.MULTILINE),
    ),
    TextRule(
        "WA03-PREMATURE-REFERENCE-REWRITE",
        "WA-03 must not introduce a reference-rewrite entry point",
        re.compile(r"\b(?:rewrite|replace|substitute)(?:Alias)?References?\s*\("),
    ),
)

REQUIRED_REASON_CODES: tuple[str, ...] = (
    "WA03-ALIAS-NOT-INTERNAL-COMBINATIONAL",
    "WA03-OBSERVABILITY-INCOMPLETE",
    "WA03-EXTERNALLY-VISIBLE",
    "WA03-KEEP",
    "WA03-DONT-TOUCH",
    "WA03-PROBE",
    "WA03-PRESERVE",
    "WA03-PUBLIC-EXPORT",
    "WA03-BLACK-BOX-BOUNDARY",
    "WA03-HIERARCHY-BOUNDARY",
    "WA03-DECLARATION-ATTRIBUTES",
    "WA03-DECLARATION-COMMENTS",
    "WA03-DRIVER-CARDINALITY",
    "WA03-DRIVER-NOT-CONTINUOUS",
    "WA03-DRIVER-NOT-FULL-OBJECT",
    "WA03-DRIVER-NOT-DIRECT-REFERENCE",
    "WA03-DRIVER-ATTRIBUTES",
    "WA03-DRIVER-COMMENTS",
    "WA03-SOURCE-KIND-EXCLUDED",
    "WA03-PACKED-SIGNEDNESS-MISMATCH",
    "WA03-PACKED-VALUE-SEMANTICS-MISMATCH",
    "WA03-PACKED-WIDTH-DOMAIN-MISMATCH",
    "WA03-PACKED-WIDTH-DOMAIN-UNPROVEN",
    "WA03-DOMAIN-EXPANSION-LIMIT",
    "WA03-ILLEGAL-SCOPE-REPLACEMENT",
    "WA03-COMBINATIONAL-CYCLE",
    "WA03-CLOCK-USE",
    "WA03-RESET-USE",
    "WA03-TRI-STATE-CONTROL-USE",
    "WA03-CONTROL-USE-UNPROVEN",
    "WA03-BIDIRECTIONAL-USE",
    "WA03-MEMORY-PORT-USE",
    "WA03-INSTANCE-BOUNDARY-USE",
)

REQUIRED_TEST_MARKERS: tuple[str, ...] = (
    "complete domain",
    "mismatching admitted binding",
    "every retained observability contract",
    "comments and attributes",
    "not visible at an alias use",
    "dependency cycles",
    "clock reset and tri-state control uses",
    "procedural contexts are rejected",
    "bounded and fails closed",
    "deterministic",
    "idempotent",
    "component names and source paths",
)

REQUIRED_VALIDATOR_MARKERS: tuple[str, ...] = (
    "strict_design_checks",
    "parameter_bindings",
    "run_formal_binding",
    "run_mutation_control",
    "run_shared_witness",
    "compare_deterministic_runs",
    "complete_cartesian_product",
    "single_common_reference",
    "INACTIVE_UNTIL_ROADMAP_COMPLETION",
    "required WA-03 tool is unavailable",
)

REQUIRED_WORKFLOW_MARKERS: tuple[str, ...] = (
    "check-wa03-gates.py --self-test",
    "check-wa03-gates.py",
    "validate_wire_assignment_equivalence.py --self-test",
    "validate_wire_assignment_equivalence.py",
    "--shared-witness",
    "--check-determinism",
    "ParameterizedStreamFifoExample",
    "iverilog",
    "verilator",
    "yosys",
    "sby",
    "yices-smt2",
    "actions/upload-artifact@v4",
)

REQUIRED_README_MARKERS: tuple[str, ...] = (
    "WA-03",
    "complete admitted parameter domain",
    "common pre-pass",
    "mutation",
    "determinism",
    "does not eliminate",
)

ROADMAP_ENTRY = re.compile(
    r"^- \[(?P<checked>[ xX])\] \*\*(?P<id>WA-[0-9]+)\s+—(?P<body>[\s\S]*?)(?=^- \[[ xX]\] \*\*WA-[0-9]+\s+—|\Z)",
    re.MULTILINE,
)


def scan_text(path: Path, text: str, rules: Sequence[TextRule]) -> list[str]:
    failures: list[str] = []
    for rule in rules:
        match = rule.pattern.search(text)
        if match is not None:
            line = text.count("\n", 0, match.start()) + 1
            failures.append(f"{path}:{line}: {rule.code}: {rule.message}")
    return failures


def require_markers(path: Path, text: str, markers: Sequence[str], code: str) -> list[str]:
    return [
        f"{path}: {code}: missing required marker {marker!r}"
        for marker in markers
        if marker not in text
    ]


def load_manifest(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise AssertionError(f"unable to load manifest {path}: {error}") from error
    if not isinstance(value, dict):
        raise AssertionError("manifest root must be an object")
    return value


def manifest_failures(path: Path, manifest: dict) -> list[str]:
    failures: list[str] = []
    cases = manifest.get("cases")
    if not isinstance(cases, list) or len(cases) < 2:
        failures.append(f"{path}: WA03-GENERIC-CASES: requires at least two generic cases")
    else:
        clocks = [case.get("clock") for case in cases if isinstance(case, dict)]
        if not any(value is None for value in clocks):
            failures.append(f"{path}: WA03-COMBINATIONAL-CASE: missing combinational case")
        if not any(value is not None for value in clocks):
            failures.append(f"{path}: WA03-SEQUENTIAL-CASE: missing sequential case")
        for index, case in enumerate(cases):
            if not isinstance(case, dict):
                continue
            domains = case.get("parameter_domains")
            simulations = case.get("representative_simulations")
            if not isinstance(domains, dict) or not domains:
                failures.append(f"{path}: WA03-DOMAIN: case {index} has no domain")
            if not isinstance(simulations, list) or not simulations:
                failures.append(f"{path}: WA03-SIMULATION: case {index} has no representative simulation")

    witness = manifest.get("shared_witness")
    if not isinstance(witness, dict):
        failures.append(f"{path}: WA03-SHARED-WITNESS: missing shared witness contract")
        return failures
    domains = witness.get("parameter_domains")
    if not isinstance(domains, dict):
        failures.append(f"{path}: WA03-WITNESS-DOMAIN: missing witness domains")
    else:
        width = domains.get("WIDTH")
        depth = domains.get("DEPTH")
        if width != list(range(1, 65)):
            failures.append(f"{path}: WA03-WIDTH-DOMAIN: WIDTH must cover admitted values 1 through 64")
        if depth != list(range(1, 9)):
            failures.append(f"{path}: WA03-DEPTH-DOMAIN: DEPTH must cover admitted values 1 through 8")
    if witness.get("common_reference_capture") != "common-pre-pass/reference.v":
        failures.append(f"{path}: WA03-COMMON-REFERENCE: capture path changed")
    slots = witness.get("future_pass_outputs")
    expected_items = {"WA-04", "WA-05", "WA-06"}
    observed_items = {
        value.get("activation_item")
        for value in slots
        if isinstance(slots, list) and isinstance(value, dict)
    } if isinstance(slots, list) else set()
    if observed_items != expected_items:
        failures.append(
            f"{path}: WA03-FUTURE-PASS-SLOTS: expected activation items {sorted(expected_items)}, observed {sorted(str(value) for value in observed_items)}"
        )
    return failures


def roadmap_entries(text: str) -> dict[str, tuple[bool, str]]:
    values: dict[str, tuple[bool, str]] = {}
    for match in ROADMAP_ENTRY.finditer(text):
        item = match.group("id")
        if item in values:
            raise AssertionError(f"roadmap repeats {item}")
        values[item] = (match.group("checked").lower() == "x", match.group("body"))
    return values


def roadmap_failures(path: Path, text: str) -> list[str]:
    failures: list[str] = []
    try:
        entries = roadmap_entries(text)
    except AssertionError as error:
        return [f"{path}: WA03-ROADMAP: {error}"]
    for item in ("WA-02", "WA-03", "WA-04"):
        if item not in entries:
            failures.append(f"{path}: WA03-ROADMAP: missing {item}")
    if failures:
        return failures
    if not entries["WA-02"][0]:
        failures.append(f"{path}: WA03-DEPENDENCY: WA-02 must remain complete")
    wa03_checked, wa03_body = entries["WA-03"]
    wa04_checked, wa04_body = entries["WA-04"]
    if wa04_checked:
        failures.append(f"{path}: WA03-SCOPE: WA-04 must not be completed by WA-03")
    if wa03_checked:
        if "**Status:** `COMPLETED`" not in wa03_body:
            failures.append(f"{path}: WA03-STATUS: checked WA-03 must be COMPLETED")
        if "**Status:** `READY`" not in wa04_body:
            failures.append(f"{path}: WA03-NEXT-STATUS: WA-04 must be READY after WA-03")
    else:
        if "**Status:** `READY`" not in wa03_body:
            failures.append(f"{path}: WA03-STATUS: open WA-03 must be READY")
        if "**Status:** `BLOCKED`" not in wa04_body:
            failures.append(f"{path}: WA03-NEXT-STATUS: WA-04 must remain BLOCKED while WA-03 is open")
    roadmap_evidence = (
        ("complete admitted parameter domain", re.compile(r"complete admitted parameter[- ]domain", re.IGNORECASE)),
        ("common pre-pass", re.compile(r"common pre-pass", re.IGNORECASE)),
        ("formal mutation", re.compile(r"formal mutation", re.IGNORECASE)),
        ("determinism", re.compile(r"determinism", re.IGNORECASE)),
        ("idempotence", re.compile(r"idempotence", re.IGNORECASE)),
    )
    for marker, pattern in roadmap_evidence:
        if pattern.search(wa03_body) is None:
            failures.append(f"{path}: WA03-ROADMAP-EVIDENCE: WA-03 entry is missing {marker!r}")
    return failures


def check_repository(root: Path) -> list[str]:
    pass_root = root / "morphhdl-passes"
    paths = {
        "safety": pass_root / "src/main/scala/morphhdl/passes/safety/WireAliasSafetyGate.scala",
        "tests": pass_root / "src/test/scala/morphhdl/passes/safety/WireAliasSafetyGateSpec.scala",
        "validator": pass_root / "scripts/validate_wire_assignment_equivalence.py",
        "manifest": pass_root / "tests/formal/wire_assignment_ir/manifest.json",
        "signatures": pass_root / "tests/formal_model/wire_assignment_ir/expected-signatures.json",
        "roadmap": pass_root / "morphhdl-ir-wire-assignment-passes-todo.md",
        "readme": pass_root / "README.md",
        "workflow": root / ".github/workflows/morphhdl-passes.yml",
    }
    failures: list[str] = []
    for label, path in paths.items():
        if not path.is_file():
            failures.append(f"WA03-{label.upper()}-MISSING: {path.relative_to(root)}")
    if failures:
        return sorted(failures)

    safety_text = paths["safety"].read_text(encoding="utf-8")
    validator_text = paths["validator"].read_text(encoding="utf-8")
    test_text = paths["tests"].read_text(encoding="utf-8")
    workflow_text = paths["workflow"].read_text(encoding="utf-8")
    readme_text = paths["readme"].read_text(encoding="utf-8")
    roadmap_text = paths["roadmap"].read_text(encoding="utf-8")

    failures.extend(scan_text(paths["safety"].relative_to(root), safety_text, GENERIC_RULES))
    failures.extend(scan_text(paths["validator"].relative_to(root), validator_text, GENERIC_RULES))
    failures.extend(scan_text(paths["safety"].relative_to(root), safety_text, TRANSFORM_RULES))
    failures.extend(
        require_markers(
            paths["safety"].relative_to(root),
            safety_text,
            REQUIRED_REASON_CODES,
            "WA03-SAFETY-REASON-MISSING",
        )
    )
    failures.extend(
        require_markers(
            paths["tests"].relative_to(root),
            test_text,
            REQUIRED_TEST_MARKERS,
            "WA03-TEST-COVERAGE-MISSING",
        )
    )
    failures.extend(
        require_markers(
            paths["validator"].relative_to(root),
            validator_text,
            REQUIRED_VALIDATOR_MARKERS,
            "WA03-VALIDATOR-CONTRACT-MISSING",
        )
    )
    if "--skip" in validator_text or "--no-formal" in validator_text:
        failures.append(
            f"{paths['validator'].relative_to(root)}: WA03-UNSAFE-BYPASS: proof gate must not expose a skip option"
        )
    failures.extend(
        require_markers(
            paths["workflow"].relative_to(root),
            workflow_text,
            REQUIRED_WORKFLOW_MARKERS,
            "WA03-WORKFLOW-GATE-MISSING",
        )
    )
    failures.extend(
        require_markers(
            paths["readme"].relative_to(root),
            readme_text,
            REQUIRED_README_MARKERS,
            "WA03-README-CONTRACT-MISSING",
        )
    )
    failures.extend(roadmap_failures(paths["roadmap"].relative_to(root), roadmap_text))

    try:
        manifest = load_manifest(paths["manifest"])
        failures.extend(manifest_failures(paths["manifest"].relative_to(root), manifest))
    except AssertionError as error:
        failures.append(f"{paths['manifest'].relative_to(root)}: WA03-MANIFEST: {error}")

    fixture_directory = pass_root / "tests/formal/wire_assignment_ir"
    required_fixtures = (
        "generic_combinational_reference.v",
        "generic_combinational_candidate.v",
        "generic_combinational_tb.v",
        "generic_sequential_reference.v",
        "generic_sequential_candidate.v",
        "generic_sequential_tb.v",
        "parameterized_witness_tb.v",
    )
    for name in required_fixtures:
        path = fixture_directory / name
        if not path.is_file():
            failures.append(f"WA03-FORMAL-FIXTURE-MISSING: {path.relative_to(root)}")

    return sorted(failures)


def expect_rejected(text: str, code: str, rules: Sequence[TextRule]) -> None:
    failures = scan_text(Path("Mutant.scala"), text, rules)
    if not any(code in value for value in failures):
        raise AssertionError(f"mutation was not rejected by {code}: {text!r}")


def run_self_test() -> None:
    allowed = """package morphhdl.passes.safety
import morphhdl.ir.v1.SymbolId
object Gate { def inspect(id: SymbolId) = id.value }
"""
    if scan_text(Path("Allowed.scala"), allowed, GENERIC_RULES + TRANSFORM_RULES):
        raise AssertionError("component-generic read-only source was rejected")

    mutations = (
        ("val selected = StreamFifo", "WA03-COMPONENT-SPECIAL-CASE", GENERIC_RULES),
        ("module.logicalName == \"special\"", "WA03-MODULE-NAME-RECOGNITION", GENERIC_RULES),
        ("import spinal.core.Component", "WA03-SPINAL-IMPLEMENTATION-COUPLING", GENERIC_RULES),
        ("val name = \"_zz_1\"", "WA03-EMITTED-NAME-RECOGNITION", GENERIC_RULES),
        (
            "val output = module.copy(declarations = module.declarations.filterNot(_ == alias))",
            "WA03-PREMATURE-DECLARATION-REWRITE",
            TRANSFORM_RULES,
        ),
        (
            "val output = design.copy(drivers = Vector.empty)",
            "WA03-PREMATURE-DRIVER-REWRITE",
            TRANSFORM_RULES,
        ),
        (
            "def rewriteAliasReferences() = ()",
            "WA03-PREMATURE-REFERENCE-REWRITE",
            TRANSFORM_RULES,
        ),
    )
    for text, code, rules in mutations:
        expect_rejected(text, code, rules)

    valid_manifest = {
        "cases": [
            {"clock": None, "parameter_domains": {"WIDTH": [1]}, "representative_simulations": [{"WIDTH": 1}]},
            {"clock": "clk", "parameter_domains": {"WIDTH": [1]}, "representative_simulations": [{"WIDTH": 1}]},
        ],
        "shared_witness": {
            "parameter_domains": {"WIDTH": list(range(1, 65)), "DEPTH": list(range(1, 9))},
            "common_reference_capture": "common-pre-pass/reference.v",
            "future_pass_outputs": [
                {"activation_item": "WA-04"},
                {"activation_item": "WA-05"},
                {"activation_item": "WA-06"},
            ],
        },
    }
    if manifest_failures(Path("manifest.json"), valid_manifest):
        raise AssertionError("valid manifest contract was rejected")
    mutant = json.loads(json.dumps(valid_manifest))
    mutant["shared_witness"]["parameter_domains"]["WIDTH"].remove(64)
    if not any("WA03-WIDTH-DOMAIN" in value for value in manifest_failures(Path("manifest.json"), mutant)):
        raise AssertionError("incomplete witness domain mutation was not rejected")

    with tempfile.TemporaryDirectory(prefix="wa03-roadmap-self-test-") as directory:
        path = Path(directory) / "roadmap.md"
        path.write_text(
            """- [x] **WA-02 — Dependency**\n\n  **Status:** `COMPLETED`\n\n- [ ] **WA-03 — Gate**\n\n  **Status:** `READY`\n  common pre-pass, complete admitted parameter domain, formal mutation, determinism, idempotence\n\n- [ ] **WA-04 — Transform**\n\n  **Status:** `BLOCKED`\n""",
            encoding="utf-8",
        )
        if roadmap_failures(path, path.read_text(encoding="utf-8")):
            raise AssertionError("valid open WA-03 roadmap state was rejected")

    print("WA-03 static gate self-tests passed.")


def parse_args(argv: Iterable[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args(list(argv))


def main(argv: Iterable[str] = sys.argv[1:]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        return 0
    root = (args.repo_root or Path(__file__).resolve().parents[2]).resolve()
    failures = check_repository(root)
    if failures:
        print("WA-03 gate contract failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print("WA-03 gate contract passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
