#!/usr/bin/env python3
"""Qualify actual default candidates with independent inherited reference RTL."""
from __future__ import annotations
import argparse
import hashlib
import json
import shutil
import subprocess
from pathlib import Path

CANDIDATES = tuple("boundaries/" + k + "/candidate.v" for k in
                   ("scalars", "bundles", "vectors", "vec-hierarchy", "hierarchy", "channels")) + (
    "pure/pure-true.v", "pure/boundaries.v", "pure/baseline-clean.v", "pure/declaration-fixture-clean.v")
EXPECTED = {mode + "/" + p for mode in ("default", "explicit") for p in CANDIDATES}
ROOT = Path(__file__).resolve().parents[2]


def require(ok: bool, message: str) -> None:
    if not ok:
        raise RuntimeError(message)


def inventory(root: Path) -> dict[str, str]:
    files = {p.relative_to(root).as_posix(): p for p in root.rglob("*.v")}
    require(set(files) == EXPECTED, "60g default candidate inventory changed: " + str(sorted(files)))
    result = {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in files.items()}
    require(all(path.stat().st_size for path in files.values()), "empty default artifact")
    for path in CANDIDATES:
        require(result["default/" + path] == result["explicit/" + path], "default differs from explicit cleanup: " + path)
    return result


def snapshot(left: Path, right: Path, inherited: Path, output: Path, scala: str) -> None:
    output.unlink(missing_ok=True)
    a, b = inventory(left), inventory(right)
    require(a == b, "fresh-JVM default rollout artifacts differ")
    for path in CANDIDATES:
        expected = hashlib.sha256((inherited / path).read_bytes()).hexdigest()
        require(a["default/" + path] == expected, "default differs from independently generated qualified candidate: " + path)
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    output.write_text(json.dumps({"head": head, "scala": scala, "sha256": a}, indent=2) + "\n")
    print(f"60g {len(a)} generated artifacts agree across modes and fresh JVMs at {head}", flush=True)


def qualify(defaults: Path, inherited: Path, proof: Path) -> None:
    inventory(defaults)
    require(not proof.exists(), "proof workspace must be fresh: " + str(proof))
    shutil.copytree(inherited, proof)
    for path in CANDIDATES:
        # Copy actual default output, not text-rewritten RTL or a reconstructed
        # reference. All feature-disabled/native reference files remain intact.
        candidate = defaults / "default" / path
        require(candidate.read_bytes() == (proof / path).read_bytes(), "default/qualified candidate mismatch: " + path)
        shutil.copyfile(candidate, proof / path)
    subprocess.run(["python3", str(ROOT / "morphhdl/scripts/check-increment-60f-equivalence-closure.py"),
                    str(proof)], cwd=ROOT, check=True)
    print("60g actual default candidates passed inherited strict tools, independent proofs and mutations", flush=True)


def compare(left: Path, right: Path) -> None:
    a, b = (json.loads((p / "manifest.json").read_text()) for p in (left, right))
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    require(a["head"] == b["head"] == head, "cross-Scala artifacts do not match this exact head")
    require({a["scala"], b["scala"]} == {"2.12.18", "2.13.12"}, "both Scala lanes are required")
    ai, bi = inventory(left / "rtl"), inventory(right / "rtl")
    require(ai == a["sha256"] and bi == b["sha256"] and ai == bi,
            "downloaded actual RTL bytes or manifests differ")
    print(f"60g cross-Scala exact-head byte comparison: {len(ai)} artifacts PASS", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    p = modes.add_parser("snapshot")
    for name in ("left", "right", "inherited", "output"):
        p.add_argument(name, type=Path)
    p.add_argument("--scala", required=True, choices=("2.12.18", "2.13.12"))
    p = modes.add_parser("qualify")
    for name in ("defaults", "inherited", "proof"):
        p.add_argument(name, type=Path)
    p = modes.add_parser("compare")
    p.add_argument("left", type=Path)
    p.add_argument("right", type=Path)
    args = parser.parse_args()
    values = vars(args).copy()
    mode = values.pop("mode")
    globals()[mode](**values)
