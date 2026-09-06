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
PADDING_59D59F_CONTRACT = "morphhdl/contracts/increment-59d-59f-padding-edits.json"
PADDING_59D59F_SHA256 = "90ed0c4fa539d427cb0c75d7a8673b0e42ccdac20ebe94a064d5788de3259fc7"
PADDING_59D59F_IDS = (
    "unsigned-padding-owner-signature",
    "unsigned-padding-owner-relation",
    "unsigned-padding-owner-call",
)
COMPOSITE_59EF_CONTRACT = "morphhdl/contracts/increment-59e-59f-publisher-edits.json"
COMPOSITE_59EF_SHA256 = "a7413d5d50fcb9a073cdd40a980c1476dddb9ef7d727635d045565b39b5b3f9a"
COMPOSITE_59EF_BASE = "b25e367d99604e61b8f2c895b2c51ca1ab90d423"
COMPOSITE_59DE_CONTRACT = "morphhdl/contracts/increment-59d-59e-publisher-edits.json"
COMPOSITE_59DE_SHA256 = "28fb1b3cd9a09aa34a227dff0caef2b709dcda6f6410659c5182ee8c2266efce"
COMPOSITE_59DE_BASE = "cf353ddd45c766576488ae45748d1d17876c3b11"
COMPOSITE_59DE_IDS = (
    "packed-read-support-identities",
    "packed-read-support-assignment-evidence",
    "packed-read-independent-owner-roots",
    "recursive-packed-element-geometry",
)
COMPOSITE_59DE_PRODUCTION_CONTRACT = "morphhdl/contracts/increment-59d-59e-production-edits.json"
COMPOSITE_59DE_PRODUCTION_SHA256 = "dcf69402fb0d91a86aed655b966f1b17644d889e73d87f8b1ed8b436ad30d5c2"
COMPOSITE_59DE_POLICY = "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeCallbackPolicy.scala"
PACKING_59DE_CONTRACT = "morphhdl/contracts/increment-59d-59e-packing-edits.json"
PACKING_59DE_SHA256 = "b740eebf7bed4dda5003cdf35e30af418a9f380c8288237efcbb3389f3c39f3d"
PACKING_59DE_IDS = {
    "core/src/main/scala/spinal/core/ParameterizedVec.scala": ("audited-composite-carrier-construction",),
    "core/src/main/scala/spinal/core/Vec.scala": ("route-exact-composite-packing",),
}


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError(detail)


def digest(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


def contract(root: Path) -> dict:
    raw = (root / CONTRACT).read_bytes()
    # The separately qualified 59e branch published a second complete frozen
    # manifest. Its historical profile has no 59d review. A combined profile
    # keeps the original 59f manifest and composes exact successor layers.
    allowed = {CONTRACT_SHA256}
    if not (root / REVIEW_59D).exists():
        allowed.add(COMPOSITE_59EF_SHA256)
    require(hashlib.sha256(raw).hexdigest() in allowed,
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


def restore_59d59f_padding(root: Path, source: str) -> str:
    """Restore exact owner-aware padding into the independently frozen zero adapter."""
    path = root / PADDING_59D59F_CONTRACT
    require(path.is_file() and not path.is_symlink(), "missing regular 59d/59f padding-owner review")
    raw = path.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == PADDING_59D59F_SHA256,
            "59d/59f reviewed padding-owner manifest changed")
    data = json.loads(raw)
    require(set(data) == {"base", "prior_review_sha256", "files"} and
            data["base"] == ZERO_59D59F_BASE and
            data["prior_review_sha256"] == ZERO_59D59F_SHA256 and len(data["files"]) == 1,
            "59d/59f padding-owner restoration baseline/schema changed")
    entry = data["files"][0]
    require(set(entry) == {"path", "before_sha256", "after_sha256", "edits"} and
            entry["path"] == FALLBACK and len(entry["edits"]) == len(PADDING_59D59F_IDS),
            "59d/59f padding-owner restoration exceeds its exact fallback scope")
    require(tuple(edit.get("id") for edit in entry["edits"]) == PADDING_59D59F_IDS and
            all(set(edit) == {"id", "before", "after"} and edit["before"] and edit["after"]
                for edit in entry["edits"]), "59d/59f padding-owner restoration spans changed")
    prior = root / ZERO_59D59F_CONTRACT
    require(prior.is_file() and not prior.is_symlink(), "missing regular 59d/59f zero-owner review")
    prior_raw = prior.read_bytes()
    require(hashlib.sha256(prior_raw).hexdigest() == ZERO_59D59F_SHA256,
            "59d/59f reviewed zero-owner manifest changed")
    require(entry["before_sha256"] == json.loads(prior_raw)["files"][0]["after_sha256"],
            "59d/59f padding-owner restoration differs from frozen zero adapter")
    require(digest(source) == entry["after_sha256"],
            "unreviewed source change outside 59d/59f padding-owner spans")
    for edit in reversed(entry["edits"]):
        require(source.count(edit["after"]) == 1, "missing/duplicate 59d/59f padding-owner span")
        source = source.replace(edit["after"], edit["before"], 1)
    require(digest(source) == entry["before_sha256"], "59d/59f restored padding-owner blob differs")
    return source


def restore_59d59e_publisher(root: Path, source: str) -> str:
    """Restore four composite packing spans after the sealed 59d width seams."""
    path = root / COMPOSITE_59DE_CONTRACT
    require(path.is_file() and not path.is_symlink(), "missing regular 59d/59e publisher review")
    raw = path.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == COMPOSITE_59DE_SHA256,
            "59d/59e reviewed publisher manifest changed")
    data = json.loads(raw)
    require(set(data) == {"base", "qualified_composite", "historical_publisher_sha256", "files"} and
            data["base"] == COMPOSITE_59DE_BASE and
            data["qualified_composite"] == COMPOSITE_59EF_BASE and
            data["historical_publisher_sha256"] == COMPOSITE_59EF_SHA256 and
            len(data["files"]) == 1, "59d/59e publisher restoration baseline/schema changed")
    entry = data["files"][0]
    require(set(entry) == {"path", "before_sha256", "after_sha256", "edits"} and
            entry["path"] == FALLBACK and len(entry["edits"]) == len(COMPOSITE_59DE_IDS),
            "59d/59e publisher restoration exceeds its exact fallback scope")
    require(tuple(edit.get("id") for edit in entry["edits"]) == COMPOSITE_59DE_IDS and
            all(set(edit) == {"id", "before", "after"} and edit["before"] and edit["after"]
                for edit in entry["edits"]), "59d/59e publisher restoration spans changed")
    historical = root / COMPOSITE_59EF_CONTRACT
    require(historical.is_file() and not historical.is_symlink(),
            "missing regular frozen 59e/59f publisher review")
    require(hashlib.sha256(historical.read_bytes()).hexdigest() == COMPOSITE_59EF_SHA256,
            "frozen 59e/59f publisher manifest changed")
    prior = root / PADDING_59D59F_CONTRACT
    require(prior.is_file() and not prior.is_symlink(), "missing regular 59d/59f padding-owner review")
    prior_raw = prior.read_bytes()
    require(hashlib.sha256(prior_raw).hexdigest() == PADDING_59D59F_SHA256,
            "59d/59f reviewed padding-owner manifest changed")
    require(entry["before_sha256"] == json.loads(prior_raw)["files"][0]["after_sha256"],
            "59d/59e publisher restoration differs from frozen padding adapter")
    require(digest(source) == entry["after_sha256"],
            "unreviewed source change outside 59d/59e publisher spans")
    for edit in reversed(entry["edits"]):
        require(source.count(edit["after"]) == 1, "missing/duplicate 59d/59e publisher span")
        source = source.replace(edit["after"], edit["before"], 1)
    require(digest(source) == entry["before_sha256"], "59d/59e restored publisher blob differs")
    return source


def reviewed_59d59e_production(root: Path) -> dict[str, str]:
    """Authorize only the combined profile's exact auto-resize admission removal."""
    path = root / COMPOSITE_59DE_PRODUCTION_CONTRACT
    require(path.is_file() and not path.is_symlink(), "missing regular 59d/59e production review")
    raw = path.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == COMPOSITE_59DE_PRODUCTION_SHA256,
            "59d/59e reviewed production manifest changed")
    data = json.loads(raw)
    require(set(data) == {"base", "files"} and data["base"] == COMPOSITE_59EF_BASE and
            len(data["files"]) == 1, "59d/59e production restoration baseline/schema changed")
    entry = data["files"][0]
    require(set(entry) == {"path", "before_sha256", "after_sha256", "edits"} and
            entry["path"] == COMPOSITE_59DE_POLICY and len(entry["edits"]) == 1,
            "59d/59e production restoration exceeds its exact callback-policy scope")
    edit = entry["edits"][0]
    require(set(edit) == {"id", "before", "after"} and edit["id"] == "reject-scalar-auto-resize" and
            edit["before"] and edit["after"], "59d/59e production restoration span changed")
    target = root / entry["path"]
    require(target.is_file() and not target.is_symlink(), "missing regular 59d/59e production source")
    source = target.read_text()
    require(digest(source) == entry["after_sha256"], "59d/59e reviewed production source changed")
    require(source.count(edit["after"]) == 1, "missing/duplicate 59d/59e production span")
    restored = source.replace(edit["after"], edit["before"], 1)
    require(digest(restored) == entry["before_sha256"], "59d/59e restored production blob differs")
    baseline = subprocess.check_output(["git", "show", COMPOSITE_59EF_BASE + ":" + entry["path"]],
                                       cwd=root, text=True)
    require(restored == baseline, "59d/59e production restoration differs from frozen 59e")
    return {entry["path"]: entry["after_sha256"]}


def reviewed_59d59e_packing(root: Path) -> dict[str, str]:
    """Restore the exact composite carrier construction to the frozen 59e files."""
    def regular_tracked(relative: str, role: str) -> Path:
        path = root / relative
        require(path.is_file() and not path.is_symlink() and not path.stat().st_mode & 0o111,
                "missing regular non-executable 59d/59e packing " + role)
        stage = subprocess.check_output(["git", "ls-files", "--stage", "--", relative],
                                        cwd=root, text=True).split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == relative,
                "59d/59e packing " + role + " is not uniquely tracked")
        return path

    path = regular_tracked(PACKING_59DE_CONTRACT, "review")
    raw = path.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == PACKING_59DE_SHA256,
            "59d/59e reviewed packing manifest changed")
    data = json.loads(raw)
    require(set(data) == {"base", "files"} and data["base"] == COMPOSITE_59EF_BASE and
            len(data["files"]) == len(PACKING_59DE_IDS),
            "59d/59e packing restoration baseline/schema changed")
    require({entry.get("path") for entry in data["files"]} == set(PACKING_59DE_IDS),
            "59d/59e packing restoration exceeds its exact two-file scope")
    hashes = {}
    for entry in data["files"]:
        require(set(entry) == {"path", "before_sha256", "after_sha256", "edits"} and
                tuple(edit.get("id") for edit in entry["edits"]) == PACKING_59DE_IDS[entry["path"]] and
                all(set(edit) == {"id", "before", "after"} and edit["before"] and edit["after"]
                    for edit in entry["edits"]), "59d/59e packing restoration spans changed")
        source = regular_tracked(entry["path"], "source").read_text()
        require(digest(source) == entry["after_sha256"], "59d/59e reviewed packing source changed")
        restored = source
        for edit in reversed(entry["edits"]):
            require(restored.count(edit["after"]) == 1, "missing/duplicate 59d/59e packing span")
            restored = restored.replace(edit["after"], edit["before"], 1)
        require(digest(restored) == entry["before_sha256"], "59d/59e restored packing blob differs")
        baseline = subprocess.check_output(["git", "show", COMPOSITE_59EF_BASE + ":" + entry["path"]],
                                           cwd=root, text=True)
        require(restored == baseline, "59d/59e packing restoration differs from frozen 59e")
        hashes[entry["path"]] = entry["after_sha256"]
    return hashes


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
        if (root / COMPOSITE_59DE_CONTRACT).exists():
            source = restore_59d59e_publisher(root, source)
        if (root / PADDING_59D59F_CONTRACT).exists():
            source = restore_59d59f_padding(root, source)
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
