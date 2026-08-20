package spinal.lib

import spinal.core._

/**
  * MorphHDL-owned construction boundary for the retained Increment 37 depth
  * sidecar. The untouched native StreamFifo receives only the concrete witness;
  * symbolic depth metadata is attached after its ordinary algorithm elaborates.
  */
object ExternalParameterizedStreamFifoDepthRegistry {
  def create[T <: Data](
      dataType: HardType[T],
      depth: ParameterizedMemoryDepth
  ): StreamFifo[T] = {
    if (dataType == null)
      throw new IllegalArgumentException("native StreamFifo payload HardType must not be null")
    if (depth == null)
      throw new IllegalArgumentException("symbolic StreamFifo depth must not be null")
    ParameterizedStreamFifoDepth.attach(
      spinal.lib.StreamFifo(dataType, depth.value),
      depth
    )
  }
}
