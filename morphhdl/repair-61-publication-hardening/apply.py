#!/usr/bin/env python3
"""Apply the exact Increment 61 publication ownership hardening patch."""
from __future__ import annotations

from pathlib import Path
import hashlib
import json
import re
import subprocess

BASE = "359a2cb595ea06cb4c04912b33d0b8d2d85c64d1"
STAGING = {
    ".github/workflows/increment-61-apply-publication-hardening.yml",
    "morphhdl/repair-61-publication-hardening/apply.py",
}


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    if text.count(old) != 1:
        raise RuntimeError(f"{label} patch precondition failed")
    path.write_text(text.replace(old, new))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, head], check=True)
    staged = set(
        subprocess.check_output(
            ["git", "diff", "--name-only", BASE, head], text=True
        ).splitlines()
    )
    if staged != STAGING:
        raise RuntimeError(f"unexpected staging delta: {sorted(staged ^ STAGING)}")

    production = Path(
        "morphhdl/src/main/scala/morphhdl/MorphPerComponentPublication.scala"
    )
    replace_once(
        production,
        '''        previous.get(file.relativePath) match {
          case Some(expected) if actual != expected =>
            throw new IllegalArgumentException(
              s"MORPHDL-ONE-FILE-PUBLISH-MANAGED-MODIFIED: ${file.relativePath}"
            )
          case None if actual != current(file.relativePath) =>
            throw new IllegalArgumentException(
              s"MORPHDL-ONE-FILE-PUBLISH-UNOWNED-COLLISION: ${file.relativePath}"
            )
          case _ =>
        }
''',
        '''        previous.get(file.relativePath) match {
          case Some(expected) if actual != expected =>
            throw new IllegalArgumentException(
              s"MORPHDL-ONE-FILE-PUBLISH-MANAGED-MODIFIED: ${file.relativePath}"
            )
          case Some(_) =>
          case None =>
            throw new IllegalArgumentException(
              s"MORPHDL-ONE-FILE-PUBLISH-UNOWNED-COLLISION: ${file.relativePath}"
            )
        }
''',
        "publication preflight",
    )

    tests = Path(
        "morphhdl/src/test/scala/morphhdl/Increment61PerComponentPublicationTests.scala"
    )
    marker = (
        '  test("oneFilePerComponent rejects a shared netlist filename before elaboration") {\n'
    )
    addition = '''  test("byte-identical unowned output collision fails closed") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        oneFilePerComponent = true
      )
      val content =
        "module Increment61Top;\\nendmodule\\n".getBytes(StandardCharsets.UTF_8)
      val target = directory.resolve("Increment61Top.v")
      Files.write(target, content)

      MorphPerComponentPublication.publish(
        config,
        "Increment61Top",
        Vector(
          MorphPreparedPublicationFile(
            "Increment61Top.v",
            content,
            reportAsSource = true
          )
        )
      ) match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.OutputWrite)
          assert(failure.detail.contains("MORPHDL-ONE-FILE-PUBLISH-UNOWNED-COLLISION"))
        case Right(paths) => fail(s"expected unowned collision rejection, received $paths")
      }

      assert(java.util.Arrays.equals(Files.readAllBytes(target), content))
      assert(!Files.exists(directory.resolve("Increment61Top.lst")))
      assert(
        !Files.exists(
          directory.resolve(".Increment61Top.morphhdl-one-file-per-component.manifest")
        )
      )
    }
  }

'''
    text = tests.read_text()
    if text.count(marker) != 1 or "byte-identical unowned output collision" in text:
        raise RuntimeError("publication regression-test patch precondition failed")
    tests.write_text(text.replace(marker, addition + marker))

    recursive = (
        "morphhdl/src/main/scala/spinal/core/internals/"
        "MorphHdlRecursivePerComponentPublication.scala"
    )
    trigger = (
        "      - 'morphhdl/src/main/scala/spinal/core/internals/"
        "MorphHdlExternalParameterizedVerilog.scala'\n"
    )
    for relative in (
        ".github/workflows/increment-61-one-file-per-component.yml",
        ".github/workflows/increment-61-compatibility-matrix.yml",
    ):
        path = Path(relative)
        body = path.read_text()
        if body.count(trigger) != 2 or recursive in body:
            raise RuntimeError(f"workflow trigger patch precondition failed: {relative}")
        path.write_text(body.replace(trigger, trigger + f"      - '{recursive}'\n"))

    contract_path = Path("morphhdl/contracts/increment-61-source-review.json")
    contract = json.loads(contract_path.read_text())
    updated = {str(production): sha256(production), str(tests): sha256(tests)}
    found: set[str] = set()
    for entry in contract["reviewed_files"]:
        path = entry["path"]
        if path in updated:
            entry["after_sha256"] = updated[path]
            found.add(path)
    if found != set(updated):
        raise RuntimeError(f"source-review entries missing: {sorted(set(updated) - found)}")
    contract_path.write_text(json.dumps(contract, indent=2) + "\n")

    checker = Path("morphhdl/scripts/check-increment-61-source-review.py")
    checker_text, count = re.subn(
        r'CONTRACT_SHA256 = "[0-9a-f]{64}"',
        f'CONTRACT_SHA256 = "{sha256(contract_path)}"',
        checker.read_text(),
    )
    if count != 1:
        raise RuntimeError("source-review checker digest patch precondition failed")
    checker.write_text(checker_text)
    subprocess.run(["python3", "-m", "py_compile", str(checker)], check=True)


if __name__ == "__main__":
    main()
