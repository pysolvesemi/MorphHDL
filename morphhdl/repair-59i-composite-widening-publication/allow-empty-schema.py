#!/usr/bin/env python3
"""Permit only a truly capture-free composite widening callback schema.

The callback inspector always returns a schema object, including for a closure
with zero serialized slots. Composite widening still rejects hardware and typed
configuration captures until their combined width transfer is separately
certified. This patch runs only after apply.py validates the exact production
baseline and applies the publication prototype.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala"

before = '''    if (shapeChanging) {
      if (schema.nonEmpty)
        fail("WIDENING-CAPTURE", "shape-changing composite leaves require a separate capture-composition proof")
'''
after = '''    if (shapeChanging) {
      if (schema.exists(value => value.hardwareInputs.nonEmpty || value.configurations.nonEmpty))
        fail("WIDENING-CAPTURE", "shape-changing composite leaves require a separate capture-composition proof")
'''

text = SOURCE.read_text()
if text.count(before) != 1:
    raise RuntimeError("exact widening-schema admission anchor changed")
SOURCE.write_text(text.replace(before, after, 1))
print("admitted only empty composite widening callback schemas")
