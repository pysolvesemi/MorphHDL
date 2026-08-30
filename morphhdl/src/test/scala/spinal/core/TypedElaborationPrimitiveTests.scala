package spinal.core

import java.nio.file.Files

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.frontend.HdlInt

/** Primitive-level contracts used by the typed StreamFifo migration. */
class TypedElaborationPrimitiveTests extends AnyFunSuite {
  test("typed log2Up retains exact numeric semantics including zero-width results") {
    val literalCases = Vector(
      0 -> 0,
      1 -> 0,
      2 -> 1,
      3 -> 2,
      4 -> 2,
      5 -> 3,
      8 -> 3
    )

    literalCases.foreach { case (input, expected) =>
      val result = log2Up(ElabInt.literal(input))
      assert(result.constantInt(s"log2Up($input)") == expected)
      assert(result.minimum == expected)
      assert(result.maximum == expected)
    }

    val zeroWidth = ElabInt.literal(1).log2Up
    assert(zeroWidth.expression.verilog == "morphhdl_ceil_log2(1)")
    val widthError = intercept[ParameterizedVerilogException] {
      zeroWidth.bits
    }
    assert(widthError.code == "SPINAL-ELAB-INT-WIDTH-DOMAIN-INVALID")

    val negativeError = intercept[ParameterizedVerilogException] {
      ElabInt.literal(-1).log2Up
    }
    assert(negativeError.code == "SPINAL-ELAB-INT-LOG2-DOMAIN-NEGATIVE")
  }

  test("typed log2Up projects exact values in each active depth branch") {
    val depth = typedDepth(default = 5)
    val root = exact(depth.expression).root
    val result = depth.log2Up
    val expected = Vector(
      BigInt(1) -> BigInt(0),
      BigInt(2) -> BigInt(1),
      BigInt(3) -> BigInt(2),
      BigInt(4) -> BigInt(2),
      BigInt(5) -> BigInt(3),
      BigInt(6) -> BigInt(3),
      BigInt(7) -> BigInt(3),
      BigInt(8) -> BigInt(3)
    )

    assert(result.expression.verilog == "morphhdl_ceil_log2(DEPTH)")
    assert(exact(result.expression).evaluations == expected)
    assert(result.minimum == 0)
    assert(result.maximum == 3)

    ElaborationDomainContext.withAdmitted(
      root,
      (2 to 8).map(BigInt(_)).toSet,
      sourceLocation = None
    ) {
      assert(result.minimum == 1)
      assert(result.maximum == 3)
      assert(result.witness == 3)
      assert(result.bits.value == 3)
    }

    ElaborationDomainContext.withAdmitted(
      root,
      Set(BigInt(1)),
      sourceLocation = None
    ) {
      assert(result.minimum == 0)
      assert(result.maximum == 0)
      assert(result.witness == 0)
      val error = intercept[ParameterizedVerilogException] {
        result.bits
      }
      assert(error.code == "SPINAL-ELAB-INT-WIDTH-DOMAIN-INVALID")
    }
  }

  test("power-of-two and Boolean-to-integer adapters preserve exact depth values") {
    val depth = typedDepth(default = 5)
    val domain = exact(depth.expression)
    val predicate = isPow2(depth)
    val encoded = predicate.toElabInt
    val powers = Set(1, 2, 4, 8).map(BigInt(_))

    assert(predicate.isSymbolic)
    assert(
      exact(predicate.expression).evaluations == domain.evaluations.map { case (rootValue, value) =>
        rootValue -> powers.contains(value)
      }
    )
    assert(
      exact(encoded.expression).evaluations == domain.evaluations.map { case (rootValue, value) =>
        rootValue -> (if (powers.contains(value)) BigInt(1) else BigInt(0))
      }
    )

    domain.universe.foreach { value =>
      ElaborationDomainContext.withAdmitted(
        domain.root,
        Set(value),
        sourceLocation = None
      ) {
        val expected = powers.contains(value)
        assert(predicate.witness == expected)
        assert(predicate.isAlwaysTrue == expected)
        assert(predicate.isAlwaysFalse == !expected)
        assert(encoded.minimum == (if (expected) 1 else 0))
        assert(encoded.maximum == (if (expected) 1 else 0))
        assert(encoded.witness == (if (expected) 1 else 0))
      }
    }
  }

  test("finite typed ranges fail closed unless the active branch has one size") {
    val depth = typedDepth(default = 5)
    val root = exact(depth.expression).root

    val globalError = intercept[ParameterizedVerilogException] {
      depth.finiteRangeFromZero("primitive range")
    }
    assert(globalError.code == "SPINAL-ELAB-INT-RANGE-DOMAIN-NOT-CONSTANT")

    ElaborationDomainContext.withAdmitted(
      root,
      Set(BigInt(3)),
      sourceLocation = None
    ) {
      assert(depth.finiteRangeFromZero("primitive range") == (0 until 3))
    }

    ElaborationDomainContext.withAdmitted(
      root,
      Set(BigInt(3), BigInt(5)),
      sourceLocation = None
    ) {
      val error = intercept[ParameterizedVerilogException] {
        depth.finiteRangeFromZero("primitive range")
      }
      assert(error.code == "SPINAL-ELAB-INT-RANGE-DOMAIN-NOT-CONSTANT")
    }

    val negativeError = intercept[ParameterizedVerilogException] {
      ElabInt.literal(-1).finiteRangeFromZero("primitive range")
    }
    assert(negativeError.code == "SPINAL-ELAB-INT-RANGE-DOMAIN-INVALID")
  }

  test("typed Vec unrolls only a constant branch-local exact size") {
    val depth = typedDepth(default = 5)
    val root = exact(depth.expression).root
    var globalError: ParameterizedVerilogException = null
    var narrowedLength = -1
    var varyingError: ParameterizedVerilogException = null

    withSpinalElaboration {
      globalError = intercept[ParameterizedVerilogException] {
        Vec(Bits(1 bit), depth)
      }

      narrowedLength = ElaborationDomainContext.withAdmitted(
        root,
        Set(BigInt(3)),
        sourceLocation = None
      ) {
        Vec(Bits(1 bit), depth).length
      }

      ElaborationDomainContext.withAdmitted(
        root,
        Set(BigInt(3), BigInt(5)),
        sourceLocation = None
      ) {
        varyingError = intercept[ParameterizedVerilogException] {
          Vec(Bits(1 bit), depth)
        }
      }
    }

    assert(globalError.code == "SPINAL-ELAB-INT-RANGE-DOMAIN-NOT-CONSTANT")
    assert(narrowedLength == 3)
    assert(varyingError.code == "SPINAL-ELAB-INT-RANGE-DOMAIN-NOT-CONSTANT")
  }

  test("typed Mem retains global depth and uses the exact branch representative") {
    val depth = typedDepth(default = 5)
    val root = exact(depth.expression).root
    var globalWordCount = -1
    var globalMetadata: ElaborationIntegerExpression = null
    var narrowedWordCount = -1
    var narrowedMetadata: ElaborationIntegerExpression = null

    withSpinalElaboration {
      val global = Mem(Bits(8 bits), depth)
      globalWordCount = global.wordCount
      globalMetadata = ParameterizedMemory.metadataOf(global).get.depth

      val narrowed = ElaborationDomainContext.withAdmitted(
        root,
        Set(BigInt(3)),
        sourceLocation = None
      ) {
        Mem(Bits(8 bits), depth)
      }
      narrowedWordCount = narrowed.wordCount
      narrowedMetadata = ParameterizedMemory.metadataOf(narrowed).get.depth
    }

    assert(globalWordCount == 5)
    assert(globalMetadata.default == 5)
    assert(globalMetadata.minimum == 1)
    assert(globalMetadata.maximum == 8)
    assert(globalMetadata.parameterRoots == Vector(root))
    assert(globalMetadata.exactDomain.nonEmpty)

    assert(narrowedWordCount == 3)
    assert(narrowedMetadata.default == 3)
    assert(narrowedMetadata.minimum == 3)
    assert(narrowedMetadata.maximum == 3)
    assert(narrowedMetadata.parameterRoots == Vector(root))
    assert(narrowedMetadata.exactDomain.nonEmpty)
  }

  test("typed Mem and Vec reject invalid inputs before native construction") {
    var zeroDepthError: ParameterizedVerilogException = null
    var nullMemoryError: IllegalArgumentException = null
    var nullVecError: IllegalArgumentException = null

    withSpinalElaboration {
      zeroDepthError = intercept[ParameterizedVerilogException] {
        Mem(Bits(8 bits), ElabInt.literal(0))
      }

      val nullSize: ElabInt = null
      nullMemoryError = intercept[IllegalArgumentException] {
        Mem(Bits(8 bits), nullSize)
      }
      nullVecError = intercept[IllegalArgumentException] {
        Vec(Bits(1 bit), nullSize)
      }
    }

    assert(
      zeroDepthError.code == "SPINAL-ELAB-INT-MEMORY-DEPTH-DOMAIN-INVALID"
    )
    assert(nullMemoryError.getMessage == "typed memory depth must not be null")
    assert(nullVecError.getMessage == "typed Vec size must not be null")
  }

  test("branch projection rejects carriers that lack exact root evidence") {
    val parameter = ElaborationIntegerParameter(
      "DEPTH",
      default = 5,
      minimum = 1,
      maximum = 8
    )
    val root = ElaborationIntegerParameterRoot.fresh("DEPTH")
    val inexact = ElabInt.fromExpression(
      ElaborationIntegerExpression(
        verilog = "DEPTH",
        default = 5,
        minimum = 1,
        maximum = 8,
        parameters = Vector(parameter),
        parameterRoots = Vector(root)
      )
    )

    ElaborationDomainContext.withAdmitted(
      root,
      Set(BigInt(3)),
      sourceLocation = None
    ) {
      val error = intercept[ParameterizedVerilogException] {
        inexact.finiteRangeFromZero("inexact primitive range")
      }
      assert(error.code == "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING")
    }
  }

  test("oversized exhaustive domains fail closed") {
    val parameter = ElaborationIntegerParameter(
      "DEPTH",
      default = 5,
      minimum = 0,
      maximum = ElabInt.MaximumExactDomainSize
    )
    val error = intercept[ParameterizedVerilogException] {
      ElabInt.directParameter(parameter, sourceLocation = None)
    }
    assert(error.code == "SPINAL-ELAB-DOMAIN-SIZE-UNSUPPORTED")
  }

  private def typedDepth(default: Int): ElabInt =
    HdlInt
      .param(
        "DEPTH",
        default = BigInt(default),
        min = BigInt(1),
        max = BigInt(8)
      )
      .asElabInt

  private def exact(
      expression: ElaborationIntegerExpression
  ): ElaborationExactDomain[BigInt] =
    expression.exactDomain.getOrElse(fail("integer exact-domain evidence is missing"))

  private def exact(
      expression: ElaborationBooleanExpression
  ): ElaborationExactDomain[Boolean] =
    expression.exactDomain.getOrElse(fail("Boolean exact-domain evidence is missing"))

  private def withSpinalElaboration(body: => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-typed-primitives-")
    try {
      SpinalVerilog(
        SpinalConfig(
          targetDirectory = directory.toString,
          headerWithRepoHash = false,
          withTimescale = false,
          printFilelist = false
        )
      ) {
        new Component {
          val keep = out(Bool())
          keep := False
          body
        }
      }
    } finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
