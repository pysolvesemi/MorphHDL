#!/usr/bin/env python3
from pathlib import Path
import base64
import zlib

TARGET = Path("morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala")
PAYLOAD = "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"

TARGET.parent.mkdir(parents=True, exist_ok=True)
TARGET.write_bytes(zlib.decompress(base64.b64decode(PAYLOAD)))
source = TARGET.read_text()
old = """    val simulationLog = run(directory, Seq("vvp", executable.toString))
    assert(simulationLog._1 == 0, simulationLog._2)
    assert(
      simulationLog._2.contains(s"PASS depth=$selectedDepth"),
      simulationLog._2
    )
"""
new = """    val simulationLog = run(directory, Seq("vvp", executable.toString))
    if (
      simulationLog._1 != 0 ||
        !simulationLog._2.contains(s"PASS depth=$selectedDepth")
    ) {
      println(s"--- BEGIN PARAMETERIZED FIFO RTL depth=$selectedDepth ---")
      println(read(rtl))
      println(s"--- END PARAMETERIZED FIFO RTL depth=$selectedDepth ---")
    }
    assert(simulationLog._1 == 0, simulationLog._2)
    assert(
      simulationLog._2.contains(s"PASS depth=$selectedDepth"),
      simulationLog._2
    )
"""
if source.count(old) != 1:
    raise SystemExit('Increment 37 simulation assertion anchor was not found')
TARGET.write_text(source.replace(old, new, 1))
print("Generated focused Increment 37 depth simulation and synthesis regression")
