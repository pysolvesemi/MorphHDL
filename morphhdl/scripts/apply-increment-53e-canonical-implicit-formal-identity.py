#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogHierarchy.scala"
)
value = path.read_text()
old = '''        val declarationKey =
          s"implicit-packed-width::${ownerClasses.head}::$definitionName::$key"
'''
new = '''        // The explicit-formal registry owns definition identity by native
        // component class and packed slot, not by Spinal's transient concrete
        // definition name. Multiple concrete witnesses of one untouched class
        // may be emitted as BufferCC, BufferCC_1, ... before MorphHDL
        // canonicalizes them; they must still share one source-stable formal.
        val declarationKey =
          s"implicit-packed-width::${ownerClasses.head}::$key"
'''
count = value.count(old)
if count != 1:
    raise SystemExit(
        f"canonical implicit formal identity marker count={count}"
    )
path.write_text(value.replace(old, new, 1))
