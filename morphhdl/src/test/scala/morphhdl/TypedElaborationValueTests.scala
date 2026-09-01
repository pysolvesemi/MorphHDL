package morphhdl

import java.nio.file.Files

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.frontend.HdlInt
import spinal.core.{
  ElabBool,
  ElabInt,
  ElaborationIntegerExpression,
  ElaborationIntegerParameter,
  ElaborationIntegerParameterRoot,
  ParameterizedBitCount,
  ParameterizedWidth,
  ParameterizedVerilogException
}

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

  test("equivalent symbolic expressions classify all ordered self-comparisons") {
    val width = parameter("WIDTH", default = 12, minimum = 9, maximum = 16)
    val left = width + 1
    val equivalent = width + 1

    assert((width < width).isAlwaysFalse)
    assert((width <= width).isAlwaysTrue)
    assert((left > equivalent).isAlwaysFalse)
    assert((left >= equivalent).isAlwaysTrue)
  }

  test("ordered-expression equivalence requires exact symbolic root identity") {
    val first = parameter("WIDTH", default = 12, minimum = 9, maximum = 16)
    val independent =
      parameter("WIDTH", default = 12, minimum = 9, maximum = 16)

    val error = intercept[ParameterizedVerilogException] {
      first <= independent
    }
    assert(error.code == "SPINAL-ELAB-DOMAIN-EXACT-CORRELATION-UNSUPPORTED")
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

  test("retained parameter schemas require portable names") {
    val invalidNames = Vector(null, "", "bad-name", "9WIDTH")

    invalidNames.foreach { name =>
      val schema =
        ElaborationIntegerParameter(name, default = 8, minimum = 1, maximum = 16)
      val error = intercept[ParameterizedVerilogException] {
        expressionWith(schema)
      }
      assert(error.code == "SPINAL-ELAB-INT-PARAMETER-NAME-INVALID")
    }
  }

  test("retained parameter schemas require a valid Int witness domain") {
    val invalidSchemas = Vector(
      ElaborationIntegerParameter("WIDTH", default = 8, minimum = 9, maximum = 16),
      ElaborationIntegerParameter("WIDTH", default = 17, minimum = 1, maximum = 16),
      ElaborationIntegerParameter("WIDTH", default = 8, minimum = 16, maximum = 1),
      ElaborationIntegerParameter(
        "WIDTH",
        default = BigInt(Int.MaxValue) + 1,
        minimum = BigInt(Int.MinValue) - 1,
        maximum = BigInt(Int.MaxValue) + 1
      ),
      ElaborationIntegerParameter("WIDTH", default = null, minimum = 1, maximum = 16),
      ElaborationIntegerParameter("WIDTH", default = 8, minimum = null, maximum = 16),
      ElaborationIntegerParameter("WIDTH", default = 8, minimum = 1, maximum = null)
    )

    invalidSchemas.foreach { schema =>
      val error = intercept[ParameterizedVerilogException] {
        expressionWith(schema)
      }
      assert(error.code == "SPINAL-ELAB-INT-PARAMETER-DOMAIN-INVALID")
    }
  }

  test("neutral parameter schemas allow negative domains with wide bounds") {
    val minimum = BigInt(Int.MinValue) - 1
    val maximum = BigInt(Int.MaxValue) + 1
    val schema = ElaborationIntegerParameter(
      "SIGNED_OFFSET",
      default = -3,
      minimum = minimum,
      maximum = maximum
    )
    val value = ElabInt.fromExpression(
      ElaborationIntegerExpression(
        verilog = "SIGNED_OFFSET",
        default = -3,
        minimum = minimum,
        maximum = maximum,
        parameters = Vector(schema)
      )
    )

    assert(value.parameters == Vector(schema))
    assert(
      value.parameters.map(parameter => parameter.minimum -> parameter.maximum) ==
        Vector(minimum -> maximum)
    )
    Vector[() => BigInt](() => value.minimum, () => value.maximum).foreach {
      consume =>
        val error = intercept[ParameterizedVerilogException] {
          consume()
        }
        assert(error.code == "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING")
    }
  }

  test("null retained parameter schemas fail deterministically") {
    val error = intercept[ParameterizedVerilogException] {
      expressionWith(null)
    }
    assert(error.code == "SPINAL-ELAB-INT-PARAMETER-SCHEMA-NULL")
  }

  test("null retained parameter and root collections fail deterministically") {
    val schema =
      ElaborationIntegerParameter("WIDTH", default = 8, minimum = 1, maximum = 16)
    val nullParameters = intercept[ParameterizedVerilogException] {
      ElabInt.fromExpression(
        ElaborationIntegerExpression(
          verilog = "WIDTH",
          default = 8,
          minimum = 1,
          maximum = 16,
          parameters = null
        )
      )
    }
    val nullRoots = intercept[ParameterizedVerilogException] {
      ElabInt.fromExpression(
        ElaborationIntegerExpression(
          verilog = "WIDTH",
          default = 8,
          minimum = 1,
          maximum = 16,
          parameters = Vector(schema),
          parameterRoots = null
        )
      )
    }

    assert(nullParameters.code == "SPINAL-ELAB-INT-PARAMETER-SCHEMA-NULL")
    assert(nullRoots.code == "SPINAL-ELAB-INT-PARAMETER-ROOT-NULL")
  }

  test("null carrier option metadata fails without a raw null dereference") {
    val schema =
      ElaborationIntegerParameter("WIDTH", default = 8, minimum = 1, maximum = 16)
    val nullSource = intercept[ParameterizedVerilogException] {
      ElabInt.fromExpression(
        ElaborationIntegerExpression(
          verilog = "WIDTH",
          default = 8,
          minimum = 1,
          maximum = 16,
          parameters = Vector(schema),
          sourceLocation = null
        )
      )
    }
    val nullGenerateIndex = intercept[ParameterizedVerilogException] {
      ElabInt.fromExpression(
        ElaborationIntegerExpression(
          verilog = "WIDTH",
          default = 8,
          minimum = 1,
          maximum = 16,
          parameters = Vector(schema),
          generateIndex = null
        )
      )
    }
    val nullRootSource = intercept[ParameterizedVerilogException] {
      ElaborationIntegerParameterRoot.fresh("WIDTH", null)
    }

    assert(nullSource.code == "SPINAL-ELAB-INT-SOURCE-OPTION-NULL")
    assert(
      nullGenerateIndex.code ==
        "SPINAL-ELAB-INT-GENERATE-INDEX-OPTION-NULL"
    )
    assert(
      nullRootSource.code ==
        "SPINAL-ELAB-INT-PARAMETER-ROOT-SOURCE-OPTION-NULL"
    )
  }

  test("typed factories and resize validate before native construction") {
    val nullWidth: ParameterizedBitCount = null
    val factoryError = intercept[IllegalArgumentException] {
      ParameterizedWidth.Bits(nullWidth)
    }
    assert(factoryError.getMessage == "symbolic bit count must not be null")

    val schema =
      ElaborationIntegerParameter("WIDTH", default = 8, minimum = 0, maximum = 16)
    val invalidExpression = ElaborationIntegerExpression(
      verilog = "WIDTH",
      default = 8,
      minimum = 0,
      maximum = 16,
      parameters = Vector(schema)
    )
    val invalidWidth =
      parameter("EXACT_INVALID_WIDTH", default = 8, minimum = 0, maximum = 16)

    final class ProbeBitsFactory extends spinal.core.BitsFactory {
      var nativeFactoryCalls = 0
      override def Bits(unit: Unit): spinal.core.Bits = {
        nativeFactoryCalls += 1
        new spinal.core.Bits
      }
    }
    final class ProbeUIntFactory extends spinal.core.UIntFactory {
      var nativeFactoryCalls = 0
      override def UInt(unit: Unit): spinal.core.UInt = {
        nativeFactoryCalls += 1
        new spinal.core.UInt
      }
    }
    final class ProbeSIntFactory extends spinal.core.SIntFactory {
      var nativeFactoryCalls = 0
      override def SInt(unit: Unit): spinal.core.SInt = {
        nativeFactoryCalls += 1
        new spinal.core.SInt
      }
    }

    val invalidBitCount = ParameterizedBitCount(
      value = 8,
      parameter = Some(schema),
      expression = Some(invalidExpression)
    )
    val bitsFactory = new ProbeBitsFactory
    val uintFactory = new ProbeUIntFactory
    val sintFactory = new ProbeSIntFactory
    Vector[() => Unit](
      () => bitsFactory.Bits(invalidBitCount),
      () => uintFactory.UInt(invalidBitCount),
      () => sintFactory.SInt(invalidBitCount),
      () => bitsFactory.Bits(nullWidth),
      () => uintFactory.UInt(nullWidth),
      () => sintFactory.SInt(nullWidth)
    ).foreach { construct =>
      intercept[IllegalArgumentException] {
        construct()
      }
    }
    assert(bitsFactory.nativeFactoryCalls == 0)
    assert(uintFactory.nativeFactoryCalls == 0)
    assert(sintFactory.nativeFactoryCalls == 0)

    final class ProbeBits extends spinal.core.Bits {
      var nativeResizeCalls = 0
      override def resize(width: Int): spinal.core.Bits = {
        nativeResizeCalls += 1
        this
      }
    }
    final class ProbeUInt extends spinal.core.UInt {
      var nativeResizeCalls = 0
      override def resize(width: Int): this.type = {
        nativeResizeCalls += 1
        this
      }
    }
    final class ProbeSInt extends spinal.core.SInt {
      var nativeResizeCalls = 0
      override def resize(width: Int): this.type = {
        nativeResizeCalls += 1
        this
      }
    }

    val bits = new ProbeBits
    val uint = new ProbeUInt
    val sint = new ProbeSInt
    Vector[() => Unit](
      () => bits.resize(invalidWidth),
      () => uint.resize(invalidWidth),
      () => sint.resize(invalidWidth)
    ).foreach { resize =>
      val error = intercept[ParameterizedVerilogException] {
        resize()
      }
      assert(error.code == "SPINAL-ELAB-INT-WIDTH-DOMAIN-INVALID")
    }
    assert(bits.nativeResizeCalls == 0)
    assert(uint.nativeResizeCalls == 0)
    assert(sint.nativeResizeCalls == 0)

    val nullTypedWidth: ElabInt = null
    val nullError = intercept[IllegalArgumentException] {
      bits.resize(nullTypedWidth)
    }
    assert(nullError.getMessage == "typed resize width must not be null")
    assert(bits.nativeResizeCalls == 0)
  }

  test("rootless carrier conversions preserve exact parameter declaration identity") {
    val schema =
      ElaborationIntegerParameter("WIDTH", default = 8, minimum = 1, maximum = 16)
    val expression = ElaborationIntegerExpression(
      verilog = "WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16,
      parameters = Vector(schema)
    )

    val first = ElabInt.fromExpression(expression)
    val repeated = ElabInt.fromExpression(expression)
    val copied = ElabInt.fromExpression(expression.copy())
    ElabInt.requireSingleSymbolicRoot(
      "rootless exact-declaration identity",
      first,
      repeated,
      copied
    )

    val independent = ElabInt.fromExpression(
      expression.copy(parameters = Vector(schema.copy()))
    )
    val error = intercept[ParameterizedVerilogException] {
      ElabInt.requireSingleSymbolicRoot(
        "rootless independent declaration",
        first,
        independent
      )
    }
    assert(error.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")
  }

  test("direct bit counts retain roots coherent with rootless carrier conversion") {
    val schema =
      ElaborationIntegerParameter("WIDTH", default = 8, minimum = 1, maximum = 16)
    val rootless = ElabInt.fromExpression(
      ElaborationIntegerExpression(
        verilog = "WIDTH",
        default = 8,
        minimum = 1,
        maximum = 16,
        parameters = Vector(schema)
      )
    )
    var firstExpression: ElaborationIntegerExpression = null
    var secondExpression: ElaborationIntegerExpression = null
    withSpinalElaboration {
      val firstLeaf = ParameterizedWidth.Bits(ParameterizedBitCount(8, schema))
      val secondLeaf = ParameterizedWidth.Bits(ParameterizedBitCount(8, schema))
      firstExpression = ParameterizedWidth.expressionOf(firstLeaf).get
      secondExpression = ParameterizedWidth.expressionOf(secondLeaf).get
    }

    assert(firstExpression.parameterRoots.nonEmpty)
    assert(firstExpression.parameterRoots.head eq secondExpression.parameterRoots.head)
    ElabInt.requireSingleSymbolicRoot(
      "direct bit-count declaration identity",
      rootless,
      ElabInt.fromExpression(firstExpression),
      ElabInt.fromExpression(secondExpression)
    )
  }

  test("width attachment rejects incoherent witnesses before mutating native data") {
    val schema =
      ElaborationIntegerParameter("WIDTH", default = 8, minimum = 1, maximum = 16)
    val mismatchedWidth =
      parameter("OTHER_WIDTH", default = 9, minimum = 1, maximum = 16).bits
        .copy(value = 8, parameter = None)
    var directError: ParameterizedVerilogException = null
    var expressionError: ParameterizedVerilogException = null
    var retainedWidths = Vector.empty[Int]
    withSpinalElaboration {
      val directTarget = spinal.core.Bits(spinal.core.BitCount(8))
      directError = intercept[ParameterizedVerilogException] {
        ParameterizedWidth.attach(directTarget, ParameterizedBitCount(7, schema))
      }
      val expressionTarget = spinal.core.Bits(spinal.core.BitCount(8))
      expressionError = intercept[ParameterizedVerilogException] {
        ParameterizedWidth.attach(expressionTarget, mismatchedWidth)
      }
      retainedWidths = Vector(
        directTarget.getBitsWidth,
        expressionTarget.getBitsWidth
      )
    }

    assert(directError.code == "SPINAL-PARAMETERIZED-VERILOG-WITNESS-MISMATCH")
    assert(expressionError.code == "SPINAL-PARAMETERIZED-VERILOG-WITNESS-MISMATCH")
    assert(retainedWidths == Vector(8, 8))
  }

  test("width attachment rejects incoherent direct-expression bounds") {
    val schema =
      ElaborationIntegerParameter("WIDTH", default = 8, minimum = 1, maximum = 16)
    val expression = ElaborationIntegerExpression(
      verilog = "WIDTH",
      default = 8,
      minimum = 2,
      maximum = 16,
      parameters = Vector(schema)
    )
    var error: ParameterizedVerilogException = null
    withSpinalElaboration {
      error = intercept[ParameterizedVerilogException] {
        ParameterizedWidth.Bits(
          ParameterizedBitCount(
            value = 8,
            parameter = Some(schema),
            expression = Some(expression)
          )
        )
      }
    }

    assert(
      error.code ==
        "SPINAL-PARAMETERIZED-VERILOG-DIRECT-PARAMETER-EXPRESSION-MISMATCH"
    )
  }

  private def expressionWith(schema: ElaborationIntegerParameter): ElabInt =
    ElabInt.fromExpression(
      ElaborationIntegerExpression(
        verilog = "WIDTH",
        default = 8,
        minimum = 1,
        maximum = 16,
        parameters = Vector(schema)
      )
    )

  private def withSpinalElaboration(body: => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-typed-width-test-")
    try {
      spinal.core.SpinalVerilog(
        spinal.core.SpinalConfig(
          targetDirectory = directory.toString,
          headerWithRepoHash = false,
          withTimescale = false,
          printFilelist = false
        )
      ) {
        new spinal.core.Component {
          val keep = spinal.core.out(spinal.core.Bool())
          keep := spinal.core.False
          body
        }
      }
    } finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach {
          path => Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
