package morphhdl

import org.scalatest.funsuite.AnyFunSuite

import spinal.core.{ElabBool, ElabInt}

class TypedElaborationValueTests extends AnyFunSuite {
  test("typed equality retains a symbolic Boolean instead of erasing to Scala Boolean") {
    val depth = ElabInt.parameter(
      name = "DEPTH",
      default = 5,
      minimum = 1,
      maximum = 8,
      sourceLocation = Some("TypedElaborationValueTests.scala:10")
    )

    val predicate: ElabBool = depth == 1

    assert(!predicate.witness)
    assert(predicate.constant.isEmpty)
    assert(predicate.expression.verilog.contains("DEPTH"))
    assert(predicate.expression.verilog.contains("=="))
    assert(predicate.expression.parameters.map(_.name) == Vector("DEPTH"))
  }

  test("literal typed values remain parameter-free and explicitly extractable") {
    val literal = ElabInt.literal(8)
    val derived = (literal + 4) / 3

    assert(derived.isConstant)
    assert(derived.constantWitness("literal geometry") == 4)
    assert(derived.expression.parameters.isEmpty)
    assert(derived.bits.expression.isEmpty)
  }

  test("StreamWidthAdapter downsize domain proves relation and factor without branch replay") {
    val inputWidth = ElabInt.parameter(
      "INPUT_WIDTH",
      default = 12,
      minimum = 9,
      maximum = 16
    )
    val outputWidth = ElabInt.literal(8)

    val equal: ElabBool = inputWidth == outputWidth
    val downsize = inputWidth > outputWidth
    val factor = (inputWidth + outputWidth - 1) / outputWidth

    assert(equal.constant.contains(false))
    assert(downsize.constant.contains(true))
    assert(factor.isConstant)
    assert(factor.constantWitness("downsize factor") == 2)
    assert(factor.expression.parameters.isEmpty)
  }

  test("StreamWidthAdapter upsize domain proves relation and factor without branch replay") {
    val inputWidth = ElabInt.literal(8)
    val outputWidth = ElabInt.parameter(
      "OUTPUT_WIDTH",
      default = 12,
      minimum = 9,
      maximum = 16
    )

    val equal: ElabBool = inputWidth == outputWidth
    val downsize = inputWidth > outputWidth
    val factor = (outputWidth + inputWidth - 1) / inputWidth

    assert(equal.constant.contains(false))
    assert(downsize.constant.contains(false))
    assert(factor.isConstant)
    assert(factor.constantWitness("upsize factor") == 2)
    assert(factor.expression.parameters.isEmpty)
  }

  test("typed Boolean operations retain bounded constant classification") {
    val width = ElabInt.parameter("WIDTH", default = 12, minimum = 9, maximum = 16)
    val alwaysWide = width > 8
    val neverSmall = width < 8

    assert(alwaysWide.constant.contains(true))
    assert(neverSmall.constant.contains(false))
    assert((alwaysWide && !neverSmall).constant.contains(true))
    assert((neverSmall || ElabBool.literal(false)).constant.contains(false))
  }

  test("symbolic-to-Int conversion is deliberately unavailable") {
    assertDoesNotCompile(
      """
        |val depth = spinal.core.ElabInt.parameter(
        |  "DEPTH",
        |  default = 5,
        |  minimum = 1,
        |  maximum = 8
        |)
        |val erased: Int = depth
        |""".stripMargin
    )
  }

  test("conflicting declarations with the same parameter name fail closed") {
    val left = ElabInt.parameter("WIDTH", default = 8, minimum = 1, maximum = 16)
    val right = ElabInt.parameter("WIDTH", default = 8, minimum = 4, maximum = 32)

    val error = intercept[IllegalArgumentException] {
      left + right
    }
    assert(error.getMessage.contains("conflicting declarations"))
  }
}
