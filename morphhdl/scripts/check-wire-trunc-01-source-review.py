#!/usr/bin/env python3
"""Cumulative exact-source review for WIRE-TRUNC-01.

Current source is authenticated before any historical projection. The prior CDC
reviewers are replayed unchanged at their last reviewed integration tree; the
later WIRE-TRUNC roadmap-only base remains the source-inventory predecessor.
This successor then seals the exact WIRE-TRUNC-01 compiler, tests and workflows
at a single source anchor. No historical receipt is treated as current behavior
proof.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import stat
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
BASE = "09880c538c4cf83022f4a1bb1dd16b43ea81a751"
BASE_TREE = "6216cf799cc51c5a5f815d08f16e435c6b48ddc7"
HISTORICAL_BASE = "67d944fd6fa1bd7f3dc65bdc6439ce31e59283ca"
HISTORICAL_TREE = "d19d225af835fcba77bcf96e142cf8a79f4a0fb6"
SOURCE = "148791429cbfd74cadaefe40badfb3741ceeb0e1"
SOURCE_TREE = "b7d694e8b7f84a9ed42c6c14d0dcd82e4dd42180"
SELF = "morphhdl/scripts/check-wire-trunc-01-source-review.py"
CONTRACT = "morphhdl/contracts/wire-trunc-01-source-review.json"
CONTRACT_BLOB = "a78a92de9130a4af947e582ba975618599b7553d"

SOURCE_PATHS = frozenset((
    ".github/workflows/cdc-wire-fixed-point.yml",
    ".github/workflows/wire-trunc-01.yml",
    "core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala",
    "morphhdl-passes/scripts/check-low-bit-truncation.py",
    "morphhdl-passes/src/main/scala/morphhdl/passes/transform/AssignmentLowBitTruncationProof.scala",
    "morphhdl-passes/src/test/scala/morphhdl/passes/transform/AssignmentLowBitTruncationProofSpec.scala",
    "morphhdl/src/main/scala/morphhdl/examples/AssignmentLowBitTruncationNativePhase.scala",
    "morphhdl/src/main/scala/morphhdl/examples/AssignmentLowBitTruncationReceiverPhase.scala",
    "morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedNativeResize.scala",
    "morphhdl/src/main/scala/spinal/core/internals/WireTruncationNativeAccess.scala",
    "morphhdl/src/test/scala/morphhdl/examples/AssignmentLowBitTruncationNativeTests.scala",
    "morphhdl/src/test/scala/nativeapplication/LowBitTruncationArtifactWriter.scala",
    "morphhdl/src/test/scala/spinal/core/internals/NativeWidthPublicationSafetyTests.scala",
    SELF,
))

PRODUCTION_PATHS = frozenset((
    "core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala",
    "morphhdl-passes/src/main/scala/morphhdl/passes/transform/AssignmentLowBitTruncationProof.scala",
    "morphhdl/src/main/scala/morphhdl/examples/AssignmentLowBitTruncationNativePhase.scala",
    "morphhdl/src/main/scala/morphhdl/examples/AssignmentLowBitTruncationReceiverPhase.scala",
    "morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedNativeResize.scala",
    "morphhdl/src/main/scala/spinal/core/internals/WireTruncationNativeAccess.scala",
))

SAFETY_MARKERS = {
    "core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala": (
        "def narrowingContext(source: Expression, target: BaseType)",
        "own.minimum >= receiver.maximum",
        "def independentBlockingInputs(root: Expression)",
        "(!base.isInput && !base.isReg)",
        "planAssignment(assignment, target)",
    ),
    "morphhdl-passes/src/main/scala/morphhdl/passes/transform/AssignmentLowBitTruncationProof.scala": (
        "receiverMaximum > sourceWidth",
        "value.origin != NameOrigin.Unnamed && value.origin != NameOrigin.Generated",
        "RtlExpr.PartSelect(value, IntExpr.Literal(offset), width)",
        "offset == 0 && width == receiverWidth",
        "RtlExpr.Resize(value, width, Signedness.Unsigned)",
        "definitions.map(_.symbol).distinct.size != definitions.size",
    ),
    "morphhdl/src/main/scala/morphhdl/examples/AssignmentLowBitTruncationNativePhase.scala": (
        "hasFixedDeclaredWidth",
        "getDeclaredField(\"fixedWidth\")",
        "value == NameOrigin.Generated || value == NameOrigin.Unnamed",
        "sourceIntent.permits(alias)",
        "receiver.maximum > source.minimum",
        "case base: BaseType if rootWidth.parameters.isEmpty",
        "receiver.maximum <= rootWidth.minimum",
        "receiver.minimum < rootWidth.minimum",
        "used(spentCopies, alias) + count > 32",
        "used(spentNodes, alias) + cost > 256",
        "independentBlockingInputs(replacement, component)",
        "AssignmentLowBitTruncationProof.prove",
        "if (definitions.isEmpty && selectedBoundary.receiver.parameters.isEmpty) return None",
        "beginLowBitTruncationConsumption(component, assignment)",
        "completeLowBitTruncationConsumption(",
        "expressionRemovalBlocker",
    ),
    "morphhdl/src/main/scala/morphhdl/examples/AssignmentLowBitTruncationReceiverPhase.scala": (
        "WireTruncationNativeAccess.protectedCarrier(symbolicCarrier)",
        ".symbolicResizeTargetWidth(component, symbolicCarrier)",
        "value == NameOrigin.Generated || value == NameOrigin.Unnamed",
        "sourceIntent.permits(symbolicCarrier)",
        "WireTruncationNativeAccess.protectedCarrier(value)",
        "sourceIntent.permits(value)",
        "receiverWidth.maximum > fixedWidth.minimum",
        "receiverWidth.minimum >= fixedWidth.minimum",
        "NativeWireExpressionCodec.fixedWidthTree(value.source)",
        "AssignmentLowBitTruncationProof.prove(",
        "before, after, receiverParameter, fixedCarrier.getBitsWidth,",
        "value.source eq symbolicCarrier",
        "sameBoundary(target, symbolicCarrier)",
        "independentBlockingInputs(replacement, component)",
        "receiverStatements.size > 32",
        "size.toLong * receiverStatements.size > 256",
        "receiver.source = replacement",
        "carrier declarations themselves",
    ),
    "morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala": (
        "new AssignmentLowBitTruncationNativePhase(sourceIntent).impl(pc)",
        "new AssignmentLowBitTruncationReceiverPhase(sourceIntent).impl(pc)",
        "Keep the six-stage canonical order unchanged",
    ),
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedNativeResize.scala": (
        "val wireTruncationReplacement = new java.util.IdentityHashMap[Record, Expression]()",
        "def beginLowBitTruncationConsumption(",
        "if (!validFresh(component, record))",
        "record.targetWidth.parameters.isEmpty || record.sourceWidth.parameters.nonEmpty",
        "value.wireTruncationReplacement.put(record, null)",
        "def completeLowBitTruncationConsumption(",
        "replacementWidth.parameters.nonEmpty",
        "validConsumedFresh(component, value, record)",
        "record.assignment.source eq replacement",
        "def validConsumedDuringPublication(",
        "current != null && current.containsKey(record)",
        "consumed(value, record) && validConsumedFresh(component, value, record)",
        "validConsumedDuringPublication(component, value, record)",
        "if (!consumed(value, record))",
    ),
    "morphhdl/src/main/scala/spinal/core/internals/WireTruncationNativeAccess.scala": (
        "value.dontSimplify",
        "value.getTags().forall(_ eq noBackendCombMerge)",
        "if (component == null || target == null) None",
        "ExternalParameterizedNativeResize",
        ".targetWidthOf(component, target)",
        ".filter(_.parameters.nonEmpty)",
    ),
}

HISTORICAL_COMMANDS = (
    ("python3", "morphhdl/scripts/check-cdc-wire-source-review.py", "--self-test"),
    ("python3", "morphhdl/scripts/check-native-source-preservation.py"),
    ("python3", "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"),
    ("python3", "morphhdl/scripts/check-cdc-wire-regressions.py", "--self-test"),
)


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError("WIRE-TRUNC-01 source review: " + detail)


def git(root: Path, *args: str) -> bytes:
    result = subprocess.run(["git", "--literal-pathspecs", *args], cwd=root,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
    require(result.returncode == 0, "git failed: " + " ".join(args) + "\n" +
            result.stderr.decode(errors="replace"))
    return result.stdout


def changed(root: Path, before: str, after: str) -> set[str]:
    return {p.decode() for p in git(root, "diff", "--no-renames", "--name-only", "-z",
                                   before, after).split(b"\0") if p}


def regular(root: Path, path: str, mode: str = "100644") -> bytes:
    relative = Path(path)
    require(not relative.is_absolute() and ".." not in relative.parts, "invalid path: " + path)
    file = root / relative
    require(all(not (root / Path(*relative.parts[:i])).is_symlink()
                for i in range(1, len(relative.parts) + 1)), "linked source: " + path)
    require(file.is_file() and stat.S_ISREG(file.stat().st_mode), "missing source: " + path)
    require(mode in ("100644", "100755") and
            bool(file.stat().st_mode & 0o111) == (mode == "100755"), "mode differs: " + path)
    return file.read_bytes()


def normalize_helper(raw: bytes) -> bytes:
    raw = re.sub(rb'^SOURCE = "[^"]+"$', b'SOURCE = "SOURCE_ANCHOR"', raw,
                 count=1, flags=re.M)
    raw = re.sub(rb'^SOURCE_TREE = "[^"]+"$', b'SOURCE_TREE = "SOURCE_TREE_ANCHOR"', raw,
                 count=1, flags=re.M)
    return re.sub(rb'^CONTRACT_BLOB = "[^"]+"$',
                  b'CONTRACT_BLOB = "MANIFEST_BLOB"', raw, count=1, flags=re.M)


def contract(root: Path) -> dict:
    value = json.loads(regular(root, CONTRACT))
    require(set(value) == {"schema_version", "base", "base_tree", "source_commit",
                          "source_tree", "helper_source_blob", "source_paths"},
            "invalid manifest keys")
    require(value["schema_version"] == 1 and value["base"] == BASE and
            value["base_tree"] == BASE_TREE and value["source_commit"] == SOURCE and
            value["source_tree"] == SOURCE_TREE, "manifest anchors differ")
    require(re.fullmatch(r"[0-9a-f]{40}", value["helper_source_blob"]) is not None,
            "invalid helper source blob")
    require(value["source_paths"] == sorted(SOURCE_PATHS), "manifest source inventory differs")
    return value


def tree_entry(root: Path, ref: str, path: str) -> tuple[str, str] | None:
    rows = git(root, "ls-tree", "-z", ref, "--", path).split(b"\0")
    rows = [row for row in rows if row]
    if not rows:
        return None
    require(len(rows) == 1, "ambiguous tree path: " + path)
    metadata, actual = rows[0].split(b"\t", 1)
    require(actual.decode() == path, "tree path mismatch: " + path)
    mode, kind, blob = metadata.decode().split()
    require(kind == "blob" and mode in ("100644", "100755"), "unsupported tree entry: " + path)
    return mode, blob


def current_index(root: Path, path: str) -> tuple[str, str] | None:
    rows = [row for row in git(root, "ls-files", "--stage", "-z", "--", path).split(b"\0") if row]
    if not rows:
        return None
    require(len(rows) == 1, "ambiguous index entry: " + path)
    metadata, actual = rows[0].split(b"\t", 1)
    mode, blob, stage = metadata.decode().split()
    require(stage == "0" and actual.decode() == path, "unmerged index: " + path)
    return mode, blob


def safety_failures(path: str, text: str) -> list[str]:
    return ["missing safety marker: " + marker for marker in SAFETY_MARKERS.get(path, ())
            if marker not in text]


def replay_historical(root: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="wire-trunc-predecessor-") as directory:
        predecessor = Path(directory) / "baseline"
        git(root, "worktree", "add", "--detach", str(predecessor), HISTORICAL_BASE)
        try:
            for command in HISTORICAL_COMMANDS:
                result = subprocess.run(command, cwd=predecessor, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True, timeout=600)
                require(result.returncode == 0,
                        "historical gate failed at immutable predecessor: " +
                        " ".join(command) + "\n" + result.stdout)
        finally:
            git(root, "worktree", "remove", "--force", str(predecessor))


def verify(root: Path = ROOT, replay: bool = True) -> dict:
    root = root.resolve()
    value = contract(root)
    head = git(root, "rev-parse", "HEAD").decode().strip()
    require(git(root, "rev-parse", BASE + "^{tree}").decode().strip() == BASE_TREE,
            "baseline tree changed")
    require(git(root, "rev-parse", HISTORICAL_BASE + "^{tree}").decode().strip() == HISTORICAL_TREE,
            "historical CDC tree changed")
    git(root, "merge-base", "--is-ancestor", HISTORICAL_BASE, BASE)
    require(git(root, "rev-parse", SOURCE + "^{tree}").decode().strip() == SOURCE_TREE,
            "source-anchor tree changed")
    git(root, "merge-base", "--is-ancestor", BASE, SOURCE)
    git(root, "merge-base", "--is-ancestor", SOURCE, head)
    # Once a manifest exists in the feature lineage it remains an exact reviewed
    # path at later source anchors, but its final bytes are sealed independently
    # below rather than treated as implementation/test/workflow source bytes.
    expected_head = SOURCE_PATHS | {CONTRACT}
    require(changed(root, BASE, SOURCE) == expected_head,
            "source-anchor inventory differs: " + repr(sorted(changed(root, BASE, SOURCE) ^ expected_head)))
    require(changed(root, BASE, head) == expected_head,
            "current inventory differs: " + repr(sorted(changed(root, BASE, head) ^ expected_head)))
    source_helper = tree_entry(root, SOURCE, SELF)
    require(source_helper is not None and source_helper[1] == value["helper_source_blob"],
            "manifest does not pin the source reviewer blob")

    # The source anchor fixes every implementation/test/workflow byte. Only this
    # reviewer's three seal constants differ in the final seal commit.
    for path in sorted(SOURCE_PATHS):
        mode, source_blob = tree_entry(root, SOURCE, path) or (None, None)
        require(mode is not None, "source anchor lost: " + path)
        raw = regular(root, path, mode)
        if path == SELF:
            require(normalize_helper(raw) == normalize_helper(git(root, "show", SOURCE + ":" + path)),
                    "reviewer changed beyond seal constants")
        else:
            require(raw == git(root, "show", SOURCE + ":" + path),
                    "source changed after anchor: " + path)
        head_entry = tree_entry(root, head, path)
        index_entry = current_index(root, path)
        require(head_entry == index_entry, "HEAD/index identity differs: " + path)
        expected_blob = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
        require(head_entry == (mode, expected_blob), "HEAD/worktree identity differs: " + path)
        if path in PRODUCTION_PATHS:
            require(not safety_failures(path, raw.decode()), path + " safety markers differ")

    contract_raw = regular(root, CONTRACT)
    contract_entry = tree_entry(root, head, CONTRACT)
    require(contract_entry == current_index(root, CONTRACT), "manifest HEAD/index identity differs")
    require(contract_entry == ("100644", CONTRACT_BLOB), "sealed manifest blob changed")
    require(contract_entry == ("100644", hashlib.sha1(
        b"blob " + str(len(contract_raw)).encode() + b"\0" + contract_raw).hexdigest()),
        "manifest HEAD/worktree identity differs")
    dirty = git(root, "status", "--porcelain", "--untracked-files=all")
    require(not dirty, "source checkout is dirty: " + dirty.decode(errors="replace"))
    if replay:
        replay_historical(root)
    return {"head": head, "base": BASE, "source": SOURCE,
            "tree": SOURCE_TREE, "paths": sorted(SOURCE_PATHS),
            "production_paths": sorted(PRODUCTION_PATHS)}


def self_test(root: Path = ROOT) -> None:
    result = verify(root, replay=True)
    rejected = 0
    for path in result["production_paths"]:
        text = (root / path).read_text()
        marker = SAFETY_MARKERS[path][0]
        require(bool(safety_failures(path, text.replace(marker, "REMOVED_WIRE_TRUNC_GUARD"))),
                "marker deletion control failed: " + path)
        rejected += 1
    for foreign in ("core/src/main/scala/spinal/core/UnreviewedWireTrunc.scala",
                    ".github/workflows/wire-trunc-skip.yml"):
        require(foreign not in SOURCE_PATHS, "foreign path entered exact inventory")
        rejected += 1
    print("WIRE_TRUNC_01_SOURCE_CONTROLS_PASS rejected=" + str(rejected) +
          " historical_gates=pass current_behavioral_proof=separate")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--print-paths", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    result = verify()
    if args.print_paths:
        print("\n".join(result["paths"]))
    else:
        print("WIRE_TRUNC_01_SOURCE_PASS " + json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()