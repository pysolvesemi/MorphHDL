package spinal.core
import spinal.core.internals._

/** Native driver edges for exact whole-leaf loop writes. They are consumed
  * only after publication validates the retained write and carrier identities. */
private[core] object TypedLoopVecBridges {
  final case class Bridge(value: BaseType, alias: BaseType, assignment: DataAssignmentStatement)
  private def fail(): Nothing = ParameterizedVerilogException.fail(
    "SPINAL-TYPED-LOOP-VEC-WRITER-MISMATCH", "typed Vec carrier lost its sole exact loop writer", None)
  def retain(component: Component, loop: ParameterizedStructure.StructuralFor): Unit = {
    loop.body.vecIndices.foreach { selection =>
      selection.unitRange.filter(range => range.offset > 0 && range.coversTail).foreach { range =>
        selection.result.flatten.zipWithIndex.foreach { case (alias, ordinal) =>
          val writes = loop.body.assignments.filter(_.finalTarget eq alias)
          if (writes.nonEmpty && selection.registerStorage.isEmpty && !selection.vector.vec.exists(_.asInstanceOf[Data].flatten.exists(_.isIo))) {
            if (!writes.forall(_.target eq alias)) fail()
            val values = selection.vector.vec.drop(range.offset).map(_.asInstanceOf[Data].flatten(ordinal))
            if (values.exists(value => value.isReg || value.isIo || (value.component ne component))) fail()
            values.foreach { value => if (!value.dlcIsEmpty) fail() }
            values.foreach { value =>
              value.removeTag(allowFloating)
              val assignment = DataAssignmentStatement(value, alias)
              component.dslBody.append(assignment)
              selection.coverageBridges :+= Bridge(value, alias, assignment)
              loop.body.assignments :+= assignment
              loop.body.statements :+= assignment
            }
          }
        }
      }
    }
  }
  def validate(bridge: Bridge, body: ParameterizedStructuralBlock,
      live: java.util.IdentityHashMap[Statement, java.lang.Boolean]): Unit = {
    if (!live.containsKey(bridge.value) || !live.containsKey(bridge.assignment) ||
        (bridge.assignment.target ne bridge.value) || (bridge.assignment.source ne bridge.alias) ||
        !bridge.value.hasOnlyOneStatement || (bridge.value.head ne bridge.assignment) ||
        !body.assignments.exists(_ eq bridge.assignment)) fail()
  }
}
