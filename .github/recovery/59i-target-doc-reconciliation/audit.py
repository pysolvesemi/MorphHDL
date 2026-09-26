#!/usr/bin/env python3
"""Authenticate the schema-19 source evidence and exact PR194 target integration."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import zipfile

SEAL = "d6b532f5dd8639649fea0434678de995ce573a62"
SEAL_TREE = "d5dc4ed5f0eb0f5c950ce6f8f50143951e6454a1"
SOURCE = "3cd7e795893035c062f432e2d5c845c8c3a9cffe"
SOURCE_TREE = "c52537a2c604c7b14d5971d0c0c84498fe63d1b1"
FEATURE = "fb49648d8b7f05310d0907381c364c2e11882645"
FEATURE_TREE = "eea3ee75833a9fa418c0da1ff47b0ba0c95ff7e2"
TARGET = "db54d01e5b21c7664f7a0de3795f061d77a3d259"
TARGET_TREE = "ed73aee1ca0c667a1c32b181d95c431c22ea3719"
COMMON_BASE = "09880c538c4cf83022f4a1bb1dd16b43ea81a751"
COMMON_BASE_TREE = "6216cf799cc51c5a5f815d08f16e435c6b48ddc7"
SOURCE_CONTROLLER = "8062794d0ef6f6d595c65c125598f697bf175dd6"
ARTIFACT_SHA256 = "cca5882db838d3c0328ed1296be50668471d6add9dd91b4e9a85c246afdc1a5f"
CONTRACT_SHA256 = "920e52d51c293eb7bd3f614573dfaf1359617a90953bef3578238fbf020c56b3"
HELPER_NORMALIZED_SHA256 = "8afd49e4b3951acdfe604a0476805d5001838dd37c5d720fd9fad9d0f7c13431"
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
        raise RuntimeError("59i schema-19 target reconciliation: " + detail)


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def git(root: Path, *args: str, binary: bool = False):
    result = subprocess.run(["git", "--literal-pathspecs", *args], cwd=root,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
    require(result.returncode == 0, "git " + " ".join(args) + " failed: " +
        result.stderr.decode(errors="replace"))
    return result.stdout if binary else result.stdout.decode()


def normalized_helper(raw: bytes) -> bytes:
    pattern = rb'^CONTRACT_SHA256 = "[^"\n]+"$'
    require(len(re.findall(pattern, raw, re.M)) == 1, "ambiguous helper seal")
    return re.sub(pattern, b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, flags=re.M)


def zip_files(raw: bytes) -> dict[str, bytes]:
    require(digest(raw) == ARTIFACT_SHA256, "source artifact digest changed")
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "duplicate artifact member")
        require(all(not n.startswith("/") and ".." not in Path(n).parts for n in names),
            "unsafe artifact member")
        files = {n: archive.read(n) for n in names if not n.endswith("/")}
    expected = {f"{i:02d}.log" for i in range(1, 26)} | {
        "identity.txt", "live-refs.txt", "results.tsv", "audit-repair-paths.txt",
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
    require(all(files[f"{i:02d}.log"] for i in range(1, 26)),
        "source artifact has an empty command log")
    require(files["started.txt"] == b"started\n", "source start marker changed")


def tree_entry(root: Path, revision: str, path: str) -> tuple[str, str] | None:
    out = git(root, "ls-tree", "-z", revision, "--", path, binary=True)
    if not out:
        return None
    metadata, found = out[:-1].split(b"\t", 1)
    mode, kind, sha = metadata.decode().split()
    require(kind == "blob" and found.decode() == path, "unexpected tree entry for " + path)
    return mode, sha


def validate_target_records(root: Path, contract: dict, expected_paths: list[str]) -> None:
    target = contract.get("target_integration")
    require(isinstance(target, dict), "missing target integration")
    require(target.get("common_base") == COMMON_BASE and
            target.get("common_base_tree") == COMMON_BASE_TREE and
            target.get("target_commit") == TARGET and target.get("target_tree") == TARGET_TREE,
            "target integration identity changed")
    records = target.get("files")
    require(isinstance(records, list) and len(records) == 26, "target record count changed")
    require([r.get("path") for r in records] == expected_paths,
            "target record order or paths changed")
    for record in records:
        path = record["path"]
        # The integration record binds each reviewed target input to its exact
        # composed source output.  COMMON_BASE determines the closed path set;
        # it is not the per-record byte predecessor.
        before = tree_entry(root, TARGET, path)
        after = tree_entry(root, SOURCE, path)
        require(before is not None and after is not None, "target record missing blob: " + path)
        before_raw = git(root, "show", TARGET + ":" + path, binary=True)
        after_raw = git(root, "show", SOURCE + ":" + path, binary=True)
        require(record.get("before_mode") == before[0] and
                record.get("before_sha256") == digest(before_raw) and
                record.get("after_mode") == after[0] and
                record.get("after_sha256") == digest(after_raw),
                "target record bytes or mode changed: " + path)


def validate(repo: Path, artifact: bytes) -> dict:
    repo = repo.resolve()
    files = zip_files(artifact)
    validate_results(files)
    require(git(repo, "rev-parse", "HEAD").strip() == SEAL, "checkout is not final seal")
    for commit, tree in ((SEAL, SEAL_TREE), (SOURCE, SOURCE_TREE),
                         (FEATURE, FEATURE_TREE),
                         (TARGET, TARGET_TREE), (COMMON_BASE, COMMON_BASE_TREE)):
        require(git(repo, "rev-parse", commit + "^{tree}").strip() == tree,
                "tree changed at " + commit)
    require(git(repo, "rev-list", "--parents", "-n", "1", SEAL).split() == [SEAL, SOURCE],
            "seal ancestry changed")
    require(git(repo, "rev-list", "--parents", "-n", "1", SOURCE).split() ==
            [SOURCE, FEATURE], "source ancestry changed")

    identity = files["identity.txt"].decode().splitlines()
    require(identity == [SEAL, SOURCE, FEATURE, TARGET, SEAL_TREE,
        SOURCE_TREE, FEATURE_TREE, TARGET_TREE],
        "source artifact identity changed")
    expected_refs = ("feature=" + FEATURE + "\ntarget=" + TARGET +
        "\nrecovery=" + SOURCE_CONTROLLER +
        "\npr_state=open\npr_draft=true\npr_head=" + FEATURE +
        "\npr_base=parameterized-verilog\n").encode()
    require(files["live-refs.txt"] == expected_refs, "source-time live refs changed")
    repair_paths = git(repo, "diff", "--name-only", FEATURE, SOURCE).splitlines()
    seal_paths = git(repo, "diff", "--name-only", SOURCE, SEAL).splitlines()
    target_paths = git(repo, "diff", "--name-only", COMMON_BASE, TARGET).splitlines()
    require(files["audit-repair-paths.txt"].decode().splitlines() == repair_paths and
            len(repair_paths) == 17, "repair path evidence changed")
    require(files["seal-paths.txt"].decode().splitlines() == seal_paths == [CONTRACT, HELPER],
            "seal path evidence changed")
    require(len(target_paths) == 26, "target composition path count changed")

    contract_raw = (repo / CONTRACT).read_bytes()
    require(digest(contract_raw) == CONTRACT_SHA256, "schema-19 contract changed")
    contract = json.loads(contract_raw)
    require(contract.get("schema_version") == 19 and
            contract.get("source_commit") == SOURCE and contract.get("source_tree") == SOURCE_TREE,
            "schema-19 source binding changed")
    require(len(contract.get("files", [])) == 427, "source record count changed")
    require(digest(normalized_helper((repo / HELPER).read_bytes())) ==
            HELPER_NORMALIZED_SHA256, "schema-19 verifier algorithm changed")
    validate_target_records(repo, contract, target_paths)

    require(git(repo, "merge-base", "--is-ancestor", TARGET, SEAL) == "",
            "target is not an ancestor of the seal")
    prospective = git(repo, "merge-tree", "--write-tree", TARGET, SEAL).strip()
    require(prospective == SEAL_TREE, "prospective merge is not tree-preserving")
    require(not git(repo, "status", "--porcelain", "--untracked-files=all"),
            "qualified checkout is not clean")
    return {"schema": 19, "seal": SEAL, "source": SOURCE, "feature": FEATURE,
            "target": TARGET, "prospective_tree": prospective,
            "source_records": 427, "target_records": 26,
            "source_commands": len(COMMANDS), "artifact_sha256": ARTIFACT_SHA256}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = validate(args.repo, args.artifact.read_bytes())
    args.output.write_text(json.dumps(result, sort_keys=True, indent=2) + "\n")
    print("SCHEMA19_TARGET_RECONCILIATION_PASS source=25 records=427 target_records=26 tree=" +
          result["prospective_tree"])


if __name__ == "__main__":
    main()
