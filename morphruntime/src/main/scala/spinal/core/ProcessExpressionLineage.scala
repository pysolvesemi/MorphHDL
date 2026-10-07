package spinal.core

import java.util.IdentityHashMap
import spinal.core.internals._

/** Exact dependency identity through removal of owner-local combinational aliases.
  * Only edges observed at capture may substitute for an alias. Names, equivalent
  * widths, newly attached drivers and foreign declarations grant no authority.
  */
private[spinal] object ProcessExpressionLineage {
  final class Evidence private[ProcessExpressionLineage] (
      original: Expression, owner: Component, facts: Any,
      children: Vector[Evidence], alias: Option[(BaseType, DataAssignmentStatement, Evidence)]) {
    def explain(current: Expression): String = {
      if (current == null) "null expression"
      else alias match {
        case Some((leaf, driver, source)) =>
          s"alias ${leaf.getClass.getSimpleName} owner=${leaf.component eq owner} facts=${fingerprint(leaf) == facts} " +
            s"single=${leaf.hasOnlyOneStatement} driver=${leaf.head eq driver} target=${driver.target eq leaf} scope=${driver.parentScope eq owner.dslBody}; " + source.explain(driver.source)
        case None =>
          s"${original.getClass.getSimpleName}->${current.getClass.getSimpleName} identity=${current eq original} facts=${fingerprint(current) == facts}:" +
            children.zip(directChildren(current)).filterNot { case (e,v) => e.accepts(v) }.map {case(e,v)=>e.explain(v)}.mkString(";")
      }
    }
    def accepts(current: Expression): Boolean = {
      if (current == null) false
      else alias match {
        case Some((leaf, driver, source)) =>
          val intact = (leaf.component eq owner) && fingerprint(leaf) == facts &&
            leaf.hasOnlyOneStatement && (leaf.head eq driver) &&
            (driver.target eq leaf) && (driver.parentScope eq owner.dslBody) && source.accepts(driver.source)
          val pruned = leaf.component == null && !leaf.isReg && !leaf.isIo && leaf.head == null && driver.parentScope == null &&
            (driver.target eq leaf) && source.accepts(driver.source)
          (intact && ((current eq original) || source.accepts(current))) ||
            (pruned && (current ne original) && source.accepts(current))
        case None =>
          (current eq original) && fingerprint(current) == facts && {
            val now = directChildren(current)
            now.size == children.size && children.zip(now).forall { case (expected, value) => expected.accepts(value) }
          }
      }
    }
  }
  private def directChildren(value: Expression): Vector[Expression] = {
    val values = Vector.newBuilder[Expression]
    value.foreachExpression(values += _)
    values.result()
  }
  private def fingerprint(value: Expression): Any = value match {
    case leaf: BaseType => (leaf.getClass, leaf.getBitsWidth, leaf.isReg, leaf.component)
    case literal: BitVectorLiteral => (literal.getClass, literal.value, literal.poisonMask,
      if (literal.hasSpecifiedBitCount) literal.bitCount else -1)
    case literal: BoolLiteral => literal.value
    case slice: BitVectorRangedAccessFixed => (slice.getClass, slice.hi, slice.lo)
    case _ => value.getClass
  }
  def capture(value: Expression, owner: Component): Evidence = {
    val visiting = new IdentityHashMap[Expression, java.lang.Boolean]()
    def visit(node: Expression): Evidence = {
      require(node != null && !visiting.containsKey(node), "cyclic process expression lineage")
      visiting.put(node, true)
      val alias = node match {
        case leaf: BaseType if !leaf.isIo && !leaf.isReg && (leaf.component eq owner) && leaf.hasOnlyOneStatement =>
          leaf.head match {
            case driver: DataAssignmentStatement if (driver.target eq leaf) && (driver.parentScope eq owner.dslBody) =>
              Some((leaf, driver, visit(driver.source)))
            case _ => None
          }
        case _ => None
      }
      val result = new Evidence(node, owner, fingerprint(node), directChildren(node).map(visit), alias)
      visiting.remove(node)
      result
    }
    visit(value)
  }
}
