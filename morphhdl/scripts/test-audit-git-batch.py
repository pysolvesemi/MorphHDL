#!/usr/bin/env python3
"""Compare the audit's optional Git transport with real Git, including failures."""
import concurrent.futures
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

PATH = Path(__file__).with_name('audit-runtime') / 'audit_git_batch.py'
SPEC = importlib.util.spec_from_file_location('audit_batch_under_test', PATH)
B = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(B)


class GitBatchTests(unittest.TestCase):
    def setUp(self):
        B.close()
        B.MULTITHREADED = False
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.git('init', '-q')
        self.one = self.blob(b'first\x00blob\n')
        self.two = self.blob(b'second\xffblob\n')

    def tearDown(self):
        B.close()
        self.directory.cleanup()

    def git(self, *args, **kwargs):
        return subprocess.run(['git', *args], cwd=self.root, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, check=True, **kwargs).stdout

    def blob(self, value):
        return self.git('hash-object', '-w', '--stdin', input=value).decode().strip()

    def command(self, oid):
        return ['git', '--literal-pathspecs', 'cat-file', 'blob', oid]

    def compare(self, oid, **extra):
        options = dict(cwd=self.root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        options.update(extra)
        expected = B.ORIGINAL_RUN(self.command(oid), **options)
        actual = B.run(self.command(oid), **options)
        self.assertEqual((actual.returncode, actual.stdout, actual.stderr, actual.args),
                         (expected.returncode, expected.stdout, expected.stderr, expected.args))
        return actual

    def test_binary_and_empty_blobs_match_without_caching_results(self):
        empty = self.blob(b'')
        with patch.object(B, 'ORIGINAL_POPEN', wraps=B.ORIGINAL_POPEN) as spawn:
            for oid in (self.one, self.two, empty, self.one):
                self.assertEqual(self.compare(oid).returncode, 0)
            self.assertEqual(spawn.call_count, 1)
            self.assertEqual(spawn.call_args.args[0][-2:], ['cat-file', '--batch'])

    def test_missing_object_preserves_error_and_check_true(self):
        missing = 'f'*40
        self.assertNotEqual(self.compare(missing).returncode, 0)
        with self.assertRaises(subprocess.CalledProcessError):
            B.run(self.command(missing), cwd=self.root, stdout=subprocess.PIPE,
                  stderr=subprocess.PIPE, timeout=5, check=True)

    def test_removed_object_is_not_served_from_a_result_cache(self):
        self.compare(self.one)
        (self.root/'.git/objects'/self.one[:2]/self.one[2:]).unlink()
        self.assertNotEqual(self.compare(self.one).returncode, 0)

    def test_other_repository_cannot_borrow_a_blob(self):
        self.compare(self.one)
        old = self.root
        self.root = self.root/'other'
        self.root.mkdir()
        self.git('init', '-q')
        self.assertNotEqual(self.compare(self.one).returncode, 0)
        self.root = old
        self.assertEqual(self.compare(self.one).returncode, 0)

    def test_replacement_refs_keep_real_git_semantics(self):
        self.compare(self.one)
        self.git('replace', self.one, self.two)
        self.assertEqual(self.compare(self.one).stdout, b'second\xffblob\n')
        self.assertIsNone(B.READER)

    def test_config_changes_invalidate_reader_and_include_uses_fallback(self):
        self.compare(self.one)
        before = B.READER.process.pid
        self.git('config', 'user.name', 'fixture')
        self.compare(self.one)
        self.assertNotEqual(before, B.READER.process.pid)
        config = self.root/'included'
        config.write_text('[user]\nname = included\n')
        self.git('config', 'include.path', str(config))
        self.compare(self.one)
        self.assertIsNone(B.READER)

    def test_invalid_configuration_preserves_stderr(self):
        self.compare(self.one)
        with (self.root/'.git/config').open('a') as f:
            f.write('\n[broken configuration\n')
        self.assertNotEqual(self.compare(self.one).returncode, 0)

    def test_text_and_unbounded_calls_keep_original_api(self):
        text = self.blob(b'plain text\n')
        self.assertIsInstance(self.compare(text, text=True).stdout, str)
        self.assertIsNone(B.READER)
        self.assertEqual(self.compare(text, timeout=None).stdout, b'plain text\n')
        self.assertIsNone(B.READER)

    def test_readonly_tree_query_keeps_reader_but_not_query_results(self):
        self.compare(self.one)
        before = B.READER.process.pid
        command = ['git', '--literal-pathspecs', 'rev-parse', '--git-dir']
        with patch.object(B, 'ORIGINAL_RUN', wraps=B.ORIGINAL_RUN) as original:
            for _ in range(2):
                B.run(command, cwd=self.root, stdout=subprocess.PIPE,
                      stderr=subprocess.PIPE, timeout=5, check=True)
            self.assertEqual(original.call_count, 2)
        self.compare(self.one)
        self.assertEqual(before, B.READER.process.pid)
        self.git('config', 'user.name', 'fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        (self.root/'file').write_text('snapshot')
        self.git('add', 'file')
        self.git('commit', '-qm', 'snapshot')
        head = self.git('rev-parse', 'HEAD').decode().strip()
        self.compare(self.one)
        before = B.READER.process.pid
        command = ['git', '--literal-pathspecs', 'show', head+':file']
        result = B.run(command, cwd=self.root, stdout=subprocess.PIPE,
                       stderr=subprocess.PIPE, timeout=5, check=True)
        self.assertEqual(result.stdout, b'snapshot')
        self.compare(self.one)
        self.assertEqual(before, B.READER.process.pid)

    def test_other_git_commands_invalidate_transport(self):
        self.compare(self.one)
        B.run(['git', 'status', '--porcelain'], cwd=self.root, stdout=subprocess.PIPE,
              stderr=subprocess.PIPE, timeout=5, check=True)
        self.assertIsNone(B.READER)

    def test_custom_object_environment_is_not_intercepted(self):
        with patch.dict(os.environ, GIT_OBJECT_DIRECTORY=str(self.root/'.git/objects')):
            self.compare(self.one)
            self.assertIsNone(B.READER)

    def test_threads_keep_original_concurrent_subprocess_execution(self):
        def read(oid):
            return B.run(self.command(oid), cwd=self.root, stdout=subprocess.PIPE,
                         stderr=subprocess.PIPE, timeout=5, check=True).stdout
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            self.assertEqual(list(pool.map(read, [self.one, self.two])),
                             [b'first\x00blob\n', b'second\xffblob\n'])
        self.assertTrue(B.MULTITHREADED)
        self.assertIsNone(B.READER)

    def test_unsupported_command_and_working_directory_are_delegated(self):
        for command, cwd in (([b'git', *self.command(self.one)[1:]], self.root),
                             (self.command(self.one), ""),
                             (self.command(self.one), os.fsencode(self.root))):
            with self.subTest(command=command, cwd=cwd), \
                    patch.object(B, 'ORIGINAL_RUN') as original:
                B.run(command, cwd=cwd, stdout=subprocess.PIPE,
                      stderr=subprocess.PIPE, timeout=5)
                original.assert_called_once_with(command, cwd=cwd, stdout=subprocess.PIPE,
                                                 stderr=subprocess.PIPE, timeout=5)

    def test_reader_startup_failure_closes_private_resources(self):
        with patch.object(B, 'ORIGINAL_POPEN', side_effect=OSError('spawn failed')), \
                patch.object(B.tempfile, 'TemporaryFile', return_value=B.tempfile.TemporaryFile()) as temporary:
            with self.assertRaises(OSError):
                B.Reader('git', self.root, B.time.monotonic()+5)
            self.assertTrue(temporary.return_value.closed)

    def test_immutable_show_matches_blob_errors_and_tree_output(self):
        self.git('config', 'user.name', 'fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        (self.root/'directory').mkdir()
        (self.root/'directory/file').write_bytes(b'blob\x00content\xff')
        self.git('add', 'directory')
        self.git('commit', '-qm', 'snapshot')
        head = self.git('rev-parse', 'HEAD').decode().strip()
        for path in ('directory/file', 'missing', 'directory'):
            command = ['git', '--literal-pathspecs', 'show', head+':'+path]
            options = dict(cwd=self.root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            expected = B.ORIGINAL_RUN(command, **options)
            result = B.run(command, **options)
            self.assertEqual((result.returncode, result.stdout, result.stderr),
                             (expected.returncode, expected.stdout, expected.stderr))
            if path == 'directory/file':
                self.assertIsNotNone(B.READER)
            else:
                self.assertIsNone(B.READER)

    def test_exact_tree_queries_match_git_modes_paths_and_errors(self):
        self.git('config', 'user.name', 'fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        (self.root/'directory').mkdir()
        (self.root/'directory/plain').write_text('plain')
        (self.root/'executable').write_text('executable')
        (self.root/'executable').chmod(0o755)
        (self.root/'link').symlink_to('directory/plain')
        (self.root/'.hidden').write_text('hidden')
        (self.root/'space name').write_text('space')
        self.git('add', '.')
        self.git('commit', '-qm', 'snapshot')
        head = self.git('rev-parse', 'HEAD').decode().strip()
        self.git('update-index', '--add', '--cacheinfo', '160000,'+head+',gitlink')
        self.git('commit', '-qm', 'gitlink')
        head = self.git('rev-parse', 'HEAD').decode().strip()
        for nul in (False, True):
            for path in ('directory/plain', 'executable', 'link', '.hidden', 'gitlink',
                         'directory', 'missing', 'missing/child', 'link/child', 'space name'):
                with self.subTest(nul=nul, path=path):
                    command = ['git', '--literal-pathspecs', 'ls-tree', *(['-z'] if nul else []), head, '--', path]
                    options = dict(cwd=self.root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
                    expected = B.ORIGINAL_RUN(command, **options)
                    actual = B.run(command, **options)
                    self.assertEqual((actual.returncode, actual.stdout, actual.stderr),
                                     (expected.returncode, expected.stdout, expected.stderr))
        # A moving ref never borrows an immutable lookup; run Git normally.
        with patch.object(B, 'ORIGINAL_RUN', wraps=B.ORIGINAL_RUN) as original:
            command = ['git', '--literal-pathspecs', 'ls-tree', '-z', 'HEAD', '--', 'directory/plain']
            B.run(command, cwd=self.root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            original.assert_called_once()

    def test_batch_timeout_never_falls_back_to_a_fresh_deadline(self):
        with patch.object(B, 'Reader') as reader, patch.object(B, 'ORIGINAL_RUN') as original:
            reader.return_value.blob.side_effect = TimeoutError()
            with self.assertRaises(subprocess.TimeoutExpired) as failure:
                B.run(self.command(self.one), cwd=self.root, stdout=subprocess.PIPE,
                      stderr=subprocess.PIPE, timeout=5)
            self.assertEqual(failure.exception.timeout, 5)
            original.assert_not_called()

    def test_protocol_fallback_uses_only_remaining_budget(self):
        with patch.object(B, 'Reader') as reader, patch.object(B, 'ORIGINAL_RUN') as original, \
                patch.object(B.time, 'monotonic', side_effect=[10.0, 12.0]):
            reader.return_value.blob.side_effect = ValueError('unsupported response')
            B.run(self.command(self.one), cwd=self.root, stdout=subprocess.PIPE,
                  stderr=subprocess.PIPE, timeout=5)
            self.assertEqual(original.call_args.kwargs['timeout'], 3.0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
