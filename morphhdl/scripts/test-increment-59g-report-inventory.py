#!/usr/bin/env python3
"""Test the actual 59g XML gate, including all original rejection conditions."""
from __future__ import annotations
import ast
import copy
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = '.github/workflows/increment-59g-register-bridges.yml'
PREDECESSOR = 'ddf61ef25f927d646027cebbcaca6d724ad8a5fa'
HISTORICAL = '5a9294363c7b74ae584f1702160fe1f7e468f58a'
EXPECTED = {'TypedBalancedReductionBridgePublicationTests': 3,
            'TypedBalancedReductionBridgeReplayTests': 12,
            'TypedBalancedReductionCallbackPolicyTests': 16,
            'TypedBalancedReductionStageReplayTests': 23,
            'TypedBalancedReductionClosedGraphTests': 27}
ENROLLMENT = '          python3 -B morphhdl/scripts/test-increment-59g-report-inventory.py\n'
OLD_BUDGET = '    timeout-minutes: 120\n'
REVIEWED_BUDGET = '    timeout-minutes: 240\n'


# Exact current integration enrollment, reversed before the original historical
# equality assertion. No general line filtering or certificate changes.
INTEGRATION_ENROLLMENT = (('', '      options: --shm-size=2g\n'),
 ('          predecessor="$output/predecessor-source"\n', ''),
 ('          python3 morphhdl/scripts/check-increment-61-source-review.py\n'
  '          python3 morphhdl/scripts/check-increment-61-source-review.py --self-test\n'
  '          base=$(python3 morphhdl/scripts/check-increment-61-source-review.py --print-base)\n'
  '          git worktree add --detach "$predecessor" "$base"\n'
  '          trap \'git worktree remove --force "$predecessor"\' EXIT\n'
  '          (\n'
  '            cd "$predecessor"\n'
  '            python3 morphhdl/scripts/check-increment-59g-source-review.py --self-test\n'
  '            python3 morphhdl/scripts/check-increment-59g-source-review.py\n'
  '            python3 morphhdl/scripts/test-increment-59g-source-review.py\n'
  '            python3 morphhdl/scripts/check-increment-60f-equivalence-closure.py --source-only\n'
  '          )\n',
  '          python3 morphhdl/scripts/check-parameterized-integration-source.py\n'
  '          python3 morphhdl/scripts/check-parameterized-integration-source.py --self-test\n'
  '          python3 morphhdl/scripts/run-parameterized-inherited-audit.py '
  'morphhdl/scripts/check-increment-61-source-review.py\n'
  '          python3 morphhdl/scripts/run-parameterized-inherited-audit.py '
  'morphhdl/scripts/check-increment-61-source-review.py --self-test\n'
  '          python3 morphhdl/scripts/run-parameterized-inherited-audit.py '
  'morphhdl/scripts/check-increment-59g-source-review.py --self-test\n'
  '          python3 morphhdl/scripts/run-parameterized-inherited-audit.py '
  'morphhdl/scripts/check-increment-59g-source-review.py\n'
  '          python3 morphhdl/scripts/run-parameterized-inherited-audit.py '
  'morphhdl/scripts/test-increment-59g-source-review.py\n'
  '          python3 morphhdl/scripts/run-parameterized-inherited-audit.py '
  'morphhdl/scripts/check-increment-60f-equivalence-closure.py --source-only\n'),
 ('',
  '\n'
  '      - name: Retain inherited source audit receipts\n'
  '        if: always()\n'
  '        uses: actions/upload-artifact@v4\n'
  '        with:\n'
  "          name: integrated-source-increment-59g-register-bridges-${{ matrix.scala || 'source' "
  '}}-${{ github.run_attempt }}\n'
  '          path: target/parameterized-inherited-audits\n'
  '          if-no-files-found: error\n'
  '          retention-days: 30\n'))

def restore_integration_enrollment(text: str) -> str:
    from candidate_provenance_migration import restore_workflow
    text = restore_workflow(WORKFLOW, text)
    original_text = text
    for historical, current in INTEGRATION_ENROLLMENT:
        if current:
            if text.count(current) != 1:
                return original_text
            text = text.replace(current, historical, 1)
        else:
            # A removed predecessor assignment is restored at its exact successor.
            anchor = '          mkdir -p "$output/bootstrap"\n'
            if text.count(anchor) != 1 or historical in text:
                return original_text
            text = text.replace(anchor, historical + anchor, 1)
    return text


def restore_reviewed_workflow(text: str) -> str:
    # Reverse exact integration enrollment, then apply the original three
    # reviewed edits. Every remaining byte must match the historical workflow.
    for expected in (ENROLLMENT, REVIEWED_BUDGET,
                     "'TypedBalancedReductionClosedGraphTests': 27"):
        if text.count(expected) != 1:
            raise AssertionError('Expected exactly one reviewed workflow edit: ' + expected)
    text = restore_integration_enrollment(text)
    return text.replace(ENROLLMENT, '').replace(REVIEWED_BUDGET, OLD_BUDGET).replace(
        "'TypedBalancedReductionClosedGraphTests': 27", "'TypedBalancedReductionClosedGraphTests': 20")


def gate(text: str) -> str:
    matches = re.findall(r"          python3 - <<'PYTEST'\n(.*?)\n          PYTEST", text, re.S)
    if len(matches) != 1:
        raise AssertionError('Expected one unchanged XML report-gate block')
    return textwrap.dedent(matches[0])


def original(path: str, revision: str = PREDECESSOR) -> str:
    return subprocess.check_output(['git', 'show', revision + ':' + path],
                                   cwd=ROOT, text=True, timeout=120)


class ReportInventoryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = (ROOT / WORKFLOW).read_text()
        cls.code = gate(cls.workflow)
        cls.old = original(WORKFLOW)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='59g-report-gate-')
        self.root = Path(self.tmp.name)
        self.reports = self.root / 'morphhdl/target/test-reports'
        self.reports.mkdir(parents=True)
        for name, count in EXPECTED.items():
            self.report(name, count)

    def tearDown(self):
        self.tmp.cleanup()

    def report(self, name, count, suffix='', **outcomes):
        node = ET.Element('testsuite', name='spinal.core.internals.' + name,
                          tests=str(count), errors='0', failures='0', skipped='0')
        node.attrib.update({key: str(value) for key, value in outcomes.items()})
        for index in range(count):
            ET.SubElement(node, 'testcase', name='case-' + str(index))
        file = self.reports / ('TEST-' + suffix + name + '.xml')
        ET.ElementTree(node).write(file)
        return file

    def run_gate(self, code=None):
        previous = Path.cwd()
        os.chdir(self.root)
        try:
            exec(compile(self.code if code is None else code, WORKFLOW + ':PYTEST', 'exec'), {})
        finally:
            os.chdir(previous)

    def test_exact_expanded_inventory_passes(self):
        self.run_gate()

    def test_original_defect_rejects_the_real_expanded_inventory(self):
        with self.assertRaises(AssertionError):
            self.run_gate(gate(self.old))

    def test_old_twenty_case_inventory_is_not_accepted(self):
        self.report('TypedBalancedReductionClosedGraphTests', 20)
        with self.assertRaises(AssertionError):
            self.run_gate()

    def test_missing_new_case_is_rejected(self):
        self.report('TypedBalancedReductionClosedGraphTests', 26)
        with self.assertRaises(AssertionError):
            self.run_gate()

    def test_unexpected_count_is_rejected(self):
        self.report('TypedBalancedReductionClosedGraphTests', 28)
        with self.assertRaises(AssertionError):
            self.run_gate()

    def test_other_exact_counts_are_not_relaxed(self):
        for name, count in EXPECTED.items():
            with self.subTest(name=name):
                self.report(name, count - 1)
                with self.assertRaises(AssertionError):
                    self.run_gate()
                self.report(name, count)

    def test_failure_is_rejected(self):
        self.report('TypedBalancedReductionClosedGraphTests', 27, failures=1)
        with self.assertRaises(AssertionError):
            self.run_gate()

    def test_error_is_rejected(self):
        self.report('TypedBalancedReductionClosedGraphTests', 27, errors=1)
        with self.assertRaises(AssertionError):
            self.run_gate()

    def test_skip_is_rejected(self):
        self.report('TypedBalancedReductionClosedGraphTests', 27, skipped=1)
        with self.assertRaises(AssertionError):
            self.run_gate()

    def test_no_report_is_rejected(self):
        for file in self.reports.iterdir():
            file.unlink()
        with self.assertRaises(AssertionError):
            self.run_gate()

    def test_each_missing_required_suite_is_rejected(self):
        for name, count in EXPECTED.items():
            with self.subTest(name=name):
                (self.reports / ('TEST-' + name + '.xml')).unlink()
                with self.assertRaises(AssertionError):
                    self.run_gate()
                self.report(name, count)

    def test_duplicate_required_suite_is_rejected(self):
        self.report('TypedBalancedReductionClosedGraphTests', 27, suffix='duplicate-')
        with self.assertRaises(AssertionError):
            self.run_gate()

    def test_malformed_xml_is_not_success(self):
        (self.reports / 'TEST-TypedBalancedReductionBadTests.xml').write_text('<testsuite')
        with self.assertRaises(ET.ParseError):
            self.run_gate()

    def test_failures_in_other_reduction_suites_are_not_ignored(self):
        self.report('TypedBalancedReductionAdditionalTests', 1, failures=1)
        with self.assertRaises(AssertionError):
            self.run_gate()

    def test_additional_passing_reduction_suites_remain_supported(self):
        self.report('TypedBalancedReductionAdditionalTests', 1)
        self.run_gate()

    def test_gate_ast_changes_only_the_reviewed_count(self):
        normalized = self.code.replace("'TypedBalancedReductionClosedGraphTests': 27",
                                       "'TypedBalancedReductionClosedGraphTests': 20")
        self.assertEqual(ast.dump(ast.parse(normalized)), ast.dump(ast.parse(gate(self.old))))

    def test_entire_workflow_is_preserved_except_three_reviewed_edits(self):
        self.assertEqual(restore_reviewed_workflow(self.workflow), self.old)

    def test_unreviewed_budget_is_rejected(self):
        for budget in (0, 120, 239, 241, 360):
            with self.subTest(budget=budget), self.assertRaises(AssertionError):
                restore_reviewed_workflow(self.workflow.replace(
                    REVIEWED_BUDGET, '    timeout-minutes: %d\n' % budget))

    def test_duplicate_budget_is_rejected(self):
        with self.assertRaises(AssertionError):
            restore_reviewed_workflow(self.workflow + REVIEWED_BUDGET)

    def test_unrelated_workflow_change_is_not_normalized(self):
        changed = self.workflow.replace('if-no-files-found: error', 'if-no-files-found: warn')
        self.assertNotEqual(changed, self.workflow)
        self.assertNotEqual(restore_reviewed_workflow(changed), self.old)

    def test_integration_enrollment_mutations_are_rejected(self):
        for historical, current in INTEGRATION_ENROLLMENT:
            # The 2026-10-06 producer owns the once-per-candidate seal check.
            # Mutate the actual remaining scope block, not an absent old block.
            current = current.replace(
                '          python3 morphhdl/scripts/check-parameterized-integration-source.py\n', '')
            if current:
                for replacement in ('', current + current, current.replace('python3', 'python2')
                                    if 'python3' in current else current.replace('2g', '1g')
                                    if '2g' in current else current.replace('30', '29')):
                    with self.subTest(block=current, replacement=replacement):
                        self.assertNotEqual(replacement, current)
                        changed = self.workflow.replace(current, replacement, 1)
                        try:
                            restored = restore_reviewed_workflow(changed)
                        except AssertionError:
                            continue
                        self.assertNotEqual(restored, self.old)

    def test_source_has_each_exact_test_inventory(self):
        for name, count in EXPECTED.items():
            source = (ROOT / 'morphhdl/src/test/scala/spinal/core/internals' / (name + '.scala')).read_text()
            names = re.findall(r'^  test\("([^"\n]+)"\)', source, re.M)
            self.assertEqual(len(names), count, name)
            self.assertEqual(len(set(names)), count, name)

    def test_all_twenty_historical_cases_and_seven_additions_are_retained(self):
        path = 'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionClosedGraphTests.scala'
        names = lambda source: re.findall(r'^  test\("([^"\n]+)"\)', source, re.M)
        old = names(original(path, HISTORICAL))
        current = names((ROOT / path).read_text())
        self.assertEqual(len(old), 20)
        self.assertEqual(current[:20], old)
        self.assertEqual(len(current[20:]), 7)


if __name__ == '__main__':
    unittest.main(verbosity=2)
