#!/usr/bin/env python3
"""Idempotently repair and seal the Increment 59 implementation tree."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

ROOT = Path(".")


def occurrences(value: str, marker: str) -> list[int]:
    result: list[int] = []
    cursor = 0
    while True:
        index = value.find(marker, cursor)
        if index < 0:
            return result
        result.append(index)
        cursor = index + len(marker)


def repair_hierarchy() -> None:
    path = ROOT / "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala"
    text = path.read_text(encoding="utf-8")

    def reduce_to_one(marker: str, following: str, role: str) -> None:
        nonlocal text
        found = occurrences(text, marker)
        if len(found) > 2:
            raise SystemExit(f"{role} has unexpected multiplicity {len(found)}")
        if len(found) == 2:
            start = found[1]
            end = text.find(following, start)
            if end < 0:
                raise SystemExit(f"cannot bound duplicate {role}")
            text = text[:start] + text[end:]
        if len(occurrences(text, marker)) != 1:
            raise SystemExit(f"{role} was not reduced to one declaration")

    reduce_to_one(
        "\n  private final case class BooleanExpressionBinding(",
        "\n  private final case class BindingSignature(",
        "BooleanExpressionBinding",
    )
    reduce_to_one(
        "\n  private def analyzeBlackBoxInstance(",
        "\n  private def analyzeInstance(",
        "analyzeBlackBoxInstance",
    )
    path.write_text(text, encoding="utf-8")


def repair_policy() -> None:
    path = ROOT / "morphhdl/contracts/increment-55-native-change-review.json"
    policy = json.loads(path.read_text(encoding="utf-8"))
    files = policy["files"]
    by_path = {entry["path"]: entry for entry in files}
    introduced_marker = "Increment 59: typed BlackBox parameter and generic binding"

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
        }
    )
    introduced = list(blackbox.get("introduced_by", []))
    if introduced_marker not in introduced:
        introduced.append(introduced_marker)
    blackbox["introduced_by"] = introduced
    blackbox["edits"] = [
        {
            "id": "blackbox-typed-generic-01",
            "kind": "overload",
            "owner": "spinal.core.BlackBox.addGeneric",
            "reason": (
                "Add adjacent typed integer and Boolean generic cases which validate exact "
                "authority, retain symbolic metadata, and pass only concrete witnesses to "
                "the unchanged native generic emitter."
            ),
            "required_exact_text": [
                {"side": "approved", "text": "case value: ElabInt", "count": 1},
                {"side": "approved", "text": "case value: ElabBool", "count": 1},
                {
                    "side": "approved",
                    "text": "ParameterizedBlackBoxGenericRegistry.retain",
                    "count": 2,
                },
            ],
        }
    ]

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
                "their roots, witnesses, order, and packed-port geometry."
            ),
            "edits": [],
        }
    )
    introduced = list(support.get("introduced_by", []))
    if introduced_marker not in introduced:
        introduced.append(introduced_marker)
    support["introduced_by"] = introduced

    policy["files"] = sorted(files, key=lambda entry: entry["path"])
    path.write_text(json.dumps(policy, indent=2) + "\n", encoding="utf-8")


def write_generator() -> None:
    path = ROOT / "morphhdl/src/test/scala/morphhdl/TypedBlackBoxGenericArtifactGenerator.scala"
    path.write_text(
        '''package morphhdl

import java.nio.file.{Files, Paths}

import nativeapplication.TypedBlackBoxGenericBindingFixture
import spinal.core.{Component, SpinalConfig}

/** Emits the representative application-shaped Increment 59 artifact. */
object TypedBlackBoxGenericArtifactGenerator {
  def main(arguments: Array[String]): Unit = {
    require(
      arguments.length == 2 && arguments(0) == "parameterized",
      "usage: TypedBlackBoxGenericArtifactGenerator parameterized <output.v>"
    )
    val output = Paths.get(arguments(1)).toAbsolutePath.normalize()
    val parent = Option(output.getParent).getOrElse(Paths.get(".").toAbsolutePath)
    Files.createDirectories(parent)

    val fixture = TypedBlackBoxGenericBindingFixture
    val candidates = fixture.getClass.getMethods.toVector
      .filter(method =>
        method.getParameterTypes.isEmpty &&
          classOf[Component].isAssignableFrom(method.getReturnType)
      )
      .sortBy(_.getName)
    val factory = candidates
      .find(_.getName.toLowerCase.contains("parameter"))
      .orElse(candidates.find(_.getName.toLowerCase.contains("top")))
      .orElse(candidates.headOption)
      .getOrElse {
        throw new IllegalStateException(
          "TypedBlackBoxGenericBindingFixture exposes no zero-argument Component factory"
        )
      }

    val config = SpinalConfig(targetDirectory = parent.toString)
    config.netlistFileName = output.getFileName.toString
    MorphVerilog(config) {
      factory.invoke(fixture).asInstanceOf[Component]
    }
  }
}
''',
        encoding="utf-8",
    )


def write_scope() -> None:
    paths = sorted(
        [
            ".github/workflows/increment-59-gates-v2.yml",
            "core/src/main/scala/spinal/core/BlackBox.scala",
            "core/src/main/scala/spinal/core/internals/ParameterizedBlackBoxGeneric.scala",
            "docs/morphhdl/increment-59-typed-blackbox-generics.md",
            "morphhdl/contracts/increment-55-native-change-review.json",
            "morphhdl/contracts/increment-59-source-scope.txt",
            "morphhdl/contracts/native-source-preservation.json",
            "morphhdl/scripts/check-increment-59-blackbox-generics.py",
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


def remove_temporary_files() -> None:
    for relative in [
        ".github/increment59-source-validation.txt",
        ".github/workflows/increment-59-export.yml",
        ".github/workflows/increment-59-bootstrap-v5.yml",
        ".github/workflows/increment-59-finalize.yml",
        ".github/workflows/increment-59-finalize-v2.yml",
    ]:
        path = ROOT / relative
        if path.exists():
            path.unlink()


def generate_native_manifest() -> None:
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
    repair_policy()
    write_generator()
    write_scope()
    remove_temporary_files()
    generate_native_manifest()


if __name__ == "__main__":
    main()
