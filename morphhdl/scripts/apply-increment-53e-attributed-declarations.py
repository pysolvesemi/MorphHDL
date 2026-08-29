#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
value = path.read_text()

old = '''    val trimmed = line.trim
    val declarationLine =
      trimmed.startsWith("input ") || trimmed.startsWith("output ") ||
        trimmed.startsWith("inout ") || trimmed.startsWith("wire ") ||
        trimmed.startsWith("reg ") || trimmed.startsWith("logic ")
    if (!declarationLine) return line
'''
new = '''    val trimmed = line.trim
    // Preserve any number of complete one-line Verilog attributes preceding a
    // native declaration. The width rewrite remains keyed by the exact final
    // BaseType name obtained from graph identity; attributes affect syntax only.
    val declarationPrefix =
      "^(?:(?:\\\\(\\\\*[^\\\\r\\\\n]*?\\\\*\\\\))\\\\s*)*" +
        "(?:input|output|inout|wire|reg|logic)\\\\b"
    if (!declarationPrefix.r.findFirstIn(trimmed).nonEmpty) return line
'''
if value.count(old) != 1:
    raise SystemExit(
        f"attributed declaration marker count={value.count(old)}"
    )
path.write_text(value.replace(old, new, 1))
