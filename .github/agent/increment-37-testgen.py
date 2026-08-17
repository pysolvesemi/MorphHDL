#!/usr/bin/env python3
from pathlib import Path
import re

root = Path(__file__).resolve().parents[2]
source_path = root / 'morphhdl/src/test/scala/morphhdl/NativeLibraryReuseTests.scala'
target_path = root / 'morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala'
text = source_path.read_text()
text = text.replace(
    'class NativeLibraryReuseTests extends AnyFunSuite',
    'class ParameterizedStreamFifoDepthTests extends AnyFunSuite',
    1,
)


def matching_paren(value: str, opening: int) -> int:
    depth = 0
    i = opening
    mode = 'code'
    while i < len(value):
        if mode == 'code':
            if value.startswith('//', i):
                mode = 'line'; i += 2; continue
            if value.startswith('/*', i):
                mode = 'block'; i += 2; continue
            if value.startswith('"""', i):
                mode = 'triple'; i += 3; continue
            c = value[i]
            if c == '"': mode = 'string'
            elif c == "'": mode = 'char'
            elif c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0:
                    return i
        elif mode == 'line':
            if value[i] == '\n': mode = 'code'
        elif mode == 'block':
            if value.startswith('*/', i): mode = 'code'; i += 2; continue
        elif mode == 'triple':
            if value.startswith('"""', i): mode = 'code'; i += 3; continue
        elif mode in ('string', 'char'):
            if value[i] == '\\': i += 2; continue
            if (mode == 'string' and value[i] == '"') or (mode == 'char' and value[i] == "'"):
                mode = 'code'
        i += 1
    raise SystemExit('unclosed StreamFifo call')

call = None
for found in re.finditer(r'(?:new\s+)?StreamFifo\s*\(', text):
    opening = text.find('(', found.start())
    closing = matching_paren(text, opening)
    args = text[opening + 1:closing]
    if re.search(r'\bdepth\s*=\s*4\b', args) or re.search(r',\s*4\s*(?:,|$)', args):
        call = (found.start(), opening, closing, args)
        break
if call is None:
    raise SystemExit('reviewed static-depth StreamFifo fixture was not found')
start, opening, closing, args = call
if re.search(r'\bdepth\s*=\s*4\b', args):
    args = re.sub(r'\bdepth\s*=\s*4\b', 'depth = symbolicDepth', args, count=1)
else:
    args, count = re.subn(r',\s*4\s*(?=,|$)', ', symbolicDepth', args, count=1)
    if count != 1:
        raise SystemExit('depth argument replacement failed')
line_start = text.rfind('\n', 0, start) + 1
indent = re.match(r'\s*', text[line_start:start]).group(0)
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
call_text = 'StreamFifo(' + args + ')'
text = text[:line_start] + setup + text[line_start:start] + call_text + text[closing + 1:]
text = text.replace(
    'ordinary StreamFifo reuses its native static-depth storage with a symbolic payload width',
    'ordinary StreamFifo retains one bounded depth across native storage and handshake geometry',
    1,
)
text = text.replace('[0:3]', '[0:DEPTH-1]')
text = text.replace(
    'assert(!verilog.contains("parameter integer DEPTH"))',
    'assert(verilog.contains("parameter integer DEPTH = 5"))',
)
text = text.replace(
    'assert(!verilog.contains("parameter DEPTH"))',
    'assert(verilog.contains("parameter integer DEPTH = 5") || verilog.contains("parameter DEPTH = 5"))',
)

marker = 'test("ordinary StreamFifo retains one bounded depth across native storage and handshake geometry")'
marker_pos = text.find(marker)
if marker_pos < 0:
    raise SystemExit('renamed StreamFifo test was not found')
brace = text.find('{', marker_pos)
# Reuse the parser state machine, now for braces.
depth = 0
i = brace
mode = 'code'
end = None
while i < len(text):
    if mode == 'code':
        if text.startswith('//', i): mode = 'line'; i += 2; continue
        if text.startswith('/*', i): mode = 'block'; i += 2; continue
        if text.startswith('"""', i): mode = 'triple'; i += 3; continue
        c = text[i]
        if c == '"': mode = 'string'
        elif c == "'": mode = 'char'
        elif c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                end = i; break
    elif mode == 'line':
        if text[i] == '\n': mode = 'code'
    elif mode == 'block':
        if text.startswith('*/', i): mode = 'code'; i += 2; continue
    elif mode == 'triple':
        if text.startswith('"""', i): mode = 'code'; i += 3; continue
    elif mode in ('string', 'char'):
        if text[i] == '\\': i += 2; continue
        if (mode == 'string' and text[i] == '"') or (mode == 'char' and text[i] == "'"):
            mode = 'code'
    i += 1
if end is None:
    raise SystemExit('StreamFifo test block was not closed')

proof = r'''
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
text = text[:end] + proof + text[end:]
target_path.write_text(text)
