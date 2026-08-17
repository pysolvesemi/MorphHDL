#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[2]
memory_path = root / 'core/src/main/scala/spinal/core/ParameterizedMemory.scala'
text = memory_path.read_text()

if 'def retainSingleDepth(' not in text:
    anchor = '''  private[spinal] def retainDepth(
      memory: Mem[_],
      depth: ParameterizedMemoryDepth
  ): Unit = {
'''
    helper = '''  /** Retain one bounded depth on the single native Mem owned by a library component. */
  private[spinal] def retainSingleDepth(
      component: Component,
      depth: ParameterizedMemoryDepth
  ): Unit = {
    val values = ArrayBuffer.empty[Mem[_]]
    component.dslBody.walkDeclarations {
      case memory: Mem[_] => values += memory
      case _              =>
    }
    val memories = values.distinct.toVector
    if (memories.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-LIBRARY-MEMORY-COUNT",
        s"parameterized library component '${component.definitionName}' must own exactly one native memory, found ${memories.size}",
        depth.sourceLocation
      )
    }
    retainDepth(memories.head, depth)
  }

'''
    if anchor not in text:
        raise SystemExit('retainDepth anchor not found after Increment 37 apply')
    text = text.replace(anchor, helper + anchor, 1)
memory_path.write_text(text)

stream_path = root / 'lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala'
stream = stream_path.read_text()
stream = stream.replace('import scala.collection.mutable.ArrayBuffer\n\n', '')
stream = stream.replace('import spinal.core.internals._\n', '')
old = '''    val memories = ArrayBuffer.empty[Mem[_]]
    fifo.dslBody.walkDeclarations {
      case memory: Mem[_] => memories += memory
      case _              =>
    }
    val distinct = memories.distinct.toVector
    if (distinct.size != 1) {
      throw new IllegalArgumentException(
        s"a parameterized-depth StreamFifo must retain exactly one native Mem, found ${distinct.size}"
      )
    }
    ParameterizedMemory.retainDepth(distinct.head, depth)
'''
new = '''    ParameterizedMemory.retainSingleDepth(fifo, depth)
'''
if old in stream:
    stream = stream.replace(old, new, 1)
elif 'retainSingleDepth' not in stream:
    raise SystemExit('StreamFifo depth attachment body was not found')
stream_path.write_text(stream)
