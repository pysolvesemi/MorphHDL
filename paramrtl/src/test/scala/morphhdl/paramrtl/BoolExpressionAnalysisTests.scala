package morphhdl.paramrtl

import morphhdl.paramrtl.BoolExpr.{And, Literal, Not, Or, ParameterRef}
import org.scalatest.funsuite.AnyFunSuite

class BoolExpressionAnalysisTests extends AnyFunSuite {
  test("evaluates literals and typed public parameter defaults") {
    val parameters = Map(
      "ENABLE" -> BooleanParameter("ENABLE", default = true),
      "BYPASS" -> BooleanParameter("BYPASS", default = false)
    )
    val expression = Or(And(ParameterRef("ENABLE"), Not(ParameterRef("BYPASS"))), Literal(false))

    assert(BoolExpressionAnalysis.evaluateDefault(expression, parameters) == Right(true))
  }

  test("preserves shared-name correlation under negation") {
    val parameter = BooleanParameter("P", default = true)
    val expression = And(ParameterRef("P"), Not(ParameterRef("P")))

    assert(BoolExpressionAnalysis.evaluateDefault(expression, Map("P" -> parameter)) == Right(false))
  }

  test("validates every reference without Boolean short-circuit") {
    val expressions = Vector(
      And(Literal(false), ParameterRef("MISSING")),
      Or(Literal(true), ParameterRef("MISSING"))
    )

    expressions.foreach { expression =>
      assert(
        BoolExpressionAnalysis.evaluateDefault(expression, Map.empty) ==
          Left(BoolExpressionFailure.UnresolvedParameter("MISSING"))
      )
    }
  }
}
