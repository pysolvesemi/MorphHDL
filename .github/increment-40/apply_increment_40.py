#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE = "8c4241396cd718a36227dcd89a2e6a29d9077f11"
RESTORED = [
    "core/src/main/scala/spinal/core/BaseType.scala",
    "core/src/main/scala/spinal/core/Bits.scala",
    "core/src/main/scala/spinal/core/SInt.scala",
    "core/src/main/scala/spinal/core/UInt.scala",
]


def run(*args: str, capture: bool = False) -> str:
    result = subprocess.run(
        args,
        cwd=ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE if capture else None,
    )
    return result.stdout.strip() if capture else ""


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


PARAMETERIZED_WIDTH = r'''package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.collection.mutable

/**
  * Elaboration metadata for one public integer parameter used directly as a
  * packed width.
  *
  * The concrete `default` remains the width used by ordinary SpinalHDL
  * elaboration and validation. MorphHDL retains the symbolic identity in an
  * external object-identity registry rather than modifying native data types.
  */
final case class ElaborationIntegerParameter(
    name: String,
    default: BigInt,
    minimum: BigInt,
    maximum: BigInt
)

/** A concrete witness bit count with an optional bounded symbolic expression. */
final case class ParameterizedBitCount(
    value: Int,
    parameter: Option[ElaborationIntegerParameter],
    sourceLocation: Option[String] = None,
    expression: Option[ElaborationIntegerExpression] = None
)

object ParameterizedBitCount {
  def apply(
      value: Int,
      parameter: ElaborationIntegerParameter
  ): ParameterizedBitCount =
    new ParameterizedBitCount(value, Some(parameter), sourceLocation = None)

  def apply(
      value: Int,
      parameter: ElaborationIntegerParameter,
      sourceLocation: Option[String]
  ): ParameterizedBitCount =
    new ParameterizedBitCount(value, Some(parameter), sourceLocation)
}

private[core] final case class RetainedWidth(
    directParameter: Option[ElaborationIntegerParameter],
    expression: Option[ElaborationIntegerExpression],
    sourceLocation: Option[String]
)

/** Weak key with identity, rather than hardware equality, semantics. */
private[core] final class RetainedWidthIdentityRef(
    value: BaseType,
    queue: ReferenceQueue[BaseType]
) extends WeakReference[BaseType](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: RetainedWidthIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/**
  * MorphHDL-owned symbolic-width registry and native-factory adapters.
  *
  * Native `BaseType`, `Bits`, `UInt` and `SInt` source remains untouched. The
  * registry associates retained geometry with concrete native objects by
  * identity. Clone-sensitive APIs are wrapped externally and still delegate to
  * the ordinary SpinalHDL algorithms.
  */
object ParameterizedWidth {
  private val queue = new ReferenceQueue[BaseType]()
  private val retained = mutable.HashMap.empty[RetainedWidthIdentityRef, RetainedWidth]

  private def reap(): Unit = {
    var reference = queue.poll().asInstanceOf[RetainedWidthIdentityRef]
    while (reference != null) {
      retained.remove(reference)
      reference = queue.poll().asInstanceOf[RetainedWidthIdentityRef]
    }
  }

  private def metadataOf(data: BaseType): Option[RetainedWidth] = synchronized {
    reap()
    retained.get(new RetainedWidthIdentityRef(data, null))
  }

  private def retain(data: BaseType, metadata: RetainedWidth): Unit = synchronized {
    reap()
    retained.update(new RetainedWidthIdentityRef(data, queue), metadata)
  }

  private def retainedExpression(width: ParameterizedBitCount): Option[ElaborationIntegerExpression] =
    width.expression.orElse {
      width.parameter.map { parameter =>
        ElaborationIntegerExpression(
          verilog = parameter.name,
          default = parameter.default,
          minimum = parameter.minimum,
          maximum = parameter.maximum,
          parameters = Vector(parameter),
          sourceLocation = width.sourceLocation
        )
      }
    }

  /** Attach a symbolic width to one concrete native bit vector. */
  def attach[T <: BitVector](data: T, width: ParameterizedBitCount): T = {
    if (data == null) throw new IllegalArgumentException("symbolic-width target must not be null")
    if (width == null) throw new IllegalArgumentException("symbolic bit count must not be null")
    data.setWidth(width.value)
    val expression = retainedExpression(width)
    if (expression.exists(_.parameters.nonEmpty)) {
      retain(
        data,
        RetainedWidth(width.parameter, expression, width.sourceLocation)
      )
    }
    data
  }

  /** MorphHDL shadow factories; each delegates to the untouched native factory. */
  def Bits(width: ParameterizedBitCount): spinal.core.Bits =
    attach(spinal.core.Bits(BitCount(width.value)), width)
  def Bits(width: BitCount): spinal.core.Bits = spinal.core.Bits(width)

  def UInt(width: ParameterizedBitCount): spinal.core.UInt =
    attach(spinal.core.UInt(BitCount(width.value)), width)
  def UInt(width: BitCount): spinal.core.UInt = spinal.core.UInt(width)

  def SInt(width: ParameterizedBitCount): spinal.core.SInt =
    attach(spinal.core.SInt(BitCount(width.value)), width)
  def SInt(width: BitCount): spinal.core.SInt = spinal.core.SInt(width)

  /** Copy registry ownership between already-created native leaves. */
  def copy(from: BaseType, to: BaseType): Unit = {
    if (from == null || to == null)
      throw new IllegalArgumentException("symbolic-width copy requires non-null leaves")
    metadataOf(from).foreach(retain(to, _))
  }

  /**
    * Copy concrete and symbolic leaf geometry in deterministic data-model order.
    * This is the external replacement for the former native `BaseType.clone`
    * hook.
    */
  def copyShape[T <: Data](from: T, to: T): T = {
    if (from == null || to == null)
      throw new IllegalArgumentException("symbolic shape copy requires non-null data")
    val sourceLeaves = from.flatten.toVector
    val targetLeaves = to.flatten.toVector
    if (sourceLeaves.size != targetLeaves.size) {
      throw new IllegalArgumentException(
        s"symbolic shape clone changed leaf count ${sourceLeaves.size} -> ${targetLeaves.size}"
      )
    }
    sourceLeaves.zip(targetLeaves).zipWithIndex.foreach {
      case ((source, target), index) =>
        if (source.getClass != target.getClass) {
          throw new IllegalArgumentException(
            s"symbolic shape clone changed leaf $index from ${source.getClass.getName} " +
              s"to ${target.getClass.getName}"
          )
        }
        (source, target) match {
          case (sourceVector: BitVector, targetVector: BitVector) =>
            targetVector.setWidth(sourceVector.getBitsWidth)
          case _ =>
        }
        copy(source, target)
    }
    to
  }

  /** Native clone algorithm plus external concrete/symbolic shape propagation. */
  def cloneOf[T <: Data](data: T): T =
    copyShape(data, spinal.core.cloneOf(data))

  /**
    * Native HardType algorithm supplied with an externally shape-preserving
    * generator. A stable template is cloned on every invocation.
    */
  def HardType[T <: Data](dataType: => T): spinal.core.HardType[T] = {
    val template = dataType
    lazy val result: spinal.core.HardType[T] =
      new spinal.core.HardType[T](cloneOf(template))
    template match {
      case bundle: Bundle => bundle.hardtype = result
      case _ =>
    }
    result
  }

  /** Untouched native register algorithm driven by the retained HardType. */
  def Reg[T <: Data](dataType: => T): T = spinal.core.Reg(HardType(dataType))

  /** Untouched native Vec algorithm driven by the retained HardType. */
  def Vec[T <: Data](dataType: => T, size: Int): spinal.core.Vec[T] =
    spinal.core.Vec(HardType(dataType), size)

  def isRetained(data: BaseType): Boolean = metadataOf(data).nonEmpty

  def parameterOf(data: BaseType): Option[ElaborationIntegerParameter] =
    metadataOf(data).flatMap(_.directParameter)

  def expressionOf(data: BaseType): Option[ElaborationIntegerExpression] =
    metadataOf(data).flatMap(_.expression)

  def sourceLocationOf(data: BaseType): Option[String] =
    metadataOf(data).flatMap(_.sourceLocation)

  def leavesOf(data: Data): Vector[BaseType] =
    data.flatten.filter(expressionOf(_).exists(_.parameters.nonEmpty)).toVector

  def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
    val leaves = scala.collection.mutable.ArrayBuffer.empty[BaseType]
    component.dslBody.walkLeafStatements {
      case baseType: BaseType if expressionOf(baseType).exists(_.parameters.nonEmpty) =>
        leaves += baseType
      case _ =>
    }
    val associated = leaves.flatMap { baseType =>
      expressionOf(baseType).toVector.flatMap(
        _.parameters.map(parameter => baseType -> parameter)
      )
    }
    val values = associated.map(_._2)
    values.groupBy(_.name).collectFirst {
      case (name, schemas) if schemas.distinct.size != 1 => name
    }.foreach { name =>
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
        s"parameter '$name' has conflicting declarations on component '${component.definitionName}'",
        associated.find(_._2.name == name).flatMap { case (baseType, _) =>
          sourceLocationOf(baseType)
        }
      )
    }
    values.distinct.sortBy(_.name).toVector
  }
}

final class ParameterizedVerilogException(
    val code: String,
    val detail: String,
    val sourceLocation: Option[String] = None
) extends IllegalArgumentException(
      s"[$code] ${sourceLocation.map(_ + ": ").getOrElse("")}$detail"
    )

private[core] object ParameterizedVerilogException {
  def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    throw new ParameterizedVerilogException(code, detail, sourceLocation)
}
'''

WRAPPERS = r'''
  /**
    * MorphHDL-owned symbolic data factories. These shadow only explicit
    * `morphhdl.frontend` calls and delegate concrete construction to untouched
    * SpinalHDL factories.
    */
  def Bits(width: ParameterizedBitCount): spinal.core.Bits =
    ParameterizedWidth.Bits(width)
  def Bits(width: BitCount): spinal.core.Bits = spinal.core.Bits(width)

  def UInt(width: ParameterizedBitCount): spinal.core.UInt =
    ParameterizedWidth.UInt(width)
  def UInt(width: BitCount): spinal.core.UInt = spinal.core.UInt(width)

  def SInt(width: ParameterizedBitCount): spinal.core.SInt =
    ParameterizedWidth.SInt(width)
  def SInt(width: BitCount): spinal.core.SInt = spinal.core.SInt(width)

  def cloneOf[T <: Data](data: T): T = ParameterizedWidth.cloneOf(data)
  def HardType[T <: Data](dataType: => T): spinal.core.HardType[T] =
    ParameterizedWidth.HardType(dataType)
  def Reg[T <: Data](dataType: => T): T = ParameterizedWidth.Reg(dataType)
  def Vec[T <: Data](dataType: => T, size: Int): spinal.core.Vec[T] =
    ParameterizedWidth.Vec(dataType, size)
'''

FOCUSED_WORKFLOW = r'''name: MorphHDL external symbolic width

on:
  push:
    branches:
      - main
      - parameterized-verilog
  pull_request:
    branches:
      - main
      - parameterized-verilog
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: morphhdl-external-symbolic-width-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  proof:
    name: External width proof Scala ${{ matrix.scala_version }}
    runs-on: ubuntu-latest
    timeout-minutes: 60
    container:
      image: ghcr.io/spinalhdl/docker:v1.2.0
    strategy:
      fail-fast: false
      matrix:
        scala_version:
          - "2.12.18"
          - "2.13.12"
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: Prove the four native files are byte-identical to Increment 0
        shell: bash
        run: |
          git diff --exit-code \
            8c4241396cd718a36227dcd89a2e6a29d9077f11 HEAD -- \
            core/src/main/scala/spinal/core/BaseType.scala \
            core/src/main/scala/spinal/core/Bits.scala \
            core/src/main/scala/spinal/core/SInt.scala \
            core/src/main/scala/spinal/core/UInt.scala
      - name: Test registry, frontend shapes and single-source contracts
        shell: bash
        run: |
          sbt -batch ++${{ matrix.scala_version }} \
            "core/testOnly spinal.core.internals.ParameterizedDataShapeTests spinal.core.internals.ParameterizedVerilogTests" \
            "frontend/testOnly morphhdl.frontend.HdlIntTests" \
            "morph/testOnly morphhdl.MorphSingleSourceVerilogTests morphhdl.NativeLibraryReuseTests morphhdl.NativeSymbolicMemoryTests"
'''

DOC = r'''# Increment 40: external symbolic width and data-shape retention

Increment 40 removes MorphHDL width hooks from the native `BaseType`, `Bits`,
`UInt` and `SInt` implementation while preserving the reviewed Increment 29 and
30 contracts.

## Architecture

The four upstream-owned files are restored byte-for-byte to the Increment 0
baseline. Symbolic geometry is now associated with native `BaseType` objects by
an identity-keyed weak registry in the existing MorphHDL sidecar
`ParameterizedWidth.scala`.

MorphHDL-owned adapters delegate to ordinary SpinalHDL algorithms:

- `Bits`, `UInt` and `SInt` construct concrete witness types through native
  factories, then register symbolic metadata externally;
- `cloneOf` invokes native cloning, restores concrete leaf widths and copies the
  external identity association;
- `HardType`, `Reg` and `Vec` use native implementations with an externally
  shape-preserving generator;
- native `Stream` and `Flow` receive that ordinary retained `HardType`, so their
  existing payload and pipeline algorithms remain authoritative.

No native constructor or clone method contains a MorphHDL callback.

## Preservation guard

The native-source preservation manifest is repinned to the implementation commit
and no longer lists the four restored files. Any later difference in those files
is therefore rejected by the existing guard.

## Validation

The focused workflow checks exact byte parity of all four restored files and
runs the width/data-shape, frontend, single-source, native library and native
memory regression suites on Scala 2.12.18 and 2.13.12. The regular native-source,
Mill, baseline, deterministic RTL and strict Verilog-2001 gates remain required.
'''


def insert_wrappers() -> None:
    path = ROOT / "frontend/src/main/scala/morphhdl/frontend/package.scala"
    text = path.read_text(encoding="utf-8")
    anchor = "  import spinal.core._\n"
    if WRAPPERS.strip() in text:
        return
    if text.count(anchor) != 1:
        raise RuntimeError("frontend package import anchor not found exactly once")
    path.write_text(text.replace(anchor, anchor + WRAPPERS, 1), encoding="utf-8")


def replace_symbolic_factories() -> None:
    roots = [ROOT / "frontend/src/test", ROOT / "morphhdl/src/test"]
    factory = re.compile(r"(?<![\w.])(Bits|UInt|SInt)\(([^()\n]*?\bbits)\)")
    leaf = r"morphhdl.frontend.\1(\2)"
    for root in roots:
        for path in root.rglob("*.scala"):
            text = path.read_text(encoding="utf-8")
            updated = factory.sub(leaf, text)
            updated = re.sub(
                r"(?<![\w.])Reg\((morphhdl\.frontend\.(?:Bits|UInt|SInt)\([^()\n]*\))\)",
                r"morphhdl.frontend.Reg(\1)",
                updated,
            )
            updated = re.sub(
                r"(?<![\w.])HardType\((morphhdl\.frontend\.(?:Bits|UInt|SInt)\([^()\n]*\))\)",
                r"morphhdl.frontend.HardType(\1)",
                updated,
            )
            for owner in ("Mem", "StreamFifo"):
                updated = re.sub(
                    rf"(?<![\w.]){owner}\((morphhdl\.frontend\.(?:Bits|UInt|SInt)\([^()\n]*\)),",
                    rf"{owner}(morphhdl.frontend.HardType(\1),",
                    updated,
                )
            if updated != text:
                path.write_text(updated, encoding="utf-8")


def migrate_core_tests() -> None:
    for relative in [
        "core/src/test/scala/spinal/core/internals/ParameterizedDataShapeTests.scala",
        "core/src/test/scala/spinal/core/internals/ParameterizedVerilogTests.scala",
    ]:
        path = ROOT / relative
        text = path.read_text(encoding="utf-8")
        for factory in ("Bits", "UInt", "SInt"):
            text = re.sub(rf"(?<![\w.]){factory}\(", f"ParameterizedWidth.{factory}(", text)
        if relative.endswith("ParameterizedDataShapeTests.scala"):
            for native, wrapper in [
                ("cloneOf", "ParameterizedWidth.cloneOf"),
                ("HardType", "ParameterizedWidth.HardType"),
                ("Reg", "ParameterizedWidth.Reg"),
                ("Vec", "ParameterizedWidth.Vec"),
            ]:
                text = re.sub(rf"(?<![\w.]){native}\(", wrapper + "(", text)
            old = '''      val directTag = top.directBits.getTag(classOf[ParameterizedWidthTag]).get
      val cloneTag = top.clonedBits.getTag(classOf[ParameterizedWidthTag]).get
      val hardTagA = top.hardUIntA.getTag(classOf[ParameterizedWidthTag]).get
      val hardTagB = top.hardUIntB.getTag(classOf[ParameterizedWidthTag]).get
      assert(directTag ne cloneTag)
      assert(hardTagA ne hardTagB)
'''
            new = '''      assert(ParameterizedWidth.isRetained(top.directBits))
      assert(ParameterizedWidth.isRetained(top.clonedBits))
      assert(ParameterizedWidth.isRetained(top.hardUIntA))
      assert(ParameterizedWidth.isRetained(top.hardUIntB))
'''
            if old not in text:
                raise RuntimeError("legacy tag-identity assertion block not found")
            text = text.replace(old, new, 1)
            text = text.replace(
                'test("cloneOf and repeated HardType construction preserve independent metadata")',
                'test("external cloneOf and HardType preserve identity-associated metadata")',
            )
            text = text.replace(
                'test("ordinary concrete factories and clones remain untagged")',
                'test("ordinary concrete factories and clones remain unregistered")',
            )
        path.write_text(text, encoding="utf-8")


def update_guidance() -> None:
    path = ROOT / "frontend/src/main/scala/morphhdl/frontend/FrontendException.scala"
    text = path.read_text(encoding="utf-8")
    text = text.replace(
        "Pass the exact HdlInt returned by HdlInt.param directly to UInt(width bits).",
        "Pass the exact HdlInt returned by HdlInt.param to the MorphHDL symbolic UInt factory.",
    )
    path.write_text(text, encoding="utf-8")


def commit(message: str) -> str:
    run("git", "add", "-A")
    run("git", "commit", "--no-gpg-sign", "-m", message)
    return run("git", "rev-parse", "HEAD", capture=True)


def update_manifest(approved_commit: str) -> None:
    path = ROOT / "morphhdl/contracts/native-source-preservation.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    manifest["approved_state"] = {
        "commit": approved_commit,
        "tree": run("git", "rev-parse", f"{approved_commit}^{{tree}}", capture=True),
        "description": "Reviewed native-source state after Increment 40 external symbolic-width migration",
    }
    restored = set(RESTORED)
    manifest["entries"] = [
        entry for entry in manifest["entries"] if entry["path"] not in restored
    ]
    path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    run("git", "checkout", BASELINE, "--", *RESTORED)
    write("core/src/main/scala/spinal/core/ParameterizedWidth.scala", PARAMETERIZED_WIDTH)
    insert_wrappers()
    replace_symbolic_factories()
    migrate_core_tests()
    update_guidance()
    write(".github/workflows/morphhdl-external-symbolic-width.yml", FOCUSED_WORKFLOW)
    write("docs/morphhdl/increment-40-external-symbolic-width.md", DOC)

    # Remove temporary controller/inventory artifacts from the implementation.
    for relative in [
        ".github/workflows/agent-increment-40-inventory.yml",
        ".github/workflows/agent-increment-40-controller.yml",
        ".github/increment-40/apply_increment_40.py",
    ]:
        path = ROOT / relative
        if path.exists():
            path.unlink()

    implementation_commit = commit("Implement Increment 40 external symbolic widths")
    update_manifest(implementation_commit)
    commit("Repin native-source manifest after Increment 40 restoration")
    run("git", "push", "origin", "HEAD:agent/increment-40-external-symbolic-width")


if __name__ == "__main__":
    main()
