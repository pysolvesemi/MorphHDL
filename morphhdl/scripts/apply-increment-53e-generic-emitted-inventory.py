#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogHierarchy.scala"
)
value = path.read_text()

inventory_old = '''        val emittedInstanceInventory = lines.zipWithIndex.collect {
          case (line, index)
              if line.toLowerCase.contains("buffercc") ||
                line.contains(instance.definitionName) ||
                line.contains(instance.instanceName) =>
            s"$index:${line.trim}"
        }.mkString(" | ")
'''
inventory_new = '''        val emittedInstanceInventory = lines.zipWithIndex.collect {
          case (line, index)
              if line.contains(instance.definitionName) ||
                line.contains(instance.instanceName) =>
            s"$index:${line.trim}"
        }.mkString(" | ")
'''
if value.count(inventory_old) != 1:
    raise SystemExit(
        f"generic emitted inventory marker count={value.count(inventory_old)}"
    )
value = value.replace(inventory_old, inventory_new, 1)

comment_old = '''        // The explicit-formal registry owns definition identity by native
        // component class and packed slot, not by Spinal's transient concrete
        // definition name. Multiple concrete witnesses of one untouched class
        // may be emitted as BufferCC, BufferCC_1, ... before MorphHDL
        // canonicalizes them; they must still share one source-stable formal.
'''
comment_new = '''        // The explicit-formal registry owns definition identity by native
        // component class and packed slot, not by Spinal's transient concrete
        // definition name. Multiple concrete witnesses of one untouched class
        // may receive deterministic numeric definition suffixes before MorphHDL
        // canonicalizes them; they must still share one source-stable formal.
'''
if value.count(comment_old) != 1:
    raise SystemExit(
        f"generic formal identity comment marker count={value.count(comment_old)}"
    )
value = value.replace(comment_old, comment_new, 1)

path.write_text(value)
