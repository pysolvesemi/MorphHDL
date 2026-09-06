package spinal.core.internals

import spinal.core._

/** Exact native scope ancestry for a protected publication anchor. */
private[internals] final class NativePublicationScope private (
    component: Component,
    ancestry: Vector[(ScopeStatement, TreeStatement)]
) {
  def matches(scope: ScopeStatement): Boolean =
    ancestry.nonEmpty && (ancestry.head._1 eq scope) &&
      ancestry.zipWithIndex.forall { case ((current, parent), index) =>
        (current.component eq component) && (current.parentStatement eq parent) &&
          (if (index == ancestry.size - 1) current eq component.dslBody
           else parent != null && (parent.parentScope eq ancestry(index + 1)._1))
      }
}

private[internals] object NativePublicationScope {
  def capture(component: Component, scope: ScopeStatement): NativePublicationScope = {
    val known = new java.util.IdentityHashMap[ScopeStatement, java.lang.Boolean]()
    val ancestry = scala.collection.mutable.ArrayBuffer.empty[(ScopeStatement, TreeStatement)]
    var current = scope
    while (current != null && !known.containsKey(current)) {
      known.put(current, java.lang.Boolean.TRUE)
      if (current.component ne component)
        throw new ParameterizedVerilogException(
          "SPINAL-PARAMETERIZED-VERILOG-PUBLICATION-SCOPE-MISMATCH",
          "native publication scope belongs to another component")
      ancestry += current -> current.parentStatement
      if (current eq component.dslBody)
        return new NativePublicationScope(component, ancestry.toVector)
      current = if (current.parentStatement == null) null else current.parentStatement.parentScope
    }
    throw new ParameterizedVerilogException(
      "SPINAL-PARAMETERIZED-VERILOG-PUBLICATION-SCOPE-MISMATCH",
      "native publication scope is detached or cyclic")
  }
}
