#!/usr/bin/env python3
"""Promote the tested composite local-enable slice behind an exact successor review.

The helper is run only in a guarded CI worktree. It first applies the source-bound
prototype, then records the two production files as exact full-file UTF-8 spans,
adds a successor reviewer and composes that reviewer ahead of the existing 59i
widening/capture reviews. It never edits the roadmap checkbox or target branch.
"""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATCHER = Path(__file__).resolve().parent / "apply.py"
PRODUCTION = (
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala",
)
FOCUSED_TEST = (
    "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeLocalEnableTests.scala"
)
CONTRACT = ROOT / "morphhdl/contracts/increment-59i-local-enable-review.json"
CHECKER = ROOT / "morphhdl/scripts/check-increment-59i-local-enable-source-review.py"
CHECKER_TEST = ROOT / "morphhdl/scripts/test-increment-59i-local-enable-source-review.py"
PARENT = ROOT / "morphhdl/scripts/check-increment-59i-source-review.py"
DOC = ROOT / "docs/morphhdl/increment-59i-composite-local-enables.md"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def output(*args: str) -> str:
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def baseline(base: str, path: str) -> bytes:
    return subprocess.check_output(["git", "show", base + ":" + path], cwd=ROOT)


def write_contract(base: str) -> str:
    files = []
    for ordinal, relative in enumerate(PRODUCTION, 1):
        before = baseline(base, relative)
        after = (ROOT / relative).read_bytes()
        require(before != after, "local-enable promotion did not change " + relative)
        files.append({
            "path": relative,
            "change": "modified",
            "reason": (
                "Retain exact recursive same-composite control-leaf identity and width authority "
                "while replaying native register bridges; field data remains bound to its own leaf."
            ),
            "baseline_sha256": digest(before),
            "edits": [{
                "id": "59i-local-enable-full-file-" + str(ordinal),
                "reason": (
                    "One source-bound whole-file span records the complete reviewed successor "
                    "without granting any unlisted production path."
                ),
                "before_start": 0,
                "before_end": len(before),
                "after_start": 0,
                "after_end": len(after),
                "before": before.decode(),
                "after": after.decode(),
            }],
        })
    raw = (json.dumps({
        "schema_version": 1,
        "base": base,
        "offset_format": "utf8-bytes",
        "files": files,
    }, indent=2) + "\n").encode()
    CONTRACT.parent.mkdir(parents=True, exist_ok=True)
    CONTRACT.write_bytes(raw)
    return digest(raw)


CHECKER_TEMPLATE = r'''#!/usr/bin/env python3
"""Exact successor review for Increment 59i composite register-local enables."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

BASE = "__BASE__"
CONTRACT = "morphhdl/contracts/increment-59i-local-enable-review.json"
CONTRACT_SHA256 = "__CONTRACT_SHA256__"
PATHS = (
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala",
)
PRODUCTION_PATHS = frozenset(PATHS)


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=root, text=True).strip()


def validate_contract(value: dict) -> dict[str, dict]:
    require(isinstance(value, dict) and set(value) ==
            {"schema_version", "base", "offset_format", "files"},
            "invalid 59i local-enable review schema")
    require(value["schema_version"] == 1 and value["base"] == BASE and
            value["offset_format"] == "utf8-bytes",
            "59i local-enable review baseline or offset format changed")
    files = value["files"]
    require(isinstance(files, list) and tuple(entry.get("path") for entry in files) == PATHS,
            "59i local-enable review changed its exact production inventory")
    result = {}
    identifiers = set()
    for entry in files:
        require(set(entry) == {"path", "change", "reason", "baseline_sha256", "edits"} and
                entry["change"] == "modified" and isinstance(entry["reason"], str) and
                entry["reason"].strip(), "invalid 59i local-enable reviewed-file entry")
        require(isinstance(entry["baseline_sha256"], str) and
                re.fullmatch("[0-9a-f]{64}", entry["baseline_sha256"]) is not None,
                "invalid 59i local-enable baseline hash")
        edits = entry["edits"]
        require(isinstance(edits, list) and len(edits) == 1,
                "59i local-enable files require one exact whole-file span")
        edit = edits[0]
        require(set(edit) == {"id", "reason", "before_start", "before_end",
                              "after_start", "after_end", "before", "after"},
                "invalid 59i local-enable span schema")
        require(isinstance(edit["id"], str) and edit["id"] and edit["id"] not in identifiers,
                "missing or duplicate 59i local-enable span id")
        identifiers.add(edit["id"])
        require(isinstance(edit["reason"], str) and edit["reason"].strip() and
                isinstance(edit["before"], str) and isinstance(edit["after"], str) and
                edit["before"] != edit["after"], "invalid 59i local-enable span content")
        before = edit["before"].encode(); after = edit["after"].encode()
        require(edit["before_start"] == edit["after_start"] == 0 and
                edit["before_end"] == len(before) and edit["after_end"] == len(after),
                "59i local-enable whole-file offsets changed")
        require(digest(before) == entry["baseline_sha256"],
                "59i local-enable span baseline hash disagrees")
        result[entry["path"]] = entry
    return result


def load_contract(root: Path) -> dict[str, dict]:
    path = root / CONTRACT
    require(path.is_file() and not path.is_symlink() and not path.stat().st_mode & 0o111,
            "59i local-enable review must be a regular non-executable file")
    raw = path.read_bytes()
    require(digest(raw) == CONTRACT_SHA256, "59i local-enable review contract changed")
    return validate_contract(json.loads(raw))


def baseline_source(root: Path, path: str) -> bytes:
    return subprocess.check_output(["git", "show", BASE + ":" + path], cwd=root)


def restore_source(root: Path, path: str, source: str) -> str:
    entries = load_contract(root)
    if path not in entries:
        return source
    entry = entries[path]
    edit = entry["edits"][0]
    before = baseline_source(root, path)
    require(before == edit["before"].encode() and digest(before) == entry["baseline_sha256"],
            "59i local-enable frozen baseline changed: " + path)
    raw = source.encode()
    if raw == before:
        return source
    require(raw == edit["after"].encode(),
            "unreviewed source change outside 59i local-enable span: " + path)
    return before.decode()


def production_changes(root: Path, revision: str) -> set[str]:
    tracked = subprocess.check_output(
        ["git", "diff", "--no-renames", "--name-only", revision], cwd=root, text=True).splitlines()
    untracked = subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard"], cwd=root, text=True).splitlines()
    return {path for path in tracked + untracked if re.search(r"(?:^|/)src/main/", path)}


def verify(root: Path) -> None:
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    entries = load_contract(root)
    require(production_changes(root, BASE) == PRODUCTION_PATHS,
            "59i local-enable production delta differs from its exact two-file inventory")
    for relative in (CONTRACT, *PATHS):
        source = root / relative
        require(source.is_file() and not source.is_symlink() and not source.stat().st_mode & 0o111,
                "59i local-enable reviewed source must be regular: " + relative)
        stage = subprocess.check_output(
            ["git", "ls-files", "--stage", "--", relative], cwd=root, text=True).split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == relative,
                "59i local-enable source is not uniquely tracked: " + relative)
        if relative in entries:
            current = source.read_text()
            require(current.encode() == entries[relative]["edits"][0]["after"].encode(),
                    "59i local-enable current source differs from its reviewed bytes: " + relative)
            require(restore_source(root, relative, current).encode() == baseline_source(root, relative),
                    "59i local-enable exact reversal failed: " + relative)
    print("59i composite local-enable exact source review PASS", flush=True)


def inherited_inventory(root: Path, paths: set[str], qualification_base: str) -> set[str]:
    verify(root)
    historical = subprocess.check_output(
        ["git", "diff", "--no-renames", "--name-only", qualification_base, BASE],
        cwd=root, text=True).splitlines()
    inherited = {path for path in historical if re.search(r"(?:^|/)src/main/", path)}
    return (paths - PRODUCTION_PATHS) | (inherited & PRODUCTION_PATHS)


def self_test(root: Path) -> None:
    entries = load_contract(root)
    for path, entry in entries.items():
        edit = entry["edits"][0]
        source = edit["after"].encode()
        require(restore_source(root, path, source.decode()).encode() == edit["before"].encode(),
                "59i local-enable positive reversal failed")
        for index in (0, len(source) // 2, len(source) - 1):
            mutation = source[:index] + bytes([source[index] ^ 1]) + source[index + 1:]
            try:
                restore_source(root, path, mutation.decode(errors="replace"))
            except RuntimeError:
                pass
            else:
                raise RuntimeError("59i local-enable mutation was accepted: " + path)
    print("59i composite local-enable source-review mutations PASS", flush=True)


def main() -> None:
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    verify(root)
    if args.self_test:
        self_test(root)


if __name__ == "__main__":
    main()
'''


CHECKER_TEST_TEMPLATE = r'''#!/usr/bin/env python3
"""Run the exact 59i composite local-enable source-review controls."""
from __future__ import annotations
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "morphhdl/scripts/check-increment-59i-local-enable-source-review.py"
spec = importlib.util.spec_from_file_location("increment_59i_local_enable_review", SOURCE)
if spec is None or spec.loader is None:
    raise RuntimeError("cannot load 59i local-enable reviewer")
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)
review.verify(ROOT)
review.self_test(ROOT)
'''


def write_reviewer(base: str, contract_sha: str) -> None:
    CHECKER.write_text(CHECKER_TEMPLATE.replace("__BASE__", base)
        .replace("__CONTRACT_SHA256__", contract_sha))
    CHECKER_TEST.write_text(CHECKER_TEST_TEMPLATE)


def compose_parent() -> None:
    text = PARENT.read_text()
    require("LOCAL_ENABLE_CHECKER" not in text, "local-enable review is already composed")
    constant = 'WIDENING_CHECKER = "morphhdl/scripts/check-increment-59i-widening-source-review.py"\n'
    require(text.count(constant) == 1, "59i widening checker constant anchor changed")
    text = text.replace(constant, constant +
        'LOCAL_ENABLE_CHECKER = "morphhdl/scripts/check-increment-59i-local-enable-source-review.py"\n', 1)
    function = "def load_widening_review(root: Path):\n"
    require(text.count(function) == 1, "59i widening loader anchor changed")
    loader = '''def load_local_enable_review(root: Path):
    source = root / LOCAL_ENABLE_CHECKER
    require(source.is_file() and not source.is_symlink() and not source.stat().st_mode & 0o111,
            "missing regular 59i local-enable successor reviewer")
    spec = importlib.util.spec_from_file_location("increment_59i_local_enable_successor_review", source)
    require(spec is not None and spec.loader is not None,
            "cannot load 59i local-enable successor reviewer")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


'''
    text = text.replace(function, loader + function, 1)
    restore = '''def restore_source(root: Path, path: str, source: str) -> str:
    source = load_widening_review(root).restore_source(root, path, source)
'''
    require(text.count(restore) == 1, "59i restore composition anchor changed")
    text = text.replace(restore, '''def restore_source(root: Path, path: str, source: str) -> str:
    source = load_local_enable_review(root).restore_source(root, path, source)
    source = load_widening_review(root).restore_source(root, path, source)
''', 1)
    inventory = "    paths = load_widening_review(root).inherited_inventory(root, paths, BASE)\n"
    require(text.count(inventory) == 1, "59i inherited inventory anchor changed")
    text = text.replace(inventory,
        "    paths = load_local_enable_review(root).inherited_inventory(root, paths, BASE)\n" + inventory, 1)
    PARENT.write_text(text)


def write_doc(base: str) -> None:
    DOC.write_text(f'''# Increment 59i — Composite register-local enable checkpoint

Status: implemented successor checkpoint; Increment 59i remains unchecked,
incomplete, draft and unmerged.

Baseline before this successor: `{base}`.

## Supported slice

An unchanged recursive `Bundle` bridge may register every scalar leaf while its
local enable reads another original leaf of the same composite value. Data for a
result leaf remains bound to the corresponding input leaf. Control leaves are
identified only by their exact recursive order, native scalar kind, owner and
symbolic width; names and concrete-width reconstruction are not used.

A register chain updates the current field's self-reference after each stage,
while peer controls remain the original same-composite bridge inputs. Equal
register counts and one exact native clock domain are required across all leaves.
Initializer width, control-bit width, reset kind, clock edge and polarity retain
the existing native 59g contracts.

The focused source fixture uses `UInt`, `SInt`, `Bits` and `Bool` fields with
cross-field enables under synchronous-high and asynchronous-low reset profiles.
External controls, registered peer controls, cross-field data movement,
unsupported predicates, unequal latency and inexact symbolic replacements remain
rejected.

This checkpoint does not mark the full 59i join complete and does not yet claim
all generated-child, nested-Vec, saturation or end-to-end mechanism pairs.
''')


def main() -> None:
    require(PATCHER.is_file(), "missing source-bound local-enable patcher")
    require(not CONTRACT.exists() and not CHECKER.exists() and not CHECKER_TEST.exists(),
            "local-enable successor files already exist")
    base = output("git", "rev-parse", "HEAD")
    subprocess.run([sys.executable, str(PATCHER)], cwd=ROOT, check=True)
    require((ROOT / FOCUSED_TEST).is_file(), "local-enable focused test was not staged")
    contract_sha = write_contract(base)
    write_reviewer(base, contract_sha)
    compose_parent()
    write_doc(base)
    subprocess.run([sys.executable, str(CHECKER), "--self-test"], cwd=ROOT, check=True)
    print("59i composite local-enable reviewed successor staged from " + base, flush=True)


if __name__ == "__main__":
    main()
