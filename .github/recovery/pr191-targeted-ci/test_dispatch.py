import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('pr191_dispatch', HERE / 'dispatch.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
MANIFEST = json.loads((HERE / 'manifest.json').read_text())


def workflow_run(row, head=module.HEAD, status='queued', conclusion=None):
    return {'id': row['id'] + 1000, 'workflow_id': row['id'], 'path': row['path'],
            'head_sha': head, 'status': status, 'conclusion': conclusion,
            'html_url': 'https://github.com/pysolvesemi/MorphHDL/actions/runs/' + str(row['id'] + 1000)}


class FakeApi:
    def __init__(self, output):
        self.output = output
        self.runs = []
        self.posts = []
        self.uncertain = False
        self.move_after = None
        self.bad_failure = False

    def request(self, path, method='GET', payload=None):
        if method == 'POST':
            value = json.loads(self.output.read_text())
            assert value['records'][-1]['action'] == 'dispatch-intent'
            assert payload == {'ref': MANIFEST['branch']}
            wid = int(path.split('/')[-2])
            assert wid in module.ALLOWED_IDS
            self.posts.append(wid)
            row = next(row for row in MANIFEST['workflows'] if row['id'] == wid)
            # A timeout may occur after GitHub accepted the request.
            self.runs.append(workflow_run(row))
            if self.uncertain:
                raise TimeoutError('ambiguous response')
            return None
        if path == '/pulls/191':
            moved = self.move_after is not None and len(self.posts) >= self.move_after
            return {'state': 'open', 'merged': False,
                    'head': {'repo': {'full_name': module.REPOSITORY},
                             'ref': MANIFEST['branch'], 'sha': 'changed' if moved else module.HEAD},
                    'base': {'ref': MANIFEST['target_branch'], 'sha': MANIFEST['target']}}
        if path.startswith('/git/ref/heads/'):
            return {'object': {'sha': module.HEAD if path.endswith(MANIFEST['branch']) else MANIFEST['target']}}
        if path.startswith('/actions/runs/'):
            row = next(row for row in MANIFEST['workflows'] if path.endswith(str(row['failed_run'])))
            run = workflow_run(row, MANIFEST['failed_head'], 'completed',
                               'success' if self.bad_failure else 'failure')
            run['id'] = row['failed_run']
            return run
        raise AssertionError(path)

    def collection(self, path, key, **query):
        if path == '/actions/runs':
            assert query == {'head_sha': module.HEAD}
            return copy.deepcopy(self.runs)
        rid = int(path.split('/')[-2])
        return [{'id': rid + 1, 'run_id': rid, 'head_sha': MANIFEST['failed_head'], 'conclusion': 'failure'}]


class DispatchSafety(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / 'dispatch-journal.json'
        self.api = FakeApi(self.output)
        self.journal = {'phase': 'preflight', 'records': []}

    def execute(self):
        module.dispatch(self.api, MANIFEST, self.journal, self.output)

    def test_exact_eight_and_durable_intent_before_each_post(self):
        self.execute()
        self.assertEqual(set(self.api.posts), module.ALLOWED_IDS)
        self.assertEqual(len(self.api.posts), 8)
        self.assertEqual(self.journal['phase'], 'requests-complete')
        self.assertTrue(all('run_id' in r for r in self.journal['records']))

    def test_existing_success_and_active_runs_are_reused(self):
        self.api.runs = [workflow_run(row, status='completed' if i % 2 else 'in_progress',
                                     conclusion='success' if i % 2 else None)
                         for i, row in enumerate(MANIFEST['workflows'])]
        self.execute()
        self.assertEqual(self.api.posts, [])
        self.assertTrue(all(r['action'] == 'reuse' for r in self.journal['records']))

    def test_any_new_head_failure_stops_the_entire_batch_before_writes(self):
        self.api.runs = [workflow_run(MANIFEST['workflows'][-1], status='completed', conclusion='failure')]
        with self.assertRaisesRegex(RuntimeError, 'needs diagnosis'):
            self.execute()
        self.assertEqual(self.api.posts, [])

    def test_duplicate_runs_stop_before_writes(self):
        row = MANIFEST['workflows'][0]
        self.api.runs = [workflow_run(row), workflow_run(row)]
        with self.assertRaisesRegex(RuntimeError, 'Multiple exact-head'):
            self.execute()
        self.assertEqual(self.api.posts, [])

    def test_skipped_or_cancelled_is_not_reusable_success(self):
        for conclusion in ('skipped', 'cancelled', 'timed_out', 'neutral'):
            with self.subTest(conclusion=conclusion):
                self.api.runs = [workflow_run(MANIFEST['workflows'][0], status='completed', conclusion=conclusion)]
                with self.assertRaises(RuntimeError):
                    self.execute()
                self.assertEqual(self.api.posts, [])

    def test_ref_movement_stops_remaining_dispatches(self):
        self.api.move_after = 1
        with self.assertRaisesRegex(RuntimeError, 'Feature branch changed'):
            self.execute()
        self.assertEqual(len(self.api.posts), 1)

    def test_uncertain_post_is_not_retried(self):
        self.api.uncertain = True
        with self.assertRaises(TimeoutError):
            self.execute()
        self.assertEqual(len(self.api.posts), 1)
        self.assertEqual(json.loads(self.output.read_text())['records'][-1]['action'], 'dispatch-uncertain')

    def test_original_failure_is_required(self):
        self.api.bad_failure = True
        with self.assertRaisesRegex(RuntimeError, 'Historical failed-run'):
            self.execute()
        self.assertEqual(self.api.posts, [])

    def test_api_rejects_source_writes_and_unrelated_dispatches(self):
        api = module.Api(MANIFEST)
        for path, method, payload in (
            ('/git/refs/heads/main', 'POST', {}),
            ('/actions/workflows/1/dispatches', 'POST', {'ref': MANIFEST['branch']}),
            ('/actions/workflows/361097183/dispatches', 'POST', {'ref': 'main'}),
            ('/pulls/191', 'PATCH', {}),
        ):
            with self.subTest(path=path, payload=payload):
                with self.assertRaises(RuntimeError):
                    api.request(path, method, payload)


if __name__ == '__main__':
    unittest.main()
