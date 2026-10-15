#!/usr/bin/env python3
"""Require the exact uncommitted file set produced by local-enable promotion.

This is intentionally stricter than a directory-prefix allowlist. The source
promoter may change three production files, one focused test, the parent 59i
reviewer, and four new review/documentation files. Renames, staged input and any
other path are rejected before the candidate commit is created.
"""
from __future__ import annotations

import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXPECTED = {
    "docs/morphhdl/increment-59i-composite-local-enables.md",
    "morphhdl/contracts/increment-59i-local-enable-review.json",
    "morphhdl/scripts/check-increment-59i-local-enable-source-review.py",
    "morphhdl/scripts/check-increment-59i-source-review.py",
    "morphhdl/scripts/test-increment-59i-local-enable-source-review.py",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala",
    "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeLocalEnableTests.scala",
}


def paths(*args: str) -> set[str]:
    raw = subprocess.check_output(["git", *args], cwd=ROOT)
    return {item.decode("utf-8") for item in raw.split(b"\0") if item}


def main() -> None:
    staged = paths("diff", "--cached", "--name-only", "-z")
    if staged:
        raise SystemExit("promotion input unexpectedly contains staged paths: " + repr(sorted(staged)))
    tracked = paths("diff", "--name-only", "-z")
    untracked = paths("ls-files", "--others", "--exclude-standard", "-z")
    changed = tracked | untracked
    missing = EXPECTED - changed
    extra = changed - EXPECTED
    if missing or extra or len(changed) != len(EXPECTED):
        raise SystemExit(
            "invalid local-enable generated inventory; missing=" + repr(sorted(missing)) +
            "; unreviewed=" + repr(sorted(extra))
        )
    print("59i local-enable generated inventory PASS: " + str(len(changed)) + " exact paths")


if __name__ == "__main__":
    main()
