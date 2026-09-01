package morphhdl.frontend

import spinal.core._
import spinal.lib.{Flow => NativeFlow, Stream => NativeStream, StreamFifo => NativeStreamFifo}

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
