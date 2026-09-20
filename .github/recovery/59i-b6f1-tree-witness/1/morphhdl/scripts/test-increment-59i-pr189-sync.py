#!/usr/bin/env python3
"""Real-history tests of the exact 59i + merged PR189 source union."""
from __future__ import annotations
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import types
import unittest

ROOT = Path(__file__).resolve().parents[2]
PARENT = '37d1629f9b78c4d9cd6646abee9962fdd046e413'
TARGET = 'e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d'
PREVIOUS = 'f42641880e0645f0c997ecedabd031bf8948bbfa'
COMMON = '27af65abbee0d2334d6be7a6e4e2408b8af32fd9'
HELPER = 'morphhdl/scripts/check-increment-59i-production-successor.py'
CONTRACT = 'morphhdl/contracts/increment-59i-production-successor.json'
CDC = 'morphhdl/scripts/check-cdc-successor-source.py'
INC61 = 'morphhdl/scripts/check-increment-61-source-review.py'

def git(root, *args):
    p = subprocess.run(['git', '--literal-pathspecs', *args], cwd=root,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    if p.returncode: raise RuntimeError(p.stderr.decode(errors='replace'))
    return p.stdout

def load(root, path):
    m = types.ModuleType('pr189_sync_test')
    m.__file__ = str(root / path)
    exec(compile((root / path).read_bytes(), m.__file__, 'exec'), m.__dict__)
    return m

class Sync(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.head = git(ROOT, 'rev-parse', 'HEAD').decode().strip()
        cls.tmp = tempfile.TemporaryDirectory(prefix='59i-pr189-sync-test-')
        cls.root = Path(cls.tmp.name) / 'source'
        git(ROOT, 'worktree', 'add', '--detach', str(cls.root), cls.head)
        cls.review = load(cls.root, HELPER)
        cls.value = cls.review.verify(cls.root)
    @classmethod
    def tearDownClass(cls):
        git(ROOT, 'worktree', 'remove', '--force', str(cls.root))
        cls.tmp.cleanup()
    def tearDown(self):
        git(self.root, 'reset', '--hard', self.head)
        git(self.root, 'clean', '-fdx')
    def verify(self):
        return self.review.verify(self.root)
    def test_exact_merged_target_is_source_parent(self):
        self.assertEqual(self.review.CONTINUATION_PARENT, PREVIOUS)
        prior = __import__('json').loads(git(self.root, 'show', PREVIOUS + ':' + CONTRACT))
        self.assertEqual(git(self.root, 'rev-list', '--parents', '-n', '1', prior['source_commit']).decode().split(),
            [prior['source_commit'], PARENT, TARGET])
        self.assertEqual(self.review.CONTINUATION_TARGET, TARGET)
        self.assertEqual(git(self.root, 'rev-list', '--parents', '-n', '1', self.value['source_commit']).decode().split(),
            [self.value['source_commit'], PREVIOUS, TARGET])
    def test_pr189_and_pr187_histories_preserved(self):
        for sha in (TARGET, 'a754a1f2f84b31544a619f8c0456dc23a27e7b88',
                    'f5049ae2abfe5a47cd1fac3574ea08d630bd183f', PARENT):
            git(self.root, 'merge-base', '--is-ancestor', sha, 'HEAD')
    def test_previous_source_seal_bytes_preserved(self):
        self.assertEqual(git(self.root, 'show', self.value['source_commit'] + ':' + CONTRACT),
                         git(self.root, 'show', PREVIOUS + ':' + CONTRACT))
    def test_target_contracts_preserved_exactly(self):
        for path in ('morphhdl/contracts/increment-61-source-review.json',
                     'morphhdl/contracts/increment-62-wa08-source-overlay.json',
                     'morphhdl/contracts/lane-when-source-scope.json'):
            self.assertEqual((self.root/path).read_bytes(), git(self.root, 'show', TARGET+':'+path))
    def test_all_runtime_files_match_exact_three_way_merge(self):
        paths = set(git(self.root, 'diff', '--name-only', COMMON, PARENT).decode().splitlines())
        paths |= set(git(self.root, 'diff', '--name-only', COMMON, TARGET).decode().splitlines())
        def read(ref, path):
            return self.review.frozen(self.root, ref, path)
        for path in sorted(p for p in paths if '/src/main/' in p):
            base, ours, theirs = (read(ref,path) for ref in (COMMON,PARENT,TARGET))
            if ours == base: expected = theirs
            elif theirs == base or ours == theirs: expected = ours
            else:
                with tempfile.TemporaryDirectory() as d:
                    names=[]
                    for name,raw in (('ours',ours),('base',base),('theirs',theirs)):
                        self.assertIsNotNone(raw, path)
                        f=Path(d)/name;f.write_bytes(raw);names.append(str(f))
                    proc=subprocess.run(['git','merge-file','-p',*names],capture_output=True,timeout=30)
                    self.assertEqual(proc.returncode,0,path)
                    expected=proc.stdout
            actual=read(self.head,path)
            self.assertEqual(actual,expected,path)
    def test_all_59i_reduction_mechanisms_unchanged_by_sync(self):
        for path in git(self.root,'ls-tree','-r','--name-only',PARENT).decode().splitlines():
            if '/src/main/' in path and 'TypedBalancedReduction' in path:
                self.assertEqual((self.root/path).read_bytes(),git(self.root,'show',PARENT+':'+path),path)
    def test_compact_and_blackbox_production_exact_target(self):
        for path in ('core/src/main/scala/spinal/core/ElaborationPublicationValue.scala',
                     'core/src/main/scala/spinal/core/ElaborationProductDomain.scala',
                     'core/src/main/scala/spinal/core/internals/ParameterizedBlackBoxGeneric.scala',
                     'morphruntime/src/main/scala/spinal/core/ElabValue.scala'):
            self.assertEqual((self.root/path).read_bytes(),git(self.root,'show',TARGET+':'+path),path)
    def test_original_27_shard_commands_and_limits_retained(self):
        path='morphhdl/scripts/check-increment-59i-audit-shards.py'
        self.assertEqual((self.root/path).read_bytes(),git(self.root,'show',PARENT+':'+path))
    def test_cumulative_wa08_projection_uses_actual_target(self):
        overlay=load(self.root,'morphhdl/scripts/check-increment-62-wa08-source-overlay.py')
        integration=overlay.integration_review(self.root)
        for path in (CDC,INC61,'frontend/src/main/scala/morphhdl/frontend/HdlInt.scala'):
            actual=overlay.overlay_target_source(self.root,integration,path,(self.root/path).read_bytes())
            self.assertEqual(actual,git(self.root,'show',TARGET+':'+path))
    def test_live_cdc_source_mutation_rejected_after_warm_verification(self):
        self.verify()
        f=self.root/'core/src/main/scala/spinal/core/ElaborationPublicationValue.scala'
        f.write_bytes(f.read_bytes()+b'\n// unreviewed sync mutation\n')
        with self.assertRaises(RuntimeError): self.verify()
    def test_live_mode_mutation_rejected(self):
        f=self.root/'morphruntime/src/main/scala/spinal/core/ElabValue.scala'
        f.chmod(0o755)
        with self.assertRaises(RuntimeError): self.verify()
    def test_target_contract_mutation_rejected(self):
        f=self.root/'morphhdl/contracts/increment-62-wa08-source-overlay.json'
        f.write_bytes(f.read_bytes()+b'\n')
        with self.assertRaises(RuntimeError): self.verify()
    def test_staged_cdc_change_with_restored_worktree_rejected(self):
        path='core/src/main/scala/spinal/core/ElaborationPublicationValue.scala';f=self.root/path;raw=f.read_bytes()
        f.write_bytes(raw+b'\n// staged mutation\n');git(self.root,'add','--',path);f.write_bytes(raw)
        with self.assertRaises(RuntimeError): self.verify()
    def test_cdc_entry_checks_current_union_before_replay(self):
        load(self.root,CDC).verify(self.root)
        f=self.root/'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala'
        f.write_bytes(f.read_bytes()+b'\n// unreviewed 59i code\n')
        with self.assertRaises(RuntimeError):load(self.root,CDC).verify(self.root)
    def test_increment61_entry_checks_current_union(self):
        load(self.root,INC61).verify(self.root)
    def test_cdc_entry_rejects_removed_certificate(self):
        (self.root/HELPER).unlink();(self.root/CONTRACT).unlink()
        with self.assertRaisesRegex(RuntimeError,'certificate was removed'):
            load(self.root,CDC).verify(self.root)
    def test_untracked_compact_source_rejected(self):
        f=self.root/'repro/cdc-independent-parameters/src/main/scala/Unreviewed.scala'
        f.write_text('object Unreviewed\n')
        with self.assertRaises(RuntimeError):self.verify()
    def test_foreign_projected_bytes_rejected(self):
        with self.assertRaises(RuntimeError):
            self.review.target_source(self.root,INC61,b'unreviewed')

if __name__ == '__main__':
    if __import__('json').loads((ROOT / CONTRACT).read_bytes()).get('schema_version') in (4, 5):
        # Keep every original exact-merge assertion on its certified schema-3
        # source. The pinned router authenticates the whole current schema-4
        # checkout before and after that unchanged historical suite.
        relative = Path('morphhdl/scripts/test-increment-59i-continuation.py')
        path = ROOT / relative
        if any(ROOT.joinpath(*relative.parts[:index]).is_symlink()
                for index in range(1, len(relative.parts) + 1)) or not path.is_file() or path.stat().st_mode & 0o111:
            raise RuntimeError('PR189 historical router must be a regular non-executable file')
        raw = path.read_bytes()
        if hashlib.sha256(raw).hexdigest() != '5db304d9d9ae38fe6687c6628a3e0234ef26e063b8f9e80ba73e5769fe5cc31d':
            raise RuntimeError('PR189 historical router changed')
        route = types.ModuleType('authenticated_pr189_historical_route')
        route.__file__ = str(path)
        exec(compile(raw, str(path), 'exec'), route.__dict__)
        route.run_schema4_historical_continuation('pr189')
    else:
        unittest.main(verbosity=2)
