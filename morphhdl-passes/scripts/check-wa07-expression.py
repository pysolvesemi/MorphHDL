#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from pathlib import Path

FORBIDDEN = (
    re.compile(r"\b(?:StreamFifo(?:CC)?|ParameterizedStreamFifo)\b"),
    re.compile(r"\blogicalName\b"),
    re.compile(r"_zz_"),
    re.compile(r"\bspinal\."),
    re.compile(r"\b(?:parseVerilog|generatedVerilog|emittedVerilog|verilogText)\b", re.I),
)

REQUIRED_PASS = (
    "object UnnamedWireExpressionEliminationPass",
    "PassId.UnnamedWireExpressionElimination",
    "NameOrigin.Unnamed",
    "DriverKind.Continuous",
    "UnnamedWireExpressionSafetyReason.DriverNotContinuous",
    "UnnamedWireExpressionSafetyReason.ReceiverNotContinuous",
    "value.directReference.nonEmpty",
    "cloneReplacement",
    "RtlExpr.Resize",
    "inlinedExpressions",
)
REQUIRED_CONFIG = (
    "final case class WireAliasPassConfiguration(enabled: Boolean = false)",
    "PassId.UnnamedWireAliasElimination",
    "PassId.NamedWireAliasElimination",
    "PassId.UnnamedWireExpressionElimination",
)
REQUIRED_TESTS = (
    "continuous unnamed expression is copied to its receiver and removed",
    "direct reference remains owned by the preceding alias pass",
    "procedural definition is retained",
    "procedural receiver is retained to preserve always-block scheduling",
    "fanout receives independent expression reference identities",
    "fixed point is idempotent",
    "complete WIDTH DEPTH domain",
)

def require(path: Path, text: str, markers: tuple[str, ...]) -> list[str]:
    return [f"{path}: missing {marker!r}" for marker in markers if marker not in text]

def check(root: Path) -> list[str]:
    paths = {
        "pass": root / "morphhdl-passes/src/main/scala/morphhdl/passes/transform/UnnamedWireExpressionEliminationPass.scala",
        "config": root / "morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala",
        "pipeline": root / "morphhdl-passes/src/main/scala/morphhdl/passes/pipeline/WireAliasPassPipeline.scala",
        "tests": root / "morphhdl-passes/src/test/scala/morphhdl/passes/transform/UnnamedWireExpressionEliminationPassSpec.scala",
        "source": root / "morphhdl-passes/examples/ParameterizedStreamFifo.scala",
        "regression": root / "morphhdl-passes/scripts/run-wa07-regression.sh",
        "roadmap": root / "morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md",
        "workflow": root / ".github/workflows/morphhdl-passes.yml",
        "manifest": root / "morphhdl-passes/tests/formal/wire_assignment_ir/manifest.json",
    }
    failures: list[str] = []
    for path in paths.values():
        if not path.is_file():
            failures.append(f"missing {path.relative_to(root)}")
    if failures:
        return failures
    pass_text = paths["pass"].read_text(encoding="utf-8")
    config_text = paths["config"].read_text(encoding="utf-8")
    pipeline_text = paths["pipeline"].read_text(encoding="utf-8")
    failures += require(paths["pass"].relative_to(root), pass_text, REQUIRED_PASS)
    failures += require(paths["config"].relative_to(root), config_text, REQUIRED_CONFIG)
    failures += require(paths["tests"].relative_to(root), paths["tests"].read_text(encoding="utf-8"), REQUIRED_TESTS)
    if "eliminateUnnamedAliases" in config_text or "eliminateNamedAliases" in config_text:
        failures.append("public configuration still exposes a per-pass flag")
    for pattern in FORBIDDEN:
        match = pattern.search(pass_text + "\n" + pipeline_text)
        if match:
            failures.append(f"generic pass source contains forbidden marker {match.group(0)!r}")
    order = (
        pipeline_text.find("case PassId.UnnamedWireAliasElimination"),
        pipeline_text.find("case PassId.NamedWireAliasElimination"),
        pipeline_text.find("case PassId.UnnamedWireExpressionElimination"),
    )
    if min(order) < 0 or list(order) != sorted(order):
        failures.append("pipeline order is not unnamed alias, named alias, unnamed expression")
    source_text = paths["source"].read_text(encoding="utf-8")
    if "temporary := source | source" not in source_text:
        failures.append("common StreamFifo witness has no behavior-neutral expression wire")
    roadmap = paths["roadmap"].read_text(encoding="utf-8")
    if "WA-07 — Unified pass control and unnamed continuous-expression elimination" not in roadmap:
        failures.append("WA-07 roadmap scope is missing")
    if "WA-08 — Final MorphHDL IR-stage production handoff" not in roadmap:
        failures.append("the preserved production-handoff increment is missing")
    workflow = paths["workflow"].read_text(encoding="utf-8")
    for marker in ("check-wa07-expression.py --self-test", "run-wa07-regression.sh"):
        if marker not in workflow:
            failures.append(f"workflow is missing {marker}")
    manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
    ids = {item["pass_id"] for item in manifest["shared_witness"]["future_pass_outputs"]}
    for expected in (
        "wire-expression-unnamed",
        "wire-alias-unnamed+wire-alias-named+wire-expression-unnamed",
    ):
        if expected not in ids:
            failures.append(f"formal manifest is missing {expected}")
    return sorted(failures)

def self_test() -> None:
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "sample"
        path.write_text("alpha beta", encoding="utf-8")
        assert require(path, path.read_text(), ("alpha",)) == []
        assert require(path, path.read_text(), ("missing",))
    print("WA-07 expression guard self-test passed.")

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    root = Path(__file__).resolve().parents[2]
    failures = check(root)
    if failures:
        for failure in failures:
            print(f"WA07-CONTRACT: {failure}", file=sys.stderr)
        return 1
    print("WA-07 unified control and expression contracts passed.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
