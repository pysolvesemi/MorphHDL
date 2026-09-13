#!/usr/bin/env python3
"""Authenticate a separately sealed source successor before historical reviews.

This module imports no inherited checker. Its manifest is deliberately absent
until an immutable source commit has been independently reviewed. The seal may
only add that manifest and replace this module's one manifest-hash placeholder.
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
from pathlib import Path

BASE = "954d9b2763b064dba60af71ad8fa509a9d7cada8"
HELPER = "morphhdl/scripts/check-increment-59i-production-successor.py"
TEST = "morphhdl/scripts/test-increment-59i-production-successor.py"
CONTRACT = "morphhdl/contracts/increment-59i-production-successor.json"
CONTRACT_SHA256 = "UNSEALED"
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
    return (isinstance(path, str) and bool(path) and Path(path).as_posix() == path and
        not Path(path).is_absolute() and ".." not in Path(path).parts and
        ".git" not in Path(path).parts and "\0" not in path)


def regular(root: Path, path: str, mode: str = "100644") -> bytes:
    require(valid_path(path), "invalid path: " + repr(path))
    parts = Path(path).parts
    require(all(not (root / Path(*parts[:n])).is_symlink() for n in range(1, len(parts) + 1)),
        "linked source: " + path)
    file = root / path
    require(file.is_file() and stat.S_ISREG(file.stat().st_mode), "missing regular source: " + path)
    require(mode in ("100644", "100755") and
        bool(file.stat().st_mode & 0o111) == (mode == "100755"), "source mode changed: " + path)
    return file.read_bytes()


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
    require(isinstance(value, dict) and set(value) == {"schema_version", "predecessor",
        "predecessor_tree", "source_commit", "source_tree", "helper_normalized_sha256", "files"},
        "invalid manifest schema")
    require(value["schema_version"] == 1 and value["predecessor"] == BASE,
        "immutable predecessor changed")
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


@functools.lru_cache(maxsize=8)
def validated_manifest(raw: bytes, predecessor: str) -> str:
    """Cache only immutable structural validation, returning no mutable authority."""
    try:
        value = validate_contract(json.loads(raw))
    except (ValueError, UnicodeDecodeError) as error:
        raise RuntimeError("59i production successor: invalid manifest JSON") from error
    require(value["predecessor"] == predecessor, "immutable predecessor changed")
    return value["helper_normalized_sha256"]


def contract(root: Path) -> dict:
    require(re.fullmatch(r"[0-9a-f]{64}", CONTRACT_SHA256) is not None,
        "source successor has not been sealed after independent review")
    raw = regular(root, CONTRACT)
    require(digest(raw) == CONTRACT_SHA256, "sealed successor manifest changed")
    expected_helper = validated_manifest(raw, BASE)
    helper = regular(root, HELPER)
    require(digest(normalized_helper(helper)) == expected_helper,
        "sealed successor helper changed")
    require(re.search(rb'^CONTRACT_SHA256 = "' + CONTRACT_SHA256.encode() + rb'"$', helper, re.M)
        is not None, "helper manifest-hash slot differs")
    # Re-read/authenticate live files on every call and give each caller a
    # fresh object. A prior pass or mutated return value grants no authority.
    return json.loads(raw)


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
    value = contract(root)
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


def verify_seal_history(root: Path, value: dict, head: str, expected: dict) -> None:
    source = value["source_commit"]
    parents = git(root, "rev-list", "--parents", "-n", "1", source).decode().split()
    require(parents == [source, BASE], "source must be one direct child of the immutable predecessor")
    require(not git(root, "rev-list", "--full-history", BASE + ".." + source, "--", CONTRACT),
        "source history already contains a successor seal")
    # A normal GitHub integration merge places the reviewed feature in its
    # second parent. Its target is already an ancestor of the immutable source
    # predecessor, so the merge must preserve the feature tree exactly. Permit
    # that one topology, with only linear descendants on either side of it.
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
                git(root, "merge-base", "--is-ancestor", target, BASE)
            except RuntimeError as error:
                raise RuntimeError("59i production successor: integration target is not an ancestor "
                    "of the immutable predecessor") from error
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
    history = git(root, "rev-list", "--full-history", BASE + ".." + head, "--", CONTRACT).decode().splitlines()
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
    require(CONTRACT not in before_tree and CONTRACT not in source_tree,
        "source or predecessor already contains successor seal")
    require(source_tree.get(HELPER, (None,))[0] == "100644",
        "immutable source helper must be regular and non-executable")
    records = {entry["path"]: entry for entry in value["files"]}
    require(set(records) == changed(root, BASE, source), "complete successor delta inventory changed")
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    value = verify(args.repo_root)
    print("59I_PRODUCTION_SUCCESSOR_PASS files=" + str(len(value["files"])))


if __name__ == "__main__":
    main()
