#!/usr/bin/env python3
"""Scheduling/evidence regressions; miniature shell jobs are not source qualification."""
from __future__ import annotations
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / 'morphhdl/scripts/check-increment-59i-audit-shards.py'
spec = importlib.util.spec_from_file_location('inherited_shards', PATH)
H = importlib.util.module_from_spec(spec)
spec.loader.exec_module(H)
WORKFLOW = (ROOT / H.WORKFLOW).read_text()


def step(text: str, name: str) -> str:
    anchor = '    - name: ' + name + '\n'
    assert text.count(anchor) == 1, 'missing/duplicate step ' + name
    body = text.split(anchor, 1)[1]
    body = re.split(r'\n(?:    - |  [a-z_]+:)', body, maxsplit=1)[0]
    match = re.search(r'      run: \|\n((?:(?:        [^\n]*|)\n?)*)', body)
    assert match is not None, 'missing run body ' + name
    return textwrap.dedent(match[1])


def commands(text: str) -> list[str]:
    return [line.strip() for line in text.splitlines()
            if re.match(r'\s*python3 (?:-B )?morphhdl/scripts/', line)]


def logged(name: str) -> bytes:
    start, stop = H.SHARDS[name]
    return ''.join(f'59I_AUDIT_{kind} {i} {H.sha(H.COMMANDS[i].encode())} {1000+i*2+j}\n'
        for i in range(start, stop) for j, kind in enumerate(('BEGIN', 'END'))).encode()


class WorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.before = subprocess.check_output(['git', 'show', H.BASELINE + ':' + H.WORKFLOW],
            cwd=ROOT, text=True, timeout=120)
        raw = cls.before.encode()
        assert hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() == \
            '15b42ea211d4bd5d58cf8bc6fb6c959fbb0e7fe2', 'immutable workflow baseline changed'

    def test_all_27_commands_preserved_once_in_original_partition_order(self):
        original = commands(step(self.before, 'Run exact composition and inherited source audits'))
        current = [command for shard in H.SHARDS
                   for command in commands(step(WORKFLOW, 'Run inherited audit shard ' + shard))]
        self.assertEqual(len(original), 27)
        self.assertEqual(list(H.COMMANDS), original)
        self.assertEqual(current, original)

    def test_preflight_commands_are_retained_with_additive_test(self):
        original = commands(step(self.before, 'Require committed source and existing immutable reviews'))
        current = commands(step(WORKFLOW, 'Require committed source and existing immutable reviews'))
        self.assertEqual(current[:-1], original)
        self.assertEqual(current[-1], 'python3 -B morphhdl/scripts/test-increment-59i-audit-shards.py')

    def test_native_parent_validation_is_byte_identical(self):
        name = 'Validate immutable native source reviews at both exact parents'
        self.assertEqual(step(WORKFLOW, name), step(self.before, name))
        for parent in ('ee2e7f2613e54f23158ce1eacae6028409a91ed6',
                       '2ebaa2ef5561eab35aa0ba9caced5c5a314d59f6'):
            self.assertEqual(WORKFLOW.count(parent), 2)

    def test_no_individual_test_or_job_budget_increase(self):
        self.assertIn('    timeout-minutes: 240\n', WORKFLOW)
        self.assertNotIn('timeout-minutes: 360', WORKFLOW)
        self.assertNotIn('continue-on-error', WORKFLOW)
        self.assertEqual(WORKFLOW.count('max-parallel: 2'), 1)
        self.assertIn('fail-fast: false', WORKFLOW)

    def test_each_matrix_lane_has_exactly_one_selected_shell_group(self):
        matrix = re.search(r'        shard:\n((?:        - [^\n]+\n)+)', WORKFLOW)[1]
        self.assertEqual(re.findall(r'- (.+)', matrix), list(H.SHARDS))
        self.assertEqual(re.findall(r"if: matrix.shard == '([^']+)'", WORKFLOW), list(H.SHARDS))

    def test_required_source_check_is_fail_closed_and_named_as_before(self):
        self.assertIn('  complete:\n    name: source\n    needs:\n    - source\n', WORKFLOW)
        self.assertIn("if: always() && (github.event_name != 'pull_request'", WORKFLOW)
        self.assertIn('SHARD_RESULT: ${{ needs.source.result }}', WORKFLOW)
        self.assertIn('test "$SHARD_RESULT" = success', WORKFLOW)
        self.assertIn('persist-credentials: false', WORKFLOW)
        self.assertEqual(WORKFLOW.count("ref: ${{ github.event.pull_request.head.sha || github.sha }}"), 2)

    def test_failed_cancelled_skipped_and_empty_needs_fail(self):
        body = step(WORKFLOW, 'Require successful completion of every matrix job')
        for result in ('failure', 'cancelled', 'skipped', ''):
            with self.subTest(result=result):
                p = subprocess.run(['bash', '-c', body], env=dict(os.environ, SHARD_RESULT=result),
                                   capture_output=True, timeout=10)
                self.assertNotEqual(p.returncode, 0)
        p = subprocess.run(['bash', '-c', body], env=dict(os.environ, SHARD_RESULT='success'), timeout=10)
        self.assertEqual(p.returncode, 0)

    def execute_shell(self, shard: str, failed: int | None = None) -> tuple[int, bytes]:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / 'bin').mkdir()
            (path / 'target/increment-59i-rollout-composition').mkdir(parents=True)
            fake = path / 'bin/python3'
            # Only the external audit program is replaced. The actual committed
            # Bash group, errexit/pipefail, tee and all completion markers run.
            fake.write_text('#!/bin/bash\n' + ('exit 0\n' if failed is None else
                f'if [[ "$*" == {shlex.quote(H.COMMANDS[failed].split(" ",1)[1])} ]]; then exit 19; fi\nexit 0\n'))
            fake.chmod(0o755)
            env = dict(os.environ, GITHUB_WORKSPACE=str(path), PATH=str(path/'bin')+os.pathsep+os.environ['PATH'])
            p = subprocess.run(['bash', '-c', step(WORKFLOW, 'Run inherited audit shard ' + shard)],
                               env=env, capture_output=True, timeout=15)
            return p.returncode, (path/'target/increment-59i-rollout-composition/source-audits.log').read_bytes()

    def test_all_real_shell_groups_produce_complete_receipts(self):
        for shard in H.SHARDS:
            with self.subTest(shard=shard):
                code, log = self.execute_shell(shard)
                self.assertEqual(code, 0)
                self.assertEqual(len(H.markers(shard, log)), H.SHARDS[shard][1]-H.SHARDS[shard][0])

    def test_nonzero_subprocess_cannot_be_hidden_by_tee(self):
        for shard, (first, last) in H.SHARDS.items():
            for failed in (first, last-1):
                with self.subTest(shard=shard, failed=failed):
                    code, log = self.execute_shell(shard, failed)
                    self.assertNotEqual(code, 0)
                    with self.assertRaisesRegex(RuntimeError, 'completion'):
                        H.markers(shard, log)

    def test_upload_preserves_failed_attempt_logs_and_does_not_overwrite(self):
        self.assertIn('increment-59i-inherited-source-qualification-shard-${{ matrix.shard }}-attempt-${{ github.run_attempt }}', WORKFLOW)
        self.assertEqual(WORKFLOW.count('if: always()'), 3) # aggregate job + both evidence uploads
        self.assertNotIn('overwrite: true', WORKFLOW)
        self.assertIn('pattern: increment-59i-inherited-source-qualification-shard-*', WORKFLOW)


class ReceiptTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.repo = self.base/'repo'
        self.repo.mkdir()
        for args in (['init', '-q'], ['config', 'user.name', 'shard fixture'],
                     ['config', 'user.email', 'shard@example.invalid']):
            subprocess.run(['git', *args], cwd=self.repo, check=True, capture_output=True)
        workflow = self.repo/H.WORKFLOW
        workflow.parent.mkdir(parents=True)
        workflow.write_text('sealed workflow fixture\n')
        subprocess.run(['git','add','.'],cwd=self.repo,check=True)
        subprocess.run(['git','commit','-qm','fixture'],cwd=self.repo,check=True)
        self.head = H.git(self.repo, 'rev-parse', 'HEAD')
        self.tree = H.git(self.repo, 'rev-parse', 'HEAD^{tree}')
        self.artifacts = self.base/'artifacts'
        self.artifacts.mkdir()
        self.paths = {}
        for shard in H.SHARDS:
            self.paths[shard] = self.make(shard, 1)

    def make(self, shard: str, attempt: int) -> Path:
        p = self.artifacts/(H.PREFIX+shard+'-attempt-'+str(attempt))
        p.mkdir()
        (p/'head.txt').write_text(self.head+'\n')
        (p/'tree.txt').write_text(self.tree+'\n')
        (p/'source-audits.log').write_bytes(logged(shard))
        H.record(self.repo, p, self.head, shard, '12345', attempt)
        return p

    def check(self, attempt: int = 1):
        return H.aggregate(self.repo, self.artifacts, self.head, '12345', attempt)

    def change(self, key: str, value, shard: str = 'composition'):
        p = self.paths[shard]/'receipt.json'
        doc = json.loads(p.read_text());doc[key]=value;p.write_text(json.dumps(doc))

    def test_complete_same_source_same_run_passes(self):
        result = self.check()
        self.assertEqual(result['original_commands'], 27)
        self.assertEqual(result['status'], 'all-shards-passed')

    def test_missing_shard_rejected(self):
        self.paths['60f'].rename(self.base/'hidden')
        with self.assertRaisesRegex(RuntimeError, 'missing shard'):self.check()

    def test_cancelled_latest_attempt_never_reuses_old_success(self):
        newer = self.artifacts/(H.PREFIX+'60f-attempt-2');newer.mkdir()
        (newer/'source-audits.log').write_text('cancelled before completion')
        with self.assertRaisesRegex(RuntimeError, 'missing or linked'):self.check(2)

    def test_missing_completion_receipt_rejected(self):
        (self.paths['60f']/'receipt.json').unlink()
        with self.assertRaisesRegex(RuntimeError, 'missing or linked'):self.check()

    def test_rerun_failed_job_retains_prior_successful_shards_of_same_run(self):
        self.make('60f',2)
        result=self.check(2)
        self.assertEqual([v['attempt'] for v in result['shards']], [1,1,1,2,1])

    def test_future_attempt_rejected(self):
        self.make('60f',2)
        with self.assertRaisesRegex(RuntimeError, 'future'):self.check()

    def test_old_head_rejected(self):
        self.change('head','0'*40)
        with self.assertRaisesRegex(RuntimeError, 'mixed-head'):self.check()

    def test_wrong_tree_rejected(self):
        self.change('tree','0'*40)
        with self.assertRaisesRegex(RuntimeError, 'mixed-head'):self.check()

    def test_other_run_rejected(self):
        self.change('run_id','12344')
        with self.assertRaisesRegex(RuntimeError, 'identity'):self.check()

    def test_wrong_workflow_rejected(self):
        self.change('workflow_sha256','0'*64)
        with self.assertRaisesRegex(RuntimeError, 'workflow identity'):self.check()

    def test_wrong_shard_rejected(self):
        self.change('shard','60f')
        with self.assertRaisesRegex(RuntimeError, 'identity'):self.check()

    def test_log_tampering_rejected(self):
        with (self.paths['60f']/'source-audits.log').open('ab') as f:f.write(b'changed')
        with self.assertRaisesRegex(RuntimeError, 'changed command log'):self.check()

    def test_nonzero_or_removed_command_rejected(self):
        value=json.loads((self.paths['60f']/'receipt.json').read_text())['commands']
        value[0]['returncode']=1
        self.change('commands',value,'60f')
        with self.assertRaisesRegex(RuntimeError, 'command receipt'):self.check()
        self.change('commands',value[1:],'60f')
        with self.assertRaisesRegex(RuntimeError, 'command receipt'):self.check()

    def test_incorrect_preflight_identity_rejected(self):
        (self.paths['60f']/'head.txt').write_text('0'*40)
        with self.assertRaisesRegex(RuntimeError, 'preflight identity'):self.check()

    def test_extra_or_unknown_artifact_rejected(self):
        (self.artifacts/'unexpected').mkdir()
        with self.assertRaisesRegex(RuntimeError, 'unexpected shard'):self.check()

    def test_artifact_directory_symlink_rejected(self):
        self.paths['60f'].rename(self.base/'linked');self.paths['60f'].symlink_to(self.base/'linked')
        with self.assertRaisesRegex(RuntimeError, 'invalid artifact'):self.check()

    def test_receipt_symlink_rejected(self):
        p=self.paths['60f']/'receipt.json';p.rename(self.base/'linked.json');p.symlink_to(self.base/'linked.json')
        with self.assertRaisesRegex(RuntimeError, 'linked file'):self.check()

    def test_truncated_or_malformed_receipt_rejected(self):
        (self.paths['60f']/'receipt.json').write_text('{')
        with self.assertRaises(json.JSONDecodeError):self.check()

    def test_duplicate_json_keys_rejected(self):
        p = self.paths['60f'] / 'receipt.json'
        text = p.read_text()
        p.write_text(text.replace('"schema": 1', '"schema": 0, "schema": 1'))
        with self.assertRaisesRegex(RuntimeError, 'duplicate JSON'): self.check()

    def test_receipt_cannot_be_overwritten(self):
        with self.assertRaisesRegex(RuntimeError,'overwrite'):
            H.record(self.repo,self.paths['60f'],self.head,'60f','12345',1)

    def test_modified_source_checkout_rejected(self):
        (self.repo/H.WORKFLOW).write_text('modified source\n')
        with self.assertRaisesRegex(RuntimeError,'changed checkout'):self.check()

    def test_staged_source_rejected_even_with_restored_worktree(self):
        p=self.repo/H.WORKFLOW;p.write_text('modified source\n')
        subprocess.run(['git','add','.'],cwd=self.repo,check=True)
        p.write_text('sealed workflow fixture\n')
        with self.assertRaisesRegex(RuntimeError,'changed checkout'):self.check()

    def test_untracked_source_rejected(self):
        (self.repo/'extra.py').write_text('extra\n')
        with self.assertRaisesRegex(RuntimeError,'changed checkout'):self.check()

    def test_wrong_checkout_head_rejected(self):
        with self.assertRaisesRegex(RuntimeError,'HEAD differs'):
            H.aggregate(self.repo,self.artifacts,'0'*40,'12345',1)

    def test_invalid_expected_head_rejected(self):
        with self.assertRaisesRegex(RuntimeError,'invalid expected'):
            H.identity(self.repo,'HEAD')

    def test_marker_drops_duplicates_reordering_and_corruption_rejected(self):
        raw=logged('60f');lines=raw.splitlines(keepends=True)
        mutations=[b''.join(lines[:-1]),raw+lines[-1],b''.join([lines[1],lines[0],*lines[2:]]),
                   raw.replace(b'59I_AUDIT_END',b'59I_AUDIT_BAD',1)]
        for changed in mutations:
            with self.assertRaises(RuntimeError):H.markers('60f',changed)

    def test_marker_timestamp_reversal_rejected(self):
        raw=logged('60f');lines=raw.splitlines(keepends=True)
        lines[1]=lines[1].rsplit(b' ',1)[0]+b' 0\n'
        with self.assertRaisesRegex(RuntimeError,'timestamps'):H.markers('60f',b''.join(lines))

    def test_boolean_returncode_not_accepted_as_integer_zero(self):
        value=json.loads((self.paths['60f']/'receipt.json').read_text())['commands'];value[0]['returncode']=False
        self.change('commands',value,'60f')
        with self.assertRaisesRegex(RuntimeError,'nonzero command'):self.check()

    def test_wrong_schema_and_boolean_attempt_rejected(self):
        self.change('schema',True)
        with self.assertRaisesRegex(RuntimeError,'identity'):self.check()
        self.change('schema',1);self.change('attempt',True)
        with self.assertRaisesRegex(RuntimeError,'identity'):self.check()


if __name__ == '__main__':
    unittest.main(verbosity=2)
