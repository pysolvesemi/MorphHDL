package spinal.lib

import spinal.core._

/** Retains a bounded public depth on one ordinary Spinal StreamFifo. */
private[lib] object ParameterizedStreamFifoDepth {
  def attach[T <: Data](
      fifo: StreamFifo[T],
      depth: ParameterizedMemoryDepth
  ): StreamFifo[T] = {
    ParameterizedMemory.retainSingleDepth(fifo, depth)
    fifo
  }
}
