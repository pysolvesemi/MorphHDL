#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/test/scala/morphhdl/"
    "NativeStreamFifoCCProofTests.scala"
)
if not path.exists():
    raise SystemExit("StreamFifoCC proof source is missing")
value = path.read_text()
value = value.replace(
'''  val fifo = spinal.lib.StreamFifoCC(
    HardType(Bits(8 bits)),
    depth,
    pushClockDomain,
    popClockDomain,
    withPopBufferedReset
  )
''',
'''  val fifo = new spinal.lib.StreamFifoCC(
    HardType(Bits(8 bits)),
    depth,
    pushClockDomain,
    popClockDomain,
    withPopBufferedReset
  )
''')
path.write_text(value)
