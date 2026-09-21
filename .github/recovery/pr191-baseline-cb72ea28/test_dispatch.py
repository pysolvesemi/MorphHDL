import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('dispatch', HERE / 'dispatch.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
MANIFEST = json.loads((HERE / 'manifest.json').read_text())


def run(status='queued', conclusion=None, head=MODULE.HEAD):
    row = MANIFEST['workflow']
    return {'id': 77, 'workflow_id': MODULE.WORKFLOW_ID, 'path': row['path'],
            'head_sha': head, 'head_branch': MANIFEST['branch'],
            'event': 'workflow_dispatch', 'status': status,
            'conclusion': conclusion, 'html_url': 'https://example/run/77'}


class FakeApi:
    def __init__(self, output):
        self.output = output
        self.runs = []
        self.posts = []
        self.move = False
        self.uncertain = False
        self.bad_failure = False
        self.bad_job = False

    def request(self, path, method='GET', payload=None):
        if method == 'POST':
            value = json.loads(self.output.read_text())
            assert value['records'][-1]['action'] == 'dispatch-intent'
            assert path == '/actions/workflows/332279971/dispatches'
            assert payload == {'ref': MANIFEST['branch']}
            self.posts.append(path)
            self.runs.append(run())
            if self.uncertain:
                raise TimeoutError('ambiguous response')
            return None
        if path == '/pulls/191':
            return {'state': 'open', 'merged': False,
                    'head': {'repo': {'full_name': MODULE.REPOSITORY},
                             'ref': MANIFEST['branch'],
                             'sha': 'moved' if self.move else MODULE.HEAD},
                    'base': {'ref': MANIFEST['target_branch'],
                             'sha': MANIFEST['target']}}
        if path.startswith('/git/ref/heads/'):
            sha = MODULE.HEAD if path.endswith(MANIFEST['branch']) else MANIFEST['target']
            return {'object': {'sha': sha}}
        if path == '/actions/runs/' + str(MANIFEST['workflow']['failed_run']):
            value = run(status='completed', conclusion='success' if self.bad_failure else 'failure',
                        head=MANIFEST['workflow']['failed_head'])
            value['id'] = MANIFEST['workflow']['failed_run']
            return value
        raise AssertionError((path, method, payload))

    def collection(self, path, key, **query):
        if path == '/actions/runs':
            return copy.deepcopy(self.runs)
        row = MANIFEST['workflow']
        return [{'id': 1, 'run_id': row['failed_run'],
                 'head_sha': 'wrong' if self.bad_job else row['failed_head'],
                 'conclusion': 'failure'}]


class DispatchSafety(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / 'journal.json'
        self.api = FakeApi(self.output)
        self.journal = {'phase': 'preflight', 'records': []}

    def execute(self):
        MODULE.dispatch(self.api, MANIFEST, self.journal, self.output)

    def test_exact_dispatch_with_durable_intent(self):
        self.execute()
        self.assertEqual(len(self.api.posts), 1)
        self.assertEqual(self.journal['records'][0]['action'], 'dispatch-requested')

    def test_active_run_is_reused(self):
        self.api.runs = [run(status='in_progress')]
        self.execute()
        self.assertEqual(self.api.posts, [])
        self.assertEqual(self.journal['records'][0]['action'], 'reuse')

    def test_success_is_reused(self):
        self.api.runs = [run(status='completed', conclusion='success')]
        self.execute()
        self.assertEqual(self.api.posts, [])

    def test_terminal_non_success_stops(self):
        for conclusion in ('failure', 'skipped', 'cancelled', 'timed_out'):
            with self.subTest(conclusion=conclusion):
                self.api.runs = [run(status='completed', conclusion=conclusion)]
                with self.assertRaisesRegex(RuntimeError, 'needs diagnosis'):
                    self.execute()
                self.assertEqual(self.api.posts, [])

    def test_duplicate_stops(self):
        self.api.runs = [run(), run()]
        with self.assertRaisesRegex(RuntimeError, 'Multiple exact-head'):
            self.execute()

    def test_ref_movement_stops(self):
        self.api.move = True
        with self.assertRaisesRegex(RuntimeError, 'Feature branch changed'):
            self.execute()
        self.assertEqual(self.api.posts, [])

    def test_uncertain_post_is_not_retried(self):
        self.api.uncertain = True
        with self.assertRaises(TimeoutError):
            self.execute()
        self.assertEqual(len(self.api.posts), 1)
        value = json.loads(self.output.read_text())
        self.assertEqual(value['records'][0]['action'], 'dispatch-uncertain')

    def test_historical_failure_is_required(self):
        self.api.bad_failure = True
        with self.assertRaisesRegex(RuntimeError, 'Historical failed-run'):
            self.execute()
        self.assertEqual(self.api.posts, [])

    def test_historical_job_source_is_required(self):
        self.api.bad_job = True
        with self.assertRaisesRegex(RuntimeError, 'Historical job source mismatch'):
            self.execute()
        self.assertEqual(self.api.posts, [])

    def test_api_rejects_every_other_write(self):
        api = MODULE.Api(MANIFEST)
        for path, method, payload in (
            ('/actions/workflows/1/dispatches', 'POST', {'ref': MANIFEST['branch']}),
            ('/actions/workflows/332279971/dispatches', 'POST', {'ref': 'main'}),
            ('/pulls/191', 'PATCH', {}),
            ('/git/refs/heads/main', 'POST', {}),
        ):
            with self.subTest(path=path):
                with self.assertRaises(RuntimeError):
                    api.request(path, method, payload)


if __name__ == '__main__':
    unittest.main()
