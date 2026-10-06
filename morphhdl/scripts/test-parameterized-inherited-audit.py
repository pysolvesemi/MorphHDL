#!/usr/bin/env python3
"""Explicit policy-migration contracts; historical programs remain immutable.

The former runtime/checkout controls described recursive replay. Their source is
retained at the pinned baseline. Current controls instead reject unauthenticated
receipts and scope changes, while byte comparisons preserve all unaffected CI
commands, jobs, matrices, proofs, timeouts and artifacts.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
from candidate_provenance_migration import restore_workflow

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('migrated_audit', Path(__file__).with_name('run-parameterized-inherited-audit.py'))
A = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(A)


class MigratedAuditTests(unittest.TestCase):
    def test_only_exact_reviewed_source_scopes_are_admitted(self):
        for entry in A.COMMANDS:
            arguments = ['morphhdl/scripts/' + entry[0], *entry[1:]]
            self.assertEqual(A.command(arguments)[2:], arguments)
        for arguments in ([], ['/tmp/check-increment-61-source-review.py'],
                ['morphhdl/scripts/../scripts/check-increment-61-source-review.py'],
                ['morphhdl/scripts/check-increment-60f-equivalence-closure.py', 'target/rtl'],
                ['morphhdl/scripts/check-increment-61-source-review.py', '--repo-root', '/tmp'],
                ['morphhdl/scripts/check-increment-61-source-review.py;true'],
                ['morphhdl/scripts/check-wa11-boolean-width.py']):
            with self.subTest(arguments=arguments), self.assertRaises(RuntimeError):
                A.command(arguments)

    def test_missing_authenticated_result_fails_before_scope_receipt(self):
        with patch.object(A, 'consume', side_effect=RuntimeError('missing producer')), \
                patch.object(A.tempfile, 'NamedTemporaryFile') as output:
            with self.assertRaisesRegex(RuntimeError, 'missing producer'):
                A.run(ROOT, ['morphhdl/scripts/check-increment-61-source-review.py'])
            output.assert_not_called()

    def test_every_migrated_scope_has_frozen_hashed_evidence(self):
        inventory = json.loads((ROOT / A.P.INVENTORY).read_text())
        anchor = next(n for n in inventory['certificates'] if n['commit'] == A.PREDECESSOR)
        for entry in A.COMMANDS:
            path = 'morphhdl/scripts/' + entry[0]
            raw = A.P.git(ROOT, 'show', A.PREDECESSOR + ':' + path)
            self.assertEqual(anchor['evidence'][path]['sha256'], hashlib.sha256(raw).hexdigest())

    def test_workflows_preserve_every_byte_outside_explicit_policy_migration(self):
        contract = json.loads((ROOT / 'morphhdl/contracts/candidate-provenance-workflow-migration.json').read_text())
        self.assertEqual(len(contract['workflows']), 12)
        for path, record in contract['workflows'].items():
            with self.subTest(path=path):
                current = (ROOT / path).read_text()
                original = A.P.git(ROOT, 'show', contract['baseline'] + ':' + path).decode()
                self.assertEqual(hashlib.sha256(current.encode()).hexdigest(), record['after_sha256'])
                self.assertEqual(hashlib.sha256(original.encode()).hexdigest(), record['before_sha256'])
                self.assertEqual(restore_workflow(path, current), original)
                # All original lane identities, matrices and deadlines survive.
                for token in ('timeout-minutes:', 'matrix:', 'scala:', 'needs:', 'runs-on:'):
                    self.assertEqual([line for line in current.splitlines() if token in line],
                                     [line for line in original.splitlines() if token in line])

    def test_missing_duplicate_and_modified_migration_hunks_are_rejected(self):
        contract = json.loads((ROOT / 'morphhdl/contracts/candidate-provenance-workflow-migration.json').read_text())
        for path, record in contract['workflows'].items():
            current = (ROOT / path).read_text()
            for hunk in record['patches']:
                for replacement in ('', hunk['after'] * 2, hunk['after'] + '# unreviewed\n'):
                    # Insertion after a hunk is an unrelated edit, retained for
                    # exact workflow comparison; edits to the hunk fail closed.
                    mutated = current.replace(hunk['after'], replacement, 1)
                    try:
                        restored = restore_workflow(path, mutated)
                    except AssertionError:
                        continue
                    self.assertNotEqual(restored, restore_workflow(path, current))

    def test_historical_certificate_and_fixture_files_remain_unchanged(self):
        baseline = 'f70a20cebdc37319a6b74d0bfe227a0e4403101f'
        for path in ('morphhdl/contracts/increment-59i-production-successor.json',
                     'morphhdl/scripts/check-increment-59i-production-successor.py',
                     'morphhdl/scripts/check-increment-61-source-review.py',
                     'morphhdl/scripts/test-increment-60f-inherited-source-scope.py',
                     'morphhdl/scripts/test-increment-59g-source-review.py',
                     'morphhdl/scripts/test-increment-59h-inherited-source-scope.py'):
            self.assertEqual((ROOT / path).read_bytes(), A.P.git(ROOT, 'show', baseline + ':' + path))


if __name__ == '__main__':
    unittest.main(verbosity=2)
