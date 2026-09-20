package morphhdl.paramrtl

import morphhdl.paramrtl.IntExpr.{Add, Literal, LocalParameterRef, Max, Min, ParameterRef}
import org.scalatest.funsuite.AnyFunSuite

class MinMaxExpressionTests extends AnyFunSuite {
  test("computes exact defaults and bounded min/max intervals") {
    val parameters = Map(
      "LEFT" -> IntExprFacts(7, IntInterval(Some(-5), Some(10))),
      "RIGHT" -> IntExprFacts(3, IntInterval(Some(2), Some(20)))
    )

    assert(
      analyze(Min(ParameterRef("LEFT"), ParameterRef("RIGHT")), parameters) ==
        Right(IntExprFacts(3, IntInterval(Some(-5), Some(10))))
    )
    assert(
      analyze(Max(ParameterRef("LEFT"), ParameterRef("RIGHT")), parameters) ==
        Right(IntExprFacts(7, IntInterval(Some(2), Some(20))))
    )
  }

  test("retains every sound one-sided extremum bound") {
    val parameters = Map(
      "UPPER_ONLY" -> IntExprFacts(8, IntInterval(None, Some(12))),
      "LOWER_ONLY" -> IntExprFacts(5, IntInterval(Some(2), None))
    )

    assert(
      analyze(Min(ParameterRef("UPPER_ONLY"), ParameterRef("LOWER_ONLY")), parameters) ==
        Right(IntExprFacts(5, IntInterval(None, Some(12))))
    )
    assert(
      analyze(Max(ParameterRef("UPPER_ONLY"), ParameterRef("LOWER_ONLY")), parameters) ==
        Right(IntExprFacts(8, IntInterval(Some(2), None)))
    )
  }

  test("discovers both operands and preserves Min/Max through substitution") {
    val expression = Min(
      ParameterRef("PUBLIC"),
      Max(LocalParameterRef("LOCAL"), ParameterRef("FALLBACK"))
    )

    assert(IntExpressionAnalysis.parameterReferences(expression) == Vector("PUBLIC", "FALLBACK"))
    assert(IntExpressionAnalysis.localParameterReferences(expression) == Vector("LOCAL"))
    assert(
      IntExpressionEquivalence.substitute(
        expression,
        Map("PUBLIC" -> Literal(8), "FALLBACK" -> Literal(2)),
        Map("LOCAL" -> Literal(5))
      ) == Min(Literal(8), Max(Literal(5), Literal(2)))
    )
  }

  test("normalizes extrema by literal folding, idempotence, and commutative order") {
    val a = Add(ParameterRef("A"), Literal(1))
    val b = Add(ParameterRef("B"), Literal(1))

    assert(IntExpressionEquivalence.equivalent(Min(Literal(8), Literal(3)), Literal(3)))
    assert(IntExpressionEquivalence.equivalent(Max(Literal(8), Literal(3)), Literal(8)))
    assert(IntExpressionEquivalence.equivalent(Min(a, a), a))
    assert(IntExpressionEquivalence.equivalent(Max(b, b), b))
    assert(IntExpressionEquivalence.equivalent(Min(a, b), Min(b, a)))
    assert(IntExpressionEquivalence.equivalent(Max(a, b), Max(b, a)))
    assert(!IntExpressionEquivalence.equivalent(Min(a, b), Max(a, b)))
  }

  test("analyzes and compares deep alternating extrema without consuming the call stack") {
    def alternating(): IntExpr = {
      var expression: IntExpr = ParameterRef("BASE")
      (1 to 900).foreach { value =>
        expression =
          if ((value & 1) == 0) Min(expression, Literal(value))
          else Max(expression, Literal(-value))
      }
      expression
    }

    val parameters = Map("BASE" -> IntExprFacts(4, IntInterval(Some(1), Some(8))))
    val left = alternating()
    val right = alternating()

    assert(analyze(left, parameters).isRight)
    assert(IntExpressionEquivalence.equivalent(left, right))
  }

  test("proves deep directly swapped Min and Max operands before the normalization budget") {
    def deep(base: String): IntExpr = {
      var expression: IntExpr = ParameterRef(base)
      (1 to 1200).foreach { value => expression = Add(expression, Literal(value)) }
      expression
    }

    val minLeft = Min(deep("A"), deep("B"))
    val minRight = Min(deep("B"), deep("A"))
    val maxLeft = Max(deep("A"), deep("B"))
    val maxRight = Max(deep("B"), deep("A"))

    assert(IntExpressionEquivalence.equivalent(minLeft, minRight))
    assert(IntExpressionEquivalence.equivalent(maxLeft, maxRight))

    val value = deep("IDEMPOTENT")
    val minimum = Min(deep("IDEMPOTENT"), deep("IDEMPOTENT"))
    val maximum = Max(deep("IDEMPOTENT"), deep("IDEMPOTENT"))
    assert(IntExpressionEquivalence.equivalent(minimum, value))
    assert(IntExpressionEquivalence.equivalent(value, minimum))
    assert(IntExpressionEquivalence.equivalent(maximum, value))
    assert(IntExpressionEquivalence.equivalent(value, maximum))
  }

  private def analyze(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts]
  ): Either[IntExpressionFailure, IntExprFacts] =
    IntExpressionAnalysis.analyze(expression, parameters, Map.empty)
}
