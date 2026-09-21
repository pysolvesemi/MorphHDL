package spinal.core

/** Test-only access to the compiler-owned geometry marker. Adding it never
  * weakens an independent user dontSimplifyIt request on the same carrier.
  */
object CdcWireGeometryControl {
  def retainedAndProtected[T <: BaseType](value: T): T = {
    ParameterizedExpressionCarrier.retain(value)
    value.dontSimplifyIt()
    value
  }
}
