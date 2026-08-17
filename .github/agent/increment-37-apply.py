#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"expected anchor not found in {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1))


# ---------------------------------------------------------------------------
# Core memory metadata: allow one ordinary Mem, created by StreamFifo at its
# concrete witness, to replace only its retained depth expression afterwards.
# ---------------------------------------------------------------------------
memory_path = ROOT / "core/src/main/scala/spinal/core/ParameterizedMemory.scala"
memory_text = memory_path.read_text()

if "ParameterizedMemoryDepthOverrideTag" not in memory_text:
    anchor = """private[core] final case class ParameterizedMemoryTag(
    metadata: ParameterizedMemoryMetadata
) extends SpinalTag {
  override def allowMultipleInstance: Boolean = false
  override def canSymplifyHost: Boolean = true
}
"""
    insertion = anchor + """
/** A shared library primitive may replace a concrete witness depth after its
  * ordinary source has elaborated, without replacing the primitive itself.
  */
private[core] final case class ParameterizedMemoryDepthOverrideTag(
    depth: ElaborationIntegerExpression,
    sourceLocation: Option[String]
) extends SpinalTag {
  override def allowMultipleInstance: Boolean = false
  override def canSymplifyHost: Boolean = true
}
"""
    if anchor not in memory_text:
        raise SystemExit("ParameterizedMemoryTag anchor not found")
    memory_text = memory_text.replace(anchor, insertion, 1)

if "def retainDepth(" not in memory_text:
    attach_anchor = """  private[core] def attach[T <: Data](
      memory: Mem[T],
      depth: ParameterizedMemoryDepth
  ): Mem[T] = {
"""
    method = """  /**
    * Replace the concrete witness depth retained by an ordinary library Mem.
    *
    * This is intentionally a shared memory representation hook: StreamFifo
    * continues to elaborate and own its native memory and only supplies the
    * bounded public depth expression after the component is complete.
    */
  private[spinal] def retainDepth(
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
      depth.expression.maximum > BigInt(Int.MaxValue)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-DOMAIN-INVALID",
        s"native memory depth '${depth.expression.verilog}' must have witness ${depth.value} and a finite positive Int-sized domain",
        depth.sourceLocation.orElse(depth.expression.sourceLocation)
      )
    }
    if (memory.getTag(classOf[ParameterizedMemoryDepthOverrideTag]).nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-OVERRIDE-DUPLICATE",
        "native memory already carries a retained library depth override",
        depth.sourceLocation
      )
    }

    val leaves = memory.wordType().flatten.toVector
    if (leaves.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-TYPE-UNSUPPORTED",
        "native symbolic memory element type has no flattened data leaves",
        depth.sourceLocation
      )
    }
    val elementWidth = leaves.map { leaf =>
      ParameterizedWidth.expressionOf(leaf).getOrElse(literal(leaf.getBitsWidth))
    }.reduce(add)
    if (
      elementWidth.default != BigInt(memory.getWidth) ||
      elementWidth.minimum < 1 ||
      elementWidth.maximum < elementWidth.minimum
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-WIDTH-INVALID",
        s"native memory concrete element width ${memory.getWidth} does not match retained expression '${elementWidth.verilog}' in [${elementWidth.minimum}, ${elementWidth.maximum}]",
        depth.sourceLocation.orElse(elementWidth.sourceLocation)
      )
    }

    if (memory.getTag(classOf[ParameterizedMemoryTag]).isEmpty) {
      memory.addTag(
        ParameterizedMemoryTag(
          ParameterizedMemoryMetadata(
            depth = literal(memory.wordCount),
            elementWidth = elementWidth,
            sourceLocation = depth.sourceLocation.orElse(elementWidth.sourceLocation)
          )
        )
      )
    }
    memory.addTag(
      ParameterizedMemoryDepthOverrideTag(
        depth.expression,
        depth.sourceLocation.orElse(depth.expression.sourceLocation)
      )
    )
  }

"""
    if attach_anchor not in memory_text:
        raise SystemExit("ParameterizedMemory.attach anchor not found")
    memory_text = memory_text.replace(attach_anchor, method + attach_anchor, 1)

metadata_pattern = re.compile(
    r"  private\[core\] def metadataOf\(\n"
    r"      memory: Mem\[_\]\n"
    r"  \): Option\[ParameterizedMemoryMetadata\] =\n"
    r"    memory\.getTag\(classOf\[ParameterizedMemoryTag\]\)\.map\(_\.metadata\)"
)
if "depthOverride" not in memory_text:
    replacement = """  private[core] def metadataOf(
      memory: Mem[_]
  ): Option[ParameterizedMemoryMetadata] = {
    val base = memory.getTag(classOf[ParameterizedMemoryTag]).map(_.metadata)
    val depthOverride =
      memory.getTag(classOf[ParameterizedMemoryDepthOverrideTag])
    depthOverride match {
      case Some(tag) =>
        base.map(
          _.copy(
            depth = tag.depth,
            sourceLocation = tag.sourceLocation.orElse(
              base.flatMap(_.sourceLocation)
            )
          )
        )
      case None => base
    }
  }"""
    memory_text, count = metadata_pattern.subn(replacement, memory_text, count=1)
    if count != 1:
        raise SystemExit("ParameterizedMemory.metadataOf anchor not found")

memory_path.write_text(memory_text)


# ---------------------------------------------------------------------------
# Shared library front door: overload the existing companion factory, then tag
# the one native Mem owned by the ordinary StreamFifo component.
# ---------------------------------------------------------------------------
stream_depth_path = ROOT / "lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala"
if not stream_depth_path.exists():
    stream_depth_path.write_text("""package spinal.lib

import scala.collection.mutable.ArrayBuffer

import spinal.core._
import spinal.core.internals._

/** Retains a bounded public depth on one ordinary Spinal StreamFifo. */
private[lib] object ParameterizedStreamFifoDepth {
  def attach[T <: Data](
      fifo: StreamFifo[T],
      depth: ParameterizedMemoryDepth
  ): StreamFifo[T] = {
    val memories = ArrayBuffer.empty[Mem[_]]
    fifo.dslBody.walkDeclarations {
      case memory: Mem[_] => memories += memory
      case _              =>
    }
    val distinct = memories.distinct.toVector
    if (distinct.size != 1) {
      throw new IllegalArgumentException(
        s"a parameterized-depth StreamFifo must retain exactly one native Mem, found ${distinct.size}"
      )
    }
    ParameterizedMemory.retainDepth(distinct.head, depth)
    fifo
  }
}
""")

stream_path = ROOT / "lib/src/main/scala/spinal/lib/Stream.scala"
stream_text = stream_path.read_text()
if "depth: ParameterizedMemoryDepth" not in stream_text:
    object_match = re.search(r"object\s+StreamFifo\s*\{", stream_text)
    if not object_match:
        raise SystemExit("object StreamFifo anchor not found")
    insertion = """

  /**
    * Construct the ordinary StreamFifo at its concrete witness while retaining
    * one bounded public depth for parameter-aware Verilog generation.
    *
    * The native fixed-depth API and all existing option-bearing constructors
    * remain unchanged. Increment 37 initially supports the ordinary default
    * StreamFifo options; unsupported alternative storage paths fail explicitly
    * when they do not elaborate exactly one native Mem.
    */
  def apply[T <: Data](
      dataType: HardType[T],
      depth: ParameterizedMemoryDepth
  ): StreamFifo[T] =
    ParameterizedStreamFifoDepth.attach(
      new StreamFifo(dataType, depth.value),
      depth
    )
"""
    pos = object_match.end()
    stream_text = stream_text[:pos] + insertion + stream_text[pos:]
stream_path.write_text(stream_text)


# ---------------------------------------------------------------------------
# Reuse the already-emitted native FIFO algorithm. The memory pass owns the
# retained DEPTH expression, so extend that shared pass to rewrite only the
# depth-derived geometry and terminal constants in a module that has the
# canonical StreamFifo handshake/occupancy interface.
# ---------------------------------------------------------------------------
backend_path = ROOT / "core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala"
backend_text = backend_path.read_text()
if "rewriteParameterizedStreamFifoDepth" not in backend_text:
    call_anchor = """    lines = rewriteMemoryDeclaration(lines, plan, helperName)
    lines = rewriteReadTargetDeclaration(lines, plan, helperName)
"""
    call_replacement = call_anchor + """    lines = rewriteParameterizedStreamFifoDepth(lines, plan, helperName)
"""
    if call_anchor not in backend_text:
        raise SystemExit("memory rewrite call anchor not found")
    backend_text = backend_text.replace(call_anchor, call_replacement, 1)

    method_anchor = """  private def rewriteReadTargetDeclaration(
"""
    method = r'''  /**
    * Retain the native non-power-of-two StreamFifo algorithm while replacing
    * its witness-only geometry with the public bounded depth. A non-power-of-
    * two witness deliberately selects the library's explicit terminal-count
    * wrap path, which is valid for every supported depth including powers of
    * two and the one-entry case.
    */
  private def rewriteParameterizedStreamFifoDepth(
      lines: Vector[String],
      plan: MemoryPlan,
      helperName: String
  ): Vector[String] = {
    val depth = plan.metadata.depth
    if (depth.parameters.isEmpty) return lines

    val lowerModule = lines.mkString("\n").toLowerCase
    val isStreamFifo =
      lowerModule.contains("io_push_valid") &&
        lowerModule.contains("io_push_ready") &&
        lowerModule.contains("io_pop_valid") &&
        lowerModule.contains("io_pop_ready") &&
        lowerModule.contains("io_occupancy") &&
        lowerModule.contains("io_availability")
    if (!isStreamFifo) return lines

    val depthExpression = render(depth, helperName)
    val pointerWidth = s"$helperName($depthExpression, 1)"
    val occupancyWidth = s"$helperName(($depthExpression + 1), 1)"
    val depthDefault = depth.default
    val packedRange = "\\[[^\\]]+\\]".r
    val sizedLiteral = "(?i)([0-9]+)'([s]?)([bodh])([0-9a-f_xz]+)".r

    def isDeclaration(line: String): Boolean = {
      val value = line.trim.toLowerCase
      value.startsWith("input ") || value.startsWith("output ") ||
      value.startsWith("inout ") || value.startsWith("wire ") ||
      value.startsWith("reg ")
    }

    def literalValue(value: String, radix: String): Option[BigInt] = {
      if (value.exists(c => c == 'x' || c == 'X' || c == 'z' || c == 'Z')) None
      else {
        val cleaned = value.replace("_", "")
        val base = radix.toLowerCase match {
          case "b" => 2
          case "o" => 8
          case "d" => 10
          case "h" => 16
        }
        Some(BigInt(cleaned, base))
      }
    }

    def replaceSized(
        line: String,
        target: BigInt,
        replacement: String
    ): String =
      sizedLiteral.replaceAllIn(
        line,
        value =>
          literalValue(value.group(4), value.group(3)) match {
            case Some(parsed) if parsed == target =>
              java.util.regex.Matcher.quoteReplacement(replacement)
            case _ => value.matched
          }
      )

    def replaceDecimal(
        line: String,
        target: BigInt,
        replacement: String
    ): String = {
      val pattern =
        ("(?<![A-Za-z0-9_$'])" + java.util.regex.Pattern.quote(target.toString) +
          "(?![A-Za-z0-9_$])").r
      pattern.replaceAllIn(
        line,
        java.util.regex.Matcher.quoteReplacement(replacement)
      )
    }

    lines.map { original =>
      val lower = original.toLowerCase
      val pointerContext =
        lower.contains("pushptr") || lower.contains("popptr") ||
          lower.contains("push_ptr") || lower.contains("pop_ptr") ||
          (lower.contains("address") &&
            (lower.contains("ram") || lower.contains("memory")))
      val occupancyContext =
        lower.contains("occupancy") || lower.contains("availability") ||
          lower.contains("push_ready")
      val memoryArray = lower.contains("[0:")

      var line = original
      if (isDeclaration(line) && !memoryArray && packedRange.findFirstIn(line).nonEmpty) {
        if (pointerContext) {
          line = packedRange.replaceFirstIn(
            line,
            java.util.regex.Matcher.quoteReplacement(s"[$pointerWidth-1:0]")
          )
        } else if (occupancyContext) {
          line = packedRange.replaceFirstIn(
            line,
            java.util.regex.Matcher.quoteReplacement(s"[$occupancyWidth-1:0]")
          )
        }
      }
      if (pointerContext) {
        line = replaceSized(line, depthDefault - 1, s"($depthExpression - 1)")
        line = replaceDecimal(line, depthDefault - 1, s"($depthExpression - 1)")
      }
      if (occupancyContext) {
        line = replaceSized(line, depthDefault, depthExpression)
        line = replaceDecimal(line, depthDefault, depthExpression)
      }
      line
    }
  }

'''
    if method_anchor not in backend_text:
        raise SystemExit("rewriteReadTargetDeclaration anchor not found")
    backend_text = backend_text.replace(method_anchor, method + method_anchor, 1)
backend_path.write_text(backend_text)


# ---------------------------------------------------------------------------
# Cross-Scala regression: derive it from the already-reviewed Increment 36 test
# harness so MorphVerilog invocation, concrete parity, and strict tool handling
# remain identical to the repository's established conventions.
# ---------------------------------------------------------------------------
source_test_path = ROOT / "morphhdl/src/test/scala/morphhdl/NativeLibraryReuseTests.scala"
test_path = ROOT / "morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala"
if not test_path.exists():
    test_text = source_test_path.read_text()
    test_text = test_text.replace(
        "class NativeLibraryReuseTests extends AnyFunSuite",
        "class ParameterizedStreamFifoDepthTests extends AnyFunSuite",
        1,
    )

    fifo_match = re.search(r"(?ms)^(?P<indent>\s*)(?P<prefix>val\s+\w+\s*=\s*)(?P<new>new\s+)?StreamFifo\((?P<args>.*?)\)", test_text)
    if not fifo_match:
        raise SystemExit("reviewed StreamFifo fixture was not found in NativeLibraryReuseTests")
    args = fifo_match.group("args")
    if re.search(r"\bdepth\s*=\s*4\b", args):
        new_args = re.sub(r"\bdepth\s*=\s*4\b", "depth = symbolicDepth", args, count=1)
    else:
        new_args, count = re.subn(r",\s*4\s*(?=,|$)", ", symbolicDepth", args, count=1)
        if count != 1:
            raise SystemExit("static depth 4 argument was not found in reviewed StreamFifo fixture")
    indent = fifo_match.group("indent")
    depth_setup = f'''{indent}val depthSchema = ElaborationIntegerParameter(
{indent}  name = "DEPTH",
{indent}  default = BigInt(5),
{indent}  minimum = BigInt(1),
{indent}  maximum = BigInt(8)
{indent})
{indent}val symbolicDepth = ParameterizedMemoryDepth(
{indent}  value = 5,
{indent}  expression = ElaborationIntegerExpression(
{indent}    verilog = "DEPTH",
{indent}    default = BigInt(5),
{indent}    minimum = BigInt(1),
{indent}    maximum = BigInt(8),
{indent}    parameters = Vector(depthSchema),
{indent}    sourceLocation = Some("ParameterizedStreamFifoDepthTests.scala:DEPTH")
{indent}  ),
{indent}  sourceLocation = Some("ParameterizedStreamFifoDepthTests.scala:DEPTH")
{indent})
'''
    replacement = (
        depth_setup
        + indent
        + fifo_match.group("prefix")
        + "StreamFifo("
        + new_args
        + ")"
    )
    test_text = test_text[: fifo_match.start()] + replacement + test_text[fifo_match.end() :]
    test_text = test_text.replace(
        "ordinary StreamFifo reuses its native static-depth storage with a symbolic payload width",
        "ordinary StreamFifo retains one bounded depth across native storage and handshake geometry",
        1,
    )
    test_text = test_text.replace("[0:3]", "[0:DEPTH-1]")
    test_text = test_text.replace(
        'assert(!verilog.contains("parameter integer DEPTH"))',
        'assert(verilog.contains("parameter integer DEPTH = 5"))',
    )
    test_text = test_text.replace(
        'assert(!verilog.contains("parameter DEPTH"))',
        'assert(verilog.contains("parameter integer DEPTH = 5") || verilog.contains("parameter DEPTH = 5"))',
    )

    marker = 'test("ordinary StreamFifo retains one bounded depth across native storage and handshake geometry")'
    marker_pos = test_text.find(marker)
    if marker_pos < 0:
        raise SystemExit("renamed StreamFifo test block was not found")
    brace_pos = test_text.find("{", marker_pos)
    depth_count = 0
    block_end = None
    in_string = False
    escaped = False
    for index in range(brace_pos, len(test_text)):
        char = test_text[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "{":
            depth_count += 1
        elif char == "}":
            depth_count -= 1
            if depth_count == 0:
                block_end = index
                break
    if block_end is None:
        raise SystemExit("StreamFifo test block did not close")

    proof = '''
      assert(
        verilog.contains("parameter integer DEPTH = 5") ||
          verilog.contains("parameter DEPTH = 5")
      )
      assert(
        verilog.contains("[0:DEPTH-1]") ||
          verilog.contains("[0:(DEPTH - 1)]")
      )
      assert(verilog.contains("clog2(DEPTH, 1)"))
      assert(
        verilog.contains("clog2((DEPTH + 1), 1)") ||
          verilog.contains("clog2(DEPTH + 1, 1)")
      )

      val modules = """(?m)^\\s*module\\s+([A-Za-z_][A-Za-z0-9_$]*)\\b""".r
        .findAllMatchIn(verilog)
        .map(_.group(1))
        .toVector
      assert(modules.nonEmpty)
      val top = modules.last
      Vector(1, 3, 5, 8).foreach { retainedDepth =>
        val source = java.nio.file.Files.createTempFile(
          s"morphhdl-streamfifo-depth-$retainedDepth-",
          ".v"
        )
        val output = java.nio.file.Files.createTempFile(
          s"morphhdl-streamfifo-depth-$retainedDepth-",
          ".out"
        )
        java.nio.file.Files.write(
          source,
          verilog.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
        val process = new ProcessBuilder(
          "iverilog",
          "-g2001",
          "-s",
          top,
          s"-P$top.DEPTH=$retainedDepth",
          "-o",
          output.toString,
          source.toString
        ).redirectErrorStream(true).start()
        val diagnostics = scala.io.Source
          .fromInputStream(process.getInputStream)
          .mkString
        val exit = process.waitFor()
        assert(
          exit == 0,
          s"strict Verilog-2001 override DEPTH=$retainedDepth failed:\\n$diagnostics"
        )
      }
'''
    test_text = test_text[:block_end] + proof + test_text[block_end:]
    test_path.write_text(test_text)


# ---------------------------------------------------------------------------
# Reviewed bounded contract.
# ---------------------------------------------------------------------------
doc_path = ROOT / "docs/morphhdl/increment-37-parameterized-streamfifo-depth.md"
if not doc_path.exists():
    doc_path.write_text("""# Increment 37 — Parameterized StreamFifo depth

## Objective

Carry one bounded public `DEPTH` parameter through the ordinary Spinal
`StreamFifo` source path. The same logical FIFO definition must elaborate at
its concrete witness and compile as strict Verilog-2001 at depths 1, 3, 5 and
8 without regeneration or a separately implemented ParamRTL FIFO.

## Native-source contract

The new overload accepts `ParameterizedMemoryDepth`, constructs the existing
`StreamFifo` at that object's concrete witness, and then retains the depth on
the FIFO's one native `Mem`. Existing `Int` constructors and concrete
`SpinalVerilog` behavior are unchanged.

The witness is deliberately non-power-of-two. That selects the existing
library terminal-count pointer wrap path, which remains correct for every
bounded override, including power-of-two depth 8. MorphHDL rewrites only the
witness-derived geometry and terminal constants in the normally emitted FIFO:

- native storage depth and its guarded address domain;
- push/pop pointer and memory-address widths as `clog2(DEPTH, 1)`;
- pointer terminal count as `DEPTH - 1`;
- occupancy and availability widths as `clog2(DEPTH + 1, 1)`;
- full-capacity comparisons and availability arithmetic as `DEPTH`.

The normal Stream valid/ready/payload, flush, synchronous read arbitration,
read-first collision policy, pointer updates and occupancy update statements
remain authoritative. No FIFO algorithm is emitted from ParamRTL or a
component-specific replacement backend.

## Supported boundary

This increment supports the ordinary default `StreamFifo` option set that
elaborates exactly one native `Mem`. Alternative option combinations that
select another storage representation are rejected by the typed overload
instead of silently losing the symbolic depth. Extending those variants can
reuse the same retained-depth representation in a later reviewed change.

The declared domain must be finite, positive, Int-sized, and its default must
match the concrete witness. The existing native-memory diagnostics enforce
those constraints.

## Validation

`ParameterizedStreamFifoDepthTests` reuses the reviewed Increment 36 harness,
checks that the public parameter reaches both the parent and native FIFO
module, verifies symbolic storage/pointer/occupancy geometry, preserves
concrete-default output, and compiles the same generated Verilog-2001 source
with `DEPTH` overridden to 1, 3, 5 and 8. The focused suite and existing
parameterized core regressions run on Scala 2.12.18 and 2.13.12.
""")

# The checkbox is intentionally left unchecked until both Scala gates pass.
todo_path = ROOT / "docs/morphhdl/parameterized-verilog-todo.md"
todo = todo_path.read_text().replace(
    "- [x] **Increment 37 — Parameterized StreamFifo depth**",
    "- [ ] **Increment 37 — Parameterized StreamFifo depth**",
)
todo_path.write_text(todo)
