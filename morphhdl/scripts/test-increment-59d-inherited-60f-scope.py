#!/usr/bin/env python3
"""Exercise exact 59d/WA-07a unions without weakening 60f's frozen scope."""
from __future__ import annotations

import json
import hashlib
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "morphhdl/scripts/check-increment-60f-equivalence-closure.py"
QUALIFIED_60F = "5a669d32095ee722c313bd069b771e7c350a1f81"
REVIEWED_59D = "4c4aa25ae02b4eb206b4d89027865d7e380e1d30"
PROFILE_AWARE_60F = "3e80cef258ddfdd6ce74819a2fbf200a8d2c5a64"
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


def commit(root: Path, *paths: str) -> None:
    git(root, "add", "--", *paths)
    git(root, "-c", "user.name=Scope guard fixture", "-c", "user.email=scope@example.invalid",
        "commit", "--no-verify", "-m", "isolated 59d/60f scope fixture")


def main() -> None:
    head = git(ROOT, "rev-parse", "HEAD")
    records = [checked(ROOT, "working reviewed descendant")]
    cases = (
        ("historical-60f", QUALIFIED_60F, None),
        ("historical-reviewed-59d", REVIEWED_59D, None),
        ("historical-profile-aware-60f", PROFILE_AWARE_60F, None),
        ("committed-reviewed-descendant", head, None),
        ("partial-wa07a-production", head, "incomplete reviewed WA-07a production delta"),
        ("ignored-untracked-production", head, "untracked production sources"),
        ("forged-59d-absorbs-wa07a", head, "59d reviewed production inventory differs"),
        ("unreviewed-production", head, "59d reviewed production inventory differs"),
        ("changed-reviewed-production", head, "59d reviewed production bytes changed"),
        ("dirty-reviewed-production", head, "59d reviewed production bytes changed"),
        ("removed-reviewed-production", head, "59d reviewed production bytes changed"),
        ("changed-independent-oracle", head, "sealed writer/checker changed"),
        ("changed-checker-restoration", head, "missing/duplicate reviewed 59d checker restoration span"),
        ("changed-checker-outside", head, "sealed writer/checker changed"),
        ("changed-signed-width-proof", head, "missing/duplicate 59d signed-width span"),
        ("changed-signed-width-outside", head, "sealed oracle/authority changed"),
        ("changed-signed-width-contract", head, "59d signed-width restoration exceeds its five exact authority seams"),
        ("changed-60d-signed-width-restoration", head, "missing/duplicate reviewed 59d checker restoration span"),
        ("changed-60e-signed-width-restoration", head, "missing/duplicate reviewed 59d checker restoration span"),
    )
    with tempfile.TemporaryDirectory(prefix="morphhdl-59d-60f-scope-") as temporary:
        for label, revision, expected in cases:
            fixture = Path(temporary) / label
            git(ROOT, "worktree", "add", "--detach", str(fixture), revision)
            try:
                path = "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala"
                if label == "partial-wa07a-production":
                    path = "morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala"
                    with (fixture / path).open("a") as stream:
                        stream.write("\n// Deliberate unreviewed WA-07a mutation.\n")
                elif label == "ignored-untracked-production":
                    path = "target/unreviewed/src/main/scala/HiddenScopeProbe.scala"
                    (fixture / path).parent.mkdir(parents=True)
                    (fixture / path).write_text("object HiddenScopeProbe\n")
                elif label == "forged-59d-absorbs-wa07a":
                    path = "morphhdl/contracts/increment-59d-production-review.json"
                    review = json.loads((fixture / path).read_text())
                    wa = "morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala"
                    review["files"].append({"path": wa,
                                            "sha256": hashlib.sha256((fixture / wa).read_bytes()).hexdigest()})
                    review["files"].sort(key=lambda entry: entry["path"])
                    (fixture / path).write_text(json.dumps(review, indent=2) + "\n")
                elif label == "unreviewed-production":
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
                elif label in ("changed-signed-width-proof", "changed-signed-width-outside"):
                    path = "morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignednessAnalysis.scala"
                    source = (fixture / path).read_text()
                    if label == "changed-signed-width-proof":
                        before = 'NativePublicationWidth.validate(value, base.component, base, "signedness width authority")'
                        if source.count(before) != 1:
                            raise RuntimeError("reviewed 59d signed-width owner seam is absent")
                        source = source.replace(before, "()")
                    else:
                        source += "\n// Deliberate authority mutation outside the exact width seams.\n"
                    (fixture / path).write_text(source)
                    # Even resealing the complete production hash must not turn
                    # a changed proof or unrelated authority byte into a seam.
                    review_path = "morphhdl/contracts/increment-59d-production-review.json"
                    review = json.loads((fixture / review_path).read_text())
                    entry = next(entry for entry in review["files"] if entry["path"] == path)
                    entry["sha256"] = hashlib.sha256(source.encode()).hexdigest()
                    (fixture / review_path).write_text(json.dumps(review, indent=2) + "\n")
                    commit(fixture, path, review_path)
                elif label == "changed-signed-width-contract":
                    path = "morphhdl/contracts/increment-59d-signed-width-edits.json"
                    contract = json.loads((fixture / path).read_text())
                    contract["edits"][0]["id"] += "-unreviewed"
                    (fixture / path).write_text(json.dumps(contract, indent=2) + "\n")
                    commit(fixture, path)
                elif label == "changed-60d-signed-width-restoration":
                    path = "morphhdl/scripts/check-increment-60d-pure-sint-casts.py"
                    source = (fixture / path).read_text()
                    before = "                current = restore(root, path, current)"
                    if source.count(before) != 1:
                        raise RuntimeError("reviewed 59d/60d signed-width restoration seam is absent")
                    (fixture / path).write_text(source.replace(before, "                current = current"))
                    commit(fixture, path)
                elif label == "changed-60e-signed-width-restoration":
                    path = "morphhdl/scripts/check-increment-60e-signedness-boundaries.py"
                    source = (fixture / path).read_text()
                    before = "        restored = restore_59d_signed_width_authority(root, path, (root / path).read_text())"
                    if source.count(before) != 1:
                        raise RuntimeError("reviewed 59d/60e signed-width restoration seam is absent")
                    (fixture / path).write_text(source.replace(before, "        restored = (root / path).read_text()"))
                    commit(fixture, path)
                records.append(checked(fixture, label, expected))
            finally:
                git(ROOT, "worktree", "remove", "--force", str(fixture))
    if git(ROOT, "rev-parse", "HEAD") != head:
        raise RuntimeError("isolated 60f scope fixtures changed the actual checkout HEAD")
    output = ROOT / "target/increment-59d-inherited-60f-scope"
    output.mkdir(parents=True, exist_ok=True)
    (output / "evidence.json").write_text(json.dumps(dict(head=head, cases=records), indent=2) + "\n")
    negatives = sum(record["expected_rejection"] is not None for record in records)
    print(f"PASS: {len(records) - negatives} positive and {negatives} exact negative inherited 60f source-scope cases")


if __name__ == "__main__":
    main()
