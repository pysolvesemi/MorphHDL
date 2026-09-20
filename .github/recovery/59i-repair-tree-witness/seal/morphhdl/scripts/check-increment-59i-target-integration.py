#!/usr/bin/env python3
"""Seal the exact 59i/WA08/WA10 merge before exposing either parent view.

This verifier deliberately imports no inherited reviewer. Parent projections
authenticate bytes; they never grant permission to an unlisted source change.
"""
from __future__ import annotations

import argparse
import functools
import hashlib
import importlib.util
import json
import os
import re
import stat
import subprocess
import sys
from pathlib import Path
from types import MappingProxyType

COMMON_BASE = "2ebaa2ef5561eab35aa0ba9caced5c5a314d59f6"
FEATURE_PARENT = "31e7488be8073022ae840dc7391c19a6f3727847"
TARGET_PARENT = "f06d9c412924b99cf2c76422549c375a571757dc"
HELPER = "morphhdl/scripts/check-increment-59i-target-integration.py"
TEST = "morphhdl/scripts/test-increment-59i-target-integration.py"
CONTRACT = "morphhdl/contracts/increment-59i-target-integration.json"
CONTRACT_SHA256 = "9ec750ceffa71d79e2fc78dc91b0c033ebe3ae4035116ed1495ba0012d9cd4b4"
SUCCESSOR_HELPER = "morphhdl/scripts/check-increment-59i-production-successor.py"
SUCCESSOR_CONTRACT = "morphhdl/contracts/increment-59i-production-successor.json"
SUCCESSOR_HELPER_SHA256 = "aadb2209a95947e8d86bf7c6cb34075b4b1f376f8894b809d20a52f56ffa7dbe"


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError("59i target integration: " + detail)


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def normalized_helper(raw: bytes) -> bytes:
    pattern = rb'^CONTRACT_SHA256 = "[^"\n]+"$'
    require(len(re.findall(pattern, raw, re.M)) == 1, "helper seal must occur exactly once")
    return re.sub(pattern, b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, flags=re.M)


def source_digest(path: str, raw: bytes) -> str:
    return digest(normalized_helper(raw) if path == HELPER else raw)


def successor_review(root: Path):
    paths = (root / SUCCESSOR_HELPER, root / SUCCESSOR_CONTRACT)
    if not any(path.exists() or path.is_symlink() for path in paths):
        return None
    raw = regular(root, SUCCESSOR_HELPER)
    pattern = rb'^CONTRACT_SHA256 = "[^"\n]+"$'
    require(len(re.findall(pattern, raw, re.M)) == 1, "successor helper seal is ambiguous")
    normalized = re.sub(pattern, b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, flags=re.M)
    require(digest(normalized) == SUCCESSOR_HELPER_SHA256, "production successor reviewer changed")
    name = "increment_59i_production_successor_" + digest(raw)
    module = sys.modules.get(name)
    if module is None:
        spec = importlib.util.spec_from_file_location(name, paths[0])
        require(spec is not None and spec.loader is not None, "cannot load production successor reviewer")
        module = importlib.util.module_from_spec(spec)
        exec(compile(raw, str(paths[0]), "exec"), module.__dict__)
        sys.modules[name] = module
    # Only authenticated code and immutable Git objects are shared. Never
    # cache authorization for live files, the index, or a moving HEAD.
    module.authenticate_contract(root)
    return module


def git(root: Path, *args: str) -> bytes:
    result = subprocess.run(["git", "--literal-pathspecs", *args], cwd=root,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    require(result.returncode == 0, "git " + " ".join(args) + " failed: " +
            result.stderr.decode(errors="replace"))
    return result.stdout


def valid_path(path: object) -> bool:
    return (isinstance(path, str) and bool(path) and Path(path).as_posix() == path and
            not Path(path).is_absolute() and ".." not in Path(path).parts and
            ".git" not in Path(path).parts and "\0" not in path)


def regular(root: Path, path: str, mode: str = "100644") -> bytes:
    require(valid_path(path), "invalid source path: " + repr(path))
    relative = Path(path)
    require(all(not (root / Path(*relative.parts[:i])).is_symlink()
                for i in range(1, len(relative.parts) + 1)), "linked source: " + path)
    file = root / path
    require(file.is_file() and stat.S_ISREG(file.stat().st_mode),
            "missing regular source: " + path)
    require(mode in ("100644", "100755") and
            bool(file.stat().st_mode & 0o111) == (mode == "100755"),
            "source mode changed: " + path)
    return file.read_bytes()


def immutable_revision(root: Path, revision: str) -> str:
    if re.fullmatch(r"[0-9a-f]{40}", revision) is not None:
        return revision
    return git(root, "rev-parse", revision + "^{commit}").decode().strip()


@functools.lru_cache(maxsize=128)
def immutable_tree(root: Path, revision: str) -> tuple[tuple[str, tuple[str, str]], ...]:
    require(re.fullmatch(r"[0-9a-f]{40}", revision) is not None,
            "only immutable object names may enter the tree cache")
    result = {}
    for row in git(root, "ls-tree", "-r", "-z", revision).split(b"\0"):
        if row:
            metadata, path = row.split(b"\t", 1)
            mode, kind, oid = metadata.decode().split()
            name = path.decode()
            require(valid_path(name) and name not in result, "invalid immutable tree inventory")
            require((kind == "blob" and mode in ("100644", "100755", "120000")) or
                    (kind == "commit" and mode == "160000"), "invalid immutable object mode")
            result[name] = (mode, oid)
    return tuple(result.items())


def tree(root: Path, revision: str) -> dict[str, tuple[str, str]]:
    root = root.resolve()
    # Return a fresh mapping so callers cannot modify cached immutable evidence.
    return dict(immutable_tree(root, immutable_revision(root, revision)))


def changed(root: Path, before: str, after: str) -> set[str]:
    return {p.decode() for p in git(root, "diff", "--no-renames", "--name-only", "-z",
                                    before, after).split(b"\0") if p}


@functools.lru_cache(maxsize=32768)
def immutable_source(root: Path, revision: str, path: str) -> bytes | None:
    require(re.fullmatch(r"[0-9a-f]{40}", revision) is not None,
            "only immutable object names may enter the source cache")
    entry = dict(immutable_tree(root, revision)).get(path)
    if entry is None:
        return None
    require(entry[0] != "160000", "source projection cannot consume a gitlink: " + path)
    return git(root, "cat-file", "blob", entry[1])


def frozen(root: Path, revision: str, path: str) -> bytes | None:
    root = root.resolve()
    return immutable_source(root, immutable_revision(root, revision), path)


@functools.lru_cache(maxsize=64)
def validated_manifest(raw: bytes, common_base: str, feature_parent: str, target_parent: str) -> str:
    """Cache only validation of immutable content, never live checkout state."""
    try:
        value = json.loads(raw)
    except (ValueError, UnicodeDecodeError) as error:
        raise RuntimeError("59i target integration: invalid manifest JSON") from error
    require(isinstance(value, dict) and set(value) == {
        "schema_version", "common_base", "feature_parent", "target_parent", "source_commit",
        "feature_tree", "target_tree", "source_tree", "helper_normalized_sha256", "files"},
        "invalid manifest schema")
    require(value["schema_version"] == 1 and value["common_base"] == common_base and
            value["feature_parent"] == feature_parent and value["target_parent"] == target_parent,
            "immutable parent anchors changed")
    for key in ("source_commit", "feature_tree", "target_tree", "source_tree"):
        require(isinstance(value[key], str) and re.fullmatch(r"[0-9a-f]{40}", value[key]) is not None,
                "invalid immutable object: " + key)
    require(isinstance(value["helper_normalized_sha256"], str) and
            re.fullmatch(r"[0-9a-f]{64}", value["helper_normalized_sha256"]) is not None,
            "invalid helper hash")
    files = value["files"]
    require(isinstance(files, list) and files, "empty integration inventory")
    paths = []
    for entry in files:
        require(isinstance(entry, dict) and set(entry) == {
            "path", "mode", "merged_sha256", "feature_sha256", "target_sha256"},
            "invalid integration source entry")
        path = entry["path"]
        require(valid_path(path) and path != CONTRACT, "invalid reviewed path: " + repr(path))
        paths.append(path)
        require(entry["mode"] in (None, "100644", "100755"), "invalid reviewed mode: " + path)
        for key in ("merged_sha256", "feature_sha256", "target_sha256"):
            require(entry[key] is None or (isinstance(entry[key], str) and
                    re.fullmatch(r"[0-9a-f]{64}", entry[key]) is not None),
                    "invalid source digest: " + path)
        require((entry["mode"] is None) == (entry["merged_sha256"] is None),
                "deleted source mode/digest disagree: " + path)
        require(any(entry[key] is not None for key in
                    ("merged_sha256", "feature_sha256", "target_sha256")),
                "empty source entry: " + path)
    require(paths == sorted(set(paths)) and HELPER in paths and TEST in paths,
            "unordered, duplicate or incomplete integration inventory")
    return value["helper_normalized_sha256"]

def _authenticated_contract_bytes(root: Path) -> bytes:
    root = root.resolve()
    raw = regular(root, CONTRACT)
    require(re.fullmatch(r"[0-9a-f]{64}", CONTRACT_SHA256) is not None and
            digest(raw) == CONTRACT_SHA256, "sealed integration manifest changed")
    expected_helper = validated_manifest(raw, COMMON_BASE, FEATURE_PARENT, TARGET_PARENT)
    helper = regular(root, HELPER)
    successor = successor_review(root)
    if successor is not None:
        helper = successor.restore_source(root, HELPER, helper)
    require(digest(normalized_helper(helper)) == expected_helper,
            "sealed integration helper changed")
    return raw


def _freeze_json(value):
    if isinstance(value, dict):
        return MappingProxyType({key: _freeze_json(item) for key, item in value.items()})
    if isinstance(value, list):
        return tuple(_freeze_json(item) for item in value)
    return value


@functools.lru_cache(maxsize=8)
def _immutable_contract_view(raw: bytes):
    # Only parsed immutable data is cached; no checkout authorization is cached.
    return _freeze_json(json.loads(raw))


def _projection_contract(root: Path):
    # Every projection still authenticates current bytes, links, modes and the
    # pinned helper before consulting immutable parsed data. Internal readers
    # cannot mutate it; the public contract() retains its fresh-object API.
    return _immutable_contract_view(_authenticated_contract_bytes(root))


def contract(root: Path) -> dict:
    return json.loads(_authenticated_contract_bytes(root))


def governed(path: str) -> bool:
    return (re.search(r"(?:^|/)src/(?:main|test)/", path) is not None or
            path.startswith(("morphhdl/scripts/", "morphhdl/contracts/", "morphhdl-passes/scripts/",
                             "morphhdl-passes/tests/", "morphhdl-passes/examples/", ".github/workflows/")) or
            path in ("build.sbt", "build.mill", "morphhdl-passes/build.sbt"))


def python_cache(path: str, committed: dict[str, tuple[str, str]]) -> bool:
    """Recognize an interpreter cache beside its still-verified tracked source.

    Cache files do not authorize source bytes or participate in projections.
    Other ignored files, including Python and JSON sources, remain forbidden.
    """
    relative = Path(path)
    if relative.parent.name != "__pycache__":
        return False
    match = re.fullmatch(r"(.+)\.cpython-[0-9]+(?:\.opt-[012])?\.pyc", relative.name)
    if match is None:
        return False
    source = (relative.parent.parent / (match.group(1) + ".py")).as_posix()
    return source in committed and committed[source][0] in ("100644", "100755")


def verify(root: Path) -> dict:
    root = root.resolve()
    value = contract(root)
    live_head = git(root, "rev-parse", "HEAD^{commit}").decode().strip()
    successor = successor_review(root)
    if successor is not None:
        successor.verify(root)
    # Authenticate the complete live successor first, then retain every
    # original immutable merge/source check at its exact sealed predecessor.
    head = live_head if successor is None else successor.BASE
    for revision in (COMMON_BASE, FEATURE_PARENT, TARGET_PARENT, value["source_commit"]):
        require(git(root, "rev-parse", revision + "^{commit}").decode().strip() == revision,
                "anchor is not its exact commit object")
        git(root, "merge-base", "--is-ancestor", revision, head)
    for name, revision in (("feature", FEATURE_PARENT), ("target", TARGET_PARENT),
                           ("source", value["source_commit"])):
        require(git(root, "rev-parse", revision + "^{tree}").decode().strip() == value[name + "_tree"],
                "immutable " + name + " tree changed")
    git(root, "merge-base", "--is-ancestor", FEATURE_PARENT, value["source_commit"])
    git(root, "merge-base", "--is-ancestor", TARGET_PARENT, value["source_commit"])
    records = {entry["path"]: entry for entry in value["files"]}
    expected_paths = {HELPER, TEST}
    for revision in (FEATURE_PARENT, TARGET_PARENT, value["source_commit"]):
        expected_paths |= changed(root, COMMON_BASE, revision)
    require(set(records) == expected_paths, "complete merged source inventory changed: " +
            repr(sorted(set(records) ^ expected_paths)))
    committed = tree(root, head)
    source_tree = tree(root, value["source_commit"])
    require(CONTRACT not in source_tree, "immutable source already contained the seal manifest")
    require(set(committed) == set(source_tree) | {CONTRACT}, "current tree path inventory differs")
    for path, entry in source_tree.items():
        if path != HELPER:
            require(committed.get(path) == entry, "current tree differs from reviewed source: " + path)
    if successor is None:
        indexed = {}
        for row in git(root, "ls-files", "--stage", "-z").split(b"\0"):
            if row:
                metadata, path = row.split(b"\t", 1)
                mode, oid, stage = metadata.decode().split()
                name = path.decode()
                require(stage == "0" and name not in indexed, "unmerged or duplicate index")
                indexed[name] = (mode, oid)
        require(indexed == committed, "HEAD/index identity differs")
    for path, entry in records.items():
        for name, revision, key in (("merged", value["source_commit"], "merged_sha256"),
                                    ("feature", FEATURE_PARENT, "feature_sha256"),
                                    ("target", TARGET_PARENT, "target_sha256")):
            raw = frozen(root, revision, path)
            actual = None if raw is None else (source_digest(path, raw) if name == "merged" else digest(raw))
            require(actual == entry[key], "immutable " + name + " source differs: " + path)
        if entry["mode"] is None:
            require(path not in committed and (successor is not None or
                    (not (root / path).exists() and not (root / path).is_symlink())),
                    "deleted source reappeared: " + path)
        else:
            require(source_tree.get(path, (None,))[0] == entry["mode"], "immutable source mode differs")
            raw = (regular(root, path, entry["mode"]) if successor is None else frozen(root, head, path))
            require(source_digest(path, raw) == entry["merged_sha256"], "reviewed merged bytes changed: " + path)
    if successor is not None:
        # The historical manifest was excluded from the original source
        # commit. Prove that its exact seal addition is still the live one.
        raw = regular(root, CONTRACT)
        oid = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
        require(committed.get(CONTRACT) == ("100644", oid), "immutable predecessor seal changed")
        require(git(root, "rev-parse", "HEAD^{commit}").decode().strip() == live_head,
                "HEAD changed while validating integration")
        return value
    for path, (mode, oid) in committed.items():
        if mode == "160000":
            directory = root / path
            require(not directory.is_symlink(), "linked submodule: " + path)
            if (directory / ".git").exists():
                require(git(directory, "rev-parse", "HEAD").decode().strip() == oid,
                        "submodule revision differs: " + path)
                require(not git(directory, "status", "--porcelain", "--untracked-files=all"),
                        "submodule contains unreviewed content: " + path)
            else:
                require(not directory.exists() or not any(directory.iterdir()),
                        "uninitialized submodule contains content: " + path)
        elif governed(path) or path in records or path in (HELPER, CONTRACT):
            raw = regular(root, path, mode)
            actual = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
            require(actual == oid, "HEAD/index/worktree identity differs: " + path)
    dirty = set()
    for args in (("diff", "--name-only", "-z"), ("diff", "--cached", "--name-only", "-z", head),
                 ("ls-files", "--others", "--exclude-standard", "-z")):
        dirty |= {p.decode() for p in git(root, *args).split(b"\0") if p}
    require(not dirty, "staged, unstaged or untracked content: " + repr(sorted(dirty)))
    prefixes = {p.split("/src/", 1)[0] + "/src/" + p.split("/src/", 1)[1].split("/", 1)[0]
                for p in committed if "/src/main/" in p or "/src/test/" in p}
    prefixes |= {"morphhdl/scripts", "morphhdl/contracts", "morphhdl-passes/scripts",
                 "morphhdl-passes/tests", "morphhdl-passes/examples", ".github/workflows"}
    gitlinks = {p for p, entry in committed.items() if entry[0] == "160000"}
    for prefix in sorted(prefixes):
        directory = root / prefix
        require(not directory.is_symlink(), "linked source inventory: " + prefix)
        for current, dirs, files in os.walk(directory, followlinks=False):
            for name in dirs + files:
                file = Path(current) / name
                path = file.relative_to(root).as_posix()
                if any(path == link or path.startswith(link + "/") for link in gitlinks):
                    continue
                require(not file.is_symlink(), "linked source inventory: " + path)
                if file.is_file():
                    require(path in committed or python_cache(path, committed),
                            "untracked or ignored source addition: " + path)
            dirs[:] = [name for name in dirs if
                       (Path(current) / name).relative_to(root).as_posix() not in gitlinks]
    require(git(root, "rev-parse", "HEAD^{commit}").decode().strip() == head,
            "HEAD changed while validating integration")
    return value


def parent_source(root: Path, path: str, source: bytes, revision: str, key: str) -> bytes:
    value = _projection_contract(root)
    entry = next((entry for entry in value["files"] if entry["path"] == path), None)
    if entry is not None:
        before = frozen(root.resolve(), revision, path)
        require((digest(before) if before is not None else None) == entry[key],
                "immutable parent projection differs: " + path)
        desired = before if before is not None else b""
        if source == desired:
            return desired
    successor = successor_review(root)
    if successor is not None:
        source = successor.restore_source(root, path, source)
    if entry is None:
        return source
    require((entry["merged_sha256"] is None and source == b"") or
            (entry["merged_sha256"] is not None and source_digest(path, source) == entry["merged_sha256"]),
            "unreviewed bytes cannot enter parent projection: " + path)
    return desired


def feature_source(root: Path, path: str, source: bytes) -> bytes:
    return parent_source(root, path, source, FEATURE_PARENT, "feature_sha256")


def target_source(root: Path, path: str, source: bytes) -> bytes:
    successor = successor_review(root)
    if successor is not None and successor.target_anchor(root) is not None:
        # Keep the original path-scoped historical authentication and its
        # precise rejection before exposing the additional refreshed view.
        parent_source(root, path, source, TARGET_PARENT, "target_sha256")
        return successor.target_source(root, path, source)
    return parent_source(root, path, source, TARGET_PARENT, "target_sha256")


def project_inventory(root: Path, paths: set[str], qualification_base: str,
                      revision: str, full: bool = False) -> set[str]:
    # The manifest is an authenticated seal addition, absent from both parent
    # views. It cannot enroll itself in the pre-seal source file inventory.
    entries = {entry["path"] for entry in verify(root)["files"]} | {CONTRACT}
    successor = successor_review(root)
    if successor is not None:
        paths = successor.predecessor_inventory(root, paths, qualification_base, full)
    current = changed(root, qualification_base, "HEAD" if successor is None else successor.BASE)
    previous = changed(root, qualification_base, revision)
    domain = entries if full else {path for path in entries if re.search(r"(?:^|/)src/main/", path)}
    visible = (entries & set(paths)) | (domain - current)
    return (set(paths) - entries) | (previous & visible)


def feature_inventory(root: Path, paths: set[str], qualification_base: str, full: bool = False) -> set[str]:
    return project_inventory(root, paths, qualification_base, FEATURE_PARENT, full)


def target_inventory(root: Path, paths: set[str], qualification_base: str, full: bool = False) -> set[str]:
    successor = successor_review(root)
    if successor is not None and successor.target_anchor(root) is not None:
        # Preserve the original parent-union certificate as well as the new
        # source/target certificate before exposing the refreshed inventory.
        verify(root)
        return successor.target_inventory(root, paths, qualification_base, full)
    return project_inventory(root, paths, qualification_base, TARGET_PARENT, full)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    value = verify(args.repo_root)
    print("59I_TARGET_INTEGRATION_PASS files=" + str(len(value["files"])))


if __name__ == "__main__":
    main()
