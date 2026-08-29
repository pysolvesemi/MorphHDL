#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/test/scala/morphhdl/"
    "NativeStreamFifoCCProofTests.scala"
)
if not path.exists():
    raise SystemExit("StreamFifoCC proof source is missing")
value = path.read_text()

old = '''  val fifo = morphhdl.frontend.StreamFifoCC(
    HardType(Bits(8 bits)),
    depth,
    pushClockDomain,
    popClockDomain,
    withPopBufferedReset
  )
'''
new = '''  val fifo = morphhdl.frontend.StreamFifoCC(
    dataType = HardType(Bits(8 bits)),
    depth = depth,
    pushClock = pushClockDomain,
    popClock = popClockDomain,
    withPopBufferedReset = withPopBufferedReset
  )
'''
value = value.replace(old, new)

old = '''  val fifo = spinal.lib.StreamFifoCC(
    HardType(Bits(8 bits)),
    depth,
    pushClockDomain,
    popClockDomain,
    withPopBufferedReset
  )
'''
new = '''  val fifo = new spinal.lib.StreamFifoCC(
    dataType = HardType(Bits(8 bits)),
    depth = depth,
    pushClock = pushClockDomain,
    popClock = popClockDomain,
    withPopBufferedReset = withPopBufferedReset
  )
'''
value = value.replace(old, new)

old = '''  val fifo = new spinal.lib.StreamFifoCC(
    HardType(Bits(8 bits)),
    depth,
    pushClockDomain,
    popClockDomain,
    withPopBufferedReset
  )
'''
value = value.replace(old, new)

path.write_text(value)
