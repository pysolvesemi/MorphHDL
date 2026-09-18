#!/usr/bin/env python3
"""Authenticate successful Increment 61 publications before cross-Scala comparison.

Diagnostic uploads never enter this gate. A success-only producer records both
complete generation sets after the existing test/tool/mutation steps. Retries
may reuse another Scala lane's earlier successful artifact from the same run,
but never another commit, workflow run, future attempt, or incomplete set.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re

SCALAS = ("2.12.18", "2.13.12")
PUBLICATION_FILES = frozenset((
    "consolidated/Increment61Top.v", "split/Increment61Leaf.v",
    "split/Increment61Top.v", "split/Increment61Top.lst",
))
# Native split publication also emits a hidden ownership manifest and three
# path-hash owner markers. These are part of the complete deterministic output,
# not disposable diagnostics. Authenticate and upload them, never filter them.
OWNERS = "split/.Increment61Top.morphhdl-one-file-per-component.owners/"
METADATA_FILES = frozenset((
    "split/.Increment61Top.morphhdl-one-file-per-component.manifest",
)) | frozenset(OWNERS + hashlib.sha256(name.encode()).hexdigest() + ".owner"
              for name in ("Increment61Leaf.v", "Increment61Top.v", "Increment61Top.lst"))
FILES = PUBLICATION_FILES | METADATA_FILES
MANIFEST = "publication-proof.json"
PREFIX = "increment-61-qualified-"


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError("INCREMENT61-ARTIFACT: " + detail)


def regular(path: Path) -> bytes:
    require(path.is_file() and not path.is_symlink(), "missing or linked file: " + str(path))
    data = path.read_bytes()
    require(bool(data), "empty file: " + str(path))
    return data


def generated(root: Path) -> dict[str, bytes]:
    require(root.is_dir() and not root.is_symlink(), "missing or linked generation set: " + str(root))
    files = {}
    for path in root.rglob("*"):
        require(not path.is_symlink(), "linked generation member: " + str(path))
        require(path.is_dir() or path.is_file(), "nonregular generation member: " + str(path))
        if path.is_file():
            files[path.relative_to(root).as_posix()] = regular(path)
    require(set(files) == FILES, "complete publication inventory differs: " + str(root) +
            "; missing=" + repr(sorted(FILES - set(files))) +
            "; extra=" + repr(sorted(set(files) - FILES)))
    return files


def hashes(files: dict[str, bytes]) -> dict[str, str]:
    return {name: hashlib.sha256(data).hexdigest() for name, data in sorted(files.items())}


def identity(head: str, scala: str, run_id: str, attempt: int) -> None:
    require(re.fullmatch(r"[0-9a-f]{40}", head) is not None, "invalid expected source commit")
    require(scala in SCALAS, "unsupported Scala lane")
    require(re.fullmatch(r"[1-9][0-9]*", run_id) is not None, "invalid workflow run")
    require(type(attempt) is int and attempt >= 1, "invalid attempt")


def record(root: Path, head: str, scala: str, run_id: str, attempt: int) -> dict:
    identity(head, scala, run_id, attempt)
    require(root.is_dir() and not root.is_symlink(), "missing or linked publication root")
    require(regular(root / "head.txt").decode().strip() == head, "producer source commit differs")
    first, second = generated(root / "generated-a"), generated(root / "generated-b")
    require(first == second, "fresh generation is not byte-identical")
    value = dict(schema_version=1, source_commit=head, scala_version=scala,
                 workflow_run_id=run_id, producer_attempt=attempt, files=hashes(first))
    (root / MANIFEST).write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    return value


def verify(root: Path, head: str, scala: str, run_id: str, attempt: int) -> dict[str, bytes]:
    identity(head, scala, run_id, attempt)
    require(root.is_dir() and not root.is_symlink(), "missing or linked qualified publication")
    def unique(pairs):
        value = dict(pairs)
        require(len(value) == len(pairs), "duplicate manifest field")
        return value
    try:
        value = json.loads(regular(root / MANIFEST), object_pairs_hook=unique)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise RuntimeError("INCREMENT61-ARTIFACT: malformed manifest") from error
    require(isinstance(value, dict) and set(value) == {
        "schema_version", "source_commit", "scala_version", "workflow_run_id", "producer_attempt", "files"
    }, "invalid manifest schema")
    require(type(value["schema_version"]) is int and value["schema_version"] == 1, "manifest version differs")
    require(value["source_commit"] == head and regular(root / "head.txt").decode().strip() == head,
            "artifact source commit differs")
    require(value["scala_version"] == scala, "artifact Scala lane differs")
    require(value["workflow_run_id"] == run_id, "artifact workflow run differs")
    require(type(value["producer_attempt"]) is int and 1 <= value["producer_attempt"] <= attempt,
            "artifact producer attempt differs")
    first, second = generated(root / "generated-a"), generated(root / "generated-b")
    require(first == second, "artifact repeated generations differ")
    require(value["files"] == hashes(first), "artifact recorded file hashes differ")
    return first


def compare(root: Path, head: str, run_id: str, attempt: int) -> dict:
    require(root.is_dir() and not root.is_symlink(), "missing or linked cross-Scala root")
    expected = {PREFIX + scala for scala in SCALAS}
    require({path.name for path in root.iterdir()} == expected, "qualified Scala artifact inventory differs")
    left, right = [verify(root / (PREFIX + scala), head, scala, run_id, attempt) for scala in SCALAS]
    require(left == right, "cross-Scala publication bytes differ")
    return dict(status="PASS", source_commit=head, workflow_run_id=run_id,
                comparison_attempt=attempt, scalas=list(SCALAS), files=hashes(left))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)
    for operation in ("record", "compare"):
        command = sub.add_parser(operation)
        command.add_argument("root", type=Path)
        command.add_argument("head")
        if operation == "record":
            command.add_argument("scala")
        command.add_argument("run_id")
        command.add_argument("attempt", type=int)
    args = parser.parse_args()
    result = record(args.root, args.head, args.scala, args.run_id, args.attempt) if args.operation == "record" else \
             compare(args.root, args.head, args.run_id, args.attempt)
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
