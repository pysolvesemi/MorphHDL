#!/usr/bin/env python3
"""Exercise inherited source-scope reconciliation in isolated Git worktrees.

No native source change from a fixture is pushed or merged. The source checker
is loaded from the current checkout while each fixture supplies its own root.
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "morphhdl/scripts/check-increment-60c-signed-declarations.py"
QUALIFIED = "75e581592334e2e596f6e1043beb9596cc20a99b"
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


def checked(root: Path, label: str, expected: str | None = None) -> dict:
    result = subprocess.run([sys.executable, "-c", DRIVER, str(root), str(CHECKER)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=120, check=False)
    if expected is None:
        if result.returncode or "immutable oracle PASS" not in result.stdout:
            raise RuntimeError(label + " did not pass:\n" + result.stdout)
    elif not result.returncode or expected not in result.stdout:
        raise RuntimeError(label + " did not fail for " + expected + ":\n" + result.stdout)
    print("PASS:", label, "[" + (expected or "accepted") + "]")
    return {"case": label, "expected_rejection": expected, "exit_code": result.returncode}


def commit_fixture(root: Path, path: str) -> None:
    git(root, "add", "--", path)
    git(root, "-c", "user.name=Scope guard negative fixture",
        "-c", "user.email=scope-fixture@example.invalid", "commit", "--no-verify",
        "-m", "isolated negative source-scope fixture")


def main() -> None:
    head = git(ROOT, "rev-parse", "HEAD")
    records = [checked(ROOT, "combined approved 59b and frozen 60c source")]
    cases = (
        ("historical", QUALIFIED, None),
        ("changed-hook", head, "native 60c declaration hooks changed after their frozen qualification"),
        ("unapproved-path", head, "MORPH-NATIVE-AUDIT-UNAPPROVED-PATH"),
        ("dirty-extension", head, "MORPH-NATIVE-AUDIT-DIRTY-WORKTREE"),
    )
    with tempfile.TemporaryDirectory(prefix="morphhdl-59b-source-scope-") as temporary:
        for label, revision, error in cases:
            fixture = Path(temporary) / label
            git(ROOT, "worktree", "add", "--detach", str(fixture), revision)
            try:
                if label == "changed-hook":
                    path = "core/src/main/scala/spinal/core/internals/VerilogBase.scala"
                    with (fixture / path).open("a") as stream:
                        stream.write("\n// Deliberate frozen-hook change in an isolated negative fixture.\n")
                    commit_fixture(fixture, path)
                elif label == "unapproved-path":
                    path = "core/src/main/scala/spinal/core/Increment59bUnauditedProbe.scala"
                    (fixture / path).write_text("package spinal.core\nobject Increment59bUnauditedProbe\n")
                    commit_fixture(fixture, path)
                elif label == "dirty-extension":
                    path = "core/src/main/scala/spinal/core/ElabBalancedReduction.scala"
                    if not (fixture / path).is_file():
                        raise RuntimeError("the reviewed 59b native dispatcher is missing")
                    with (fixture / path).open("a") as stream:
                        stream.write("\n// Deliberate uncommitted extension mutation.\n")
                records.append(checked(fixture, label, error))
            finally:
                git(ROOT, "worktree", "remove", "--force", str(fixture))
    if git(ROOT, "rev-parse", "HEAD") != head:
        raise RuntimeError("source-scope fixtures changed the real checkout HEAD")
    output = ROOT / "target/increment-59b-source-scope"
    output.mkdir(parents=True, exist_ok=True)
    (output / "evidence.json").write_text(json.dumps({"head": head, "cases": records}, indent=2) + "\n")
    print("PASS: two positive and three exact negative inherited source-scope cases")


if __name__ == "__main__":
    main()
