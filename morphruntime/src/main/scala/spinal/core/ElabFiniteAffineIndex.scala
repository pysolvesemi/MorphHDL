package spinal.core

/** Syntax adapter only: all bounds, guard and lineage proofs remain native. */
private[core] final class ElabFiniteAffineIndex(
    private[core] val index: ElabFiniteIndex, private[core] val coefficient: Int, private[core] val offset: Int) extends ElabScopedIndex {
  require(index != null && coefficient > 0 && offset >= 0,
    "scoped affine selectors require a positive coefficient and nonnegative offset")

  def downto(low: ElabScopedIndex): ElabScopedRange = {
    val bottom = low match {
      case value: ElabFiniteIndex => new ElabFiniteAffineIndex(value, 1, 0)
      case value: ElabFiniteAffineIndex => value
      case _ => throw new IllegalArgumentException("SPINAL-SCOPED-RANGE-IDENTITY: unsupported lower bound")
    }
    require((bottom.index eq index) && bottom.coefficient == coefficient && offset >= bottom.offset,
      "SPINAL-SCOPED-RANGE-IDENTITY: bounds require the same loop and stride, with positive width")
    val width = Math.addExact(Math.subtractExact(offset,bottom.offset),1)
    new ElabScopedRange {
      private[core] def select(source: Bits): Bits = TypedFinitePackedAccess.range(index,source,
        ElabInt.literal(coefficient),ElabInt.literal(bottom.offset),ElabInt.literal(width))
    }
  }

  private[core] def runtimeUInt: UInt = {
    require(BigInt(coefficient) * index.expression.maximum + offset <= BigInt(Int.MaxValue),
      "SPINAL-SCOPED-INDEX-VALUE-OVERFLOW: affine comparison exceeds the nonnegative Int domain")
    val width = (BigInt(coefficient) * index.expression.maximum + offset).bitLength.max(1)
    ((index.uint(width bits) * U(coefficient, width bits)) + U(offset, width bits)).resize(width)
  }

  private[core] def selectBit(source: Bits): Bool = {
    if (!ParameterizedStructure.captureEnabled || index.expression.generateIndex.isEmpty)
      return source(Math.addExact(Math.multiplyExact(index.witness, coefficient), offset))
    ParameterizedStructure.requireActiveFiniteIndex(index)
    require(source.component eq Component.current, "SPINAL-SCOPED-BIT-OWNER-MISMATCH: source must belong to the loop component")
    NativeWidthProvenance.widthOf(source).getOrElse(
      throw new IllegalArgumentException("SPINAL-SCOPED-BIT-WIDTH-MISSING: source has no authenticated geometry"))
    TypedFinitePackedAccess.bit(index, source, coefficient, offset)
  }

  private[core] def selectPacked(source: Bits, width: BitCount): Bits =
    TypedFinitePackedAccess.range(index, source, ElabInt.literal(coefficient),
      ElabInt.literal(offset), ElabInt.literal(width.value))

  def *(width: ElabInt): ElabScopedWordOffset =
    new ElabFiniteWordOffset(index, width * coefficient, width * offset)

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
