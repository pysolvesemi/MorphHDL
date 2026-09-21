#!/usr/bin/env python3
"""Report-shape and historical-preservation regressions, not hardware execution."""
from __future__ import annotations
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
COMBINED_PARENT='883c5d8f088a0e2eab35592cf171d87792d30bf4'
COMBINED_WORKFLOW='.github/workflows/increment-59i-combined-closure.yml'
LEXICAL_OLD="                    'ParameterizedVerilogStructuralLexicalTests': 6,\n"
LEXICAL_CURRENT="                    'ParameterizedVerilogStructuralLexicalTests': 7,\n"
BUDGET_OLD="    timeout-minutes: 240\n"
BUDGET_CURRENT="    timeout-minutes: 360\n"
spec=importlib.util.spec_from_file_location('inventory',ROOT/'morphhdl/scripts/check-increment-59i-regression-inventory.py')
M=importlib.util.module_from_spec(spec);spec.loader.exec_module(M)

def restore_combined_workflow(text):
    if text.count(LEXICAL_CURRENT) != 1:
        raise AssertionError('expected one reviewed lexical inventory update')
    if text.count(BUDGET_CURRENT) != 1:
        raise AssertionError('expected one reviewed integration job budget update')
    restored=text.replace(LEXICAL_CURRENT,LEXICAL_OLD).replace(BUDGET_CURRENT,BUDGET_OLD)
    old=M.git(ROOT,'show',COMBINED_PARENT+':'+COMBINED_WORKFLOW).decode()
    if restored != old:
        raise AssertionError('combined workflow changed outside the reviewed inventory count and job budget')
    return restored


def verify_59h_partition(text):
    """Every original step stays exact; only the audited scheduling boundary moves."""
    path='.github/workflows/increment-59h-nested-owners.yml'
    old=M.git(ROOT,'show',COMBINED_PARENT+':'+path).decode()
    import re
    def steps(job):
        chunks=re.split(r'(?=^      - )',job,flags=re.M)
        return chunks[0],chunks[1:]
    old_header,old_steps=steps(old.split('  validate:\n',1)[1])
    prefix,body=text.split('jobs:\n',1)
    if prefix != old.split('jobs:\n',1)[0]:
        raise AssertionError('59h workflow triggers, permissions or concurrency changed')
    source,validate=body.split('  validate:\n',1)
    source_header,source_steps=steps(source)
    validate_header,validate_steps=steps(validate)
    condition=old_header[old_header.index('    if: >-'):old_header.index('    name: ')]
    expected_header='  source:\n'+condition+'''    name: Nested reduction owner source audits
    runs-on: ubuntu-latest
    timeout-minutes: 360
    container:
      image: ghcr.io/spinalhdl/docker:v1.2.0
    outputs:
      head: ${{ steps.identity.outputs.head }}
      tree: ${{ steps.identity.outputs.tree }}
    steps:
'''
    identity='''      - name: Identify the successfully audited source
        id: identity
        shell: bash
        run: |
          set -euo pipefail
          printf 'head=%s\\n' "$(git rev-parse HEAD)" >> "$GITHUB_OUTPUT"
          printf 'tree=%s\\n' "$(git rev-parse 'HEAD^{tree}')" >> "$GITHUB_OUTPUT"
'''
    evidence='''      - name: Retain source audit evidence
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: increment-59h-source-${{ github.run_attempt }}
          path: |
            target/increment-59h-nested-owners
            target/increment-59h-source-scope
            target/increment-59h-count-case
          if-no-files-found: error
          retention-days: 30

'''
    prepare='''      - name: Preserve the same successfully audited source for this Scala lane
        shell: bash
        env:
          AUDITED_HEAD: ${{ needs.source.outputs.head }}
          AUDITED_TREE: ${{ needs.source.outputs.tree }}
        run: |
          set -euo pipefail
          test "$(git rev-parse HEAD)" = "$AUDITED_HEAD"
          test "$(git rev-parse 'HEAD^{tree}')" = "$AUDITED_TREE"
          output="$GITHUB_WORKSPACE/target/increment-59h-nested-owners"
          mkdir -p "$output/bootstrap"
          git rev-parse HEAD > "$output/head.txt"
          git archive HEAD | gzip > "$output/source.tar.gz"
'''
    if source_header != expected_header:
        raise AssertionError('59h source job must remain required and source-bound')
    if source_steps != [old_steps[0],old_steps[1],identity,evidence]:
        raise AssertionError('59h complete original source audit or success receipt changed')
    if validate_header != '    needs: source\n'+old_header:
        raise AssertionError('59h Scala lanes must depend on successful same-run source audit')
    if validate_steps != [old_steps[0],prepare]+old_steps[2:]:
        raise AssertionError('59h exact tests, A/B generation, proofs or evidence changed')

class Reports(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.expected={'morphhdl':{'probe.Suite':['negative','positive']}}
        self.file=self.root/'morphhdl/target/test-reports/TEST-probe.Suite.xml'
        self.file.parent.mkdir(parents=True)
        self.suite=ET.Element('testsuite',name='probe.Suite',tests='2',failures='0',errors='0',skipped='0')
        for name in ('negative','positive'):ET.SubElement(self.suite,'testcase',name=name,classname='probe.Suite')
        self.save()
    def save(self):ET.ElementTree(self.suite).write(self.file)
    def check(self):return M.reports(self.root,self.expected)
    def reject(self):
        with self.assertRaises((RuntimeError,ET.ParseError)):self.check()
    def test_exact_positive_and_negative_cases(self):self.assertEqual(M.summary(self.check())['morphhdl']['tests'],2)
    def test_missing_case(self):self.suite.remove(self.suite[0]);self.save();self.reject()
    def test_same_count_duplicate_case(self):self.suite[1].set('name','negative');self.save();self.reject()
    def test_same_count_renamed_case(self):self.suite[1].set('name','other');self.save();self.reject()
    def test_unexpected_case(self):ET.SubElement(self.suite,'testcase',name='extra',classname='probe.Suite');self.save();self.reject()
    def test_inconsistent_count(self):self.suite.set('tests','1');self.save();self.reject()
    def test_suite_failure(self):self.suite.set('failures','1');self.save();self.reject()
    def test_suite_error(self):self.suite.set('errors','1');self.save();self.reject()
    def test_suite_skip(self):self.suite.set('skipped','1');self.save();self.reject()
    def test_missing_status(self):del self.suite.attrib['skipped'];self.save();self.reject()
    def test_hidden_failure_node(self):ET.SubElement(self.suite[0],'failure');self.save();self.reject()
    def test_hidden_error_node(self):ET.SubElement(self.suite[0],'error');self.save();self.reject()
    def test_hidden_skipped_node(self):ET.SubElement(self.suite[0],'skipped');self.save();self.reject()
    def test_wrong_owner(self):self.suite[0].set('classname','other');self.save();self.reject()
    def test_duplicate_suite(self):(self.file.parent/'duplicate.xml').write_bytes(self.file.read_bytes());self.reject()
    def test_foreign_suite(self):self.suite.set('name','wrong');self.save();self.reject()
    def test_missing_suite(self):self.file.unlink();self.reject()
    def test_wrong_filename(self):self.file.rename(self.file.with_name('different.xml'));self.reject()
    def test_linked_report(self):raw=self.file.read_bytes();self.file.unlink();p=self.root/'report';p.write_bytes(raw);self.file.symlink_to(p);self.reject()
    def test_linked_report_parent(self):p=self.root/'alternate';self.file.parent.rename(p);self.file.parent.symlink_to(p);self.reject()
    def test_malformed_xml(self):self.file.write_text('<testsuite>');self.reject()
    def test_xml_entity(self):self.file.write_bytes(b'<!DOCTYPE x [<!ENTITY q "x">]>'+self.file.read_bytes());self.reject()

class Preservation(unittest.TestCase):
    def test_59h_partition_retains_all_original_source_test_and_proof_obligations(self):
        verify_59h_partition((ROOT/'.github/workflows/increment-59h-nested-owners.yml').read_text())
    def test_59h_partition_rejects_optional_stale_missing_and_changed_obligations(self):
        current=(ROOT/'.github/workflows/increment-59h-nested-owners.yml').read_text()
        changes=(
            ('    needs: source\n',''),
            ('  source:\n','  source:\n    continue-on-error: true\n'),
            ('      head: ${{ steps.identity.outputs.head }}','      head: stale'),
            ('        id: identity\n','        id: identity\n        if: always()\n'),
            ('          test "$(git rev-parse HEAD)" = "$AUDITED_HEAD"\n',''),
            ('          ref: ${{ github.event.pull_request.head.sha || github.sha }}','          ref: parameterized-verilog'),
            ('          python3 morphhdl/scripts/test-inherited-source-audit-timeouts.py\n',''),
            ('          python3 morphhdl/scripts/check-increment-59h-nested-owners.py "$output/a" "$output/b"\n',''),
            ('          python3 morphhdl/scripts/check-increment-59b-publication.py "$output/inherited-a" "$output/inherited-b"\n',''),
            ('          if-no-files-found: error','          if-no-files-found: warn'),
        )
        for index,(before,after) in enumerate(changes):
            self.assertIn(before,current)
            with self.subTest(mutation=index),self.assertRaises(AssertionError):
                verify_59h_partition(current.replace(before,after,1))
    def test_combined_workflow_tracks_all_seven_lexical_cases(self):
        current=(ROOT/COMBINED_WORKFLOW).read_text()
        self.assertEqual(restore_combined_workflow(current).count(LEXICAL_OLD),1)
        source=(ROOT/'morphhdl/src/test/scala/spinal/core/internals/ParameterizedVerilogStructuralLexicalTests.scala').read_text()
        self.assertEqual(source.count('  test("'),7)
    def test_combined_workflow_rejects_unreviewed_count_or_other_change(self):
        current=(ROOT/COMBINED_WORKFLOW).read_text()
        for count in (0,6,8):
            with self.subTest(count=count),self.assertRaises(AssertionError):
                restore_combined_workflow(current.replace(LEXICAL_CURRENT,LEXICAL_CURRENT.replace(': 7,',f': {count},')))
        for minutes in (240,359,361):
            with self.subTest(minutes=minutes),self.assertRaises(AssertionError):
                restore_combined_workflow(current.replace(BUDGET_CURRENT,f'    timeout-minutes: {minutes}\n'))
        with self.assertRaises(AssertionError):restore_combined_workflow(current+'\n')
    def test_contract_hash_and_complete_inventory(self):
        raw=(ROOT/M.CONTRACT).read_bytes();self.assertEqual(hashlib.sha256(raw).hexdigest(),M.CONTRACT_SHA256)
        v=json.loads(raw)
        self.assertEqual(sum(len(c) for p in v['projects'].values() for c in p.values()),2307)
        self.assertEqual(sum(len(p) for p in v['projects'].values()),230)
        self.assertEqual(sum(sum(p.values()) for p in v['historical_counts'].values()),2064)
        for project,suites in v['historical_counts'].items():
            self.assertTrue(set(suites)<=set(v['projects'][project]))
            for name,count in suites.items():self.assertGreaterEqual(len(v['projects'][project][name]),count)
    def test_original_lexical_case_is_restored_byte_for_byte(self):
        path='morphhdl/src/test/scala/spinal/core/internals/ParameterizedVerilogStructuralLexicalTests.scala'
        old=M.git(ROOT,'show','5374b958f8f94114b1ed46a3069845d580886da9:'+path).decode()
        block=old[old.index('  test("signed declaration qualifiers never connect independent dependency sets")'):old.rfind('\n}')]
        self.assertIn(block,(ROOT/path).read_text())
    def test_exact_historical_cases_come_from_immutable_source(self):
        import re
        v=json.loads((ROOT/M.CONTRACT).read_text())
        for name,expected in v['retained_cases'].items():
            path='morphhdl/src/test/scala/'+name.replace('.','/')+'.scala'
            old=M.git(ROOT,'show',v['historical_base']+':'+path).decode()
            self.assertEqual(sorted(re.findall(r'\btest\("([^"\n]+)"\)',old)),expected)
    def test_original_historical_catalog_is_unchanged(self):
        v=json.loads((ROOT/M.CONTRACT).read_text())
        raw=M.git(ROOT,'show',v['historical_base']+':morphhdl/scripts/check-increment-60f-artifacts.py')
        self.assertEqual(M.digest(raw),v['historical_checker_sha256'])
    def test_test_payloads_remain_pinned(self):
        v=json.loads((ROOT/M.CONTRACT).read_text())
        for p,digest in v['test_source_sha256'].items():self.assertEqual(M.digest((ROOT/p).read_bytes()),digest,p)
    def test_source_checked_before_reading_contract_or_reports(self):
        with patch.object(M,'source_review',side_effect=RuntimeError('current source rejected')) as verify,patch.object(M,'regular') as regular:
            with self.assertRaisesRegex(RuntimeError,'current source rejected'):M.specification(ROOT)
            verify.assert_called_once_with(ROOT);regular.assert_not_called()
    def test_no_module_cache_may_cache_authorization(self):
        with patch.object(M,'source_review',side_effect=RuntimeError('verify')) as verify:
            for _ in range(2):
                with self.assertRaises(RuntimeError):M.specification(ROOT)
            self.assertEqual(verify.call_count,2)
    def test_missing_historical_aggregation_rejected(self):
        # Valid current receipt cannot stand in for the old checker's result.
        v=json.loads((ROOT/M.CONTRACT).read_text())
        with tempfile.TemporaryDirectory() as d,patch.object(M,'specification',return_value=('head',v)),patch.object(M,'reports',return_value={}):
            p=Path(d);(p/'increment-59i-test-inventory.json').write_text(json.dumps(dict(head='head',complete={})))
            with self.assertRaises(FileNotFoundError):M.finish(ROOT,p)

if __name__=='__main__':unittest.main(verbosity=2)
