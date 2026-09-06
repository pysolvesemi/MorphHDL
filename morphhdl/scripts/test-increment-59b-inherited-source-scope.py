#!/usr/bin/env python3
"""Exercise inherited source-scope reconciliation in isolated Git worktrees.

No native source change from a fixture is pushed or merged. The source checker
is loaded from the current checkout while each fixture supplies its own root.
The original source-restoration contracts remain tested on completed 60f,
which contains the qualified combined 59b and 60c/60d/60e source;
current descendants use 60f's completed-history and current-native audit gate.
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "morphhdl/scripts/check-increment-60c-signed-declarations.py"
CURRENT_CHECKER = ROOT / "morphhdl/scripts/check-increment-60f-equivalence-closure.py"
QUALIFIED = "75e581592334e2e596f6e1043beb9596cc20a99b"
QUALIFIED_60D = "6c2d0027c36076942c03bd2a4f6d4df1b7934962"
QUALIFIED_60E = "dc8cab41cf3fd41b026ba7359f30cb596b14d015"
QUALIFIED_59B = "b0a4388e3babbc01500a620eefe6c0965e9e6343"
COMPLETED_60F = "5a669d32095ee722c313bd069b771e7c350a1f81"
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


def checked(root: Path, label: str, expected: str | None = None,
            boundary_expected: str | None = None, boundary_only: bool = False) -> dict:
    checkers = [(CHECKER, "immutable oracle PASS")]
    pure_checker = CHECKER.with_name("check-increment-60d-pure-sint-casts.py")
    if (root / "morphhdl/scripts" / pure_checker.name).is_file():
        checkers.append((pure_checker, "independent oracle scope PASS"))
    boundary_checker = CHECKER.with_name("check-increment-60e-signedness-boundaries.py")
    if (root / "morphhdl/scripts" / boundary_checker.name).is_file():
        checkers.append((boundary_checker, "generic boundaries PASS"))
    if boundary_only:
        checkers = [(checker, marker) for checker, marker in checkers if checker == boundary_checker]
        if not checkers:
            raise RuntimeError("boundary mutation fixture has no 60e checker")
    evidence = []
    # Check each inherited gate independently. An early 60c rejection must not
    # hide a weakened or broken 60d guard on the same negative fixture.
    for checker, marker in checkers:
        rejection = boundary_expected if checker == boundary_checker and boundary_expected else expected
        result = subprocess.run([sys.executable, "-c", DRIVER, str(root), str(checker)],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                timeout=120, check=False)
        if rejection is None:
            if result.returncode or marker not in result.stdout:
                raise RuntimeError(label + " " + checker.name + " did not pass:\n" + result.stdout)
        elif not result.returncode or rejection not in result.stdout:
            raise RuntimeError(label + " " + checker.name + " did not fail for " + rejection + ":\n" + result.stdout)
        evidence.append({"checker": checker.name, "exit_code": result.returncode,
                         "expected_rejection": rejection})
    print("PASS:", label, "[" + (expected or "accepted") + "]")
    return {"case": label, "expected_rejection": expected, "checks": evidence}


def checked_current(root: Path, label: str, expected: str | None = None) -> dict:
    """Current native changes use the audited descendant contract, not old Vec spans."""
    result = subprocess.run([sys.executable, "-c", DRIVER, str(root), str(CURRENT_CHECKER)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=120, check=False)
    marker = "sealed current oracles and current native audits PASS"
    if expected is None:
        if result.returncode or marker not in result.stdout:
            raise RuntimeError(label + " current inherited gate did not pass:\n" + result.stdout)
    elif not result.returncode or expected not in result.stdout:
        raise RuntimeError(label + " current inherited gate did not fail for " + expected +
                           ":\n" + result.stdout)
    print("PASS:", label, "[" + (expected or "accepted") + "]")
    return {"case": label, "expected_rejection": expected,
            "checks": [{"checker": CURRENT_CHECKER.name, "exit_code": result.returncode,
                        "expected_rejection": expected}]}


def commit_fixture(root: Path, path: str) -> None:
    git(root, "add", "--", path)
    git(root, "-c", "user.name=Scope guard negative fixture",
        "-c", "user.email=scope-fixture@example.invalid", "commit", "--no-verify",
        "-m", "isolated negative source-scope fixture")


def main() -> None:
    head = git(ROOT, "rev-parse", "HEAD")
    records = [checked_current(ROOT, "current descendant with qualified history and approved native source")]
    # Each original exact-span negative remains anchored to the complete tree
    # it qualified. In particular, a later reviewed Vec implementation must not
    # turn the original changed-Vec negative into a permanent current-source seal.
    cases = (
        ("historical", QUALIFIED, None, False),
        ("historical-60d", QUALIFIED_60D, None, False),
        ("historical-60e", QUALIFIED_60E, None, False),
        ("historical-59b", QUALIFIED_59B, None, False),
        ("historical-combined-60f", COMPLETED_60F, None, False),
        ("changed-hook", COMPLETED_60F,
         "native signed declaration/cast hooks changed after their frozen qualification", False),
        ("changed-printer", COMPLETED_60F,
         "native signed declaration/cast hooks changed after their frozen qualification", False),
        ("unapproved-path", COMPLETED_60F, "MORPH-NATIVE-AUDIT-UNAPPROVED-PATH", False),
        ("dirty-extension", COMPLETED_60F, "MORPH-NATIVE-AUDIT-DIRTY-WORKTREE", False),
        ("changed-boundary-printer", COMPLETED_60F,
         "native signed declaration/cast hooks changed after their frozen qualification", False),
        ("changed-vec", COMPLETED_60F, "unreviewed source change outside 60e spans", False),
        ("changed-hook", head,
         "native signed declaration/cast hooks changed after their frozen qualification", True),
        ("changed-printer", head,
         "native signed declaration/cast hooks changed after their frozen qualification", True),
        ("unapproved-path", head, "MORPH-NATIVE-AUDIT-UNAPPROVED-PATH", True),
        ("dirty-extension", head, "unstaged production sources", True),
        ("changed-boundary-printer", head,
         "native signed declaration/cast hooks changed after their frozen qualification", True),
    )
    with tempfile.TemporaryDirectory(prefix="morphhdl-59b-source-scope-") as temporary:
        for label, revision, error, current in cases:
            case_label = ("current-" if current else "") + label
            fixture = Path(temporary) / case_label
            git(ROOT, "worktree", "add", "--detach", str(fixture), revision)
            try:
                if label == "changed-hook":
                    path = "core/src/main/scala/spinal/core/internals/VerilogBase.scala"
                    with (fixture / path).open("a") as stream:
                        stream.write("\n// Deliberate frozen-hook change in an isolated negative fixture.\n")
                    commit_fixture(fixture, path)
                elif label == "changed-printer":
                    path = "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala"
                    source = (fixture / path).read_text()
                    before = "    val emitted = emitExpression(operand)"
                    if source.count(before) != 1:
                        raise RuntimeError("reviewed 60d printer span is missing")
                    (fixture / path).write_text(source.replace(before, before + ' + "corrupt"'))
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
                elif label == "changed-boundary-printer":
                    path = "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala"
                    source = (fixture / path).read_text()
                    before = '    val sign = if (verilogBase.literalIsSigned(e)) "s" else ""'
                    if source.count(before) != 1:
                        raise RuntimeError("reviewed 60e literal printer span is missing")
                    (fixture / path).write_text(source.replace(before, before + ' + "corrupt"'))
                    commit_fixture(fixture, path)
                elif label == "changed-vec":
                    path = "morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogVecs.scala"
                    with (fixture / path).open("a") as stream:
                        stream.write("\n// Deliberate unreviewed combined Vec mutation.\n")
                    commit_fixture(fixture, path)
                boundary_error = None
                if label in ("changed-hook", "changed-printer"):
                    boundary_error = "unreviewed source change outside 60e spans"
                elif label == "changed-boundary-printer":
                    boundary_error = "missing/duplicate 60e span"
                if current:
                    records.append(checked_current(fixture, case_label, error))
                else:
                    records.append(checked(fixture, case_label, error, boundary_error, label == "changed-vec"))
            finally:
                git(ROOT, "worktree", "remove", "--force", str(fixture))
    if git(ROOT, "rev-parse", "HEAD") != head:
        raise RuntimeError("source-scope fixtures changed the real checkout HEAD")
    output = ROOT / "target/increment-59b-source-scope"
    output.mkdir(parents=True, exist_ok=True)
    (output / "evidence.json").write_text(json.dumps({"head": head, "cases": records}, indent=2) + "\n")
    print("PASS: six positive, six original historical negatives and five current native negatives")


if __name__ == "__main__":
    main()
