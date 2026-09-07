#!/usr/bin/env python3
"""Exercise complete 60f source checks on exact 59c and historical worktrees.

Only temporary detached worktrees are committed. The complete reviewed source
and audit metadata are copied explicitly into the temporary control checkpoint,
allowing these controls to run before the real branch's final source/metadata
commit. Its exact source review and canonical native audit still both run.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "morphhdl/scripts/check-increment-60f-equivalence-closure.py"
QUALIFIED_60F = "5a669d32095ee722c313bd069b771e7c350a1f81"
CONTRACT = "morphhdl/contracts/increment-59c-source-review.json"
AUDIT_INPUTS = (
    CONTRACT,
    "morphhdl/contracts/increment-55-native-change-review.json",
    "morphhdl/contracts/native-source-preservation.json",
    "morphhdl/scripts/check-increment-59c-source-review.py",
    "morphhdl/scripts/check-increment-60e-signedness-boundaries.py",
    "morphhdl/scripts/check-increment-60f-equivalence-closure.py",
    "morphhdl/scripts/test-increment-59b-inherited-source-scope.py",
    "morphhdl/scripts/test-increment-59c-inherited-source-scope.py",
    "morphhdl/scripts/test-increment-59d-inherited-60f-scope.py",
    "morphhdl/scripts/test-increment-59f-source-scope.py",
)
DRIVER = """import importlib.util, sys
from pathlib import Path
spec = importlib.util.spec_from_file_location('closure_scope', sys.argv[2])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.source_scope(Path(sys.argv[1]))
"""


def git(root: Path, *arguments: str) -> str:
    result = subprocess.run(["git", "-C", str(root), *arguments], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=120, check=False)
    if result.returncode:
        raise RuntimeError("source-scope fixture git command failed:\n" + result.stdout)
    return result.stdout.strip()


def commit(root: Path, *paths: str) -> None:
    git(root, "add", "-f", "--", *paths)
    git(root, "-c", "user.name=59c source-scope fixture",
        "-c", "user.email=source-scope@example.invalid", "commit", "--no-verify", "--allow-empty",
        "-m", "isolated 59c source-scope control")


def checked(root: Path, label: str, expected: str | None = None) -> dict:
    result = subprocess.run([sys.executable, "-c", DRIVER, str(root), str(CHECKER)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=120, check=False)
    if expected is None:
        if result.returncode or "inherited native audits PASS" not in result.stdout:
            raise RuntimeError(label + " did not pass its complete source checks:\n" + result.stdout)
    elif not result.returncode or expected not in result.stdout:
        raise RuntimeError(label + " did not reject for " + expected + ":\n" + result.stdout)
    print("PASS:", label, "[" + (expected or "accepted") + "]", flush=True)
    named = (root / "morphhdl/src/main/scala/morphhdl/MorphNamedFieldVectors.scala").is_file()
    return {"case": label, "exit_code": result.returncode, "expected_rejection": expected,
            "source_head": git(root, "rev-parse", "HEAD"),
            "scope": "current-59c" if named else "historical-qualified-source"}


def frozen_inherited_fixture(root: Path, relative: str, output_relative: str,
                             current_checks, marker: str) -> None:
    """Keep immutable historical controls separate from current-source checks."""
    head = git(root, "rev-parse", "HEAD")
    current = current_checks()
    baseline = json.loads((root / CONTRACT).read_text())["base"]
    output = root / output_relative
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="morphhdl-59c-frozen-scope-") as directory:
        historical = Path(directory) / "qualified-siblings"
        git(root, "worktree", "add", "--detach", str(historical), baseline)
        try:
            result = subprocess.run([sys.executable, relative], cwd=historical, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    timeout=900, check=False)
            (output / "historical-contract-controls.log").write_text(result.stdout)
            if result.returncode or marker not in result.stdout:
                raise RuntimeError("unchanged historical source controls failed:\n" + result.stdout)
            evidence = json.loads((historical / output_relative / "evidence.json").read_text())
            if evidence["head"] != baseline:
                raise RuntimeError("historical controls do not identify their exact qualified tree")
        finally:
            git(root, "worktree", "remove", "--force", str(historical))
    if git(root, "rev-parse", "HEAD") != head:
        raise RuntimeError("historical source controls changed the actual checkout HEAD")
    (output / "evidence.json").write_text(json.dumps({
        "head": head,
        "current_source_cases": current,
        "historical_contract_controls": evidence,
        "scope": "Current complete source audits plus unchanged historical contract controls; historical mutations do not qualify the current 59c delta.",
    }, indent=2) + "\n")
    print("PASS: current complete source audits; separately scoped historical contract controls", flush=True)
    print(result.stdout, end="", flush=True)


def main() -> None:
    head = git(ROOT, "rev-parse", "HEAD")
    records = [checked(ROOT, "current 59c production and reviewed metadata")]
    with tempfile.TemporaryDirectory(prefix="morphhdl-59c-source-scope-") as temporary:
        directory = Path(temporary)
        historical = directory / "historical-60f"
        git(ROOT, "worktree", "add", "--detach", str(historical), QUALIFIED_60F)
        try:
            records.append(checked(historical, "qualified 60f without successor exceptions"))
            path = "foreign/src/main/Unreviewed.scala"
            (historical / path).parent.mkdir(parents=True)
            (historical / path).write_text("object Unreviewed\n")
            records.append(checked(historical, "historical unreviewed new production root",
                                   "untracked production sources"))
        finally:
            git(ROOT, "worktree", "remove", "--force", str(historical))

        control = directory / "reviewed-control"
        git(ROOT, "worktree", "add", "--detach", str(control), head)
        try:
            contract = json.loads((ROOT / CONTRACT).read_text())
            reviewed_inputs = sorted(set(AUDIT_INPUTS) | {entry["path"] for entry in contract["files"]})
            for path in reviewed_inputs:
                (control / path).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / path, control / path)
            commit(control, *reviewed_inputs)
            records.append(checked(control, "isolated exact reviewed successor"))
            checkpoint = git(control, "rev-parse", "HEAD")
            # Keep the inherited 59b fixture and its six negative expectations
            # unchanged. The temporary checkpoint supplies exactly the current
            # source and audits so its nested HEAD fixtures see that same state.
            inherited = subprocess.run([sys.executable, "morphhdl/scripts/test-increment-59b-inherited-source-scope.py"],
                                       cwd=control, text=True, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, timeout=300, check=False)
            if inherited.returncode or "exact negative inherited source-scope cases" not in inherited.stdout:
                raise RuntimeError("unchanged inherited 59b source controls failed:\n" + inherited.stdout)
            output = ROOT / "target/increment-59c-source-scope"
            output.mkdir(parents=True, exist_ok=True)
            (output / "inherited-59b.log").write_text(inherited.stdout)
            shutil.copyfile(control / "target/increment-59b-source-scope/evidence.json", output / "inherited-59b.json")
            print("PASS: current 59b source audit and separately recorded frozen historical contract controls", flush=True)
            outside = "unreviewed source change outside 60e spans"
            inventory = "59c production delta differs from the complete reviewed inventory"
            branch_mutations = {
                "changed-60e-restore-branch": ("source = named.restore_source(root, path, source)", "source = source"),
                "changed-59f-restore-branch": ("source = named.restore_source(root, path, source)", "source = source"),
                "changed-60f-restore-branch": ("source_bytes = named.restore_source(root, path, source_bytes.decode()).encode()", "source_bytes = source_bytes"),
                "changed-60f-inventory-branch": ('"59c": {', '"59z": {'),
            }
            cases = (
                ("paired-named-source-and-review", "morphhdl/src/main/scala/morphhdl/MorphNamedFieldVectors.scala", "59c reviewed source manifest changed"),
                ("changed-60e-restore-branch", "morphhdl/scripts/check-increment-60e-signedness-boundaries.py", "missing/changed 59c reviewed source span"),
                ("changed-59f-restore-branch", "morphhdl/scripts/check-increment-59f-source-scope.py", "missing/changed 59c reviewed source span"),
                ("changed-60f-restore-branch", "morphhdl/scripts/check-increment-60f-equivalence-closure.py", "missing/changed 59c reviewed source span"),
                ("changed-60f-inventory-branch", "morphhdl/scripts/check-increment-60f-artifacts.py", "missing/changed 59c reviewed source span"),
                ("changed-inherited-59d-production", "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala", "59d reviewed production bytes changed"),
                ("changed-inherited-59e-production", "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala", "reviewed production source hash differs"),
                ("changed-inherited-59f-production", "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCaptureSchema.scala", "reviewed production source hash differs"),
                ("changed-core", "core/src/main/scala/spinal/core/Vec.scala", outside),
                ("changed-added-helper", "morphhdl/src/main/scala/morphhdl/MorphNamedFieldVectors.scala", outside),
                ("changed-in-span", "core/src/main/scala/spinal/core/ParameterizedVec.scala", "missing/changed 59c reviewed source span"),
                ("unreviewed-production", "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala", "native signed declaration/cast hooks changed after their frozen qualification"),
                ("new-production-root", "foreign/src/main/Unreviewed.scala", "unreviewed production delta"),
                ("untracked-production", "morphhdl/src/main/scala/Unreviewed.scala", "untracked production sources"),
                ("removed-production", "morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogFieldLayout.scala", "59c reviewed source is missing"),
                ("changed-60e-adapter", "morphhdl/scripts/check-increment-60e-signedness-boundaries.py", outside),
                ("changed-sealed-oracle", "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala", "sealed writer/checker changed"),
                ("changed-sealed-checker", "morphhdl/scripts/check-increment-60c-signed-declarations.py", "sealed writer/checker changed"),
                ("removed-review", CONTRACT, "59c source-review checker or contract is missing"),
                ("forged-added-baseline", CONTRACT, "59c source-review changed its explicit added-file inventory"),
            )
            for label, path, expected in cases:
                fixture = directory / label
                git(ROOT, "worktree", "add", "--detach", str(fixture), checkpoint)
                try:
                    target = fixture / path
                    if label in ("removed-production", "removed-review"):
                        target.unlink()
                    elif label == "forged-added-baseline":
                        contract = json.loads(target.read_text())
                        added = next(entry for entry in contract["files"] if entry["change"] == "added")
                        added["change"] = "modified"
                        target.write_text(json.dumps(contract, indent=2) + "\n")
                    elif label == "paired-named-source-and-review":
                        source = target.read_text()
                        before = "config != null && config.phasesInserters.contains(marker)"
                        if source.count(before) != 1:
                            raise RuntimeError("exact named-profile mutation target differs")
                        source = source.replace(before, "config != null", 1)
                        target.write_text(source)
                        review_path = fixture / CONTRACT
                        review = json.loads(review_path.read_text())
                        entry = next(entry for entry in review["files"] if entry["path"] == path)
                        edit = entry["edits"][0]
                        edit["after"] = source
                        edit["after_end"] = len(source.encode())
                        review_path.write_text(json.dumps(review, indent=2) + "\n")
                    elif label in branch_mutations:
                        before, after = branch_mutations[label]
                        source = target.read_text()
                        if source.count(before) != 1:
                            raise RuntimeError("exact inherited restore branch mutation target differs: " + label)
                        target.write_text(source.replace(before, after, 1))
                    elif label == "changed-in-span":
                        contract = json.loads((fixture / CONTRACT).read_text())
                        entry = next(entry for entry in contract["files"] if entry["path"] == path)
                        edit = next(edit for edit in entry["edits"] if edit["after"])
                        source = target.read_bytes()
                        index = edit["after_start"]
                        target.write_bytes(source[:index] + bytes([source[index] ^ 1]) + source[index + 1:])
                    else:
                        target.parent.mkdir(parents=True, exist_ok=True)
                        with target.open("a") as stream:
                            stream.write("\n// Deliberate unreviewed source-scope mutation.\n")
                    if label != "untracked-production":
                        commit(fixture, path, CONTRACT) if label == "paired-named-source-and-review" else commit(fixture, path)
                    records.append(checked(fixture, label, expected))
                finally:
                    git(ROOT, "worktree", "remove", "--force", str(fixture))
        finally:
            git(ROOT, "worktree", "remove", "--force", str(control))
    if git(ROOT, "rev-parse", "HEAD") != head:
        raise RuntimeError("source-scope fixtures changed the real branch HEAD")
    output = ROOT / "target/increment-59c-source-scope"
    output.mkdir(parents=True, exist_ok=True)
    (output / "evidence.json").write_text(json.dumps({"head": head, "cases": records}, indent=2) + "\n")
    current_negatives = sum(record["expected_rejection"] is not None and record["scope"] == "current-59c"
                            for record in records)
    print(f"59c current-source controls PASS: two positives and {current_negatives} exact rejections; historical 60f/59b controls separately scoped")


if __name__ == "__main__":
    main()
