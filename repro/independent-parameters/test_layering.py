"""Ensure the standalone application is scanned, never an audit exception."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPRO_ROOT = 'repro/independent-parameters/src/main'
SPEC = importlib.util.spec_from_file_location(
    'independent_layering_guard', ROOT / 'morphhdl/scripts/check-typed-layering-ir.py')
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)


class StandaloneLayeringTests(unittest.TestCase):
    def setUp(self):
        self.manifest = GUARD.load_manifest(ROOT / GUARD.DEFAULT_MANIFEST)
        self.contract = GUARD.compile_contract(self.manifest)
        self.temporary = tempfile.TemporaryDirectory(prefix='independent-layering-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for source_root in self.contract['roots']:
            (self.root / source_root).mkdir(parents=True)
        GUARD.write_build_fixtures(self.root, self.contract['build'])
        GUARD.write_required_files(self.root, self.contract)
        self.probe = self.root / REPRO_ROOT / 'scala/repro/AuditProbe.scala'
        self.probe.parent.mkdir(parents=True)
        self.probe.write_text('package repro\nobject AuditProbe\n')
        GUARD.validate_sources(self.root, self.contract)

    def test_reproducer_is_a_scanned_high_level_application(self):
        self.assertIn(REPRO_ROOT, self.contract['roots'])
        self.assertNotIn(REPRO_ROOT, self.manifest['low_level_source_roots'])
        self.assertGreater(GUARD.validate_sources(ROOT, self.contract), 0)

    def test_retired_sidecar_in_reproducer_is_rejected(self):
        self.probe.write_text('package repro\nobject ExternalParameterizedMemoryRegistry\n')
        with self.assertRaisesRegex(GUARD.LayeringError, 'obsolete-parameterized-sidecar-symbol'):
            GUARD.validate_sources(self.root, self.contract)

    def test_duplicate_typed_owner_in_reproducer_is_rejected(self):
        self.probe.write_text('package repro\nfinal class ElabInt\n')
        with self.assertRaisesRegex(GUARD.LayeringError, 'elab-int-owner'):
            GUARD.validate_sources(self.root, self.contract)

    def test_narrowing_reproducer_out_of_inventory_is_rejected(self):
        narrowed = json.loads(json.dumps(self.manifest))
        narrowed['production_source_roots'].remove(REPRO_ROOT)
        with self.assertRaisesRegex(GUARD.LayeringError, 'closed Increment 54 contract'):
            GUARD.compile_contract(narrowed)


if __name__ == '__main__':
    unittest.main()
