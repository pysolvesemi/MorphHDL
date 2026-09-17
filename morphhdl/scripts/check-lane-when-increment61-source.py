#!/usr/bin/env python3
"""Authenticate the exact PR187/Increment61 union before inherited projections.

The eight qualified lane compiler files and six merged publication files are
retained byte-for-byte. Historical checks are separately identified evidence;
no historical source or test result substitutes for the current source seal.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
BASE = "7f355a859e7e88ca343e1ff82f261fb47b3311d0"
TARGET = "27af65abbee0d2334d6be7a6e4e2408b8af32fd9"
LANE = "5374b958f8f94114b1ed46a3069845d580886da9"
SELF = "morphhdl/scripts/check-lane-when-increment61-source.py"
SCOPE = "morphhdl/scripts/check-lane-when-source-scope.py"
CHECKER = "morphhdl/scripts/check-increment-61-source-review.py"
CONTRACT = "morphhdl/contracts/increment-61-source-review.json"
REGISTRY = "morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json"
OUTER = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"
OUTER_NORMALIZED_SHA256 = "14feb8286f32152b7c6881c73e0339e069bbeaaf07cdc1d51d84cc208fc39fab"


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError("LANE-61-SOURCE: " + detail)


def git(root: Path, *args: str) -> bytes:
    result = subprocess.run(["git", "--literal-pathspecs", *args], cwd=root,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    require(result.returncode == 0, "git " + " ".join(args) + ": " +
            result.stderr.decode(errors="replace"))
    return result.stdout


def load(root: Path, relative: str):
    path = root / relative
    require(path.is_file() and not path.is_symlink(), "missing regular checker: " + relative)
    spec = importlib.util.spec_from_file_location("lane61_" + path.stem, path)
    require(spec is not None and spec.loader is not None, "cannot load checker: " + relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def changed(root: Path, before: str, after: str) -> set[str]:
    return {p.decode() for p in git(root, "diff", "--no-renames", "--name-only", "-z",
                                   before, after).split(b"\0") if p}


def production(path: str) -> bool:
    return "/src/main/" in path or path.startswith("morphhdl-passes/examples/")


def unique_json(raw: bytes) -> dict:
    def unique(pairs):
        result = dict(pairs)
        require(len(result) == len(pairs), "duplicate JSON key")
        return result
    return json.loads(raw, object_pairs_hook=unique)


def verify(root: Path = ROOT) -> dict:
    root = root.resolve()
    for ancestor in (TARGET, LANE):
        git(root, "merge-base", "--is-ancestor", ancestor, "HEAD")
    require(git(root, "merge-base", TARGET, LANE).decode().strip() == BASE,
            "changed integration ancestry")
    raw = (root / OUTER).read_bytes()
    normalized = re.sub(rb'^CONTRACT_SHA256 = "[^"]+"$',
                        b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, count=1, flags=re.M)
    require(hashlib.sha256(normalized).hexdigest() == OUTER_NORMALIZED_SHA256,
            "outer verifier implementation changed")
    outer = load(root, OUTER)
    outer.verify(root)  # Complete current Git/index/worktree and source-anchor identity.
    scope = load(root, SCOPE)
    inventory = scope.verify(root)
    allowed = set(inventory["implementation_paths"]) | set(inventory["review_paths"])
    require(changed(root, TARGET, "HEAD") <= allowed, "unreviewed successor path")
    contract_raw = outer.regular(root, CONTRACT)
    require(contract_raw == git(root, "show", TARGET + ":" + CONTRACT),
            "immutable Increment 61 contract changed")
    contract = unique_json(contract_raw)
    target_paths = {entry["path"] for entry in contract["reviewed_files"]} | set(contract["audit_paths"])
    require(changed(root, BASE, TARGET) == target_paths, "merged Increment 61 inventory changed")
    lane_paths = changed(root, BASE, LANE)
    expected = target_paths | lane_paths | {SELF, "morphhdl-passes/scripts/test-boundary-guard.sh"}
    require(changed(root, BASE, "HEAD") == expected, "complete integration inventory differs")

    target_tree = outer.tree(root, TARGET)
    lane_tree = outer.tree(root, LANE)
    target_production = {p for p in target_paths if production(p)}
    lane_production = {p for p in lane_paths if production(p)}
    require(target_production == set(contract["production_paths"]) and len(target_production) == 6,
            "Increment 61 production inventory changed")
    require(len(lane_production) == 8 and not lane_production & target_production,
            "qualified compiler changes overlap or have changed inventory")
    require({p for p in changed(root, BASE, "HEAD") if production(p)} ==
            target_production | lane_production, "unexpected integrated compiler change")
    for revision, paths, tree in ((TARGET, target_production, target_tree),
                                  (LANE, lane_production, lane_tree)):
        for path in paths:
            require(outer.regular(root, path, tree[path][0]) == git(root, "show", revision + ":" + path),
                    "qualified production bytes changed: " + path)

    # Retain all original reviewed documentation/test/publication bytes. Only
    # the registry needs an exact, separately checked union of the two parents.
    for entry in contract["reviewed_files"]:
        path = entry["path"]
        if path != REGISTRY:
            require(outer.regular(root, path, entry["mode"]) == git(root, "show", TARGET + ":" + path),
                    "merged Increment 61 reviewed bytes changed: " + path)
            require(git(root, "show", "HEAD:" + path) == git(root, "show", ":" + path) ==
                    (root / path).read_bytes(), "Increment 61 reviewed identity differs: " + path)
    inherited = unique_json(git(root, "show", TARGET + ":" + REGISTRY))
    current = unique_json(outer.regular(root, REGISTRY))
    require(set(current) == set(inherited) == {"schema_version", "algorithm", "files"},
            "formal registry schema changed")
    require(current["schema_version"] == inherited["schema_version"] and
            current["algorithm"] == inherited["algorithm"], "formal registry identity changed")
    require(set(current["files"]) == set(inherited["files"]) and len(current["files"]) == 98,
            "formal signature path inventory changed")
    for path, digest in current["files"].items():
        require(hashlib.sha256((root / path).read_bytes()).hexdigest() == digest,
                "formal signature differs from actual source: " + path)
        require(digest == inherited["files"][path] or path in allowed,
                "unreviewed formal signature change: " + path)
    return {"target": TARGET, "lane": LANE, "head": git(root, "rev-parse", "HEAD").decode().strip(),
            "production_files": len(target_production | lane_production),
            "paths": sorted(expected)}


def verify_predecessor(root: Path = ROOT, self_test: bool = False) -> None:
    receipt = verify(root)
    with tempfile.TemporaryDirectory(prefix="lane61-inherited-review-") as temporary:
        predecessor = Path(temporary) / "merged-increment61"
        git(root, "worktree", "add", "--quiet", "--detach", str(predecessor), TARGET)
        try:
            command = [sys.executable, CHECKER] + (["--self-test"] if self_test else [])
            result = subprocess.run(command, cwd=predecessor, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
            marker = "Increment 61 source-review self-test PASS (14 rejection controls)" if self_test else \
                     "Increment 61 exact source review PASS (12 reviewed files, 6 production files, integrated-target)"
            require(result.returncode == 0 and marker in result.stdout,
                    "unchanged predecessor review failed:\n" + result.stdout)
        finally:
            git(root, "worktree", "remove", "--force", str(predecessor))
    output = root / "target/lane-increment61-source"
    output.mkdir(parents=True, exist_ok=True)
    (output / "source-identity.json").write_text(json.dumps(receipt, indent=2) + "\n")
    (output / ("historical-self-test.log" if self_test else "historical-source-review.log")).write_text(result.stdout)
    print(result.stdout, end="")
    print("LANE_INCREMENT61_CURRENT_SOURCE_PASS compiler_files=14 signatures=98 head=" + receipt["head"])


def self_test(root: Path = ROOT) -> None:
    receipt = verify(root)
    paths = sorted(p for p in changed(root, BASE, TARGET) | changed(root, BASE, LANE) if production(p))
    cases = []
    with tempfile.TemporaryDirectory(prefix="lane61-current-controls-") as temporary:
        fixture = Path(temporary) / "current"
        git(root, "worktree", "add", "--quiet", "--detach", str(fixture), receipt["head"])
        try:
            def reject(label, mutate):
                git(fixture, "reset", "--hard", receipt["head"])
                git(fixture, "clean", "-fdx")
                mutate()
                try:
                    verify(fixture)
                except RuntimeError as error:
                    cases.append({"case": label, "rejection": str(error).splitlines()[0]})
                else:
                    raise RuntimeError("accepted source mutation: " + label)

            def append(relative):
                path = fixture / relative
                path.write_bytes(path.read_bytes() + b"\n// unreviewed integration mutation\n")

            for path in paths:
                reject("current compiler bytes " + path, lambda p=path: append(p))
            reject("Increment 61 documentation", lambda: append("docs/morphhdl/increment-61-one-file-per-component.md"))
            reject("formal signature registry", lambda: append(REGISTRY))
            reject("boundary source routing controls", lambda: append("morphhdl-passes/scripts/test-boundary-guard.sh"))
            reject("frozen Increment 61 contract", lambda: append(CONTRACT))
            victim = paths[0]
            def hidden_index():
                original = (fixture / victim).read_bytes()
                append(victim)
                git(fixture, "add", "--", victim)
                (fixture / victim).write_bytes(original)
            reject("hidden staged compiler bytes", hidden_index)
            def foreign_source():
                path = fixture / "foreign/src/main/Unreviewed.scala"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("// unreviewed\n")
            reject("foreign production root", foreign_source)
            for ancestor in (TARGET, LANE):
                reject("missing integration parent " + ancestor,
                       lambda rev=ancestor: git(fixture, "reset", "--hard", rev))
        finally:
            git(root, "worktree", "remove", "--force", str(fixture))
    require(len(cases) == 22, "changed current-source rejection inventory")
    output = root / "target/lane-increment61-source"
    output.mkdir(parents=True, exist_ok=True)
    (output / "current-rejection-controls.json").write_text(json.dumps({
        "head": receipt["head"], "cases": cases}, indent=2) + "\n")
    print("LANE_INCREMENT61_CURRENT_REJECTIONS_PASS controls=" + str(len(cases)))


if __name__ == "__main__":
    verify_predecessor()
