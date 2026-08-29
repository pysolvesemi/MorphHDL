#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path('.')
scala_files = list(ROOT.rglob('*.scala'))


def texts_with(pattern: str):
    result = []
    for path in scala_files:
        value = path.read_text(encoding='utf-8', errors='replace')
        if pattern in value:
            result.append((path, value))
    return result

elab_candidates = [
    (p, t) for p, t in texts_with('ElabInt')
    if re.search(r'\b(?:class|trait|case class)\s+ElabInt\b', t)
]
if len(elab_candidates) != 1:
    raise SystemExit(f'expected exactly one ElabInt definition, found {[str(p) for p, _ in elab_candidates]}')
elab_path, elab_text = elab_candidates[0]

required_tokens = ['def +', 'def -', 'def *', 'def /', 'def %', 'def <', 'def <=', 'def >', 'def >=']
missing = [token for token in required_tokens if token not in elab_text]
if missing:
    raise SystemExit(f'ElabInt foundation is missing typed operations: {missing}')

constant_match = re.search(
    r'def\s+(constantInt|requireConstantInt|staticInt|witnessIfConstant)\s*\(',
    elab_text,
)
if not constant_match:
    raise SystemExit('ElabInt foundation needs one explicit complete-domain constant-to-Int method')
constant_method = constant_match.group(1)

width_helpers = []
for path, value in texts_with('widthOfExpr'):
    if re.search(r'def\s+widthOfExpr\s*\(', value):
        width_helpers.append(path)
if not width_helpers:
    raise SystemExit('typed foundation needs a generic widthOfExpr(Data): ElabInt helper')

stream = Path('lib/src/main/scala/spinal/lib/Stream.scala')
text = stream.read_text(encoding='utf-8')
old_widths = '''    val inputWidth = widthOf(input.payload)\n    val outputWidth = widthOf(output.payload)\n'''
new_widths = '''    val inputWidth: ElabInt = widthOfExpr(input.payload)\n    val outputWidth: ElabInt = widthOfExpr(output.payload)\n'''
if old_widths in text:
    text = text.replace(old_widths, new_widths, 1)
elif new_widths not in text:
    raise SystemExit('StreamWidthAdapter width-query insertion point not found')

old_down = '''      val factor = (inputWidth + outputWidth - 1) / outputWidth\n      val paddedInputWidth = factor * outputWidth\n'''
new_down = f'''      val factorExpr = (inputWidth + outputWidth - 1) / outputWidth\n      val factor = factorExpr.{constant_method}("StreamWidthAdapter downsize factor")\n      val paddedInputWidth = outputWidth * factor\n'''
if old_down in text:
    text = text.replace(old_down, new_down, 1)
elif new_down not in text:
    raise SystemExit('StreamWidthAdapter downsize factor insertion point not found')

old_up = '''      val factor  = (outputWidth + inputWidth - 1) / inputWidth\n      val paddedOutputWidth = factor * inputWidth\n'''
new_up = f'''      val factorExpr = (outputWidth + inputWidth - 1) / inputWidth\n      val factor  = factorExpr.{constant_method}("StreamWidthAdapter upsize factor")\n      val paddedOutputWidth = inputWidth * factor\n'''
if old_up in text:
    text = text.replace(old_up, new_up, 1)
elif new_up not in text:
    raise SystemExit('StreamWidthAdapter upsize factor insertion point not found')

# Parameter-sensitive shift amounts are intentionally explicit. The Increment
# 53d upsize contract fixes the input chunk width over each admitted domain.
old_shift = '        buffer := input.payload ## (buffer >> inputWidth)\n'
new_shift = f'''        buffer := input.payload ## (buffer >> inputWidth.{constant_method}("StreamWidthAdapter input chunk width"))\n'''
if old_shift in text:
    text = text.replace(old_shift, new_shift, 1)
elif new_shift not in text:
    raise SystemExit('StreamWidthAdapter shift insertion point not found')

stream.write_text(text, encoding='utf-8')

manifest = Path('docs/morphhdl/increment-53d-typed-native-change-manifest.md')
manifest.write_text(f'''# Increment 53d typed native-change manifest

The approved typed-elaboration architecture permits the following reviewed
native change. The original algorithm remains authoritative.

| File | Classification | Change | Algorithm impact |
|---|---|---|---|
| `lib/src/main/scala/spinal/lib/Stream.scala` | typed helper and mechanical propagation | `StreamWidthAdapter` obtains payload geometry with `widthOfExpr`, keeps width arithmetic typed, and extracts only domain-proven constant factors/chunk widths for existing host-side collection and counter APIs | none; equal-width, downsize, upsize, ordering, buffering and ready/valid logic are unchanged |

Neutral carrier definition: `{elab_path}`.

Forbidden changes checked by CI:

- no separately authored adapter;
- no module, port, signal or emitted-text recognition;
- no native-`Int` shadow capture in the typed adapter path;
- no algorithm change outside the parameter-sensitive width statements above.
''', encoding='utf-8')
