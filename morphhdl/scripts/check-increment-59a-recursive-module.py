#!/usr/bin/env python3
"""Static contract for Increment 59a bounded recursive module support."""

from __future__ import annotations

import argparse
import pathlib
import sys


REQUIRED_FILES = (
    ".github/workflows/increment-59a-recursive-verilog-module.yml",
    "docs/morphhdl/increment-59a-bounded-recursive-verilog-module.md",
    "morphhdl/contracts/increment-59a-source-scope.txt",
    "morphhdl/scripts/check-increment-59a-recursive-module.py",
    "morphhdl/scripts/prove-increment-59a-recursive-module.sh",
    "morphhdl/src/main/scala/spinal/core/internals/BoundedRecursiveModuleValidation.scala",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala",
    "morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala",
    "morphhdl/src/test/scala/morphhdl/BoundedRecursivePowerTests.scala",
    "morphhdl/src/test/scala/nativeapplication/BoundedRecursivePowerFixture.scala",
    "morphruntime/src/main/scala/spinal/core/ParameterizedStructure.scala",
)


def require(text: str, token: str, role: str) -> None:
    if token not in text:
        raise AssertionError(f"{role} is missing required token: {token}")


def require_count(text: str, token: str, expected: int, role: str) -> None:
    actual = text.count(token)
    if actual != expected:
        raise AssertionError(
            f"{role} expected {expected} copies of {token!r}, found {actual}"
        )


def read(root: pathlib.Path, relative: str) -> str:
    path = root / relative
    if not path.is_file():
        raise AssertionError(f"missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def validate(root: pathlib.Path) -> None:
    for relative in REQUIRED_FILES:
        if not (root / relative).is_file():
            raise AssertionError(f"missing required file: {relative}")

    roadmap = read(root, "docs/morphhdl/parameterized-verilog-todo.md")
    unchecked_59a = (
        "- [ ] **Increment 59a — Bounded recursive Verilog module generation and proof**"
    )
    checked_59a = unchecked_59a.replace("[ ]", "[x]", 1)
    require_count(
        roadmap,
        unchecked_59a if unchecked_59a in roadmap else checked_59a,
        1,
        "roadmap Increment 59a entry",
    )
    if roadmap.count(unchecked_59a) + roadmap.count(checked_59a) != 1:
        raise AssertionError("roadmap must contain exactly one Increment 59a entry")
    require_count(
        roadmap,
        "- [ ] **Increment 59b — Typed parameterized Vec reduceBalancedTree**",
        1,
        "roadmap Increment 59b entry",
    )
    require(
        roadmap,
        "**Dependencies:** Increment 59 implemented and merged.",
        "roadmap Increment 59a dependency",
    )
    require(
        roadmap,
        "**Dependencies:** Increment 59a implemented and merged.",
        "roadmap Increment 59b dependency",
    )

    fixture = read(
        root,
        "morphhdl/src/test/scala/nativeapplication/BoundedRecursivePowerFixture.scala",
    )
    for token in (
        'setDefinitionName("BoundedRecursivePower")',
        'setBlackBoxName(moduleName)',
        'addGeneric("N", nextExponent)',
        'exponent.hdlEq(0).generateIf("g_base", "g_step")',
        'new SelfReference("BoundedRecursivePower", exponent - 1)',
        'new SelfReference("BoundedRecursivePowerNonDecreasing", exponent)',
        'HdlInt.param("N", default = 2, min = -1, max = 4)',
        "final class ConcretePower(exponent: Int)",
        "for (_ <- 0 until exponent)",
    ):
        require(fixture, token, "recursive fixture")
    for forbidden in ("setInlineVerilog", "addRTLPath", "ParamRTL"):
        if forbidden in fixture:
            raise AssertionError(f"recursive fixture must not use {forbidden}")

    validator = read(
        root,
        "morphhdl/src/main/scala/spinal/core/internals/BoundedRecursiveModuleValidation.scala",
    )
    for token in (
        "blackBox.definitionName == ownerName",
        "ParameterizedBlackBoxGenericRegistry.recordsOf(selfReference)",
        "requireAuthoritativeIntegerDomain",
        "parameter.minimum == 0",
        "result >= 0 && result < rootValue",
        "evaluations.map(_._1).toSet == positiveValues",
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-METRIC-NONDECREASING",
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-BINDING-UNPROVEN",
        "selfReference.impl != null || selfReference.listRTLPath.nonEmpty",
    ):
        require(validator, token, "bounded recursion validator")

    publication = read(
        root,
        "morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala",
    )
    require_count(
        publication,
        "BoundedRecursiveModuleValidation.validate(components)",
        1,
        "publication validation hook",
    )

    tests = read(root, "morphhdl/src/test/scala/morphhdl/BoundedRecursivePowerTests.scala")
    for token in (
        "moduleDefinitionCount(first, \"BoundedRecursivePower\") == 1",
        "parameter integer N = 5",
        ".NN-1",
        "RECURSION-METRIC-NONDECREASING",
        "RECURSION-BINDING-UNPROVEN",
        "BoundedRecursivePowerArtifactWriter",
    ):
        require(tests, token, "recursive tests")

    proof = read(root, "morphhdl/scripts/prove-increment-59a-recursive-module.sh")
    for token in (
        "iverilog -g2001",
        "verilator --lint-only --language 1364-2001",
        "yosys",
        "sat -verify -prove mismatch 0",
        "mutation",
        "0 1 2 3 5 8",
    ):
        require(proof, token, "tool proof")

    workflow = read(
        root, ".github/workflows/increment-59a-recursive-verilog-module.yml"
    )
    for token in (
        "'2.12.18'",
        "'2.13.12'",
        "morphhdl.BoundedRecursivePowerTests",
        "BoundedRecursivePowerArtifactWriter",
        "prove-increment-59a-recursive-module.sh",
        "check-native-source-preservation.py",
        "check-production-retirement.py",
        "Increment 59a canonical closure",
    ):
        require(workflow, token, "Increment 59a workflow")

    scope_path = root / "morphhdl/contracts/increment-59a-source-scope.txt"
    scope_lines = scope_path.read_text(encoding="utf-8").splitlines()
    if not scope_lines or any(not line or line.startswith("#") for line in scope_lines):
        raise AssertionError("Increment 59a source scope must be sealed and non-empty")
    if scope_lines != sorted(set(scope_lines)):
        raise AssertionError("Increment 59a source scope must be sorted and unique")
    if tuple(scope_lines) != REQUIRED_FILES:
        raise AssertionError(
            "Increment 59a source scope does not equal the exact required inventory"
        )


def self_test() -> None:
    try:
        require("alpha", "beta", "self-test")
    except AssertionError as error:
        if "beta" not in str(error):
            raise
    else:
        raise AssertionError("contract self-test failed to reject a missing token")

    try:
        require_count("x x", "x", 1, "self-test")
    except AssertionError as error:
        if "found 2" not in str(error):
            raise
    else:
        raise AssertionError("contract self-test failed to reject duplicate tokens")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=pathlib.Path)
    parser.add_argument("--self-test", action="store_true")
    arguments = parser.parse_args(argv)

    if arguments.self_test:
        self_test()
    if arguments.repo_root is not None:
        validate(arguments.repo_root.resolve())
    if not arguments.self_test and arguments.repo_root is None:
        parser.error("provide --repo-root and/or --self-test")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
