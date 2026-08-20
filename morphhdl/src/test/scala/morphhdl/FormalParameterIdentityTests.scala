package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.{formalParam, HdlInt}

object FormalParameterIdentitySmoke {
  final class Leaf(actualWidth: HdlInt) extends Component {
    setDefinitionName("FormalIdentityLeaf")
    private val width = formalParam(actualWidth, "WIDTH")

    val din = in(morphhdl.frontend.Bits(width bits))
    val dout = out(morphhdl.frontend.Bits(width bits))
    dout := din
  }

  final class Top(leftWidth: HdlInt, rightWidth: HdlInt) extends Component {
    setDefinitionName("FormalIdentityTop")

    val leftIn = in(morphhdl.frontend.Bits(leftWidth bits))
    val leftOut = out(morphhdl.frontend.Bits(leftWidth bits))
    val rightIn = in(morphhdl.frontend.Bits(rightWidth bits))
    val rightOut = out(morphhdl.frontend.Bits(rightWidth bits))

    val left = new Leaf(leftWidth)
    left.setName("left")
    val right = new Leaf(rightWidth)
    right.setName("right")

    left.din := leftIn
    leftOut := left.dout
    right.din := rightIn
    rightOut := right.dout
  }

  def component(): Component = {
    val leftWidth = HdlInt.param("LEFT_WIDTH", default = 8, min = 1, max = 16)
    val rightWidth = HdlInt.param("RIGHT_WIDTH", default = 8, min = 2, max = 32)
    new Top(leftWidth, rightWidth)
  }

  def emit(directory: Path, filename: String): String = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    MorphVerilog(config)(component())
    new String(
      Files.readAllBytes(directory.resolve(filename)),
      StandardCharsets.UTF_8
    )
  }

  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      throw new IllegalArgumentException(
        "Usage: FormalParameterIdentitySmoke <output-directory>"
      )
    }
    emit(Paths.get(args(0)), "formal_parameter_identity.v")
    ()
  }
}

class FormalParameterIdentityTests extends AnyFunSuite {
  import FormalParameterIdentitySmoke._

  private final class LiteralTop(actualWidth: HdlInt) extends Component {
    setDefinitionName("FormalLiteralTop")
    val din = in(morphhdl.frontend.Bits(8 bits))
    val dout = out(morphhdl.frontend.Bits(8 bits))
    val leaf = new Leaf(actualWidth)
    leaf.setName("leaf")
    leaf.din := din
    dout := leaf.dout
  }

  private final class BoundedLeaf(actualWidth: HdlInt, formalMaximum: BigInt)
      extends Component {
    setDefinitionName("FormalBoundedLeaf")
    private val width = formalParam(
      actualWidth,
      "WIDTH",
      minimum = BigInt(1),
      maximum = formalMaximum
    )
    val din = in(morphhdl.frontend.Bits(width bits))
    val dout = out(morphhdl.frontend.Bits(width bits))
    dout := din
  }

  private final class BoundedTop(
      leftWidth: HdlInt,
      rightWidth: HdlInt,
      leftMaximum: BigInt,
      rightMaximum: BigInt
  ) extends Component {
    setDefinitionName("FormalBoundedTop")
    val leftIn = in(morphhdl.frontend.Bits(leftWidth bits))
    val leftOut = out(morphhdl.frontend.Bits(leftWidth bits))
    val rightIn = in(morphhdl.frontend.Bits(rightWidth bits))
    val rightOut = out(morphhdl.frontend.Bits(rightWidth bits))

    val left = new BoundedLeaf(leftWidth, leftMaximum)
    left.setName("left")
    val right = new BoundedLeaf(rightWidth, rightMaximum)
    right.setName("right")
    left.din := leftIn
    leftOut := left.dout
    right.din := rightIn
    rightOut := right.dout
  }

  private final class DuplicateFormalLeaf(actualWidth: HdlInt) extends Component {
    setDefinitionName("DuplicateFormalLeaf")
    private val inputWidth = formalParam(actualWidth, "WIDTH")
    private val outputWidth = formalParam(actualWidth, "WIDTH")
    val din = in(morphhdl.frontend.Bits(inputWidth bits))
    val dout = out(morphhdl.frontend.Bits(outputWidth bits))
    dout := din
  }

  private final class DuplicateFormalTop(actualWidth: HdlInt) extends Component {
    setDefinitionName("DuplicateFormalTop")
    val din = in(morphhdl.frontend.Bits(actualWidth bits))
    val dout = out(morphhdl.frontend.Bits(actualWidth bits))
    val leaf = new DuplicateFormalLeaf(actualWidth)
    leaf.setName("leaf")
    leaf.din := din
    dout := leaf.dout
  }

  private final class AmbiguousFormalLeaf(
      inputActual: HdlInt,
      outputActual: HdlInt
  ) extends Component {
    setDefinitionName("AmbiguousFormalLeaf")

    private def width(actual: HdlInt): HdlInt =
      formalParam(actual, "WIDTH")

    val din = in(morphhdl.frontend.Bits(width(inputActual) bits))
    val dout = out(morphhdl.frontend.Bits(width(outputActual) bits))
    dout := din
  }

  private final class AmbiguousFormalTop(
      inputWidth: HdlInt,
      outputWidth: HdlInt
  ) extends Component {
    setDefinitionName("AmbiguousFormalTop")
    val din = in(morphhdl.frontend.Bits(inputWidth bits))
    val dout = out(morphhdl.frontend.Bits(outputWidth bits))
    val leaf = new AmbiguousFormalLeaf(inputWidth, outputWidth)
    leaf.setName("leaf")
    leaf.din := din
    dout := leaf.dout
  }

  private final class MismatchedConnectionTop(
      formalActual: HdlInt,
      connectedWidth: HdlInt
  ) extends Component {
    setDefinitionName("MismatchedFormalConnectionTop")
    val din = in(morphhdl.frontend.Bits(connectedWidth bits))
    val dout = out(morphhdl.frontend.Bits(connectedWidth bits))
    val leaf = new Leaf(formalActual)
    leaf.setName("leaf")
    leaf.din := din
    dout := leaf.dout
  }

  test("explicit child formals retain one canonical module across caller domains") {
    withTemporaryDirectory { firstDirectory =>
      withTemporaryDirectory { secondDirectory =>
        val first = emit(firstDirectory, "formal_parameter_identity.v")
        val second = emit(secondDirectory, "formal_parameter_identity.v")
        assert(first == second)

        assert(
          "(?m)^module FormalIdentityLeaf\\b".r.findAllMatchIn(first).size == 1
        )
        assert(
          "(?m)^  FormalIdentityLeaf #\\(".r.findAllMatchIn(first).size == 2
        )
        assert(first.contains("module FormalIdentityLeaf #("))
        assert(first.contains("parameter integer WIDTH = 8"))
        assert(first.contains("module FormalIdentityTop #("))
        assert(first.contains("parameter integer LEFT_WIDTH = 8"))
        assert(first.contains("parameter integer RIGHT_WIDTH = 8"))
        assert(first.contains(".WIDTH(LEFT_WIDTH)"))
        assert(first.contains(".WIDTH(RIGHT_WIDTH)"))
        assert(hasDeclarationWidth(first, "din", "[WIDTH-1:0]"))
        assert(hasDeclarationWidth(first, "dout", "[WIDTH-1:0]"))
        assert(hasDeclarationWidth(first, "leftIn", "[LEFT_WIDTH-1:0]"))
        assert(hasDeclarationWidth(first, "leftOut", "[LEFT_WIDTH-1:0]"))
        assert(hasDeclarationWidth(first, "rightIn", "[RIGHT_WIDTH-1:0]"))
        assert(hasDeclarationWidth(first, "rightOut", "[RIGHT_WIDTH-1:0]"))
        assert(hasDeclarationWidth(first, "left_dout", "[LEFT_WIDTH-1:0]"))
        assert(hasDeclarationWidth(first, "right_dout", "[RIGHT_WIDTH-1:0]"))
        assert(!first.contains("FormalIdentityLeaf_1"))
        assert(!first.contains("ParamRTL"))
      }
    }
  }

  test("one concrete instance actual binds the canonical formal to a literal") {
    withTemporaryDirectory { directory =>
      val actual = HdlInt.literal(BigInt(8))
      val verilog = emitComponent(
        directory,
        "formal_literal.v",
        new LiteralTop(actual)
      )
      assert(verilog.contains("module FormalLiteralTop ("))
      assert(!verilog.contains("module FormalLiteralTop #("))
      assert(verilog.contains(".WIDTH(8)"))
      assert(verilog.contains("module FormalIdentityLeaf #("))
    }
  }

  test("an instance actual must fit the explicit formal domain") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "formal_actual_domain.v",
        "MORPH-FRONTEND-FORMAL-PARAMETER-ACTUAL-DOMAIN-UNSUPPORTED"
      ) {
        val actual = HdlInt.param("ACTUAL_WIDTH", default = 8, min = 1, max = 32)
        new BoundedTop(actual, actual, leftMaximum = 16, rightMaximum = 16)
      }
    }
  }

  test("one formal declaration rejects incompatible instance defaults") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "formal_default_conflict.v",
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-DEFAULT-CONFLICT"
      ) {
        val left = HdlInt.param("LEFT_WIDTH", default = 8, min = 1, max = 16)
        val right = HdlInt.param("RIGHT_WIDTH", default = 9, min = 1, max = 16)
        new Top(left, right)
      }
    }
  }

  test("one formal declaration rejects incompatible explicit domains") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "formal_domain_conflict.v",
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-DOMAIN-CONFLICT"
      ) {
        val left = HdlInt.param("LEFT_WIDTH", default = 8, min = 1, max = 16)
        val right = HdlInt.param("RIGHT_WIDTH", default = 8, min = 1, max = 32)
        new BoundedTop(left, right, leftMaximum = 16, rightMaximum = 32)
      }
    }
  }

  test("multiple explicit declaration call sites cannot claim one formal slot") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "formal_duplicate.v",
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-DUPLICATE-DECLARATION"
      ) {
        val actual = HdlInt.param("ACTUAL_WIDTH", default = 8, min = 1, max = 16)
        new DuplicateFormalTop(actual)
      }
    }
  }

  test("one declaration identity cannot map a formal slot to two actuals") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "formal_ambiguous.v",
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-AMBIGUOUS"
      ) {
        val input = HdlInt.param("INPUT_WIDTH", default = 8, min = 1, max = 16)
        val output = HdlInt.param("OUTPUT_WIDTH", default = 8, min = 1, max = 32)
        new AmbiguousFormalTop(input, output)
      }
    }
  }

  test("explicit constructor actual must agree with direct parent connections") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "formal_connection_conflict.v",
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-CONNECTION-CONFLICT"
      ) {
        val formalActual =
          HdlInt.param("FORMAL_ACTUAL", default = 8, min = 1, max = 16)
        val connected =
          HdlInt.param("CONNECTED_WIDTH", default = 8, min = 1, max = 16)
        new MismatchedConnectionTop(formalActual, connected)
      }
    }
  }

  test("formal parameter names are always explicit portable identifiers") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "formal_name.v",
        "MORPH-FRONTEND-FORMAL-PARAMETER-NAME-INVALID"
      ) {
        val actual = HdlInt.param("ACTUAL_WIDTH", default = 8, min = 1, max = 16)
        new Component {
          setDefinitionName("InvalidFormalName")
          private val width = formalParam(actual, "BAD-WIDTH")
          val din = in(morphhdl.frontend.Bits(width bits))
          val dout = out(morphhdl.frontend.Bits(width bits))
          dout := din
        }
      }
    }
  }

  private def emitComponent(
      directory: Path,
      filename: String,
      component: => Component
  ): String = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    MorphVerilog(config)(component)
    new String(
      Files.readAllBytes(directory.resolve(filename)),
      StandardCharsets.UTF_8
    )
  }

  private def expectFailure(
      directory: Path,
      filename: String,
      code: String
  )(component: => Component): Unit = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    MorphVerilog.tryGenerate(config)(component) match {
      case Left(failure) => assert(failure.detail.contains(code), failure.detail)
      case Right(report) => fail(s"Expected $code, received $report")
    }
  }

  private def hasDeclarationWidth(
      verilog: String,
      name: String,
      range: String
  ): Boolean = {
    val pattern =
      (java.util.regex.Pattern.quote(range) + "\\s+" +
        java.util.regex.Pattern.quote(name) + "(?=\\s*(?:[,;]|\\)))").r
    pattern.findFirstIn(verilog).nonEmpty
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-formal-identity-test-")
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
