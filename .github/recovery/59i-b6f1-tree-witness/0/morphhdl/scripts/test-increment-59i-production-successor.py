#!/usr/bin/env python3
"""Prove successor sealing and inherited projections in disposable Git repos.

Only miniature synthetic sources are sealed here. This test never generates a
manifest for the production checkout or authorizes its unreviewed source bytes.
"""
from __future__ import annotations

import copy
import difflib
import hashlib
import importlib.util
import json
import os
import re
import sys
import types
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HELPER = "morphhdl/scripts/check-increment-59i-production-successor.py"
TEST = "morphhdl/scripts/test-increment-59i-production-successor.py"
CONTRACT = "morphhdl/contracts/increment-59i-production-successor.json"
TARGET = "morphhdl/scripts/check-increment-59i-target-integration.py"
CACHE_SOURCE_CASES = (
    TARGET,
    TEST,
    "morphhdl/scripts/test-increment-62-wa08-source-overlay.py",
    "morphhdl/scripts/check-native-source-preservation.py",
)


def load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load synthetic review fixture")
    module = importlib.util.module_from_spec(spec)
    exec(compile(path.read_bytes(), str(path), "exec"), module.__dict__)
    return module


def replace_constant(raw: bytes, name: str, value: str) -> bytes:
    raw, count = re.subn((r'^' + name + r' = "[^"\n]+"$').encode(),
        (name + ' = "' + value + '"').encode(), raw, flags=re.M)
    if count != 1:
        raise RuntimeError("fixture constant is not unique: " + name)
    return raw


def spans(path: str, before: bytes, after: bytes) -> list[dict]:
    a, b = before.decode(), after.decode()
    edits = []
    for kind, i, j, k, l in difflib.SequenceMatcher(a=a, b=b, autojunk=False).get_opcodes():
        if kind != "equal":
            edits.append({"id": path + ":" + str(len(edits)),
                "reason": "Synthetic fixture exact " + kind + " span",
                "before_start": len(a[:i].encode()), "before_end": len(a[:j].encode()),
                "after_start": len(b[:k].encode()), "after_end": len(b[:l].encode()),
                "before": a[i:j], "after": b[k:l]})
    return edits


class ProductionSuccessorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.legacy_module = load(ROOT / "morphhdl/scripts/test-increment-59i-target-integration.py",
            "successor_legacy_fixture")
        cls.submodule_path = "core/src/test/sublib"
        original_commit = cls.legacy_module.commit

        def commit_with_submodule(root, message):
            if message == "common base":
                child = root / cls.submodule_path
                child.mkdir(parents=True)
                cls.legacy_module.git(child, "init", "-q")
                cls.legacy_module.write(child, "model.py", b"VALUE = 1\n")
                cls.legacy_module.write(child, ".gitignore", b"*.ignored\n__pycache__/\n")
                cls.submodule_commit = original_commit(child, "immutable child source")
            return original_commit(root, message)

        cls.legacy_module.commit = commit_with_submodule
        cls.legacy = cls.legacy_module.IntegrationTests
        cls.legacy.setUpClass()
        cls.root = cls.legacy.root
        cls.git = staticmethod(cls.legacy_module.git)
        cls.write = staticmethod(cls.legacy_module.write)
        cls.commit = staticmethod(cls.legacy_module.commit)
        cls.predecessor = cls.legacy.head
        cls.added = "core/src/main/Successor.scala"
        cls.removed = cls.legacy.target_only
        raw = replace_constant((ROOT / HELPER).read_bytes(), "BASE", cls.predecessor)
        raw = replace_constant(raw, "CONTRACT_SHA256", "UNSEALED")
        cls.write(cls.root, HELPER, raw)
        cls.write(cls.root, TEST, (ROOT / TEST).read_bytes())
        unsealed = load(cls.root / HELPER, "synthetic_unsealed_successor")
        target = replace_constant((cls.root / TARGET).read_bytes(), "SUCCESSOR_HELPER_SHA256",
            unsealed.digest(unsealed.normalized_helper(raw)))
        cls.write(cls.root, TARGET, target)
        # Compile the actual two sources which exposed marshal representation
        # differences, but keep every cache and mutation inside this fixture.
        for path in CACHE_SOURCE_CASES[2:]:
            cls.write(cls.root, path, (ROOT / path).read_bytes())
        cls.write(cls.root, cls.legacy.shared, "reviewed successor shared π\nsecond line\n".encode())
        cls.write(cls.root, cls.added, b"successor added source\n")
        cls.write(cls.root, cls.legacy.doc, b"reviewed successor documentation\n")
        cls.write(cls.root, unsealed.COMPLETION_TODO,
            b"Synthetic roadmap\n" + unsealed.COMPLETION_ANCHOR + b"Unchanged next increment.\n")
        (cls.root / cls.removed).unlink()
        (cls.root / cls.legacy.unrelated).chmod(0o755)
        cls.source = cls.commit(cls.root, "immutable synthetic reviewed successor source")
        before_tree, after_tree = unsealed.tree(cls.root, cls.predecessor), unsealed.tree(cls.root, cls.source)
        records = []
        for path in sorted(unsealed.changed(cls.root, cls.predecessor, cls.source)):
            before, after = unsealed.frozen(cls.root, cls.predecessor, path), unsealed.frozen(cls.root, cls.source, path)
            records.append({"path": path, "before_mode": before_tree.get(path, (None,))[0],
                "after_mode": after_tree.get(path, (None,))[0],
                "before_sha256": None if before is None else unsealed.digest(before),
                "after_sha256": None if after is None else unsealed.digest(after),
                "reason": "Reviewed synthetic fixture source delta",
                "edits": spans(path, before or b"", after or b"")})
        cls.manifest = {"schema_version": 1, "predecessor": cls.predecessor,
            "predecessor_tree": cls.git(cls.root, "rev-parse", cls.predecessor + "^{tree}").decode().strip(),
            "source_commit": cls.source,
            "source_tree": cls.git(cls.root, "rev-parse", cls.source + "^{tree}").decode().strip(),
            "helper_normalized_sha256": unsealed.digest(unsealed.normalized_helper(raw)), "files": records}
        cls.manifest_raw = (json.dumps(cls.manifest, indent=2, sort_keys=True) + "\n").encode()
        cls.write(cls.root, CONTRACT, cls.manifest_raw)
        cls.write(cls.root, HELPER, replace_constant(raw, "CONTRACT_SHA256", unsealed.digest(cls.manifest_raw)))
        cls.head = cls.commit(cls.root, "seal synthetic immutable successor")
        cls.review = load(cls.root / HELPER, "synthetic_sealed_successor")
        cls.target = load(cls.root / TARGET, "synthetic_successor_target_adapter")
        cls.review.verify(cls.root)
        cls.target.verify(cls.root)

    @classmethod
    def tearDownClass(cls):
        cls.legacy.tearDownClass()

    def setUp(self):
        self.restore()

    def tearDown(self):
        self.restore()

    def restore(self):
        for current, dirs, files in os.walk(self.root, followlinks=False):
            dirs[:] = [name for name in dirs if name != ".git"]
            for name in dirs + files:
                file = Path(current) / name
                if file.is_symlink():
                    file.unlink()
        self.git(self.root, "update-index", "--no-assume-unchanged", self.legacy.shared)
        self.git(self.root, "reset", "--hard", self.head)
        self.git(self.root, "clean", "-fdx")
        child = self.root / self.submodule_path
        self.git(child, "update-index", "--no-assume-unchanged", "model.py")
        self.git(child, "reset", "--hard", self.submodule_commit)
        self.git(child, "clean", "-fdx")

    def reject(self, action=None, detail="59i (?:production successor|target integration)"):
        with self.assertRaisesRegex(RuntimeError, detail):
            (action or (lambda: self.target.verify(self.root)))()

    def append(self, path):
        file = self.root / path
        file.write_bytes(file.read_bytes() + b"\nunreviewed mutation\n")

    def test_exact_live_source_and_historical_predecessor_both_verify(self):
        self.review.verify(self.root)
        self.target.verify(self.root)
        self.assertEqual(self.git(self.root, "show", self.predecessor + ":" + self.target.CONTRACT),
            (self.root / self.target.CONTRACT).read_bytes())
        self.assertEqual(self.git(self.root, "diff", "--name-only", self.source, self.head).decode().splitlines(),
            sorted([CONTRACT, HELPER]))

    def test_cached_structure_never_shares_mutable_authority(self):
        first = self.review.contract(self.root)
        first["files"][0]["path"] = "foreign/src/main/Forged.scala"
        first["source_commit"] = self.predecessor
        self.assertEqual(self.review.contract(self.root), self.manifest)
        self.target.verify(self.root)
        self.append(CONTRACT)
        self.reject()

    def test_all_source_spans_reverse_and_reject_changed_bytes(self):
        for entry in self.manifest["files"]:
            path = entry["path"]
            before = self.review.frozen(self.root, self.predecessor, path) or b""
            after = self.review.frozen(self.root, self.source, path) or b""
            self.assertEqual(self.review.restore_reviewed(entry, before, after), before)
            current = (self.root / path).read_bytes() if entry["after_mode"] else b""
            self.assertEqual(self.review.restore_source(self.root, path, current), before)
            self.assertEqual(self.review.restore_source(self.root, path, before), before)
            self.reject(lambda: self.review.restore_source(self.root, path, current + b"mutation"))
            for edit in entry["edits"]:
                a, b = edit["after_start"], edit["after_end"]
                altered = after[:a] + (bytes([after[a] ^ 1]) + after[a + 1:] if a < b else b"x" + after[a:])
                self.reject(lambda: self.review.restore_reviewed(entry, before, altered))

    def test_exact_both_parent_views_including_added_and_deleted_paths(self):
        paths = {entry["path"] for entry in self.manifest["files"]} | {
            entry["path"] for entry in self.target.contract(self.root)["files"]} | {CONTRACT}
        for path in paths:
            current = (self.root / path).read_bytes() if (self.root / path).is_file() else b""
            for parent, project in ((self.legacy.feature, self.target.feature_source),
                    (self.legacy.target, self.target.target_source)):
                expected = self.target.frozen(self.root, parent, path) or b""
                self.assertEqual(project(self.root, path, current), expected, path)
                self.assertEqual(project(self.root, path, expected), expected, path)
                self.reject(lambda: project(self.root, path, current + b"mutation"))

    def test_complete_inventory_overlap_cancellation_and_unknown_sentinels(self):
        for parent, project in ((self.legacy.feature, self.target.feature_inventory),
                (self.legacy.target, self.target.target_inventory)):
            for base in (self.legacy.base, self.legacy.feature, self.legacy.target,
                    self.legacy.source, self.predecessor, self.source, "HEAD"):
                for full in (False, True):
                    with self.subTest(parent=parent, base=base, full=full):
                        domain = lambda paths: paths if full else {p for p in paths if "/src/main/" in p}
                        current = domain(self.review.changed(self.root, base, "HEAD"))
                        expected = domain(self.review.changed(self.root, base, parent))
                        sentinel = "foreign/src/main/Unreviewed.scala"
                        self.assertEqual(project(self.root, current, base, full), expected)
                        self.assertEqual(project(self.root, expected, base, full), expected)
                        self.assertEqual(project(self.root, current | {sentinel}, base, full), expected | {sentinel})

    def test_real_upstream_loaders_authenticate_successor_adapter(self):
        actual_target = (ROOT / TARGET).read_bytes()
        actual_hash = self.target.digest(self.target.normalized_helper(actual_target)).encode()
        fixture_hash = self.target.digest(self.target.normalized_helper((self.root / TARGET).read_bytes())).encode()
        for name in ("check-increment-59i-rollout-composition.py", "check-increment-62-wa08-source-overlay.py"):
            with self.subTest(loader=name):
                self.restore()
                raw = (ROOT / "morphhdl/scripts" / name).read_bytes()
                self.assertEqual(raw.count(actual_hash), 1)
                # Substitute only the exact code hash for this synthetic
                # target helper, whose immutable parent ids are also synthetic.
                module = types.ModuleType("synthetic_upstream_loader")
                exec(compile(raw.replace(actual_hash, fixture_hash), name, "exec"), module.__dict__)
                module.integration_review(self.root).verify(self.root)
                self.append(TARGET)
                self.reject(lambda: module.integration_review(self.root), "target integration reviewer changed")
                self.restore()
                self.append(HELPER)
                self.reject(lambda: module.integration_review(self.root), "production successor reviewer changed")

    def test_exact_completion_transition_preserves_source_and_parent_proofs(self):
        todo = self.root / self.review.COMPLETION_TODO
        original = todo.read_bytes()
        todo.write_bytes(original.replace(self.review.COMPLETION_ANCHOR,
            self.review.COMPLETION_ANCHOR.replace(b"[ ]", b"[x]", 1), 1))
        self.write(self.root, self.review.COMPLETION_RECORD, b"Evidence only; no authority for source changes.\n")
        self.commit(self.root, "record qualified synthetic completion")
        self.review.verify(self.root)
        self.target.verify(self.root)
        self.assertEqual(self.review.frozen(self.root, self.source, self.review.COMPLETION_TODO), original)
        for parent, project in ((self.legacy.feature, self.target.feature_inventory),
                (self.legacy.target, self.target.target_inventory)):
            current = self.review.changed(self.root, self.predecessor, "HEAD")
            expected = self.review.changed(self.root, self.predecessor, parent)
            self.assertEqual(project(self.root, current, self.predecessor, full=True), expected)
        self.assertEqual(self.target.feature_source(self.root, self.review.COMPLETION_RECORD,
            (self.root / self.review.COMPLETION_RECORD).read_bytes()), b"")

    def history_commit(self, treeish, parents, message):
        """Create a history probe without modifying the real project repository."""
        tree = self.git(self.root, "rev-parse", treeish + "^{tree}").decode().strip()
        args = ["commit-tree", tree]
        for parent in parents:
            args.extend(("-p", parent))
        return self.git(self.root, *args, data=(message + "\n").encode()).decode().strip()

    def staged_tree(self):
        self.git(self.root, "add", "-A")
        return self.git(self.root, "write-tree").decode().strip()

    def assert_merged_parent_projections(self):
        self.review.verify(self.root)
        self.target.verify(self.root)
        current = self.review.changed(self.root, self.predecessor, "HEAD")
        paths = (self.legacy.shared, self.added, self.removed,
            self.review.COMPLETION_TODO, self.review.COMPLETION_RECORD)
        for parent, inventory, project in (
                (self.legacy.feature, self.target.feature_inventory, self.target.feature_source),
                (self.legacy.target, self.target.target_inventory, self.target.target_source)):
            with self.subTest(projected_parent=parent):
                expected = self.review.changed(self.root, self.predecessor, parent)
                self.assertEqual(inventory(self.root, current, self.predecessor, full=True), expected)
                for path in paths:
                    live = self.root / path
                    raw = live.read_bytes() if live.is_file() else b""
                    self.assertEqual(project(self.root, path, raw),
                        self.review.frozen(self.root, parent, path) or b"", path)

    def complete_history_tree(self):
        todo = self.root / self.review.COMPLETION_TODO
        original = todo.read_bytes()
        self.assertEqual(original.count(self.review.COMPLETION_ANCHOR), 1)
        todo.write_bytes(original.replace(self.review.COMPLETION_ANCHOR,
            self.review.COMPLETION_ANCHOR.replace(b"[ ]", b"[x]", 1), 1))
        self.write(self.root, self.review.COMPLETION_RECORD, b"Synthetic qualification evidence only.\n")
        return self.staged_tree()

    def test_normal_target_integration_merge_preserves_exact_feature_and_parent_proofs(self):
        merged = self.history_commit(self.head, [self.legacy.target, self.head],
            "ordinary target-first integration of the sealed feature")
        self.git(self.root, "reset", "--hard", merged)
        self.assertEqual(self.review.tree(self.root, merged), self.review.tree(self.root, self.head))
        self.assertNotIn(self.source,
            self.git(self.root, "rev-list", "--first-parent", merged).decode().splitlines())
        self.assert_merged_parent_projections()

    def test_completion_before_or_after_integration_preserves_both_parent_projections(self):
        for timing in ("before", "after"):
            with self.subTest(completion=timing):
                self.restore()
                if timing == "before":
                    feature = self.history_commit(self.complete_history_tree(), [self.head],
                        "complete the sealed feature before integration")
                    merged = self.history_commit(feature, [self.legacy.target, feature],
                        "integrate the completed feature without tree changes")
                    self.git(self.root, "reset", "--hard", merged)
                else:
                    merged = self.history_commit(self.head, [self.legacy.target, self.head],
                        "integrate the sealed feature before completion")
                    self.git(self.root, "reset", "--hard", merged)
                    completed = self.history_commit(self.complete_history_tree(), [merged],
                        "record completion after target integration")
                    self.git(self.root, "reset", "--hard", completed)
                self.assert_merged_parent_projections()

    def test_integration_merge_rejects_source_contract_workflow_and_mode_changes(self):
        for kind in ("source", "contract", "workflow", "mode"):
            with self.subTest(merge_change=kind):
                self.restore()
                if kind == "source":
                    self.append(self.legacy.shared)
                elif kind == "contract":
                    self.append(CONTRACT)
                elif kind == "workflow":
                    self.write(self.root, ".github/workflows/unreviewed-integration.yml", b"name: unreviewed\n")
                else:
                    (self.root / self.legacy.shared).chmod(0o755)
                merged = self.history_commit(self.staged_tree(), [self.legacy.target, self.head],
                    "unreviewed integration tree change: " + kind)
                self.git(self.root, "reset", "--hard", merged)
                self.assertNotEqual(self.review.tree(self.root, merged), self.review.tree(self.root, self.head))
                self.reject(lambda: self.review.verify(self.root))
                self.reject()

    def test_integration_cannot_repair_an_unreviewed_feature_tip(self):
        self.append(self.legacy.shared)
        invalid = self.history_commit(self.staged_tree(), [self.head], "unreviewed feature tip")
        merged = self.history_commit(self.head, [self.legacy.target, invalid],
            "hide invalid feature bytes by repairing the merge tree")
        self.git(self.root, "reset", "--hard", merged)
        self.assertEqual(self.review.tree(self.root, merged), self.review.tree(self.root, self.head))
        self.assertNotEqual(self.review.tree(self.root, invalid), self.review.tree(self.root, self.head))
        self.reject(lambda: self.review.verify(self.root))
        self.reject()

    def test_integration_rejects_unreviewed_parent_topologies_even_with_exact_sealed_tree(self):
        sibling = self.history_commit(self.predecessor, [self.predecessor], "sibling outside reviewed source")
        unrelated = self.history_commit(self.predecessor, [], "unrelated root with coincidentally equal bytes")
        advanced = self.history_commit(self.legacy.target, [self.legacy.target], "unreviewed target advancement")
        integrated = self.history_commit(self.head, [self.legacy.target, self.head], "first permitted integration")
        parents = {
            "sibling target": [sibling, self.head],
            "unrelated target": [unrelated, self.head],
            "advanced target": [advanced, self.head],
            "source as target": [self.source, self.head],
            "reversed parents": [self.head, self.legacy.target],
            "octopus": [self.legacy.target, self.head, self.legacy.feature],
            "second integration": [self.legacy.target, integrated],
            "unsealed feature": [self.legacy.target, self.source],
        }
        for name, ancestry in parents.items():
            with self.subTest(topology=name):
                self.restore()
                invalid = self.history_commit(self.head, ancestry, name)
                self.git(self.root, "reset", "--hard", invalid)
                self.assertEqual(self.review.tree(self.root, invalid), self.review.tree(self.root, self.head))
                self.reject(lambda: self.review.verify(self.root))
                self.reject()

    def test_restored_production_tree_cannot_hide_drift_in_linear_or_merged_history(self):
        for integration in (False, True):
            with self.subTest(integration=integration):
                self.restore()
                self.append(self.legacy.shared)
                invalid = self.history_commit(self.staged_tree(), [self.head], "temporary unreviewed source")
                restored = self.history_commit(self.head, [invalid], "restore exact sealed source after drift")
                tip = self.history_commit(restored, [self.legacy.target, restored],
                    "integrate feature with hidden historical source drift") if integration else restored
                self.git(self.root, "reset", "--hard", tip)
                self.assertEqual(self.review.tree(self.root, tip), self.review.tree(self.root, self.head))
                self.assertNotEqual(self.review.tree(self.root, invalid), self.review.tree(self.root, self.head))
                self.reject(lambda: self.review.verify(self.root))
                self.reject()

    def test_completion_does_not_authorize_other_docs_proof_or_workflow_changes(self):
        for path in (self.review.COMPLETION_TODO, self.legacy.doc, TARGET,
                ".github/workflows/unreviewed-completion.yml"):
            with self.subTest(path=path):
                self.restore()
                file = self.root / path
                if file.exists(): self.append(path)
                else: self.write(self.root, path, b"unreviewed workflow\n")
                self.commit(self.root, "unreviewed completion change")
                self.reject()
        for path in (self.review.COMPLETION_TODO, self.review.COMPLETION_RECORD):
            for kind in ("mode", "symlink"):
                with self.subTest(path=path, kind=kind):
                    self.restore()
                    file = self.root / path
                    if not file.exists(): self.write(self.root, path, b"record\n")
                    if kind == "mode": file.chmod(0o755)
                    else:
                        file.unlink()
                        file.symlink_to("/dev/null")
                    self.commit(self.root, "invalid completion mode")
                    self.reject()

    def forge_successor_seal(self, source):
        review = load(self.root / HELPER, "synthetic_later_unsealed_reviewer")
        value = copy.deepcopy(self.manifest)
        value["source_commit"] = source
        value["source_tree"] = self.git(self.root, "rev-parse", source + "^{tree}").decode().strip()
        before_tree, after_tree = review.tree(self.root, self.predecessor), review.tree(self.root, source)
        value["files"] = []
        for path in sorted(review.changed(self.root, self.predecessor, source)):
            before, after = review.frozen(self.root, self.predecessor, path), review.frozen(self.root, source, path)
            value["files"].append({"path": path,
                "before_mode": before_tree.get(path, (None,))[0], "after_mode": after_tree.get(path, (None,))[0],
                "before_sha256": None if before is None else review.digest(before),
                "after_sha256": None if after is None else review.digest(after),
                "reason": "Forged attempt to authorize later source", "edits": spans(path, before or b"", after or b"")})
        raw = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()
        self.write(self.root, CONTRACT, raw)
        self.write(self.root, HELPER, replace_constant((self.root / HELPER).read_bytes(),
            "CONTRACT_SHA256", review.digest(raw)))
        return self.commit(self.root, "forged later seal")

    def test_linear_reseal_cannot_authorize_later_production(self):
        self.append(self.legacy.shared)
        (self.root / CONTRACT).unlink()
        self.write(self.root, HELPER, replace_constant((self.root / HELPER).read_bytes(), "CONTRACT_SHA256", "UNSEALED"))
        source = self.commit(self.root, "unreviewed later source after a genuine seal")
        self.forge_successor_seal(source)
        review = load(self.root / HELPER, "synthetic_resealed_reviewer")
        self.reject(lambda: review.verify(self.root))
        self.reject()

    def test_sibling_merge_reseal_cannot_replace_earlier_source_authority(self):
        self.git(self.root, "reset", "--hard", self.predecessor)
        self.git(self.root, "read-tree", "--reset", "-u", self.source)
        self.append(self.legacy.shared)
        source = self.commit(self.root, "forged sibling source directly after predecessor")
        seal = self.forge_successor_seal(source)
        merged_tree = self.git(self.root, "rev-parse", seal + "^{tree}").decode().strip()
        merged = self.git(self.root, "commit-tree", merged_tree, "-p", seal, "-p", self.head,
            data=b"merge old published seal without changing forged source tree\n").decode().strip()
        self.git(self.root, "reset", "--hard", merged)
        self.git(self.root, "merge-base", "--is-ancestor", self.head, "HEAD")
        review = load(self.root / HELPER, "synthetic_sibling_resealed_reviewer")
        self.reject(lambda: review.verify(self.root),
            "integration target is not an ancestor of the immutable predecessor")
        self.reject()

    def test_initialized_submodule_hidden_bytes_index_and_ignored_sources_reject(self):
        child = self.root / self.submodule_path
        self.review.verify(self.root)
        original = (child / "model.py").read_bytes()
        self.git(child, "update-index", "--assume-unchanged", "model.py")
        (child / "model.py").write_bytes(original + b"UNREVIEWED = True\n")
        self.assertEqual(self.git(child, "status", "--porcelain"), b"")
        self.reject(lambda: self.review.verify(self.root), "HEAD/index/worktree identity differs")
        self.reject()
        self.restore()
        (child / "model.py").write_bytes(original + b"UNREVIEWED = True\n")
        self.git(child, "add", "model.py")
        (child / "model.py").write_bytes(original)
        self.reject(lambda: self.review.verify(self.root), "HEAD/index identity differs")
        self.restore()
        (child / "hidden.ignored").write_bytes(b"unreviewed hidden child source\n")
        self.assertEqual(self.git(child, "status", "--porcelain"), b"")
        self.reject(lambda: self.review.verify(self.root), "untracked or ignored source addition")
        self.reject()

    def test_live_mutations_reject_even_after_previous_success(self):
        self.target.verify(self.root)
        for path in (self.legacy.shared, self.legacy.unrelated, self.legacy.doc, self.added,
                TARGET, HELPER, TEST, CONTRACT, self.target.CONTRACT):
            with self.subTest(path=path):
                self.restore()
                self.append(path)
                self.reject()
                self.commit(self.root, "committed unreviewed mutation")
                self.reject()

    def test_missing_added_removed_and_unknown_sources_reject(self):
        for path in (self.added, HELPER, CONTRACT):
            with self.subTest(missing=path):
                self.restore()
                (self.root / path).unlink()
                self.reject()
        for path in (self.removed, "core/src/main/Unknown.scala", "docs/unknown.md"):
            with self.subTest(added=path):
                self.restore()
                self.write(self.root, path, b"unreviewed\n")
                self.reject()
                self.commit(self.root, "unexpected committed addition")
                self.reject()

    def test_hidden_index_and_assume_unchanged_reject(self):
        old = (self.root / self.legacy.shared).read_bytes()
        self.append(self.legacy.shared)
        self.git(self.root, "add", self.legacy.shared)
        self.write(self.root, self.legacy.shared, old)
        self.reject()
        self.restore()
        self.git(self.root, "update-index", "--assume-unchanged", self.legacy.shared)
        self.append(self.legacy.shared)
        self.reject()

    def test_modes_symlinks_and_ignored_source_reject(self):
        for kind in ("mode", "symlink", "parent-link", "ignored"):
            with self.subTest(kind=kind):
                self.restore()
                file = self.root / self.legacy.shared
                if kind == "mode":
                    file.chmod(0o755)
                elif kind == "symlink":
                    file.unlink()
                    file.symlink_to("/dev/null")
                elif kind == "parent-link":
                    file.parent.rename(file.parent.with_name("saved"))
                    file.parent.symlink_to("saved", target_is_directory=True)
                else:
                    self.write(self.root, "core/src/main/Unknown.ignored", b"hidden source\n")
                    self.assertEqual(self.git(self.root, "status", "--porcelain"), b"")
                self.reject()

    def test_schema_spans_and_source_identities_reject_even_with_rehashed_manifest(self):
        cases = []
        for key, value in (("schema_version", 2), ("predecessor", "0" * 40),
                ("predecessor_tree", "0" * 40), ("source_commit", self.predecessor),
                ("source_tree", "0" * 40), ("helper_normalized_sha256", "0" * 64)):
            changed = copy.deepcopy(self.manifest)
            changed[key] = value
            cases.append((key, changed))
        for kind in ("missing", "duplicate", "replace", "reorder", "before", "after", "span", "mode"):
            changed = copy.deepcopy(self.manifest)
            entries = changed["files"]
            if kind == "missing": entries.pop()
            elif kind == "duplicate": entries.append(copy.deepcopy(entries[0]))
            elif kind == "replace": entries[0]["path"] = "foreign/src/main/Unknown.scala"
            elif kind == "reorder": entries.reverse()
            elif kind == "before": next(e for e in entries if e["before_sha256"])["before_sha256"] = "0" * 64
            elif kind == "after": next(e for e in entries if e["after_sha256"])["after_sha256"] = "0" * 64
            elif kind == "span": next(e for e in entries if e["edits"])["edits"].pop()
            elif kind == "mode": next(e for e in entries if e["after_mode"])["after_mode"] = "100755"
            cases.append((kind, changed))
        for label, value in cases:
            with self.subTest(label=label):
                self.restore()
                raw = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()
                self.write(self.root, CONTRACT, raw)
                self.write(self.root, HELPER, replace_constant((self.root / HELPER).read_bytes(),
                    "CONTRACT_SHA256", hashlib.sha256(raw).hexdigest()))
                self.commit(self.root, "commit forged synthetic review seal")
                changed_review = load(self.root / HELPER, "synthetic_forged_review")
                self.reject(lambda: changed_review.verify(self.root))

    def make_fixture_cache(self, source, optimization, invalidation):
        import py_compile

        # An explicit cfile keeps this attack inside the fixture even if an
        # unrelated caller has configured a global interpreter cache prefix.
        suffix = "" if optimization == 0 else ".opt-" + str(optimization)
        cache = source.parent / "__pycache__" / (
            source.stem + "." + sys.implementation.cache_tag + suffix + ".pyc")
        self.assertTrue(cache.is_relative_to(self.root))
        py_compile.compile(str(source), cfile=str(cache), doraise=True,
            optimize=optimization, invalidation_mode=invalidation)
        self.assertTrue(cache.is_file())
        return cache

    def reject_fixture_cache(self, cache):
        path = cache.relative_to(self.root).as_posix()
        self.assertEqual(self.git(self.root, "status", "--porcelain"), b"")
        detail = "59i production successor: untracked or ignored source addition: " + path
        self.reject(lambda: self.review.verify(self.root), re.escape(detail))
        self.reject(detail=re.escape(detail))

    def test_real_py_compile_caches_are_rejected_for_every_optimization_and_header(self):
        import py_compile

        exclude = self.root / ".git/info/exclude"
        original_exclude = exclude.read_bytes()
        exclude.write_bytes(original_exclude + b"\n*.pyc\n")
        try:
            self.review.verify(self.root)
            self.target.verify(self.root)
            for path in CACHE_SOURCE_CASES:
                source = self.root / path
                original_source = source.read_bytes()
                for optimization in (0, 1, 2):
                    for invalidation in (py_compile.PycInvalidationMode.TIMESTAMP,
                            py_compile.PycInvalidationMode.CHECKED_HASH,
                            py_compile.PycInvalidationMode.UNCHECKED_HASH):
                        with self.subTest(source=path, optimization=optimization, header=invalidation.name):
                            cache = self.make_fixture_cache(source, optimization, invalidation)
                            self.assertEqual(source.read_bytes(), original_source)
                            self.reject_fixture_cache(cache)
                            cache.unlink()
                            self.review.verify(self.root)
                            self.target.verify(self.root)
        finally:
            exclude.write_bytes(original_exclude)

    def test_forged_cache_body_metadata_filename_headers_and_trailing_bytes_reject(self):
        import marshal
        import py_compile

        source = self.root / TARGET
        original_source = source.read_bytes()
        exclude = self.root / ".git/info/exclude"
        original_exclude = exclude.read_bytes()
        exclude.write_bytes(original_exclude + b"\n*.pyc\n")
        try:
            for optimization in (0, 1, 2):
                with self.subTest(optimization=optimization):
                    cache = self.make_fixture_cache(source, optimization,
                        py_compile.PycInvalidationMode.TIMESTAMP)
                    legitimate = cache.read_bytes()
                    self.reject_fixture_cache(cache)
                    compiled = compile(original_source, str(source), "exec",
                        dont_inherit=True, optimize=optimization)

                    # Keep the real interpreter magic, timestamp and source
                    # size. Even with -B, a normal source loader reads an
                    # existing cache and executes this unrelated code body.
                    forged = compile("UNREVIEWED_EXECUTABLE_CACHE = True\n",
                        str(source), "exec", dont_inherit=True, optimize=optimization)
                    cache.write_bytes(legitimate[:16] + marshal.dumps(forged))
                    self.assertEqual(source.read_bytes(), original_source)
                    self.assertEqual(self.git(self.root, "status", "--porcelain"), b"")
                    if optimization == 0:
                        spec = importlib.util.spec_from_file_location("forged_timestamp_cache", source)
                        loaded = importlib.util.module_from_spec(spec)
                        spec.loader.exec_module(loaded)
                        self.assertTrue(loaded.UNREVIEWED_EXECUTABLE_CACHE)
                    variants = {
                        "executable body": legitimate[:16] + marshal.dumps(forged),
                        "line metadata": legitimate[:16] + marshal.dumps(
                            compiled.replace(co_firstlineno=compiled.co_firstlineno + 1)),
                        "filename": legitimate[:16] + marshal.dumps(
                            compiled.replace(co_filename=str(source.parent / "Unreviewed.py"))),
                        "magic": b"\0\0\0\0" + legitimate[4:],
                        "unsupported flags": legitimate[:4] + (4).to_bytes(4, "little") + legitimate[8:],
                        "truncated header": legitimate[:12],
                        "trailing bytes": legitimate + b"unreviewed trailing bytes",
                    }
                    nested = next(value for value in compiled.co_consts if isinstance(value, types.CodeType))
                    changed_nested = nested.replace(co_name=nested.co_name + "_unreviewed")
                    variants["nested code metadata"] = legitimate[:16] + marshal.dumps(compiled.replace(
                        co_consts=tuple(changed_nested if value is nested else value for value in compiled.co_consts)))
                    for kind, raw in variants.items():
                        with self.subTest(cache_mutation=kind):
                            cache.write_bytes(raw)
                            self.assertEqual(source.read_bytes(), original_source)
                            self.reject_fixture_cache(cache)
                    cache.unlink()
                    self.review.verify(self.root)
                    self.target.verify(self.root)
        finally:
            exclude.write_bytes(original_exclude)

    def test_cache_rejection_covers_initialized_gitlinks_but_preserves_build_output_scope(self):
        import py_compile

        cache = self.make_fixture_cache(self.root / self.submodule_path / "model.py", 0,
            py_compile.PycInvalidationMode.TIMESTAMP)
        self.reject_fixture_cache(cache)
        cache.unlink()
        self.review.verify(self.root)
        self.target.verify(self.root)

        # The successor still scopes ignored-content checks to production,
        # audit and workflow roots; it does not ban opaque build artifacts.
        exclude = self.root / ".git/info/exclude"
        original_exclude = exclude.read_bytes()
        exclude.write_bytes(original_exclude + b"\n*.pyc\n")
        outside = self.root / "target" / "build-cache.pyc"
        try:
            self.write(self.root, "target/build-cache.pyc", b"ignored build artifact, never imported\n")
            self.assertEqual(self.git(self.root, "status", "--porcelain"), b"")
            self.review.verify(self.root)
            self.target.verify(self.root)
        finally:
            outside.unlink(missing_ok=True)
            exclude.write_bytes(original_exclude)

    def test_unsealed_source_commit_cannot_qualify(self):
        self.git(self.root, "reset", "--hard", self.source)
        unsealed = load(self.root / HELPER, "synthetic_unsealed_rejection")
        self.reject(lambda: unsealed.verify(self.root), "has not been sealed")


if __name__ == "__main__":
    unittest.main()
