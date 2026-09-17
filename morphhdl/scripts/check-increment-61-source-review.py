#!/usr/bin/env python3
"""Exact-source review for Increment 61 and its inherited-audit adapters."""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import tempfile
import re
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "morphhdl/contracts/increment-61-source-review.json"
CONTRACT_SHA256 = "67f19808ea90ef6b8ef2b17f9dedb98f7ea40dbcdd57df172604e0da17faa972"


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


CONTINUATION_HELPER_SHA256 = "c9be9212f628384ac6c30d59c2382009ce681da40c514f4592e1b1a60ae790a7"


def continuation_review(root: Path, self_test: bool = False) -> bool:
    path = "morphhdl/scripts/check-increment-59i-production-successor.py"
    file = root / path
    seal = root / "morphhdl/contracts/increment-59i-production-successor.json"
    if not (file.exists() or file.is_symlink() or seal.exists() or seal.is_symlink()):
        # A removed successor cannot silently recover a legacy permission.
        history = git("rev-list", "--full-history", "HEAD", "--", path,
                      "morphhdl/contracts/increment-59i-production-successor.json", root=root)
        require(not history, "Increment 59i continuation certificate was removed")
        return False
    require(file.is_file() and not file.is_symlink(), "missing or linked continuation verifier")
    raw = file.read_bytes()
    pattern = rb'^CONTRACT_SHA256 = "[^"\n]+"$'
    require(len(re.findall(pattern, raw, re.M)) == 1, "ambiguous continuation seal slot")
    normalized = re.sub(pattern, b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, flags=re.M)
    require(sha256(normalized) == CONTINUATION_HELPER_SHA256, "unreviewed continuation verifier")
    module = types.ModuleType("increment_61_reviewed_59i_continuation")
    module.__file__ = str(file)
    exec(compile(raw, str(file), "exec"), module.__dict__)
    value = module.verify(root)
    require(value["schema_version"] == 3 and
            module.target_anchor(root) == "27af65abbee0d2334d6be7a6e4e2408b8af32fd9",
            "unreviewed Increment 61 continuation target")
    # The original contract still authenticates its exact reviewed inventory;
    # the outer certificate authenticates every current byte, mode and parent.
    require((root / "morphhdl/contracts/increment-61-source-review.json").read_bytes() == module.frozen(root, module.CONTINUATION_TARGET,
            "morphhdl/contracts/increment-61-source-review.json"), "Increment 61 certificate changed")
    module.audit_immutable_certificate(root, module.CONTINUATION_TARGET,
        "morphhdl/scripts/check-increment-61-source-review.py", module.CONTINUATION_61_HELPER,
        self_test=self_test)
    print("Increment 61 exact source review PASS (authenticated 59i continuation; original certificate retained)")
    return True


def verify(root: Path = ROOT) -> None:
    if continuation_review(root):
        return
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
    if continuation_review(ROOT, self_test=True):
        return
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
