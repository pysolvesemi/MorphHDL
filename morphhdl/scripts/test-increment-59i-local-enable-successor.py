#!/usr/bin/env python3
"""Exercise schema-4 history and sealing only in disposable synthetic Git repos.

The positive fixture replays a real schema-1 predecessor audit in an isolated
checkout. No audit function is mocked and no production manifest is generated.
"""
from __future__ import annotations

import copy
import json
import re
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
HELPER = "morphhdl/scripts/check-increment-59i-production-successor.py"
TEST = "morphhdl/scripts/test-increment-59i-production-successor.py"
CONTRACT = "morphhdl/contracts/increment-59i-production-successor.json"
CERT61 = "morphhdl/contracts/increment-61-source-review.json"
CHECK61 = "morphhdl/scripts/check-increment-61-source-review.py"


def load(raw: bytes, filename: Path):
    module = types.ModuleType("synthetic_local_enable_successor")
    module.__file__ = str(filename)
    exec(compile(raw, str(filename), "exec"), module.__dict__)
    return module


FIXTURE = load((ROOT / "morphhdl/scripts/test-increment-59i-target-integration.py").read_bytes(),
    ROOT / "morphhdl/scripts/test-increment-59i-target-integration.py")
git, write, commit = FIXTURE.git, FIXTURE.write, FIXTURE.commit


def constant(raw: bytes, name: str, value: str) -> bytes:
    result, count = re.subn((r'^' + name + r' = "[^"\n]+"$').encode(),
        (name + ' = "' + value + '"').encode(), raw, flags=re.M)
    if count != 1:
        raise RuntimeError("nonunique synthetic anchor: " + name)
    return result


def encoded(value) -> bytes:
    return (json.dumps(value, sort_keys=True, indent=2) + "\n").encode()


class LocalEnableSuccessorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="59i-local-enable-successor-")
        cls.root = Path(cls.temporary.name)
        git(cls.root, "init", "-q")
        git(cls.root, "config", "core.filemode", "true")
        cls.raw = (ROOT / HELPER).read_bytes()
        helper = load(cls.raw, cls.root / HELPER)
        cls.model = "core/src/main/LocalEnable.scala"
        write(cls.root, cls.model, b"initial synthetic source\n")
        write(cls.root, helper.COMPLETION_TODO, b"Roadmap\n" + helper.COMPLETION_ANCHOR)
        cls.base = commit(cls.root, "synthetic immutable base")

        # A separately pinned target carries a tiny, independently executable
        # certificate. Its exact checker bytes are authenticated and executed.
        certificate = encoded({"integrated_target_commit": cls.base})
        checker = ("import json\nfrom pathlib import Path\n"
            f"assert json.loads(Path({CERT61!r}).read_bytes()) == "
            f"{{'integrated_target_commit': {cls.base!r}}}\n").encode()
        write(cls.root, CERT61, certificate)
        write(cls.root, CHECK61, checker)
        cls.target = commit(cls.root, "synthetic target certificate")
        git(cls.root, "reset", "--hard", cls.base)
        write(cls.root, CERT61, certificate)
        write(cls.root, CHECK61, checker)
        cls.raw = constant(cls.raw, "BASE", cls.base)
        cls.raw = constant(cls.raw, "CONTRACT_SHA256", "UNSEALED")
        write(cls.root, HELPER, cls.raw)
        write(cls.root, TEST, b"# Synthetic predecessor test inventory\n")
        write(cls.root, cls.model, b"previously reviewed synthetic source\n")
        cls.previous_source = commit(cls.root, "synthetic schema-1 source")
        helper = load(cls.raw, cls.root / HELPER)
        cls.previous_value = cls.manifest(helper, cls.previous_source, 1)
        cls.previous_raw = encoded(cls.previous_value)
        cls.previous = cls.seal(helper, cls.previous_source, cls.previous_value)
        previous_helper = load((cls.root / HELPER).read_bytes(), cls.root / HELPER)
        previous_helper.verify(cls.root)

        # Preserve a real two-parent development checkpoint, then use normal
        # single-parent commits for the extension source.
        git(cls.root, "reset", "--hard", cls.previous_source)
        write(cls.root, cls.model, b"historical synthetic prototype\n")
        cls.prototype = commit(cls.root, "synthetic prototype")
        git(cls.root, "reset", "--hard", cls.previous)
        write(cls.root, cls.model, b"preserved synthetic local-enable prototype\n")
        git(cls.root, "add", "-A")
        checkpoint_tree = git(cls.root, "write-tree").decode().strip()
        cls.checkpoint = git(cls.root, "commit-tree", checkpoint_tree,
            "-p", cls.previous, "-p", cls.prototype, data=b"preserved development merge\n").decode().strip()
        git(cls.root, "reset", "--hard", cls.checkpoint)
        constants = {
            "LOCAL_ENABLE_PARENT": cls.previous,
            "LOCAL_ENABLE_PARENT_TREE": cls.tree_id(cls.previous),
            "LOCAL_ENABLE_PARENT_SOURCE": cls.previous_source,
            "LOCAL_ENABLE_PARENT_MANIFEST": helper.digest(cls.previous_raw),
            "LOCAL_ENABLE_PARENT_HELPER": cls.previous_value["helper_normalized_sha256"],
            "LOCAL_ENABLE_CHECKPOINT": cls.checkpoint,
            "LOCAL_ENABLE_CHECKPOINT_TREE": checkpoint_tree,
            "LOCAL_ENABLE_PROTOTYPE": cls.prototype,
            "CONTINUATION_PARENT": cls.previous,
            "CONTINUATION_TARGET": cls.target,
            "CONTINUATION_COMMON": cls.base,
            "CONTINUATION_INTEGRATION_PARENT": cls.previous,
            "CONTINUATION_61_BASE": cls.base,
            "CONTINUATION_61_CONTRACT": helper.digest(certificate),
            "CONTINUATION_61_HELPER": helper.digest(checker),
        }
        for name, value in constants.items():
            cls.raw = constant(cls.raw, name, value)
        # The synthetic target files are copied exactly, hence no target
        # reconciliation exceptions exist in this fixture.
        cls.raw, count = re.subn(rb"CONTINUATION_RECONCILIATIONS = frozenset\(\(.*?\n\)\)",
            b"CONTINUATION_RECONCILIATIONS = frozenset()", cls.raw, flags=re.S)
        if count != 1:
            raise RuntimeError("nonunique synthetic reconciliation inventory")
        write(cls.root, HELPER, cls.raw)
        write(cls.root, cls.model, b"reviewed synthetic local-enable source\n")
        cls.source = commit(cls.root, "reviewed linear descendant")
        helper = load(cls.raw, cls.root / HELPER)
        cls.value = cls.manifest(helper, cls.source, 4)
        cls.head = cls.seal(helper, cls.source, cls.value)
        cls.review = load((cls.root / HELPER).read_bytes(), cls.root / HELPER)
        cls.review.verify(cls.root)

    @classmethod
    def tree_id(cls, revision):
        return git(cls.root, "rev-parse", revision + "^{tree}").decode().strip()

    @classmethod
    def records(cls, helper, before_commit, after_commit, paths):
        before_tree, after_tree = helper.tree(cls.root, before_commit), helper.tree(cls.root, after_commit)
        result = []
        for path in sorted(paths):
            before, after = helper.frozen(cls.root, before_commit, path), helper.frozen(cls.root, after_commit, path)
            a, b = before or b"", after or b""
            result.append({"path": path, "before_mode": before_tree.get(path, (None,))[0],
                "after_mode": after_tree.get(path, (None,))[0],
                "before_sha256": None if before is None else helper.digest(before),
                "after_sha256": None if after is None else helper.digest(after),
                "reason": "Synthetic exact file review",
                "edits": [] if a == b else [{"id": path, "reason": "Synthetic exact file span",
                    "before_start": 0, "before_end": len(a), "after_start": 0, "after_end": len(b),
                    "before": a.decode(), "after": b.decode()}]})
        return result

    @classmethod
    def manifest(cls, helper, source, schema):
        value = {"schema_version": schema, "predecessor": cls.base,
            "predecessor_tree": cls.tree_id(cls.base), "source_commit": source,
            "source_tree": cls.tree_id(source),
            "helper_normalized_sha256": helper.digest(helper.normalized_helper(cls.raw)),
            "files": cls.records(helper, cls.base, source,
                helper.changed(cls.root, cls.base, source) - {CONTRACT})}
        if schema == 4:
            value.update(previous_seal=helper.previous_certificate(4),
                development_checkpoint=helper.development_checkpoint(),
                target_integration={"target_commit": cls.target, "target_tree": cls.tree_id(cls.target),
                    "common_base": cls.base, "common_base_tree": cls.tree_id(cls.base),
                    "files": cls.records(helper, cls.target, source,
                        helper.changed(cls.root, cls.base, cls.target))})
        return value

    @classmethod
    def seal(cls, helper, source, value):
        git(cls.root, "reset", "--hard", source)
        raw = encoded(value)
        write(cls.root, CONTRACT, raw)
        write(cls.root, HELPER, constant(cls.raw, "CONTRACT_SHA256", helper.digest(raw)))
        return commit(cls.root, "synthetic direct two-file seal")

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def setUp(self):
        git(self.root, "reset", "--hard", self.head)
        git(self.root, "clean", "-fdx")

    def tearDown(self):
        self.setUp()

    def test_real_frozen_predecessor_audit_and_exact_two_file_seal(self):
        self.review.verify(self.root)
        self.assertEqual(self.review.changed(self.root, self.source, self.head), {CONTRACT, HELPER})
        self.assertEqual(self.review.frozen(self.root, self.source, CONTRACT), self.previous_raw)
        self.assertEqual(self.review.target_anchor(self.root), self.target)
        current = (self.root / self.model).read_bytes()
        self.assertEqual(self.review.restore_source(self.root, self.model, current),
            self.review.frozen(self.root, self.base, self.model))
        self.assertEqual(self.review.target_source(self.root, self.model, current),
            self.review.frozen(self.root, self.target, self.model))

    def test_changed_certificate_checkpoint_target_and_schema_are_rejected(self):
        mutations = [("previous_seal", "seal_commit"), ("previous_seal", "contract_sha256"),
            ("previous_seal", "helper_normalized_sha256"), ("development_checkpoint", "commit"),
            ("development_checkpoint", "tree"), ("target_integration", "target_commit")]
        for outer, inner in mutations:
            with self.subTest(outer=outer, inner=inner):
                value = copy.deepcopy(self.value)
                value[outer][inner] = "0" * len(value[outer][inner])
                with self.assertRaises(RuntimeError):
                    self.review.validate_contract(value)
        value = copy.deepcopy(self.value)
        value["development_checkpoint"]["parents"].reverse()
        with self.assertRaisesRegex(RuntimeError, "checkpoint identity"):
            self.review.validate_contract(value)
        value["schema_version"] = 5
        with self.assertRaisesRegex(RuntimeError, "invalid manifest schema"):
            self.review.validate_contract(value)

    def test_wrong_actual_checkpoint_parent_order_is_rejected(self):
        wrong = git(self.root, "commit-tree", self.tree_id(self.checkpoint),
            "-p", self.prototype, "-p", self.previous, data=b"wrong parent order\n").decode().strip()
        with patch.object(self.review, "LOCAL_ENABLE_CHECKPOINT", wrong):
            value = dict(self.value, development_checkpoint=self.review.development_checkpoint())
            with self.assertRaisesRegex(RuntimeError, "checkpoint topology"):
                self.review.verify_development_history(self.root, value)

    def test_wrong_actual_checkpoint_tree_is_rejected(self):
        with patch.object(self.review, "LOCAL_ENABLE_CHECKPOINT_TREE", self.tree_id(self.base)):
            value = dict(self.value, development_checkpoint=self.review.development_checkpoint())
            with self.assertRaisesRegex(RuntimeError, "checkpoint tree"):
                self.review.verify_development_history(self.root, value)

    def test_unrelated_source_cannot_claim_preserved_checkpoint(self):
        value = dict(self.value, source_commit=self.prototype)
        with self.assertRaisesRegex(RuntimeError, "merge-base"):
            self.review.verify_development_history(self.root, value)

    def test_nonlinear_source_descendant_is_rejected(self):
        merged = git(self.root, "commit-tree", self.tree_id(self.source),
            "-p", self.source, "-p", self.prototype, data=b"unreviewed additional merge\n").decode().strip()
        with self.assertRaisesRegex(RuntimeError, "linear descendant"):
            self.review.verify_development_history(self.root, dict(self.value, source_commit=merged))

    def test_changed_then_restored_predecessor_certificate_is_rejected(self):
        git(self.root, "reset", "--hard", self.source)
        write(self.root, CONTRACT, self.previous_raw + b"\n")
        commit(self.root, "invalid temporary certificate edit")
        write(self.root, CONTRACT, self.previous_raw)
        source = commit(self.root, "restore certificate does not erase history")
        helper = load(self.raw, self.root / HELPER)
        value = self.manifest(helper, source, 4)
        self.seal(helper, source, value)
        sealed = load((self.root / HELPER).read_bytes(), self.root / HELPER)
        with self.assertRaisesRegex(RuntimeError, "development history changed"):
            sealed.verify(self.root)

    def test_unsealed_source_and_extra_seal_edit_are_rejected(self):
        git(self.root, "reset", "--hard", self.source)
        unsealed = load((self.root / HELPER).read_bytes(), self.root / HELPER)
        with self.assertRaisesRegex(RuntimeError, "has not been sealed"):
            unsealed.verify(self.root)
        git(self.root, "reset", "--hard", self.head)
        write(self.root, self.model, b"unreviewed extra seal change\n")
        git(self.root, "add", "-A")
        git(self.root, "commit", "--amend", "--no-edit", "-q")
        with self.assertRaisesRegex(RuntimeError, "first seal differs"):
            self.review.verify(self.root)

    def test_postseal_changed_then_restored_contract_is_rejected(self):
        raw = (self.root / CONTRACT).read_bytes()
        write(self.root, CONTRACT, raw + b"\n")
        commit(self.root, "invalid sealed certificate history")
        write(self.root, CONTRACT, raw)
        commit(self.root, "restored sealed certificate")
        with self.assertRaisesRegex(RuntimeError, "contract history differs"):
            self.review.verify(self.root)


if __name__ == "__main__":
    unittest.main()
