#!/usr/bin/env python3
"""Exercise the exact 59d descendant exception to 60f's frozen scope."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "morphhdl/scripts/check-increment-60f-equivalence-closure.py"
QUALIFIED_60F = "5a669d32095ee722c313bd069b771e7c350a1f81"
DRIVER = """import importlib.util, sys
from pathlib import Path
spec = importlib.util.spec_from_file_location('closure_scope', sys.argv[2])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.source_scope(Path(sys.argv[1]))
"""


def git(root: Path, *args: str) -> str:
    result = subprocess.run(["git", "-C", str(root), *args], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=120, check=False)
    if result.returncode:
        raise RuntimeError("isolated fixture git command failed:\n" + result.stdout)
    return result.stdout.strip()


def checked(root: Path, label: str, expected: str | None = None) -> dict:
    result = subprocess.run([sys.executable, "-c", DRIVER, str(root), str(CHECKER)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=120, check=False)
    if expected is None:
        if result.returncode or "inherited native audits PASS" not in result.stdout:
            raise RuntimeError(label + " did not pass:\n" + result.stdout)
    elif not result.returncode or expected not in result.stdout:
        raise RuntimeError(label + " did not reject for " + expected + ":\n" + result.stdout)
    print("PASS:", label, "[" + (expected or "accepted") + "]", flush=True)
    return dict(case=label, expected_rejection=expected, exit_code=result.returncode)


def commit(root: Path, path: str) -> None:
    git(root, "add", "--", path)
    git(root, "-c", "user.name=Scope guard fixture", "-c", "user.email=scope@example.invalid",
        "commit", "--no-verify", "-m", "isolated 59d/60f scope fixture")


def main() -> None:
    head = git(ROOT, "rev-parse", "HEAD")
    records = [checked(ROOT, "working reviewed descendant")]
    cases = (
        ("historical-60f", QUALIFIED_60F, None),
        ("committed-59d", head, None),
        ("unreviewed-production", head, "59d reviewed production inventory differs"),
        ("changed-reviewed-production", head, "59d reviewed production bytes changed"),
        ("dirty-reviewed-production", head, "59d reviewed production bytes changed"),
        ("removed-reviewed-production", head, "59d reviewed production bytes changed"),
        ("changed-independent-oracle", head, "sealed writer/checker changed"),
        ("changed-checker-restoration", head, "missing/duplicate reviewed 59d checker restoration span"),
        ("changed-checker-outside", head, "sealed writer/checker changed"),
    )
    with tempfile.TemporaryDirectory(prefix="morphhdl-59d-60f-scope-") as temporary:
        for label, revision, expected in cases:
            fixture = Path(temporary) / label
            git(ROOT, "worktree", "add", "--detach", str(fixture), revision)
            try:
                path = "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala"
                if label == "unreviewed-production":
                    path = "morphhdl/src/main/scala/spinal/core/internals/Unreviewed59dScopeProbe.scala"
                    (fixture / path).write_text("package spinal.core.internals\nobject Unreviewed59dScopeProbe\n")
                    commit(fixture, path)
                elif label in ("changed-reviewed-production", "dirty-reviewed-production"):
                    with (fixture / path).open("a") as stream:
                        stream.write("\n// Deliberate unreviewed production mutation.\n")
                    if label == "changed-reviewed-production":
                        commit(fixture, path)
                elif label == "removed-reviewed-production":
                    (fixture / path).unlink()
                    commit(fixture, path)
                elif label == "changed-independent-oracle":
                    path = "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala"
                    with (fixture / path).open("a") as stream:
                        stream.write("\n// Deliberate independent oracle mutation.\n")
                    commit(fixture, path)
                elif label == "changed-checker-restoration":
                    path = "morphhdl/scripts/check-increment-60e-signedness-boundaries.py"
                    source = (fixture / path).read_text()
                    before = "if width_contract.is_file() and path == fallback:"
                    if source.count(before) != 1:
                        raise RuntimeError("exact 59d checker restoration seam is absent")
                    (fixture / path).write_text(source.replace(before, "if False:"))
                    commit(fixture, path)
                elif label == "changed-checker-outside":
                    path = "morphhdl/scripts/check-increment-60e-signedness-boundaries.py"
                    with (fixture / path).open("a") as stream:
                        stream.write("\n# Deliberate checker mutation outside the exact restoration seam.\n")
                    commit(fixture, path)
                records.append(checked(fixture, label, expected))
            finally:
                git(ROOT, "worktree", "remove", "--force", str(fixture))
    if git(ROOT, "rev-parse", "HEAD") != head:
        raise RuntimeError("isolated 60f scope fixtures changed the actual checkout HEAD")
    output = ROOT / "target/increment-59d-inherited-60f-scope"
    output.mkdir(parents=True, exist_ok=True)
    (output / "evidence.json").write_text(json.dumps(dict(head=head, cases=records), indent=2) + "\n")
    print("PASS: 3 positive and 7 exact negative inherited 60f source-scope cases")


if __name__ == "__main__":
    main()
