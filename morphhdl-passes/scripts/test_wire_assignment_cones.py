#!/usr/bin/env python3
"""Fail-closed evidence and complete-property coverage tests (synthetic tools)."""
import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import prove_wire_assignment_cones as cone

GOOD_LOG = ("Verification of invariant with 0 clauses was successful.  Time = 0.00 sec\n"
            "Property proved.  Time = 0.01 sec\n")
MITER = "module Probe; always @* begin assert(1); assert(1); assert(1); end endmodule\n"


class ConeEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        for name in ("reference.il", "candidate.il"):
            (self.directory / name).write_text("# prepared fixture\n")
        self.calls = []
        with patch.object(cone, "_command", side_effect=self.synthetic_command):
            self.evidence = cone.run_proof(self.directory, "Probe", MITER, 3, 10)
        self.root = self.directory / cone.DIRECTORY

    def synthetic_command(self, root, name, argv, deadline):
        self.calls.append(name)
        text = "synthetic tool fixture\n"
        if name == "compile":
            (root / "full.aig").write_bytes(b"aig 0 0 0 0 0 3 0\n0\n0\n0\n")
        else:
            index = int(name.split("-")[1])
            stem = cone.property_stem(index)
            # Property zero normalizes to zero outputs; it MUST still be proved.
            # Properties one and two are byte-identical and share one proof.
            original = b"aig 1 0 0 1 1\n2\n" + bytes([index])
            normalized = b"aig 0 0 0 0 0\n" if index == 0 else b"aig 1 0 0 1 1\n2\nshared"
            if name.endswith("-isolate"):
                (root / (stem + "-raw.aig")).write_bytes(original)
            elif name.endswith("-extract"):
                (root / (stem + "-original.aig")).write_bytes(original)
                (root / (stem + "-normalized.aig")).write_bytes(normalized)
            elif name.endswith("-prove"):
                (root / (stem + "-proven.aig")).write_bytes(original if index == 0 else normalized)
                text = GOOD_LOG
                (root / (stem + "-invariant.pla")).write_text(
                    "# synthetic timestamp\n.i 0\n.o 1\n.p 0\n.e\n")
        (root / (name + ".log")).write_text(text)
        cone._write(root / (name + ".execution"), {
            "argv": argv, "returncode": 0, "timed_out": False,
            "elapsed_seconds": 0.01,
            "log_sha256": cone.digest(root / (name + ".log")),
        })

    def validate(self):
        return cone.validate_proof(self.directory, "Probe", MITER, 3, 10)

    def alter_evidence(self, mutate):
        path = self.root / "evidence.json"
        evidence = json.loads(path.read_text())
        mutate(evidence)
        cone._write(path, evidence)

    def test_complete_duplicate_coverage_and_constant_fallback(self):
        self.assertEqual(self.validate(), self.evidence)
        self.assertEqual([row["index"] for row in self.evidence["properties"]], [0, 1, 2])
        self.assertEqual(len(self.evidence["unique_proofs"]), 2)
        self.assertFalse(self.evidence["properties"][0]["normalization_kept_output"])
        self.assertIn("property-0000-prove", self.calls)
        self.assertNotIn("property-0002-prove", self.calls)

    def test_reordered_properties_rejected(self):
        self.alter_evidence(lambda data: data["properties"].reverse())
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_omitted_property_rejected(self):
        self.alter_evidence(lambda data: data["properties"].pop())
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_duplicate_property_index_rejected(self):
        self.alter_evidence(lambda data: data["properties"][1].update(index=0))
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_missing_property_file_rejected(self):
        (self.root / "property-0002-normalized.aig").unlink()
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_formula_hash_mismatch_rejected(self):
        self.alter_evidence(lambda data: data["properties"][1].update(formula_sha256="0" * 64))
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_changed_duplicate_requires_independent_proof(self):
        path = self.root / "property-0002-normalized.aig"
        path.write_bytes(path.read_bytes() + b"changed")
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_proved_snapshot_must_equal_extracted_formula(self):
        path = self.root / "property-0001-proven.aig"
        path.write_bytes(path.read_bytes() + b"changed")
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_source_changed_rejected(self):
        (self.directory / "reference.il").write_text("different source\n")
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_noncanonical_clock_preparation_rejected(self):
        path = self.root / "compile.ys"
        path.write_text(path.read_text().replace("clk2fflogic", "formalff -clk2ff"))
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_noncanonical_cone_index_rejected(self):
        path = self.root / "property-0002-extract.abc"
        path.write_text(path.read_text().replace("-O 2", "-O 1"))
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_false_pass_without_verified_invariant_rejected(self):
        log = self.root / "property-0001-prove.log"
        log.write_text("Property proved.  Time = 0.01 sec\n")
        execution = self.root / "property-0001-prove.execution"
        data = cone._load(execution)
        data["log_sha256"] = cone.digest(log)
        cone._write(execution, data)
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_valid_log_requires_actual_process_success(self):
        execution = self.root / "property-0001-prove.execution"
        data = cone._load(execution)
        data["returncode"] = 1
        cone._write(execution, data)
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_missing_invariant_rejected(self):
        (self.root / "property-0001-invariant.pla").unlink()
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_changed_invariant_rejected(self):
        path = self.root / "property-0001-invariant.txt"
        path.write_text(path.read_text().replace(".p 0", ".p 1"))
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_shared_timeout_budget_rejected(self):
        path = self.root / "compile.execution"
        record = cone._load(path)
        record["elapsed_seconds"] = 11
        cone._write(path, record)
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_log_change_rejected(self):
        (self.root / "property-0001-prove.log").write_text("PASS\n")
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_malformed_manifest_rejected(self):
        (self.root / "evidence.json").write_text("{ broken\n")
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_wrong_property_contract_rejected(self):
        with self.assertRaises(cone.ConeProofError):
            cone.validate_proof(self.directory, "Probe", MITER, 2, 10)

    def test_wrong_assumption_contract_rejected(self):
        with self.assertRaises(cone.ConeProofError):
            cone.validate_proof(self.directory, "Probe", MITER, 3, 10, expected_assumption_count=1)

    def test_full_aig_property_loss_rejected(self):
        (self.root / "full.aig").write_bytes(b"aig 0 0 0 0 0 2 0\n0\n0\n")
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_failed_rerun_removes_previous_success_and_retains_diagnostic(self):
        with patch.object(cone, "_command", side_effect=cone.ConeProofError("TIMEOUT test")):
            with self.assertRaises(cone.ConeProofError):
                cone.run_proof(self.directory, "Probe", MITER, 3, 10)
        self.assertFalse((self.root / "evidence.json").exists())
        self.assertEqual(cone._load(self.root / "failure.json")["status"], "FAIL")

    def test_real_subprocess_timeout_records_failure(self):
        with patch.object(cone.subprocess, "run", side_effect=subprocess.TimeoutExpired(["yosys"], 0.1)):
            with self.assertRaises(cone.ConeProofError):
                cone._command(self.root, "timeout", ["yosys"], cone.time.monotonic() + 0.1)
        record = cone._load(self.root / "timeout.execution")
        self.assertTrue(record["timed_out"])
        self.assertIsNone(record["returncode"])


class ConeParsingTests(unittest.TestCase):
    def test_strict_pdr_status(self):
        self.assertEqual(cone.validate_pdr_log(GOOD_LOG), "PASS")
        for bad in ("PASS", GOOD_LOG + GOOD_LOG, GOOD_LOG + "UNKNOWN\n", GOOD_LOG + "UNDECIDED\n",
                    GOOD_LOG + "Output 0 was asserted in frame 3.\n",
                    "The problem is trivially true for all states.\n",
                    GOOD_LOG.replace("successful", "unsuccessful")):
            with self.subTest(bad=bad):
                with self.assertRaises(cone.ConeProofError): cone.validate_pdr_log(bad)

    def test_malformed_header_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.aig"
            for value in (b"aig 0 0 0 1 0", b"aag 0 0 0 1 0\n", b"aig 0 0 0 -1 0\n",
                          b"aig 2 0 0 1 0\n", b"aig 0 0 0 1 0 0 0 0 0 0\n"):
                path.write_bytes(value)
                with self.subTest(value=value):
                    with self.assertRaises(cone.ConeProofError): cone.aiger_header(path)

    def test_no_init_or_clock_weakening(self):
        script = cone.compile_script("Probe")
        keep = "setattr -set keep 1 t:$assert t:$assume t:$check"
        self.assertIn(keep, script)
        self.assertLess(script.index(keep), script.index("prep -top"))
        self.assertLess(script.index("clk2fflogic"), script.index("formalff -ff2anyinit"))
        self.assertLess(script.index("formalff -ff2anyinit"), script.index("opt -full -keepdc"))
        self.assertNotIn("formalff -clk2ff", script)
        self.assertNotIn("-setundef", script)
        for commands in (cone.extraction_script(0), cone.proof_script(0, True, 10)):
            self.assertIn("&get; &trim -o; &put", commands)
            self.assertNotIn("; trim", commands)
        self.assertIn("pdr -y -T 10 -v -d -I", cone.proof_script(0, True, 10))


class ConstantFalseEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.miter = "module Probe; always @* assert(1); endmodule\n"
        self.calls = []
        for name in ("reference.il", "candidate.il"):
            (self.directory / name).write_text("# prepared fixture\n")
        with patch.object(cone, "_command", side_effect=self.command):
            self.evidence = cone.run_proof(self.directory, "Probe", self.miter, 1, 10)
        self.root = self.directory / cone.DIRECTORY

    def command(self, root, name, argv, deadline):
        self.calls.append(name)
        if name == "compile":
            (root / "full.aig").write_bytes(b"aig 0 0 0 0 0 1\n0\nc")
        elif name.endswith("-isolate"):
            (root / "property-0000-raw.aig").write_bytes(b"aig 3 3 0 1 0\n0\nc")
        elif name.endswith("-extract"):
            for suffix in ("original", "normalized"):
                (root / f"property-0000-{suffix}.aig").write_bytes(b"aig 3 3 0 1 0\n0\nc")
        else:
            raise AssertionError("constant-false certificate must not invoke a solver")
        (root / (name + ".log")).write_text("synthetic extraction fixture\n")
        cone._write(root / (name + ".execution"), {
            "argv": argv, "returncode": 0, "timed_out": False,
            "elapsed_seconds": 0.01, "log_sha256": cone.digest(root / (name + ".log")),
        })

    def validate(self):
        return cone.validate_proof(self.directory, "Probe", self.miter, 1, 10)

    def test_literal_zero_is_separate_structural_proof(self):
        self.assertEqual(self.validate(), self.evidence)
        proof = self.evidence["unique_proofs"][0]
        self.assertEqual(self.evidence["schema_version"], 2)
        self.assertEqual(proof["proof_method"], "constant-false")
        self.assertFalse(proof["verified_invariant"])
        self.assertNotIn("property-0000-prove", self.calls)

    def test_missing_certificate_rejected(self):
        (self.root / "property-0000-constant-false.json").unlink()
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_modified_certificate_rejected(self):
        path = self.root / "property-0000-constant-false.json"
        record = cone._load(path)
        record["formula_sha256"] = "0" * 64
        cone._write(path, record)
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_output_mutation_cannot_reuse_certificate(self):
        path = self.root / "property-0000-original.aig"
        path.write_bytes(path.read_bytes().replace(b"\n0\n", b"\n1\n"))
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_normalized_constant_cannot_replace_original_literal(self):
        original = self.directory / "original.aig"
        normalized = self.directory / "normalized.aig"
        normalized.write_bytes(b"aig 1 1 0 1 0\n0\nc")
        for literal in (0, 1, 2):
            original.write_bytes(f"aig 3 3 0 1 0\n{literal}\nc".encode())
            self.assertFalse(cone.use_normalized_formula(original, normalized))

    def test_certificate_discriminant_cannot_claim_pdr(self):
        path = self.root / "evidence.json"
        record = cone._load(path)
        record["unique_proofs"][0].update(proof_method="abc-pdr", verified_invariant=True)
        cone._write(path, record)
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_structural_certificate_rejects_nonzero_outputs_and_state(self):
        path = self.directory / "model.aig"
        for value in (b"aig 0 0 0 1 0\n1\nc", b"aig 1 1 0 1 0\n2\nc",
                      b"aig 1 0 1 1 0\n0\n0\nc", b"aig 1 0 0 1 1\n0\n\x00\x00c"):
            path.write_bytes(value)
            with self.subTest(value=value):
                self.assertIsNone(cone.constant_false_certificate(path))

    def test_malformed_or_trailing_certificate_data_rejected(self):
        path = self.directory / "model.aig"
        for body in (b"", b"0", b"00\nc", b"0\nextra", b"0\ncgarbage", b"0\n0\nc", b"2\nc"):
            path.write_bytes(b"aig 0 0 0 1 0\n" + body)
            with self.subTest(body=body):
                with self.assertRaises(cone.ConeProofError): cone.constant_false_certificate(path)


if __name__ == "__main__":
    unittest.main()
