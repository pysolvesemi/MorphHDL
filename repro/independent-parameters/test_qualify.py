"""Negative controls for the clean-build gate's JUnit evidence consumer."""
import tempfile
import unittest
from pathlib import Path
from qualify import verify_reports


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


if __name__ == '__main__':
    unittest.main()
