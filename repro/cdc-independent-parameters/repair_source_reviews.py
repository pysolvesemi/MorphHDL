#!/usr/bin/env python3
"""One-shot, pinned PR189 source-review repair; never part of normal validation.

Only the three-file transport checkpoint directly following INPUT is admitted.
No production source is edited here. The existing native manifest generator is
used with an explicit two-file review update; the WA08 verifier is unchanged.
Run the resulting candidate's guards before publishing any candidate commit.
"""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
INPUT = "0a495945f705fa905588737fd8bbf9c02f690844"
TARGET = "27af65abbee0d2334d6be7a6e4e2408b8af32fd9"
WORKFLOW = ".github/workflows/cdc-independent-parameter-consumers.yml"
SELF = "repro/cdc-independent-parameters/repair_source_reviews.py"
SUCCESSOR = "morphhdl/scripts/check-cdc-successor-source.py"
NATIVE_CHECK = "morphhdl/scripts/check-native-source-preservation.py"
POLICY = "morphhdl/contracts/increment-55-native-change-review.json"
NATIVE = "morphhdl/contracts/native-source-preservation.json"
WA_CHECK = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"
WA_CONTRACT = "morphhdl/contracts/increment-62-wa08-source-overlay.json"
INC61 = "morphhdl/scripts/check-increment-61-source-review.py"
NEW_NATIVE = "core/src/main/scala/spinal/core/ElaborationPublicationValue.scala"
BLACKBOX = "core/src/main/scala/spinal/core/internals/ParameterizedBlackBoxGeneric.scala"


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError("PR189 pinned source repair: " + detail)


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", "--literal-pathspecs", *args], cwd=ROOT, timeout=120)


def load(path: str, name: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    require(spec is not None and spec.loader is not None, "cannot load " + path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def paths(base: str, head: str = "HEAD") -> set[str]:
    return {p.decode() for p in git("diff", "--no-renames", "--name-only", "-z", base, head).split(b"\0") if p}


def write_json(path: str, value: dict) -> None:
    (ROOT / path).write_text(json.dumps(value, indent=2) + "\n")


def commit(message: str, files: list[str]) -> str:
    git("add", "--", *files)
    git("commit", "-q", "-m", message + " [skip ci]")
    return git("rev-parse", "HEAD").decode().strip()


def main() -> None:
    require(git("rev-parse", "--show-toplevel").decode().strip() == str(ROOT), "wrong repository root")
    head = git("rev-parse", "HEAD").decode().strip()
    require(git("show", "-s", "--format=%P", "HEAD").decode().strip() == INPUT,
            "this one-shot repair only accepts a direct transport child of " + INPUT)
    require(paths(INPUT) == {WORKFLOW, SELF, SUCCESSOR}, "unreviewed transport changes")
    require(not git("status", "--porcelain", "--untracked-files=all"), "dirty starting checkout")
    git("merge-base", "--is-ancestor", TARGET, "HEAD")
    native = load(NATIVE_CHECK, "pr189_native_manifest_generator")
    wa08 = load(WA_CHECK, "pr189_frozen_wa08")
    old_overlay = json.loads((ROOT / WA_CONTRACT).read_bytes())
    old_native = json.loads((ROOT / NATIVE).read_bytes())
    require(wa08.digest(wa08.normalized_helper((ROOT / WA_CHECK).read_bytes())) ==
            old_overlay["helper_normalized_sha256"], "predecessor WA08 algorithm changed")
    native_delta = {p for p in paths(TARGET) if native.under_root(p, native.EXPECTED_SOURCE_ROOTS)}
    require(native_delta == {NEW_NATIVE, BLACKBOX}, "unexpected native compiler delta")
    # The existing PR189 production bytes are pinned by INPUT; this maintenance
    # step cannot approve newly supplied or uncommitted compiler changes.
    for path in native_delta:
        require((ROOT / path).read_bytes() == git("show", INPUT + ":" + path),
                "unpinned compiler bytes: " + path)
    require((ROOT / NATIVE_CHECK).read_bytes() == git("show", TARGET + ":" + NATIVE_CHECK),
            "native audit algorithm changed")
    require((ROOT / INC61).read_bytes() == git("show", TARGET + ":" + INC61),
            "unexpected existing Increment61 adapter")

    policy = json.loads((ROOT / POLICY).read_bytes())
    require(not any(e["path"] == NEW_NATIVE for e in policy["files"]), "support file already enrolled")
    policy["files"].append({
        "path": NEW_NATIVE, "baseline_path": None, "change": "added",
        "classification": "typed-support-file",
        "introduced_by": ["PR189: authenticated independent-parameter publication consumers"],
        "reason": "Authenticate retained expression identity before and after consumer-specific projection; do not grant finite structural elaboration authority.",
        "edits": []
    })
    blackbox = next(e for e in policy["files"] if e["path"] == BLACKBOX)
    require(blackbox["change"] == "added" and blackbox["edits"] == [],
            "BlackBox support-file review is not the expected added-file form")
    blackbox["introduced_by"].append("PR189: retain authenticated independent parameter roots in BlackBox integer generics")
    blackbox["reason"] += " PR189 routes integer projection through ElaborationPublicationValue while retaining the existing final-owner and schema checks."
    policy["files"].sort(key=lambda entry: entry["path"])
    write_json(POLICY, policy)
    generated = native.generate_manifest_value(ROOT, ROOT / POLICY)
    old_entries = {e["path"]: e for e in old_native["entries"]}
    new_entries = {e["path"]: e for e in generated["entries"]}
    require(set(new_entries) == set(old_entries) | {NEW_NATIVE}, "native inventory grew beyond the one reviewed support file")
    for path, entry in old_entries.items():
        if path != BLACKBOX:
            require(new_entries[path] == entry, "unrelated native entry changed: " + path)
    require(generated["baseline"] == old_native["baseline"], "upstream baseline changed")
    for before, after in zip(old_native["source_roots"], generated["source_roots"]):
        require(before["path"] == after["path"] and before["baseline_tree"] == after["baseline_tree"],
                "native root baseline changed")
        if before["path"] != "core/src/main":
            require(before == after, "unrelated native root changed")
    write_json(NATIVE, generated)

    original = (ROOT / INC61).read_text()
    def replace_once(text: str, before: str, after: str) -> str:
        require(text.count(before) == 1, "ambiguous Increment61 insertion")
        return text.replace(before, after, 1)
    loader = '''def _cdc_successor(root: Path):
    # The optional successor must authenticate every current byte before the
    # unchanged historical Increment61 check is allowed to run.
    import importlib.util
    path = root / "morphhdl/scripts/check-cdc-successor-source.py"
    if not path.exists():
        return None
    spec = importlib.util.spec_from_file_location("inc61_cdc_successor", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("unable to load the PR189 successor verifier")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


'''
    changed = replace_once(original, "def verify(root: Path = ROOT) -> None:\n",
        loader + "def verify(root: Path = ROOT) -> None:\n" +
        "    successor = _cdc_successor(root)\n" +
        "    if successor is not None:\n" +
        "        successor.verify(root)\n" +
        "        successor.verify_predecessor(root)\n" +
        "        return\n")
    changed = replace_once(changed, "def self_test() -> None:\n",
        "def self_test() -> None:\n" +
        "    successor = _cdc_successor(ROOT)\n" +
        "    if successor is not None:\n" +
        "        successor.verify(ROOT)\n" +
        "        successor.verify_predecessor(ROOT, self_test=True)\n" +
        "        successor.self_test(ROOT)\n" +
        "        return\n")
    (ROOT / INC61).write_text(changed)
    source = commit("PR189: enroll reviewed native consumers and compose Increment61 successor audit", [POLICY, NATIVE, INC61])
    scope = load(SUCCESSOR, "pr189_reviewed_successor_inventory")
    require(paths(TARGET) == scope.SUCCESSOR_PATHS, "prepared successor inventory differs")

    records = []
    old_records = {entry["path"]: entry for entry in old_overlay["files"]}
    base = old_overlay["base"]
    selected = paths(base) - {WA_CHECK, WA_CONTRACT}
    require(set(old_records) <= selected, "an inherited reviewed path was unexpectedly removed or reverted")
    for path in sorted(selected):
        row = git("ls-tree", "-z", source, "--", path)
        require(row and b"\0" in row, "deleted reviewed paths need an explicit deletion review: " + path)
        metadata, returned_path = row[:-1].split(b"\t", 1)
        mode, kind, blob = metadata.decode().split()
        require(kind == "blob" and mode in ("100644", "100755") and returned_path.decode() == path,
                "unsupported reviewed file: " + path)
        raw = git("show", source + ":" + path)
        previous_row = git("ls-tree", "-z", base, "--", path)
        previous = git("show", base + ":" + path) if previous_row else None
        before_hash = wa08.digest(previous) if previous is not None else None
        if path in old_records:
            require(old_records[path]["before_sha256"] == before_hash and old_records[path]["mode"] == mode,
                    "inherited baseline hash or mode changed: " + path)
        records.append({"path": path, "mode": mode, "before_sha256": before_hash,
                        "after_sha256": wa08.digest(raw)})
    value = {"base": base, "files": records, "final_source_commit": source,
             "helper_normalized_sha256": old_overlay["helper_normalized_sha256"], "schema_version": 1}
    write_json(WA_CONTRACT, value)
    helper = (ROOT / WA_CHECK).read_bytes()
    digest = wa08.digest((ROOT / WA_CONTRACT).read_bytes())
    helper, count = re.subn(rb'^CONTRACT_SHA256 = "[^"]+"$',
                         ('CONTRACT_SHA256 = "' + digest + '"').encode(), helper, count=1, flags=re.M)
    require(count == 1, "missing unique WA08 digest constant")
    (ROOT / WA_CHECK).write_bytes(helper)
    require(wa08.digest(wa08.normalized_helper(helper)) == old_overlay["helper_normalized_sha256"],
            "WA08 verifier logic was modified")
    final = commit("PR189: seal the reviewed consumer and Increment61 source union", [WA_CONTRACT, WA_CHECK])
    require(paths(TARGET) == scope.SUCCESSOR_PATHS, "sealed successor inventory differs")
    print("PR189_SOURCE_REPAIR_CANDIDATE " + json.dumps({
        "input": head, "source_anchor": source, "candidate": final,
        "tree": git("rev-parse", "HEAD^{tree}").decode().strip(),
        "reviewed_files": len(records), "successor_paths": len(scope.SUCCESSOR_PATHS)
    }, sort_keys=True))
    print("Candidate is NOT qualified or published by this script; run every targeted guard next.")


if __name__ == "__main__":
    main()
