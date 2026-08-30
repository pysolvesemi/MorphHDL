#!/usr/bin/env python3
"""Validate the reviewed MorphHDL native-source preservation manifest."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Tuple

EXPECTED_REPOSITORY = "pysolvesemi/MorphHDL"
DEFAULT_MANIFEST = "morphhdl/contracts/native-source-preservation.json"
TYPED_OVERLAY_MANIFEST = "morphhdl/contracts/typed-native-source-overlay.json"
TYPED_OVERLAY_GUARD = "morphhdl/scripts/check-typed-native-source-overlay.py"
ALLOWED_CLASSIFICATIONS = {
    "direct_edit",
    "morphhdl_sidecar",
    "generated_backend_coupling",
}
CHANGE_TO_STATUS = {"added": "A", "modified": "M"}
SHA1_RE = re.compile(r"^[0-9a-f]{40}$")


class GuardError(RuntimeError):
    """A deterministic native-source preservation contract failure."""


def run_git(
    repo_root: Path,
    arguments: Sequence[str],
    *,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["git", "-C", str(repo_root), *arguments],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if check and result.returncode != 0:
        command = " ".join(["git", *arguments])
        detail = result.stderr.strip() or result.stdout.strip()
        raise GuardError(f"{command} failed: {detail}")
    return result


def require_sha1(value: Any, field: str) -> str:
    if not isinstance(value, str) or SHA1_RE.fullmatch(value) is None:
        raise GuardError(f"{field} must be one lowercase 40-character Git SHA-1")
    return value


def require_clean_relative_path(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise GuardError(f"{field} must be a non-empty repository-relative path")
    path = Path(value)
    if path.is_absolute() or value.startswith("./") or ".." in path.parts:
        raise GuardError(f"{field} is not a clean repository-relative path: {value}")
    normalized = path.as_posix()
    if normalized != value or value.endswith("/"):
        raise GuardError(f"{field} must use normalized POSIX spelling: {value}")
    return value


def load_manifest(path: Path) -> Mapping[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise GuardError(f"manifest does not exist: {path}") from error
    except json.JSONDecodeError as error:
        raise GuardError(f"manifest is not valid JSON: {error}") from error
    if not isinstance(payload, dict):
        raise GuardError("manifest root must be a JSON object")
    return payload


def resolve_commit(repo_root: Path, value: str, field: str) -> str:
    resolved = run_git(
        repo_root,
        ["rev-parse", "--verify", f"{value}^{{commit}}"],
    ).stdout.strip()
    if resolved != value:
        raise GuardError(f"{field} must be a full immutable commit SHA, got {value}")
    return resolved


def resolve_tree(repo_root: Path, commit: str) -> str:
    return run_git(repo_root, ["rev-parse", f"{commit}^{{tree}}"]).stdout.strip()


def is_ancestor(repo_root: Path, older: str, newer: str) -> bool:
    result = run_git(
        repo_root,
        ["merge-base", "--is-ancestor", older, newer],
        check=False,
    )
    if result.returncode not in (0, 1):
        detail = result.stderr.strip() or result.stdout.strip()
        raise GuardError(f"git merge-base failed: {detail}")
    return result.returncode == 0


def object_at(repo_root: Path, commit: str, path: str) -> Optional[str]:
    result = run_git(
        repo_root,
        ["rev-parse", "--verify", f"{commit}:{path}"],
        check=False,
    )
    if result.returncode != 0:
        return None
    object_sha = result.stdout.strip()
    object_type = run_git(repo_root, ["cat-file", "-t", object_sha]).stdout.strip()
    if object_type != "blob":
        raise GuardError(
            f"manifest path must resolve to a blob at {commit}: {path} ({object_type})"
        )
    return object_sha


def parse_name_status(output: str) -> Dict[str, str]:
    changes: Dict[str, str] = {}
    for line in output.splitlines():
        if not line:
            continue
        fields = line.split("\t")
        if len(fields) != 2:
            raise GuardError(f"unsupported git name-status record: {line}")
        status, path = fields
        if status not in {"A", "M", "D", "T"}:
            raise GuardError(f"unsupported native-source change status {status}: {path}")
        if path in changes:
            raise GuardError(f"duplicate native-source change record: {path}")
        changes[path] = status
    return changes


def changed_paths(
    repo_root: Path,
    older: str,
    newer: str,
    roots: Sequence[str],
) -> Dict[str, str]:
    output = run_git(
        repo_root,
        [
            "diff",
            "--name-status",
            "--no-renames",
            older,
            newer,
            "--",
            *roots,
        ],
    ).stdout
    return parse_name_status(output)


def under_any_root(path: str, roots: Sequence[str]) -> bool:
    return any(path == root or path.startswith(root + "/") for root in roots)


def validate_manifest_shape(manifest: Mapping[str, Any]) -> Tuple[
    str,
    str,
    str,
    str,
    List[str],
    List[Mapping[str, Any]],
]:
    if manifest.get("schema_version") != 1:
        raise GuardError("schema_version must be 1")
    if manifest.get("repository") != EXPECTED_REPOSITORY:
        raise GuardError(f"repository must be {EXPECTED_REPOSITORY}")
    if manifest.get("hash_format") != "git-sha1":
        raise GuardError("hash_format must be git-sha1")

    baseline = manifest.get("baseline")
    approved = manifest.get("approved_state")
    if not isinstance(baseline, dict) or not isinstance(approved, dict):
        raise GuardError("baseline and approved_state must be JSON objects")
    baseline_commit = require_sha1(baseline.get("commit"), "baseline.commit")
    baseline_tree = require_sha1(baseline.get("tree"), "baseline.tree")
    approved_commit = require_sha1(approved.get("commit"), "approved_state.commit")
    approved_tree = require_sha1(approved.get("tree"), "approved_state.tree")

    roots_raw = manifest.get("source_roots")
    if not isinstance(roots_raw, list) or not roots_raw:
        raise GuardError("source_roots must be a non-empty JSON array")
    roots = [
        require_clean_relative_path(value, f"source_roots[{index}]")
        for index, value in enumerate(roots_raw)
    ]
    if roots != sorted(set(roots)):
        raise GuardError("source_roots must be unique and lexicographically sorted")

    classifications = manifest.get("classifications")
    if not isinstance(classifications, list) or set(classifications) != ALLOWED_CLASSIFICATIONS:
        raise GuardError(
            "classifications must contain exactly direct_edit, morphhdl_sidecar and generated_backend_coupling"
        )

    entries_raw = manifest.get("entries")
    if not isinstance(entries_raw, list) or not entries_raw:
        raise GuardError("entries must be a non-empty JSON array")
    entries: List[Mapping[str, Any]] = []
    paths: List[str] = []
    for index, raw in enumerate(entries_raw):
        if not isinstance(raw, dict):
            raise GuardError(f"entries[{index}] must be a JSON object")
        path = require_clean_relative_path(raw.get("path"), f"entries[{index}].path")
        if not under_any_root(path, roots):
            raise GuardError(f"manifest entry is outside source_roots: {path}")
        if not path.endswith(".scala"):
            raise GuardError(f"manifest entry is not a Scala source file: {path}")
        change = raw.get("change")
        if change not in CHANGE_TO_STATUS:
            raise GuardError(f"unsupported manifest change for {path}: {change}")
        classification = raw.get("classification")
        if classification not in ALLOWED_CLASSIFICATIONS:
            raise GuardError(f"unsupported classification for {path}: {classification}")
        introduced_by = raw.get("introduced_by")
        if (
            not isinstance(introduced_by, list)
            or not introduced_by
            or any(not isinstance(value, str) or not value for value in introduced_by)
        ):
            raise GuardError(f"introduced_by must be a non-empty string array for {path}")
        reason = raw.get("reason")
        if not isinstance(reason, str) or not reason.strip():
            raise GuardError(f"reason must be non-empty for {path}")
        entries.append(raw)
        paths.append(path)

    if paths != sorted(paths):
        raise GuardError("manifest entries must be lexicographically sorted by path")
    if len(paths) != len(set(paths)):
        raise GuardError("manifest entries contain duplicate paths")

    return (
        baseline_commit,
        baseline_tree,
        approved_commit,
        approved_tree,
        roots,
        entries,
    )


def check_repository(
    repo_root: Path,
    manifest_path: Path,
    *,
    head_override: Optional[str] = None,
) -> None:
    manifest = load_manifest(manifest_path)
    (
        baseline_commit,
        baseline_tree,
        approved_commit,
        approved_tree,
        roots,
        entries,
    ) = validate_manifest_shape(manifest)

    resolve_commit(repo_root, baseline_commit, "baseline.commit")
    resolve_commit(repo_root, approved_commit, "approved_state.commit")
    if head_override is not None:
        resolve_commit(repo_root, head_override, "head_override")
    actual_baseline_tree = resolve_tree(repo_root, baseline_commit)
    actual_approved_tree = resolve_tree(repo_root, approved_commit)
    if actual_baseline_tree != baseline_tree:
        raise GuardError(
            f"baseline.tree mismatch: manifest {baseline_tree}, Git {actual_baseline_tree}"
        )
    if actual_approved_tree != approved_tree:
        raise GuardError(
            f"approved_state.tree mismatch: manifest {approved_tree}, Git {actual_approved_tree}"
        )
    if not is_ancestor(repo_root, baseline_commit, approved_commit):
        raise GuardError("baseline.commit is not an ancestor of approved_state.commit")

    head = (
        resolve_commit(repo_root, head_override, "head_override")
        if head_override is not None
        else run_git(repo_root, ["rev-parse", "HEAD"]).stdout.strip()
    )
    if not is_ancestor(repo_root, approved_commit, head):
        raise GuardError(
            "approved_state.commit is not an ancestor of HEAD; rebase or review a new approved state"
        )

    actual_changes = changed_paths(
        repo_root,
        baseline_commit,
        approved_commit,
        roots,
    )
    expected_changes = {
        str(entry["path"]): CHANGE_TO_STATUS[str(entry["change"])]
        for entry in entries
    }
    missing = sorted(set(actual_changes) - set(expected_changes))
    stale = sorted(set(expected_changes) - set(actual_changes))
    mismatched = sorted(
        path
        for path in set(actual_changes).intersection(expected_changes)
        if actual_changes[path] != expected_changes[path]
    )
    if missing or stale or mismatched:
        details: List[str] = []
        if missing:
            details.append("unclassified=" + ", ".join(missing))
        if stale:
            details.append("not-in-reviewed-diff=" + ", ".join(stale))
        if mismatched:
            details.append(
                "status-mismatch="
                + ", ".join(
                    f"{path}:{expected_changes[path}}->{actual_changes[path}"
                    for path in mismatched
                )
            )
        raise GuardError("manifest does not exactly classify the reviewed source diff: " + "; ".join(details))

    counts = {name: 0 for name in sorted(ALLOWED_CLASSIFICATIONS)}
    for entry in entries:
        path = str(entry["path"])
        change = str(entry["change"])
        baseline_blob = object_at(repo_root, baseline_commit, path)
        approved_blob = object_at(repo_root, approved_commit, path)
        if approved_blob is None:
            raise GuardError(f"approved source blob is missing: {path}")
        if change == "added":
            if baseline_blob is not None:
                raise GuardError(f"added manifest entry already existed at baseline: {path}")
        elif change == "modified":
            if baseline_blob is None:
                raise GuardError(f"modified manifest entry is absent at baseline: {path}")
            if baseline_blob == approved_blob:
                raise GuardError(f"modified manifest entry has identical blobs: {path}")
        counts[str(entry["classification"])] += 1

    post_approval_changes = changed_paths(
        repo_root,
        approved_commit,
        head,
        roots,
    )
    if post_approval_changes:
        rendered = ", ".join(
            f"{status} {path}" for path, status in sorted(post_approval_changes.items())
        )
        raise GuardError(
            "unapproved native-source modifications exist after approved_state.commit: "
            + rendered
        )

    dirty = run_git(
        repo_root,
        ["status", "--porcelain=v1", "--untracked-files=all", "--", *roots],
    ).stdout.strip()
    if dirty:
        raise GuardError("native-source working tree is not clean:\n" + dirty)

    print("Native-source preservation manifest is valid")
    print(f"  baseline commit : {baseline_commit}")
    print(f"  baseline tree   : {baseline_tree}")
    print(f"  approved commit : {approved_commit}")
    print(f"  approved tree   : {approved_tree}")
    print(f"  checked HEAD    : {head}")
    print(f"  classified paths: {len(entries)}")
    for classification, count in counts.items():
        print(f"    {classification}: {count}")


def write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def commit_all(repo_root: Path, message: str) -> str:
    run_git(repo_root, ["add", "-A"])
    run_git(repo_root, ["commit", "-q", "-m", message])
    return run_git(repo_root, ["rev-parse", "HEAD"]).stdout.strip()


def expect_guard_failure(repo_root: Path, manifest_path: Path, label: str) -> None:
    try:
        check_repository(repo_root, manifest_path)
    except GuardError:
        return
    raise GuardError(f"self-test expected guard failure: {label}")


def run_self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="morphhdl-native-guard-") as directory:
        repo_root = Path(directory)
        run_git(repo_root, ["init", "-q"])
        run_git(repo_root, ["config", "user.name", "MorphHDL guard test"])
        run_git(repo_root, ["config", "user.email", "guard@example.invalid"])

        native = repo_root / "native"
        native.mkdir()
        native_file = native / "Native.scala"
        native_file.write_text("object Native\n", encoding="utf-8")
        baseline_commit = commit_all(repo_root, "baseline")
        baseline_tree = resolve_tree(repo_root, baseline_commit)

        native_file.write_text("object Native { val reviewed = true }\n", encoding="utf-8")
        sidecar = native / "Sidecar.scala"
        sidecar.write_text("object Sidecar\n", encoding="utf-8")
        approved_commit = commit_all(repo_root, "approved")
        approved_tree = resolve_tree(repo_root, approved_commit)

        manifest: Dict[str, Any] = {
            "schema_version": 1,
            "repository": EXPECTED_REPOSITORY,
            "hash_format": "git-sha1",
            "baseline": {
                "commit": baseline_commit,
                "tree": baseline_tree,
                "description": "self-test baseline",
            },
            "approved_state": {
                "commit": approved_commit,
                "tree": approved_tree,
                "description": "self-test approved state",
            },
            "source_roots": ["native"],
            "classifications": sorted(ALLOWED_CLASSIFICATIONS),
            "entries": [
                {
                    "path": "native/Native.scala",
                    "change": "modified",
                    "classification": "direct_edit",
                    "introduced_by": ["test"],
                    "reason": "self-test reviewed modification",
                },
                {
                    "path": "native/Sidecar.scala",
                    "change": "added",
                    "classification": "morphhdl_sidecar",
                    "introduced_by": ["test"],
                    "reason": "self-test reviewed sidecar",
                },
            ],
        }
        manifest_path = repo_root / "manifest.json"
        write_json(manifest_path, manifest)
        check_repository(repo_root, manifest_path)

        native_file.write_text("object Native { val dirty = true }\n", encoding="utf-8")
        expect_guard_failure(repo_root, manifest_path, "dirty tracked native source")
        run_git(repo_root, ["checkout", "--", "native/Native.scala"])

        unexpected = native / "Unexpected.scala"
        unexpected.write_text("object Unexpected\n", encoding="utf-8")
        commit_all(repo_root, "unapproved addition")
        expect_guard_failure(repo_root, manifest_path, "committed unapproved native source")
        run_git(repo_root, ["reset", "--hard", approved_commit])

        incomplete = json.loads(json.dumps(manifest))
        incomplete["entries"] = incomplete["entries"][:1]
        incomplete_path = repo_root / "incomplete.json"
        write_json(incomplete_path, incomplete)
        expect_guard_failure(repo_root, incomplete_path, "unclassified reviewed source")

        bad_tree = json.loads(json.dumps(manifest))
        bad_tree["approved_state"]["tree"] = "0" * 40
        bad_tree_path = repo_root / "bad-tree.json"
        write_json(bad_tree_path, bad_tree)
        expect_guard_failure(repo_root, bad_tree_path, "incorrect approved tree hash")

    print("Native-source preservation guard self-test passed")


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest",
        default=DEFAULT_MANIFEST,
        help=f"manifest path relative to the repository root (default: {DEFAULT_MANIFEST})",
    )
    parser.add_argument(
        "--repo-root",
        help="repository root; defaults to git rev-parse --show-toplevel",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run isolated positive and negative guard tests",
    )
    arguments = parser.parse_args(argv)

    try:
        if arguments.self_test:
            run_self_test()
            return 0
        if arguments.repo_root:
            repo_root = Path(arguments.repo_root).resolve()
        else:
            probe = subprocess.run(
                ["git", "rev-parse", "--show-toplevel"],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            if probe.returncode != 0:
                raise GuardError(probe.stderr.strip() or "not inside a Git repository")
            repo_root = Path(probe.stdout.strip()).resolve()
        manifest_path = Path(arguments.manifest)
        if not manifest_path.is_absolute():
            manifest_path = repo_root / manifest_path
        overlay_path = repo_root / TYPED_OVERLAY_MANIFEST
        if (
            arguments.manifest == DEFAULT_MANIFEST
            and overlay_path.is_file()
        ):
            overlay = load_manifest(overlay_path)
            base = overlay.get("base")
            if not isinstance(base, dict):
                raise GuardError("typed native overlay base must be a JSON object")
            typed_base = require_sha1(
                base.get("commit"),
                "typed_native_overlay.base.commit",
            )
            # Prove the complete historical zero-edit contract at the exact
            # architecture-pivot base, then prove only the reviewed typed
            # overlay from that immutable base to the current HEAD.
            check_repository(
                repo_root,
                manifest_path,
                head_override=typed_base,
            )
            guard = repo_root / TYPED_OVERLAY_GUARD
            result = subprocess.run(
                [
                    sys.executable,
                    str(guard),
                    "--repo-root",
                    str(repo_root),
                    "--manifest",
                    str(overlay_path),
                ],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            if result.stdout:
                print(result.stdout.rstrip())
            if result.returncode != 0:
                detail = result.stderr.strip() or result.stdout.strip()
                raise GuardError(
                    "typed native-source overlay validation failed: " + detail
                )
        else:
            check_repository(repo_root, manifest_path)
        return 0
    except GuardError as error:
        print(f"Native-source preservation guard failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
