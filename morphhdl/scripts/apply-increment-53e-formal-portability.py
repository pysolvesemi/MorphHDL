#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/test/scala/morphhdl/"
    "NativeStreamFifoCCFormalEquivalenceTests.scala"
)
value = path.read_text()
value = value.replace(
    "import java.nio.file.{Files, Path}\n",
    "import java.nio.file.{Files, Path, Paths}\n"
)
value = value.replace(
    "import morphhdl.frontend.HdlInt.hdlIntToParameterizedMemoryDepth\n",
    ""
)
value = value.replace(
    '''        popClock,
        withPopBufferedReset = buffered
''',
    '''        popClock,
        buffered
'''
)
value = value.replace(
    '''          popClock,
          withPopBufferedReset = buffered
''',
    '''          popClock,
          buffered
'''
)
value = value.replace(
    'ClockDomain.external("push", config) -> ClockDomain.external("pop", config)',
    'ClockDomain.external("push", config = config) -> ClockDomain.external("pop", config = config)'
)
value = value.replace(
    'val directory = Path.of(value).toAbsolutePath',
    'val directory = Paths.get(value).toAbsolutePath'
)
value = value.replace(
    '.reverse.foreach(Files.deleteIfExists)',
    '.reverse.foreach(path => Files.deleteIfExists(path))'
)
path.write_text(value)
