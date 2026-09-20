"""Focused no-network rejection/reconciliation controls for external dispatches."""
import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('dispatch', HERE / 'dispatch.py')
D = importlib.util.module_from_spec(spec); spec.loader.exec_module(D)
MANIFEST = D.load_manifest(HERE / 'dispatch-manifest.json')


class Fake:
    def __init__(self, output):
        self.output = output; self.posts = []; self.rows = {}; self.jobs = {}
        self.allowed_dispatch = {}; self.fail_post = False; self.moved = False
        self.runs = {5000: dict(id=5000, path='.github/workflows/' + D.CONTROLLER,
            head_branch=D.RECOVERY, head_sha='a' * 40, run_attempt=1,
            created_at='2026-09-20T17:00:00Z', status='in_progress')}
        self.definitions = []
        for index, row in enumerate(MANIFEST['workflows'], 1000):
            self.rows[index] = []
            self.definitions.append(dict(id=index, path=row['path'], state='active'))
        for name, identity, conclusion in D.PRIOR:
            self.runs[identity] = self.run(identity, name, D.BASE, conclusion=conclusion)

    def run(self, identity, name, head=D.CANDIDATE, status='completed', conclusion='success'):
        return dict(id=identity, path='.github/workflows/' + name, head_sha=head,
            head_branch=D.FEATURE, repository={'full_name': D.REPOSITORY},
            event='workflow_dispatch', run_attempt=1, status=status, conclusion=conclusion,
            created_at='2026-09-20T17:10:00Z', html_url='https://github.com/run/' + str(identity))

    def existing(self, index=1000, identity=9000, **changes):
        row = MANIFEST['workflows'][index - 1000]
        run = self.run(identity, row['workflow'], **changes)
        self.rows[index].append(run); self.runs[identity] = run
        self.jobs[identity] = [dict(id=number, name=name, status='completed',
            conclusion='success' if name in row['required_job_names'] else 'skipped',
            steps=[{'conclusion': 'success'}] * 3)
            for number, name in enumerate(row['all_job_names'], 1)]

    def get(self, suffix, **query):
        if suffix == '/pulls/177':
            return dict(state='open', merged=False, draft=True,
                head=dict(sha=D.CANDIDATE, ref=D.FEATURE, repo={'full_name': D.REPOSITORY}),
                base=dict(ref='parameterized-verilog', repo={'full_name': D.REPOSITORY}))
        if suffix == '/git/ref/heads/' + D.FEATURE:
            return {'object': {'sha': 'f' * 40 if self.moved else D.CANDIDATE}}
        if suffix == '/git/ref/heads/parameterized-verilog':
            return {'object': {'sha': D.TARGET}}
        if suffix.startswith('/actions/runs/'):
            return copy.deepcopy(self.runs[int(suffix.split('/')[3])])
        raise AssertionError(suffix)

    def collection(self, suffix, key, **query):
        if suffix == '/actions/workflows':
            return copy.deepcopy(self.definitions)
        if suffix == '/actions/workflows/' + D.CONTROLLER + '/runs':
            return [copy.deepcopy(self.runs[5000])]
        if suffix.startswith('/actions/workflows/'):
            return copy.deepcopy(self.rows[int(suffix.split('/')[3])])
        if '/attempts/' in suffix:
            return copy.deepcopy(self.jobs[int(suffix.split('/')[3])])
        raise AssertionError(suffix)

    def post(self, suffix, payload):
        index = int(suffix.split('/')[3]) - 1000
        journal = json.loads((self.output / 'dispatch-journal.json').read_text())
        assert journal['workflows'][index]['action'] == 'dispatch-intent'
        assert tuple(row['workflow'] for row in journal['workflows']) == D.WORKFLOWS
        self.posts.append((suffix, payload))
        if self.fail_post:
            raise TimeoutError('dispatch accepted or not unknown')


class Controls(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / 'receipts'; self.api = Fake(self.output)
        self.env = {'GITHUB_TOKEN': 'test-only', 'GITHUB_REPOSITORY': D.REPOSITORY,
            'GITHUB_REF': 'refs/heads/' + D.RECOVERY, 'GITHUB_RUN_ID': '5000',
            'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_SHA': 'a' * 40}

    def execute(self, mode='dispatch'):
        args = types.SimpleNamespace(repo_root=Path(self.temp.name) / 'qualification',
            output=self.output, manifest=HERE / 'dispatch-manifest.json',
            stage_gate=HERE / 'stage-gate.json', mode=mode)
        with patch.dict(os.environ, self.env), patch.object(D, 'Api', return_value=self.api), \
             patch.object(D, 'clean_source'), patch.object(D, 'require_stage', return_value={'verified_source_checks': 25}), \
             patch.object(D, 'stage_run'), contextlib.redirect_stdout(io.StringIO()):
            D.controller(args)

    def test_plan_never_posts(self):
        self.execute('plan'); self.assertEqual(self.api.posts, [])

    def test_only_sixteen_allowlisted_workflows_and_inputs(self):
        self.execute(); self.assertEqual(len(self.api.posts), 16)
        self.assertEqual(self.api.posts[8][1], {'ref': D.FEATURE, 'inputs': {'mode': 'checks'}})
        self.assertTrue(all(payload['ref'] == D.FEATURE for _, payload in self.api.posts))

    def test_existing_success_and_active_runs_reused(self):
        self.api.existing()
        self.api.existing(1001, 9001, status='in_progress', conclusion=None)
        self.execute(); self.assertEqual(len(self.api.posts), 14)

    def test_last_workflow_failure_blocks_all_posts(self):
        self.api.existing(1015, conclusion='failure')
        with self.assertRaisesRegex(RuntimeError, 'requires diagnosis'):
            self.execute()
        self.assertEqual(self.api.posts, [])

    def test_newer_same_head_failure_is_not_hidden_by_older_success(self):
        self.api.existing(1000, 9000)
        self.api.existing(1000, 9001, conclusion='failure')
        with self.assertRaisesRegex(RuntimeError, 'requires diagnosis'):
            self.execute()
        self.assertEqual(self.api.posts, [])

    def test_skipped_required_job_blocks_all_posts(self):
        self.api.existing(); self.api.jobs[9000][0]['conclusion'] = 'skipped'
        with self.assertRaisesRegex(RuntimeError, 'required job'):
            self.execute()
        self.assertEqual(self.api.posts, [])

    def test_uncertain_post_survives_read_only_plan_and_blocks_redispatch(self):
        self.api.fail_post = True
        with self.assertRaises(TimeoutError):
            self.execute()
        self.api.fail_post = False
        self.execute('plan')
        with self.assertRaisesRegex(RuntimeError, 'unresolved prior dispatch'):
            self.execute()
        self.assertEqual(len(self.api.posts), 1)
        journal = json.loads((self.output / 'dispatch-journal.json').read_text())
        self.assertEqual(journal['workflows'][0]['action'], 'dispatch-uncertain')

    def test_reconcile_visible_uncertain_run_without_repeating_it(self):
        self.api.fail_post = True
        with self.assertRaises(TimeoutError):
            self.execute()
        self.api.fail_post = False; self.api.existing()
        self.execute('reconcile'); self.assertEqual(len(self.api.posts), 1)
        self.execute(); self.assertEqual(len(self.api.posts), 16)

    def test_moved_feature_blocks_all_posts(self):
        self.api.moved = True
        with self.assertRaisesRegex(RuntimeError, 'identity changed'):
            self.execute()
        self.assertEqual(self.api.posts, [])

    def test_historical_run_status_change_blocks_all_posts(self):
        self.api.runs[D.PRIOR[0][1]]['conclusion'] = 'success'
        with self.assertRaisesRegex(RuntimeError, 'historical failure'):
            self.execute()
        self.assertEqual(self.api.posts, [])

    def test_disappeared_previously_bound_run_is_not_redispatched(self):
        self.api.existing(); self.execute('plan'); self.api.rows[1000] = []
        with self.assertRaisesRegex(RuntimeError, 'bound run disappeared'):
            self.execute()
        self.assertEqual(self.api.posts, [])

    def test_nonterminal_source_stage_rejected(self):
        gate = dict(run_id=321, run_attempt=1, artifact_id=123,
            controller_sha='b' * 40, artifact_sha256='c' * 64)
        run = dict(id=321, status='in_progress', conclusion=None, head_sha='b' * 40,
            head_branch=D.RECOVERY, repository={'full_name': D.REPOSITORY}, event='push',
            path='.github/workflows/' + D.STAGE, run_attempt=1)
        with patch.object(self.api, 'get', return_value=run):
            with self.assertRaisesRegex(RuntimeError, 'not terminal successful'):
                D.stage_run(self.api, gate)

    def test_unknown_stage_artifact_rejected(self):
        with self.assertRaisesRegex(RuntimeError, 'not filled in'):
            D.stage_run(self.api, json.loads((HERE / 'stage-gate.json').read_text()))

    def test_remote_write_allowlist_rejects_ref_pr_and_unlisted_workflow(self):
        api = D.Api('test-only'); api.allowed_dispatch = {1000: {}}
        for suffix in ('/git/refs/heads/' + D.FEATURE, '/pulls/177/merge',
                       '/actions/workflows/2000/dispatches', '/actions/runs/123/rerun'):
            with self.assertRaisesRegex(RuntimeError, 'forbidden remote write'):
                api.request(suffix, 'POST', {'ref': D.FEATURE})
        with self.assertRaisesRegex(RuntimeError, 'ref or inputs differ'):
            api.request('/actions/workflows/1000/dispatches', 'POST', {'ref': D.RECOVERY})


if __name__ == '__main__':
    unittest.main(verbosity=2)
