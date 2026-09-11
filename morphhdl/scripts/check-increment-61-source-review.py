#!/usr/bin/env python3
"""Exact-source review for Increment 61 and its inherited-audit adapters."""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "morphhdl/contracts/increment-61-source-review.json"
CONTRACT_SHA256 = "be689dedee91c2412ae4595598e4932138e92b72f071c0d80d58205809c46f8e"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git(*args: str, root: Path = ROOT, text: bool = False):
    return subprocess.check_output(["git", *args], cwd=root, text=text)


def load_contract() -> dict:
    raw = CONTRACT.read_bytes()
    require(sha256(raw) == CONTRACT_SHA256, "Increment 61 source-review contract digest changed")

    def unique(pairs):
        keys = [key for key, _ in pairs]
        require(len(keys) == len(set(keys)), "duplicate Increment 61 contract field")
        return dict(pairs)

    value = json.loads(raw, object_pairs_hook=unique)
    require(isinstance(value, dict), "Increment 61 source-review contract is not an object")
    require(value.get("schema") == 1 and value.get("increment") == "61",
            "Increment 61 source-review contract identity changed")
    base = value.get("base_commit")
    require(isinstance(base, str) and len(base) == 40 and all(c in "0123456789abcdef" for c in base),
            "invalid Increment 61 base commit")
    integrated = value.get("integrated_target_commit")
    require(
        isinstance(integrated, str) and len(integrated) == 40 and
        all(c in "0123456789abcdef" for c in integrated),
        "invalid Increment 61 integrated target commit"
    )
    files = value.get("reviewed_files")
    audit = value.get("audit_paths")
    require(isinstance(files, list) and files, "empty Increment 61 reviewed file set")
    require(isinstance(audit, list) and audit, "empty Increment 61 audit path set")
    paths = [entry.get("path") for entry in files]
    require(all(isinstance(path, str) and path and not path.startswith("/") and ".." not in Path(path).parts
                for path in paths + audit), "unsafe Increment 61 contract path")
    require(len(paths) == len(set(paths)) and len(audit) == len(set(audit)),
            "duplicate Increment 61 contract path")
    require(not set(paths).intersection(audit), "reviewed and audit path sets overlap")
    return value


def integrated_target_is_ancestor(contract: dict, root: Path = ROOT) -> bool:
    return subprocess.run(
        ["git", "merge-base", "--is-ancestor", contract["integrated_target_commit"], "HEAD"],
        cwd=root,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL
    ).returncode == 0


def expected_after_sha(entry: dict, integrated: bool) -> str:
    alternate = entry.get("after_sha256_integrated")
    if alternate is not None:
        require(
            isinstance(alternate, str) and len(alternate) == 64 and
            all(c in "0123456789abcdef" for c in alternate),
            f"invalid integrated Increment 61 digest for {entry.get('path')}"
        )
    return alternate if integrated and alternate is not None else entry["after_sha256"]


def verify(root: Path = ROOT) -> None:
    contract = load_contract()
    base = contract["base_commit"]
    integrated = integrated_target_is_ancestor(contract, root)
    subprocess.run(["git", "merge-base", "--is-ancestor", base, "HEAD"], cwd=root, check=True)
    scope_base = contract["integrated_target_commit"] if integrated else base
    changed_raw = git("diff", "--no-renames", "--name-only", "-z", scope_base, "HEAD", root=root)
    changed = {item.decode() for item in changed_raw.split(b"\0") if item}
    expected = {entry["path"] for entry in contract["reviewed_files"]} | set(contract["audit_paths"])
    require(changed == expected,
            "Increment 61 changed-file inventory differs: " +
            f"missing={sorted(expected - changed)} extra={sorted(changed - expected)}")

    reviewed_by_path = {entry["path"]: entry for entry in contract["reviewed_files"]}
    production = {path for path in changed if any(path.startswith(prefix)
                                                  for prefix in contract["production_prefixes"])}
    require(production == set(contract["production_paths"]),
            "Increment 61 production source inventory differs")

    for path, entry in reviewed_by_path.items():
        file = root / path
        require(file.is_file() and not file.is_symlink(), f"missing or linked Increment 61 file: {path}")
        require(sha256(file.read_bytes()) == expected_after_sha(entry, integrated),
                f"Increment 61 reviewed file changed: {path}")
        mode = git("ls-files", "--stage", "--", path, root=root, text=True).strip().split()
        require(mode and mode[0] == entry["mode"], f"Increment 61 file mode changed: {path}")
        status = entry["status"]
        if status == "modified":
            before = git("show", f"{base}:{path}", root=root)
            require(sha256(before) == entry["before_sha256"],
                    f"Increment 61 predecessor changed: {path}")
        elif status == "added":
            absent = subprocess.run(["git", "cat-file", "-e", f"{base}:{path}"], cwd=root,
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode != 0
            require(absent and entry["before_sha256"] is None,
                    f"Increment 61 added-file predecessor exists: {path}")
        else:
            raise RuntimeError(f"unsupported Increment 61 status for {path}: {status}")

    require("oneFilePerComponent" in (root / "morphhdl/src/main/scala/morphhdl/MorphVerilog.scala").read_text(),
            "Increment 61 MorphVerilog entrypoint marker is missing")
    require("MorphPerComponentPublication.capture" in
            (root / "morphhdl/src/main/scala/morphhdl/MorphVerilog.scala").read_text(),
            "Increment 61 exact native split capture is missing")
    require("MORPHDL-ONE-FILE-PUBLISH-MANAGED-MODIFIED" in
            (root / "morphhdl/src/main/scala/morphhdl/MorphPerComponentPublication.scala").read_text(),
            "Increment 61 managed-output fail-closed guard is missing")
    target_mode = "integrated-target" if integrated else "feature-head"
    print(
        f"Increment 61 exact source review PASS ({len(reviewed_by_path)} reviewed files, "
        f"{len(production)} production files, {target_mode})"
    )


def self_test() -> None:
    contract = load_contract()
    integrated = integrated_target_is_ancestor(contract, ROOT)
    cases = 0
    with tempfile.TemporaryDirectory(prefix="morphhdl-increment-61-source-review-"):
        for entry in contract["reviewed_files"]:
            original = (ROOT / entry["path"]).read_bytes()
            expected = expected_after_sha(entry, integrated)
            require(sha256(original) == expected,
                    "self-test started from an unreviewed file")
            mutated = original + b"\n// increment-61-source-review-mutation\n"
            require(sha256(mutated) != expected,
                    "Increment 61 source mutation was not detected")
            cases += 1
        bad = json.loads(CONTRACT.read_text())
        bad["reviewed_files"].append(dict(bad["reviewed_files"][0]))
        paths = [entry["path"] for entry in bad["reviewed_files"]]
        require(len(paths) != len(set(paths)), "duplicate-path negative control did not mutate inventory")
        cases += 1
        bad["audit_paths"].append("../escape")
        require(".." in Path(bad["audit_paths"][-1]).parts,
                "path-traversal negative control did not mutate inventory")
        cases += 1
    require(cases == len(contract["reviewed_files"]) + 2,
            "Increment 61 source-review self-test inventory changed")
    print(f"Increment 61 source-review self-test PASS ({cases} rejection controls)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--print-base", action="store_true")
    args = parser.parse_args()
    if args.print_base:
        print(load_contract()["base_commit"])
    elif args.self_test:
        self_test()
    else:
        verify()


if __name__ == "__main__":
    main()

# Final-head qualification trigger after recursive-owner publication repair.
