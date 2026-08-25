package spinal.lib

import spinal.core._

import morphhdl.frontend.{HdlInt, formalComponent}

/** MorphHDL-owned native constructor boundary for untouched StreamFifo. */
object ExternalParameterizedStreamFifoDepthRegistry {
  def create[T <: Data](
      dataType: HardType[T],
      depth: HdlInt
  )(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): StreamFifo[T] = {
    if (dataType == null)
      throw new IllegalArgumentException(
        "native StreamFifo payload HardType must not be null"
      )
    if (depth == null)
      throw new IllegalArgumentException(
        "symbolic StreamFifo depth must not be null"
      )

    formalComponent.parameter(
      actual = depth,
      name = "DEPTH",
      minimum = BigInt(1),
      maximum = BigInt(4096)
    )(
      witness => spinal.lib.StreamFifo(dataType, witness)
    )
  }
}
