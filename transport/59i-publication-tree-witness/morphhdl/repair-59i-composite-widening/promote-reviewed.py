#!/usr/bin/env python3
"""Promote the exact dual-Scala widening prototype behind a successor review.

This script is intentionally source-bound. It runs from the staging commit that
contains the already-tested exact patches, applies them once, emits a separate
review contract, composes that contract ahead of the existing capture/59i
reviews, and adds mutation controls. It never edits the roadmap or target branch.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATCH_DIR = Path(__file__).resolve().parent
PATCHES = tuple(PATCH_DIR / f"0{index}-{name}.patch" for index, name in (
    (1, "leaf-replay"),
    (2, "width-schedule"),
    (3, "composite-replay"),
    (4, "tests"),
))
REVIEW_PATCHES = PATCHES[:3]
CONTRACT_PATH = ROOT / "morphhdl/contracts/increment-59i-widening-review.json"
WIDENING_CHECKER_PATH = ROOT / "morphhdl/scripts/check-increment-59i-widening-source-review.py"
WIDENING_TEST_PATH = ROOT / "morphhdl/scripts/test-increment-59i-widening-source-review.py"
PARENT_CHECKER_PATH = ROOT / "morphhdl/scripts/check-increment-59i-source-review.py"

REASONS = {
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeLeafReplay.scala":
        "Certify each changing composite result leaf through the existing native scalar graph transfer without inventing a second reduction operator.",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeWidthSchedule.scala":
        "Lift the already-qualified ragged scalar stage schedule independently over every recursive composite leaf.",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala":
        "Substitute certified per-leaf widths through composite operator and bridge replay while retargeting only fresh unassigned clones.",
}
IDS = {
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeLeafReplay.scala": "59i-widening-leaf-replay",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeWidthSchedule.scala": "59i-widening-stage-schedule",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala": "59i-widening-composite-replay",
}


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def output(*args: str) -> str:
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def load_apply_module():
    source = PATCH_DIR / "apply-exact-patches.py"
    spec = importlib.util.spec_from_file_location("increment_59i_widening_apply", source)
    require(spec is not None and spec.loader is not None, "cannot load exact patch applicator")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def byte_offset(text: str, character: int) -> int:
    return len(text[:character].encode())


def reviewed_entry(module, patch: Path, base: str) -> dict:
    old_path, new_path, hunks = module.parse(patch)
    relative = new_path or old_path
    require(relative in REASONS, "unexpected production patch: " + str(relative))
    source = (ROOT / relative).read_text()
    if old_path is None:
        require(len(hunks) == 1 and hunks[0][0] == "" and hunks[0][1] == source,
                "added widening source does not match its exact whole-file patch")
        return {
            "path": relative,
            "change": "added",
            "reason": REASONS[relative],
            "baseline_sha256": None,
            "edits": [{
                "id": IDS[relative],
                "reason": REASONS[relative],
                "before_start": 0,
                "before_end": 0,
                "after_start": 0,
                "after_end": len(source.encode()),
                "before": "",
                "after": source,
            }],
        }

    baseline = subprocess.check_output(["git", "show", f"{base}:{relative}"], cwd=ROOT).decode()
    before_cursor = after_cursor = 0
    edits = []
    for ordinal, (before, after) in enumerate(hunks, 1):
        before_index = baseline.find(before, before_cursor)
        after_index = source.find(after, after_cursor)
        require(before_index >= 0 and after_index >= 0,
                f"review hunk {ordinal} cannot be located in exact baseline/current source")
        require(baseline[before_cursor:before_index] == source[after_cursor:after_index],
                f"unreviewed bytes precede widening hunk {ordinal}")
        edits.append({
            "id": f"{IDS[relative]}-{ordinal}",
            "reason": REASONS[relative],
            "before_start": byte_offset(baseline, before_index),
            "before_end": byte_offset(baseline, before_index + len(before)),
            "after_start": byte_offset(source, after_index),
            "after_end": byte_offset(source, after_index + len(after)),
            "before": before,
            "after": after,
        })
        before_cursor = before_index + len(before)
        after_cursor = after_index + len(after)
    require(baseline[before_cursor:] == source[after_cursor:],
            "unreviewed bytes follow the widening patch hunks")
    return {
        "path": relative,
        "change": "modified",
        "reason": REASONS[relative],
        "baseline_sha256": digest(baseline.encode()),
        "edits": edits,
    }


WIDENING_CHECKER_TEMPLATE = r'''#!/usr/bin/env python3
"""Exact successor review for Increment 59i composite widening production."""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path

BASE = "__BASE__"
CONTRACT = "morphhdl/contracts/increment-59i-widening-review.json"
CONTRACT_SHA256 = "__CONTRACT_SHA256__"
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
    return subprocess.check_output(["git", "show", BASE + ":" + path], cwd=root)


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
    tracked = subprocess.check_output(["git", "diff", "--no-renames", "--name-only", revision],
                                     cwd=root, text=True).splitlines()
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"],
                                       cwd=root, text=True).splitlines()
    return {path for path in tracked + untracked if re.search(r"(?:^|/)src/main/", path)}


def verify(root: Path) -> None:
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    entries = load_contract(root)
    require(production_changes(root, BASE) == PRODUCTION_PATHS,
            "59i widening production delta differs from its reviewed three-file inventory")
    for relative in (CONTRACT, *PATHS):
        source = root / relative
        require(source.is_file() and not source.is_symlink() and not source.stat().st_mode & 0o111,
                "59i widening source must be a regular non-executable file: " + relative)
        stage = subprocess.check_output(["git", "ls-files", "--stage", "--", relative],
                                        cwd=root, text=True).split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == relative,
                "59i widening reviewed source is not uniquely tracked: " + relative)
        if relative in entries:
            baseline = baseline_source(root, relative)
            require(restore_source(root, relative, source.read_text()).encode() == baseline,
                    "59i widening exact reversal failed: " + relative)
    print("59i widening production inventory and exact successor spans PASS", flush=True)


def inherited_inventory(root: Path, paths: set[str], qualification_base: str) -> set[str]:
    verify(root)
    historical = subprocess.check_output(
        ["git", "diff", "--no-renames", "--name-only", qualification_base, BASE],
        cwd=root, text=True).splitlines()
    inherited = {path for path in historical if re.search(r"(?:^|/)src/main/", path)}
    return (paths - PRODUCTION_PATHS) | (inherited & PRODUCTION_PATHS)


def main() -> None:
    root = Path(__file__).resolve().parents[2]
    verify(root)


if __name__ == "__main__":
    main()
'''

WIDENING_TEST_TEMPLATE = r'''#!/usr/bin/env python3
"""Mutation controls for the 59i widening successor source review."""
import copy
import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "morphhdl/scripts/check-increment-59i-widening-source-review.py"
spec = importlib.util.spec_from_file_location("increment_59i_widening_review", SOURCE)
assert spec is not None and spec.loader is not None
W = importlib.util.module_from_spec(spec)
spec.loader.exec_module(W)


class WideningSourceReviewTests(unittest.TestCase):
    def test_complete_worktree_and_parent_composition(self):
        W.verify(ROOT)
        parent_source = ROOT / "morphhdl/scripts/check-increment-59i-source-review.py"
        parent_spec = importlib.util.spec_from_file_location("increment_59i_parent_review", parent_source)
        assert parent_spec is not None and parent_spec.loader is not None
        parent = importlib.util.module_from_spec(parent_spec)
        parent_spec.loader.exec_module(parent)
        parent.verify(ROOT)

    def test_every_reviewed_span_rejects_a_changed_byte(self):
        entries = W.load_contract(ROOT)
        rejected = 0
        for path, entry in entries.items():
            source = (ROOT / path).read_bytes()
            baseline = W.baseline_source(ROOT, path)
            self.assertEqual(W.restore_source(ROOT, path, source.decode()).encode(), baseline)
            for edit in entry["edits"]:
                position = edit["after_start"]
                changed = source[:position] + bytes([source[position] ^ 1]) + source[position + 1:]
                with self.assertRaises(RuntimeError):
                    W.restore_reviewed(entry, baseline, changed)
                rejected += 1
        self.assertGreaterEqual(rejected, 3)

    def test_contract_schema_rejects_missing_duplicate_and_reclassified_files(self):
        value = json.loads((ROOT / W.CONTRACT).read_text())
        mutations = []
        missing = copy.deepcopy(value); missing["files"].pop(); mutations.append(missing)
        duplicate = copy.deepcopy(value); duplicate["files"].append(copy.deepcopy(duplicate["files"][0])); mutations.append(duplicate)
        reclassified = copy.deepcopy(value); reclassified["files"][0]["change"] = "modified"; mutations.append(reclassified)
        wrong_base = copy.deepcopy(value); wrong_base["base"] = "0" * 40; mutations.append(wrong_base)
        for mutation in mutations:
            with self.assertRaises(RuntimeError):
                W.validate_contract(mutation)


if __name__ == "__main__":
    unittest.main(verbosity=2)
'''


def patch_parent_checker() -> None:
    text = PARENT_CHECKER_PATH.read_text()
    anchor = "# This successor adds two production paths; the original PATHS/manifest API is\n"
    insertion = '''WIDENING_CHECKER = "morphhdl/scripts/check-increment-59i-widening-source-review.py"\n\ndef load_widening_review(root: Path):\n    source = root / WIDENING_CHECKER\n    require(source.is_file() and not source.is_symlink() and not source.stat().st_mode & 0o111,\n            "missing regular 59i widening successor reviewer")\n    spec = importlib.util.spec_from_file_location("increment_59i_widening_successor_review", source)\n    require(spec is not None and spec.loader is not None, "cannot load 59i widening successor reviewer")\n    module = importlib.util.module_from_spec(spec)\n    spec.loader.exec_module(module)\n    return module\n\n'''
    require(text.count(anchor) == 1, "parent checker insertion anchor changed")
    text = text.replace(anchor, insertion + anchor, 1)

    old = '''def restore_source(root: Path, path: str, source: str) -> str:\n    entries = load_contract(root)\n    captures = load_capture_contract(root)\n'''
    new = '''def restore_source(root: Path, path: str, source: str) -> str:\n    source = load_widening_review(root).restore_source(root, path, source)\n    entries = load_contract(root)\n    captures = load_capture_contract(root)\n'''
    require(text.count(old) == 1, "parent restore-source anchor changed")
    text = text.replace(old, new, 1)

    old = '''def verify(root: Path) -> None:\n    paths = production_changes(root, BASE)\n    # A later independently qualified WA-07b pass implementation remains a\n'''
    new = '''def verify(root: Path) -> None:\n    paths = production_changes(root, BASE)\n    paths = load_widening_review(root).inherited_inventory(root, paths, BASE)\n    # A later independently qualified WA-07b pass implementation remains a\n'''
    require(text.count(old) == 1, "parent production-inventory anchor changed")
    text = text.replace(old, new, 1)
    PARENT_CHECKER_PATH.write_text(text)


def main() -> None:
    require(output("git", "status", "--porcelain") == "", "promotion requires a clean exact staging commit")
    base = output("git", "rev-parse", "HEAD")
    module = load_apply_module()
    changed = []
    for patch in PATCHES:
        require(patch.is_file(), "missing exact widening patch: " + str(patch))
        changed.append(module.apply(ROOT, patch))
    require(len(set(changed)) == 4, "widening patch/file cardinality changed")

    entries = [reviewed_entry(module, patch, base) for patch in REVIEW_PATCHES]
    contract = {
        "schema_version": 1,
        "base": base,
        "offset_format": "utf8-bytes",
        "files": entries,
    }
    CONTRACT_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONTRACT_PATH.write_text(json.dumps(contract, indent=2) + "\n")
    contract_sha = digest(CONTRACT_PATH.read_bytes())

    checker = WIDENING_CHECKER_TEMPLATE.replace("__BASE__", base).replace(
        "__CONTRACT_SHA256__", contract_sha)
    WIDENING_CHECKER_PATH.write_text(checker)
    WIDENING_TEST_PATH.write_text(WIDENING_TEST_TEMPLATE)
    patch_parent_checker()

    subprocess.run(["python3", "-m", "py_compile", str(WIDENING_CHECKER_PATH),
                    str(WIDENING_TEST_PATH), str(PARENT_CHECKER_PATH)], cwd=ROOT, check=True)
    print("prepared exact reviewed 59i widening production source from", base, flush=True)
    print("widening review contract sha256", contract_sha, flush=True)


if __name__ == "__main__":
    main()
