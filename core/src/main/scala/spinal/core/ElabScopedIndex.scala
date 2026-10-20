package spinal.core

/** Dependency-neutral interface for a lexically scoped elaboration index.
  * Vec delegates without extracting a concrete integer or hardware selector.
  */
abstract class ElabScopedIndex private[core] () {
  private[core] def select[T <: Data](vector: Vec[T]): T
  private[core] def runtimeUInt: UInt
  private[core] final def sizedRuntimeUInt(width: BitCount): UInt = {
    val value = runtimeUInt
    require(width.value > 0 && width.value >= value.getBitsWidth,
      "SPINAL-FINITE-INDEX-VALUE-WIDTH: sized index must represent its complete unsigned domain")
    value.resize(width.value)
  }
  private[core] def selectBit(source: Bits): Bool
  private[core] def selectPacked(source: Bits, width: BitCount): Bits
  def *(width: ElabInt): ElabScopedWordOffset
  def downto(low: ElabScopedIndex): ElabScopedRange
  def -(offset: Int): ElabScopedIndex = this + Math.negateExact(offset)
  def +(offset: Int): ElabScopedIndex
  def *(coefficient: Int): ElabScopedIndex
  def elabEq(value: Int): ElabScopedCondition
  final def elabNe(value: Int): ElabScopedCondition = !elabEq(value)
  def <(bound: ElabInt): ElabScopedCondition
  final def <(bound: Int): ElabScopedCondition = this < ElabInt.literal(bound)
}

/** A predicate whose authority belongs to one retained finite loop. */
abstract class ElabScopedCondition private[core] () { self =>
  final def unary_! : ElabScopedCondition = new ElabScopedCondition {
    private[core] def capture(role: String, emptyTrue: Boolean, emptyFalse: Boolean)(ifTrue: => Unit)(ifFalse: => Unit): Unit =
      self.capture(role, emptyFalse, emptyTrue)(ifFalse)(ifTrue)
  }
  private[core] def capture(role: String, emptyTrue: Boolean = false, emptyFalse: Boolean = false)(ifTrue: => Unit)(ifFalse: => Unit): Unit
}

/** A symbolic word offset retains its original finite-loop index and stride.
  * It is not a host integer or a runtime hardware address.
  */
abstract class ElabScopedRange private[core] () {
  private[core] def select(source: Bits): Bits
}

abstract class ElabScopedWordOffset private[core] () {
  def +(offset: ElabInt): ElabScopedWordOffset
  final def +(offset: Int): ElabScopedWordOffset = this + ElabInt.literal(offset)
  final def -(offset: Int): ElabScopedWordOffset = this + ElabInt.literal(Math.negateExact(offset))
  def downto(low: ElabScopedWordOffset): ElabScopedRange
  private[core] def selectPacked(source: Bits, width: ParameterizedBitCount): Bits
}
