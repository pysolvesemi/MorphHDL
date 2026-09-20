package spinal.core

import java.nio.file.Files

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.frontend.HdlInt
import spinal.core.internals.{DataAssignmentStatement, Resize}

private object TypedElaborationPrimitiveFixture {
  final case class ResizeGraph(
      kind: String,
      operation: String,
      targetWidth: Int,
      resultClass: String,
      resultWidth: Int,
      resultIsFixedWidth: Boolean,
      resultHasOneAssignment: Boolean,
      assignmentTargetsResult: Boolean,
      resizeClass: String,
      resizeWidth: Int,
      resizeInputIsSource: Boolean,
      resizeInputWidth: Int,
      retainedResultWidth: Boolean,
      retainedResizeWidth: Boolean
  )

  final class LiteralResizeParity(
      useTypedLiteral: Boolean,
      captured: ArrayBuffer[ResizeGraph]
  ) extends Component {
    setDefinitionName("LiteralResizeParity")

    val bitsSource = in(Bits(8 bits)).setName("bits_source")
    val uintSource = in(UInt(8 bits)).setName("uint_source")
    val sintSource = in(SInt(8 bits)).setName("sint_source")

    private def bitsResize(width: Int): Bits =
      if (useTypedLiteral) bitsSource.resize(ElabInt.literal(width))
      else bitsSource.resize(width)

    private def uintResize(width: Int): UInt =
      if (useTypedLiteral) uintSource.resize(ElabInt.literal(width))
      else uintSource.resize(width)

    private def sintResize(width: Int): SInt =
      if (useTypedLiteral) sintSource.resize(ElabInt.literal(width))
      else sintSource.resize(width)

    private def capture(
        kind: String,
        operation: String,
        targetWidth: Int,
        source: BitVector,
        result: BitVector
    ): Unit = {
      val hasOneAssignment = result.hasOnlyOneStatement
      val assignment =
        if (hasOneAssignment)
          result.head match {
            case value: DataAssignmentStatement => value
            case other =>
              throw new IllegalStateException(
                s"$kind $operation resize result carried ${other.getClass.getName}"
              )
          }
        else
          throw new IllegalStateException(
            s"$kind $operation resize result did not carry one assignment"
          )
      val resize = assignment.source match {
        case value: Resize => value
        case other =>
          throw new IllegalStateException(
            s"$kind $operation resize assignment carried ${other.getClass.getName}"
          )
      }

      captured += ResizeGraph(
        kind = kind,
        operation = operation,
        targetWidth = targetWidth,
        resultClass = result.getClass.getSimpleName,
        resultWidth = result.getBitsWidth,
        resultIsFixedWidth = result.isFixedWidth,
        resultHasOneAssignment = hasOneAssignment,
        assignmentTargetsResult = (assignment.target eq result) && (assignment.finalTarget eq result),
        resizeClass = resize.getClass.getSimpleName,
        resizeWidth = resize.size,
        resizeInputIsSource = resize.input eq source,
        resizeInputWidth = resize.input.getWidth,
        retainedResultWidth = ParameterizedWidth.expressionOf(result).nonEmpty,
        retainedResizeWidth = ParameterizedWidth.resizeExpressionOf(resize).nonEmpty
      )
    }

    private def keep(
        kind: String,
        operation: String,
        width: Int,
        source: BitVector,
        value: BitVector
    ): Unit = {
      value.setName(s"${kind.toLowerCase}_${operation}_value").dontSimplifyIt()
      capture(kind, operation, width, source, value)
      kind match {
        case "Bits" =>
          val output = out(Bits(width bits))
            .setName(s"bits_${operation}_output")
          output := value.asInstanceOf[Bits]
        case "UInt" =>
          val output = out(UInt(width bits))
            .setName(s"uint_${operation}_output")
          output := value.asInstanceOf[UInt]
        case "SInt" =>
          val output = out(SInt(width bits))
            .setName(s"sint_${operation}_output")
          output := value.asInstanceOf[SInt]
      }
    }

    Vector("shrink" -> 3, "noop" -> 8, "grow" -> 12).foreach { case (operation, width) =>
      keep("Bits", operation, width, bitsSource, bitsResize(width))
    }
    Vector("shrink" -> 3, "noop" -> 8, "grow" -> 12).foreach { case (operation, width) =>
      keep("UInt", operation, width, uintSource, uintResize(width))
    }
    Vector("shrink" -> 3, "noop" -> 8, "grow" -> 12).foreach { case (operation, width) =>
      keep("SInt", operation, width, sintSource, sintResize(width))
    }
  }
}

/** Primitive-level contracts used by the typed StreamFifo migration. */
class TypedElaborationPrimitiveTests extends AnyFunSuite {
  import TypedElaborationPrimitiveFixture._

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
    assert(zeroWidth.expression.verilog == "0")
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

  test("typed Vec retains symbolic depth independently of its audited native carrier") {
    val depth = typedDepth(default = 5)
    val root = exact(depth.expression).root
    var globalGeometryError: ParameterizedVerilogException = null
    var globalCarrierLength = -1
    var globalDepth: ElaborationIntegerExpression = null
    var narrowedLength = -1
    var narrowedDepth: ElaborationIntegerExpression = null
    var varyingGeometryError: ParameterizedVerilogException = null
    var varyingCarrierLength = -1
    var varyingDepth: ElaborationIntegerExpression = null

    withSpinalElaboration {
      val global = Vec(Bits(1 bit), depth)
      globalGeometryError = intercept[ParameterizedVerilogException] {
        global.length
      }
      globalCarrierLength = global.carrierLength
      globalDepth = ParameterizedVec.shapeOf(global).get.depth

      ElaborationDomainContext.withAdmitted(
        root,
        Set(BigInt(3)),
        sourceLocation = None
      ) {
        val narrowed = Vec(Bits(1 bit), depth)
        narrowedLength = narrowed.length
        narrowedDepth = ParameterizedVec.shapeOf(narrowed).get.depth
      }

      ElaborationDomainContext.withAdmitted(
        root,
        Set(BigInt(3), BigInt(5)),
        sourceLocation = None
      ) {
        val varying = Vec(Bits(1 bit), depth)
        varyingGeometryError = intercept[ParameterizedVerilogException] {
          varying.length
        }
        varyingCarrierLength = varying.carrierLength
        varyingDepth = ParameterizedVec.shapeOf(varying).get.depth
      }
    }

    assert(globalGeometryError.code == "SPINAL-ELAB-VEC-OPERATION-UNSUPPORTED")
    assert(globalCarrierLength == 8)
    assert(globalDepth.verilog == "DEPTH")
    assert(globalDepth.default == 5)
    assert(globalDepth.minimum == 1)
    assert(globalDepth.maximum == 8)

    assert(narrowedLength == 3)
    assert(narrowedDepth.verilog == "DEPTH")
    assert(narrowedDepth.default == 3)
    assert(narrowedDepth.minimum == 3)
    assert(narrowedDepth.maximum == 3)

    assert(varyingGeometryError.code == "SPINAL-ELAB-VEC-OPERATION-UNSUPPORTED")
    assert(varyingCarrierLength == 5)
    assert(varyingDepth.verilog == "DEPTH")
    assert(varyingDepth.default == 5)
    assert(varyingDepth.minimum == 3)
    assert(varyingDepth.maximum == 5)
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

  test("typed Mem and Vec reject null typed inputs before native construction") {
    var nullMemoryError: IllegalArgumentException = null
    var nullVecError: IllegalArgumentException = null

    withSpinalElaboration {
      val nullSize: ElabInt = null
      nullMemoryError = intercept[IllegalArgumentException] {
        Mem(Bits(8 bits), nullSize)
      }
      nullVecError = intercept[IllegalArgumentException] {
        Vec(Bits(1 bit), nullSize)
      }
    }

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

  test("literal typed resize delegates to byte-authoritative native behavior") {
    val directory = Files.createTempDirectory("morphhdl-literal-resize-parity-")
    try {
      val native = emitLiteralResize(directory.resolve("native"), useTyped = false)
      val typed = emitLiteralResize(directory.resolve("typed"), useTyped = true)

      assert(typed._1 == native._1, s"typed graph: ${typed._1}\nnative graph: ${native._1}")
      native._1.foreach { graph =>
        assert(graph.resultClass == graph.kind, graph)
        assert(graph.resultWidth == graph.targetWidth, graph)
        assert(graph.resultHasOneAssignment, graph)
        assert(graph.assignmentTargetsResult, graph)
        assert(graph.resizeClass == s"Resize${graph.kind}", graph)
        assert(graph.resizeWidth == graph.targetWidth, graph)
        assert(graph.resizeInputIsSource, graph)
        assert(graph.resizeInputWidth == 8, graph)
        assert(!graph.retainedResultWidth, graph)
        assert(!graph.retainedResizeWidth, graph)
      }
      assert(
        java.util.Arrays.equals(typed._2, native._2),
        "ElabInt literal resize RTL differs from the authoritative Int overload"
      )
    } finally deleteTree(directory)
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

  private def emitLiteralResize(
      directory: java.nio.file.Path,
      useTyped: Boolean
  ): (Vector[ResizeGraph], Array[Byte]) = {
    Files.createDirectories(directory)
    val captured = ArrayBuffer.empty[ResizeGraph]
    val config = SpinalConfig(
      targetDirectory = directory.toString,
      headerWithRepoHash = false,
      withTimescale = false,
      printFilelist = false
    )
    val fileName = "LiteralResizeParity.v"
    config.netlistFileName = fileName
    SpinalVerilog(config) {
      new LiteralResizeParity(useTyped, captured)
    }
    captured.toVector -> Files.readAllBytes(directory.resolve(fileName))
  }

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
    } finally deleteTree(directory)
  }

  private def deleteTree(directory: java.nio.file.Path): Unit = {
    if (!Files.exists(directory)) return
    val stream = Files.walk(directory)
    try {
      stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
        Files.deleteIfExists(path)
      }
    } finally stream.close()
  }
}
