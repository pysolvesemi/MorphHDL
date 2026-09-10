#!/usr/bin/env python3
"""Exact one-line successor review for the 59i native core-tree anchor.

The historical 59i source contract remains immutable and is validated at the
supplied predecessor.  This reviewer accepts only a direct successor whose sole
committed change replaces the stale aggregate ``core/src/main`` approved-tree
anchor with the already reviewed current tree.  File entries, reviewed spans,
production source bytes and every other manifest byte must remain unchanged.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import stat
import subprocess
import sys
from pathlib import Path

MANIFEST = "morphhdl/contracts/native-source-preservation.json"
EXPECTED_BASE_BLOB = "d99bc8f8e48a1740e49ad3bc05438073737822b6"
OLD_TREE = "dc4a734b29cd4b5ac63ea017cc6e580ab6d4a4a5"
NEW_TREE = "943729ae908f900ec07cc7d7be666b64915e6aff"
OLD_LINE = f'      "approved_tree": "{OLD_TREE}"\n'.encode()
NEW_LINE = f'      "approved_tree": "{NEW_TREE}"\n'.encode()


class ReviewError(RuntimeError):
    pass


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise ReviewError("59i native-tree-anchor review: " + detail)


def git(root: Path, *arguments: str, check: bool = True) -> subprocess.CompletedProcess[bytes]:
    result = subprocess.run(["git", *arguments], cwd=root, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False)
    if check:
        require(result.returncode == 0, "git " + " ".join(arguments) + " failed: " +
                result.stderr.decode(errors="replace").strip())
    return result


def git_text(root: Path, *arguments: str) -> str:
    return git(root, *arguments).stdout.decode().strip()


def blob_oid(value: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(value)).encode() + b"\0" + value).hexdigest()


def replace_exact(before: bytes) -> bytes:
    require(before.count(OLD_LINE) == 1, "predecessor has no unique stale core-tree anchor")
    require(before.count(NEW_LINE) == 0, "predecessor already contains the successor core-tree anchor")
    return before.replace(OLD_LINE, NEW_LINE, 1)


def verify_bytes(before: bytes, after: bytes) -> None:
    require(blob_oid(before) == EXPECTED_BASE_BLOB,
            "predecessor manifest blob changed: " + blob_oid(before))
    expected = replace_exact(before)
    require(after == expected, "successor changes bytes outside the exact aggregate tree anchor")
    require(after.count(OLD_LINE) == 0 and after.count(NEW_LINE) == 1,
            "successor core-tree anchor multiplicity changed")
    require(after.replace(NEW_LINE, OLD_LINE, 1) == before,
            "successor reversal does not reproduce the exact predecessor")


def regular(path: Path) -> bytes:
    require(path.is_file() and not path.is_symlink() and stat.S_ISREG(path.stat().st_mode),
            "manifest is not a regular file")
    require(not path.stat().st_mode & 0o111, "manifest became executable")
    return path.read_bytes()


def verify(root: Path, predecessor: str) -> None:
    predecessor = git_text(root, "rev-parse", predecessor + "^{commit}")
    head = git_text(root, "rev-parse", "HEAD^{commit}")
    parents = git_text(root, "rev-list", "--parents", "-n", "1", "HEAD").split()
    require(len(parents) == 2 and parents[1] == predecessor,
            "reviewed candidate must be a direct one-parent successor of the supplied predecessor")
    require(head != predecessor, "candidate did not create a successor commit")

    before = git(root, "show", predecessor + ":" + MANIFEST).stdout
    after = regular(root / MANIFEST)
    verify_bytes(before, after)

    changed = git_text(root, "diff", "--no-renames", "--name-only", predecessor, head).splitlines()
    require(changed == [MANIFEST], "candidate changed files outside the manifest: " + repr(changed))
    status = git_text(root, "diff", "--no-renames", "--name-status", predecessor, head).splitlines()
    require(status == ["M\t" + MANIFEST], "manifest change status is not one modification: " + repr(status))
    numstat = git_text(root, "diff", "--numstat", predecessor, head, "--", MANIFEST)
    require(numstat == "1\t1\t" + MANIFEST, "manifest delta is not exactly one removed and one added line")

    base_tree = git_text(root, "rev-parse", predecessor + ":core/src/main")
    head_tree = git_text(root, "rev-parse", "HEAD:core/src/main")
    require(base_tree == NEW_TREE and head_tree == NEW_TREE,
            "production core tree changed or does not match the reviewed aggregate anchor")

    stage = git_text(root, "ls-files", "--stage", "--", MANIFEST).split()
    require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == MANIFEST,
            "successor manifest is not one stage-zero regular Git blob")
    require(stage[1] == blob_oid(after), "index manifest differs from the checked-out successor")
    require(not git_text(root, "status", "--porcelain=v1", "--untracked-files=all"),
            "candidate checkout has staged, unstaged or untracked changes")

    print("59i native core-tree anchor exact successor review PASS")
    print("  predecessor : " + predecessor)
    print("  successor   : " + head)
    print("  old tree    : " + OLD_TREE)
    print("  new tree    : " + NEW_TREE)
    print("  manifest    : " + blob_oid(after))


def self_test(root: Path) -> None:
    before = git(root, "show", "HEAD:" + MANIFEST).stdout
    # The checked-out staging predecessor still has the old anchor.  When this
    # test is run from a candidate, use its direct parent instead.
    if before.count(OLD_LINE) != 1:
        before = git(root, "show", "HEAD^:" + MANIFEST).stdout
    after = replace_exact(before)
    verify_bytes(before, after)
    rejected = 0
    mutations = (
        after.replace(NEW_TREE.encode(), ("0" * 40).encode(), 1),
        after + b"\n",
        after.replace(b'"schema_version": 2', b'"schema_version": 3', 1),
        after.replace(NEW_LINE, NEW_LINE + NEW_LINE, 1),
        before,
    )
    for mutation in mutations:
        try:
            verify_bytes(before, mutation)
        except ReviewError:
            rejected += 1
        else:
            raise ReviewError("59i native-tree-anchor review self-test accepted a mutation")
    require(rejected == len(mutations), "self-test rejection count changed")
    print(f"59i native core-tree anchor review self-test PASS: {rejected} mutations rejected")


def repository_root(explicit: Path | None) -> Path:
    if explicit is not None:
        root = explicit.resolve()
    else:
        probe = subprocess.run(["git", "rev-parse", "--show-toplevel"],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        require(probe.returncode == 0, "not inside a Git repository")
        root = Path(probe.stdout.decode().strip()).resolve()
    require(Path(git_text(root, "rev-parse", "--show-toplevel")).resolve() == root,
            "repository root argument is not the Git top level")
    return root


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path)
    parser.add_argument("--predecessor")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    try:
        root = repository_root(args.repo_root)
        if args.self_test:
            require(args.predecessor is None, "--self-test does not accept --predecessor")
            self_test(root)
        else:
            require(bool(args.predecessor), "--predecessor is required")
            verify(root, args.predecessor)
        return 0
    except ReviewError as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
