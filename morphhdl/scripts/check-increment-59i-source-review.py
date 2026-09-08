#!/usr/bin/env python3
"""Validate exact 59i successor source before unchanged inherited audits.

This development review binds only the implemented scoped-composite slice. It
neither marks the integration join complete nor waives native, behavioral or
formal gates. Every production path changed from the pinned integration base
must be in this inventory; every changed byte must match an explicit span.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import subprocess
from pathlib import Path

BASE = 'd32fcf71fc81662618d72b0ec0a5d8a59c50b4d6'
CONTRACT = "morphhdl/contracts/increment-59i-source-review.json"
CONTRACT_SHA256 = 'f9d41a62fb3a9775669d52dd68f3e4158208541e039634db15234b65691e835a'
PATHS = (
    'core/src/main/scala/spinal/core/Vec.scala',
    'morphhdl/contracts/increment-55-native-change-review.json',
    'morphhdl/contracts/native-source-preservation.json',
    'morphhdl/scripts/check-increment-59g-source-review.py',
    'morphhdl/scripts/test-increment-59h-inherited-source-scope.py',
    'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala',
    'morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala',
    'morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogVecs.scala',
    'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala',
    'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeCallbackPolicy.scala',
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
            {"schema_version", "base", "offset_format", "files"}, "invalid 59i source-review schema")
    require(value["schema_version"] == 2 and value["base"] == BASE and
            value["offset_format"] == "utf8-bytes", "59i source-review baseline or offset format changed")
    files = value["files"]
    require(isinstance(files, list) and tuple(entry.get("path") for entry in files) == PATHS,
            "59i source-review must retain its exact production and checker path inventory")
    result = {}
    identifiers = set()
    for entry in files:
        require(set(entry) == {"path", "change", "reason", "baseline_sha256", "edits"},
                "invalid 59i reviewed file schema")
        added = entry["path"] in ADDED_PATHS
        require(entry["change"] == ("added" if added else "modified"),
                "59i source-review changed its explicit added-file inventory")
        require(isinstance(entry["reason"], str) and entry["reason"].strip(),
                "59i reviewed file requires an explanation")
        require((entry["baseline_sha256"] is None) if added else
                (isinstance(entry["baseline_sha256"], str) and len(entry["baseline_sha256"]) == 64 and
                 all(character in "0123456789abcdef" for character in entry["baseline_sha256"])),
                "invalid 59i baseline content hash")
        require(isinstance(entry["edits"], list) and entry["edits"],
                "59i source-review requires explicit changed spans")
        previous_before = previous_after = 0
        for edit in entry["edits"]:
            require(isinstance(edit, dict) and set(edit) ==
                    {"id", "reason", "before_start", "before_end", "after_start", "after_end", "before", "after"},
                    "invalid 59i reviewed edit schema")
            require(isinstance(edit["id"], str) and edit["id"] and edit["id"] not in identifiers,
                    "missing or duplicate 59i reviewed edit identifier")
            identifiers.add(edit["id"])
            require(isinstance(edit["reason"], str) and edit["reason"].strip(),
                    "59i reviewed edit requires an explanation")
            for key in ("before_start", "before_end", "after_start", "after_end"):
                require(type(edit[key]) is int and edit[key] >= 0, "invalid 59i reviewed byte offset")
            require(isinstance(edit["before"], str) and isinstance(edit["after"], str) and
                    edit["before"] != edit["after"], "59i reviewed span must contain an exact change")
            require(edit["before_end"] - edit["before_start"] == len(edit["before"].encode()) and
                    edit["after_end"] - edit["after_start"] == len(edit["after"].encode()),
                    "59i span text disagrees with its exact byte offsets")
            require(edit["before_start"] >= previous_before and edit["after_start"] >= previous_after and
                    edit["before_start"] - previous_before == edit["after_start"] - previous_after,
                    "overlapping or non-corresponding 59i reviewed spans")
            previous_before, previous_after = edit["before_end"], edit["after_end"]
        if added:
            edit = entry["edits"][0]
            require(len(entry["edits"]) == 1 and edit["before_start"] == edit["before_end"] ==
                    edit["after_start"] == 0 and edit["before"] == "" and edit["after"],
                    "59i added source requires one explicit whole-file addition span")
        result[entry["path"]] = entry
    return result


def restore_reviewed(entry: dict, baseline: bytes, source: bytes) -> bytes:
    path = entry["path"]
    require((baseline == b"" and entry["baseline_sha256"] is None) if entry["change"] == "added" else
            digest(baseline) == entry["baseline_sha256"], "59i frozen baseline hash changed: " + path)
    restored = []
    previous_before = previous_after = 0
    for edit in entry["edits"]:
        old_start, old_end = edit["before_start"], edit["before_end"]
        new_start, new_end = edit["after_start"], edit["after_end"]
        require(baseline[old_start:old_end] == edit["before"].encode(),
                "59i reviewed before span does not belong to the frozen baseline: " + edit["id"])
        require(source[previous_after:new_start] == baseline[previous_before:old_start],
                "unreviewed source change outside 59i spans: " + path + "")
        require(source[new_start:new_end] == edit["after"].encode(),
                "missing/changed 59i reviewed source span: " + edit["id"])
        restored.extend((source[previous_after:new_start], edit["before"].encode()))
        previous_before, previous_after = old_end, new_end
    require(source[previous_after:] == baseline[previous_before:],
            "unreviewed source change outside 59i spans: " + path + "")
    restored.append(source[previous_after:])
    result = b"".join(restored)
    require(result == baseline, "59i exact reversal did not reproduce its frozen baseline: " + path)
    return result


def load_contract(root: Path) -> dict[str, dict]:
    path = root / CONTRACT
    require(path.is_file() and not path.is_symlink() and not path.stat().st_mode & 0o111,
            "59i source review must be a regular non-executable file")
    raw = path.read_bytes()
    entries = validate_contract(json.loads(raw))
    require(digest(raw) == CONTRACT_SHA256, "59i reviewed source manifest changed")
    return entries


def baseline_source(root: Path, path: str) -> bytes:
    return subprocess.check_output(["git", "show", BASE + ":" + path], cwd=root)


def restore_source(root: Path, path: str, source: str) -> str:
    entries = load_contract(root)
    if path not in entries:
        return source
    return restore_reviewed(entries[path], baseline_source(root, path), source.encode()).decode()


def production_changes(root: Path, revision: str) -> set[str]:
    tracked = subprocess.check_output(["git", "diff", "--no-renames", "--name-only", revision],
                                     cwd=root, text=True).splitlines()
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"],
                                       cwd=root, text=True).splitlines()
    return {path for path in tracked + untracked if re.search(r"(?:^|/)src/main/", path)}


def require_production_inventory(paths: set[str]) -> None:
    require(paths == PRODUCTION_PATHS,
            "59i production delta differs from the complete reviewed inventory; missing=" +
            repr(sorted(PRODUCTION_PATHS - paths)) + "; unreviewed=" + repr(sorted(paths - PRODUCTION_PATHS)))


def verify_spans(root: Path) -> None:
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    entries = load_contract(root)
    for relative in (CONTRACT, *PATHS):
        source = root / relative
        require(source.is_file() and not source.is_symlink() and not source.stat().st_mode & 0o111,
                "59i reviewed source must be a regular non-executable file: " + relative)
        stage = subprocess.check_output(["git", "ls-files", "--stage", "--", relative],
                                        cwd=root, text=True).split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == relative,
                "59i reviewed source is not uniquely tracked: " + relative)
        if relative in entries:
            restore_reviewed(entries[relative], baseline_source(root, relative), source.read_bytes())


def verify(root: Path) -> None:
    require_production_inventory(production_changes(root, BASE))
    verify_spans(root)
    print("59i complete production inventory and exact reviewed spans restore the integration baseline PASS", flush=True)


def inherited_inventory(root: Path, paths: set[str], qualification_base: str) -> set[str]:
    """Remove only the verified join delta, not older overlapping source edits."""
    verify(root)
    historical = subprocess.check_output(
        ["git", "diff", "--no-renames", "--name-only", qualification_base, BASE],
        cwd=root, text=True).splitlines()
    inherited = {path for path in historical if re.search(r"(?:^|/)src/main/", path)}
    return (paths - PRODUCTION_PATHS) | (inherited & PRODUCTION_PATHS)


def expect_failure(label: str, function, expected: str) -> None:
    try:
        function()
    except RuntimeError as error:
        require(expected in str(error), "59i source-review mutation failed for an unrelated reason: " +
                label + ": " + str(error))
        return
    raise RuntimeError("59i source-review mutation was accepted: " + label)


def self_test(root: Path) -> None:
    # Test the reviewed snapshot in memory. The separate verify invocation is
    # mandatory for current-source qualification and rejects a stale review.
    # This keeps the negative controls reproducible while an implementation
    # branch is still changing before its final review refresh.
    entries = load_contract(root)
    negatives = 0
    for path, entry in entries.items():
        baseline = baseline_source(root, path)
        parts = []
        previous = 0
        for edit in entry["edits"]:
            parts.extend((baseline[previous:edit["before_start"]], edit["after"].encode()))
            previous = edit["before_end"]
        parts.append(baseline[previous:])
        source = b"".join(parts)
        require(restore_reviewed(entry, baseline, source) == baseline, "59i positive source reversal failed")
        edit = next(edit for edit in entry["edits"] if edit["after"])
        start, end = edit["after_start"], edit["after_end"]
        outside = "unreviewed source change outside 59i spans"
        changed_span = "missing/changed 59i reviewed source span"
        mutations = (
            ("unreviewed prefix", b"// unreviewed\n" + source, changed_span if start == 0 else outside),
            ("unreviewed suffix", source + b"\n// unreviewed\n", outside),
            ("changed reviewed source", source[:start] + bytes([source[start] ^ 1]) + source[start + 1:], changed_span),
            ("deleted reviewed source", source[:start] + source[end:], changed_span),
            ("duplicated reviewed source", source[:end] + source[start:end] + source[end:], outside),
        )
        for label, mutation, expected in mutations:
            expect_failure(path + " " + label,
                           lambda mutation=mutation: restore_reviewed(entry, baseline, mutation), expected)
            negatives += 1
        changed = copy.deepcopy(entry)
        changed["edits"][0]["before"] = "corrupt" + changed["edits"][0]["before"]
        expect_failure(path + " forged baseline span", lambda: restore_reviewed(changed, baseline, source),
                       "59i reviewed before span does not belong to the frozen baseline")
        negatives += 1
    contract = json.loads((root / CONTRACT).read_text())
    changed = copy.deepcopy(contract)
    changed["files"].pop()
    expect_failure("removed reviewed file", lambda: validate_contract(changed),
                   "59i source-review must retain its exact production and checker path inventory")
    changed = copy.deepcopy(contract)
    changed["files"][0]["edits"].pop()
    first = entries[PATHS[0]]
    baseline = baseline_source(root, PATHS[0])
    parts = []
    previous = 0
    for edit in first["edits"]:
        parts.extend((baseline[previous:edit["before_start"]], edit["after"].encode()))
        previous = edit["before_end"]
    source = b"".join(parts + [baseline[previous:]])
    expect_failure("removed reviewed edit", lambda: restore_reviewed(
        changed["files"][0], baseline, source),
        "unreviewed source change outside 59i spans")
    require_production_inventory(set(PRODUCTION_PATHS))
    for label, paths in (
        ("unreviewed production file", set(PRODUCTION_PATHS) | {"foreign/src/main/Unreviewed.scala"}),
        ("removed production file", set(PRODUCTION_PATHS) - {next(iter(PRODUCTION_PATHS))}),
        ("same-count replacement production file", (set(PRODUCTION_PATHS) - {next(iter(PRODUCTION_PATHS))}) |
         {"foreign/src/main/Unreviewed.scala"}),
    ):
        expect_failure(label, lambda paths=paths: require_production_inventory(paths),
                       "59i production delta differs from the complete reviewed inventory")
        negatives += 1
    print(f"59i source-review controls PASS: {len(entries)} reviewed-snapshot reversals and {negatives + 2} rejected mutations")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test(args.repo_root)
    else:
        verify(args.repo_root)


if __name__ == "__main__":
    main()
