#!/usr/bin/env python3
"""Restore exact reviewed 59e/59f publisher edits for inherited source gates.

The complete before/after blobs and unique exact spans are checked. Restoring a
file does not accept its historical contract: the caller still compares every
byte with its original qualification baseline and runs its original audits.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import subprocess
from pathlib import Path

BASE = "5a669d32095ee722c313bd069b771e7c350a1f81"
CONTRACT = "morphhdl/contracts/increment-59f-publisher-edits.json"
CONTRACT_SHA256 = "a7413d5d50fcb9a073cdd40a980c1476dddb9ef7d727635d045565b39b5b3f9a"
FALLBACK = "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
BOUNDARY_CHECKER = "morphhdl/scripts/check-increment-60e-signedness-boundaries.py"
PATHS = frozenset((FALLBACK, BOUNDARY_CHECKER))


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError(detail)


def digest(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


def contract(root: Path) -> dict:
    raw = (root / CONTRACT).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == CONTRACT_SHA256,
            "59f reviewed publisher manifest changed")
    data = json.loads(raw)
    require(set(data) == {"base", "files"} and data["base"] == BASE,
            "59f restoration baseline/schema changed")
    entries = data["files"]
    require(len(entries) == len(PATHS) and {entry["path"] for entry in entries} == PATHS,
            "59f reviewed publisher path inventory changed")
    return data


def restore_rollout(root: Path, path: str, source: str) -> str:
    helper = root / "morphhdl/scripts/check-increment-60g-source-scope.py"
    if not helper.is_file():
        return source
    spec = importlib.util.spec_from_file_location("rollout_scope", helper)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module.restore_60g_source(root, path, source)


def restore_59f_source(root: Path, path: str, source: str) -> str:
    source = restore_rollout(root, path, source)
    if path not in PATHS:
        return source
    data = contract(root)
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"],
                   cwd=root, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    entry = next(item for item in data["files"] if item["path"] == path)
    require(digest(source) == entry["after_sha256"],
            "unreviewed source change outside 59f spans: " + path)
    for edit in reversed(entry["edits"]):
        require(edit["before"] and edit["after"] and source.count(edit["after"]) == 1,
                "missing/duplicate 59f span in " + path)
        source = source.replace(edit["after"], edit["before"], 1)
    require(digest(source) == entry["before_sha256"],
            "59f restored publisher blob differs: " + path)
    baseline = subprocess.check_output(["git", "show", BASE + ":" + path], cwd=root, text=True)
    require(source == baseline, "59f publisher restoration differs from recorded baseline: " + path)
    return source


def source_scope(root: Path) -> None:
    contract(root)
    for path in sorted(PATHS):
        restore_59f_source(root, path, (root / path).read_text())
    print("59f exact reviewed publisher spans and complete before/after blobs PASS", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    source_scope(parser.parse_args().root)
