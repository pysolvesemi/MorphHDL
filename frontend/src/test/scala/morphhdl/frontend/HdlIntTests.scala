package morphhdl.frontend

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.collection.JavaConverters._

import morphhdl.frontend.ParamRtlFrontend.integerParameter
import morphhdl.paramrtl.BoolExpr.{
  Equal,
  GreaterThan,
  GreaterThanOrEqual,
  LessThan,
  LessThanOrEqual,
  NotEqual
}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Add, Divide, Literal, Max, Min, Modulo, Multiply, Negate, ParameterRef, Subtract}
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

  test("retains exact concrete witnesses and symbolic provenance for every integer operator") {
    val left = HdlInt.param("LEFT", default = 17, min = -64, max = 64)
    val right = HdlInt.param("RIGHT", default = 5, min = 1, max = 16)

    val expressions = Vector(
      left + right -> (BigInt(22), Add(ParameterRef("LEFT"), ParameterRef("RIGHT"))),
      left - right -> (BigInt(12), Subtract(ParameterRef("LEFT"), ParameterRef("RIGHT"))),
      left * right -> (BigInt(85), Multiply(ParameterRef("LEFT"), ParameterRef("RIGHT"))),
      left / right -> (BigInt(3), Divide(ParameterRef("LEFT"), ParameterRef("RIGHT"))),
      left % right -> (BigInt(2), Modulo(ParameterRef("LEFT"), ParameterRef("RIGHT"))),
      left.min(right) -> (BigInt(5), Min(ParameterRef("LEFT"), ParameterRef("RIGHT"))),
      left.max(right) -> (BigInt(17), Max(ParameterRef("LEFT"), ParameterRef("RIGHT"))),
      -left -> (BigInt(-17), Negate(ParameterRef("LEFT")))
    )

    expressions.foreach { case (actual, (witness, expression)) =>
      assert(actual.witness == witness)
      assert(actual.expression == expression)
      assert(actual.parameters == left.parameters ++ right.parameters || actual.parameters == left.parameters)
    }
  }

  test("keeps Int operands in the symbolic domain on both sides") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val expressions: Vector[HdlInt] = Vector(
      width + 3,
      3 + width,
      width - 3,
      20 - width,
      width * 3,
      3 * width,
      width / 2,
      32 / width,
      width % 3,
      19 % width,
      width.min(3),
      width.max(12)
    )

    assert(
      expressions.map(_.witness) ==
        Vector[BigInt](11, 11, 5, 12, 24, 24, 4, 4, 2, 3, 3, 12)
    )
    assert(expressions.map(_.expression) == Vector(
      Add(ParameterRef("WIDTH"), Literal(3)),
      Add(Literal(3), ParameterRef("WIDTH")),
      Subtract(ParameterRef("WIDTH"), Literal(3)),
      Subtract(Literal(20), ParameterRef("WIDTH")),
      Multiply(ParameterRef("WIDTH"), Literal(3)),
      Multiply(Literal(3), ParameterRef("WIDTH")),
      Divide(ParameterRef("WIDTH"), Literal(2)),
      Divide(Literal(32), ParameterRef("WIDTH")),
      Modulo(ParameterRef("WIDTH"), Literal(3)),
      Modulo(Literal(19), ParameterRef("WIDTH")),
      Min(ParameterRef("WIDTH"), Literal(3)),
      Max(ParameterRef("WIDTH"), Literal(12))
    ))
  }

  test("retains exact witnesses and expression trees for every integer comparison direction") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val comparisons = Vector(
      (width < 10) -> (true, LessThan(ParameterRef("WIDTH"), Literal(10))),
      (7 < width) -> (true, LessThan(Literal(7), ParameterRef("WIDTH"))),
      (width <= 8) -> (true, LessThanOrEqual(ParameterRef("WIDTH"), Literal(8))),
      (8 <= width) -> (true, LessThanOrEqual(Literal(8), ParameterRef("WIDTH"))),
      (width > 4) -> (true, GreaterThan(ParameterRef("WIDTH"), Literal(4))),
      (10 > width) -> (true, GreaterThan(Literal(10), ParameterRef("WIDTH"))),
      (width >= 8) -> (true, GreaterThanOrEqual(ParameterRef("WIDTH"), Literal(8))),
      (8 >= width) -> (true, GreaterThanOrEqual(Literal(8), ParameterRef("WIDTH"))),
      width.hdlEq(8) -> (true, Equal(ParameterRef("WIDTH"), Literal(8))),
      8.hdlEq(width) -> (true, Equal(Literal(8), ParameterRef("WIDTH"))),
      width.hdlNe(9) -> (true, NotEqual(ParameterRef("WIDTH"), Literal(9))),
      9.hdlNe(width) -> (true, NotEqual(Literal(9), ParameterRef("WIDTH")))
    )

    comparisons.foreach { case (actual, (expectedWitness, expectedExpression)) =>
      assert(actual.witness == expectedWitness)
      assert(actual.expression == expectedExpression)
      assert(actual.integerParameters == width.parameters)
      assert(actual.localParameters.isEmpty)
    }
  }

  test("computes comparison witnesses as mathematical BigInts") {
    val aboveLong = BigInt(Long.MaxValue) + 100
    val value = HdlInt.literal(aboveLong)

    assert((value > HdlInt.literal(BigInt(Long.MaxValue))).witness)
    assert(value.hdlEq(HdlInt.literal(aboveLong)).witness)
    assert(!value.hdlNe(HdlInt.literal(aboveLong)).witness)
  }

  test("rejects a zero concrete divisor witness at the operator source") {
    val zero = HdlInt.param("ZERO", default = 0, min = 0, max = 1)
    val divideLine = sourcecode.Line() + 1
    val divide = intercept[FrontendException](HdlInt.literal(12) / zero)
    val moduloLine = sourcecode.Line() + 1
    val modulo = intercept[FrontendException](HdlInt.literal(12) % zero)

    assert(divide.code == "MORPH-FRONTEND-DIVISOR-WITNESS-ZERO")
    assert(divide.origin.line == divideLine)
    assert(modulo.code == "MORPH-FRONTEND-DIVISOR-WITNESS-ZERO")
    assert(modulo.origin.line == moduloLine)
    assert(divide.suggestion.contains("full domain excludes zero"))
  }

  test("retains caller source origins on symbolic values") {
    val declarationLine = sourcecode.Line() + 1
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
    val width = HdlInt.param("DATA_WIDTH", default = 8, min = 1, max = 1024)
    val multiplicationLine = sourcecode.Line() + 1
    val product = lanes * width
    val comparisonLine = sourcecode.Line() + 1
    val compared = product >= width

    assert(lanes.origin.file.endsWith("HdlIntTests.scala"))
    assert(lanes.origin.line == declarationLine)
    assert(product.origin.file.endsWith("HdlIntTests.scala"))
    assert(product.origin.line == multiplicationLine)
    assert(compared.origin.file.endsWith("HdlIntTests.scala"))
    assert(compared.origin.line == comparisonLine)
  }

  test("fails closed for forward HdlInt equality and inequality") {
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
    val other = HdlInt.literal(4)
    val expectedSuggestion =
      "Use hdlEq/hdlNe for HdlInt equality, or use a static Scala condition for unsupported symbolic equality."

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

  test("retains a direct public parameter through the SpinalHDL bit-count bridge") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val bitCount = width.bits

    assert(bitCount.value == 8)
    assert(
      bitCount.parameter.contains(
        spinal.core.ElaborationIntegerParameter(
          "WIDTH",
          default = 8,
          minimum = 1,
          maximum = 64
        )
      )
    )
  }

  test("keeps ordinary SpinalHDL Int bit-count syntax unambiguous") {
    import spinal.core.{assert => _, _}

    val ordinary: BitCount = 8 bits
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val symbolic: ParameterizedBitCount = width bits

    assert(ordinary == BitCount(8))
    assert(symbolic.value == 8)
    assert(symbolic.parameter.exists(_.name == "WIDTH"))
  }

  test("an HdlInt literal config emits an ordinary concrete UInt component") {
    import spinal.core.{assert => _, _}

    final case class Config(width: HdlInt)
    val directory = Files.createTempDirectory("morphhdl-hdlint-literal-width-")
    try {
      val report = SpinalVerilog(
        SpinalConfig(
          targetDirectory = directory.toString,
          headerWithRepoHash = false,
          withTimescale = false,
          printFilelist = false
        )
      ) {
        val config = Config(8)
        new Component {
          setDefinitionName("LiteralWidthWire")
          val din = in(UInt(config.width bits))
          val dout = out(UInt(config.width bits))
          dout := din
        }
      }

      val verilog = new String(
        Files.readAllBytes(java.nio.file.Paths.get(report.generatedSourcesPaths.head)),
        StandardCharsets.UTF_8
      )
      assert(ParameterizedWidth.parametersOf(report.toplevel).isEmpty)
      assert(verilog.contains("input  wire [7:0]    din"))
      assert(verilog.contains("output wire [7:0]    dout"))
      assert(!verilog.contains("parameter integer"))
    } finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }

  test("the Increment 29 bit-count bridge rejects derived symbolic widths") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val useLine = sourcecode.Line() + 1
    val error = intercept[FrontendException] { (width + 1).bits }

    assert(error.code == "MORPH-FRONTEND-SPINAL-WIDTH-NOT-DIRECT-PARAMETER")
    assert(error.detail.contains("unmodified HdlInt.param"))
    assert(error.origin.line == useLine)
    assert(error.sourceLocation.endsWith(s"HdlIntTests.scala:$useLine"))
  }

  test("the SpinalHDL bit-count bridge requires a positive non-empty bounded domain") {
    val nonpositive = intercept[FrontendException] {
      HdlInt.param("WIDTH", default = 0, min = 0, max = 64).bits
    }
    val empty = intercept[FrontendException] {
      HdlInt.param("WIDTH", default = 8, min = 9, max = 8).bits
    }
    val invalidDefault = intercept[FrontendException] {
      HdlInt.param("WIDTH", default = 8, min = 9, max = 64).bits
    }
    val tooLarge = intercept[FrontendException] {
      HdlInt.param(
        "WIDTH",
        default = 8,
        min = 1,
        max = BigInt(Int.MaxValue) + 1
      ).bits
    }

    assert(nonpositive.code == "MORPH-FRONTEND-SPINAL-WIDTH-DOMAIN-NONPOSITIVE")
    assert(empty.code == "MORPH-FRONTEND-SPINAL-WIDTH-DOMAIN-NONPOSITIVE")
    assert(invalidDefault.code == "MORPH-FRONTEND-SPINAL-WIDTH-DEFAULT-INVALID")
    assert(tooLarge.code == "MORPH-FRONTEND-SPINAL-WIDTH-DOMAIN-TOO-LARGE")
  }

  test("the SpinalHDL bit-count bridge rejects invalid parameter names without throwing NPE") {
    val values = Vector(null, "bad-name")

    values.foreach { name =>
      val error = intercept[FrontendException] {
        HdlInt.param(name, default = 8, min = 1, max = 64).bits
      }
      assert(error.code == "MORPH-FRONTEND-SPINAL-WIDTH-NAME-INVALID")
    }
  }
}
