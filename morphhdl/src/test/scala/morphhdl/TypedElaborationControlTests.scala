package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._
import morphhdl.frontend.HdlInt

object TypedElaborationControlSmoke {
  final class Sink extends Component {
    setDefinitionName("TypedElaborationControlSink")
    val din = in Bits (8 bits)
    val observed = out Bool()
    observed := din.orR
  }

  final class Top(width: ElabInt) extends Component {
    setDefinitionName("TypedElaborationControlTop")

    val din = in Bits (8 bits)
    val alive = out Bool()
    alive := din.orR

    require(width > 0, "WIDTH must remain positive")

    if (width == 8) attach(din)
    else if (width > 8) attach(~din)
    else attach(din)

    private def attach(value: Bits): Unit = {
      val sink = new Sink
      sink.din := value
    }
  }

  def component(): Top = {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
    new Top(width.asElabInt)
  }
}

/**
  * Same source unit, same identifier spellings, different lexical bindings.
  * Ordinary Int bindings must never inherit typed-control meaning from another
  * class or method merely because their names match.
  */
object TypedElaborationLexicalScopeSmoke {
  final class TypedOwner(inputWidth: ElabInt, outputWidth: ElabInt) {
    def typedPredicate: ElabBool = inputWidth.elabEq(outputWidth)
  }

  def ordinaryEqual(inputWidth: Int, outputWidth: Int): Boolean =
    inputWidth == outputWidth

  def ordinaryNested(inputWidth: Int): Boolean = {
    val outputWidth: Int = 8
    inputWidth == outputWidth
  }
}

/**
  * A typed carrier may be an ordinary value inside a Scala expression.  Merely
  * mentioning it must not turn the expression's Boolean result into typed
  * elaboration control.
  */
object TypedElaborationLexicalMentionSmoke {
  def sequenceNonEmpty(width: ElabInt): Boolean =
    if (Seq(width).nonEmpty) true else false

  def parameterCountEqualsOne(width: ElabInt): Boolean =
    if (width.parameters.size == 1) true else false

  def requireParameterMetadata(width: ElabInt): Unit =
    require(width.parameters.nonEmpty, "expected one symbolic parameter")

  def ordinaryGenerate(width: ElabInt): String =
    Seq(width).nonEmpty.generate("ordinary")

  def typedNestedControl(width: ElabInt): Boolean =
    if ((width + 1 > 8) && width.elabNe(99)) true else false

  def typedGenerate(width: ElabInt): String =
    (width > 0).generate("typed")

  def standaloneEqual(width: ElabInt): ElabBool =
    width == width

  def standaloneNotEqual(width: ElabInt): ElabBool =
    width != width

  def inferredPredicate(width: ElabInt): Boolean = {
    val predicate = width > 0
    if (predicate) true else false
  }

  def inferredEquality(width: ElabInt): ElabBool = {
    val predicate = width == 8
    predicate
  }

  def literalOnLeft(width: ElabInt): ElabBool =
    8 == width

  def stringOnLeft(width: ElabInt): Boolean =
    "WIDTH" == width

  def stringOnRight(width: ElabInt): Boolean =
    width == "WIDTH"

  def nullOnLeft(width: ElabInt): Boolean =
    null == width

  def nullOnRight(width: ElabInt): Boolean =
    width == null

  def nullNotEqualOnLeft(width: ElabInt): Boolean =
    null != width

  def nullNotEqualOnRight(width: ElabInt): Boolean =
    width != null
}

class TypedElaborationControlTests extends AnyFunSuite {
  import TypedElaborationControlSmoke._

  test("natural typed equality and else-if lower without native Int shadow reconstruction") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "typed_elaboration_control.v"
      MorphVerilog(config)(component())
      val verilog = new String(
        Files.readAllBytes(directory.resolve("typed_elaboration_control.v")),
        StandardCharsets.UTF_8
      )
      val compact = verilog.replaceAll("\\s+", "")

      assert(verilog.contains("module TypedElaborationControlTop #("))
      assert(verilog.contains("parameter integer WIDTH = 8"))
      assert(
        compact.contains("if(((WIDTH)==(8)))begin") ||
          compact.contains("if((WIDTH==8))begin")
      )
      assert(
        compact.contains("elseif(((WIDTH)>(8)))begin") ||
          compact.contains("elseif((WIDTH>8))begin")
      )
      assert(!verilog.contains("NativeIntShadow"))
      assert(!verilog.contains("compilerTrackArgument"))
    }
  }

  test("typed expressions reject independent symbolic roots before native elaboration") {
    val left = HdlInt.param("LEFT", default = 8, min = 1, max = 16).asElabInt
    val right = HdlInt.param("RIGHT", default = 8, min = 1, max = 16).asElabInt
    val error = intercept[ParameterizedVerilogException] {
      ElabInt.requireSingleSymbolicRoot("typed unit test", left, right)
    }
    assert(error.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")
  }

  test("same-named same-schema declarations remain independent symbolic roots") {
    val first = HdlInt.param("WIDTH", default = 8, min = 1, max = 16).asElabInt
    val second = HdlInt.param("WIDTH", default = 8, min = 1, max = 16).asElabInt
    val error = intercept[ParameterizedVerilogException] {
      ElabInt.requireSingleSymbolicRoot("typed same-name unit test", first, second)
    }
    assert(error.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")

    val arithmeticError = intercept[ParameterizedVerilogException] {
      first + second
    }
    assert(
      arithmeticError.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED"
    )

    val booleanError = intercept[ParameterizedVerilogException] {
      (first > 0) && (second > 0)
    }
    assert(booleanError.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")
  }

  test("copies and derived expressions preserve one symbolic root identity") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
    val typed = width.asElabInt
    val copied = width.asElabInt
    val frontendDerived =
      (width + HdlInt.literal(BigInt(1))).asElabInt
    val coreDerived = (typed * 2) - 1

    ElabInt.requireSingleSymbolicRoot(
      "typed derived unit test",
      typed,
      copied,
      frontendDerived,
      coreDerived
    )
    assert(typed.elabEq(copied).isAlwaysTrue)
  }

  test("direct HdlInt bit counts retain declaration identity through width metadata") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
    val first = ElabInt.fromExpression(width.bits.expression.get)
    val copied = ElabInt.fromExpression(width.bits.expression.get)
    ElabInt.requireSingleSymbolicRoot("direct bit-count copy", first, copied)

    val independent = HdlInt
      .param("WIDTH", default = 8, min = 1, max = 16)
      .bits
      .expression
      .map(ElabInt.fromExpression)
      .get
    val error = intercept[ParameterizedVerilogException] {
      ElabInt.requireSingleSymbolicRoot(
        "direct bit-count independent root",
        first,
        independent
      )
    }
    assert(error.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")
  }

  test("ordinary bindings shadow same-named typed bindings in the same source unit") {
    assert(TypedElaborationLexicalScopeSmoke.ordinaryEqual(8, 8))
    assert(!TypedElaborationLexicalScopeSmoke.ordinaryEqual(8, 9))
    assert(TypedElaborationLexicalScopeSmoke.ordinaryNested(8))
    assert(!TypedElaborationLexicalScopeSmoke.ordinaryNested(7))
  }

  test("lexical carrier mentions remain ordinary unless the expression result is typed") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16).asElabInt

    assert(TypedElaborationLexicalMentionSmoke.sequenceNonEmpty(width))
    assert(TypedElaborationLexicalMentionSmoke.parameterCountEqualsOne(width))
    TypedElaborationLexicalMentionSmoke.requireParameterMetadata(width)
    assert(TypedElaborationLexicalMentionSmoke.ordinaryGenerate(width) == "ordinary")
    assert(TypedElaborationLexicalMentionSmoke.typedNestedControl(width))
    assert(TypedElaborationLexicalMentionSmoke.typedGenerate(width) == "typed")

    val equal = TypedElaborationLexicalMentionSmoke.standaloneEqual(width)
    val notEqual = TypedElaborationLexicalMentionSmoke.standaloneNotEqual(width)
    assert(equal.isAlwaysTrue)
    assert(notEqual.isAlwaysFalse)

    assert(TypedElaborationLexicalMentionSmoke.inferredPredicate(width))
    assert(TypedElaborationLexicalMentionSmoke.inferredEquality(width).isSymbolic)
    assert(TypedElaborationLexicalMentionSmoke.literalOnLeft(width).isSymbolic)
    assert(!TypedElaborationLexicalMentionSmoke.stringOnLeft(width))
    assert(!TypedElaborationLexicalMentionSmoke.stringOnRight(width))
    assert(!TypedElaborationLexicalMentionSmoke.nullOnLeft(width))
    assert(!TypedElaborationLexicalMentionSmoke.nullOnRight(width))
    assert(TypedElaborationLexicalMentionSmoke.nullNotEqualOnLeft(width))
    assert(TypedElaborationLexicalMentionSmoke.nullNotEqualOnRight(width))
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-typed-elaboration-control-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach {
          path => Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
