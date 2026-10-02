#!/usr/bin/env python3
"""Exercise the independent merge seal in a disposable real Git repository."""
from __future__ import annotations

import hashlib
import json
import os
import py_compile
import re
import subprocess
import tempfile
import types
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HELPER = "morphhdl/scripts/check-increment-59i-target-integration.py"
TEST = "morphhdl/scripts/test-increment-59i-target-integration.py"
CONTRACT = "morphhdl/contracts/increment-59i-target-integration.json"


def git(root: Path, *args: str, data: bytes | None = None) -> bytes:
    return subprocess.check_output(["git", "-c", "user.name=59i integration fixture",
        "-c", "user.email=59i-integration@example.invalid", *args], cwd=root,
        input=data, stderr=subprocess.STDOUT, timeout=90)


def write(root: Path, path: str, data: bytes) -> None:
    file = root / path
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_bytes(data)


def load(path: Path):
    module = types.ModuleType("independent_integration_fixture")
    module.__file__ = str(path)
    exec(compile(path.read_bytes(), str(path), "exec"), module.__dict__)
    return module


def commit(root: Path, message: str) -> str:
    git(root, "add", "-A")
    git(root, "commit", "-qm", message)
    return git(root, "rev-parse", "HEAD").decode().strip()


class IntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="59i-target-integration-")
        cls.root = Path(cls.temporary.name) / "repo"
        cls.root.mkdir()
        git(cls.root, "init", "-q")
        git(cls.root, "config", "core.filemode", "true")
        cls.shared = "core/src/main/Shared.scala"
        cls.feature_only = "core/src/main/Feature.scala"
        cls.target_only = "core/src/main/Target.scala"
        cls.deleted = "core/src/main/Deleted.scala"
        cls.canceled = "core/src/main/Canceled.scala"
        cls.unrelated = "core/src/main/Stable.scala"
        cls.doc = "docs/plan.md"
        for p in (cls.shared, cls.deleted, cls.canceled, cls.unrelated, cls.doc):
            write(cls.root, p, ("base " + p + "\n").encode())
        write(cls.root, ".gitignore", b"*.ignored\n")
        cls.base = commit(cls.root, "common base")
        write(cls.root, cls.shared, b"feature shared\n")
        write(cls.root, cls.feature_only, b"feature new\n")
        write(cls.root, cls.canceled, b"feature canceled later\n")
        write(cls.root, cls.doc, b"feature documentation\n")
        (cls.root / cls.deleted).unlink()
        cls.feature = commit(cls.root, "feature parent")
        git(cls.root, "reset", "--hard", cls.base)
        write(cls.root, cls.shared, b"target shared\n")
        write(cls.root, cls.target_only, b"target new\n")
        write(cls.root, cls.doc, b"target documentation\n")
        cls.target = commit(cls.root, "target parent")
        write(cls.root, cls.shared, b"reviewed merged shared\n")
        write(cls.root, cls.feature_only, b"feature new\n")
        write(cls.root, cls.doc, b"reviewed merged documentation\n")
        (cls.root / cls.deleted).unlink()
        helper = (ROOT / HELPER).read_bytes()
        for key, value in (("COMMON_BASE", cls.base), ("FEATURE_PARENT", cls.feature),
                           ("TARGET_PARENT", cls.target)):
            helper, count = re.subn((r'^' + key + r' = "[^"]+"$').encode(),
                                    (key + ' = "' + value + '"').encode(), helper, flags=re.M)
            assert count == 1
        helper = re.sub(rb'^CONTRACT_SHA256 = "[^"]+"$', b'CONTRACT_SHA256 = "UNSEALED"',
                        helper, flags=re.M)
        write(cls.root, HELPER, helper)
        write(cls.root, TEST, (ROOT / TEST).read_bytes())
        git(cls.root, "add", "-A")
        merged_tree = git(cls.root, "write-tree").decode().strip()
        cls.source = git(cls.root, "commit-tree", merged_tree, "-p", cls.feature,
                         "-p", cls.target, data=b"immutable reviewed merge source\n").decode().strip()
        git(cls.root, "reset", "--hard", cls.source)
        unsealed = load(cls.root / HELPER)
        paths = {HELPER, TEST}
        for revision in (cls.feature, cls.target, cls.source):
            paths |= unsealed.changed(cls.root, cls.base, revision)
        source_tree = unsealed.tree(cls.root, cls.source)
        entries = []
        for path in sorted(paths):
            entry = {"path": path, "mode": source_tree.get(path, (None,))[0]}
            for revision, key in ((cls.source, "merged_sha256"), (cls.feature, "feature_sha256"),
                                  (cls.target, "target_sha256")):
                raw = unsealed.frozen(cls.root, revision, path)
                entry[key] = (None if raw is None else
                    unsealed.source_digest(path, raw) if key == "merged_sha256" else unsealed.digest(raw))
            entries.append(entry)
        value = {"schema_version": 1, "common_base": cls.base,
            "feature_parent": cls.feature, "target_parent": cls.target, "source_commit": cls.source,
            "feature_tree": git(cls.root, "rev-parse", cls.feature + "^{tree}").decode().strip(),
            "target_tree": git(cls.root, "rev-parse", cls.target + "^{tree}").decode().strip(),
            "source_tree": merged_tree,
            "helper_normalized_sha256": unsealed.digest(unsealed.normalized_helper(helper)),
            "files": entries}
        raw = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()
        cls.manifest_raw = raw
        write(cls.root, CONTRACT, raw)
        sealed = helper.replace(b'CONTRACT_SHA256 = "UNSEALED"',
            ('CONTRACT_SHA256 = "' + hashlib.sha256(raw).hexdigest() + '"').encode())
        write(cls.root, HELPER, sealed)
        cls.head = commit(cls.root, "seal reviewed source")
        cls.review = load(cls.root / HELPER)
        cls.review.verify(cls.root)

    def setUp(self):
        self.restore()

    def restore(self):
        # Remove links before allowing Git to restore any tracked descendants.
        for current, dirs, files in os.walk(self.root, followlinks=False):
            dirs[:] = [name for name in dirs if name != ".git"]
            for name in dirs + files:
                file = Path(current) / name
                if file.is_symlink():
                    file.unlink()
        git(self.root, "update-index", "--no-assume-unchanged", self.shared)
        git(self.root, "reset", "--hard", self.head)
        git(self.root, "clean", "-fdx")

    def tearDown(self):
        self.restore()

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def reject(self, action=None):
        with self.assertRaisesRegex(RuntimeError, "59i target integration"):
            (action or (lambda: self.review.verify(self.root)))()

    def append(self, path):
        file = self.root / path
        file.write_bytes(file.read_bytes() + b"\nmutation\n")

    def test_both_views_exact_and_idempotent(self):
        value = self.review.verify(self.root)
        for entry in value["files"]:
            path = entry["path"]
            source = (self.root / path).read_bytes() if entry["mode"] else b""
            for revision, view in ((self.feature, self.review.feature_source),
                                   (self.target, self.review.target_source)):
                expected = self.review.frozen(self.root, revision, path) or b""
                self.assertEqual(view(self.root, path, source), expected)
                self.assertEqual(view(self.root, path, expected), expected)
                self.reject(lambda: view(self.root, path, source + b"mutation"))
        arbitrary = b"outside-owned-path mutation"
        self.assertEqual(self.review.feature_source(self.root, self.unrelated, arbitrary), arbitrary)

    def test_inventory_preserves_overlap_cancelation_and_domains(self):
        production = lambda paths: {p for p in paths if "/src/main/" in p}
        for parent, project in ((self.feature, self.review.feature_inventory),
                                (self.target, self.review.target_inventory)):
            current = production(self.review.changed(self.root, self.base, "HEAD"))
            expected = production(self.review.changed(self.root, self.base, parent))
            actual = project(self.root, current, self.base)
            self.assertEqual(actual, expected)
            self.assertEqual(project(self.root, actual, self.base), actual)
            omitted = project(self.root, (current - {self.shared}) | {self.unrelated}, self.base)
            self.assertNotIn(self.shared, omitted)
            self.assertIn(self.unrelated, omitted)
            canceled = project(self.root, {self.unrelated}, "HEAD")
            self.assertEqual(canceled, production(self.review.changed(self.root, "HEAD", parent)) |
                             {self.unrelated})
            self.assertNotIn(self.doc, canceled)
            full = project(self.root, set(), "HEAD", full=True)
            self.assertEqual(full, self.review.changed(self.root, "HEAD", parent))
            self.assertIn(self.doc, full)
            self.assertEqual(project(self.root, full, "HEAD", full=True), full)
        # The feature modification was genuinely canceled in the merged tree.
        self.assertNotIn(self.canceled, self.review.changed(self.root, self.base, "HEAD"))
        self.assertIn(self.canceled, self.review.feature_inventory(self.root, set(), self.base))

    def test_inventory_against_each_parent_and_source_anchor(self):
        for parent, project in ((self.feature, self.review.feature_inventory),
                                (self.target, self.review.target_inventory)):
            for qualification in (self.base, self.feature, self.target, self.source, "HEAD"):
                for full in (False, True):
                    with self.subTest(parent=parent, qualification=qualification, full=full):
                        domain = lambda paths: paths if full else {p for p in paths if "/src/main/" in p}
                        current = domain(self.review.changed(self.root, qualification, "HEAD"))
                        expected = domain(self.review.changed(self.root, qualification, parent))
                        actual = project(self.root, current, qualification, full=full)
                        self.assertEqual(actual, expected)
                        self.assertEqual(project(self.root, actual, qualification, full=full), actual)

    def test_same_process_mutation_after_pass_rejects(self):
        self.review.verify(self.root)
        self.append(self.shared)
        self.reject()

    def test_dirty_and_committed_mutations(self):
        for path in (self.shared, self.unrelated, self.doc, HELPER, TEST, CONTRACT):
            with self.subTest(path=path):
                self.restore()
                self.append(path)
                self.reject()
                commit(self.root, "committed mutation")
                self.reject()

    def test_unknown_production_and_documentation(self):
        for path in ("core/src/main/Unknown.scala", "docs/unknown.md"):
            with self.subTest(path=path):
                self.restore()
                write(self.root, path, b"unknown\n")
                self.reject()
                commit(self.root, "unknown committed source")
                self.reject()

    def test_hidden_index_and_assume_unchanged(self):
        old = (self.root / self.shared).read_bytes()
        self.append(self.shared)
        git(self.root, "add", self.shared)
        write(self.root, self.shared, old)
        self.reject()
        self.restore()
        git(self.root, "update-index", "--assume-unchanged", self.shared)
        self.append(self.shared)
        self.reject()

    def test_ignored_source_rejects(self):
        path = "core/src/main/Unknown.ignored"
        write(self.root, path, b"ignored source\n")
        self.assertEqual(git(self.root, "status", "--porcelain"), b"")
        self.reject()

    def test_only_recognized_python_caches_may_be_ignored(self):
        exclude = self.root / ".git/info/exclude"
        previous = exclude.read_bytes()
        try:
            exclude.write_bytes(previous + b"\n*.pyc\nUnknown.py\nUnknown.json\nUnknown.source\n")
            py_compile.compile(str(self.root / HELPER), doraise=True)
            self.assertEqual(git(self.root, "status", "--porcelain"), b"")
            self.review.verify(self.root)
            for path in ("morphhdl/scripts/Unknown.py", "morphhdl/contracts/Unknown.json",
                         ".github/workflows/Unknown.source",
                         "morphhdl/scripts/__pycache__/Unknown.cpython-312.pyc"):
                with self.subTest(path=path):
                    write(self.root, path, b"unreviewed ignored source\n")
                    self.assertEqual(git(self.root, "status", "--porcelain"), b"")
                    self.reject()
                    (self.root / path).unlink()
        finally:
            exclude.write_bytes(previous)

    def test_modes_symlinks_and_missing_source(self):
        for kind in ("mode", "symlink", "parent-symlink", "missing", "deleted-reappears"):
            with self.subTest(kind=kind):
                self.restore()
                if kind == "mode":
                    (self.root / self.shared).chmod(0o755)
                elif kind == "symlink":
                    (self.root / self.shared).unlink()
                    (self.root / self.shared).symlink_to("/dev/null")
                elif kind == "parent-symlink":
                    directory = self.root / "core/src/main"
                    directory.rename(directory.with_name("saved"))
                    directory.symlink_to("saved", target_is_directory=True)
                elif kind == "missing":
                    (self.root / self.shared).unlink()
                else:
                    write(self.root, self.deleted, b"reappeared\n")
                self.reject()

    def test_contract_schema_and_anchor_mutations_even_when_resealed(self):
        original_digest = self.review.CONTRACT_SHA256
        cases = (("schema_version", 2), ("feature_parent", "0" * 40),
                 ("source_tree", "0" * 40), ("source_commit", self.target),
                 ("helper_normalized_sha256", "0" * 64), ("files", []))
        try:
            for key, replacement in cases:
                with self.subTest(key=key):
                    self.restore()
                    value = json.loads(self.manifest_raw)
                    value[key] = replacement
                    raw = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()
                    write(self.root, CONTRACT, raw)
                    self.review.CONTRACT_SHA256 = hashlib.sha256(raw).hexdigest()
                    self.reject()
        finally:
            self.review.CONTRACT_SHA256 = original_digest


if __name__ == "__main__":
    unittest.main()
