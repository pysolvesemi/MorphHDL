#!/usr/bin/env python3
"""Prepare an exact bidirectional 59i/60g source-review composition.

The current draft branch has two independently reviewed parents: the 59i feature
history and the current parameterized-verilog rollout.  Their checker edits can
coexist in one merged file, so neither parent's whole-file restorer can safely
pretend the other parent is absent.  This generator records the exact merged
bytes and adds two views:

* feature_view: exact merged bytes -> exact first-parent bytes for old 59i audits
* target_view:  exact merged bytes -> exact second-parent bytes for 60g audits

Every mapped path is hash-bound, and the real HEAD/index/worktree identity is
still checked.  No production path or changed byte is admitted by name alone.
"""
from __future__ import annotations

import hashlib
import json
import os
import stat
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMMON = "64e8fddc432e859b6b532540bee96c5608d46efa"
FEATURE = "ee2e7f2613e54f23158ce1eacae6028409a91ed6"
TARGET = "2ebaa2ef5561eab35aa0ba9caced5c5a314d59f6"

PARENT = ROOT / "morphhdl/scripts/check-increment-59i-source-review.py"
WIDENING = ROOT / "morphhdl/scripts/check-increment-59i-widening-source-review.py"
ROLLOUT = ROOT / "morphhdl/scripts/check-increment-60g-source-scope.py"
PROMOTER = ROOT / "morphhdl/repair-59i-composite-local-enable/promote-reviewed.py"
PROMOTER_CI = ROOT / "morphhdl/repair-59i-composite-local-enable/promote-reviewed-ci.py"
PATCHED = tuple(path.relative_to(ROOT).as_posix() for path in
                (PARENT, WIDENING, ROLLOUT, PROMOTER, PROMOTER_CI))

CONTRACT = ROOT / "morphhdl/contracts/increment-59i-rollout-composition.json"
CHECKER = ROOT / "morphhdl/scripts/check-increment-59i-rollout-composition.py"
CHECKER_TEST = ROOT / "morphhdl/scripts/test-increment-59i-rollout-composition.py"
DOC = ROOT / "docs/morphhdl/increment-59i-rollout-composition.md"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def run(*args: str, check: bool = True) -> subprocess.CompletedProcess[bytes]:
    result = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False)
    if check and result.returncode != 0:
        raise RuntimeError("command failed: " + " ".join(args) + "\n" +
                           result.stderr.decode(errors="replace"))
    return result


def output(*args: str) -> str:
    return run(*args).stdout.decode().strip()


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def replace_once(text: str, before: str, after: str, label: str) -> str:
    require(text.count(before) == 1,
            label + " anchor count changed: " + str(text.count(before)))
    return text.replace(before, after, 1)


def changed(older: str, newer: str) -> set[str]:
    raw = run("git", "diff", "--no-renames", "--name-only", "-z", older, newer).stdout
    return {item.decode() for item in raw.split(b"\0") if item}


def revision_bytes(revision: str, path: str) -> bytes | None:
    result = run("git", "show", revision + ":" + path, check=False)
    return result.stdout if result.returncode == 0 else None


def worktree_bytes(path: str) -> bytes | None:
    source = ROOT / path
    if not source.exists() and not source.is_symlink():
        return None
    require(source.is_file() and not source.is_symlink() and
            stat.S_ISREG(source.stat().st_mode),
            "composition path is not a regular file: " + path)
    return source.read_bytes()


def patch_parent() -> None:
    text = PARENT.read_text()
    constant = 'WIDENING_CHECKER = "morphhdl/scripts/check-increment-59i-widening-source-review.py"\n'
    text = replace_once(text, constant,
        'COMPOSITION_CHECKER = "morphhdl/scripts/check-increment-59i-rollout-composition.py"\n' +
        constant, "59i composition constant")
    anchor = "def load_widening_review(root: Path):\n"
    loader = '''def load_composition_review(root: Path):
    source = root / COMPOSITION_CHECKER
    require(source.is_file() and not source.is_symlink() and not source.stat().st_mode & 0o111,
            "missing regular 59i rollout-composition reviewer")
    spec = importlib.util.spec_from_file_location("increment_59i_rollout_composition", source)
    require(spec is not None and spec.loader is not None,
            "cannot load 59i rollout-composition reviewer")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


'''
    text = replace_once(text, anchor, loader + anchor, "59i composition loader")
    restore = '''def restore_source(root: Path, path: str, source: str) -> str:
    source = load_widening_review(root).restore_source(root, path, source)
'''
    restored = '''def restore_source(root: Path, path: str, source: str) -> str:
    source = load_composition_review(root).feature_view(root, path, source)
    source = load_widening_review(root).restore_source(root, path, source)
'''
    text = replace_once(text, restore, restored, "59i feature-view restoration")
    verify = '''def verify(root: Path) -> None:
    paths = production_changes(root, BASE)
    paths = load_widening_review(root).inherited_inventory(root, paths, BASE)
'''
    verified = '''def verify(root: Path) -> None:
    paths = production_changes(root, BASE)
    paths = load_composition_review(root).feature_inventory(root, paths, BASE)
    paths = load_widening_review(root).inherited_inventory(root, paths, BASE)
'''
    text = replace_once(text, verify, verified, "59i feature inventory")
    PARENT.write_text(text)


def patch_widening() -> None:
    text = WIDENING.read_text()
    text = replace_once(text, "import hashlib\nimport json\n",
                        "import hashlib\nimport importlib.util\nimport json\n",
                        "widening composition import")
    constant = 'CONTRACT_SHA256 = "f85db8c98de37f8fd306e320455e564cecc05aa2b079ca9041bac26ba46f03e0"\n'
    text = replace_once(text, constant, constant +
        'COMPOSITION_CHECKER = "morphhdl/scripts/check-increment-59i-rollout-composition.py"\n',
        "widening composition constant")
    anchor = "\ndef require(condition: bool, detail: str) -> None:\n"
    loader = '''
def load_composition_review(root: Path):
    source = root / COMPOSITION_CHECKER
    require(source.is_file() and not source.is_symlink() and not source.stat().st_mode & 0o111,
            "missing regular 59i rollout-composition reviewer")
    spec = importlib.util.spec_from_file_location("increment_59i_widening_composition", source)
    require(spec is not None and spec.loader is not None,
            "cannot load 59i rollout-composition reviewer from widening review")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

'''
    text = replace_once(text, anchor, loader + anchor, "widening composition loader")
    restore = '''def restore_source(root: Path, path: str, source: str) -> str:
    entries = load_contract(root)
'''
    restored = '''def restore_source(root: Path, path: str, source: str) -> str:
    source = load_composition_review(root).feature_view(root, path, source)
    entries = load_contract(root)
'''
    text = replace_once(text, restore, restored, "widening feature view")
    verify = '''    entries = load_contract(root)
    require(production_changes(root, BASE) == PRODUCTION_PATHS,
            "59i widening production delta differs from its reviewed three-file inventory")
'''
    verified = '''    entries = load_contract(root)
    paths = production_changes(root, BASE)
    paths = load_composition_review(root).feature_inventory(root, paths, BASE)
    require(paths == PRODUCTION_PATHS,
            "59i widening production delta differs from its reviewed three-file inventory")
'''
    text = replace_once(text, verify, verified, "widening feature inventory")
    WIDENING.write_text(text)


def patch_rollout() -> None:
    text = ROLLOUT.read_text()
    oracle = 'ORACLE = "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala"\n'
    text = replace_once(text, oracle, oracle +
        'COMPOSITION_CHECKER = "morphhdl/scripts/check-increment-59i-rollout-composition.py"\n',
        "60g composition constant")

    sibling = "\ndef sibling_scope(root: Path, extra: set[str]) -> None:\n"
    helpers = '''
def composition_review(root: Path):
    source = root / COMPOSITION_CHECKER
    if not (source.exists() or source.is_symlink()):
        return None
    require(source.is_file() and not source.is_symlink() and not source.stat().st_mode & 0o111,
            "missing regular 59i rollout-composition reviewer")
    spec = importlib.util.spec_from_file_location("increment_59i_rollout_composition", source)
    require(spec is not None and spec.loader is not None,
            "cannot load 59i rollout-composition reviewer")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def target_source(root: Path, review, path: str, source: str) -> str:
    return source if review is None else review.target_view(root, path, source)

'''
    text = replace_once(text, sibling, helpers + sibling, "60g composition helpers")

    oracle_block = '''def oracle_only(root: Path) -> None:
    source = restore_60g_source(root, ORACLE, (root / ORACLE).read_text())
'''
    oracle_fixed = '''def oracle_only(root: Path) -> None:
    review = composition_review(root)
    if review is not None:
        review.verify(root)
    source = target_source(root, review, ORACLE, (root / ORACLE).read_text())
    source = restore_60g_source(root, ORACLE, source)
'''
    text = replace_once(text, oracle_block, oracle_fixed, "60g oracle target view")

    text = replace_once(text,
        "def reviewed_blob_scope(root: Path, expected: dict[str, str]) -> None:\n",
        "def reviewed_blob_scope(root: Path, expected: dict[str, str], source_view=None) -> None:\n",
        "60g projected blob signature")
    raw_hash = '''        raw = file.read_bytes()
        require(hashlib.sha256(raw).hexdigest() == fingerprint,
                "60g reviewed raw source bytes differ: " + path)
        oid = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\\0" + raw).hexdigest()
'''
    projected_hash = '''        raw = file.read_bytes()
        reviewed = raw if source_view is None else source_view(path, raw)
        require(hashlib.sha256(reviewed).hexdigest() == fingerprint,
                "60g reviewed projected source bytes differ: " + path)
        oid = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\\0" + raw).hexdigest()
'''
    text = replace_once(text, raw_hash, projected_hash, "60g projected blob hash")

    scope = '''    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    changed = {p for p in git("diff", "--no-renames", "--name-only", BASE).splitlines()
               if "/src/main/" in "/" + p}
    ternary = boolean_ternary_review(root)
'''
    scope_fixed = '''    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    review = composition_review(root)
    if review is not None:
        review.verify(root)
    changed = {p for p in git("diff", "--no-renames", "--name-only", BASE).splitlines()
               if "/src/main/" in "/" + p}
    if review is not None:
        changed = review.target_inventory(root, changed, BASE)
    ternary = boolean_ternary_review(root)
'''
    text = replace_once(text, scope, scope_fixed, "60g target production inventory")

    source_loop = '''    for path, expected in {**PRODUCTION, **QUALIFICATION}.items():
        file = root / path
        require(file.is_file() and not file.is_symlink(), "missing/linked reviewed 60g source: " + path)
        require(digest(file.read_text()) == expected, "60g reviewed source bytes differ: " + path)
        stage = git("ls-files", "--stage", "--", path).split()
'''
    source_loop_fixed = '''    for path, expected in {**PRODUCTION, **QUALIFICATION}.items():
        file = root / path
        require(file.is_file() and not file.is_symlink(), "missing/linked reviewed 60g source: " + path)
        projected = target_source(root, review, path, file.read_text())
        require(digest(projected) == expected, "60g reviewed target-view source bytes differ: " + path)
        stage = git("ls-files", "--stage", "--", path).split()
'''
    text = replace_once(text, source_loop, source_loop_fixed, "60g target source hashes")

    contract_loop = '''    for entry in contract(root)["files"]:
        path = entry["path"]
        source = (root / path).read_text()
        require(digest(source) == entry["after_sha256"], "60g reviewed current source differs: " + path)
        require(restore_entry(entry, source) == git("show", BASE + ":" + path),
'''
    contract_loop_fixed = '''    for entry in contract(root)["files"]:
        path = entry["path"]
        source = target_source(root, review, path, (root / path).read_text())
        require(digest(source) == entry["after_sha256"], "60g reviewed target-view source differs: " + path)
        require(restore_entry(entry, source) == git("show", BASE + ":" + path),
'''
    text = replace_once(text, contract_loop, contract_loop_fixed, "60g target contract view")

    native = '''    native = git("diff", "--name-only", BASE, "--", "core/src/main", "lib/src/main",
                 "idslplugin/src/main", "sim/src/main", "morphhdl/contracts/native-source-preservation.json",
                 "morphhdl/contracts/typed-native-source-overlay.json")
'''
    native_fixed = '''    native_revision = review.TARGET_PARENT if review is not None else "HEAD"
    native = git("diff", "--name-only", BASE, native_revision, "--", "core/src/main", "lib/src/main",
                 "idslplugin/src/main", "sim/src/main", "morphhdl/contracts/native-source-preservation.json",
                 "morphhdl/contracts/typed-native-source-overlay.json")
'''
    text = replace_once(text, native, native_fixed, "60g target native inventory")

    manifest = '''    require(digest((root / manifest_path).read_text()) == NATIVE_MANIFEST_SHA256,
            "60g reviewed native manifest changed")
    reviewed_blob_scope(root, reviewed_sources(root))
'''
    manifest_fixed = '''    manifest_source = target_source(root, review, manifest_path, (root / manifest_path).read_text())
    require(digest(manifest_source) == NATIVE_MANIFEST_SHA256,
            "60g reviewed target-view native manifest changed")
    source_view = None if review is None else (lambda path, raw:
        review.target_view(root, path, raw.decode()).encode())
    reviewed_blob_scope(root, reviewed_sources(root), source_view)
'''
    text = replace_once(text, manifest, manifest_fixed, "60g projected reviewed blobs")

    blob_test = '''    expected = reviewed_sources(repository)
    contents = {path: (repository / path).read_bytes() for path in expected}
    rejected = 0
'''
    blob_test_fixed = '''    expected = reviewed_sources(repository)
    review = composition_review(repository)
    if review is not None:
        review.verify(repository)
    contents = {}
    for path in expected:
        raw = (repository / path).read_bytes()
        contents[path] = raw if review is None else review.target_view(
            repository, path, raw.decode()).encode()
    rejected = 0
'''
    text = replace_once(text, blob_test, blob_test_fixed, "60g blob-test target fixtures")

    self_test = '''def self_test(root: Path) -> None:
    rejected = 0
    for entry in contract(root)["files"]:
        after = (root / entry["path"]).read_text()
'''
    self_test_fixed = '''def self_test(root: Path) -> None:
    review = composition_review(root)
    if review is not None:
        review.verify(root)
    rejected = 0
    for entry in contract(root)["files"]:
        after = target_source(root, review, entry["path"], (root / entry["path"]).read_text())
'''
    text = replace_once(text, self_test, self_test_fixed, "60g self-test target view")
    ROLLOUT.write_text(text)


def patch_promoter() -> None:
    text = PROMOTER.read_text()
    parent = 'PARENT = ROOT / "morphhdl/scripts/check-increment-59i-source-review.py"\n'
    parent_fixed = ('PARENT_REL = "morphhdl/scripts/check-increment-59i-source-review.py"\n'
                    'PARENT = ROOT / PARENT_REL\n'
                    'REVIEWED = PRODUCTION + (PARENT_REL,)\n')
    text = replace_once(text, parent, parent_fixed, "local-enable reviewed parent path")
    text = replace_once(text,
        "    for ordinal, relative in enumerate(PRODUCTION, 1):\n",
        "    for ordinal, relative in enumerate(REVIEWED, 1):\n",
        "local-enable reviewed path inventory")
    path_tuple = '''PATHS = (
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala",
)
PRODUCTION_PATHS = frozenset(PATHS)
'''
    path_tuple_fixed = '''PATHS = (
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala",
    "morphhdl/scripts/check-increment-59i-source-review.py",
)
PRODUCTION_PATHS = frozenset(PATHS[:2])
'''
    text = replace_once(text, path_tuple, path_tuple_fixed,
                        "local-enable parent review template")

    old_restore = '''    restore = '''def restore_source(root: Path, path: str, source: str) -> str:
    source = load_widening_review(root).restore_source(root, path, source)
'''
    require(text.count(restore) == 1, "59i restore composition anchor changed")
    text = text.replace(restore, '''def restore_source(root: Path, path: str, source: str) -> str:
    source = load_local_enable_review(root).restore_source(root, path, source)
    source = load_widening_review(root).restore_source(root, path, source)
''', 1)
    inventory = "    paths = load_widening_review(root).inherited_inventory(root, paths, BASE)\\n"
    require(text.count(inventory) == 1, "59i inherited inventory anchor changed")
    text = text.replace(inventory,
        "    paths = load_local_enable_review(root).inherited_inventory(root, paths, BASE)\\n" + inventory, 1)
'''
    new_restore = '''    restore = '''def restore_source(root: Path, path: str, source: str) -> str:
    source = load_composition_review(root).feature_view(root, path, source)
    source = load_widening_review(root).restore_source(root, path, source)
'''
    require(text.count(restore) == 1, "59i restore composition anchor changed")
    text = text.replace(restore, '''def restore_source(root: Path, path: str, source: str) -> str:
    source = load_local_enable_review(root).restore_source(root, path, source)
    source = load_composition_review(root).feature_view(root, path, source)
    source = load_widening_review(root).restore_source(root, path, source)
''', 1)
    inventory = "    paths = load_composition_review(root).feature_inventory(root, paths, BASE)\\n"
    require(text.count(inventory) == 1, "59i composition inventory anchor changed")
'''
    text = replace_once(text, old_restore, new_restore,
                        "local-enable composition ordering")

    old_order = '''    contract_sha = write_contract(base)
    write_reviewer(base, contract_sha)
    compose_parent()
    write_doc(base)
    subprocess.run([sys.executable, str(CHECKER), "--self-test"], cwd=ROOT, check=True)
'''
    new_order = '''    compose_parent()
    contract_sha = write_contract(base)
    write_reviewer(base, contract_sha)
    write_doc(base)
'''
    text = replace_once(text, old_order, new_order,
                        "local-enable review generation order")
    PROMOTER.write_text(text)

    ci = PROMOTER_CI.read_text()
    old_ci = '''contract_sha = promote.write_contract(base)
promote.write_reviewer(base, contract_sha)
promote.compose_parent()
promote.write_doc(base)
'''
    new_ci = '''promote.compose_parent()
contract_sha = promote.write_contract(base)
promote.write_reviewer(base, contract_sha)
promote.write_doc(base)
'''
    ci = replace_once(ci, old_ci, new_ci, "local-enable CI review generation order")
    PROMOTER_CI.write_text(ci)


CHECKER_TEMPLATE = r'''#!/usr/bin/env python3
"""Exact bidirectional source review for the merged Increment 59i/60g tree."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import stat
import subprocess
from pathlib import Path

COMMON_BASE = "__COMMON__"
FEATURE_PARENT = "__FEATURE__"
TARGET_PARENT = "__TARGET__"
COMBINED_BASE = "__COMBINED__"
CONTRACT = "morphhdl/contracts/increment-59i-rollout-composition.json"
CONTRACT_SHA256 = "__CONTRACT_SHA256__"
LOCAL_ENABLE_CHECKER = "morphhdl/scripts/check-increment-59i-local-enable-source-review.py"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def run(root: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[bytes]:
    result = subprocess.run(args, cwd=root, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False)
    if check and result.returncode != 0:
        raise RuntimeError("composition command failed: " + " ".join(args) + "\n" +
                           result.stderr.decode(errors="replace"))
    return result


def revision_bytes(root: Path, revision: str, path: str) -> bytes | None:
    result = run(root, "git", "show", revision + ":" + path, check=False)
    return result.stdout if result.returncode == 0 else None


def changed(root: Path, older: str, newer: str) -> set[str]:
    raw = run(root, "git", "diff", "--no-renames", "--name-only", "-z", older, newer).stdout
    return {item.decode() for item in raw.split(b"\0") if item}


def production(paths: set[str]) -> set[str]:
    return {path for path in paths if re.search(r"(?:^|/)src/main/", path)}


def load_contract(root: Path) -> dict:
    source = root / CONTRACT
    require(source.is_file() and not source.is_symlink() and
            not source.stat().st_mode & 0o111,
            "59i rollout-composition contract must be a regular non-executable file")
    raw = source.read_bytes()
    require(digest(raw) == CONTRACT_SHA256,
            "59i rollout-composition contract changed")
    value = json.loads(raw)
    require(set(value) == {"schema_version", "common_base", "feature_parent",
                           "target_parent", "combined_base", "combined_production", "files"},
            "invalid 59i rollout-composition schema")
    require(value["schema_version"] == 1 and value["common_base"] == COMMON_BASE and
            value["feature_parent"] == FEATURE_PARENT and
            value["target_parent"] == TARGET_PARENT and
            value["combined_base"] == COMBINED_BASE,
            "59i rollout-composition commit anchors changed")
    require(isinstance(value["combined_production"], list) and
            value["combined_production"] == sorted(set(value["combined_production"])),
            "invalid combined production inventory")
    files = value["files"]
    require(isinstance(files, list) and
            [entry.get("path") for entry in files] == sorted(entry.get("path") for entry in files),
            "59i rollout-composition paths must be sorted")
    result = {}
    for entry in files:
        require(set(entry) == {"path", "combined_sha256", "feature_sha256", "target_sha256"},
                "invalid 59i rollout-composition file entry")
        path = entry["path"]
        require(isinstance(path, str) and path and path not in result and
                not Path(path).is_absolute() and ".." not in Path(path).parts,
                "invalid or duplicate 59i rollout-composition path")
        for key in ("combined_sha256", "feature_sha256", "target_sha256"):
            value_hash = entry[key]
            require(value_hash is None or
                    (isinstance(value_hash, str) and re.fullmatch("[0-9a-f]{64}", value_hash)),
                    "invalid 59i rollout-composition hash: " + path + " " + key)
        require(entry["combined_sha256"] is not None or
                entry["feature_sha256"] is not None or entry["target_sha256"] is not None,
                "empty 59i rollout-composition entry: " + path)
        result[path] = entry
    value["entries"] = result
    return value


def local_enable_review(root: Path):
    source = root / LOCAL_ENABLE_CHECKER
    if not (source.exists() or source.is_symlink()):
        return None
    require(source.is_file() and not source.is_symlink() and
            not source.stat().st_mode & 0o111,
            "missing regular 59i local-enable successor reviewer")
    spec = importlib.util.spec_from_file_location("increment_59i_local_enable_successor", source)
    require(spec is not None and spec.loader is not None,
            "cannot load 59i local-enable successor reviewer")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def successor_view(root: Path, path: str, source: str) -> str:
    review = local_enable_review(root)
    if review is None or path not in review.PATHS:
        return source
    return review.restore_source(root, path, source)


def view(root: Path, path: str, source: str, revision: str, key: str) -> str:
    contract = load_contract(root)
    entry = contract["entries"].get(path)
    if entry is None:
        return source
    desired_hash = entry[key]
    require(desired_hash is not None,
            "requested source view is absent: " + revision + ":" + path)
    raw = source.encode()
    if digest(raw) == desired_hash:
        return source
    projected = successor_view(root, path, source).encode()
    if digest(projected) == desired_hash:
        return projected.decode()
    require(entry["combined_sha256"] is not None and
            digest(projected) == entry["combined_sha256"],
            "unreviewed merged source outside 59i/60g composition: " + path)
    desired = revision_bytes(root, revision, path)
    require(desired is not None and digest(desired) == desired_hash,
            "immutable source-view parent changed: " + revision + ":" + path)
    try:
        return desired.decode()
    except UnicodeDecodeError as error:
        raise RuntimeError("source reviewer requested a non-UTF-8 path: " + path) from error


def feature_view(root: Path, path: str, source: str) -> str:
    return view(root, path, source, FEATURE_PARENT, "feature_sha256")


def target_view(root: Path, path: str, source: str) -> str:
    return view(root, path, source, TARGET_PARENT, "target_sha256")


def project_inventory(root: Path, paths: set[str], qualification_base: str,
                      revision: str) -> set[str]:
    verify(root)
    review = local_enable_review(root)
    if review is not None:
        paths = review.inherited_inventory(root, paths, qualification_base)
    combined = production(changed(root, qualification_base, COMBINED_BASE))
    desired = production(changed(root, qualification_base, revision))
    return (paths - combined) | desired


def feature_inventory(root: Path, paths: set[str], qualification_base: str) -> set[str]:
    return project_inventory(root, paths, qualification_base, FEATURE_PARENT)


def target_inventory(root: Path, paths: set[str], qualification_base: str) -> set[str]:
    return project_inventory(root, paths, qualification_base, TARGET_PARENT)


def verify(root: Path) -> None:
    contract = load_contract(root)
    for revision in (COMMON_BASE, FEATURE_PARENT, TARGET_PARENT, COMBINED_BASE):
        run(root, "git", "merge-base", "--is-ancestor", revision, "HEAD")
    current = production(changed(root, COMMON_BASE, "HEAD"))
    untracked = production({item for item in run(
        root, "git", "ls-files", "--others", "--exclude-standard").stdout.decode().splitlines()})
    current |= untracked
    review = local_enable_review(root)
    if review is not None:
        current = review.inherited_inventory(root, current, COMMON_BASE)
    require(current == set(contract["combined_production"]),
            "merged 59i/60g production inventory changed; missing=" +
            repr(sorted(set(contract["combined_production"]) - current)) +
            "; unreviewed=" + repr(sorted(current - set(contract["combined_production"]))))

    for path, entry in contract["entries"].items():
        source = root / path
        expected = entry["combined_sha256"]
        if expected is None:
            require(not source.exists() and not source.is_symlink(),
                    "deleted composition path reappeared: " + path)
            continue
        require(source.is_file() and not source.is_symlink() and
                stat.S_ISREG(source.stat().st_mode) and not source.stat().st_mode & 0o111,
                "composition source must be a regular non-executable file: " + path)
        raw = source.read_bytes()
        try:
            projected = successor_view(root, path, raw.decode()).encode()
        except UnicodeDecodeError as error:
            raise RuntimeError("reviewed composition source is not UTF-8: " + path) from error
        require(digest(projected) == expected,
                "59i/60g combined source bytes changed: " + path)
        stage = run(root, "git", "ls-files", "--stage", "-z", "--", path).stdout
        records = [record for record in stage.split(b"\0") if record]
        require(len(records) == 1, "composition source is not uniquely indexed: " + path)
        metadata, raw_path = records[0].split(b"\t", 1)
        mode, indexed, stage_number = metadata.decode().split()
        require(mode == "100644" and stage_number == "0" and raw_path.decode() == path,
                "composition source index mode/stage changed: " + path)
        committed = output(root, "git", "rev-parse", "HEAD:" + path)
        actual = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
        require(indexed == actual == committed,
                "composition HEAD/index/worktree identity changed: " + path)
    print("59i/60g exact bidirectional source composition PASS", flush=True)


def output(root: Path, *args: str) -> str:
    return run(root, *args).stdout.decode().strip()


def self_test(root: Path) -> None:
    verify(root)
    contract = load_contract(root)
    checked = rejected = 0
    for path, entry in contract["entries"].items():
        if entry["combined_sha256"] is None:
            continue
        source = (root / path).read_bytes()
        try:
            text = source.decode()
        except UnicodeDecodeError:
            continue
        for revision, key, function in (
            (FEATURE_PARENT, "feature_sha256", feature_view),
            (TARGET_PARENT, "target_sha256", target_view),
        ):
            if entry[key] is None:
                continue
            result = function(root, path, text).encode()
            expected = revision_bytes(root, revision, path)
            require(expected is not None and result == expected,
                    "positive composition view failed: " + path)
            checked += 1
        for index in sorted({0, len(source) // 2, len(source) - 1}):
            if index < 0:
                continue
            mutation = source[:index] + bytes([source[index] ^ 1]) + source[index + 1:]
            for function in (feature_view, target_view):
                try:
                    function(root, path, mutation.decode(errors="replace"))
                except RuntimeError:
                    rejected += 1
                else:
                    raise RuntimeError("composition accepted mutated merged source: " + path)
    require(checked > 0 and rejected >= checked,
            "incomplete 59i/60g composition controls")
    print("59i/60g composition controls PASS: " + str(checked) +
          " parent views and " + str(rejected) + " rejected mutations", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    verify(args.repo_root)
    if args.self_test:
        self_test(args.repo_root)


if __name__ == "__main__":
    main()
'''


CHECKER_TEST_TEMPLATE = r'''#!/usr/bin/env python3
"""Run exact 59i/60g bidirectional composition controls."""
from __future__ import annotations
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "morphhdl/scripts/check-increment-59i-rollout-composition.py"
spec = importlib.util.spec_from_file_location("increment_59i_rollout_composition_test", SOURCE)
if spec is None or spec.loader is None:
    raise RuntimeError("cannot load 59i rollout-composition reviewer")
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)
review.verify(ROOT)
review.self_test(ROOT)
'''


def write_contract(combined_base: str) -> str:
    paths = changed(COMMON, FEATURE) | changed(COMMON, TARGET) | changed(COMMON, combined_base)
    paths |= set(PATCHED)
    entries = []
    for path in sorted(paths):
        combined = worktree_bytes(path)
        feature = revision_bytes(FEATURE, path)
        target = revision_bytes(TARGET, path)
        if combined is None and feature is None and target is None:
            continue
        entries.append({
            "path": path,
            "combined_sha256": None if combined is None else digest(combined),
            "feature_sha256": None if feature is None else digest(feature),
            "target_sha256": None if target is None else digest(target),
        })
    production_paths = sorted(path for path in changed(COMMON, combined_base)
                              if "/src/main/" in "/" + path)
    raw = (json.dumps({
        "schema_version": 1,
        "common_base": COMMON,
        "feature_parent": FEATURE,
        "target_parent": TARGET,
        "combined_base": combined_base,
        "combined_production": production_paths,
        "files": entries,
    }, indent=2) + "\n").encode()
    CONTRACT.parent.mkdir(parents=True, exist_ok=True)
    CONTRACT.write_bytes(raw)
    return digest(raw)


def write_checker(combined_base: str, contract_sha: str) -> None:
    source = (CHECKER_TEMPLATE.replace("__COMMON__", COMMON)
              .replace("__FEATURE__", FEATURE)
              .replace("__TARGET__", TARGET)
              .replace("__COMBINED__", combined_base)
              .replace("__CONTRACT_SHA256__", contract_sha))
    CHECKER.write_text(source)
    CHECKER_TEST.write_text(CHECKER_TEST_TEMPLATE)


def write_doc(combined_base: str) -> None:
    DOC.write_text(f'''# Increment 59i — exact 59i/60g source composition

Status: reviewed development composition. Increment 59i remains incomplete,
unchecked, draft and unmerged.

- Common integration baseline: `{COMMON}`
- 59i feature parent: `{FEATURE}`
- Current `parameterized-verilog` parent: `{TARGET}`
- Exact pre-composition merged tree: `{combined_base}`

The merged source is validated as one real HEAD/index/worktree tree. Historical
59i audits receive only the exact first-parent view, while 60g rollout audits
receive only the exact second-parent view. A whole-file hash map rejects any
third byte sequence; no checker accepts arbitrary edits between old span ranges.

The adapter also recognizes the separately reviewed local-enable successor when
present, strips it first, and then validates this immutable merged checkpoint.
This is source-review composition only; it does not substitute for Scala, RTL,
simulation, synthesis, formal-equivalence, mutation or final integration gates.
''')


def main() -> None:
    require(not CONTRACT.exists() and not CHECKER.exists() and not CHECKER_TEST.exists(),
            "59i rollout-composition successor already exists")
    combined_base = output("git", "rev-parse", "HEAD")
    for revision in (COMMON, FEATURE, TARGET):
        run("git", "merge-base", "--is-ancestor", revision, combined_base)
    patch_parent()
    patch_widening()
    patch_rollout()
    patch_promoter()
    contract_sha = write_contract(combined_base)
    write_checker(combined_base, contract_sha)
    write_doc(combined_base)
    print("59i/60g exact composition prepared from " + combined_base, flush=True)


if __name__ == "__main__":
    main()
