#!/usr/bin/env python3
"""Compatibility entry point for legacy preservation plus typed native overlay."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Optional, Sequence

DEFAULT_MANIFEST = "morphhdl/contracts/native-source-preservation.json"
OVERLAY_MANIFEST = "morphhdl/contracts/typed-native-source-overlay.json"
OVERLAY_GUARD = "morphhdl/scripts/check-typed-native-source-overlay.py"
SCRIPT_PATH = "morphhdl/scripts/check-native-source-preservation.py"


class GuardError(RuntimeError):
    pass


def run(command: Sequence[str], *, cwd: Optional[Path] = None) -> None:
    result = subprocess.run(
        list(command),
        cwd=str(cwd) if cwd is not None else None,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.stdout:
        print(result.stdout.rstrip())
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise GuardError(detail or f"command failed with status {result.returncode}")


def git(root: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(root), *arguments],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise GuardError(f"git {' '.join(arguments)} failed: {detail}")
    return result.stdout.strip()


def repository_root(explicit: Optional[str]) -> Path:
    if explicit:
        return Path(explicit).resolve()
    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise GuardError(result.stderr.strip() or "not inside a Git repository")
    return Path(result.stdout.strip()).resolve()


def json_object(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise GuardError(f"manifest is missing: {path}") from error
    except json.JSONDecodeError as error:
        raise GuardError(f"manifest is invalid JSON: {error}") from error
    if not isinstance(value, dict):
        raise GuardError(f"manifest root must be an object: {path}")
    return value


def materialize_script(root: Path, commit: str, destination: Path) -> None:
    result = subprocess.run(
        ["git", "-C", str(root), "show", f"{commit}:{SCRIPT_PATH}"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise GuardError(f"cannot load legacy guard from {commit}: {detail}")
    destination.write_bytes(result.stdout)


def legacy_commit(manifest: dict) -> str:
    approved = manifest.get("approved_state")
    if not isinstance(approved, dict):
        raise GuardError("legacy manifest approved_state must be an object")
    value = approved.get("commit")
    if not isinstance(value, str) or len(value) != 40:
        raise GuardError("legacy manifest approved_state.commit is invalid")
    return value


def typed_base(overlay: dict) -> str:
    base = overlay.get("base")
    if not isinstance(base, dict):
        raise GuardError("typed overlay base must be an object")
    value = base.get("commit")
    if not isinstance(value, str) or len(value) != 40:
        raise GuardError("typed overlay base.commit is invalid")
    return value


def run_legacy_self_test(root: Path, commit: str) -> None:
    with tempfile.TemporaryDirectory(prefix="morphhdl-legacy-guard-script-") as directory:
        script = Path(directory) / "legacy-guard.py"
        materialize_script(root, commit, script)
        run([sys.executable, str(script), "--self-test"])


def run_legacy_at_commit(root: Path, commit: str, manifest: str) -> None:
    with tempfile.TemporaryDirectory(prefix="morphhdl-legacy-guard-tree-") as directory:
        worktree = Path(directory) / "worktree"
        git(root, "worktree", "add", "--detach", str(worktree), commit)
        try:
            run(
                [
                    sys.executable,
                    str(worktree / SCRIPT_PATH),
                    "--repo-root",
                    str(worktree),
                    "--manifest",
                    manifest,
                ]
            )
        finally:
            subprocess.run(
                ["git", "-C", str(root), "worktree", "remove", "--force", str(worktree)],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )


def run_legacy_on_current(root: Path, commit: str, manifest: Path, self_test: bool) -> None:
    with tempfile.TemporaryDirectory(prefix="morphhdl-legacy-guard-script-") as directory:
        script = Path(directory) / "legacy-guard.py"
        materialize_script(root, commit, script)
        arguments = [sys.executable, str(script)]
        if self_test:
            arguments.append("--self-test")
        else:
            arguments.extend(["--repo-root", str(root), "--manifest", str(manifest)])
        run(arguments)


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=DEFAULT_MANIFEST)
    parser.add_argument("--repo-root")
    parser.add_argument("--self-test", action="store_true")
    arguments = parser.parse_args(argv)

    try:
        root = repository_root(arguments.repo_root)
        manifest_path = Path(arguments.manifest)
        if not manifest_path.is_absolute():
            manifest_path = root / manifest_path
        manifest_path = manifest_path.resolve()
        default_manifest_path = (root / DEFAULT_MANIFEST).resolve()
        legacy = json_object(manifest_path)
        overlay_path = root / OVERLAY_MANIFEST

        if manifest_path != default_manifest_path or not overlay_path.is_file():
            run_legacy_on_current(
                root,
                legacy_commit(legacy),
                manifest_path,
                arguments.self_test,
            )
            return 0

        overlay = json_object(overlay_path)
        base = typed_base(overlay)
        if arguments.self_test:
            run_legacy_self_test(root, base)
            run(
                [
                    sys.executable,
                    str(root / OVERLAY_GUARD),
                    "--self-test",
                ]
            )
        else:
            run_legacy_at_commit(root, base, DEFAULT_MANIFEST)
            run(
                [
                    sys.executable,
                    str(root / OVERLAY_GUARD),
                    "--repo-root",
                    str(root),
                    "--manifest",
                    str(overlay_path),
                ]
            )
        return 0
    except GuardError as error:
        print(f"Native-source preservation guard failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
