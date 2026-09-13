#!/usr/bin/env python3
"""Authenticate WA-10's exact successor scope and retain WA-09 audit evidence.

The existing outer overlay still proves current HEAD/index/worktree bytes and
the complete governed delta. This layer binds WA-10 to its immutable, merged
predecessor and enumerates only this request's sources. Historical text-contract
checks can then inspect that predecessor without mistaking their old negative
capability assertions for current compiler requirements. Executable regression
and equivalence workflows continue to compile the current source tree.
"""
from __future__ import annotations

import argparse
import copy
import importlib.util
import json
from pathlib import Path

PREDECESSOR = "086cb4c642d0182e33bd86fd391f46fc7c0eb2a8"
CONTRACT = "morphhdl/contracts/wa10-source-scope.json"
OVERLAY = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError("WA-10 source scope: " + detail)


def outer_overlay(root: Path):
    spec = importlib.util.spec_from_file_location("wa10_outer_overlay", root / OVERLAY)
    require(spec is not None and spec.loader is not None, "cannot load outer overlay")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_inventory(value: dict, actual: set[str]) -> None:
    require(isinstance(value, dict) and
            set(value) == {"schema_version", "predecessor", "paths"} and
            value["schema_version"] == 1 and value["predecessor"] == PREDECESSOR,
            "invalid successor inventory schema or predecessor")
    paths = value["paths"]
    require(isinstance(paths, list) and bool(paths) and
            all(isinstance(path, str) for path in paths) and paths == sorted(set(paths)),
            "successor paths must be nonempty, unique and sorted")
    require(all(path and Path(path).as_posix() == path and not Path(path).is_absolute() and
                ".." not in Path(path).parts for path in paths), "invalid successor path")
    # Documentation is reviewable request scope too, but historical source
    # projection remains governed by the stronger exact outer byte inventory.
    require(actual == set(paths), "successor path inventory differs: " +
            repr(sorted(actual ^ set(paths))))


def verify(root: Path) -> dict:
    root = root.resolve()
    overlay = outer_overlay(root)
    sealed = overlay.verify(root)
    require(CONTRACT in {entry["path"] for entry in sealed["files"]},
            "successor inventory is not sealed by the outer overlay")
    value = json.loads(overlay.regular(root, CONTRACT))
    overlay.git(root, "merge-base", "--is-ancestor", PREDECESSOR, "HEAD")
    actual = {path.decode() for path in overlay.git(
        root, "diff", "--no-renames", "--name-only", "-z", PREDECESSOR, "HEAD").split(b"\0") if path}
    validate_inventory(value, actual)
    return value


def historical_sources(root: Path, paths) -> dict[str, str]:
    """Read immutable WA-09 evidence only after authenticating current WA-10."""
    verify(root)
    overlay = outer_overlay(root)
    result = {}
    for path in paths:
        raw = overlay.frozen(root.resolve(), PREDECESSOR, path)
        require(raw is not None, "missing predecessor evidence: " + path)
        result[path] = raw.decode()
    return result


def self_test() -> None:
    actual = {"core/src/main/Reviewed.scala", "morphhdl/contracts/wa10-source-scope.json"}
    valid = {"schema_version": 1, "predecessor": PREDECESSOR, "paths": sorted(actual)}
    validate_inventory(valid, actual)
    controls = 0
    for key, value in (
        ("schema_version", 2), ("predecessor", "0" * 40),
        ("paths", []), ("paths", [None]),
        ("paths", list(reversed(valid["paths"]))),
        ("paths", valid["paths"] + valid["paths"]),
        ("paths", ["../core/Reviewed.scala"]), ("paths", ["/core/Reviewed.scala"]),
        ("paths", ["./core/Reviewed.scala"]), ("paths", [""]),
        ("paths", valid["paths"][:1]),
        ("paths", sorted(actual | {"core/src/main/Unreviewed.scala"})),
    ):
        mutant = copy.deepcopy(valid)
        mutant[key] = value
        try:
            validate_inventory(mutant, actual)
        except RuntimeError:
            controls += 1
        else:
            raise AssertionError("accepted scope mutation: " + repr((key, value)))
    print("WA10_SCOPE_MUTATIONS_PASS controls=" + str(controls))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--print-paths", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    value = verify(args.repo_root)
    if args.print_paths:
        print("\n".join(value["paths"]))
    else:
        print("WA10_SOURCE_SCOPE_PASS paths=" + str(len(value["paths"])))


if __name__ == "__main__":
    main()
