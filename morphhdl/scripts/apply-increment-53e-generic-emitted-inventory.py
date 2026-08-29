#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogHierarchy.scala"
)
value = path.read_text()
old = '''        val emittedInstanceInventory = lines.zipWithIndex.collect {
          case (line, index)
              if line.toLowerCase.contains("buffercc") ||
                line.contains(instance.definitionName) ||
                line.contains(instance.instanceName) =>
            s"$index:${line.trim}"
        }.mkString(" | ")
'''
new = '''        val emittedInstanceInventory = lines.zipWithIndex.collect {
          case (line, index)
              if line.contains(instance.definitionName) ||
                line.contains(instance.instanceName) =>
            s"$index:${line.trim}"
        }.mkString(" | ")
'''
if value.count(old) != 1:
    raise SystemExit(
        f"generic emitted inventory marker count={value.count(old)}"
    )
path.write_text(value.replace(old, new, 1))
