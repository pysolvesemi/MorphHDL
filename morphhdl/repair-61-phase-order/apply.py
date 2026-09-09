#!/usr/bin/env python3
"""Apply the exact Increment 61 signedness/recursive phase-order fix."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

BASE = "eb8efe39e943ec0711d34ecfbb0787a647251ad4"
STAGING = {
    ".github/workflows/increment-61-apply-phase-order.yml",
    "morphhdl/repair-61-phase-order/apply.py",
}


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    if text.count(old) != 1:
        raise RuntimeError(f"{label} patch precondition failed")
    path.write_text(text.replace(old, new))


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, head], check=True)
    staged = set(subprocess.check_output(
        ["git", "diff", "--name-only", BASE, head], text=True
    ).splitlines())
    if staged != STAGING:
        raise RuntimeError(f"unexpected staging delta: {sorted(staged ^ STAGING)}")

    production = Path("morphhdl/src/main/scala/morphhdl/MorphVerilog.scala")
    replace_once(
        production,
        '''  private def copyForSingleSource(config: SpinalConfig, workspace: Path): SpinalConfig = {
    val phaseInserters = config.phasesInserters.clone()
    phaseInserters += ExternalParameterizedNativeResize.install _
    phaseInserters += ExternalParameterizedAutoResize.install _
    phaseInserters += ExternalParameterizedHighBit.install _
    phaseInserters += TypedBalancedReductionBackend.install _
    phaseInserters += MorphHdlRecursivePerComponentPublication.install _
''',
        '''  private def copyForSingleSource(config: SpinalConfig, workspace: Path): SpinalConfig = {
    val phaseInserters = config.phasesInserters.clone()
    // The recursive adapter brackets PhaseVerilog. Install it before every
    // caller-owned inserter so a pre-existing strict signedness observation can
    // still claim the exact phase immediately before the emitter. Consolidated
    // publication must not acquire these otherwise inactive lifecycle phases.
    if (config.oneFilePerComponent)
      phaseInserters.insert(0, MorphHdlRecursivePerComponentPublication.install _)
    phaseInserters += ExternalParameterizedNativeResize.install _
    phaseInserters += ExternalParameterizedAutoResize.install _
    phaseInserters += ExternalParameterizedHighBit.install _
    phaseInserters += TypedBalancedReductionBackend.install _
''',
        "single-source phase order",
    )

    tests = Path(
        "morphhdl/src/test/scala/morphhdl/Increment61PerComponentPublicationTests.scala"
    )
    replace_once(
        tests,
        '''import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.{formalParam, HdlInt}
''',
        '''import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.internals.MorphHdlSignednessAnalysis

import morphhdl.frontend.{formalParam, HdlInt}
''',
        "signedness test import",
    )
    marker = '  test("split publication preserves a validated bounded recursive owner file") {\n'
    addition = '''  test("split lifecycle preserves an existing strict signedness emission boundary") {
    for (split <- Vector(false, true)) {
      withTemporaryDirectory { directory =>
        var observations = 0
        val base = SpinalConfig(
          targetDirectory = directory.toString,
          oneFilePerComponent = split
        )
        val config = MorphSignedDeclarations.disable(base)
        config.phasesInserters += MorphHdlSignednessAnalysis.install { snapshot =>
          observations += 1
          assert(snapshot.facts.nonEmpty)
        }

        val report = MorphVerilog(config)(hierarchical())
        assert(observations == 1)
        assert(report.generatedSourcesPaths.nonEmpty)
      }
    }
  }

'''
    text = tests.read_text()
    if text.count(marker) != 1 or "split lifecycle preserves an existing strict signedness" in text:
        raise RuntimeError("signedness phase regression patch precondition failed")
    tests.write_text(text.replace(marker, addition + marker))

    contract_path = Path("morphhdl/contracts/increment-61-source-review.json")
    contract = json.loads(contract_path.read_text())
    updated = {str(production): digest(production), str(tests): digest(tests)}
    found = set()
    for entry in contract["reviewed_files"]:
        if entry["path"] in updated:
            entry["after_sha256"] = updated[entry["path"]]
            found.add(entry["path"])
    if found != set(updated):
        raise RuntimeError(f"missing source-review entries: {sorted(set(updated) - found)}")
    contract_path.write_text(json.dumps(contract, indent=2) + "\n")

    checker = Path("morphhdl/scripts/check-increment-61-source-review.py")
    checker_text, count = re.subn(
        r'CONTRACT_SHA256 = "[0-9a-f]{64}"',
        f'CONTRACT_SHA256 = "{digest(contract_path)}"',
        checker.read_text(),
    )
    if count != 1:
        raise RuntimeError("source-review digest patch precondition failed")
    checker.write_text(checker_text)
    subprocess.run(["python3", "-m", "py_compile", str(checker)], check=True)


if __name__ == "__main__":
    main()

# Second staging push: the workflow is already present and can now execute.
