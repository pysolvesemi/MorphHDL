#!/usr/bin/env python3
"""Mutation controls for the 59i widening successor source review."""
import copy
import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "morphhdl/scripts/check-increment-59i-widening-source-review.py"
spec = importlib.util.spec_from_file_location("increment_59i_widening_review", SOURCE)
assert spec is not None and spec.loader is not None
W = importlib.util.module_from_spec(spec)
spec.loader.exec_module(W)


class WideningSourceReviewTests(unittest.TestCase):
    def test_complete_worktree_and_parent_composition(self):
        W.verify(ROOT)
        parent_source = ROOT / "morphhdl/scripts/check-increment-59i-source-review.py"
        parent_spec = importlib.util.spec_from_file_location("increment_59i_parent_review", parent_source)
        assert parent_spec is not None and parent_spec.loader is not None
        parent = importlib.util.module_from_spec(parent_spec)
        parent_spec.loader.exec_module(parent)
        parent.verify(ROOT)

    def test_every_reviewed_span_rejects_a_changed_byte(self):
        entries = W.load_contract(ROOT)
        rejected = 0
        for path, entry in entries.items():
            source = (ROOT / path).read_bytes()
            baseline = W.baseline_source(ROOT, path)
            self.assertEqual(W.restore_source(ROOT, path, source.decode()).encode(), baseline)
            for edit in entry["edits"]:
                position = edit["after_start"]
                changed = source[:position] + bytes([source[position] ^ 1]) + source[position + 1:]
                with self.assertRaises(RuntimeError):
                    W.restore_reviewed(entry, baseline, changed)
                rejected += 1
        self.assertGreaterEqual(rejected, 3)

    def test_contract_schema_rejects_missing_duplicate_and_reclassified_files(self):
        value = json.loads((ROOT / W.CONTRACT).read_text())
        mutations = []
        missing = copy.deepcopy(value); missing["files"].pop(); mutations.append(missing)
        duplicate = copy.deepcopy(value); duplicate["files"].append(copy.deepcopy(duplicate["files"][0])); mutations.append(duplicate)
        reclassified = copy.deepcopy(value); reclassified["files"][0]["change"] = "modified"; mutations.append(reclassified)
        wrong_base = copy.deepcopy(value); wrong_base["base"] = "0" * 40; mutations.append(wrong_base)
        for mutation in mutations:
            with self.assertRaises(RuntimeError):
                W.validate_contract(mutation)


if __name__ == "__main__":
    unittest.main(verbosity=2)
