package spinal.core

/** Dependency-neutral interface for a lexically scoped elaboration index.
  * Vec delegates without extracting a concrete integer or hardware selector.
  */
abstract class ElabScopedIndex private[core] () {
  private[core] def select[T <: Data](vector: Vec[T]): T
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
    private[core] def capture(role: String)(ifTrue: => Unit)(ifFalse: => Unit): Unit =
      self.capture(role)(ifFalse)(ifTrue)
  }
  private[core] def capture(role: String)(ifTrue: => Unit)(ifFalse: => Unit): Unit
}
