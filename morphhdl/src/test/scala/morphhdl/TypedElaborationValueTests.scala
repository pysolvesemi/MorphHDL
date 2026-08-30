package morphhdl

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.frontend.HdlInt
import spinal.core.{ElabBool, ElabInt, ParameterizedVerilogException}

class TypedElaborationValueTests extends AnyFunSuite {
  private def parameter(
      name: String,
      default: Int,
      minimum: Int,
      maximum: Int
  ): ElabInt =
    HdlInt.param(name, default = default, min = minimum, max = maximum).asElabInt

  test("typed equality retains a symbolic Boolean instead of erasing to Scala Boolean") {
    val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
    val predicate: ElabBool = depth.elabEq(1)
    val predicateIsSymbolic = predicate.isSymbolic
    val predicateParameters = predicate.parameters.map(_.name)
    val rendered = predicate.toString

    assert(predicateIsSymbolic)
    assert(predicateParameters == Vector("DEPTH"))
    assert(rendered.contains("DEPTH"))
    assert(rendered.contains("=="))
    assert(rendered.contains("witness=false"))
  }

  test("literal typed values remain parameter-free and explicitly extractable") {
    val literal = ElabInt.literal(8)
    val derived = (literal + 4) / 3
    val isDomainConstant = derived.isDomainConstant
    val typedWidth = derived.bits
    val witness = typedWidth.value
    val parameters = derived.parameters
    val retainedWidth = typedWidth.expression

    assert(isDomainConstant)
    assert(witness == 4)
    assert(parameters.isEmpty)
    assert(retainedWidth.isEmpty)
  }

  test("StreamWidthAdapter downsize domain proves relation and factor without branch replay") {
    val inputWidth =
      parameter("INPUT_WIDTH", default = 12, minimum = 9, maximum = 16)
    val outputWidth = ElabInt.literal(8)

    val equal: ElabBool = inputWidth.elabEq(outputWidth)
    val downsize = inputWidth > outputWidth
    val factor = (inputWidth + outputWidth - 1) / outputWidth
    val equalIsAlwaysFalse = equal.isAlwaysFalse
    val downsizeIsAlwaysTrue = downsize.isAlwaysTrue
    val factorIsDomainConstant = factor.isDomainConstant
    val factorWitness = factor.bits.value
    val factorParameters = factor.parameters.map(_.name)

    assert(equalIsAlwaysFalse)
    assert(downsizeIsAlwaysTrue)
    assert(factorIsDomainConstant)
    assert(factorWitness == 2)
    assert(factorParameters == Vector("INPUT_WIDTH"))
  }

  test("StreamWidthAdapter upsize domain proves relation and factor without branch replay") {
    val inputWidth = ElabInt.literal(8)
    val outputWidth =
      parameter("OUTPUT_WIDTH", default = 12, minimum = 9, maximum = 16)

    val equal: ElabBool = inputWidth.elabEq(outputWidth)
    val downsize = inputWidth > outputWidth
    val factor = (outputWidth + inputWidth - 1) / inputWidth
    val equalIsAlwaysFalse = equal.isAlwaysFalse
    val downsizeIsAlwaysFalse = downsize.isAlwaysFalse
    val factorIsDomainConstant = factor.isDomainConstant
    val factorWitness = factor.bits.value
    val factorParameters = factor.parameters.map(_.name)

    assert(equalIsAlwaysFalse)
    assert(downsizeIsAlwaysFalse)
    assert(factorIsDomainConstant)
    assert(factorWitness == 2)
    assert(factorParameters == Vector("OUTPUT_WIDTH"))
  }

  test("typed Boolean operations retain bounded constant classification") {
    val width = parameter("WIDTH", default = 12, minimum = 9, maximum = 16)
    val alwaysWide = width > 8
    val neverSmall = width < 8
    val wideIsAlwaysTrue = alwaysWide.isAlwaysTrue
    val smallIsAlwaysFalse = neverSmall.isAlwaysFalse
    val conjunctionIsAlwaysTrue = (alwaysWide && !neverSmall).isAlwaysTrue
    val disjunctionIsAlwaysFalse =
      (neverSmall || ElabBool.literal(false)).isAlwaysFalse

    assert(wideIsAlwaysTrue)
    assert(smallIsAlwaysFalse)
    assert(conjunctionIsAlwaysTrue)
    assert(disjunctionIsAlwaysFalse)
  }

  test("symbolic-to-Int conversion is deliberately unavailable") {
    assertDoesNotCompile(
      """
        |val depth = morphhdl.frontend.HdlInt
        |  .param("DEPTH", default = 5, min = 1, max = 8)
        |  .asElabInt
        |val erased: Int = depth
        |""".stripMargin
    )
    assertDoesNotCompile(
      """
        |val depth = morphhdl.frontend.HdlInt
        |  .param("DEPTH", default = 5, min = 1, max = 8)
        |  .asElabInt
        |val leakedWitness: Int = depth.witness
        |""".stripMargin
    )
  }

  test("conflicting declarations with the same parameter name fail closed") {
    val left = parameter("WIDTH", default = 8, minimum = 1, maximum = 16)
    val right = parameter("WIDTH", default = 8, minimum = 4, maximum = 32)

    val error = intercept[ParameterizedVerilogException] {
      left + right
    }
    assert(error.code == "SPINAL-ELAB-INT-PARAMETER-SCHEMA-CONFLICT")
  }
}
