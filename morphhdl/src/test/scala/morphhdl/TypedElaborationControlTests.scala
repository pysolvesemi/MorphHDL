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

  test("ordinary bindings shadow same-named typed bindings in the same source unit") {
    assert(TypedElaborationLexicalScopeSmoke.ordinaryEqual(8, 8))
    assert(!TypedElaborationLexicalScopeSmoke.ordinaryEqual(8, 9))
    assert(TypedElaborationLexicalScopeSmoke.ordinaryNested(8))
    assert(!TypedElaborationLexicalScopeSmoke.ordinaryNested(7))
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
