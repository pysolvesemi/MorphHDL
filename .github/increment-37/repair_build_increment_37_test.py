#!/usr/bin/env python3
from pathlib import Path

path = Path('.github/increment-37/build_increment_37_test.py')
text = path.read_text()
start_marker = 'selected = None\n'
end_marker = '\nblock = block.replace("[0:3]", "[0:DEPTH-1]")'
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('Increment 37 regression-generator repair anchors were not found')
replacement = '''symbolic_call = "val fifo = StreamFifo(Bits(width bits), depth = symbolicDepth)"
if symbolic_call not in text:
    fail("symbolic-depth StreamFifo fixture not found after source transformation")
'''
path.write_text(text[:start] + replacement + text[end:])
print('Repaired Increment 37 regression generator')
