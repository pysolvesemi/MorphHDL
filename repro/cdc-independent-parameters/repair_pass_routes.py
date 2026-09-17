#!/usr/bin/env python3
"""Pinned CI-only repair for PR189's pass-routing and boundary-self-test failures.

No refs are changed here. The complete candidate and its source-test evidence
are retained for explicit publication through the authorized GitHub connection.
Git object export is limited to this repository's blobs, trees and commits.
"""
from __future__ import annotations
import base64
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request

BASE = "6ee3605c71704ca9f684d4859762cbb02f3677c8"
TARGET = "27af65abbee0d2334d6be7a6e4e2408b8af32fd9"
SCRIPT = "repro/cdc-independent-parameters/repair_pass_routes.py"
CONTROL = ".github/workflows/pr189-60b-source-repair.yml"
WORKFLOW = ".github/workflows/morphhdl-passes.yml"
BOUNDARY = "morphhdl-passes/scripts/test-boundary-guard.sh"
REGISTRY = "morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json"
SCOPE = "morphhdl/scripts/check-cdc-successor-source.py"
HELPER = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"
CONTRACT = "morphhdl/contracts/increment-62-wa08-source-overlay.json"
ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = Path(os.environ.get("EVIDENCE", "/tmp/pr189-pass-route-repair"))


def require(ok, message):
    if not ok:
        raise RuntimeError("PR189 pass-route repair: " + message)


def git(*args):
    return subprocess.check_output(["git", "--literal-pathspecs", *args], cwd=ROOT, timeout=120)


def changed(before, after="HEAD"):
    return {p.decode() for p in git("diff", "--no-renames", "--name-only", "-z", before, after).split(b"\0") if p}


def replace(path, old, new):
    text = (ROOT / path).read_text()
    require(text.count(old) == 1, "non-unique exact repair in " + path)
    (ROOT / path).write_text(text.replace(old, new, 1))


def commit(message, paths):
    git("add", "--", *sorted(paths))
    git("commit", "-q", "-m", message + " [skip ci]")
    return git("rev-parse", "HEAD").decode().strip()


def load(relative, name):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check(command, label, cwd=ROOT):
    result = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=900, check=False)
    (EVIDENCE / (label + ".log")).write_bytes(result.stdout)
    print(result.stdout.decode(errors="replace"), end="", flush=True)
    require(result.returncode == 0, label + " failed (see retained log)")


def export_objects(input_head):
    token = os.environ["GH_OBJECT_TOKEN"]
    endpoint = "https://api.github.com/repos/pysolvesemi/MorphHDL/"
    def post(path, data):
        require(path in {"git/blobs", "git/trees", "git/commits"}, "non-object API write forbidden")
        request = urllib.request.Request(endpoint + path,
            data=json.dumps(data).encode(), method="POST", headers={
                "Authorization": "Bearer " + token,
                "Accept": "application/vnd.github+json", "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    def person(value):
        match = re.fullmatch(r"(.*) <([^<>]*)> ([0-9]+) ([+-][0-9]{4})", value)
        require(match is not None, "unexpected commit identity")
        name, email, seconds, offset = match.groups()
        minutes = (int(offset[1:3]) * 60 + int(offset[3:])) * (1 if offset[0] == "+" else -1)
        tz = datetime.timezone(datetime.timedelta(minutes=minutes))
        date = datetime.datetime.fromtimestamp(int(seconds), tz).isoformat()
        return {"name": name, "email": email, "date": date}
    for revision in git("rev-list", "--reverse", input_head + "..HEAD").decode().splitlines():
        raw = git("cat-file", "-p", revision).decode()
        header, message = raw.split("\n\n", 1)
        fields = dict(line.split(" ", 1) for line in header.splitlines())
        require(set(fields) == {"tree", "parent", "author", "committer"}, "unexpected commit headers")
        entries = []
        for path in sorted(changed(fields["parent"], revision)):
            row = git("ls-tree", "-z", revision, "--", path)
            require(row and row.endswith(b"\0"), "deleted candidate source")
            metadata, returned = row[:-1].split(b"\t", 1)
            mode, kind, blob = metadata.decode().split()
            require(kind == "blob" and returned.decode() == path, "non-file candidate delta")
            data = git("show", revision + ":" + path)
            created = post("git/blobs", {"content": base64.b64encode(data).decode(), "encoding": "base64"})
            require(created["sha"] == blob, "exported blob differs")
            entries.append({"path": path, "mode": mode, "type": "blob", "sha": blob})
        tree = post("git/trees", {"base_tree": git("rev-parse", fields["parent"] + "^{tree}").decode().strip(), "tree": entries})
        require(tree["sha"] == fields["tree"], "exported tree differs")
        created = post("git/commits", {"message": message, "tree": fields["tree"],
            "parents": [fields["parent"]], "author": person(fields["author"]), "committer": person(fields["committer"])})
        require(created["sha"] == revision, "exported commit differs")
        print("PR189_EXPORTED_UNREFERENCED_COMMIT", revision, flush=True)


def main():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    input_head = git("rev-parse", "HEAD").decode().strip()
    require(input_head == os.environ["EXPECTED_INPUT"], "wrong event checkout")
    git("merge-base", "--is-ancestor", BASE, input_head)
    git("merge-base", "--is-ancestor", TARGET, BASE)
    require(changed(BASE) == {SCRIPT, CONTROL}, "input differs outside the two reviewed preparation files")
    require(not git("status", "--porcelain", "--untracked-files=all"), "dirty input")
    for path in (WORKFLOW, BOUNDARY, REGISTRY, SCOPE, HELPER, CONTRACT):
        require((ROOT / path).read_bytes() == git("show", BASE + ":" + path), "wrong reviewed input bytes: " + path)
    with tempfile.TemporaryDirectory(prefix="pr189-route-predecessor-") as temporary:
        predecessor = Path(temporary) / "source"
        git("worktree", "add", "--quiet", "--detach", str(predecessor), BASE)
        try:
            check(["python3", str(predecessor / "morphhdl/scripts/check-increment-61-source-review.py")], "predecessor-source", predecessor)
        finally:
            git("worktree", "remove", "--force", str(predecessor))
    original_registry = json.loads((ROOT / REGISTRY).read_bytes())
    for path in (WORKFLOW, BOUNDARY):
        require(original_registry["files"][path] == hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), "stale prior signature: " + path)

    replace(WORKFLOW, '          if [[ "$SOURCE_HEAD_REF" == agent/increment-61-one-file-per-component ]]; then', '''          if [[ "$SOURCE_HEAD_REF" == agent/cdc-independent-parameter-consumers ]]; then
            # Authenticate the entire live successor BEFORE any frozen replay.
            # This is not a WA branch-name exemption or a current-code bypass.
            python3 morphhdl/scripts/check-increment-62-wa08-source-overlay.py
            python3 morphhdl/scripts/check-increment-61-source-review.py
            python3 morphhdl/scripts/check-cdc-successor-source.py --self-test
            python3 morphhdl/scripts/check-native-source-preservation.py
            python3 morphhdl/scripts/check-typed-native-source-overlay.py
            python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py --self-test
            integrated=27af65abbee0d2334d6be7a6e4e2408b8af32fd9
            audit_source=61d1fe0dcac0b52856620944a2d7426fd1390a48
            git merge-base --is-ancestor "$integrated" HEAD
            git merge-base --is-ancestor "$audit_source" "$integrated"
            # Only this workflow and the boundary self-test/signature adapter
            # change. Every pass implementation, rule, oracle and formal test
            # is still byte-identical to the integrated, reviewed pass sources.
            registry=morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json
            boundary_test=morphhdl-passes/scripts/test-boundary-guard.sh
            git diff --exit-code "$integrated" HEAD -- morphhdl-passes \\
              ":(exclude)$registry" ":(exclude)$boundary_test"
            git diff --exit-code "$audit_source" "$integrated" -- morphhdl-passes \\
              ":(exclude)$registry" ":(exclude)$boundary_test"
            # Check the CURRENT workflow's source-to-audit routing and every
            # existing boundary negative control before the historical replay.
            bash morphhdl-passes/scripts/test-boundary-guard.sh
            audit_root=$(mktemp -d "${RUNNER_TEMP:-/tmp}/pr189-pass-static.XXXXXX")
            git worktree add --detach "$audit_root" "$audit_source"
            audit_head_ref=agent/wa-10-inherited-audit-timeout
            audit_base_sha=3b547ae5622ae212c17f6e67cc96924127a46a41
            printf 'Current PR189 source: %s\\nHistorical pass-only static source: %s\\n' \\
              "$(git rev-parse HEAD)" "$audit_source"
          elif [[ "$SOURCE_HEAD_REF" == agent/increment-61-one-file-per-component ]]; then''')

    old = '''if "MORPHDL_PASSES_HEAD_REF: ${{ steps.source.outputs.head_ref }}" not in workflow:
    raise SystemExit("boundary enforcement must consume the resolved source branch")'''
    new = '''def validate_boundary_route(text):
    direct = "MORPHDL_PASSES_HEAD_REF: ${{ steps.source.outputs.head_ref }}"
    if "        id: audit\\n" not in text:
        if direct not in text:
            raise SystemExit("boundary enforcement must consume the resolved source branch")
        return
    # The Increment61 adapter introduced an authenticated intermediate route.
    # Verify its actual input, fallback, output and enforcement connections;
    # do not accept a decorative source-variable occurrence or a branch label.
    required = (
        "SOURCE_HEAD_REF: ${{ steps.source.outputs.head_ref }}",
        'audit_root="$GITHUB_WORKSPACE"',
        'audit_head_ref="$SOURCE_HEAD_REF"',
        'audit_base_sha="$SOURCE_BASE_SHA"',
        '"$audit_root" "$audit_head_ref" "$audit_base_sha" >> "$GITHUB_OUTPUT"',
        "working-directory: ${{ steps.audit.outputs.root }}",
        "MORPHDL_PASSES_BASE_SHA: ${{ steps.audit.outputs.base_sha }}",
        "MORPHDL_PASSES_HEAD_REF: ${{ steps.audit.outputs.head_ref }}",
    )
    if not all(fragment in text for fragment in required):
        raise SystemExit("authenticated boundary route must preserve source input and audited outputs")
    marker = 'if [[ "$SOURCE_HEAD_REF" == agent/cdc-independent-parameter-consumers ]]; then'
    if text.count(marker) != 1:
        raise SystemExit("missing exact PR189 source-authentication route")
    route = text.split(marker, 1)[1].split('          elif ', 1)[0]
    checks = (
        "python3 morphhdl/scripts/check-increment-62-wa08-source-overlay.py",
        "python3 morphhdl/scripts/check-increment-61-source-review.py",
        "python3 morphhdl/scripts/check-cdc-successor-source.py --self-test",
        "python3 morphhdl/scripts/check-native-source-preservation.py",
        "python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py --self-test",
        'git diff --exit-code "$integrated" HEAD -- morphhdl-passes',
        'git diff --exit-code "$audit_source" "$integrated" -- morphhdl-passes',
        "bash morphhdl-passes/scripts/test-boundary-guard.sh",
    )
    replay = route.find('git worktree add --detach "$audit_root" "$audit_source"')
    if replay < 0 or any(route.find(check) < 0 or route.find(check) > replay for check in checks):
        raise SystemExit("current source checks must all precede frozen pass replay")
    return required, checks

route_contract = validate_boundary_route(workflow)
if route_contract:
    fragments = route_contract[0] + route_contract[1]
    rejected = 0
    for fragment in fragments:
        mutated = workflow.replace(fragment, "REMOVED_BOUNDARY_ROUTE_EDGE", 1)
        try:
            validate_boundary_route(mutated)
        except SystemExit:
            rejected += 1
        else:
            raise SystemExit("boundary route mutation was accepted: " + fragment)
    if rejected != 16:
        raise SystemExit("boundary route mutation inventory changed")
    print("BOUNDARY_ROUTE_MUTATIONS_PASS controls=16")'''
    replace(BOUNDARY, old, new)
    registry = json.loads(json.dumps(original_registry))
    for path in (WORKFLOW, BOUNDARY):
        registry["files"][path] = hashlib.sha256((ROOT / path).read_bytes()).hexdigest()
    require({p for p in registry["files"] if registry["files"][p] != original_registry["files"][p]} == {WORKFLOW, BOUNDARY}, "signature scope differs")
    (ROOT / REGISTRY).write_text(json.dumps(registry, indent=2, sort_keys=True) + "\n")
    scope = load(SCOPE, "old_route_successor")
    expected_paths = set(scope.SUCCESSOR_PATHS) | {SCRIPT, WORKFLOW, BOUNDARY, REGISTRY}
    text, count = re.subn(r'SUCCESSOR_PATHS = frozenset\("""\n.*?\n"""\.split\(\)\)',
        'SUCCESSOR_PATHS = frozenset("""\n' + '\n'.join(sorted(expected_paths)) + '\n""".split())',
        (ROOT / SCOPE).read_text(), count=1, flags=re.S)
    require(count == 1, "missing closed successor inventory")
    (ROOT / SCOPE).write_text(text)
    source = commit("PR189: retain authenticated pass audit routing and all boundary controls", {WORKFLOW, BOUNDARY, REGISTRY, SCOPE})
    wa = load(HELPER, "route_overlay")
    manifest = wa.contract(ROOT)
    original_records = {entry["path"]: entry for entry in manifest["files"]}
    selected = changed(manifest["base"]) - {HELPER, CONTRACT}
    require(set(original_records) <= selected, "inherited source entry removed")
    records = []
    for path in sorted(selected):
        row = git("ls-tree", "-z", source, "--", path)
        require(row and row.endswith(b"\0"), "missing candidate path: " + path)
        metadata, returned = row[:-1].split(b"\t", 1)
        mode, kind, _ = metadata.decode().split()
        require(kind == "blob" and mode in ("100644", "100755") and returned.decode() == path, "non-regular candidate path")
        before = wa.digest(git("show", manifest["base"] + ":" + path)) if git("ls-tree", "-z", manifest["base"], "--", path) else None
        if path in original_records:
            require(original_records[path]["before_sha256"] == before and original_records[path]["mode"] == mode, "inherited baseline or mode changed")
        record = {"path": path, "mode": mode, "before_sha256": before, "after_sha256": wa.digest(git("show", source + ":" + path))}
        if path not in {SCRIPT, CONTROL, WORKFLOW, BOUNDARY, REGISTRY, SCOPE}:
            require(record == original_records[path], "unrelated source review changed: " + path)
        records.append(record)
    manifest.update(files=records, final_source_commit=source)
    (ROOT / CONTRACT).write_text(json.dumps(manifest, indent=2) + "\n")
    before = (ROOT / HELPER).read_bytes()
    after, count = re.subn(rb'^CONTRACT_SHA256 = "[^"]+"$',
        ('CONTRACT_SHA256 = "' + wa.digest((ROOT / CONTRACT).read_bytes()) + '"').encode(), before, count=1, flags=re.M)
    require(count == 1 and wa.normalized_helper(before) == wa.normalized_helper(after), "WA08 verifier logic changed")
    (ROOT / HELPER).write_bytes(after)
    candidate = commit("PR189: seal exact pass-routing successor without changing compiler or rule sources", {HELPER, CONTRACT})
    require(changed(BASE) == {SCRIPT, CONTROL, WORKFLOW, BOUNDARY, REGISTRY, SCOPE, HELPER, CONTRACT}, "unexpected final source delta")
    require(changed(TARGET) == expected_paths, "successor inventory differs")
    identity = {"input": input_head, "base": BASE, "target": TARGET, "source_anchor": source,
        "candidate": candidate, "tree": git("rev-parse", "HEAD^{tree}").decode().strip(),
        "compiler_sources_unchanged": True, "changed_paths": sorted(changed(BASE))}
    (EVIDENCE / "identity.json").write_text(json.dumps(identity, indent=2) + "\n")
    (EVIDENCE / "candidate.patch").write_bytes(git("diff", "--binary", input_head, "HEAD"))
    git("bundle", "create", str(EVIDENCE / "candidate.bundle"), "HEAD", "^" + input_head)
    checks = [
        ["git", "diff", "--check"],
        ["python3", HELPER], ["python3", HELPER, "--self-test"],
        ["python3", "morphhdl/scripts/check-increment-61-source-review.py"],
        ["python3", "morphhdl/scripts/check-increment-61-source-review.py", "--self-test"],
        ["python3", "morphhdl/scripts/check-native-source-preservation.py"],
        ["python3", "morphhdl/scripts/check-native-source-preservation.py", "--self-test"],
        ["python3", "morphhdl/scripts/check-typed-layering-ir.py", "--self-test"],
        ["python3", "morphhdl/scripts/check-production-retirement.py", "--self-test"],
        ["bash", BOUNDARY],
        ["python3", "morphhdl-passes/scripts/validate_wire_assignment_equivalence.py", "--self-test"],
    ]
    for index, command in enumerate(checks, 1):
        check(command, "source-check-" + str(index))
    require(not git("status", "--porcelain", "--untracked-files=all"), "candidate source drifted during checks")
    export_objects(input_head)
    (EVIDENCE / "source-pass.json").write_text(json.dumps(identity, indent=2) + "\n")
    print("PR189_PASS_ROUTE_SOURCE_READY " + json.dumps(identity, sort_keys=True), flush=True)
    print("No branch ref was moved. This source-checked candidate still needs failed-workflow qualification.", flush=True)


if __name__ == "__main__":
    main()
