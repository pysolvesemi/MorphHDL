#!/usr/bin/env python3
"""Qualify actual default candidates with independent inherited reference RTL."""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

CANDIDATES = tuple("boundaries/" + k + "/candidate.v" for k in
                   ("scalars", "bundles", "vectors", "vec-hierarchy", "hierarchy", "channels")) + (
    "pure/pure-true.v", "pure/boundaries.v", "pure/baseline-clean.v", "pure/declaration-fixture-clean.v")
EXPECTED = {mode + "/" + p for mode in ("default", "explicit") for p in CANDIDATES}
SCALAS = {"2.12.18", "2.13.12"}
ROOT = Path(__file__).resolve().parents[2]


def require(ok: bool, message: str) -> None:
    if not ok:
        raise RuntimeError(message)


def head() -> str:
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()


def inventory(root: Path) -> dict[str, str]:
    require(root.is_dir() and not root.is_symlink(), "missing or linked RTL directory: " + str(root))
    nodes = list(root.rglob("*"))
    require(not any(p.is_symlink() for p in nodes), "linked default artifact")
    require(all(p.is_dir() or p.is_file() for p in nodes), "non-regular default artifact")
    files = {p.relative_to(root).as_posix(): p for p in nodes if p.is_file()}
    require(set(files) == EXPECTED, "60g default candidate inventory changed: " + str(sorted(files)))
    require(all(path.stat().st_size for path in files.values()), "empty default artifact")
    result = {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in sorted(files.items())}
    for path in CANDIDATES:
        require(result["default/" + path] == result["explicit/" + path], "default differs from explicit cleanup: " + path)
    return result


def snapshot(left: Path, right: Path, inherited: Path, output: Path, scala: str) -> None:
    output.unlink(missing_ok=True)
    require(scala in SCALAS, "unsupported Scala lane")
    a, b = inventory(left), inventory(right)
    require(a == b, "fresh-JVM default rollout artifacts differ")
    for path in CANDIDATES:
        expected = hashlib.sha256((inherited / path).read_bytes()).hexdigest()
        require(a["default/" + path] == expected, "default differs from independently generated qualified candidate: " + path)
    commit = head()
    output.write_text(json.dumps({"head": commit, "scala": scala, "sha256": a}, indent=2) + "\n")
    print(f"60g {len(a)} generated artifacts agree across modes and fresh JVMs at {commit}", flush=True)


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
    inherited_result = json.loads((proof / "qualification-60f.json").read_text())
    require(inherited_result.get("scope") == "full inherited and closure qualification" and
            inherited_result.get("boundary_equivalence_tuples") == 64,
            "inherited checker did not record complete qualification")
    (proof / "qualification-60g.json").write_text(json.dumps({
        "head": head(), "publication_default": "signed declarations and proven minimal casts",
        "actual_default_candidates": len(CANDIDATES), "sha256": inventory(defaults),
        "inherited_scope": inherited_result["scope"],
        "independent_checked_generated_files": inherited_result["independent_generated_files"],
        "boundary_equivalence_tuples": inherited_result["boundary_equivalence_tuples"],
        "memory_bounded_steps": inherited_result["memory_bounded_steps"],
        "compatibility": "separate native compatibility suite and full 60f workflow remain required"
    }, indent=2) + "\n")
    print("60g actual default candidates passed inherited strict tools, independent proofs and mutations", flush=True)


def manifest(path: Path) -> dict:
    require(path.is_file() and not path.is_symlink(), "missing or linked manifest")

    def unique(pairs):
        keys = [key for key, _ in pairs]
        require(len(keys) == len(set(keys)), "duplicate manifest field")
        return dict(pairs)

    value = json.loads(path.read_text(), object_pairs_hook=unique)
    require(isinstance(value, dict) and set(value) == {"head", "scala", "sha256"}, "invalid manifest schema")
    require(isinstance(value["head"], str) and re.fullmatch(r"[0-9a-f]{40}", value["head"]), "invalid manifest head")
    require(value["scala"] in SCALAS, "unsupported manifest Scala lane")
    hashes = value["sha256"]
    require(isinstance(hashes, dict) and set(hashes) == EXPECTED, "invalid manifest RTL inventory")
    require(all(isinstance(v, str) and re.fullmatch(r"[0-9a-f]{64}", v) for v in hashes.values()), "invalid RTL hash")
    return value


def compare(left: Path, right: Path) -> None:
    a, b = (manifest(p / "manifest.json") for p in (left, right))
    require(a["head"] == b["head"] == head(), "cross-Scala artifacts do not match this exact head")
    require({a["scala"], b["scala"]} == SCALAS, "both Scala lanes are required")
    ai, bi = inventory(left / "rtl"), inventory(right / "rtl")
    require(ai == a["sha256"] and bi == b["sha256"] and ai == bi,
            "downloaded actual RTL bytes or manifests differ")
    print(f"60g cross-Scala exact-head byte comparison: {len(ai)} artifacts PASS", flush=True)


def self_test() -> None:
    # Deliberately synthetic files test the evidence gate, not RTL semantics.
    # Passing these controls never counts as compiler, solver or mutation proof.
    with tempfile.TemporaryDirectory(prefix="morphhdl-60g-inventory-") as temporary:
        root = Path(temporary)
        left, right, inherited = (root / name for name in ("left", "right", "inherited"))
        for path in CANDIDATES:
            data = ("// synthetic inventory control " + path + "\n").encode()
            for parent in (left / "rtl" / "default", left / "rtl" / "explicit", inherited):
                file = parent / path
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(data)
        shutil.copytree(left, right)
        snapshot(left / "rtl", right / "rtl", inherited, left / "manifest.json", "2.12.18")
        snapshot(left / "rtl", right / "rtl", inherited, right / "manifest.json", "2.13.12")
        compare(left, right)
        originals = {p: p.read_bytes() for p in root.rglob("*" ) if p.is_file()}
        first = "default/" + CANDIDATES[0]
        target = right / "rtl" / first
        cases = []

        def rejection(label, mutation, check):
            mutation()
            try:
                check()
            except (RuntimeError, OSError, ValueError, TypeError):
                cases.append(label)
            else:
                raise RuntimeError("60g evidence gate accepted " + label)
            finally:
                for p in sorted(root.rglob("*"), key=lambda p: len(p.parts), reverse=True):
                    if p.is_symlink() or (p.is_file() and p not in originals):
                        p.unlink()
                for p, data in originals.items():
                    if p.is_symlink():
                        p.unlink()
                    p.parent.mkdir(parents=True, exist_ok=True)
                    p.write_bytes(data)

        def edit_manifest(change):
            value = json.loads(originals[right / "manifest.json"])
            change(value)
            (right / "manifest.json").write_text(json.dumps(value))

        rejection("missing RTL", target.unlink, lambda: inventory(right / "rtl"))
        rejection("empty RTL", lambda: target.write_bytes(b""), lambda: inventory(right / "rtl"))
        rejection("extra RTL", lambda: (right / "rtl" / "extra.v").write_text("module extra; endmodule"), lambda: inventory(right / "rtl"))
        rejection("non-Verilog extra file", lambda: (right / "rtl" / "extra.sv").write_text("module extra; endmodule"), lambda: inventory(right / "rtl"))
        rejection("changed default", lambda: target.write_text("changed"), lambda: inventory(right / "rtl"))
        rejection("linked RTL", lambda: (target.unlink(), target.symlink_to(left / "rtl" / first)), lambda: inventory(right / "rtl"))
        link = root / "linked-root"
        rejection("linked root", lambda: link.symlink_to(left / "rtl", target_is_directory=True), lambda: inventory(link))
        rejection("linked child directory", lambda: (right / "rtl" / "linked").symlink_to(inherited, target_is_directory=True), lambda: inventory(right / "rtl"))
        rejection("same Scala lane", lambda: edit_manifest(lambda v: v.update(scala="2.12.18")), lambda: compare(left, right))
        rejection("stale source head", lambda: edit_manifest(lambda v: v.update(head="0" * 40)), lambda: compare(left, right))
        rejection("stale RTL hash", lambda: edit_manifest(lambda v: v["sha256"].update({first: "0" * 64})), lambda: compare(left, right))
        rejection("extra manifest key", lambda: edit_manifest(lambda v: v.update(extra=True)), lambda: compare(left, right))
        rejection("missing manifest path", lambda: edit_manifest(lambda v: v["sha256"].pop(first)), lambda: compare(left, right))
        rejection("unsupported Scala lane", lambda: edit_manifest(lambda v: v.update(scala="3.0.0")), lambda: compare(left, right))
        rejection("malformed hash", lambda: edit_manifest(lambda v: v["sha256"].update({first: "not-a-hash"})), lambda: compare(left, right))
        rejection("duplicate manifest field", lambda: (right / "manifest.json").write_text('{"head":"x","head":"y"}'), lambda: compare(left, right))

        def change_pair():
            for mode in ("default", "explicit"):
                (right / "rtl" / mode / CANDIDATES[0]).write_text("changed both modes")

        rejection("fresh-JVM drift", change_pair,
                  lambda: snapshot(left / "rtl", right / "rtl", inherited, root / "bad.json", "2.12.18"))

        def change_both_pairs():
            for side in (left, right):
                for mode in ("default", "explicit"):
                    (side / "rtl" / mode / CANDIDATES[0]).write_text("changed all candidates")

        rejection("all candidates drift from inherited", change_both_pairs,
                  lambda: snapshot(left / "rtl", right / "rtl", inherited, root / "bad.json", "2.12.18"))
        require(len(cases) == 18, "missing evidence-gate rejection control")
        compare(left, right)
        print(f"60g evidence-gate self-test: clean inventory/byte comparison and {len(cases)} rejections PASS (not RTL proof)", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    p = modes.add_parser("snapshot")
    for name in ("left", "right", "inherited", "output"):
        p.add_argument(name, type=Path)
    p.add_argument("--scala", required=True, choices=sorted(SCALAS))
    p = modes.add_parser("qualify")
    for name in ("defaults", "inherited", "proof"):
        p.add_argument(name, type=Path)
    p = modes.add_parser("compare")
    p.add_argument("left", type=Path)
    p.add_argument("right", type=Path)
    modes.add_parser("self-test")
    args = parser.parse_args()
    values = vars(args).copy()
    mode = values.pop("mode")
    if mode == "self-test":
        self_test()
    else:
        globals()[mode](**values)
