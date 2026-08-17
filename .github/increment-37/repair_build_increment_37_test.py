#!/usr/bin/env python3
from pathlib import Path

path = Path('.github/increment-37/build_increment_37_test.py')
path.write_text('''#!/usr/bin/env python3
from pathlib import Path

TARGET = Path("morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala")

if not TARGET.is_file():
    raise SystemExit("Increment 37 transformed regression was not generated")

text = TARGET.read_text()
required = (
    "class ParameterizedStreamFifoDepthTests extends AnyFunSuite",
    "ParameterizedMemoryDepth(",
    "depth = symbolicDepth",
    "parameter integer DEPTH = 5",
    "Vector(1, 3, 5, 8)",
)
missing = [token for token in required if token not in text]
if missing:
    raise SystemExit(
        "Increment 37 transformed regression is incomplete: " + ", ".join(missing)
    )

print("Increment 37 four-depth regression already generated")
''')
print('Repaired Increment 37 regression validator')
