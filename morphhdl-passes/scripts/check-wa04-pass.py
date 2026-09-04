#!/usr/bin/env python3
"""Static and mutation-tested contract guard for the WA-04 unnamed alias pass."""

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
        "WA04-COMPONENT-SPECIAL-CASE",
        "pass implementation must not recognize a library component or shared witness class",
        re.compile(r"\b(?:StreamFifo(?:CC)?|ParameterizedStreamFifo)\b"),
    ),
    TextRule(
        "WA04-MODULE-NAME-RECOGNITION",
        "pass implementation must not inspect canonical logical module names",
        re.compile(r"\blogicalName\b"),
    ),
    TextRule(
        "WA04-SPINAL-IMPLEMENTATION-COUPLING",
        "pass implementation must consume canonical MorphHDL IR rather than Spinal implementation classes",
        re.compile(r"\bspinal\."),
    ),
    TextRule(
        "WA04-EMITTED-NAME-RECOGNITION",
        "pass implementation must not recognize an emitted temporary identifier",
        re.compile(r"_zz_"),
    ),
    TextRule(
        "WA04-FILE-TEXT-INGRESS",
        "pass implementation must not parse or postprocess generated files",
        re.compile(
            r"\b(?:scala\.io|java\.io|java\.nio\.(?:file|channels))\b|"
            r"\b(?:fromFile|readAllBytes|readString|newInputStream|parseVerilog|generatedVerilog)\b"
        ),
    ),
    TextRule(
        "WA04-REGEX-CANDIDATE-DISCOVERY",
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
        "WA04-BRIDGE-COMPONENT-RECOGNITION",
        "native witness bridge must not inspect a component definition or instance name",
        re.compile(r"\.(?:definitionName|getName|getPartialName)\b"),
    ),
    TextRule(
        "WA04-BRIDGE-EMITTED-NAME-RECOGNITION",
        "native witness bridge must not recognize an emitted temporary identifier",
        re.compile(r"_zz_"),
    ),
    TextRule(
        "WA04-BRIDGE-GENERATED-HDL-PARSER",
        "native witness bridge must mutate exact graph identities rather than parse generated HDL",
        re.compile(r"\b(?:parseVerilog|generatedVerilog|emittedVerilog|verilogText)\b", re.IGNORECASE),
    ),
)

REQUIRED_BRIDGE_MARKERS: tuple[str, ...] = (
    "final class UnnamedWireAliasNativePhase extends Phase",
    "PhaseRemoveIntermediateUnnameds",
    "alias.isUnnamed",
    "UnnamedWireAliasEliminationPass.run",
    "WireAliasPassConfiguration(enabled = true)",
    "statement.walkRemapDrivingExpressions",
    "reference eq alias",
    "aliasAssignment.removeStatement()",
    "alias.removeStatement()",
    "executed_before_name_allocation",
    "native_full_alias_removal_suppressed",
    "ParameterizedStreamFifoUnnamedPassWitness",
    "eliminated no unnamed alias",
)

REQUIRED_SOURCE_MARKERS: tuple[str, ...] = (
    "PassId.UnnamedWireAliasElimination",
    "configuration.enabled",
    "PassResult.skipped",
    "WireAliasSafetyGate",
    "NameOrigin.Unnamed",
    "transformToFixedPoint",
    "CanonicalIrPassAdapter.bind(rewritten)",
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
    "AliasNameOrigin.Unnamed",
)

REQUIRED_TEST_MARKERS: tuple[str, ...] = (
    "disabled by default",
    "exact symbol identity",
    "recursive expression rewriting",
    "neighboring symbols remain untouched",
    "emitted-name conventions are never unnamed candidates",
    "unsafe unnamed alias is retained",
    "complete WIDTH and DEPTH domain",
    "fixed point and the pass is idempotent",
    "surviving names and metadata remain unchanged",
    "invalid canonical input fails closed",
    "deterministic",
    "component names and source paths do not affect",
)

REQUIRED_WORKFLOW_MARKERS: tuple[str, ...] = (
    "check-wa04-pass.py --self-test",
    "check-wa04-pass.py",
    "UnnamedWireAliasEliminationPassSpec",
    "shared parameterized witness proof contract",
    "wire-alias-unnamed.v",
    "validate_wire_assignment_equivalence.py",
    "ParameterizedStreamFifoUnnamedPassWitness",
    "cmp -s",
    "executed_before_name_allocation",
)

REQUIRED_README_MARKERS: tuple[str, ...] = (
    "WA-04",
    "UnnamedWireAliasEliminationPass",
    "exact symbol identity",
    "fixed point",
    "disabled by default",
    "WIDTH",
    "DEPTH",
    "common pre-pass",
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
        return [f"{path}: WA04-ROADMAP: {error}"]

    failures: list[str] = []
    for item in ("WA-03", "WA-04", "WA-05"):
        if item not in entries:
            failures.append(f"{path}: WA04-ROADMAP: missing {item}")
    if failures:
        return failures

    wa03_checked, wa03_body = entries["WA-03"]
    wa04_checked, wa04_body = entries["WA-04"]
    wa05_checked, wa05_body = entries["WA-05"]
    if not wa03_checked or "**Status:** `COMPLETED`" not in wa03_body:
        failures.append(f"{path}: WA04-DEPENDENCY: WA-03 must remain completed")

    if wa04_checked:
        if "**Status:** `COMPLETED`" not in wa04_body:
            failures.append(f"{path}: WA04-STATUS: checked WA-04 must be COMPLETED")
        if wa05_checked:
            if "**Status:** `COMPLETED`" not in wa05_body:
                failures.append(f"{path}: WA04-NEXT-STATUS: checked WA-05 must be COMPLETED")
        elif "**Status:** `READY`" not in wa05_body:
            failures.append(f"{path}: WA04-NEXT-STATUS: open WA-05 must be READY after WA-04")
    else:
        if "**Status:** `READY`" not in wa04_body:
            failures.append(f"{path}: WA04-STATUS: open WA-04 must be READY")
        if "**Status:** `BLOCKED`" not in wa05_body or "WA-04" not in wa05_body:
            failures.append(f"{path}: WA04-NEXT-STATUS: WA-05 must remain BLOCKED by WA-04")

    required_scope = (
        "exact symbol identity",
        "sole direct assignment",
        "surviving names unchanged",
        "shared parameterized StreamFifo",
        "common pre-pass reference",
    )
    for marker in required_scope:
        if marker.lower() not in wa04_body.lower():
            failures.append(
                f"{path}: WA04-ROADMAP-SCOPE: WA-04 entry is missing {marker!r}"
            )
    return failures


def manifest_failures(path: Path, value: object) -> list[str]:
    if not isinstance(value, dict):
        return [f"{path}: WA04-MANIFEST: root must be an object"]
    witness = value.get("shared_witness")
    if not isinstance(witness, dict):
        return [f"{path}: WA04-MANIFEST: shared_witness is missing"]
    slots = witness.get("future_pass_outputs")
    if not isinstance(slots, list):
        return [f"{path}: WA04-MANIFEST: future_pass_outputs is missing"]
    matching = [
        slot
        for slot in slots
        if isinstance(slot, dict) and slot.get("activation_item") == "WA-04"
    ]
    if len(matching) != 1:
        return [f"{path}: WA04-MANIFEST: expected exactly one WA-04 slot"]
    expected = {
        "activation_item": "WA-04",
        "candidate": "morphhdl-passes/build/pass-outputs/wire-alias-unnamed.v",
        "pass_id": "wire-alias-unnamed",
    }
    if matching[0] != expected:
        return [
            f"{path}: WA04-MANIFEST: WA-04 slot changed; expected {expected}, observed {matching[0]}"
        ]
    return []


def check_repository(root: Path) -> list[str]:
    pass_root = root / "morphhdl-passes"
    paths = {
        "source": pass_root
        / "src/main/scala/morphhdl/passes/transform/UnnamedWireAliasEliminationPass.scala",
        "tests": pass_root
        / "src/test/scala/morphhdl/passes/transform/UnnamedWireAliasEliminationPassSpec.scala",
        "bridge": pass_root / "examples/UnnamedWireAliasNativeBridge.scala",
        "roadmap": pass_root / "morphhdl-ir-wire-assignment-passes-todo.md",
        "readme": pass_root / "README.md",
        "workflow": root / ".github/workflows/morphhdl-passes.yml",
        "manifest": pass_root / "tests/formal/wire_assignment_ir/manifest.json",
    }
    failures: list[str] = []
    for label, path in paths.items():
        if not path.is_file():
            failures.append(f"WA04-{label.upper()}-MISSING: {path.relative_to(root)}")
    if failures:
        return sorted(failures)

    source_text = paths["source"].read_text(encoding="utf-8")
    bridge_text = paths["bridge"].read_text(encoding="utf-8")
    test_text = paths["tests"].read_text(encoding="utf-8")
    roadmap_text = paths["roadmap"].read_text(encoding="utf-8")
    readme_text = paths["readme"].read_text(encoding="utf-8")
    workflow_text = paths["workflow"].read_text(encoding="utf-8")

    failures.extend(
        scan_text(paths["source"].relative_to(root), source_text, GENERIC_RULES)
    )
    failures.extend(
        require_markers(
            paths["source"].relative_to(root),
            source_text,
            REQUIRED_SOURCE_MARKERS,
            "WA04-SOURCE-CONTRACT-MISSING",
        )
    )
    phase_start = bridge_text.find("final class UnnamedWireAliasNativePhase extends Phase")
    phase_end = bridge_text.find("final case class UnnamedWireAliasNativeReport")
    if phase_start < 0 or phase_end <= phase_start:
        failures.append(
            f"{paths['bridge'].relative_to(root)}: WA04-BRIDGE-BOUNDARY: unable to isolate native bridge phase"
        )
        bridge_phase_text = bridge_text
    else:
        bridge_phase_text = bridge_text[phase_start:phase_end]
    failures.extend(
        scan_text(paths["bridge"].relative_to(root), bridge_phase_text, BRIDGE_RULES)
    )
    failures.extend(
        require_markers(
            paths["bridge"].relative_to(root),
            bridge_text,
            REQUIRED_BRIDGE_MARKERS,
            "WA04-BRIDGE-CONTRACT-MISSING",
        )
    )
    failures.extend(
        require_markers(
            paths["tests"].relative_to(root),
            test_text,
            REQUIRED_TEST_MARKERS,
            "WA04-TEST-COVERAGE-MISSING",
        )
    )
    failures.extend(
        require_markers(
            paths["workflow"].relative_to(root),
            workflow_text,
            REQUIRED_WORKFLOW_MARKERS,
            "WA04-WORKFLOW-GATE-MISSING",
        )
    )
    failures.extend(
        require_markers(
            paths["readme"].relative_to(root),
            readme_text,
            REQUIRED_README_MARKERS,
            "WA04-README-CONTRACT-MISSING",
        )
    )
    failures.extend(roadmap_failures(paths["roadmap"].relative_to(root), roadmap_text))

    try:
        manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
        failures.extend(manifest_failures(paths["manifest"].relative_to(root), manifest))
    except (OSError, json.JSONDecodeError) as error:
        failures.append(f"{paths['manifest'].relative_to(root)}: WA04-MANIFEST: {error}")

    return sorted(failures)


def expect_rejected(text: str, code: str) -> None:
    failures = scan_text(Path("Mutant.scala"), text, GENERIC_RULES)
    if not any(code in failure for failure in failures):
        raise AssertionError(f"mutation was not rejected by {code}: {text!r}")


def run_self_test() -> None:
    allowed = """package morphhdl.passes.transform
import morphhdl.ir.v1.{NameOrigin, SymbolId}
object Pass { def eligible(origin: NameOrigin, left: SymbolId, right: SymbolId) =
  origin == NameOrigin.Unnamed && left == right }
"""
    if scan_text(Path("Allowed.scala"), allowed, GENERIC_RULES):
        raise AssertionError("component-generic identity-based pass source was rejected")

    mutations = (
        ("val selected = StreamFifo", "WA04-COMPONENT-SPECIAL-CASE"),
        ("module.logicalName == \"special\"", "WA04-MODULE-NAME-RECOGNITION"),
        ("import spinal.core.Component", "WA04-SPINAL-IMPLEMENTATION-COUPLING"),
        ("val name = \"_zz_1\"", "WA04-EMITTED-NAME-RECOGNITION"),
        ("scala.io.Source.fromFile(\"generated.v\")", "WA04-FILE-TEXT-INGRESS"),
        ("val matcher = \"assign.*\".r", "WA04-REGEX-CANDIDATE-DISCOVERY"),
    )
    for text, code in mutations:
        expect_rejected(text, code)

    bridge_allowed = """final class UnnamedWireAliasNativePhase extends Phase {
  def applyIdentity(alias: BaseType, source: BaseType) = alias eq source
}
"""
    if scan_text(Path("Bridge.scala"), bridge_allowed, BRIDGE_RULES):
        raise AssertionError("identity-based native bridge source was rejected")
    bridge_mutations = (
        ("candidate.component.definitionName", "WA04-BRIDGE-COMPONENT-RECOGNITION"),
        ("val emitted = \"_zz_3\"", "WA04-BRIDGE-EMITTED-NAME-RECOGNITION"),
        ("parseVerilog(generatedVerilog)", "WA04-BRIDGE-GENERATED-HDL-PARSER"),
    )
    for text, code in bridge_mutations:
        failures = scan_text(Path("BridgeMutant.scala"), text, BRIDGE_RULES)
        if not any(code in failure for failure in failures):
            raise AssertionError(f"native bridge mutation was not rejected by {code}")

    bridge_contract = "\n".join(REQUIRED_BRIDGE_MARKERS)
    for marker in REQUIRED_BRIDGE_MARKERS:
        mutant = bridge_contract.replace(marker, "<removed>")
        missing = require_markers(
            Path("Bridge.scala"),
            mutant,
            REQUIRED_BRIDGE_MARKERS,
            "WA04-BRIDGE-CONTRACT-MISSING",
        )
        if not any(marker in failure for failure in missing):
            raise AssertionError(f"missing bridge marker mutation was not rejected: {marker}")

    source_contract = "\n".join(REQUIRED_SOURCE_MARKERS)
    for marker in REQUIRED_SOURCE_MARKERS:
        mutant = source_contract.replace(marker, "<removed>")
        missing = require_markers(
            Path("Pass.scala"),
            mutant,
            REQUIRED_SOURCE_MARKERS,
            "WA04-SOURCE-CONTRACT-MISSING",
        )
        if not any(marker in failure for failure in missing):
            raise AssertionError(f"missing source marker mutation was not rejected: {marker}")

    open_roadmap = """- [x] **WA-03 — Gate**

  **Status:** `COMPLETED`

- [ ] **WA-04 — Transform**

  **Status:** `READY`
  exact symbol identity; sole direct assignment; surviving names unchanged;
  shared parameterized StreamFifo; common pre-pass reference.

- [ ] **WA-05 — Named**

  **Status:** `BLOCKED` by WA-04.
"""
    completed_roadmap = open_roadmap.replace(
        "- [ ] **WA-04", "- [x] **WA-04"
    ).replace(
        "**Status:** `READY`\n  exact symbol identity",
        "**Status:** `COMPLETED`\n  exact symbol identity",
    ).replace("**Status:** `BLOCKED` by WA-04.", "**Status:** `READY`.")
    wa05_completed_roadmap = completed_roadmap.replace(
        "- [ ] **WA-05", "- [x] **WA-05"
    ).replace("**Status:** `READY`.", "**Status:** `COMPLETED`.")
    for value in (open_roadmap, completed_roadmap, wa05_completed_roadmap):
        if roadmap_failures(Path("roadmap.md"), value):
            raise AssertionError("valid WA-04 roadmap transition state was rejected")
    invalid_roadmap = completed_roadmap.replace(
        "**Status:** `READY`.", "**Status:** `BLOCKED` by WA-04."
    )
    if not any(
        "WA04-NEXT-STATUS" in failure
        for failure in roadmap_failures(Path("roadmap.md"), invalid_roadmap)
    ):
        raise AssertionError("completed WA-04 with blocked WA-05 was not rejected")

    manifest = {
        "shared_witness": {
            "future_pass_outputs": [
                {
                    "activation_item": "WA-04",
                    "candidate": "morphhdl-passes/build/pass-outputs/wire-alias-unnamed.v",
                    "pass_id": "wire-alias-unnamed",
                }
            ]
        }
    }
    if manifest_failures(Path("manifest.json"), manifest):
        raise AssertionError("valid WA-04 manifest slot was rejected")
    manifest["shared_witness"]["future_pass_outputs"][0]["candidate"] = "wrong.v"
    if not manifest_failures(Path("manifest.json"), manifest):
        raise AssertionError("mutated WA-04 manifest slot was not rejected")

    with tempfile.TemporaryDirectory(prefix="wa04-self-test-") as directory:
        root = Path(directory)
        path = root / "marker.txt"
        path.write_text("deterministic", encoding="utf-8")
        if path.read_text(encoding="utf-8") != "deterministic":
            raise AssertionError("temporary self-test fixture was not deterministic")

    print("WA-04 pass contract self-tests passed.")


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
        print("WA-04 pass contract failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print("WA-04 pass contract passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
