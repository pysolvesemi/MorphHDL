#!/usr/bin/env python3
from pathlib import Path

paths = [
    Path(
        "morphhdl/src/main/scala/spinal/core/internals/"
        "ExternalParameterizedVerilogHierarchy.scala"
    ),
    Path(
        "morphhdl/src/main/scala/spinal/core/internals/"
        "ExternalParameterizedVerilogNativeFallback.scala"
    ),
    Path(
        "morphhdl/src/main/scala/spinal/core/internals/"
        "ParameterizedVerilogMemories.scala"
    ),
]

replacements = {
    "BufferCC, BufferCC_1, ...": "NativeChild, NativeChild_1, ...",
    "native FIFO": "native component",
    "native fifo": "native component",
}

for path in paths:
    if not path.exists():
        continue
    value = path.read_text()
    for old, new in replacements.items():
        value = value.replace(old, new)
    path.write_text(value)
