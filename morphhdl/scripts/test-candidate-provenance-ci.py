#!/usr/bin/env python3
"""Reject wrong producer heads, incomplete runs and corrupted artifact transport."""
import copy
import io
import unittest
import zipfile
from unittest.mock import patch
import candidate_provenance_ci as C


class ProvenanceTransportTests(unittest.TestCase):
    def setUp(self):
        self.head = 'a' * 40
        self.run = {'id': 7, 'run_attempt': 1, 'workflow_id': C.WORKFLOW, 'head_sha': self.head, 'head_branch': 'candidate', 'status': 'completed',
                    'conclusion': 'success', 'event': 'workflow_dispatch',
                    'repository': {'full_name': C.REPOSITORY},
                    'head_repository': {'full_name': C.REPOSITORY}}

    def test_requires_one_successful_same_head_allowlisted_producer(self):
        self.assertEqual(C.select_run([self.run], self.head, 'candidate'), self.run)
        for field, value in (('head_branch', 'wrong'), ('head_sha', 'b' * 40), ('workflow_id', 1), ('status', 'in_progress'),
                             ('conclusion', 'failure'), ('event', 'push'),
                             ('head_repository', {'full_name': 'foreign/repository'})):
            run = copy.deepcopy(self.run)
            run[field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                C.select_run([run], self.head, 'candidate')
        for runs in ([], [self.run, self.run]):
            with self.assertRaises(RuntimeError):
                C.select_run(runs, self.head, 'candidate')

    def archive(self, names):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as archive:
            for name in names:
                archive.writestr(name, b'authenticated fixture')
        return output.getvalue()

    def test_artifact_hash_members_and_zip_crc_are_required(self):
        raw = self.archive(['receipt.json'])
        artifact = {'digest': 'sha256:' + C.P.digest(raw)}
        self.assertEqual(C.extract_receipt(raw, artifact), b'authenticated fixture')
        with self.assertRaisesRegex(RuntimeError, 'digest mismatch'):
            C.extract_receipt(raw + b'changed', artifact)
        for names in ([], ['../receipt.json'], ['receipt.json', 'extra'], ['receipt.json', 'receipt.json']):
            raw = self.archive(names)
            with self.subTest(names=names), self.assertRaises(RuntimeError):
                C.extract_receipt(raw, {'digest': 'sha256:' + C.P.digest(raw)})

    def test_pagination_enumerates_all_pages(self):
        with patch.object(C, 'api', side_effect=[{'jobs': list(range(100))}, {'jobs': [100]}]) as api:
            self.assertEqual(C.pages('/fixture?filter=all', 'jobs'), list(range(101)))
            self.assertTrue(api.call_args_list[-1].args[0].endswith('&per_page=100&page=2'))

    def test_missing_skipped_failed_or_duplicate_producer_lanes_are_rejected(self):
        good = {'name': C.JOB, 'status': 'completed', 'conclusion': 'success',
                'head_sha': self.head, 'head_branch': 'candidate', 'run_attempt': 1}
        for jobs in ([], [dict(good, conclusion='skipped')], [dict(good, conclusion='failure')],
                     [dict(good, status='in_progress')], [dict(good, head_sha='b' * 40)],
                     [dict(good, head_branch='wrong')], [dict(good, run_attempt=2)], [good, good]):
            with self.subTest(jobs=jobs), patch.dict(C.os.environ, {'GITHUB_REF_NAME': 'candidate', 'GITHUB_HEAD_REF': ''}), \
                    patch.object(C.P, 'identity', return_value={'commit': self.head}), \
                    patch.object(C, 'pages', side_effect=[[self.run], jobs]), \
                    patch.object(C, 'api') as download, self.assertRaises(RuntimeError):
                C.fetch(C.P.ROOT)
            download.assert_not_called()

    def test_expired_missing_or_wrong_head_artifacts_are_rejected(self):
        good = {'name': C.JOB, 'status': 'completed', 'conclusion': 'success',
                'head_sha': self.head, 'head_branch': 'candidate', 'run_attempt': 1}
        artifact = {'name': 'candidate-provenance-' + self.head + '-1', 'expired': False,
                    'workflow_run': {'id': 7, 'head_sha': self.head}}
        for artifacts in ([], [dict(artifact, expired=True)], [artifact, artifact],
                          [dict(artifact, workflow_run={'id': 8, 'head_sha': self.head})],
                          [dict(artifact, workflow_run={'id': 7, 'head_sha': 'b' * 40})]):
            with self.subTest(artifacts=artifacts), patch.dict(C.os.environ, {'GITHUB_REF_NAME': 'candidate', 'GITHUB_HEAD_REF': ''}), \
                    patch.object(C.P, 'identity', return_value={'commit': self.head}), \
                    patch.object(C, 'pages', side_effect=[[self.run], [good], artifacts]), \
                    patch.object(C, 'api') as download, self.assertRaises(RuntimeError):
                C.fetch(C.P.ROOT)
            download.assert_not_called()

    def test_automatic_token_is_not_forwarded_by_redirect_handler(self):
        self.assertIsNone(C.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://storage.invalid'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
