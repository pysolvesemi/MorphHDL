#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re

ROOT = Path.cwd()
SOURCE = ROOT / "morphhdl/src/test/scala/morphhdl/NativeLibraryReuseTests.scala"
TARGET = ROOT / "morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala"


def fail(message: str) -> None:
    raise SystemExit(message)


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


text = SOURCE.read_text()
text = text.replace(
    "class NativeLibraryReuseTests extends AnyFunSuite",
    "class ParameterizedStreamFifoDepthTests extends AnyFunSuite",
    1,
)
old_marker = 'test("ordinary StreamFifo reuses its native static-depth storage with a symbolic payload width")'
new_marker = 'test("ordinary StreamFifo retains one bounded depth across native storage and handshake geometry")'
marker_index = text.find(old_marker)
if marker_index < 0:
    fail("reviewed Increment 36 StreamFifo test not found")
outer_open = text.find("{", marker_index + len(old_marker))
if outer_open < 0:
    fail("StreamFifo test opening brace not found")
outer_close = matching_delimiter(text, outer_open, "{", "}")
block = text[outer_open + 1 : outer_close]

selected = None
for found in re.finditer(r"(?:new\s+)?StreamFifo\s*\(", block):
    opening = block.find("(", found.start())
    closing = matching_delimiter(block, opening, "(", ")")
    arguments = block[opening + 1 : closing]
    if re.search(r"\bdepth\s*=\s*4\b", arguments) or re.search(
        r",\s*4\s*(?:,|$)", arguments
    ):
        selected = (found.start(), opening, closing, arguments)
        break
if selected is None:
    fail("static depth-four StreamFifo call not found in reviewed fixture")
call_start, call_open, call_close, arguments = selected
if re.search(r"\bdepth\s*=\s*4\b", arguments):
    arguments = re.sub(
        r"\bdepth\s*=\s*4\b",
        "depth = symbolicDepth",
        arguments,
        count=1,
    )
else:
    arguments, count = re.subn(
        r",\s*4\s*(?=,|$)",
        ", symbolicDepth",
        arguments,
        count=1,
    )
    if count != 1:
        fail("StreamFifo depth argument replacement failed")

line_start = block.rfind("\n", 0, call_start) + 1
indent = re.match(r"\s*", block[line_start:call_start]).group(0)
setup = f'''{indent}val depthSchema = ElaborationIntegerParameter(
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
replacement_call = "StreamFifo(" + arguments + ")"
block = (
    block[:line_start]
    + setup
    + block[line_start:call_start]
    + replacement_call
    + block[call_close + 1 :]
)
block = block.replace("[0:3]", "[0:DEPTH-1]")
block = block.replace(
    'assert(!verilog.contains("parameter integer DEPTH"))',
    'assert(verilog.contains("parameter integer DEPTH = 5"))',
)
block = block.replace(
    'assert(!verilog.contains("parameter DEPTH"))',
    'assert(verilog.contains("parameter integer DEPTH = 5") || verilog.contains("parameter DEPTH = 5"))',
)

inner_close = len(block) - 1
while inner_close >= 0 and block[inner_close].isspace():
    inner_close -= 1
if inner_close < 0 or block[inner_close] != "}":
    fail("reviewed temporary-directory scope was not found")

proof = r'''

      assert(
        verilog.contains("parameter integer DEPTH = 5") ||
          verilog.contains("parameter DEPTH = 5")
      )
      assert(
        verilog.contains("[0:DEPTH-1]") ||
          verilog.contains("[0:(DEPTH - 1)]")
      )
      val generatedLines = verilog.split("\\n").toVector
      assert(
        generatedLines.exists { line =>
          (line.contains("pushPtr") || line.contains("popPtr") ||
            line.toLowerCase.contains("address")) &&
          line.contains("clog2(DEPTH, 1)")
        }
      )
      assert(
        generatedLines.exists { line =>
          (line.toLowerCase.contains("occupancy") ||
            line.toLowerCase.contains("availability")) &&
          (line.contains("clog2((DEPTH + 1), 1)") ||
            line.contains("clog2(DEPTH + 1, 1)"))
        }
      )
      assert(verilog.contains("DEPTH - 1"))

      val modules = """(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b""".r
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
        val diagnosticsSource = scala.io.Source.fromInputStream(process.getInputStream)
        val diagnostics = try diagnosticsSource.mkString finally diagnosticsSource.close()
        val exit = process.waitFor()
        assert(
          exit == 0,
          s"strict Verilog-2001 override DEPTH=$retainedDepth failed:\n$diagnostics"
        )
      }
'''
block = block[:inner_close] + proof + "\n" + block[inner_close:]
rebuilt = text[:marker_index] + new_marker + " {" + block + "}" + text[outer_close + 1 :]
if "class ParameterizedStreamFifoDepthTests" not in rebuilt:
    fail("test class rename failed")
if "Vector(1, 3, 5, 8)" not in rebuilt:
    fail("four-depth proof was not generated")
TARGET.write_text(rebuilt)
print("Increment 37 four-depth regression generated")
