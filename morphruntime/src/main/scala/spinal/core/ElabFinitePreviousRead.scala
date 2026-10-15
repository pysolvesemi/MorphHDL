package spinal.core

/** Immutable evidence for one guarded backwards edge, independent of carrier names. */
private[core] final class ElabFinitePreviousRead(
    vector: Vec[_], selector: ElaborationIntegerExpression, index: ElabFiniteIndex,
    distance: Int, admitted: Set[BigInt], capture: Long, component: Component,
    depth: ElaborationIntegerExpression) {
  def envelope(base: ElaborationIntegerExpression, count: ElaborationIntegerExpression,
      originalDepth: ElaborationIntegerExpression, replayDepth: ElaborationIntegerExpression,
      roots: Set[BigInt], coefficient: Int, offset: Int): Boolean =
    (base eq index.expression) && (count eq index.count) && (originalDepth eq depth) &&
      (replayDepth eq depth) && roots.isEmpty && coefficient == 1 && offset == -distance

  def matches(actualVector: Vec[_], actualSelector: ElaborationIntegerExpression,
      token: ElabFiniteIndexToken, ownerCount: Option[ElaborationIntegerExpression]): Boolean =
    (actualVector eq vector) && (actualSelector eq selector) && (token eq index.token) &&
      (vector.component eq component) && distance > 0 &&
      index.expression.generateIndex.contains(index.expression.verilog) && index.expression.parameters.isEmpty &&
      index.expression.default == 0 && index.expression.minimum == 0 && index.expression.maximum == index.count.maximum - 1 &&
      admitted.forall(i => i >= distance && i < index.count.maximum) &&
      ParameterizedVec.shapeOf(vector).exists(_.depth eq depth) &&
      ElabFiniteRange.equivalentLogicalCount(depth, index.count) &&
      ownerCount.forall(ElabFiniteRange.equivalentLogicalCount(_, index.count)) &&
      selector.verilog == s"(${index.expression.verilog} - $distance)" &&
      selector.generateIndex == index.expression.generateIndex &&
      selector.default == (if (admitted.isEmpty) BigInt(0) else admitted.min - distance) &&
      selector.minimum == selector.default &&
      selector.maximum == (if (admitted.isEmpty) BigInt(0) else admitted.max - distance)

  def owns(block: ParameterizedStructuralBlock): Boolean = {
    val root = ParameterizedStructure.finitePredicateRoot(component, index.token)
    val universe = (BigInt(0) until index.count.maximum).toSet
    def visit(current: ParameterizedStructuralBlock, allowed: Set[BigInt]): Vector[Set[BigInt]] = {
      val here = if (current eq block) Vector(allowed) else Vector.empty
      here ++ current.regions.flatMap {
        case branch: ParameterizedStructure.StructuralIf if branch.predicateDomain.exists(d => root.exists(_ eq d.root)) =>
          val domain = branch.predicateDomain.get
          if (!ParameterizedStructure.authenticatedFinitePredicate(component, branch)) Vector.empty
          else visit(branch.whenTrue, allowed intersect domain.whenTrue) ++
            visit(branch.whenFalse, allowed intersect (domain.universe -- domain.whenTrue))
        case region => region.blocks.flatMap(b => visit(b, allowed))
      }
    }
    def loops(regions: Vector[ParameterizedStructure.StructuralRegion]): Vector[Set[BigInt]] = regions.flatMap {
      case loop: ParameterizedStructure.StructuralFor if loop.finiteIndexToken.exists(_ eq index.token) => visit(loop.body, universe)
      case region => region.blocks.flatMap(b => loops(b.regions))
    }
    ParameterizedStructure.previousIndexOwner(component, capture, block) &&
      loops(ParameterizedStructure.regionsOf(component)) == Vector(admitted)
  }
}
