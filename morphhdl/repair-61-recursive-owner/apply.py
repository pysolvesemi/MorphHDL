#!/usr/bin/env python3
"""Apply the exact Increment 61 recursive-owner publication fix."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

BASE = "0aecc067b76f954152bdeb15cd3cf6167a5ff4f2"
STAGING = {
    ".github/workflows/increment-61-apply-recursive-owner.yml",
    "morphhdl/repair-61-recursive-owner/apply.py",
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

    production = Path(
        "morphhdl/src/main/scala/spinal/core/internals/"
        "MorphHdlExternalParameterizedVerilog.scala"
    )
    replace_once(
        production,
        """    val recursiveReferences = BoundedRecursiveModuleValidation.validate(components)\n    components.foreach(component =>\n""",
        """    val recursiveReferences = BoundedRecursiveModuleValidation.validate(components)\n    val recursiveReferenceIdentities =\n      new IdentityHashMap[BlackBox, java.lang.Boolean]()\n    recursiveReferences.foreach(reference =>\n      recursiveReferenceIdentities.put(reference, java.lang.Boolean.TRUE)\n    )\n    components.foreach(component =>\n""",
        "recursive identity capture",
    )
    replace_once(
        production,
        """      components.collect {\n        case component\n            if component.isInBlackBoxTree || component.isInstanceOf[BlackBox] =>\n          componentName(component)\n      }.distinct.foreach { name =>\n""",
        """      components.collect {\n        case component\n            if (component.isInBlackBoxTree || component.isInstanceOf[BlackBox]) &&\n              !recursiveReferenceIdentities.containsKey(component) =>\n          componentName(component)\n      }.distinct.foreach { name =>\n""",
        "recursive owner deletion exclusion",
    )

    tests = Path(
        "morphhdl/src/test/scala/morphhdl/Increment61PerComponentPublicationTests.scala"
    )
    replace_once(
        tests,
        "import nativeapplication.TypedBlackBoxGenericBindingFixture\n",
        "import nativeapplication.{BoundedRecursivePowerFixture, TypedBlackBoxGenericBindingFixture}\n",
        "recursive fixture import",
    )
    marker = '  test("split publication keeps typed BlackBox definitions external") {\n'
    addition = '''  test("split publication preserves a validated bounded recursive owner file") {
    withTemporaryDirectory { directory =>
      val report = MorphVerilog(
        SpinalConfig(
          targetDirectory = directory.toString,
          oneFilePerComponent = true
        )
      )(BoundedRecursivePowerFixture.parameterized())

      val names = report.generatedSourcesPaths.map(path => Paths.get(path).getFileName.toString)
      assert(names == Vector("BoundedRecursivePower.v"))
      val recursive = read(directory.resolve("BoundedRecursivePower.v"))
      assert(moduleDefinitions(recursive) == Vector("BoundedRecursivePower"))
      assert(recursive.contains("parameter integer N = 5"))
      assert(recursive.contains("BoundedRecursivePower #("))
      assert(!recursive.contains("__morphhdl_recursive_reference_"))
      assert(
        read(directory.resolve("BoundedRecursivePower.lst"))
          .split("\\n", -1)
          .toVector
          .filter(_.nonEmpty) == names
      )
    }
  }

'''
    text = tests.read_text()
    if text.count(marker) != 1 or "preserves a validated bounded recursive owner file" in text:
        raise RuntimeError("recursive regression patch precondition failed")
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
