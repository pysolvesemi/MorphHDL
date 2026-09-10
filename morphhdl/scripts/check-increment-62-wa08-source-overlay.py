#!/usr/bin/env python3
"""Verify the WA-08 delta before projecting exact baseline bytes for older audits."""
from __future__ import annotations

import argparse
import functools
import hashlib
import json
import re
import stat
import subprocess
from pathlib import Path

BASE = "2ebaa2ef5561eab35aa0ba9caced5c5a314d59f6"
HELPER = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"
CONTRACT = "morphhdl/contracts/increment-62-wa08-source-overlay.json"
CONTRACT_SHA256 = "4dafe34aa779a23145cf0c52659584611df0a4a564a866a83716f22bb12b0a1d"


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError("WA-08 source overlay: " + detail)


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def git(root: Path, *args: str) -> bytes:
    result = subprocess.run(["git", "--literal-pathspecs", *args], cwd=root,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    require(result.returncode == 0, "git failed: " + " ".join(args) + "\n" +
            result.stderr.decode(errors="replace"))
    return result.stdout


def governed(path: str) -> bool:
    return (re.search(r"(?:^|/)src/(?:main|test)/", path) is not None or
            path.startswith(("morphhdl/scripts/", "morphhdl/contracts/",
                             "morphhdl-passes/scripts/", "morphhdl-passes/tests/",
                             "morphhdl-passes/examples/", ".github/workflows/")) or
            path in ("build.sbt", "build.mill", "morphhdl-passes/build.sbt"))


def regular(root: Path, path: str, mode: str = "100644") -> bytes:
    relative = Path(path)
    require(not relative.is_absolute() and ".." not in relative.parts,
            "invalid reviewed path: " + path)
    file = root / relative
    require(all(not (root / Path(*relative.parts[:i])).is_symlink()
                for i in range(1, len(relative.parts) + 1)),
            "linked reviewed source: " + path)
    require(file.is_file() and stat.S_ISREG(file.stat().st_mode),
            "missing regular reviewed source: " + path)
    require(mode in ("100644", "100755") and
            bool(file.stat().st_mode & 0o111) == (mode == "100755"),
            "reviewed source mode differs: " + path)
    return file.read_bytes()


def normalized_helper(raw: bytes) -> bytes:
    return re.sub(rb'^CONTRACT_SHA256 = "[^"]+"$',
                  b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, count=1, flags=re.M)


def contract(root: Path) -> dict:
    raw = regular(root, CONTRACT)
    require(digest(raw) == CONTRACT_SHA256, "sealed WA-08 manifest changed")
    value = json.loads(raw)
    require(set(value) == {"schema_version", "base", "final_source_commit",
                          "helper_normalized_sha256", "files"} and
            value["schema_version"] == 1 and value["base"] == BASE,
            "invalid WA-08 manifest schema or baseline")
    require(re.fullmatch(r"[0-9a-f]{40}", value["final_source_commit"]) is not None,
            "invalid immutable final source anchor")
    paths = [entry["path"] for entry in value["files"]]
    require(bool(paths) and paths == sorted(set(paths)), "duplicate or unordered inventory")
    for entry in value["files"]:
        require(set(entry) == {"path", "mode", "before_sha256", "after_sha256"} and
                entry["mode"] in ("100644", "100755") and
                re.fullmatch(r"[0-9a-f]{64}", entry["after_sha256"]) is not None and
                (entry["before_sha256"] is None or
                 re.fullmatch(r"[0-9a-f]{64}", entry["before_sha256"]) is not None),
                "invalid reviewed source entry")
    require(digest(normalized_helper(regular(root, HELPER))) ==
            value["helper_normalized_sha256"], "sealed overlay helper changed")
    return value


@functools.lru_cache(maxsize=4096)
def frozen(root: Path, revision: str, path: str) -> bytes | None:
    entries = git(root, "ls-tree", "-z", revision, "--", path)
    return git(root, "show", revision + ":" + path) if entries else None


def tree(root: Path, revision: str) -> dict[str, tuple[str, str]]:
    result = {}
    for row in git(root, "ls-tree", "-r", "-z", revision).split(b"\0"):
        if row:
            metadata, path = row.split(b"\t", 1)
            mode, kind, blob = metadata.decode().split()
            result[path.decode()] = (mode, blob)
    return result


def verify(root: Path) -> dict:
    root = root.resolve()
    value = contract(root)
    head = git(root, "rev-parse", "HEAD").decode().strip()
    git(root, "merge-base", "--is-ancestor", BASE, head)
    git(root, "merge-base", "--is-ancestor", value["final_source_commit"], head)
    records = {entry["path"]: entry for entry in value["files"]}
    committed = tree(root, head)
    final = tree(root, value["final_source_commit"])
    indexed = {}
    for row in git(root, "ls-files", "--stage", "-z").split(b"\0"):
        if row:
            metadata, path = row.split(b"\t", 1)
            mode, blob, stage = metadata.decode().split()
            require(stage == "0" and path.decode() not in indexed, "unmerged index")
            indexed[path.decode()] = (mode, blob)
    for path, entry in records.items():
        raw = regular(root, path, entry["mode"])
        require(digest(raw) == entry["after_sha256"],
                "unreviewed production delta: current reviewed bytes differ: " + path)
        old = frozen(root, BASE, path)
        require((digest(old) if old is not None else None) == entry["before_sha256"],
                "immutable baseline bytes differ: " + path)
        expected = (entry["mode"], hashlib.sha1(
            b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest())
        require(final.get(path) == expected, "immutable final source differs: " + path)
        require(committed.get(path) == indexed.get(path) == expected,
                "HEAD/index/worktree identity differs: " + path)
    for path in (HELPER, CONTRACT):
        raw = regular(root, path)
        expected = ("100644", hashlib.sha1(
            b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest())
        require(committed.get(path) == indexed.get(path) == expected,
                "HEAD/index/worktree identity differs: " + path)
    changed = {p.decode() for p in git(root, "diff", "--no-renames", "--name-only",
                                      "-z", BASE, head).split(b"\0") if p}
    expected = set(records) | {HELPER, CONTRACT}
    require({p for p in changed if governed(p)} == {p for p in expected if governed(p)},
            "unreviewed production delta: governed inventory differs: " +
            repr(sorted({p for p in changed ^ expected if governed(p)})))
    dirty = set()
    for args in (("diff", "--name-only", "-z"),
                 ("diff", "--cached", "--name-only", "-z", head),
                 ("ls-files", "--others", "--exclude-standard", "-z")):
        dirty |= {p.decode() for p in git(root, *args).split(b"\0") if p}
    require(not {p for p in dirty if governed(p)},
            "staged, unstaged or untracked governed content: " + repr(sorted(dirty)))
    # A gitlink owns a separate pinned inventory. A recursive CI checkout must
    # not make its .git metadata look like untracked superproject source.
    gitlinks = {p: entry for p, entry in committed.items() if entry[0] == "160000"}
    for path, entry in gitlinks.items():
        directory = root / path
        require(not directory.is_symlink() and indexed.get(path) == entry,
                "linked or staged submodule differs: " + path)
        if (directory / ".git").exists():
            require(git(directory, "rev-parse", "HEAD").decode().strip() == entry[1],
                    "submodule differs from its pinned revision: " + path)
            require(not git(directory, "status", "--porcelain", "--untracked-files=all"),
                    "submodule contains unreviewed source: " + path)
        else:
            require(not directory.exists() or not any(directory.iterdir()),
                    "uninitialized submodule contains unreviewed files: " + path)
    # Include ignored source additions and linked ancestors; Git status alone
    # cannot detect ignored files or assume-unchanged worktree corruption.
    prefixes = {p.split("/src/", 1)[0] + "/src/" + p.split("/src/", 1)[1].split("/", 1)[0]
                for p in committed if "/src/main/" in p or "/src/test/" in p}
    for prefix in sorted(prefixes):
        directory = root / prefix
        require(not directory.is_symlink(), "linked source inventory: " + prefix)
        for file in directory.rglob("*"):
            path = file.relative_to(root).as_posix()
            if any(path == link or path.startswith(link + "/") for link in gitlinks):
                continue
            require(not file.is_symlink(), "linked source inventory: " + path)
            if file.is_file():
                require(path in committed, "untracked or ignored source addition: " + path)
    return value


def restore_source(root: Path, path: str, source: bytes) -> bytes:
    entry = next((e for e in contract(root)["files"] if e["path"] == path), None)
    if entry is None:
        return source
    before = frozen(root.resolve(), BASE, path) or b""
    require(source == before or digest(source) == entry["after_sha256"],
            "unreviewed bytes cannot enter historical projection: " + path)
    return before


def restore_text(root: Path, path: str, source: str) -> str:
    return restore_source(root, path, source.encode()).decode()


def inherited_inventory(root: Path, paths: set[str], revision: str) -> set[str]:
    entries = {entry["path"] for entry in verify(root)["files"]}
    # Callers may supply a production-only inventory. Do not introduce test or
    # audit paths from the historical tree into that narrower domain.
    visible = (entries & set(paths)) | {p for p in entries if "/src/main/" in "/" + p}
    previous = {p.decode() for p in git(root, "diff", "--no-renames", "--name-only",
                                       "-z", revision, BASE).split(b"\0") if p}
    return (set(paths) - entries) | (previous & visible)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        subprocess.run(["python3", str(args.repo_root /
            "morphhdl/scripts/test-increment-62-wa08-source-overlay.py")], check=True)
    else:
        value = verify(args.repo_root)
        print("WA08_SOURCE_OVERLAY_PASS files=" + str(len(value["files"])))


if __name__ == "__main__":
    main()
