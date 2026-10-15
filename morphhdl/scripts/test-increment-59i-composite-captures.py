#!/usr/bin/env python3
"""Independent checker regressions; these do not count as HDL qualification."""
import copy
import importlib.util
import random
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('capture_checker', Path(__file__).with_name('check-increment-59i-composite-captures.py'))
C = importlib.util.module_from_spec(spec)
spec.loader.exec_module(C)


def manifest():
    return {'schema': 1, 'scope': C.SCOPE,
        'candidates': [{'layout': a, 'signed': b, 'module': f'C_{a}_{b}', 'file': f'C_{a}_{b}.v'}
            for a, b in sorted(C.PROFILES)],
        'cases': [dict(zip(C.KEYS, s), id=f's{i}', module=f'N{i}', file=f'N{i}.v')
            for i, s in enumerate(sorted(C.SHAPES))]}


def recursive_oracle(case, sample):
    w, t, c, n, mode = [case[k] for k in C.KEYS]
    def unpack(raw, sizes):
        offsets = [sum(sizes[:i]) for i in range(len(sizes))]
        return [tuple((raw >> (lane * sum(sizes) + offset)) & ((1 << width) - 1)
            for offset, width in zip(offsets, sizes)) for lane in range(n)]
    def select(a, b, signed=False):
        if not mode:
            a, b = b, a
        if signed:
            sign = lambda x: x if x < (1 << (w - 1)) else x - (1 << w)
            return ((sign(a[0]) + sign(b[1]) + sign(sample['offset'])) % (1 << w),
                    (sign(a[1]) - sign(b[0])) % (1 << w))
        key = min(a[0], b[0]) if sample['choose'] == mode else max(a[0], b[0])
        return (key, a[1] ^ b[1] ^ sample['mask'],
            ((a[2] + b[3]) % (1 << c)) ^ sample['biasA'],
            ((a[3] - b[2]) % (1 << c)) ^ sample['biasB'])
    def tree(rows, signed=False):
        if len(rows) == 1:
            return rows[0]
        # A complete power-of-two left subtree, with the remaining right
        # subtree, is independent of the checker's iterative round schedule.
        split = 1 << ((len(rows) - 1).bit_length() - 1)
        return select(tree(rows[:split], signed), tree(rows[split:], signed), signed)
    unsigned = tree(unpack(sample['records'], (w, t, c, c)))
    signed = tree(unpack(sample['signedRecords'], (w, w)), True)
    return dict(zip(('result_key', 'result_tag', 'result_x', 'result_y',
        'signedResult_real', 'signedResult_imag'), unsigned + signed))


class CaptureCheckerTests(unittest.TestCase):
    def test_complete_matrix_and_rejection_inventory(self):
        C.validate(manifest())
        C.self_test()

    def test_bool_schema_and_shape_are_not_integers(self):
        for column in ('schema', *C.KEYS):
            m = manifest()
            if column == 'schema': m[column] = True
            else: m['cases'][0][column] = True
            with self.assertRaises(RuntimeError): C.validate(m)

    def test_candidates_and_references_must_be_independent(self):
        for key in ('file', 'module'):
            m = manifest(); m['cases'][0][key] = m['candidates'][0][key]
            with self.assertRaises(RuntimeError): C.validate(m)
        m = manifest(); m['cases'][0]['id'] = '../escaped'
        with self.assertRaises(RuntimeError): C.validate(m)

    def test_wire_layout_contains_each_input_bit_once(self):
        for case in manifest()['cases']:
            lines, bindings = C.field_wires(case, 'x_')
            self.assertEqual(len(bindings), 6)
            self.assertTrue(all(s.startswith(('wire ', 'assign ')) for s in lines))
            for port, leaves in C.fields(case).items():
                assignments = [s for s in lines if s.startswith('assign x_' + port + '_')]
                import re
                covered = []
                for s in assignments:
                    for start, width in re.findall(r'\[(\d+) \+: (\d+)\]', s):
                        covered.extend(range(int(start), int(start) + int(width)))
                self.assertEqual(sorted(covered), list(range(C.inputs(case)[port])))

    def test_model_matches_recursive_integer_oracle(self):
        randomizer = random.Random(590991)
        for case in manifest()['cases']:
            for _ in range(32):
                data = {k: randomizer.getrandbits(w) for k, w in C.inputs(case).items()}
                self.assertEqual(C.reference(case, data), recursive_oracle(case, data))

    def test_singleton_never_reads_capture_values(self):
        case = dict(zip(C.KEYS, (5, 3, 7, 1, 1)))
        data = {k: 1 for k in C.inputs(case)}
        expected = C.reference(case, data)
        changed = {**data, **{k: (1 << C.inputs(case)[k]) - 1
            for k in ('biasA', 'biasB', 'mask', 'offset', 'choose')}}
        self.assertEqual(expected, C.reference(case, changed))

    def test_mutations_change_rhs_not_interfaces_or_targets(self):
        source = "input wire [4:0] biasA, biasB;\nassign biasA = other;\nassign result = biasA ^ biasB;\n"
        changed = C.mutate(source, 'capture-swap')
        self.assertIn('input wire [4:0] biasA, biasB;', changed)
        self.assertIn('assign biasA = other;', changed)
        self.assertIn('assign result = biasB ^ biasA;', changed)
        for control in C.CONTROLS:
            with self.assertRaises(RuntimeError): C.mutate('module absent; endmodule', control)

    def test_failed_and_focused_runs_remove_stale_proof(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); report = root / 'evidence.json'; report.write_text('stale proof')
            with self.assertRaises(RuntimeError): C.qualify(root, root)
            self.assertFalse(report.exists())


if __name__ == '__main__':
    unittest.main()
