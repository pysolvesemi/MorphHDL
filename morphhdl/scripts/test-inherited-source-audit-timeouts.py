#!/usr/bin/env python3
"""Test audit caller budgets and fail-closed results without sleeping or Git writes.

These caller tests supplement, never replace, the real current/historical source
audits in the 59d and 59h workflows. Subprocess execution alone is mocked.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import subprocess
import sys
import types
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
                module.main()
                self.assertEqual(len(invoked), 1)
                self.assertEqual(run.call_count, 1)
                self.assertEqual(run.call_args.kwargs["timeout"], 600)
                self.assertEqual(run.call_args.args[0][:2], [sys.executable, "-c"])
                self.assertEqual(run.call_args.args[0][3], str(ROOT))
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

    def test_legacy_59b_checks_keep_120_seconds(self):
        module = load(CASES[0][0])
        output = "immutable oracle PASS independent oracle scope PASS generic boundaries PASS"
        with contextlib.redirect_stdout(io.StringIO()), \
                patch.object(module.subprocess, "run", return_value=types.SimpleNamespace(
                    returncode=0, stdout=output)) as run:
            module.checked(ROOT, "historical signedness gates")
            self.assertEqual(run.call_count, 3)
            self.assertTrue(all(call.kwargs["timeout"] == 120 for call in run.call_args_list))


if __name__ == "__main__":
    unittest.main(verbosity=2)
