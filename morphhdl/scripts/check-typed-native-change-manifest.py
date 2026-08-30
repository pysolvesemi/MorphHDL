#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "docs/morphhdl/typed-native-change-manifest.json"
DEFAULT_BASELINE = "morphhdl-baseline-2026-08-12"
NATIVE_ROOTS = ("core", "lib", "idslplugin")
ALLOWED_CLASSIFICATIONS = {
    "legacy-approved-before-typed-pivot",
    "typed-formal-or-overload",
    "typed-helper",
    "typed-mechanical-propagation",
}


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(ROOT), *args], text=True
    ).strip()


def changed_paths(baseline: str) -> list[str]:
    output = git(
        "diff", "--name-only", "--diff-filter=ACMRT", baseline, "--", *NATIVE_ROOTS
    )
    return sorted(line for line in output.splitlines() if line)


def digest(path: str) -> str:
    target = ROOT / path
    if not target.is_file():
        raise SystemExit(f"typed native manifest references missing file: {path}")
    return hashlib.sha256(target.read_bytes()).hexdigest()


def classification(path: str) -> str:
    if path == "lib/src/main/scala/spinal/lib/Stream.scala":
        return "typed-helper"
    return "legacy-approved-before-typed-pivot"


def write_manifest(baseline: str) -> None:
    files = [
        {
            "path": path,
            "sha256": digest(path),
            "classification": classification(path),
        }
        for path in changed_paths(baseline)
    ]
    payload = {
        "schema": 1,
        "baseline": baseline,
        "policy": "typed-elaboration-native-change-audit",
        "files": files,
    }
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def verify_manifest() -> None:
    if not MANIFEST.is_file():
        raise SystemExit(f"typed native manifest is missing: {MANIFEST.relative_to(ROOT)}")
    payload = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if payload.get("schema") != 1:
        raise SystemExit("typed native manifest schema must be 1")
    baseline = payload.get("baseline")
    if not isinstance(baseline, str) or not baseline:
        raise SystemExit("typed native manifest baseline is missing")
    entries = payload.get("files")
    if not isinstance(entries, list):
        raise SystemExit("typed native manifest files must be a list")

    indexed: dict[str, dict[str, str]] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            raise SystemExit("typed native manifest entry must be an object")
        path = entry.get("path")
        expected = entry.get("sha256")
        kind = entry.get("classification")
        if not isinstance(path, str) or not path:
            raise SystemExit("typed native manifest entry has no path")
        if path in indexed:
            raise SystemExit(f"duplicate typed native manifest path: {path}")
        if not any(path == root or path.startswith(root + "/") for root in NATIVE_ROOTS):
            raise SystemExit(f"manifest path is outside native roots: {path}")
        if kind not in ALLOWED_CLASSIFICATIONS:
            raise SystemExit(f"unapproved native-change classification for {path}: {kind}")
        if not isinstance(expected, str) or len(expected) != 64:
            raise SystemExit(f"invalid sha256 for {path}")
        indexed[path] = entry

    actual_paths = changed_paths(baseline)
    expected_paths = sorted(indexed)
    if actual_paths != expected_paths:
        missing = sorted(set(actual_paths) - set(expected_paths))
        stale = sorted(set(expected_paths) - set(actual_paths))
        raise SystemExit(
            "typed native manifest path mismatch; "
            f"unlisted={missing}, stale={stale}"
        )

    mismatches = [
        path for path in actual_paths if digest(path) != indexed[path]["sha256"]
    ]
    if mismatches:
        raise SystemExit(
            "typed native manifest hash mismatch: " + ", ".join(mismatches)
        )

    stream = "lib/src/main/scala/spinal/lib/Stream.scala"
    if stream not in indexed:
        raise SystemExit("typed Increment 53d must audit Stream.scala")
    if indexed[stream]["classification"] not in {
        "typed-helper", "typed-mechanical-propagation"
    }:
        raise SystemExit("Stream.scala must be classified as an approved typed change")


parser = argparse.ArgumentParser()
parser.add_argument("--write", action="store_true")
parser.add_argument("--baseline", default=DEFAULT_BASELINE)
args = parser.parse_args()
if args.write:
    write_manifest(args.baseline)
verify_manifest()
