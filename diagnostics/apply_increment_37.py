#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import sys

ROOT = Path.cwd()


def fail(message: str) -> None:
    raise SystemExit(message)


def replace_once(text: str, old: str, new: str, description: str) -> str:
    if new in text:
        return text
    if old not in text:
        fail(f"missing {description} anchor")
    return text.replace(old, new, 1)


def matching_delimiter(text: str, opening: int, opening_char: str, closing_char: str) -> int:
    depth = 0
    index = opening
    mode = "code"
    while index < len(text):
        if mode == "code":
            if text.startswith("//", index):
                mode = "line_comment"
                index += 2
                continue
            if text.startswith("/*", index):
                mode = "block_comment"
                index += 2
                continue
            if text.startswith('"""', index):
                mode = "triple_string"
                index += 3
                continue
            char = text[index]
            if char == '"':
                mode = "string"
            elif char == "'":
                mode = "char"
            elif char == opening_char:
                depth += 1
            elif char == closing_char:
                depth -= 1
                if depth == 0:
                    return index
        elif mode == "line_comment":
            if text[index] == "\n":
                mode = "code"
        elif mode == "block_comment":
            if text.startswith("*/", index):
                mode = "code"
                index += 2
                continue
        elif mode == "triple_string":
            if text.startswith('"""', index):
                mode = "code"
                index += 3
                continue
        elif mode in ("string", "char"):
            if text[index] == "\\":
                index += 2
                continue
            if (mode == "string" and text[index] == '"') or (
                mode == "char" and text[index] == "'"
            ):
                mode = "code"
        index += 1
    fail(f"unclosed {opening_char}{closing_char} delimiter")


# ---------------------------------------------------------------------------
# Shared native-memory representation
# ---------------------------------------------------------------------------
memory_path = ROOT / "core/src/main/scala/spinal/core/ParameterizedMemory.scala"
memory = memory_path.read_text()

memory_tag_anchor = """private[core] final case class ParameterizedMemoryTag(
    metadata: ParameterizedMemoryMetadata
) extends SpinalTag {
  override def allowMultipleInstance: Boolean = false
  override def canSymplifyHost: Boolean = true
}
"""
memory_tag_block = memory_tag_anchor + """
/**
  * A shared native library primitive may replace only the concrete witness
  * depth retained by its ordinary Mem after normal elaboration.
  */
private[core] final case class ParameterizedMemoryDepthOverrideTag(
    depth: ElaborationIntegerExpression,
    sourceLocation: Option[String]
) extends SpinalTag {
  override def allowMultipleInstance: Boolean = false
  override def canSymplifyHost: Boolean = true
}
"""
memory = replace_once(
    memory,
    memory_tag_anchor,
    memory_tag_block,
    "ParameterizedMemoryTag",
)

attach_anchor = """  private[core] def attach[T <: Data](
      memory: Mem[T],
      depth: ParameterizedMemoryDepth
  ): Mem[T] = {
"""
retain_methods = """  /**
    * Retain one bounded depth on the single native Mem owned by a library
    * component while leaving that component's ordinary algorithm authoritative.
    */
  private[spinal] def retainSingleDepth(
      component: Component,
      depth: ParameterizedMemoryDepth
  ): Unit = {
    val values = ArrayBuffer.empty[Mem[_]]
    component.dslBody.walkDeclarations {
      case memory: Mem[_] => values += memory
      case _              =>
    }
    val memories = values.distinct.toVector
    if (memories.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-LIBRARY-MEMORY-COUNT",
        s"parameterized library component '${component.definitionName}' must own exactly one native memory, found ${memories.size}",
        depth.sourceLocation
      )
    }
    retainDepth(memories.head, depth)
  }

  /** Overlay a bounded symbolic depth on an existing ordinary native Mem. */
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

    val leaves = memory.wordType().asInstanceOf[Data].flatten.toVector
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
if "def retainSingleDepth(" not in memory:
    if attach_anchor not in memory:
        fail("missing ParameterizedMemory.attach anchor")
    memory = memory.replace(attach_anchor, retain_methods + attach_anchor, 1)

metadata_old = """  private[core] def metadataOf(
      memory: Mem[_]
  ): Option[ParameterizedMemoryMetadata] =
    memory.getTag(classOf[ParameterizedMemoryTag]).map(_.metadata)
"""
metadata_new = """  private[core] def metadataOf(
      memory: Mem[_]
  ): Option[ParameterizedMemoryMetadata] = {
    val base = memory.getTag(classOf[ParameterizedMemoryTag]).map(_.metadata)
    memory.getTag(classOf[ParameterizedMemoryDepthOverrideTag]) match {
      case Some(tag) =>
        base.map { metadata =>
          metadata.copy(
            depth = tag.depth,
            sourceLocation = tag.sourceLocation.orElse(metadata.sourceLocation)
          )
        }
      case None => base
    }
  }
"""
memory = replace_once(
    memory,
    metadata_old,
    metadata_new,
    "ParameterizedMemory.metadataOf",
)
memory_path.write_text(memory)


# ---------------------------------------------------------------------------
# Ordinary StreamFifo front door
# ---------------------------------------------------------------------------
helper_path = ROOT / "lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala"
helper_path.write_text(
    """package spinal.lib

import spinal.core._

/** Retains a bounded public depth on one ordinary Spinal StreamFifo. */
private[lib] object ParameterizedStreamFifoDepth {
  def attach[T <: Data](
      fifo: StreamFifo[T],
      depth: ParameterizedMemoryDepth
  ): StreamFifo[T] = {
    ParameterizedMemory.retainSingleDepth(fifo, depth)
    fifo
  }
}
"""
)

stream_path = ROOT / "lib/src/main/scala/spinal/lib/Stream.scala"
stream = stream_path.read_text()
if "depth: ParameterizedMemoryDepth" not in stream:
    object_match = re.search(r"object\s+StreamFifo\s*\{", stream)
    if object_match is None:
        fail("object StreamFifo not found")
    overload = """

  /**
    * Construct the ordinary StreamFifo at its concrete witness while retaining
    * one bounded public depth for parameter-aware Verilog generation.
    *
    * The existing Int front door and the native FIFO algorithm are unchanged.
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
    stream = stream[: object_match.end()] + overload + stream[object_match.end() :]
push_increment = "        push := push + 1"
pop_increment = "        pop := pop + 1"
if push_increment not in stream or pop_increment not in stream:
    fail("native StreamFifo pointer increment anchors not found")
stream = stream.replace(
    push_increment,
    "        push := (push + 1).resized",
    1,
)
stream = stream.replace(
    pop_increment,
    "        pop := (pop + 1).resized",
    1,
)
stream_path.write_text(stream)


# ---------------------------------------------------------------------------
# Retain depth-derived geometry in the normally emitted FIFO module
# ---------------------------------------------------------------------------
backend_path = ROOT / "core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala"
backend = backend_path.read_text()
call_anchor = """    lines = rewriteMemoryDeclaration(lines, plan, helperName)
    lines = rewriteReadTargetDeclaration(lines, plan, helperName)
"""
call_replacement = call_anchor + """    lines = rewriteParameterizedStreamFifoDepth(lines, plan, helperName)
"""
backend = replace_once(
    backend,
    call_anchor,
    call_replacement,
    "native-memory rewrite sequence",
)

if "private def rewriteParameterizedStreamFifoDepth(" not in backend:
    method_anchor = """  private def rewriteReadTargetDeclaration(
"""
    if method_anchor not in backend:
        fail("rewriteReadTargetDeclaration anchor not found")
    method = r'''  /**
    * Retain the native non-power-of-two StreamFifo algorithm while replacing
    * witness-only geometry with the public bounded depth. A witness of five
    * selects the library's explicit terminal-count wrap path, which remains
    * valid for each supported override, including one and powers of two.
    */
  private def rewriteParameterizedStreamFifoDepth(
      lines: Vector[String],
      plan: MemoryPlan,
      helperName: String
  ): Vector[String] = {
    val depth = plan.metadata.depth
    if (depth.parameters.isEmpty) return lines

    val moduleText = lines.mkString("\n").toLowerCase
    val isStreamFifo =
      moduleText.contains("io_push_valid") &&
        moduleText.contains("io_push_ready") &&
        moduleText.contains("io_pop_valid") &&
        moduleText.contains("io_pop_ready") &&
        moduleText.contains("io_occupancy") &&
        moduleText.contains("io_availability")
    if (!isStreamFifo) return lines

    val depthExpression = render(depth, helperName)
    val pointerWidth = s"$helperName($depthExpression, 1)"
    val occupancyWidth = s"$helperName(($depthExpression + 1), 1)"
    val depthDefault = depth.default
    val packedRange = "\\[[^\\]]+\\]".r
    val sizedLiteral = "(?i)([0-9]+)'([s]?)([bodh])([0-9a-f_xz]+)".r
    val pushReadyAssignment =
      """^(\s*assign\s+io_push_ready\s*=\s*)(.*)(;\s*)$""".r

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

    def replaceSized(line: String, target: BigInt, replacement: String): String =
      sizedLiteral.replaceAllIn(
        line,
        value =>
          literalValue(value.group(4), value.group(3)) match {
            case Some(parsed) if parsed == target =>
              java.util.regex.Matcher.quoteReplacement(replacement)
            case _ => value.matched
          }
      )

    def replaceDecimal(line: String, target: BigInt, replacement: String): String = {
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
      // Normal emission uses logic_ptr_push/logic_ptr_pop while other
    // contexts use pushPtr/popPtr. Normalize separators and accept both
    // word orders so a non-power-of-two override never retains the
    // concrete witness terminal.
    val compactName = lower.replace("_", "")
    val pointerContext =
      compactName.contains("pushptr") || compactName.contains("popptr") ||
        compactName.contains("ptrpush") || compactName.contains("ptrpop") ||
        compactName.contains("poponio") ||
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
      line match {
        case pushReadyAssignment(prefix, rhs, suffix) =>
          s"$prefix(($depthExpression == 1) ? ((io_occupancy == 0) || (io_pop_valid && io_pop_ready)) : ($rhs))$suffix"
        case _ => line
      }
    }
  }

'''
    backend = backend.replace(method_anchor, method + method_anchor, 1)
helper_needed_old = """    val helperNeeded =
      Vector(
        plan.metadata.depth,
        plan.metadata.elementWidth,
        plan.readAddressWidth,
        plan.writeAddressWidth
      ).exists(expression => PortableLogCall.findFirstIn(expression.verilog).nonEmpty) ||
        PortableLogCall.findFirstIn(normalized).nonEmpty
"""
helper_needed_new = """    val helperNeeded =
      Vector(
        plan.metadata.depth,
        plan.metadata.elementWidth,
        plan.readAddressWidth,
        plan.writeAddressWidth
      ).exists(expression => PortableLogCall.findFirstIn(expression.verilog).nonEmpty) ||
        PortableLogCall.findFirstIn(normalized).nonEmpty ||
        (
          plan.metadata.depth.parameters.nonEmpty &&
            normalized.toLowerCase.contains("io_push_valid") &&
            normalized.toLowerCase.contains("io_push_ready") &&
            normalized.toLowerCase.contains("io_pop_valid") &&
            normalized.toLowerCase.contains("io_pop_ready") &&
            normalized.toLowerCase.contains("io_occupancy") &&
            normalized.toLowerCase.contains("io_availability")
        )
"""
backend = replace_once(
    backend,
    helper_needed_old,
    helper_needed_new,
    "StreamFifo portable-log helper dependency",
)
backend_path.write_text(backend)


# ---------------------------------------------------------------------------
# Focused regression, based on the reviewed Increment 36 harness
# ---------------------------------------------------------------------------
source_test_path = ROOT / "morphhdl/src/test/scala/morphhdl/NativeLibraryReuseTests.scala"
target_test_path = ROOT / "morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala"
test_text = source_test_path.read_text()
test_text = test_text.replace(
    "class NativeLibraryReuseTests extends AnyFunSuite",
    "class ParameterizedStreamFifoDepthTests extends AnyFunSuite",
    1,
)
marker = 'test("ordinary StreamFifo reuses its native static-depth storage with a symbolic payload width")'
marker_index = test_text.find(marker)
if marker_index < 0:
    fail("reviewed Increment 36 StreamFifo test not found")
outer_open = test_text.find("{", marker_index)
outer_close = matching_delimiter(test_text, outer_open, "{", "}")
block = test_text[outer_open + 1 : outer_close]

fixture_anchor = "    val fifo = StreamFifo(Bits(width bits), depth = 4, latency = 2)"
fixture_replacement = f'''    val depthSchema = ElaborationIntegerParameter(
      name = "DEPTH",
      default = BigInt(5),
      minimum = BigInt(1),
      maximum = BigInt(8)
    )
    val symbolicDepth = ParameterizedMemoryDepth(
      value = 5,
      expression = ElaborationIntegerExpression(
        verilog = "DEPTH",
        default = BigInt(5),
        minimum = BigInt(1),
        maximum = BigInt(8),
        parameters = Vector(depthSchema),
        sourceLocation = Some("ParameterizedStreamFifoDepthTests.scala:DEPTH")
      ),
      sourceLocation = Some("ParameterizedStreamFifoDepthTests.scala:DEPTH")
    )
    val fifo = StreamFifo(Bits(width bits), depth = symbolicDepth)'''
if fixture_anchor not in test_text:
    fail("static depth-four StreamFifo fixture not found")
test_text = test_text.replace(fixture_anchor, fixture_replacement, 1)
marker_index = test_text.find(marker)
outer_open = test_text.find("{", marker_index)
outer_close = matching_delimiter(test_text, outer_open, "{", "}")
block = test_text[outer_open + 1 : outer_close]

block = block.replace("[0:3]", "[0:DEPTH-1]")
block = block.replace(
    'assert(parameterizedReport._1.parameters.map(_.name) == Vector("WIDTH"))',
    'assert(parameterizedReport._1.parameters.map(_.name) == Vector("DEPTH", "WIDTH"))',
)
block = block.replace(
    'assert(parameterized.contains(".WIDTH(WIDTH)"))',
    'assert(parameterized.contains(".WIDTH(WIDTH)"))\n      assert(parameterized.contains(".DEPTH(DEPTH)"))',
)
block = block.replace(
    'assert(parameterized.contains("< 4"))',
    'assert(parameterized.contains("< DEPTH"))',
)
block = block.replace(
    'assert(concrete.contains("[0:DEPTH-1]"))',
    'assert(concrete.contains("[0:4]"))',
)
block = block.replace(
    'assert(!concrete.contains("parameter integer WIDTH"))',
    'assert(!concrete.contains("parameter integer WIDTH"))\n      assert(!concrete.contains("parameter integer DEPTH"))',
)
block = block.replace(
    'assert(!parameterized.contains("parameter integer DEPTH"))',
    'assert(parameterized.contains("parameter integer DEPTH = 5"))',
)

# Insert the override proof before the inner temporary-directory scope closes.
inner_last = len(block) - 1
while inner_last >= 0 and block[inner_last].isspace():
    inner_last -= 1
if inner_last < 0 or block[inner_last] != "}":
    fail("expected reviewed temporary-directory scope")
proof = r'''

      assert(
        parameterized.contains("parameter integer DEPTH = 5") ||
          parameterized.contains("parameter DEPTH = 5")
      )
      assert(
        parameterized.contains("[0:DEPTH-1]") ||
          parameterized.contains("[0:(DEPTH - 1)]")
      )
      assert(parameterized.contains("clog2(DEPTH, 1)"))
      assert(
        parameterized.contains("clog2((DEPTH + 1), 1)") ||
          parameterized.contains("clog2(DEPTH + 1, 1)")
      )
      assert(
        """(?m)^\s*(?:wire|reg|output(?:\s+reg)?)\s+\[clog2\(DEPTH, 1\)-1:0\].*(?:pushPtr|popPtr|address).*;\s*$""".r
          .findFirstIn(parameterized)
          .nonEmpty
      )
      assert(
        """(?m)^\s*(?:wire|reg|output(?:\s+reg)?)\s+\[clog2\(\(?DEPTH \+ 1\)?, 1\)-1:0\].*(?:occupancy|availability).*;\s*$""".r
          .findFirstIn(parameterized)
          .nonEmpty
      )

      val modules = """(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b""".r
        .findAllMatchIn(parameterized)
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
          parameterized.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
        val process = new ProcessBuilder(
          "iparameterized",
          "-g2001",
          "-s",
          top,
          s"-P$top.DEPTH=$retainedDepth",
          "-o",
          output.toString,
          source.toString
        ).redirectErrorStream(true).start()
        val diagnosticsSource = scala.io.Source.fromInputStream(process.getInputStream)
        val diagnostics = try diagnosticsSource.mkString finally diagnosticsSource.close()
        val exit = process.waitFor()
        assert(
          exit == 0,
          s"strict Verilog-2001 override DEPTH=$retainedDepth failed:\n$diagnostics"
        )
      }
'''
block = block[:inner_last] + proof + "\n" + block[inner_last:]
new_marker = 'test("ordinary StreamFifo retains one bounded depth across native storage and handshake geometry")'
test_text = test_text[:marker_index] + new_marker + " {" + block + "\n  }" + test_text[outer_close + 1 :]
# Rebuild more safely if the marker-length splice above encountered a changed offset.
if "class ParameterizedStreamFifoDepthTests" not in test_text or new_marker not in test_text:
    fail("failed to rebuild parameterized StreamFifo test")
target_test_path.write_text(test_text)


# ---------------------------------------------------------------------------
# Contract documentation; roadmap remains unchecked until both Scala gates pass
# ---------------------------------------------------------------------------
doc_path = ROOT / "docs/morphhdl/increment-37-parameterized-streamfifo-depth.md"
doc_path.write_text(
    """# Increment 37 — Parameterized StreamFifo depth

## Objective

Carry one bounded public `DEPTH` parameter through the ordinary Spinal
`StreamFifo` source path. The same logical FIFO definition must elaborate at
its concrete witness and compile as strict Verilog-2001 at depths 1, 3, 5 and
8 without regeneration or a separately implemented ParamRTL FIFO.

## Native-source contract

The overload accepts `ParameterizedMemoryDepth`, constructs the existing
`StreamFifo` at that object's concrete witness, and retains the bounded depth
on the FIFO's one native `Mem`. Existing `Int` constructors and concrete
`SpinalVerilog` behavior are unchanged.

A non-power-of-two witness selects the existing terminal-count pointer-wrap
path. MorphHDL rewrites only witness-derived geometry and terminal constants in
the normally emitted FIFO:

- native storage depth and its guarded address domain;
- push/pop pointer and memory-address widths as `clog2(DEPTH, 1)`;
- pointer terminal count as `DEPTH - 1`;
- occupancy and availability widths as `clog2(DEPTH + 1, 1)`;
- full-capacity comparisons and availability arithmetic as `DEPTH`.

The normal Stream valid/ready/payload, flush, synchronous read arbitration,
read-first collision policy, pointer updates and occupancy update statements
remain authoritative. No FIFO algorithm is emitted from ParamRTL or a
component-specific replacement implementation.

## Supported boundary

This increment supports the ordinary default `StreamFifo` option set that
elaborates exactly one native `Mem`. Alternative option combinations selecting
another storage representation fail explicitly instead of silently losing the
symbolic depth. The declared domain must be finite, positive and Int-sized,
and its default must match the concrete witness.

## Validation

`ParameterizedStreamFifoDepthTests` reuses the reviewed Increment 36 harness,
checks public parameter propagation and symbolic storage/pointer/occupancy
geometry, preserves concrete-default output, and compiles the same generated
strict Verilog-2001 source with `DEPTH` overridden to 1, 3, 5 and 8. The focused
suite and existing parameterized core regressions run on Scala 2.12.18 and
2.13.12.
"""
)

todo_path = ROOT / "docs/morphhdl/parameterized-verilog-todo.md"
todo = todo_path.read_text()
todo = todo.replace(
    "- [x] **Increment 37 — Parameterized StreamFifo depth**",
    "- [ ] **Increment 37 — Parameterized StreamFifo depth**",
)
if "- [ ] **Increment 37 — Parameterized StreamFifo depth**" not in todo:
    fail("Increment 37 roadmap entry not found")
todo_path.write_text(todo)

print("Increment 37 source transformation applied")
