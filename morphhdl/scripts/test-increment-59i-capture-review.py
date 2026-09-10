#!/usr/bin/env python3
"""Exercise exact callback-source reversal without weakening prior certificates."""
import copy
import hashlib
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('capture_review', ROOT / 'morphhdl/scripts/check-increment-59i-source-review.py')
R = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(R)


class CaptureSourceReviewTests(unittest.TestCase):
    def test_original_certificates_are_unchanged(self):
        for path, sha in ((R.CONTRACT, R.CONTRACT_SHA256), (R.INTEGRATION_CONTRACT, R.INTEGRATION_SHA256)):
            raw = (ROOT / path).read_bytes()
            self.assertEqual(hashlib.sha256(raw).hexdigest(), sha)
            self.assertEqual(raw, subprocess.check_output(['git', 'show', R.CAPTURE_BASE + ':' + path], cwd=ROOT))

    def test_current_successor_sources_reverse_through_both_exact_layers(self):
        entries = R.load_capture_contract(ROOT)
        self.assertEqual(tuple(entries), R.CAPTURE_PATHS)
        for path, entry in entries.items():
            current = (ROOT / path).read_bytes()
            previous = R.capture_baseline_source(ROOT, path)
            self.assertEqual(R.restore_reviewed(entry, previous, current), previous)
            self.assertEqual(R.restore_source(ROOT, path, current.decode()).encode(), R.baseline_source(ROOT, path))

    def test_each_changed_span_and_unreviewed_suffix_reject(self):
        count = 0
        for path, entry in R.load_capture_contract(ROOT).items():
            previous = R.capture_baseline_source(ROOT, path)
            current = (ROOT / path).read_bytes()
            for edit in entry['edits']:
                a, b = edit['after_start'], edit['after_end']
                if a == b:
                    changed = current[:a] + b'// unauthorized\n' + current[a:]
                else:
                    changed = current[:a] + bytes([current[a] ^ 1]) + current[a+1:]
                with self.assertRaises(RuntimeError): R.restore_reviewed(entry, previous, changed)
                count += 1
            for changed in (b'// unauthorized\n' + current, current + b'\n// unauthorized\n', previous):
                with self.assertRaises(RuntimeError): R.restore_reviewed(entry, previous, changed)
                count += 1
        self.assertGreater(count, 30)
        print('Capture source mutation controls:', count)

    def test_disjoint_callback_paths_restore_before_old_owner_inventory(self):
        spec = importlib.util.spec_from_file_location('owner_capture_view', ROOT / 'morphhdl/scripts/check-increment-59h-source-review.py')
        owner = importlib.util.module_from_spec(spec); spec.loader.exec_module(owner)
        paths = owner.production_changes(ROOT, owner.BASE)
        sentinel = 'foreign/src/main/Unreviewed.scala'
        restored = owner.register_inherited_inventory(ROOT, paths | {sentinel}, owner.BASE)
        new_paths = {p for p in R.CAPTURE_PATHS if '/src/main/' in p} - set(R.PATHS)
        self.assertFalse(new_paths & restored)
        self.assertEqual(set(owner.PRODUCTION_PATHS) & restored, set(owner.PRODUCTION_PATHS))
        self.assertIn(sentinel, restored, 'unreviewed paths must not disappear during composition')

    def test_missing_modified_executable_and_linked_sidecars_reject(self):
        raw = (ROOT / R.CAPTURE_CONTRACT).read_bytes()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); p = root / R.CAPTURE_CONTRACT; p.parent.mkdir(parents=True)
            with self.assertRaises(RuntimeError): R.load_capture_contract(root)
            p.write_bytes(raw + b'\n')
            with self.assertRaises(RuntimeError): R.load_capture_contract(root)
            p.write_bytes(raw); p.chmod(0o755)
            with self.assertRaises(RuntimeError): R.load_capture_contract(root)
            p.chmod(0o644); alternate = p.with_suffix('.data'); p.rename(alternate); p.symlink_to(alternate.name)
            with self.assertRaises(RuntimeError): R.load_capture_contract(root)

    def test_omitted_added_reordered_or_same_count_substituted_paths_reject(self):
        valid = json.loads((ROOT / R.CAPTURE_CONTRACT).read_text())
        for operation in ('remove', 'duplicate', 'replace', 'reorder', 'base'):
            value = copy.deepcopy(valid)
            if operation == 'remove': value['files'].pop()
            elif operation == 'duplicate': value['files'].append(copy.deepcopy(value['files'][0]))
            elif operation == 'replace': value['files'][0]['path'] = 'foreign/src/main/Unknown.scala'
            elif operation == 'reorder': value['files'].reverse()
            else: value['base'] = R.BASE
            with self.assertRaises(RuntimeError): R.validate_contract(value, R.CAPTURE_BASE, R.CAPTURE_PATHS)
        self.assertEqual(len({p for p in R.CAPTURE_PATHS if '/src/main/' in p} - set(R.PATHS)), 2)
        with self.assertRaises(RuntimeError): R.require_production_inventory(set(R.PRODUCTION_PATHS) - {R.CAPTURE_PATHS[0]})


if __name__ == '__main__': unittest.main()
