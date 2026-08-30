#!/usr/bin/env python3
"""Use the direct spinal.core typed-width factory in the Increment 53d proof.

The direct ElabInt proof must not route typed widths back through the legacy
morphhdl.frontend Bits facade.  That facade is retained only as a compatibility
oracle for the pre-53d shadow path.
"""

from pathlib import Path

PATH = Path(
    "morphhdl/src/test/scala/morphhdl/ParameterizedStreamWidthAdapterTests.scala"
)

text = PATH.read_text()
old = "morphhdl.frontend.Bits("
new = "spinal.core.Bits("
count = text.count(old)
if count == 0:
    raise SystemExit(f"expected at least one {old!r} occurrence in {PATH}")

PATH.write_text(text.replace(old, new))
print(f"replaced {count} legacy Bits factory call(s) with direct spinal.core.Bits")
