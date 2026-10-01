#!/usr/bin/env python3
"""Test audit caller budgets and fail-closed results without sleeping or Git writes.

These caller tests supplement, never replace, the real current/historical source
audits in the 59d and 59h workflows. External execution and fixture orchestration
are mocked; the actual audit wrappers and entry-point budget choices execute.
"""
from __future__ import annotations

import ast
import contextlib
import importlib.util
import io
import subprocess
import sys
import types
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
CASES = (
    ("test-increment-59b-inherited-source-scope.py", "checked_current"),
    ("test-increment-59d-inherited-60f-scope.py", "checked"),
)
PASS = "inherited native audits PASS"


def load(filename: str):
    spec = importlib.util.spec_from_file_location("audit_budget_" + filename,
                                                ROOT / "morphhdl/scripts" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class AuditTimeoutTests(unittest.TestCase):
    def exercise(self, function):
        for filename, name in CASES:
            with self.subTest(caller=filename), contextlib.redirect_stdout(io.StringIO()):
                module = load(filename)
                function(module, getattr(module, name))

    def test_historical_positive_keeps_120_seconds(self):
        def check(module, caller):
            with patch.object(module.subprocess, "run", return_value=types.SimpleNamespace(
                    returncode=0, stdout=PASS)) as run:
                record = caller(ROOT, "historical positive")
                self.assertEqual(run.call_args.kwargs["timeout"], 120)
                checks = record.get("checks", [record])
                self.assertTrue(all(item["exit_code"] == 0 for item in checks))
        self.exercise(check)

    def test_expected_mutation_keeps_120_seconds(self):
        def check(module, caller):
            with patch.object(module.subprocess, "run", return_value=types.SimpleNamespace(
                    returncode=1, stdout="exact rejection")) as run:
                record = caller(ROOT, "mutation", "exact rejection")
                self.assertEqual(run.call_args.kwargs["timeout"], 120)
                self.assertEqual(record["expected_rejection"], "exact rejection")
        self.exercise(check)

    def test_current_entrypoint_selects_600_seconds_only_for_complete_positive(self):
        def check(module, caller):
            invoked = []
            def frozen(root, relative, output, current_checks, marker):
                invoked.append((root, relative, marker))
                return current_checks()
            helper = types.SimpleNamespace(frozen_inherited_fixture=frozen)
            spec = types.SimpleNamespace(loader=types.SimpleNamespace(exec_module=lambda _: None))
            with patch.object(module.importlib.util, "spec_from_file_location", return_value=spec), \
                    patch.object(module.importlib.util, "module_from_spec", return_value=helper), \
                    patch.object(module.subprocess, "run", return_value=types.SimpleNamespace(
                        returncode=0, stdout=PASS)) as run:
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    marker = root / "morphhdl/contracts/increment-59c-source-review.json"
                    marker.parent.mkdir(parents=True)
                    marker.write_text("budget fixture only; authentication is not mocked by production")
                    with patch.object(module, "ROOT", root):
                        module.main()
                self.assertEqual(len(invoked), 1)
                self.assertEqual(run.call_count, 1)
                self.assertEqual(run.call_args.kwargs["timeout"], 600)
                self.assertEqual(run.call_args.args[0][:2], [sys.executable, "-c"])
                self.assertEqual(run.call_args.args[0][3], str(root))
                self.assertTrue(run.call_args.args[0][4].endswith(
                    "check-increment-60f-equivalence-closure.py"))
                self.assertIn("exact negative inherited", invoked[0][2])
        self.exercise(check)

    def test_positive_requires_zero_exit_and_pass_marker(self):
        def check(module, caller):
            for code, output in ((1, PASS), (0, "no marker"), (1, "no marker")):
                with patch.object(module.subprocess, "run", return_value=types.SimpleNamespace(
                        returncode=code, stdout=output)), self.assertRaises(RuntimeError):
                    caller(ROOT, "current positive", timeout_seconds=600)
        self.exercise(check)

    def test_mutation_requires_nonzero_exit_and_exact_diagnostic(self):
        def check(module, caller):
            for code, output in ((0, "exact rejection"), (1, "unrelated error"), (0, PASS)):
                with patch.object(module.subprocess, "run", return_value=types.SimpleNamespace(
                        returncode=code, stdout=output)), self.assertRaises(RuntimeError):
                    caller(ROOT, "mutation", "exact rejection")
        self.exercise(check)

    def test_timeout_is_never_accepted_as_a_pass_or_mutation(self):
        def check(module, caller):
            for expected, budget in ((None, 600), ("exact rejection", 120)):
                with patch.object(module.subprocess, "run", side_effect=subprocess.TimeoutExpired(
                        "source audit", budget)), self.assertRaises(subprocess.TimeoutExpired):
                    caller(ROOT, "timed-out audit", expected, timeout_seconds=budget)
        self.exercise(check)

    def test_git_commands_keep_120_seconds(self):
        def check(module, caller):
            with patch.object(module.subprocess, "run", return_value=types.SimpleNamespace(
                    returncode=0, stdout="commit")) as run:
                module.git(ROOT, "rev-parse", "HEAD")
                self.assertEqual(run.call_args.kwargs["timeout"], 120)
        self.exercise(check)

    def test_both_workflows_trigger_on_shared_audit_changes(self):
        for filename in ("increment-59d-widening.yml", "increment-59h-nested-owners.yml"):
            source = (ROOT / ".github/workflows" / filename).read_text()
            for relative in ("morphhdl/scripts/test-increment-59b-inherited-source-scope.py",
                             "morphhdl/scripts/test-inherited-source-audit-timeouts.py"):
                with self.subTest(workflow=filename, path=relative):
                    self.assertEqual(source.count("      - '" + relative + "'"), 2)
            self.assertIn("python3 morphhdl/scripts/test-inherited-source-audit-timeouts.py", source)

    def test_exact_wa10_repair_branch_runs_both_inherited_workflows(self):
        for filename in ("increment-59d-widening.yml", "increment-59h-nested-owners.yml"):
            source = (ROOT / ".github/workflows" / filename).read_text()
            condition = source.split("    if: >-\n", 1)[1].split("    name:", 1)[0]
            self.assertIn("(github.head_ref || github.ref_name) == "
                          "'agent/wa-10-inherited-audit-timeout' ||", condition)
            self.assertNotIn("startsWith(github.head_ref || github.ref_name, 'agent/wa-')", condition)

    def test_legacy_59b_checks_keep_120_seconds(self):
        module = load(CASES[0][0])
        output = "immutable oracle PASS independent oracle scope PASS generic boundaries PASS"
        with contextlib.redirect_stdout(io.StringIO()), \
                patch.object(module.subprocess, "run", return_value=types.SimpleNamespace(
                    returncode=0, stdout=output)) as run:
            module.checked(ROOT, "historical signedness gates")
            self.assertEqual(run.call_count, 3)
            self.assertTrue(all(call.kwargs["timeout"] == 120 for call in run.call_args_list))

    def test_59h_historical_and_mutation_defaults_keep_180_seconds(self):
        module = load("test-increment-59h-inherited-source-scope.py")
        for expected, code, output in ((None, 0, PASS), ("exact rejection", 1, "exact rejection")):
            with self.subTest(expected=expected), contextlib.redirect_stdout(io.StringIO()), \
                    patch.object(module.subprocess, "run", side_effect=(
                        types.SimpleNamespace(returncode=code, stdout=output),
                        types.SimpleNamespace(returncode=0, stdout="source-head"))) as run:
                record = module.checked(ROOT, "historical or mutation", expected)
                self.assertEqual(run.call_count, 2)
                self.assertEqual(run.call_args_list[0].kwargs["timeout"], 180)
                self.assertEqual(run.call_args_list[1].kwargs["timeout"], 120)
                self.assertEqual(record["exit_code"], code)
                self.assertEqual(record["expected_rejection"], expected)
                self.assertEqual(record["source_head"], "source-head")

    def test_59h_current_successor_negative_budget_has_bounded_headroom(self):
        module = load("test-increment-59h-inherited-source-scope.py")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(module.current_negative_timeout(root), 180)
            integration = root / "morphhdl/contracts/increment-59i-target-integration.json"
            integration.parent.mkdir(parents=True)
            integration.write_text("fixture presence only")
            self.assertEqual(module.current_negative_timeout(root), 180)
            successor = root / "morphhdl/contracts/increment-59i-production-successor.json"
            successor.write_text("fixture presence only; the real audit authenticates its bytes")
            self.assertEqual(module.current_negative_timeout(root), 600)
            self.assertGreater(module.current_negative_timeout(root), 2 * 180)

    def test_59h_current_negative_call_uses_the_successor_selector(self):
        module = load("test-increment-59h-inherited-source-scope.py")
        tree = ast.parse(Path(module.__file__).read_text())
        main = next(node for node in tree.body
                    if isinstance(node, ast.FunctionDef) and node.name == "main")
        calls = [node for node in ast.walk(main)
                 if isinstance(node, ast.Call)
                 and isinstance(node.func, ast.Name)
                 and node.func.id == "checked"]
        routed = [keyword.value for call in calls for keyword in call.keywords
                  if keyword.arg == "timeout_seconds"
                  and isinstance(keyword.value, ast.Call)
                  and isinstance(keyword.value.func, ast.Name)
                  and keyword.value.func.id == "current_negative_timeout"]
        self.assertEqual(len(routed), 1)
        self.assertEqual(ast.unparse(routed[0]),
                         "current_negative_timeout(fixture)")

    def test_59h_main_selects_600_seconds_only_for_complete_positive(self):
        module = load("test-increment-59h-inherited-source-scope.py")
        class StopBeforeFixtureOrchestration(Exception):
            pass
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(module, "ROOT", Path(directory)), \
                contextlib.redirect_stdout(io.StringIO()), \
                patch.object(module, "git", return_value="source-head") as git, \
                patch.object(module.importlib.util, "spec_from_file_location",
                             side_effect=StopBeforeFixtureOrchestration) as load_review, \
                patch.object(module.subprocess, "run", return_value=types.SimpleNamespace(
                    returncode=0, stdout=PASS)) as run:
            with self.assertRaises(StopBeforeFixtureOrchestration):
                module.main()
            self.assertEqual(run.call_count, 1)
            self.assertEqual(run.call_args.kwargs["timeout"], 600)
            self.assertEqual(run.call_args.args[0][:2], [sys.executable, "-c"])
            self.assertEqual(run.call_args.args[0][3], directory)
            self.assertEqual(run.call_args.args[0][4], str(Path(directory) / module.CHECKER))
            self.assertEqual(git.call_count, 2)
            self.assertTrue(all(call.args == (Path(directory), "rev-parse", "HEAD")
                                for call in git.call_args_list))
            load_review.assert_called_once()

    def test_59h_pass_and_rejection_require_exact_outcomes(self):
        module = load("test-increment-59h-inherited-source-scope.py")
        for expected, budget, results in (
                (None, 600, ((1, PASS), (0, "no marker"), (1, "no marker"))),
                ("exact rejection", 180,
                 ((0, "exact rejection"), (1, "unrelated error"), (0, PASS)))):
            for code, output in results:
                with self.subTest(expected=expected, code=code, output=output), \
                        patch.object(module.subprocess, "run", return_value=types.SimpleNamespace(
                            returncode=code, stdout=output)), self.assertRaises(RuntimeError):
                    module.checked(ROOT, "invalid outcome", expected, timeout_seconds=budget)

    def test_59h_timeout_is_never_a_pass_or_expected_rejection(self):
        module = load("test-increment-59h-inherited-source-scope.py")
        for expected, budget in ((None, 600), ("exact rejection", 180)):
            with self.subTest(expected=expected), \
                    patch.object(module.subprocess, "run", side_effect=subprocess.TimeoutExpired(
                        "source audit", budget)), self.assertRaises(subprocess.TimeoutExpired):
                module.checked(ROOT, "timed-out audit", expected, timeout_seconds=budget)


    def test_joined_budgets_preserve_target_only_600_second_selection(self):
        for filename, budget, marker in (
                (CASES[0][0], 3600, "increment-59i-production-successor.json"),
                (CASES[1][0], 3600, "increment-59i-production-successor.json"),
                ("test-increment-59h-inherited-source-scope.py", 900,
                 "increment-59i-target-integration.json")):
            module = load(filename)
            with self.subTest(caller=filename), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.assertEqual(module.current_positive_timeout(root), 600)
                file = root / "morphhdl/contracts" / marker
                file.parent.mkdir(parents=True)
                file.write_text("presence selects time only, not source acceptance")
                self.assertEqual(module.current_positive_timeout(root), budget)
                file.unlink()
                self.assertEqual(module.current_positive_timeout(root), 600)

    def test_joined_60f_entrypoints_pass_3600_seconds_to_actual_wrapper(self):
        def check(module, caller):
            spec = types.SimpleNamespace(loader=types.SimpleNamespace(exec_module=lambda _: None))
            helper = types.SimpleNamespace(frozen_inherited_fixture=lambda root, path, out, checks, marker: checks())
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for marker in ("increment-59c-source-review.json", "increment-59i-production-successor.json"):
                    file = root / "morphhdl/contracts" / marker
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_text("fixture")
                with patch.object(module, "ROOT", root), \
                        patch.object(module.importlib.util, "spec_from_file_location", return_value=spec), \
                        patch.object(module.importlib.util, "module_from_spec", return_value=helper), \
                        patch.object(module.subprocess, "run", return_value=types.SimpleNamespace(
                            returncode=0, stdout=PASS)) as run:
                    module.main()
                    self.assertEqual(run.call_count, 1)
                    self.assertEqual(run.call_args.kwargs["timeout"], 3600)
        self.exercise(check)

    def test_joined_59h_entrypoint_passes_900_seconds_to_actual_wrapper(self):
        module = load("test-increment-59h-inherited-source-scope.py")
        class StopBeforeFixtures(Exception):
            pass
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / "morphhdl/contracts/increment-59i-target-integration.json"
            marker.parent.mkdir(parents=True)
            marker.write_text("fixture")
            with patch.object(module, "ROOT", root), \
                    patch.object(module, "git", return_value="source-head"), \
                    patch.object(module.importlib.util, "spec_from_file_location", side_effect=StopBeforeFixtures), \
                    patch.object(module.subprocess, "run", return_value=types.SimpleNamespace(
                        returncode=0, stdout=PASS)) as run, contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(StopBeforeFixtures):
                    module.main()
                self.assertEqual(run.call_count, 1)
                self.assertEqual(run.call_args.kwargs["timeout"], 900)

    def test_joined_timeout_and_contradictory_markers_are_not_success(self):
        def check(module, caller):
            with patch.object(module.subprocess, "run", side_effect=subprocess.TimeoutExpired("joined", 3600)), \
                    self.assertRaises(subprocess.TimeoutExpired):
                caller(ROOT, "joined", timeout_seconds=3600)
            with patch.object(module.subprocess, "run", return_value=types.SimpleNamespace(returncode=1, stdout=PASS)), \
                    self.assertRaises(RuntimeError):
                caller(ROOT, "joined", timeout_seconds=3600)
        self.exercise(check)


if __name__ == "__main__":
    unittest.main(verbosity=2)
