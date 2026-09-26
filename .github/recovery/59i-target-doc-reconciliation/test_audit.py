#!/usr/bin/env python3
"""Focused rejection controls for the schema-19 target reconciliation."""
from __future__ import annotations

import hashlib
import importlib.util
import io
import os
from pathlib import Path
import unittest
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("schema19_target_audit", HERE / "audit.py")
A = importlib.util.module_from_spec(spec)
spec.loader.exec_module(A)


def archive(files: dict[str, bytes]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as target:
        for name, raw in files.items():
            target.writestr(name, raw)
    return output.getvalue()


class AuditTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.files = A.zip_files(Path(os.environ["SOURCE_ARTIFACT"]).read_bytes())

    def test_exact_result_inventory_passes(self):
        A.validate_results(dict(self.files))

    def test_failed_result_is_rejected(self):
        files = dict(self.files)
        files["results.tsv"] = files["results.tsv"].replace(b"PASS\t0\t", b"FAIL\t1\t", 1)
        with self.assertRaisesRegex(RuntimeError, "failed or malformed"):
            A.validate_results(files)

    def test_missing_log_is_rejected(self):
        files = dict(self.files); files["05.log"] = b""
        with self.assertRaisesRegex(RuntimeError, "empty command log"):
            A.validate_results(files)

    def test_digest_precedes_member_authority(self):
        files = dict(self.files); files["started.txt"] = b"forged\n"
        with self.assertRaisesRegex(RuntimeError, "artifact digest"):
            A.zip_files(archive(files))

    def test_unsafe_member_is_rejected_after_digest_gate(self):
        old = A.ARTIFACT_SHA256
        try:
            raw = archive({"../escape": b"x"})
            A.ARTIFACT_SHA256 = hashlib.sha256(raw).hexdigest()
            with self.assertRaisesRegex(RuntimeError, "unsafe artifact"):
                A.zip_files(raw)
        finally:
            A.ARTIFACT_SHA256 = old

    def test_identity_mutation_is_detectable(self):
        files = dict(self.files)
        files["identity.txt"] = files["identity.txt"].replace(A.SEAL.encode(), b"0" * 40, 1)
        self.assertNotEqual(files["identity.txt"].decode().splitlines()[0], A.SEAL)

    def test_target_identity_mutation_is_detectable(self):
        files = dict(self.files)
        files["live-refs.txt"] = files["live-refs.txt"].replace(A.TARGET.encode(), b"0" * 40, 1)
        self.assertNotIn("target=" + A.TARGET, files["live-refs.txt"].decode().splitlines())


if __name__ == "__main__":
    unittest.main(verbosity=2)
