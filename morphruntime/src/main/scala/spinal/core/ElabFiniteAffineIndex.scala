package spinal.core

/** Syntax adapter only: all bounds, guard and lineage proofs remain native. */
private[core] final class ElabFiniteAffineIndex(
    index: ElabFiniteIndex, coefficient: Int, offset: Int) extends ElabScopedIndex {
  require(index != null && coefficient > 0 && offset >= 0,
    "scoped affine selectors require a positive coefficient and nonnegative offset")

  def +(amount: Int): ElabScopedIndex =
    new ElabFiniteAffineIndex(index, coefficient, Math.addExact(offset, amount))

  def *(factor: Int): ElabScopedIndex =
    new ElabFiniteAffineIndex(index, Math.multiplyExact(coefficient, factor), Math.multiplyExact(offset, factor))

  def elabEq(value: Int): ElabScopedCondition = new ElabScopedCondition {
    private[core] def capture(role: String)(ifTrue: => Unit)(ifFalse: => Unit): Unit = {
      if (ParameterizedStructure.captureEnabled && index.expression.generateIndex.nonEmpty)
        ParameterizedStructure.requireActiveFiniteIndex(index)
      val difference = BigInt(value) - offset
      if (difference < 0 || difference % coefficient != 0) ifFalse
      else index.whenEqual((difference / coefficient).toInt, role)(ifTrue)(ifFalse)
    }
  }

  def <(bound: ElabInt): ElabScopedCondition = {
    require(bound != null, "scoped affine bound must not be null")
    new ElabScopedCondition {
      private[core] def capture(role: String)(ifTrue: => Unit)(ifFalse: => Unit): Unit =
        index.whenAffineLessThan(coefficient, offset, bound, role)(ifTrue)(ifFalse)
    }
  }

  private[core] def select[T <: Data](vector: Vec[T]): T =
    if (coefficient == 1 && offset == 0) index(vector)
    else if (coefficient == 1) index.at(vector, offset)
    else index.affine(vector, coefficient, offset)
}
