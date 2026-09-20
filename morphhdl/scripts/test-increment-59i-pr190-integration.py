#!/usr/bin/env python3
"""Actual current-source mutations and exact PR190 lifecycle rejection controls."""
from __future__ import annotations

import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
PATH = 'morphhdl/scripts/check-increment-59i-pr190-integration.py'
spec = importlib.util.spec_from_file_location('current_pr190_tests', ROOT / PATH)
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)


def git(root, *args, data=None):
    result = subprocess.run(['git', '--literal-pathspecs', *args], cwd=root,
        input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120,
        env=dict(os.environ, GIT_AUTHOR_NAME='59i audit fixture',
            GIT_AUTHOR_EMAIL='audit@example.invalid', GIT_COMMITTER_NAME='59i audit fixture',
            GIT_COMMITTER_EMAIL='audit@example.invalid'))
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout


class Pr190IntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='59i-pr190-current-controls-')
        cls.root = Path(cls.temp.name) / 'source'
        cls.head = git(ROOT, 'rev-parse', 'HEAD').decode().strip()
        git(ROOT, 'worktree', 'add', '--quiet', '--detach', str(cls.root), cls.head)
        cls.production = review.source_review(cls.root)
        cls.value = cls.production.verify(cls.root)
        cls.source = cls.value['source_commit']
        cls.result = review.verify(cls.root)

    @classmethod
    def tearDownClass(cls):
        git(ROOT, 'worktree', 'remove', '--force', str(cls.root))
        cls.temp.cleanup()

    def tearDown(self):
        git(self.root, 'reset', '--hard', self.head)
        git(self.root, 'clean', '-fdx')

    def append(self, path):
        file = self.root / path
        file.write_bytes(file.read_bytes() + b'\n# deliberate unreviewed source\n')

    def reject(self, action=None):
        with self.assertRaises(RuntimeError):
            (action or (lambda: review.verify(self.root)))()

    def test_current_source_and_runtime_merge_are_complete(self):
        self.assertEqual(self.result['runtime_files'], 1845)
        self.assertEqual(self.result['target_records'], 40)
        self.assertEqual((self.result['expected_testcases'], self.result['expected_suites']), (2307, 230))
        self.assertEqual(self.value['previous_seal'], self.production.previous_certificate(5))
        # A later source certificate is not a parent of the historical merge.
        # Compare retained metadata with Git itself, independently of the
        # production helper which constructs the expected checkpoint object.
        self.assertEqual(self.value['target_checkpoint'], {
            'commit': review.CHECKPOINT,
            'tree': git(self.root, 'rev-parse', review.CHECKPOINT + '^{tree}').decode().strip(),
            'parents': git(self.root, 'rev-list', '--parents', '-n', '1',
                review.CHECKPOINT).decode().split()[1:],
        })

    def test_sequential_and_reduction_bodies_reject_live_edits_after_warm_pass(self):
        for path in (
            'core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala',
            'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala',
            'morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala',
            'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeLocalEnableTests.scala',
            'core/src/test/scala/spinal/core/internals/SequentialWireEmitterTests.scala',
            'morphhdl/scripts/check-increment-59i-composite-local-enable.py',
            'morphhdl/scripts/check-increment-59i-local-enable-combined.py',
        ):
            with self.subTest(path=path):
                raw = (self.root / path).read_bytes()
                try:
                    self.append(path)
                    self.reject()
                finally:
                    (self.root / path).write_bytes(raw)

    def test_hidden_staged_source_is_rejected(self):
        path = 'core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala'
        raw = (self.root / path).read_bytes()
        self.append(path)
        git(self.root, 'add', path)
        (self.root / path).write_bytes(raw)
        self.reject()

    def test_live_mode_and_link_mutations_are_rejected(self):
        path = self.root / 'morphhdl/scripts/check-increment-59i-pr190-integration.py'
        path.chmod(0o755)
        self.reject()
        path.chmod(0o644)
        raw = path.read_bytes()
        target = Path(self.temp.name) / 'shadow-review.py'
        target.write_bytes(raw)
        path.unlink()
        path.symlink_to(target)
        self.reject()

    def test_ignored_compiler_addition_is_rejected(self):
        path = self.root / 'core/src/main/scala/target/Unreviewed.scala'
        path.parent.mkdir(parents=True)
        path.write_text('object Unreviewed\n')
        self.reject()

    def test_target_and_checkpoint_and_old_certificate_forgery_rejected(self):
        for mutate in (
            lambda v: v['target_integration'].update(target_commit='0' * 40),
            lambda v: v['target_checkpoint'].update(commit=self.source),
            lambda v: v['target_checkpoint'].update(tree='0' * 40),
            lambda v: v['target_checkpoint']['parents'].reverse(),
            lambda v: v['previous_seal'].update(seal_commit=review.LEFT[:-1] + '0'),
            lambda v: v.update(development_checkpoint={}),
        ):
            value = copy.deepcopy(self.value)
            mutate(value)
            self.reject(lambda: self.production.validate_contract(value))

    def test_missing_target_record_and_unlisted_reconciliation_rejected(self):
        value = copy.deepcopy(self.value)
        value['target_integration']['files'].pop()
        self.reject(lambda: self.production.verify_target_integration(self.root, value))
        value = copy.deepcopy(self.value)
        entry = next(e for e in value['target_integration']['files'] if e['path'].endswith('SequentialWireEmitterTests.scala'))
        entry['after_sha256'] = '0' * 64
        self.reject(lambda: self.production.validate_contract(value))

    def test_predecessor_and_target_projections_are_exact_and_reject_foreign_bytes(self):
        paths = ('core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala',
            'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala')
        for path in paths:
            raw = (self.root / path).read_bytes()
            self.assertEqual(self.production.target_source(self.root, path, raw),
                self.production.frozen(self.root, review.TARGET, path) or b'')
            self.assertEqual(self.production.restore_source(self.root, path, raw),
                self.production.frozen(self.root, self.production.BASE, path) or b'')
            self.reject(lambda: self.production.target_source(self.root, path, raw + b'foreign'))

    def test_combined_ci_or_inventory_mutations_are_rejected(self):
        for path in (review.INVENTORY, '.github/workflows/increment-60f-equivalence-closure.yml',
            '.github/workflows/increment-59i-local-enable-committed-head.yml'):
            raw = (self.root / path).read_bytes()
            try:
                self.append(path)
                self.reject()
            finally:
                (self.root / path).write_bytes(raw)

    def test_regression_budget_accepts_only_exact_240_minute_job_change(self):
        relative = '.github/workflows/increment-60f-equivalence-closure.yml'
        path = self.root / relative
        original = path.read_bytes()
        review.verify_ci_and_inventory(self.root, self.production)
        self.assertEqual(original.count(b'    timeout-minutes: 240\n'), 1)
        self.assertEqual(original.count(b'    timeout-minutes: 180\n'), 1)
        mutations = (
            self.production.frozen(self.root, review.CHECKPOINT, relative),
            original.replace(b'    timeout-minutes: 240\n', b'    timeout-minutes: 241\n', 1),
            original.replace(b'    timeout-minutes: 180\n', b'    timeout-minutes: 240\n', 1),
            original + b'\n# unrelated workflow edit\n',
            original.replace(b'      fail-fast: false\n', b'      fail-fast: true\n', 1),
        )
        try:
            for index, changed in enumerate(mutations):
                with self.subTest(mutation=index):
                    self.assertNotEqual(changed, original)
                    path.write_bytes(changed)
                    with self.assertRaisesRegex(RuntimeError, 'combined qualification workflow changed'):
                        review.verify_ci_and_inventory(self.root, self.production)
        finally:
            path.write_bytes(original)

    def test_complete_source_routes_reject_removed_certificate(self):
        (self.root / review.CONTRACT).unlink()
        (self.root / review.HELPER).unlink()
        spec = importlib.util.spec_from_file_location('pr190_removed_seal',
            self.root / 'morphhdl/scripts/check-pr190-pr189-source-sync.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.reject(lambda: module.verify(self.root))

    def test_sync_route_authenticates_reviewer_before_executing_it(self):
        path = self.root / PATH
        path.write_bytes(path.read_bytes() + b'\nraise AssertionError("unreviewed code executed")\n')
        spec = importlib.util.spec_from_file_location('pr190_changed_reviewer',
            self.root / 'morphhdl/scripts/check-pr190-pr189-source-sync.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.reject(lambda: module.verify(self.root))

    def commit_tree(self, tree, parents):
        args = ['commit-tree', tree]
        for parent in parents:
            args += ['-p', parent]
        return git(self.root, *args, data=b'deliberate source history fixture\n').decode().strip()

    def test_linear_audit_descendant_supported_but_extra_merge_rejected(self):
        source_tree = git(self.root, 'rev-parse', self.source + '^{tree}').decode().strip()
        positive = self.commit_tree(source_tree, [self.source])
        value = dict(self.value, source_commit=positive)
        self.production.verify_pr190_development_history(self.root, value)
        forged = self.commit_tree(source_tree, [self.source, review.TARGET])
        value['source_commit'] = forged
        self.reject(lambda: self.production.verify_pr190_development_history(self.root, value))

    def test_documentation_checkpoint_is_the_exact_two_roadmap_merge(self):
        self.production.verify_pr190_documentation_checkpoint(self.root)
        self.assertEqual(self.production.PR190_DOCUMENTATION_PATHS, frozenset((
            'docs/morphhdl/parameterized-verilog-todo.md',
            'morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md',
        )))
        self.assertTrue(self.production.PR190_DOCUMENTATION_PATHS.isdisjoint(
            self.production.PR190_AUDIT_PATHS))
        for path in self.production.PR190_DOCUMENTATION_PATHS:
            self.assertEqual(self.production.tree(self.root, self.source)[path],
                self.production.tree(self.root, self.production.PR190_DOCUMENTATION_CHECKPOINT)[path])

    def test_documentation_checkpoint_tree_and_parent_forgeries_are_rejected(self):
        with mock.patch.object(self.production, 'PR190_DOCUMENTATION_CHECKPOINT_TREE', '0' * 40):
            with self.assertRaisesRegex(RuntimeError, 'documentation immutable tree changed'):
                self.production.verify_pr190_documentation_checkpoint(self.root)
        for parents in (
                [self.production.PR190_DOCUMENTATION_TARGET, self.production.PR190_PARENT],
                [self.production.PR190_PARENT, review.TARGET],
                [self.production.PR190_PARENT]):
            forged = self.commit_tree(self.production.PR190_DOCUMENTATION_CHECKPOINT_TREE, parents)
            with mock.patch.object(self.production, 'PR190_DOCUMENTATION_CHECKPOINT', forged):
                with self.assertRaisesRegex(RuntimeError, 'documentation checkpoint topology changed'):
                    self.production.verify_pr190_documentation_checkpoint(self.root)

    def test_documentation_live_bytes_and_modes_are_still_sealed(self):
        for relative in self.production.PR190_DOCUMENTATION_PATHS:
            with self.subTest(path=relative):
                path = self.root / relative
                original = path.read_bytes()
                try:
                    self.append(relative)
                    self.reject()
                    path.write_bytes(original)
                    path.chmod(0o755)
                    self.reject()
                finally:
                    path.write_bytes(original)
                    path.chmod(0o644)

    def test_documentation_history_rejects_mode_or_byte_drift_even_if_restored(self):
        source_tree = git(self.root, 'rev-parse', self.source + '^{tree}').decode().strip()
        for relative in self.production.PR190_DOCUMENTATION_PATHS:
            for mode_only in (False, True):
                with self.subTest(path=relative, mode_only=mode_only):
                    git(self.root, 'reset', '--hard', self.source)
                    if mode_only:
                        git(self.root, 'update-index', '--chmod=+x', relative)
                    else:
                        self.append(relative)
                        git(self.root, 'add', relative)
                    bad = self.commit_tree(git(self.root, 'write-tree').decode().strip(), [self.source])
                    restored = self.commit_tree(source_tree, [bad])
                    with self.assertRaisesRegex(RuntimeError, 'changed runtime or unlisted audit source'):
                        self.production.verify_pr190_development_history(
                            self.root, dict(self.value, source_commit=restored))

    def test_exact_documentation_target_integration_is_accepted(self):
        current_tree = git(self.root, 'rev-parse', self.head + '^{tree}').decode().strip()
        integrated = self.commit_tree(current_tree,
            [self.production.PR190_DOCUMENTATION_TARGET, self.head])
        git(self.root, 'reset', '--hard', integrated)
        result = review.verify(self.root)
        self.assertEqual(result['head'], integrated)
        self.assertEqual(result['target'], review.TARGET)
        self.assertEqual((result['runtime_files'], result['target_records']), (1845, 40))

    def test_runtime_drift_then_restore_in_history_is_rejected(self):
        git(self.root, 'reset', '--hard', self.source)
        path = 'core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala'
        self.append(path)
        git(self.root, 'add', path)
        bad = self.commit_tree(git(self.root, 'write-tree').decode().strip(), [self.source])
        restored = self.commit_tree(git(self.root, 'rev-parse', self.source + '^{tree}').decode().strip(), [bad])
        self.reject(lambda: self.production.verify_pr190_development_history(
            self.root, dict(self.value, source_commit=restored)))

    def test_rewritten_preserved_certificate_is_rejected(self):
        git(self.root, 'reset', '--hard', self.source)
        self.append(review.CONTRACT)
        git(self.root, 'add', review.CONTRACT)
        bad = self.commit_tree(git(self.root, 'write-tree').decode().strip(), [self.source])
        self.reject(lambda: self.production.verify_pr190_development_history(
            self.root, dict(self.value, source_commit=bad)))


if __name__ == '__main__':
    unittest.main(verbosity=2)
