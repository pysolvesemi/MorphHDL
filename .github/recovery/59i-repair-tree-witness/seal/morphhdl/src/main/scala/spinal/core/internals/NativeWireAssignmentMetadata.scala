package spinal.core.internals

import java.util.IdentityHashMap

import spinal.core._

/** External publication records own native identities as well as RTL values.
  * A wire rewrite must leave those identities intact until the owning lowering
  * validates its carrier and operation journal. This query never uses names,
  * source positions, concrete widths or emitted text to discover ownership.
  */
object NativeWireAssignmentMetadata {
  /** These final compiler-owned tags contain only immutable elaboration-integer
    * expressions and optional source locations, with no native RTL identities.
    * Keep the classification closed: arbitrary tags can hide references.
    */
  def isReferenceFreeTag(tag: SpinalTag): Boolean = tag match {
    case _: ParameterizedMemoryTag | _: ParameterizedMemoryDepthOverrideTag => true
    case _ => false
  }

  def retains(alias: BaseType): Boolean = {
    if (alias == null || alias.component == null) return true

    val visited = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    def once(value: AnyRef)(body: => Boolean): Boolean =
      if (visited.put(value, java.lang.Boolean.TRUE) != null) false else body

    def expressionUses(value: Expression): Boolean = {
      var found = false
      value.walkExpression {
        case leaf: BaseType if leaf eq alias => found = true
        case _ =>
      }
      found
    }

    // Only the listed compiler-owned case classes have a complete Product
    // contract here. Unknown records fail closed instead of introspecting an
    // arbitrary object and overlooking references hidden outside its fields.
    def recordUses(value: Product with AnyRef): Boolean = once(value) {
      value.productIterator.exists(uses)
    }
    def uses(value: Any): Boolean = value match {
      case null => false
      case leaf: BaseType => leaf eq alias
      case data: Data => data.flatten.exists(_ eq alias)
      case expression: Expression => once(expression) { expressionUses(expression) }
      case assignment: DataAssignmentStatement => once(assignment) {
        uses(assignment.target) || uses(assignment.source)
      }
      case statement: Statement => once(statement) {
        var found = false
        statement.walkDrivingExpressions {
          case leaf: BaseType if leaf eq alias => found = true
          case _ =>
        }
        found
      }
      case value: ParameterizedVecStaticIndex => recordUses(value)
      case value: ParameterizedVecStaticWrite => recordUses(value)
      case value: ParameterizedVecReadSelect => recordUses(value)
      case value: ParameterizedVecDynamicAccess => recordUses(value)
      case value: ParameterizedVecDynamicWrite => recordUses(value)
      case value: ParameterizedVecWriteCondition => recordUses(value)
      case value: ParameterizedVecDynamicWriteGuard => recordUses(value)
      case value: ParameterizedVecWriteInvocation => recordUses(value)
      case value: ParameterizedVecForwardedStaticWrite => recordUses(value)
      case value: ParameterizedVecForwardedDynamicWriteGuard => recordUses(value)
      case value: ParameterizedVecForwardedDynamicWrite => recordUses(value)
      case value: ParameterizedVecWholeAssignment => recordUses(value)
      case value: ParameterizedVecPackedRead => recordUses(value)
      case value: ParameterizedVecPackedSlice => recordUses(value)
      case value: ParameterizedVecPackedAssignment => recordUses(value)
      case value: ParameterizedVecPackedSourceAlias => recordUses(value)
      case value: ParameterizedVecAutoConnect => recordUses(value)
      case value: Option[_] => value.exists(uses)
      case value: scala.collection.Seq[_] => value.exists(uses)
      case _: String | _: Int | _: Boolean => false
      case _ => true
    }

    var root = alias.component
    while (root.parent != null) root = root.parent
    var retained = false
    root.walkComponents { component =>
      ParameterizedVec.retainedVectorsOf(component).foreach { vector =>
        if (uses(vector) || ParameterizedVec.operationsOf(vector).exists(uses) ||
            ParameterizedVec.writeInvocationsOf(vector).exists(uses)) retained = true
      }
    }
    retained
  }
}
