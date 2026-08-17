#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[2]
path = root / 'morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala'
text = path.read_text()
marker = 'test("ordinary StreamFifo retains one bounded depth across native storage and handshake geometry")'
start = text.find(marker)
if start < 0:
    raise SystemExit('parameterized StreamFifo test marker not found')
brace = text.find('{', start)

def block_end(value: str, opening: int) -> int:
    depth = 0
    i = opening
    mode = 'code'
    while i < len(value):
        if mode == 'code':
            if value.startswith('//', i): mode = 'line'; i += 2; continue
            if value.startswith('/*', i): mode = 'block'; i += 2; continue
            if value.startswith('"""', i): mode = 'triple'; i += 3; continue
            c = value[i]
            if c == '"': mode = 'string'
            elif c == "'": mode = 'char'
            elif c == '{': depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0: return i
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
    raise SystemExit('test block not closed')

end = block_end(text, brace)
proof_start = text.rfind('\n      assert(\n        verilog.contains("parameter integer DEPTH = 5")', brace, end)
if proof_start < 0:
    raise SystemExit('override proof block not found')
proof = text[proof_start:end]
text = text[:proof_start] + text[end:]
# Recompute the outer block end after removing the proof.
start = text.find(marker)
brace = text.find('{', start)
end = block_end(text, brace)
previous = end - 1
while previous > brace and text[previous].isspace():
    previous -= 1
if text[previous] != '}':
    raise SystemExit('expected reviewed fixture scope immediately before test close')
text = text[:previous] + proof + '\n' + text[previous:]
path.write_text(text)
