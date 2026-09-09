#!/usr/bin/env python3
"""CI entry point for the reviewed local-enable promotion.

Generate the successor while files are still untracked, then let the promotion
workflow commit them before invoking reviewers that intentionally require exact
HEAD/index/worktree identity.
"""
from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).resolve().parent / "promote-reviewed.py"
spec = importlib.util.spec_from_file_location("increment_59i_local_enable_promoter", SOURCE)
if spec is None or spec.loader is None:
    raise RuntimeError("cannot load 59i local-enable promoter")
promote = importlib.util.module_from_spec(spec)
spec.loader.exec_module(promote)

promote.require(promote.PATCHER.is_file(), "missing source-bound local-enable patcher")
promote.require(not promote.CONTRACT.exists() and not promote.CHECKER.exists() and
                not promote.CHECKER_TEST.exists(), "local-enable successor files already exist")
base = promote.output("git", "rev-parse", "HEAD")
subprocess.run([sys.executable, str(promote.PATCHER)], cwd=ROOT, check=True)
promote.require((ROOT / promote.FOCUSED_TEST).is_file(), "local-enable focused test was not staged")
contract_sha = promote.write_contract(base)
promote.write_reviewer(base, contract_sha)
promote.compose_parent()
promote.write_doc(base)
print("59i composite local-enable reviewed successor prepared from " + base, flush=True)
