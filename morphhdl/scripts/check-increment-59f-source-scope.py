#!/usr/bin/env python3
"""Restore only the reviewed 59f publisher edits for inherited source gates.

The complete before/after blobs and unique exact spans are checked. Restoring a
file does not accept its historical contract: the caller still compares every
byte with its original qualification baseline and runs its original audits.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path

BASE = "5a669d32095ee722c313bd069b771e7c350a1f81"
CONTRACT = "morphhdl/contracts/increment-59f-publisher-edits.json"
CONTRACT_SHA256 = "5fa83b2aae310305db22829e157dd5edf52387428f10ef4718c29f0ae01de0c2"
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


def restore_59f_source(root: Path, path: str, source: str) -> str:
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
