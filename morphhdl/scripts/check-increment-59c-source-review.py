#!/usr/bin/env python3
"""Restore only reviewed 59c publication edits before inherited source checks.

The exact 59c production delta and inherited-checker adapter carry reviewed
before/after byte spans against the merged base. Every byte between spans must
remain identical. Explicitly added files have one complete addition span and
must be absent from that base. Restoring these spans does not replace the
independent 60f/60e/60d/60c checks or canonical native-source audit.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import subprocess
from pathlib import Path


BASE = "99b6017d7ac69112a088680457029623620224d3"
CONTRACT = "morphhdl/contracts/increment-59c-source-review.json"
CONTRACT_SHA256 = "0b39a3cdfdd00fb6ada6339148d32196e5429accadcb6bf2689264b22fba3837"
PATHS = (
    "core/src/main/scala/spinal/core/ParameterizedVec.scala",
    "core/src/main/scala/spinal/core/Vec.scala",
    "morphhdl/scripts/check-increment-59f-source-scope.py",
    "morphhdl/scripts/check-increment-60e-signedness-boundaries.py",
    "morphhdl/scripts/check-increment-60f-artifacts.py",
    "morphhdl/scripts/check-increment-60f-equivalence-closure.py",
    "morphhdl/src/main/scala/morphhdl/MorphNamedFieldVectors.scala",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala",
    "morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala",
    "morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogFieldLayout.scala",
    "morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogVecs.scala",
)
ADDED_PATHS = frozenset((
    "morphhdl/src/main/scala/morphhdl/MorphNamedFieldVectors.scala",
    "morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogFieldLayout.scala",
))
PRODUCTION_PATHS = frozenset(path for path in PATHS if "/src/main/" in path)


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def validate_contract(value: dict) -> dict[str, dict]:
    require(isinstance(value, dict) and set(value) ==
            {"schema_version", "base", "offset_format", "files"}, "invalid 59c source-review schema")
    require(value["schema_version"] == 2 and value["base"] == BASE and
            value["offset_format"] == "utf8-bytes", "59c source-review baseline or offset format changed")
    files = value["files"]
    require(isinstance(files, list) and tuple(entry.get("path") for entry in files) == PATHS,
            "59c source-review must retain its exact production and checker path inventory")
    result = {}
    identifiers = set()
    for entry in files:
        require(set(entry) == {"path", "change", "reason", "baseline_sha256", "edits"},
                "invalid 59c reviewed file schema")
        added = entry["path"] in ADDED_PATHS
        require(entry["change"] == ("added" if added else "modified"),
                "59c source-review changed its explicit added-file inventory")
        require(isinstance(entry["reason"], str) and entry["reason"].strip(),
                "59c reviewed file requires an explanation")
        require((entry["baseline_sha256"] is None) if added else
                (isinstance(entry["baseline_sha256"], str) and len(entry["baseline_sha256"]) == 64 and
                 all(character in "0123456789abcdef" for character in entry["baseline_sha256"])),
                "invalid 59c baseline content hash")
        require(isinstance(entry["edits"], list) and entry["edits"],
                "59c source-review requires explicit changed spans")
        previous_before = previous_after = 0
        for edit in entry["edits"]:
            require(isinstance(edit, dict) and set(edit) ==
                    {"id", "reason", "before_start", "before_end", "after_start", "after_end", "before", "after"},
                    "invalid 59c reviewed edit schema")
            require(isinstance(edit["id"], str) and edit["id"] and edit["id"] not in identifiers,
                    "missing or duplicate 59c reviewed edit identifier")
            identifiers.add(edit["id"])
            require(isinstance(edit["reason"], str) and edit["reason"].strip(),
                    "59c reviewed edit requires an explanation")
            for key in ("before_start", "before_end", "after_start", "after_end"):
                require(type(edit[key]) is int and edit[key] >= 0, "invalid 59c reviewed byte offset")
            require(isinstance(edit["before"], str) and isinstance(edit["after"], str) and
                    edit["before"] != edit["after"], "59c reviewed span must contain an exact change")
            require(edit["before_end"] - edit["before_start"] == len(edit["before"].encode()) and
                    edit["after_end"] - edit["after_start"] == len(edit["after"].encode()),
                    "59c span text disagrees with its exact byte offsets")
            require(edit["before_start"] >= previous_before and edit["after_start"] >= previous_after and
                    edit["before_start"] - previous_before == edit["after_start"] - previous_after,
                    "overlapping or non-corresponding 59c reviewed spans")
            previous_before, previous_after = edit["before_end"], edit["after_end"]
        if added:
            edit = entry["edits"][0]
            require(len(entry["edits"]) == 1 and edit["before_start"] == edit["before_end"] ==
                    edit["after_start"] == 0 and edit["before"] == "" and edit["after"],
                    "59c added source requires one explicit whole-file addition span")
        result[entry["path"]] = entry
    return result


def restore_reviewed(entry: dict, baseline: bytes, source: bytes) -> bytes:
    path = entry["path"]
    require((baseline == b"" and entry["baseline_sha256"] is None) if entry["change"] == "added" else
            digest(baseline) == entry["baseline_sha256"], "59c frozen baseline hash changed: " + path)
    restored = []
    previous_before = previous_after = 0
    for edit in entry["edits"]:
        old_start, old_end = edit["before_start"], edit["before_end"]
        new_start, new_end = edit["after_start"], edit["after_end"]
        require(baseline[old_start:old_end] == edit["before"].encode(),
                "59c reviewed before span does not belong to the frozen baseline: " + edit["id"])
        require(source[previous_after:new_start] == baseline[previous_before:old_start],
                "unreviewed source change outside 60e spans: " + path + " (outside reviewed 59c spans)")
        require(source[new_start:new_end] == edit["after"].encode(),
                "missing/changed 59c reviewed source span: " + edit["id"])
        restored.extend((source[previous_after:new_start], edit["before"].encode()))
        previous_before, previous_after = old_end, new_end
    require(source[previous_after:] == baseline[previous_before:],
            "unreviewed source change outside 60e spans: " + path + " (outside reviewed 59c spans)")
    restored.append(source[previous_after:])
    result = b"".join(restored)
    require(result == baseline, "59c exact reversal did not reproduce its frozen baseline: " + path)
    return result


def load_contract(root: Path) -> dict[str, dict]:
    raw = (root / CONTRACT).read_bytes()
    entries = validate_contract(json.loads(raw))
    require(digest(raw) == CONTRACT_SHA256, "59c reviewed source manifest changed")
    return entries


def baseline_source(root: Path, path: str, revision: str = BASE) -> bytes:
    if path in ADDED_PATHS:
        entry = subprocess.check_output(["git", "ls-tree", revision, "--", path], cwd=root)
        require(not entry, "59c explicitly added source already exists in the frozen baseline: " + path)
        return b""
    return subprocess.check_output(["git", "show", revision + ":" + path], cwd=root)


def restore_source(root: Path, path: str, source: str) -> str:
    """Leave unrelated historical hooks to their own exact source contracts."""
    entries = load_contract(root)
    if path not in entries:
        return source
    return restore_reviewed(entries[path], baseline_source(root, path), source.encode()).decode()


def production_changes(root: Path, revision: str) -> set[str]:
    tracked = subprocess.check_output(["git", "diff", "--name-only", revision], cwd=root, text=True).splitlines()
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"],
                                        cwd=root, text=True).splitlines()
    return {path for path in tracked + untracked if re.search(r"(?:^|/)src/main/", path)}


def require_production_inventory(paths: set[str]) -> None:
    require(paths == PRODUCTION_PATHS,
            "59c production delta differs from the complete reviewed inventory; missing=" +
            repr(sorted(PRODUCTION_PATHS - paths)) + "; unreviewed=" + repr(sorted(paths - PRODUCTION_PATHS)))


def verify_spans(root: Path, qualification_base: str = BASE) -> None:
    """Validate the exact successor layer before inherited source-union checks."""
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    entries = load_contract(root)
    for path, entry in entries.items():
        baseline = baseline_source(root, path)
        require(baseline == baseline_source(root, path, qualification_base),
                "59c baseline differs from the inherited qualification source: " + path)
        source = root / path
        require(source.is_file(), "59c reviewed source is missing: " + path)
        require(not source.is_symlink() and not source.stat().st_mode & 0o111,
                "59c reviewed source must be a regular non-executable file: " + path)
        stage = subprocess.check_output(["git", "ls-files", "--stage", "--", path], cwd=root, text=True).split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == path,
                "59c reviewed source is not uniquely tracked: " + path)
        restore_reviewed(entry, baseline, source.read_bytes())


def verify(root: Path, qualification_base: str = BASE) -> None:
    require_production_inventory(production_changes(root, qualification_base))
    verify_spans(root, qualification_base)
    print("59c complete production inventory and exact source spans restore the merged baseline PASS")


def expect_failure(label: str, function, expected: str) -> None:
    try:
        function()
    except RuntimeError as error:
        require(expected in str(error), "59c source-review mutation failed for an unrelated reason: " +
                label + ": " + str(error))
        return
    raise RuntimeError("59c source-review mutation was accepted: " + label)


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
        require(restore_reviewed(entry, baseline, source) == baseline, "59c positive source reversal failed")
        edit = next(edit for edit in entry["edits"] if edit["after"])
        start, end = edit["after_start"], edit["after_end"]
        outside = "unreviewed source change outside 60e spans"
        changed_span = "missing/changed 59c reviewed source span"
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
                       "59c reviewed before span does not belong to the frozen baseline")
        negatives += 1
    contract = json.loads((root / CONTRACT).read_text())
    changed = copy.deepcopy(contract)
    changed["files"].pop()
    expect_failure("removed reviewed file", lambda: validate_contract(changed),
                   "59c source-review must retain its exact production and checker path inventory")
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
        "unreviewed source change outside 60e spans")
    require_production_inventory(set(PRODUCTION_PATHS))
    for label, paths in (
        ("unreviewed production file", set(PRODUCTION_PATHS) | {"foreign/src/main/Unreviewed.scala"}),
        ("removed production file", set(PRODUCTION_PATHS) - {next(iter(PRODUCTION_PATHS))}),
        ("same-count replacement production file", (set(PRODUCTION_PATHS) - {next(iter(PRODUCTION_PATHS))}) |
         {"foreign/src/main/Unreviewed.scala"}),
    ):
        expect_failure(label, lambda paths=paths: require_production_inventory(paths),
                       "59c production delta differs from the complete reviewed inventory")
        negatives += 1
    print(f"59c source-review controls PASS: {len(entries)} reviewed-snapshot reversals and {negatives + 2} rejected mutations")


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
