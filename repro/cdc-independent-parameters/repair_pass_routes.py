#!/usr/bin/env python3
"""Bind the already CI-checked pass-route files to their published source commit.

The workflow-capable connection published the four exact blobs from passing
run 35204063331. This final CI stage changes only the two WA08 review records;
its Git-object export never changes a workflow file or any repository ref.
All current-source and boundary controls are rerun on the final candidate.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

BASE = "6ee3605c71704ca9f684d4859762cbb02f3677c8"
TARGET = "27af65abbee0d2334d6be7a6e4e2408b8af32fd9"
PUBLISHED = "911bb6cb4845ae9ca9e2058d694ff0e4a0f8d282"
PUBLISHED_TREE = "6a601d2b6fe64d79a4346d95f3636468c4610289"
PIN = "e2b952601fbb231fff82b616c19b7a675c850807"
SCRIPT = "repro/cdc-independent-parameters/repair_pass_routes.py"
PIN_BLOB = "47aecfd91c4bef37b5a9dbd54f6ced1c64b3adb0"
ROOT = Path(__file__).resolve().parents[2]
EXPECTED = {
    ".github/workflows/morphhdl-passes.yml": "afbc539336316764b4e958c87c76a2946970f963",
    "morphhdl-passes/scripts/test-boundary-guard.sh": "e9eea5b6a1f881bbd208cbf408f1bd328a5201b5",
    "morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json": "791c6d77f040aecb269d635506d508eddd90450c",
    "morphhdl/scripts/check-cdc-successor-source.py": "41d841254f9daeb502242df5d5554d938be5e932",
}
CONTROL = ".github/workflows/pr189-60b-source-repair.yml"
HELPER = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"
CONTRACT = "morphhdl/contracts/increment-62-wa08-source-overlay.json"


def git(*args):
    return subprocess.check_output(["git", "--literal-pathspecs", *args], cwd=ROOT, timeout=120)


def main():
    raw = git("show", PIN + ":" + SCRIPT)
    if hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() != PIN_BLOB:
        raise RuntimeError("Pinned source-validation utilities differ")
    ns = {"__name__": "reviewed_source_utilities", "__file__": str(ROOT / SCRIPT)}
    exec(compile(raw, PIN + ":" + SCRIPT, "exec"), ns)
    require, changed, load, check = (ns[k] for k in ("require", "changed", "load", "check"))
    evidence = ns["EVIDENCE"]
    evidence.mkdir(parents=True, exist_ok=True)
    head = git("rev-parse", "HEAD").decode().strip()
    require(head == os.environ["EXPECTED_INPUT"], "wrong event checkout")
    require(git("show", "-s", "--format=%P", "HEAD").decode().strip() == PUBLISHED, "wrong metadata preparation parent")
    require(git("rev-parse", PUBLISHED + "^{tree}").decode().strip() == PUBLISHED_TREE, "published source tree differs from CI handoff")
    require(changed(PUBLISHED) == {SCRIPT}, "unreviewed change after source publication")
    require(changed(BASE) == set(EXPECTED) | {SCRIPT, CONTROL}, "source delta differs from the closed reviewed paths")
    git("merge-base", "--is-ancestor", TARGET, BASE)
    require(not git("status", "--porcelain", "--untracked-files=all"), "dirty source input")
    for path, blob in EXPECTED.items():
        require(git("rev-parse", "HEAD:" + path).decode().strip() == blob, "published file differs from its passing CI source: " + path)
    require(git("rev-parse", "HEAD:" + CONTROL).decode().strip() == "173561be766096014bf9d953120d45f3f8f9193c", "reviewed qualification controller changed")
    for path in (HELPER, CONTRACT):
        require((ROOT / path).read_bytes() == git("show", BASE + ":" + path), "unreviewed prior metadata change")
    wa = load(HELPER, "published_route_overlay")
    value = wa.contract(ROOT)
    old_records = {row["path"]: row for row in value["files"]}
    selected = changed(value["base"]) - {HELPER, CONTRACT}
    require(set(old_records) <= selected, "inherited reviewed path removed")
    allowed = set(EXPECTED) | {SCRIPT, CONTROL}
    records = []
    for path in sorted(selected):
        row = git("ls-tree", "-z", head, "--", path)
        require(row and row.endswith(b"\0"), "reviewed source is missing")
        metadata, returned = row[:-1].split(b"\t", 1)
        mode, kind, _ = metadata.decode().split()
        require(kind == "blob" and mode in ("100644", "100755") and returned.decode() == path, "unexpected reviewed entry")
        previous = wa.digest(git("show", value["base"] + ":" + path)) if git("ls-tree", "-z", value["base"], "--", path) else None
        record = {"path": path, "mode": mode, "before_sha256": previous,
                  "after_sha256": wa.digest(git("show", head + ":" + path))}
        if path in old_records:
            require(old_records[path]["before_sha256"] == previous and old_records[path]["mode"] == mode, "inherited baseline or mode changed")
        if path not in allowed:
            require(record == old_records[path], "unrelated reviewed source changed: " + path)
        records.append(record)
    value.update(files=records, final_source_commit=head)
    (ROOT / CONTRACT).write_text(json.dumps(value, indent=2) + "\n")
    before = (ROOT / HELPER).read_bytes()
    after, count = re.subn(rb'^CONTRACT_SHA256 = "[^"]+"$',
        ('CONTRACT_SHA256 = "' + wa.digest((ROOT / CONTRACT).read_bytes()) + '"').encode(), before, count=1, flags=re.M)
    require(count == 1 and wa.normalized_helper(before) == wa.normalized_helper(after), "WA08 validator algorithm changed")
    (ROOT / HELPER).write_bytes(after)
    candidate = ns["commit"]("PR189: authenticate the exact published pass-routing repair", {HELPER, CONTRACT})
    require(changed(head) == {HELPER, CONTRACT}, "metadata candidate changed non-metadata source")
    require(changed(BASE) == allowed | {HELPER, CONTRACT}, "final source scope differs")
    checks = [
        ["git", "diff", "--check"],
        ["python3", HELPER], ["python3", HELPER, "--self-test"],
        ["python3", "morphhdl/scripts/check-increment-61-source-review.py"],
        ["python3", "morphhdl/scripts/check-increment-61-source-review.py", "--self-test"],
        ["python3", "morphhdl/scripts/check-native-source-preservation.py"],
        ["python3", "morphhdl/scripts/check-native-source-preservation.py", "--self-test"],
        ["python3", "morphhdl/scripts/check-typed-layering-ir.py", "--self-test"],
        ["python3", "morphhdl/scripts/check-production-retirement.py", "--self-test"],
        ["bash", "morphhdl-passes/scripts/test-boundary-guard.sh"],
        ["python3", "morphhdl-passes/scripts/validate_wire_assignment_equivalence.py", "--self-test"],
    ]
    identity = {"input": head, "base": BASE, "target": TARGET, "source_anchor": head,
                "candidate": candidate, "tree": git("rev-parse", "HEAD^{tree}").decode().strip(),
                "compiler_sources_unchanged": True, "changed_paths": sorted(changed(BASE)),
                "checked_source_run": 35204063331, "published_source": PUBLISHED}
    (evidence / "identity.json").write_text(json.dumps(identity, indent=2) + "\n")
    (evidence / "candidate.patch").write_bytes(git("diff", "--binary", head, "HEAD"))
    git("bundle", "create", str(evidence / "candidate.bundle"), "HEAD", "^" + head)
    for index, command in enumerate(checks, 1):
        check(command, "source-check-" + str(index))
    require(not git("status", "--porcelain", "--untracked-files=all"), "tested source drifted")
    # The sole exported commit changes only the two metadata paths. Workflow
    # sources are already present in the authorized remote parent; no token
    # permission expansion, workflow-tree creation or ref update is attempted.
    ns["export_objects"](head)
    (evidence / "source-pass.json").write_text(json.dumps(identity, indent=2) + "\n")
    print("PR189_FINAL_ROUTE_SOURCE_PASS " + json.dumps(identity, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
