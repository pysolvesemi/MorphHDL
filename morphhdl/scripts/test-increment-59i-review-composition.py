#!/usr/bin/env python3
"""Exact source-view controls for the 59i / WA-07b integration join.

These source tests neither run HDL nor substitute for full inherited audits.
All mutations are confined to a disposable real-Git worktree.
"""
from __future__ import annotations

import importlib.util
import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JOIN = 'morphhdl/scripts/check-increment-59i-source-review.py'
WA = 'morphhdl/scripts/check-wa07b-inherited-review.py'
REGISTER = 'morphhdl/scripts/check-increment-59g-source-review.py'


def load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError('missing source reviewer: ' + str(path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def git(root: Path, *args: str) -> bytes:
    return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.STDOUT, timeout=90)


class ReviewCompositionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='59i-review-composition-')
        cls.root = Path(cls.temp.name) / 'repo'
        git(ROOT, 'worktree', 'add', '--quiet', '--detach', str(cls.root), 'HEAD')
        cls.join = load(cls.root / JOIN, 'joined_review_test')
        cls.wa = load(cls.root / WA, 'ternary_review_test')
        cls.register = load(cls.root / REGISTER, 'register_review_test')

    def tearDown(self):
        git(self.root, 'reset', '--hard', 'HEAD')
        git(self.root, 'clean', '-fdx')

    @classmethod
    def tearDownClass(cls):
        git(ROOT, 'worktree', 'remove', '--force', str(cls.root))
        cls.temp.cleanup()

    def test_previous_manifests_remain_byte_identical(self):
        self.assertEqual(hashlib.sha256((self.root / self.join.CONTRACT).read_bytes()).hexdigest(),
                         'af633343f73d1e9d54cf68c05ff5e21a8d79c9738fc45af4ddd07f2086aab600')
        self.assertEqual((self.root / self.wa.CONTRACT).read_bytes(),
                         git(self.root, 'show', self.join.BASE + ':' + self.wa.CONTRACT))

    def test_unaffected_review_entries_are_preserved(self):
        old = json.loads((self.root / self.join.CONTRACT).read_text())
        current = self.join.load_contract(self.root)
        for entry in old['files']:
            if entry['path'] not in self.join.INTEGRATION_PATHS:
                self.assertEqual(entry, current[entry['path']])
        self.assertEqual(len(current), len(old['files']) + 1)

    def test_source_views_restore_each_frozen_layer(self):
        source = (self.root / REGISTER).read_bytes()
        intermediate = self.join.restore_source(self.root, REGISTER, source.decode()).encode()
        self.assertEqual(intermediate, git(self.root, 'show', self.join.BASE + ':' + REGISTER))
        self.assertEqual(self.wa.joined_adapter_source(self.root, REGISTER, source), intermediate)
        self.assertEqual(self.wa.joined_adapter_source(self.root, REGISTER, intermediate), intermediate)
        restored = self.wa.restore_adapter(self.root, REGISTER, source.decode()).encode()
        self.assertEqual(restored, git(self.root, 'show', self.wa.BASE + ':' + REGISTER))
        self.join.verify_spans(self.root)
        self.register.verify_spans(self.root)

    def test_pass_sources_do_not_acquire_adapter_permission(self):
        self.assertEqual(self.wa.joined_adapter_source(self.root, self.wa.MARKER, b'arbitrary pass bytes'),
                         b'arbitrary pass bytes')
        self.assertNotIn(self.wa.MARKER, self.join.PATHS)

    def test_changed_register_adapter_rejects_in_both_views(self):
        source = (self.root / REGISTER).read_bytes() + b'\n# unreviewed change\n'
        for action in (lambda: self.join.restore_source(self.root, REGISTER, source.decode()),
                       lambda: self.wa.restore_adapter(self.root, REGISTER, source.decode())):
            with self.assertRaisesRegex(RuntimeError, 'outside 59i spans'):
                action()

    def test_old_join_checker_cannot_replace_composed_checker(self):
        source = git(self.root, 'show', self.join.HISTORICAL_BASE + ':' + REGISTER)
        with self.assertRaises(RuntimeError):
            self.wa.restore_adapter(self.root, REGISTER, source.decode())

    def test_changed_helper_is_not_hidden_by_composition(self):
        p = self.root / WA
        p.write_bytes(p.read_bytes() + b'\n# unreviewed helper\n')
        with self.assertRaisesRegex(RuntimeError, 'outside 59i spans'):
            self.join.verify_spans(self.root)

    def test_missing_sidecar_rejects(self):
        (self.root / self.join.INTEGRATION_CONTRACT).unlink()
        with self.assertRaisesRegex(RuntimeError, 'missing regular 59i integration'):
            self.join.load_contract(self.root)

    def test_modified_sidecar_rejects(self):
        p = self.root / self.join.INTEGRATION_CONTRACT
        p.write_bytes(p.read_bytes() + b'\n')
        with self.assertRaisesRegex(RuntimeError, 'integration review changed'):
            self.join.load_contract(self.root)

    def test_symlinked_sidecar_rejects(self):
        p = self.root / self.join.INTEGRATION_CONTRACT
        backup = p.with_suffix('.saved')
        p.rename(backup)
        p.symlink_to(backup.name)
        with self.assertRaisesRegex(RuntimeError, 'missing regular 59i integration'):
            self.join.load_contract(self.root)

    def test_executable_sidecar_rejects(self):
        p = self.root / self.join.INTEGRATION_CONTRACT
        p.chmod(0o755)
        with self.assertRaisesRegex(RuntimeError, 'missing regular 59i integration'):
            self.join.load_contract(self.root)

    def test_changed_historical_manifest_rejects(self):
        p = self.root / self.join.CONTRACT
        p.write_bytes(p.read_bytes() + b'\n')
        with self.assertRaisesRegex(RuntimeError, 'reviewed source manifest changed'):
            self.join.load_contract(self.root)

    def test_missing_join_reviewer_does_not_enable_wa_fallback(self):
        (self.root / JOIN).unlink()
        with self.assertRaisesRegex(RuntimeError, 'missing regular 59i successor'):
            self.wa.restore_adapter(self.root, REGISTER, (self.root / REGISTER).read_text())

    def test_missing_join_manifest_does_not_enable_wa_fallback(self):
        (self.root / self.join.CONTRACT).unlink()
        with self.assertRaisesRegex(RuntimeError, 'missing regular 59i successor'):
            self.wa.restore_adapter(self.root, REGISTER, (self.root / REGISTER).read_text())

    def test_missing_all_join_evidence_rejects_combined_bytes(self):
        (self.root / JOIN).unlink()
        (self.root / self.join.CONTRACT).unlink()
        with self.assertRaisesRegex(RuntimeError, 'WA-07b adapter'):
            self.wa.restore_adapter(self.root, REGISTER, (self.root / REGISTER).read_text())

    def test_unreviewed_production_path_is_not_accepted(self):
        with self.assertRaisesRegex(RuntimeError, 'unreviewed='):
            self.join.require_production_inventory(set(self.join.PRODUCTION_PATHS) | {'foreign/src/main/Extra.scala'})


if __name__ == '__main__':
    unittest.main()
