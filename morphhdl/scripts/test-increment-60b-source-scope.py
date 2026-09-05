#!/usr/bin/env python3
"""Exercise introduction and regression audits with real, isolated Git histories."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name('check-increment-60b-signedness-authority.py')
SPEC = importlib.util.spec_from_file_location('signedness_scope', SCRIPT)
assert SPEC is not None and SPEC.loader is not None
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)
ROOT = Path(__file__).resolve().parents[2]
PREREQUISITE = '- [x] **Increment 60a — Baseline, semantic contract and independent oracle**'
TITLE = '**Increment 60b — Typed declaration and expression signedness authority**'


class SourceScopeLifecycleTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix='morphhdl-60b-scope-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Scope Regression')
        self.git('config', 'user.email', 'scope-test@example.invalid')
        self.write(CHECKER.ROADMAP, PREREQUISITE + '\n' + TITLE + '\n')
        self.base = self.commit('qualified prerequisite')
        for name in CHECKER.PRODUCTION:
            self.write(name, (ROOT / name).read_text())
        self.write(CHECKER.SCOPE, '\n'.join(sorted((*CHECKER.PRODUCTION, CHECKER.SCOPE))) + '\n')
        self.introduction = self.commit('analysis-only introduction')
        tree = self.git('rev-parse', 'HEAD^{tree}')
        self.merged = self.git('commit-tree', tree, '-p', self.base, '-p', self.introduction,
                               '-m', 'merge qualified introduction')
        for name, value in (('BASE', self.base), ('QUALIFIED_MERGE', self.merged)):
            manager = patch.object(CHECKER, name, value)
            manager.start()
            self.addCleanup(manager.stop)

    def git(self, *arguments: str) -> str:
        return subprocess.check_output(['git', *arguments], cwd=self.root, text=True,
                                       stderr=subprocess.PIPE).strip()

    def write(self, name: str, text: str) -> None:
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def commit(self, message: str) -> str:
        self.git('add', '.')
        self.git('-c', 'commit.gpgsign=false', 'commit', '-qm', message)
        return self.git('rev-parse', 'HEAD')

    def checkout(self, revision: str) -> None:
        self.git('checkout', '-q', '--detach', revision)

    def check(self) -> str:
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            CHECKER.check(self.root, source_scope=True)
        return output.getvalue()

    def test_initial_candidate_still_audits_its_entire_delta(self) -> None:
        self.assertIn('scope PASS at HEAD', self.check())
        self.write('unreviewed.scala', 'unrelated change\n')
        self.commit('unapproved introduction change')
        with self.assertRaisesRegex(RuntimeError, 'outside the reviewed analysis-only source scope'):
            self.check()

    def test_merged_descendant_does_not_attribute_later_increments_to_60b(self) -> None:
        self.checkout(self.merged)
        self.write('morphhdl-passes/later.scala', 'independently reviewed pass\n')
        self.write('morphhdl/recursive.scala', 'independently reviewed recursion\n')
        self.commit('Merge Increment 59a; preserve 60b and WA-07')
        output = self.check()
        self.assertIn('scope PASS at ' + self.merged, output)
        self.assertIn('current dependency and analysis-only boundary PASS', output)

    def test_current_production_still_rejects_forbidden_inference(self) -> None:
        self.checkout(self.merged)
        path = self.root / CHECKER.PRODUCTION[1]
        path.write_text(path.read_text() + '\n// getName( is forbidden\n')
        self.commit('bad current inference')
        with self.assertRaisesRegex(RuntimeError, 'forbidden dependency: getName'):
            self.check()

    def test_current_production_still_requires_identity_authority(self) -> None:
        self.checkout(self.merged)
        path = self.root / CHECKER.PRODUCTION[1]
        path.write_text(path.read_text().replace('IdentityHashMap', 'UnsafeMap'))
        self.commit('remove current identity binding')
        with self.assertRaisesRegex(RuntimeError, 'missing exact authority boundary: IdentityHashMap'):
            self.check()

    def test_current_roadmap_dependency_cannot_be_unchecked(self) -> None:
        self.checkout(self.merged)
        self.write(CHECKER.ROADMAP, PREREQUISITE.replace('[x]', '[ ]') + '\n' + TITLE + '\n')
        self.commit('invalid current dependency')
        with self.assertRaisesRegex(RuntimeError, '60a must be complete'):
            self.check()

    def test_missing_qualified_history_does_not_disable_the_delta_audit(self) -> None:
        with patch.object(CHECKER, 'QUALIFIED_MERGE', 'f' * 40):
            self.assertIn('scope PASS at HEAD', self.check())
            self.write('unreviewed.scala', 'unapproved change\n')
            self.commit('out-of-scope without qualified history')
            with self.assertRaisesRegex(RuntimeError, 'outside the reviewed analysis-only source scope'):
                self.check()

    def test_missing_prerequisite_history_fails_closed(self) -> None:
        with patch.object(CHECKER, 'BASE', 'e' * 40):
            with self.assertRaises(subprocess.CalledProcessError):
                self.check()

    def test_unrelated_prerequisite_is_not_accepted(self) -> None:
        tree = self.git('rev-parse', self.base + '^{tree}')
        unrelated = self.git('commit-tree', tree, '-m', 'unrelated root')
        with patch.object(CHECKER, 'BASE', unrelated):
            with self.assertRaisesRegex(RuntimeError, 'not an ancestor'):
                self.check()

    def test_both_production_sources_are_required_in_the_initial_delta(self) -> None:
        self.git('rm', CHECKER.PRODUCTION[1])
        self.commit('incomplete introduction')
        with self.assertRaisesRegex(RuntimeError, 'both exact graph binding and target-neutral facts'):
            self.check()

    def test_current_inventory_cannot_rewrite_the_sealed_introduction_audit(self) -> None:
        self.write('unreviewed.scala', 'bad introduction\n')
        invalid_introduction = self.commit('bad introduction scope')
        self.write(CHECKER.SCOPE, (self.root / CHECKER.SCOPE).read_text() + 'unreviewed.scala\n')
        self.commit('try to widen history from a later checkout')
        with patch.object(CHECKER, 'QUALIFIED_MERGE', invalid_introduction):
            with self.assertRaisesRegex(RuntimeError, 'outside the reviewed analysis-only source scope'):
                self.check()

    def test_workflow_does_not_infer_scope_from_merge_message_text(self) -> None:
        workflow = (ROOT / '.github/workflows/increment-60b-signedness-authority.yml').read_text()
        self.assertNotIn('head_commit.message', workflow)
        self.assertNotIn('--without-git-scope', workflow)
        self.assertIn('python3 morphhdl/scripts/test-increment-60b-source-scope.py -v', workflow)
        for required in ('TypedSignednessAuthorityTests', 'TypedSignednessResumeTests',
                         'TypedSignednessReplayArtifactWriter', 'check-increment-60a-sint-baseline.py',
                         "scala: ['2.12.18', '2.13.12']"):
            self.assertIn(required, workflow)


if __name__ == '__main__':
    unittest.main()
