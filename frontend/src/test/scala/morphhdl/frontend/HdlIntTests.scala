package morphhdl.frontend

import morphhdl.frontend.ParamRtlFrontend.integerParameter
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Literal, Multiply, ParameterRef}
import morphhdl.paramrtl.IntegerParameter
import org.scalatest.funsuite.AnyFunSuite

class HdlIntTests extends AnyFunSuite {
  test("converts Int to HdlInt without changing ordinary Int ranges") {
    final case class Config(lanes: HdlInt)

    val config = Config(4)
    val ordinary: Range = 0 until 4

    assert(config.lanes.witness == 4)
    assert(config.lanes.expression == Literal(4))
    assert(ordinary == Range(0, 4))
  }

  test("retains concrete and symbolic multiplication") {
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
    val width = HdlInt.param("DATA_WIDTH", default = 8, min = 1, max = 1024)
    val product = lanes * width

    assert(product.witness == 32)
    assert(product.expression == Multiply(ParameterRef("LANES"), ParameterRef("DATA_WIDTH")))
  }

  test("retains caller source origins on symbolic values") {
    val declarationLine = sourcecode.Line() + 1
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
    val width = HdlInt.param("DATA_WIDTH", default = 8, min = 1, max = 1024)
    val multiplicationLine = sourcecode.Line() + 1
    val product = lanes * width

    assert(lanes.origin.file.endsWith("HdlIntTests.scala"))
    assert(lanes.origin.line == declarationLine)
    assert(product.origin.file.endsWith("HdlIntTests.scala"))
    assert(product.origin.line == multiplicationLine)
  }

  test("fails closed for forward HdlInt equality and inequality") {
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
    val other = HdlInt.literal(4)
    val expectedSuggestion =
      "Use a static Scala condition, or wait for the parameter-aware HdlBool comparison API."

    val errors = Vector(
      intercept[FrontendException](lanes == other),
      intercept[FrontendException](lanes != other),
      intercept[FrontendException](lanes == 4),
      intercept[FrontendException](lanes != 4),
      intercept[FrontendException](lanes.hashCode)
    )

    errors.foreach { error =>
      assert(error.code == "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED")
      assert(error.origin == lanes.origin)
      assert(error.sourceLocation == lanes.origin.rendered)
      assert(error.suggestion == expectedSuggestion)
      assert(error.suggestedReplacement == expectedSuggestion)
      assert(error.getMessage.contains(s"${lanes.origin.rendered}:"))
      assert(error.getMessage.contains(s"Suggested replacement: $expectedSuggestion"))
    }
  }

  test("fails closed for explicit Number conversion methods") {
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
    val error = intercept[FrontendException](lanes.intValue())

    assert(error.code == "MORPH-FRONTEND-SYMBOLIC-CONVERSION-UNSUPPORTED")
    assert(error.detail.contains("Scala Int"))
  }

  test("retains the complete public parameter declaration") {
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)

    assert(
      integerParameter(lanes).raw == IntegerParameter(
        "LANES",
        4,
        Vector(MinInclusive(1), MaxInclusive(64))
      )
    )
  }

  test("provides the documented bounded default maximum") {
    val lanes = HdlInt.param("LANES", default = 4, min = 1)

    assert(
      integerParameter(lanes).raw == IntegerParameter(
        "LANES",
        4,
        Vector(MinInclusive(1), MaxInclusive(Int.MaxValue))
      )
    )
  }

  test("does not provide an HdlInt to Int conversion") {
    assertTypeError("""
      import morphhdl.frontend._
      val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
      val value: Int = lanes
    """)
    assertTypeError("""
      import morphhdl.frontend._
      HdlInt.param("LANES", default = 4, min = 1, max = 64).toInt
    """)
  }

  test("does not allow GenIndex to index Scala collections") {
    assertTypeError("""
      import morphhdl.frontend._
      val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
      for (lane <- 0 until lanes) {
        Vector(10, 20, 30, 40)(lane)
      }
    """)
  }

  test("does not implicitly convert GenIndex into an HdlInt consumer") {
    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
      for (lane <- 0 until lanes) {
        parameterBinding("INDEX", lane)
      }
    """)
    assertTypeError("""
      import morphhdl.frontend._
      val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
      for (lane <- 0 until lanes) {
        val value: Int = lane
      }
    """)
  }

  test("does not expose collection-like generate operations") {
    assertTypeError("""
      import morphhdl.frontend._
      val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
      for (lane <- 0 until lanes if true) ()
    """)
    assertTypeError("""
      import morphhdl.frontend._
      val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
      for (lane <- 0 until lanes) yield lane
    """)
    assertTypeError("""
      import morphhdl.frontend._
      val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
      for (lane <- 0 until lanes by 2) ()
    """)
  }

  test("does not make the standard Int range accept HdlInt") {
    assertTypeError("""
      import morphhdl.frontend._
      val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
      Predef.intWrapper(0).until(lanes)
    """)
  }
}
