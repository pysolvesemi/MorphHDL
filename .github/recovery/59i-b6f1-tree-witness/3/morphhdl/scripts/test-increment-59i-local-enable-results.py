#!/usr/bin/env python3
"""Controller-only negative checks; these do not represent Scala/RTL runs."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('local_results', Path(__file__).with_name('check-increment-59i-local-enable-results.py'))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ResultChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.out = self.root / 'evidence'
        self.out.mkdir()
        inventory = {name: ['case-a', 'case-b'] for name in checker.SUITES}
        contract = self.root / 'morphhdl/contracts/increment-59i-regression-inventory.json'
        contract.parent.mkdir(parents=True)
        contract.write_text(json.dumps({'projects': {'morphhdl': inventory}}))
        self.reports = self.root / 'morphhdl/target/test-reports'
        self.reports.mkdir(parents=True)
        for name, cases in inventory.items():
            root = ET.Element('testsuite', name=name, tests='2', failures='0', errors='0', skipped='0')
            for case in cases:
                ET.SubElement(root, 'testcase', name=case, classname=name)
            ET.ElementTree(root).write(self.reports / ('TEST-' + name + '.xml'))

    def test_complete_fixture(self):
        self.assertEqual(checker.reports(self.root, self.out)['tests'], 20)

    def test_same_count_different_case_rejected(self):
        path = next(self.reports.glob('*.xml'))
        path.write_text(path.read_text().replace('case-b', 'case-a'))
        with self.assertRaisesRegex(RuntimeError, 'identities differ'):
            checker.reports(self.root, self.out)

    def test_missing_suite_rejected(self):
        next(self.reports.glob('*.xml')).unlink()
        with self.assertRaisesRegex(RuntimeError, 'inventory differs'):
            checker.reports(self.root, self.out)

    def test_failed_case_retained_then_rejected(self):
        path = next(self.reports.glob('*.xml'))
        root = ET.parse(path).getroot()
        ET.SubElement(root.find('testcase'), 'failure', message='controller fixture')
        ET.ElementTree(root).write(path)
        with self.assertRaisesRegex(RuntimeError, 'nonpassing'):
            checker.reports(self.root, self.out)
        self.assertEqual((self.out / 'test-reports' / path.name).read_bytes(), path.read_bytes())

    def test_linked_report_rejected(self):
        path = next(self.reports.glob('*.xml'))
        (self.reports / 'linked.xml').symlink_to(path)
        with self.assertRaisesRegex(RuntimeError, 'linked'):
            checker.reports(self.root, self.out)

    def test_changed_class_or_missing_outcome_rejected(self):
        path = next(self.reports.glob('*.xml'))
        original = path.read_bytes()
        root = ET.parse(path).getroot()
        root.find('testcase').set('classname', 'foreign')
        ET.ElementTree(root).write(path)
        with self.assertRaisesRegex(RuntimeError, 'class differs'):
            checker.reports(self.root, self.out)
        path.write_bytes(original)
        root = ET.parse(path).getroot()
        del root.attrib['skipped']
        ET.ElementTree(root).write(path)
        with self.assertRaisesRegex(RuntimeError, 'missing or nonpassing'):
            checker.reports(self.root, self.out)

    def test_empty_and_changed_rtl_rejected(self):
        for name in ('hardware-A', 'hardware-B'):
            (self.out / name).mkdir()
        with self.assertRaisesRegex(RuntimeError, 'empty RTL'):
            checker.deterministic(self.out)
        for name in ('hardware-A', 'hardware-B'):
            (self.out / name / 'top.v').write_text('module top; endmodule\n')
        checker.deterministic(self.out)
        checker.unchanged(self.out)
        (self.out / 'hardware-B/top.v').write_text('module wrong; endmodule\n')
        with self.assertRaisesRegex(RuntimeError, 'RTL changed'):
            checker.unchanged(self.out)
        with self.assertRaisesRegex(RuntimeError, 'RTL differs'):
            checker.deterministic(self.out)


if __name__ == '__main__':
    unittest.main()
