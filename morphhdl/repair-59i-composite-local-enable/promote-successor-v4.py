#!/usr/bin/env python3
"""Prepare an exact, idempotent 59i composite-local-enable successor.

The historical 59i and current target branches are reviewed by their own pinned
parent audits.  This helper binds only the local-enable successor: two production
files and its focused test.  It never edits the roadmap or target branch.
"""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
APPLY = HERE / "apply.py"
FIX = HERE / "fix-generated.py"
STATEMENTS = HERE / "include-statements.py"
PRODUCTION = (
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala",
)
TEST = "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeLocalEnableTests.scala"
REVIEWED = (*PRODUCTION, TEST)
CONTRACT = ROOT / "morphhdl/contracts/increment-59i-local-enable-successor-v4.json"
CHECKER = ROOT / "morphhdl/scripts/check-increment-59i-local-enable-successor-v4.py"
DOC = ROOT / "docs/morphhdl/increment-59i-composite-local-enable-successor-v4.md"
MARKERS = {
    PRODUCTION[0]: (
        "controlValues: Vector[BaseType]",
        "BRIDGE-CONTROL-BINDING",
    ),
    PRODUCTION[1]: (
        "hasLocalEnables",
        "BRIDGE-CONTROL-REGISTER",
        "callback.assignments ++ callback.statements",
    ),
}


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError(detail)


def run(*args: str) -> None:
    subprocess.run(args, cwd=ROOT, check=True)


def output(*args: str) -> str:
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def committed(path: str, revision: str = "HEAD") -> bytes:
    return subprocess.check_output(["git", "show", revision + ":" + path], cwd=ROOT)


def implementation_present() -> bool:
    return all((ROOT / path).is_file() and all(marker in (ROOT / path).read_text()
               for marker in markers) for path, markers in MARKERS.items())


def implementation_committed() -> bool:
    try:
        return all(all(marker in committed(path).decode() for marker in markers)
                   for path, markers in MARKERS.items())
    except subprocess.CalledProcessError:
        return False


def find_before_revision() -> str:
    """Find the exact committed predecessor for the first local-enable marker."""
    path = PRODUCTION[0]
    marker = MARKERS[path][0]
    commits = output("git", "log", "--format=%H", "-S" + marker, "--", path).splitlines()
    require(commits, "cannot locate the committed local-enable introduction")
    for commit in commits:
        parent = output("git", "rev-parse", commit + "^")
        try:
            before = committed(path, parent).decode()
            after = committed(path, commit).decode()
        except subprocess.CalledProcessError:
            continue
        if marker not in before and marker in after:
            return parent
    raise RuntimeError("cannot identify the exact local-enable predecessor")


def prepare_implementation() -> str:
    if implementation_present():
        require(implementation_committed(),
                "local-enable markers exist only in an uncommitted worktree")
        return find_before_revision()
    require(not implementation_committed(),
            "committed local-enable implementation disappeared from the worktree")
    base = output("git", "rev-parse", "HEAD")
    for source in (APPLY, FIX, STATEMENTS):
        require(source.is_file(), "missing exact local-enable patch stage: " + str(source))
        run(sys.executable, str(source))
    require(implementation_present(), "local-enable patch stages did not produce all markers")
    require((ROOT / TEST).is_file(), "local-enable patch did not produce its focused test")
    return base


def entry(base: str, path: str, ordinal: int) -> dict:
    after = (ROOT / path).read_bytes()
    try:
        before = committed(path, base)
        change = "modified"
        baseline_hash = digest(before)
    except subprocess.CalledProcessError:
        before = b""
        change = "added"
        baseline_hash = None
    require(before != after, "reviewed successor path did not change from its predecessor: " + path)
    return {
        "path": path,
        "change": change,
        "reason": ("Exact same-composite control identity, symbolic-width replay and "
                   "focused rejection coverage for native composite register bridges."),
        "baseline_sha256": baseline_hash,
        "edits": [{
            "id": "59i-local-enable-v4-whole-file-" + str(ordinal),
            "reason": "One exact full-file span grants no unlisted production or test path.",
            "before_start": 0,
            "before_end": len(before),
            "after_start": 0,
            "after_end": len(after),
            "before": before.decode(),
            "after": after.decode(),
        }],
    }


CHECKER_TEMPLATE = r'''#!/usr/bin/env python3
"""Exact narrow successor review for 59i composite register-local enables."""
from __future__ import annotations
import argparse
import copy
import hashlib
import json
import subprocess
from pathlib import Path

BASE = "__BASE__"
CONTRACT = "morphhdl/contracts/increment-59i-local-enable-successor-v4.json"
CONTRACT_SHA256 = "__CONTRACT_SHA256__"
PATHS = (
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala",
    "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeLocalEnableTests.scala",
)
PRODUCTION = frozenset(PATHS[:2])


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError(detail)


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def baseline(root: Path, path: str, change: str) -> bytes:
    if change == "added":
        return b""
    return subprocess.check_output(["git", "show", BASE + ":" + path], cwd=root)


def load(root: Path) -> dict[str, dict]:
    raw = (root / CONTRACT).read_bytes()
    require(digest(raw) == CONTRACT_SHA256, "59i local-enable v4 contract changed")
    value = json.loads(raw)
    require(set(value) == {"schema_version", "base", "offset_format", "files"} and
            value["schema_version"] == 1 and value["base"] == BASE and
            value["offset_format"] == "utf8-bytes",
            "invalid 59i local-enable v4 contract header")
    files = value["files"]
    require(tuple(item.get("path") for item in files) == PATHS,
            "59i local-enable v4 reviewed path inventory changed")
    result = {}
    identifiers = set()
    for item in files:
        require(set(item) == {"path", "change", "reason", "baseline_sha256", "edits"},
                "invalid 59i local-enable v4 file entry")
        require(item["change"] in ("added", "modified") and item["reason"].strip(),
                "invalid 59i local-enable v4 file metadata")
        edits = item["edits"]
        require(len(edits) == 1, "local-enable v4 requires one full-file span")
        edit = edits[0]
        require(set(edit) == {"id", "reason", "before_start", "before_end",
                              "after_start", "after_end", "before", "after"},
                "invalid local-enable v4 span schema")
        require(edit["id"] and edit["id"] not in identifiers and edit["reason"].strip(),
                "missing or duplicate local-enable v4 span identity")
        identifiers.add(edit["id"])
        old = edit["before"].encode(); new = edit["after"].encode()
        require(old != new and edit["before_start"] == edit["after_start"] == 0 and
                edit["before_end"] == len(old) and edit["after_end"] == len(new),
                "local-enable v4 full-file offsets/content changed")
        frozen = baseline(root, item["path"], item["change"])
        require(frozen == old, "local-enable v4 frozen predecessor changed: " + item["path"])
        if item["change"] == "added":
            require(item["baseline_sha256"] is None,
                    "added local-enable v4 path has a predecessor hash")
        else:
            require(item["baseline_sha256"] == digest(old),
                    "modified local-enable v4 predecessor hash changed")
        result[item["path"]] = item
    return result


def restore_source(root: Path, path: str, source: str) -> str:
    entries = load(root)
    if path not in entries:
        return source
    item = entries[path]
    old = item["edits"][0]["before"].encode()
    new = item["edits"][0]["after"].encode()
    raw = source.encode()
    if raw == old:
        return source
    require(raw == new, "unreviewed source outside local-enable v4 span: " + path)
    return old.decode()


def verify(root: Path) -> None:
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"],
                   cwd=root, check=True)
    entries = load(root)
    for path in (CONTRACT, *PATHS):
        file = root / path
        require(file.is_file() and not file.is_symlink() and not file.stat().st_mode & 0o111,
                "local-enable v4 source must be regular and non-executable: " + path)
        stage = subprocess.check_output(["git", "ls-files", "--stage", "--", path],
                                        cwd=root, text=True).split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == path,
                "local-enable v4 source is not uniquely tracked: " + path)
        raw = file.read_bytes()
        committed = subprocess.check_output(["git", "show", "HEAD:" + path], cwd=root)
        require(raw == committed, "local-enable v4 HEAD/worktree bytes differ: " + path)
        if path in entries:
            require(raw == entries[path]["edits"][0]["after"].encode(),
                    "local-enable v4 current bytes differ: " + path)
            require(restore_source(root, path, raw.decode()).encode() ==
                    entries[path]["edits"][0]["before"].encode(),
                    "local-enable v4 exact reversal failed: " + path)
    changed = set(subprocess.check_output(
        ["git", "diff", "--no-renames", "--name-only", BASE, "HEAD", "--", *PATHS[:2]],
        cwd=root, text=True).splitlines())
    require(changed == PRODUCTION,
            "local-enable v4 production delta differs from its exact two-file scope: " + repr(sorted(changed)))
    print("59i composite local-enable v4 exact successor review PASS", flush=True)


def self_test(root: Path) -> None:
    entries = load(root)
    rejected = 0
    for path, item in entries.items():
        new = item["edits"][0]["after"].encode()
        require(restore_source(root, path, new.decode()).encode() ==
                item["edits"][0]["before"].encode(), "positive v4 reversal failed")
        for index in (0, len(new) // 2, len(new) - 1):
            changed = new[:index] + bytes([new[index] ^ 1]) + new[index + 1:]
            try:
                restore_source(root, path, changed.decode(errors="replace"))
            except RuntimeError:
                rejected += 1
            else:
                raise RuntimeError("local-enable v4 accepted changed bytes: " + path)
    require(rejected == 3 * len(PATHS), "missing local-enable v4 mutation controls")
    print("59i local-enable v4 source mutations PASS: " + str(rejected), flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    verify(root)
    if args.self_test:
        self_test(root)


if __name__ == "__main__":
    main()
'''


def write_review(base: str) -> None:
    entries = [entry(base, path, index) for index, path in enumerate(REVIEWED, 1)]
    raw = (json.dumps({
        "schema_version": 1,
        "base": base,
        "offset_format": "utf8-bytes",
        "files": entries,
    }, indent=2) + "\n").encode()
    CONTRACT.parent.mkdir(parents=True, exist_ok=True)
    CONTRACT.write_bytes(raw)
    CHECKER.parent.mkdir(parents=True, exist_ok=True)
    CHECKER.write_text(CHECKER_TEMPLATE.replace("__BASE__", base)
                       .replace("__CONTRACT_SHA256__", digest(raw)))
    DOC.parent.mkdir(parents=True, exist_ok=True)
    DOC.write_text(f"""# Increment 59i — Composite register-local-enable successor v4

Status: implementation/review checkpoint only. Increment 59i remains unchecked,
draft and unmerged.

Exact predecessor: `{base}`.

The native-looking `Vec[Bundle].reduceBalancedTree` bridge may register each
scalar field while its enable reads an original scalar field from the same
composite value. Data remains bound to the corresponding field. Control binding
uses recursive leaf identity, scalar kind and symbolic width—not names or native
`Int` reconstruction. External controls, registered peer controls, cross-field
data movement, unequal register depth and unsupported predicates remain rejected.

This narrow successor review covers exactly the two production files and its
focused test. Historical 59i and current-target source trees retain their own
parent-bound audits. This document does not claim saturation, generated-child,
full nested-Vec, every-pair or final integration closure.
""")


def main() -> None:
    require(output("git", "status", "--porcelain") == "", "publisher requires a clean checkout")
    already_reviewed = CONTRACT.exists() or CHECKER.exists() or DOC.exists()
    require(not already_reviewed,
            "v4 successor review already exists; run its checker instead of regenerating it")
    base = prepare_implementation()
    write_review(base)
    print("59i local-enable v4 successor prepared from " + base, flush=True)


if __name__ == "__main__":
    main()
