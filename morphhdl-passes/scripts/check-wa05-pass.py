#!/usr/bin/env python3
"""Static and mutation-tested contract guard for the WA-05 named alias pass."""

from __future__ import annotations

import argparse
import json
import re
import sys
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
        "WA05-COMPONENT-SPECIAL-CASE",
        "pass implementation must not recognize a library component or shared witness class",
        re.compile(r"\b(?:StreamFifo(?:CC)?|ParameterizedStreamFifo)\b"),
    ),
    TextRule(
        "WA05-MODULE-NAME-RECOGNITION",
        "pass implementation must not inspect canonical logical module names",
        re.compile(r"\blogicalName\b"),
    ),
    TextRule(
        "WA05-SPINAL-IMPLEMENTATION-COUPLING",
        "pass implementation must consume canonical MorphHDL IR rather than Spinal implementation classes",
        re.compile(r"\bspinal\."),
    ),
    TextRule(
        "WA05-EMITTED-NAME-RECOGNITION",
        "pass implementation must not recognize an emitted temporary identifier",
        re.compile(r"_zz_"),
    ),
    TextRule(
        "WA05-FILE-TEXT-INGRESS",
        "pass implementation must not parse or postprocess generated files",
        re.compile(
            r"\b(?:scala\.io|java\.io|java\.nio\.(?:file|channels))\b|"
            r"\b(?:fromFile|readAllBytes|readString|newInputStream|parseVerilog|generatedVerilog)\b"
        ),
    ),
    TextRule(
        "WA05-REGEX-CANDIDATE-DISCOVERY",
        "pass implementation must not discover candidates with regular expressions",
        re.compile(
            r"\b(?:scala\.util\.matching\.Regex|java\.util\.regex|Pattern\.compile)\b|"
            r"(?:\"\"\".*?\"\"\"|\"(?:\\.|[^\"\\])*\")\s*\.r\b",
            re.DOTALL,
        ),
    ),
)

BRIDGE_RULES: tuple[TextRule, ...] = (
    TextRule(
        "WA05-BRIDGE-COMPONENT-RECOGNITION",
        "native witness bridge must not inspect a component definition or instance name",
        re.compile(r"\.(?:definitionName|getName|getPartialName)\b"),
    ),
    TextRule(
        "WA05-BRIDGE-EMITTED-NAME-RECOGNITION",
        "native witness bridge must not recognize an emitted temporary identifier",
        re.compile(r"_zz_"),
    ),
    TextRule(
        "WA05-BRIDGE-GENERATED-HDL-PARSER",
        "native witness bridge must mutate exact graph identities rather than parse generated HDL",
        re.compile(r"\b(?:parseVerilog|generatedVerilog|emittedVerilog|verilogText)\b", re.IGNORECASE),
    ),
)

REQUIRED_SOURCE_MARKERS: tuple[str, ...] = (
    "PassId.NamedWireAliasElimination",
    "configuration.enabled",
    "PassResult.skipped",
    "WireAliasSafetyGate",
    "NameOrigin.Explicit",
    "explicitName(value.nameOrigin).nonEmpty",
    "transformToFixedPoint",
    "CanonicalIrPassAdapter.bindFixture(rewritten)",
    "declarations = module.declarations.filterNot(_.id == aliasSymbol)",
    ".filterNot(_.id == aliasDriverId)",
    "target == aliasSymbol",
    "value.copy(target = sourceSymbol)",
    "RtlExpr.Unary",
    "RtlExpr.Binary",
    "RtlExpr.Mux",
    "RtlExpr.Concat",
    "RtlExpr.BitSelect",
    "RtlExpr.PartSelect",
    "RtlExpr.Resize",
    "RtlExpr.Cast",
    "AliasNameOrigin.Explicit",
    "without transferring the removed name",
)

REQUIRED_BRIDGE_MARKERS: tuple[str, ...] = (
    "final class NamedWireAliasNativePhase extends Phase",
    "PhaseRemoveIntermediateUnnameds",
    "alias.isNamed",
    "ExplicitNamedWireAliasSourceTag",
    "explicitSourceName(alias)",
    "NamedWireAliasEliminationPass.run",
    "WireAliasPassConfiguration(enabled = true)",
    "statement.walkRemapDrivingExpressions",
    "reference eq alias",
    "aliasAssignment.removeStatement()",
    "alias.removeStatement()",
    "executed_before_name_allocation",
    "eliminated_names",
    "ParameterizedStreamFifoNamedPassWitness",
    "eliminated no named alias",
)

REQUIRED_TEST_MARKERS: tuple[str, ...] = (
    "disabled by default",
    "exact symbol identity",
    "recursive expression rewriting",
    "neighboring symbols remain untouched",
    "only explicit source names are candidates",
    "emitted-name text is not classification",
    "unsafe explicitly named alias is retained",
    "public hierarchical preservation probe attribute comment and source contracts are retained",
    "complete WIDTH and DEPTH domain",
    "fixed point and the pass is idempotent",
    "surviving names and metadata remain unchanged",
    "without transferring the removed name",
    "invalid canonical input fails closed",
    "deterministic",
    "component names and source paths do not affect",
)

REQUIRED_WORKFLOW_MARKERS: tuple[str, ...] = (
    "check-wa05-pass.py --self-test",
    "check-wa05-pass.py",
    "NamedWireAliasEliminationPassSpec",
    "ParameterizedStreamFifoNamedPassWitness",
    "wire-alias-named.v",
    "wire-alias-named-report.json",
    "eliminated_names",
    "validate_wire_assignment_equivalence.py",
    "cmp -s",
    "executed_before_name_allocation",
)

REQUIRED_README_MARKERS: tuple[str, ...] = (
    "WA-05",
    "NamedWireAliasEliminationPass",
    "explicit source name",
    "without transferring",
    "source location",
    "disabled by default",
    "WIDTH",
    "DEPTH",
    "common pre-pass",
    "512",
    "WA-07",
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


def require_markers(
    path: Path,
    text: str,
    markers: Sequence[str],
    code: str,
) -> list[str]:
    return [
        f"{path}: {code}: missing required marker {marker!r}"
        for marker in markers
        if marker not in text
    ]


def roadmap_entries(text: str) -> dict[str, tuple[bool, str]]:
    values: dict[str, tuple[bool, str]] = {}
    for match in ROADMAP_ENTRY.finditer(text):
        item = match.group("id")
        if item in values:
            raise AssertionError(f"roadmap repeats {item}")
        values[item] = (match.group("checked").lower() == "x", match.group("body"))
    return values


def roadmap_failures(path: Path, text: str) -> list[str]:
    try:
        entries = roadmap_entries(text)
    except AssertionError as error:
        return [f"{path}: WA05-ROADMAP: {error}"]

    failures: list[str] = []
    for item in ("WA-04", "WA-05", "WA-06"):
        if item not in entries:
            failures.append(f"{path}: WA05-ROADMAP: missing {item}")
    if failures:
        return failures

    wa04_checked, wa04_body = entries["WA-04"]
    wa05_checked, wa05_body = entries["WA-05"]
    wa06_checked, wa06_body = entries["WA-06"]
    if not wa04_checked or "**Status:** `COMPLETED`" not in wa04_body:
        failures.append(f"{path}: WA05-DEPENDENCY: WA-04 must remain completed")

    if wa05_checked:
        if "**Status:** `COMPLETED`" not in wa05_body:
            failures.append(f"{path}: WA05-STATUS: checked WA-05 must be COMPLETED")
        if wa06_checked:
            if "**Status:** `COMPLETED`" not in wa06_body:
                failures.append(
                    f"{path}: WA05-NEXT-STATUS: checked WA-06 must be COMPLETED"
                )
        elif "**Status:** `READY`" not in wa06_body:
            failures.append(f"{path}: WA05-NEXT-STATUS: WA-06 must be READY after WA-05")
    else:
        if wa06_checked:
            failures.append(f"{path}: WA05-DEPENDENCY: WA-06 cannot complete while WA-05 is open")
        if "**Status:** `READY`" not in wa05_body:
            failures.append(f"{path}: WA05-STATUS: open WA-05 must be READY")
        if "**Status:** `BLOCKED`" not in wa06_body or "WA-05" not in wa06_body:
            failures.append(f"{path}: WA05-NEXT-STATUS: WA-06 must remain BLOCKED by WA-05")

    required_scope = (
        "same safety contract",
        "explicitly named internal aliases",
        "Do not rename or transfer",
        "Report each removed name and source location deterministically",
        "shared parameterized StreamFifo",
        "common pre-pass reference",
    )
    for marker in required_scope:
        if marker.lower() not in wa05_body.lower():
            failures.append(
                f"{path}: WA05-ROADMAP-SCOPE: WA-05 entry is missing {marker!r}"
            )
    return failures


def manifest_failures(path: Path, value: object) -> list[str]:
    if not isinstance(value, dict):
        return [f"{path}: WA05-MANIFEST: root must be an object"]
    witness = value.get("shared_witness")
    if not isinstance(witness, dict):
        return [f"{path}: WA05-MANIFEST: shared_witness is missing"]
    slots = witness.get("future_pass_outputs")
    if not isinstance(slots, list):
        return [f"{path}: WA05-MANIFEST: future_pass_outputs is missing"]
    matching = [
        slot
        for slot in slots
        if isinstance(slot, dict) and slot.get("activation_item") == "WA-05"
    ]
    if len(matching) != 1:
        return [f"{path}: WA05-MANIFEST: expected exactly one WA-05 slot"]
    expected = {
        "activation_item": "WA-05",
        "candidate": "morphhdl-passes/build/pass-outputs/wire-alias-named.v",
        "pass_id": "wire-alias-named",
    }
    if matching[0] != expected:
        return [
            f"{path}: WA05-MANIFEST: WA-05 slot changed; expected {expected}, observed {matching[0]}"
        ]
    return []


def check_repository(root: Path) -> list[str]:
    pass_root = root / "morphhdl-passes"
    paths = {
        "source": pass_root / "src/main/scala/morphhdl/passes/transform/NamedWireAliasEliminationPass.scala",
        "tests": pass_root / "src/test/scala/morphhdl/passes/transform/NamedWireAliasEliminationPassSpec.scala",
        "bridge": pass_root / "examples/NamedWireAliasNativeBridge.scala",
        "witness": pass_root / "examples/ParameterizedStreamFifo.scala",
        "roadmap": pass_root / "morphhdl-ir-wire-assignment-passes-todo.md",
        "readme": pass_root / "README.md",
        "workflow": root / ".github/workflows/morphhdl-passes.yml",
        "manifest": pass_root / "tests/formal/wire_assignment_ir/manifest.json",
        "signatures": pass_root / "tests/formal_model/wire_assignment_ir/expected-signatures.json",
    }
    failures: list[str] = []
    for label, path in paths.items():
        if not path.is_file():
            failures.append(f"WA05-{label.upper()}-MISSING: {path.relative_to(root)}")
    if failures:
        return sorted(failures)

    source_text = paths["source"].read_text(encoding="utf-8")
    bridge_text = paths["bridge"].read_text(encoding="utf-8")
    test_text = paths["tests"].read_text(encoding="utf-8")
    witness_text = paths["witness"].read_text(encoding="utf-8")
    roadmap_text = paths["roadmap"].read_text(encoding="utf-8")
    readme_text = paths["readme"].read_text(encoding="utf-8")
    workflow_text = paths["workflow"].read_text(encoding="utf-8")

    failures.extend(scan_text(paths["source"].relative_to(root), source_text, GENERIC_RULES))
    failures.extend(require_markers(
        paths["source"].relative_to(root), source_text, REQUIRED_SOURCE_MARKERS,
        "WA05-SOURCE-CONTRACT-MISSING",
    ))
    phase_start = bridge_text.find("final class NamedWireAliasNativePhase extends Phase")
    phase_end = bridge_text.find("final case class NamedWireAliasNativeReport")
    if phase_start < 0 or phase_end <= phase_start:
        failures.append(
            f"{paths['bridge'].relative_to(root)}: WA05-BRIDGE-BOUNDARY: unable to isolate native bridge phase"
        )
        bridge_phase_text = bridge_text
    else:
        bridge_phase_text = bridge_text[phase_start:phase_end]
    failures.extend(scan_text(paths["bridge"].relative_to(root), bridge_phase_text, BRIDGE_RULES))
    failures.extend(require_markers(
        paths["bridge"].relative_to(root), bridge_text, REQUIRED_BRIDGE_MARKERS,
        "WA05-BRIDGE-CONTRACT-MISSING",
    ))
    failures.extend(require_markers(
        paths["tests"].relative_to(root), test_text, REQUIRED_TEST_MARKERS,
        "WA05-TEST-COVERAGE-MISSING",
    ))
    witness_markers = (
        "ExplicitNamedWireAliasSourceTag",
        "directNamedAlias",
        'setName("popPayloadNamedAlias")',
        "directNamedAlias(directUnnamedAlias(popPayloadSource))",
    )
    failures.extend(require_markers(
        paths["witness"].relative_to(root), witness_text, witness_markers,
        "WA05-WITNESS-CONTRACT-MISSING",
    ))
    failures.extend(require_markers(
        paths["workflow"].relative_to(root), workflow_text, REQUIRED_WORKFLOW_MARKERS,
        "WA05-WORKFLOW-GATE-MISSING",
    ))
    failures.extend(require_markers(
        paths["readme"].relative_to(root), readme_text, REQUIRED_README_MARKERS,
        "WA05-README-CONTRACT-MISSING",
    ))
    failures.extend(roadmap_failures(paths["roadmap"].relative_to(root), roadmap_text))

    try:
        manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
        failures.extend(manifest_failures(paths["manifest"].relative_to(root), manifest))
    except (OSError, json.JSONDecodeError) as error:
        failures.append(f"{paths['manifest'].relative_to(root)}: WA05-MANIFEST: {error}")

    try:
        registry = json.loads(paths["signatures"].read_text(encoding="utf-8"))
        registered = registry.get("files", {}) if isinstance(registry, dict) else {}
        required_registered = (
            paths["source"], paths["tests"], paths["bridge"], paths["witness"],
            Path(__file__).resolve(),
        )
        for registered_path in required_registered:
            key = registered_path.relative_to(root).as_posix()
            if key not in registered:
                failures.append(
                    f"{paths['signatures'].relative_to(root)}: WA05-SIGNATURE-MISSING: {key}"
                )
    except (OSError, json.JSONDecodeError) as error:
        failures.append(f"{paths['signatures'].relative_to(root)}: WA05-SIGNATURES: {error}")

    return sorted(failures)


def run_self_test() -> None:
    allowed = """package morphhdl.passes.transform
import morphhdl.ir.v1.{NameOrigin, SymbolId}
object Pass { def eligible(origin: NameOrigin, left: SymbolId, right: SymbolId) =
  origin match { case NameOrigin.Explicit(_) => left == right; case _ => false } }
"""
    if scan_text(Path("Allowed.scala"), allowed, GENERIC_RULES):
        raise AssertionError("component-generic explicit-provenance pass source was rejected")

    mutations = (
        ("val selected = StreamFifo", "WA05-COMPONENT-SPECIAL-CASE"),
        ("module.logicalName == \"special\"", "WA05-MODULE-NAME-RECOGNITION"),
        ("import spinal.core._", "WA05-SPINAL-IMPLEMENTATION-COUPLING"),
        ("symbol == \"_zz_1\"", "WA05-EMITTED-NAME-RECOGNITION"),
        ("scala.io.Source.fromFile(path)", "WA05-FILE-TEXT-INGRESS"),
        ("val candidate = \"named.*\".r", "WA05-REGEX-CANDIDATE-DISCOVERY"),
    )
    for text, code in mutations:
        failures = scan_text(Path("Mutant.scala"), text, GENERIC_RULES)
        if not any(code in failure for failure in failures):
            raise AssertionError(f"mutation was not rejected by {code}")

    open_roadmap = """- [x] **WA-04 — Unnamed**

  **Status:** `COMPLETED`

- [ ] **WA-05 — Named**

  **Status:** `READY`
  same safety contract; explicitly named internal aliases; Do not rename or transfer;
  Report each removed name and source location deterministically;
  shared parameterized StreamFifo; common pre-pass reference.

- [ ] **WA-06 — Combined**

  **Status:** `BLOCKED` by WA-05.
"""
    wa05_completed = open_roadmap.replace(
        "- [ ] **WA-05", "- [x] **WA-05"
    ).replace(
        "**Status:** `READY`\n  same safety contract",
        "**Status:** `COMPLETED`\n  same safety contract",
    ).replace("**Status:** `BLOCKED` by WA-05.", "**Status:** `READY`.")
    wa06_completed = wa05_completed.replace(
        "- [ ] **WA-06", "- [x] **WA-06"
    ).replace("**Status:** `READY`.", "**Status:** `COMPLETED`.")
    for value in (open_roadmap, wa05_completed, wa06_completed):
        if roadmap_failures(Path("roadmap.md"), value):
            raise AssertionError("valid WA-05 roadmap transition state was rejected")

    invalid_transition = open_roadmap.replace(
        "- [ ] **WA-06", "- [x] **WA-06"
    ).replace("**Status:** `BLOCKED` by WA-05.", "**Status:** `COMPLETED`.")
    if not roadmap_failures(Path("roadmap.md"), invalid_transition):
        raise AssertionError("WA-06 completion without WA-05 was not rejected")

    manifest = {
        "shared_witness": {
            "future_pass_outputs": [
                {
                    "activation_item": "WA-05",
                    "candidate": "morphhdl-passes/build/pass-outputs/wire-alias-named.v",
                    "pass_id": "wire-alias-named",
                }
            ]
        }
    }
    if manifest_failures(Path("manifest.json"), manifest):
        raise AssertionError("valid WA-05 manifest slot was rejected")
    manifest["shared_witness"]["future_pass_outputs"][0]["candidate"] = "wrong.v"
    if not manifest_failures(Path("manifest.json"), manifest):
        raise AssertionError("mutated WA-05 manifest slot was not rejected")

    print("WA-05 pass contract self-tests passed.")


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
        print("WA-05 pass contract failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print("WA-05 pass contract passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
