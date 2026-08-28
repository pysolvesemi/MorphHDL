package morphhdl.frontend

import spinal.core._
import spinal.lib.{
  Counter => NativeCounter,
  Flow => NativeFlow,
  Stream => NativeStream,
  StreamFifo => NativeStreamFifo,
  StreamFifoCC => NativeStreamFifoCC
}

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
    spinal.lib.StreamFifo(dataType, depth.toParameterizedMemoryDepth(file, line))

  def apply[T <: Data](
      dataType: HardType[T],
      depth: ParameterizedMemoryDepth
  )(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): NativeStreamFifo[T] =
    spinal.lib.StreamFifo(dataType, depth)
}

/**
  * MorphHDL-owned construction boundary for the untouched native
  * `spinal.lib.StreamFifoCC` implementation.
  *
  * Only the checked concrete witness crosses the native `depth: Int` API. The
  * returned object is exactly `spinal.lib.StreamFifoCC[T]`; MorphHDL retains the
  * formal/actual binding externally and does not provide another FIFO class.
  */
object StreamFifoCC {
  def apply[T <: Data](
      dataType: HardType[T],
      depth: HdlInt,
      pushClock: ClockDomain,
      popClock: ClockDomain,
      withPopBufferedReset: Boolean = ClockDomain.crossClockBufferPushToPopResetGen.get
  )(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): NativeStreamFifoCC[T] = {
    val origin = SourceOrigin.capture
    val retained = HdlInt.nativeIntExpression(
      depth,
      "native StreamFifoCC depth",
      origin
    )
    if (retained.minimum < 2) {
      FrontendException.failAt(
        "MORPH-FRONTEND-NATIVE-STREAMFIFOCC-DEPTH-DOMAIN-INVALID",
        s"native StreamFifoCC depth '${retained.verilog}' must be at least 2 over its complete domain, received [${retained.minimum}, ${retained.maximum}]",
        origin
      )
    }
    val witness = retained.default
    if (witness <= 0 || (witness & (witness - 1)) != 0) {
      FrontendException.failAt(
        "MORPH-FRONTEND-NATIVE-STREAMFIFOCC-DEPTH-WITNESS-NOT-POWER-OF-TWO",
        s"native StreamFifoCC concrete depth witness $witness must be a power of two",
        origin
      )
    }

    formalComponent.parameter(
      actual = depth,
      name = "DEPTH",
      minimum = retained.minimum,
      maximum = retained.maximum
    )(
      value =>
        new spinal.lib.StreamFifoCC(
          dataType,
          value,
          pushClock,
          popClock,
          withPopBufferedReset
        )
    )
  }
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
