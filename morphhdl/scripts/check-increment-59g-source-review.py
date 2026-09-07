#!/usr/bin/env python3
"""Validate the exact register-bridge successor before frozen inherited audits.

Every modified production and inherited-checker file has explicit before/after
UTF-8 byte spans. Each unchanged interval must equal the merged 59c baseline.
This review cannot authorize native edits, extra production paths, or changes
to any inherited manifest. Independent inherited audits run after restoration.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import subprocess
from pathlib import Path

BASE = "0018da2740645e0ac0c419ded7b67c01622d2bb7"
CONTRACT = "morphhdl/contracts/increment-59g-source-review.json"
CONTRACT_SHA256 = "2af2f16dc0bf5682da63346cb7b204bbd3ada90642bb0c965b60659f22aa9b4c"
PATHS = (
    "morphhdl/scripts/check-increment-59c-source-review.py",
    "morphhdl/scripts/check-increment-60f-artifacts.py",
    "morphhdl/scripts/check-increment-60f-equivalence-closure.py",
    "morphhdl/scripts/test-increment-59c-inherited-source-scope.py",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCallbackPolicy.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionClosedGraph.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionStageReplay.scala",
)
ADDED_PATHS = frozenset()
PRODUCTION_PATHS = frozenset(path for path in PATHS if "/src/main/" in path)


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def validate_contract(value: dict) -> dict[str, dict]:
    require(isinstance(value, dict) and set(value) ==
            {"schema_version", "base", "offset_format", "files"}, "invalid 59g source-review schema")
    require(value["schema_version"] == 2 and value["base"] == BASE and
            value["offset_format"] == "utf8-bytes", "59g source-review baseline or offset format changed")
    files = value["files"]
    require(isinstance(files, list) and tuple(entry.get("path") for entry in files) == PATHS,
            "59g source-review must retain its exact production and checker path inventory")
    result = {}
    identifiers = set()
    for entry in files:
        require(set(entry) == {"path", "change", "reason", "baseline_sha256", "edits"},
                "invalid 59g reviewed file schema")
        added = entry["path"] in ADDED_PATHS
        require(entry["change"] == ("added" if added else "modified"),
                "59g source-review changed its explicit added-file inventory")
        require(isinstance(entry["reason"], str) and entry["reason"].strip(),
                "59g reviewed file requires an explanation")
        require((entry["baseline_sha256"] is None) if added else
                (isinstance(entry["baseline_sha256"], str) and len(entry["baseline_sha256"]) == 64 and
                 all(character in "0123456789abcdef" for character in entry["baseline_sha256"])),
                "invalid 59g baseline content hash")
        require(isinstance(entry["edits"], list) and entry["edits"],
                "59g source-review requires explicit changed spans")
        previous_before = previous_after = 0
        for edit in entry["edits"]:
            require(isinstance(edit, dict) and set(edit) ==
                    {"id", "reason", "before_start", "before_end", "after_start", "after_end", "before", "after"},
                    "invalid 59g reviewed edit schema")
            require(isinstance(edit["id"], str) and edit["id"] and edit["id"] not in identifiers,
                    "missing or duplicate 59g reviewed edit identifier")
            identifiers.add(edit["id"])
            require(isinstance(edit["reason"], str) and edit["reason"].strip(),
                    "59g reviewed edit requires an explanation")
            for key in ("before_start", "before_end", "after_start", "after_end"):
                require(type(edit[key]) is int and edit[key] >= 0, "invalid 59g reviewed byte offset")
            require(isinstance(edit["before"], str) and isinstance(edit["after"], str) and
                    edit["before"] != edit["after"], "59g reviewed span must contain an exact change")
            require(edit["before_end"] - edit["before_start"] == len(edit["before"].encode()) and
                    edit["after_end"] - edit["after_start"] == len(edit["after"].encode()),
                    "59g span text disagrees with its exact byte offsets")
            require(edit["before_start"] >= previous_before and edit["after_start"] >= previous_after and
                    edit["before_start"] - previous_before == edit["after_start"] - previous_after,
                    "overlapping or non-corresponding 59g reviewed spans")
            previous_before, previous_after = edit["before_end"], edit["after_end"]
        if added:
            edit = entry["edits"][0]
            require(len(entry["edits"]) == 1 and edit["before_start"] == edit["before_end"] ==
                    edit["after_start"] == 0 and edit["before"] == "" and edit["after"],
                    "59g added source requires one explicit whole-file addition span")
        result[entry["path"]] = entry
    return result


def restore_reviewed(entry: dict, baseline: bytes, source: bytes) -> bytes:
    path = entry["path"]
    require((baseline == b"" and entry["baseline_sha256"] is None) if entry["change"] == "added" else
            digest(baseline) == entry["baseline_sha256"], "59g frozen baseline hash changed: " + path)
    restored = []
    previous_before = previous_after = 0
    for edit in entry["edits"]:
        old_start, old_end = edit["before_start"], edit["before_end"]
        new_start, new_end = edit["after_start"], edit["after_end"]
        require(baseline[old_start:old_end] == edit["before"].encode(),
                "59g reviewed before span does not belong to the frozen baseline: " + edit["id"])
        require(source[previous_after:new_start] == baseline[previous_before:old_start],
                "unreviewed source change outside reviewed 59g spans: " + path + " (outside reviewed 59g spans)")
        require(source[new_start:new_end] == edit["after"].encode(),
                "missing/changed 59g reviewed source span: " + edit["id"])
        restored.extend((source[previous_after:new_start], edit["before"].encode()))
        previous_before, previous_after = old_end, new_end
    require(source[previous_after:] == baseline[previous_before:],
            "unreviewed source change outside reviewed 59g spans: " + path + " (outside reviewed 59g spans)")
    restored.append(source[previous_after:])
    result = b"".join(restored)
    require(result == baseline, "59g exact reversal did not reproduce its frozen baseline: " + path)
    return result


def load_contract(root: Path) -> dict[str, dict]:
    raw = (root / CONTRACT).read_bytes()
    entries = validate_contract(json.loads(raw))
    require(digest(raw) == CONTRACT_SHA256, "59g reviewed source manifest changed")
    return entries

def production_changes(root: Path, revision: str) -> set[str]:
    tracked = subprocess.check_output(["git", "diff", "--name-only", revision], cwd=root, text=True).splitlines()
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"],
                                        cwd=root, text=True).splitlines()
    return {path for path in tracked + untracked if re.search(r"(?:^|/)src/main/", path)}


def require_production_inventory(paths: set[str]) -> None:
    require(paths == PRODUCTION_PATHS,
            "59g production delta differs from the complete reviewed inventory; missing=" +
            repr(sorted(PRODUCTION_PATHS - paths)) + "; unreviewed=" + repr(sorted(paths - PRODUCTION_PATHS)))



def baseline_source(root: Path, path: str) -> bytes:
    return subprocess.check_output(["git", "show", BASE + ":" + path], cwd=root)


def restore_source(root: Path, path: str, source: str) -> str:
    entries = load_contract(root)
    if path not in entries:
        return source
    return restore_reviewed(entries[path], baseline_source(root, path), source.encode()).decode()


def verify_spans(root: Path) -> None:
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    entries = load_contract(root)
    for relative in (CONTRACT, *PATHS):
        source = root / relative
        require(source.is_file() and not source.is_symlink() and not source.stat().st_mode & 0o111,
                "59g reviewed source must be a regular non-executable file: " + relative)
        stage = subprocess.check_output(["git", "ls-files", "--stage", "--", relative],
                                        cwd=root, text=True).split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == relative,
                "59g reviewed source is not uniquely tracked: " + relative)
        if relative in entries:
            restore_reviewed(entries[relative], baseline_source(root, relative), source.read_bytes())


def verify(root: Path) -> None:
    require_production_inventory(production_changes(root, BASE))
    verify_spans(root)
    print("59g complete production inventory and exact source spans restore the merged baseline PASS")


def self_test(root: Path) -> None:
    entries = load_contract(root)
    negatives = 0
    for path, entry in entries.items():
        baseline = baseline_source(root, path)
        parts, previous = [], 0
        for edit in entry["edits"]:
            parts.extend((baseline[previous:edit["before_start"]], edit["after"].encode()))
            previous = edit["before_end"]
        source = b"".join(parts + [baseline[previous:]])
        require(restore_reviewed(entry, baseline, source) == baseline,
                "59g exact positive source reversal failed")
        for edit in entry["edits"]:
            if not edit["after"]:
                continue
            start, end = edit["after_start"], edit["after_end"]
            for label, mutation in (
                ("changed", source[:start] + bytes([source[start] ^ 1]) + source[start + 1:]),
                ("deleted", source[:start] + source[end:]),
                ("duplicated", source[:end] + source[start:end] + source[end:]),
            ):
                try:
                    restore_reviewed(entry, baseline, mutation)
                except RuntimeError:
                    negatives += 1
                else:
                    raise RuntimeError("59g accepted " + label + " source span " + edit["id"])
        for mutation in (b"unreviewed\n" + source, source + b"\nunreviewed\n"):
            try:
                restore_reviewed(entry, baseline, mutation)
            except RuntimeError:
                negatives += 1
            else:
                raise RuntimeError("59g accepted source outside its reviewed spans")
    for changed in (set(PRODUCTION_PATHS) - {next(iter(PRODUCTION_PATHS))},
                    set(PRODUCTION_PATHS) | {"foreign/src/main/Unreviewed.scala"}):
        try:
            require_production_inventory(changed)
        except RuntimeError:
            negatives += 1
        else:
            raise RuntimeError("59g accepted incomplete or extended production inventory")
    print(f"59g source-review controls PASS: {len(entries)} frozen snapshot reversals and {negatives} rejected mutations")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    self_test(args.repo_root) if args.self_test else verify(args.repo_root)


if __name__ == "__main__":
    main()
