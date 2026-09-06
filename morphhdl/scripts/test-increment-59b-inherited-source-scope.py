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
QUALIFIED_60D = "6c2d0027c36076942c03bd2a4f6d4df1b7934962"
QUALIFIED_60E = "dc8cab41cf3fd41b026ba7359f30cb596b14d015"
QUALIFIED_59B = "b0a4388e3babbc01500a620eefe6c0965e9e6343"
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
            boundary_expected: str | None = None, boundary_only: bool = False,
            fallback_only: bool = False) -> dict:
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
    if fallback_only:
        # The 60c and 60e scopes own this fallback. The independent 60d scope
        # seals cast authority and native printers, with no fallback edit.
        checkers = [(checker, marker) for checker, marker in checkers if checker != pure_checker]
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


def commit_fixture(root: Path, path: str) -> None:
    git(root, "add", "--", path)
    git(root, "-c", "user.name=Scope guard negative fixture",
        "-c", "user.email=scope-fixture@example.invalid", "commit", "--no-verify",
        "-m", "isolated negative source-scope fixture")


def main() -> None:
    head = git(ROOT, "rev-parse", "HEAD")
    records = [checked(ROOT, "combined approved 59b and frozen 60c/60d/60e source")]
    # The validated successor publisher can reject this same outside-span
    # mutation before either inherited fallback boundary is reached.
    publisher_rejection = None
    if (ROOT / "morphhdl/scripts/check-increment-59f-source-scope.py").is_file():
        publisher_rejection = "unreviewed source change outside 59f spans"
        if (ROOT / "morphhdl/contracts/increment-59d-59f-zero-edits.json").is_file():
            publisher_rejection = "unreviewed source change outside 59d/59f zero-owner span"
        if (ROOT / "morphhdl/contracts/increment-59d-59f-padding-edits.json").is_file():
            publisher_rejection = "unreviewed source change outside 59d/59f padding-owner spans"
    cases = (
        ("historical", QUALIFIED, None),
        ("historical-60d", QUALIFIED_60D, None),
        ("historical-60e", QUALIFIED_60E, None),
        ("historical-59b", QUALIFIED_59B, None),
        ("changed-hook", head, "native signed declaration/cast hooks changed after their frozen qualification"),
        ("changed-printer", head, "native signed declaration/cast hooks changed after their frozen qualification"),
        ("unapproved-path", head, "MORPH-NATIVE-AUDIT-UNAPPROVED-PATH"),
        ("dirty-extension", head, "MORPH-NATIVE-AUDIT-DIRTY-WORKTREE"),
        ("changed-boundary-printer", head, "native signed declaration/cast hooks changed after their frozen qualification"),
        ("changed-vec", head, "unreviewed source change outside 60e spans"),
    )
    if (ROOT / "morphhdl/contracts/increment-59d-width-publication-edits.json").is_file():
        cases += (
            ("changed-width-fallback-hook", head, "missing/duplicate 59d span"),
            ("changed-width-fallback-resize", head, "missing/duplicate 59d span"),
            ("changed-width-fallback-domain", head, "missing/duplicate 59d span"),
            ("changed-width-fallback-session", head, "missing/duplicate 59d span"),
            ("changed-width-fallback-single-driver", head, "missing/duplicate 59d span"),
            ("changed-width-fallback-publication-width", head, "missing/duplicate 59d span"),
            ("changed-width-fallback-width-matcher", head, "missing/duplicate 59d span"),
            ("changed-width-fallback-outside", head,
             publisher_rejection or "fallback change exceeds preserving the graph-owned declaration section"),
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
                elif label == "changed-width-fallback-hook":
                    path = "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
                    source = (fixture / path).read_text()
                    before = "ExternalParameterizedHighBit.rewrite(component, rewrittenValues)"
                    if source.count(before) != 1:
                        raise RuntimeError("reviewed 59d high-bit publication seam is missing")
                    (fixture / path).write_text(source.replace(before,
                        'ExternalParameterizedHighBit.rewrite(component, rewrittenValues + "corrupt")'))
                    commit_fixture(fixture, path)
                elif label == "changed-width-fallback-outside":
                    path = "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
                    with (fixture / path).open("a") as stream:
                        stream.write("\n// Deliberate unreviewed fallback mutation outside the width seams.\n")
                    commit_fixture(fixture, path)
                elif label == "changed-width-fallback-single-driver":
                    path = "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
                    source = (fixture / path).read_text()
                    before = "uint.hasOnlyOneStatement && (uint.head eq assignment) &&"
                    if source.count(before) != 1:
                        raise RuntimeError("reviewed 59d native result single-driver seam is missing")
                    (fixture / path).write_text(source.replace(before, "true && (uint.head eq assignment) &&"))
                    commit_fixture(fixture, path)
                elif label == "changed-width-fallback-publication-width":
                    path = "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
                    source = (fixture / path).read_text()
                    before = "(declaration, expected) => widthInference.retainedDeclarationWidthMismatch(declaration, expected)"
                    if source.count(before) != 1:
                        raise RuntimeError("reviewed 59d published resize width seam is missing")
                    (fixture / path).write_text(source.replace(before, "(declaration, expected) => None"))
                    commit_fixture(fixture, path)
                elif label == "changed-width-fallback-width-matcher":
                    path = "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
                    source = (fixture / path).read_text()
                    before = "equivalentWidthExpression(actual, captured) ||"
                    if source.count(before) != 1:
                        raise RuntimeError("reviewed 59d declaration width matcher seam is missing")
                    (fixture / path).write_text(source.replace(before, "true ||"))
                    commit_fixture(fixture, path)
                elif label == "changed-width-fallback-resize":
                    path = "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
                    source = (fixture / path).read_text()
                    before = "ExternalParameterizedNativeResize.rewrite(component, rewrittenResizes)"
                    if source.count(before) != 1:
                        raise RuntimeError("reviewed 59d native resize publication seam is missing")
                    (fixture / path).write_text(source.replace(before,
                        'ExternalParameterizedNativeResize.rewrite(component, rewrittenResizes + "corrupt")'))
                    commit_fixture(fixture, path)
                elif label == "changed-width-fallback-domain":
                    path = "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
                    source = (fixture / path).read_text()
                    before = "widthInference.provesCompleteRelation(left, right)(relation)"
                    if source.count(before) != 1:
                        raise RuntimeError("reviewed 59d width relation seam is missing")
                    (fixture / path).write_text(source.replace(before, "true"))
                    commit_fixture(fixture, path)
                elif label == "changed-width-fallback-session":
                    path = "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
                    source = (fixture / path).read_text()
                    before = "ExternalParameterizedNativeResize.withPublicationValidation(component)"
                    if source.count(before) != 1:
                        raise RuntimeError("reviewed 59d publication validation seam is missing")
                    (fixture / path).write_text(source.replace(before,
                        "ExternalParameterizedNativeResize.withPublicationValidation(null)"))
                    commit_fixture(fixture, path)
                boundary_error = None
                if label in ("changed-hook", "changed-printer"):
                    boundary_error = "unreviewed source change outside 60e spans"
                elif label == "changed-boundary-printer":
                    boundary_error = "missing/duplicate 60e span"
                elif label == "changed-width-fallback-outside":
                    boundary_error = publisher_rejection or "unreviewed source change outside 60e spans"
                records.append(checked(fixture, label, error, boundary_error,
                    label == "changed-vec", label.startswith("changed-width-fallback-")))
            finally:
                git(ROOT, "worktree", "remove", "--force", str(fixture))
    if git(ROOT, "rev-parse", "HEAD") != head:
        raise RuntimeError("source-scope fixtures changed the real checkout HEAD")
    output = ROOT / "target/increment-59b-source-scope"
    output.mkdir(parents=True, exist_ok=True)
    (output / "evidence.json").write_text(json.dumps({"head": head, "cases": records}, indent=2) + "\n")
    negatives = sum(record["expected_rejection"] is not None for record in records)
    print(f"PASS: {len(records) - negatives} positive and {negatives} exact negative inherited source-scope cases")


if __name__ == "__main__":
    main()
