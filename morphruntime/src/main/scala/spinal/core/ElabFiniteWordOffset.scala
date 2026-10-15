package spinal.core

/** No geometry is recovered from a witness: the existing packed-access proof
  * receives the exact loop identity, stride expression and authored width.
  */
private[core] final class ElabFiniteWordOffset(index: ElabFiniteIndex, stride: ElabInt)
    extends ElabScopedWordOffset {
  require(index != null && stride != null, "scoped word offset requires an index and stride")
  private[core] def selectPacked(source: Bits, width: ParameterizedBitCount): Bits =
    index.packed(source, stride, width)
}
