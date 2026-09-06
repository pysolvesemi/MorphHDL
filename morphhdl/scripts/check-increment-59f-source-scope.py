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
WIDTH_59D_CONTRACT = "morphhdl/contracts/increment-59d-width-publication-edits.json"
WIDTH_59D_CONTRACT_SHA256 = "5cc4a878be4d9b853182ab51f5d5d4337387cab08f73cee6a5b8e164945e2fa2"
REVIEW_59D = "morphhdl/contracts/increment-59d-production-review.json"
CHECKER_59D_EDITS_SHA256 = "3fe12841116bb69c44408df8c02e2b3f03aff0469d09f6d0ce550b101d3d0eb8"
ZERO_59D59F_BASE = "c85659a20d428dd58cc6116c12c8b24418c37722"
ZERO_59D59F_CONTRACT = "morphhdl/contracts/increment-59d-59f-zero-edits.json"
ZERO_59D59F_SHA256 = "24f99ef636cb303bed133bb12a21c3ab53c3d08f3352ad3f517f310e601b5f1b"


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


def restore_59d59f_zero(root: Path, source: str) -> str:
    """Restore the single joint zero-owner adapter after the seventeen width seams."""
    path = root / ZERO_59D59F_CONTRACT
    require(path.is_file() and not path.is_symlink(), "missing regular 59d/59f zero-owner review")
    raw = path.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == ZERO_59D59F_SHA256,
            "59d/59f reviewed zero-owner manifest changed")
    data = json.loads(raw)
    require(set(data) == {"base", "files"} and data["base"] == ZERO_59D59F_BASE and
            len(data["files"]) == 1, "59d/59f zero-owner restoration baseline/schema changed")
    entry = data["files"][0]
    require(set(entry) == {"path", "before_sha256", "after_sha256", "edits"} and
            entry["path"] == FALLBACK and len(entry["edits"]) == 1,
            "59d/59f zero-owner restoration exceeds its exact fallback scope")
    edit = entry["edits"][0]
    require(set(edit) == {"id", "before", "after"} and edit["id"] == "native-zero-width-owner" and
            edit["before"] and edit["after"], "59d/59f zero-owner restoration span changed")
    require(digest(source) == entry["after_sha256"],
            "unreviewed source change outside 59d/59f zero-owner span")
    require(source.count(edit["after"]) == 1, "missing/duplicate 59d/59f zero-owner span")
    source = source.replace(edit["after"], edit["before"], 1)
    require(digest(source) == entry["before_sha256"], "59d/59f restored zero-owner blob differs")
    baseline = subprocess.check_output(["git", "show", ZERO_59D59F_BASE + ":" + FALLBACK],
                                       cwd=root, text=True)
    require(source == baseline, "59d/59f zero-owner restoration differs from frozen 59f")
    return source


def restore_59f_source(root: Path, path: str, source: str) -> str:
    if path not in PATHS:
        return source
    data = contract(root)
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"],
                   cwd=root, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    entry = next(item for item in data["files"] if item["path"] == path)
    # The additional adapter belongs only to the combined width/callback
    # publication. Historical standalone 59d and 59f contracts stay unchanged.
    if path == FALLBACK and (root / REVIEW_59D).exists() and (root / ZERO_59D59F_CONTRACT).exists():
        source = restore_59d59f_zero(root, source)
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


def restore_59d_then_59f_source(root: Path, path: str, source: str) -> str:
    """Compose the two reviewed deltas, retaining the complete frozen 59f blob.

    The 59d production hashes change as its own implementation is qualified;
    this restoration reads only separately sealed publisher and checker spans.
    It cannot accept a modified contract paired with modified source text.
    """
    if path not in PATHS:
        return source
    if path == FALLBACK:
        width_path = root / WIDTH_59D_CONTRACT
        require(width_path.is_file() and not width_path.is_symlink(),
                "missing regular 59d width publication restoration contract")
        raw = width_path.read_bytes()
        require(hashlib.sha256(raw).hexdigest() == WIDTH_59D_CONTRACT_SHA256,
                "59d reviewed width publication restoration changed")
        data = json.loads(raw)
        edits = data["edits"]
    else:
        review_path = root / REVIEW_59D
        require(review_path.is_file() and not review_path.is_symlink(),
                "missing regular 59d production review")
        reviewed = json.loads(review_path.read_text())
        require(set(reviewed) == {"base", "files", "checker_edits"} and
                reviewed["base"] == BASE,
                "59d production review baseline/schema changed")
        edits = reviewed["checker_edits"]
        require(digest(json.dumps(edits, sort_keys=True, separators=(",", ":"))) ==
                CHECKER_59D_EDITS_SHA256,
                "59d reviewed checker restoration changed")
    for edit in reversed([edit for edit in edits if edit["path"] == path]):
        require(edit["before"] and edit["after"] and source.count(edit["after"]) == 1,
                "missing/duplicate reviewed 59d restoration span in " + path)
        source = source.replace(edit["after"], edit["before"], 1)
    return restore_59f_source(root, path, source)


def source_scope(root: Path) -> None:
    contract(root)
    restore = (restore_59d_then_59f_source if (root / REVIEW_59D).exists()
               else restore_59f_source)
    for path in sorted(PATHS):
        restore(root, path, (root / path).read_text())
    print("59f exact reviewed publisher spans and complete before/after blobs PASS", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    source_scope(parser.parse_args().root)
