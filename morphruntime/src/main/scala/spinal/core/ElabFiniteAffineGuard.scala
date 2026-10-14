package spinal.core

/** Exact (parameter value, loop iteration) truth table. Ordinals are private
  * proof coordinates, never hardware values or replacements for parameters.
  */
private[core] final class ElabFiniteAffineGuard(
    val component: Component, val index: ElabFiniteIndex,
    val coefficient: Int, val offset: Int, val bound: ElaborationIntegerExpression) {
  import ParameterizedStructure._
  val count = ElabInt.projectExpression(index.count, "affine guard count")
  private val c = ElabFiniteRange.requireCompleteSymbolicDomain(count, "affine guard count",
    "SPINAL-ELAB-FINITE-GUARD-DOMAIN-REQUIRED").getOrElse(fail("symbolic loop count required"))
  private val b = ElabFiniteRange.requireCompleteSymbolicDomain(bound, "affine guard bound",
    "SPINAL-ELAB-FINITE-GUARD-DOMAIN-REQUIRED").getOrElse(fail("symbolic bound required"))
  require(coefficient > 0 && offset >= 0, "affine guard requires positive stride and nonnegative offset")
  if ((c._1.root ne b._1.root) || c._2 != b._2 || count.parameters != bound.parameters)
    fail("loop count and bound must retain the same exact parameter domain")
  private val counts = c._2.toVector.sorted.map { value =>
    val n = c._1.evaluate(value).get
    if(n < 0 || n > ElaborationExactDomain.MaximumDomainSize) fail("invalid finite guard count")
    value -> n
  }
  private val size = counts.map(_._2).sum
  if(size <= 0 || size > ElaborationExactDomain.MaximumDomainSize)
    fail("finite guard exceeds the exact proof budget or has no iterations")
  val rows: Vector[(BigInt, BigInt)] = counts.flatMap { case (value,n) =>
    (BigInt(0) until n).map(i => value -> i)
  }
  if(rows.exists { case (_,i) => coefficient*i+offset > Int.MaxValue })
    fail("affine guard arithmetic exceeds the Verilog integer domain")
  val universe = rows.indices.map(BigInt(_)).toSet
  val truth = universe.filter { row =>
    val (value, i) = rows(row.toInt)
    coefficient * i + offset < b._1.evaluate(value).get
  }
  val condition = ElaborationBooleanExpression(
    s"(($coefficient * ${index.expression.verilog} + $offset) < ${bound.verilog})",
    offset < bound.default, bound.parameters, bound.sourceLocation,
    parameterRoots = bound.completedParameterRoots)
  private val representative = rows.indexWhere { case (value,i) =>
    i == 0 && c._1.evaluate(value).contains(count.default) && b._1.evaluate(value).contains(bound.default)
  }
  if(representative < 0) fail("affine guard lost its correlated construction representative")
  private val root = new StructuralPredicateRoot(index.expression.verilog, representative, 0, rows.size-1, bound.parameters)
  val domain = StructuralPredicateDomain(root, universe, truth)

  def authentic(branch: StructuralIf): Boolean = {
    def contains(regions: Vector[StructuralRegion]): Boolean = regions.exists(r =>
      (r eq branch) || r.blocks.exists(b => contains(b.regions)))
    def owner(regions: Vector[StructuralRegion]): Boolean = regions.exists {
      case loop: StructuralFor if loop.finiteIndexToken.exists(_ eq index.token) =>
        loop.indexName == index.expression.verilog &&
          ElabFiniteAffineGuard.sameCapturedCount(loop.count, index.count) && contains(loop.body.regions)
      case region => region.blocks.exists(b => owner(b.regions))
    }
    branch.predicateDomain.exists(_ eq domain) &&
      index.expression.default == 0 && index.expression.minimum == 0 &&
      index.expression.maximum == index.count.maximum-1 && index.expression.parameters.isEmpty &&
      index.expression.generateIndex.contains(index.expression.verilog) &&
      branch.condition.verilog == condition.verilog && branch.condition.default == condition.default &&
      branch.condition.parameters == condition.parameters &&
      branch.condition.completedParameterRoots == condition.completedParameterRoots &&
      owner(regionsOf(component))
  }

  def allowed(positive: Boolean): Set[(BigInt, BigInt)] =
    (if(positive) truth else universe -- truth).map(row => rows(row.toInt))

  private def fail(message: String): Nothing = ParameterizedVerilogException.fail(
    "SPINAL-ELAB-FINITE-GUARD-DOMAIN-REQUIRED", message, bound.sourceLocation)
}

private[core] object ElabFiniteAffineGuard {
  /** Replay immutable captured tables, never reopen the ambient authoring scope. */
  def sameCapturedCount(a: ElaborationIntegerExpression, b: ElaborationIntegerExpression): Boolean =
    a.parameters == b.parameters && a.generateIndex == b.generateIndex &&
      a.completedParameterRoots == b.completedParameterRoots &&
      ((a.exactDomain,b.exactDomain) match {
        case (Some(x),Some(y)) => (x.root eq y.root) && x.evidenceValues == y.evidenceValues &&
          x.evidenceValues.forall(v => x.evaluate(v) == y.evaluate(v))
        case _ => false
      })
}

/** One read's exact guard path and native block identity, retained until replay. */
private[core] final class ElabFiniteGuardedAffineRead(
    val index: ElabFiniteIndex, val vector: Vec[_], val depth: ElaborationIntegerExpression,
    val count: ElaborationIntegerExpression, val selector: ElaborationIntegerExpression,
    val coefficient: Int, val offset: Int,
    val frames: Vector[(ElabFiniteAffineGuard, Boolean)], val capture: Long,
    val component: Component) {
  private val first = frames.head._1
  private val originalDepth = ParameterizedVec.shapeOf(vector).get.depth
  private val permitted = frames.foldLeft(first.rows.toSet) { case (rows, (guard, positive)) =>
    if(guard.rows != first.rows) Set.empty[(BigInt, BigInt)] else rows intersect guard.allowed(positive)
  }
  private val domain = depth.exactDomain
  val bounded: Boolean = permitted.nonEmpty && domain.exists(d =>
    first.count.exactDomain.exists(c => c.root eq d.root) &&
      permitted.forall { case (value, i) => d.evaluate(value).exists(n => coefficient*i+offset < n) })
  val maximum: BigInt = if(permitted.isEmpty) BigInt(-1) else permitted.map { case (_,i) => coefficient*i+offset }.max

  def matches(actualVector: Vec[_], actualSelector: ElaborationIntegerExpression,
      token: ElabFiniteIndexToken, ownerCount: Option[ElaborationIntegerExpression]): Boolean =
    bounded && (actualVector eq vector) && (actualSelector eq selector) && (token eq index.token) &&
      ParameterizedVec.shapeOf(vector).exists(_.depth eq originalDepth) &&
      ownerCount.forall(ElabFiniteAffineGuard.sameCapturedCount(_, index.count)) &&
      selector.verilog == s"($coefficient * ${index.expression.verilog} + $offset)" &&
      selector.generateIndex == index.expression.generateIndex && selector.parameters.isEmpty &&
      selector.default == offset && selector.minimum == offset && selector.maximum == maximum

  def owns(block: ParameterizedStructuralBlock): Boolean = {
    if(!ParameterizedStructure.previousIndexOwner(component, capture, block)) return false
    def visit(current: ParameterizedStructuralBlock,
        path: Vector[(ElabFiniteAffineGuard, Boolean)]): Vector[Vector[(ElabFiniteAffineGuard, Boolean)]] = {
      val here = if(current eq block) Vector(path) else Vector.empty
      here ++ current.regions.flatMap {
        case branch: ParameterizedStructure.StructuralIf =>
          frames.map(_._1).find(g => branch.predicateDomain.exists(_ eq g.domain)) match {
            case Some(g) if g.authentic(branch) =>
              visit(branch.whenTrue,path :+ (g -> true)) ++ visit(branch.whenFalse,path :+ (g -> false))
            case Some(_) => Vector.empty
            case None => branch.blocks.flatMap(b => visit(b,path))
          }
        case region => region.blocks.flatMap(b => visit(b,path))
      }
    }
    ParameterizedStructure.regionsOf(component).flatMap(_.blocks).flatMap(b => visit(b,Vector.empty)) == Vector(frames)
  }
}
