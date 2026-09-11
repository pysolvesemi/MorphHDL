#!/usr/bin/env python3
"""Compose the exact qualified WA-07b workspace with frozen inherited audits.

This read-only adapter does not bless arbitrary pass sources, relax an old
production inventory, or replace behavioral qualification. It validates one of
two complete, committed main/test source inventories before presenting the
pinned pre-WA-07b view to historical checkers. Unknown files, partial upgrades,
changed bytes, staged changes and mismatched reports remain errors.
"""
from __future__ import annotations

import argparse
import functools
import hashlib
import importlib.util
import json
import stat
import subprocess
from pathlib import Path

BASE = "125eec24465bb8576e517865089c71bf3cb0448a"
QUALIFIED = "8b28792ff27466b3f9b31df52b8916bc493e8f66"
CONTRACT = "morphhdl/contracts/wa07b-inherited-review.json"
CONTRACT_SHA256 = "6fbe86d6c9b5edde39f65a06a3631e847e63b099a814e220cabf0474fcd994fa"
ROOTS = ("morphhdl-passes/src/main", "morphhdl-passes/src/test")
MARKER = "morphhdl-passes/src/main/scala/morphhdl/passes/transform/BooleanTernarySimplificationPass.scala"
ADAPTER_PATHS = (
    "morphhdl/scripts/check-increment-59g-source-review.py",
    "morphhdl/scripts/check-increment-59h-source-review.py",
    "morphhdl/scripts/check-increment-60f-equivalence-closure.py",
    "morphhdl/scripts/check-increment-60f-artifacts.py",
)

WA08_OVERLAY = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"


def wa08_overlay(root: Path):
    path = root / WA08_OVERLAY
    if not (path.exists() or path.is_symlink()):
        return None
    require(path.is_file() and not path.is_symlink(), "missing regular WA-08 overlay")
    spec = importlib.util.spec_from_file_location("wa08_overlay", path)
    require(spec is not None and spec.loader is not None, "cannot load WA-08 overlay")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def restore_wa08(root: Path, path: str, source: bytes) -> bytes:
    overlay = wa08_overlay(root)
    return source if overlay is None else overlay.restore_source(root, path, source)



def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError("WA07B inherited review: " + detail)


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def git(root: Path, *args: str) -> bytes:
    result = subprocess.run(["git", *args], cwd=root, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False, timeout=90)
    require(result.returncode == 0, "git command failed: " + " ".join(args) + "\n" + result.stderr.decode())
    return result.stdout


def regular(root: Path, relative: str) -> bytes:
    path = root / relative
    require(path.is_file() and not path.is_symlink() and
            stat.S_ISREG(path.stat().st_mode) and not path.stat().st_mode & 0o111,
            "missing regular non-executable reviewed source: " + relative)
    # A symlinked ancestor must not redirect an otherwise regular source.
    require(all(not parent.is_symlink() for parent in path.parents if parent != root.parent),
            "symlinked reviewed source directory: " + relative)
    return path.read_bytes()


def load_contract(root: Path) -> dict:
    raw = regular(root, CONTRACT)
    require(digest(raw) == CONTRACT_SHA256, "sealed compatibility manifest changed")
    value = json.loads(raw)
    require(set(value) == {"schema_version", "baseline", "qualified_pass_commit",
                          "baseline_sources", "ternary_sources", "production_delta",
                          "checker_adapters", "pass_suites"}, "invalid compatibility manifest schema")
    require(value["schema_version"] == 1 and value["baseline"] == BASE and
            value["qualified_pass_commit"] == QUALIFIED, "compatibility anchors changed")
    require(tuple(x["path"] for x in value["checker_adapters"]) == ADAPTER_PATHS,
            "checker restoration escaped the four reviewed adapters")
    before, after = value["baseline_sources"], value["ternary_sources"]
    require(len(before) == 22 and len(after) == 26 and MARKER not in before and MARKER in after,
            "complete main/test inventories changed")
    expected = sorted(p for p in set(before) | set(after)
                      if "/src/main/" in p and before.get(p) != after.get(p))
    require(value["production_delta"] == expected and len(expected) == 3,
            "production restoration escaped the exact WA-07b delta")
    require(len(value["pass_suites"]) == 17 and sum(value["pass_suites"].values()) == 144,
            "qualified pass test inventory changed")
    return value


@functools.lru_cache(maxsize=256)
def frozen_source(root: Path, revision: str, path: str) -> bytes:
    """Only immutable Git-object bytes are cached, never current validation."""
    return git(root, "show", revision + ":" + path)


def restore_bytes(entry: dict, baseline: bytes, source: bytes) -> bytes:
    """Reverse exact spans, with unchanged gaps and the full old/new digests."""
    require(digest(baseline) == entry["before_sha256"], "adapter baseline changed: " + entry["path"])
    # Keep the existing outer source-audit diagnostic category for callers.
    require(digest(source) == entry["after_sha256"],
            "unreviewed source change outside reviewed 59g spans (WA-07b adapter): " + entry["path"])
    pieces = []
    old_cursor = new_cursor = 0
    for edit in entry["edits"]:
        a, b, c, d = (edit[x] for x in ("before_start", "before_end", "after_start", "after_end"))
        require(all(type(x) is int for x in (a, b, c, d)) and
                0 <= old_cursor <= a <= b <= len(baseline) and
                0 <= new_cursor <= c <= d <= len(source), "invalid or overlapping reviewed adapter offsets")
        require(baseline[a:b] == edit["before"].encode() and source[c:d] == edit["after"].encode(),
                "changed reviewed adapter span: " + entry["path"])
        require(baseline[old_cursor:a] == source[new_cursor:c], "unreviewed adapter gap: " + entry["path"])
        pieces.extend((source[new_cursor:c], baseline[a:b]))
        old_cursor, new_cursor = b, d
    require(baseline[old_cursor:] == source[new_cursor:], "unreviewed adapter tail: " + entry["path"])
    pieces.append(source[new_cursor:])
    restored = b"".join(pieces)
    require(restored == baseline, "adapter reversal changed frozen content: " + entry["path"])
    return restored


def restore_rollout(root: Path, path: str, source: str) -> str:
    """Reverse only the separately pinned outer publication layer, if present.

    This is byte restoration, not a validation shortcut: verify() still checks
    the complete current pass tree and index, and restore_bytes() still binds
    all original WA-07b adapter spans to their immutable baseline.
    """
    helper = root / "morphhdl/scripts/check-increment-60g-source-scope.py"
    if not (helper.exists() or helper.is_symlink()):
        return source
    require(helper.is_file() and not helper.is_symlink(), "missing regular 60g reviewer")
    spec = importlib.util.spec_from_file_location("rollout_60g_scope", helper)
    require(spec is not None and spec.loader is not None, "cannot import 60g reviewer")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.restore_60g_source(root, path, source)


def restore_adapter(root: Path, path: str, source: str) -> str:
    if path not in ADAPTER_PATHS:
        return source
    source = restore_rollout(root, path, source)
    value = load_contract(root)
    entry = next(x for x in value["checker_adapters"] if x["path"] == path)
    return restore_bytes(entry, frozen_source(root.resolve(), BASE, path), source.encode()).decode()


def tree_entries(root: Path, revision: str, paths: tuple[str, ...]) -> dict[str, tuple[str, str]]:
    result = {}
    for record in git(root, "ls-tree", "-r", "-z", revision, "--", *paths).split(b"\0"):
        if not record:
            continue
        metadata, raw_path = record.split(b"\t", 1)
        mode, kind, oid = metadata.decode().split()
        path = raw_path.decode()
        require(path not in result and kind == "blob" and mode == "100644",
                "reviewed source is not a unique regular Git blob: " + path)
        result[path] = (mode, oid)
    return result


def verify(root: Path) -> bool:
    """Validate real checkout bytes, HEAD and index before choosing a profile."""
    overlay = wa08_overlay(root)
    if overlay is not None:
        overlay.verify(root)
    value = load_contract(root)
    git(root, "merge-base", "--is-ancestor", BASE, "HEAD")
    entries = tree_entries(root, "HEAD", ROOTS)
    marker = root / MARKER
    enabled = MARKER in entries or marker.exists() or marker.is_symlink()
    inherited = subprocess.run(["git", "merge-base", "--is-ancestor", QUALIFIED, "HEAD"],
                               cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                               check=False, timeout=90)
    require(inherited.returncode in (0, 1), "qualified WA-07b ancestry is unavailable")
    require(inherited.returncode != 0 or enabled, "qualified WA-07b cannot disappear from a descendant")
    expected = value["ternary_sources" if enabled else "baseline_sources"]
    physical = set()
    for prefix in ROOTS:
        directory = root / prefix
        require(directory.is_dir() and not directory.is_symlink(), "missing regular pass source root: " + prefix)
        for path in directory.rglob("*"):
            require(not path.is_symlink(), "symlink in pass source inventory: " + path.relative_to(root).as_posix())
            if path.is_file():
                physical.add(path.relative_to(root).as_posix())
    # Older source-audit controls require this diagnostic category. The new
    # exact inventory check can reject a changed sibling before those audits
    # run; preserve the category without permitting any different source bytes.
    main = lambda paths: {path for path in paths if path.startswith(ROOTS[0] + "/")}
    require(main(entries) == main(expected) and main(physical) == main(expected),
            "unreviewed production delta: incomplete or extra pass main source inventory; " +
            "missing=" + repr(sorted(main(expected) - main(physical))) +
            "; extra=" + repr(sorted(main(physical) - main(expected))))
    # Removing only the ternary marker selects the old inventory. Verify the
    # remaining main bytes before reporting extra tests from a partial upgrade.
    for path in sorted(main(expected)):
        inherited = restore_wa08(root, path, regular(root, path))
        require(digest(inherited) == expected[path],
                "unreviewed production delta: unreviewed pass main/test bytes: " + path)
    require(set(entries) == set(expected) and physical == set(expected),
            "incomplete or extra pass main/test source inventory; " +
            "missing=" + repr(sorted(set(expected) - physical)) +
            "; extra=" + repr(sorted(physical - set(expected))))
    indexed = {}
    for record in git(root, "ls-files", "--stage", "-z", "--", *ROOTS).split(b"\0"):
        if not record:
            continue
        metadata, raw_path = record.split(b"\t", 1)
        mode, oid, stage = metadata.decode().split()
        path = raw_path.decode()
        require(stage == "0" and path not in indexed, "unmerged or duplicate pass source: " + path)
        indexed[path] = (mode, oid)
    require(indexed == entries, "staged or missing pass source differs from HEAD")
    for path, fingerprint in expected.items():
        actual = regular(root, path)
        data = restore_wa08(root, path, actual)
        category = ("unreviewed production delta" if path.startswith(ROOTS[0] + "/")
                    else "unreviewed pass test source")
        require(digest(data) == fingerprint, category + ": unreviewed pass main/test bytes: " + path)
        oid = hashlib.sha1(b"blob " + str(len(actual)).encode() + b"\0" + actual).hexdigest()
        require(oid == entries[path][1], "uncommitted pass source differs from HEAD: " + path)
    adapters = tree_entries(root, "HEAD", (*ADAPTER_PATHS, CONTRACT))
    require(set(adapters) == set(ADAPTER_PATHS) | {CONTRACT}, "uncommitted compatibility adapters or manifest")
    for entry in value["checker_adapters"]:
        path = entry["path"]
        current = regular(root, path)
        restored = restore_rollout(root, path, current.decode()).encode()
        restore_bytes(entry, frozen_source(root.resolve(), BASE, path), restored)
    # Reject a hidden index change even when the visible bytes were restored.
    dirty = git(root, "diff", "--cached", "--name-only", "HEAD", "--", *ADAPTER_PATHS, CONTRACT)
    require(not dirty.strip(), "staged compatibility adapter or manifest")
    dirty = git(root, "diff", "--name-only", "HEAD", "--", *ADAPTER_PATHS, CONTRACT)
    require(not dirty.strip(), "uncommitted compatibility adapter or manifest")
    return enabled


def inherited_inventory(root: Path, paths: set[str], qualification_base: str) -> set[str]:
    """Strip only complete verified successor deltas; retain every unrelated path."""
    if not verify(root):
        return paths
    overlay = wa08_overlay(root)
    if overlay is not None:
        paths = overlay.inherited_inventory(root, paths, qualification_base)
    delta = set(load_contract(root)["production_delta"])
    previous = set(git(root, "diff", "--no-renames", "--name-only", qualification_base, BASE,
                       "--", *sorted(delta)).decode().splitlines())
    return (paths - delta) | previous


def restore_pass_source(root: Path, path: str, source: bytes) -> bytes:
    value = load_contract(root)
    if path not in value["production_delta"]:
        return source
    # Non-pass sources may already be restored by the independently verified
    # publication layer. Only reverse the WA-08 overlay on this layer's owned
    # pass sources; their exact signatures remain mandatory below.
    source = restore_wa08(root, path, source)
    require(digest(source) == value["ternary_sources"][path], "unreviewed B source in inherited byte view: " + path)
    if path not in value["baseline_sources"]:
        require(not git(root, "ls-tree", BASE, "--", path), "new B source exists in the old baseline")
        return b""
    before = frozen_source(root.resolve(), BASE, path)
    require(digest(before) == value["baseline_sources"][path], "old pass source changed: " + path)
    return before


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    enabled = verify(args.repo_root.resolve())
    print("WA07B_INHERITED_SOURCE_PASS profile=" + ("wa07b" if enabled else "pre-wa07b"))


if __name__ == "__main__":
    main()
