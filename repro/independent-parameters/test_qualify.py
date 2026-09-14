"""Negative controls for the clean-build gate's JUnit evidence consumer."""
import tempfile
import unittest
from pathlib import Path
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


if __name__ == '__main__':
    unittest.main()
