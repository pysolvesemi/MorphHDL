package morphhdl.frontend

import spinal.core._
import spinal.lib.{Counter => NativeCounter, Flow => NativeFlow, Stream => NativeStream, StreamFifo => NativeStreamFifo}

/** External MorphHDL adapters that return and execute ordinary SpinalHDL library objects. */
object Counter {
  def apply(width: ParameterizedBitCount): NativeCounter =
    spinal.lib.ExternalParameterizedCounterRegistry.create(width)

  def apply(width: ParameterizedBitCount, increment: Bool): NativeCounter = {
    val counter = apply(width)
    when(increment) { counter.increment() }
    counter
  }

  def apply(width: BitCount): NativeCounter = spinal.lib.Counter(width)
  def apply(width: BitCount, increment: Bool): NativeCounter =
    spinal.lib.Counter(width, increment)
  def apply(stateCount: BigInt): NativeCounter = spinal.lib.Counter(stateCount)
  def apply(stateCount: BigInt, increment: Bool): NativeCounter =
    spinal.lib.Counter(stateCount, increment)
  def apply(start: BigInt, end: BigInt): NativeCounter =
    spinal.lib.Counter(start, end)
  def apply(start: BigInt, end: BigInt, increment: Bool): NativeCounter =
    spinal.lib.Counter(start, end, increment)
  def apply(range: Range): NativeCounter = spinal.lib.Counter(range)
  def apply(range: Range, increment: Bool): NativeCounter =
    spinal.lib.Counter(range, increment)
  def apply(time: TimeNumber): NativeCounter = spinal.lib.Counter(time)
  def apply(time: TimeNumber, increment: Bool): NativeCounter =
    spinal.lib.Counter(time, increment)

  def down(stateCount: BigInt): NativeCounter = spinal.lib.Counter.down(stateCount)
  def both(stateCount: BigInt): NativeCounter = spinal.lib.Counter.both(stateCount)
}

object CounterFreeRun {
  def apply(width: ParameterizedBitCount): NativeCounter = Counter(width).freeRun()
  def apply(width: BitCount): NativeCounter = spinal.lib.CounterFreeRun(width)
  def apply(stateCount: BigInt): NativeCounter = spinal.lib.CounterFreeRun(stateCount)
}

object Stream {
  def apply[T <: Data](payloadType: HardType[T]): NativeStream[T] = {
    val stream = spinal.lib.Stream(payloadType)
    ExternalParameterizedHardTypeShape.attach(stream.payload, payloadType)
    stream
  }

  def apply[T <: Data](payloadType: => T): NativeStream[T] =
    apply(ExternalParameterizedHardTypeRegistry.create(payloadType))
}

object StreamFifo {
  def apply[T <: Data](
      dataType: HardType[T],
      depth: HdlInt
  )(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): NativeStreamFifo[T] =
    spinal.lib.StreamFifo(dataType, depth.asElabInt)
}

object Flow {
  def apply[T <: Data](payloadType: HardType[T]): NativeFlow[T] = {
    val flow = spinal.lib.Flow(payloadType)
    ExternalParameterizedHardTypeShape.attach(flow.payload, payloadType)
    flow
  }

  def apply[T <: Data](payloadType: => T): NativeFlow[T] =
    apply(ExternalParameterizedHardTypeRegistry.create(payloadType))
}
