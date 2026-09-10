#!/usr/bin/env python3
"""Finalize the reviewed Increment 61 phase-order repair before exact-head audit."""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path

BASE = "eb8efe39e943ec0711d34ecfbb0787a647251ad4"
STAGING = {
    ".github/workflows/increment-61-apply-phase-order.yml",
    ".github/workflows/increment-61-finalize-phase-order.yml",
    ".github/workflows/increment-61-commit-then-audit.yml",
    "morphhdl/repair-61-phase-order/apply.py",
    "morphhdl/repair-61-phase-order-v2/apply.py",
    "morphhdl/repair-61-phase-order-v2/trigger.txt",
    "morphhdl/repair-61-phase-order-v3/apply.py",
    "morphhdl/repair-61-phase-order-v3/trigger.txt",
}


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    old_count = text.count(old)
    new_count = text.count(new)
    if old_count == 1 and new_count == 0:
        path.write_text(text.replace(old, new))
    elif old_count == 0 and new_count == 1:
        return
    else:
        raise RuntimeError(
            f"{label} patch state is ambiguous: old={old_count}, new={new_count}"
        )


def main() -> None:
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, head], check=True)

    contract_path = Path("morphhdl/contracts/increment-61-source-review.json")
    contract = json.loads(contract_path.read_text())
    reviewed = {entry["path"] for entry in contract["reviewed_files"]}
    audited = set(contract["audit_paths"])
    changed = set(
        subprocess.check_output(
            ["git", "diff", "--no-renames", "--name-only", BASE, head], text=True
        ).splitlines()
    )
    allowed = reviewed | audited | STAGING
    require(not (changed - allowed), f"unexpected staging delta: {sorted(changed - allowed)}")

    production = Path("morphhdl/src/main/scala/morphhdl/MorphVerilog.scala")
    old = '''  private def copyForSingleSource(config: SpinalConfig, workspace: Path): SpinalConfig = {
    val phaseInserters = config.phasesInserters.clone()
    phaseInserters += ExternalParameterizedNativeResize.install _
    phaseInserters += ExternalParameterizedAutoResize.install _
    phaseInserters += ExternalParameterizedHighBit.install _
    phaseInserters += TypedBalancedReductionBackend.install _
    phaseInserters += MorphHdlRecursivePerComponentPublication.install _
'''
    new = '''  private def copyForSingleSource(config: SpinalConfig, workspace: Path): SpinalConfig = {
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
'''
    replace_once(production, old, new, "single-source phase order")

    tests = Path("morphhdl/src/test/scala/morphhdl/Increment61PerComponentPublicationTests.scala")
    test_text = tests.read_text()
    plain_import = '''import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.{formalParam, HdlInt}
'''
    signed_import = '''import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.internals.MorphHdlSignednessAnalysis

import morphhdl.frontend.{formalParam, HdlInt}
'''
    if signed_import not in test_text:
        require(test_text.count(plain_import) == 1, "signedness test import precondition failed")
        test_text = test_text.replace(plain_import, signed_import)

    marker = '  test("split publication preserves a validated bounded recursive owner file") {\n'
    test_name = "split lifecycle preserves an existing strict signedness emission boundary"
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
    if test_name not in test_text:
        require(test_text.count(marker) == 1, "signedness phase regression insertion point changed")
        test_text = test_text.replace(marker, addition + marker)
    else:
        require(test_text.count(test_name) == 1, "duplicate signedness phase regression")
    tests.write_text(test_text)

    updated = {str(production): digest(production), str(tests): digest(tests)}
    found = set()
    for entry in contract["reviewed_files"]:
        if entry["path"] in updated:
            entry["after_sha256"] = updated[entry["path"]]
            found.add(entry["path"])
    require(found == set(updated), f"missing source-review entries: {sorted(set(updated) - found)}")
    contract_path.write_text(json.dumps(contract, indent=2) + "\n")

    checker = Path("morphhdl/scripts/check-increment-61-source-review.py")
    checker_text, count = re.subn(
        r'CONTRACT_SHA256 = "[0-9a-f]{64}"',
        f'CONTRACT_SHA256 = "{digest(contract_path)}"',
        checker.read_text(),
    )
    require(count == 1, "source-review digest patch precondition failed")
    checker.write_text(checker_text)
    subprocess.run(["python3", "-m", "py_compile", str(checker)], check=True)


if __name__ == "__main__":
    main()
