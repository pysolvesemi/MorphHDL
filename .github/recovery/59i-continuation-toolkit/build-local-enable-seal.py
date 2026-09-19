#!/usr/bin/env python3
"""Prepare the reviewed schema-4 manifest for one exact committed source.

Default mode only validates and prints a summary. --stage explicitly writes and
stages exactly the production manifest and helper hash slot. Never commits.

The reviewer supplies --review-reasons JSON: {"path": "concrete review reason"}
for every source path changed since the published certificate's source commit.
No placeholder, repair directory, generated result, or test success grants
review authority. Use only after the final source delta has been reviewed.
"""
from __future__ import annotations

import argparse
import copy
import difflib
import hashlib
import json
import re
import subprocess
import sys
import types
from pathlib import Path

EXPECTED_HELPER = "bf05c8442278b46cbb68a45a72ba051c7501c4eb6a3c1479124249ddeaa99f8f"
EXPECTED_PARENT = "90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6"
EXPECTED_PARENT_SOURCE = "1ed8930326497aa43a98c2491468d4cb75e59f3a"
EXPECTED_PARENT_MANIFEST = "99dd143a0cc54898051e21adb58d311671af97642c6a77e52554f5d86b85125b"
EXPECTED_BASE = "954d9b2763b064dba60af71ad8fa509a9d7cada8"
HELPER = "morphhdl/scripts/check-increment-59i-production-successor.py"
CONTRACT = "morphhdl/contracts/increment-59i-production-successor.json"
UNSEALED_SLOT = b'CONTRACT_SHA256 = "UNSEALED"\n'


def require(condition, message):
    if not condition:
        raise RuntimeError("local-enable seal preparation: " + message)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def git(root, *arguments):
    result = subprocess.run(["git", "--literal-pathspecs", *arguments], cwd=root,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120, check=False)
    require(result.returncode == 0, "git " + " ".join(arguments) + ": " +
        result.stderr.decode(errors="replace"))
    return result.stdout


def encoded(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def load_helper(root):
    filename = root / HELPER
    require(filename.is_file() and not filename.is_symlink(), "missing or linked source helper")
    raw = filename.read_bytes()
    pattern = rb'^CONTRACT_SHA256 = "[^"\n]+"$'
    require(len(re.findall(pattern, raw, re.M)) == 1 and raw.count(UNSEALED_SLOT) == 1,
        "source must have exactly one UNSEALED helper slot")
    normalized = re.sub(pattern, b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, flags=re.M)
    require(digest(normalized) == EXPECTED_HELPER, "source helper differs from reviewed schema-4 implementation")
    module = types.ModuleType("reviewed_local_enable_seal_preparation")
    module.__file__ = str(filename)
    exec(compile(raw, str(filename), "exec"), module.__dict__)
    require(module.BASE == EXPECTED_BASE and module.LOCAL_ENABLE_PARENT == EXPECTED_PARENT and
        module.LOCAL_ENABLE_PARENT_SOURCE == EXPECTED_PARENT_SOURCE and
        module.LOCAL_ENABLE_PARENT_MANIFEST == EXPECTED_PARENT_MANIFEST,
        "published certificate anchors differ")
    return module, raw


def line_spans(path, before, after, reason, domain):
    # Whole-line spans retain all exact unchanged gaps and UTF-8 byte offsets;
    # unlike a whole-file replacement, this exposes the actual review delta.
    old_lines = before.decode("utf-8").splitlines(keepends=True)
    new_lines = after.decode("utf-8").splitlines(keepends=True)
    old_offsets, new_offsets = [0], [0]
    for line in old_lines:
        old_offsets.append(old_offsets[-1] + len(line.encode()))
    for line in new_lines:
        new_offsets.append(new_offsets[-1] + len(line.encode()))
    result = []
    for operation, a, b, c, d in difflib.SequenceMatcher(
            a=old_lines, b=new_lines, autojunk=False).get_opcodes():
        if operation != "equal":
            result.append({"id": "local-enable-" + domain + ":" + path + ":" + str(len(result) + 1),
                "reason": reason,
                "before_start": old_offsets[a], "before_end": old_offsets[b],
                "after_start": new_offsets[c], "after_end": new_offsets[d],
                "before": "".join(old_lines[a:b]), "after": "".join(new_lines[c:d])})
    return result


def identity(helper, root, commit, path, inventory):
    entry = inventory.get(path)
    require(entry is None or entry[0] in ("100644", "100755"),
        "reviewed source delta cannot introduce/project a gitlink: " + path)
    raw = helper.frozen(root, commit, path)
    return (None if entry is None else entry[0], None if raw is None else digest(raw), raw)


def records(helper, root, before_commit, source, paths, previous, reasons, domain):
    before_tree, after_tree = helper.tree(root, before_commit), helper.tree(root, source)
    prior = {entry["path"]: entry for entry in previous}
    result, reused, rebuilt = [], [], []
    for path in sorted(paths):
        before_mode, before_sha, before = identity(helper, root, before_commit, path, before_tree)
        after_mode, after_sha, after = identity(helper, root, source, path, after_tree)
        old = prior.get(path)
        exact_identity = {"path": path, "before_mode": before_mode, "before_sha256": before_sha,
            "after_mode": after_mode, "after_sha256": after_sha}
        if old is not None and all(old[key] == value for key, value in exact_identity.items()):
            entry = copy.deepcopy(old)
            reused.append(path)
            # Canonical JSON is used by both the published and new manifest;
            # unchanged record bytes, ids, and rationale therefore stay exact.
            require(encoded(entry) == encoded(old), "historical record changed: " + path)
        else:
            require(path in reasons, "missing independent delta review reason: " + path)
            rationale = reasons[path]
            entry = dict(exact_identity,
                reason=(old["reason"] + " " if old else "") + "Local-enable continuation: " + rationale,
                edits=line_spans(path, before or b"", after or b"", rationale, domain))
            rebuilt.append(path)
        helper.restore_reviewed(entry, before or b"", after or b"")
        result.append(entry)
    return result, reused, rebuilt


def prepare(root, source, reasons_path):
    require(re.fullmatch(r"[0-9a-f]{40}", source) is not None, "--source must be an exact full commit SHA")
    require(git(root, "rev-parse", "HEAD").decode().strip() == source,
        "checkout HEAD is not the requested immutable source")
    helper, raw_helper = load_helper(root)
    require(helper.revision(root, source) == source, "source commit is not exact")
    source_tree = helper.tree(root, source)
    # Includes tracked/index/worktree equality, initialized gitlinks, untracked
    # files, ignored source files, executable cache files and symlink checks.
    helper.verify_checkout(root, source_tree)
    require(helper.regular(root, HELPER) == helper.frozen(root, source, HELPER),
        "source helper is not committed")
    previous_raw = helper.frozen(root, EXPECTED_PARENT, CONTRACT)
    require(previous_raw is not None and digest(previous_raw) == EXPECTED_PARENT_MANIFEST,
        "published prior manifest changed")
    require(helper.regular(root, CONTRACT) == previous_raw and
        helper.frozen(root, source, CONTRACT) == previous_raw,
        "unsealed source must retain exact published predecessor manifest")
    previous = json.loads(previous_raw)
    require(encoded(previous) == previous_raw, "published manifest is not canonical JSON")
    require(previous["source_commit"] == EXPECTED_PARENT_SOURCE,
        "published predecessor source changed")
    continuation_paths = helper.changed(root, EXPECTED_PARENT_SOURCE, source) - {CONTRACT}
    reasons = json.loads(reasons_path.read_bytes())
    require(isinstance(reasons, dict), "review reasons must be a JSON object")
    require(set(reasons) == continuation_paths,
        "review reasons must cover exactly the continuation delta; missing=" +
        repr(sorted(continuation_paths - set(reasons))) + "; extra=" +
        repr(sorted(set(reasons) - continuation_paths)))
    for path, reason in reasons.items():
        require(helper.valid_path(path) and isinstance(reason, str) and bool(reason.strip()) and
            not re.search(r"\b(?:TODO|TBD|PLACEHOLDER)\b", reason, re.I),
            "missing or placeholder review rationale: " + str(path))
    cumulative_paths = helper.changed(root, EXPECTED_BASE, source) - {CONTRACT}
    require(helper.HELPER in cumulative_paths and helper.TEST in cumulative_paths,
        "cumulative source inventory lacks original mandatory helper/test paths")
    files, reused, rebuilt = records(helper, root, EXPECTED_BASE, source,
        cumulative_paths, previous["files"], reasons, "source")
    target_commit, common, _ = helper.integration_parameters(4)
    previous_target = previous["target_integration"]
    require(previous_target["target_commit"] == target_commit and previous_target["common_base"] == common,
        "published target integration anchors changed")
    target_paths = helper.changed(root, common, target_commit)
    target_files, target_reused, target_rebuilt = records(helper, root, target_commit, source,
        target_paths, previous_target["files"], reasons, "target")
    value = {"schema_version": 4, "predecessor": EXPECTED_BASE,
        "predecessor_tree": git(root, "rev-parse", EXPECTED_BASE + "^{tree}").decode().strip(),
        "source_commit": source, "source_tree": git(root, "rev-parse", source + "^{tree}").decode().strip(),
        "helper_normalized_sha256": EXPECTED_HELPER, "files": files,
        "previous_seal": helper.previous_certificate(4),
        "development_checkpoint": helper.development_checkpoint(),
        "target_integration": dict(previous_target, files=target_files)}
    helper.validate_contract(value)
    helper.verify_development_history(root, value)
    # These run the original frozen 90b predecessor chain and original target
    # checker from isolated Git worktrees. Current source is never injected.
    helper.verify_previous_certificate(root, value)
    helper.verify_target_integration(root, value)
    helper.completion_tree(root, value, source_tree, source_tree, source)
    candidate_raw = encoded(value)
    candidate_hash = digest(candidate_raw)
    sealed_helper = raw_helper.replace(UNSEALED_SLOT,
        b'CONTRACT_SHA256 = "' + candidate_hash.encode() + b'"\n', 1)
    require(helper.normalized_helper(sealed_helper) == helper.normalized_helper(raw_helper),
        "sealing changed more than the helper manifest-hash slot")
    require(candidate_raw != previous_raw and sealed_helper != raw_helper, "seal must change both exact paths")
    summary = {"source_commit": source, "source_tree": value["source_tree"],
        "manifest_sha256": candidate_hash, "helper_normalized_sha256": EXPECTED_HELPER,
        "schema_version": 4, "source_records": len(files), "source_records_preserved": len(reused),
        "source_records_refreshed": rebuilt, "target_records": len(target_files),
        "target_records_preserved": len(target_reused), "target_records_refreshed": target_rebuilt,
        "continuation_review_paths": sorted(continuation_paths),
        "historical_records_removed_by_exact_base_reversion": sorted(
            {entry["path"] for entry in previous["files"]} - cumulative_paths),
        "required_seal_delta": sorted([CONTRACT, HELPER]),
        "qualification": "Source identity and history only; no hardware or CI qualification"}
    return helper, raw_helper, previous_raw, candidate_raw, sealed_helper, summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--source", required=True, help="full exact SHA of reviewed committed UNSEALED source")
    parser.add_argument("--review-reasons", type=Path, required=True,
        help="reviewer-authored JSON mapping each changed continuation path to its exact rationale")
    parser.add_argument("--stage", action="store_true",
        help="explicitly write and stage exactly helper+manifest after validation; never commit")
    args = parser.parse_args()
    root = args.repo_root.resolve()
    helper, old_helper, old_manifest, new_manifest, new_helper, summary = prepare(
        root, args.source, args.review_reasons)
    if args.stage:
        # Recheck immediately before mutation. No existing work is overwritten.
        require(helper.revision(root, "HEAD") == args.source, "HEAD changed during preparation")
        helper.verify_checkout(root, helper.tree(root, args.source))
        require(helper.regular(root, HELPER) == old_helper and helper.regular(root, CONTRACT) == old_manifest,
            "source changed during preparation")
        (root / CONTRACT).write_bytes(new_manifest)
        (root / HELPER).write_bytes(new_helper)
        git(root, "add", "--", CONTRACT, HELPER)
        require(helper.revision(root, "HEAD") == args.source, "HEAD changed during staging")
        changed = {p.decode() for p in git(root, "diff", "--cached", "--name-only", "-z", args.source).split(b"\0") if p}
        require(changed == {CONTRACT, HELPER}, "staged delta is not exactly the two seal files")
        require(not git(root, "diff", "--name-only", "-z"), "unstaged edits appeared during staging")
        require(helper.regular(root, HELPER) == new_helper and helper.regular(root, CONTRACT) == new_manifest,
            "staged candidate source changed")
        git(root, "diff", "--cached", "--check")
        summary["staged_tree"] = git(root, "write-tree").decode().strip()
        summary["state"] = "STAGED_ONLY; commit the exact two-file seal, then run the normal production verifier"
    else:
        require(helper.revision(root, "HEAD") == args.source, "HEAD changed during validation")
        helper.verify_checkout(root, helper.tree(root, args.source))
        summary["state"] = "VALIDATED_DRY_RUN; no repository bytes or index changed"
    print(json.dumps(summary, indent=2, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
