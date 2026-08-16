package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.HdlInt

object HierarchyParameterBindingSmoke {
  final class Leaf(width: HdlInt) extends Component {
    setDefinitionName("NativeHierarchyLeaf")
    val din = in(Bits(width bits))
    val dout = out(Bits(width bits))
    dout := din
  }

  final class Top(
      leftWidth: HdlInt,
      rightWidth: HdlInt,
      leafWidth: HdlInt
  ) extends Component {
    setDefinitionName("NativeHierarchyTop")
    val leftIn = in(Bits(leftWidth bits))
    val leftOut = out(Bits(leftWidth bits))
    val rightIn = in(Bits(rightWidth bits))
    val rightOut = out(Bits(rightWidth bits))

    val left = new Leaf(leafWidth)
    left.setName("left")
    val right = new Leaf(leafWidth)
    right.setName("right")
    left.din := leftIn
    leftOut := left.dout
    right.din := rightIn
    rightOut := right.dout
  }

  def component(): Component = {
    val leftWidth = HdlInt.param("LEFT_WIDTH", default = 8, min = 1, max = 64)
    val rightWidth = HdlInt.param("RIGHT_WIDTH", default = 8, min = 1, max = 64)
    val leafWidth = HdlInt.param("LEAF_WIDTH", default = 8, min = 1, max = 64)
    new Top(leftWidth, rightWidth, leafWidth)
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
        "Usage: HierarchyParameterBindingSmoke <output-directory>"
      )
    }
    emit(Paths.get(args(0)), "native_hierarchy.v")
    ()
  }
}

class HierarchyParameterBindingTests extends AnyFunSuite {
  import HierarchyParameterBindingSmoke._

  private final class LiteralTop(childWidth: HdlInt) extends Component {
    setDefinitionName("ConcreteHierarchyTop")
    val din = in(Bits(8 bits))
    val dout = out(Bits(8 bits))
    val leaf = new Leaf(childWidth)
    leaf.setName("leaf")
    leaf.din := din
    dout := leaf.dout
  }

  private final class ConflictingTop(
      leftWidth: HdlInt,
      rightWidth: HdlInt,
      childWidth: HdlInt
  ) extends Component {
    setDefinitionName("ConflictingHierarchyTop")
    val din = in(Bits(leftWidth bits))
    val dout = out(Bits(rightWidth bits))
    val leaf = new Leaf(childWidth)
    leaf.setName("leaf")
    leaf.din := din
    dout := leaf.dout
  }

  private final class TwoLeafTop(
      leftWidth: HdlInt,
      rightWidth: HdlInt,
      leftLeafWidth: HdlInt,
      rightLeafWidth: HdlInt
  ) extends Component {
    setDefinitionName("TwoLeafHierarchyTop")
    val leftIn = in(Bits(leftWidth bits))
    val leftOut = out(Bits(leftWidth bits))
    val rightIn = in(Bits(rightWidth bits))
    val rightOut = out(Bits(rightWidth bits))
    val left = new Leaf(leftLeafWidth)
    left.setName("left")
    val right = new Leaf(rightLeafWidth)
    right.setName("right")
    left.din := leftIn
    leftOut := left.dout
    right.din := rightIn
    rightOut := right.dout
  }

  test("ordinary hierarchy emits one canonical child with inferred named bindings") {
    withTemporaryDirectory { directory =>
      val verilog = emit(directory, "native_hierarchy.v")
      val childParameter =
        "(?s)module NativeHierarchyLeaf #\\(\\s*parameter integer ([A-Za-z_][A-Za-z0-9_]*) = 8\\s*\\)".r
          .findFirstMatchIn(verilog)
          .map(_.group(1))
          .getOrElse(fail("NativeHierarchyLeaf parameter header is missing"))

      assert(childParameter == "LEAF_WIDTH")
      assert(verilog.contains("module NativeHierarchyTop #("))
      assert(verilog.contains("parameter integer LEFT_WIDTH = 8"))
      assert(verilog.contains("parameter integer RIGHT_WIDTH = 8"))
      assert(
        "(?m)^module NativeHierarchyLeaf\\b".r.findAllMatchIn(verilog).size == 1
      )
      assert(
        "(?m)^  NativeHierarchyLeaf #\\(".r.findAllMatchIn(verilog).size == 2
      )
      assert(verilog.contains(".LEAF_WIDTH(LEFT_WIDTH)"))
      assert(verilog.contains(".LEAF_WIDTH(RIGHT_WIDTH)"))
      assert(verilog.contains("leftIn[LEFT_WIDTH-1:0]"))
      assert(verilog.contains("left_dout[LEFT_WIDTH-1:0]"))
      assert(verilog.contains("rightIn[RIGHT_WIDTH-1:0]"))
      assert(verilog.contains("right_dout[RIGHT_WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "leftIn", "[LEFT_WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "leftOut", "[LEFT_WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "rightIn", "[RIGHT_WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "rightOut", "[RIGHT_WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "left_dout", "[LEFT_WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "right_dout", "[RIGHT_WIDTH-1:0]"))
      assert(!verilog.contains("__v_"))
      assert(!verilog.contains("ParamRTL"))
    }
  }

  test("a parameterless parent binds a parameterized child to one concrete literal") {
    withTemporaryDirectory { directory =>
      val childWidth =
        HdlInt.param("LEAF_WIDTH", default = 8, min = 1, max = 64)
      val verilog = emitComponent(
        directory,
        "literal_hierarchy.v",
        new LiteralTop(childWidth)
      )
      assert(verilog.contains("module ConcreteHierarchyTop ("))
      assert(!verilog.contains("module ConcreteHierarchyTop #("))
      assert(verilog.contains("NativeHierarchyLeaf #("))
      assert(verilog.contains(".LEAF_WIDTH(8)"))
      assert(verilog.contains("module NativeHierarchyLeaf #("))
    }
  }

  test("inconsistent parent connections reject one ambiguous child binding") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "conflicting_hierarchy.v",
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-CONFLICT"
      ) {
        val leftWidth =
          HdlInt.param("LEFT_WIDTH", default = 8, min = 1, max = 64)
        val rightWidth =
          HdlInt.param("RIGHT_WIDTH", default = 8, min = 1, max = 64)
        val childWidth =
          HdlInt.param("LEAF_WIDTH", default = 8, min = 1, max = 64)
        new ConflictingTop(leftWidth, rightWidth, childWidth)
      }
    }
  }

  test("a parent binding must remain inside the complete child domain") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "hierarchy_domain.v",
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-DOMAIN-UNSUPPORTED"
      ) {
        val parentWidth =
          HdlInt.param("PARENT_WIDTH", default = 8, min = 1, max = 64)
        val childWidth =
          HdlInt.param("LEAF_WIDTH", default = 8, min = 1, max = 16)
        new ConflictingTop(parentWidth, parentWidth, childWidth)
      }
    }
  }

  test("deduplicated child definitions require one identical symbolic schema") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "hierarchy_schema.v",
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-CANONICAL-SCHEMA-CONFLICT"
      ) {
        val leftWidth =
          HdlInt.param("LEFT_WIDTH", default = 8, min = 1, max = 32)
        val rightWidth =
          HdlInt.param("RIGHT_WIDTH", default = 8, min = 1, max = 64)
        val leftLeafWidth =
          HdlInt.param("LEAF_WIDTH", default = 8, min = 1, max = 32)
        val rightLeafWidth =
          HdlInt.param("LEAF_WIDTH", default = 8, min = 1, max = 64)
        new TwoLeafTop(
          leftWidth,
          rightWidth,
          leftLeafWidth,
          rightLeafWidth
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
    val directory = Files.createTempDirectory("morphhdl-hierarchy-test-")
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
