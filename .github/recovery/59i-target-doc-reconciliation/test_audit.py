#!/usr/bin/env python3
"""Focused rejection controls for the 59i target-documentation audit."""
from __future__ import annotations

import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("target_doc_audit", HERE / "audit.py")
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
        artifact = Path(__import__("os").environ["SOURCE_ARTIFACT"]).read_bytes()
        cls.files = A.zip_files(artifact)

    def test_exact_result_inventory_passes(self):
        A.validate_results(dict(self.files))

    def test_failed_result_is_rejected(self):
        files = dict(self.files)
        files["results.tsv"] = files["results.tsv"].replace(b"PASS\t0\t", b"FAIL\t1\t", 1)
        with self.assertRaisesRegex(RuntimeError, "failed or malformed"):
            A.validate_results(files)

    def test_missing_or_empty_log_is_rejected(self):
        files = dict(self.files); files["17.log"] = b""
        with self.assertRaisesRegex(RuntimeError, "empty command log"):
            A.validate_results(files)

    def test_changed_target_instructions_are_rejected(self):
        raw = b"# MorphHDL project instructions\n" + b"x" * (5651 - 32)
        with self.assertRaises(RuntimeError):
            A.validate_target_bytes(raw)

    def test_digest_precedes_zip_member_authority(self):
        files = dict(self.files); files["started.txt"] = b"forged\n"
        raw = archive(files)
        self.assertNotEqual(hashlib.sha256(raw).hexdigest(), A.ARTIFACT_SHA256)
        with self.assertRaisesRegex(RuntimeError, "artifact digest"):
            A.zip_files(raw)

    def test_unsafe_member_is_rejected_after_exact_digest_gate(self):
        # Exercise the lexical control directly without granting a forged digest.
        old = A.ARTIFACT_SHA256
        try:
            raw = archive({"../escape": b"x"})
            A.ARTIFACT_SHA256 = hashlib.sha256(raw).hexdigest()
            with self.assertRaisesRegex(RuntimeError, "unsafe artifact"):
                A.zip_files(raw)
        finally:
            A.ARTIFACT_SHA256 = old


if __name__ == "__main__":
    unittest.main(verbosity=2)
