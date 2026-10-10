package spinal.core

/** Parameter-constant access to a native Vec, without a hardware index mux. */
object ElabVec {
  /** Select one scalar leaf using an authenticated elaboration expression.
    * The index must be in range for every admitted parameter value. The result
    * preserves Bool/Bits/UInt/SInt type and symbolic width. Hardware UInt
    * indexing continues to use the ordinary Vec API.
    */
  def select[T <: BaseType](vector: Vec[T], index: ElabInt): T =
    TypedVecStaticSelect(vector, index)
}
