package spinal.core.internals

import spinal.core.{ElabBool, ElabInt, ParameterizedVec, Vec}

/** Publication geometry, not a second reduction implementation. The native
  * helper remains responsible for invoking the operator and level bridge.
  * No callback, native RTL construction, signal name or witness reconstruction
  * is allowed here. Element widths remain on the independent Vec leaf shapes.
  */
private[spinal] final case class TypedBalancedReductionStage(
    level: Int,
    inputCount: ElabInt,
    pairCount: ElabInt,
    outputCount: ElabInt,
    active: ElabBool,
    hasOddTail: ElabBool
)

private[spinal] final case class TypedBalancedReductionPlan(
    count: ElabInt,
    resultDepth: ElabInt,
    stages: Vector[TypedBalancedReductionStage]
)

private[spinal] object TypedBalancedReductionPlan {
  private val DomainCode = "MORPH-REDUCE-BALANCED-DOMAIN-INVALID"

  /** Retain the exact object-owned Vec count before any collection conversion. */
  def forVec(vector: Vec[_]): Option[TypedBalancedReductionPlan] = {
    require(vector != null, s"$DomainCode: Vec must not be null")
    ParameterizedVec.shapeOf(vector).map { shape =>
      apply(ElabInt.fromExpression(shape.depth))
    }
  }

  def apply(count: ElabInt): TypedBalancedReductionPlan = {
    require(count != null, s"$DomainCode: count must not be null")
    // Validate the untouched declaration authority before accepting a projected
    // branch domain. A copied summary/default is not count evidence.
    val expression = count.authoritativeProjectedExpression(
      "reduceBalancedTree element count",
      DomainCode,
      requireProjectedExactExtrema = true
    )
    require(
      expression.minimum >= 1 && expression.maximum >= expression.minimum &&
        expression.maximum <= BigInt(Int.MaxValue),
      s"$DomainCode: count must have a finite, non-empty positive Int domain"
    )
    val logicalCount = ElabInt.fromExpression(expression)
    val maximumLevels = (expression.maximum - 1).bitLength
    val stages = Vector.tabulate(maximumLevels) { level =>
      // Derive each stage directly from the original count. Repeated symbolic
      // ceil-halving would duplicate the previous expression exponentially.
      // Subtraction is safe because the entire count domain was proved positive.
      val inputs = if (level == 0) logicalCount
        else (logicalCount - 1) / ElabInt.fromBigInt(BigInt(1) << level) + 1
      val pairs = inputs / 2
      val remainder = inputs % 2
      val active = inputs > 1
      TypedBalancedReductionStage(
        level = level,
        inputCount = inputs,
        pairCount = pairs,
        // Positive-domain ceil-halving is monotonic and overflow-safe. Unlike
        // pairs + remainder it also keeps exact interval extrema: their maxima
        // need not occur at the same admitted count when the upper bound is even.
        outputCount = (inputs - 1) / 2 + 1,
        active = active,
        hasOddTail = active && remainder.elabEq(1)
      )
    }
    TypedBalancedReductionPlan(logicalCount, logicalCount.log2Up, stages)
  }
}
