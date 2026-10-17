#!/usr/bin/env python3
"""Restore only reviewed 59h lexical-owner edits before inherited source checks.

The exact 59h production delta and inherited-checker adapters carry reviewed
before/after byte spans against the merged base. Every byte between spans must
remain identical. Explicitly added files have one complete addition span and
must be absent from that base. Restoring these spans does not replace the
independent 60f/60e/60d/60c checks or canonical native-source audit.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import json
import re
import subprocess
from pathlib import Path


BASE = "0018da2740645e0ac0c419ded7b67c01622d2bb7"
CONTRACT = "morphhdl/contracts/increment-59h-source-review.json"
CONTRACT_SHA256 = "8a1fdfeead9e591d71f4051563af24b01dd88a6748c362061eef32b18f890449"
PATHS = (
    "core/src/main/scala/spinal/core/Vec.scala",
    "frontend/src/main/scala/morphhdl/frontend/NativeStructuralFrontend.scala",
    "frontend/src/main/scala/spinal/core/ExternalAnalyzedStructuralPublisher.scala",
    "morphhdl/contracts/increment-55-native-change-review.json",
    "morphhdl/contracts/native-source-preservation.json",
    "morphhdl/scripts/check-increment-59c-source-review.py",
    "morphhdl/scripts/check-increment-59f-source-scope.py",
    "morphhdl/scripts/check-increment-60f-artifacts.py",
    "morphhdl/scripts/check-increment-60f-equivalence-closure.py",
    "morphhdl/scripts/test-increment-59c-inherited-source-scope.py",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala",
    "morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala",
    "morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogVecs.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCapture.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeCallbackPolicy.scala",
    "morphruntime/src/main/scala/spinal/core/ElabFiniteRange.scala",
    "morphruntime/src/main/scala/spinal/core/ParameterizedStructure.scala",
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
            {"schema_version", "base", "offset_format", "files"}, "invalid 59h source-review schema")
    require(value["schema_version"] == 2 and value["base"] == BASE and
            value["offset_format"] == "utf8-bytes", "59h source-review baseline or offset format changed")
    files = value["files"]
    require(isinstance(files, list) and tuple(entry.get("path") for entry in files) == PATHS,
            "59h source-review must retain its exact production and checker path inventory")
    result = {}
    identifiers = set()
    for entry in files:
        require(set(entry) == {"path", "change", "reason", "baseline_sha256", "edits"},
                "invalid 59h reviewed file schema")
        added = entry["path"] in ADDED_PATHS
        require(entry["change"] == ("added" if added else "modified"),
                "59h source-review changed its explicit added-file inventory")
        require(isinstance(entry["reason"], str) and entry["reason"].strip(),
                "59h reviewed file requires an explanation")
        require((entry["baseline_sha256"] is None) if added else
                (isinstance(entry["baseline_sha256"], str) and len(entry["baseline_sha256"]) == 64 and
                 all(character in "0123456789abcdef" for character in entry["baseline_sha256"])),
                "invalid 59h baseline content hash")
        require(isinstance(entry["edits"], list) and entry["edits"],
                "59h source-review requires explicit changed spans")
        previous_before = previous_after = 0
        for edit in entry["edits"]:
            require(isinstance(edit, dict) and set(edit) ==
                    {"id", "reason", "before_start", "before_end", "after_start", "after_end", "before", "after"},
                    "invalid 59h reviewed edit schema")
            require(isinstance(edit["id"], str) and edit["id"] and edit["id"] not in identifiers,
                    "missing or duplicate 59h reviewed edit identifier")
            identifiers.add(edit["id"])
            require(isinstance(edit["reason"], str) and edit["reason"].strip(),
                    "59h reviewed edit requires an explanation")
            for key in ("before_start", "before_end", "after_start", "after_end"):
                require(type(edit[key]) is int and edit[key] >= 0, "invalid 59h reviewed byte offset")
            require(isinstance(edit["before"], str) and isinstance(edit["after"], str) and
                    edit["before"] != edit["after"], "59h reviewed span must contain an exact change")
            require(edit["before_end"] - edit["before_start"] == len(edit["before"].encode()) and
                    edit["after_end"] - edit["after_start"] == len(edit["after"].encode()),
                    "59h span text disagrees with its exact byte offsets")
            require(edit["before_start"] >= previous_before and edit["after_start"] >= previous_after and
                    edit["before_start"] - previous_before == edit["after_start"] - previous_after,
                    "overlapping or non-corresponding 59h reviewed spans")
            previous_before, previous_after = edit["before_end"], edit["after_end"]
        if added:
            edit = entry["edits"][0]
            require(len(entry["edits"]) == 1 and edit["before_start"] == edit["before_end"] ==
                    edit["after_start"] == 0 and edit["before"] == "" and edit["after"],
                    "59h added source requires one explicit whole-file addition span")
        result[entry["path"]] = entry
    return result


def restore_reviewed(entry: dict, baseline: bytes, source: bytes) -> bytes:
    path = entry["path"]
    require((baseline == b"" and entry["baseline_sha256"] is None) if entry["change"] == "added" else
            digest(baseline) == entry["baseline_sha256"], "59h frozen baseline hash changed: " + path)
    restored = []
    previous_before = previous_after = 0
    for edit in entry["edits"]:
        old_start, old_end = edit["before_start"], edit["before_end"]
        new_start, new_end = edit["after_start"], edit["after_end"]
        require(baseline[old_start:old_end] == edit["before"].encode(),
                "59h reviewed before span does not belong to the frozen baseline: " + edit["id"])
        require(source[previous_after:new_start] == baseline[previous_before:old_start],
                "unreviewed source change outside 59h spans: " + path + "")
        require(source[new_start:new_end] == edit["after"].encode(),
                "missing/changed 59h reviewed source span: " + edit["id"])
        restored.extend((source[previous_after:new_start], edit["before"].encode()))
        previous_before, previous_after = old_end, new_end
    require(source[previous_after:] == baseline[previous_before:],
            "unreviewed source change outside 59h spans: " + path + "")
    restored.append(source[previous_after:])
    result = b"".join(restored)
    require(result == baseline, "59h exact reversal did not reproduce its frozen baseline: " + path)
    return result


def load_contract(root: Path) -> dict[str, dict]:
    path = root / CONTRACT
    require(path.is_file() and not path.is_symlink() and not path.stat().st_mode & 0o111,
            "59h source review must be a regular non-executable file")
    raw = path.read_bytes()
    entries = validate_contract(json.loads(raw))
    require(digest(raw) == CONTRACT_SHA256, "59h reviewed source manifest changed")
    return entries


def baseline_source(root: Path, path: str, revision: str = BASE) -> bytes:
    if path in ADDED_PATHS:
        entry = subprocess.check_output(["git", "ls-tree", revision, "--", path], cwd=root)
        require(not entry, "59h explicitly added source already exists in the frozen baseline: " + path)
        return b""
    return subprocess.check_output(["git", "show", revision + ":" + path], cwd=root)


def register_source_review(root: Path):
    """Restore a separately pinned register successor before the frozen owner audit."""
    checker = root / "morphhdl/scripts/check-increment-59g-source-review.py"
    contract = root / "morphhdl/contracts/increment-59g-source-review.json"
    if not (checker.exists() or checker.is_symlink() or contract.exists() or contract.is_symlink()):
        return None
    require(checker.is_file() and not checker.is_symlink() and
            contract.is_file() and not contract.is_symlink(),
            "59g source-review checker or contract is missing")
    spec = importlib.util.spec_from_file_location("register_59g_review", checker)
    require(spec is not None and spec.loader is not None, "cannot load exact 59g source review")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def register_inherited_inventory(root: Path, paths: set[str], qualification_base: str) -> set[str]:
    # The join can add production paths disjoint from the register layer. Its
    # complete exact-source audit must restore those paths before subtraction
    # of the older register/owner inventories; never just widen their allowlist.
    register = register_source_review(root)
    if register is not None:
        join = register.join_source_review(root)
        if join is not None:
            paths = join.inherited_inventory(root, paths, qualification_base)
    reviewer = root / "morphhdl/scripts/check-wa07b-inherited-review.py"
    if reviewer.exists() or reviewer.is_symlink():
        require(reviewer.is_file() and not reviewer.is_symlink(), "missing regular WA-07b inherited reviewer")
        spec = importlib.util.spec_from_file_location("wa07b_owner_review", reviewer)
        require(spec is not None and spec.loader is not None, "cannot load WA-07b inherited reviewer")
        ternary = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(ternary)
        paths = ternary.inherited_inventory(root, paths, qualification_base)
    register = register_source_review(root)
    if register is None:
        return paths
    register.verify(root)
    historical = subprocess.check_output(
        ["git", "diff", "--no-renames", "--name-only", qualification_base, register.BASE],
        cwd=root, text=True).splitlines()
    inherited = {path for path in historical if re.search(r"(?:^|/)src/main/", path)}
    return (paths - register.PRODUCTION_PATHS) | (inherited & register.PRODUCTION_PATHS)


def rollout_scope(root: Path):
    """Load the separately pinned outer publication contract, when present."""
    helper = root / "morphhdl/scripts/check-increment-60g-source-scope.py"
    if not helper.is_file():
        return None
    spec = importlib.util.spec_from_file_location("rollout_60g_scope", helper)
    require(spec is not None and spec.loader is not None, "cannot import reviewed 60g source scope")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def restore_rollout(root: Path, path: str, source: str) -> str:
    rollout = rollout_scope(root)
    return source if rollout is None else rollout.restore_60g_source(root, path, source)


def restore_source(root: Path, path: str, source: str) -> str:
    """Leave unrelated historical hooks to their own exact source contracts."""
    register = register_source_review(root)
    joined = None if register is None else getattr(register, "join_source_review", lambda _: None)(root)
    if joined is not None:
        source = register.restore_source(root, path, source)
    else:
        source = restore_rollout(root, path, source)
        if register is not None:
            source = register.restore_source(root, path, source)
    entries = load_contract(root)
    if path not in entries:
        return source
    return restore_reviewed(entries[path], baseline_source(root, path), source.encode()).decode()


def production_changes(root: Path, revision: str) -> set[str]:
    tracked = subprocess.check_output(["git", "diff", "--name-only", revision], cwd=root, text=True).splitlines()
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"],
                                        cwd=root, text=True).splitlines()
    paths = {path for path in tracked + untracked if re.search(r"(?:^|/)src/main/", path)}
    rollout = rollout_scope(root)
    if rollout is not None:
        prior = subprocess.check_output(["git", "diff", "--name-only", revision, rollout.BASE],
                                        cwd=root, text=True).splitlines()
        paths -= set(rollout.PRODUCTION) - set(prior)
        paths = rollout.without_sibling_delta(root, paths, revision)
    return paths


def require_production_inventory(paths: set[str]) -> None:
    require(paths == PRODUCTION_PATHS,
            "59h production delta differs from the complete reviewed inventory; missing=" +
            repr(sorted(PRODUCTION_PATHS - paths)) + "; unreviewed=" + repr(sorted(paths - PRODUCTION_PATHS)))


def verify_spans(root: Path, qualification_base: str = BASE) -> None:
    """Validate the exact successor layer before inherited source-union checks."""
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    register = register_source_review(root)
    if register is not None:
        register.verify_spans(root)
    entries = load_contract(root)
    for path, entry in entries.items():
        baseline = baseline_source(root, path)
        require(baseline == baseline_source(root, path, qualification_base),
                "59h baseline differs from the inherited qualification source: " + path)
        source = root / path
        require(source.is_file(), "59h reviewed source is missing: " + path)
        require(not source.is_symlink() and not source.stat().st_mode & 0o111,
                "59h reviewed source must be a regular non-executable file: " + path)
        stage = subprocess.check_output(["git", "ls-files", "--stage", "--", path], cwd=root, text=True).split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == path,
                "59h reviewed source is not uniquely tracked: " + path)
        current = source.read_text()
        joined = None if register is None else getattr(register, "join_source_review", lambda _: None)(root)
        if joined is not None:
            current = register.restore_source(root, path, current)
        else:
            current = restore_rollout(root, path, current)
            if register is not None:
                current = register.restore_source(root, path, current)
        restore_reviewed(entry, baseline, current.encode())


def inherited_inventory(root: Path, paths: set[str], qualification_base: str) -> set[str]:
    """Remove only this verified successor delta from an older production view."""
    verify(root)
    paths = register_inherited_inventory(root, paths, qualification_base)
    historical = subprocess.check_output(
        ["git", "diff", "--no-renames", "--name-only", qualification_base, BASE],
        cwd=root, text=True).splitlines()
    inherited = {path for path in historical if re.search(r"(?:^|/)src/main/", path)}
    return (paths - PRODUCTION_PATHS) | (inherited & PRODUCTION_PATHS)


def verify(root: Path, qualification_base: str = BASE) -> None:
    rollout = rollout_scope(root)
    if rollout is not None:
        rollout.source_scope(root)
    paths = register_inherited_inventory(root, production_changes(root, qualification_base), qualification_base)
    register = register_source_review(root)
    if register is not None:
        # The complete register audit above binds current production to its
        # pinned merged baseline. Disjoint production changes already present
        # in that baseline are not new 59h edits. Exclude them only from this
        # local inventory: inherited_inventory must preserve those paths for
        # the outer 60f source-union and immutable-content checks.
        historical = subprocess.check_output(
            ["git", "diff", "--no-renames", "--name-only", qualification_base, register.BASE],
            cwd=root, text=True).splitlines()
        siblings = {path for path in historical if re.search(r"(?:^|/)src/main/", path)} - PRODUCTION_PATHS
        require(not siblings & register.PRODUCTION_PATHS,
                "59h disjoint inherited production overlaps the register review")
        paths -= siblings
    require_production_inventory(paths)
    verify_spans(root, qualification_base)
    print("59h complete production inventory and exact source spans restore the merged baseline PASS", flush=True)


def expect_failure(label: str, function, expected: str) -> None:
    try:
        function()
    except RuntimeError as error:
        require(expected in str(error), "59h source-review mutation failed for an unrelated reason: " +
                label + ": " + str(error))
        return
    raise RuntimeError("59h source-review mutation was accepted: " + label)


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
        require(restore_reviewed(entry, baseline, source) == baseline, "59h positive source reversal failed")
        edit = next(edit for edit in entry["edits"] if edit["after"])
        start, end = edit["after_start"], edit["after_end"]
        outside = "unreviewed source change outside 59h spans"
        changed_span = "missing/changed 59h reviewed source span"
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
                       "59h reviewed before span does not belong to the frozen baseline")
        negatives += 1
    contract = json.loads((root / CONTRACT).read_text())
    changed = copy.deepcopy(contract)
    changed["files"].pop()
    expect_failure("removed reviewed file", lambda: validate_contract(changed),
                   "59h source-review must retain its exact production and checker path inventory")
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
        "unreviewed source change outside 59h spans")
    require_production_inventory(set(PRODUCTION_PATHS))
    for label, paths in (
        ("unreviewed production file", set(PRODUCTION_PATHS) | {"foreign/src/main/Unreviewed.scala"}),
        ("removed production file", set(PRODUCTION_PATHS) - {next(iter(PRODUCTION_PATHS))}),
        ("same-count replacement production file", (set(PRODUCTION_PATHS) - {next(iter(PRODUCTION_PATHS))}) |
         {"foreign/src/main/Unreviewed.scala"}),
    ):
        expect_failure(label, lambda paths=paths: require_production_inventory(paths),
                       "59h production delta differs from the complete reviewed inventory")
        negatives += 1
    print(f"59h source-review controls PASS: {len(entries)} reviewed-snapshot reversals and {negatives + 2} rejected mutations")


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
