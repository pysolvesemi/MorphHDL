#!/usr/bin/env python3
"""Exact historical successor review for Increment 59i composite widening.

The widening implementation is one immutable layer in the larger 59i join.
Later, independently reviewed target-branch and pass changes may add unrelated
``src/main`` paths.  This checker therefore proves the exact BASE -> SUCCESSOR
production delta, then requires every reviewed widening source and its contract
to remain byte-identical at the current descendant.  The parent 59i reviewer
continues to own the complete current production inventory.
"""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path

BASE = "3c959a44e251df51d5a1faa6b17e5d88d7984081"
SUCCESSOR = "df76262934b38516785fc7bf51595d918a20e7d2"
CONTRACT = "morphhdl/contracts/increment-59i-widening-review.json"
CONTRACT_SHA256 = "f85db8c98de37f8fd306e320455e564cecc05aa2b079ca9041bac26ba46f03e0"
PATHS = (
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeLeafReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeWidthSchedule.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala",
)
ADDED_PATHS = frozenset(PATHS[:2])
PRODUCTION_PATHS = frozenset(PATHS)


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def git_bytes(root: Path, *arguments: str) -> bytes:
    result = subprocess.run(["git", *arguments], cwd=root, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False)
    require(result.returncode == 0,
            "59i widening Git command failed: git " + " ".join(arguments) +
            "\n" + result.stderr.decode(errors="replace"))
    return result.stdout


def revision_source(root: Path, revision: str, path: str) -> bytes:
    return git_bytes(root, "show", revision + ":" + path)


def revision_mode(root: Path, revision: str, path: str) -> str:
    line = git_bytes(root, "ls-tree", revision, "--", path).decode().strip().split()
    require(len(line) >= 4 and line[1] == "blob", "59i widening reviewed Git blob is missing: " + path)
    return line[0]


def revision_production_changes(root: Path, before: str, after: str) -> set[str]:
    changed = git_bytes(root, "diff", "--no-renames", "--name-only", before, after).decode().splitlines()
    return {path for path in changed if re.search(r"(?:^|/)src/main/", path)}


def validate_contract(value: dict) -> dict[str, dict]:
    require(isinstance(value, dict) and set(value) ==
            {"schema_version", "base", "offset_format", "files"},
            "invalid 59i widening review schema")
    require(value["schema_version"] == 1 and value["base"] == BASE and
            value["offset_format"] == "utf8-bytes",
            "59i widening review baseline or offset format changed")
    files = value["files"]
    require(isinstance(files, list) and tuple(entry.get("path") for entry in files) == PATHS,
            "59i widening review changed its exact production path inventory")
    identifiers = set()
    result = {}
    for entry in files:
        require(set(entry) == {"path", "change", "reason", "baseline_sha256", "edits"},
                "invalid 59i widening reviewed-file schema")
        added = entry["path"] in ADDED_PATHS
        require(entry["change"] == ("added" if added else "modified"),
                "59i widening review changed its added/modified inventory")
        require(isinstance(entry["reason"], str) and entry["reason"].strip(),
                "59i widening reviewed file needs a reason")
        require((entry["baseline_sha256"] is None) if added else
                (isinstance(entry["baseline_sha256"], str) and
                 re.fullmatch("[0-9a-f]{64}", entry["baseline_sha256"]) is not None),
                "invalid 59i widening baseline hash")
        edits = entry["edits"]
        require(isinstance(edits, list) and edits, "59i widening file needs explicit spans")
        previous_before = previous_after = 0
        for edit in edits:
            require(set(edit) == {"id", "reason", "before_start", "before_end",
                                  "after_start", "after_end", "before", "after"},
                    "invalid 59i widening span schema")
            require(isinstance(edit["id"], str) and edit["id"] and edit["id"] not in identifiers,
                    "missing or duplicate 59i widening span id")
            identifiers.add(edit["id"])
            require(isinstance(edit["reason"], str) and edit["reason"].strip(),
                    "59i widening span needs a reason")
            for key in ("before_start", "before_end", "after_start", "after_end"):
                require(type(edit[key]) is int and edit[key] >= 0,
                        "invalid 59i widening byte offset")
            require(isinstance(edit["before"], str) and isinstance(edit["after"], str) and
                    edit["before"] != edit["after"],
                    "59i widening span must contain an exact change")
            require(edit["before_end"] - edit["before_start"] == len(edit["before"].encode()) and
                    edit["after_end"] - edit["after_start"] == len(edit["after"].encode()),
                    "59i widening span text disagrees with byte offsets")
            require(edit["before_start"] >= previous_before and edit["after_start"] >= previous_after and
                    edit["before_start"] - previous_before == edit["after_start"] - previous_after,
                    "overlapping or non-corresponding 59i widening spans")
            previous_before, previous_after = edit["before_end"], edit["after_end"]
        if added:
            edit = edits[0]
            require(len(edits) == 1 and edit["before_start"] == edit["before_end"] ==
                    edit["after_start"] == 0 and edit["before"] == "" and edit["after"],
                    "added widening source needs one whole-file span")
        result[entry["path"]] = entry
    return result


def load_contract(root: Path) -> dict[str, dict]:
    path = root / CONTRACT
    require(path.is_file() and not path.is_symlink() and not path.stat().st_mode & 0o111,
            "59i widening review must be a regular non-executable file")
    raw = path.read_bytes()
    require(digest(raw) == CONTRACT_SHA256, "59i widening review contract changed")
    return validate_contract(json.loads(raw))


def baseline_source(root: Path, path: str) -> bytes:
    if path in ADDED_PATHS:
        return b""
    return revision_source(root, BASE, path)


def restore_reviewed(entry: dict, baseline: bytes, source: bytes) -> bytes:
    path = entry["path"]
    require((baseline == b"" and entry["baseline_sha256"] is None) if entry["change"] == "added" else
            digest(baseline) == entry["baseline_sha256"],
            "59i widening frozen baseline changed: " + path)
    restored = []
    previous_before = previous_after = 0
    for edit in entry["edits"]:
        old_start, old_end = edit["before_start"], edit["before_end"]
        new_start, new_end = edit["after_start"], edit["after_end"]
        require(baseline[old_start:old_end] == edit["before"].encode(),
                "widening before-span is not in its baseline: " + edit["id"])
        require(source[previous_after:new_start] == baseline[previous_before:old_start],
                "unreviewed source change outside widening spans: " + path)
        require(source[new_start:new_end] == edit["after"].encode(),
                "missing or changed widening source span: " + edit["id"])
        restored.extend((source[previous_after:new_start], edit["before"].encode()))
        previous_before, previous_after = old_end, new_end
    require(source[previous_after:] == baseline[previous_before:],
            "unreviewed source change outside widening spans: " + path)
    restored.append(source[previous_after:])
    result = b"".join(restored)
    require(result == baseline, "widening reversal did not reproduce its exact baseline: " + path)
    return result


def restore_source(root: Path, path: str, source: str) -> str:
    entries = load_contract(root)
    if path not in entries:
        return source
    baseline = baseline_source(root, path)
    raw = source.encode()
    if raw == baseline:
        return source
    return restore_reviewed(entries[path], baseline, raw).decode()


def production_changes(root: Path, revision: str) -> set[str]:
    tracked = git_bytes(root, "diff", "--no-renames", "--name-only", revision).decode().splitlines()
    untracked = git_bytes(root, "ls-files", "--others", "--exclude-standard").decode().splitlines()
    return {path for path in tracked + untracked if re.search(r"(?:^|/)src/main/", path)}


def verify(root: Path) -> None:
    # The exact reviewed layer is immutable and must remain in the ancestry.
    git_bytes(root, "merge-base", "--is-ancestor", BASE, SUCCESSOR)
    git_bytes(root, "merge-base", "--is-ancestor", SUCCESSOR, "HEAD")
    entries = load_contract(root)
    require(revision_production_changes(root, BASE, SUCCESSOR) == PRODUCTION_PATHS,
            "59i widening successor delta differs from its reviewed three-file inventory")
    require(revision_source(root, SUCCESSOR, CONTRACT) == (root / CONTRACT).read_bytes(),
            "59i widening contract differs from its immutable successor")
    for relative in PATHS:
        source = root / relative
        require(source.is_file() and not source.is_symlink() and not source.stat().st_mode & 0o111,
                "59i widening source must be a regular non-executable file: " + relative)
        stage = git_bytes(root, "ls-files", "--stage", "--", relative).decode().split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == relative,
                "59i widening reviewed source is not uniquely tracked: " + relative)
        require(revision_mode(root, SUCCESSOR, relative) == "100644",
                "59i widening successor source mode changed: " + relative)
        reviewed = revision_source(root, SUCCESSOR, relative)
        require(source.read_bytes() == reviewed,
                "59i widening production source differs from its immutable reviewed successor: " + relative)
        baseline = baseline_source(root, relative)
        require(restore_reviewed(entries[relative], baseline, reviewed) == baseline,
                "59i widening exact successor reversal failed: " + relative)
    print("59i widening immutable successor and exact reviewed spans PASS", flush=True)


def inherited_inventory(root: Path, paths: set[str], qualification_base: str) -> set[str]:
    """Remove only the verified widening layer from the caller's full inventory."""
    verify(root)
    historical = git_bytes(root, "diff", "--no-renames", "--name-only",
                           qualification_base, BASE).decode().splitlines()
    inherited = {path for path in historical if re.search(r"(?:^|/)src/main/", path)}
    return (paths - PRODUCTION_PATHS) | (inherited & PRODUCTION_PATHS)


def main() -> None:
    root = Path(__file__).resolve().parents[2]
    verify(root)


if __name__ == "__main__":
    main()
