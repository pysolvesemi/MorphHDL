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
                (root / cone.canonical_name(index, False)).write_bytes(b"aig 1 1 0 1 0\n0\nc")
            elif name.endswith("-prove"):
                attempt = int(name.split("-")[3])
                stem = cone.attempt_stem(index, attempt)
                (root / (stem + "-proven.aig")).write_bytes(original if index == 0 else normalized)
                (root / cone.canonical_name(index, True, attempt)).write_bytes(b"aig 1 1 0 1 0\n0\nc")
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
        self.assertIn("property-0000-attempt-00-default-prove", self.calls)
        self.assertNotIn("property-0002-attempt-00-default-prove", self.calls)

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
        path = self.root / "property-0001-attempt-00-default-proven.aig"
        path.write_bytes(path.read_bytes() + b"changed")
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_duplicate_property_still_requires_canonical_snapshot(self):
        (self.root / cone.canonical_name(2, False)).unlink()
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_proof_canonical_snapshot_must_match_extraction(self):
        (self.root / cone.canonical_name(1, True)).write_bytes(b"aig 1 1 0 1 0\n1\nc")
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_nonzero_canonical_initial_state_rejected_even_for_duplicate(self):
        (self.root / cone.canonical_name(2, False)).write_bytes(b"aig 1 0 1 1 0\n0 1\n0\nc")
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_canonical_write_without_matching_read_rejected(self):
        path = self.root / "property-0001-attempt-00-default-prove.abc"
        path.write_text(path.read_text().replace("read_aiger property-0001-attempt-00-default-canonical-proof.aig; ", ""))
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
        log = self.root / "property-0001-attempt-00-default-prove.log"
        log.write_text("Property proved.  Time = 0.01 sec\n")
        execution = self.root / "property-0001-attempt-00-default-prove.execution"
        data = cone._load(execution)
        data["log_sha256"] = cone.digest(log)
        cone._write(execution, data)
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_valid_log_requires_actual_process_success(self):
        execution = self.root / "property-0001-attempt-00-default-prove.execution"
        data = cone._load(execution)
        data["returncode"] = 1
        cone._write(execution, data)
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_missing_invariant_rejected(self):
        (self.root / "property-0001-attempt-00-default-invariant.pla").unlink()
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_changed_invariant_rejected(self):
        path = self.root / "property-0001-attempt-00-default-invariant.txt"
        path.write_text(path.read_text().replace(".p 0", ".p 1"))
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_shared_timeout_budget_rejected(self):
        path = self.root / "compile.execution"
        record = cone._load(path)
        record["elapsed_seconds"] = 11
        cone._write(path, record)
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_log_change_rejected(self):
        (self.root / "property-0001-attempt-00-default-prove.log").write_text("PASS\n")
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


LIMIT_LOG = ("Reached conflict limit (100) in frame 27.\n"
             "Property UNDECIDED.  Time = 0.01 sec\n")
FRAME_LOG = ("Reached limit on the number of timeframes (64).\n"
             "Property UNDECIDED.  Time = 0.01 sec\n")


class PortfolioEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.root = self.directory / cone.DIRECTORY
        for name in ("reference.il", "candidate.il"):
            (self.directory / name).write_text("# prepared fixture\n")
        self.calls = []
        self.outcomes = (LIMIT_LOG, GOOD_LOG)
        self.mutate = None

    def command(self, root, name, argv, deadline):
        ConeEvidenceTests.synthetic_command(self, root, name, argv, deadline)
        if not name.endswith("-prove"):
            return
        attempt = int(name.split("-")[3])
        log = self.outcomes[attempt]
        (root / (name + ".log")).write_text(log)
        execution = root / (name + ".execution")
        record = cone._load(execution)
        record["log_sha256"] = cone.digest(root / (name + ".log"))
        cone._write(execution, record)
        if self.mutate:
            self.mutate(root, name, attempt)

    def generate(self):
        self.calls.clear()
        with patch.object(cone, "_command", side_effect=self.command):
            return cone.run_proof(self.directory, "Probe", MITER, 3, 10)

    def validate(self):
        return cone.validate_proof(self.directory, "Probe", MITER, 3, 10)

    def assert_immediate_failure(self):
        with self.assertRaises(cone.ConeProofError):
            self.generate()
        attempts = [name for name in self.calls if name.endswith("-prove")]
        self.assertEqual(attempts, [cone.attempt_stem(0, 0) + "-prove"])
        self.assertFalse((self.root / "evidence.json").exists())

    def test_explicit_effort_limit_advances_and_records_every_attempt(self):
        evidence = self.generate()
        self.assertEqual(evidence, self.validate())
        for proof in evidence["unique_proofs"]:
            self.assertEqual([a["profile"] for a in proof["attempts"]], ["default", "monolithic"])
            self.assertEqual([a["status"] for a in proof["attempts"]], ["UNDECIDED", "PASS"])
            self.assertFalse(proof["attempts"][0]["verified_invariant"])
            self.assertTrue(proof["attempts"][1]["verified_invariant"])
            self.assertEqual(proof["attempts"][0]["effort_limit"], {"kind": "conflicts", "limit": 100})
        self.assertFalse(any(name.startswith(cone.property_stem(2) + "-attempt-") for name in self.calls))
        self.assertTrue((self.root / cone.canonical_name(2, False)).is_file())

    def test_final_unbounded_profile_still_requires_a_real_proof(self):
        self.outcomes = (LIMIT_LOG, FRAME_LOG, LIMIT_LOG, GOOD_LOG)
        evidence = self.generate()
        self.assertEqual(evidence, self.validate())
        self.assertEqual([a["profile"] for a in evidence["unique_proofs"][0]["attempts"]],
                         [profile[0] for profile in cone.PDR_PROFILES])
        self.assertNotIn("-C 100", (self.root / (cone.attempt_stem(0, 3) + "-prove.abc")).read_text())

    def test_counterexample_fails_without_fallback(self):
        self.outcomes = ("Output 0 was asserted in frame 3.\n", GOOD_LOG)
        self.assert_immediate_failure()

    def test_truncated_proof_log_fails_without_fallback(self):
        self.outcomes = (GOOD_LOG.split("Property proved.")[0], GOOD_LOG)
        self.assert_immediate_failure()

    def test_cpu_timeout_fails_without_fallback(self):
        self.outcomes = ("Reached time limit (10).\nProperty UNDECIDED. Time = 10.00 sec\n", GOOD_LOG)
        self.assert_immediate_failure()

    def test_wall_timeout_fails_without_fallback(self):
        def timeout(root, name, attempt):
            path = root / (name + ".execution")
            record = cone._load(path)
            record.update(returncode=None, timed_out=True)
            cone._write(path, record)
        self.mutate = timeout
        self.assert_immediate_failure()

    def test_nonzero_exit_or_parser_error_does_not_fallback(self):
        def exit_error(root, name, attempt):
            path = root / (name + ".execution")
            record = cone._load(path)
            record["returncode"] = 1
            cone._write(path, record)
        self.mutate = exit_error
        self.assert_immediate_failure()
        self.mutate = None
        self.outcomes = (LIMIT_LOG + "Error: solver input rejected\n", GOOD_LOG)
        self.assert_immediate_failure()

    def test_missing_forged_or_contradictory_limit_does_not_fallback(self):
        for log in ("Property UNDECIDED. Time = 0.01 sec\n", LIMIT_LOG.replace("100", "99"),
                    FRAME_LOG.replace("64", "65"), LIMIT_LOG + GOOD_LOG, LIMIT_LOG + LIMIT_LOG,
                    LIMIT_LOG + "Output 0 was asserted in frame 3.\n",
                    LIMIT_LOG + "Reached time limit (10).\n",
                    LIMIT_LOG.replace("frame 27.", "frame unknown.")):
            with self.subTest(log=log):
                self.outcomes = (log, GOOD_LOG)
                self.assert_immediate_failure()

    def test_changed_log_hash_does_not_fallback(self):
        self.mutate = lambda root, name, attempt: (root / (name + ".log")).write_text(LIMIT_LOG + "changed\n")
        self.assert_immediate_failure()

    def test_changed_canonical_or_formula_does_not_fallback(self):
        for suffix in ("-canonical-proof.aig", "-proven.aig"):
            def change(root, name, attempt):
                path = root / (name.removesuffix("-prove") + suffix)
                path.write_bytes(path.read_bytes() + b"changed")
            with self.subTest(suffix=suffix):
                self.mutate = change
                self.assert_immediate_failure()

    def test_missing_prior_attempt_is_rejected_even_when_manifest_is_adjusted(self):
        self.generate()
        for path in self.root.glob(cone.attempt_stem(0, 0) + "-*"):
            path.unlink()
        path = self.root / "evidence.json"
        data = cone._load(path)
        data["unique_proofs"][0]["attempts"].pop(0)
        cone._write(path, data)
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_reordered_attempts_and_changed_profile_scripts_are_rejected(self):
        self.generate()
        path = self.root / "evidence.json"
        data = cone._load(path)
        data["unique_proofs"][0]["attempts"].reverse()
        cone._write(path, data)
        with self.assertRaises(cone.ConeProofError): self.validate()
        self.generate()
        path = self.root / (cone.attempt_stem(0, 0) + "-prove.abc")
        path.write_text(path.read_text().replace("pdr -C", "pdr -m -C"))
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_extra_attempt_after_pass_or_on_duplicate_is_rejected(self):
        for index, attempt in ((0, 2), (2, 0)):
            with self.subTest(index=index):
                self.generate()
                (self.root / (cone.attempt_stem(index, attempt) + "-prove.log")).write_text(GOOD_LOG)
                with self.assertRaises(cone.ConeProofError): self.validate()

    def test_prior_attempt_requires_all_artifacts_and_unchanged_model(self):
        for suffix in ("-prove.abc", "-prove.log", "-prove.execution", "-canonical-proof.aig", "-proven.aig",
                       "-invariant.pla", "-invariant.txt", "-invariant.execution"):
            with self.subTest(suffix=suffix):
                self.generate()
                (self.root / (cone.attempt_stem(0, 0) + suffix)).unlink()
                with self.assertRaises(cone.ConeProofError): self.validate()
        self.generate()
        path = self.root / (cone.attempt_stem(0, 0) + "-canonical-proof.aig")
        path.write_bytes(path.read_bytes() + b"changed")
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_effort_limited_clauses_are_required_but_never_certify_the_property(self):
        evidence = self.generate()
        attempt = evidence["unique_proofs"][0]["attempts"][0]
        self.assertEqual(attempt["retained_clauses"], "unverified-last-timeframe")
        self.assertFalse(attempt["verified_invariant"])
        self.assertEqual(attempt["status"], "UNDECIDED")
        (self.root / (cone.attempt_stem(0, 0) + "-invariant.pla")).unlink()
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_effort_limited_raw_and_canonical_clause_changes_are_rejected(self):
        for suffix in ("-invariant.pla", "-invariant.txt", "-invariant.execution"):
            with self.subTest(suffix=suffix):
                self.generate()
                path = self.root / (cone.attempt_stem(0, 0) + suffix)
                path.write_text(path.read_text() + "changed\n")
                with self.assertRaises(cone.ConeProofError): self.validate()

    def test_missing_effort_limited_clauses_prevents_fallback(self):
        def remove_clauses(root, name, attempt):
            (root / (name.removesuffix("-prove") + "-invariant.pla")).unlink()
        self.mutate = remove_clauses
        self.assert_immediate_failure()

    def test_effort_limited_clauses_cannot_be_relabelled_as_verified(self):
        self.generate()
        path = self.root / "evidence.json"
        data = cone._load(path)
        data["unique_proofs"][0]["attempts"][0].update(
            retained_clauses="verified-invariant", verified_invariant=True)
        cone._write(path, data)
        with self.assertRaises(cone.ConeProofError): self.validate()

    def test_every_attempt_counts_toward_shared_budget(self):
        self.generate()
        path = self.root / (cone.attempt_stem(0, 0) + "-prove.execution")
        data = cone._load(path)
        data["elapsed_seconds"] = 10
        cone._write(path, data)
        with self.assertRaisesRegex(cone.ConeProofError, "shared binding timeout"): self.validate()

    def test_unknown_for_every_profile_never_becomes_pass(self):
        self.outcomes = (LIMIT_LOG,) * len(cone.PDR_PROFILES)
        with self.assertRaises(cone.ConeProofError): self.generate()
        self.assertEqual(len([name for name in self.calls if name.endswith("-prove")]), len(cone.PDR_PROFILES))
        self.assertFalse((self.root / "evidence.json").exists())
        self.assertEqual(cone._load(self.root / "failure.json")["status"], "FAIL")

    def test_repeated_portfolio_evidence_is_identical(self):
        first = self.generate()
        second = self.generate()
        self.assertEqual(first, second)
        self.assertEqual(first, self.validate())


class CommandDiagnosticTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.argv = ["yosys-abc", "-f", "property-0002-attempt-00-default-prove.abc"]
        self.name = "property-0002-attempt-00-default-prove"
        self.log = self.root / (self.name + ".log")
        self.log.write_text(GOOD_LOG)
        self.record = {
            "argv": self.argv, "returncode": 0, "timed_out": False,
            "elapsed_seconds": 0.01, "log_sha256": cone.digest(self.log),
        }

    def validate(self, **changes):
        cone._write(self.root / (self.name + ".execution"), {**self.record, **changes})
        return cone._validate_command(self.root, self.name, self.argv)

    def test_success_requires_the_retained_successful_execution(self):
        self.assertEqual(self.validate(), GOOD_LOG)
        for code in (1, -9):
            with self.subTest(code=code):
                with self.assertRaisesRegex(cone.ConeProofError, f"command exited with status {code}: {self.name}"):
                    self.validate(returncode=code)
        with self.assertRaisesRegex(cone.ConeProofError, f"command launch failed: {self.name}"):
            self.validate(returncode=None)

    def test_timeout_requires_a_valid_record_and_unchanged_log(self):
        with self.assertRaisesRegex(cone.ConeProofError, f"^TIMEOUT:.*{self.name}$"):
            self.validate(returncode=None, timed_out=True)
        self.log.write_text("changed after the recorded timeout\n")
        with self.assertRaisesRegex(cone.ConeProofError, f"^command log hash mismatch: {self.name}$"):
            self.validate(returncode=None, timed_out=True)

    def test_invalid_record_cannot_be_classified_as_timeout(self):
        mutations = (
            {"argv": ["different-tool"]}, {"returncode": 0}, {"returncode": False},
            {"returncode": 0.0}, {"timed_out": 1}, {"timed_out": "true"},
            {"elapsed_seconds": True}, {"elapsed_seconds": -1},
            {"elapsed_seconds": float("nan")}, {"elapsed_seconds": float("inf")},
            {"elapsed_seconds": "1"}, {"log_sha256": None}, {"log_sha256": "bad"},
            {"unexpected": "field"},
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                changes = {"returncode": None, "timed_out": True, **mutation}
                with self.assertRaisesRegex(cone.ConeProofError, "^invalid command execution record:"):
                    self.validate(**changes)

    def test_missing_fields_and_nonobject_records_are_invalid(self):
        for record in ([], {key: value for key, value in self.record.items() if key != "timed_out"}):
            with self.subTest(record=record):
                cone._write(self.root / (self.name + ".execution"), record)
                with self.assertRaisesRegex(cone.ConeProofError, "^invalid command execution record:"):
                    cone._validate_command(self.root, self.name, self.argv)

    def test_zero_exit_tool_error_remains_distinct_and_rejected(self):
        self.log.write_text("Error: invalid ABC command\n")
        with self.assertRaisesRegex(cone.ConeProofError, f"^tool reported an error: {self.name}$"):
            self.validate(log_sha256=cone.digest(self.log))


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
            self.assertIn("; dch; dc2", commands)
        for proof, commands in ((False, cone.extraction_script(0)), (True, cone.proof_script(0, True, 10))):
            name = cone.canonical_name(0, proof)
            self.assertIn(f"dc2; &get; &w -u {name}; read_aiger {name}; dch", commands)
        self.assertIn("pdr -m -y -r -T 10 -v -d -I", cone.proof_script(0, True, 10, attempt=3))
        for attempt, flags in enumerate(("", "-m ", "-m -y ")):
            self.assertIn(f"pdr {flags}-C 100 -D 100 -F 64 -T 10", cone.proof_script(0, True, 10, attempt=attempt))

    def test_canonical_initial_state_requires_zero_for_every_latch(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.aig"
            for latch in (b"0\n", b"0 0\n", b"2 0\n"):
                path.write_bytes(b"aig 1 0 1 1 0\n" + latch + b"0\nc")
                self.assertEqual(cone.zero_initialized_single(path)["L"], 1)
            for latch in (b"0 1\n", b"0 2\n", b"4\n", b"\n", b"0"):
                path.write_bytes(b"aig 1 0 1 1 0\n" + latch + b"0\nc")
                with self.subTest(latch=latch):
                    with self.assertRaises(cone.ConeProofError): cone.zero_initialized_single(path)


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
            (root / cone.canonical_name(0, False)).write_bytes(b"aig 3 3 0 1 0\n0\nc")
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
        self.assertEqual(self.evidence["schema_version"], 3)
        self.assertEqual(proof["proof_method"], "constant-false")
        self.assertFalse(proof["verified_invariant"])
        self.assertNotIn("property-0000-attempt-00-default-prove", self.calls)

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
