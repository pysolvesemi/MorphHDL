#!/usr/bin/env python3
"""Compatibility entry point for the retired native AXI4 recognizer proof.

Increment 53g deliberately removes the file-specific compiler component and
runtime sidecar which supplied that proof. The stable script path now proves
their production absence; retained typed formal proofs run in the canonical
Increment 53g workflow.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path


def forwarded_arguments() -> list[str]:
    compatibility = argparse.ArgumentParser(add_help=False)
    compatibility.add_argument("--artifact-dir", type=Path)
    compatibility.add_argument("--workspace", type=Path)
    _, remaining = compatibility.parse_known_args()
    return remaining


root = Path(__file__).resolve().parents[2]
guard = root / "morphhdl/scripts/check-production-retirement.py"
os.execv(
    sys.executable,
    [sys.executable, str(guard), "--repo-root", str(root)] + forwarded_arguments(),
)
