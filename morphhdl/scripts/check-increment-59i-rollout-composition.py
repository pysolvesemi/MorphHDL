#!/usr/bin/env python3
"""Exact bidirectional source review for the merged Increment 59i/60g tree."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import stat
import subprocess
import sys
from pathlib import Path

COMMON_BASE = "64e8fddc432e859b6b532540bee96c5608d46efa"
FEATURE_PARENT = "ee2e7f2613e54f23158ce1eacae6028409a91ed6"
TARGET_PARENT = "2ebaa2ef5561eab35aa0ba9caced5c5a314d59f6"
COMBINED_BASE = "71efa81bf56e8483f7837519b2e44cdeba908439"
CONTRACT = "morphhdl/contracts/increment-59i-rollout-composition.json"
CONTRACT_SHA256 = "3eda911201658d435d98c1f957fa1554ed8626f6e70473eb3a0698327021d7a8"
LOCAL_ENABLE_CHECKER = "morphhdl/scripts/check-increment-59i-local-enable-source-review.py"
LOCAL_ENABLE_CHECKER_SHA256 = "e5b1b42acb91d9eeb86af15c06c6f8ac8ac80448b30540f90ee807d05ab836ba"


def integration_review(root: Path):
    checker = root / "morphhdl/scripts/check-increment-59i-target-integration.py"
    manifest = root / "morphhdl/contracts/increment-59i-target-integration.json"
    if not any(path.exists() or path.is_symlink() for path in (checker, manifest)):
        return None
    for path in (checker, manifest):
        require(path.is_file() and not path.is_symlink() and not path.stat().st_mode & 0o111,
                "59i target integration requires a regular reviewer and manifest")
        relative = path.relative_to(root)
        require(all(not (root / Path(*relative.parts[:i])).is_symlink()
                    for i in range(1, len(relative.parts))),
                "59i target integration reviewer ancestry is linked")
    raw = checker.read_bytes()
    pattern = rb'^CONTRACT_SHA256 = "[^"\n]+"$'
    require(len(re.findall(pattern, raw, re.M)) == 1,
            "59i target integration reviewer seal is ambiguous")
    normalized = re.sub(pattern, b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, flags=re.M)
    require(hashlib.sha256(normalized).hexdigest() == "1329dd3b37bafa13b137bcd1976feaf78472de623aa3f79a9c02030b3cd5e451",
            "59i target integration reviewer changed")
    # Share only authenticated code and its immutable-object caches. Every
    # caller still reads the current manifest and verifies live checkout bytes.
    name = "increment_59i_target_integration_" + hashlib.sha256(raw).hexdigest()
    module = sys.modules.get(name)
    if module is None:
        spec = importlib.util.spec_from_file_location(name, checker)
        require(spec is not None and spec.loader is not None,
                "cannot load exact 59i target integration reviewer")
        module = importlib.util.module_from_spec(spec)
        exec(compile(raw, str(checker), "exec"), module.__dict__)
        sys.modules[name] = module
    module.contract(root)
    return module


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
        # Absence is historical only when HEAD has never contained this layer.
        # Deleting a mandatory current reviewer cannot restore legacy rights.
        history = run(root, "git", "log", "--full-history", "-1", "--format=%H",
                      "HEAD", "--", LOCAL_ENABLE_CHECKER).stdout
        require(not history.strip(), "59i local-enable successor reviewer was removed")
        return None
    relative = Path(LOCAL_ENABLE_CHECKER)
    require(all(not root.joinpath(*relative.parts[:index]).is_symlink()
                for index in range(1, len(relative.parts) + 1)),
            "linked 59i local-enable successor reviewer")
    require(source.is_file() and not source.stat().st_mode & 0o111,
            "missing regular 59i local-enable successor reviewer")
    raw = source.read_bytes()
    require(digest(raw) == LOCAL_ENABLE_CHECKER_SHA256,
            "59i local-enable successor reviewer changed")
    name = "increment_59i_local_enable_successor_" + digest(raw)
    module = sys.modules.get(name)
    if module is None:
        spec = importlib.util.spec_from_file_location(name, source)
        require(spec is not None and spec.loader is not None,
                "cannot load 59i local-enable successor reviewer")
        module = importlib.util.module_from_spec(spec)
        exec(compile(raw, str(source), "exec"), module.__dict__)
        sys.modules[name] = module
    # Re-read the pinned manifest on every call; cached code grants no authority.
    module.load_contract(root)
    return module


def successor_view(root: Path, path: str, source: str) -> str:
    integration = integration_review(root)
    if integration is not None:
        source = integration.feature_source(root, path, source.encode()).decode()
    if path == 'morphhdl/contracts/native-source-preservation.json':
        checker = root / 'morphhdl/scripts/check-increment-59i-native-tree-anchor-review.py'
        require(checker.is_file() and not checker.is_symlink() and not checker.stat().st_mode & 0o111,
                'missing regular native-anchor successor reviewer')
        raw = checker.read_bytes()
        actual = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        require(actual == 'b936a111af4cb09c8475cf63ba0c199a4290137b',
                'native-anchor successor reviewer changed')
        spec = importlib.util.spec_from_file_location('native_anchor_composition', checker)
        require(spec is not None and spec.loader is not None, 'cannot load native-anchor successor review')
        anchor = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(anchor)
        try:
            source = anchor.restore_source(root, path, source)
        except anchor.ReviewError as error:
            require(False, 'unreviewed source change outside 59i spans; ' + str(error))
    review = local_enable_review(root)
    if review is None or path not in review.PATHS:
        return source
    return review.restore_source(root, path, source)


def view(root: Path, path: str, source: str, revision: str, key: str) -> str:
    contract = load_contract(root)
    entry = contract["entries"].get(path)
    if entry is None:
        integration = integration_review(root)
        if integration is not None:
            return integration.feature_source(root, path, source.encode()).decode()
        return source
    desired_hash = entry[key]
    require(desired_hash is not None,
            "requested source view is absent: " + revision + ":" + path)
    raw = source.encode()
    if digest(raw) == desired_hash:
        return source
    # Repeated inherited restoration may already expose this exact frozen
    # combined preimage. Only the sealed digest bypasses the outer successor.
    projected = raw if digest(raw) == entry["combined_sha256"] else successor_view(root, path, source).encode()
    if digest(projected) == desired_hash:
        return projected.decode()
    require(entry["combined_sha256"] is not None and
            digest(projected) == entry["combined_sha256"],
            "unreviewed source change outside 59i spans; outside 59i/60g composition: " + path)
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
    integration = integration_review(root)
    if integration is not None:
        paths = integration.feature_inventory(root, paths, qualification_base)
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
    integration = integration_review(root)
    # The inventory projection below runs the complete current integration
    # verifier once; no cached verification result authorizes this checkout.
    contract = load_contract(root)
    for revision in (COMMON_BASE, FEATURE_PARENT, TARGET_PARENT, COMBINED_BASE):
        run(root, "git", "merge-base", "--is-ancestor", revision, "HEAD")
    current = production(changed(root, COMMON_BASE, "HEAD"))
    untracked = production({item for item in run(
        root, "git", "ls-files", "--others", "--exclude-standard").stdout.decode().splitlines()})
    current |= untracked
    if integration is not None:
        current = integration.feature_inventory(root, current, COMMON_BASE)
    review = local_enable_review(root)
    if review is not None:
        current = review.inherited_inventory(root, current, COMMON_BASE)
    require(current == set(contract["combined_production"]),
            "merged 59i/60g production inventory changed; missing=" +
            repr(sorted(set(contract["combined_production"]) - current)) +
            "; unreviewed=" + repr(sorted(current - set(contract["combined_production"]))))

    # Batch immutable HEAD and live index inventory reads. Every reviewed
    # file still independently binds its mode and raw worktree blob to both.
    head = output(root, "git", "rev-parse", "HEAD")
    committed = {}
    for record in run(root, "git", "ls-tree", "-r", "-z", head).stdout.split(b"\0"):
        if record:
            metadata, raw_path = record.split(b"\t", 1)
            mode, kind, oid = metadata.decode().split()
            committed[raw_path.decode()] = (mode, kind, oid)
    indexed = {}
    for record in run(root, "git", "ls-files", "--stage", "-z").stdout.split(b"\0"):
        if record:
            metadata, raw_path = record.split(b"\t", 1)
            mode, oid, stage = metadata.decode().split()
            indexed.setdefault(raw_path.decode(), []).append((mode, oid, stage))
    for path, entry in contract["entries"].items():
        source = root / path
        expected = entry["combined_sha256"]
        if expected is None:
            require(not source.exists() and not source.is_symlink(),
                    "deleted composition path reappeared: " + path)
            continue
        require(source.is_file() and not source.is_symlink() and
                stat.S_ISREG(source.stat().st_mode),
                "composition source must be a regular file: " + path)
        raw = source.read_bytes()
        try:
            projected = successor_view(root, path, raw.decode()).encode()
        except UnicodeDecodeError as error:
            raise RuntimeError("reviewed composition source is not UTF-8: " + path) from error
        require(digest(projected) == expected,
                "59i/60g combined source bytes changed: " + path)
        records = indexed.get(path, [])
        require(len(records) == 1, "composition source is not uniquely indexed: " + path)
        mode, indexed_oid, stage_number = records[0]
        require(mode in ("100644", "100755") and stage_number == "0",
                "composition source index mode/stage changed: " + path)
        current = committed.get(path)
        require(current is not None and current[:2] == (mode, "blob"),
                "composition source HEAD mode/type changed: " + path)
        actual = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
        require(indexed_oid == actual == current[2],
                "composition HEAD/index/worktree identity changed: " + path)
    require(output(root, "git", "rev-parse", "HEAD") == head,
            "composition HEAD changed while checking source identity")
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
