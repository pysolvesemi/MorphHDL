package morphhdl.paramrtl

import morphhdl.paramrtl.IntExpr._
import org.scalatest.funsuite.AnyFunSuite

object IntExpressionAnalysisTests {
  private final case class BinaryCase(
      name: String,
      expression: IntExpr,
      leftValues: Vector[BigInt],
      rightValues: Vector[BigInt],
      evaluate: (BigInt, BigInt) => BigInt
  )
}

class IntExpressionAnalysisTests extends AnyFunSuite {
  import IntExpressionAnalysisTests.BinaryCase

  test("finite interval analysis soundly contains exhaustive arithmetic results") {
    val mixedLeft = values(-4, 3)
    val mixedRight = values(-3, 5)
    val positiveDivisors = values(1, 4)
    val negativeDivisors = values(-4, -1)

    val cases = Vector(
      BinaryCase(
        "+",
        Add(ParameterRef("LEFT"), ParameterRef("RIGHT")),
        mixedLeft,
        mixedRight,
        (left, right) => left + right
      ),
      BinaryCase(
        "-",
        Subtract(ParameterRef("LEFT"), ParameterRef("RIGHT")),
        mixedLeft,
        mixedRight,
        (left, right) => left - right
      ),
      BinaryCase(
        "*",
        Multiply(ParameterRef("LEFT"), ParameterRef("RIGHT")),
        mixedLeft,
        mixedRight,
        (left, right) => left * right
      ),
      BinaryCase(
        "/ positive",
        Divide(ParameterRef("LEFT"), ParameterRef("RIGHT")),
        mixedLeft,
        positiveDivisors,
        (left, right) => left / right
      ),
      BinaryCase(
        "/ negative",
        Divide(ParameterRef("LEFT"), ParameterRef("RIGHT")),
        mixedLeft,
        negativeDivisors,
        (left, right) => left / right
      ),
      BinaryCase(
        "% positive",
        Modulo(ParameterRef("LEFT"), ParameterRef("RIGHT")),
        mixedLeft,
        positiveDivisors,
        (left, right) => left % right
      ),
      BinaryCase(
        "% negative",
        Modulo(ParameterRef("LEFT"), ParameterRef("RIGHT")),
        mixedLeft,
        negativeDivisors,
        (left, right) => left % right
      )
    )

    cases.foreach { arithmeticCase =>
      val parameters = Map(
        "LEFT" -> facts(arithmeticCase.leftValues.head, arithmeticCase.leftValues),
        "RIGHT" -> facts(arithmeticCase.rightValues.last, arithmeticCase.rightValues)
      )
      val analyzed = analyze(arithmeticCase.expression, parameters)

      arithmeticCase.leftValues.foreach { left =>
        arithmeticCase.rightValues.foreach { right =>
          val actual = arithmeticCase.evaluate(left, right)
          assert(
            analyzed.interval.contains(actual),
            s"${arithmeticCase.name}: $left and $right produced $actual outside ${analyzed.interval}"
          )
        }
      }

      assert(
        analyzed.defaultValue ==
          arithmeticCase.evaluate(arithmeticCase.leftValues.head, arithmeticCase.rightValues.last)
      )
    }
  }

  test("negation interval contains every finite-domain result") {
    val domain = values(-7, 4)
    val analyzed = analyze(
      Negate(ParameterRef("VALUE")),
      Map("VALUE" -> facts(default = -3, domain))
    )

    domain.foreach(value => assert(analyzed.interval.contains(-value)))
    assert(analyzed.defaultValue == 3)
    assert(analyzed.interval == interval(-4, 7))
  }

  test("signed divide and modulo defaults follow truncation toward zero and dividend sign") {
    val cases = Vector(
      (BigInt(-7), BigInt(3), BigInt(-2), BigInt(-1)),
      (BigInt(7), BigInt(-3), BigInt(-2), BigInt(1)),
      (BigInt(-7), BigInt(-3), BigInt(2), BigInt(-1))
    )

    cases.foreach { case (numerator, denominator, quotient, remainder) =>
      val divideFacts = analyze(Divide(Literal(numerator), Literal(denominator)))
      val moduloFacts = analyze(Modulo(Literal(numerator), Literal(denominator)))

      assert(divideFacts == IntExprFacts(quotient, IntInterval.point(quotient)))
      assert(moduloFacts.defaultValue == remainder)
      assert(moduloFacts.interval.contains(remainder))
      assert(numerator == quotient * denominator + remainder)
    }
  }

  test("divide and modulo fail closed for a denominator interval containing zero") {
    val parameters = Map(
      "DENOMINATOR" -> IntExprFacts(1, interval(-2, 2))
    )

    Vector(
      Divide(Literal(9), ParameterRef("DENOMINATOR")) -> "/",
      Modulo(Literal(9), ParameterRef("DENOMINATOR")) -> "%"
    ).foreach { case (expression, expectedOperator) =>
      IntExpressionAnalysis.analyze(expression, parameters, Map.empty) match {
        case Left(IntExpressionFailure.DivisorMayBeZero(operator, divisorInterval)) =>
          assert(operator == expectedOperator)
          assert(divisorInterval == interval(-2, 2))
        case result => fail(s"Expected DivisorMayBeZero, got $result")
      }
    }
  }

  test("unbounded operands remain conservative instead of inventing finite bounds") {
    val unbounded = IntExprFacts(defaultValue = 2, IntInterval(None, None))
    val finite = IntExprFacts(defaultValue = 3, interval(1, 4))
    val parameters = Map("UNBOUNDED" -> unbounded, "FINITE" -> finite)

    val product = analyze(Multiply(ParameterRef("UNBOUNDED"), ParameterRef("FINITE")), parameters)
    val quotient = analyze(Divide(ParameterRef("UNBOUNDED"), ParameterRef("FINITE")), parameters)

    assert(product.defaultValue == 6)
    assert(product.interval == IntInterval(None, None))
    assert(quotient.defaultValue == 0)
    assert(quotient.interval == IntInterval(None, None))
  }

  private def analyze(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts] = Map.empty,
      localParameters: Map[String, IntExprFacts] = Map.empty
  ): IntExprFacts =
    IntExpressionAnalysis.analyze(expression, parameters, localParameters) match {
      case Right(result) => result
      case Left(failure) => fail(s"Expression analysis failed: $failure")
    }

  private def facts(default: BigInt, domain: Vector[BigInt]): IntExprFacts =
    IntExprFacts(default, interval(domain.min, domain.max))

  private def interval(lower: BigInt, upper: BigInt): IntInterval =
    IntInterval.bounded(lower, upper).get

  private def values(lower: Int, upper: Int): Vector[BigInt] =
    (lower to upper).map(BigInt(_)).toVector
}
