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
TARGET = "27af65abbee0d2334d6be7a6e4e2408b8af32fd9"
OVERLAY = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"
INC61 = "morphhdl/scripts/check-increment-61-source-review.py"
INC61_CONTRACT = "morphhdl/contracts/increment-61-source-review.json"
SUCCESSOR_PATHS = frozenset("""
.github/workflows/cdc-independent-parameter-consumers.yml
core/src/main/scala/spinal/core/ElaborationPublicationValue.scala
core/src/main/scala/spinal/core/internals/ParameterizedBlackBoxGeneric.scala
morphhdl/contracts/increment-54-typed-layering-ir.contract
morphhdl/contracts/increment-55-native-change-review.json
morphhdl/contracts/increment-62-wa08-source-overlay.json
morphhdl/contracts/native-source-preservation.json
morphhdl/scripts/check-cdc-successor-source.py
morphhdl/scripts/check-increment-61-source-review.py
morphhdl/scripts/check-increment-62-wa08-source-overlay.py
morphhdl/scripts/check-typed-layering-ir.py
morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala
morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala
morphruntime/src/main/scala/spinal/core/ElabValue.scala
morphruntime/src/main/scala/spinal/core/ExternalParameterizedValueRegistry.scala
repro/cdc-independent-parameters/.gitignore
repro/cdc-independent-parameters/build.sbt
repro/cdc-independent-parameters/check_baseline.py
repro/cdc-independent-parameters/check_consumers.py
repro/cdc-independent-parameters/export_tools.py
repro/cdc-independent-parameters/project/build.properties
repro/cdc-independent-parameters/repair_source_reviews.py
repro/cdc-independent-parameters/src/main/scala/CdcConsumerSuccess.scala
repro/cdc-independent-parameters/src/main/scala/CdcIndependentParametersRepro.scala
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


def verify(root: Path = ROOT) -> None:
    root = root.resolve()
    git(root, "merge-base", "--is-ancestor", TARGET, "HEAD")
    # Authenticate the full current HEAD/index/worktree BEFORE projecting any
    # historical bytes. This also seals this verifier and the Inc61 adapter.
    load(root, OVERLAY).verify(root)
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
    # The historical checker stays byte-for-byte intact in this pinned worktree.
    # Current source was authenticated separately; predecessor success alone is
    # never accepted as current-head qualification.
    with tempfile.TemporaryDirectory(prefix="pr189-inc61-predecessor-") as temporary:
        checkout = Path(temporary) / "source"
        git(root, "worktree", "add", "--quiet", "--detach", str(checkout), TARGET)
        try:
            command = [sys.executable, str(checkout / INC61)]
            if self_test:
                command.append("--self-test")
            subprocess.run(command, cwd=checkout, check=True, timeout=300)
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
    require(controls == 13, "mutation inventory changed")
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
