#!/usr/bin/env python3
"""Validate Increment 59 typed BlackBox generic and port binding contracts."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Optional, Sequence


class ContractError(RuntimeError):
    pass


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise ContractError(detail)


def repository_root(explicit: Optional[str]) -> Path:
    if explicit:
        return Path(explicit).resolve()
    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise ContractError(result.stderr.strip() or "not inside a Git repository")
    return Path(result.stdout.strip()).resolve()


def read(root: Path, relative: str) -> str:
    path = root / relative
    require(path.is_file(), f"missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def one(text: str, token: str, role: str) -> None:
    count = text.count(token)
    require(count == 1, f"{role} must contain exactly one {token!r}, found {count}")


def check_sources(root: Path) -> None:
    todo = read(root, "docs/morphhdl/parameterized-verilog-todo.md")
    one(
        todo,
        "**Increment 59 — Typed BlackBox parameter and generic binding**",
        "roadmap",
    )
    require(
        "**Dependencies:** Increment 58 implemented and merged." in todo,
        "Increment 59 dependency is missing",
    )

    blackbox = read(root, "core/src/main/scala/spinal/core/BlackBox.scala")
    one(blackbox, "case value: ElabInt =>", "BlackBox.addGeneric")
    one(blackbox, "case value: ElabBool =>", "BlackBox.addGeneric")
    require(
        blackbox.count("ParameterizedBlackBoxGenericRegistry.retain") == 2,
        "BlackBox.addGeneric must retain exactly the integer and Boolean typed cases",
    )

    registry = read(
        root,
        "core/src/main/scala/spinal/core/internals/ParameterizedBlackBoxGeneric.scala",
    )
    for token in (
        "object ParameterizedBlackBoxGenericRegistry",
        "blackBox.userCache",
        "authoritativeProjectedExpression",
        "value.projectedExpression(role)",
        "def integerExpressionsOf",
        "def booleanExpressionsOf",
        "def portWidthsOf",
    ):
        require(token in registry, f"typed BlackBox registry is missing {token!r}")
    for forbidden in (
        "ThreadLocal",
        "sourcecode.File",
        "getScalaLocation",
        "definitionName ==",
        "getName() ==",
        "NativeIntShadow",
    ):
        require(forbidden not in registry, f"registry contains forbidden reconstruction token {forbidden!r}")

    hierarchy = read(
        root,
        "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala",
    )
    for token in (
        "BooleanExpressionBinding",
        "preserveExistingGenericAssociations",
        "analyzeBlackBoxInstance",
        "ParameterizedBlackBoxGenericRegistry.recordsOf",
        "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-GENERIC-DUPLICATE",
        "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-GENERIC-ASSOCIATION-NOT-FOUND",
    ):
        require(token in hierarchy, f"hierarchy integration is missing {token!r}")
    require(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BLACKBOX-UNSUPPORTED" not in hierarchy,
        "the retired blanket BlackBox rejection remains in production",
    )

    fallback = read(
        root,
        "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala",
    )
    main = read(
        root,
        "morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala",
    )
    require(
        "ParameterizedBlackBoxGenericRegistry.hasSymbolicBindings(component)" in fallback,
        "fallback support predicate does not recognize typed BlackBox bindings",
    )
    compact_main = re.sub(r"\s+", "", main)
    for token in (
        "ParameterizedBlackBoxGenericRegistry.integerExpressionsOf(component)",
        "ParameterizedBlackBoxGenericRegistry.booleanExpressionsOf(component)",
        "ParameterizedBlackBoxGenericRegistry.parametersOf(component)",
        "ParameterizedBlackBoxGenericRegistry.hasSymbolicBindings(component)",
    ):
        require(
            token in compact_main,
            f"main publication boundary is missing {token!r}",
        )

    production = "\n".join((blackbox, registry, hierarchy, fallback, main))
    for fixture_name in (
        "TypedExternalLeaf",
        "TypedParameterOnlyExternal",
        "TypedBlackBoxGenericTop",
        "external_a",
        "external_b",
    ):
        require(
            fixture_name not in production,
            f"production source recognizes test fixture name {fixture_name!r}",
        )

    tests = read(
        root,
        "morphhdl/src/test/scala/morphhdl/TypedBlackBoxGenericBindingTests.scala",
    )
    fixture = read(
        root,
        "morphhdl/src/test/scala/nativeapplication/TypedBlackBoxGenericBindingFixture.scala",
    )
    require("SpinalVhdl" in tests, "native VHDL witness compatibility test is missing")
    require("DuplicateGenericExternal" in fixture, "duplicate generic negative fixture is missing")
    require("ConcreteMatrixTop" in fixture, "independent concrete formal witness is missing")


def module_is_defined(verilog: str, name: str) -> bool:
    return re.search(rf"(?m)^\s*module\s+{re.escape(name)}\b", verilog) is not None


def instance_block(verilog: str, definition: str, instance: str) -> str:
    lines = verilog.splitlines()
    terminator = re.compile(rf"^\s*\)\s+{re.escape(instance)}\s*\(\s*$")
    plain = re.compile(
        rf"^\s*{re.escape(definition)}\s+{re.escape(instance)}\s*\(\s*$"
    )
    bodies = [
        index
        for index, line in enumerate(lines)
        if terminator.match(line) or plain.match(line)
    ]
    require(
        len(bodies) == 1,
        f"expected one {definition} {instance} body, found {len(bodies)}",
    )
    body = bodies[0]
    if plain.match(lines[body]):
        start = body
    else:
        starts = [
            index
            for index in range(body - 1, -1, -1)
            if re.match(rf"^\s*{re.escape(definition)}\s*#\s*\(\s*$", lines[index])
        ]
        require(bool(starts), f"missing parameterized start for {definition} {instance}")
        start = starts[0]
    ends = [index for index in range(body + 1, len(lines)) if lines[index].strip() == ");"]
    require(bool(ends), f"missing terminator for {definition} {instance}")
    return "\n".join(lines[start : ends[0] + 1])


def association(block: str, name: str) -> str:
    matches = re.findall(rf"(?m)^.*\.{re.escape(name)}\s*\(.*$", block)
    require(len(matches) == 1, f"expected one association {name}, found {len(matches)}")
    return matches[0]


def check_artifact(path: Path) -> None:
    require(path.is_file(), f"generated artifact does not exist: {path}")
    verilog = path.read_text(encoding="utf-8")
    require("module TypedBlackBoxGenericTop #(" in verilog, "typed top parameter header is missing")
    for parameter, default in (("ENABLE", "1"), ("LATENCY", "2"), ("WIDTH", "8")):
        require(
            re.search(rf"\bparameter\s+integer\s+{parameter}\s*=\s*{default}\b", verilog)
            is not None,
            f"parameter {parameter}={default} is missing",
        )
    require(not module_is_defined(verilog, "TypedExternalLeaf"), "external leaf was generated")
    require(
        not module_is_defined(verilog, "TypedParameterOnlyExternal"),
        "parameter-only external module was generated",
    )

    external_a = instance_block(verilog, "TypedExternalLeaf", "external_a")
    external_b = instance_block(verilog, "TypedExternalLeaf", "external_b")
    parameter_only = instance_block(
        verilog, "TypedParameterOnlyExternal", "parameter_only"
    )

    require('"typed"' in association(external_a, "LABEL"), "string generic changed")
    require(association(external_a, "WIDTH").count("WIDTH") >= 2, "external_a WIDTH is concrete")
    require("WIDTH" in association(external_a, "DOUBLE_WIDTH"), "derived WIDTH is missing")
    require("ENABLE" in association(external_a, "ENABLED"), "Boolean binding is missing")
    require("4" in association(external_a, "DEPTH"), "concrete integer generic changed")
    require("1'b1" in association(external_a, "CONCRETE_ENABLE"), "concrete Boolean generic changed")
    require(association(external_b, "WIDTH").count("WIDTH") >= 2, "external_b WIDTH is concrete")
    require("1" in association(external_b, "WIDTH"), "external_b derived offset is missing")
    require("ENABLE" in association(external_b, "ENABLED"), "external_b Boolean binding is missing")
    require(association(parameter_only, "LATENCY").count("LATENCY") >= 2, "BlackBox-only parameter is concrete")

    require(
        re.search(r"(?m)^.*\.din\s*\(\s*narrow_in\s*\[.*WIDTH.*:0\]", external_a)
        is not None,
        "external_a symbolic input slice is missing",
    )
    require(
        re.search(r"(?m)^.*\.din\s*\(\s*wide_in\s*\[.*WIDTH.*:0\]", external_b)
        is not None,
        "external_b symbolic input slice is missing",
    )
    require("ParamRTL" not in verilog, "legacy ParamRTL token leaked into output")
    require("NativeIntShadow" not in verilog, "native-Int shadow token leaked into output")


def self_test() -> None:
    good = """
module TypedBlackBoxGenericTop #(
  parameter integer ENABLE = 1,
  parameter integer LATENCY = 2,
  parameter integer WIDTH = 8
) (
  input wire [WIDTH-1:0] narrow_in,
  input wire [(WIDTH + 1)-1:0] wide_in
);
  TypedExternalLeaf #(
    .LABEL ("typed"),
    .WIDTH (WIDTH),
    .DEPTH (4),
    .DOUBLE_WIDTH ((WIDTH * 2)),
    .CONCRETE_ENABLE (1'b1),
    .ENABLED (((ENABLE) == (1)))
  ) external_a (
    .din (narrow_in[WIDTH-1:0]), //i
    .dout () //o
  );
  TypedExternalLeaf #(
    .LABEL ("typed"),
    .WIDTH ((WIDTH + 1)),
    .DEPTH (4),
    .DOUBLE_WIDTH (((WIDTH + 1) * 2)),
    .CONCRETE_ENABLE (1'b1),
    .ENABLED (!(((ENABLE) == (1))))
  ) external_b (
    .din (wide_in[(WIDTH + 1)-1:0]), //i
    .dout () //o
  );
  TypedParameterOnlyExternal #(
    .LATENCY (LATENCY)
  ) parameter_only (
    .din (fixed_in), //i
    .dout () //o
  );
endmodule
""".strip()
    with tempfile.TemporaryDirectory(prefix="increment-59-self-test-") as temporary:
        path = Path(temporary) / "good.v"
        path.write_text(good, encoding="utf-8")
        check_artifact(path)
        path.write_text(good.replace(".WIDTH (WIDTH)", ".WIDTH (8)", 1), encoding="utf-8")
        try:
            check_artifact(path)
        except ContractError:
            pass
        else:
            raise ContractError("artifact mutation was not rejected")


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root")
    parser.add_argument("--artifact")
    parser.add_argument("--self-test", action="store_true")
    arguments = parser.parse_args(argv)
    try:
        if arguments.self_test:
            self_test()
        elif arguments.artifact:
            check_artifact(Path(arguments.artifact).resolve())
        else:
            check_sources(repository_root(arguments.repo_root))
    except ContractError as error:
        print(f"Increment 59 contract failed: {error}", file=sys.stderr)
        return 1
    print("Increment 59 typed BlackBox contract passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
