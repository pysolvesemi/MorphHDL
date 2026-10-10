#!/usr/bin/env python3
"""Real Git fixtures for the current-head provenance boundary."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('provenance', Path(__file__).with_name('check-candidate-provenance.py'))
P = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(P)


class CandidateProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name) / 'source'
        self.root.mkdir()
        root_patch = patch.object(P, 'HISTORICAL_ROOT', None)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        P.git(self.root, 'config', 'user.name', 'Provenance fixture')
        P.git(self.root, 'config', 'user.email', 'fixture@example.invalid')
        self.evidence = 'morphhdl/contracts/frozen.json'
        self.write(self.evidence, '{"retained":true}\n')
        self.commit()
        anchor = P.identity(self.root)
        self.node = {'commit': anchor['commit'], 'tree': anchor['tree'], 'parents': [],
                     'references': [], 'evidence': {self.evidence: {
                         'mode': '100644', 'sha256': P.digest((self.root / self.evidence).read_bytes())}}}
        self.inventory = {'schema': 1, 'policy': P.POLICY_ID, 'roots': [anchor['commit']],
                          'reviewer_paths': [], 'certificates': [self.node], 'successor_chain': []}
        self.write(P.INVENTORY, json.dumps(self.inventory))
        for path in (P.SELF, P.POLICY, P.SEAL, P.CI, P.MIGRATION):
            self.write(path, 'reviewed fixture\n')
        self.write(P.GUARD, 'def verify(root):\n    assert (root / "AGENTS.md").read_text() == "reviewed fixture\\n"\n')
        self.commit()
        self.receipt = Path(self.directory.name) / 'receipt.json'

    def write(self, path, contents):
        p = self.root / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(contents)

    def commit(self):
        P.git(self.root, 'add', '.')
        P.git(self.root, 'commit', '-qm', 'fixture')

    def produce(self):
        return P.produce(self.root, self.receipt)

    def test_producer_and_consumer_exact_identity_without_historical_execution(self):
        original = P.git
        calls = []
        def observed(root, *args):
            calls.append(args)
            return original(root, *args)
        with patch.object(P, 'git', side_effect=observed):
            sha = self.produce()
            result = P.consume(self.root, self.receipt, sha)
        self.assertEqual(result['candidate'], P.identity(self.root))
        self.assertEqual(len(result['certificates']), 1)
        self.assertEqual(sum(c[:2] == ('cat-file', '-t') for c in calls), 1)
        self.assertFalse(any(c[0] in ('worktree', 'checkout', 'clone') for c in calls))
        with self.assertRaisesRegex(RuntimeError, 'already exists'):
            self.produce()

    def test_missing_altered_failed_and_incomplete_receipts_rejected(self):
        sha = self.produce()
        original = self.receipt.read_bytes()
        for field, value in (('status', 'fail'), ('certificates', []), ('policy', 'obsolete'),
                             ('rtl_qualification', True)):
            record = P.parse(original)
            record[field] = value
            raw = P.canonical(record)
            self.receipt.write_bytes(raw)
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                P.consume(self.root, self.receipt, P.digest(raw))
        self.receipt.write_bytes(original + b' ')
        with self.assertRaisesRegex(RuntimeError, 'altered receipt'):
            P.consume(self.root, self.receipt, sha)
        self.receipt.unlink()
        with self.assertRaisesRegex(RuntimeError, 'missing or linked receipt'):
            P.consume(self.root, self.receipt, sha)

    def test_consumer_requires_external_digest(self):
        self.produce()
        for sha in (None, '', 'pass', '0' * 64):
            with self.subTest(sha=sha), self.assertRaises(RuntimeError):
                P.consume(self.root, self.receipt, sha)

    def test_changed_candidate_and_same_tree_new_commit_rejected(self):
        sha = self.produce()
        P.git(self.root, 'commit', '--allow-empty', '-qm', 'different candidate same tree')
        with self.assertRaisesRegex(RuntimeError, 'stale candidate'):
            P.consume(self.root, self.receipt, sha)

    def test_uncommitted_source_and_modified_policy_checker_manifest_rejected(self):
        sha = self.produce()
        for path in (P.POLICY, P.SELF, P.INVENTORY, self.evidence):
            file = self.root / path
            original = file.read_bytes()
            file.write_bytes(original + b'\nchanged\n')
            with self.subTest(path=path), self.assertRaisesRegex(RuntimeError, 'dirty candidate'):
                P.consume(self.root, self.receipt, sha)
            file.write_bytes(original)
        self.write('untracked', 'unreviewed')
        with self.assertRaisesRegex(RuntimeError, 'dirty candidate'):
            P.consume(self.root, self.receipt, sha)

    def test_changed_authenticated_inputs_rejected_even_with_updated_candidate_field(self):
        sha = self.produce()
        self.write(P.POLICY, 'revised policy')
        self.commit()
        record = P.parse(self.receipt.read_bytes())
        record['candidate'] = P.identity(self.root)
        raw = P.canonical(record)
        self.receipt.write_bytes(raw)
        with self.assertRaisesRegex(RuntimeError, 'stale policy, checker or manifest'):
            P.consume(self.root, self.receipt, P.digest(raw))

    def test_historical_evidence_hash_mode_tree_parent_and_missing_path_rejected(self):
        for mutation in ('hash', 'mode', 'tree', 'parents', 'missing'):
            inventory = copy.deepcopy(self.inventory)
            node = inventory['certificates'][0]
            if mutation == 'hash': node['evidence'][self.evidence]['sha256'] = '0' * 64
            if mutation == 'mode': node['evidence'][self.evidence]['mode'] = '100755'
            if mutation == 'tree': node['tree'] = '0' * 40
            if mutation == 'parents': node['parents'] = [node['commit']]
            if mutation == 'missing': node['evidence']['missing.json'] = node['evidence'].pop(self.evidence)
            with self.subTest(mutation=mutation), self.assertRaises(RuntimeError):
                P.authenticate_history(self.root, inventory, P.identity(self.root))

    def test_wrong_ancestry_rejected(self):
        # A valid commit/tree/blob tuple from a disconnected history is not
        # evidence for the candidate even when all its hashes are authentic.
        tree = self.node['tree']
        foreign = subprocess.check_output(['git', 'commit-tree', tree, '-m', 'foreign'],
                                         cwd=self.root).decode().strip()
        inventory = copy.deepcopy(self.inventory)
        inventory['roots'] = [foreign]
        inventory['certificates'][0]['commit'] = foreign
        with self.assertRaises(subprocess.CalledProcessError):
            P.authenticate_history(self.root, inventory, P.identity(self.root))

    def test_missing_duplicate_unreachable_certificates_and_duplicate_json_rejected(self):
        for mutation in ('missing', 'duplicate', 'unreachable'):
            inventory = copy.deepcopy(self.inventory)
            if mutation == 'missing': inventory['certificates'][0]['references'] = ['0' * 40]
            if mutation == 'duplicate': inventory['certificates'].append(copy.deepcopy(self.node))
            if mutation == 'unreachable': inventory['roots'] = ['0' * 40]
            with self.subTest(mutation=mutation), self.assertRaises(RuntimeError):
                P.validate_inventory(inventory)
        with self.assertRaisesRegex(RuntimeError, 'duplicate JSON'):
            P.parse(b'{"schema":1,"schema":2}')

    def test_linked_inputs_and_receipts_rejected(self):
        sha = self.produce()
        original = self.receipt.with_suffix('.original')
        self.receipt.rename(original)
        self.receipt.symlink_to(original)
        with self.assertRaisesRegex(RuntimeError, 'linked receipt'):
            P.consume(self.root, self.receipt, sha)
        path = self.root / P.POLICY
        path.unlink()
        path.symlink_to(original)
        with self.assertRaisesRegex(RuntimeError, 'linked file'):
            P.regular(self.root, P.POLICY)


if __name__ == '__main__':
    unittest.main(verbosity=2)
