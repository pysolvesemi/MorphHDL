#!/usr/bin/env python3
"""Exact lane/when repair scope, separately composed with the inherited seal.

This authenticates the immutable implementation introduction and the enumerated
review infrastructure. It does not authorize source by branch name or use test
results as a substitute for the complete HEAD/index/worktree overlay audit.
"""
from __future__ import annotations
import argparse
import importlib.util
import json
from pathlib import Path
import re

PREDECESSOR = "3b547ae5622ae212c17f6e67cc96924127a46a41"
IMPLEMENTATION = "1670d24b1f0d767359338490b4d40ad11d1b70e9"
CONTRACT = "morphhdl/contracts/lane-when-source-scope.json"
OVERLAY = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"
OVERLAY_CONTRACT = "morphhdl/contracts/increment-62-wa08-source-overlay.json"
# Exact paths for this repair's review infrastructure; no source root wildcard.
REVIEW_PATHS = frozenset((
    '.github/workflows/increment-61-one-file-per-component.yml',
    'morphhdl/scripts/check-increment-61-publication-artifacts.py',
    'morphhdl/scripts/test-increment-61-publication-artifacts.py',
    "morphhdl-passes/scripts/test-boundary-guard.sh",
    ".github/workflows/increment-60f-equivalence-closure.yml",
    ".github/workflows/increment-62-wa08-source-overlay.yml",
    "morphhdl/scripts/check-increment-61-source-review.py",
    "morphhdl/scripts/check-lane-when-increment61-source.py",
    # Exact ABI blocker repair; every source also requires the native/outer seals.
    "core/src/main/scala/spinal/core/internals/VerilogBase.scala",
    "core/src/main/scala/spinal/core/internals/PhaseVerilog.scala",
    "core/src/test/scala/spinal/core/internals/VerilogDeclarationPolicyStorageTests.scala",
    "morphhdl/src/test/scala/morphhdl/SignedDeclarationPublicationTests.scala",
    ".github/workflows/increment-60g-default-signed-verilog.yml",
    ".github/workflows/morphhdl-passes.yml",
    "docs/morphhdl/lane-when-inlining-repair.md",
    "morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md",
    "morphhdl-passes/scripts/check-boundary.sh",
    "morphhdl-passes/scripts/check-wa05-pass.py",
    "morphhdl-passes/scripts/check-wa10-general-expression.py",
    "morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json",
    "morphhdl/contracts/increment-55-native-change-review.json",
    "morphhdl/contracts/native-source-preservation.json",
    "morphhdl/scripts/check-increment-60f-artifacts.py",
    "morphhdl/scripts/check-lane-when-source-scope.py",
    "morphhdl/scripts/test-increment-59h-inherited-source-scope.py",
    "morphhdl/scripts/test-lane-when-source-scope.py",
    'docs/morphhdl/evidence/lane-when-inlining/SHA256SUMS',
    'docs/morphhdl/evidence/lane-when-inlining/after/conditions.v',
    'docs/morphhdl/evidence/lane-when-inlining/after/lane.v',
    'docs/morphhdl/evidence/lane-when-inlining/after/receivers.v',
    'docs/morphhdl/evidence/lane-when-inlining/before/conditions.v',
    'docs/morphhdl/evidence/lane-when-inlining/before/lane.v',
    'docs/morphhdl/evidence/lane-when-inlining/before/receivers.v',
    CONTRACT, OVERLAY, OVERLAY_CONTRACT,
))

MARKERS = {
    "core/src/main/scala/spinal/core/internals/ComponentEmitter.scala": (
        "def canInlineRepeatedWhenCondition(condition: Expression): Boolean = false",
        "n > 1 && !canInlineRepeatedWhenCondition(c)",
    ),
    "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala": (
        "override def canInlineRepeatedWhenCondition(condition: Expression): Boolean",
        "component, spinalConfig, condition, wrappersProvenRedundant)",
        "requiredBeforeDepthCut.containsKey(literal) || !wrappersProvenRedundant.containsKey(literal)",
    ),
    "core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala": (
        "target.getTags().forall(_ eq noBackendCombMerge)",
        "if (!isEnabled(config) || !approved.containsKey(expression)",
        "expression.getTypeObject != TypeBool", "nodes > 64",
        "nativeConditions == 1 && leaves >= 1 && leaves <= 32",
        "leaves.toLong * nodes <= 256", "count.intValue == 1",
        "ParameterizedWidth.expressionOf(target).isEmpty", "dataAssignments == 1",
        "lo <= otherHi && otherLo <= hi", "referenceRequired", "isUnannotated",
    ),
    "morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala": (
        "conditionSourceIntent.exists(_.permits(alias))", "WA10-CONDITION-USER-NAME",
        "NativeWireExpressionCodec.fixedWidthTree(candidate.sourceExpression)",
        "conditionScopeWithin(statement.parentScope, alias.parentScope)",
        "WA10-CONDITION-BLOCKING-DEPENDENCY", "renderedUses.toLong * sourceSize > 256",
        "new NamedWireAliasNativePhase().expressionRemovalBlocker(",
        "NativePureExpressionCopy(candidate.sourceExpression)",
        "replacements != candidate.receiverOccurrenceCount", "if (remaining != 0)",
        "when.cond = expression", "candidate.assignment.removeStatement()",
        "candidate.alias.removeStatement()", "case value: BaseType if !value.isVital",
        "captured && removable.containsKey(value)",
    ),
    "morphhdl-passes/examples/UnnamedWireExpressionNativeBridge.scala": (
        "conditionSourceIntent: Option[NativeConditionSourceIntent] = None",
        "new NamedWireExpressionNativePhase(unnamedOnly = true, conditionSourceIntent)",
    ),
    "morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala": (
        "val sourceIntent = new NativeConditionSourceIntent",
        "new ProductionWireAssignmentPhase(sourceIntent)",
        "phases.indexWhere(_.isInstanceOf[PhaseRemoveUselessStuff])",
        "firstLiveness < 0 || firstLiveness >= cleanup(2)",
        "phases.insert(firstLiveness, sourceIntent)",
        "new UnnamedWireExpressionNativePhase(Some(sourceIntent))",
        "new NamedWireExpressionNativePhase(conditionSourceIntent = Some(sourceIntent))",
    ),
}

def require(ok: bool, message: str) -> None:
    if not ok:
        raise RuntimeError("LANE-WHEN-SCOPE: " + message)


def outer_overlay(root: Path):
    spec = importlib.util.spec_from_file_location("lane_when_outer", root / OVERLAY)
    require(spec is not None and spec.loader is not None, "missing outer source verifier")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_inventory(value: dict, actual: set[str]) -> None:
    require(isinstance(value, dict) and set(value) == {
        "schema_version", "predecessor", "implementation_source", "implementation_paths", "review_paths"},
        "invalid review schema")
    require(value["schema_version"] == 1 and value["predecessor"] == PREDECESSOR and
            value["implementation_source"] == IMPLEMENTATION, "changed immutable source anchors")
    for field in ("implementation_paths", "review_paths"):
        paths = value[field]
        require(isinstance(paths, list) and bool(paths) and
                all(isinstance(path, str) for path in paths) and paths == sorted(set(paths)),
                "unordered, empty, duplicate or invalid " + field)
        require(all(path and not Path(path).is_absolute() and ".." not in Path(path).parts and
                    Path(path).as_posix() == path for path in paths), "noncanonical review path")
    require(set(value["implementation_paths"]) == actual, "implementation introduction inventory differs")
    require(set(value["review_paths"]) == REVIEW_PATHS, "review infrastructure inventory differs")
    require(not actual.intersection(REVIEW_PATHS), "implementation and review inventories overlap")


def safety_failures(path: str, text: str) -> list[str]:
    errors = ["missing safety marker: " + marker for marker in MARKERS[path] if marker not in text]
    # Fixture generators share one adapter file, but they do not select compiler
    # candidates. Inspect the real phase/policy region, not those test factories.
    code = text
    for fixture_boundary in ("object ParameterizedStreamFifoNamedExpressionPassWitness",
                             "object ParameterizedStreamFifoExpressionPassWitness"):
        code = code.split(fixture_boundary, 1)[0]
    code = re.sub(r"/\*.*?\*/|//[^\n]*", "", code, flags=re.S)
    for pattern in (r'"(?:_zz_[^"]*|when_[^"]*_l\d+[^"]*)"',
                    r'"(?:LaneExpressionExample|DisplayControllerProgressiveTimingGenerator)"',
                    r'\b(?:Pattern\.compile|parseVerilog|readAllBytes|readString|fromFile)\b'):
        if re.search(pattern, code): errors.append("forbidden name/text candidate mechanism")
    return errors


def verify(root: Path) -> dict:
    root = root.resolve()
    outer = outer_overlay(root)
    sealed = outer.verify(root)
    entries = {entry["path"]: entry for entry in sealed["files"]}
    require(CONTRACT in entries, "review contract is not in the exact outer seal")
    value = json.loads(outer.regular(root, CONTRACT))
    outer.git(root, "merge-base", "--is-ancestor", PREDECESSOR, IMPLEMENTATION)
    outer.git(root, "merge-base", "--is-ancestor", IMPLEMENTATION, "HEAD")
    actual = {path.decode() for path in outer.git(root, "diff", "--no-renames", "--name-only", "-z",
                                                PREDECESSOR, IMPLEMENTATION).split(b"\0") if path}
    validate_inventory(value, actual)
    for path in REVIEW_PATHS - {OVERLAY, OVERLAY_CONTRACT}:
        if outer.governed(path): require(path in entries, "unsealed review path: " + path)
        raw = outer.regular(root, path, entries.get(path, {}).get("mode", "100644"))
        require(outer.frozen(root, "HEAD", path) == raw and
                outer.git(root, "show", ":" + path) == raw, "uncommitted or staged review path: " + path)
    for path in MARKERS:
        require(path in entries, "unsealed production path: " + path)
        errors = safety_failures(path, outer.regular(root, path).decode())
        require(not errors, path + ": " + repr(errors))
    return value


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--print-paths", action="store_true")
    args = parser.parse_args()
    value = verify(args.repo_root)
    if args.print_paths:
        print("\n".join(sorted(set(value["implementation_paths"]) | REVIEW_PATHS)))
    else:
        print("LANE_WHEN_SOURCE_SCOPE_PASS implementation_paths=" + str(len(value["implementation_paths"])) +
              " review_paths=" + str(len(REVIEW_PATHS)))

if __name__ == "__main__":
    main()
