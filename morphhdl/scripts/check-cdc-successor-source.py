#!/usr/bin/env python3
"""Authenticate the exact PR189 successor before running frozen Increment61 audits.

This verifier never generates manifests or accepts a branch name as authority.
The cumulative WA08 source seal authenticates every current byte and source
anchor; this additional inventory limits the successor to its reviewed paths.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
TARGET = "f5049ae2abfe5a47cd1fac3574ea08d630bd183f"
PREVIOUS_CDC = "1fb79c663b3db9e6a162fd48c08632c24f63c73e"
COMMON_BASE = "27af65abbee0d2334d6be7a6e4e2408b8af32fd9"
REGRESSION_BASE = "5374b958f8f94114b1ed46a3069845d580886da9"
OVERLAY = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"
INC61 = "morphhdl/scripts/check-increment-61-source-review.py"
INC61_CONTRACT = "morphhdl/contracts/increment-61-source-review.json"
SUCCESSOR_PATHS = frozenset("""
.github/workflows/pr189-pr187-integration.yml
repro/cdc-independent-parameters/prepare_pr187_integration.py
.github/workflows/cdc-independent-parameter-consumers.yml
.github/workflows/increment-60b-signedness-authority.yml
.github/workflows/morphhdl-passes.yml
.github/workflows/pr189-60b-source-repair.yml
.github/workflows/pr189-compact-timeout.yml
core/src/main/scala/spinal/core/ElaborationProductDomain.scala
core/src/main/scala/spinal/core/ElaborationPublicationValue.scala
core/src/main/scala/spinal/core/ExternalCompilerPermit.scala
core/src/main/scala/spinal/core/NativeSymbolicLegality.scala
core/src/main/scala/spinal/core/internals/ParameterizedBlackBoxGeneric.scala
frontend/src/main/scala/morphhdl/frontend/HdlInt.scala
frontend/src/main/scala/morphhdl/frontend/StructuralExpressionBridge.scala
frontend/src/main/scala/spinal/core/ExternalAnalyzedFrontendPermitIssuer.scala
morphhdl-passes/scripts/test-boundary-guard.sh
morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json
morphhdl/contracts/increment-54-typed-layering-ir.contract
morphhdl/contracts/increment-55-native-change-review.json
morphhdl/contracts/increment-62-wa08-source-overlay.json
morphhdl/contracts/native-source-preservation.json
morphhdl/scripts/check-cdc-successor-source.py
morphhdl/scripts/check-increment-61-source-review.py
morphhdl/scripts/check-increment-62-wa08-source-overlay.py
morphhdl/scripts/check-typed-layering-ir.py
morphhdl/scripts/test-increment-59h-inherited-source-scope.py
morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala
morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala
morphhdl/src/main/scala/spinal/core/internals/NativePublicationWidth.scala
morphhdl/src/test/scala/spinal/core/internals/ParameterizedVerilogTests.scala
morphruntime/src/main/scala/spinal/core/ElabValue.scala
morphruntime/src/main/scala/spinal/core/ExternalParameterizedValueRegistry.scala
morphruntime/src/main/scala/spinal/core/ParameterizedStructure.scala
repro/cdc-independent-parameters/.gitignore
repro/cdc-independent-parameters/build.sbt
repro/cdc-independent-parameters/check_baseline.py
repro/cdc-independent-parameters/check_compact_timeout.py
repro/cdc-independent-parameters/check_consumers.py
repro/cdc-independent-parameters/export_tools.py
repro/cdc-independent-parameters/prepare_compact_timeout.py
repro/cdc-independent-parameters/project/build.properties
repro/cdc-independent-parameters/repair_pass_routes.py
repro/cdc-independent-parameters/repair_source_reviews.py
repro/cdc-independent-parameters/src/main/scala/CdcConsumerSuccess.scala
repro/cdc-independent-parameters/src/main/scala/CdcIndependentParametersRepro.scala
repro/cdc-independent-parameters/src/main/scala/CompactTimeout.scala
repro/cdc-independent-parameters/test_layering_root.py
repro/cdc-independent-parameters/test_source_review.py
""".split())


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError("PR189 successor source: " + detail)


def git(root: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", "--literal-pathspecs", *args], cwd=root, timeout=120)


def load(root: Path, relative: str):
    spec = importlib.util.spec_from_file_location("cdc_review_" + str(id(root)), root / relative)
    require(spec is not None and spec.loader is not None, "cannot load source verifier")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SYNC_HELPER_SHA256 = "2cd3030c3a320ca392527ee38e4186f33db4bc7a6b6320b3bf10946b57fb0f20"
SYNC_TARGET = "e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d"
ORIGINAL_SYNC_CHECKER = "69e1456b09f4e1b8c40a3afe405a271af1dd12aafeb1e5648f73f350c6e8a8f1"


def sync_continuation(root: Path) -> bool:
    import hashlib, re, stat, types
    helper = "morphhdl/scripts/check-increment-59i-production-successor.py"
    certificate = "morphhdl/contracts/increment-59i-production-successor.json"
    path = root / helper
    if not any(p.exists() or p.is_symlink() for p in (path, root / certificate)):
        require(not git(root, "rev-list", "--full-history", "HEAD", "--", helper, certificate),
                "59i synchronization certificate was removed")
        return False
    require(path.is_file() and not path.is_symlink() and
            not path.stat().st_mode & 0o111, "missing, linked or executable 59i sync verifier")
    raw = path.read_bytes()
    normalized, count = re.subn(rb'^CONTRACT_SHA256 = "[^"\n]+"$',
        b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, flags=re.M)
    require(count == 1 and hashlib.sha256(normalized).hexdigest() == SYNC_HELPER_SHA256,
            "unreviewed 59i synchronization verifier")
    module = types.ModuleType("cdc_59i_sync")
    module.__file__ = str(path)
    exec(compile(raw, str(path), "exec"), module.__dict__)
    value = module.verify(root)  # Fresh HEAD/index/worktree authorization, never a cached result.
    if value['schema_version'] in (5, 6, 7):
        require(module.target_anchor(root) == module.PR190_TARGET,
                "unreviewed 59i PR190 synchronization target")
        load(root, "morphhdl/scripts/check-increment-59i-pr190-integration.py").verify(root)
    else:
        require(module.target_anchor(root) == SYNC_TARGET, "unreviewed 59i synchronization target")
    module.audit_immutable_certificate(root, SYNC_TARGET,
        "morphhdl/scripts/check-cdc-successor-source.py", ORIGINAL_SYNC_CHECKER)
    print("PR189_SUCCESSOR_SOURCE_PASS (complete current 59i seal and target review; original PR189 checker retained)")
    return True


def verify(root: Path = ROOT) -> None:
    root = root.resolve()
    if sync_continuation(root):
        return
    sync_path = root / "morphhdl/scripts/check-pr190-pr189-source-sync.py"
    if sync_path.exists():
        # Authenticate the complete current union before running any frozen
        # predecessor review. The original PR189 verifier remains below.
        sealed = load(root, OVERLAY).verify(root)
        result = load(root, "morphhdl/scripts/check-pr190-pr189-source-sync.py").verify(root, sealed)
        print("PR189_SUCCESSOR_SOURCE_PASS combined_PR190 paths=" + str(len(result["paths"])))
        return
    git(root, "merge-base", "--is-ancestor", TARGET, "HEAD")
    # Authenticate the full current HEAD/index/worktree BEFORE projecting any
    # historical bytes. This also seals this verifier and the Inc61 adapter.
    load(root, OVERLAY).verify(root)
    git(root, "merge-base", "--is-ancestor", PREVIOUS_CDC, "HEAD")
    require(git(root, "merge-base", PREVIOUS_CDC, TARGET).decode().strip() == COMMON_BASE,
            "qualified parent ancestry changed")
    def implementation_paths(ref):
        return {p for p in git(root, "diff", "--name-only", COMMON_BASE, ref).decode().splitlines()
                if "/src/main/" in p or "/src/test/" in p or
                (p.startswith("morphhdl-passes/examples/") and p.endswith(".scala"))}
    cdc_paths, lane_paths = implementation_paths(PREVIOUS_CDC), implementation_paths(TARGET)
    require(not cdc_paths.intersection(lane_paths), "qualified implementation edits overlap")
    for ref, paths in ((PREVIOUS_CDC, cdc_paths), (TARGET, lane_paths)):
        for path in sorted(paths):
            require(git(root, "ls-tree", "HEAD", "--", path) == git(root, "ls-tree", ref, "--", path),
                    "qualified implementation mode/blob changed: " + path)
            require((root / path).read_bytes() == git(root, "show", ref + ":" + path),
                    "qualified implementation bytes changed: " + path)
    changed = {p.decode() for p in git(root, "diff", "--no-renames", "--name-only", "-z", TARGET, "HEAD").split(b"\0") if p}
    require(changed == SUCCESSOR_PATHS,
            "closed changed-file inventory differs: missing=" + repr(sorted(SUCCESSOR_PATHS - changed)) +
            " extra=" + repr(sorted(changed - SUCCESSOR_PATHS)))
    frozen_raw = git(root, "show", TARGET + ":" + INC61_CONTRACT)
    require((root / INC61_CONTRACT).read_bytes() == frozen_raw,
            "the original Increment61 review contract changed")
    frozen = json.loads(frozen_raw)
    production = set(frozen["production_paths"])
    require(not production.intersection(SUCCESSOR_PATHS),
            "this successor must not modify Increment61 production sources")
    preserved = ({entry["path"] for entry in frozen["reviewed_files"]} |
                 set(frozen["audit_paths"])) - SUCCESSOR_PATHS
    for path in sorted(preserved | production):
        require((root / path).is_file() and not (root / path).is_symlink(),
                "missing or linked preserved Increment61 file: " + path)
        require((root / path).read_bytes() == git(root, "show", TARGET + ":" + path),
                "preserved Increment61 bytes changed: " + path)
    print("PR189_SUCCESSOR_SOURCE_PASS paths=" + str(len(changed)))


def verify_predecessor(root: Path = ROOT, self_test: bool = False) -> None:
    # Neither parent's original checker is edited or projected onto current code.
    # Both historical reviews remain required, after exact current-source checks.
    for ref in (PREVIOUS_CDC, TARGET):
        with tempfile.TemporaryDirectory(prefix="pr189-qualified-parent-") as temporary:
            checkout = Path(temporary) / "source"
            git(root, "worktree", "add", "--quiet", "--detach", str(checkout), ref)
            try:
                command = [sys.executable, str(checkout / INC61)]
                if self_test:
                    command.append("--self-test")
                subprocess.run(command, cwd=checkout, check=True, timeout=600)
            finally:
                git(root, "worktree", "remove", "--force", str(checkout))


def self_test(root: Path = ROOT) -> None:
    verify(root)
    controls = 0
    with tempfile.TemporaryDirectory(prefix="pr189-source-mutations-") as temporary:
        checkout = Path(temporary) / "source"
        git(root, "worktree", "add", "--quiet", "--detach", str(checkout), "HEAD")
        try:
            candidates = [
                "core/src/main/scala/spinal/core/ElaborationPublicationValue.scala",
                "core/src/main/scala/spinal/core/internals/ParameterizedBlackBoxGeneric.scala",
                "morphruntime/src/main/scala/spinal/core/ElabValue.scala",
                "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala",
                "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala",
                "morphhdl/src/main/scala/morphhdl/MorphVerilog.scala",
                "morphhdl/contracts/increment-55-native-change-review.json",
                "morphhdl/contracts/native-source-preservation.json",
                INC61, INC61_CONTRACT,
                "repro/cdc-independent-parameters/check_consumers.py",
                "core/src/main/scala/spinal/core/internals/ComponentEmitter.scala",
                "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala",
                "core/src/main/scala/spinal/core/internals/PhaseVerilog.scala",
                "core/src/main/scala/spinal/core/internals/VerilogBase.scala",
                "core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala",
                "morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala",
                "morphhdl-passes/examples/UnnamedWireExpressionNativeBridge.scala",
                "morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala",
                "morphhdl/scripts/check-increment-61-publication-artifacts.py",
                "morphhdl/scripts/test-increment-61-publication-artifacts.py",
                "morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json",
                "morphhdl-passes/scripts/test-boundary-guard.sh",
                ".github/workflows/pr189-pr187-integration.yml",
                "repro/cdc-independent-parameters/prepare_pr187_integration.py",
                "morphhdl/scripts/check-cdc-successor-source.py",
            ]
            def rejected(label: str) -> None:
                nonlocal controls
                result = subprocess.run([sys.executable, str(checkout / INC61)], cwd=checkout,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=300)
                require(result.returncode != 0, "mutation accepted: " + label)
                require("WA-08 source overlay:" in result.stderr or
                        "PR189 successor source:" in result.stderr,
                        "mutation did not reach a source guard: " + label + "\n" + result.stderr)
                controls += 1
            for path in candidates:
                file = checkout / path
                raw = file.read_bytes()
                try:
                    file.write_bytes(raw + b"\n# PR189 live source mutation\n" if path.endswith(".py") else
                                     raw + b"\n// PR189 live source mutation\n")
                    rejected(path)
                finally:
                    file.write_bytes(raw)
            path = candidates[0]
            file = checkout / path
            raw = file.read_bytes()
            try:
                file.unlink()
                rejected("deleted approved source")
            finally:
                file.write_bytes(raw)
            file = checkout / "core/src/main/scala/spinal/core/UnreviewedCdcAddition.scala"
            try:
                file.write_text("package spinal.core\nobject UnreviewedCdcAddition\n")
                rejected("untracked native source")
            finally:
                file.unlink()
            verify(checkout)
        finally:
            git(root, "worktree", "remove", "--force", str(checkout))
    require(controls == 28, "mutation inventory changed")
    print("PR189_SUCCESSOR_MUTATIONS_PASS controls=" + str(controls))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        verify()


if __name__ == "__main__":
    main()
