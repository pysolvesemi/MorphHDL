#!/usr/bin/env python3
"""Test widening projection with the real successor verifier in miniature Git repos.

Only synthetic sources and contracts are created, all below TemporaryDirectory.
This never seals production, substitutes a projector double for the positive
case, or treats these fixtures as full-repository inherited qualification.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "morphhdl/scripts/check-increment-59i-widening-source-review.py"
HELPER = "morphhdl/scripts/check-increment-59i-production-successor.py"
HELPER_SOURCE = Path(os.environ.get("MORPHHDL_59I_HELPER_SOURCE", str(ROOT / HELPER)))


def load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load test source: " + str(path))
    module = importlib.util.module_from_spec(spec)
    exec(compile(path.read_bytes(), str(path), "exec"), module.__dict__)
    return module


def git(root: Path, *args: str) -> bytes:
    result = subprocess.run(["git", *args], cwd=root, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=30, check=False)
    if result.returncode:
        raise RuntimeError("fixture Git failed: " + repr(args) + "\n" + result.stderr.decode())
    return result.stdout


def write(root: Path, path: str, raw: bytes) -> None:
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)
    target.chmod(0o644)


def commit(root: Path, message: str) -> str:
    git(root, "add", "-A")
    git(root, "commit", "-q", "-m", message)
    return git(root, "rev-parse", "HEAD").decode().strip()


def replace_constant(raw: bytes, key: str, value: str) -> bytes:
    result, count = re.subn((r'^' + key + r' = "[^"\n]+"$').encode(),
                            (key + ' = "' + value + '"').encode(), raw, flags=re.M)
    if count != 1:
        raise RuntimeError("non-unique synthetic constant: " + key)
    return result


def whole_span(path: str, before: bytes, after: bytes) -> list[dict]:
    if before == after:
        return []
    return [{"id": "synthetic:" + path, "reason": "Exact miniature fixture change",
             "before_start": 0, "before_end": len(before),
             "after_start": 0, "after_end": len(after),
             "before": before.decode(), "after": after.decode()}]


class WideningSuccessorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="59i-widening-successor-unit-")
        cls.root = Path(cls.tmp.name)
        cls.W = load(CHECKER, "widening_repair_tested_source")
        w = cls.W
        git(cls.root, "init", "-q")
        git(cls.root, "config", "user.name", "Synthetic widening fixture")
        git(cls.root, "config", "user.email", "fixture@example.invalid")
        git(cls.root, "config", "commit.gpgsign", "false")
        cls.before = {path: b"" for path in w.PATHS}
        cls.before[w.PATHS[2]] = b"object HistoricalBase {}\n"
        write(cls.root, w.PATHS[2], cls.before[w.PATHS[2]])
        write(cls.root, "README.md", b"Synthetic fixture only.\n")
        write(cls.root, ".gitignore", b"__pycache__/\ntarget/\n*.ignored\n")
        cls.base = commit(cls.root, "synthetic widening baseline")
        w.BASE = cls.base
        cls.historical = {path: ("object Reviewed" + str(index) + " {}\n").encode()
                          for index, path in enumerate(w.PATHS)}
        entries = []
        for path in w.PATHS:
            write(cls.root, path, cls.historical[path])
            before = cls.before[path]
            entries.append({"path": path, "change": "added" if path in w.ADDED_PATHS else "modified",
                            "reason": "Synthetic historical widening layer",
                            "baseline_sha256": None if path in w.ADDED_PATHS else w.digest(before),
                            "edits": whole_span(path, before, cls.historical[path])})
        legacy = {"schema_version": 1, "base": cls.base, "offset_format": "utf8-bytes", "files": entries}
        raw = (json.dumps(legacy, indent=2) + "\n").encode()
        write(cls.root, w.CONTRACT, raw)
        w.CONTRACT_SHA256 = w.digest(raw)
        cls.predecessor = commit(cls.root, "synthetic immutable widening successor")
        w.SUCCESSOR = cls.predecessor
        w.verify(cls.root)

        # Only the anchor and seal slot change for this disposable repository.
        # All verifier implementation functions are the recovered real code.
        original_helper = HELPER_SOURCE.read_bytes()
        normalized, slots = re.subn(rb'^CONTRACT_SHA256 = "[^"\n]+"$',
                                    b'CONTRACT_SHA256 = "MANIFEST_HASH"', original_helper, flags=re.M)
        if slots != 1 or hashlib.sha256(normalized).hexdigest() != w.SUCCESSOR_HELPER_SHA256:
            raise RuntimeError("unit fixtures require the exact pinned real successor verifier")
        cls.helper_source = replace_constant(original_helper, "BASE", cls.predecessor)
        cls.helper_source = replace_constant(cls.helper_source, "CONTRACT_SHA256", "UNSEALED")
        write(cls.root, HELPER, cls.helper_source)
        h = load(cls.root / HELPER, "synthetic_unsealed_widening_successor")
        cls.H = h
        write(cls.root, h.TEST, b"# Synthetic fixture manifest identity; tests run externally.\n")
        write(cls.root, h.COMPLETION_TODO, b"Synthetic TODO\n" + h.COMPLETION_ANCHOR)
        cls.current = dict(cls.historical)
        for path in (w.PATHS[0], w.PATHS[2]):
            cls.current[path] += "// reviewed current layer \u03c0\n".encode()
            write(cls.root, path, cls.current[path])
        cls.source = commit(cls.root, "synthetic reviewed evolved source")
        before_tree, after_tree = h.tree(cls.root, cls.predecessor), h.tree(cls.root, cls.source)
        records = []
        for path in sorted(h.changed(cls.root, cls.predecessor, cls.source)):
            before = h.frozen(cls.root, cls.predecessor, path)
            after = h.frozen(cls.root, cls.source, path)
            records.append({"path": path, "before_mode": before_tree.get(path, (None,))[0],
                            "after_mode": after_tree.get(path, (None,))[0],
                            "before_sha256": None if before is None else h.digest(before),
                            "after_sha256": None if after is None else h.digest(after),
                            "reason": "Synthetic source successor, not production authority",
                            "edits": whole_span(path, before or b"", after or b"")})
        cls.manifest = {"schema_version": 1, "predecessor": cls.predecessor,
                        "predecessor_tree": git(cls.root, "rev-parse", cls.predecessor + "^{tree}").decode().strip(),
                        "source_commit": cls.source,
                        "source_tree": git(cls.root, "rev-parse", cls.source + "^{tree}").decode().strip(),
                        "helper_normalized_sha256": h.digest(h.normalized_helper(cls.helper_source)),
                        "files": records}
        cls.manifest_raw = (json.dumps(cls.manifest, indent=2, sort_keys=True) + "\n").encode()
        write(cls.root, h.CONTRACT, cls.manifest_raw)
        write(cls.root, HELPER, replace_constant(cls.helper_source, "CONTRACT_SHA256", h.digest(cls.manifest_raw)))
        cls.seal = commit(cls.root, "synthetic direct-child seal")
        w.SUCCESSOR_HELPER_SHA256 = h.digest(h.normalized_helper(cls.helper_source))
        w.verify(cls.root)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def reset(self, revision=None):
        git(self.root, "reset", "--hard", revision or self.seal)
        git(self.root, "clean", "-fdx")

    def setUp(self):
        self.reset()

    def tearDown(self):
        self.reset()

    def reject(self, detail):
        with self.assertRaisesRegex(RuntimeError, detail):
            self.W.verify(self.root)

    def test_legacy_successor_and_unrelated_descendant_keep_direct_reads(self):
        self.reset(self.predecessor)
        self.assertIsNone(self.W.successor_review(self.root))
        self.W.verify(self.root)
        write(self.root, "README.md", b"Synthetic unrelated documentation change.\n")
        commit(self.root, "synthetic legacy descendant")
        self.assertIsNone(self.W.successor_review(self.root))
        self.W.verify(self.root)

    def test_sealed_current_bytes_project_with_real_verifier(self):
        successor = self.W.successor_review(self.root)
        self.assertIsNotNone(successor)
        self.assertEqual(successor.verify(self.root), self.manifest)
        for path in self.W.PATHS:
            self.assertEqual(self.W.current_reviewed_source(self.root, path, successor), self.historical[path])
            self.assertEqual(self.W.restore_source(self.root, path, self.historical[path].decode()).encode(),
                             self.before[path])
        self.W.verify(self.root)
        self.assertFalse(list(self.root.rglob("__pycache__")))

    def test_original_direct_read_defect_is_detected(self):
        with mock.patch.object(self.W, "current_reviewed_source",
                               side_effect=lambda root, path, successor: (root / path).read_bytes()):
            self.reject("59i widening production source differs from its immutable reviewed successor")

    def test_projected_bytes_still_must_equal_frozen_widening(self):
        original = self.W.current_reviewed_source
        with mock.patch.object(self.W, "current_reviewed_source",
                               side_effect=lambda root, path, successor: original(root, path, successor) + b"x"):
            self.reject("59i widening production source differs from its immutable reviewed successor")

    def test_both_seal_files_removed_reject_unstaged_staged_and_committed(self):
        for mode in ("unstaged", "staged", "committed"):
            with self.subTest(mode=mode):
                self.reset()
                for path in (HELPER, self.H.CONTRACT):
                    (self.root / path).unlink()
                # Roll back the widening source as well: direct comparison alone
                # would accept this attempt to hide the enclosing seal.
                for path, raw in self.historical.items():
                    write(self.root, path, raw)
                if mode != "unstaged":
                    git(self.root, "add", "-A")
                if mode == "committed":
                    commit(self.root, "synthetic malicious seal removal")
                self.reject("59i widening production successor files were removed")

    def test_merge_cannot_hide_removed_seal_on_second_parent(self):
        # Ordinary path-history simplification can hide the sealed second
        # parent when the merge adopts the legacy first parent's entire tree.
        tree = git(self.root, "rev-parse", self.predecessor + "^{tree}").decode().strip()
        merged = git(self.root, "commit-tree", tree, "-p", self.predecessor, "-p", self.seal,
                     "-m", "synthetic malicious merge hiding its sealed second parent").decode().strip()
        self.reset(merged)
        self.reject("59i widening production successor files were removed")

    def test_staged_seal_deletion_with_restored_visible_files_rejects(self):
        paths = (HELPER, self.H.CONTRACT)
        original = {path: (self.root / path).read_bytes() for path in paths}
        git(self.root, "rm", "--cached", "--", *paths)
        for path, raw in original.items():
            self.assertEqual((self.root / path).read_bytes(), raw)
        self.reject("59i production successor: HEAD/index identity differs")

    def test_either_seal_file_missing_rejects(self):
        for path in (HELPER, self.H.CONTRACT):
            for mode in ("unstaged", "staged", "committed"):
                with self.subTest(path=path, mode=mode):
                    self.reset()
                    (self.root / path).unlink()
                    if mode != "unstaged":
                        git(self.root, "add", "-A")
                    if mode == "committed":
                        commit(self.root, "synthetic incomplete seal")
                    self.reject("59i widening missing regular non-executable successor file")

    def test_source_drift_rejects_in_worktree_index_and_history(self):
        for mode in ("worktree", "index", "committed"):
            with self.subTest(mode=mode):
                self.reset()
                path = self.W.PATHS[0]
                write(self.root, path, self.current[path] + b"unreviewed\n")
                if mode == "index":
                    git(self.root, "add", path)
                    write(self.root, path, self.current[path])
                elif mode == "committed":
                    commit(self.root, "synthetic source drift")
                self.reject("59i production successor: (?:HEAD/index|sealed route tree)")

    def test_helper_replacement_is_rejected_before_execution(self):
        sentinel = self.root / ".git/executed-untrusted-helper"
        raw = (self.root / HELPER).read_bytes()
        raw += ("\nfrom pathlib import Path\nPath(" + repr(str(sentinel)) + ").touch()\n").encode()
        write(self.root, HELPER, raw)
        self.reject("59i widening production successor reviewer changed")
        self.assertFalse(sentinel.exists())

    def test_linked_and_executable_seal_files_reject(self):
        for path in (HELPER, self.H.CONTRACT):
            for mode in ("executable", "linked"):
                with self.subTest(path=path, mode=mode):
                    self.reset()
                    target = self.root / path
                    if mode == "executable":
                        target.chmod(0o755)
                        detail = "59i widening missing regular non-executable successor file"
                    else:
                        saved = self.root / ".git/synthetic-link-target"
                        saved.write_bytes(target.read_bytes())
                        target.unlink()
                        target.symlink_to(saved)
                        detail = "59i widening linked production successor file"
                    self.reject(detail)

    def test_contract_drift_and_unsealed_source_reject(self):
        path = self.root / self.H.CONTRACT
        path.write_bytes(path.read_bytes() + b"\n")
        self.reject("59i production successor: sealed successor manifest changed")
        self.reset(self.source)
        self.reject("59i widening missing regular non-executable successor file")
        write(self.root, self.H.CONTRACT, self.manifest_raw)
        self.reject("59i production successor: source successor has not been sealed")

    def test_cached_code_does_not_cache_live_authorization(self):
        self.W.verify(self.root)
        write(self.root, "README.md", b"Unreviewed bytes outside widening source.\n")
        self.reject("59i production successor: HEAD/index/worktree identity differs: README.md")

    def test_resealing_cannot_replace_original_seal(self):
        value = json.loads(self.manifest_raw)
        value["files"][0]["reason"] = "Changed synthetic reason in a forbidden later seal"
        raw = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()
        write(self.root, self.H.CONTRACT, raw)
        write(self.root, HELPER, replace_constant(self.helper_source, "CONTRACT_SHA256", hashlib.sha256(raw).hexdigest()))
        commit(self.root, "synthetic competing reseal")
        self.reject("59i production successor: first seal differs from immutable source plus exact seal")

    def test_hidden_drift_then_revert_is_rejected(self):
        write(self.root, "README.md", b"Forbidden intermediate change.\n")
        commit(self.root, "synthetic drift before revert")
        write(self.root, "README.md", b"Synthetic fixture only.\n")
        commit(self.root, "synthetic revert is not authorization")
        self.reject("59i production successor: sealed route tree differs")

    def test_ignored_python_cache_rejected_and_loader_emits_none(self):
        import py_compile
        self.W.verify(self.root)
        self.assertFalse(list(self.root.rglob("*.pyc")))
        cache = self.root / "morphhdl/scripts/__pycache__/unreviewed.pyc"
        py_compile.compile(str(self.root / HELPER), cfile=str(cache), doraise=True)
        self.reject("59i production successor: untracked or ignored source addition")
        cache.unlink()
        self.W.verify(self.root)

    def test_historical_span_mutations_remain_rejected(self):
        successor = self.W.successor_review(self.root)
        for path, entry in self.W.load_contract(self.root).items():
            raw = self.W.current_reviewed_source(self.root, path, successor)
            for edit in entry["edits"]:
                position = edit["after_start"]
                changed = raw[:position] + bytes([raw[position] ^ 1]) + raw[position + 1:]
                with self.assertRaisesRegex(RuntimeError, "missing or changed widening source span"):
                    self.W.restore_reviewed(entry, self.before[path], changed)

    def test_wrong_frozen_contract_hash_still_rejects(self):
        with mock.patch.object(self.W, "CONTRACT_SHA256", "0" * 64):
            self.reject("59i widening review contract changed")


if __name__ == "__main__":
    unittest.main(verbosity=2)
