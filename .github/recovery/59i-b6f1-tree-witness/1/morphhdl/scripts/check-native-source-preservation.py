#!/usr/bin/env python3
"""Verify the exact reviewed MorphHDL native-source delta.

Schema version 2 pins the selected upstream commit and each upstream
production-root tree, but not an approved commit. Approved root trees and file
blobs live outside this manifest's own tree, so they remain stable across
rebases, merge commits, and a same-commit manifest update.

For a modified file, ordered byte-span pairs describe the reviewed edits. The
guard hashes both sides of every span and proves that every byte between spans
is identical to the upstream blob. It therefore does not depend on Git diff
hunk heuristics, text conversion, line endings, or formatter behavior.
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional, Sequence, Tuple


EXPECTED_REPOSITORY = "pysolvesemi/MorphHDL"
DEFAULT_MANIFEST = "morphhdl/contracts/native-source-preservation.json"
DEFAULT_UPSTREAM_CONFIG = "morphhdl/upstream-base.conf"
RETIREMENT_MANIFEST = "morphhdl/contracts/increment-53g-production-retirement.contract"
RETIREMENT_GUARD = "morphhdl/scripts/check-production-retirement.py"

EXPECTED_SOURCE_ROOTS = (
    "core/src/main",
    "idslpayload/src/main",
    "idslplugin/src/main",
    "lib/src/main",
    "scalaplugin/src/main",
    "sim/src/main",
    "tester/src/main",
)

ALLOWED_CLASSIFICATIONS = {
    "backend-isolation-hook",
    "mechanical-propagation",
    "platform-integration-hook",
    "typed-overload",
    "typed-signature",
    "typed-support-file",
}

ALLOWED_EDIT_KINDS = {
    "backend-isolation",
    "mechanical-propagation",
    "overload",
    "platform-integration",
    "signature",
}

CHANGE_STATUSES = {"added": "A", "modified": "M", "removed": "D"}
SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
GIT_MODE = re.compile(r"^[0-7]{6}$")
ERROR_PREFIX = "MORPH-NATIVE-AUDIT-"


class AuditError(RuntimeError):
    """One deterministic native-audit failure."""

    def __init__(self, code: str, detail: str):
        self.code = code if code.startswith(ERROR_PREFIX) else ERROR_PREFIX + code
        self.detail = detail
        super().__init__(f"{self.code}: {detail}")


def fail(code: str, detail: str) -> None:
    raise AuditError(code, detail)


def decode_output(value: bytes) -> str:
    return value.decode("utf-8", errors="replace").strip()


def run_git_bytes(
    root: Path,
    arguments: Sequence[str],
    *,
    check: bool = True,
) -> subprocess.CompletedProcess[bytes]:
    result = subprocess.run(
        ["git", "-C", str(root), *arguments],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if check and result.returncode != 0:
        detail = decode_output(result.stderr) or decode_output(result.stdout)
        fail("GIT-FAILURE", f"git {' '.join(arguments)} failed: {detail}")
    return result


def git_text(root: Path, *arguments: str) -> str:
    return decode_output(run_git_bytes(root, arguments).stdout)


def repository_root(explicit: Optional[str]) -> Path:
    if explicit:
        root = Path(explicit).resolve()
        probe = run_git_bytes(root, ["rev-parse", "--show-toplevel"])
        actual = Path(decode_output(probe.stdout)).resolve()
        if actual != root:
            fail(
                "REPOSITORY-ROOT-MISMATCH",
                f"--repo-root resolved to {root}, but Git reports {actual}",
            )
        return root
    probe = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if probe.returncode != 0:
        fail("GIT-FAILURE", decode_output(probe.stderr) or "not inside a Git repository")
    return Path(decode_output(probe.stdout)).resolve()


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def require_exact_keys(
    value: Mapping[str, Any],
    required: Sequence[str],
    role: str,
) -> None:
    expected = set(required)
    actual = set(value)
    missing = sorted(expected - actual)
    unknown = sorted(actual - expected)
    if missing or unknown:
        fail(
            "MANIFEST-INVALID",
            f"{role} keys differ; missing={missing}, unknown={unknown}",
        )


def require_nonempty_string(value: Any, role: str) -> str:
    if not isinstance(value, str) or not value.strip():
        fail("MANIFEST-INVALID", f"{role} must be a non-empty string")
    return value


def require_sha1(value: Any, role: str) -> str:
    if not isinstance(value, str) or SHA1.fullmatch(value) is None:
        fail("MANIFEST-INVALID", f"{role} must be one lowercase 40-character Git SHA-1")
    return value


def require_sha256(value: Any, role: str) -> str:
    if not isinstance(value, str) or SHA256.fullmatch(value) is None:
        fail("MANIFEST-INVALID", f"{role} must be one lowercase SHA-256")
    return value


def require_mode(value: Any, role: str) -> str:
    if not isinstance(value, str) or GIT_MODE.fullmatch(value) is None:
        fail("MANIFEST-INVALID", f"{role} must be one six-digit Git mode")
    return value


def clean_path(value: Any, role: str) -> str:
    if not isinstance(value, str) or not value:
        fail("MANIFEST-INVALID", f"{role} must be a non-empty repository-relative path")
    path = Path(value)
    if path.is_absolute() or value.startswith("./") or ".." in path.parts:
        fail("MANIFEST-INVALID", f"{role} is not a clean repository-relative path: {value}")
    if path.as_posix() != value or value.endswith("/"):
        fail("MANIFEST-INVALID", f"{role} must use normalized POSIX spelling: {value}")
    if any(ord(character) < 32 for character in value):
        fail("MANIFEST-INVALID", f"{role} contains a control character")
    return value


def under_root(path: str, roots: Sequence[str]) -> bool:
    return any(path.startswith(root + "/") for root in roots)


def load_manifest(path: Path) -> Mapping[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        fail("MANIFEST-MISSING", f"manifest does not exist: {path}")
        raise AssertionError("unreachable") from error
    except json.JSONDecodeError as error:
        fail("MANIFEST-INVALID", f"manifest is not valid JSON: {error}")
        raise AssertionError("unreachable") from error
    if not isinstance(value, dict):
        fail("MANIFEST-INVALID", "manifest root must be a JSON object")
    return value


def parse_state(value: Any, role: str, roots: Sequence[str]) -> Mapping[str, Any]:
    if not isinstance(value, dict):
        fail("MANIFEST-INVALID", f"{role} must be an object")
    require_exact_keys(value, ("path", "mode", "blob", "sha256"), role)
    path = clean_path(value.get("path"), f"{role}.path")
    if not under_root(path, roots):
        fail("MANIFEST-INVALID", f"{role}.path lies outside audited roots: {path}")
    require_mode(value.get("mode"), f"{role}.mode")
    require_sha1(value.get("blob"), f"{role}.blob")
    require_sha256(value.get("sha256"), f"{role}.sha256")
    return value


def parse_span(value: Any, role: str) -> Mapping[str, Any]:
    if not isinstance(value, dict):
        fail("MANIFEST-INVALID", f"{role} must be an object")
    require_exact_keys(value, ("start", "end", "sha256"), role)
    start = value.get("start")
    end = value.get("end")
    if (
        not isinstance(start, int)
        or isinstance(start, bool)
        or not isinstance(end, int)
        or isinstance(end, bool)
        or start < 0
        or end < start
    ):
        fail("MANIFEST-INVALID", f"{role} must be one non-negative half-open byte span")
    require_sha256(value.get("sha256"), f"{role}.sha256")
    return value


def validate_manifest_shape(manifest: Mapping[str, Any]) -> Tuple[
    str,
    str,
    str,
    List[Mapping[str, Any]],
    List[Mapping[str, Any]],
]:
    require_exact_keys(
        manifest,
        (
            "schema_version",
            "repository",
            "git_object_format",
            "content_hash_format",
            "upstream_config",
            "baseline",
            "source_roots",
            "classifications",
            "entries",
        ),
        "manifest",
    )
    if manifest.get("schema_version") != 2:
        fail("MANIFEST-INVALID", "schema_version must be 2")
    if manifest.get("repository") != EXPECTED_REPOSITORY:
        fail("MANIFEST-INVALID", f"repository must be {EXPECTED_REPOSITORY}")
    if manifest.get("git_object_format") != "sha1":
        fail("MANIFEST-INVALID", "git_object_format must be sha1")
    if manifest.get("content_hash_format") != "sha256":
        fail("MANIFEST-INVALID", "content_hash_format must be sha256")
    upstream_config = clean_path(manifest.get("upstream_config"), "upstream_config")

    baseline = manifest.get("baseline")
    if not isinstance(baseline, dict):
        fail("MANIFEST-INVALID", "baseline must be an object")
    require_exact_keys(baseline, ("commit", "tree"), "baseline")
    baseline_commit = require_sha1(baseline.get("commit"), "baseline.commit")
    baseline_tree = require_sha1(baseline.get("tree"), "baseline.tree")

    roots_value = manifest.get("source_roots")
    if not isinstance(roots_value, list):
        fail("ROOT-SET-MISMATCH", "source_roots must be an array")
    roots: List[Mapping[str, Any]] = []
    root_paths: List[str] = []
    for index, value in enumerate(roots_value):
        role = f"source_roots[{index}]"
        if not isinstance(value, dict):
            fail("ROOT-SET-MISMATCH", f"{role} must be an object")
        require_exact_keys(value, ("path", "baseline_tree", "approved_tree"), role)
        path = clean_path(value.get("path"), f"{role}.path")
        require_sha1(value.get("baseline_tree"), f"{role}.baseline_tree")
        require_sha1(value.get("approved_tree"), f"{role}.approved_tree")
        roots.append(value)
        root_paths.append(path)
    if tuple(root_paths) != EXPECTED_SOURCE_ROOTS:
        fail(
            "ROOT-SET-MISMATCH",
            "source_roots must contain exactly, in order: " + ", ".join(EXPECTED_SOURCE_ROOTS),
        )

    classifications = manifest.get("classifications")
    if not isinstance(classifications, list) or classifications != sorted(ALLOWED_CLASSIFICATIONS):
        fail(
            "MANIFEST-INVALID",
            "classifications must contain exactly the canonical approved-change classes",
        )

    entries_value = manifest.get("entries")
    if not isinstance(entries_value, list) or not entries_value:
        fail("MANIFEST-INVALID", "entries must be a non-empty array")
    entries: List[Mapping[str, Any]] = []
    entry_paths: List[str] = []
    edit_ids = set()
    consumed_paths = set()

    for index, entry in enumerate(entries_value):
        role = f"entries[{index}]"
        if not isinstance(entry, dict):
            fail("MANIFEST-INVALID", f"{role} must be an object")
        require_exact_keys(
            entry,
            (
                "path",
                "change",
                "classification",
                "introduced_by",
                "reason",
                "baseline",
                "approved",
                "edits",
            ),
            role,
        )
        path = clean_path(entry.get("path"), f"{role}.path")
        if not under_root(path, root_paths):
            fail("MANIFEST-INVALID", f"entry lies outside audited roots: {path}")
        change = entry.get("change")
        if change not in {"added", "modified", "removed", "renamed"}:
            fail("MANIFEST-INVALID", f"unsupported change for {path}: {change}")
        classification = entry.get("classification")
        if classification not in ALLOWED_CLASSIFICATIONS:
            fail("MANIFEST-INVALID", f"unsupported classification for {path}: {classification}")
        introduced_by = entry.get("introduced_by")
        if (
            not isinstance(introduced_by, list)
            or not introduced_by
            or any(not isinstance(item, str) or not item.strip() for item in introduced_by)
        ):
            fail("MANIFEST-INVALID", f"introduced_by must be a non-empty string array for {path}")
        require_nonempty_string(entry.get("reason"), f"{role}.reason")

        baseline_state = entry.get("baseline")
        approved_state = entry.get("approved")
        if baseline_state is not None:
            baseline_state = parse_state(baseline_state, f"{role}.baseline", root_paths)
        if approved_state is not None:
            approved_state = parse_state(approved_state, f"{role}.approved", root_paths)

        if change == "added":
            if baseline_state is not None or approved_state is None or approved_state["path"] != path:
                fail("MANIFEST-INVALID", f"added entry has inconsistent states: {path}")
        elif change == "removed":
            if approved_state is not None or baseline_state is None or baseline_state["path"] != path:
                fail("MANIFEST-INVALID", f"removed entry has inconsistent states: {path}")
        elif change == "modified":
            if (
                baseline_state is None
                or approved_state is None
                or baseline_state["path"] != path
                or approved_state["path"] != path
            ):
                fail("MANIFEST-INVALID", f"modified entry has inconsistent states: {path}")
        else:
            if (
                baseline_state is None
                or approved_state is None
                or approved_state["path"] != path
                or baseline_state["path"] == approved_state["path"]
            ):
                fail("MANIFEST-INVALID", f"renamed entry has inconsistent states: {path}")

        consumed = []
        if baseline_state is not None:
            consumed.append(str(baseline_state["path"]))
        if approved_state is not None:
            consumed.append(str(approved_state["path"]))
        for consumed_path in set(consumed):
            if consumed_path in consumed_paths:
                fail("MANIFEST-INVALID", f"native path is consumed by multiple entries: {consumed_path}")
            consumed_paths.add(consumed_path)

        edits = entry.get("edits")
        if not isinstance(edits, list):
            fail("MANIFEST-INVALID", f"edits must be an array for {path}")
        if change == "modified" and not edits:
            fail("MANIFEST-INVALID", f"modified entry needs at least one byte-span edit: {path}")
        if change in {"added", "removed"} and edits:
            fail("MANIFEST-INVALID", f"{change} entry must use whole-file approval: {path}")

        previous_baseline_end = 0
        previous_approved_end = 0
        for edit_index, edit in enumerate(edits):
            edit_role = f"{role}.edits[{edit_index}]"
            if not isinstance(edit, dict):
                fail("MANIFEST-INVALID", f"{edit_role} must be an object")
            require_exact_keys(
                edit,
                (
                    "id",
                    "kind",
                    "owner",
                    "reason",
                    "baseline_span",
                    "approved_span",
                    "required_exact_text",
                ),
                edit_role,
            )
            edit_id = require_nonempty_string(edit.get("id"), f"{edit_role}.id")
            if re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", edit_id) is None:
                fail("MANIFEST-INVALID", f"edit id is not stable kebab-case: {edit_id}")
            if edit_id in edit_ids:
                fail("MANIFEST-INVALID", f"duplicate edit id: {edit_id}")
            edit_ids.add(edit_id)
            if edit.get("kind") not in ALLOWED_EDIT_KINDS:
                fail("MANIFEST-INVALID", f"unsupported edit kind for {edit_id}: {edit.get('kind')}")
            require_nonempty_string(edit.get("owner"), f"{edit_role}.owner")
            require_nonempty_string(edit.get("reason"), f"{edit_role}.reason")
            baseline_span = parse_span(edit.get("baseline_span"), f"{edit_role}.baseline_span")
            approved_span = parse_span(edit.get("approved_span"), f"{edit_role}.approved_span")
            if (
                baseline_span["start"] < previous_baseline_end
                or approved_span["start"] < previous_approved_end
            ):
                fail("HUNK-ORDER", f"byte spans overlap or move backwards at {path}:{edit_id}")
            previous_baseline_end = int(baseline_span["end"])
            previous_approved_end = int(approved_span["end"])

            assertions = edit.get("required_exact_text")
            if not isinstance(assertions, list) or not assertions:
                fail("MANIFEST-INVALID", f"{edit_role}.required_exact_text must be non-empty")
            for assertion_index, assertion in enumerate(assertions):
                assertion_role = f"{edit_role}.required_exact_text[{assertion_index}]"
                if not isinstance(assertion, dict):
                    fail("MANIFEST-INVALID", f"{assertion_role} must be an object")
                require_exact_keys(assertion, ("side", "text", "count"), assertion_role)
                if assertion.get("side") not in {"baseline", "approved"}:
                    fail("MANIFEST-INVALID", f"{assertion_role}.side must be baseline or approved")
                require_nonempty_string(assertion.get("text"), f"{assertion_role}.text")
                count = assertion.get("count")
                if not isinstance(count, int) or isinstance(count, bool) or count < 1:
                    fail("MANIFEST-INVALID", f"{assertion_role}.count must be a positive integer")

        entries.append(entry)
        entry_paths.append(path)

    if entry_paths != sorted(entry_paths):
        fail("MANIFEST-INVALID", "entries must be lexicographically sorted by path")
    if len(entry_paths) != len(set(entry_paths)):
        fail("MANIFEST-INVALID", "entries contain duplicate canonical paths")

    return baseline_commit, baseline_tree, upstream_config, roots, entries


def parse_upstream_commit(path: Path) -> str:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except FileNotFoundError:
        fail("UPSTREAM-CONFIG-MISMATCH", f"upstream config is missing: {path}")
    values = [line.split("=", 1)[1] for line in lines if line.startswith("UPSTREAM_COMMIT=")]
    if len(values) != 1 or SHA1.fullmatch(values[0]) is None:
        fail("UPSTREAM-CONFIG-MISMATCH", f"UPSTREAM_COMMIT is missing or invalid in {path}")
    return values[0]


def resolve_commit(root: Path, commit: str) -> str:
    resolved = git_text(root, "rev-parse", "--verify", f"{commit}^{{commit}}")
    if resolved != commit:
        fail("BASE-COMMIT-MISMATCH", f"baseline commit did not resolve to itself: {commit} -> {resolved}")
    return resolved


def tree_at(root: Path, commit: str, path: Optional[str] = None) -> str:
    expression = f"{commit}^{{tree}}" if path is None else f"{commit}:{path}"
    object_id = git_text(root, "rev-parse", expression)
    object_type = git_text(root, "cat-file", "-t", object_id)
    if object_type != "tree":
        fail("GIT-FAILURE", f"expected a tree at {expression}, found {object_type}")
    return object_id


def is_ancestor(root: Path, older: str, newer: str) -> bool:
    result = run_git_bytes(root, ["merge-base", "--is-ancestor", older, newer], check=False)
    if result.returncode not in (0, 1):
        detail = decode_output(result.stderr) or decode_output(result.stdout)
        fail("GIT-FAILURE", f"git merge-base failed: {detail}")
    return result.returncode == 0


def object_state(root: Path, commit: str, path: str) -> Optional[Dict[str, Any]]:
    output = run_git_bytes(root, ["ls-tree", "-z", commit, "--", path]).stdout
    if not output:
        return None
    records = [record for record in output.split(b"\0") if record]
    if len(records) != 1 or b"\t" not in records[0]:
        fail("GIT-FAILURE", f"unexpected ls-tree result for {commit}:{path}")
    metadata, raw_path = records[0].split(b"\t", 1)
    try:
        actual_path = raw_path.decode("utf-8")
        fields = metadata.decode("ascii").split()
    except UnicodeDecodeError:
        fail("GIT-FAILURE", f"non-UTF-8 Git record for {commit}:{path}")
    if actual_path != path or len(fields) != 3:
        fail("GIT-FAILURE", f"unexpected ls-tree record for {commit}:{path}")
    mode, object_type, blob = fields
    if object_type != "blob":
        fail("GIT-FAILURE", f"native manifest path is not a blob at {commit}: {path}")
    content = run_git_bytes(root, ["cat-file", "blob", blob]).stdout
    return {
        "path": path,
        "mode": mode,
        "blob": blob,
        "sha256": sha256_bytes(content),
        "content": content,
    }


def parse_raw_inventory(root: Path, baseline: str, head: str, roots: Sequence[str]) -> Dict[str, Dict[str, str]]:
    output = run_git_bytes(
        root,
        [
            "diff",
            "--raw",
            "-z",
            "--abbrev=40",
            "--no-renames",
            "--no-ext-diff",
            "--no-textconv",
            baseline,
            head,
            "--",
            *roots,
        ],
    ).stdout
    if not output:
        return {}
    fields = output.split(b"\0")
    result: Dict[str, Dict[str, str]] = {}
    index = 0
    while index < len(fields):
        header = fields[index]
        index += 1
        if not header:
            if index == len(fields):
                break
            fail("GIT-FAILURE", "empty record inside NUL-delimited raw diff")
        if index >= len(fields):
            fail("GIT-FAILURE", "raw diff ended before its path")
        raw_path = fields[index]
        index += 1
        try:
            header_fields = header.decode("ascii").split()
            path = raw_path.decode("utf-8")
        except UnicodeDecodeError:
            fail("UNAPPROVED-PATH", "raw diff contains a non-UTF-8 path or header")
        if len(header_fields) != 5 or not header_fields[0].startswith(":"):
            fail("GIT-FAILURE", f"unsupported raw diff header: {decode_output(header)}")
        old_mode = header_fields[0][1:]
        new_mode, old_blob, new_blob, status_field = header_fields[1:]
        status = status_field[:1]
        if status not in {"A", "M", "D", "T", "U"}:
            fail("GIT-FAILURE", f"unsupported raw diff status for {path}: {status_field}")
        if path in result:
            fail("GIT-FAILURE", f"duplicate raw diff path: {path}")
        result[path] = {
            "status": status,
            "old_mode": old_mode,
            "new_mode": new_mode,
            "old_blob": old_blob,
            "new_blob": new_blob,
        }
    return result


def expected_inventory(entries: Sequence[Mapping[str, Any]]) -> Dict[str, str]:
    result: Dict[str, str] = {}
    for entry in entries:
        change = str(entry["change"])
        if change == "renamed":
            pairs = (
                (str(entry["baseline"]["path"]), "D"),
                (str(entry["approved"]["path"]), "A"),
            )
        else:
            pairs = ((str(entry["path"]), CHANGE_STATUSES[change]),)
        for path, status in pairs:
            if path in result:
                fail("MANIFEST-INVALID", f"path appears twice in expected inventory: {path}")
            result[path] = status
    return result


def validate_review_assertions(value: Any, role: str) -> Sequence[Mapping[str, Any]]:
    if not isinstance(value, list) or not value:
        fail("GENERATION-POLICY-INVALID", f"{role} must be a non-empty array")
    for index, assertion in enumerate(value):
        assertion_role = f"{role}[{index}]"
        if not isinstance(assertion, dict):
            fail("GENERATION-POLICY-INVALID", f"{assertion_role} must be an object")
        try:
            require_exact_keys(assertion, ("side", "text", "count"), assertion_role)
            if assertion.get("side") not in {"baseline", "approved"}:
                fail("GENERATION-POLICY-INVALID", f"{assertion_role}.side is invalid")
            require_nonempty_string(assertion.get("text"), f"{assertion_role}.text")
        except AuditError as error:
            if error.code == ERROR_PREFIX + "MANIFEST-INVALID":
                fail("GENERATION-POLICY-INVALID", error.detail)
            raise
        count = assertion.get("count")
        if not isinstance(count, int) or isinstance(count, bool) or count < 1:
            fail("GENERATION-POLICY-INVALID", f"{assertion_role}.count must be positive")
    return value


def load_review_policy(path: Path) -> Tuple[str, List[Mapping[str, Any]]]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        fail("GENERATION-POLICY-MISSING", f"review policy does not exist: {path}")
    except json.JSONDecodeError as error:
        fail("GENERATION-POLICY-INVALID", f"review policy is invalid JSON: {error}")
    if not isinstance(value, dict):
        fail("GENERATION-POLICY-INVALID", "review policy root must be an object")
    try:
        require_exact_keys(
            value,
            ("schema_version", "repository", "baseline_commit", "files"),
            "review policy",
        )
    except AuditError as error:
        fail("GENERATION-POLICY-INVALID", error.detail)
    if value.get("schema_version") != 1:
        fail("GENERATION-POLICY-INVALID", "review policy schema_version must be 1")
    if value.get("repository") != EXPECTED_REPOSITORY:
        fail("GENERATION-POLICY-INVALID", f"review policy repository must be {EXPECTED_REPOSITORY}")
    try:
        baseline_commit = require_sha1(value.get("baseline_commit"), "review policy baseline_commit")
    except AuditError as error:
        fail("GENERATION-POLICY-INVALID", error.detail)

    files_value = value.get("files")
    if not isinstance(files_value, list) or not files_value:
        fail("GENERATION-POLICY-INVALID", "review policy files must be non-empty")
    files: List[Mapping[str, Any]] = []
    paths: List[str] = []
    consumed_paths = set()
    edit_ids = set()
    for index, file_policy in enumerate(files_value):
        role = f"review policy files[{index}]"
        if not isinstance(file_policy, dict):
            fail("GENERATION-POLICY-INVALID", f"{role} must be an object")
        try:
            require_exact_keys(
                file_policy,
                (
                    "path",
                    "baseline_path",
                    "change",
                    "classification",
                    "introduced_by",
                    "reason",
                    "edits",
                ),
                role,
            )
            path_value = clean_path(file_policy.get("path"), f"{role}.path")
        except AuditError as error:
            fail("GENERATION-POLICY-INVALID", error.detail)
        if not under_root(path_value, EXPECTED_SOURCE_ROOTS):
            fail("GENERATION-POLICY-INVALID", f"policy path is outside native roots: {path_value}")
        change = file_policy.get("change")
        if change not in {"added", "modified", "removed", "renamed"}:
            fail("GENERATION-POLICY-INVALID", f"unsupported change for {path_value}: {change}")
        classification = file_policy.get("classification")
        if classification not in ALLOWED_CLASSIFICATIONS:
            fail(
                "GENERATION-POLICY-INVALID",
                f"unsupported classification for {path_value}: {classification}",
            )
        introduced_by = file_policy.get("introduced_by")
        if (
            not isinstance(introduced_by, list)
            or not introduced_by
            or any(not isinstance(item, str) or not item.strip() for item in introduced_by)
        ):
            fail("GENERATION-POLICY-INVALID", f"introduced_by is missing for {path_value}")
        try:
            require_nonempty_string(file_policy.get("reason"), f"{role}.reason")
        except AuditError as error:
            fail("GENERATION-POLICY-INVALID", error.detail)

        baseline_path_value = file_policy.get("baseline_path")
        if baseline_path_value is not None:
            try:
                baseline_path_value = clean_path(baseline_path_value, f"{role}.baseline_path")
            except AuditError as error:
                fail("GENERATION-POLICY-INVALID", error.detail)
            if not under_root(baseline_path_value, EXPECTED_SOURCE_ROOTS):
                fail(
                    "GENERATION-POLICY-INVALID",
                    f"baseline policy path is outside native roots: {baseline_path_value}",
                )
        if change == "added" and baseline_path_value is not None:
            fail("GENERATION-POLICY-INVALID", f"added policy needs null baseline_path: {path_value}")
        if change in {"modified", "removed"} and baseline_path_value != path_value:
            fail(
                "GENERATION-POLICY-INVALID",
                f"{change} policy baseline_path must equal path: {path_value}",
            )
        if change == "renamed" and (
            baseline_path_value is None or baseline_path_value == path_value
        ):
            fail("GENERATION-POLICY-INVALID", f"rename policy needs a distinct baseline_path: {path_value}")

        for consumed in {path_value, baseline_path_value} - {None}:
            if consumed in consumed_paths:
                fail("GENERATION-POLICY-INVALID", f"policy consumes a path twice: {consumed}")
            consumed_paths.add(consumed)

        edits = file_policy.get("edits")
        if not isinstance(edits, list):
            fail("GENERATION-POLICY-INVALID", f"edits must be an array for {path_value}")
        if change in {"added", "removed"} and edits:
            fail("GENERATION-POLICY-INVALID", f"{change} policy cannot contain edit metadata")
        for edit_index, edit in enumerate(edits):
            edit_role = f"{role}.edits[{edit_index}]"
            if not isinstance(edit, dict):
                fail("GENERATION-POLICY-INVALID", f"{edit_role} must be an object")
            try:
                require_exact_keys(
                    edit,
                    ("id", "kind", "owner", "reason", "required_exact_text"),
                    edit_role,
                )
                edit_id = require_nonempty_string(edit.get("id"), f"{edit_role}.id")
                require_nonempty_string(edit.get("owner"), f"{edit_role}.owner")
                require_nonempty_string(edit.get("reason"), f"{edit_role}.reason")
            except AuditError as error:
                fail("GENERATION-POLICY-INVALID", error.detail)
            if re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", edit_id) is None:
                fail("GENERATION-POLICY-INVALID", f"edit id is not kebab-case: {edit_id}")
            if edit_id in edit_ids:
                fail("GENERATION-POLICY-INVALID", f"duplicate edit id: {edit_id}")
            edit_ids.add(edit_id)
            if edit.get("kind") not in ALLOWED_EDIT_KINDS:
                fail("GENERATION-POLICY-INVALID", f"unsupported edit kind: {edit.get('kind')}")
            validate_review_assertions(edit.get("required_exact_text"), f"{edit_role}.required_exact_text")

        paths.append(path_value)
        files.append(file_policy)
    if paths != sorted(paths) or len(paths) != len(set(paths)):
        fail("GENERATION-POLICY-INVALID", "review policy files must be uniquely sorted by path")
    return baseline_commit, files


def policy_inventory(files: Sequence[Mapping[str, Any]]) -> Dict[str, str]:
    result: Dict[str, str] = {}
    for file_policy in files:
        change = str(file_policy["change"])
        if change == "renamed":
            pairs = (
                (str(file_policy["baseline_path"]), "D"),
                (str(file_policy["path"]), "A"),
            )
        else:
            pairs = ((str(file_policy["path"]), CHANGE_STATUSES[change]),)
        for path, status in pairs:
            if path in result:
                fail("GENERATION-POLICY-INVALID", f"policy path appears twice: {path}")
            result[path] = status
    return result


def cumulative_offsets(lines: Sequence[bytes]) -> List[int]:
    result = [0]
    total = 0
    for line in lines:
        total += len(line)
        result.append(total)
    return result


def shrink_changed_span(
    baseline: bytes,
    approved: bytes,
    baseline_start: int,
    baseline_end: int,
    approved_start: int,
    approved_end: int,
) -> Tuple[int, int, int, int]:
    old = baseline[baseline_start:baseline_end]
    new = approved[approved_start:approved_end]
    prefix = 0
    prefix_limit = min(len(old), len(new))
    while prefix < prefix_limit and old[prefix] == new[prefix]:
        prefix += 1
    suffix = 0
    suffix_limit = min(len(old) - prefix, len(new) - prefix)
    while suffix < suffix_limit and old[len(old) - suffix - 1] == new[len(new) - suffix - 1]:
        suffix += 1
    return (
        baseline_start + prefix,
        baseline_end - suffix,
        approved_start + prefix,
        approved_end - suffix,
    )


def stable_changed_spans(baseline: bytes, approved: bytes) -> List[Tuple[int, int, int, int]]:
    """Return deterministic line-anchored, byte-minimized replacement spans."""

    baseline_lines = baseline.splitlines(keepends=True)
    approved_lines = approved.splitlines(keepends=True)
    baseline_offsets = cumulative_offsets(baseline_lines)
    approved_offsets = cumulative_offsets(approved_lines)
    matcher = difflib.SequenceMatcher(
        None,
        baseline_lines,
        approved_lines,
        autojunk=False,
    )
    result: List[Tuple[int, int, int, int]] = []
    for tag, old_start, old_end, new_start, new_end in matcher.get_opcodes():
        if tag == "equal":
            continue
        span = shrink_changed_span(
            baseline,
            approved,
            baseline_offsets[old_start],
            baseline_offsets[old_end],
            approved_offsets[new_start],
            approved_offsets[new_end],
        )
        if span[0] == span[1] and span[2] == span[3]:
            continue
        result.append(span)
    return result


def generate_manifest_value(root: Path, policy_path: Path) -> Mapping[str, Any]:
    baseline_commit, files = load_review_policy(policy_path)
    if git_text(root, "rev-parse", "--show-object-format") != "sha1":
        fail("OBJECT-FORMAT-MISMATCH", "manifest generation requires a SHA-1 Git repository")
    resolve_commit(root, baseline_commit)
    configured_commit = parse_upstream_commit(root / DEFAULT_UPSTREAM_CONFIG)
    if configured_commit != baseline_commit:
        fail(
            "UPSTREAM-CONFIG-MISMATCH",
            f"review policy records {baseline_commit}, upstream config records {configured_commit}",
        )
    head = git_text(root, "rev-parse", "HEAD")
    if not is_ancestor(root, baseline_commit, head):
        fail("BASE-NOT-ANCESTOR", f"generation baseline {baseline_commit} is not an ancestor of {head}")
    check_dirty_tree(root, EXPECTED_SOURCE_ROOTS)
    actual_inventory = parse_raw_inventory(root, baseline_commit, head, EXPECTED_SOURCE_ROOTS)
    verify_inventory(actual_inventory, policy_inventory(files))

    entries: List[Mapping[str, Any]] = []
    for file_policy in files:
        path = str(file_policy["path"])
        baseline_path = file_policy.get("baseline_path")
        change = str(file_policy["change"])
        baseline_actual = (
            object_state(root, baseline_commit, str(baseline_path))
            if baseline_path is not None
            else None
        )
        approved_actual = object_state(root, head, path) if change != "removed" else None
        if baseline_path is not None and baseline_actual is None:
            fail("GENERATION-REVIEW-MISMATCH", f"policy baseline path is missing: {baseline_path}")
        if change != "removed" and approved_actual is None:
            fail("GENERATION-REVIEW-MISMATCH", f"policy approved path is missing: {path}")

        spans: List[Tuple[int, int, int, int]] = []
        if baseline_actual is not None and approved_actual is not None:
            spans = stable_changed_spans(baseline_actual["content"], approved_actual["content"])
        edit_policy = file_policy["edits"]
        if len(spans) != len(edit_policy):
            fail(
                "GENERATION-REVIEW-MISMATCH",
                f"{path} has {len(spans)} deterministic changed spans but policy provides {len(edit_policy)} reviewed edits; clean unrelated changes or review each span explicitly",
            )
        edits = []
        for metadata, span in zip(edit_policy, spans):
            old_start, old_end, new_start, new_end = span
            old_segment = baseline_actual["content"][old_start:old_end]
            new_segment = approved_actual["content"][new_start:new_end]
            edits.append(
                {
                    "id": metadata["id"],
                    "kind": metadata["kind"],
                    "owner": metadata["owner"],
                    "reason": metadata["reason"],
                    "baseline_span": {
                        "start": old_start,
                        "end": old_end,
                        "sha256": sha256_bytes(old_segment),
                    },
                    "approved_span": {
                        "start": new_start,
                        "end": new_end,
                        "sha256": sha256_bytes(new_segment),
                    },
                    "required_exact_text": metadata["required_exact_text"],
                }
            )
        if edits:
            verify_edits(
                baseline_actual["content"],
                approved_actual["content"],
                edits,
                path,
            )

        entries.append(
            {
                "path": path,
                "change": change,
                "classification": file_policy["classification"],
                "introduced_by": file_policy["introduced_by"],
                "reason": file_policy["reason"],
                "baseline": (
                    {key: baseline_actual[key] for key in ("path", "mode", "blob", "sha256")}
                    if baseline_actual is not None
                    else None
                ),
                "approved": (
                    {key: approved_actual[key] for key in ("path", "mode", "blob", "sha256")}
                    if approved_actual is not None
                    else None
                ),
                "edits": edits,
            }
        )

    roots = [
        {
            "path": path,
            "baseline_tree": tree_at(root, baseline_commit, path),
            "approved_tree": tree_at(root, head, path),
        }
        for path in EXPECTED_SOURCE_ROOTS
    ]
    result: Mapping[str, Any] = {
        "schema_version": 2,
        "repository": EXPECTED_REPOSITORY,
        "git_object_format": "sha1",
        "content_hash_format": "sha256",
        "upstream_config": DEFAULT_UPSTREAM_CONFIG,
        "baseline": {
            "commit": baseline_commit,
            "tree": tree_at(root, baseline_commit),
        },
        "source_roots": roots,
        "classifications": sorted(ALLOWED_CLASSIFICATIONS),
        "entries": entries,
    }
    validate_manifest_shape(result)
    return result


def verify_inventory(actual: Mapping[str, Mapping[str, str]], expected: Mapping[str, str]) -> None:
    unapproved = sorted(set(actual) - set(expected))
    if unapproved:
        rendered = ", ".join(f"{actual[path]['status']} {path}" for path in unapproved)
        fail("UNAPPROVED-PATH", f"unapproved native changes: {rendered}")
    stale = sorted(set(expected) - set(actual))
    if stale:
        rendered = ", ".join(f"{expected[path]} {path}" for path in stale)
        fail("STALE-ENTRY", f"manifest entries absent from the native diff: {rendered}")
    mismatched = sorted(path for path in expected if actual[path]["status"] != expected[path])
    if mismatched:
        rendered = ", ".join(
            f"{path}:{expected[path]}->{actual[path]['status']}" for path in mismatched
        )
        fail("STATUS-MISMATCH", f"native change statuses differ: {rendered}")


def verify_manifest_state(expected: Mapping[str, Any], actual: Optional[Mapping[str, Any]], role: str) -> None:
    if actual is None:
        fail("BLOB-MISMATCH", f"approved state is missing: {role}")
    if expected["mode"] != actual["mode"]:
        fail("MODE-MISMATCH", f"{role} mode expected {expected['mode']}, found {actual['mode']}")
    if expected["blob"] != actual["blob"]:
        fail("BLOB-MISMATCH", f"{role} blob expected {expected['blob']}, found {actual['blob']}")
    if expected["sha256"] != actual["sha256"]:
        fail(
            "CONTENT-HASH-MISMATCH",
            f"{role} SHA-256 expected {expected['sha256']}, found {actual['sha256']}",
        )


def line_column(content: bytes, offset: int) -> Tuple[int, int]:
    bounded = min(max(offset, 0), len(content))
    line = content.count(b"\n", 0, bounded) + 1
    last_newline = content.rfind(b"\n", 0, bounded)
    column = bounded + 1 if last_newline < 0 else bounded - last_newline
    return line, column


def first_difference(left: bytes, right: bytes) -> int:
    limit = min(len(left), len(right))
    for index in range(limit):
        if left[index] != right[index]:
            return index
    return limit


def verify_required_text(
    assertion: Mapping[str, Any],
    baseline_segment: bytes,
    approved_segment: bytes,
    path: str,
    edit_id: str,
) -> None:
    side = str(assertion["side"])
    segment = baseline_segment if side == "baseline" else approved_segment
    text = str(assertion["text"]).encode("utf-8")
    expected = int(assertion["count"])
    actual = segment.count(text)
    if actual == expected:
        return
    detail = (
        f"path={path}, edit={edit_id}, side={side}, expected_count={expected}, "
        f"actual_count={actual}, text={assertion['text']!r}"
    )
    if actual > expected:
        fail("SIGNATURE-DUPLICATE", detail)
    fail("SIGNATURE-MISSING", detail)


def verify_edits(
    baseline_content: bytes,
    approved_content: bytes,
    edits: Sequence[Mapping[str, Any]],
    path: str,
) -> None:
    baseline_cursor = 0
    approved_cursor = 0
    for edit in edits:
        edit_id = str(edit["id"])
        baseline_span = edit["baseline_span"]
        approved_span = edit["approved_span"]
        baseline_start = int(baseline_span["start"])
        baseline_end = int(baseline_span["end"])
        approved_start = int(approved_span["start"])
        approved_end = int(approved_span["end"])
        if baseline_end > len(baseline_content) or approved_end > len(approved_content):
            fail(
                "HUNK-RANGE-INVALID",
                f"path={path}, edit={edit_id}, baseline_size={len(baseline_content)}, approved_size={len(approved_content)}",
            )

        unchanged_baseline = baseline_content[baseline_cursor:baseline_start]
        unchanged_approved = approved_content[approved_cursor:approved_start]
        if unchanged_baseline != unchanged_approved:
            delta = first_difference(unchanged_baseline, unchanged_approved)
            baseline_offset = baseline_cursor + min(delta, len(unchanged_baseline))
            approved_offset = approved_cursor + min(delta, len(unchanged_approved))
            baseline_line, baseline_column = line_column(baseline_content, baseline_offset)
            approved_line, approved_column = line_column(approved_content, approved_offset)
            fail(
                "UNAPPROVED-CONTENT",
                f"path={path}, before_edit={edit_id}, baseline={baseline_line}:{baseline_column}, approved={approved_line}:{approved_column}",
            )

        baseline_segment = baseline_content[baseline_start:baseline_end]
        approved_segment = approved_content[approved_start:approved_end]
        actual_baseline_hash = sha256_bytes(baseline_segment)
        if actual_baseline_hash != baseline_span["sha256"]:
            fail(
                "HUNK-BASE-MISMATCH",
                f"path={path}, edit={edit_id}, expected={baseline_span['sha256']}, found={actual_baseline_hash}",
            )
        actual_approved_hash = sha256_bytes(approved_segment)
        if actual_approved_hash != approved_span["sha256"]:
            fail(
                "HUNK-APPROVED-MISMATCH",
                f"path={path}, edit={edit_id}, expected={approved_span['sha256']}, found={actual_approved_hash}",
            )
        if baseline_segment == approved_segment:
            fail("MANIFEST-INVALID", f"byte-span edit is a no-op: {path}:{edit_id}")
        for assertion in edit["required_exact_text"]:
            verify_required_text(assertion, baseline_segment, approved_segment, path, edit_id)

        baseline_cursor = baseline_end
        approved_cursor = approved_end

    unchanged_baseline = baseline_content[baseline_cursor:]
    unchanged_approved = approved_content[approved_cursor:]
    if unchanged_baseline != unchanged_approved:
        delta = first_difference(unchanged_baseline, unchanged_approved)
        baseline_offset = baseline_cursor + min(delta, len(unchanged_baseline))
        approved_offset = approved_cursor + min(delta, len(unchanged_approved))
        baseline_line, baseline_column = line_column(baseline_content, baseline_offset)
        approved_line, approved_column = line_column(approved_content, approved_offset)
        fail(
            "UNAPPROVED-CONTENT",
            f"path={path}, after_last_edit=true, baseline={baseline_line}:{baseline_column}, approved={approved_line}:{approved_column}",
        )


def verify_entry(
    root: Path,
    baseline_commit: str,
    head: str,
    entry: Mapping[str, Any],
    raw_inventory: Mapping[str, Mapping[str, str]],
) -> int:
    path = str(entry["path"])
    change = str(entry["change"])
    baseline_expected = entry.get("baseline")
    approved_expected = entry.get("approved")
    baseline_actual = None
    approved_actual = None

    if baseline_expected is not None:
        baseline_actual = object_state(root, baseline_commit, str(baseline_expected["path"]))
        verify_manifest_state(baseline_expected, baseline_actual, f"baseline:{baseline_expected['path']}")
    if approved_expected is not None:
        approved_actual = object_state(root, head, str(approved_expected["path"]))
        if approved_actual is None:
            fail("BLOB-MISMATCH", f"approved source blob is missing: {approved_expected['path']}")

    if change == "modified":
        if approved_expected["mode"] != approved_actual["mode"]:
            fail("MODE-MISMATCH", f"approved:{path} mode differs")
        verify_edits(
            baseline_actual["content"],
            approved_actual["content"],
            entry["edits"],
            path,
        )
        verify_manifest_state(approved_expected, approved_actual, f"approved:{path}")
    elif change == "added":
        verify_manifest_state(approved_expected, approved_actual, f"approved:{path}")
    elif change == "removed":
        if object_state(root, head, path) is not None:
            fail("STATUS-MISMATCH", f"removed source still exists at HEAD: {path}")
    else:
        approved_path = str(approved_expected["path"])
        if approved_expected["mode"] != approved_actual["mode"]:
            fail("MODE-MISMATCH", f"approved:{approved_path} mode differs")
        if entry["edits"]:
            verify_edits(
                baseline_actual["content"],
                approved_actual["content"],
                entry["edits"],
                approved_path,
            )
        elif baseline_actual["content"] != approved_actual["content"]:
            fail("UNAPPROVED-CONTENT", f"edited rename has no byte-span map: {path}")
        verify_manifest_state(approved_expected, approved_actual, f"approved:{approved_path}")

    paths = []
    if change == "renamed":
        paths = [str(baseline_expected["path"]), str(approved_expected["path"])]
    else:
        paths = [path]
    for raw_path in paths:
        record = raw_inventory[raw_path]
        if record["status"] in {"M", "D"} and baseline_expected is not None:
            if record["old_mode"] != baseline_expected["mode"]:
                fail("MODE-MISMATCH", f"raw baseline mode differs for {raw_path}")
        if record["status"] in {"M", "A"} and approved_expected is not None:
            if record["new_mode"] != approved_expected["mode"]:
                fail("MODE-MISMATCH", f"raw approved mode differs for {raw_path}")
    return len(entry["edits"])


def check_dirty_tree(root: Path, roots: Sequence[str]) -> None:
    output = run_git_bytes(
        root,
        ["status", "--porcelain=v2", "-z", "--untracked-files=all", "--", *roots],
    ).stdout
    if output:
        rendered = output.decode("utf-8", errors="replace").replace("\0", " | ").strip()
        fail("DIRTY-WORKTREE", f"native production roots are dirty: {rendered}")


def validate_repository(root: Path, manifest_path: Path) -> Mapping[str, Any]:
    manifest = load_manifest(manifest_path)
    baseline_commit, baseline_tree, upstream_config, roots, entries = validate_manifest_shape(manifest)
    root_paths = [str(value["path"]) for value in roots]

    object_format = git_text(root, "rev-parse", "--show-object-format")
    if object_format != "sha1":
        fail("OBJECT-FORMAT-MISMATCH", f"repository object format is {object_format}, expected sha1")
    resolve_commit(root, baseline_commit)
    actual_baseline_tree = tree_at(root, baseline_commit)
    if actual_baseline_tree != baseline_tree:
        fail("BASE-TREE-MISMATCH", f"baseline tree expected {baseline_tree}, found {actual_baseline_tree}")
    configured_commit = parse_upstream_commit(root / upstream_config)
    if configured_commit != baseline_commit:
        fail(
            "UPSTREAM-CONFIG-MISMATCH",
            f"{upstream_config} records {configured_commit}, manifest records {baseline_commit}",
        )

    for value in roots:
        path = str(value["path"])
        actual_tree = tree_at(root, baseline_commit, path)
        if actual_tree != value["baseline_tree"]:
            fail(
                "BASE-ROOT-TREE-MISMATCH",
                f"{path} baseline tree expected {value['baseline_tree']}, found {actual_tree}",
            )

    head = git_text(root, "rev-parse", "HEAD")
    if not is_ancestor(root, baseline_commit, head):
        fail("BASE-NOT-ANCESTOR", f"baseline {baseline_commit} is not an ancestor of HEAD {head}")

    raw_inventory = parse_raw_inventory(root, baseline_commit, head, root_paths)
    expected = expected_inventory(entries)
    verify_inventory(raw_inventory, expected)

    edit_count = 0
    for entry in entries:
        edit_count += verify_entry(root, baseline_commit, head, entry, raw_inventory)

    for value in roots:
        path = str(value["path"])
        actual_tree = tree_at(root, head, path)
        if actual_tree != value["approved_tree"]:
            fail(
                "APPROVED-ROOT-TREE-MISMATCH",
                f"{path} approved tree expected {value['approved_tree']}, found {actual_tree}",
            )

    check_dirty_tree(root, root_paths)
    return {
        "baseline": baseline_commit,
        "head": head,
        "root_count": len(roots),
        "entry_count": len(entries),
        "edit_count": edit_count,
    }


def run_retirement_guard(root: Path, *, self_test: bool) -> None:
    manifest = root / RETIREMENT_MANIFEST
    guard = root / RETIREMENT_GUARD
    if not manifest.is_file() or not guard.is_file():
        fail(
            "RETIREMENT-MISSING",
            f"conjunctive Increment 53g retirement inputs are missing: {manifest}, {guard}",
        )
    command = [
        sys.executable,
        str(guard),
        "--repo-root",
        str(root),
        "--manifest",
        str(manifest),
    ]
    if self_test:
        command.append("--self-test")
    result = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    if result.stdout:
        print(result.stdout.rstrip())
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        fail("RETIREMENT-FAILED", detail or f"retirement guard exited {result.returncode}")


def commit_all(root: Path, message: str) -> str:
    git_text(root, "add", "-A")
    git_text(root, "commit", "-q", "-m", message)
    return git_text(root, "rev-parse", "HEAD")


def state_for_manifest(root: Path, commit: str, path: str) -> Mapping[str, Any]:
    state = object_state(root, commit, path)
    if state is None:
        raise AssertionError(f"self-test state is missing: {commit}:{path}")
    return {key: state[key] for key in ("path", "mode", "blob", "sha256")}


def write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def expect_code(root: Path, manifest: Path, code: str, label: str) -> None:
    expected = ERROR_PREFIX + code
    try:
        validate_repository(root, manifest)
    except AuditError as error:
        if error.code != expected:
            raise AuditError(
                "SELF-TEST-FAILED",
                f"{label}: expected {expected}, received {error.code}: {error.detail}",
            )
        return
    raise AuditError("SELF-TEST-FAILED", f"{label}: expected {expected}, guard passed")


def expect_generation_code(root: Path, policy: Path, code: str, label: str) -> None:
    expected = ERROR_PREFIX + code
    try:
        generate_manifest_value(root, policy)
    except AuditError as error:
        if error.code != expected:
            raise AuditError(
                "SELF-TEST-FAILED",
                f"{label}: expected {expected}, received {error.code}: {error.detail}",
            )
        return
    raise AuditError("SELF-TEST-FAILED", f"{label}: expected {expected}, generation passed")


def clone_json(value: Mapping[str, Any]) -> Dict[str, Any]:
    return json.loads(json.dumps(value))


def run_self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="morphhdl-native-audit-v2-") as directory:
        workspace = Path(directory)
        root = workspace / "repository"
        manifest_directory = workspace / "manifests"
        root.mkdir()
        manifest_directory.mkdir()
        git_text(root, "init", "-q")
        git_text(root, "config", "user.name", "MorphHDL native audit")
        git_text(root, "config", "user.email", "audit@example.invalid")

        marker_paths = {}
        for ordinal, source_root in enumerate(EXPECTED_SOURCE_ROOTS):
            marker = root / source_root / "scala" / f"RootMarker{ordinal}.scala"
            marker.parent.mkdir(parents=True, exist_ok=True)
            marker.write_text(f"object RootMarker{ordinal}\n", encoding="utf-8")
            marker_paths[source_root] = marker.relative_to(root).as_posix()

        native_path = "core/src/main/scala/Native.scala"
        native_file = root / native_path
        baseline_content = (
            b"object Native {\n"
            b"  def width(value: Int): Int = value\n"
            b"  val untouched = 7\n"
            b"}\n"
        )
        native_file.write_bytes(baseline_content)
        baseline_commit = commit_all(root, "baseline")
        baseline_tree = tree_at(root, baseline_commit)

        insertion = b"  def width(value: ElabInt): ElabInt = value\n"
        insertion_at = baseline_content.index(b"  val untouched")
        approved_content = baseline_content[:insertion_at] + insertion + baseline_content[insertion_at:]
        native_file.write_bytes(approved_content)

        support_path = "lib/src/main/scala/TypedSupport.scala"
        support_file = root / support_path
        support_file.write_text("object TypedSupport\n", encoding="utf-8")
        config = root / DEFAULT_UPSTREAM_CONFIG
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text(
            "UPSTREAM_REPOSITORY=https://github.com/SpinalHDL/SpinalHDL.git\n"
            "UPSTREAM_BRANCH=dev\n"
            f"UPSTREAM_COMMIT={baseline_commit}\n",
            encoding="utf-8",
        )
        approved_commit = commit_all(root, "approved")

        roots = [
            {
                "path": source_root,
                "baseline_tree": tree_at(root, baseline_commit, source_root),
                "approved_tree": tree_at(root, approved_commit, source_root),
            }
            for source_root in EXPECTED_SOURCE_ROOTS
        ]
        manifest_value: Dict[str, Any] = {
            "schema_version": 2,
            "repository": EXPECTED_REPOSITORY,
            "git_object_format": "sha1",
            "content_hash_format": "sha256",
            "upstream_config": DEFAULT_UPSTREAM_CONFIG,
            "baseline": {"commit": baseline_commit, "tree": baseline_tree},
            "source_roots": roots,
            "classifications": sorted(ALLOWED_CLASSIFICATIONS),
            "entries": [
                {
                    "path": native_path,
                    "change": "modified",
                    "classification": "typed-overload",
                    "introduced_by": ["self-test"],
                    "reason": "exercise exact typed overload insertion",
                    "baseline": state_for_manifest(root, baseline_commit, native_path),
                    "approved": state_for_manifest(root, approved_commit, native_path),
                    "edits": [
                        {
                            "id": "native-width-typed-overload",
                            "kind": "overload",
                            "owner": "Native.width",
                            "reason": "self-test reviewed overload",
                            "baseline_span": {
                                "start": insertion_at,
                                "end": insertion_at,
                                "sha256": sha256_bytes(b""),
                            },
                            "approved_span": {
                                "start": insertion_at,
                                "end": insertion_at + len(insertion),
                                "sha256": sha256_bytes(insertion),
                            },
                            "required_exact_text": [
                                {
                                    "side": "approved",
                                    "text": "def width(value: ElabInt): ElabInt",
                                    "count": 1,
                                }
                            ],
                        }
                    ],
                },
                {
                    "path": support_path,
                    "change": "added",
                    "classification": "typed-support-file",
                    "introduced_by": ["self-test"],
                    "reason": "exercise whole-file support approval",
                    "baseline": None,
                    "approved": state_for_manifest(root, approved_commit, support_path),
                    "edits": [],
                },
            ],
        }

        review_policy: Dict[str, Any] = {
            "schema_version": 1,
            "repository": EXPECTED_REPOSITORY,
            "baseline_commit": baseline_commit,
            "files": [
                {
                    "path": native_path,
                    "baseline_path": native_path,
                    "change": "modified",
                    "classification": "typed-overload",
                    "introduced_by": ["self-test"],
                    "reason": "exercise exact typed overload insertion",
                    "edits": [
                        {
                            "id": "native-width-typed-overload",
                            "kind": "overload",
                            "owner": "Native.width",
                            "reason": "self-test reviewed overload",
                            "required_exact_text": [
                                {
                                    "side": "approved",
                                    "text": "def width(value: ElabInt): ElabInt",
                                    "count": 1,
                                }
                            ],
                        }
                    ],
                },
                {
                    "path": support_path,
                    "baseline_path": None,
                    "change": "added",
                    "classification": "typed-support-file",
                    "introduced_by": ["self-test"],
                    "reason": "exercise whole-file support approval",
                    "edits": [],
                },
            ],
        }
        policy_path = manifest_directory / "review-policy.json"
        write_json(policy_path, review_policy)
        generated = generate_manifest_value(root, policy_path)
        if generated != manifest_value:
            raise AuditError(
                "SELF-TEST-FAILED",
                "deterministic generator did not reproduce the reviewed manifest",
            )

        incomplete_policy = clone_json(review_policy)
        incomplete_policy["files"][0]["edits"] = []
        incomplete_policy_path = manifest_directory / "incomplete-policy.json"
        write_json(incomplete_policy_path, incomplete_policy)
        expect_generation_code(
            root,
            incomplete_policy_path,
            "GENERATION-REVIEW-MISMATCH",
            "missing reviewed hunk metadata",
        )

        manifest_path = manifest_directory / "manifest.json"
        write_json(manifest_path, manifest_value)
        validate_repository(root, manifest_path)

        narrowed = clone_json(manifest_value)
        narrowed["source_roots"] = narrowed["source_roots"][:-1]
        narrowed_path = manifest_directory / "narrowed.json"
        write_json(narrowed_path, narrowed)
        expect_code(root, narrowed_path, "ROOT-SET-MISMATCH", "root narrowing")

        stale = clone_json(manifest_value)
        stale_path_name = marker_paths["idslpayload/src/main"]
        stale_entry = {
            "path": stale_path_name,
            "change": "modified",
            "classification": "mechanical-propagation",
            "introduced_by": ["self-test"],
            "reason": "deliberately stale self-test entry",
            "baseline": state_for_manifest(root, baseline_commit, stale_path_name),
            "approved": state_for_manifest(root, approved_commit, stale_path_name),
            "edits": [
                {
                    "id": "stale-marker-edit",
                    "kind": "mechanical-propagation",
                    "owner": "RootMarker1",
                    "reason": "deliberately stale edit",
                    "baseline_span": {"start": 0, "end": 1, "sha256": sha256_bytes(b"o")},
                    "approved_span": {"start": 0, "end": 1, "sha256": sha256_bytes(b"o")},
                    "required_exact_text": [{"side": "approved", "text": "o", "count": 1}],
                }
            ],
        }
        stale["entries"].append(stale_entry)
        stale["entries"] = sorted(stale["entries"], key=lambda entry: entry["path"])
        stale_path = manifest_directory / "stale.json"
        write_json(stale_path, stale)
        expect_code(root, stale_path, "STALE-ENTRY", "stale manifest entry")

        unexpected = root / "core/src/main/scala/Unexpected.scala"
        unexpected.write_text("object Unexpected\n", encoding="utf-8")
        commit_all(root, "unapproved addition")
        expect_code(root, manifest_path, "UNAPPROVED-PATH", "unapproved committed path")
        git_text(root, "reset", "--hard", approved_commit)

        native_file.write_bytes(approved_content.replace(b"untouched = 7", b"untouched = 8"))
        commit_all(root, "outside-span mutation")
        expect_code(root, manifest_path, "UNAPPROVED-CONTENT", "outside-span mutation")
        git_text(root, "reset", "--hard", approved_commit)

        native_file.write_bytes(approved_content.replace(b"ElabInt", b"ElabLong"))
        commit_all(root, "inside-span mutation")
        expect_code(root, manifest_path, "HUNK-APPROVED-MISMATCH", "inside-span mutation")
        git_text(root, "reset", "--hard", approved_commit)

        support_file.write_text("object MutatedSupport\n", encoding="utf-8")
        commit_all(root, "added-file blob mutation")
        expect_code(root, manifest_path, "BLOB-MISMATCH", "added-file blob mutation")
        git_text(root, "reset", "--hard", approved_commit)

        native_file.write_bytes(approved_content + b"// dirty\n")
        expect_code(root, manifest_path, "DIRTY-WORKTREE", "dirty native source")
        git_text(root, "checkout", "--", native_path)

    print("Native approved-change audit self-test passed")
    print("  positive repository: 1")
    print("  exact negative diagnostics: 8")


def generate_to_output(
    root: Path,
    policy_path: Path,
    output_path: Path,
    *,
    force: bool,
) -> Mapping[str, Any]:
    if not output_path.parent.is_dir():
        fail("GENERATION-OUTPUT-INVALID", f"output parent does not exist: {output_path.parent}")
    if output_path.exists() and not force:
        fail(
            "GENERATION-OUTPUT-EXISTS",
            f"refusing to overwrite {output_path}; pass --force after reviewing the policy",
        )
    if output_path.resolve() == policy_path.resolve():
        fail("GENERATION-OUTPUT-INVALID", "review policy and generated manifest must be different files")

    value = generate_manifest_value(root, policy_path)
    serialized = (json.dumps(value, indent=2) + "\n").encode("utf-8")
    with tempfile.TemporaryDirectory(prefix="morphhdl-native-manifest-candidate-") as directory:
        candidate = Path(directory) / "native-source-preservation.json"
        candidate.write_bytes(serialized)
        validate_repository(root, candidate)
    run_retirement_guard(root, self_test=False)
    output_path.write_bytes(serialized)
    print("Generated reviewed native approved-change manifest")
    print(f"  review policy : {policy_path}")
    print(f"  output        : {output_path}")
    print(f"  manifest SHA-256: {sha256_bytes(serialized)}")
    return value


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=DEFAULT_MANIFEST)
    parser.add_argument("--repo-root")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument(
        "--generate-template",
        metavar="REVIEW_POLICY",
        help="generate schema v2 from an explicit reviewed metadata policy",
    )
    parser.add_argument("--output", help="output path required by --generate-template")
    parser.add_argument(
        "--force",
        action="store_true",
        help="permit --generate-template to replace an existing output",
    )
    arguments = parser.parse_args(argv)

    try:
        root = repository_root(arguments.repo_root)
        if arguments.self_test and arguments.generate_template:
            fail("ARGUMENTS-INVALID", "--self-test and --generate-template are mutually exclusive")
        if arguments.generate_template:
            if not arguments.output:
                fail("ARGUMENTS-INVALID", "--generate-template requires --output")
            policy_path = Path(arguments.generate_template)
            if not policy_path.is_absolute():
                policy_path = root / policy_path
            output_path = Path(arguments.output)
            if not output_path.is_absolute():
                output_path = root / output_path
            generate_to_output(
                root,
                policy_path.resolve(),
                output_path.resolve(),
                force=arguments.force,
            )
            return 0
        if arguments.output or arguments.force:
            fail("ARGUMENTS-INVALID", "--output/--force require --generate-template")
        if arguments.self_test:
            run_self_test()
            run_retirement_guard(root, self_test=True)
            return 0

        manifest_path = Path(arguments.manifest)
        if not manifest_path.is_absolute():
            manifest_path = root / manifest_path
        result = validate_repository(root, manifest_path.resolve())
        print("Native approved-change manifest is valid")
        print(f"  baseline commit : {result['baseline']}")
        print(f"  checked HEAD    : {result['head']}")
        print(f"  audited roots   : {result['root_count']}")
        print(f"  approved paths  : {result['entry_count']}")
        print(f"  reviewed edits  : {result['edit_count']}")
        run_retirement_guard(root, self_test=False)
        return 0
    except AuditError as error:
        print(f"Native approved-change audit failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
