#!/usr/bin/env python3
"""Independent manifest, transport and exact-source regression controls."""
import copy
import importlib.util
import json
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

C = load('nested_results_checker', Path(__file__).with_name('check-increment-59i-nested-results.py'))
R = load('nested_results_source', Path(__file__).with_name('check-increment-59i-source-review.py'))

class NestedResultCheckerTests(unittest.TestCase):
    def manifest(self):
        return {'schema':1,'scope':C.SCOPE,
            'candidates':[dict(layout=l,signed_mode=s,default_count=d,module=f'C{i}',file=f'C{i}.v')
                          for i,(l,s,d) in enumerate(sorted(C.PROFILES))],
            'cases':[dict(zip(C.KEYS,values),id=f'x{i}',module=f'R{i}',file=f'R{i}.v')
                     for i,values in enumerate(sorted(C.SHAPES))]}

    def test_complete_matrix(self):
        C.self_test()

    def test_manifest_rejects_forged_identities_and_domains(self):
        for field, value in (('id','../escape'),('module','bad-name'),('count',True),('inner',0)):
            m=self.manifest();m['cases'][0][field]=value
            with self.assertRaises(RuntimeError): C.validate(m)
        m=self.manifest();m['schema']=True
        with self.assertRaises(RuntimeError): C.validate(m)
        m=self.manifest();m['candidates'][0]['default_count']=True
        with self.assertRaises(RuntimeError): C.validate(m)
        for key in ('module','file'):
            m=self.manifest();m['cases'][0][key]=m['candidates'][0][key]
            with self.assertRaises(RuntimeError): C.validate(m)

    def test_independent_fields_and_all_single_bit_payloads_are_retained(self):
        for values in sorted(C.SHAPES):
            case=dict(zip(C.KEYS,values));word,fields=C.geometry(case)
            for bit in range(word):
                # COUNT copies of the same full word must return it in either mode.
                packed=sum((1<<bit)<<(lane*word) for lane in range(case['count']))
                self.assertEqual(C.expected(case,packed),1<<bit)
            widths={name:sum(w for _,w in slots) for name,slots in fields.items()}
            self.assertEqual(widths['samples_signed'],case['sw']*case['inner'])
            self.assertEqual(widths['samples_unsigned'],case['uw']*case['inner'])
            self.assertEqual(widths['tag'],case['tw'])

    def test_actual_rtl_mutations_require_complete_and_distinct_anchors(self):
        lines=['if (((MODE) > (0))) begin : g_max',
               'input wire records_samples_unsigned, records_samples_bitsValue;']
        for owner in (1,2):
            for lane in (0,1,2):
                for field in ('unsigned','signed'):
                    lines.append(f'selected_samples_{field}[{lane}*U_W +: U_W] = morphhdl_balanced_{owner}_result_leaf_{lane*4+2};')
        text='\n'.join(lines)
        for control in C.CONTROLS:
            self.assertNotEqual(C.mutate(text,control),text)
        for control in ('inner-index-swap','signed-bit-loss'):
            with self.assertRaises(RuntimeError): C.mutate('\n'.join(lines[:-2]),control)
        with self.assertRaises(RuntimeError):
            C.mutate(text.replace('result_leaf_6','result_leaf_2'),'inner-index-swap')

    def test_new_review_spans_reject_removed_ownership_and_process_guards(self):
        entries=R.load_contract(ROOT)
        capture_entries=R.load_capture_contract(ROOT)
        controls={
            'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala':
                ('record.published', '!record.lexicalOwner.isModuleScope', 'assignments.forall', 'nestedVectors(record.output)'),
            'morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogVecs.scala':
                ('if (!scopedResult)', 'parsed.operator != "="', 'owners.size == 1', 'Some(name)',
                 'start < assignmentLine', 'lines(start).trim == "always @(*) begin"')}
        for path,tokens in controls.items():
            baseline=R.baseline_source(ROOT,path);source=(ROOT/path).read_bytes();entry=entries[path]
            # The current Backend also contains the later capture layer. Reverse
            # that exact layer first, then exercise the still-sealed nested-result
            # spans against the source version they review. Capture-layer mutation
            # coverage remains independent in test-increment-59i-capture-review.py.
            if path in capture_entries:
                source=R.restore_reviewed(capture_entries[path],
                    R.capture_baseline_source(ROOT,path),source)
            self.assertEqual(R.restore_reviewed(entry,baseline,source),baseline)
            edit=next(e for e in entry['edits'] if e['id'].startswith('59i-nested-result-'))
            for token in tokens:
                self.assertIn(token,edit['after'])
                position=edit['after_start']+edit['after'].encode().index(token.encode())
                changed=source[:position]+b'/* unreviewed bypass */'+source[position:]
                with self.assertRaises(RuntimeError): R.restore_reviewed(entry,baseline,changed)

if __name__=='__main__':unittest.main(verbosity=2)
