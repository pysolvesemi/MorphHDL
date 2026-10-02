#!/usr/bin/env python3
"""Fail-closed controls for the bounded 59f source/test job partition."""
from __future__ import annotations

from pathlib import Path
import re
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = ".github/workflows/increment-59f-callback-graphs.yml"
BASELINE = "c48bad51b9857570e65fbd7c1be5dec465a3e8fa"
WORKFLOW = (ROOT / WORKFLOW_PATH).read_text()


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True)


def job(text: str, name: str, next_name: str | None = None) -> str:
    start = text.index("  " + name + ":\n")
    if next_name is None:
        return text[start:]
    return text[start:text.index("  " + next_name + ":\n", start + 1)]


def steps(text: str) -> tuple[str, list[str]]:
    chunks = re.split(r"(?=^      - )", text, flags=re.M)
    return chunks[0], chunks[1:]


class SchedulingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.before = git("show", BASELINE + ":" + WORKFLOW_PATH)
        cls.before_prefix, cls.before_jobs = cls.before.split("jobs:\n", 1)
        cls.current_prefix, cls.current_jobs = WORKFLOW.split("jobs:\n", 1)
        cls.old_header, cls.old_steps = steps(job("jobs:\n" + cls.before_jobs,
                                                  "validate"))
        cls.source_header, cls.source_steps = steps(job(
            "jobs:\n" + cls.current_jobs, "source", "validate"))
        cls.validate_header, cls.validate_steps = steps(job(
            "jobs:\n" + cls.current_jobs, "validate"))

    def test_triggers_permissions_concurrency_and_global_environment_are_exact(self):
        self.assertEqual(self.current_prefix, self.before_prefix)

    def test_original_checkout_and_every_source_audit_command_are_exact(self):
        self.assertEqual(self.source_steps[0], self.old_steps[0])
        original = self.old_steps[1]
        current = self.source_steps[1]
        additive = "          python3 morphhdl/scripts/test-increment-59f-scheduling.py\n"
        self.assertEqual(current, original + additive)

    def test_both_scala_lanes_keep_every_original_test_proof_and_artifact_step(self):
        self.assertEqual(self.validate_steps[0], self.old_steps[0])
        self.assertEqual(self.validate_steps[2:], self.old_steps[2:])
        self.assertIn("matrix:\n        scala: ['2.12.18', '2.13.12']",
                      self.validate_header)
        self.assertIn("fail-fast: false", self.validate_header)

    def test_source_success_and_exact_head_tree_gate_both_scala_lanes(self):
        self.assertIn("  validate:\n    needs: source\n", WORKFLOW)
        prep = self.validate_steps[1]
        self.assertIn("AUDITED_HEAD: ${{ needs.source.outputs.head }}", prep)
        self.assertIn("AUDITED_TREE: ${{ needs.source.outputs.tree }}", prep)
        self.assertIn('test "$(git rev-parse HEAD)" = "$AUDITED_HEAD"', prep)
        self.assertIn("test \"$(git rev-parse 'HEAD^{tree}')\" = \"$AUDITED_TREE\"", prep)

    def test_source_identity_is_published_only_after_the_exact_audits(self):
        identity = self.source_steps[2]
        self.assertIn("id: identity", identity)
        self.assertIn("head=%s", identity)
        self.assertIn("tree=%s", identity)
        self.assertIn("outputs:\n      head: ${{ steps.identity.outputs.head }}\n"
                      "      tree: ${{ steps.identity.outputs.tree }}",
                      self.source_header)

    def test_source_and_lane_budgets_are_exact_and_no_gate_is_softened(self):
        self.assertEqual(WORKFLOW.count("    timeout-minutes: 360\n"), 2)
        self.assertNotIn("continue-on-error", WORKFLOW)
        self.assertNotIn("fail-fast: true", WORKFLOW)

    def test_separate_source_artifact_retains_the_audit_checkout(self):
        evidence = self.source_steps[3]
        self.assertIn("if: always()", evidence)
        self.assertIn("name: increment-59f-source-${{ github.run_attempt }}", evidence)
        self.assertIn("path: target/increment-59f-callback-graphs", evidence)
        self.assertIn("if-no-files-found: error", evidence)


if __name__ == "__main__":
    unittest.main()
