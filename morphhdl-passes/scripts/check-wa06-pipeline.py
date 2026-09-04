#!/usr/bin/env python3
"""Static and mutation-tested contract guard for the WA-06 ordered pipeline."""

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
        "WA06-COMPONENT-SPECIAL-CASE",
        "pipeline implementation must not recognize a library component or witness class",
        re.compile(r"\b(?:StreamFifo(?:CC)?|ParameterizedStreamFifo)\b"),
    ),
    TextRule(
        "WA06-MODULE-NAME-RECOGNITION",
        "pipeline implementation must not inspect canonical logical module names",
        re.compile(r"\blogicalName\b"),
    ),
    TextRule(
        "WA06-SPINAL-IMPLEMENTATION-COUPLING",
        "pipeline implementation must consume canonical MorphHDL IR rather than Spinal implementation classes",
        re.compile(r"\bspinal\."),
    ),
    TextRule(
        "WA06-EMITTED-NAME-RECOGNITION",
        "pipeline implementation must not recognize emitted temporary identifiers",
        re.compile(r"_zz_"),
    ),
    TextRule(
        "WA06-FILE-TEXT-INGRESS",
        "pipeline implementation must not read or postprocess generated files",
        re.compile(
            r"\b(?:scala\.io|java\.io|java\.nio\.(?:file|channels))\b|"
            r"\b(?:fromFile|readAllBytes|readString|newInputStream)\s*\("
        ),
    ),
    TextRule(
        "WA06-GENERATED-HDL-PARSER",
        "pipeline implementation must not parse or reconstruct generated HDL",
        re.compile(
            r"\b(?:parseVerilog|parseGeneratedHdl|generatedVerilog|emittedVerilog|verilogText)\b",
            re.IGNORECASE,
        ),
    ),
    TextRule(
        "WA06-REGEX-CANDIDATE-DISCOVERY",
        "pipeline implementation must not discover candidates with regular expressions",
        re.compile(
            r"\b(?:scala\.util\.matching\.Regex|java\.util\.regex|Pattern\.compile)\b|"
            r"(?:\"\"\".*?\"\"\"|\"(?:\\.|[^\"\\])*\")\s*\.r\b",
            re.DOTALL,
        ),
    ),
)

BRIDGE_RULES: tuple[TextRule, ...] = (
    TextRule(
        "WA06-BRIDGE-COMPONENT-RECOGNITION",
        "combined native witness must not inspect a component definition or instance name",
        re.compile(r"\.(?:definitionName|getName|getPartialName)\b"),
    ),
    TextRule(
        "WA06-BRIDGE-EMITTED-NAME-RECOGNITION",
        "combined native witness must not recognize emitted temporary identifiers",
        re.compile(r"_zz_"),
    ),
    TextRule(
        "WA06-BRIDGE-GENERATED-HDL-PARSER",
        "combined native witness must mutate exact graph identities rather than parse generated HDL",
        re.compile(
            r"\b(?:parseVerilog|generatedVerilog|emittedVerilog|verilogText)\b",
            re.IGNORECASE,
        ),
    ),
)

REQUIRED_SOURCE_MARKERS: tuple[str, ...] = (
    "final case class WireAliasPipelineResult",
    "object WireAliasPassPipeline",
    "WireAliasPassConfiguration()",
    "configuration.enabledPasses",
    "PassId.UnnamedWireAliasElimination",
    "PassId.NamedWireAliasElimination",
    "UnnamedWireAliasEliminationPass.run",
    "NamedWireAliasEliminationPass.run",
    "CanonicalIrPassAdapter.bind(handoff).design",
    "output = design",
    "PassExecutionStatus.Skipped",
    "PassExecutionStatus.Unchanged",
    "PassExecutionStatus.Changed",
    "PassExecutionStatus.Failed",
    "stages.map(_.eliminationReport.passId)",
    "stages.map(_.eliminationReport)",
)

REQUIRED_TEST_MARKERS: tuple[str, ...] = (
    "ordered pipeline is disabled by default",
    "either pass can run independently",
    "fixed unnamed-then-named order for alias chains and fanout",
    "deterministic and support byte-identical repeated emission",
    "idempotent IR at a fixed point",
    "invalid canonical input fails closed with atomic rollback",
    "surviving names metadata and reference identities remain unchanged",
    "component names and source paths do not affect pipeline decisions",
    "shared parameterized witness proof contract covers the complete WIDTH and DEPTH domain",
    "Vector(1, 1)",
)

REQUIRED_BRIDGE_MARKERS: tuple[str, ...] = (
    "final class OrderedWireAliasNativePhase extends Phase",
    "WireAliasPassPipeline.run",
    "WireAliasPassConfiguration.selectedForTesting",
    "PassId.UnnamedWireAliasElimination",
    "PassId.NamedWireAliasElimination",
    "pipeline.executedPasses != expected",
    "unnamedPhase.impl(pc)",
    "namedPhase.impl(pc)",
    "wire-alias-unnamed+wire-alias-named",
    "executed_before_name_allocation",
    "ParameterizedStreamFifoCombinedPassWitness",
    "OrderedWireAliasWitnessPhasePlan.install",
    "eliminated no unnamed alias",
    "eliminated no named alias",
)

REQUIRED_REGRESSION_MARKERS: tuple[str, ...] = (
    "wire-alias-unnamed.v",
    "wire-alias-named.v",
    "wire-alias-combined.v",
    "wire-alias-combined-report.json",
    "ParameterizedStreamFifoCombinedPassWitness",
    "WireAliasPassPipelineSpec",
    "cmp -s",
    "iverilog -g2001",
    "verilator --lint-only --language 1364-2001",
    "yosys -Q",
    "WA03_SIM_PASS",
    "WIDTH-1__DEPTH-1",
    "WIDTH-64__DEPTH-8",
)

REQUIRED_WORKFLOW_MARKERS: tuple[str, ...] = (
    "check-wa06-pipeline.py --self-test",
    "check-wa06-pipeline.py",
    "run-wa07-regression.sh",
    "WireAliasPassPipelineSpec",
    "ParameterizedStreamFifoCombinedPassWitness",
    "wire-alias-combined.v",
    "validate_wire_assignment_equivalence.py --self-test",
    "--shared-witness",
    "--check-determinism",
    "actions/upload-artifact@v4",
)

REQUIRED_README_MARKERS: tuple[str, ...] = (
    "WA-06",
    "WireAliasPassPipeline",
    "disabled by default",
    "historical direct-alias stages",
    "unnamed then named",
    "alias chains and fanout",
    "byte-identical repeated emission",
    "strict Verilog-2001",
    "512",
    "common pre-pass",
    "WA-07",
)

ROADMAP_ENTRY = re.compile(
    r"^- \[(?P<checked>[ xX])\] \*\*(?P<id>WA-[0-9]+)\s+—(?P<body>[\s\S]*?)(?=^- \[[ xX]\] \*\*WA-[0-9]+\s+—|\Z)",
    re.MULTILINE,
)
PV58 = re.compile(r"^- \[[xX]\] \*\*Increment 58\s+—", re.MULTILINE)


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


def roadmap_failures(path: Path, text: str, pv_text: str) -> list[str]:
    try:
        entries = roadmap_entries(text)
    except AssertionError as error:
        return [f"{path}: WA06-ROADMAP: {error}"]

    failures: list[str] = []
    for item in ("WA-05", "WA-06", "WA-07", "WA-08"):
        if item not in entries:
            failures.append(f"{path}: WA06-ROADMAP: missing {item}")
    if failures:
        return failures

    wa05_checked, wa05_body = entries["WA-05"]
    wa06_checked, wa06_body = entries["WA-06"]
    wa07_checked, wa07_body = entries["WA-07"]
    wa08_checked, wa08_body = entries["WA-08"]
    if not wa05_checked or "**Status:** `COMPLETED`" not in wa05_body:
        failures.append(f"{path}: WA06-DEPENDENCY: WA-05 must remain completed")
    if PV58.search(pv_text) is None:
        failures.append(f"{path}: WA06-PV58: Increment 58 must remain completed")
    if not wa06_checked or "**Status:** `COMPLETED`" not in wa06_body:
        failures.append(f"{path}: WA06-STATUS: WA-06 must remain completed")
    if not wa07_checked or "**Status:** `COMPLETED`" not in wa07_body:
        failures.append(f"{path}: WA06-SUCCESSOR: completed WA-07 must retain WA-06")
    if wa08_checked or "**Status:** `READY`" not in wa08_body:
        failures.append(f"{path}: WA06-NEXT-STATUS: WA-08 must remain open and READY")

    required_scope = (
        "optional MorphHDL-IR pipeline entrypoint",
        "historical",
        "unnamed-then-named",
        "alias chains",
        "fanout",
        "without parsing emitted Verilog",
        "deterministic reports",
        "idempotent IR",
        "byte-identical repeated emission",
        "strict Verilog-2001 legality",
        "synthesis",
        "formal equivalence",
        "common pre-pass StreamFifo reference",
    )
    for marker in required_scope:
        if marker.lower() not in wa06_body.lower():
            failures.append(
                f"{path}: WA06-ROADMAP-SCOPE: WA-06 entry is missing {marker!r}"
            )
    return failures


def manifest_failures(path: Path, value: object) -> list[str]:
    if not isinstance(value, dict):
        return [f"{path}: WA06-MANIFEST: root must be an object"]
    witness = value.get("shared_witness")
    if not isinstance(witness, dict):
        return [f"{path}: WA06-MANIFEST: shared_witness is missing"]
    slots = witness.get("future_pass_outputs")
    if not isinstance(slots, list):
        return [f"{path}: WA06-MANIFEST: future_pass_outputs is missing"]
    matching = [
        slot
        for slot in slots
        if isinstance(slot, dict) and slot.get("activation_item") == "WA-06"
    ]
    if len(matching) != 1:
        return [f"{path}: WA06-MANIFEST: expected exactly one WA-06 slot"]
    expected = {
        "activation_item": "WA-06",
        "candidate": "morphhdl-passes/build/pass-outputs/wire-alias-combined.v",
        "pass_id": "wire-alias-unnamed+wire-alias-named",
    }
    if matching[0] != expected:
        return [
            f"{path}: WA06-MANIFEST: WA-06 slot changed; expected {expected}, observed {matching[0]}"
        ]
    return []


def check_repository(root: Path) -> list[str]:
    pass_root = root / "morphhdl-passes"
    paths = {
        "source": pass_root / "src/main/scala/morphhdl/passes/pipeline/WireAliasPassPipeline.scala",
        "tests": pass_root / "src/test/scala/morphhdl/passes/pipeline/WireAliasPassPipelineSpec.scala",
        "bridge": pass_root / "examples/OrderedWireAliasNativeBridge.scala",
        "regression": pass_root / "scripts/run-wa06-regression.sh",
        "roadmap": pass_root / "morphhdl-ir-wire-assignment-passes-todo.md",
        "pv_roadmap": root / "docs/morphhdl/parameterized-verilog-todo.md",
        "readme": pass_root / "README.md",
        "workflow": root / ".github/workflows/morphhdl-passes.yml",
        "manifest": pass_root / "tests/formal/wire_assignment_ir/manifest.json",
        "signatures": pass_root / "tests/formal_model/wire_assignment_ir/expected-signatures.json",
        "wa05_guard": pass_root / "scripts/check-wa05-pass.py",
    }
    failures: list[str] = []
    for label, path in paths.items():
        if not path.is_file():
            failures.append(f"WA06-{label.upper()}-MISSING: {path.relative_to(root)}")
    if failures:
        return sorted(failures)

    source_text = paths["source"].read_text(encoding="utf-8")
    test_text = paths["tests"].read_text(encoding="utf-8")
    bridge_text = paths["bridge"].read_text(encoding="utf-8")
    regression_text = paths["regression"].read_text(encoding="utf-8")
    roadmap_text = paths["roadmap"].read_text(encoding="utf-8")
    pv_text = paths["pv_roadmap"].read_text(encoding="utf-8")
    readme_text = paths["readme"].read_text(encoding="utf-8")
    workflow_text = paths["workflow"].read_text(encoding="utf-8")

    failures.extend(scan_text(paths["source"].relative_to(root), source_text, GENERIC_RULES))
    failures.extend(require_markers(
        paths["source"].relative_to(root), source_text, REQUIRED_SOURCE_MARKERS,
        "WA06-SOURCE-CONTRACT-MISSING",
    ))
    failures.extend(require_markers(
        paths["tests"].relative_to(root), test_text, REQUIRED_TEST_MARKERS,
        "WA06-TEST-COVERAGE-MISSING",
    ))
    phase_start = bridge_text.find("final class OrderedWireAliasNativePhase extends Phase")
    phase_end = bridge_text.find("final case class OrderedWireAliasNativeReport")
    if phase_start < 0 or phase_end <= phase_start:
        failures.append(
            f"{paths['bridge'].relative_to(root)}: WA06-BRIDGE-BOUNDARY: unable to isolate ordered native phase"
        )
        bridge_phase_text = bridge_text
    else:
        bridge_phase_text = bridge_text[phase_start:phase_end]
    failures.extend(scan_text(paths["bridge"].relative_to(root), bridge_phase_text, BRIDGE_RULES))
    failures.extend(require_markers(
        paths["bridge"].relative_to(root), bridge_text, REQUIRED_BRIDGE_MARKERS,
        "WA06-BRIDGE-CONTRACT-MISSING",
    ))
    failures.extend(require_markers(
        paths["regression"].relative_to(root), regression_text, REQUIRED_REGRESSION_MARKERS,
        "WA06-REGRESSION-CONTRACT-MISSING",
    ))
    failures.extend(require_markers(
        paths["workflow"].relative_to(root), workflow_text, REQUIRED_WORKFLOW_MARKERS,
        "WA06-WORKFLOW-GATE-MISSING",
    ))
    failures.extend(require_markers(
        paths["readme"].relative_to(root), readme_text, REQUIRED_README_MARKERS,
        "WA06-README-CONTRACT-MISSING",
    ))
    failures.extend(
        roadmap_failures(paths["roadmap"].relative_to(root), roadmap_text, pv_text)
    )

    try:
        manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
        failures.extend(manifest_failures(paths["manifest"].relative_to(root), manifest))
    except (OSError, json.JSONDecodeError) as error:
        failures.append(f"{paths['manifest'].relative_to(root)}: WA06-MANIFEST: {error}")

    try:
        registry = json.loads(paths["signatures"].read_text(encoding="utf-8"))
        registered = registry.get("files", {}) if isinstance(registry, dict) else {}
        required_registered = (
            paths["source"],
            paths["tests"],
            paths["bridge"],
            paths["regression"],
            paths["wa05_guard"],
            Path(__file__).resolve(),
        )
        for registered_path in required_registered:
            key = registered_path.relative_to(root).as_posix()
            if key not in registered:
                failures.append(
                    f"{paths['signatures'].relative_to(root)}: WA06-SIGNATURE-MISSING: {key}"
                )
    except (OSError, json.JSONDecodeError) as error:
        failures.append(f"{paths['signatures'].relative_to(root)}: WA06-SIGNATURES: {error}")

    return sorted(failures)


def run_self_test() -> None:
    allowed = """package morphhdl.passes.pipeline
import morphhdl.ir.v1.Design
object Pipeline { def run(value: Design) = value.modules.map(_.id) }
"""
    if scan_text(Path("Allowed.scala"), allowed, GENERIC_RULES):
        raise AssertionError("component-generic canonical pipeline source was rejected")

    mutations = (
        ("val selected = StreamFifo", "WA06-COMPONENT-SPECIAL-CASE"),
        ("module.logicalName == \"special\"", "WA06-MODULE-NAME-RECOGNITION"),
        ("import spinal.core._", "WA06-SPINAL-IMPLEMENTATION-COUPLING"),
        ("symbol == \"_zz_1\"", "WA06-EMITTED-NAME-RECOGNITION"),
        ("java.nio.file.Files.readString(path)", "WA06-FILE-TEXT-INGRESS"),
        ("parseVerilog(verilogText)", "WA06-GENERATED-HDL-PARSER"),
        ("val matcher = \"alias.*\".r", "WA06-REGEX-CANDIDATE-DISCOVERY"),
    )
    for text, code in mutations:
        failures = scan_text(Path("Mutant.scala"), text, GENERIC_RULES)
        if not any(code in failure for failure in failures):
            raise AssertionError(f"mutation was not rejected by {code}")

    roadmap = """- [x] **WA-05 — Named**

  **Status:** `COMPLETED`.

- [x] **WA-06 — Ordered**

  **Status:** `COMPLETED`.
  optional MorphHDL-IR pipeline entrypoint; historical direct stages use the
  unnamed-then-named order; alias chains and fanout; without parsing emitted
  Verilog; deterministic reports; idempotent IR; byte-identical repeated
  emission; strict Verilog-2001 legality; synthesis; formal equivalence;
  common pre-pass StreamFifo reference.

- [x] **WA-07 — Expressions**

  **Status:** `COMPLETED`.

- [ ] **WA-08 — Handoff**

  **Status:** `READY`.
"""
    pv_roadmap = "- [x] **Increment 58 — Retirement**\n"
    if roadmap_failures(Path("roadmap.md"), roadmap, pv_roadmap):
        raise AssertionError("valid WA-06 completion state was rejected")
    if not roadmap_failures(
        Path("roadmap.md"),
        roadmap.replace("- [x] **WA-06", "- [ ] **WA-06"),
        pv_roadmap,
    ):
        raise AssertionError("unchecked implemented WA-06 was not rejected")
    if not roadmap_failures(
        Path("roadmap.md"),
        roadmap.replace("**Status:** `READY`.", "**Status:** `BLOCKED` by WA-07."),
        pv_roadmap,
    ):
        raise AssertionError("blocked WA-08 after completed WA-07 was not rejected")

    manifest = {
        "shared_witness": {
            "future_pass_outputs": [
                {
                    "activation_item": "WA-06",
                    "candidate": "morphhdl-passes/build/pass-outputs/wire-alias-combined.v",
                    "pass_id": "wire-alias-unnamed+wire-alias-named",
                }
            ]
        }
    }
    if manifest_failures(Path("manifest.json"), manifest):
        raise AssertionError("valid WA-06 manifest slot was rejected")
    manifest["shared_witness"]["future_pass_outputs"][0]["pass_id"] = "wrong"
    if not manifest_failures(Path("manifest.json"), manifest):
        raise AssertionError("mutated WA-06 manifest slot was not rejected")

    print("WA-06 pipeline contract self-tests passed.")


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
        print("WA-06 pipeline contract failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print("WA-06 pipeline contract passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
