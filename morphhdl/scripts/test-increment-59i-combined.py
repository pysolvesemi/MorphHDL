#!/usr/bin/env python3
"""Independent regression tests for the 59i checker; no HDL pass is implied."""
from __future__ import annotations

import importlib.util
import tempfile
import random
import unittest
from unittest.mock import patch
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('combined_checker',
    Path(__file__).with_name('check-increment-59i-combined.py'))
assert SPEC is not None and SPEC.loader is not None
C = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(C)


class ClockContractTests(unittest.TestCase):
    def test_synchronous_reset_stalls_when_clock_enable_is_low(self):
        model = C.Pipeline(5)
        rows = [(7, 1, 2, 3), (6, 2, 3, 4), (5, 3, 4, 5), (4, 4, 5, 6), (1, 5, 6, 7)]
        model.step(rows, True, True, False)
        for _ in range(3):
            result = model.step(rows, False, True, False)
        self.assertEqual(result, (1, 5, 6, 7))
        self.assertEqual(model.step(rows, True, False, False), result)
        self.assertEqual(model.step(rows, True, True, False), (0, 0, 0, 0))

    def test_model_requires_an_enabled_reset_before_observing_registered_state(self):
        for enable in (False, True):
            with self.subTest(enable=enable):
                with self.assertRaisesRegex(RuntimeError, 'enabled reset'):
                    C.Pipeline(3).step([(1, 1, 1, 1)] * 3, False, enable, False)
        with self.assertRaisesRegex(RuntimeError, 'enabled reset'):
            C.Pipeline(3).step([(1, 1, 1, 1)] * 3, True, False, False)

    def test_singleton_is_combinational_under_every_reset_enable_combination(self):
        for reset in (False, True):
            for enable in (False, True):
                self.assertEqual(C.Pipeline(1).step([(2, 3, 4, 5)], reset, enable, False), (2, 3, 4, 5))

    def test_native_tree_model_matches_independent_transaction_history(self):
        # Reference is a flat stable min/max plus a shift register, not another tree.
        for count in C.COUNTS:
            for maximum in (False, True):
                with self.subTest(count=count, maximum=maximum):
                    rng = random.Random(59_009 + count)
                    model = C.Pipeline(count)
                    depth = (count - 1).bit_length()
                    history = [(0, 0, 0, 0)] * depth
                    for cycle in range(160):
                        rows = [(rng.randrange(8), cycle, i, rng.randrange(32)) for i in range(count)]
                        reset = cycle == 0 or cycle % 19 in (5, 6)
                        enable = cycle == 0 or cycle % 7 not in (0, 1, 5)
                        expected = (max if maximum else min)(rows, key=lambda row: row[0])
                        if depth:
                            if enable:
                                history = ([(0, 0, 0, 0)] * depth if reset else [expected] + history[:-1])
                            expected = history[-1]
                        self.assertEqual(model.step(rows, reset, enable, maximum), expected,
                                         (count, maximum, cycle, reset, enable))

    def test_directed_stimulus_exposes_reset_during_stall_with_nonzero_state(self):
        for count in C.COUNTS:
            for mode in (0, 1):
                case = dict(width=5, tag_width=3, coord_width=7, count=count, mode=mode)
                trace = C.stimulus(case)
                self.assertEqual(trace[0][1:], (True, True))
                depth = (count - 1).bit_length()
                model = C.Pipeline(count)
                for cycle, (sample, reset, enable) in enumerate(trace[:depth + 4]):
                    rows = C.decode(sample['records'], C.fields(case)['records'], count)
                    result = model.step(rows, reset, enable, bool(mode))
                    if cycle == depth + 1 and depth:
                        self.assertTrue(reset and not enable)
                        self.assertEqual(result, (31, 7, 127, 127))
                    if cycle == depth + 3 and depth:
                        self.assertTrue(reset and enable)
                        self.assertEqual(result, (0, 0, 0, 0))

    def test_pre_and_post_edge_checks_are_both_generated(self):
        case = dict(width=5, tag_width=3, coord_width=7, count=3, mode=0, module='Native')
        candidate = dict(layout='fields', signed_mode='legacy', default_count=1, module='Candidate')
        bench, cycles = C.testbench(case, [candidate])
        self.assertEqual(cycles, len(C.stimulus(case)))
        self.assertIn('before-edge cycle=1', bench)
        self.assertNotIn('before-edge cycle=0', bench)
        self.assertIn('after-edge cycle=0', bench)
        self.assertIn('clk = 0; reset = 1; enable = 1;', bench)


class MutationContractTests(unittest.TestCase):
    def test_reset_priority_mutation_preserves_enable_when_reset_is_low(self):
        rtl = "always @(posedge clk) begin\nif(enable) begin\nif(reset) q <= 0; else q <= d;\nend\nend\n"
        changed = C.mutate(rtl, 'reset-overrides-enable')
        self.assertEqual(changed, rtl.replace('if(enable)', 'if(enable || reset)'))
        for enable in (False, True):
            for reset in (False, True):
                native_update = enable
                mutation_update = enable or reset
                self.assertEqual(native_update != mutation_update, reset and not enable)

    def test_latency_bypass_rewires_all_record_leaves(self):
        rtl = '\n'.join('assign delayed_' + name + ' = saved_' + name + ';'
                        for name in ('key', 'tag', 'x', 'y'))
        changed = C.mutate(rtl, 'bypass-latency')
        for name in ('key', 'tag', 'x', 'y'):
            self.assertIn('assign delayed_' + name + ' = selected_' + name + ';', changed)
        with self.assertRaisesRegex(RuntimeError, 'anchor'):
            C.mutate('assign delayed_key = saved_key;', 'bypass-latency')

    def test_every_mutation_rejects_a_missing_anchor(self):
        for control in C.MUTATIONS:
            with self.subTest(control=control):
                with self.assertRaisesRegex(RuntimeError, 'anchor'):
                    C.mutate('module absent; endmodule', control)

    def test_reset_entry_and_induction_keep_distinct_initial_state_contracts(self):
        reset_script = C.setup([Path('original.v'), Path('miter.v')])
        induction_script = C.setup([Path('original.v'), Path('miter.v')], True)
        self.assertNotIn('zinit', reset_script)
        self.assertNotIn('set-init-zero', reset_script)
        self.assertIn('zinit -all', induction_script)
        self.assertIn('opt -full -keepdc', induction_script)


class EvidenceContractTests(unittest.TestCase):
    def manifest(self):
        candidates = [dict(layout=l, signed_mode=s, default_count=d,
                           module=f'C_{l}_{s}_{d}', file=f'C_{l}_{s}_{d}.v')
                      for l, s, d in sorted(C.PROFILES)]
        cases = [dict(zip(('width', 'tag_width', 'coord_width', 'count', 'mode'), values),
                      id=f's{i}', module=f'R{i}', file=f'R{i}.v')
                 for i, values in enumerate(sorted(C.SHAPES))]
        return dict(schema=1, scope=C.SCOPE, candidates=candidates, cases=cases)

    def test_complete_original_matrix_still_validates(self):
        C.validate_manifest(self.manifest())

    def test_candidate_cannot_replace_native_reference_identity(self):
        for key, message in (('module', 'module identities'), ('file', 'artifact identities')):
            manifest = self.manifest()
            manifest['cases'][0][key] = manifest['candidates'][0][key]
            with self.assertRaisesRegex(RuntimeError, message):
                C.validate_manifest(manifest)

    def test_bool_cannot_impersonate_integer_default(self):
        manifest = self.manifest()
        one = next(c for c in manifest['candidates'] if c['default_count'] == 1)
        one['default_count'] = True
        with self.assertRaisesRegex(RuntimeError, 'non-integer candidate'):
            C.validate_manifest(manifest)

    def test_case_identity_cannot_escape_evidence_directory(self):
        manifest = self.manifest()
        manifest['cases'][0]['id'] = '../../outside'
        with self.assertRaisesRegex(RuntimeError, 'invalid case identity'):
            C.validate_manifest(manifest)

    def test_same_directory_is_not_independent_A_B_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'evidence.json').write_text('stale certificate')
            with self.assertRaisesRegex(RuntimeError, 'two distinct artifact directories'):
                C.qualify(root, root)
            self.assertFalse((root / 'evidence.json').exists())

    def test_tool_failure_cannot_leave_a_stale_success_certificate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'evidence.json').write_text('stale certificate')
            with patch.object(C.shutil, 'which', return_value=None):
                with self.assertRaisesRegex(RuntimeError, 'required tool is missing'):
                    C.qualify(root, root / 'independent')
            self.assertFalse((root / 'evidence.json').exists())


if __name__ == '__main__':
    unittest.main()
