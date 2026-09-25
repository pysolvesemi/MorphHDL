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
JOIN_CONTRACT = "morphhdl/contracts/increment-59i-source-review.json"
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


def checked(root: Path, label: str, expected: str | None = None,
            timeout_seconds: int = 180) -> dict:
    result = subprocess.run([sys.executable, "-c", DRIVER, str(root), str(ROOT / CHECKER)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=timeout_seconds, check=False)
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


def current_positive_timeout(root: Path) -> int:
    # The complete 60f traversal needs the same successor-only 3600s as
    # its own inherited harness. The older 900/600s selections, all 180s
    # negatives and historical checks, and all 120s Git limits are unchanged.
    if (root / "morphhdl/contracts/increment-59i-production-successor.json").is_file():
        return 3600
    return 900 if (root / "morphhdl/contracts/increment-59i-target-integration.json").is_file() else 600


def current_negative_timeout(root: Path) -> int:
    # Current schema-successor negatives authenticate the complete joined
    # source before reaching their deliberate mutation.  Reserve the same
    # bounded rejection headroom used by the inherited 60f harness only when
    # that exact successor contract is present.  Historical/default 59h
    # negatives retain their original 180-second contract.
    if (root / "morphhdl/contracts/increment-59i-production-successor.json").is_file():
        return 600
    return 180


def main() -> None:
    head = git(ROOT, "rev-parse", "HEAD")
    # The complete current traversal can exceed 180s under audit contention.
    # Match the bounded full-positive budget; historical/mutation calls stay 180s.
    records = [checked(ROOT, "current exact 59h delta and all inherited audits",
                       timeout_seconds=current_positive_timeout(ROOT))]
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
    # A reviewed successor must reject its own mutations first. Keep the
    # independent older diagnostics for files outside that exact inventory;
    # this changes no production bytes, mutation actions or acceptance rules.
    join = getattr(register, "join_source_review", lambda root: None)(ROOT) if register is not None else None
    # New disjoint production spans are checked by the successor before the
    # older inventory check. Preserve every mutation and require its exact
    # successor diagnostic rather than accepting an arbitrary rejection.
    joined_paths = set(getattr(join, "ALL_PATHS", join.PATHS)) if join is not None else set()
    composition = (join.load_composition_review(ROOT)
                   if join is not None and hasattr(join, "load_composition_review") else None)
    if composition is not None:
        # The exact composed bytes reject before older field-span reviewers.
        # Retain the original mutations and independently replay frozen tests.
        joined_paths |= set(composition.load_contract(ROOT)["entries"])
    cases = [("unreviewed suffix " + path, path, "suffix",
              "unreviewed source change outside 59i spans" if path in joined_paths else
              "unreviewed source change outside reviewed 59g spans"
              if register is not None and path in register.PATHS else outside)
             for path in review.PATHS]
    cases += [
        ("changed reviewed owner span", runtime, "inside", "missing/changed 59h reviewed source span"),
        ("missing owner implementation", prod, "remove",
         "59i reviewed source must be a regular non-executable file" if join is not None else "59h reviewed source is missing"),
        ("removed review", CONTRACT, "remove", "59h source-review checker or contract is missing"),
        ("paired production and review mutation", prod, "paired",
         "unreviewed source change outside 59i spans" if join is not None else "59h reviewed source manifest changed"),
        ("unreviewed production root", "foreign/src/main/Unreviewed.scala", "suffix",
         "merged 59i/60g production inventory changed" if composition is not None else "untracked production sources"),
        ("staged hidden owner change", prod, "hidden-index",
         "composition HEAD/index/worktree identity changed" if composition is not None else "staged production sources"),
        ("changed native printer", "core/src/main/scala/spinal/core/internals/VerilogBase.scala", "suffix",
         "native signed declaration/cast hooks changed after their frozen qualification"),
        ("changed sealed oracle", "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala", "suffix",
         "sealed writer/checker changed"),
        ("changed inherited 59e source", "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala", "suffix",
         "unreviewed source change outside 59i spans"
         if "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala" in joined_paths
         else ("59i" if join is not None else "59g" if register is not None else "59h") + " production delta differs from the complete reviewed inventory"),
    ]
    if join is not None:
        cases += [
            ("changed inherited owner review alone", CONTRACT, "suffix", "59h reviewed source manifest changed"),
            ("removed combined successor review", JOIN_CONTRACT, "remove", "59i source-review checker or contract is missing"),
            ("changed combined successor review", JOIN_CONTRACT, "suffix", "59i reviewed source manifest changed"),
            ("paired production and combined review mutation", prod, "paired-join", "59i reviewed source manifest changed"),
        ]
    if (ROOT / "morphhdl/scripts/check-increment-62-wa08-source-overlay.py").is_file():
        # These worktree/index mutations now meet the verified outer inventory
        # first. Preserve the mutations and require that exact path diagnostic.
        overlay_paths = {entry["path"] for entry in json.loads((ROOT /
            "morphhdl/contracts/increment-62-wa08-source-overlay.json").read_text())["files"]}
        adapted = []
        for label, relative, mutation, expected in cases:
            if label == "changed native printer" and mutation == "suffix" and relative in overlay_paths:
                # Native-hook verification authenticates current overlay bytes
                # before attempting a historical projection. Preserve the
                # exact earlier rejection, including its path and nonzero exit.
                expected = "WA-08 source overlay: unreviewed production delta: current reviewed bytes differ: " + relative
            elif mutation in ("suffix", "inside") and relative in overlay_paths:
                expected = "WA-08 source overlay: unreviewed bytes cannot enter historical projection: " + relative
            elif (mutation == "hidden-index" or relative.startswith("foreign/src/main/") or
                    relative.endswith("/TypedBalancedReductionCompositeReplay.scala") or
                    (mutation == "suffix" and relative ==
                     "core/src/main/scala/spinal/core/internals/VerilogBase.scala")):
                expected = "WA-08 source overlay: staged, unstaged or untracked governed content: " + repr([relative])
            adapted.append((label, relative, mutation, expected))
        cases = adapted
    integration = (composition.integration_review(ROOT)
                   if composition is not None and hasattr(composition, "integration_review") else None)
    if integration is not None:
        # Preserve every current mutation and every frozen historical replay.
        # The reviewed parent union owns these exact first-rejection paths;
        # older contracts outside that inventory retain their diagnostics.
        integration_paths = {entry["path"] for entry in integration.contract(ROOT)["files"]}
        adapted = []
        for label, relative, mutation, expected in cases:
            if mutation in ("suffix", "paired") and relative in integration_paths and relative not in (CONTRACT, JOIN_CONTRACT):
                expected = "59i target integration: unreviewed bytes cannot enter parent projection: " + relative
            elif mutation == "hidden-index":
                expected = "59i target integration: HEAD/index identity differs"
            elif relative.startswith("foreign/src/main/"):
                expected = "59i target integration: staged, unstaged or untracked content: " + repr([relative])
            elif mutation == "suffix" and relative == "core/src/main/scala/spinal/core/internals/VerilogBase.scala":
                expected = "59i target integration: HEAD/index/worktree identity differs: " + relative
            adapted.append((label, relative, mutation, expected))
        cases = adapted
        successor = getattr(integration, "successor_review", lambda root: None)(ROOT)
        if successor is not None:
            # This branch is reached only after a successful complete current
            # source audit and authentication of the exact successor manifest.
            # Preserve every mutation and require its first owning guard.
            successor_paths = {entry["path"] for entry in successor.contract(ROOT)["files"]}
            successor_tree = successor.tree(ROOT, "HEAD")
            # The joined reviewer reaches its paths in contract order.  Once
            # the first local-enable path is restored, that reviewer must
            # authenticate the complete successor checkout before continuing.
            # Later and disjoint mutations therefore retain the stronger
            # checkout rejection; only the prefix through that exact boundary
            # can reach the path-scoped predecessor projection first.
            successor_path_rejections = set()
            if join is not None and prod in join.PATHS:
                boundary = join.PATHS.index(prod)
                successor_path_rejections = set(join.PATHS[:boundary + 1])
            adapted = []
            for label, relative, mutation, expected in cases:
                if (mutation in ("suffix", "paired", "inside") and
                        relative in successor_paths and relative in successor_path_rejections and
                        relative not in (CONTRACT, JOIN_CONTRACT)):
                    expected = "59i production successor: unreviewed bytes cannot enter predecessor projection: " + relative
                elif (mutation in ("suffix", "inside") and relative in successor_tree and
                      relative not in (CONTRACT, JOIN_CONTRACT)):
                    # Files inherited byte-for-byte from the schema predecessor
                    # are outside the successor span inventory, but remain part
                    # of its authenticated checkout.  Preserve every negative
                    # mutation and require that stronger first-owning rejection.
                    expected = "59i production successor: HEAD/index/worktree identity differs: " + relative
                elif mutation == "hidden-index":
                    expected = "59i production successor: HEAD/index identity differs"
                elif relative.startswith("foreign/src/main/"):
                    expected = "59i production successor: staged, unstaged or untracked content: " + repr([relative])
                elif mutation == "suffix" and relative == "core/src/main/scala/spinal/core/internals/VerilogBase.scala":
                    expected = "59i production successor: HEAD/index/worktree identity differs: " + relative
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
                    path.write_bytes(original + (b"\n" if relative in (CONTRACT, JOIN_CONTRACT) else
                        b"\n" + marker + b" isolated unreviewed 59h mutation\n"))
                    if mutation in ("paired", "paired-join"):
                        contract = fixture / (JOIN_CONTRACT if mutation == "paired-join" else CONTRACT)
                        contract.write_bytes(contract.read_bytes() + b"\n")
                    elif mutation == "hidden-index":
                        git(fixture, "add", "--", relative)
                        path.write_bytes(original)
                records.append(checked(fixture, label, expected,
                                       timeout_seconds=current_negative_timeout(fixture)))
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
