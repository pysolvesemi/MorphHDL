#!/usr/bin/env python3
from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

REPOSITORY = "pysolvesemi/MorphHDL"
BRANCH = "agent/increment-43-native-memory-reuse"
BASELINE = "8c4241396cd718a36227dcd89a2e6a29d9077f11"
ROOT = Path.cwd()


def run(*args: str, capture: bool = False) -> str:
    result = subprocess.run(
        list(args),
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stdout or ""
        raise SystemExit(f"command failed ({result.returncode}): {' '.join(args)}\n{detail}")
    return (result.stdout or "").strip()


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def require_once(text: str, needle: str, path: str) -> None:
    count = text.count(needle)
    if count != 1:
        raise SystemExit(f"{path}: expected one occurrence of {needle!r}, found {count}")


def restore_baseline(path: str) -> None:
    content = subprocess.run(
        ["git", "show", f"{BASELINE}:{path}"],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if content.returncode != 0:
        raise SystemExit(content.stderr.decode("utf-8", errors="replace"))
    (ROOT / path).write_bytes(content.stdout)


run("git", "config", "user.name", "MorphHDL Increment 43")
run("git", "config", "user.email", "actions@users.noreply.github.com")

# Restore the two upstream-owned native files touched by Increment 35.
restore_baseline("core/src/main/scala/spinal/core/Mem.scala")
restore_baseline("core/src/main/scala/spinal/core/internals/PhaseVerilog.scala")

# Move the existing reviewed memory lowerer into the MorphHDL-owned module.
core_lowerer = ROOT / "core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala"
morph_lowerer = ROOT / "morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala"
if not core_lowerer.is_file() or morph_lowerer.exists():
    raise SystemExit("unexpected Increment 43 lowerer source state")
morph_lowerer.parent.mkdir(parents=True, exist_ok=True)
shutil.move(str(core_lowerer), str(morph_lowerer))
lowerer_text = morph_lowerer.read_text(encoding="utf-8")
require_once(
    lowerer_text,
    "Increment 35 lowering for one ordinary bounded Spinal Mem.",
    str(morph_lowerer),
)
lowerer_text = lowerer_text.replace(
    "Increment 35 lowering for one ordinary bounded Spinal Mem.",
    "MorphHDL-owned external lowering for one ordinary bounded Spinal Mem.",
    1,
)
if lowerer_text.count("ParameterizedMemory.") < 2:
    raise SystemExit("memory lowerer no longer exposes the expected metadata calls")
lowerer_text = lowerer_text.replace(
    "ParameterizedMemory.",
    "ExternalParameterizedMemoryRegistry.",
)
morph_lowerer.write_text(lowerer_text, encoding="utf-8")

# External object-identity registry. It combines direct MorphHDL Mem geometry
# with the temporary library-depth tags retained until Increment 45.
write(
    "frontend/src/main/scala/spinal/core/ExternalParameterizedMemoryRegistry.scala",
    r'''package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/** Weak key with native Mem object-identity semantics. */
private[core] final class ExternalMemoryIdentityRef(
    value: Mem[_],
    queue: ReferenceQueue[Mem[_]]
) extends WeakReference[Mem[_]](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalMemoryIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/**
  * MorphHDL-owned native-memory geometry registry.
  *
  * Ordinary SpinalHDL Mem construction remains untouched. A MorphHDL depth
  * adapter records the bounded depth beside the concrete native Mem, while the
  * final external publication phase discovers symbolic element widths from the
  * existing HardType registry. Read/write ports, clocks, enables and collision
  * policies are always inspected from the native AST itself.
  */
object ExternalParameterizedMemoryRegistry {
  private val queue = new ReferenceQueue[Mem[_]]()
  private val retained =
    mutable.HashMap.empty[ExternalMemoryIdentityRef, ParameterizedMemoryMetadata]

  private def reap(): Unit = {
    var reference = queue.poll().asInstanceOf[ExternalMemoryIdentityRef]
    while (reference != null) {
      retained.remove(reference)
      reference = queue.poll().asInstanceOf[ExternalMemoryIdentityRef]
    }
  }

  private def externalMetadataOf(
      memory: Mem[_]
  ): Option[ParameterizedMemoryMetadata] = synchronized {
    reap()
    retained.get(new ExternalMemoryIdentityRef(memory, null))
  }

  private def retain(
      memory: Mem[_],
      metadata: ParameterizedMemoryMetadata
  ): Unit = synchronized {
    reap()
    if (retained.contains(new ExternalMemoryIdentityRef(memory, null))) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-METADATA-DUPLICATE",
        "native memory already carries external symbolic geometry metadata",
        metadata.sourceLocation
      )
    }
    retained.update(new ExternalMemoryIdentityRef(memory, queue), metadata)
  }

  /** Native Mem factory followed only by external metadata association. */
  def create[T <: Data](
      wordType: HardType[T],
      depth: ParameterizedMemoryDepth
  ): Mem[T] = {
    if (wordType == null)
      throw new IllegalArgumentException("native memory word type must not be null")
    if (depth == null)
      throw new IllegalArgumentException("symbolic native memory depth must not be null")
    attach(spinal.core.Mem(wordType, depth.value), depth)
  }

  /** Associate a bounded depth with an already-created ordinary native Mem. */
  def attach[T <: Data](
      memory: Mem[T],
      depth: ParameterizedMemoryDepth
  ): Mem[T] = {
    if (memory == null)
      throw new IllegalArgumentException("native memory must not be null")
    validateDepth(memory, depth)
    if (metadataOf(memory).nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-METADATA-DUPLICATE",
        "native memory already carries symbolic geometry metadata",
        depth.sourceLocation
      )
    }
    val elementWidth = elementWidthOf(
      memory,
      depth.sourceLocation.orElse(depth.expression.sourceLocation)
    )
    retain(
      memory,
      ParameterizedMemoryMetadata(
        depth = depth.expression,
        elementWidth = elementWidth,
        sourceLocation = depth.sourceLocation
          .orElse(depth.expression.sourceLocation)
          .orElse(elementWidth.sourceLocation)
      )
    )
    memory
  }

  /**
    * Discover symbolic element geometry after normal elaboration and inherited
    * validation. This records no hardware statement and changes no native Mem,
    * port or algorithm.
    */
  private[core] def discover(component: Component): Unit = {
    allMemoriesOf(component).foreach { memory =>
      if (metadataOf(memory).isEmpty) {
        val leaves = memory.wordType().asInstanceOf[Data].flatten.toVector
        val symbolic = leaves.exists { leaf =>
          ParameterizedWidth.expressionOf(leaf).exists(_.parameters.nonEmpty)
        }
        if (symbolic) {
          val elementWidth = elementWidthOf(memory, sourceLocation = None)
          retain(
            memory,
            ParameterizedMemoryMetadata(
              depth = literal(memory.wordCount),
              elementWidth = elementWidth,
              sourceLocation = elementWidth.sourceLocation
            )
          )
        }
      }
    }
  }

  private[core] def metadataOf(
      memory: Mem[_]
  ): Option[ParameterizedMemoryMetadata] = {
    val external = externalMetadataOf(memory)
    val library = ParameterizedMemory.metadataOf(memory)
    (external, library) match {
      case (Some(left), Some(right))
          if left.depth != right.depth || left.elementWidth != right.elementWidth =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-MEMORY-METADATA-CONFLICT",
          "native memory carries conflicting external and library symbolic geometry",
          left.sourceLocation.orElse(right.sourceLocation)
        )
      case (Some(left), Some(right)) =>
        Some(left.copy(sourceLocation = left.sourceLocation.orElse(right.sourceLocation)))
      case (Some(value), None) => Some(value)
      case (None, Some(value)) => Some(value)
      case _                   => None
    }
  }

  private[core] def memoriesOf(component: Component): Vector[Mem[_]] = {
    discover(component)
    allMemoriesOf(component).filter(memory => metadataOf(memory).nonEmpty)
  }

  def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
    val memories = memoriesOf(component)
    val referenced = memories.flatMap { memory =>
      val metadata = metadataOf(memory).get
      metadata.depth.parameters ++ metadata.elementWidth.parameters
    }
    val grouped = referenced.groupBy(_.name)
    grouped.collectFirst {
      case (name, schemas) if schemas.distinct.size != 1 => name
    }.foreach { name =>
      val source = memories.iterator
        .flatMap(metadataOf)
        .find(metadata =>
          (metadata.depth.parameters ++ metadata.elementWidth.parameters)
            .exists(_.name == name)
        )
        .flatMap(_.sourceLocation)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
        s"parameter '$name' has conflicting native-memory declarations on component '${component.definitionName}'",
        source
      )
    }
    grouped.toVector.map(_._2.head).sortBy(_.name)
  }

  private def allMemoriesOf(component: Component): Vector[Mem[_]] = {
    val values = ArrayBuffer.empty[Mem[_]]
    component.dslBody.walkDeclarations {
      case memory: Mem[_] => values += memory
      case _              =>
    }
    values.toVector
  }

  private def validateDepth(
      memory: Mem[_],
      depth: ParameterizedMemoryDepth
  ): Unit = {
    if (depth.value < 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-NOT-POSITIVE",
        s"native memory depth witness ${depth.value} must be positive",
        depth.sourceLocation
      )
    }
    if (depth.expression.generateIndex.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-GENERATE-DEPENDENT",
        "native memory depth cannot depend on a generate index",
        depth.sourceLocation
      )
    }
    if (
      depth.expression.default != BigInt(depth.value) ||
      depth.expression.minimum < 1 ||
      depth.expression.maximum < depth.expression.minimum ||
      depth.expression.maximum > BigInt(Int.MaxValue) ||
      memory.wordCount != depth.value
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-DOMAIN-INVALID",
        s"native memory depth '${depth.expression.verilog}' must have witness ${memory.wordCount} and a finite positive Int-sized domain",
        depth.sourceLocation.orElse(depth.expression.sourceLocation)
      )
    }
  }

  private def elementWidthOf(
      memory: Mem[_],
      sourceLocation: Option[String]
  ): ElaborationIntegerExpression = {
    val leaves = memory.wordType().asInstanceOf[Data].flatten.toVector
    if (leaves.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-TYPE-UNSUPPORTED",
        "native symbolic memory element type has no flattened data leaves",
        sourceLocation
      )
    }
    val elementWidth = leaves.map { leaf =>
      ParameterizedWidth.expressionOf(leaf).getOrElse(literal(leaf.getBitsWidth))
    }.reduce(add)
    if (
      elementWidth.default != BigInt(memory.getWidth) ||
      elementWidth.minimum < 1 || elementWidth.maximum < elementWidth.minimum
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-WIDTH-INVALID",
        s"native memory concrete element width ${memory.getWidth} does not match retained expression '${elementWidth.verilog}' in [${elementWidth.minimum}, ${elementWidth.maximum}]",
        sourceLocation.orElse(elementWidth.sourceLocation)
      )
    }
    elementWidth
  }

  private def literal(value: Int): ElaborationIntegerExpression =
    ElaborationIntegerExpression(
      verilog = value.toString,
      default = BigInt(value),
      minimum = BigInt(value),
      maximum = BigInt(value),
      parameters = Vector.empty
    )

  private def add(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): ElaborationIntegerExpression =
    ElaborationIntegerExpression(
      verilog = s"(${left.verilog} + ${right.verilog})",
      default = left.default + right.default,
      minimum = left.minimum + right.minimum,
      maximum = left.maximum + right.maximum,
      parameters = (left.parameters ++ right.parameters).distinct.sortBy(_.name),
      sourceLocation = left.sourceLocation.orElse(right.sourceLocation)
    )

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
''',
)

# MorphHDL-owned user-facing factory; readSync/write remain native methods.
write(
    "frontend/src/main/scala/morphhdl/frontend/Memory.scala",
    r'''package morphhdl.frontend

import spinal.core.{Data, ExternalParameterizedMemoryRegistry, HardType, Mem => SpinalMem}

/**
  * MorphHDL shadow factory for an ordinary SpinalHDL Mem with retained depth.
  * The returned object is the native Mem and all port algorithms remain native.
  */
object Mem {
  def apply[T <: Data](
      wordType: HardType[T],
      wordCount: HdlInt
  )(implicit file: sourcecode.File, line: sourcecode.Line): SpinalMem[T] = {
    if (wordCount == null)
      throw new IllegalArgumentException("symbolic native memory depth must not be null")
    ExternalParameterizedMemoryRegistry.create(
      wordType,
      wordCount.toParameterizedMemoryDepth(file, line)
    )
  }

  def apply[T <: Data](wordType: HardType[T], wordCount: Int): SpinalMem[T] =
    spinal.core.Mem(wordType, wordCount)

  def apply[T <: Data](wordType: HardType[T], wordCount: BigInt): SpinalMem[T] =
    spinal.core.Mem(wordType, wordCount)

  def fill[T <: Data](wordCount: HdlInt)(
      wordType: HardType[T]
  )(implicit file: sourcecode.File, line: sourcecode.Line): SpinalMem[T] =
    apply(wordType, wordCount)

  def fill[T <: Data](wordCount: Int)(wordType: HardType[T]): SpinalMem[T] =
    spinal.core.Mem.fill(wordCount)(wordType)
}
''',
)

# Install memory discovery and memory-first lowering in the final external pass.
external_path = "morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala"
external = read(external_path)
external = external.replace(
    "MorphHDL-owned final publication transform for Increments 41 and 42.",
    "MorphHDL-owned final publication transform for Increments 41 through 43.",
)
external = external.replace(
    "proves symbolic expression, connection, hierarchy, structural and process\n  * contracts, then rewrites only the published Verilog artifact.",
    "proves symbolic memory, expression, connection, hierarchy, structural and\n  * process contracts, then rewrites only the published Verilog artifact.",
)
component_marker = "    val components = componentGraph(top)\n"
require_once(external, component_marker, external_path)
external = external.replace(
    component_marker,
    component_marker +
    "    components.foreach(ExternalParameterizedMemoryRegistry.discover)\n",
    1,
)
process_marker = '''        val rewritten = withPulledExternalClockInputs(component) {
          val withProcesses = ParameterizedVerilogProcesses.rewrite(
            component,
            text,
            pc
          )'''
require_once(external, process_marker, external_path)
external = external.replace(
    process_marker,
    '''        val rewritten = withPulledExternalClockInputs(component) {
          val withMemories = ParameterizedVerilogMemories.rewrite(
            component,
            text,
            pc
          )
          val withProcesses = ParameterizedVerilogProcesses.rewrite(
            component,
            withMemories,
            pc
          )''',
    1,
)
external = external.replace(
    "native memory lowering first, then procedural loops, structural generate",
    "external memory lowering first, then procedural loops, structural generate",
)
external = external.replace(
    "ParameterizedMemory.parametersOf(",
    "ExternalParameterizedMemoryRegistry.parametersOf(",
)
write(external_path, external)

# Every MorphHDL-owned lowerer must use the same combined external/library view.
internals = ROOT / "morphhdl/src/main/scala/spinal/core/internals"
for path in internals.glob("*.scala"):
    text = path.read_text(encoding="utf-8")
    updated = text.replace(
        "ParameterizedMemory.parametersOf(",
        "ExternalParameterizedMemoryRegistry.parametersOf(",
    )
    if updated != text:
        path.write_text(updated, encoding="utf-8")

remaining = []
for path in internals.glob("*.scala"):
    if "ParameterizedMemory.parametersOf(" in path.read_text(encoding="utf-8"):
        remaining.append(str(path))
if remaining:
    raise SystemExit("native memory parameter lookup remains in MorphHDL lowerers: " + ", ".join(remaining))

# Move direct symbolic-depth fixtures to the MorphHDL shadow factory while
# retaining all ordinary readSync/write code and negative-policy tests.
test_path = "morphhdl/src/test/scala/morphhdl/NativeSymbolicMemoryTests.scala"
tests = read(test_path)
replacement_count = tests.count("val memory = Mem(")
if replacement_count < 6:
    raise SystemExit(f"expected symbolic Mem fixtures, found {replacement_count}")
tests = tests.replace("val memory = Mem(", "val memory = morphhdl.frontend.Mem(")
static_marker = '  test("ordinary SpinalVerilog remains concrete and ignores retained memory metadata") {'
require_once(tests, static_marker, test_path)
static_test = r'''  test("ordinary native Mem with static depth discovers symbolic element width externally") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
      val verilog = emitMorph(
        directory,
        "native_static_depth_memory.v",
        new Component {
          setDefinitionName("NativeStaticDepthMemory")
          val read_enable = in(Bool())
          val write_enable = in(Bool())
          val address = in(morphhdl.frontend.UInt(3 bits))
          val write_data = in(morphhdl.frontend.Bits(width bits))
          val read_data = out(morphhdl.frontend.Bits(width bits))
          val memory = spinal.core.Mem(
            morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)),
            5
          ).setName("memory")
          val read_word = memory.readSync(
            address,
            enable = read_enable,
            readUnderWrite = readFirst
          )
          memory.write(address, write_data, enable = write_enable)
          read_data := read_word
        }
      )

      assert(verilog.contains("parameter integer WIDTH = 8"))
      assert(!verilog.contains("parameter integer DEPTH"))
      assert(verilog.contains("reg [WIDTH-1:0] memory [0:4];"))
      assert(verilog.contains("if (address < 5) begin"))
      assert(verilog.contains("memory[address] <= write_data;"))
    }
  }

'''
tests = tests.replace(static_marker, static_test + static_marker, 1)
write(test_path, tests)

write(
    "docs/morphhdl/increment-43-external-native-memory.md",
    r'''# Increment 43 — Native memory reuse with zero `Mem.scala` changes

Increment 43 restores the selected upstream `Mem.scala` and native
`PhaseVerilog.scala` byte-for-byte. MorphHDL no longer changes ordinary Mem
constructors or routes memory rewriting through the native Verilog phase.

## External ownership

- `morphhdl.frontend.Mem` delegates construction to the ordinary native Mem
  factory, then records only bounded depth metadata in a weak object-identity
  registry.
- `ExternalParameterizedMemoryRegistry` discovers symbolic element geometry
  from the existing external HardType/width registry after normal elaboration
  and inherited validation. It also reads the temporary StreamFifo library-depth
  tags that remain until Increment 45.
- The existing Increment 35 memory analyzer/lowerer now lives in the MorphHDL
  orchestration module. It still derives native read/write ports, address
  expressions, enables, clocking, masks and collision policy directly from AST
  identities.
- The final publication order is memory, process, structure, then generic
  expression/connection/hierarchy lowering.

The returned memory object and its `readSync`, `write`, `readSyncPort` and
`writePort` algorithms are unmodified SpinalHDL implementations. Ordinary
`SpinalVerilog` ignores the external registry and remains concrete.

## Preserved contracts

The dual-Scala proof retains the complete Increment 35 policy:

- one reviewed synchronous read and whole-word write shape;
- explicit active-high enables;
- positive-edge shared clocking;
- explicit read-first collision behavior;
- address capacity over the complete declared depth domain;
- guarded out-of-range reads and writes with zero read fallback;
- parameterized element width and depth without specializing the module; and
- concrete-default parity, deterministic Verilog-2001, simulation, lint and
  synthesis gates.
''',
)

write(
    "morphhdl/scripts/check-external-memory-boundary.sh",
    f'''#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"
baseline={BASELINE}

mem=core/src/main/scala/spinal/core/Mem.scala
phase=core/src/main/scala/spinal/core/internals/PhaseVerilog.scala
core_lowerer=core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala
morph_lowerer=morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala
registry=frontend/src/main/scala/spinal/core/ExternalParameterizedMemoryRegistry.scala
adapter=frontend/src/main/scala/morphhdl/frontend/Memory.scala
external=morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala
manifest=morphhdl/contracts/native-source-preservation.json

test "$(git hash-object "$mem")" = "$(git rev-parse "${{baseline}}:${{mem}}")"
test "$(git hash-object "$phase")" = "$(git rev-parse "${{baseline}}:${{phase}}")"
test ! -e "$core_lowerer"
test -f "$morph_lowerer"
test -f "$registry"
test -f "$adapter"
! grep -Fq 'ParameterizedMemoryDepth' "$mem"
! grep -Fq 'ParameterizedMemory.attach' "$mem"
! grep -Fq 'ParameterizedVerilogMemories' "$phase"
grep -Fq 'ExternalParameterizedMemoryRegistry.discover' "$external"
grep -Fq 'ParameterizedVerilogMemories.rewrite' "$external"
grep -Fq 'ExternalParameterizedMemoryRegistry.create' "$adapter"

python3 - <<'PY'
import json
from pathlib import Path

external = Path("morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala").read_text()
order = [
    external.index("ExternalParameterizedMemoryRegistry.discover"),
    external.index("ParameterizedVerilogMemories.rewrite"),
    external.index("ParameterizedVerilogProcesses.rewrite"),
    external.index("ParameterizedVerilogStructural.rewrite"),
    external.index("ExternalParameterizedVerilogNativeFallback.rewrite"),
]
if order != sorted(order):
    raise SystemExit("external memory/process/structure/expression publication order is invalid")

manifest = json.loads(Path("morphhdl/contracts/native-source-preservation.json").read_text())
paths = {{entry["path"] for entry in manifest["entries"]}}
for path in (
    "core/src/main/scala/spinal/core/Mem.scala",
    "core/src/main/scala/spinal/core/internals/PhaseVerilog.scala",
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala",
):
    if path in paths:
        raise SystemExit(f"restored/relocated native path remains in manifest: {{path}}")
PY

python3 morphhdl/scripts/check-native-source-preservation.py

echo "External native-memory ownership boundary is valid"
''',
)

write(
    ".github/workflows/morphhdl-external-memory.yml",
    r'''name: MorphHDL external native memory

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
  group: morphhdl-external-memory-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  source-boundary:
    name: Native memory source boundary
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - name: Check out complete history
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Prove zero Mem.scala and native phase coupling
        shell: bash
        run: bash morphhdl/scripts/check-external-memory-boundary.sh

  contracts:
    name: External native-memory proof Scala ${{ matrix.scala_version }}
    needs: source-boundary
    runs-on: ubuntu-latest
    timeout-minutes: 60
    container:
      image: ghcr.io/spinalhdl/docker:latest
    env:
      XDG_CACHE_HOME: /tmp/morphhdl-cache
    strategy:
      fail-fast: false
      matrix:
        scala_version:
          - "2.12.18"
          - "2.13.12"
    steps:
      - name: Check out sources
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Install pinned Mill bootstrap
        shell: bash
        run: |
          set -euo pipefail
          curl --fail --location --retry 5 --retry-all-errors \
            https://repo.maven.apache.org/maven2/com/lihaoyi/mill-dist/1.1.0/mill-dist-1.1.0.exe \
            --output /tmp/morphhdl-mill
          chmod +x /tmp/morphhdl-mill

      - name: Validate native memory reuse and inherited contracts
        shell: bash
        run: |
          set -euo pipefail
          /tmp/morphhdl-mill frontend[${{ matrix.scala_version }}].testOnly \
            morphhdl.frontend.HdlIntTests
          /tmp/morphhdl-mill morph[${{ matrix.scala_version }}].testOnly \
            morphhdl.NativeSymbolicMemoryTests \
            morphhdl.NativeLibraryReuseTests \
            morphhdl.ParameterizedStreamFifoDepthTests \
            morphhdl.GenericExpressionAndStreamTests \
            morphhdl.HierarchyParameterBindingTests \
            morphhdl.MorphSingleSourceVerilogTests
          /tmp/morphhdl-mill core[${{ matrix.scala_version }}].testOnly \
            spinal.core.internals.SpinalVerilogPhasePlanTests
''',
)

# Remove the one-time bootstrap from the final publication delta.
for path in (
    ".github/workflows/increment-43-bootstrap.yml",
    "morphhdl/scripts/increment-43-bootstrap.py",
):
    target = ROOT / path
    if target.exists():
        target.unlink()

run("git", "add", "-A")
run("git", "diff", "--cached", "--check")
run("git", "commit", "-m", "Implement Increment 43 external native memory reuse")
source_commit = run("git", "rev-parse", "HEAD", capture=True)
source_tree = run("git", "rev-parse", "HEAD^{{tree}}", capture=True)

# Pin the strict native-source manifest to the exact reviewed implementation
# commit; later checkbox/docs commits do not touch protected roots.
manifest_path = ROOT / "morphhdl/contracts/native-source-preservation.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
manifest["approved_state"] = {
    "commit": source_commit,
    "tree": source_tree,
    "description": "Reviewed native-source state after Increment 43 external native-memory reuse",
}
removed = {
    "core/src/main/scala/spinal/core/Mem.scala",
    "core/src/main/scala/spinal/core/internals/PhaseVerilog.scala",
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala",
}
manifest["entries"] = [
    entry for entry in manifest["entries"] if entry["path"] not in removed
]
for entry in manifest["entries"]:
    if entry["path"] == "core/src/main/scala/spinal/core/ParameterizedMemory.scala":
        entry["reason"] = (
            "Retains temporary StreamFifo library-depth override metadata until "
            "Increment 45; direct native Mem geometry is now associated externally."
        )
manifest["entries"] = sorted(manifest["entries"], key=lambda entry: entry["path"])
manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

run("git", "add", "morphhdl/contracts/native-source-preservation.json")
run("git", "diff", "--cached", "--check")
run("git", "commit", "-m", "Approve Increment 43 native source state")

run("bash", "morphhdl/scripts/check-external-memory-boundary.sh")
run("git", "status", "--porcelain")
run("git", "push", "origin", f"HEAD:{BRANCH}")

print(f"Increment 43 implementation head: {run('git', 'rev-parse', 'HEAD', capture=True)}")
