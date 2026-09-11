#!/usr/bin/env python3
"""Audit current 59h and retain unchanged 59c controls on their completed tree.

Only disposable detached worktrees are changed. Current review mutations cannot
borrow a historical PASS: each record identifies its own checked source head.
"""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = "0018da2740645e0ac0c419ded7b67c01622d2bb7"
CHECKER = "morphhdl/scripts/check-increment-60f-equivalence-closure.py"
CONTRACT = "morphhdl/contracts/increment-59h-source-review.json"
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
        raise RuntimeError("59h fixture git command failed:\n" + result.stdout)
    return result.stdout.strip()


def checked(root: Path, label: str, expected: str | None = None) -> dict:
    result = subprocess.run([sys.executable, "-c", DRIVER, str(root), str(ROOT / CHECKER)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=180, check=False)
    if expected is None:
        if result.returncode or "inherited native audits PASS" not in result.stdout:
            raise RuntimeError(label + " did not pass complete source audits:\n" + result.stdout)
    elif not result.returncode or expected not in result.stdout:
        raise RuntimeError(label + " did not reject for " + expected + ":\n" + result.stdout)
    print("PASS:", label, "[" + (expected or "accepted") + "]", flush=True)
    return {"case": label, "exit_code": result.returncode, "expected_rejection": expected,
            "source_head": git(root, "rev-parse", "HEAD")}


def frozen_inherited_fixture(root: Path, relative: str, output_relative: str,
                             current_checks, marker: str) -> None:
    """Run current audits and unchanged historical mutations with separate evidence."""
    head = git(root, "rev-parse", "HEAD")
    current = current_checks()
    output = root / output_relative
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="morphhdl-59h-frozen-scope-") as directory:
        historical = Path(directory) / "completed-59c"
        git(root, "worktree", "add", "--detach", str(historical), BASE)
        try:
            result = subprocess.run([sys.executable, relative], cwd=historical, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    timeout=900, check=False)
            (output / "historical-contract-controls.log").write_text(result.stdout)
            if result.returncode or marker not in result.stdout:
                raise RuntimeError("unchanged 59c historical controls failed:\n" + result.stdout)
            evidence = json.loads((historical / output_relative / "evidence.json").read_text())
            if evidence["head"] != BASE:
                raise RuntimeError("historical controls do not identify their exact qualified tree")
        finally:
            git(root, "worktree", "remove", "--force", str(historical))
    if git(root, "rev-parse", "HEAD") != head:
        raise RuntimeError("historical source controls changed the actual checkout HEAD")
    (output / "evidence.json").write_text(json.dumps({
        "head": head, "current_source_cases": current, "historical_contract_controls": evidence,
        "scope": "Current complete 59h source audits plus unchanged completed 59c contract controls. Historical mutations do not qualify the current 59h delta.",
    }, indent=2) + "\n")
    print("PASS: current complete 59h source audits; separately scoped unchanged 59c controls", flush=True)
    print(result.stdout, end="", flush=True)


def main() -> None:
    head = git(ROOT, "rev-parse", "HEAD")
    records = [checked(ROOT, "current exact 59h delta and all inherited audits")]
    prod = "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala"
    runtime = "morphruntime/src/main/scala/spinal/core/ParameterizedStructure.scala"
    spec = importlib.util.spec_from_file_location(
        "nested_source_review", ROOT / "morphhdl/scripts/check-increment-59h-source-review.py")
    review = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(review)
    outside = "unreviewed source change outside 59h spans"
    # Keep every original source mutation. Diagnose the first exact layer that
    # owns the changed file; independently replay frozen historical controls.
    rollout = review.rollout_scope(ROOT) if hasattr(review, "rollout_scope") else None
    register = getattr(review, "register_source_review", lambda root: None)(ROOT)
    cases = [("unreviewed suffix " + path, path, "suffix",
              "or 60g publication spans: " + path
              if rollout is not None and path in rollout.PATHS else
              "unreviewed source change outside reviewed 59g spans"
              if register is not None and path in register.PATHS else outside)
             for path in review.PATHS]
    cases += [
        ("changed reviewed owner span", runtime, "inside", "missing/changed 59h reviewed source span"),
        ("missing owner implementation", prod, "remove", "59h reviewed source is missing"),
        ("removed review", CONTRACT, "remove", "59h source-review checker or contract is missing"),
        ("paired production and review mutation", prod, "paired", "59h reviewed source manifest changed"),
        ("unreviewed production root", "foreign/src/main/Unreviewed.scala", "suffix",
         "untracked production sources"),
        ("staged hidden owner change", prod, "hidden-index", "staged production sources"),
        ("changed native printer", "core/src/main/scala/spinal/core/internals/VerilogBase.scala", "suffix",
         "native signed declaration/cast hooks changed after their frozen qualification"),
        ("changed sealed oracle", "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala", "suffix",
         "sealed writer/checker changed"),
        ("changed inherited 59e source", "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala", "suffix",
         "60g publication/serialization delta differs from the merged baseline"
         if rollout is not None else
         ("59g" if register is not None else "59h") + " production delta differs from the complete reviewed inventory"),
    ]
    if (ROOT / "morphhdl/scripts/check-increment-62-wa08-source-overlay.py").is_file():
        # These worktree/index mutations now meet the verified outer inventory
        # first. Preserve the mutations and require that exact path diagnostic.
        adapted = []
        for label, relative, mutation, expected in cases:
            if (mutation == "hidden-index" or relative.startswith("foreign/src/main/") or
                    relative.endswith("/TypedBalancedReductionCompositeReplay.scala")):
                expected = "WA-08 source overlay: staged, unstaged or untracked governed content: " + repr([relative])
            adapted.append((label, relative, mutation, expected))
        cases = adapted
    with tempfile.TemporaryDirectory(prefix="morphhdl-59h-source-scope-") as directory:
        for index, (label, relative, mutation, expected) in enumerate(cases):
            fixture = Path(directory) / ("negative-" + str(index))
            git(ROOT, "worktree", "add", "--detach", str(fixture), head)
            try:
                path = fixture / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                original = path.read_bytes() if path.exists() else b""
                if mutation == "remove":
                    path.unlink()
                elif mutation == "inside":
                    entries = json.loads((fixture / CONTRACT).read_text())["files"]
                    entry = next(entry for entry in entries if entry["path"] == relative)
                    edit = next(edit for edit in entry["edits"] if edit["after"])
                    start = edit["after_start"]
                    path.write_bytes(original[:start] + bytes([original[start] ^ 1]) + original[start + 1:])
                else:
                    marker = b"#" if path.suffix == ".py" else b"//"
                    path.write_bytes(original + b"\n" + marker + b" isolated unreviewed 59h mutation\n")
                    if mutation == "paired":
                        contract = fixture / CONTRACT
                        contract.write_bytes(contract.read_bytes() + b"\n")
                    elif mutation == "hidden-index":
                        git(fixture, "add", "--", relative)
                        path.write_bytes(original)
                records.append(checked(fixture, label, expected))
            finally:
                git(ROOT, "worktree", "remove", "--force", str(fixture))
        historical = Path(directory) / "historical-59c"
        git(ROOT, "worktree", "add", "--detach", str(historical), BASE)
        try:
            records.append(checked(historical, "completed 59c without 59h exceptions"))
            path = historical / prod
            path.write_bytes(path.read_bytes() + b"\n// original frozen 59d mutation\n")
            records.append(checked(historical, "historical 59d source remains frozen",
                                   "59d reviewed production bytes changed"))
        finally:
            git(ROOT, "worktree", "remove", "--force", str(historical))
    if git(ROOT, "rev-parse", "HEAD") != head:
        raise RuntimeError("59h source controls changed the actual checkout HEAD")
    output = ROOT / "target/increment-59h-source-scope"
    output.mkdir(parents=True, exist_ok=True)
    (output / "evidence.json").write_text(json.dumps({"head": head, "cases": records}, indent=2) + "\n")
    frozen_inherited_fixture(
        ROOT, "morphhdl/scripts/test-increment-59c-inherited-source-scope.py",
        "target/increment-59c-source-scope", lambda: records[:1], "59c current-source controls PASS")
    if rollout is not None:
        with tempfile.TemporaryDirectory(prefix="morphhdl-60g-frozen-59h-") as directory:
            historical = Path(directory) / "completed-59h"
            git(ROOT, "worktree", "add", "--detach", str(historical), rollout.BASE)
            try:
                result = subprocess.run([sys.executable,
                    "morphhdl/scripts/test-increment-59h-inherited-source-scope.py"],
                    cwd=historical, text=True, stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT, timeout=900, check=False)
                (output / "pre-rollout-59h-controls.log").write_text(result.stdout)
                if result.returncode or "59h current-source controls PASS" not in result.stdout:
                    raise RuntimeError("unchanged pre-rollout 59h controls failed:\n" + result.stdout)
                evidence = json.loads((historical / "target/increment-59h-source-scope/evidence.json").read_text())
                if evidence["head"] != rollout.BASE:
                    raise RuntimeError("pre-rollout 59h evidence has the wrong source head")
                (output / "pre-rollout-59h-controls.json").write_text(json.dumps(evidence, indent=2) + "\n")
            finally:
                git(ROOT, "worktree", "remove", "--force", str(historical))
        print("PASS: unchanged completed 59h controls independently replayed at " + rollout.BASE, flush=True)
    print(f"59h current-source controls PASS: two positives and {len(records) - 2} exact rejections; unchanged 59c historical controls separately scoped", flush=True)


if __name__ == "__main__":
    main()
