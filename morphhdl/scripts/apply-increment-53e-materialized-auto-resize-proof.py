#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedAutoResize.scala"
)
value = path.read_text()

old = '''        val currentSource = assignment.source
        exactEdge &&
        (assignment.target eq target) &&
        (assignment.finalTarget eq target) &&
        (target.component eq component) &&
        currentSource.isInstanceOf[WidthProvider] &&
        currentSource.getTypeObject == TypeUInt &&
        currentSource.asInstanceOf[WidthProvider].getWidth == target.getBitsWidth &&
        !currentSource.isInstanceOf[Resize]
'''
new = '''        val currentSource = assignment.source
        val materializedAutoResize = currentSource match {
          case resize: ResizeUInt =>
            (assignment eq record.outer) &&
            (target eq record.target) &&
            resize.getTypeObject == TypeUInt &&
            resize.size == target.getBitsWidth &&
            resize.input != null &&
            resize.input.getTypeObject == TypeUInt &&
            ((resize.input eq record.resizeSource) ||
              (resize.input eq record.sourceDriver.source))
          case _ => false
        }
        exactEdge &&
        (assignment.target eq target) &&
        (assignment.finalTarget eq target) &&
        (target.component eq component) &&
        currentSource.isInstanceOf[WidthProvider] &&
        currentSource.getTypeObject == TypeUInt &&
        currentSource.asInstanceOf[WidthProvider].getWidth == target.getBitsWidth &&
        (!currentSource.isInstanceOf[Resize] || materializedAutoResize)
'''

count = value.count(old)
if count != 1:
    raise SystemExit(
        f"materialized auto-resize proof: expected one match, found {count}"
    )

path.write_text(value.replace(old, new, 1))
