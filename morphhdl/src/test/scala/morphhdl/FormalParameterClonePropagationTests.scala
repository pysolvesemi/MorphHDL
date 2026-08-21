package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.{formalParam, HdlInt}

object FormalParameterClonePropagationSmoke {
  final class Leaf(actualWidth: HdlInt) extends Component {
    setDefinitionName("FormalCloneLeaf")

    @dontName
    private val width = formalParam(actualWidth, "WIDTH")

    @dontName
    private val prototype = morphhdl.frontend.Bits(width bits)

    val clk = in(Bool())
    val din = in(morphhdl.frontend.cloneOf(prototype))
    val dout = out(morphhdl.frontend.cloneOf(prototype))

    @dontName
    private val hardType =
      morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits))

    @dontName
    private val hardValue = hardType()

    @dontName
    private val registerClockDomain = ClockDomain(clock = clk)

    private val registerArea = new ClockingArea(registerClockDomain) {
      @dontName
      val state = morphhdl.frontend.Reg(morphhdl.frontend.Bits(width bits))
      state := hardValue
    }

    @dontName
    private val values =
      morphhdl.frontend.Vec(morphhdl.frontend.Bits(width bits), 2)

    prototype := din
    hardValue := prototype
    values(0) := registerArea.state
    values(1) := values(0)
    dout := values(1)

    requireFormal(hardValue, "HardType instance")
    requireFormal(registerArea.state, "Reg result")
    requireFormal(values(0), "Vec element 0")
    requireFormal(values(1), "Vec element 1")
  }

  final class Top(leftWidth: HdlInt, rightWidth: HdlInt) extends Component {
    setDefinitionName("FormalCloneTop")

    val clk = in(Bool())
    val leftIn = in(morphhdl.frontend.Bits(leftWidth bits))
    val leftOut = out(morphhdl.frontend.Bits(leftWidth bits))
    val rightIn = in(morphhdl.frontend.Bits(rightWidth bits))
    val rightOut = out(morphhdl.frontend.Bits(rightWidth bits))

    val left = new Leaf(leftWidth)
    left.setName("left")
    val right = new Leaf(rightWidth)
    right.setName("right")

    left.clk := clk
    left.din := leftIn
    leftOut := left.dout
    right.clk := clk
    right.din := rightIn
    rightOut := right.dout
  }

  final class MismatchedTop(
      formalActual: HdlInt,
      connectedWidth: HdlInt
  ) extends Component {
    setDefinitionName("FormalCloneMismatchTop")

    val clk = in(Bool())
    val din = in(morphhdl.frontend.Bits(connectedWidth bits))
    val dout = out(morphhdl.frontend.Bits(connectedWidth bits))

    val leaf = new Leaf(formalActual)
    leaf.setName("leaf")
    leaf.clk := clk
    leaf.din := din
    dout := leaf.dout
  }

  def component(): Component = {
    val leftWidth = HdlInt.param("LEFT_WIDTH", default = 8, min = 1, max = 16)
    val rightWidth = HdlInt.param("RIGHT_WIDTH", default = 8, min = 2, max = 32)
    new Top(leftWidth, rightWidth)
  }

  private def requireFormal(data: Data, role: String): Unit = {
    val leaves = data.flatten.toVector
    require(leaves.nonEmpty, s"$role has no flattened leaves")
    leaves.foreach { leaf =>
      require(
        ExternalFormalParameterRegistry
          .bindingOf(leaf)
          .exists(_.formal.name == "WIDTH"),
        s"$role lost explicit formal binding through a shape-copy path"
      )
    }
  }
}

class FormalParameterClonePropagationTests extends AnyFunSuite {
  import FormalParameterClonePropagationSmoke._

  test("clone-derived ports and native shape copies retain per-instance actuals") {
    withTemporaryDirectory { directory =>
      val verilog = emitComponent(
        directory,
        "formal_parameter_clone_propagation.v",
        component()
      )

      assert(
        "(?m)^module FormalCloneLeaf\\b".r.findAllMatchIn(verilog).size == 1
      )
      assert(
        "(?m)^  FormalCloneLeaf #\\(".r.findAllMatchIn(verilog).size == 2
      )
      assert(verilog.contains("module FormalCloneLeaf #("))
      assert(verilog.contains("parameter integer WIDTH = 8"))
      assert(verilog.contains("module FormalCloneTop #("))
      assert(verilog.contains("parameter integer LEFT_WIDTH = 8"))
      assert(verilog.contains("parameter integer RIGHT_WIDTH = 8"))
      assert(verilog.contains(".WIDTH(LEFT_WIDTH)"))
      assert(verilog.contains(".WIDTH(RIGHT_WIDTH)"))
      assert(hasDeclarationWidth(verilog, "din", "[WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "dout", "[WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "leftIn", "[LEFT_WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "leftOut", "[LEFT_WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "rightIn", "[RIGHT_WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "rightOut", "[RIGHT_WIDTH-1:0]"))
      assert(!verilog.contains("FormalCloneLeaf_1"))
    }
  }

  test("clone-derived formal ports keep constructor-actual conflict checks") {
    withTemporaryDirectory { directory =>
      val formalActual =
        HdlInt.param("FORMAL_ACTUAL", default = 8, min = 1, max = 16)
      val connected =
        HdlInt.param("CONNECTED_WIDTH", default = 8, min = 1, max = 16)

      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "formal_clone_connection_conflict.v"
      MorphVerilog.tryGenerate(config)(new MismatchedTop(formalActual, connected)) match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-CONNECTION-CONFLICT"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(
            "Expected clone-derived formal connection conflict, received " + report
          )
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
    val directory = Files.createTempDirectory("morphhdl-formal-clone-test-")
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
