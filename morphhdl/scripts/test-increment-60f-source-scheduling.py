#!/usr/bin/env python3
"""Resource-budget controls; synthetic subprocesses are not source qualification."""
from __future__ import annotations
import ast
import importlib.util
import inspect
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / "morphhdl/scripts/test-increment-60f-inherited-source-scope.py"
spec = importlib.util.spec_from_file_location("source_scheduling_harness", PATH)
H = importlib.util.module_from_spec(spec)
spec.loader.exec_module(H)


class SourceSchedulingTests(unittest.TestCase):
    def test_historical_current_positive_budgets_are_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(H.current_positive_timeout(root), 600)
            self.assertEqual(H.current_negative_timeout(root), 120)
            contract = root / "morphhdl/contracts/increment-59i-target-integration.json"
            contract.parent.mkdir(parents=True)
            contract.write_text("fixture presence only")
            self.assertEqual(H.current_positive_timeout(root), 900)
            self.assertEqual(H.current_negative_timeout(root), 120)

    def test_joined_current_positive_reserves_measured_runtime_headroom(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            contract = root / "morphhdl/contracts/increment-59i-production-successor.json"
            contract.parent.mkdir(parents=True)
            contract.write_text("fixture presence only; actual audit still authenticates this")
            self.assertEqual(H.current_positive_timeout(root), 3600)
            self.assertGreater(H.current_positive_timeout(root), 2 * 1621.921)
            self.assertEqual(H.current_negative_timeout(root), 600)
            self.assertGreater(H.current_negative_timeout(root), 2 * 120)

    def test_negative_and_git_commands_keep_original_120_seconds(self):
        self.assertEqual(inspect.signature(H.check).parameters["timeout_seconds"].default, 120)
        result = subprocess.CompletedProcess([], 1, "precise-negative-diagnostic")
        with patch.object(H.subprocess, "run", return_value=result) as run:
            H.check(Path("."), "synthetic negative scheduling", "precise-negative-diagnostic")
            self.assertEqual(run.call_args.kwargs["timeout"], 120)
        with patch.object(H.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "ok")) as run:
            H.git(Path("."), "status")
            self.assertEqual(run.call_args.kwargs["timeout"], 120)

    def test_positive_timeout_reaches_subprocess_and_is_not_success(self):
        with patch.object(H.subprocess, "run", side_effect=subprocess.TimeoutExpired("fixture", 3600)) as run:
            with self.assertRaises(subprocess.TimeoutExpired):
                H.check(Path("."), "synthetic timeout", timeout_seconds=3600)
            self.assertEqual(run.call_args.kwargs["timeout"], 3600)

    def test_original_seventeen_fixture_cases_plus_current_positive_retained(self):
        tree = ast.parse(PATH.read_text())
        main = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "main")
        cases = next(n.value for n in ast.walk(main) if isinstance(n, ast.Assign) and
            any(isinstance(t, ast.Name) and t.id == "cases" for t in n.targets))
        self.assertEqual([n.elts[0].value for n in cases.elts], [
            "original-baseline", "qualified-merge", "later-committed-production",
            "unreviewed-pass-production", "later-untracked-production", "original-committed-production",
            "original-untracked-production", "changed-sealed-oracle", "changed-signed-authority",
            "changed-native-hook", "changed-historical-emitter", "changed-committed-successor-emitter",
            "changed-uncommitted-successor-emitter", "changed-staged-successor-emitter-restored-worktree",
            "changed-committed-successor-pass-contracts", "changed-staged-successor-pass-contracts-restored-worktree",
            "unapproved-native-path"])
        checks = [n for n in ast.walk(main) if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "check"]
        self.assertEqual(len(checks), 2)
        self.assertEqual(sum(any(k.arg == "timeout_seconds" for k in n.keywords) for n in checks), 2)
        negative = next(k.value for n in checks for k in n.keywords
                        if k.arg == "timeout_seconds" and isinstance(k.value, ast.Call))
        self.assertEqual(ast.unparse(negative), "current_negative_timeout(fixture)")

    def test_qualification_workflows_retain_both_complete_audits(self):
        # These assertions protect command enrollment, not YAML parser behavior.
        for name, budget in (("increment-60f-equivalence-closure.yml", 180),
                ("increment-59i-inherited-source-qualification.yml", 240)):
            source = (ROOT / ".github/workflows" / name).read_text()
            self.assertIn("timeout-minutes: " + str(budget), source)
            self.assertIn("python3 morphhdl/scripts/check-increment-60f-equivalence-closure.py --source-only", source)
            self.assertIn("python3 morphhdl/scripts/test-increment-60f-inherited-source-scope.py", source)
            self.assertNotIn("continue-on-error: true", source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
