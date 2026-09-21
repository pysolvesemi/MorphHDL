#!/usr/bin/env python3
"""Authenticate one documentation-only target move around qualified 59i source."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import zipfile

SEAL = "9d6d738d32c71ff4359384ae9d87f715bf20ec55"
SEAL_TREE = "3498988dd26e3ee8c64209dec3dc57814dcf4111"
SOURCE = "dafc1c73658f0c0539068001c22dfbb5ff84b2b1"
SOURCE_TREE = "4bf6bbcd1f84223e623b1a272594177550d75422"
FEATURE_PARENT = "883c5d8f088a0e2eab35592cf171d87792d30bf4"
FEATURE_PARENT_TREE = "f2219f49ef53ea3defc3526b0df76e5a67df4993"
OLD_TARGET = "bbae646ba43e6189c69feb308f8decb9b677b15f"
OLD_TARGET_TREE = "c2f6e2abd588a131c5e6909173659935c62e77b7"
TARGET = "ce4a02c11b5ec19777c3d900e7fdc06ebbf6d7cd"
TARGET_TREE = "d59b08a6cc850d35532aaf98856031a2da4ee862"
AGENTS_BLOB = "36abb9e910357b79ab3cd72fac4276b51d6ba9d5"
AGENTS_SHA256 = "1f20a687842ecd9f0f3cc2d7a26c43627fc42468b9011a9ae9b8bec949a2bca3"
MERGE_TREE = "7cf398c5e5bb13a75ed0f0b88ce6606b89ca187d"
ARTIFACT_SHA256 = "ab4efce935a5a6e84a6b18cd0342bdf1c278f2a891604de7d1e17ac4ffa8e664"
MANIFEST_SHA256 = "ee9ced2c0cd0957d7f5f3f859c3a7a9fbba34460d039d641ed36fe70569836d8"
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
        raise RuntimeError("59i target reconciliation: " + detail)


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def git(root: Path, *args: str) -> str:
    result = subprocess.run(["git", "--literal-pathspecs", *args], cwd=root,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
    require(result.returncode == 0, "git " + " ".join(args) + " failed: " +
        result.stderr.decode(errors="replace"))
    return result.stdout.decode()


def validate_target_bytes(raw: bytes) -> None:
    require(len(raw) == 5651 and raw.count(b"\n") == 101,
        "target AGENTS.md size or line inventory changed")
    require(digest(raw) == AGENTS_SHA256, "target AGENTS.md bytes changed")
    require(raw.startswith(b"# MorphHDL project instructions\n"), "target instructions heading changed")


def zip_files(raw: bytes) -> dict[str, bytes]:
    require(digest(raw) == ARTIFACT_SHA256, "source artifact digest changed")
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "duplicate artifact member")
        require(all(not name.startswith("/") and ".." not in Path(name).parts for name in names),
            "unsafe artifact member")
        files = {name: archive.read(name) for name in names if not name.endswith("/")}
    expected = {f"{index:02d}.log" for index in range(1, 26)} | {
        "identity.txt", "live-refs.txt", "results.tsv", "runtime-repair-paths.txt",
        "seal-paths.txt", "started.txt"}
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
    require(git(repo, "rev-parse", "HEAD").strip() == SEAL, "checkout is not qualified seal")
    require(git(repo, "rev-parse", "HEAD^{tree}").strip() == SEAL_TREE, "seal tree changed")
    require(git(repo, "rev-list", "--parents", "-n", "1", SEAL).split() == [SEAL, SOURCE],
        "seal parent changed")
    require(git(repo, "rev-list", "--parents", "-n", "1", SOURCE).split() ==
        [SOURCE, FEATURE_PARENT], "source parent changed")
    require(git(repo, "rev-parse", SOURCE + "^{tree}").strip() == SOURCE_TREE,
        "source tree changed")
    require(git(repo, "rev-parse", FEATURE_PARENT + "^{tree}").strip() == FEATURE_PARENT_TREE,
        "feature predecessor tree changed")
    identity = [line for line in files["identity.txt"].decode().splitlines() if line]
    require(identity == [SEAL, SOURCE, FEATURE_PARENT, SEAL_TREE, FEATURE_PARENT_TREE],
        "source artifact identity changed")
    require(files["live-refs.txt"] ==
        ("feature=" + FEATURE_PARENT + "\ntarget=" + OLD_TARGET + "\n").encode(),
        "source-time live refs changed")
    repair_paths = git(repo, "diff", "--name-only", FEATURE_PARENT, SOURCE).splitlines()
    require(len(repair_paths) == 27 and files["runtime-repair-paths.txt"].decode().splitlines() == repair_paths,
        "source repair path evidence changed")
    require(files["seal-paths.txt"].decode().splitlines() ==
        git(repo, "diff", "--name-only", SOURCE, SEAL).splitlines() == [CONTRACT, HELPER],
        "seal path evidence changed")
    require(digest((repo / CONTRACT).read_bytes()) == MANIFEST_SHA256,
        "qualified schema-6 manifest changed")

    require(git(repo, "rev-parse", OLD_TARGET + "^{tree}").strip() == OLD_TARGET_TREE,
        "source-time target tree changed")
    require(git(repo, "rev-list", "--parents", "-n", "1", TARGET).split() ==
        [TARGET, OLD_TARGET], "new target is not the exact direct documentation child")
    require(git(repo, "rev-parse", TARGET + "^{tree}").strip() == TARGET_TREE,
        "new target tree changed")
    require(git(repo, "diff", "--name-status", OLD_TARGET, TARGET).splitlines() == ["A\tAGENTS.md"],
        "new target changed more than root AGENTS.md")
    agents_row = git(repo, "ls-tree", TARGET, "AGENTS.md").split()
    require(agents_row == ["100644", "blob", AGENTS_BLOB, "AGENTS.md"],
        "target AGENTS.md mode or blob changed")
    agents = subprocess.check_output(["git", "cat-file", "blob", AGENTS_BLOB], cwd=repo)
    validate_target_bytes(agents)

    forward = git(repo, "merge-tree", "--write-tree", SEAL, TARGET).strip()
    reverse = git(repo, "merge-tree", "--write-tree", TARGET, SEAL).strip()
    require(forward == reverse == MERGE_TREE, "prospective merge tree changed or conflicted")
    require(git(repo, "diff", "--name-status", SEAL, MERGE_TREE).splitlines() == ["A\tAGENTS.md"],
        "prospective merge changes more than root AGENTS.md")
    require(git(repo, "show", MERGE_TREE + ":AGENTS.md").encode() == agents,
        "prospective merge does not retain exact target instructions")
    for path in (HELPER, CONTRACT):
        require(git(repo, "rev-parse", SEAL + ":" + path).strip() ==
            git(repo, "rev-parse", MERGE_TREE + ":" + path).strip(),
            "prospective merge changed qualified schema-6 source: " + path)
    require(not git(repo, "status", "--porcelain", "--untracked-files=all"),
        "qualification checkout is dirty")
    return {
        "schema": 1, "source_run": 35556994859, "source_artifact": 10622841322,
        "source_artifact_sha256": ARTIFACT_SHA256, "source_commands_passed": 25,
        "seal": SEAL, "seal_tree": SEAL_TREE, "source": SOURCE,
        "source_tree": SOURCE_TREE, "feature_predecessor": FEATURE_PARENT,
        "source_time_target": OLD_TARGET, "current_target": TARGET,
        "current_target_tree": TARGET_TREE, "target_change": ["AGENTS.md"],
        "agents_blob": AGENTS_BLOB, "agents_sha256": AGENTS_SHA256,
        "prospective_merge_tree": MERGE_TREE, "feature_ref_updated": False,
        "full_ci_started": False, "qualification_claimed": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = validate(args.repo, args.artifact.read_bytes())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print("INCREMENT_59I_TARGET_DOCUMENTATION_RECONCILIATION_PASS", flush=True)


if __name__ == "__main__":
    main()
