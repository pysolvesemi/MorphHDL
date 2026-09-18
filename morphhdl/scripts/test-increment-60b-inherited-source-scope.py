#!/usr/bin/env python3
"""Real-history controls for 60b inheritance; no source-authenticator test double.

The suite executes the repaired reader against exact historical checkouts and
sealed current source, then verifies dirty, forged and missing evidence rejects.
It does not run Scala or substitute these checks for hardware qualification.
"""
from __future__ import annotations

import contextlib
import io
import os
from pathlib import Path
import re
import subprocess
import tempfile
import types
import unittest

ROOT = Path(__file__).resolve().parents[2]
CHECKER = 'morphhdl/scripts/check-increment-60b-signedness-authority.py'
CONTRACT = 'morphhdl/contracts/increment-59i-production-successor.json'


def git(root: Path, *args: str) -> str:
    env = dict(os.environ, GIT_AUTHOR_NAME='60b regression', GIT_COMMITTER_NAME='60b regression',
               GIT_AUTHOR_EMAIL='tests@example.invalid', GIT_COMMITTER_EMAIL='tests@example.invalid')
    return subprocess.check_output(['git', '-c', 'core.hooksPath=/dev/null', *args],
                                   cwd=root, env=env, text=True, stderr=subprocess.PIPE, timeout=120)


def load(path: Path):
    module = types.ModuleType('test_60b_reader')
    module.__file__ = str(path)
    exec(compile(path.read_bytes(), str(path), 'exec'), module.__dict__)
    return module


class Inherited60bTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.reader = load(ROOT / CHECKER)
        cls.head = git(ROOT, 'rev-parse', 'HEAD').strip()
        cls.tmp = tempfile.TemporaryDirectory(prefix='60b-real-history-tests-')
        cls.root = Path(cls.tmp.name) / 'checkout'
        git(ROOT, 'worktree', 'add', '--detach', str(cls.root), cls.head)
        cls.tracked = git(cls.root, 'ls-files', '--stage')

    @classmethod
    def tearDownClass(cls):
        git(ROOT, 'worktree', 'remove', '--force', str(cls.root))
        cls.tmp.cleanup()

    def setUp(self):
        git(self.root, 'reset', '--hard', self.head)
        git(self.root, 'clean', '-fdx')
        self.assertEqual(git(self.root, 'ls-files', '--stage'), self.tracked)

    def tearDown(self):
        self.setUp()

    def check(self, scope=True):
        with contextlib.redirect_stdout(io.StringIO()) as stream:
            self.reader.check(self.root, scope)
        return stream.getvalue()

    def reject(self, pattern='.'):
        with self.assertRaisesRegex((RuntimeError, subprocess.CalledProcessError), pattern):
            self.check()

    def append(self, path, text='\n// unreviewed mutation\n'):
        f = self.root / path
        f.write_text(f.read_text() + text)

    def test_01_current_sealed_head_passes(self):
        self.assertIn('60B_INHERITED_SOURCE_PASS', self.check())
        self.assertEqual(git(self.root, 'ls-files', '--stage'), self.tracked)
        self.assertFalse(git(self.root, 'status', '--porcelain').strip())

    def test_02_original_qualified_60b_merge_passes_direct_scope(self):
        git(self.root, 'reset', '--hard', self.reader.QUALIFIED_60B)
        self.assertNotIn('60B_INHERITED_SOURCE_PASS', self.check())

    def test_03_original_qualified_60b_feature_passes_direct_scope(self):
        git(self.root, 'reset', '--hard', '6d857f936e1b60077e309476a8cd07b36afbb672')
        self.assertNotIn('60B_INHERITED_SOURCE_PASS', self.check())

    def test_04_published_schema2_59i_seal_passes(self):
        git(self.root, 'reset', '--hard', 'f43100e4899593eb5c9e78537a4dcf6f53f9c30f')
        self.assertIn('60B_INHERITED_SOURCE_PASS', self.check())

    def test_05_unsealed_source_is_rejected(self):
        source = git(self.root, 'rev-parse', self.head + '^').strip()
        git(self.root, 'reset', '--hard', source)
        self.reject()

    def test_06_uncertified_target_is_rejected(self):
        git(self.root, 'reset', '--hard', '27af65abbee0d2334d6be7a6e4e2408b8af32fd9')
        self.reject('missing inherited 60b source')

    def test_07_missing_manifest_is_rejected(self):
        (self.root / CONTRACT).unlink()
        self.reject()

    def test_08_missing_verifier_is_rejected(self):
        (self.root / self.reader.SUCCESSOR).unlink()
        self.reject('missing inherited 60b source')

    def test_09_replaced_verifier_is_rejected_before_execution(self):
        sentinel = Path(self.tmp.name) / 'verifier-was-executed'
        (self.root / self.reader.SUCCESSOR).write_text(
            'from pathlib import Path\nPath(' + repr(str(sentinel)) + ').touch()\n'
            'CONTRACT_SHA256 = "fake"\ndef verify(root): return {}\n')
        self.reject('unreviewed inherited 60b source authenticator')
        self.assertFalse(sentinel.exists())

    def test_10_executable_verifier_is_rejected(self):
        (self.root / self.reader.SUCCESSOR).chmod(0o755)
        self.reject('source mode changed')

    def test_11_linked_verifier_is_rejected(self):
        f = self.root / self.reader.SUCCESSOR
        raw = f.read_bytes()
        target = Path(self.tmp.name) / 'linked-helper.py'
        target.write_bytes(raw)
        f.unlink(); f.symlink_to(target)
        self.reject('linked inherited 60b source')

    def test_12_expanded_allowlist_is_rejected(self):
        self.append(self.reader.SCOPE, '\nextra.scala\n')
        self.reject()

    def test_13_dirty_analysis_source_is_rejected(self):
        self.append(self.reader.PRODUCTION[1])
        self.reject('identity differs')

    def test_14_dirty_native_source_is_rejected(self):
        self.append('core/src/main/scala/spinal/core/UInt.scala')
        self.reject('identity differs')

    def test_15_dirty_target61_source_is_rejected(self):
        self.append('morphhdl/src/main/scala/morphhdl/MorphVerilog.scala')
        self.reject('identity differs')

    def test_16_staged_analysis_change_is_rejected(self):
        self.append(self.reader.PRODUCTION[1])
        git(self.root, 'add', '--', self.reader.PRODUCTION[1])
        self.reject('HEAD/index identity differs')

    def test_17_committed_postseal_change_is_rejected(self):
        self.append(self.reader.PRODUCTION[1])
        git(self.root, 'add', '--all')
        git(self.root, 'commit', '-m', 'Unreviewed post-seal change')
        self.reject()

    def test_18_committed_manifest_removal_is_rejected(self):
        git(self.root, 'rm', '--', CONTRACT)
        git(self.root, 'commit', '-m', 'Remove authenticated review')
        self.reject()

    def test_19_untracked_source_addition_is_rejected(self):
        (self.root / 'morphhdl/src/main/extra.scala').write_text('// extra\n')
        self.reject('untracked')

    def test_20_ignored_source_addition_is_rejected(self):
        extra = 'morphhdl/scripts/unchecked-shadow.py'
        exclude = Path(git(self.root, 'rev-parse', '--git-path', 'info/exclude').strip())
        if not exclude.is_absolute(): exclude = self.root / exclude
        prior = exclude.read_bytes() if exclude.exists() else b''
        exclude.parent.mkdir(parents=True, exist_ok=True)
        try:
            exclude.write_bytes(prior + b'\n' + extra.encode() + b'\n')
            (self.root / extra).write_text('print("shadow")\n')
            self.reject('ignored source addition')
        finally:
            (self.root / extra).unlink(missing_ok=True)
            exclude.write_bytes(prior)

    def test_21_production_executable_mode_is_rejected(self):
        (self.root / self.reader.PRODUCTION[1]).chmod(0o755)
        self.reject('source mode changed')

    def test_22_repeated_check_revalidates_live_source(self):
        self.check()
        self.append(self.reader.PRODUCTION[1])
        self.reject('identity differs')

    def test_23_repeated_check_revalidates_manifest_slot(self):
        self.check()
        f = self.root / self.reader.SUCCESSOR
        s = f.read_text()
        s, n = re.subn(r'^CONTRACT_SHA256 = "[^"\n]+"$', 'CONTRACT_SHA256 = "' + '0'*64 + '"', s, flags=re.M)
        self.assertEqual(n, 1); f.write_text(s)
        self.reject()

    def test_24_dirty_roadmap_is_rejected(self):
        self.append(self.reader.ROADMAP, '\nUnexpected roadmap content\n')
        self.reject('identity differs')

    def test_25_current_lint_rejects_name_inference(self):
        self.append(self.reader.PRODUCTION[1], '\n// getName(\n')
        with self.assertRaisesRegex(RuntimeError, 'forbidden dependency'):
            self.check(scope=False)

    def test_26_current_lint_rejects_neutral_native_import(self):
        self.append(self.reader.PRODUCTION[0], '\nimport spinal.core._\n')
        with self.assertRaisesRegex(RuntimeError, 'neutral facts'):
            self.check(scope=False)

    def test_27_current_lint_rejects_missing_kind(self):
        f = self.root / self.reader.PRODUCTION[0]
        s = f.read_text(); self.assertIn('case object Unknown', s)
        f.write_text(s.replace('case object Unknown', 'case object MissingKind'))
        with self.assertRaisesRegex(RuntimeError, 'missing signedness kind'):
            self.check(scope=False)

    def test_28_current_lint_rejects_hardtype_reevaluation(self):
        self.append(self.reader.PRODUCTION[1], '\n// wordType()\n')
        with self.assertRaisesRegex(RuntimeError, 'must not reevaluate HardType'):
            self.check(scope=False)

    def test_29_current_lint_rejects_missing_authority(self):
        f = self.root / self.reader.PRODUCTION[1]
        s = f.read_text(); self.assertIn('OPERAND-IDENTITY', s)
        f.write_text(s.replace('OPERAND-IDENTITY', 'REMOVED-IDENTITY'))
        with self.assertRaisesRegex(RuntimeError, 'missing exact authority boundary'):
            self.check(scope=False)

    def test_30_workflow_retains_all_original_commands(self):
        path = '.github/workflows/increment-60b-signedness-authority.yml'
        original = git(self.root, 'show', 'f43100e4899593eb5c9e78537a4dcf6f53f9c30f:' + path)
        current = (self.root / path).read_text()
        addition = '          python3 -B morphhdl/scripts/test-increment-60b-inherited-source-scope.py\n'
        self.assertEqual(current.count(addition), 1)
        self.assertEqual(current.replace(addition, '', 1), original)
        self.assertNotIn('--without-git-scope', current)

    def test_31_frozen_checker_failure_is_not_accepted(self):
        old = self.reader.ORIGINAL_CHECKER_SHA256
        try:
            self.reader.ORIGINAL_CHECKER_SHA256 = '0'*64
            self.reject('original 60b checker changed')
        finally:
            self.reader.ORIGINAL_CHECKER_SHA256 = old

    def test_32_frozen_inventory_failure_is_not_accepted(self):
        old = self.reader.ORIGINAL_SCOPE_SHA256
        try:
            self.reader.ORIGINAL_SCOPE_SHA256 = '0'*64
            self.reject('original 60b analysis-only inventory changed')
        finally:
            self.reader.ORIGINAL_SCOPE_SHA256 = old


if __name__ == '__main__':
    unittest.main(verbosity=2)
