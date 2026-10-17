#!/usr/bin/env python3
"""Live I/O regressions for immutable-content-only audit acceleration."""
from __future__ import annotations

import hashlib
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HELPER = Path(__file__).with_name('check-increment-59i-production-successor.py')
spec = importlib.util.spec_from_file_location('source_io_under_test', HELPER)
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)


class SourceIoTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='59i-source-io-')
        self.root = Path(self.tmp.name)
        self.path = 'nested/source/File.scala'
        self.file = self.root / self.path
        self.file.parent.mkdir(parents=True)
        self.file.write_bytes(b'object Original {}\n')
        self.file.chmod(0o644)
        review._CANONICAL_MANIFESTS.clear()
        review._relative_ancestors.cache_clear()
        review._valid_path_text.cache_clear()
        review.normalized_helper.cache_clear()

    def tearDown(self):
        self.tmp.cleanup()

    def read(self, mode='100644'):
        return review.regular(self.root, self.path, mode)

    def test_regular_bytes_are_read_each_time(self):
        self.assertEqual(self.read(), b'object Original {}\n')
        self.file.write_bytes(b'object Updated  {}\n')
        self.assertEqual(self.read(), b'object Updated  {}\n')

    def test_equal_size_and_restored_mtime_do_not_hide_changed_bytes(self):
        original = self.read()
        before = self.file.stat()
        changed = original.replace(b'Original', b'Modified')
        self.assertEqual(len(original), len(changed))
        self.file.write_bytes(changed)
        os.utime(self.file, ns=(before.st_atime_ns, before.st_mtime_ns))
        self.assertEqual(self.read(), changed)

    def test_warm_lexical_cache_does_not_cache_mode(self):
        self.read()
        self.file.chmod(0o755)
        with self.assertRaisesRegex(RuntimeError, 'source mode changed'):
            self.read()

    def test_executable_source_retains_exact_mode_policy(self):
        self.file.chmod(0o755)
        self.assertEqual(self.read('100755'), self.file.read_bytes())
        self.file.chmod(0o644)
        with self.assertRaisesRegex(RuntimeError, 'source mode changed'):
            self.read('100755')

    def test_unsupported_mode_is_rejected(self):
        for mode in ('120000', '160000', '100600', None):
            with self.subTest(mode=mode), self.assertRaisesRegex(RuntimeError, 'source mode changed'):
                self.read(mode)

    def test_warm_cache_rejects_leaf_symlink(self):
        self.read()
        target = self.root / 'target'
        self.file.rename(target)
        self.file.symlink_to(target)
        with self.assertRaisesRegex(RuntimeError, 'linked source'):
            self.read()

    def test_warm_cache_rejects_parent_symlink(self):
        self.read()
        parent = self.file.parent
        target = self.root / 'moved'
        parent.rename(target)
        parent.symlink_to(target, target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError, 'linked source'):
            self.read()

    def test_warm_cache_rejects_broken_parent_link(self):
        self.read()
        self.file.unlink()
        self.file.parent.rmdir()
        self.file.parent.symlink_to(self.root / 'absent', target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError, 'linked source'):
            self.read()

    def test_warm_cache_rejects_missing_file(self):
        self.read()
        self.file.unlink()
        with self.assertRaisesRegex(RuntimeError, 'missing regular source'):
            self.read()

    def test_warm_cache_rejects_directory_instead_of_file(self):
        self.read()
        self.file.unlink()
        self.file.mkdir()
        with self.assertRaisesRegex(RuntimeError, 'missing regular source'):
            self.read()

    def test_warm_cache_rejects_file_instead_of_parent(self):
        self.read()
        self.file.unlink()
        self.file.parent.rmdir()
        self.file.parent.write_text('not a directory')
        with self.assertRaisesRegex(RuntimeError, 'missing regular source'):
            self.read()

    def test_identical_relative_path_in_other_root_is_independent(self):
        self.read()
        other = self.root / 'other'
        f = other / self.path
        f.parent.mkdir(parents=True)
        f.write_bytes(b'other root\n')
        self.assertEqual(review.regular(other, self.path), b'other root\n')
        self.assertEqual(self.read(), b'object Original {}\n')

    def test_invalid_paths_keep_rejection_including_unhashable_inputs(self):
        for path in ('', '/tmp/source', '../source', 'a/../b', '.git/config',
                     'a/.git/b', 'a//b', './a', 'a\0b', [], {}, None, 7):
            with self.subTest(path=path):
                self.assertFalse(review.valid_path(path))
                with self.assertRaisesRegex(RuntimeError, 'invalid path'):
                    review.regular(self.root, path)

    def test_lexical_cache_retains_no_root_or_file_state(self):
        expected = ('nested', 'nested/source', self.path)
        self.assertEqual(review._relative_ancestors(self.path), expected)
        self.assertIs(review._relative_ancestors(self.path), review._relative_ancestors(self.path))
        self.assertTrue(all(type(item) is str for item in expected))
        self.read()
        self.file.write_bytes(b'fresh')
        self.assertEqual(self.read(), b'fresh')

    def test_lexical_caches_are_bounded(self):
        for i in range(8200):
            path = f'dir/source{i}.scala'
            self.assertTrue(review.valid_path(path))
            review._relative_ancestors(path)
        self.assertLessEqual(review._valid_path_text.cache_info().currsize, 8192)
        self.assertLessEqual(review._relative_ancestors.cache_info().currsize, 8192)

    def test_exact_fresh_content_reuses_immutable_bytes_not_a_stat_record(self):
        raw = b'{"manifest": "immutable"}'
        expected = hashlib.sha256(raw).hexdigest()
        first = review._canonical_manifest(raw, expected)
        copy = bytes(bytearray(raw))
        self.assertIsNot(raw, copy)
        with patch.object(review, 'digest', side_effect=AssertionError('redundant SHA computation')):
            self.assertIs(review._canonical_manifest(copy, expected), first)

    def test_equal_size_byte_mutation_does_not_hit_content_cache(self):
        raw = b'original'
        expected = hashlib.sha256(raw).hexdigest()
        review._canonical_manifest(raw, expected)
        with self.assertRaisesRegex(RuntimeError, 'sealed successor manifest changed'):
            review._canonical_manifest(b'modified', expected)

    def test_same_bytes_under_wrong_expected_digest_are_rejected(self):
        raw = b'original'
        review._canonical_manifest(raw, hashlib.sha256(raw).hexdigest())
        with self.assertRaisesRegex(RuntimeError, 'sealed successor manifest changed'):
            review._canonical_manifest(raw, '0' * 64)

    def test_failed_authentication_does_not_populate_cache(self):
        with self.assertRaisesRegex(RuntimeError, 'sealed successor manifest changed'):
            review._canonical_manifest(b'invalid', '0' * 64)
        self.assertEqual(len(review._CANONICAL_MANIFESTS), 0)

    def test_only_full_equal_content_hits_cache(self):
        raw = b'prefix-middle-suffix'
        expected = hashlib.sha256(raw).hexdigest()
        review._canonical_manifest(raw, expected)
        for changed in (raw + b' ', raw[:-1], b'Prefix-middle-suffix', b'prefix-Middle-suffix'):
            with self.subTest(changed=changed), self.assertRaisesRegex(RuntimeError, 'sealed successor manifest changed'):
                review._canonical_manifest(changed, expected)

    def test_content_cache_is_bounded_and_eviction_revalidates(self):
        for i in range(12):
            raw = f'manifest {i}'.encode()
            review._canonical_manifest(raw, hashlib.sha256(raw).hexdigest())
        self.assertEqual(review._CANONICAL_MANIFESTS.maxlen, 8)
        self.assertEqual(len(review._CANONICAL_MANIFESTS), 8)
        with patch.object(review, 'digest', wraps=review.digest) as checked:
            raw = b'manifest 0'
            review._canonical_manifest(raw, hashlib.sha256(raw).hexdigest())
            checked.assert_called_once_with(raw)

    def test_fresh_live_reads_feed_content_authentication(self):
        expected = hashlib.sha256(self.file.read_bytes()).hexdigest()
        review._canonical_manifest(self.read(), expected)
        before = self.file.stat()
        self.file.write_bytes(b'object Modified {}\n')
        os.utime(self.file, ns=(before.st_atime_ns, before.st_mtime_ns))
        with self.assertRaisesRegex(RuntimeError, 'sealed successor manifest changed'):
            review._canonical_manifest(self.read(), expected)

    def test_helper_normalization_cache_is_content_keyed(self):
        raw = b'CONTRACT_SHA256 = "old"\ndef check(): return True\n'
        normalized = review.normalized_helper(raw)
        self.assertIs(review.normalized_helper(bytes(bytearray(raw))), normalized)
        self.assertNotEqual(review.normalized_helper(raw.replace(b'True', b'False')), normalized)
        with self.assertRaisesRegex(RuntimeError, 'ambiguous helper seal'):
            review.normalized_helper(raw + b'CONTRACT_SHA256 = "other"\n')

    def test_helper_normalization_cache_is_bounded(self):
        for i in range(20):
            review.normalized_helper(f'CONTRACT_SHA256 = "{i}"\n'.encode())
        self.assertLessEqual(review.normalized_helper.cache_info().currsize, 16)


if __name__ == '__main__':
    unittest.main(verbosity=2)
