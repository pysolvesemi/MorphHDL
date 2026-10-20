package spinal.core

/** No geometry is recovered from a witness: the existing packed-access proof
  * receives the exact loop identity, stride expression and authored width.
  */
private[core] final class ElabFiniteWordOffset(private val index: ElabFiniteIndex, private val stride: ElabInt, private val offset: ElabInt = ElabInt.literal(0))
    extends ElabScopedWordOffset {
  require(index != null && stride != null, "scoped word offset requires an index and stride")
  private[core] def selectPacked(source: Bits, width: ParameterizedBitCount): Bits =
    TypedFinitePackedAccess.range(index,source,stride,offset,
      ElabInt.fromExpression(ParameterizedWidth.validatedWidthExpression(width).getOrElse(ElabInt.literal(width.value).expression)))
  def +(amount: ElabInt): ElabScopedWordOffset = new ElabFiniteWordOffset(index,stride,offset+amount)
  def downto(low: ElabScopedWordOffset): ElabScopedRange = low match {
    case bottom: ElabFiniteWordOffset if (bottom.index eq index) && stride.elabEq(bottom.stride).isAlwaysTrue =>
      val width=offset-bottom.offset+1
      require(width.minimum > 0, "SPINAL-SCOPED-RANGE-WIDTH: range width must remain positive")
      new ElabScopedRange {
        private[core] def select(source: Bits): Bits = TypedFinitePackedAccess.range(index,source,stride,bottom.offset,width)
      }
    case _ => throw new IllegalArgumentException("SPINAL-SCOPED-RANGE-IDENTITY: bounds require the same loop and stride")
  }
}
