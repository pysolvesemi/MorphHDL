#!/usr/bin/env python3
"""Real-repository controls for 60f's historical callback-integration reader.

All mutations are confined to disposable Git worktrees. --checker-path allows
an external development reader to be checked without changing a sealed tree;
that mode must not be reported as final-head qualification.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

parser = argparse.ArgumentParser(add_help=False)
parser.add_argument('--repo-root', type=Path, default=Path(__file__).resolve().parents[2])
parser.add_argument('--checker-path', type=Path)
parser.add_argument('--result-json', type=Path)
options, unittest_arguments = parser.parse_known_args()
ROOT = options.repo_root.resolve()
CHECKER = (options.checker_path or ROOT / 'morphhdl/scripts/check-increment-60f-equivalence-closure.py').resolve()
HELPER = 'morphhdl/scripts/check-increment-59i-production-successor.py'
CONTRACT = 'morphhdl/contracts/increment-59d-59f-integration-edits.json'
SCALAR = 'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionScalarGraphReplay.scala'
LEGACY = ('cf353ddd45c766576488ae45748d1d17876c3b11',
          'c10bab1e72d051ee03f4ff05c0f56abdc0815553')


def git(root: Path, *arguments: str) -> bytes:
    result = subprocess.run(['git', '-C', str(root), *arguments],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            check=False, timeout=120)
    if result.returncode:
        raise RuntimeError('fixture Git failed: ' + repr(arguments) + '\n' + result.stderr.decode(errors='replace'))
    return result.stdout


def load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError('cannot load reader: ' + str(path))
    module = importlib.util.module_from_spec(spec)
    exec(compile(path.read_bytes(), str(path), 'exec'), module.__dict__)
    return module


class HistoricalIntegrationReaderTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.head = git(ROOT, 'rev-parse', 'HEAD').decode().strip()
        cls.index = git(ROOT, 'ls-files', '--stage', '-z')
        if git(ROOT, 'status', '--porcelain'):
            raise RuntimeError('real checkout must be clean before isolated reader tests')
        cls.auth = load(ROOT / HELPER, 'real_60f_test_source_authentication')
        cls.auth.verify(ROOT)
        cls.reader = load(CHECKER, 'real_60f_integration_reader_under_test')
        cls.expected = {entry['path']: entry['after_sha256']
                        for entry in json.loads((ROOT / CONTRACT).read_bytes())['files']}
        cls.temp = tempfile.TemporaryDirectory(prefix='morphhdl-60f-real-integration-tests-')

    @classmethod
    def tearDownClass(cls):
        cls.auth.verify(ROOT)
        if git(ROOT, 'rev-parse', 'HEAD').decode().strip() != cls.head or \
                git(ROOT, 'ls-files', '--stage', '-z') != cls.index or git(ROOT, 'status', '--porcelain'):
            raise RuntimeError('isolated reader tests changed the real checkout')
        cls.temp.cleanup()

    def setUp(self):
        self.fixture = Path(self.temp.name) / self._testMethodName
        revision = LEGACY[0] if self._testMethodName.endswith('legacy_first') else \
                   LEGACY[1] if self._testMethodName.endswith('legacy_second') else self.head
        git(ROOT, 'worktree', 'add', '--quiet', '--detach', str(self.fixture), revision)

    def tearDown(self):
        git(ROOT, 'worktree', 'remove', '--force', str(self.fixture))

    def check(self):
        return self.reader.integration_59d59f(self.fixture)

    def expect_rejection(self, text):
        with self.assertRaises(RuntimeError) as result:
            self.check()
        self.assertIn(text, str(result.exception))

    def test_current_sealed_source_uses_real_projection(self):
        self.assertIsNotNone(self.reader.named_source_review(self.fixture))
        self.assertEqual(self.check(), self.expected)

    def test_legacy_first(self):
        self.assertIsNone(self.reader.named_source_review(self.fixture))
        self.assertEqual(self.check(), self.expected)

    def test_legacy_second(self):
        self.assertIsNone(self.reader.named_source_review(self.fixture))
        self.assertEqual(self.check(), self.expected)

    def test_unreviewed_current_source_rejects(self):
        path = self.fixture / SCALAR
        path.write_bytes(path.read_bytes() + b'\n// Deliberate isolated reader mutation.\n')
        self.expect_rejection('59i production successor: unreviewed bytes cannot enter predecessor projection: ' + SCALAR)

    def test_frozen_integration_manifest_change_rejects(self):
        path = self.fixture / CONTRACT
        path.write_bytes(path.read_bytes() + b'\n')
        self.expect_rejection('59d/59f reviewed integration manifest changed')

    def test_linked_integration_manifest_rejects(self):
        path = self.fixture / CONTRACT
        saved = Path(self.temp.name) / ('manifest-' + self._testMethodName)
        saved.write_bytes(path.read_bytes())
        path.unlink(); path.symlink_to(saved)
        self.expect_rejection('missing regular 59d/59f integration review')

    def test_missing_integration_source_rejects(self):
        (self.fixture / SCALAR).unlink()
        self.expect_rejection('reviewed production source must be a regular non-executable file: ' + SCALAR)

    def test_executable_integration_source_rejects(self):
        (self.fixture / SCALAR).chmod(0o755)
        self.expect_rejection('reviewed production source must be a regular non-executable file: ' + SCALAR)

    def test_linked_integration_source_rejects(self):
        path = self.fixture / SCALAR
        saved = Path(self.temp.name) / ('source-' + self._testMethodName)
        saved.write_bytes(path.read_bytes())
        path.unlink(); path.symlink_to(saved)
        self.expect_rejection('reviewed production source must be a regular non-executable file: ' + SCALAR)

    def test_forged_outer_successor_manifest_rejects(self):
        path = self.fixture / self.auth.CONTRACT
        path.write_bytes(path.read_bytes() + b'\n')
        self.expect_rejection('59i production successor: sealed successor manifest changed')


if __name__ == '__main__':
    program = unittest.main(argv=[sys.argv[0], *unittest_arguments], exit=False)
    result = program.result
    if options.result_json is not None:
        report = {'scope': __doc__, 'repo_head': git(ROOT, 'rev-parse', 'HEAD').decode().strip(),
                  'reader': str(CHECKER), 'reader_sha256': hashlib.sha256(CHECKER.read_bytes()).hexdigest(),
                  'test_source_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  'tests_run': result.testsRun, 'failures': len(result.failures),
                  'errors': len(result.errors), 'skipped': len(result.skipped),
                  'passed': result.wasSuccessful()}
        options.result_json.parent.mkdir(parents=True, exist_ok=True)
        options.result_json.write_text(json.dumps(report, indent=2) + '\n')
    raise SystemExit(0 if result.wasSuccessful() else 1)
