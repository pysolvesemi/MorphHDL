package morphhdl.frontend

import spinal.core._
import spinal.lib.{Flow => NativeFlow, Stream => NativeStream, StreamFifo => NativeStreamFifo}

@deprecated("Compatibility-only alias; use spinal.lib.Stream directly", "Increment 58")
object Stream {
  def apply[T <: Data](payloadType: HardType[T]): NativeStream[T] =
    spinal.lib.Stream(payloadType)

  def apply[T <: Data](payloadType: => T): NativeStream[T] =
    apply(ParameterizedWidth.HardType(payloadType))
}

@deprecated("Compatibility-only alias; use spinal.lib.StreamFifo typed/native overloads directly", "Increment 58")
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

@deprecated("Compatibility-only alias; use spinal.lib.Flow directly", "Increment 58")
object Flow {
  def apply[T <: Data](payloadType: HardType[T]): NativeFlow[T] =
    spinal.lib.Flow(payloadType)

  def apply[T <: Data](payloadType: => T): NativeFlow[T] =
    apply(ParameterizedWidth.HardType(payloadType))
}
