package morphhdl.paramrtl

import morphhdl.paramrtl.BoolExpr.{
  And,
  Equal,
  GreaterThan,
  GreaterThanOrEqual,
  LessThan,
  LessThanOrEqual,
  Literal,
  Not,
  NotEqual,
  Or,
  ParameterRef
}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{
  Add,
  Divide,
  Literal => IntLiteral,
  LocalParameterRef,
  ParameterRef => IntParameterRef,
  Select
}
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

  test("evaluates all six integer comparisons over exact public, local, and arithmetic defaults") {
    val integerParameters = parameterFacts(IntegerParameter(
      "WIDTH",
      default = 8,
      constraints = Vector(MinInclusive(1), MaxInclusive(32))
    ))
    val localParameters = Map(
      "LIMIT" -> facts(Add(IntParameterRef("WIDTH"), IntLiteral(2)), integerParameters)
    )
    val expressions = Vector(
      LessThan(IntParameterRef("WIDTH"), LocalParameterRef("LIMIT")) -> true,
      LessThanOrEqual(IntParameterRef("WIDTH"), IntLiteral(8)) -> true,
      GreaterThan(LocalParameterRef("LIMIT"), IntParameterRef("WIDTH")) -> true,
      GreaterThanOrEqual(IntParameterRef("WIDTH"), IntLiteral(9)) -> false,
      Equal(Add(IntParameterRef("WIDTH"), IntLiteral(2)), LocalParameterRef("LIMIT")) -> true,
      NotEqual(IntParameterRef("WIDTH"), LocalParameterRef("LIMIT")) -> true
    )

    expressions.foreach { case (expression, expected) =>
      assert(
        BoolExpressionAnalysis.evaluateDefault(expression, Map.empty, integerParameters, localParameters) ==
          Right(expected)
      )
    }
  }

  test("uses mathematical BigInt equality without target-width truncation") {
    val huge = BigInt(1) << 200
    val expression = Equal(Add(IntLiteral(huge), IntLiteral(1)), IntLiteral(huge + 1))

    assert(BoolExpressionAnalysis.evaluateDefault(expression, Map.empty) == Right(true))
  }

  test("preserves shared integer-name correlation across composed comparisons") {
    val integerParameters = parameterFacts(IntegerParameter(
      "P",
      default = 10,
      constraints = Vector(MinInclusive(0), MaxInclusive(20))
    ))
    val impossible = And(
      LessThan(IntParameterRef("P"), IntLiteral(10)),
      GreaterThanOrEqual(IntParameterRef("P"), IntLiteral(10))
    )

    assert(
      BoolExpressionAnalysis.evaluateDefault(impossible, Map.empty, integerParameters, Map.empty) == Right(false)
    )
  }

  test("analyzes comparison operands before Boolean short-circuiting") {
    val denominator = IntExprFacts(
      defaultValue = 1,
      interval = IntInterval(Some(BigInt(0)), Some(BigInt(2)))
    )
    val unsafe = LessThan(Divide(IntLiteral(8), IntParameterRef("D")), IntLiteral(10))
    val expressions = Vector(And(Literal(false), unsafe), Or(Literal(true), unsafe))

    expressions.foreach { expression =>
      BoolExpressionAnalysis.evaluateDefault(expression, Map.empty, Map("D" -> denominator), Map.empty) match {
        case Left(BoolExpressionFailure.InvalidIntegerExpression(
              IntExpressionFailure.DivisorMayBeZero("/", _)
            )) =>
        case other => fail(s"Expected eager divisor failure, got $other")
      }
    }
  }

  test("analyzes deeply nested selection conditions without repeated exponential work") {
    val depth = 48
    val nested = (1 to depth).foldLeft[IntExpr](IntLiteral(0)) { (previous, value) =>
      Select(
        Equal(previous, IntLiteral(value - 1)),
        IntLiteral(value),
        IntLiteral(-value)
      )
    }

    assert(BoolExpressionAnalysis.evaluateDefault(Equal(nested, IntLiteral(depth)), Map.empty) == Right(true))
  }

  test("reports unresolved integer operands and collects typed references") {
    val expression = And(
      LessThan(IntParameterRef("MISSING"), LocalParameterRef("LIMIT")),
      GreaterThan(IntParameterRef("OTHER"), IntLiteral(0))
    )

    assert(BoolExpressionAnalysis.integerParameterReferences(expression) == Vector("MISSING", "OTHER"))
    assert(BoolExpressionAnalysis.localParameterReferences(expression) == Vector("LIMIT"))
    assert(
      BoolExpressionAnalysis.evaluateDefault(expression, Map.empty, Map.empty, Map.empty) ==
        Left(BoolExpressionFailure.InvalidIntegerExpression(
          IntExpressionFailure.UnresolvedParameter("MISSING")
        ))
    )
  }

  private def parameterFacts(parameter: IntegerParameter): Map[String, IntExprFacts] =
    IntExpressionAnalysis.parameterFacts(parameter) match {
      case Some(value) => Map(parameter.name -> value)
      case None        => fail(s"Invalid test parameter $parameter")
    }

  private def facts(expression: IntExpr, parameters: Map[String, IntExprFacts]): IntExprFacts =
    IntExpressionAnalysis.analyze(expression, parameters, Map.empty) match {
      case Right(value) => value
      case Left(failure) => fail(s"Invalid test expression: $failure")
    }
}
