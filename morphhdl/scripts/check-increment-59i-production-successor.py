#!/usr/bin/env python3
"""Authenticate a separately sealed source successor before historical reviews.

Historical schemas retain their original absent-manifest lifecycle. Schema 3
retains the published predecessor certificate, authenticates its immutable source
and the integrated target in isolated Git checkouts, then binds a separately
reviewed cumulative source manifest. Every new seal changes only that manifest
and this module's one manifest-hash placeholder. No result is RTL qualification.
Schema 4 also preserves an exact development merge and admits only linear
reviewed source descendants while replaying the published schema-3 audit.
Schema 5 preserves that complete certificate, the exact PR190 integration,
and its runtime bytes while permitting a closed audit-only source review.
Schema 6 preserves the published schema-5 seal and admits one exact, direct
runtime/ABI repair successor whose complete bytes remain manifest reviewed.
Schema 7 preserves that published schema-6 seal and admits one exact, direct
audit-diagnostic repair without changing compiler, tests, proofs or workflows.
"""
from __future__ import annotations

import argparse
import functools
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
from pathlib import Path
from types import MappingProxyType
from collections import deque

BASE = "954d9b2763b064dba60af71ad8fa509a9d7cada8"
# Schema 2 admits only this reviewed target refresh. Schema 1 and all its
# historical direct-child rules remain valid; a moving branch grants no rights.
INTEGRATION_TARGET = "61d1fe0dcac0b52856620944a2d7426fd1390a48"
INTEGRATION_BASE = "f06d9c412924b99cf2c76422549c375a571757dc"
INTEGRATION_RECONCILIATIONS = frozenset((
    ".github/workflows/increment-59d-widening.yml",
    ".github/workflows/increment-59h-nested-owners.yml",
    "core/src/main/scala/spinal/core/ElabInt.scala",
    "morphhdl/contracts/increment-55-native-change-review.json",
    "morphhdl/contracts/native-source-preservation.json",
    "morphhdl/scripts/check-increment-62-wa08-source-overlay.py",
    "morphhdl/scripts/test-increment-59b-inherited-source-scope.py",
    "morphhdl/scripts/test-increment-59d-inherited-60f-scope.py",
    "morphhdl/scripts/test-increment-59h-inherited-source-scope.py",
    "morphhdl/scripts/test-inherited-source-audit-timeouts.py",
))
# A separately reviewed continuation retains the published seal as its first
# parent. These are immutable certificates, never moving branch permissions.
CONTINUATION_PARENT = "f42641880e0645f0c997ecedabd031bf8948bbfa"
CONTINUATION_PARENT_TREE = "c24dc1b9842849bb10abc3e6ad051fe7f8847172"
CONTINUATION_PARENT_SOURCE = "7d5a336423fba5b200794045dfd167823590181b"
CONTINUATION_PARENT_MANIFEST = "279c45f7412a8a4f1844f5ebbe37c6acec40f053cf21d7d624a0bde2f6da3946"
CONTINUATION_PARENT_HELPER = "c3eff01cc7b0dda2d9d04c288e2ad6942feab0eb100fabd4c4baff76f8c8d5aa"
CONTINUATION_TARGET = "e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d"
# The immediate sealed predecessor already contains Increment61. The current
# target delta is exactly 27af65ab..the PR189 merge, including PR187. Its earlier
# 81 target records remain mandatory through the immutable predecessor audit.
CONTINUATION_INTEGRATION_PARENT = "37d1629f9b78c4d9cd6646abee9962fdd046e413"
CONTINUATION_COMMON = "27af65abbee0d2334d6be7a6e4e2408b8af32fd9"
CONTINUATION_61_BASE = "7f355a859e7e88ca343e1ff82f261fb47b3311d0"
CONTINUATION_61_CONTRACT = "67f19808ea90ef6b8ef2b17f9dedb98f7ea40dbcdd57df172604e0da17faa972"
CONTINUATION_61_HELPER = "82b1e8db624b1076a1a4871e1980691d907517e97956f3bf885763fe8a02298f"
CONTINUATION_RECONCILIATIONS = frozenset((
    '.github/workflows/increment-60b-signedness-authority.yml',
    '.github/workflows/increment-60f-equivalence-closure.yml',
    '.github/workflows/increment-60g-default-signed-verilog.yml',
    '.github/workflows/increment-62-wa08-source-overlay.yml',
    'frontend/src/main/scala/morphhdl/frontend/HdlInt.scala',
    'morphhdl/contracts/increment-55-native-change-review.json',
    'morphhdl/contracts/native-source-preservation.json',
    'morphhdl/scripts/check-cdc-successor-source.py',
    'morphhdl/scripts/check-increment-61-source-review.py',
    'morphhdl/scripts/check-increment-62-wa08-source-overlay.py',
    'morphhdl/scripts/test-increment-59h-inherited-source-scope.py',
    'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala',
    'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala',
))

# The local-enable development lineage is fixed separately from its subsequent
# source review. Neither a branch name nor an arbitrary merge grants authority.
LOCAL_ENABLE_PARENT = "90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6"
LOCAL_ENABLE_PARENT_TREE = "1b8bf66047a28b836d61f6b168d28dd3b8f95cb2"
LOCAL_ENABLE_PARENT_SOURCE = "1ed8930326497aa43a98c2491468d4cb75e59f3a"
LOCAL_ENABLE_PARENT_MANIFEST = "99dd143a0cc54898051e21adb58d311671af97642c6a77e52554f5d86b85125b"
LOCAL_ENABLE_PARENT_HELPER = "fe07b4a07dd2e1d7c31d0dcfeafd5e83eac10ba03ed4a7839db124c2f032e60c"
LOCAL_ENABLE_CHECKPOINT = "d76fbd5f84869ac56186b36f35dfc3c480a80cbb"
LOCAL_ENABLE_CHECKPOINT_TREE = "5df50314aa7ae3bad916b157b69a87a278c391ba"
LOCAL_ENABLE_PROTOTYPE = "1931c0aa82860d81a9a651ffa06b920840ddea1e"

PR190_PARENT = "b6f1fefb531ca4cb5aca266628dc29093f6bbafe"
PR190_PARENT_TREE = "3da204d0bc088d904c7d3583d38a695a2ebc35b2"
PR190_PARENT_SOURCE = "1bea280f891717660ca4f332ec6e08754ef59fbd"
PR190_PARENT_MANIFEST = "008f378bbe19b7039dd48dad4689c749d963ce3069c45f8879d6dfb5f065674b"
PR190_PARENT_HELPER = "31226de9bc5a5818017b01ecdd295e991e23c5752f67137c01dd667208a35227"
PR190_INTEGRATION_PARENT = "58fb59773a2deebba0251b5b626c19a22453f0a4"
PR190_TARGET = "4b8a86e25f5a1a3f0cb4c37dc537a8dd8aa7b097"
PR190_TARGET_TREE = "ebe59eecbc8f550d265e78c717fb093603329055"
PR190_COMMON = "e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d"
PR190_CHECKPOINT = "ccec54986c70077e361f291cd322f0aa547aee15"
PR190_CHECKPOINT_TREE = "daa5f39b8f6f94a89073f0fad5a160b69df3ebc5"
PR190_TARGET_CHECKER = "cfa38c671546dbfd892416589b53112e50c9588dd0bb48602b3e9b8e7b393001"
# Preserve the runtime integration above. This later target adds exactly two
# roadmaps; neither document becomes writable during source development.
PR190_DOCUMENTATION_TARGET = "bbae646ba43e6189c69feb308f8decb9b677b15f"
PR190_DOCUMENTATION_TARGET_TREE = "c2f6e2abd588a131c5e6909173659935c62e77b7"
PR190_DOCUMENTATION_CHECKPOINT = "771b02d9c5669f7e3a3cc3732b393dd083947ea6"
PR190_DOCUMENTATION_CHECKPOINT_TREE = "8eab276de28956f977fe4089e00318ea924813f3"
PR190_DOCUMENTATION_PATHS = frozenset((
    "docs/morphhdl/parameterized-verilog-todo.md",
    "morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md",
))
PR190_RECONCILIATIONS = frozenset((
    '.github/workflows/increment-60f-equivalence-closure.yml',
    '.github/workflows/increment-62-wa08-source-overlay.yml',
    'morphhdl/contracts/increment-55-native-change-review.json',
    'morphhdl/contracts/native-source-preservation.json',
    'morphhdl/scripts/check-cdc-successor-source.py',
    'morphhdl/scripts/check-increment-61-source-review.py',
    'morphhdl/scripts/check-increment-62-wa08-source-overlay.py',
    'morphhdl/scripts/check-pr190-pr189-source-sync.py',
    'morphhdl/scripts/check-sequential-wire-source-review.py',
))
# No compiler, tests of generated hardware, build settings, hardware checker,
# oracle, or source-review certificate from the previous seal is writable here.
# Every audit byte below still needs an explicit cumulative manifest record.
PR190_AUDIT_PATHS = frozenset((
    '.github/workflows/cdc-independent-parameter-consumers.yml',
    '.github/workflows/increment-59e-composite-reduction.yml',
    '.github/workflows/increment-59f-callback-graphs.yml',
    '.github/workflows/increment-59g-register-bridges.yml',
    '.github/workflows/increment-59h-nested-owners.yml',
    '.github/workflows/increment-60f-equivalence-closure.yml',
    '.github/workflows/increment-61-compatibility-matrix.yml',
    '.github/workflows/increment-61-one-file-per-component.yml',
    '.github/workflows/lane-when-expression-diagnostic.yml',
    '.github/workflows/morphhdl-baseline.yml',
    '.github/workflows/morphhdl-mill.yml',
    '.github/workflows/increment-59i-local-enable-committed-head.yml',
    'docs/morphhdl/increment-59i-pr190-integration-review.md',
    'morphhdl/scripts/check-increment-59i-production-successor.py',
    'morphhdl/scripts/check-increment-59i-pr190-integration.py',
    'morphhdl/scripts/check-increment-59i-regression-inventory.py',
    'morphhdl/scripts/test-increment-59i-pr190-integration.py',
    'morphhdl/scripts/check-increment-59i-target-integration.py',
    'morphhdl/scripts/check-increment-59i-local-enable-source-review.py',
    'morphhdl/scripts/check-increment-59i-widening-source-review.py',
    'morphhdl/scripts/check-increment-59i-regression-inventory.py',
    'morphhdl/scripts/check-increment-59i-rollout-composition.py',
    'morphhdl/scripts/check-increment-60b-signedness-authority.py',
    'morphhdl/scripts/check-increment-61-source-review.py',
    'morphhdl/scripts/check-increment-62-wa08-source-overlay.py',
    'morphhdl/scripts/check-cdc-successor-source.py',
    'morphhdl/scripts/check-pr190-pr189-source-sync.py',
    'morphhdl/scripts/check-sequential-wire-source-review.py',
    'morphhdl/scripts/test-increment-59i-continuation.py',
    'morphhdl/scripts/test-increment-59i-nested-results.py',
    'morphhdl/scripts/test-increment-59i-pr189-sync.py',
    'morphhdl/scripts/test-increment-60b-inherited-source-scope.py',
    'morphhdl/scripts/test-increment-62-wa08-source-overlay.py',
))

# The failed-first qualification exposed legacy JVM descriptors and two exact
# inventory guards that were not exercised by the prior seal.  The successor
# must be one direct child of this immutable seal and may touch only this closed
# set.  The cumulative manifest still records and reverses every changed byte.
RUNTIME_REPAIR_PARENT = "883c5d8f088a0e2eab35592cf171d87792d30bf4"
RUNTIME_REPAIR_PARENT_TREE = "f2219f49ef53ea3defc3526b0df76e5a67df4993"
RUNTIME_REPAIR_PARENT_SOURCE = "44313610eb2d72759808f348a552134e97805474"
RUNTIME_REPAIR_PARENT_MANIFEST = "17f8727e14fd4788c7e0f362506cf2542b9f6bcb6bea4e724a0692e9ee393615"
RUNTIME_REPAIR_PARENT_HELPER = "aadb2209a95947e8d86bf7c6cb34075b4b1f376f8894b809d20a52f56ffa7dbe"
RUNTIME_REPAIR_PATHS = frozenset((
    '.github/workflows/increment-59h-nested-owners.yml',
    '.github/workflows/increment-59i-combined-closure.yml',
    '.github/workflows/increment-60f-equivalence-closure.yml',
    'morphhdl/scripts/check-increment-59i-production-successor.py',
    'morphhdl/scripts/check-increment-59i-local-enable-source-review.py',
    'morphhdl/scripts/check-increment-59i-pr190-integration.py',
    'morphhdl/scripts/check-increment-59i-regression-inventory.py',
    'morphhdl/scripts/check-increment-59i-rollout-composition.py',
    'morphhdl/scripts/check-increment-59i-target-integration.py',
    'morphhdl/scripts/check-increment-59i-widening-source-review.py',
    'morphhdl/scripts/check-increment-60b-signedness-authority.py',
    'morphhdl/scripts/check-increment-61-source-review.py',
    'morphhdl/scripts/check-increment-62-wa08-source-overlay.py',
    'morphhdl/scripts/check-cdc-successor-source.py',
    'morphhdl/scripts/check-pr190-pr189-source-sync.py',
    'morphhdl/scripts/test-increment-59g-report-inventory.py',
    'morphhdl/scripts/test-increment-59i-regression-inventory.py',
    'morphhdl/scripts/test-increment-59i-continuation.py',
    'morphhdl/scripts/test-increment-59i-pr189-sync.py',
    'morphhdl/scripts/test-increment-59i-pr190-integration.py',
    'morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala',
    'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala',
    'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala',
    'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCapture.scala',
    'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionClosedGraph.scala',
    'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala',
    'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionStageReplay.scala',
))

# Four targeted workflows exposed ordering and runtime changes in retained
# negative fixtures: schema-6 correctly rejects inherited mutations before the
# older reviewer, and current successor negatives now authenticate enough source
# to exceed their historical 120-second cap.  This successor changes only the
# exact audit programs and current-only scheduling controls needed for them.
AUDIT_REJECTION_REPAIR_PARENT = "9d6d738d32c71ff4359384ae9d87f715bf20ec55"
AUDIT_REJECTION_REPAIR_PARENT_TREE = "3498988dd26e3ee8c64209dec3dc57814dcf4111"
AUDIT_REJECTION_REPAIR_PARENT_SOURCE = "dafc1c73658f0c0539068001c22dfbb5ff84b2b1"
AUDIT_REJECTION_REPAIR_PARENT_MANIFEST = "ee9ced2c0cd0957d7f5f3f859c3a7a9fbba34460d039d641ed36fe70569836d8"
AUDIT_REJECTION_REPAIR_PARENT_HELPER = "4da300df4db3263f8c0be728c501f567756d1815b15dbc55d73422d4af864462"
AUDIT_REJECTION_REPAIR_PATHS = frozenset((
    'morphhdl/scripts/check-cdc-successor-source.py',
    'morphhdl/scripts/check-increment-59i-local-enable-source-review.py',
    'morphhdl/scripts/check-increment-59i-pr190-integration.py',
    'morphhdl/scripts/check-increment-59i-production-successor.py',
    'morphhdl/scripts/check-increment-59i-regression-inventory.py',
    'morphhdl/scripts/check-increment-59i-rollout-composition.py',
    'morphhdl/scripts/check-increment-59i-target-integration.py',
    'morphhdl/scripts/check-increment-59i-widening-source-review.py',
    'morphhdl/scripts/check-increment-60b-signedness-authority.py',
    'morphhdl/scripts/check-increment-61-source-review.py',
    'morphhdl/scripts/check-increment-62-wa08-source-overlay.py',
    'morphhdl/scripts/check-pr190-pr189-source-sync.py',
    'morphhdl/scripts/test-increment-59h-inherited-source-scope.py',
    'morphhdl/scripts/test-increment-59i-continuation.py',
    'morphhdl/scripts/test-increment-59i-pr189-sync.py',
    'morphhdl/scripts/test-increment-59i-pr190-integration.py',
    'morphhdl/scripts/test-increment-60f-inherited-source-scope.py',
    'morphhdl/scripts/test-increment-60f-source-budget.py',
    'morphhdl/scripts/test-increment-60f-source-scheduling.py',
    'morphhdl/scripts/test-wa07b-inherited-review.py',
))


def integration_parameters(schema: int) -> tuple[str, str, frozenset]:
    if schema in (5, 6, 7):
        return PR190_TARGET, PR190_COMMON, PR190_RECONCILIATIONS
    if schema in (3, 4):
        return CONTINUATION_TARGET, CONTINUATION_COMMON, CONTINUATION_RECONCILIATIONS
    return INTEGRATION_TARGET, INTEGRATION_BASE, INTEGRATION_RECONCILIATIONS


def previous_certificate(schema: int = 3) -> dict:
    if schema == 7:
        return {"seal_commit": AUDIT_REJECTION_REPAIR_PARENT,
            "seal_tree": AUDIT_REJECTION_REPAIR_PARENT_TREE,
            "source_commit": AUDIT_REJECTION_REPAIR_PARENT_SOURCE,
            "contract_sha256": AUDIT_REJECTION_REPAIR_PARENT_MANIFEST,
            "helper_normalized_sha256": AUDIT_REJECTION_REPAIR_PARENT_HELPER}
    if schema == 6:
        return {"seal_commit": RUNTIME_REPAIR_PARENT, "seal_tree": RUNTIME_REPAIR_PARENT_TREE,
            "source_commit": RUNTIME_REPAIR_PARENT_SOURCE,
            "contract_sha256": RUNTIME_REPAIR_PARENT_MANIFEST,
            "helper_normalized_sha256": RUNTIME_REPAIR_PARENT_HELPER}
    if schema == 5:
        return {"seal_commit": PR190_PARENT, "seal_tree": PR190_PARENT_TREE,
            "source_commit": PR190_PARENT_SOURCE,
            "contract_sha256": PR190_PARENT_MANIFEST,
            "helper_normalized_sha256": PR190_PARENT_HELPER}
    if schema == 4:
        return {"seal_commit": LOCAL_ENABLE_PARENT, "seal_tree": LOCAL_ENABLE_PARENT_TREE,
            "source_commit": LOCAL_ENABLE_PARENT_SOURCE,
            "contract_sha256": LOCAL_ENABLE_PARENT_MANIFEST,
            "helper_normalized_sha256": LOCAL_ENABLE_PARENT_HELPER}
    return {"seal_commit": CONTINUATION_PARENT, "seal_tree": CONTINUATION_PARENT_TREE,
        "source_commit": CONTINUATION_PARENT_SOURCE,
        "contract_sha256": CONTINUATION_PARENT_MANIFEST,
        "helper_normalized_sha256": CONTINUATION_PARENT_HELPER}


def development_checkpoint() -> dict:
    return {"commit": LOCAL_ENABLE_CHECKPOINT, "tree": LOCAL_ENABLE_CHECKPOINT_TREE,
        "parents": [LOCAL_ENABLE_PARENT, LOCAL_ENABLE_PROTOTYPE]}


def pr190_checkpoint() -> dict:
    return {"commit": PR190_CHECKPOINT, "tree": PR190_CHECKPOINT_TREE,
        "parents": [PR190_INTEGRATION_PARENT, PR190_TARGET]}

HELPER = "morphhdl/scripts/check-increment-59i-production-successor.py"
TEST = "morphhdl/scripts/test-increment-59i-production-successor.py"
CONTRACT = "morphhdl/contracts/increment-59i-production-successor.json"
CONTRACT_SHA256 = "df280f1e6f51435e696eab081e3c6c432bd07708f52e13cde0551e1dca55d5ce"
COMPLETION_TODO = "docs/morphhdl/parameterized-verilog-todo.md"
COMPLETION_RECORD = "docs/morphhdl/increment-59i-final-qualification.md"
COMPLETION_ANCHOR = "- [ ] **Increment 59i — Combined Vec/reduction compatibility, proof and publication closure**\n".encode()


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError("59i production successor: " + detail)


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def blob(raw: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()


@functools.lru_cache(maxsize=16)
def normalized_helper(raw: bytes) -> bytes:
    pattern = rb'^CONTRACT_SHA256 = "[^"\n]+"$'
    require(len(re.findall(pattern, raw, re.M)) == 1, "ambiguous helper seal")
    return re.sub(pattern, b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, flags=re.M)


def git(root: Path, *args: str) -> bytes:
    result = subprocess.run(["git", "--literal-pathspecs", *args], cwd=root,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    require(result.returncode == 0, "git " + " ".join(args) + " failed: " +
        result.stderr.decode(errors="replace"))
    return result.stdout


def valid_path(path: object) -> bool:
    return isinstance(path, str) and _valid_path_text(path)


@functools.lru_cache(maxsize=8192)
def _valid_path_text(path: str) -> bool:
    return (bool(path) and Path(path).as_posix() == path and
        not Path(path).is_absolute() and ".." not in Path(path).parts and
        ".git" not in Path(path).parts and "\0" not in path)


@functools.lru_cache(maxsize=8192)
def _relative_ancestors(path: str) -> tuple[str, ...]:
    """Cache lexical paths only, never a filesystem lookup or permission."""
    parts = Path(path).parts
    return tuple(os.path.join(*parts[:n]) for n in range(1, len(parts) + 1))


def regular(root: Path, path: str, mode: str = "100644") -> bytes:
    require(valid_path(path), "invalid path: " + repr(path))
    # lstat every ancestor on every call. Parsing the same relative path is
    # pure; caching stat results, modes, links, or live bytes would not be.
    filename = None
    info = None
    for relative in _relative_ancestors(path):
        filename = os.path.join(root, relative)
        try:
            info = os.lstat(filename)
        except (FileNotFoundError, NotADirectoryError):
            require(False, "missing regular source: " + path)
        require(not stat.S_ISLNK(info.st_mode), "linked source: " + path)
    require(info is not None and stat.S_ISREG(info.st_mode), "missing regular source: " + path)
    require(mode in ("100644", "100755") and
        bool(info.st_mode & 0o111) == (mode == "100755"), "source mode changed: " + path)
    with open(filename, "rb") as stream:
        return stream.read()


@functools.lru_cache(maxsize=128)
def immutable_commit(root: Path, name: str) -> str:
    require(re.fullmatch(r"[0-9a-f]{40}", name) is not None, "mutable commit cache key")
    require(git(root, "rev-parse", name + "^{commit}").decode().strip() == name,
        "anchor is not its exact commit object")
    return name


def revision(root: Path, name: str) -> str:
    if re.fullmatch(r"[0-9a-f]{40}", name) is not None:
        return immutable_commit(root.resolve(), name)
    return git(root, "rev-parse", name + "^{commit}").decode().strip()


@functools.lru_cache(maxsize=128)
def immutable_tree(root: Path, commit: str) -> tuple:
    require(re.fullmatch(r"[0-9a-f]{40}", commit) is not None, "mutable tree cache key")
    entries = {}
    for row in git(root, "ls-tree", "-r", "-z", commit).split(b"\0"):
        if row:
            metadata, path = row.split(b"\t", 1)
            mode, kind, oid = metadata.decode().split()
            name = path.decode()
            require(valid_path(name) and name not in entries, "invalid immutable path inventory")
            require((kind == "blob" and mode in ("100644", "100755")) or
                (kind == "commit" and mode == "160000"), "unsupported immutable mode: " + name)
            entries[name] = (mode, oid)
    return tuple(entries.items())


def tree(root: Path, commit: str) -> dict:
    return dict(immutable_tree(root.resolve(), revision(root, commit)))


@functools.lru_cache(maxsize=32768)
def immutable_source(root: Path, commit: str, path: str) -> bytes | None:
    entry = dict(immutable_tree(root, commit)).get(path)
    if entry is None:
        return None
    require(entry[0] != "160000", "cannot project a gitlink: " + path)
    return git(root, "cat-file", "blob", entry[1])


def frozen(root: Path, commit: str, path: str) -> bytes | None:
    return immutable_source(root.resolve(), revision(root, commit), path)


def changed(root: Path, before: str, after: str) -> set[str]:
    return {item.decode() for item in git(root, "diff", "--no-renames", "--name-only", "-z",
        before, after).split(b"\0") if item}


def validate_contract(value: dict) -> dict:
    keys = {"schema_version", "predecessor", "predecessor_tree", "source_commit",
        "source_tree", "helper_normalized_sha256", "files"}
    require(isinstance(value, dict) and (
        (type(value.get("schema_version")) is int and value["schema_version"] == 1 and set(value) == keys) or
        (type(value.get("schema_version")) is int and value["schema_version"] == 2 and
         set(value) == keys | {"target_integration"}) or
        (type(value.get("schema_version")) is int and value["schema_version"] == 3 and
         set(value) == keys | {"target_integration", "previous_seal"}) or
        (type(value.get("schema_version")) is int and value["schema_version"] == 4 and
         set(value) == keys | {"target_integration", "previous_seal", "development_checkpoint"}) or
        (type(value.get("schema_version")) is int and value["schema_version"] == 5 and
         set(value) == keys | {"target_integration", "previous_seal", "target_checkpoint"}) or
        (type(value.get("schema_version")) is int and value["schema_version"] in (6, 7) and
         set(value) == keys | {"target_integration", "previous_seal"})),
        "invalid manifest schema")
    require(value["predecessor"] == BASE, "immutable predecessor changed")
    if value["schema_version"] in (3, 4, 5, 6, 7):
        require(value["previous_seal"] == previous_certificate(value["schema_version"]),
            "previous source seal identity changed")
    if value["schema_version"] == 4:
        require(value["development_checkpoint"] == development_checkpoint(),
            "development checkpoint identity changed")
    if value["schema_version"] == 5:
        require(value["target_checkpoint"] == pr190_checkpoint(),
            "PR190 checkpoint identity changed")
    if value["schema_version"] >= 2:
        validate_target_integration(value["target_integration"], value["schema_version"])
    for key in ("predecessor_tree", "source_commit", "source_tree"):
        require(isinstance(value[key], str) and re.fullmatch(r"[0-9a-f]{40}", value[key]) is not None,
            "invalid immutable object: " + key)
    require(isinstance(value["helper_normalized_sha256"], str) and
        re.fullmatch(r"[0-9a-f]{64}", value["helper_normalized_sha256"]) is not None,
        "invalid helper hash")
    files = value["files"]
    require(isinstance(files, list) and files, "empty source inventory")
    paths, identifiers = [], set()
    for entry in files:
        require(isinstance(entry, dict) and set(entry) == {"path", "before_mode", "after_mode",
            "before_sha256", "after_sha256", "reason", "edits"}, "invalid reviewed file schema")
        path = entry["path"]
        require(valid_path(path) and path != CONTRACT, "invalid reviewed path: " + repr(path))
        paths.append(path)
        require(isinstance(entry["reason"], str) and entry["reason"].strip(), "missing file reason")
        for side in ("before", "after"):
            mode, sha = entry[side + "_mode"], entry[side + "_sha256"]
            require(mode in (None, "100644", "100755") and ((mode is None and sha is None) or
                (mode is not None and isinstance(sha, str) and
                 re.fullmatch(r"[0-9a-f]{64}", sha) is not None)), "invalid source identity: " + path)
        require(entry["before_mode"] is not None or entry["after_mode"] is not None,
            "empty reviewed file: " + path)
        require((entry["before_mode"], entry["before_sha256"]) !=
            (entry["after_mode"], entry["after_sha256"]), "unchanged reviewed file: " + path)
        edits = entry["edits"]
        require(isinstance(edits, list) and (edits or entry["before_mode"] != entry["after_mode"]),
            "missing reviewed spans: " + path)
        previous_before = previous_after = 0
        for edit in edits:
            require(isinstance(edit, dict) and set(edit) == {"id", "reason", "before_start",
                "before_end", "after_start", "after_end", "before", "after"}, "invalid span schema")
            require(isinstance(edit["id"], str) and edit["id"] and edit["id"] not in identifiers,
                "missing or duplicate span id")
            identifiers.add(edit["id"])
            require(isinstance(edit["reason"], str) and edit["reason"].strip(), "missing span reason")
            for key in ("before_start", "before_end", "after_start", "after_end"):
                require(type(edit[key]) is int and edit[key] >= 0, "invalid UTF-8 byte offset")
            require(isinstance(edit["before"], str) and isinstance(edit["after"], str) and
                edit["before"] != edit["after"], "empty reviewed span")
            require(edit["before_end"] - edit["before_start"] == len(edit["before"].encode()) and
                edit["after_end"] - edit["after_start"] == len(edit["after"].encode()),
                "span text differs from byte offsets")
            require(edit["before_start"] >= previous_before and edit["after_start"] >= previous_after and
                edit["before_start"] - previous_before == edit["after_start"] - previous_after,
                "overlapping or non-corresponding spans")
            previous_before, previous_after = edit["before_end"], edit["after_end"]
    require(paths == sorted(set(paths)) and HELPER in paths and TEST in paths,
        "unordered, duplicate or incomplete source inventory")
    return value


def validate_target_integration(value: dict, schema: int = 2) -> None:
    target_commit, common_base, reconciliations = integration_parameters(schema)
    require(isinstance(value, dict) and set(value) == {"target_commit", "target_tree",
        "common_base", "common_base_tree", "files"}, "invalid target integration schema")
    require(value["target_commit"] == target_commit and value["common_base"] == common_base,
        "target integration anchors changed")
    for key in ("target_tree", "common_base_tree"):
        require(isinstance(value[key], str) and re.fullmatch(r"[0-9a-f]{40}", value[key]) is not None,
            "invalid target integration tree: " + key)
    records = value["files"]
    require(isinstance(records, list) and records, "empty target integration inventory")
    paths, identifiers, reconciled = [], set(), set()
    for entry in records:
        require(isinstance(entry, dict) and set(entry) == {"path", "before_mode", "after_mode",
            "before_sha256", "after_sha256", "reason", "edits"}, "invalid target file schema")
        path = entry["path"]
        require(valid_path(path) and path not in (HELPER, CONTRACT), "invalid target path")
        paths.append(path)
        require(isinstance(entry["reason"], str) and entry["reason"].strip(), "missing target reason")
        for side in ("before", "after"):
            mode, sha = entry[side + "_mode"], entry[side + "_sha256"]
            require(mode in (None, "100644", "100755") and ((mode is None and sha is None) or
                (mode is not None and isinstance(sha, str) and
                 re.fullmatch(r"[0-9a-f]{64}", sha) is not None)), "invalid target source identity: " + path)
        differs = (entry["before_mode"], entry["before_sha256"]) != (entry["after_mode"], entry["after_sha256"])
        edits = entry["edits"]
        require(isinstance(edits, list), "invalid target span list: " + path)
        if not differs:
            require(not edits, "unchanged target source has review spans: " + path)
            continue
        require(path in reconciliations, "unreviewed target-only change: " + path)
        reconciled.add(path)
        require(edits or entry["before_mode"] != entry["after_mode"], "missing target reconciliation spans")
        previous_before = previous_after = 0
        for edit in edits:
            require(isinstance(edit, dict) and set(edit) == {"id", "reason", "before_start",
                "before_end", "after_start", "after_end", "before", "after"}, "invalid target span schema")
            require(isinstance(edit["id"], str) and edit["id"] and edit["id"] not in identifiers,
                "missing or duplicate target span id")
            identifiers.add(edit["id"])
            require(isinstance(edit["reason"], str) and edit["reason"].strip(), "missing target span reason")
            for key in ("before_start", "before_end", "after_start", "after_end"):
                require(type(edit[key]) is int and edit[key] >= 0, "invalid target byte offset")
            require(isinstance(edit["before"], str) and isinstance(edit["after"], str) and
                edit["before"] != edit["after"], "empty target review span")
            require(edit["before_end"] - edit["before_start"] == len(edit["before"].encode()) and
                edit["after_end"] - edit["after_start"] == len(edit["after"].encode()),
                "target span text differs from byte offsets")
            require(edit["before_start"] >= previous_before and edit["after_start"] >= previous_after and
                edit["before_start"] - previous_before == edit["after_start"] - previous_after,
                "overlapping target reconciliation spans")
            previous_before, previous_after = edit["before_end"], edit["after_end"]
    require(paths == sorted(set(paths)), "unordered or duplicate target source inventory")
    require(reconciled == reconciliations, "target reconciliation inventory changed")


def verify_target_integration(root: Path, value: dict) -> None:
    target = value["target_integration"]
    target_commit, common_base, _ = integration_parameters(value["schema_version"])
    scope_parent = CONTINUATION_INTEGRATION_PARENT if value["schema_version"] in (3, 4) else BASE
    if value["schema_version"] in (5, 6, 7):
        scope_parent = PR190_INTEGRATION_PARENT
    if value["schema_version"] in (3, 4):
        git(root, "merge-base", "--is-ancestor", scope_parent, CONTINUATION_PARENT)
        git(root, "merge-base", "--is-ancestor", CONTINUATION_COMMON, CONTINUATION_PARENT)
        git(root, "merge-base", "--is-ancestor", CONTINUATION_COMMON, CONTINUATION_TARGET)
    for key, commit in (("target", target_commit), ("common_base", common_base)):
        require(revision(root, commit) == commit and
            git(root, "rev-parse", commit + "^{tree}").decode().strip() == target[key + "_tree"],
            "immutable target integration tree changed: " + key)
    require(git(root, "merge-base", "--all", scope_parent, target_commit).decode().splitlines() ==
        [common_base], "target refresh common base changed")
    entries = {entry["path"]: entry for entry in target["files"]}
    require(set(entries) == changed(root, common_base, target_commit),
        "complete target integration inventory changed")
    target_tree, source_tree = tree(root, target_commit), tree(root, value["source_commit"])
    for path, entry in entries.items():
        before = frozen(root, target_commit, path)
        after = frozen(root, value["source_commit"], path)
        require(target_tree.get(path, (None,))[0] == entry["before_mode"] and
            source_tree.get(path, (None,))[0] == entry["after_mode"], "target/source mode differs: " + path)
        require((None if before is None else digest(before)) == entry["before_sha256"] and
            (None if after is None else digest(after)) == entry["after_sha256"],
            "immutable target/source bytes differ: " + path)
        if before == after:
            require(not entry["edits"], "unchanged target source has review spans: " + path)
        else:
            restore_reviewed(entry, before or b"", after or b"")


@functools.lru_cache(maxsize=8)
def validated_manifest(raw: bytes, predecessor: str) -> str:
    """Cache only immutable structural validation, returning no mutable authority."""
    try:
        value = validate_contract(json.loads(raw))
    except (ValueError, UnicodeDecodeError) as error:
        raise RuntimeError("59i production successor: invalid manifest JSON") from error
    require(value["predecessor"] == predecessor, "immutable predecessor changed")
    return value["helper_normalized_sha256"]


# Successful immutable (digest, bytes) pairs, not authorization decisions.
# A hit requires equality of ALL freshly read bytes, not size/mtime/inode or
# a previous call's success. Return the original bytes object so its Python
# hash and parsed immutable view can also be reused without hashing megabytes.
_CANONICAL_MANIFESTS = deque(maxlen=8)


def _canonical_manifest(raw: bytes, expected: str) -> bytes:
    for known_digest, known_bytes in _CANONICAL_MANIFESTS:
        if known_digest == expected and raw == known_bytes:
            return known_bytes
    require(digest(raw) == expected, "sealed successor manifest changed")
    _CANONICAL_MANIFESTS.append((expected, raw))
    return raw


def _authenticated_contract_bytes(root: Path) -> bytes:
    require(re.fullmatch(r"[0-9a-f]{64}", CONTRACT_SHA256) is not None,
        "source successor has not been sealed after independent review")
    raw = _canonical_manifest(regular(root, CONTRACT), CONTRACT_SHA256)
    expected_helper = validated_manifest(raw, BASE)
    helper = regular(root, HELPER)
    require(digest(normalized_helper(helper)) == expected_helper,
        "sealed successor helper changed")
    require(re.search(rb'^CONTRACT_SHA256 = "' + CONTRACT_SHA256.encode() + rb'"$', helper, re.M)
        is not None, "helper manifest-hash slot differs")
    return raw


def _freeze_json(value):
    if isinstance(value, dict):
        return MappingProxyType({key: _freeze_json(item) for key, item in value.items()})
    if isinstance(value, list):
        return tuple(_freeze_json(item) for item in value)
    return value


@functools.lru_cache(maxsize=8)
def _immutable_contract_view(raw: bytes):
    # Only parsed immutable data is cached; no checkout authorization is cached.
    return _freeze_json(json.loads(raw))


def authenticate_contract(root: Path) -> None:
    _authenticated_contract_bytes(root)


def _projection_contract(root: Path):
    # Every projection still authenticates current bytes, links, modes and the
    # pinned helper before consulting immutable parsed data. Internal readers
    # cannot mutate it; the public contract() retains its fresh-object API.
    return _immutable_contract_view(_authenticated_contract_bytes(root))


def contract(root: Path) -> dict:
    return json.loads(_authenticated_contract_bytes(root))


def restore_reviewed(entry: dict, before: bytes, after: bytes) -> bytes:
    path = entry["path"]
    require((entry["before_mode"] is None and before == b"") or
        digest(before) == entry["before_sha256"], "immutable before bytes changed: " + path)
    require((entry["after_mode"] is None and after == b"") or
        digest(after) == entry["after_sha256"], "unreviewed successor bytes: " + path)
    previous_before = previous_after = 0
    result = []
    for edit in entry["edits"]:
        a, b = edit["before_start"], edit["before_end"]
        c, d = edit["after_start"], edit["after_end"]
        require(before[a:b] == edit["before"].encode(), "before span differs: " + edit["id"])
        require(after[previous_after:c] == before[previous_before:a],
            "unreviewed bytes outside successor spans: " + path)
        require(after[c:d] == edit["after"].encode(), "after span differs: " + edit["id"])
        result.extend((after[previous_after:c], before[a:b]))
        previous_before, previous_after = b, d
    require(after[previous_after:] == before[previous_before:],
        "unreviewed bytes outside successor spans: " + path)
    result.append(after[previous_after:])
    restored = b"".join(result)
    require(restored == before, "reversal did not restore exact predecessor: " + path)
    return restored


def sealed_helper(root: Path, value: dict) -> bytes:
    source = frozen(root, value["source_commit"], HELPER)
    require(source is not None and source.count(b'CONTRACT_SHA256 = "UNSEALED"\n') == 1,
        "immutable source helper must contain one unsealed placeholder")
    require(digest(normalized_helper(source)) == value["helper_normalized_sha256"],
        "immutable source helper changed")
    return source.replace(b'CONTRACT_SHA256 = "UNSEALED"\n',
        b'CONTRACT_SHA256 = "' + CONTRACT_SHA256.encode() + b'"\n', 1)


def restore_source(root: Path, path: str, source: bytes) -> bytes:
    """Accept only an exact reviewed source or its immutable predecessor view."""
    value = _projection_contract(root)
    if path == CONTRACT:
        require(source in (b"", regular(root, CONTRACT)), "unreviewed seal bytes in projection")
        return b""
    if path in (COMPLETION_TODO, COMPLETION_RECORD):
        before = frozen(root, BASE, path) or b""
        if source == before:
            return source
        current = frozen(root, "HEAD", path) or b""
        after = frozen(root, value["source_commit"], path) or b""
        if source != after:
            require(source == current, "unreviewed completion bytes in projection: " + path)
            completion_tree(root, value, tree(root, "HEAD"), {})
        return before
    entry = next((entry for entry in value["files"] if entry["path"] == path), None)
    if entry is None:
        return source
    before = frozen(root, BASE, path)
    require((None if before is None else digest(before)) == entry["before_sha256"],
        "immutable predecessor projection changed: " + path)
    if source == (before or b""):
        return source
    after = frozen(root, value["source_commit"], path)
    if path == HELPER and source == sealed_helper(root, value):
        source = after
    require(source == (after or b""), "unreviewed bytes cannot enter predecessor projection: " + path)
    return restore_reviewed(entry, before or b"", source)


def completion_tree(root: Path, value: dict, committed: dict, expected: dict,
        commit: str = "HEAD") -> dict:
    """Two documentation exceptions carry no source or proof authority."""
    source = value["source_commit"]
    original = frozen(root, source, COMPLETION_TODO)
    require(original is not None and original.count(COMPLETION_ANCHOR) == 1,
        "immutable source lacks its unique unchecked 59i completion anchor")
    require(tree(root, source).get(COMPLETION_TODO, (None,))[0] == "100644" and
        committed.get(COMPLETION_TODO, (None,))[0] == "100644", "completion TODO mode changed")
    current = frozen(root, commit, COMPLETION_TODO)
    completed = original.replace(COMPLETION_ANCHOR, COMPLETION_ANCHOR.replace(b"[ ]", b"[x]", 1), 1)
    require(current in (original, completed), "completion TODO changes more than the exact 59i checkbox")
    result = dict(expected)
    result[COMPLETION_TODO] = committed[COMPLETION_TODO]
    if COMPLETION_RECORD in committed:
        require(committed[COMPLETION_RECORD][0] == "100644", "qualification record mode changed")
        result[COMPLETION_RECORD] = committed[COMPLETION_RECORD]
    elif COMPLETION_RECORD in expected:
        require(False, "source qualification record was removed")
    return result


@functools.lru_cache(maxsize=8)
def audit_immutable_certificate(root: Path, commit: str, checker: str, expected: str,
        normalized: bool = False, self_test: bool = False) -> None:
    require(re.fullmatch(r"[0-9a-f]{40}", commit) is not None, "mutable certificate anchor")
    raw = frozen(root, commit, checker)
    require(raw is not None and digest(normalized_helper(raw) if normalized else raw) == expected,
        "immutable parent certificate checker changed")
    # Each isolated checkout comes solely from its fixed Git object. No live
    # source, replacement module or generated receipt is injected into it.
    with tempfile.TemporaryDirectory(prefix="59i-parent-certificate-") as directory:
        checkout = Path(directory) / "source"
        git(root, "worktree", "add", "--detach", str(checkout), commit)
        try:
            require(regular(checkout, checker) == raw, "parent checkout checker differs")
            command = [sys.executable, "-B", checker]
            if self_test:
                command.append("--self-test")
            result = subprocess.run(command, cwd=checkout,
                env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"),
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=300)
            require(result.returncode == 0, "original immutable certificate rejected: " +
                result.stdout.decode(errors="replace"))
            require(tree(checkout, "HEAD") == tree(root, commit), "parent audit changed HEAD")
            verify_checkout(checkout, tree(root, commit))
        finally:
            git(root, "worktree", "remove", "--force", str(checkout))


def verify_previous_certificate(root: Path, value: dict) -> None:
    certificate = previous_certificate(value["schema_version"])
    require(value["previous_seal"] == certificate, "previous source seal identity changed")
    parent = certificate["seal_commit"]
    require(git(root, "rev-parse", parent + "^{tree}").decode().strip() ==
        certificate["seal_tree"], "previous seal tree changed")
    require(git(root, "rev-list", "--parents", "-n", "1", parent).decode().split() ==
        [parent, certificate["source_commit"]], "previous seal topology changed")
    raw = frozen(root, parent, CONTRACT)
    require(raw is not None and digest(raw) == certificate["contract_sha256"],
        "previous immutable manifest changed")
    require(frozen(root, value["source_commit"], CONTRACT) == raw,
        "unsealed continuation must preserve its predecessor certificate bytes")
    require(CONTRACT not in tree(root, integration_parameters(value["schema_version"])[0]),
        "target carries an unrelated 59i seal")
    audit_immutable_certificate(root, parent, HELPER,
        certificate["helper_normalized_sha256"], normalized=True)
    certificate = frozen(root, CONTINUATION_TARGET, "morphhdl/contracts/increment-61-source-review.json")
    require(certificate is not None and digest(certificate) == CONTINUATION_61_CONTRACT,
        "immutable Increment 61 contract changed")
    require(json.loads(certificate)["integrated_target_commit"] == CONTINUATION_61_BASE,
        "immutable Increment 61 predecessor changed")
    audit_immutable_certificate(root, CONTINUATION_TARGET,
        "morphhdl/scripts/check-increment-61-source-review.py", CONTINUATION_61_HELPER)
    if value["schema_version"] in (5, 6, 7):
        audit_immutable_certificate(root, PR190_TARGET,
            "morphhdl/scripts/check-pr190-pr189-source-sync.py", PR190_TARGET_CHECKER)


def verify_runtime_repair_history(root: Path, value: dict) -> None:
    """Admit only the exact direct successor of the published schema-5 seal."""
    source = value["source_commit"]
    require(git(root, "rev-list", "--parents", "-n", "1", source).decode().split() ==
        [source, RUNTIME_REPAIR_PARENT],
        "runtime repair source must be one direct child of the published seal")
    require(git(root, "rev-parse", RUNTIME_REPAIR_PARENT + "^{tree}").decode().strip() ==
        RUNTIME_REPAIR_PARENT_TREE, "runtime repair parent tree changed")
    require(changed(root, RUNTIME_REPAIR_PARENT, source) == RUNTIME_REPAIR_PATHS,
        "runtime repair changed files outside the closed reviewed set")
    require(tree(root, source).get(CONTRACT) == tree(root, RUNTIME_REPAIR_PARENT).get(CONTRACT),
        "runtime repair source changed the preserved predecessor certificate")


def verify_audit_rejection_repair_history(root: Path, value: dict) -> None:
    """Admit only the exact direct audit successor of the schema-6 seal."""
    source = value["source_commit"]
    require(git(root, "rev-list", "--parents", "-n", "1", source).decode().split() ==
        [source, AUDIT_REJECTION_REPAIR_PARENT],
        "audit rejection repair source must be one direct child of the published seal")
    require(git(root, "rev-parse", AUDIT_REJECTION_REPAIR_PARENT + "^{tree}").decode().strip() ==
        AUDIT_REJECTION_REPAIR_PARENT_TREE, "audit rejection repair parent tree changed")
    require(changed(root, AUDIT_REJECTION_REPAIR_PARENT, source) == AUDIT_REJECTION_REPAIR_PATHS,
        "audit rejection repair changed files outside the closed reviewed set")
    require(tree(root, source).get(CONTRACT) == tree(root, AUDIT_REJECTION_REPAIR_PARENT).get(CONTRACT),
        "audit rejection repair source changed the preserved predecessor certificate")


def verify_pr190_documentation_checkpoint(root: Path) -> None:
    """Reconstruct the exact documentation merge without granting doc exceptions."""
    for commit, expected in (
            (PR190_DOCUMENTATION_TARGET, PR190_DOCUMENTATION_TARGET_TREE),
            (PR190_DOCUMENTATION_CHECKPOINT, PR190_DOCUMENTATION_CHECKPOINT_TREE)):
        require(git(root, "rev-parse", commit + "^{tree}").decode().strip() == expected,
            "PR190 documentation immutable tree changed")
    require(git(root, "rev-list", "--parents", "-n", "1",
        PR190_DOCUMENTATION_CHECKPOINT).decode().split() ==
        [PR190_DOCUMENTATION_CHECKPOINT, PR190_PARENT, PR190_DOCUMENTATION_TARGET],
        "PR190 documentation checkpoint topology changed")
    require(git(root, "merge-base", "--all", PR190_PARENT,
        PR190_DOCUMENTATION_TARGET).decode().splitlines() == [PR190_TARGET],
        "PR190 documentation merge base changed")
    require(changed(root, PR190_TARGET, PR190_DOCUMENTATION_TARGET) == PR190_DOCUMENTATION_PATHS,
        "PR190 documentation target changed outside the exact roadmaps")
    require(changed(root, PR190_PARENT, PR190_DOCUMENTATION_CHECKPOINT) == PR190_DOCUMENTATION_PATHS,
        "PR190 documentation checkpoint changed outside the exact roadmaps")
    expected_tree = dict(tree(root, PR190_PARENT))
    for path in sorted(PR190_DOCUMENTATION_PATHS):
        with tempfile.TemporaryDirectory(prefix="59i-pr190-doc-merge-") as directory:
            files = []
            for ref, name in ((PR190_PARENT, "left"), (PR190_TARGET, "base"),
                    (PR190_DOCUMENTATION_TARGET, "target")):
                require(tree(root, ref).get(path, (None,))[0] == "100644",
                    "PR190 documentation source mode changed: " + path)
                file = Path(directory) / name
                file.write_bytes(frozen(root, ref, path))
                files.append(str(file))
            result = subprocess.run(["git", "merge-file", "-p", *files],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
            require(result.returncode == 0, "PR190 documentation merge conflict: " + path)
            expected_tree[path] = ("100644", blob(result.stdout))
    require(tree(root, PR190_DOCUMENTATION_CHECKPOINT) == expected_tree,
        "PR190 documentation checkpoint is not the exact parent merge")


def verify_pr190_development_history(root: Path, value: dict) -> None:
    """Bind the exact merge and reject runtime drift even if later restored."""
    require(value["target_checkpoint"] == pr190_checkpoint(), "PR190 checkpoint identity changed")
    for commit, expected in ((PR190_CHECKPOINT, PR190_CHECKPOINT_TREE),
            (PR190_TARGET, PR190_TARGET_TREE)):
        require(git(root, "rev-parse", commit + "^{tree}").decode().strip() == expected,
            "PR190 immutable tree changed")
    require(git(root, "rev-list", "--parents", "-n", "1", PR190_CHECKPOINT).decode().split() ==
        [PR190_CHECKPOINT, PR190_INTEGRATION_PARENT, PR190_TARGET], "PR190 checkpoint topology changed")
    require(git(root, "merge-base", "--all", PR190_INTEGRATION_PARENT, PR190_TARGET).decode().splitlines() ==
        [PR190_COMMON], "PR190 merge base changed")
    verify_pr190_documentation_checkpoint(root)
    git(root, "merge-base", "--is-ancestor", PR190_DOCUMENTATION_CHECKPOINT, value["source_commit"])
    original = tree(root, PR190_PARENT).get(CONTRACT)
    current = value["source_commit"]
    while True:
        require(tree(root, current).get(CONTRACT) == original,
            "PR190 development changed the preserved source certificate")
        require(changed(root, PR190_DOCUMENTATION_CHECKPOINT, current) <= PR190_AUDIT_PATHS,
            "PR190 review descendant changed runtime or unlisted audit source")
        if current == PR190_DOCUMENTATION_CHECKPOINT:
            break
        parents = git(root, "rev-list", "--parents", "-n", "1", current).decode().split()
        require(len(parents) == 2 and parents[0] == current,
            "PR190 source review must be a linear audit-only descendant")
        current = parents[1]


def verify_development_history(root: Path, value: dict) -> None:
    """Preserve the saved merge and its certificate throughout source development."""
    require(value["development_checkpoint"] == development_checkpoint(),
        "development checkpoint identity changed")
    checkpoint = LOCAL_ENABLE_CHECKPOINT
    require(git(root, "rev-parse", checkpoint + "^{tree}").decode().strip() ==
        LOCAL_ENABLE_CHECKPOINT_TREE, "development checkpoint tree changed")
    require(git(root, "rev-list", "--parents", "-n", "1", checkpoint).decode().split() ==
        [checkpoint, LOCAL_ENABLE_PARENT, LOCAL_ENABLE_PROTOTYPE],
        "development checkpoint topology changed")
    git(root, "merge-base", "--is-ancestor", checkpoint, value["source_commit"])
    original = tree(root, LOCAL_ENABLE_PARENT).get(CONTRACT)
    require(original is not None, "published predecessor certificate is missing")
    current = value["source_commit"]
    while True:
        require(tree(root, current).get(CONTRACT) == original,
            "development history changed its published predecessor certificate")
        if current == checkpoint:
            break
        parents = git(root, "rev-list", "--parents", "-n", "1", current).decode().split()
        require(len(parents) == 2 and parents[0] == current,
            "development source must be a linear descendant of the saved checkpoint")
        current = parents[1]


def verify_seal_history(root: Path, value: dict, head: str, expected: dict) -> None:
    source = value["source_commit"]
    parents = git(root, "rev-list", "--parents", "-n", "1", source).decode().split()
    if value["schema_version"] == 1:
        require(parents == [source, BASE], "source must be one direct child of the immutable predecessor")
    elif value["schema_version"] == 2:
        require(parents == [source, BASE, INTEGRATION_TARGET],
            "source must join the exact predecessor and reviewed target in order")
        verify_target_integration(root, value)
    elif value["schema_version"] == 3:
        require(parents == [source, CONTINUATION_PARENT, CONTINUATION_TARGET],
            "continuation must join the published seal and exact reviewed target in order")
        verify_previous_certificate(root, value)
        verify_target_integration(root, value)
    elif value["schema_version"] == 4:
        verify_development_history(root, value)
        verify_previous_certificate(root, value)
        verify_target_integration(root, value)
    elif value["schema_version"] == 5:
        verify_pr190_development_history(root, value)
        verify_previous_certificate(root, value)
        verify_target_integration(root, value)
    elif value["schema_version"] == 6:
        verify_runtime_repair_history(root, value)
        verify_previous_certificate(root, value)
        verify_target_integration(root, value)
    else:
        require(value["schema_version"] == 7, "unsupported seal lifecycle")
        verify_audit_rejection_repair_history(root, value)
        verify_previous_certificate(root, value)
        verify_target_integration(root, value)
    if value["schema_version"] not in (3, 4, 5, 6, 7):
        require(not git(root, "rev-list", "--full-history", BASE + ".." + source, "--", CONTRACT),
            "source history already contains a successor seal")
    # A normal GitHub integration merge places the reviewed feature in its
    # second parent. Schema 1 requires its historical target to be an ancestor
    # of BASE. Schema 2 additionally admits the exact authenticated target
    # refresh and its ancestors. In either case the merge is tree-preserving;
    # no source change or newer target is authorized by this lifecycle rule.
    route = []
    integration = None
    current = head
    while current != source:
        ancestry = git(root, "rev-list", "--parents", "-n", "1", current).decode().split()
        require(ancestry[0] == current and len(ancestry) in (2, 3),
            "sealed route must be linear except for one two-parent integration merge")
        route.append(current)
        if len(ancestry) == 3:
            require(integration is None, "sealed route contains more than one integration merge")
            target, feature = ancestry[1:]
            try:
                target_ceiling = (PR190_DOCUMENTATION_TARGET if value["schema_version"] in (5, 6, 7) else
                    integration_parameters(value["schema_version"])[0] if value["schema_version"] >= 2 else BASE)
                git(root, "merge-base", "--is-ancestor", target, target_ceiling)
            except RuntimeError as error:
                boundary = "reviewed target refresh" if value["schema_version"] >= 2 else "immutable predecessor"
                raise RuntimeError("59i production successor: integration target is not an ancestor "
                    "of the " + boundary) from error
            integration = (current, feature)
            current = feature
        else:
            current = ancestry[1]
    require(bool(route), "immutable first seal is missing")
    seal = route[-1]
    require(git(root, "rev-list", "--parents", "-n", "1", seal).decode().split() == [seal, source],
        "first seal must be one direct child of the immutable source")
    require(tree(root, seal) == expected and changed(root, source, seal) == {HELPER, CONTRACT},
        "first seal differs from immutable source plus exact seal")
    history_base = source if value["schema_version"] in (3, 4, 5, 6, 7) else BASE
    history = git(root, "rev-list", "--full-history", history_base + ".." + head, "--", CONTRACT).decode().splitlines()
    require(bool(history), "immutable first seal is missing")
    for commit in history:
        require(tree(root, commit).get(CONTRACT) == expected[CONTRACT],
            "successor contract history differs from its immutable first seal")
        git(root, "merge-base", "--is-ancestor", seal, commit)
    if integration is not None:
        merged, feature = integration
        require(tree(root, merged) == tree(root, feature),
            "integration merge changed the reviewed feature tree")
    for commit in route:
        committed = tree(root, commit)
        require(committed == completion_tree(root, value, committed, expected, commit),
            "sealed route tree differs from immutable source plus exact seal")


def verify_checkout(root: Path, committed: dict, display_prefix: str = "", nested: bool = False) -> None:
    """Check actual tracked bytes, including every initialized gitlink recursively."""
    head = revision(root, "HEAD")
    indexed = {}
    for row in git(root, "ls-files", "--stage", "-z").split(b"\0"):
        if row:
            metadata, path = row.split(b"\t", 1)
            mode, oid, stage = metadata.decode().split()
            name = path.decode()
            require(stage == "0" and name not in indexed, "unmerged or duplicate index")
            indexed[name] = (mode, oid)
    require(indexed == committed, "HEAD/index identity differs" +
        (": " + display_prefix.rstrip("/") if display_prefix else ""))
    gitlinks = {path for path, entry in committed.items() if entry[0] == "160000"}
    for path, (mode, oid) in committed.items():
        if mode == "160000":
            directory = root / path
            parts = Path(path).parts
            require(all(not (root / Path(*parts[:n])).is_symlink() for n in range(1, len(parts) + 1)),
                "linked submodule: " + display_prefix + path)
            if (directory / ".git").exists():
                require(revision(directory, "HEAD") == oid, "submodule revision changed: " + display_prefix + path)
                verify_checkout(directory, tree(directory, oid), display_prefix + path + "/", nested=True)
            else:
                require(not directory.exists() or not any(directory.iterdir()),
                    "uninitialized submodule contains content: " + display_prefix + path)
        else:
            require(blob(regular(root, path, mode)) == oid,
                "HEAD/index/worktree identity differs: " + display_prefix + path)
    dirty = set()
    for args in (("diff", "--name-only", "-z"), ("diff", "--cached", "--name-only", "-z", head),
            ("ls-files", "--others", "--exclude-standard", "-z")):
        dirty |= {path.decode() for path in git(root, *args).split(b"\0") if path}
    require(not dirty, "staged, unstaged or untracked content: " + repr(sorted(dirty)))
    prefixes = {path.split("/src/", 1)[0] + "/src/" + path.split("/src/", 1)[1].split("/", 1)[0]
        for path in committed if "/src/main/" in path or "/src/test/" in path}
    prefixes |= {"morphhdl/scripts", "morphhdl/contracts", "morphhdl-passes/scripts",
        "morphhdl-passes/tests", "morphhdl-passes/examples", ".github/workflows"}
    if nested:
        prefixes = {"."}
    for prefix in sorted(prefixes):
        directory = root / prefix
        require(not directory.is_symlink(), "linked source inventory: " + display_prefix + prefix)
        for current, dirs, files in os.walk(directory, followlinks=False):
            for name in dirs + files:
                file = Path(current) / name
                path = file.relative_to(root).as_posix()
                if (nested and (path == ".git" or path.startswith(".git/"))) or any(
                        path == link or path.startswith(link + "/") for link in gitlinks):
                    continue
                require(not file.is_symlink(), "linked source inventory: " + display_prefix + path)
                if file.is_file():
                    require(path in committed,
                        "untracked or ignored source addition: " + display_prefix + path)
            dirs[:] = [name for name in dirs if not (nested and name == ".git") and
                (Path(current) / name).relative_to(root).as_posix() not in gitlinks]
    require(revision(root, "HEAD") == head, "HEAD changed during checkout verification")


def verify(root: Path) -> dict:
    root = root.resolve()
    value = contract(root)
    head = revision(root, "HEAD")
    source = value["source_commit"]
    require(source != BASE and revision(root, BASE) == BASE and revision(root, source) == source,
        "source and predecessor must be distinct exact commits")
    git(root, "merge-base", "--is-ancestor", BASE, source)
    git(root, "merge-base", "--is-ancestor", source, head)
    for key, commit in (("predecessor", BASE), ("source", source)):
        require(git(root, "rev-parse", commit + "^{tree}").decode().strip() == value[key + "_tree"],
            "immutable " + key + " tree changed")
    before_tree, source_tree, committed = tree(root, BASE), tree(root, source), tree(root, head)
    require(CONTRACT not in before_tree and (value["schema_version"] in (3, 4, 5, 6, 7) or CONTRACT not in source_tree),
        "source or predecessor already contains successor seal")
    require(source_tree.get(HELPER, (None,))[0] == "100644",
        "immutable source helper must be regular and non-executable")
    records = {entry["path"]: entry for entry in value["files"]}
    source_delta = changed(root, BASE, source)
    if value["schema_version"] in (3, 4, 5, 6, 7):
        require(CONTRACT in source_delta, "continuation lost its prior certificate")
        source_delta.remove(CONTRACT)
    require(set(records) == source_delta, "complete successor delta inventory changed")
    for path, entry in records.items():
        for side, revision_tree, commit in (("before", before_tree, BASE),
                ("after", source_tree, source)):
            require(revision_tree.get(path, (None,))[0] == entry[side + "_mode"],
                "immutable " + side + " mode changed: " + path)
            raw = frozen(root, commit, path)
            require((None if raw is None else digest(raw)) == entry[side + "_sha256"],
                "immutable " + side + " source changed: " + path)
        restore_reviewed(entry, frozen(root, BASE, path) or b"", frozen(root, source, path) or b"")
    expected = dict(source_tree)
    expected[HELPER] = ("100644", blob(sealed_helper(root, value)))
    expected[CONTRACT] = ("100644", blob(regular(root, CONTRACT)))
    verify_seal_history(root, value, head, expected)
    permitted = completion_tree(root, value, committed, expected)
    require(committed == permitted, "current tree differs from immutable source plus exact seal")
    verify_checkout(root, committed)
    require(revision(root, "HEAD") == head, "HEAD changed during verification")
    return value


def predecessor_inventory(root: Path, paths: set[str], qualification_base: str,
        full: bool = False) -> set[str]:
    entries = {entry["path"] for entry in verify(root)["files"]} | {
        CONTRACT, COMPLETION_TODO, COMPLETION_RECORD}
    current = changed(root, qualification_base, "HEAD")
    previous = changed(root, qualification_base, BASE)
    domain = entries if full else {path for path in entries if re.search(r"(?:^|/)src/main/", path)}
    visible = (entries & set(paths)) | (domain - current)
    return (set(paths) - entries) | (previous & visible)


def target_anchor(root: Path) -> str | None:
    schema = _projection_contract(root)["schema_version"]
    return integration_parameters(schema)[0] if schema >= 2 else None


def target_source(root: Path, path: str, source: bytes) -> bytes:
    """Project only authenticated current bytes to the pinned refreshed target."""
    value = _projection_contract(root)
    require(value["schema_version"] >= 2, "target refresh projection requires schema 2, 3 or 4")
    before = frozen(root, integration_parameters(value["schema_version"])[0], path) or b""
    if source == before:
        return before
    if path in (COMPLETION_TODO, COMPLETION_RECORD):
        current = frozen(root, "HEAD", path) or b""
        require(source == current, "unreviewed completion bytes in target projection: " + path)
        completion_tree(root, value, tree(root, "HEAD"), {})
        return before
    after = frozen(root, value["source_commit"], path) or b""
    if path == HELPER:
        after = sealed_helper(root, value)
    elif path == CONTRACT:
        after = regular(root, CONTRACT)
    require(source == after, "unreviewed bytes cannot enter target refresh projection: " + path)
    return before


def target_inventory(root: Path, paths: set[str], qualification_base: str,
        full: bool = False) -> set[str]:
    value = verify(root)
    require(value["schema_version"] >= 2, "target refresh inventory requires schema 2, 3 or 4")
    target_commit = integration_parameters(value["schema_version"])[0]
    entries = changed(root, target_commit, value["source_commit"]) | {
        HELPER, CONTRACT, COMPLETION_TODO, COMPLETION_RECORD}
    current = changed(root, qualification_base, "HEAD")
    previous = changed(root, qualification_base, target_commit)
    domain = entries if full else {path for path in entries if re.search(r"(?:^|/)src/main/", path)}
    visible = (entries & set(paths)) | (domain - current)
    return (set(paths) - entries) | (previous & visible)



def increment61_predecessor_source(root: Path, path: str, source: bytes) -> bytes:
    """Compose only the authenticated 61 target view into its frozen predecessor.

    This is the historical WA-08 audit view, never the source compiled by CI.
    The complete verify() authenticates both certificates and the live tree;
    each projection additionally authenticates its manifest and exact input.
    """
    schema = _projection_contract(root)["schema_version"]
    require(schema in (3, 4, 5, 6, 7), "Increment 61 predecessor requires schema 3, 4, 5, 6 or 7")
    current = frozen(root, integration_parameters(schema)[0], path) or b""
    previous = frozen(root, CONTINUATION_61_BASE, path) or b""
    require(source in (current, previous), "unreviewed bytes cannot enter Increment 61 predecessor projection: " + path)
    return previous


def increment61_predecessor_inventory(root: Path, paths: set[str], qualification_base: str,
        full: bool = False) -> set[str]:
    schema = verify(root)["schema_version"]
    require(schema in (3, 4, 5, 6, 7), "Increment 61 predecessor requires schema 3, 4, 5, 6 or 7")
    target = integration_parameters(schema)[0]
    entries = changed(root, CONTINUATION_61_BASE, target)
    current = changed(root, qualification_base, target)
    previous = changed(root, qualification_base, CONTINUATION_61_BASE)
    domain = entries if full else {path for path in entries if re.search(r"(?:^|/)src/main/", path)}
    visible = (entries & set(paths)) | (domain - current)
    return (set(paths) - entries) | (previous & visible)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    value = verify(args.repo_root)
    print("59I_PRODUCTION_SUCCESSOR_PASS files=" + str(len(value["files"])))


if __name__ == "__main__":
    main()
