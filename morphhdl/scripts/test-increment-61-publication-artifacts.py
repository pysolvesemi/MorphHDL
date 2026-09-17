#!/usr/bin/env python3
"""Regression controls for failed-attempt artifact isolation and exact-head comparison."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("check-increment-61-publication-artifacts.py")
spec = importlib.util.spec_from_file_location("publication_artifacts", SCRIPT)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
HEAD = "a" * 40
RUN = "12345"


class PublicationArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="increment61-artifact-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for scala in gate.SCALAS:
            root = self.root / (gate.PREFIX + scala)
            root.mkdir()
            (root / "head.txt").write_text(HEAD + "\n")
            for generation in ("generated-a", "generated-b"):
                for relative in gate.FILES:
                    path = root / generation / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text("fixture " + relative + "\n")
            gate.record(root, HEAD, scala, RUN, 1)
        self.right = self.root / (gate.PREFIX + "2.13.12")

    def check(self):
        return gate.compare(self.root, HEAD, RUN, 2)

    def manifest(self, **changes):
        path = self.right / gate.MANIFEST
        value = json.loads(path.read_text())
        value.update(changes)
        path.write_text(json.dumps(value))

    def test_complete_publications_pass(self):
        self.assertEqual(self.check()["status"], "PASS")

    def test_mixed_successful_attempts_in_same_run_pass(self):
        gate.record(self.right, HEAD, "2.13.12", RUN, 2)
        self.assertEqual(self.check()["status"], "PASS")

    def test_failed_attempt_without_generated_files_is_rejected(self):
        import shutil
        shutil.rmtree(self.right / "generated-a")
        with self.assertRaisesRegex(RuntimeError, "generation set"):
            self.check()

    def test_wrong_commit_run_lane_and_attempt_are_rejected(self):
        original = (self.right / gate.MANIFEST).read_bytes()
        mutations = ({"source_commit": "b" * 40}, {"workflow_run_id": "98765"},
                     {"scala_version": "2.12.18"}, {"producer_attempt": 3},
                     {"producer_attempt": 0}, {"producer_attempt": True}, {"schema_version": True})
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                (self.right / gate.MANIFEST).write_bytes(original)
                self.manifest(**mutation)
                with self.assertRaises(RuntimeError):
                    self.check()

    def test_unsealed_or_duplicate_manifest_is_rejected(self):
        path = self.right / gate.MANIFEST
        original = path.read_text()
        for malformed in ("{}", original.rstrip()[:-1] + ', "source_commit": "' + HEAD + '"}'):
            with self.subTest(manifest=malformed[:20]):
                path.write_text(malformed)
                with self.assertRaises(RuntimeError):
                    self.check()

    def test_changed_head_receipt_is_rejected(self):
        (self.right / "head.txt").write_text("b" * 40 + "\n")
        with self.assertRaisesRegex(RuntimeError, "source commit"):
            self.check()

    def test_partial_extra_empty_and_linked_outputs_are_rejected(self):
        for mutation in ("missing", "extra", "empty", "linked"):
            with self.subTest(mutation=mutation):
                path = self.right / "generated-a" / "split/Increment61Top.v"
                original = path.read_bytes()
                extra = self.right / "generated-a/extra.v"
                try:
                    if mutation == "missing": path.unlink()
                    if mutation == "extra": extra.write_text("extra")
                    if mutation == "empty": path.write_bytes(b"")
                    if mutation == "linked":
                        path.unlink()
                        path.symlink_to(self.right / "generated-b/split/Increment61Top.v")
                    with self.assertRaises(RuntimeError): self.check()
                finally:
                    if path.is_symlink(): path.unlink()
                    path.write_bytes(original)
                    if extra.exists(): extra.unlink()

    def test_modified_both_generations_without_new_hash_is_rejected(self):
        for generation in ("generated-a", "generated-b"):
            (self.right / generation / "split/Increment61Top.v").write_text("changed\n")
        with self.assertRaisesRegex(RuntimeError, "recorded file hashes"):
            self.check()

    def test_real_cross_scala_mismatch_survives_new_manifest(self):
        for generation in ("generated-a", "generated-b"):
            (self.right / generation / "split/Increment61Top.v").write_text("changed\n")
        gate.record(self.right, HEAD, "2.13.12", RUN, 2)
        with self.assertRaisesRegex(RuntimeError, "cross-Scala publication bytes"):
            self.check()

    def test_missing_lane_and_extra_diagnostic_artifact_are_rejected(self):
        (self.root / "increment-61-diagnostics-2.13.12-attempt-1").mkdir()
        with self.assertRaisesRegex(RuntimeError, "Scala artifact inventory"):
            self.check()

    def test_workflow_keeps_failures_separate_and_all_existing_gates(self):
        workflow = SCRIPT.parents[2] / ".github/workflows/increment-61-one-file-per-component.yml"
        text = workflow.read_text()
        self.assertIn("name: increment-61-diagnostics-${{ matrix.scala }}-attempt-${{ github.run_attempt }}", text)
        self.assertIn("pattern: increment-61-qualified-2.*", text)
        block = text.split("- name: Upload qualified publication only after every producer gate", 1)[1]
        block = block.split("  cross-scala:", 1)[0]
        for expected in ("if: success()", "overwrite: true", "name: increment-61-qualified-${{ matrix.scala }}"):
            self.assertIn(expected, block)
        for expected in ("equiv_status -assert", "missing child definition unexpectedly compiled", "duplicate child definition unexpectedly compiled", "wrong-file publication mutation was not detected", "diff -ru", "submodules: recursive", "fetch-depth: 0"):
            self.assertIn(expected, text)

    def test_review_trigger_is_owner_only_same_repo_branch_and_exact_head(self):
        text = (SCRIPT.parents[2] / ".github/workflows/increment-61-one-file-per-component.yml").read_text()
        guard = text.split("  source:\n", 1)[1].split("    name:", 1)[0]
        for expected in ("github.event_name != 'pull_request_review'", "github.event.review.state == 'commented'", "github.event.review.user.login == github.repository_owner", "github.event.pull_request.head.repo.full_name == github.repository", "github.event.pull_request.base.ref == 'parameterized-verilog'", "github.event.pull_request.head.ref == 'agent/lane-when-expression-inlining'", "github.event.review.commit_id == github.event.pull_request.head.sha", "github.event.review.body == format('/qualify-increment61 {0}', github.event.pull_request.head.sha)"):
            self.assertIn(expected, guard)
        self.assertNotIn("pull_request_target:", text)
        self.assertIn("permissions:\n  contents: read", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
