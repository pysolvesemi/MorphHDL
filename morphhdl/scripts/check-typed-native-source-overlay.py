#!/usr/bin/env python3
"""Compatibility entry point for the historical typed native-source overlay."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Dict, Mapping, Optional, Sequence

EXPECTED_REPOSITORY = "pysolvesemi/MorphHDL"
DEFAULT_MANIFEST = "morphhdl/contracts/typed-native-source-overlay.json"
RETIREMENT_MANIFEST = "morphhdl/contracts/increment-53g-production-retirement.contract"
RETIREMENT_GUARD = "morphhdl/scripts/check-production-retirement.py"
EXPECTED_SOURCE_ROOTS = ["core", "idslplugin", "lib"]
ALLOWED_CLASSIFICATIONS = {
    "typed-formal-or-overload",
    "typed-helper",
    "typed-mechanical-propagation",
}
CHANGE_STATUS = {"added": "A", "modified": "M", "removed": "D"}
SHA1 = re.compile(r"^[0-9a-f]{40}$")


class OverlayError(RuntimeError):
    pass


def git(root: Path, *args: str, check: bool = True) -> str:
    result = subprocess.run(
        ["git", "-C", str(root), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if check and result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise OverlayError(f"git {' '.join(args)} failed: {detail}")
    return result.stdout.strip()


def sha(value: Any, role: str) -> str:
    if not isinstance(value, str) or SHA1.fullmatch(value) is None:
        raise OverlayError(f"{role} must be one full lowercase Git SHA-1")
    return value


def clean_path(value: Any, role: str) -> str:
    if not isinstance(value, str) or not value:
        raise OverlayError(f"{role} must be a non-empty path")
    path = Path(value)
    if path.is_absolute() or value.startswith("./") or ".." in path.parts:
        raise OverlayError(f"{role} is not a clean repository-relative path: {value}")
    if path.as_posix() != value or value.endswith("/"):
        raise OverlayError(f"{role} must use normalized POSIX spelling: {value}")
    return value


def load(path: Path) -> Mapping[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise OverlayError(f"typed native overlay manifest is missing: {path}") from error
    except json.JSONDecodeError as error:
        raise OverlayError(f"typed native overlay manifest is invalid JSON: {error}") from error
    if not isinstance(value, dict):
        raise OverlayError("typed native overlay manifest root must be an object")
    return value


def name_status(root: Path, base: str, head: str, roots: Sequence[str]) -> Dict[str, str]:
    output = git(
        root,
        "diff",
        "--name-status",
        "--no-renames",
        base,
        head,
        "--",
        *roots,
    )
    result: Dict[str, str] = {}
    for line in output.splitlines():
        if not line:
            continue
        fields = line.split("\t")
        if len(fields) != 2 or fields[0] not in {"A", "M", "D"}:
            raise OverlayError(f"unsupported native overlay diff record: {line}")
        status, path = fields
        if path in result:
            raise OverlayError(f"duplicate native overlay path: {path}")
        result[path] = status
    return result


def validate(root: Path, manifest_path: Path) -> None:
    manifest = load(manifest_path)
    if manifest.get("schema_version") != 1:
        raise OverlayError("schema_version must be 1")
    if manifest.get("repository") != EXPECTED_REPOSITORY:
        raise OverlayError(f"repository must be {EXPECTED_REPOSITORY}")
    if manifest.get("hash_format") != "git-sha1":
        raise OverlayError("hash_format must be git-sha1")

    base_value = manifest.get("base")
    if not isinstance(base_value, dict):
        raise OverlayError("base must be an object")
    base = sha(base_value.get("commit"), "base.commit")
    base_tree = sha(base_value.get("tree"), "base.tree")
    if git(root, "rev-parse", "--verify", f"{base}^{{commit}}") != base:
        raise OverlayError("base.commit must resolve to itself")
    if git(root, "rev-parse", f"{base}^{{tree}}") != base_tree:
        raise OverlayError("base.tree does not match base.commit")

    head = git(root, "rev-parse", "HEAD")
    ancestry = subprocess.run(
        ["git", "-C", str(root), "merge-base", "--is-ancestor", base, head],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if ancestry.returncode != 0:
        raise OverlayError("typed overlay base is not an ancestor of HEAD")

    roots_raw = manifest.get("source_roots")
    if not isinstance(roots_raw, list) or not roots_raw:
        raise OverlayError("source_roots must be a non-empty array")
    roots = [clean_path(value, f"source_roots[{index}]") for index, value in enumerate(roots_raw)]
    if roots != EXPECTED_SOURCE_ROOTS:
        raise OverlayError(
            "source_roots must contain exactly the audited native roots: "
            + ", ".join(EXPECTED_SOURCE_ROOTS)
        )

    classes = manifest.get("classifications")
    if not isinstance(classes, list) or set(classes) != ALLOWED_CLASSIFICATIONS:
        raise OverlayError("classifications do not match the approved typed categories")

    raw_entries = manifest.get("entries")
    if not isinstance(raw_entries, list) or not raw_entries:
        raise OverlayError("entries must be a non-empty array")
    entries: Dict[str, Mapping[str, Any]] = {}
    ordered = []
    for index, raw in enumerate(raw_entries):
        if not isinstance(raw, dict):
            raise OverlayError(f"entries[{index}] must be an object")
        path = clean_path(raw.get("path"), f"entries[{index}].path")
        if not any(path == root_name or path.startswith(root_name + "/") for root_name in roots):
            raise OverlayError(f"overlay path lies outside native roots: {path}")
        if path in entries:
            raise OverlayError(f"duplicate overlay entry: {path}")
        change = raw.get("change")
        if change not in CHANGE_STATUS:
            raise OverlayError(f"unsupported overlay change for {path}: {change}")
        classification = raw.get("classification")
        if classification not in ALLOWED_CLASSIFICATIONS:
            raise OverlayError(f"unapproved overlay classification for {path}: {classification}")
        reason = raw.get("reason")
        if not isinstance(reason, str) or not reason.strip():
            raise OverlayError(f"overlay reason is missing for {path}")
        expected_blob = raw.get("blob")
        if change == "removed":
            if expected_blob is not None:
                raise OverlayError(f"removed overlay entry must have a null blob: {path}")
        else:
            sha(expected_blob, f"entries[{index}].blob")
        entries[path] = raw
        ordered.append(path)
    if ordered != sorted(ordered):
        raise OverlayError("overlay entries must be sorted by path")

    actual = name_status(root, base, head, roots)
    expected = {path: CHANGE_STATUS[str(entry["change"])] for path, entry in entries.items()}
    if actual != expected:
        unlisted = sorted(set(actual) - set(expected))
        stale = sorted(set(expected) - set(actual))
        mismatched = sorted(
            path for path in set(actual).intersection(expected) if actual[path] != expected[path]
        )
        raise OverlayError(
            "typed native overlay diff mismatch; "
            f"unlisted={unlisted}, stale={stale}, status_mismatch={mismatched}"
        )

    for path, entry in entries.items():
        if entry["change"] == "removed":
            result = subprocess.run(
                ["git", "-C", str(root), "cat-file", "-e", f"HEAD:{path}"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            if result.returncode == 0:
                raise OverlayError(f"removed overlay path still exists: {path}")
        else:
            actual_blob = git(root, "rev-parse", f"HEAD:{path}")
            if actual_blob != entry["blob"]:
                raise OverlayError(
                    f"typed native overlay blob mismatch for {path}: "
                    f"expected {entry['blob']}, found {actual_blob}"
                )

    dirty = git(root, "status", "--porcelain=v1", "--untracked-files=all", "--", *roots)
    if dirty:
        raise OverlayError("native overlay working tree is dirty:\n" + dirty)

    print("Typed native-source overlay is valid")
    print(f"  base commit: {base}")
    print(f"  checked HEAD: {head}")
    print(f"  approved paths: {len(entries)}")


def commit_all(root: Path, message: str) -> str:
    git(root, "add", "-A")
    git(root, "commit", "-q", "-m", message)
    return git(root, "rev-parse", "HEAD")


def expect_failure(root: Path, manifest: Path, label: str) -> None:
    try:
        validate(root, manifest)
    except OverlayError:
        return
    raise OverlayError(f"self-test expected failure: {label}")


def self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="morphhdl-typed-native-overlay-") as directory:
        root = Path(directory)
        git(root, "init", "-q")
        git(root, "config", "user.name", "MorphHDL overlay self-test")
        git(root, "config", "user.email", "overlay@example.invalid")
        core = root / "core"
        lib = root / "lib"
        core.mkdir()
        lib.mkdir()
        first = core / "A.scala"
        first.write_text("object A\n", encoding="utf-8")
        base = commit_all(root, "base")
        base_tree = git(root, "rev-parse", f"{base}^{{tree}}")
        first.write_text("object A { val typed = true }\n", encoding="utf-8")
        second = lib / "B.scala"
        second.write_text("object B\n", encoding="utf-8")
        commit_all(root, "typed overlay")
        manifest = root / "overlay.json"
        value = {
            "schema_version": 1,
            "repository": EXPECTED_REPOSITORY,
            "hash_format": "git-sha1",
            "base": {"commit": base, "tree": base_tree},
            "source_roots": EXPECTED_SOURCE_ROOTS,
            "classifications": sorted(ALLOWED_CLASSIFICATIONS),
            "entries": [
                {
                    "path": "core/A.scala",
                    "change": "modified",
                    "classification": "typed-helper",
                    "blob": git(root, "rev-parse", "HEAD:core/A.scala"),
                    "reason": "self-test modification",
                },
                {
                    "path": "lib/B.scala",
                    "change": "added",
                    "classification": "typed-mechanical-propagation",
                    "blob": git(root, "rev-parse", "HEAD:lib/B.scala"),
                    "reason": "self-test addition",
                },
            ],
        }
        manifest.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
        validate(root, manifest)

        narrowed = root / "narrowed-overlay.json"
        narrowed_value = json.loads(json.dumps(value))
        narrowed_value["source_roots"] = ["core", "idslplugin"]
        narrowed.write_text(
            json.dumps(narrowed_value, indent=2) + "\n",
            encoding="utf-8",
        )
        expect_failure(root, narrowed, "narrowed native source roots")

        unexpected = core / "Unexpected.scala"
        unexpected.write_text("object Unexpected\n", encoding="utf-8")
        commit_all(root, "unapproved")
        expect_failure(root, manifest, "unapproved committed native source")
        git(root, "reset", "--hard", "HEAD^")

        first.write_text("object A { val dirty = true }\n", encoding="utf-8")
        expect_failure(root, manifest, "dirty native source")

    print("Typed native-source overlay self-test passed")


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=DEFAULT_MANIFEST)
    parser.add_argument("--repo-root")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.repo_root:
            root = Path(args.repo_root).resolve()
        else:
            result = subprocess.run(
                ["git", "rev-parse", "--show-toplevel"],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            if result.returncode != 0:
                raise OverlayError(result.stderr.strip() or "not inside a Git repository")
            root = Path(result.stdout.strip()).resolve()

        # Increment 53g intentionally changes reviewed native typed files while
        # retiring the reconstruction path. Keep this stable command for older
        # required workflows, but reverse its contract once the closed 53g
        # manifest exists. Historical worktrees without that manifest continue
        # to validate their original positive overlay.
        retirement_manifest = root / RETIREMENT_MANIFEST
        if retirement_manifest.is_file():
            command = [
                sys.executable,
                str(root / RETIREMENT_GUARD),
                "--repo-root",
                str(root),
                "--manifest",
                str(retirement_manifest),
            ]
            if args.self_test:
                command.append("--self-test")
            return subprocess.run(command, check=False).returncode

        if args.self_test:
            self_test()
            return 0
        manifest = Path(args.manifest)
        if not manifest.is_absolute():
            manifest = root / manifest
        validate(root, manifest)
        return 0
    except OverlayError as error:
        print(f"Typed native-source overlay failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
