"""Fail-closed source-audit budgets and clean-build JUnit evidence controls."""
import contextlib
import importlib.util
import io
import subprocess
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch
from qualify import verify_reports, verify_qualification_reports


class QualificationReportTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.expected = {"SelectedSuite": 1}

    def report(self, attributes='', body='<testcase name="ran"/>', filename='result.xml'):
        path = self.root / filename
        path.write_text(f'<testsuite name="SelectedSuite" tests="1" {attributes}>{body}</testsuite>')

    def test_complete_report(self):
        self.report()
        self.assertEqual(verify_reports(self.root, self.expected), self.expected)

    def test_missing_report(self):
        with self.assertRaisesRegex(RuntimeError, "missing suite"):
            verify_reports(self.root, self.expected)

    def test_duplicate_report(self):
        self.report()
        self.report(filename='duplicate.xml')
        with self.assertRaisesRegex(RuntimeError, "duplicate"):
            verify_reports(self.root, self.expected)

    def test_failed_report(self):
        self.report(attributes='failures="1"')
        with self.assertRaisesRegex(RuntimeError, "nonzero failures"):
            verify_reports(self.root, self.expected)

    def test_skipped_case_even_without_summary_flag(self):
        self.report(body='<testcase name="not run"><skipped/></testcase>')
        with self.assertRaisesRegex(RuntimeError, "unsuccessful"):
            verify_reports(self.root, self.expected)

    def test_missing_actual_testcases(self):
        self.report(body='')
        with self.assertRaisesRegex(RuntimeError, "expected 1 executed"):
            verify_reports(self.root, self.expected)

    def test_wrong_inventory_count(self):
        self.report()
        with self.assertRaisesRegex(RuntimeError, "expected 2 executed"):
            verify_reports(self.root, {"SelectedSuite": 2})


class QualificationModuleCoverageTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def report(self, module, suite, result=''):
        directory = self.root / module / 'target/test-reports'
        directory.mkdir(parents=True, exist_ok=True)
        (directory / 'suite.xml').write_text(
            f'<testsuite name="{suite}" tests="1"><testcase name="executed">{result}</testcase></testsuite>')

    def test_both_module_reports_are_required(self):
        self.report('morphhdl', 'NativeSuite')
        with self.assertRaisesRegex(RuntimeError, "missing suite reports: FrontendSuite"):
            verify_qualification_reports(self.root, {'NativeSuite': 1}, {'FrontendSuite': 1})

    def test_frontend_failure_is_not_hidden_by_native_success(self):
        self.report('morphhdl', 'NativeSuite')
        self.report('frontend', 'FrontendSuite', '<failure message="wrong diagnostic"/>')
        with self.assertRaisesRegex(RuntimeError, "FrontendSuite: unsuccessful"):
            verify_qualification_reports(self.root, {'NativeSuite': 1}, {'FrontendSuite': 1})

    def test_complete_module_reports(self):
        self.report('morphhdl', 'NativeSuite')
        self.report('frontend', 'FrontendSuite')
        self.assertEqual(verify_qualification_reports(self.root, {'NativeSuite': 1}, {'FrontendSuite': 1}),
                         {'morph': {'NativeSuite': 1}, 'frontend': {'FrontendSuite': 1}})


class RegisterBridgeAuditBudgetTests(unittest.TestCase):
    """Execute the real wrapper; mock execution, not its acceptance policy."""
    ROOT = Path(__file__).resolve().parents[2]
    PASS = "inherited native audits PASS"

    def load_audit(self):
        filename = self.ROOT / 'morphhdl/scripts/test-increment-59g-source-review.py'
        spec = importlib.util.spec_from_file_location('independent_59g_audit_budget', filename)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_historical_and_mutation_defaults_still_use_180_seconds(self):
        module = self.load_audit()
        for expected, code, output in ((None, 0, self.PASS),
                                       ('exact rejection', 1, 'exact rejection')):
            with self.subTest(expected=expected), contextlib.redirect_stdout(io.StringIO()), \
                    patch.object(module.subprocess, 'run', side_effect=(
                        types.SimpleNamespace(returncode=code, stdout=output),
                        types.SimpleNamespace(returncode=0, stdout='checked-source-head'))) as run:
                record = module.check(self.ROOT, 'historical or mutation', expected)
                self.assertEqual(run.call_count, 2)
                self.assertEqual(run.call_args_list[0].kwargs['timeout'], 180)
                self.assertEqual(run.call_args_list[1].kwargs['timeout'], 120)
                self.assertEqual(record['exit_code'], code)
                self.assertEqual(record['expected_rejection'], expected)
                self.assertEqual(record['head'], 'checked-source-head')

    def assert_complete_positive_budget(self, contracts, budget):
        module = self.load_audit()

        class StopBeforeMutationWorktrees(Exception):
            pass

        # Resource selection must not depend on whichever increment happens
        # to be present in the test runner's checkout. These fixture files
        # select only a timeout: the real wrapper's process result and marker
        # checks are still executed. They do not authenticate any source.
        with tempfile.TemporaryDirectory(prefix='independent-59g-budget-') as temporary:
            root = Path(temporary)
            for contract in contracts:
                filename = root / 'morphhdl/contracts' / contract
                filename.parent.mkdir(parents=True, exist_ok=True)
                filename.write_text('{}\n')
            with contextlib.redirect_stdout(io.StringIO()), \
                    patch.object(module, 'ROOT', root), \
                    patch.object(module, 'git', return_value='checked-source-head') as git, \
                    patch.object(module.importlib.util, 'spec_from_file_location',
                                 side_effect=StopBeforeMutationWorktrees) as load_review, \
                    patch.object(module.subprocess, 'run', return_value=types.SimpleNamespace(
                        returncode=0, stdout=self.PASS)) as run:
                with self.assertRaises(StopBeforeMutationWorktrees):
                    module.main()
                self.assertEqual(run.call_count, 1)
                self.assertEqual(run.call_args.kwargs['timeout'], budget)
                self.assertEqual(run.call_args.args[0][:2], [sys.executable, '-c'])
                self.assertEqual(run.call_args.args[0][3], str(root))
                self.assertEqual(run.call_args.args[0][4], str(root / module.CHECKER))
                self.assertEqual(git.call_count, 2)
                self.assertTrue(all(call.args == (root, 'rev-parse', 'HEAD')
                                    for call in git.call_args_list))
                load_review.assert_called_once()

    def test_main_gives_only_complete_positive_audit_600_seconds(self):
        self.assert_complete_positive_budget((), 600)

    def test_main_gives_target_integration_positive_900_seconds(self):
        self.assert_complete_positive_budget(
            ('increment-59i-target-integration.json',), 900)

    def test_main_gives_sealed_59i_positive_3600_seconds(self):
        self.assert_complete_positive_budget(
            ('increment-59i-target-integration.json',
             'increment-59i-production-successor.json'), 3600)

    def test_joined_positive_budgets_keep_exact_outcome_checks(self):
        module = self.load_audit()
        for budget in (900, 3600):
            for code, output in ((1, self.PASS), (0, 'no marker'), (1, 'no marker')):
                with self.subTest(budget=budget, code=code, output=output), \
                        patch.object(module.subprocess, 'run', return_value=types.SimpleNamespace(
                            returncode=code, stdout=output)), self.assertRaises(RuntimeError):
                    module.check(self.ROOT, 'invalid joined outcome', timeout_seconds=budget)

    def test_joined_timeout_cannot_count_as_positive(self):
        module = self.load_audit()
        for budget in (900, 3600):
            with self.subTest(budget=budget), \
                    patch.object(module.subprocess, 'run', side_effect=subprocess.TimeoutExpired(
                        'source audit', budget, output=self.PASS)), \
                    self.assertRaises(subprocess.TimeoutExpired):
                module.check(self.ROOT, 'timed-out joined audit', timeout_seconds=budget)

    def test_positive_and_mutation_still_require_exact_outcomes(self):
        module = self.load_audit()
        for expected, budget, results in (
                (None, 600, ((1, self.PASS), (0, 'no marker'), (1, 'no marker'))),
                ('exact rejection', 180,
                 ((0, 'exact rejection'), (1, 'unrelated error'), (0, self.PASS)))):
            for code, output in results:
                with self.subTest(expected=expected, code=code, output=output), \
                        patch.object(module.subprocess, 'run', return_value=types.SimpleNamespace(
                            returncode=code, stdout=output)), self.assertRaises(RuntimeError):
                    module.check(self.ROOT, 'invalid outcome', expected, timeout_seconds=budget)

    def test_timeout_cannot_be_a_pass_or_expected_mutation_rejection(self):
        module = self.load_audit()
        for expected, budget in ((None, 600), ('exact rejection', 180)):
            with self.subTest(expected=expected), \
                    patch.object(module.subprocess, 'run', side_effect=subprocess.TimeoutExpired(
                        'source audit', budget, output=self.PASS + ' exact rejection')), \
                    self.assertRaises(subprocess.TimeoutExpired):
                module.check(self.ROOT, 'timed-out audit', expected, timeout_seconds=budget)


if __name__ == '__main__':
    unittest.main()
