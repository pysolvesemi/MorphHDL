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

broken_splice = 'test_text = test_text[:marker_index] + new_marker + test_text[marker_index + len(marker) : outer_open + (len(new_marker) - len(marker)) + 1] + block + test_text[outer_close + (len(new_marker) - len(marker)) :]'
safe_splice = 'test_text = test_text[:marker_index] + new_marker + " {" + block + "\\n  }" + test_text[outer_close + 1 :]'
if safe_splice not in repaired:
    if broken_splice not in repaired:
        raise SystemExit('Increment 37 test splice anchor was not found')
    repaired = repaired.replace(broken_splice, safe_splice, 1)

parameter_assertions_old = '''block = block.replace(
    'assert(!verilog.contains("parameter integer DEPTH"))',
    'assert(verilog.contains("parameter integer DEPTH = 5"))',
)
block = block.replace(
    'assert(!verilog.contains("parameter DEPTH"))',
    'assert(verilog.contains("parameter integer DEPTH = 5") || verilog.contains("parameter DEPTH = 5"))',
)
'''
parameter_assertions_new = '''block = block.replace(
    'assert(!parameterized.contains("parameter integer DEPTH"))',
    'assert(parameterized.contains("parameter integer DEPTH = 5"))',
)
'''
if parameter_assertions_new not in repaired:
    if parameter_assertions_old not in repaired:
        raise SystemExit('Increment 37 copied-test parameter assertion anchors were not found')
    repaired = repaired.replace(
        parameter_assertions_old,
        parameter_assertions_new,
        1,
    )

geometry_anchor = 'block = block.replace("[0:3]", "[0:DEPTH-1]")\n'
geometry_repair = geometry_anchor + '''block = block.replace(
    'assert(parameterizedReport._1.parameters.map(_.name) == Vector("WIDTH"))',
    'assert(parameterizedReport._1.parameters.map(_.name) == Vector("DEPTH", "WIDTH"))',
)
block = block.replace(
    'assert(parameterized.contains(".WIDTH(WIDTH)"))',
    'assert(parameterized.contains(".WIDTH(WIDTH)"))\\n      assert(parameterized.contains(".DEPTH(DEPTH)"))',
)
block = block.replace(
    'assert(parameterized.contains("< 4"))',
    'assert(parameterized.contains("< DEPTH"))',
)
block = block.replace(
    'assert(concrete.contains("[0:DEPTH-1]"))',
    'assert(concrete.contains("[0:4]"))',
)
block = block.replace(
    'assert(!concrete.contains("parameter integer WIDTH"))',
    'assert(!concrete.contains("parameter integer WIDTH"))\\n      assert(!concrete.contains("parameter integer DEPTH"))',
)
'''
if geometry_repair not in repaired:
    if geometry_anchor not in repaired:
        raise SystemExit('Increment 37 copied-test geometry anchor was not found')
    repaired = repaired.replace(geometry_anchor, geometry_repair, 1)

proof_start = repaired.find("proof = r'''")
proof_end = repaired.find("\n'''", proof_start)
if proof_start < 0 or proof_end < 0:
    raise SystemExit('Increment 37 override-proof anchors were not found')
proof_block = repaired[proof_start:proof_end]
if 'verilog.contains' in proof_block or 'verilog.getBytes' in proof_block:
    proof_block = proof_block.replace('verilog', 'parameterized')
elif 'parameterized.contains' not in proof_block:
    raise SystemExit('Increment 37 override proof has no recognized Verilog source binding')
repaired = repaired[:proof_start] + proof_block + repaired[proof_end:]

path.write_text(repaired)
print(
    f'Repaired Increment 37 fixture transformation, {occurrences} existential '
    'memory word access(es), pointer increment sizing, test-body splice, and '
    'copied regression bindings'
)
