#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('contracts', Path(__file__).with_name('run-contracts.py'))
contracts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contracts)


class Reports(unittest.TestCase):
    def check(self, xml, expected=('example.Suite',)):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.xml'
            path.write_text(xml)
            return contracts.reports([path], expected)

    def test_success_single_and_mill_wrapper(self):
        body = '<testsuite name="example.Suite" tests="1"><testcase classname="example.Suite" name="case"/></testsuite>'
        for xml in (body, '<testsuites>' + body + '</testsuites>'):
            self.assertEqual(self.check(xml), {'example.Suite': ['case']})

    def test_missing_empty_failed_skipped_duplicate_and_wrong_suite(self):
        bodies = [
            '<testsuites/>',
            '<testsuite name="example.Suite" tests="0"/>',
            '<testsuite name="example.Suite" tests="1" failures="1"><testcase classname="example.Suite" name="case"/></testsuite>',
            '<testsuite name="example.Suite" tests="1"><testcase classname="example.Suite" name="case"><skipped/></testcase></testsuite>',
            '<testsuite name="example.Suite" tests="1"><testcase classname="example.Suite" name="case"><error/></testcase></testsuite>',
            '<testsuite name="example.Suite" tests="2"><testcase classname="example.Suite" name="case"/><testcase classname="example.Suite" name="case"/></testsuite>',
            '<testsuite name="other.Suite" tests="1"><testcase classname="example.Suite" name="case"/></testsuite>',
            '<testsuite name="example.Suite" tests="2"><testcase classname="example.Suite" name="case"/></testsuite>',
            '<testsuite name="example.Suite" tests="1"><testcase classname="other.Suite" name="case"/></testsuite>',
            '<!DOCTYPE testsuite><testsuite name="example.Suite" tests="1"><testcase classname="example.Suite" name="case"/></testsuite>',
        ]
        for xml in bodies:
            with self.subTest(xml=xml), self.assertRaises(RuntimeError):
                self.check(xml)


if __name__ == '__main__':
    unittest.main()
