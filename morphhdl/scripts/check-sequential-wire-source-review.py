#!/usr/bin/env python3
"""Exact PR190 successor review; never treat historical success as current proof."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BASE = "f5049ae2abfe5a47cd1fac3574ea08d630bd183f"
SOURCE = "d9c3f574ef7dc036ebdfd8e2078a512c40c5aed9"
BASE_TREE = "a189550421dd2eabd7ea82faaf2c3449960707ab"
SOURCE_TREE = "c062abc4b2be7f932fb7b6efc3d1750276689d43"
OUTER = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"
IMPLEMENTATION_PATHS = frozenset((
    '.github/workflows/sequential-wire-consumers.yml',
    'core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala',
    'core/src/test/scala/spinal/core/internals/SequentialWireEmitterTests.scala',
    'morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala',
    'morphhdl-passes/examples/UnnamedWireAliasNativeBridge.scala',
    'morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala',
    'morphhdl/src/test/scala/morphhdl/SequentialWireNativeTests.scala',
    'morphhdl/src/test/scala/morphhdl/examples/SequentialWireRetentionTests.scala',
    'repro/remaining-wires/README.md',
    'repro/remaining-wires/build.sbt',
    'repro/remaining-wires/check.py',
    'repro/remaining-wires/project/build.properties',
    'repro/remaining-wires/src/main/scala/RemainingWireMatrix.scala',
    'repro/remaining-wires/src/main/scala/RemainingWireRepro.scala',
    'repro/remaining-wires/src/main/scala/RemainingWireTrace.scala',
))
REVIEW_PATHS = frozenset((
    '.github/workflows/increment-62-wa08-source-overlay.yml',
    '.github/workflows/sequential-source-review-targeted.yml',
    '.github/workflows/sequential-wire-consumers.yml',
    'docs/morphhdl/sequential-wire-source-review.md',
    'morphhdl-passes/scripts/check-boundary.sh',
    'morphhdl-passes/scripts/test-boundary-guard.sh',
    'morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json',
    'morphhdl/contracts/increment-55-native-change-review.json',
    'morphhdl/contracts/increment-62-wa08-source-overlay.json',
    'morphhdl/contracts/lane-when-source-scope.json',
    'morphhdl/contracts/native-source-preservation.json',
    'morphhdl/scripts/check-increment-62-wa08-source-overlay.py',
    'morphhdl/scripts/check-increment-60f-artifacts.py',
    'morphhdl/scripts/check-increment-61-source-review.py',
    'morphhdl/scripts/check-lane-when-increment61-source.py',
    'morphhdl/scripts/check-lane-when-source-scope.py',
    'morphhdl/scripts/check-sequential-wire-source-review.py',
    'morphhdl/scripts/test-sequential-wire-source-review.py',
))
PRODUCTION_PATHS = frozenset((
    'core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala',
    'morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala',
    'morphhdl-passes/examples/UnnamedWireAliasNativeBridge.scala',
    'morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala',
))

# The source anchor fixes complete compiler bodies. These obligations explain
# the reviewed exception and also have independent deletion/injection controls.
SAFETY_MARKERS = {
    "core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala": (
        "private[internals] def directSelectBase(",
        "depth > 32", "base.component eq component",
        "base.getTypeObject == TypeUInt || base.getTypeObject == TypeBits",
        "ParameterizedWidth.expressionOf(base).isEmpty",
        "resize.isInstanceOf[ResizeUInt] || resize.isInstanceOf[ResizeBits]",
        "isUnannotated(resize)", "resize.size == resize.input.getWidth",
        "resize.getTypeObject == resize.input.getTypeObject",
        "ParameterizedWidth.resizeExpressionOf(resize).isEmpty",
        "case target: BaseType if fixedTargetBoundary(target)",
    ),
    "morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala": (
        "conditionSourceIntent.exists(_.permits(alias))", "WA10-CONDITION-USER-NAME",
        "target.isReg && target.clockDomain != null", "assignment.target eq target",
        "WA10-CONDITION-UNSUPPORTED-CONTROL", "WA10-CONDITION-BLOCKING-DEPENDENCY",
        "assignment.finalTarget.isComb && dependencies.exists",
        "controlled.size + registers.size", "renderedUses.toLong * sourceSize > 256",
        "replacements != candidate.receiverOccurrenceCount", "if (remaining != 0)",
        "resize.size < resize.input.getWidth",
    ),
    "morphhdl-passes/examples/UnnamedWireAliasNativeBridge.scala": (
        "sourceIntent: Option[NativeConditionSourceIntent]", "def this() = this(None)",
        "source.isInput && (source.parentScope eq candidate.component.dslBody)",
        "sourceIntent.exists(intent => !intent.permits(alias))", "WA04-NATIVE-SOURCE-INTENT",
        "WA04-NATIVE-SEQUENTIAL-SOURCE-INTENT", "packedTypeProof(alias, source)",
        "preservationMetadataAllows(alias, assignment)", "expressionRemovalBlocker(",
        "assignment.target eq assignment.finalTarget", "assignment.finalTarget.clockDomain != null",
        "candidate.useStatements.forall(allowedUse", "pc.components().toVector.flatMap(statementsOf)",
        "if (remaining != 0)", "aliasAssignment.removeStatement()", "alias.removeStatement()",
    ),
    "morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala": (
        "new UnnamedWireAliasNativePhase(Some(sourceIntent))",
        "new ProductionWireAssignmentPhase(sourceIntent)",
        "phases.insert(firstLiveness, sourceIntent)",
    ),
}


def require(ok: bool, message: str) -> None:
    if not ok:
        raise RuntimeError("SEQUENTIAL-WIRE-SOURCE: " + message)


def git(root: Path, *args: str) -> bytes:
    result = subprocess.run(["git", "--literal-pathspecs", *args], cwd=root,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    require(result.returncode == 0, "git failed: " + " ".join(args) + "\n" +
            result.stderr.decode(errors="replace"))
    return result.stdout


def changed(root: Path, before: str, after: str) -> set[str]:
    return {p.decode() for p in git(root, "diff", "--no-renames", "--name-only", "-z",
                                   before, after).split(b"\0") if p}


def load_outer(root: Path):
    spec = importlib.util.spec_from_file_location("sequential_review_outer", root / OUTER)
    require(spec is not None and spec.loader is not None, "missing outer checker")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def safety_failures(path: str, text: str) -> list[str]:
    errors = ["missing safety marker: " + m for m in SAFETY_MARKERS[path] if m not in text]
    code = text
    for boundary in ("object ParameterizedStreamFifoNamedExpressionPassWitness",
                     "object ParameterizedStreamFifoExpressionPassWitness",
                     "object ParameterizedStreamFifoUnnamedPassWitness"):
        code = code.split(boundary, 1)[0]
    code = re.sub(r"/\*.*?\*/|//[^\n]*", "", code, flags=re.S)
    for pattern in (r'"(?:_zz_[^"]*|when_[^"]*_l\d+[^"]*)"',
                    r'"(?:RemainingWireRepro|DisplayControllerProgressiveTimingGenerator)"',
                    r"\b(?:parseVerilog|Pattern\.compile|readAllBytes|readString|fromFile)\b"):
        if re.search(pattern, code):
            errors.append("forbidden application/name/text recognition")
    return errors


def verify(root: Path = ROOT, sealed: dict | None = None) -> dict:
    root = root.resolve()
    outer = load_outer(root)
    # Before any projection, authenticate every governed source in current
    # HEAD, index and worktree, including ignored additions and gitlinks.
    seal = sealed if sealed is not None else outer.verify(root)
    for anchor, tree in ((BASE, BASE_TREE), (SOURCE, SOURCE_TREE)):
        git(root, "merge-base", "--is-ancestor", anchor, "HEAD")
        require(git(root, "rev-parse", anchor + "^{tree}").decode().strip() == tree,
                "immutable source tree changed")
    require(changed(root, BASE, SOURCE) == IMPLEMENTATION_PATHS,
            "implementation introduction inventory changed")
    actual = changed(root, BASE, "HEAD")
    require(actual <= IMPLEMENTATION_PATHS | REVIEW_PATHS,
            "unreviewed successor paths: " + repr(sorted(actual - IMPLEMENTATION_PATHS - REVIEW_PATHS)))
    # Repro src/main files are tests, but only these EXACT enumerated files are
    # excluded from the production classifier; other source roots still fail.
    actual_production = {p for p in actual if p.startswith("morphhdl-passes/examples/") or
                         ("/src/main/" in p and p not in IMPLEMENTATION_PATHS - PRODUCTION_PATHS)}
    require(actual_production == PRODUCTION_PATHS, "unexpected compiler change")
    records = {e["path"]: e for e in seal["files"]}
    for path in (IMPLEMENTATION_PATHS | REVIEW_PATHS) - {OUTER, outer.CONTRACT}:
        if outer.governed(path):
            require(path in records, "successor path is absent from current source seal: " + path)
    for path in IMPLEMENTATION_PATHS - REVIEW_PATHS:
        raw = outer.regular(root, path)
        require(raw == git(root, "show", SOURCE + ":" + path),
                "qualified compiler or regression bytes changed: " + path)
        require(raw == git(root, "show", "HEAD:" + path) == git(root, "show", ":" + path),
                "source HEAD/index/worktree mismatch: " + path)
    for path in PRODUCTION_PATHS:
        errors = safety_failures(path, outer.regular(root, path).decode())
        require(not errors, path + ": " + repr(errors))
    # Keep the Increment61 dispatcher itself unchanged except for the exact
    # successor implementation digest. Its original contract and tests stay.
    dispatcher = "morphhdl/scripts/check-increment-61-source-review.py"
    successor = "morphhdl/scripts/check-lane-when-increment61-source.py"
    old_dispatcher = git(root, "show", BASE + ":" + dispatcher).decode()
    old_pin = hashlib.sha256(git(root, "show", BASE + ":" + successor)).hexdigest()
    new_pin = hashlib.sha256(outer.regular(root, successor)).hexdigest()
    require(old_dispatcher.count(old_pin) == 1, "original successor pin is missing")
    require(outer.regular(root, dispatcher).decode() == old_dispatcher.replace(old_pin, new_pin),
            "Increment61 dispatcher changed beyond its reviewed successor pin")
    # The reusable trigger addition must not alter the original failed job
    # graph, commands, needs edges, parameters, or any broader HDL gate.
    for path, revision in ((".github/workflows/increment-62-wa08-source-overlay.yml", BASE),
                           (".github/workflows/sequential-wire-consumers.yml", SOURCE)):
        before = git(root, "show", revision + ":" + path).decode()
        current = outer.regular(root, path).decode()
        require(current.split("permissions:", 1)[1] == before.split("permissions:", 1)[1],
                "original workflow permissions/jobs changed: " + path)
        require("  workflow_call:\n" in current, "missing reusable workflow trigger")
    return {"head": git(root, "rev-parse", "HEAD").decode().strip(),
            "base": BASE, "source": SOURCE, "production_paths": sorted(PRODUCTION_PATHS),
            "implementation_paths": sorted(IMPLEMENTATION_PATHS), "review_paths": sorted(REVIEW_PATHS)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--print-paths", action="store_true")
    args = parser.parse_args()
    result = verify()
    if args.print_paths:
        print("\n".join(sorted(IMPLEMENTATION_PATHS | REVIEW_PATHS)))
    else:
        print("SEQUENTIAL_WIRE_CURRENT_SOURCE_PASS compiler_files=4 head=" + result["head"])


if __name__ == "__main__":
    main()
