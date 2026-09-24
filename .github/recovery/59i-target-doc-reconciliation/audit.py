#!/usr/bin/env python3
"""Authenticate the final schema-17 source evidence and current target."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import zipfile

SEAL = "b1c8183face8761e14746cf0aed62e654f6a3bee"
SEAL_TREE = "3116fa7575150fc0e7567df158006c97b052ec08"
SOURCE = "a11891bb879333f83a2469bd8c529265a58725fd"
SOURCE_TREE = "6712f79b63220f38ee7cadc86be589a31eff5d13"
PREDECESSOR_SEAL = "f9805ccfc3c1d9d73a38d1e0e6dd5676cc21cbb6"
PREDECESSOR_SEAL_TREE = "303dd21d525ea001ddf8327a96e215d417e20ba6"
PREDECESSOR_SOURCE = "7ae72385bf0079c3a8c70ab0c317f97c421ddd09"
PREDECESSOR_SOURCE_TREE = "e033d6419c3775e5d0ba57eab5d4a8033153e65c"
ANCESTOR_SEAL = "c48bad51b9857570e65fbd7c1be5dec465a3e8fa"
ANCESTOR_SEAL_TREE = "6c2bee9158e61022a4fdc56efb33b056bf2135ff"
ANCESTOR_SOURCE = "9270cb332aba63b6ea7ca17dc2694b93ff7ded52"
ANCESTOR_SOURCE_TREE = "898934c355f6a7280932f92e5e7f88f2e1f3bc1b"
PRIOR_SEAL = "5adb952e038cd001f9a4adb27292a9fd7614fbe8"
PRIOR_SEAL_TREE = "23e84f75a05372bfd0f819355806bd71dbcc31a5"
PRIOR_SOURCE = "5a8178aa2422fb1987d7bab8692a107599c9b7ef"
PRIOR_SOURCE_TREE = "ec400eb14314a82fdb924f1824bb62a43cb0532b"
OLDER_SEAL = "d6536e454b69346fe0954a322e0515396812f218"
OLDER_SEAL_TREE = "17b93cad066276a2ab96a3325ed0e02d85e7e6f5"
OLDER_SOURCE = "f6575ae3559aa65a042bd644a93068a4a2044fbf"
OLDER_SOURCE_TREE = "68a31a90d404c4ac693dccbb8d1c565adfca9af1"
OLDEST_SEAL = "c654f43c24d86ca99c056dd4cb74b7a18d9f41e3"
OLDEST_SEAL_TREE = "815a381426e9507363ab91bd3a258ab9668fb87a"
OLDEST_SOURCE = "2797acc2fbeb0733c29d8c05d64857801de32ae2"
OLDEST_SOURCE_TREE = "bad8f069942e54c0e4736076ebe5ff336dec26d6"
LEGACY_SEAL = "1eb63e57d41fb1f707da8ca7f176b1ecc609aebf"
LEGACY_SEAL_TREE = "e22a4775ab70c3c1d516ba2d83323f2273776c63"
LEGACY_SOURCE = "1ee1e5e3e84497de4b983e08be31e46ceb4dbc25"
LEGACY_SOURCE_TREE = "324087367b0b979bf8eee675d96c74f40937e2ac"
DOCUMENTATION_CHECKPOINT = "31be34e11c41d12c08d39626314b860c24bc9781"
DOCUMENTATION_CHECKPOINT_TREE = "3e18070703fbb27d74deefacb796a56df4a0717a"
EARLIER_SEAL = "3ce0bf30e51af8d84a432a8937c88f31db1d7d05"
FEATURE = "c48bad51b9857570e65fbd7c1be5dec465a3e8fa"
FEATURE_TREE = "6c2bee9158e61022a4fdc56efb33b056bf2135ff"
TARGET = "09880c538c4cf83022f4a1bb1dd16b43ea81a751"
TARGET_TREE = "6216cf799cc51c5a5f815d08f16e435c6b48ddc7"
OLD_TARGET = "8ee07f251f5400922763382073db45ca76d012bd"
OLD_TARGET_TREE = "1e6753d901256d31af1c46977e1de86e47a0b7b7"
TARGET_PATH = "morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md"
TARGET_BLOB = "5739586b9271dee22df84126f83a26cb07e0b1db"
TARGET_SHA256 = "d727555a7210570b711f66dcfb8eebfa6b687977bf3f3c056a36455adac0f668"
ARTIFACT_SHA256 = "b8d010323e54e63256a5d445e49b9a22870865f5dc343a88017a43b6c1835b0e"
CONTRACT_SHA256 = "bb36fbe0447d71332306fcbed3b561ac4180f84a717a224699184f27d0543c0d"
HELPER_NORMALIZED_SHA256 = "b4cf29a4bfbe058e8d77adc7e8896548207273ce75ea6f352497f57e160a15f6"
HELPER = "morphhdl/scripts/check-increment-59i-production-successor.py"
CONTRACT = "morphhdl/contracts/increment-59i-production-successor.json"

COMMANDS = (
    "python3 -B morphhdl/scripts/check-increment-59i-production-successor.py",
    "python3 -B morphhdl/scripts/check-increment-59i-pr190-integration.py",
    "python3 -B morphhdl/scripts/test-increment-59i-pr190-integration.py",
    "python3 -B morphhdl/scripts/test-pr190-pr189-source-sync.py",
    "python3 -B morphhdl/scripts/test-sequential-wire-source-review.py",
    "python3 -B morphhdl/scripts/check-increment-59i-local-enable-source-review.py --self-test",
    "python3 -B morphhdl/scripts/test-increment-59i-local-enable-source-review.py",
    "python3 -B morphhdl/scripts/test-increment-59i-local-enable-successor.py",
    "python3 -B morphhdl/scripts/test-increment-59i-local-enable-results.py",
    "python3 -B morphhdl/scripts/check-increment-61-source-review.py",
    "python3 -B morphhdl/scripts/check-native-source-preservation.py",
    "python3 -B morphhdl/scripts/check-typed-native-source-overlay.py",
    "python3 -B morphhdl/scripts/check-increment-59i-target-integration.py",
    "python3 -B morphhdl/scripts/check-increment-62-wa08-source-overlay.py",
    "python3 -B morphhdl/scripts/check-wa10-source-scope.py",
    "python3 -B morphhdl/scripts/check-increment-59i-widening-source-review.py",
    "python3 -B morphhdl/scripts/check-increment-59i-rollout-composition.py",
    "python3 -B morphhdl/scripts/check-increment-60b-signedness-authority.py",
    "python3 -B morphhdl/scripts/check-cdc-successor-source.py",
    "python3 -B morphhdl/scripts/check-lane-when-source-scope.py",
    "python3 -B morphhdl/scripts/test-increment-59i-inherited-audit-budgets.py",
    "python3 -B morphhdl/scripts/test-increment-59i-regression-inventory.py",
    "python3 -B morphhdl/scripts/test-increment-59i-production-successor.py",
    "python3 -B morphhdl/scripts/test-increment-59i-continuation.py",
    "python3 -B morphhdl/scripts/test-increment-59i-pr189-sync.py",
)


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError("59i schema-17 target reconciliation: " + detail)


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def git(root: Path, *args: str) -> str:
    result = subprocess.run(["git", "--literal-pathspecs", *args], cwd=root,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
    require(result.returncode == 0, "git " + " ".join(args) + " failed: " +
        result.stderr.decode(errors="replace"))
    return result.stdout.decode()


def normalized_helper(raw: bytes) -> bytes:
    pattern = rb'^CONTRACT_SHA256 = "[^"\n]+"$'
    require(len(re.findall(pattern, raw, re.M)) == 1, "ambiguous helper seal")
    return re.sub(pattern, b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, flags=re.M)


def validate_target_bytes(raw: bytes) -> None:
    require(len(raw) == 76252 and raw.count(b"\n") == 1435,
        "target documentation size or line inventory changed")
    require(digest(raw) == TARGET_SHA256, "target documentation bytes changed")


def zip_files(raw: bytes) -> dict[str, bytes]:
    require(digest(raw) == ARTIFACT_SHA256, "source artifact digest changed")
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "duplicate artifact member")
        require(all(not name.startswith("/") and ".." not in Path(name).parts for name in names),
            "unsafe artifact member")
        files = {name: archive.read(name) for name in names if not name.endswith("/")}
    expected = {f"{index:02d}.log" for index in range(1, 26)} | {
        "identity.txt", "live-refs.txt", "results.tsv", "audit-repair-paths.txt",
        "seal-paths.txt", "target-reconciliation-paths.txt", "started.txt"}
    require(set(files) == expected, "source artifact member inventory changed")
    return files


def validate_results(files: dict[str, bytes]) -> None:
    rows = [line.split("\t") for line in files["results.tsv"].decode().splitlines()]
    require(len(rows) == 25 and all(len(row) == 4 for row in rows),
        "source result row inventory changed")
    require(all(row[0] == "PASS" and row[1] == "0" and row[2].isdigit() for row in rows),
        "source artifact contains a failed or malformed gate")
    require(tuple(row[3] for row in rows) == COMMANDS, "source command inventory changed")
    require(all(files[f"{index:02d}.log"] for index in range(1, 26)),
        "source artifact has an empty command log")
    require(files["started.txt"] == b"started\n", "source start marker changed")


def validate(repo: Path, artifact: bytes) -> dict:
    repo = repo.resolve()
    files = zip_files(artifact)
    validate_results(files)
    require(git(repo, "rev-parse", "HEAD").strip() == SEAL, "checkout is not final seal")
    require(git(repo, "rev-parse", "HEAD^{tree}").strip() == SEAL_TREE, "seal tree changed")
    chains = ((SEAL, [SEAL, SOURCE]),
              (SOURCE, [SOURCE, PREDECESSOR_SEAL]),
              (PREDECESSOR_SEAL, [PREDECESSOR_SEAL, PREDECESSOR_SOURCE]),
              (PREDECESSOR_SOURCE, [PREDECESSOR_SOURCE, ANCESTOR_SEAL]),
              (ANCESTOR_SEAL, [ANCESTOR_SEAL, ANCESTOR_SOURCE]),
              (ANCESTOR_SOURCE, [ANCESTOR_SOURCE, PRIOR_SEAL]),
              (PRIOR_SEAL, [PRIOR_SEAL, PRIOR_SOURCE]),
              (PRIOR_SOURCE, [PRIOR_SOURCE, OLDER_SEAL]),
              (OLDER_SEAL, [OLDER_SEAL, OLDER_SOURCE]),
              (OLDER_SOURCE, [OLDER_SOURCE, OLDEST_SEAL]),
              (OLDEST_SEAL, [OLDEST_SEAL, OLDEST_SOURCE]),
              (OLDEST_SOURCE, [OLDEST_SOURCE, LEGACY_SEAL]),
              (LEGACY_SEAL, [LEGACY_SEAL, LEGACY_SOURCE]),
              (LEGACY_SOURCE, [LEGACY_SOURCE, DOCUMENTATION_CHECKPOINT]),
              (DOCUMENTATION_CHECKPOINT, [DOCUMENTATION_CHECKPOINT, EARLIER_SEAL, TARGET]))
    for commit, expected in chains:
        require(git(repo, "rev-list", "--parents", "-n", "1", commit).split() == expected,
            "ordered ancestry changed at " + commit)
    trees = ((SOURCE, SOURCE_TREE), (PREDECESSOR_SEAL, PREDECESSOR_SEAL_TREE),
             (PREDECESSOR_SOURCE, PREDECESSOR_SOURCE_TREE),
             (ANCESTOR_SEAL, ANCESTOR_SEAL_TREE),
             (ANCESTOR_SOURCE, ANCESTOR_SOURCE_TREE),
             (PRIOR_SEAL, PRIOR_SEAL_TREE), (PRIOR_SOURCE, PRIOR_SOURCE_TREE),
             (OLDER_SEAL, OLDER_SEAL_TREE), (OLDER_SOURCE, OLDER_SOURCE_TREE),
             (OLDEST_SEAL, OLDEST_SEAL_TREE), (OLDEST_SOURCE, OLDEST_SOURCE_TREE),
             (LEGACY_SEAL, LEGACY_SEAL_TREE), (LEGACY_SOURCE, LEGACY_SOURCE_TREE),
             (DOCUMENTATION_CHECKPOINT, DOCUMENTATION_CHECKPOINT_TREE),
             (FEATURE, FEATURE_TREE), (TARGET, TARGET_TREE), (OLD_TARGET, OLD_TARGET_TREE))
    for commit, expected in trees:
        require(git(repo, "rev-parse", commit + "^{tree}").strip() == expected,
            "tree changed at " + commit)
    identity = [line for line in files["identity.txt"].decode().splitlines() if line]
    require(identity == [SEAL, SOURCE, PREDECESSOR_SEAL, PREDECESSOR_SOURCE,
        ANCESTOR_SEAL, ANCESTOR_SOURCE, PRIOR_SEAL, PRIOR_SOURCE, OLDER_SEAL,
        OLDER_SOURCE, OLDEST_SEAL, OLDEST_SOURCE, LEGACY_SEAL, LEGACY_SOURCE,
        DOCUMENTATION_CHECKPOINT, EARLIER_SEAL, FEATURE, SEAL_TREE,
        SOURCE_TREE, PREDECESSOR_SEAL_TREE, PREDECESSOR_SOURCE_TREE,
        ANCESTOR_SEAL_TREE, ANCESTOR_SOURCE_TREE, PRIOR_SEAL_TREE,
        PRIOR_SOURCE_TREE, OLDER_SEAL_TREE, OLDER_SOURCE_TREE,
        OLDEST_SEAL_TREE, OLDEST_SOURCE_TREE, LEGACY_SEAL_TREE, LEGACY_SOURCE_TREE,
        DOCUMENTATION_CHECKPOINT_TREE,
        "9f9c856590f0eb3ea93d7e6abec0e9c97609b454"],
        "source artifact identity changed")
    expected_refs = ("feature=" + FEATURE + "\ntarget=" + TARGET +
        "\ntarget_tree=" + TARGET_TREE + "\npr_state=open\npr_draft=true\npr_head=" +
        FEATURE + "\npr_base=parameterized-verilog\n").encode()
    require(files["live-refs.txt"] == expected_refs, "source-time live refs changed")
    require(files["audit-repair-paths.txt"].decode().splitlines() ==
        git(repo, "diff", "--name-only", PREDECESSOR_SEAL, SOURCE).splitlines(),
        "audit repair path evidence changed")
    require(files["seal-paths.txt"].decode().splitlines() ==
        git(repo, "diff", "--name-only", SOURCE, SEAL).splitlines() == [CONTRACT, HELPER],
        "seal path evidence changed")
    require(files["target-reconciliation-paths.txt"].decode().splitlines() ==
        git(repo, "diff", "--name-only", TARGET, DOCUMENTATION_CHECKPOINT).splitlines(),
        "target reconciliation path evidence changed")
    require(digest((repo / CONTRACT).read_bytes()) == CONTRACT_SHA256,
        "schema-17 contract changed")
    require(digest(normalized_helper((repo / HELPER).read_bytes())) == HELPER_NORMALIZED_SHA256,
        "schema-17 verifier algorithm changed")

    require(git(repo, "rev-list", "--parents", "-n", "1", TARGET).split() ==
        [TARGET, OLD_TARGET], "target is not the exact direct documentation child")
    require(git(repo, "diff", "--name-status", OLD_TARGET, TARGET).splitlines() ==
        ["M\t" + TARGET_PATH], "target changed more than the reviewed documentation")
    row = git(repo, "ls-tree", TARGET, "--", TARGET_PATH).split()
    require(row == ["100644", "blob", TARGET_BLOB, TARGET_PATH],
        "target documentation mode or blob changed")
    target_bytes = subprocess.check_output(["git", "show", TARGET + ":" + TARGET_PATH], cwd=repo)
    validate_target_bytes(target_bytes)
    require(subprocess.run(["git", "merge-base", "--is-ancestor", TARGET, SEAL], cwd=repo).returncode == 0,
        "current target is not an ancestor of the final seal")
    forward = git(repo, "merge-tree", "--write-tree", SEAL, TARGET).strip()
    reverse = git(repo, "merge-tree", "--write-tree", TARGET, SEAL).strip()
    require(forward == reverse == SEAL_TREE,
        "current target changes the final seal tree or conflicts")
    require(not git(repo, "diff", "--name-only", SEAL, forward).strip(),
        "prospective integration changes final seal bytes")
    require(not git(repo, "status", "--porcelain", "--untracked-files=all"),
        "qualification checkout is dirty")
    return {"schema": 17, "source_run": 35969094981, "source_artifact": 10804655467,
        "source_artifact_sha256": ARTIFACT_SHA256, "source_commands_passed": 25,
        "seal": SEAL, "seal_tree": SEAL_TREE, "source": SOURCE,
        "source_tree": SOURCE_TREE, "feature_predecessor": FEATURE,
        "current_target": TARGET, "current_target_tree": TARGET_TREE,
        "target_change": [TARGET_PATH], "target_blob": TARGET_BLOB,
        "prospective_merge_tree": SEAL_TREE, "target_is_ancestor": True,
        "feature_ref_updated": False, "full_ci_started": False,
        "qualification_claimed": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = validate(args.repo, args.artifact.read_bytes())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print("INCREMENT_59I_SCHEMA17_TARGET_RECONCILIATION_PASS", flush=True)


if __name__ == "__main__":
    main()
