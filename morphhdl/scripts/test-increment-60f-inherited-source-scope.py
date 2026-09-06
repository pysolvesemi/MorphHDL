#!/usr/bin/env python3
"""Keep 60f qualification-only scope sealed while checking later source safely.

Fixtures use isolated Git worktrees. The current checker is exercised against
the original baseline, the exact qualified merge and deliberately changed
descendants; no fixture edits are made in the real checkout.
"""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "morphhdl/scripts/check-increment-60f-equivalence-closure.py"
DRIVER = """import importlib.util, sys
from pathlib import Path
spec = importlib.util.spec_from_file_location('scope_checker', sys.argv[2])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.source_scope(Path(sys.argv[1]))
"""


def git(root: Path, *arguments: str) -> str:
    result = subprocess.run(["git", "-C", str(root), *arguments], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=120, check=False)
    if result.returncode:
        raise RuntimeError("fixture git command failed:\n" + result.stdout)
    return result.stdout.strip()


def check(root: Path, label: str, rejection: str | None = None) -> dict:
    result = subprocess.run([sys.executable, "-c", DRIVER, str(root), str(CHECKER)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=120, check=False)
    if rejection is None:
        if result.returncode or "sealed writers/checkers and inherited native audits PASS" not in result.stdout:
            raise RuntimeError(label + " did not pass:\n" + result.stdout)
    elif not result.returncode or rejection not in result.stdout:
        raise RuntimeError(label + " did not reject for " + rejection + ":\n" + result.stdout)
    print("PASS:", label, "[" + (rejection or "accepted") + "]", flush=True)
    return {"case": label, "expected_rejection": rejection, "exit_code": result.returncode}


def main() -> None:
    spec = importlib.util.spec_from_file_location("closure_scope", CHECKER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    head = git(ROOT, "rev-parse", "HEAD")
    records = [check(ROOT, "current descendant with separately owned production changes")]
    production = "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala"
    oracle = "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala"
    cases = (
        ("original-baseline", module.BASE, None, False,
         "qualified 60f must be an ancestor of HEAD"),
        ("qualified-merge", module.QUALIFIED_60F, None, False, None),
        ("later-committed-production", module.QUALIFIED_60F, production, True,
         "unreviewed production delta"),
        ("later-untracked-production", module.QUALIFIED_60F,
         "morphhdl/src/main/scala/Increment60fLaterScopeProbe.scala", False,
         "untracked production sources"),
        ("original-committed-production", module.BASE, production, True,
         "qualified 60f must be an ancestor of HEAD"),
        ("original-untracked-production", module.BASE,
         "new-project/src/main/scala/Increment60fScopeProbe.scala", False,
         "qualified 60f must be an ancestor of HEAD"),
        ("changed-sealed-oracle", module.QUALIFIED_60F, oracle, True, "sealed writer/checker changed"),
        ("changed-signed-authority", module.QUALIFIED_60F,
         "morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignednessAnalysis.scala", True,
         "sealed oracle/authority changed"),
        ("changed-native-hook", module.QUALIFIED_60F,
         "core/src/main/scala/spinal/core/internals/VerilogBase.scala", True,
         "native signed declaration/cast hooks changed after their frozen qualification"),
        ("unapproved-native-path", module.QUALIFIED_60F,
         "core/src/main/scala/spinal/core/Increment60fUnauditedProbe.scala", True,
         "MORPH-NATIVE-AUDIT-UNAPPROVED-PATH"),
    )
    with tempfile.TemporaryDirectory(prefix="morphhdl-60f-source-scope-") as temporary:
        for label, revision, path, commit, rejection in cases:
            fixture = Path(temporary) / label
            git(ROOT, "worktree", "add", "--detach", str(fixture), revision)
            try:
                if path is not None:
                    target = fixture / path
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with target.open("a") as stream:
                        stream.write("\n// Deliberate isolated source-scope fixture mutation.\n")
                    if commit:
                        git(fixture, "add", "--", path)
                        git(fixture, "-c", "user.name=Scope guard fixture",
                            "-c", "user.email=scope-fixture@example.invalid", "commit", "--no-verify",
                            "-m", "isolated 60f inherited source-scope fixture")
                records.append(check(fixture, label, rejection))
            finally:
                git(ROOT, "worktree", "remove", "--force", str(fixture))
    if git(ROOT, "rev-parse", "HEAD") != head:
        raise RuntimeError("source-scope fixtures changed the real checkout HEAD")
    output = ROOT / "target/increment-60f/source-scope"
    output.mkdir(parents=True, exist_ok=True)
    (output / "evidence.json").write_text(json.dumps({"head": head, "cases": records}, indent=2) + "\n")
    print("PASS: two positive and nine exact negative inherited 60f source-scope cases", flush=True)


if __name__ == "__main__":
    main()
