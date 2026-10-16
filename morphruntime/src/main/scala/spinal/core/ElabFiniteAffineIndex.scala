package spinal.core

/** Syntax adapter only: all bounds, guard and lineage proofs remain native. */
private[core] final class ElabFiniteAffineIndex(
    index: ElabFiniteIndex, coefficient: Int, offset: Int) extends ElabScopedIndex {
  require(index != null && coefficient > 0 && offset >= 0,
    "scoped affine selectors require a positive coefficient and nonnegative offset")

  private[core] def selectBit(source: Bits): Bool = {
    if (!ParameterizedStructure.captureEnabled || index.expression.generateIndex.isEmpty)
      return source(Math.addExact(Math.multiplyExact(index.witness, coefficient), offset))
    ParameterizedStructure.requireActiveFiniteIndex(index)
    require(source.component eq Component.current, "SPINAL-SCOPED-BIT-OWNER-MISMATCH: source must belong to the loop component")
    val width=NativeWidthProvenance.widthOf(source).getOrElse(
      throw new IllegalArgumentException("SPINAL-SCOPED-BIT-WIDTH-MISSING: source has no authenticated geometry"))
    val view=Vec(Bool(),ElabInt.fromExpression(width))
    view.assignFromBits(source)
    select(view)
  }

  private def requireWordOffset(): Unit =
    if (offset != 0) ParameterizedVerilogException.fail("SPINAL-SCOPED-WORD-OFFSET-UNSUPPORTED",
      "ordinary packed word access currently requires an unshifted index times its stride", index.expression.sourceLocation)

  private[core] def selectPacked(source: Bits, width: BitCount): Bits = {
    requireWordOffset()
    index.packed(source, coefficient, width)
  }
  def *(width: ElabInt): ElabScopedWordOffset = {
    requireWordOffset()
    new ElabFiniteWordOffset(index, width * coefficient)
  }

  def +(amount: Int): ElabScopedIndex =
    new ElabFiniteAffineIndex(index, coefficient, Math.addExact(offset, amount))

  def *(factor: Int): ElabScopedIndex =
    new ElabFiniteAffineIndex(index, Math.multiplyExact(coefficient, factor), Math.multiplyExact(offset, factor))

  def elabEq(value: Int): ElabScopedCondition = new ElabScopedCondition {
    private[core] def capture(role: String, emptyTrue: Boolean, emptyFalse: Boolean)(ifTrue: => Unit)(ifFalse: => Unit): Unit = {
      if (ParameterizedStructure.captureEnabled && index.expression.generateIndex.nonEmpty)
        ParameterizedStructure.requireActiveFiniteIndex(index)
      val difference = BigInt(value) - offset
      if (difference < 0 || difference % coefficient != 0) ifFalse
      else if (!ParameterizedStructure.captureEnabled || index.expression.generateIndex.isEmpty)
        index.whenEqual((difference / coefficient).toInt, role)(ifTrue)(ifFalse)
      else ParameterizedStructure.captureFiniteIndexIf(index, (difference / coefficient).toInt, role,
        emptyElse = emptyFalse, emptyIf = emptyTrue)(ifTrue)(ifFalse)
    }
  }

  def <(bound: ElabInt): ElabScopedCondition = {
    require(bound != null, "scoped affine bound must not be null")
    new ElabScopedCondition {
      private[core] def capture(role: String, emptyTrue: Boolean, emptyFalse: Boolean)(ifTrue: => Unit)(ifFalse: => Unit): Unit =
        index.whenAffineLessThan(coefficient, offset, bound, role, emptyTrue, emptyFalse)(ifTrue)(ifFalse)
    }
  }

  private[core] def select[T <: Data](vector: Vec[T]): T =
    if (coefficient == 1 && offset == 0) index.select(vector)
    else if (coefficient == 1) index.at(vector, offset)
    else index.affine(vector, coefficient, offset)
}
