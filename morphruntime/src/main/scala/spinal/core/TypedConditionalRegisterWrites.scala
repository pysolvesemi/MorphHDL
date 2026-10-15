package spinal.core

import spinal.core.internals._

/** A complete static alternative chooses a next value, not another register.
  * Normalize only two exact unconditional whole-leaf writes to storage declared
  * in their enclosing capture. The ordinary graph checks validate the resulting
  * combinational join and the unchanged single register/reset process.
  */
private[core] object TypedConditionalRegisterWrites {
  def normalize(component: Component, whenTrue: ParameterizedStructuralBlock,
      whenFalse: ParameterizedStructuralBlock, locallyDeclared: BaseType => Boolean): Unit = {
    val current = DslScopeStack.get
    val candidates = whenTrue.assignments.map(_.finalTarget).distinct.filter { target =>
      target.isReg && target.isDirectionLess && (target.component eq component) &&
        (target.parentScope eq current) && locallyDeclared(target) &&
        !whenTrue.declarations.exists(_ eq target) && !whenFalse.declarations.exists(_ eq target)
    }
    candidates.foreach { target =>
      val left = whenTrue.assignments.filter(_.finalTarget eq target)
      val right = whenFalse.assignments.filter(_.finalTarget eq target)
      val live = scala.collection.mutable.ArrayBuffer.empty[DataAssignmentStatement]
      target.foreachStatements { case a: DataAssignmentStatement => live += a; case _ => }
      val writes = left ++ right
      if(left.size == 1 && right.size == 1 && writes.forall(a =>
          (a.target eq target) && (a.parentScope eq current)) &&
          live.size == 2 && live.forall(a => writes.exists(_ eq a))) {
        val next = ParameterizedWidth.cloneOf(target).setAsComb()
        next.setCompositeName(target, "next", weak = true)
        writes.foreach { assignment =>
          assignment.dlcRemove()
          assignment.target = next
          next.dlcAppend(assignment)
        }
        current.append(DataAssignmentStatement(target,next))
      }
    }
  }
}
