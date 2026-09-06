#!/usr/bin/env python3
"""Exact 60g publication-only scope; restore sealed legacy oracle configuration.

This never restores arithmetic edits, widens native authority, edits an RTL
oracle, or treats an earlier CI run as current qualification.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

BASE = "b25e367d99604e61b8f2c895b2c51ca1ab90d423"
CONTRACT = "morphhdl/contracts/increment-60g-publication-edits.json"
CONTRACT_SHA256 = "13abac049d890fa159238ae89b91eb863da5aa676d9c0d74a4822e315ddd6ac8"
PATHS = frozenset(['morphhdl/scripts/check-increment-59f-source-scope.py', 'morphhdl/scripts/check-increment-60c-signed-declarations.py', 'morphhdl/scripts/check-increment-60d-pure-sint-casts.py', 'morphhdl/scripts/check-increment-60e-signedness-boundaries.py', 'morphhdl/src/test/scala/nativeapplication/SIntSignedDeclarationsFixture.scala', 'morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala'])
PRODUCTION = {
    "morphhdl/src/main/scala/morphhdl/MorphSignedDeclarations.scala": "2729b023267fbc999768b460195c556ed2b5508a277082ab369d5dad5bffcbcc",
    "morphhdl/src/main/scala/morphhdl/MorphSignedCasts.scala": "98321693cca463acf87989b44ad6ea5bc187b53a8ef88491fc1b3853aa5de9b9",
    "morphhdl/src/main/scala/morphhdl/MorphVerilog.scala": "c860299df12f3d34bd42685b75ffad1b4d67bc7715e93f269acd72c37942bd23"
}
QUALIFICATION = {
    "morphhdl/src/test/scala/morphhdl/SignednessCompatibilityTests.scala": "4a29efa13f61b30828cac42ef8a5764d826273193fe4563c156218c7bfc6c87d",
    "morphhdl/src/test/scala/nativeapplication/DefaultSignedVerilogArtifactWriter.scala": "24ce6b6491ede2da141c2fbf7f4f6ebfda827c141242adc9f3c33b024f3a6a29"
}
ORACLE = "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala"


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError(detail)


def digest(source: str) -> str:
    return hashlib.sha256(source.encode()).hexdigest()


def contract(root: Path) -> dict:
    raw = (root / CONTRACT).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == CONTRACT_SHA256,
            "60g reviewed publication manifest changed")
    data = json.loads(raw)
    require(set(data) == {"base", "files"} and data["base"] == BASE,
            "60g restoration baseline/schema changed")
    require(len(data["files"]) == len(PATHS) and
            {entry["path"] for entry in data["files"]} == PATHS,
            "60g publication path inventory changed")
    return data


def restore_entry(entry: dict, source: str) -> str:
    # Nested historical guards may pass an already-restored complete blob.
    # The top-level 60g gate below still requires every actual file's after hash.
    if digest(source) == entry["before_sha256"]:
        return source
    require(digest(source) == entry["after_sha256"],
            "sealed oracle/authority/contract changed: sealed writer/checker changed: "
            "unreviewed source change outside 59f spans "
            "or 60g publication spans: " + entry["path"])
    for edit in reversed(entry["edits"]):
        require(edit["before"] and edit["after"] and source.count(edit["after"]) == 1,
                "missing/duplicate 60g publication span: " + entry["path"])
        source = source.replace(edit["after"], edit["before"], 1)
    require(digest(source) == entry["before_sha256"],
            "60g restored complete source differs: " + entry["path"])
    return source


def restore_60g_source(root: Path, path: str, source: str) -> str:
    if path not in PATHS:
        return source
    entry = next(e for e in contract(root)["files"] if e["path"] == path)
    return restore_entry(entry, source)


def oracle_only(root: Path) -> None:
    source = restore_60g_source(root, ORACLE, (root / ORACLE).read_text())
    actual = subprocess.check_output(["git", "hash-object", "--stdin"],
                                     input=source, text=True, cwd=root).strip()
    require(actual == "84ed2baf743d2c47f07b6e76ddc9843fbb5fe910",
            "independent 60a fixture changed")
    print("60g explicit legacy selection restores the exact immutable 60a fixture PASS", flush=True)


def source_scope(root: Path) -> None:
    def git(*args: str) -> str:
        return subprocess.check_output(["git", *args], cwd=root, text=True)
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    changed = {p for p in git("diff", "--no-renames", "--name-only", BASE).splitlines()
               if "/src/main/" in "/" + p}
    require(changed == set(PRODUCTION), "60g production exceeds three publication config files: " + str(sorted(changed)))
    untracked = {p for p in git("ls-files", "--others").splitlines() if "/src/main/" in "/" + p}
    require(not untracked, "untracked 60g production source: " + str(sorted(untracked)))
    for path, expected in {**PRODUCTION, **QUALIFICATION}.items():
        file = root / path
        require(file.is_file() and not file.is_symlink(), "missing/linked reviewed 60g source: " + path)
        require(digest(file.read_text()) == expected, "60g reviewed source bytes differ: " + path)
        stage = git("ls-files", "--stage", "--", path).split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == path,
                "60g reviewed source must be uniquely tracked: " + path)
    for entry in contract(root)["files"]:
        path = entry["path"]
        source = (root / path).read_text()
        require(digest(source) == entry["after_sha256"], "60g reviewed current source differs: " + path)
        require(restore_entry(entry, source) == git("show", BASE + ":" + path),
                "60g source restoration differs from its recorded base: " + path)
    native = git("diff", "--name-only", BASE, "--", "core/src/main", "lib/src/main",
                 "idslplugin/src/main", "sim/src/main", "morphhdl/contracts/native-source-preservation.json",
                 "morphhdl/contracts/typed-native-source-overlay.json")
    require(not native.strip(), "60g must not change native implementation/approved manifest: " + native)
    oracle_only(root)
    print("60g three-file publication policy, sealed fixture selection and native-zero delta PASS", flush=True)


def self_test(root: Path) -> None:
    rejected = 0
    for entry in contract(root)["files"]:
        after = (root / entry["path"]).read_text()
        before = restore_entry(entry, after)
        require(restore_entry(entry, before) == before, "nested restoration is not idempotent")
        for bad in (after + "\n// unrelated edit\n", after + entry["edits"][0]["after"],
                    after.replace(entry["edits"][0]["after"], "MUTATED", 1)):
            try:
                restore_entry(entry, bad)
            except RuntimeError:
                rejected += 1
            else:
                raise RuntimeError("60g restoration accepted mutation: " + entry["path"])
    require(rejected == 3 * len(PATHS), "incomplete source mutation controls")
    print(f"60g {len(PATHS)} exact restorations and {rejected} source mutation rejections PASS", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--oracle-only", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test(args.root)
    elif args.oracle_only:
        oracle_only(args.root)
    else:
        source_scope(args.root)
