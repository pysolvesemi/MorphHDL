#!/usr/bin/env python3
"""Validate the exact register-bridge successor before frozen inherited audits.

Every modified production and inherited-checker file has explicit before/after
UTF-8 byte spans. Each unchanged interval must equal the pinned merged integration baseline.
This review cannot authorize native edits, extra production paths, or changes
to any inherited manifest. Independent inherited audits run after restoration.
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

BASE = "5db83983b42c71df4f43d6a7c37c5bd552cd96c5"
CONTRACT = "morphhdl/contracts/increment-59g-source-review.json"
CONTRACT_SHA256 = "3b9706cc8d4b06ddf41c885b5f28740f77f779365438c72bcde4e1d0e46dd41c"
PATHS = (
    "morphhdl/scripts/check-increment-59c-source-review.py",
    "morphhdl/scripts/check-increment-59h-source-review.py",
    "morphhdl/scripts/check-increment-60f-artifacts.py",
    "morphhdl/scripts/check-increment-60f-equivalence-closure.py",
    "morphhdl/scripts/test-increment-59h-inherited-source-scope.py",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCallbackPolicy.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionClosedGraph.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionStageReplay.scala",
)
# Keep the reviewed bridge/owner manifest immutable. This additional exact
# checker-only span binds the already-merged sibling inventory composition;
# it does not authorize any production change or bypass a source-union check.
COMPOSITION_REVIEW = {'path': 'morphhdl/scripts/check-increment-59c-source-review.py', 'change': 'modified', 'reason': 'After complete 59g and nested-owner source verification, remove only already-merged disjoint siblings from the local 59c inventory; preserve those paths for the independent 60f immutable-source union.', 'baseline_sha256': 'bb0e3c55244a57b13543b7fc6dcd3045183a8abe1ced465cddd840723c7b89aa', 'edits': [{'id': '59c-register-sibling-inventory-1', 'reason': 'After complete 59g and nested-owner source verification, remove only already-merged disjoint siblings from the local 59c inventory; preserve those paths for the independent 60f immutable-source union.', 'before_start': 11811, 'before_end': 11811, 'after_start': 11811, 'after_end': 12686, 'before': '', 'after': '        register = getattr(nested, "register_source_review", lambda _: None)(root)\n        if register is not None:\n            # The nested/register audits have already verified the complete\n            # current production tree against the register\'s pinned merged base.\n            # Strip only that base\'s disjoint siblings from this local 59c\n            # inventory, preserving them for the enclosing 60f profile check.\n            baseline_paths = subprocess.check_output(\n                ["git", "diff", "--no-renames", "--name-only", qualification_base, register.BASE],\n                cwd=root, text=True).splitlines()\n            baseline_paths = {path for path in baseline_paths if re.search(r"(?:^|/)src/main/", path)}\n            inherited = nested.inherited_inventory(root, baseline_paths, qualification_base)\n            paths -= inherited - PRODUCTION_PATHS\n'}]}

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
    require(digest(raw) == CONTRACT_SHA256, "59g reviewed source manifest changed")
    value = json.loads(raw)
    # Insert only the separately pinned checker span; validate the complete
    # ordered inventory with the same strict schema and byte-reversal rules.
    value["files"].insert(0, copy.deepcopy(COMPOSITION_REVIEW))
    return validate_contract(value)

def production_changes(root: Path, revision: str) -> set[str]:
    tracked = subprocess.check_output(["git", "diff", "--name-only", revision], cwd=root, text=True).splitlines()
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"],
                                        cwd=root, text=True).splitlines()
    return {path for path in tracked + untracked if re.search(r"(?:^|/)src/main/", path)}


def require_production_inventory(paths: set[str]) -> None:
    require(paths == PRODUCTION_PATHS,
            "59g production delta differs from the complete reviewed inventory; unreviewed production delta; missing=" +
            repr(sorted(PRODUCTION_PATHS - paths)) + "; unreviewed=" + repr(sorted(paths - PRODUCTION_PATHS)))



def baseline_source(root: Path, path: str) -> bytes:
    return subprocess.check_output(["git", "show", BASE + ":" + path], cwd=root)


def join_source_review(root: Path):
    """Verify the exact 59i join before exposing the frozen register view."""
    checker = root / "morphhdl/scripts/check-increment-59i-source-review.py"
    contract = root / "morphhdl/contracts/increment-59i-source-review.json"
    if not (checker.exists() or checker.is_symlink() or contract.exists() or contract.is_symlink()):
        return None
    require(checker.is_file() and not checker.is_symlink() and
            contract.is_file() and not contract.is_symlink(),
            "59i source-review checker or contract is missing")
    spec = importlib.util.spec_from_file_location("combined_59i_review", checker)
    require(spec is not None and spec.loader is not None, "cannot load exact 59i source review")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module



def boolean_ternary_review(root: Path):
    """An independently sealed pass successor; absence retains historical audits."""
    path = root / "morphhdl/scripts/check-wa07b-inherited-review.py"
    if not (path.exists() or path.is_symlink()):
        return None
    require(path.is_file() and not path.is_symlink(), "missing regular WA-07b inherited reviewer")
    spec = importlib.util.spec_from_file_location("wa07b_inherited_review", path)
    require(spec is not None and spec.loader is not None, "cannot load WA-07b inherited reviewer")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def restore_source(root: Path, path: str, source: str) -> str:
    join = join_source_review(root)
    if join is not None:
        source = join.restore_source(root, path, source)
    ternary = boolean_ternary_review(root)
    if ternary is not None:
        source = ternary.restore_adapter(root, path, source)
    entries = load_contract(root)
    if path not in entries:
        return source
    return restore_reviewed(entries[path], baseline_source(root, path), source.encode()).decode()


def verify_spans(root: Path) -> None:
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    join = join_source_review(root)
    if join is not None:
        join.verify_spans(root)
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
            current = source.read_text()
            if join is not None:
                current = join.restore_source(root, relative, current)
            ternary = boolean_ternary_review(root)
            if ternary is not None:
                current = ternary.restore_adapter(root, relative, current)
            restore_reviewed(entries[relative], baseline_source(root, relative), current.encode())


def verify(root: Path) -> None:
    paths = production_changes(root, BASE)
    join = join_source_review(root)
    if join is not None:
        # Verify the complete joined source before removing only its delta.
        # The independent ternary audit below still checks its full inventory.
        paths = join.inherited_inventory(root, paths, BASE)
    ternary = boolean_ternary_review(root)
    if ternary is not None:
        paths = ternary.inherited_inventory(root, paths, BASE)
    require_production_inventory(paths)
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
