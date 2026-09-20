#!/usr/bin/env python3
"""Review the current sealed 59i/PR190 source and its exact merge provenance.

Frozen certificates supplement this review. They cannot authorize different
current compiler, test, build, hardware checker, workflow or inventory bytes.
No source-only receipt establishes Scala or hardware qualification.
"""
from __future__ import annotations

import functools
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import types

ROOT = Path(__file__).resolve().parents[2]
HELPER = "morphhdl/scripts/check-increment-59i-production-successor.py"
CONTRACT = "morphhdl/contracts/increment-59i-production-successor.json"
HELPER_SHA256 = "31226de9bc5a5818017b01ecdd295e991e23c5752f67137c01dd667208a35227"
LEFT = "58fb59773a2deebba0251b5b626c19a22453f0a4"
TARGET = "4b8a86e25f5a1a3f0cb4c37dc537a8dd8aa7b097"
BASE = "e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d"
CHECKPOINT = "ccec54986c70077e361f291cd322f0aa547aee15"
INVENTORY = "morphhdl/contracts/increment-59i-regression-inventory.json"
INVENTORY_SHA256 = "338e26e122d657349e254e19ff452fbd94f839b774268bbaecb93ea002e6027f"


def require(ok: bool, message: str) -> None:
    if not ok:
        raise RuntimeError("SEQUENTIAL-WIRE-SOURCE: 59i PR190 integration: " + message)


def source_review(root: Path):
    path = root / HELPER
    require(path.is_file() and not path.is_symlink() and not path.stat().st_mode & 0o111,
        "missing, linked or executable production verifier")
    require(all(not root.joinpath(*Path(HELPER).parts[:i]).is_symlink()
        for i in range(1, len(Path(HELPER).parts))), "linked verifier ancestor")
    raw = path.read_bytes()
    normalized, count = re.subn(rb'^CONTRACT_SHA256 = "[^"\n]+"$',
        b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, flags=re.M)
    require(count == 1 and hashlib.sha256(normalized).hexdigest() == HELPER_SHA256,
        "unreviewed production verifier")
    key = "pr190_production_" + hashlib.sha256(raw).hexdigest()
    if key not in sys.modules:
        module = types.ModuleType(key)
        module.__file__ = str(path)
        exec(compile(raw, str(path), "exec"), module.__dict__)
        sys.modules[key] = module
    # Only authenticated code is shared. Every caller verifies the live tree.
    return sys.modules[key]


def runtime(path: str) -> bool:
    return ("/src/main/" in path or "/src/test/" in path or
        path.endswith((".scala", ".sbt")) or path.startswith("project/") or
        path in ("build.sc", ".gitmodules"))


@functools.lru_cache(maxsize=8)
def immutable_merge_inventory(root: Path) -> tuple:
    """Reconstruct each runtime blob independently from the two parent trees."""
    review = source_review(root)
    base, left, target = (review.tree(root, ref) for ref in (BASE, LEFT, TARGET))
    paths = {p for p in set(base) | set(left) | set(target) if runtime(p)}
    output = {}
    for path in sorted(paths):
        a, b, c = left.get(path), base.get(path), target.get(path)
        if a == b:
            expected = c
        elif c == b or a == c:
            expected = a
        else:
            require(a is not None and b is not None and c is not None and
                a[0] == b[0] == c[0] and a[0] in ("100644", "100755"),
                "unsupported runtime merge: " + path)
            with tempfile.TemporaryDirectory(prefix="59i-pr190-merge-") as directory:
                files = []
                for ref, name in ((LEFT, "left"), (BASE, "base"), (TARGET, "target")):
                    file = Path(directory) / name
                    file.write_bytes(review.frozen(root, ref, path))
                    files.append(str(file))
                result = subprocess.run(["git", "merge-file", "-p", *files],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
                require(result.returncode == 0, "runtime merge conflict: " + path)
                expected = (a[0], review.blob(result.stdout))
        if expected is not None:
            output[path] = expected
    return tuple(output.items())


def verify_runtime_merge(root: Path, review) -> int:
    expected = dict(immutable_merge_inventory(root.resolve()))
    for ref in (CHECKPOINT, "HEAD"):
        actual = {p: entry for p, entry in review.tree(root, ref).items() if runtime(p)}
        require(actual == expected, "current runtime is not the exact parent merge")
    return len(expected)


def verify_ci_and_inventory(root: Path, review) -> None:
    # The combined projection validates every original/copied XML before
    # removing copied successor reports. Its complete manifest owns all 37
    # new cases; the preserved PR190 route remains in the legacy else branch.
    workflows = (
        ".github/workflows/increment-60f-equivalence-closure.yml",
        ".github/workflows/morphhdl-passes.yml",
        ".github/workflows/sequential-source-review-targeted.yml",
        ".github/workflows/sequential-wire-consumers.yml",
    )
    for path in workflows:
        require(review.regular(root, path) == review.frozen(root, CHECKPOINT, path),
            "combined qualification workflow changed: " + path)
    raw = review.regular(root, INVENTORY)
    require(hashlib.sha256(raw).hexdigest() == INVENTORY_SHA256,
        "complete regression inventory changed")
    inventory = json.loads(raw)
    # The inventory checker independently verifies all exact names/counts and
    # 590 test-source hashes. Pinning this catalog preserves the old 2,270 plus
    # the complete 37-case target delta; no report success is inferred here.
    require(sum(sum(len(cases) for cases in suites.values()) for suites in inventory['projects'].values()) == 2307 and
        sum(len(suites) for suites in inventory['projects'].values()) == 230 and
        len(inventory['test_source_sha256']) == 590,
        "complete regression totals changed")


def verify(root: Path = ROOT) -> dict:
    root = root.resolve()
    review = source_review(root)
    value = review.verify(root)
    require(value["schema_version"] == 5 and review.target_anchor(root) == TARGET and
        value["target_checkpoint"] == review.pr190_checkpoint(), "wrong reviewed merge lifecycle")
    count = verify_runtime_merge(root, review)
    verify_ci_and_inventory(root, review)
    # These policies and every sequential compiler body remain byte-identical
    # to PR190. The complete outer seal has already checked current modes,
    # tracked/index/live identity, ignored additions and complete history.
    for path in (
        "morphhdl/contracts/increment-54-typed-layering-ir.contract",
        "morphhdl/scripts/check-typed-layering-ir.py",
        "morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json",
        "core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala",
        "morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala",
        "morphhdl-passes/examples/UnnamedWireAliasNativeBridge.scala",
        "morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala",
    ):
        require(review.regular(root, path) == review.frozen(root, TARGET, path),
            "preserved PR190 compiler/policy changed: " + path)
    return {"head": review.revision(root, "HEAD"), "source": value["source_commit"],
        "target": TARGET, "checkpoint": CHECKPOINT, "runtime_files": count,
        "target_records": len(value["target_integration"]["files"]),
        "expected_testcases": 2307, "expected_suites": 230,
        "qualification": "source identity only; no runtime proof credit"}


def main() -> None:
    print("59I_PR190_CURRENT_SOURCE_PASS " + json.dumps(verify(), sort_keys=True))


if __name__ == "__main__":
    main()
