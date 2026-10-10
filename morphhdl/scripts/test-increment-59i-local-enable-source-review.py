#!/usr/bin/env python3
"""Reject local-enable review mutations without changing the production tree.

Pure span/schema/identity cases use the exact review payload or disposable Git
fixtures. The final class additionally requires the real complete current
source seal; its failure cannot be converted into a skipped qualification.
"""
from __future__ import annotations

import ast
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "morphhdl/scripts/check-increment-59i-local-enable-source-review.py"
spec = importlib.util.spec_from_file_location("local_enable_source_review_test", SOURCE)
assert spec is not None and spec.loader is not None
W = importlib.util.module_from_spec(spec)
exec(compile(SOURCE.read_bytes(), str(SOURCE), "exec"), W.__dict__)


class LocalEnableReviewUnitTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = (ROOT / W.CONTRACT).read_bytes()
        cls.value = json.loads(cls.raw)
        cls.entries = W.load_contract(ROOT)

    def source(self, path, entry):
        before = W.frozen(ROOT, W.BASE, path)
        pieces, position = [], 0
        for edit in entry["edits"]:
            pieces.extend((before[position:edit["before_start"]], edit["after"].encode()))
            position = edit["before_end"]
        pieces.append(before[position:])
        result = b"".join(pieces)
        self.assertEqual(W.digest(result), entry["source_sha256"])
        return before, result

    def test_exact_four_runtime_files_and_new_suite_are_bound(self):
        self.assertEqual(tuple(self.entries), W.PATHS)
        self.assertEqual(len(W.PRODUCTION_PATHS), 4)
        self.assertIn("TypedBalancedReductionBackend.scala", " ".join(W.PRODUCTION_PATHS))
        for path, entry in self.entries.items():
            before, after = self.source(path, entry)
            self.assertEqual(W.restore_reviewed(entry, before, after), before)
        self.assertEqual(W.QUALIFICATION, self.value["qualification"])

    def test_schema_rejects_changed_anchors_inventory_and_classification(self):
        mutations = []
        for key in ("base", "preserved_development", "inherited_feature"):
            value = copy.deepcopy(self.value); value[key] = "0" * 40; mutations.append(value)
        for key, replacement in (("schema_version", True), ("offset_format", "characters"),
                ("qualification", "hardware qualified")):
            value = copy.deepcopy(self.value); value[key] = replacement; mutations.append(value)
        value = copy.deepcopy(self.value); value["files"].pop(0); mutations.append(value)
        value = copy.deepcopy(self.value); value["files"].append(copy.deepcopy(value["files"][0])); mutations.append(value)
        value = copy.deepcopy(self.value); value["files"].reverse(); mutations.append(value)
        value = copy.deepcopy(self.value); value["files"][0]["change"] = "added"; mutations.append(value)
        value = copy.deepcopy(self.value); value["files"][-1]["baseline_sha256"] = "0" * 64; mutations.append(value)
        value = copy.deepcopy(self.value); value["files"][0]["source_sha256"] = "not-a-hash"; mutations.append(value)
        value = copy.deepcopy(self.value); value["foreign"] = True; mutations.append(value)
        for value in mutations:
            with self.subTest(value=value.keys()), self.assertRaises(RuntimeError):
                W.validate_contract(value)

    def test_schema_rejects_duplicate_ids_missing_spans_and_invalid_offsets(self):
        mutations = []
        for key, replacement in (("before_start", -1), ("after_start", True),
                ("after_end", 0), ("reason", "")):
            value = copy.deepcopy(self.value); value["files"][0]["edits"][0][key] = replacement; mutations.append(value)
        value = copy.deepcopy(self.value); value["files"][0]["edits"] = []; mutations.append(value)
        value = copy.deepcopy(self.value)
        value["files"][1]["edits"][0]["id"] = value["files"][0]["edits"][0]["id"]
        mutations.append(value)
        value = copy.deepcopy(self.value); value["files"][0]["edits"].reverse(); mutations.append(value)
        value = copy.deepcopy(self.value); value["files"][-1]["edits"][0]["before"] = "injected"; mutations.append(value)
        for value in mutations:
            with self.assertRaises(RuntimeError):
                W.validate_contract(value)

    def test_every_span_rejects_changed_bytes_even_with_recomputed_source_hash(self):
        rejected = 0
        for path, entry in self.entries.items():
            before, after = self.source(path, entry)
            for edit in entry["edits"]:
                position = edit["after_start"]
                altered = (after[:position] + bytes([after[position] ^ 1]) + after[position + 1:]
                    if position < len(after) else after + b"x")
                with self.subTest(path=path, span=edit["id"]):
                    with self.assertRaises(RuntimeError):
                        W.restore_reviewed(entry, before, altered)
                    forged = copy.deepcopy(entry); forged["source_sha256"] = W.digest(altered)
                    with self.assertRaises(RuntimeError):
                        W.restore_reviewed(forged, before, altered)
                rejected += 1
        self.assertGreaterEqual(rejected, 40)

    def test_unchanged_gaps_and_trailing_bytes_reject_recomputed_hashes(self):
        rejected = 0
        for path, entry in self.entries.items():
            before, after = self.source(path, entry)
            positions = {len(after)}
            previous = 0
            for edit in entry["edits"]:
                if edit["after_start"] > previous:
                    positions.add(previous)
                previous = edit["after_end"]
            if previous < len(after):
                positions.add(previous)
            for position in positions:
                altered = (after[:position] + bytes([after[position] ^ 1]) + after[position + 1:]
                    if position < len(after) else after + b"x")
                forged = copy.deepcopy(entry); forged["source_sha256"] = W.digest(altered)
                with self.assertRaises(RuntimeError):
                    W.restore_reviewed(forged, before, altered)
                rejected += 1
        self.assertGreater(rejected, 4)

    def test_changed_frozen_preimage_cannot_be_rehashed_into_a_review(self):
        for path, entry in self.entries.items():
            before, after = self.source(path, entry)
            with self.assertRaises(RuntimeError):
                W.restore_reviewed(entry, before + b"forged baseline", after)

    def test_original_and_current_control_substitution_is_rejected(self):
        path = W.PATHS[1]
        entry = self.entries[path]
        before, after = self.source(path, entry)
        for old, new in ((b"enable.replay(prior, controlValues)", b"enable.replay(prior, Vector(prior))"),
                (b"case object CurrentData", b"case object CompositeInput"),
                (b"controls.foreach(_.requireFreshness())", b"controls.foreach(_ => ())")):
            self.assertIn(old, after)
            altered = after.replace(old, new, 1)
            with self.assertRaisesRegex(RuntimeError, "unreviewed source bytes"):
                W.restore_reviewed(entry, before, altered)

    def test_changed_or_paired_contract_bytes_cannot_repin_loaded_checker(self):
        with tempfile.TemporaryDirectory(prefix="59i-local-review-contract-") as temporary:
            root = Path(temporary); target = root / W.CONTRACT; target.parent.mkdir(parents=True)
            for raw in (self.raw + b"\n", self.raw.replace(W.BASE.encode(), b"0" * 40)):
                target.write_bytes(raw)
                with self.assertRaisesRegex(RuntimeError, "review contract changed"):
                    W.load_contract(root)
            value = copy.deepcopy(self.value); value["files"][0]["source_sha256"] = "0" * 64
            target.write_text(json.dumps(value))
            with self.assertRaisesRegex(RuntimeError, "review contract changed"):
                W.load_contract(root)

    def test_regular_rejects_missing_executable_and_linked_ancestors(self):
        with tempfile.TemporaryDirectory(prefix="59i-local-review-path-") as temporary:
            root = Path(temporary); (root / "source").mkdir(); target = root / "source/file"
            with self.assertRaisesRegex(RuntimeError, "missing regular source"):
                W.regular(root, "source/file")
            target.write_bytes(b"source\n"); self.assertEqual(W.regular(root, "source/file"), b"source\n")
            target.chmod(0o755)
            with self.assertRaisesRegex(RuntimeError, "non-executable"):
                W.regular(root, "source/file")
            target.chmod(0o644); (root / "linked").symlink_to(root / "source", target_is_directory=True)
            with self.assertRaisesRegex(RuntimeError, "linked source"):
                W.regular(root, "linked/file")
            target.unlink(); target.symlink_to(root / "linked/file")
            with self.assertRaisesRegex(RuntimeError, "linked source"):
                W.regular(root, "source/file")

    def test_successor_importer_authenticates_code_before_execution(self):
        with tempfile.TemporaryDirectory(prefix="59i-local-review-import-") as temporary:
            root = Path(temporary); target = root / W.SUCCESSOR_HELPER; target.parent.mkdir(parents=True)
            # If unauthenticated bytes execute, the diagnostic is AssertionError.
            target.write_text('CONTRACT_SHA256 = "' + "0" * 64 + '"\nraise AssertionError("untrusted code executed")\n')
            with self.assertRaisesRegex(RuntimeError, "unreviewed production successor verifier"):
                W.source_review(root)

    def test_original_historical_continuation_and_sync_assertions_are_unchanged(self):
        for path, class_name in (
                ("morphhdl/scripts/test-increment-59i-continuation.py", "ContinuationTests"),
                ("morphhdl/scripts/test-increment-59i-pr189-sync.py", "Sync")):
            original = W.frozen(ROOT, W.BASE, path).decode()
            current = (ROOT / path).read_text()
            def body(text):
                node = next(node for node in ast.parse(text).body
                    if isinstance(node, ast.ClassDef) and node.name == class_name)
                return ast.get_source_segment(text, node)
            self.assertEqual(body(current), body(original), path)

    def test_rollout_importer_authenticates_local_code_manifest_and_removal(self):
        path = ROOT / "morphhdl/scripts/check-increment-59i-rollout-composition.py"
        spec = importlib.util.spec_from_file_location("local_enable_rollout_import_test", path)
        self.assertIsNotNone(spec); self.assertIsNotNone(spec.loader)
        rollout = importlib.util.module_from_spec(spec)
        exec(compile(path.read_bytes(), str(path), "exec"), rollout.__dict__)
        with tempfile.TemporaryDirectory(prefix="59i-local-rollout-import-") as temporary:
            root = Path(temporary)
            W.git(root, "init", "-q")
            W.git(root, "-c", "core.hooksPath=/dev/null", "-c", "user.name=review test",
                "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-qm", "historical fixture")
            self.assertIsNone(rollout.local_enable_review(root))
            helper, contract = root / W.CHECKER, root / W.CONTRACT
            helper.parent.mkdir(parents=True); contract.parent.mkdir(parents=True)
            helper.write_bytes(SOURCE.read_bytes()); contract.write_bytes(self.raw)
            self.assertIsNotNone(rollout.local_enable_review(root))
            helper.write_bytes(SOURCE.read_bytes() + b'\nraise AssertionError("untrusted code executed")\n')
            with self.assertRaisesRegex(RuntimeError, "local-enable successor reviewer changed"):
                rollout.local_enable_review(root)
            helper.write_bytes(SOURCE.read_bytes()); contract.write_bytes(self.raw + b"\n")
            with self.assertRaisesRegex(RuntimeError, "review contract changed"):
                rollout.local_enable_review(root)
            contract.write_bytes(self.raw)
            W.git(root, "add", ".")
            W.git(root, "-c", "core.hooksPath=/dev/null", "-c", "user.name=review test",
                "-c", "user.email=test@example.invalid", "commit", "-qm", "review layer fixture")
            helper.unlink()
            with self.assertRaisesRegex(RuntimeError, "reviewer was removed"):
                rollout.local_enable_review(root)

    def test_exact_identity_rejects_worktree_index_hidden_changes_and_modes(self):
        with tempfile.TemporaryDirectory(prefix="59i-local-review-git-") as temporary:
            root = Path(temporary); path = "src/main/Source.scala"
            target = root / path; target.parent.mkdir(parents=True); target.write_bytes(b"reviewed\n")
            W.git(root, "init", "-q"); W.git(root, "add", ".")
            W.git(root, "-c", "core.hooksPath=/dev/null", "-c", "user.name=review test",
                "-c", "user.email=test@example.invalid", "commit", "-qm", "reviewed fixture")
            head = W.git(root, "rev-parse", "HEAD").decode().strip()
            W.verify_committed_identity(root, (path,))
            for mutation in ("worktree", "staged", "hidden", "mode", "deleted"):
                W.git(root, "update-index", "--no-assume-unchanged", path)
                W.git(root, "reset", "--hard", head)
                if mutation == "hidden":
                    W.git(root, "update-index", "--assume-unchanged", path)
                if mutation == "mode":
                    target.chmod(0o755)
                elif mutation == "deleted":
                    target.unlink()
                else:
                    target.write_bytes(b"unreviewed\n")
                    if mutation == "staged":
                        W.git(root, "add", path)
                with self.subTest(mutation=mutation), self.assertRaises(RuntimeError):
                    W.verify_committed_identity(root, (path,))


class LocalEnableCommittedReviewTests(unittest.TestCase):
    def test_real_committed_source_and_historical_views(self):
        entries = W.verify(ROOT)
        sentinel = "foreign/src/main/Unreviewed.scala"
        paths = {sentinel, *W.PRODUCTION_PATHS}
        self.assertEqual(W.inherited_inventory(ROOT, paths, W.BASE), paths)
        self.assertEqual(W.restore_source(ROOT, sentinel, "unreviewed source\n"), "unreviewed source\n")
        for path in entries:
            current = W.regular(ROOT, path).decode()
            baseline = W.frozen(ROOT, W.BASE, path).decode()
            inherited = W.frozen(ROOT, W.INHERITED_FEATURE, path).decode()
            self.assertEqual(W.restore_source(ROOT, path, current), baseline)
            self.assertEqual(W.restore_source(ROOT, path, baseline), baseline)
            self.assertEqual(W.restore_source(ROOT, path, inherited), inherited)
            with self.assertRaises(RuntimeError):
                W.restore_source(ROOT, path, current + "unreviewed mutation\n")


if __name__ == "__main__":
    unittest.main(verbosity=2)
