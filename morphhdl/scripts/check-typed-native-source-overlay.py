#!/usr/bin/env python3
"""Compatibility shim for the superseded typed native-source overlay."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path
from typing import Optional, Sequence


CANONICAL_GUARD = "morphhdl/scripts/check-native-source-preservation.py"


def repository_root(explicit: Optional[str]) -> Path:
    if explicit:
        return Path(explicit).resolve()
    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "not inside a Git repository")
    return Path(result.stdout.strip()).resolve()


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root")
    parser.add_argument(
        "--manifest",
        help="accepted for legacy callers; the canonical schema-v2 manifest is always used",
    )
    parser.add_argument("--self-test", action="store_true")
    arguments = parser.parse_args(argv)
    try:
        root = repository_root(arguments.repo_root)
    except RuntimeError as error:
        print(f"Typed native overlay compatibility shim failed: {error}", file=sys.stderr)
        return 1

    command = [
        sys.executable,
        str(root / CANONICAL_GUARD),
        "--repo-root",
        str(root),
    ]
    if arguments.self_test:
        command.append("--self-test")
    return subprocess.run(command, check=False).returncode


if __name__ == "__main__":
    sys.exit(main())
