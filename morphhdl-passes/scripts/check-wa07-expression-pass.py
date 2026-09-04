#!/usr/bin/env python3
"""Static and mutation-tested contract guard for WA-07."""

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
        "WA07-COMPONENT-SPECIAL-CASE",
        "canonical pass source must not recognize a component or witness class",
        re.compile(r"\b(?:StreamFifo(?:CC)?|ParameterizedStreamFifo)\b"),
    ),
    TextRule(
        "WA07-MODULE-NAME-RECOGNITION",
        "canonical pass source must not inspect logical module names",
        re.compile(r"\blogicalName\b"),
    ),
    TextRule(
        "WA07-SPINAL-COUPLING",
        "canonical pass source must depend on MorphHDL IR rather than Spinal implementation classes",
        re.compile(r"\bspinal\."),
    ),
    TextRule(
        "WA07-EMITTED-NAME-RECOGNITION",
        "canonical pass source must not recognize emitted temporary identifiers",
        re.compile(r"_zz_"),
    ),
    TextRule(
        "WA07-FILE-TEXT-INGRESS",
        "canonical pass source must not read or postprocess generated files",
        re.compile(
            r"\b(?:scala\.io|java\.io|java\.nio\.(?:file|channels))\b|"
            r"\b(?:fromFile|readAllBytes|readString|newInputStream)\s*\("
        ),
    ),
    TextRule(
        "WA07-GENERATED-HDL-PARSER",
        "canonical pass source must not parse or reconstruct generated HDL",
        re.compile(
            r"\b(?:parseVerilog|parseGeneratedHdl|generatedVerilog|emittedVerilog|verilogText)\b",
            re.IGNORECASE,
        ),
    ),
    TextRule(
        "WA07-REGEX-CANDIDATE-DISCOVERY",
        "canonical pass source must not discover candidates with regular expressions",
        re.compile(
            r"\b(?:scala\.util\.matching\.Regex|java\.util\.regex|Pattern\.compile)\b|"
            r"(?:\"\"\".*?\"\"\"|\"(?:\\.|[^\"\\])*\")\s*\.r\b",
            re.DOTALL,
        ),
    ),
)

BRIDGE_RULES: tuple[TextRule, ...] = (
    TextRule(
        "WA07-BRIDGE-COMPONENT-RECOGNITION",
        "native witness must not inspect a component definition or instance name",
        re.compile(r"\.(?:definitionName|getName|getPartialName)\b"),
    ),
    TextRule(
        "WA07-BRIDGE-EMITTED-NAME-RECOGNITION",
        "native witness must not recognize emitted temporary identifiers",
        re.compile(r"_zz_"),
    ),
    TextRule(
        "WA07-BRIDGE-GENERATED-HDL-PARSER",
        "native witness must mutate exact graph identities rather than parse generated HDL",
        re.compile(
            r"\b(?:parseVerilog|generatedVerilog|emittedVerilog|verilogText)\b",
            re.IGNORECASE,
        ),
    ),
)

LEGACY_FLAG = re.compile(r"\b(?:eliminateUnnamedAliases|eliminateNamedAliases)\b")

REQUIRED_API_MARKERS: tuple[str, ...] = (
    "val UnnamedWireExpressionElimination",
    "val allWireAssignmentPasses",
    "val enabled: Boolean",
    "if (enabled) PassId.allWireAssignmentPasses else Vector.empty",
    "private[morphhdl] def selectedForTesting",
    "final case class EliminatedWireExpression",
    "eliminatedExpressions: Vector[EliminatedWireExpression]",
)

REQUIRED_SOURCE_MARKERS: tuple[str, ...] = (
    "object UnnamedWireExpressionEliminationPass",
    "PassId.UnnamedWireExpressionElimination",
    "NameOrigin.Unnamed",
    "DriverKind.Continuous",
    "DriverKind.Procedural",
    "ReceiverProcedural",
    "DirectReferenceHandledElsewhere",
    "sourceReferences.foreach",
    "scopeIsAncestor",
    "createsCombinationalCycle",
    "RtlExpr.Resize",
    "cloneForReceiver",
    "ReferenceId.unsafe",
    "filterNot(_.id == aliasDriver.id)",
    "declarations.filterNot(_.id == aliasSymbol)",
)

REQUIRED_TEST_MARKERS: tuple[str, ...] = (
    "unnamed expression pass is disabled by the common flag by default",
    "binary expression is cloned into every continuous receiver",
    "literal RHS is an expression",
    "direct reference aliases remain the responsibility of the previous pass",
    "assignment to an unnamed temporary inside an always block is retained",
    "continuous temporary used by an always-block assignment is retained",
    "named and observable expression temporaries are outside the pass",
    "self-referential expression is retained",
    "deterministic and reaches an idempotent fixed point",
    "module names and source paths do not affect expression decisions",
    "shared parameterized witness proof contract covers the complete WIDTH domain",
    "invalid canonical input fails closed",
)

REQUIRED_PIPELINE_MARKERS: tuple[str, ...] = (
    "WireAliasPassConfiguration(enabled = true)",
    "PassId.allWireAssignmentPasses",
    "Vector(1, 1, 1)",
    "wire-alias-unnamed+wire-alias-named+wire-expression-unnamed",
    "one disabled flag executes no wire-assignment pass",
)

REQUIRED_EXPRESSION_BRIDGE_MARKERS: tuple[str, ...] = (
    "final class UnnamedWireExpressionNativePhase extends Phase",
    "assignment.parentScope eq alias.rootScopeStatement",
    "case _: BaseType",
    "allowedUse",
    "WA07-NATIVE-PROCEDURAL-OR-EXCLUDED-RECEIVER",
    "walkRemapDrivingExpressions",
    "UnnamedWireExpressionEliminationPass.run",
    "procedural_receiver_rewrites",
    "ParameterizedStreamFifoExpressionPassWitness",
)

REQUIRED_ALL_BRIDGE_MARKERS: tuple[str, ...] = (
    "final class AllWireAssignmentNativePhase extends Phase",
    "WireAliasPassConfiguration(enabled = true)",
    "PassId.allWireAssignmentPasses",
    "unnamed.impl(pc)",
    "named.impl(pc)",
    "expression.impl(pc)",
    "common_flag_enabled",
    "procedural_receiver_rewrites",
    "ParameterizedStreamFifoAllPassWitness",
)

REQUIRED_REGRESSION_MARKERS: tuple[str, ...] = (
    "wire-expression-unnamed.v",
    "wire-assignment-all.v",
    "wire-expression-unnamed-report.json",
    "wire-assignment-all-report.json",
    "ParameterizedStreamFifoExpressionPassWitness",
    "ParameterizedStreamFifoAllPassWitness",
    "common_flag_enabled",
    "procedural_receiver_rewrites",
    "cmp -s",
    "iverilog -g2001",
    "verilator --lint-only --language 1364-2001",
    "yosys -Q",
    "WA03_SIM_PASS",
    "WIDTH-1__DEPTH-1",
    "WIDTH-64__DEPTH-8",
)

REQUIRED_WORKFLOW_MARKERS: tuple[str, ...] = (
    "check-wa07-expression-pass.py --self-test",
    "check-wa07-expression-pass.py",
    "run-wa07-regression.sh",
    "UnnamedWireExpressionEliminationPassSpec",
    "WireAssignmentAllPassPipelineSpec",
    "ParameterizedStreamFifoExpressionPassWitness",
    "ParameterizedStreamFifoAllPassWitness",
    "wire-expression-unnamed.v",
    "wire-assignment-all.v",
    "validate_wire_assignment_equivalence.py --self-test",
    "--shared-witness",
    "--check-determinism",
    "actions/upload-artifact@v4",
)

REQUIRED_README_MARKERS: tuple[str, ...] = (
    "WA-07",
    "one product-facing `enabled` flag",
    "UnnamedWireExpressionEliminationPass",
    "DriverKind.Procedural",
    "always",
    "type fence",
    "wire-expression-unnamed.v",
    "wire-assignment-all.v",
    "512",
    "common pre-pass",
    "WA-08",
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
        return [f"{path}: WA07-ROADMAP: {error}"]

    failures: list[str] = []
    for item in ("WA-06", "WA-07", "WA-08"):
        if item not in entries:
            failures.append(f"{path}: WA07-ROADMAP: missing {item}")
    if failures:
        return failures

    wa06_checked, wa06_body = entries["WA-06"]
    wa07_checked, wa07_body = entries["WA-07"]
    wa08_checked, wa08_body = entries["WA-08"]
    if not wa06_checked or "**Status:** `COMPLETED`" not in wa06_body:
        failures.append(f"{path}: WA07-DEPENDENCY: WA-06 must remain completed")
    if PV58.search(pv_text) is None:
        failures.append(f"{path}: WA07-PV58: Increment 58 must remain completed")
    if not wa07_checked:
        failures.append(f"{path}: WA07-INCOMPLETE: implemented WA-07 must be checked")
    elif "**Status:** `COMPLETED`" not in wa07_body:
        failures.append(f"{path}: WA07-STATUS: checked WA-07 must be COMPLETED")
    if wa08_checked:
        failures.append(f"{path}: WA07-SCOPE: WA-07 must not complete WA-08")
    if "**Status:** `READY`" not in wa08_body:
        failures.append(f"{path}: WA07-NEXT-STATUS: WA-08 must be READY")

    required_scope = (
        "one `enabled` flag",
        "execute no wire-assignment pass",
        "execute unnamed direct aliases, named direct aliases, then unnamed continuous expression",
        "any pure canonical RHS expression",
        "every continuous receiver",
        "explicit type fence",
        "procedural drivers",
        "always",
        "shared parameterized StreamFifo",
        "one unchanged pre-pass reference",
        "512",
    )
    normalized_body = " ".join(wa07_body.lower().split())
    for marker in required_scope:
        normalized_marker = " ".join(marker.lower().split())
        if normalized_marker not in normalized_body:
            failures.append(
                f"{path}: WA07-ROADMAP-SCOPE: WA-07 entry is missing {marker!r}"
            )
    return failures


def manifest_failures(path: Path, value: object) -> list[str]:
    if not isinstance(value, dict):
        return [f"{path}: WA07-MANIFEST: root must be an object"]
    witness = value.get("shared_witness")
    if not isinstance(witness, dict):
        return [f"{path}: WA07-MANIFEST: shared_witness is missing"]
    slots = witness.get("future_pass_outputs")
    if not isinstance(slots, list):
        return [f"{path}: WA07-MANIFEST: future_pass_outputs is missing"]
    matching = [
        slot
        for slot in slots
        if isinstance(slot, dict) and slot.get("activation_item") == "WA-07"
    ]
    expected = [
        {
            "activation_item": "WA-07",
            "candidate": "morphhdl-passes/build/pass-outputs/wire-expression-unnamed.v",
            "pass_id": "wire-expression-unnamed",
        },
        {
            "activation_item": "WA-07",
            "candidate": "morphhdl-passes/build/pass-outputs/wire-assignment-all.v",
            "pass_id": "wire-alias-unnamed+wire-alias-named+wire-expression-unnamed",
        },
    ]
    if matching != expected:
        return [
            f"{path}: WA07-MANIFEST: expected WA-07 slots {expected}, observed {matching}"
        ]
    return []


def check_repository(root: Path) -> list[str]:
    pass_root = root / "morphhdl-passes"
    paths = {
        "api": pass_root / "src/main/scala/morphhdl/passes/api/PassContracts.scala",
        "source": pass_root / "src/main/scala/morphhdl/passes/transform/UnnamedWireExpressionEliminationPass.scala",
        "pipeline": pass_root / "src/main/scala/morphhdl/passes/pipeline/WireAliasPassPipeline.scala",
        "tests": pass_root / "src/test/scala/morphhdl/passes/transform/UnnamedWireExpressionEliminationPassSpec.scala",
        "pipeline_tests": pass_root / "src/test/scala/morphhdl/passes/pipeline/WireAssignmentAllPassPipelineSpec.scala",
        "flag_tests": pass_root / "src/test/scala/morphhdl/passes/api/AllPassConfigurationSpec.scala",
        "expression_bridge": pass_root / "examples/UnnamedWireExpressionNativeBridge.scala",
        "all_bridge": pass_root / "examples/AllWireAssignmentNativeBridge.scala",
        "regression": pass_root / "scripts/run-wa07-regression.sh",
        "roadmap": pass_root / "morphhdl-ir-wire-assignment-passes-todo.md",
        "pv_roadmap": root / "docs/morphhdl/parameterized-verilog-todo.md",
        "readme": pass_root / "README.md",
        "workflow": root / ".github/workflows/morphhdl-passes.yml",
        "manifest": pass_root / "tests/formal/wire_assignment_ir/manifest.json",
        "signatures": pass_root / "tests/formal_model/wire_assignment_ir/expected-signatures.json",
    }
    failures: list[str] = []
    for label, path in paths.items():
        if not path.is_file():
            failures.append(f"WA07-{label.upper()}-MISSING: {path.relative_to(root)}")
    if failures:
        return sorted(failures)

    texts = {
        key: path.read_text(encoding="utf-8")
        for key, path in paths.items()
        if path.suffix != ".json"
    }

    for key in ("source", "pipeline"):
        failures.extend(
            scan_text(paths[key].relative_to(root), texts[key], GENERIC_RULES)
        )
    if LEGACY_FLAG.search(texts["api"]):
        failures.append(
            f"{paths['api'].relative_to(root)}: WA07-LEGACY-PER-PASS-FLAG: public configuration must expose only enabled"
        )

    failures.extend(require_markers(
        paths["api"].relative_to(root), texts["api"], REQUIRED_API_MARKERS,
        "WA07-API-CONTRACT-MISSING",
    ))
    failures.extend(require_markers(
        paths["source"].relative_to(root), texts["source"], REQUIRED_SOURCE_MARKERS,
        "WA07-SOURCE-CONTRACT-MISSING",
    ))
    failures.extend(require_markers(
        paths["tests"].relative_to(root), texts["tests"], REQUIRED_TEST_MARKERS,
        "WA07-TEST-COVERAGE-MISSING",
    ))
    failures.extend(require_markers(
        paths["pipeline_tests"].relative_to(root), texts["pipeline_tests"],
        REQUIRED_PIPELINE_MARKERS, "WA07-PIPELINE-TEST-MISSING",
    ))

    for key, start_marker, end_marker, rules in (
        (
            "expression_bridge",
            "final class UnnamedWireExpressionNativePhase extends Phase",
            "final case class UnnamedWireExpressionNativeReport",
            BRIDGE_RULES,
        ),
        (
            "all_bridge",
            "final class AllWireAssignmentNativePhase extends Phase",
            "final case class AllWireAssignmentNativeReport",
            BRIDGE_RULES,
        ),
    ):
        text = texts[key]
        start = text.find(start_marker)
        end = text.find(end_marker)
        if start < 0 or end <= start:
            failures.append(
                f"{paths[key].relative_to(root)}: WA07-BRIDGE-BOUNDARY: unable to isolate native phase"
            )
            phase_text = text
        else:
            phase_text = text[start:end]
        failures.extend(scan_text(paths[key].relative_to(root), phase_text, rules))

    failures.extend(require_markers(
        paths["expression_bridge"].relative_to(root), texts["expression_bridge"],
        REQUIRED_EXPRESSION_BRIDGE_MARKERS, "WA07-EXPRESSION-BRIDGE-MISSING",
    ))
    failures.extend(require_markers(
        paths["all_bridge"].relative_to(root), texts["all_bridge"],
        REQUIRED_ALL_BRIDGE_MARKERS, "WA07-ALL-BRIDGE-MISSING",
    ))
    failures.extend(require_markers(
        paths["regression"].relative_to(root), texts["regression"],
        REQUIRED_REGRESSION_MARKERS, "WA07-REGRESSION-CONTRACT-MISSING",
    ))
    failures.extend(require_markers(
        paths["workflow"].relative_to(root), texts["workflow"],
        REQUIRED_WORKFLOW_MARKERS, "WA07-WORKFLOW-GATE-MISSING",
    ))
    failures.extend(require_markers(
        paths["readme"].relative_to(root), texts["readme"],
        REQUIRED_README_MARKERS, "WA07-README-CONTRACT-MISSING",
    ))
    failures.extend(
        roadmap_failures(
            paths["roadmap"].relative_to(root),
            texts["roadmap"],
            texts["pv_roadmap"],
        )
    )

    try:
        manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
        failures.extend(manifest_failures(paths["manifest"].relative_to(root), manifest))
    except (OSError, json.JSONDecodeError) as error:
        failures.append(f"{paths['manifest'].relative_to(root)}: WA07-MANIFEST: {error}")

    try:
        registry = json.loads(paths["signatures"].read_text(encoding="utf-8"))
        registered = registry.get("files", {}) if isinstance(registry, dict) else {}
        required_registered = (
            paths["api"],
            paths["source"],
            paths["pipeline"],
            paths["tests"],
            paths["pipeline_tests"],
            paths["flag_tests"],
            paths["expression_bridge"],
            paths["all_bridge"],
            paths["regression"],
            Path(__file__).resolve(),
        )
        for registered_path in required_registered:
            key = registered_path.relative_to(root).as_posix()
            if key not in registered:
                failures.append(
                    f"{paths['signatures'].relative_to(root)}: WA07-SIGNATURE-MISSING: {key}"
                )
    except (OSError, json.JSONDecodeError) as error:
        failures.append(f"{paths['signatures'].relative_to(root)}: WA07-SIGNATURES: {error}")

    return sorted(failures)


def run_self_test() -> None:
    allowed = """package morphhdl.passes.transform
import morphhdl.ir.v1.RtlExpr
object ExpressionPass { def run(value: RtlExpr) = value }
"""
    if scan_text(Path("Allowed.scala"), allowed, GENERIC_RULES):
        raise AssertionError("component-generic canonical expression source was rejected")

    mutations = (
        ("val selected = StreamFifo", "WA07-COMPONENT-SPECIAL-CASE"),
        ("module.logicalName == \"special\"", "WA07-MODULE-NAME-RECOGNITION"),
        ("import spinal.core._", "WA07-SPINAL-COUPLING"),
        ("symbol == \"_zz_1\"", "WA07-EMITTED-NAME-RECOGNITION"),
        ("java.nio.file.Files.readString(path)", "WA07-FILE-TEXT-INGRESS"),
        ("parseVerilog(verilogText)", "WA07-GENERATED-HDL-PARSER"),
        ("val matcher = \"alias.*\".r", "WA07-REGEX-CANDIDATE-DISCOVERY"),
    )
    for text, code in mutations:
        failures = scan_text(Path("Mutant.scala"), text, GENERIC_RULES)
        if not any(code in failure for failure in failures):
            raise AssertionError(f"mutation was not rejected by {code}")

    if LEGACY_FLAG.search("val eliminateNamedAliases = true") is None:
        raise AssertionError("legacy per-pass Boolean mutation was not detected")

    roadmap = """- [x] **WA-06 — Ordered**

  **Status:** `COMPLETED`.

- [x] **WA-07 — Expressions**

  **Status:** `COMPLETED`.
  one `enabled` flag; execute no wire-assignment pass; execute unnamed direct
  aliases, named direct aliases, then unnamed continuous expression; any pure
  canonical RHS expression; every continuous receiver; explicit type fence;
  procedural drivers; always; shared parameterized StreamFifo; one unchanged
  pre-pass reference; 512.

- [ ] **WA-08 — Handoff**

  **Status:** `READY`.
"""
    pv = "- [x] **Increment 58 — Retirement**\n"
    if roadmap_failures(Path("roadmap.md"), roadmap, pv):
        raise AssertionError("valid WA-07 completion state was rejected")
    if not roadmap_failures(
        Path("roadmap.md"),
        roadmap.replace("- [x] **WA-07", "- [ ] **WA-07"),
        pv,
    ):
        raise AssertionError("unchecked implemented WA-07 was not rejected")
    if not roadmap_failures(
        Path("roadmap.md"),
        roadmap.replace("**Status:** `READY`.", "**Status:** `BLOCKED` by WA-07."),
        pv,
    ):
        raise AssertionError("blocked WA-08 after completed WA-07 was not rejected")

    manifest = {
        "shared_witness": {
            "future_pass_outputs": [
                {
                    "activation_item": "WA-07",
                    "candidate": "morphhdl-passes/build/pass-outputs/wire-expression-unnamed.v",
                    "pass_id": "wire-expression-unnamed",
                },
                {
                    "activation_item": "WA-07",
                    "candidate": "morphhdl-passes/build/pass-outputs/wire-assignment-all.v",
                    "pass_id": "wire-alias-unnamed+wire-alias-named+wire-expression-unnamed",
                },
            ]
        }
    }
    if manifest_failures(Path("manifest.json"), manifest):
        raise AssertionError("valid WA-07 manifest slots were rejected")
    manifest["shared_witness"]["future_pass_outputs"][1]["pass_id"] = "wrong"
    if not manifest_failures(Path("manifest.json"), manifest):
        raise AssertionError("mutated WA-07 manifest slot was not rejected")

    print("WA-07 expression-pass contract self-tests passed.")


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
        print("WA-07 expression-pass contract failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print("WA-07 expression-pass contract passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
