#!/usr/bin/env python3
"""Negative-result and replay-preservation controls; these are not hardware tests."""
import importlib.util
import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
REPO = Path(os.environ.get('MORPHHDL_REPO', str(HERE.parent / 'repo'))).resolve()
spec = importlib.util.spec_from_file_location('replay', HERE / 'replay_saturation.py')
R = importlib.util.module_from_spec(spec)
spec.loader.exec_module(R)
S = runpy.run_path(str(REPO / 'morphhdl/scripts/check-increment-60f-equivalence-closure.py'))

class ReplayTests(unittest.TestCase):
    def classify(self, rc, text):
        return S['classify_solver'](rc, text, S['SAT_FAIL'])
    def test_accept_exact_counterexample_result(self):
        self.classify(0, S['SAT_FAIL'])
    def test_reject_original_verify_error(self):
        with self.assertRaisesRegex(RuntimeError, 'nonzero'):
            self.classify(1, 'ERROR: Called with -verify and proof did fail!')
    def test_reject_failure_exit_even_with_counterexample_marker(self):
        with self.assertRaisesRegex(RuntimeError, 'nonzero'):
            self.classify(1, S['SAT_FAIL'])
    def test_reject_counterexample_marker_with_error(self):
        with self.assertRaises(RuntimeError): self.classify(0, S['SAT_FAIL'] + '\nERROR: invalid input')
    def test_reject_timeout(self):
        with self.assertRaises(RuntimeError): self.classify(0, S['SAT_FAIL'] + '\nTIMEOUT')
    def test_reject_unknown(self):
        with self.assertRaises(RuntimeError): self.classify(0, S['SAT_FAIL'] + '\nUNKNOWN')
    def test_reject_missing_marker(self):
        with self.assertRaises(RuntimeError): self.classify(0, 'proof did fail')
    def test_reject_duplicate_marker(self):
        with self.assertRaises(RuntimeError): self.classify(0, S['SAT_FAIL'] * 2)
    def test_reject_contradictory_results(self):
        with self.assertRaises(RuntimeError): self.classify(0, S['SAT_FAIL'] + '\n' + S['SAT_PASS'])
    def test_archive_checksum_change_rejected_before_extraction(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); archive = root / 'input.zip'; archive.write_bytes(b'wrong')
            with self.assertRaisesRegex(RuntimeError, 'checksum'):
                R.validate_archive('2.12.18', archive, root / 'extract', REPO)
            self.assertFalse((root / 'extract').exists())
    def run_plan(self, produce_model=True):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            calls = []
            def execute(command, directory, label, expected, classifier):
                calls.append((command, label, expected))
                if label == 'replay-simulation':
                    (root / 'replay-simulation.log').write_text('59I-SATURATION-CYCLES 259\n59I-SATURATION-SIM-PASS\n')
                if label == 'replay-negative' and produce_model:
                    (root / 'mutation-counterexample.vcd').write_text('mock model for plan control only')
                return {'mock': True}
            with patch.object(R, 'execute', execute):
                result = R.hardware(root, S)
            positive = (root / 'replay-positive.ys').read_text()
            negative = (root / 'replay-negative.ys').read_text()
            return result, calls, positive, negative
    def test_only_negative_sat_mode_changes_and_budget_remains_120(self):
        result, calls, positive, negative = self.run_plan()
        self.assertEqual(positive.splitlines()[1:], ['prep -top miter -flatten','opt -full','check -assert', R.SAT_COMMAND])
        self.assertIn(' -verify ', positive); self.assertNotIn(' -falsify ', positive)
        self.assertIn(' -falsify ', negative); self.assertNotIn(' -verify ', negative)
        self.assertEqual(negative.splitlines()[1:4], positive.splitlines()[1:4])
        self.assertIn(' -timeout 120 -dump_vcd ', negative)
        self.assertNotIn('no-timeout', negative)
        self.assertEqual(calls[-1][2], S['SAT_FAIL']); self.assertEqual(calls[-3][2], S['SAT_PASS'])
        self.assertEqual(len(calls), 9)
    def test_negative_proof_requires_actual_counterexample_file(self):
        with self.assertRaisesRegex(RuntimeError, 'actual counterexample'):
            self.run_plan(produce_model=False)

if __name__ == '__main__': unittest.main(verbosity=2)
