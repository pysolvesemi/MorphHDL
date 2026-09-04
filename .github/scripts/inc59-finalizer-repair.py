#!/usr/bin/env python3
"""Idempotently repair the final Increment 59 target before verification."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

ROOT = Path(".")


def positions(value: str, marker: str) -> list[int]:
    result: list[int] = []
    cursor = 0
    while True:
        index = value.find(marker, cursor)
        if index < 0:
            return result
        result.append(index)
        cursor = index + len(marker)


def reduce_to_one(value: str, marker: str, following: str, role: str) -> str:
    found = positions(value, marker)
    if len(found) > 2:
        raise SystemExit(f"{role} has unexpected multiplicity {len(found)}")
    if len(found) == 2:
        start = found[1]
        end = value.find(following, start)
        if end < 0:
            raise SystemExit(f"cannot bound duplicate {role}")
        value = value[:start] + value[end:]
    if len(positions(value, marker)) != 1:
        raise SystemExit(f"{role} was not reduced to one declaration")
    return value


def repair_hierarchy() -> None:
    path = ROOT / "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala"
    text = path.read_text(encoding="utf-8")
    text = reduce_to_one(
        text,
        "\n  private final case class BooleanExpressionBinding(",
        "\n  private final case class BindingSignature(",
        "BooleanExpressionBinding",
    )
    text = reduce_to_one(
        text,
        "\n  private def analyzeBlackBoxInstance(",
        "\n  private def analyzeInstance(",
        "analyzeBlackBoxInstance",
    )
    path.write_text(text, encoding="utf-8")


def repair_review_policy() -> None:
    path = ROOT / "morphhdl/contracts/increment-55-native-change-review.json"
    policy = json.loads(path.read_text(encoding="utf-8"))
    files = policy["files"]
    by_path = {entry["path"]: entry for entry in files}
    marker = "Increment 59: typed BlackBox parameter and generic binding"

    blackbox_path = "core/src/main/scala/spinal/core/BlackBox.scala"
    blackbox = by_path.get(blackbox_path)
    if blackbox is None:
        blackbox = {"path": blackbox_path}
        files.append(blackbox)
    blackbox.update(
        {
            "baseline_path": blackbox_path,
            "change": "modified",
            "classification": "typed-overload",
            "reason": (
                "Accept exact typed ElabInt and ElabBool BlackBox generic values while "
                "preserving the inherited concrete generic collection and emitter behavior."
            ),
            "edits": [
                {
                    "id": "blackbox-typed-generic-01",
                    "kind": "overload",
                    "owner": "spinal.core.BlackBox.addGeneric",
                    "reason": (
                        "Add adjacent typed integer and Boolean generic cases which validate "
                        "exact authority, retain symbolic metadata, and pass only their "
                        "concrete witnesses to the unchanged native generic emitter."
                    ),
                    "required_exact_text": [
                        {
                            "side": "approved",
                            "text": "case value: ElabInt",
                            "count": 1,
                        },
                        {
                            "side": "approved",
                            "text": "case value: ElabBool",
                            "count": 1,
                        },
                        {
                            "side": "approved",
                            "text": "ParameterizedBlackBoxGenericRegistry.retain",
                            "count": 2,
                        },
                    ],
                }
            ],
        }
    )
    introduced = list(blackbox.get("introduced_by", []))
    if marker not in introduced:
        introduced.append(marker)
    blackbox["introduced_by"] = introduced

    support_path = "core/src/main/scala/spinal/core/internals/ParameterizedBlackBoxGeneric.scala"
    support = by_path.get(support_path)
    if support is None:
        support = {"path": support_path}
        files.append(support)
    support.update(
        {
            "baseline_path": None,
            "change": "added",
            "classification": "typed-support-file",
            "reason": (
                "Retain exact identity-owned typed BlackBox generic bindings and validate "
                "their parameter roots, witnesses, ordering, and packed-port geometry."
            ),
            "edits": [],
        }
    )
    introduced = list(support.get("introduced_by", []))
    if marker not in introduced:
        introduced.append(marker)
    support["introduced_by"] = introduced

    policy["files"] = sorted(files, key=lambda entry: entry["path"])
    path.write_text(json.dumps(policy, indent=2) + "\n", encoding="utf-8")


def remove_temporary_files() -> None:
    for relative in [
        ".github/increment59-source-validation.txt",
        ".github/workflows/increment-59-export.yml",
        ".github/workflows/increment-59-gates-v2.yml",
        ".github/workflows/increment-59-bootstrap-v5.yml",
        ".github/workflows/increment-59-bootstrap-v6.yml",
        ".github/workflows/increment-59-finalize.yml",
        ".github/workflows/increment-59-finalize-v2.yml",
        ".github/scripts/inc59-complete-v6.py",
        ".github/scripts/inc59-complete-v6.sh",
    ]:
        path = ROOT / relative
        if path.exists():
            path.unlink()


def write_scope() -> None:
    paths = sorted(
        [
            ".github/workflows/increment-59-gates-v3.yml",
            "core/src/main/scala/spinal/core/BlackBox.scala",
            "core/src/main/scala/spinal/core/internals/ParameterizedBlackBoxGeneric.scala",
            "docs/morphhdl/increment-59-typed-blackbox-generics.md",
            "morphhdl/contracts/increment-55-native-change-review.json",
            "morphhdl/contracts/increment-59-source-scope.txt",
            "morphhdl/contracts/native-source-preservation.json",
            "morphhdl/scripts/check-increment-59-blackbox-generics.py",
            "morphhdl/scripts/check-increment-59-final-gates.sh",
            "morphhdl/scripts/generate-increment-59-blackbox-stubs.py",
            "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala",
            "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala",
            "morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala",
            "morphhdl/src/test/scala/morphhdl/TypedBlackBoxGenericArtifactGenerator.scala",
            "morphhdl/src/test/scala/morphhdl/TypedBlackBoxGenericBindingTests.scala",
            "morphhdl/src/test/scala/nativeapplication/TypedBlackBoxGenericBindingFixture.scala",
        ]
    )
    (ROOT / "morphhdl/contracts/increment-59-source-scope.txt").write_text(
        "\n".join(paths) + "\n", encoding="utf-8"
    )


def regenerate_manifest() -> None:
    subprocess.run(
        [
            "python3",
            "morphhdl/scripts/check-native-source-preservation.py",
            "--generate-template",
            "morphhdl/contracts/increment-55-native-change-review.json",
            "--output",
            "morphhdl/contracts/native-source-preservation.json",
            "--force",
        ],
        check=True,
    )


def main() -> None:
    repair_hierarchy()
    repair_review_policy()
    remove_temporary_files()
    write_scope()
    regenerate_manifest()


if __name__ == "__main__":
    main()
