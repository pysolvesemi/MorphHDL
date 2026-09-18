#!/usr/bin/env python3
"""Authenticate a separately sealed source successor before historical reviews.

Historical schemas retain their original absent-manifest lifecycle. Schema 3
retains the published predecessor certificate, authenticates its immutable source
and the integrated target in isolated Git checkouts, then binds a separately
reviewed cumulative source manifest. Every new seal changes only that manifest
and this module's one manifest-hash placeholder. No result is RTL qualification.
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
CONTINUATION_PARENT = "c74b34bb1154d1df20bf85aa63e1276388511a02"
CONTINUATION_PARENT_TREE = "3e29a314d85c6f708fdd78eaeb68098ff136510c"
CONTINUATION_PARENT_SOURCE = "106fa49a30e3b8513a6b6038b929250305360f8a"
CONTINUATION_PARENT_MANIFEST = "c009edae6cbae02695ed834579eaa62300e434e749821ad8af1ced692352d18c"
CONTINUATION_PARENT_HELPER = "c9be9212f628384ac6c30d59c2382009ce681da40c514f4592e1b1a60ae790a7"
CONTINUATION_TARGET = "27af65abbee0d2334d6be7a6e4e2408b8af32fd9"
# Keep the original integrated target inventory rooted at the same two
# historical branches. The immediate predecessor now already contains that
# integration; using its merge-base would incorrectly erase the 81 target
# records. Both fixed ancestors are independently authenticated below.
CONTINUATION_INTEGRATION_PARENT = "f43100e4899593eb5c9e78537a4dcf6f53f9c30f"
CONTINUATION_COMMON = "61d1fe0dcac0b52856620944a2d7426fd1390a48"
CONTINUATION_61_BASE = "7f355a859e7e88ca343e1ff82f261fb47b3311d0"
CONTINUATION_61_CONTRACT = "67f19808ea90ef6b8ef2b17f9dedb98f7ea40dbcdd57df172604e0da17faa972"
CONTINUATION_61_HELPER = "0b095ea126c5f7d844db6338a1811fc0bce290b82e17ded71d9aaac7dd84443e"
CONTINUATION_RECONCILIATIONS = frozenset((
    '.github/workflows/independent-parameter-domains.yml',
    'repro/independent-parameters/test_qualify.py',
    '.github/workflows/increment-59c-named-field-vectors.yml',
    '.github/workflows/increment-59g-register-bridges.yml',
    '.github/workflows/increment-60c-signed-declarations.yml',
    '.github/workflows/increment-60d-pure-sint-casts.yml',
    '.github/workflows/increment-60e-signedness-boundaries.yml',
    '.github/workflows/increment-60f-equivalence-closure.yml',
    '.github/workflows/increment-60g-default-signed-verilog.yml',
    '.github/workflows/increment-62-wa08-source-overlay.yml',
    'core/src/main/scala/spinal/core/ElabInt.scala',
    'morphhdl/contracts/increment-55-native-change-review.json',
    'morphhdl/contracts/native-source-preservation.json',
    'morphhdl/scripts/check-increment-61-source-review.py',
    'morphhdl/scripts/check-increment-62-wa08-source-overlay.py',
    'morphhdl/scripts/test-increment-59g-source-review.py',
    'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala',
    'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala',
    'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala',
    'morphruntime/src/main/scala/spinal/core/ElabFormalComponent.scala',
    'morphruntime/src/main/scala/spinal/core/ExternalFormalParameterRegistry.scala',
))


def integration_parameters(schema: int) -> tuple[str, str, frozenset]:
    if schema == 3:
        return CONTINUATION_TARGET, CONTINUATION_COMMON, CONTINUATION_RECONCILIATIONS
    return INTEGRATION_TARGET, INTEGRATION_BASE, INTEGRATION_RECONCILIATIONS


def previous_certificate() -> dict:
    return {"seal_commit": CONTINUATION_PARENT, "seal_tree": CONTINUATION_PARENT_TREE,
        "source_commit": CONTINUATION_PARENT_SOURCE,
        "contract_sha256": CONTINUATION_PARENT_MANIFEST,
        "helper_normalized_sha256": CONTINUATION_PARENT_HELPER}

HELPER = "morphhdl/scripts/check-increment-59i-production-successor.py"
TEST = "morphhdl/scripts/test-increment-59i-production-successor.py"
CONTRACT = "morphhdl/contracts/increment-59i-production-successor.json"
CONTRACT_SHA256 = "ba5181c48a6f6173daa139ce6206bb67b956431165d2593ba8ef98209a4bb3c0"
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
         set(value) == keys | {"target_integration", "previous_seal"})), "invalid manifest schema")
    require(value["predecessor"] == BASE, "immutable predecessor changed")
    if value["schema_version"] == 3:
        require(value["previous_seal"] == previous_certificate(), "previous source seal identity changed")
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
    scope_parent = CONTINUATION_INTEGRATION_PARENT if value["schema_version"] == 3 else BASE
    if value["schema_version"] == 3:
        git(root, "merge-base", "--is-ancestor", scope_parent, CONTINUATION_PARENT)
        git(root, "merge-base", "--is-ancestor", CONTINUATION_TARGET, CONTINUATION_PARENT)
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
    require(value["previous_seal"] == previous_certificate(), "previous source seal identity changed")
    require(git(root, "rev-parse", CONTINUATION_PARENT + "^{tree}").decode().strip() ==
        CONTINUATION_PARENT_TREE, "previous seal tree changed")
    require(git(root, "rev-list", "--parents", "-n", "1", CONTINUATION_PARENT).decode().split() ==
        [CONTINUATION_PARENT, CONTINUATION_PARENT_SOURCE], "previous seal topology changed")
    raw = frozen(root, CONTINUATION_PARENT, CONTRACT)
    require(raw is not None and digest(raw) == CONTINUATION_PARENT_MANIFEST,
        "previous immutable manifest changed")
    require(frozen(root, value["source_commit"], CONTRACT) == raw,
        "unsealed continuation must preserve its predecessor certificate bytes")
    require(CONTRACT not in tree(root, CONTINUATION_TARGET), "target carries an unrelated 59i seal")
    audit_immutable_certificate(root, CONTINUATION_PARENT, HELPER,
        CONTINUATION_PARENT_HELPER, normalized=True)
    certificate = frozen(root, CONTINUATION_TARGET, "morphhdl/contracts/increment-61-source-review.json")
    require(certificate is not None and digest(certificate) == CONTINUATION_61_CONTRACT,
        "immutable Increment 61 contract changed")
    require(json.loads(certificate)["integrated_target_commit"] == CONTINUATION_61_BASE,
        "immutable Increment 61 predecessor changed")
    audit_immutable_certificate(root, CONTINUATION_TARGET,
        "morphhdl/scripts/check-increment-61-source-review.py", CONTINUATION_61_HELPER)


def verify_seal_history(root: Path, value: dict, head: str, expected: dict) -> None:
    source = value["source_commit"]
    parents = git(root, "rev-list", "--parents", "-n", "1", source).decode().split()
    if value["schema_version"] == 1:
        require(parents == [source, BASE], "source must be one direct child of the immutable predecessor")
    elif value["schema_version"] == 2:
        require(parents == [source, BASE, INTEGRATION_TARGET],
            "source must join the exact predecessor and reviewed target in order")
        verify_target_integration(root, value)
    else:
        require(parents == [source, CONTINUATION_PARENT, CONTINUATION_TARGET],
            "continuation must join the published seal and exact reviewed target in order")
        verify_previous_certificate(root, value)
        verify_target_integration(root, value)
    if value["schema_version"] != 3:
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
                target_ceiling = integration_parameters(value["schema_version"])[0] if value["schema_version"] >= 2 else BASE
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
    history_base = source if value["schema_version"] == 3 else BASE
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
    require(CONTRACT not in before_tree and (value["schema_version"] == 3 or CONTRACT not in source_tree),
        "source or predecessor already contains successor seal")
    require(source_tree.get(HELPER, (None,))[0] == "100644",
        "immutable source helper must be regular and non-executable")
    records = {entry["path"]: entry for entry in value["files"]}
    source_delta = changed(root, BASE, source)
    if value["schema_version"] == 3:
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
    require(value["schema_version"] >= 2, "target refresh projection requires schema 2 or 3")
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
    require(value["schema_version"] >= 2, "target refresh inventory requires schema 2 or 3")
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
    require(_projection_contract(root)["schema_version"] == 3, "Increment 61 predecessor requires schema 3")
    current = frozen(root, CONTINUATION_TARGET, path) or b""
    previous = frozen(root, CONTINUATION_61_BASE, path) or b""
    require(source in (current, previous), "unreviewed bytes cannot enter Increment 61 predecessor projection: " + path)
    return previous


def increment61_predecessor_inventory(root: Path, paths: set[str], qualification_base: str,
        full: bool = False) -> set[str]:
    require(verify(root)["schema_version"] == 3, "Increment 61 predecessor requires schema 3")
    entries = changed(root, CONTINUATION_61_BASE, CONTINUATION_TARGET)
    current = changed(root, qualification_base, CONTINUATION_TARGET)
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
