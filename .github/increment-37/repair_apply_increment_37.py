#!/usr/bin/env python3
from pathlib import Path

path = Path('.github/increment-37/apply_increment_37.py')
text = path.read_text()
start_marker = 'call_match = None\n'
end_marker = '\nblock = block.replace("[0:3]", "[0:DEPTH-1]")'
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('Increment 37 fixture-repair anchors were not found')
replacement = '''fixture_anchor = "    val fifo = StreamFifo(Bits(width bits), depth = 4, latency = 2)"
fixture_replacement = f\'''    val depthSchema = ElaborationIntegerParameter(
      name = "DEPTH",
      default = BigInt(5),
      minimum = BigInt(1),
      maximum = BigInt(8)
    )
    val symbolicDepth = ParameterizedMemoryDepth(
      value = 5,
      expression = ElaborationIntegerExpression(
        verilog = "DEPTH",
        default = BigInt(5),
        minimum = BigInt(1),
        maximum = BigInt(8),
        parameters = Vector(depthSchema),
        sourceLocation = Some("ParameterizedStreamFifoDepthTests.scala:DEPTH")
      ),
      sourceLocation = Some("ParameterizedStreamFifoDepthTests.scala:DEPTH")
    )
    val fifo = StreamFifo(Bits(width bits), depth = symbolicDepth)\'''
if fixture_anchor not in test_text:
    fail("static depth-four StreamFifo fixture not found")
test_text = test_text.replace(fixture_anchor, fixture_replacement, 1)
marker_index = test_text.find(marker)
outer_open = test_text.find("{", marker_index)
outer_close = matching_delimiter(test_text, outer_open, "{", "}")
block = test_text[outer_open + 1 : outer_close]
'''
repaired = text[:start] + replacement + text[end:]

existential_old = '    val leaves = memory.wordType().flatten.toVector'
existential_new = '    val leaves = memory.wordType().asInstanceOf[Data].flatten.toVector'
occurrences = repaired.count(existential_old)
if occurrences == 0 and existential_new not in repaired:
    raise SystemExit('Increment 37 existential memory-word anchors were not found')
repaired = repaired.replace(existential_old, existential_new)
if existential_old in repaired:
    raise SystemExit('Increment 37 existential memory-word repair was incomplete')

stream_write_anchor = 'stream_path.write_text(stream)'
pointer_resize_block = '''push_increment = "        push := push + 1"
pop_increment = "        pop := pop + 1"
if push_increment not in stream or pop_increment not in stream:
    fail("native StreamFifo pointer increment anchors not found")
stream = stream.replace(
    push_increment,
    "        push := (push + 1).resized",
    1,
)
stream = stream.replace(
    pop_increment,
    "        pop := (pop + 1).resized",
    1,
)
'''
if pointer_resize_block not in repaired:
    anchor_index = repaired.find(stream_write_anchor)
    if anchor_index < 0:
        raise SystemExit('Increment 37 Stream.scala write anchor was not found')
    repaired = repaired[:anchor_index] + pointer_resize_block + repaired[anchor_index:]

path.write_text(repaired)
print(
    f'Repaired Increment 37 fixture transformation, {occurrences} existential '
    'memory word access(es), and pointer increment sizing'
)
