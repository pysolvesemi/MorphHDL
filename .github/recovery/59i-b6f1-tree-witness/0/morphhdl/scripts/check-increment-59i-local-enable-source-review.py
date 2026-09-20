#!/usr/bin/env python3
"""Review exact composite local-enable source after its complete successor seal.

This certificate binds source bytes and history, never a hardware-test result.
The cumulative production successor owns complete checkout authentication and
historical inventories. Compilation always uses the live source; projections
serve only the inherited source reviewers.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import stat
import subprocess
import sys

BASE = "90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6"
PRESERVED_DEVELOPMENT = "d76fbd5f84869ac56186b36f35dfc3c480a80cbb"
PRESERVED_TREE = "5df50314aa7ae3bad916b157b69a87a278c391ba"
PRESERVED_SECOND_PARENT = "1931c0aa82860d81a9a651ffa06b920840ddea1e"
INHERITED_FEATURE = "31e7488be8073022ae840dc7391c19a6f3727847"
CONTRACT = "morphhdl/contracts/increment-59i-local-enable-review.json"
CHECKER = "morphhdl/scripts/check-increment-59i-local-enable-source-review.py"
TEST = "morphhdl/scripts/test-increment-59i-local-enable-source-review.py"
CONTRACT_SHA256 = "d886bc65b410f971a5e4d009ea63b5d630caf449a6ed40b2411ef196775bd81d"
SUCCESSOR_HELPER = "morphhdl/scripts/check-increment-59i-production-successor.py"
SUCCESSOR_CONTRACT = "morphhdl/contracts/increment-59i-production-successor.json"
SUCCESSOR_HELPER_SHA256 = "0efe97dfed07dbe541a8847cac1f997d9c30505feb5b12df20e0a1835fa150a7"
BASE_CONTRACT_SHA256 = "99dd143a0cc54898051e21adb58d311671af97642c6a77e52554f5d86b85125b"
QUALIFICATION = "source-review-only; hardware and final-head qualification remain independent mandatory gates"
PATHS = (
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionClosedGraph.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala",
    "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeLocalEnableTests.scala",
)
PRODUCTION_PATHS = frozenset(PATHS[:-1])
ADDED_PATHS = frozenset(PATHS[-1:])


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError("59i local-enable source review: " + detail)


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def git(root: Path, *args: str) -> bytes:
    result = subprocess.run(["git", "--literal-pathspecs", *args], cwd=root,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    require(result.returncode == 0, "Git failed: " + " ".join(args) + ": " +
        result.stderr.decode(errors="replace"))
    return result.stdout


def regular(root: Path, path: str) -> bytes:
    relative = Path(path)
    require(isinstance(path, str) and path and not relative.is_absolute() and
        relative.as_posix() == path and ".." not in relative.parts and
        ".git" not in relative.parts and "\0" not in path, "invalid source path")
    info = None
    for index in range(1, len(relative.parts) + 1):
        item = root.joinpath(*relative.parts[:index])
        try:
            info = item.lstat()
        except (FileNotFoundError, NotADirectoryError):
            require(False, "missing regular source: " + path)
        require(not stat.S_ISLNK(info.st_mode), "linked source: " + path)
    require(info is not None and stat.S_ISREG(info.st_mode) and not info.st_mode & 0o111,
        "source must be regular and non-executable: " + path)
    return (root / relative).read_bytes()


def frozen(root: Path, revision: str, path: str) -> bytes:
    require(re.fullmatch("[0-9a-f]{40}", revision) is not None, "mutable source anchor")
    listing = git(root, "ls-tree", "-z", revision, "--", path).split(b"\0")
    records = [row for row in listing if row]
    if not records:
        return b""
    require(len(records) == 1, "ambiguous immutable source: " + path)
    metadata, filename = records[0].split(b"\t", 1)
    mode, kind, oid = metadata.decode().split()
    require(filename.decode() == path and mode == "100644" and kind == "blob",
        "immutable source mode changed: " + path)
    return git(root, "cat-file", "blob", oid)


def valid_hash(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch("[0-9a-f]{64}", value) is not None


def validate_contract(value: dict) -> dict[str, dict]:
    require(isinstance(value, dict) and set(value) == {"schema_version", "base",
        "preserved_development", "inherited_feature", "offset_format", "qualification", "files"},
        "invalid contract schema")
    require(type(value["schema_version"]) is int and value["schema_version"] == 1 and
        value["base"] == BASE and value["preserved_development"] == PRESERVED_DEVELOPMENT and
        value["inherited_feature"] == INHERITED_FEATURE and value["offset_format"] == "utf8-bytes" and
        value["qualification"] == QUALIFICATION, "contract anchors or qualification scope changed")
    files = value["files"]
    require(isinstance(files, list) and all(isinstance(entry, dict) for entry in files) and
        tuple(entry.get("path") for entry in files) == PATHS, "reviewed path inventory changed")
    identifiers, entries = set(), {}
    for entry in files:
        require(set(entry) == {"path", "change", "reason", "baseline_sha256", "source_sha256", "edits"},
            "invalid reviewed-file schema")
        path = entry["path"]
        added = path in ADDED_PATHS
        require(entry["change"] == ("added" if added else "modified") and
            (entry["baseline_sha256"] is None if added else valid_hash(entry["baseline_sha256"])) and
            valid_hash(entry["source_sha256"]), "source classification or hash changed: " + path)
        require(isinstance(entry["reason"], str) and entry["reason"].strip(), "file needs review rationale")
        edits = entry["edits"]
        require(isinstance(edits, list) and edits, "source needs explicit reviewed spans")
        previous_before = previous_after = 0
        for edit in edits:
            require(isinstance(edit, dict) and set(edit) == {"id", "reason", "before_start", "before_end",
                "after_start", "after_end", "before", "after"}, "invalid span schema")
            require(isinstance(edit["id"], str) and edit["id"] and edit["id"] not in identifiers,
                "missing or duplicate span identity")
            identifiers.add(edit["id"])
            require(isinstance(edit["reason"], str) and edit["reason"].strip(), "span needs review rationale")
            require(all(type(edit[key]) is int and edit[key] >= 0 for key in
                ("before_start", "before_end", "after_start", "after_end")), "invalid UTF-8 byte offset")
            require(isinstance(edit["before"], str) and isinstance(edit["after"], str) and
                edit["before"] != edit["after"], "span must describe a real source change")
            a, b, c, d = (edit[key] for key in ("before_start", "before_end", "after_start", "after_end"))
            require(b - a == len(edit["before"].encode()) and d - c == len(edit["after"].encode()),
                "span text disagrees with byte offsets")
            require(a >= previous_before and c >= previous_after and
                a - previous_before == c - previous_after, "overlapping or non-corresponding spans")
            previous_before, previous_after = b, d
        if added:
            first = edits[0]
            require(len(edits) == 1 and first["before_start"] == first["before_end"] ==
                first["after_start"] == 0 and first["before"] == "" and first["after"],
                "added test requires one exact whole-file span")
        entries[path] = entry
    return entries


def load_contract(root: Path) -> dict[str, dict]:
    raw = regular(root, CONTRACT)
    require(digest(raw) == CONTRACT_SHA256, "review contract changed")
    try:
        return validate_contract(json.loads(raw))
    except (ValueError, UnicodeDecodeError) as error:
        raise RuntimeError("59i local-enable source review: invalid contract JSON") from error


def restore_reviewed(entry: dict, baseline: bytes, source: bytes) -> bytes:
    path = entry["path"]
    require((entry["change"] == "added" and baseline == b"" and entry["baseline_sha256"] is None) or
        digest(baseline) == entry["baseline_sha256"], "immutable baseline changed: " + path)
    require(digest(source) == entry["source_sha256"], "unreviewed source bytes: " + path)
    result = []
    previous_before = previous_after = 0
    for edit in entry["edits"]:
        a, b, c, d = (edit[key] for key in ("before_start", "before_end", "after_start", "after_end"))
        require(baseline[a:b] == edit["before"].encode(), "before span differs: " + edit["id"])
        require(source[previous_after:c] == baseline[previous_before:a],
            "unreviewed bytes outside local-enable spans: " + path)
        require(source[c:d] == edit["after"].encode(), "after span differs: " + edit["id"])
        result.extend((source[previous_after:c], edit["before"].encode()))
        previous_before, previous_after = b, d
    require(source[previous_after:] == baseline[previous_before:],
        "unreviewed trailing bytes outside local-enable spans: " + path)
    result.append(source[previous_after:])
    restored = b"".join(result)
    require(restored == baseline, "reversal did not reproduce the exact baseline: " + path)
    return restored


def source_review(root: Path, complete: bool = False):
    raw = regular(root, SUCCESSOR_HELPER)
    pattern = rb'^CONTRACT_SHA256 = "[^"\n]+"$'
    require(len(re.findall(pattern, raw, re.M)) == 1, "ambiguous production successor seal")
    normalized = re.sub(pattern, b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, flags=re.M)
    require(digest(normalized) == SUCCESSOR_HELPER_SHA256, "unreviewed production successor verifier")
    name = "increment_59i_production_successor_" + digest(raw)
    module = sys.modules.get(name)
    if module is None:
        spec = importlib.util.spec_from_file_location(name, root / SUCCESSOR_HELPER)
        require(spec is not None and spec.loader is not None, "cannot load production successor")
        module = importlib.util.module_from_spec(spec)
        # Execute only authenticated bytes; never trust a stale timestamp .pyc.
        exec(compile(raw, str(root / SUCCESSOR_HELPER), "exec"), module.__dict__)
        sys.modules[name] = module
    if complete:
        module.verify(root)
    else:
        module.authenticate_contract(root)
    return module


def verify_committed_identity(root: Path, paths=PATHS) -> None:
    head = git(root, "rev-parse", "HEAD").decode().strip()
    committed = {}
    for row in git(root, "ls-tree", "-r", "-z", head).split(b"\0"):
        if row:
            meta, name = row.split(b"\t", 1)
            committed[name.decode()] = tuple(meta.decode().split())
    indexed = {}
    for row in git(root, "ls-files", "--stage", "-z").split(b"\0"):
        if row:
            meta, name = row.split(b"\t", 1)
            indexed.setdefault(name.decode(), []).append(tuple(meta.decode().split()))
    for path in paths:
        raw = regular(root, path)
        oid = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
        require(committed.get(path) == ("100644", "blob", oid) and
            indexed.get(path) == [("100644", oid, "0")], "HEAD/index/worktree identity differs: " + path)
    require(git(root, "rev-parse", "HEAD").decode().strip() == head, "HEAD changed during source identity check")


def verify(root: Path) -> dict[str, dict]:
    root = root.resolve()
    successor = source_review(root, complete=True)
    entries = load_contract(root)
    head = git(root, "rev-parse", "HEAD").decode().strip()
    git(root, "merge-base", "--is-ancestor", PRESERVED_DEVELOPMENT, head)
    require(git(root, "rev-parse", PRESERVED_DEVELOPMENT + "^{tree}").decode().strip() == PRESERVED_TREE,
        "preserved development tree changed")
    require(git(root, "rev-list", "--parents", "-n", "1", PRESERVED_DEVELOPMENT).decode().split() ==
        [PRESERVED_DEVELOPMENT, BASE, PRESERVED_SECOND_PARENT], "preserved development history changed")
    require(digest(frozen(root, BASE, SUCCESSOR_CONTRACT)) == BASE_CONTRACT_SHA256,
        "published predecessor certificate changed")
    delta = {path.decode() for path in git(root, "diff", "--no-renames", "--name-only", "-z", BASE, head).split(b"\0")
        if path and re.search(rb"(?:^|/)src/main/", path)}
    expected = set(PRODUCTION_PATHS)
    if successor.contract(root)['schema_version'] == 5:
        # Every additional target body is independently bound to the exact
        # PR190 merge; it cannot become a local-enable review exception.
        expected |= {path for path in successor.changed(root, successor.PR190_COMMON,
            successor.PR190_TARGET) if re.search(r'(?:^|/)src/main/', path)}
    require(delta == expected, "complete local-enable production delta changed: " + repr(sorted(delta)))
    verify_committed_identity(root, (*PATHS, CONTRACT, CHECKER, TEST))
    for path, entry in entries.items():
        restore_reviewed(entry, frozen(root, BASE, path), regular(root, path))
    require(git(root, "rev-parse", "HEAD").decode().strip() == head, "HEAD changed during source review")
    return entries


def restore_source(root: Path, path: str, source: str) -> str:
    """Reverse live local source; preserve the already projected frozen view."""
    source_review(root)
    entries = load_contract(root)
    if path not in entries:
        return source
    raw = source.encode()
    baseline = frozen(root, BASE, path)
    # The rollout compositor invokes us after cumulative feature projection.
    # Returning exact frozen bytes cannot authorize any changed current input.
    if raw in (baseline, frozen(root, INHERITED_FEATURE, path)):
        return source
    return restore_reviewed(entries[path], baseline, raw).decode()


def inherited_inventory(root: Path, paths: set[str], qualification_base: str) -> set[str]:
    """Retain the cumulative successor's already authenticated projection.

    The production successor owns the complete new source inventory. Applying
    the later 90b baseline a second time would incorrectly restore newer paths
    into the rollout compositor's older 31e feature view. Unknown sentinels are
    preserved for that caller's original strict inventory comparison.
    """
    verify(root)
    return set(paths)


def self_test(root: Path) -> None:
    entries = verify(root)
    rejected = 0
    for path, entry in entries.items():
        baseline, source = frozen(root, BASE, path), regular(root, path)
        require(restore_reviewed(entry, baseline, source) == baseline, "positive span reversal failed")
        positions = {0, len(source) // 2, len(source) - 1}
        positions.update(edit["after_start"] for edit in entry["edits"])
        for index in sorted(positions):
            altered = (source[:index] + bytes([source[index] ^ 1]) + source[index + 1:]
                if index < len(source) else source + b"x")
            try:
                restore_reviewed(entry, baseline, altered)
            except RuntimeError:
                rejected += 1
            else:
                require(False, "accepted source mutation: " + path)
    require(rejected >= sum(len(entry["edits"]) for entry in entries.values()), "missing mutation controls")
    print("59i local-enable source mutations PASS: " + str(rejected), flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    verify(args.repo_root)
    if args.self_test:
        self_test(args.repo_root)
    print("59i local-enable exact committed source review PASS (not hardware qualification)", flush=True)


if __name__ == "__main__":
    main()
