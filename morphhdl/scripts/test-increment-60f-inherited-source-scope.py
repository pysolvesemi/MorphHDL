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


def check(root: Path, label: str, rejection: str | None = None,
          timeout_seconds: int = 120) -> dict:
    result = subprocess.run([sys.executable, "-c", DRIVER, str(root), str(CHECKER)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=timeout_seconds, check=False)
    if rejection is None:
        if result.returncode or "inherited native audits PASS" not in result.stdout:
            raise RuntimeError(label + " did not pass:\n" + result.stdout)
    elif not result.returncode or rejection not in result.stdout:
        raise RuntimeError(label + " did not reject for " + rejection + ":\n" + result.stdout)
    print("PASS:", label, "[" + (rejection or "accepted") + "]", flush=True)
    return {"case": label, "expected_rejection": rejection, "exit_code": result.returncode}


def current_positive_timeout(root: Path) -> int:
    # The complete joined 60f traversal (including its repeated profile)
    # passed in 1621.921s on the recovered 59i checkpoint. Reserve 3600s for
    # that current positive only; historical/mutation checks and fixture Git
    # commands keep their original 120s limit. No audit is omitted or cached.
    if (root / "morphhdl/contracts/increment-59i-production-successor.json").is_file():
        return 3600
    return 900 if (root / "morphhdl/contracts/increment-59i-target-integration.json").is_file() else 600


def current_negative_timeout(root: Path) -> int:
    # Schema-successor rejection checks now authenticate the complete joined
    # source before reaching some deliberate mutations.  Reserve bounded
    # headroom only for those current fixtures; historical negatives and the
    # check() default retain their original 120-second contract.
    if (root / "morphhdl/contracts/increment-59i-production-successor.json").is_file():
        return 600
    return 120


def main() -> None:
    spec = importlib.util.spec_from_file_location("closure_scope", CHECKER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    head = git(ROOT, "rev-parse", "HEAD")
    timeout = current_positive_timeout(ROOT)
    records = [check(ROOT, "current descendant with separately owned production changes",
                     timeout_seconds=timeout)]
    ternary = module.boolean_ternary_review(ROOT)
    adapter = getattr(ternary, "wa08_overlay", None)
    overlay = adapter(ROOT) if adapter is not None else None
    integration = overlay.integration_review(ROOT) if overlay is not None else None
    integration_paths = ({entry["path"] for entry in integration.contract(ROOT)["files"]}
                         if integration is not None else set())
    successor = (getattr(integration, "successor_review", lambda root: None)(ROOT)
                 if integration is not None else None)
    successor_paths = ({entry["path"] for entry in successor.contract(ROOT)["files"]}
                       if successor is not None else set())

    def changed_successor(path: str) -> str:
        # The positive audit authenticates the enclosing parent-union review.
        # Keep the original attacks and require its exact path rejection only
        # for source owned by that review. Index-only attacks remain unchanged.
        if path in successor_paths:
            return "59i production successor: unreviewed bytes cannot enter predecessor projection: " + path
        if path in integration_paths:
            return "59i target integration: unreviewed bytes cannot enter parent projection: " + path
        return "WA-08 source overlay: unreviewed production delta: current reviewed bytes differ: " + path

    def current_successor_rejection(path: str, state: str) -> str:
        contract = ROOT / "morphhdl/contracts/increment-59i-production-successor.json"
        schema = json.loads(contract.read_text()).get("schema_version", 0) if contract.is_file() else 0
        if schema >= 7:
            if state == "committed":
                return "59i production successor: sealed route tree differs from immutable source plus exact seal"
            if state == "uncommitted":
                return "59i production successor: HEAD/index/worktree identity differs: " + path
            if state == "staged":
                return "59i production successor: HEAD/index identity differs"
            raise RuntimeError("unknown current-successor fixture state: " + state)
        if state == "staged":
            return "WA-08 source overlay: HEAD/index/worktree identity differs: " + path
        return changed_successor(path)

    production = "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala"
    oracle = "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala"
    cases = (
        ("original-baseline", module.BASE, None, False,
         "qualified 60f must be an ancestor of HEAD"),
        ("qualified-merge", module.COMPLETED_60F, None, False, None),
        ("later-committed-production", module.COMPLETED_60F, production, True,
         "unreviewed production delta"),
        ("unreviewed-pass-production", module.COMPLETED_60F,
         "morphhdl-passes/src/main/scala/Increment60fUnreviewedPass.scala", True,
         "unreviewed production delta"),
        ("later-untracked-production", module.COMPLETED_60F,
         "morphhdl/src/main/scala/Increment60fLaterScopeProbe.scala", False,
         "untracked production sources"),
        ("original-committed-production", module.BASE, production, True,
         "qualified 60f must be an ancestor of HEAD"),
        ("original-untracked-production", module.BASE,
         "new-project/src/main/scala/Increment60fScopeProbe.scala", False,
         "qualified 60f must be an ancestor of HEAD"),
        ("changed-sealed-oracle", module.COMPLETED_60F, oracle, True, "sealed writer/checker changed"),
        ("changed-signed-authority", module.COMPLETED_60F,
         "morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignednessAnalysis.scala", True,
         "sealed oracle/authority changed"),
        ("changed-native-hook", module.COMPLETED_60F,
         "core/src/main/scala/spinal/core/internals/VerilogBase.scala", True,
         "native signed declaration/cast hooks changed after their frozen qualification"),
        ("changed-historical-emitter", module.COMPLETED_60F,
         "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala", True,
         "native signed declaration/cast hooks changed after their frozen qualification"),
        ("changed-committed-successor-emitter", head,
         "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala", True,
         current_successor_rejection(
             "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala", "committed")),
        ("changed-uncommitted-successor-emitter", head,
         "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala", False,
         current_successor_rejection(
             "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala", "uncommitted")),
        ("changed-staged-successor-emitter-restored-worktree", head,
         "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala", "staged",
         current_successor_rejection(
             "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala", "staged")),
        ("changed-committed-successor-pass-contracts", head,
         "morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala", True,
         current_successor_rejection(
             "morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala", "committed")),
        ("changed-staged-successor-pass-contracts-restored-worktree", head,
         "morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala", "staged",
         current_successor_rejection(
             "morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala", "staged")),
        ("unapproved-native-path", module.COMPLETED_60F,
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
                    original = target.read_bytes() if target.is_file() else None
                    with target.open("a") as stream:
                        stream.write("\n// Deliberate isolated source-scope fixture mutation.\n")
                    if commit:
                        git(fixture, "add", "--", path)
                        if commit == "staged":
                            assert original is not None
                            target.write_bytes(original)
                        else:
                            git(fixture, "-c", "user.name=Scope guard fixture",
                                "-c", "user.email=scope-fixture@example.invalid", "commit", "--no-verify",
                                "-m", "isolated 60f inherited source-scope fixture")
                records.append(check(fixture, label, rejection,
                                     timeout_seconds=current_negative_timeout(fixture)))
            finally:
                git(ROOT, "worktree", "remove", "--force", str(fixture))
    if git(ROOT, "rev-parse", "HEAD") != head:
        raise RuntimeError("source-scope fixtures changed the real checkout HEAD")
    output = ROOT / "target/increment-60f/source-scope"
    output.mkdir(parents=True, exist_ok=True)
    (output / "evidence.json").write_text(json.dumps({"head": head, "cases": records}, indent=2) + "\n")
    print("PASS: two positive and sixteen exact negative inherited 60f source-scope cases", flush=True)


if __name__ == "__main__":
    main()
