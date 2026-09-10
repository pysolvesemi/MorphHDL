#!/usr/bin/env python3
"""Prepare the complete reviewed local-enable successor for a CI commit.

All three source-bound transformations run exactly once. The review generator is
then called through its pure helper functions, rather than its command-line main
(which would apply the first patch a second time). The successor review covers
the scalar bridge proof, composite partition/replay, and publication backend
statement inventory. Reviewers requiring exact HEAD/index/worktree identity run
only after the workflow commits these files.
"""
from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIRECTORY = Path(__file__).resolve().parent
SOURCE = DIRECTORY / "promote-reviewed.py"
PATCHERS = (
    DIRECTORY / "apply.py",
    DIRECTORY / "fix-generated.py",
    DIRECTORY / "include-statements.py",
)
BACKEND = "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala"

spec = importlib.util.spec_from_file_location("increment_59i_local_enable_promoter", SOURCE)
if spec is None or spec.loader is None:
    raise RuntimeError("cannot load 59i local-enable promoter")
promote = importlib.util.module_from_spec(spec)
spec.loader.exec_module(promote)

promote.require(all(path.is_file() for path in PATCHERS),
                "missing source-bound local-enable transformation")
promote.require(not promote.CONTRACT.exists() and not promote.CHECKER.exists() and
                not promote.CHECKER_TEST.exists(),
                "local-enable successor files already exist")
promote.require(BACKEND not in promote.PRODUCTION,
                "backend is already present in the base promotion inventory")
promote.PRODUCTION = promote.PRODUCTION + (BACKEND,)
checker_anchor = '''    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala",
)
'''
promote.require(promote.CHECKER_TEMPLATE.count(checker_anchor) == 1,
                "local-enable reviewer path anchor changed")
promote.CHECKER_TEMPLATE = promote.CHECKER_TEMPLATE.replace(
    checker_anchor,
    '''    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala",
)
''',
    1,
)

base = promote.output("git", "rev-parse", "HEAD")
for patcher in PATCHERS:
    subprocess.run([sys.executable, str(patcher)], cwd=ROOT, check=True)
promote.require((ROOT / promote.FOCUSED_TEST).is_file(),
                "local-enable focused test was not staged")
contract_sha = promote.write_contract(base)
promote.write_reviewer(base, contract_sha)
promote.compose_parent()
promote.write_doc(base)
print("59i complete local-enable reviewed successor prepared from " + base, flush=True)
