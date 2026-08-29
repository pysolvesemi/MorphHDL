#!/usr/bin/env python3
"""Finish the generic compiler-selection refactor.

Private implementation identifiers and diagnostics must describe the structural
native-Component rule rather than either FIFO regression witness.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PLUGIN = ROOT / "morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala"
path = PLUGIN
value = path.read_text()

# These names are private to this compiler phase.  Renaming them makes it
# mechanically clear that the transformation is selected by upstream source,
# Component ancestry and a single Int constructor parameter—not by a library
# component identity.
value = value.replace("nativeStreamFifo", "nativeIntComponent")
value = value.replace("NativeStreamFifo", "NativeIntComponent")
value = value.replace("StreamFifoCC", "native Component")
value = value.replace("StreamFifo", "native Component")
value = value.replace("/lib/src/main/scala/spinal/lib/Stream.scala", "/lib/src/main/scala/spinal/")

for forbidden in ("StreamFifo", "StreamFifoCC", "BufferCC"):
    if forbidden in value:
        raise SystemExit(
            f"component-specific compiler identity remains in {path}: {forbidden}"
        )

required = (
    "isGenericNativeIntComponent",
    "isNativeIntConstructorParameter",
    "integerParameters == 1",
    'base.fullName == "spinal.core.Component"',
)
for marker in required:
    if marker not in value:
        raise SystemExit(f"generic compiler-selection marker missing: {marker}")

path.write_text(value)
