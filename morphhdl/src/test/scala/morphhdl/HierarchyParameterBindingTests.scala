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
    val din = in(morphhdl.frontend.Bits(width bits))
    val dout = out(morphhdl.frontend.Bits(width bits))
    dout := din
  }

  final class Top(
      leftWidth: HdlInt,
      rightWidth: HdlInt,
      leafWidth: HdlInt
  ) extends Component {
    setDefinitionName("NativeHierarchyTop")
    val leftIn = in(morphhdl.frontend.Bits(leftWidth bits))
    val leftOut = out(morphhdl.frontend.Bits(leftWidth bits))
    val rightIn = in(morphhdl.frontend.Bits(rightWidth bits))
    val rightOut = out(morphhdl.frontend.Bits(rightWidth bits))

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
    val din = in(morphhdl.frontend.Bits(8 bits))
    val dout = out(morphhdl.frontend.Bits(8 bits))
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
    val din = in(morphhdl.frontend.Bits(leftWidth bits))
    val dout = out(morphhdl.frontend.Bits(rightWidth bits))
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
    val leftIn = in(morphhdl.frontend.Bits(leftWidth bits))
    val leftOut = out(morphhdl.frontend.Bits(leftWidth bits))
    val rightIn = in(morphhdl.frontend.Bits(rightWidth bits))
    val rightOut = out(morphhdl.frontend.Bits(rightWidth bits))
    val left = new Leaf(leftLeafWidth)
    left.setName("left")
    val right = new Leaf(rightLeafWidth)
    right.setName("right")
    left.din := leftIn
    leftOut := left.dout
    right.din := rightIn
    rightOut := right.dout
  }

  private final class DerivedWidthLeaf(depth: HdlInt) extends Component {
    setDefinitionName("DerivedWidthHierarchyLeaf")
    val direct = in(morphhdl.frontend.Bits(depth bits))
    val occupancy = out(
      morphhdl.frontend.UInt((depth + 1).ceilLog2 bits)
    )
    occupancy := 0
  }

  private final class DerivedWidthTop(depth: HdlInt) extends Component {
    setDefinitionName("DerivedWidthHierarchyTop")
    val direct = in(morphhdl.frontend.Bits(depth bits))
    val occupancy = out(UInt(4 bits))
    val lexicalMarker = Bool().setName("lexical_marker").dontSimplifyIt()
    lexicalMarker := False
    lexicalMarker.addAttribute(
      "morphhdl_note",
      "morphhdl_address_width(DEPTH) remains attribute text"
    )
    val leaf = new DerivedWidthLeaf(depth)
    leaf.setName("leaf")
    leaf.addComment("morphhdl_unknown_helper( remains comment text")
    leaf.direct := direct
    occupancy := leaf.occupancy.resized
  }

  private final class UnconsumedDerivedWidthTop(depth: HdlInt)
      extends Component {
    setDefinitionName("UnconsumedDerivedWidthHierarchyTop")
    val direct = in(morphhdl.frontend.Bits(depth bits))
    val observed = out(Bool())
    val leaf = new DerivedWidthLeaf(depth)
    leaf.setName("leaf")
    leaf.direct := direct
    observed := direct.orR
  }

  private final class PartiallyConsumedDerivedWidthTop(depth: HdlInt)
      extends Component {
    setDefinitionName("PartiallyConsumedDerivedWidthHierarchyTop")
    val direct = in(morphhdl.frontend.Bits(depth bits))
    val observed = out(Bool())
    val leaf = new DerivedWidthLeaf(depth)
    leaf.setName("leaf")
    leaf.direct := direct
    observed := leaf.occupancy(0)
  }

  private final class TwoUnconsumedDerivedWidthTop(
      leftDepth: HdlInt,
      rightDepth: HdlInt,
      childDepth: HdlInt
  ) extends Component {
    setDefinitionName("TwoUnconsumedDerivedWidthHierarchyTop")
    val leftDirect = in(morphhdl.frontend.Bits(leftDepth bits))
    val rightDirect = in(morphhdl.frontend.Bits(rightDepth bits))
    val observed = out(Bool())

    val left = new DerivedWidthLeaf(childDepth)
    left.setName("left")
    left.direct := leftDirect
    val right = new DerivedWidthLeaf(childDepth)
    right.setName("right")
    right.direct := rightDirect
    observed := leftDirect.orR ^ rightDirect.orR
  }

  private final class DerivedInputLeaf(depth: HdlInt) extends Component {
    setDefinitionName("DerivedInputHierarchyLeaf")
    val direct = in(morphhdl.frontend.Bits(depth bits))
    val address = in(morphhdl.frontend.UInt((depth + 1).ceilLog2 bits))
    val observed = out(Bool())
    observed := address.orR
  }

  private final class FixedDerivedInputTop(depth: HdlInt) extends Component {
    setDefinitionName("FixedDerivedInputHierarchyTop")
    val direct = in(morphhdl.frontend.Bits(depth bits))
    val fixedAddress = in(UInt(3 bits))
    val observed = out(Bool())
    val leaf = new DerivedInputLeaf(depth)
    leaf.setName("leaf")
    leaf.direct := direct
    leaf.address := fixedAddress
    observed := leaf.observed
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

  test("derived child-output width is instantiated in the parent connection") {
    withTemporaryDirectory { directory =>
      val depth =
        HdlInt.param("PARENT_DEPTH", default = 5, min = 1, max = 8)
      val verilog = emitComponent(
        directory,
        "derived_width_hierarchy.v",
        new DerivedWidthTop(depth)
      )
      val top =
        "(?ms)^module DerivedWidthHierarchyTop\\b.*?^endmodule\\b".r
          .findFirstIn(verilog)
          .getOrElse(fail("Derived-width hierarchy top is missing"))
      val declarationRange =
        "(?m)^\\s*wire\\s+\\[([^\\]]+)\\]\\s+leaf_occupancy\\s*;\\s*$".r
          .findFirstMatchIn(top)
          .map(_.group(1).trim)
          .getOrElse(fail("Derived child-output declaration is missing"))
      val connectionRange =
        "(?s)\\.occupancy\\s*\\(\\s*leaf_occupancy\\[([^\\]]+)\\]".r
          .findFirstMatchIn(top)
          .map(_.group(1).trim)
          .getOrElse(fail("Derived child-output instance connection is missing"))

      assert(verilog.contains(".PARENT_DEPTH(PARENT_DEPTH)"))
      assert(declarationRange == connectionRange)
      assert(declarationRange.contains("PARENT_DEPTH"))
      assert(declarationRange.contains("clog2"))
      assert(hasDeclarationWidth(top, "occupancy", "[3:0]"))
      assert(top.contains("assign occupancy ="))
      assert(top.contains("morphhdl_unknown_helper( remains comment text"))
      assert(
        top.contains(
          "morphhdl_address_width(DEPTH) remains attribute text"
        )
      )
    }
  }

  test("an unconsumed derived child output rewrites its native private carrier") {
    withTemporaryDirectory { directory =>
      val depth =
        HdlInt.param("PARENT_DEPTH", default = 5, min = 1, max = 8)
      val verilog = emitComponent(
        directory,
        "unconsumed_derived_width_hierarchy.v",
        new UnconsumedDerivedWidthTop(depth)
      )
      val top =
        "(?ms)^module UnconsumedDerivedWidthHierarchyTop\\b.*?^endmodule\\b".r
          .findFirstIn(verilog)
          .getOrElse(fail("Unconsumed derived-width hierarchy top is missing"))

      assert(verilog.contains(".PARENT_DEPTH(PARENT_DEPTH)"))
      assert(top.contains("DerivedWidthHierarchyLeaf #("))
      assert("(?s)\\.direct\\s*\\(".r.findFirstIn(top).nonEmpty)
      val declarationRange =
        "(?m)^\\s*wire\\s+\\[([^\\]]+)\\]\\s+leaf_occupancy\\s*;\\s*$".r
          .findFirstMatchIn(top)
          .map(_.group(1).trim)
          .getOrElse(fail("Unconsumed child-output declaration is missing"))
      val connectionRange =
        "(?s)\\.occupancy\\s*\\(\\s*leaf_occupancy\\[([^\\]]+)\\]".r
          .findFirstMatchIn(top)
          .map(_.group(1).trim)
          .getOrElse(fail("Unconsumed child-output connection is missing"))
      assert(declarationRange == connectionRange)
      assert(declarationRange.contains("PARENT_DEPTH"))
      assert(declarationRange.contains("clog2"))
    }
  }

  test("unconsumed canonical child outputs use each parent binding") {
    withTemporaryDirectory { directory =>
      val leftDepth =
        HdlInt.param("LEFT_DEPTH", default = 5, min = 1, max = 8)
      val rightDepth =
        HdlInt.param("RIGHT_DEPTH", default = 5, min = 2, max = 16)
      val childDepth =
        HdlInt.param("CHILD_DEPTH", default = 5, min = 1, max = 16)
      val verilog = emitComponent(
        directory,
        "two_unconsumed_derived_width_hierarchy.v",
        new TwoUnconsumedDerivedWidthTop(
          leftDepth,
          rightDepth,
          childDepth
        )
      )
      val top =
        "(?ms)^module TwoUnconsumedDerivedWidthHierarchyTop\\b.*?^endmodule\\b".r
          .findFirstIn(verilog)
          .getOrElse(fail("Two-child derived-width hierarchy top is missing"))

      def rangeOf(signal: String): String =
        ("(?m)^\\s*wire\\s+\\[([^\\]]+)\\]\\s+" +
          java.util.regex.Pattern.quote(signal) + "\\s*;\\s*$").r
          .findFirstMatchIn(top)
          .map(_.group(1).trim)
          .getOrElse(fail(s"Declaration for $signal is missing"))

      val leftRange = rangeOf("left_occupancy")
      val rightRange = rangeOf("right_occupancy")
      assert(leftRange.contains("LEFT_DEPTH"))
      assert(!leftRange.contains("RIGHT_DEPTH"))
      assert(rightRange.contains("RIGHT_DEPTH"))
      assert(!rightRange.contains("LEFT_DEPTH"))
      assert(verilog.contains(".CHILD_DEPTH(LEFT_DEPTH)"))
      assert(verilog.contains(".CHILD_DEPTH(RIGHT_DEPTH)"))
      assert(
        ("(?s)\\.occupancy\\s*\\(\\s*left_occupancy\\[" +
          java.util.regex.Pattern.quote(leftRange) + "\\]").r
          .findFirstIn(top)
          .nonEmpty
      )
      assert(
        ("(?s)\\.occupancy\\s*\\(\\s*right_occupancy\\[" +
          java.util.regex.Pattern.quote(rightRange) + "\\]").r
          .findFirstIn(top)
          .nonEmpty
      )
    }
  }

  test("an unconsumed-output allowance never accepts a partial consumer") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "partially_consumed_derived_width_hierarchy.v",
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-CONNECTION-UNSUPPORTED"
      ) {
        val depth =
          HdlInt.param("PARENT_DEPTH", default = 5, min = 1, max = 8)
        new PartiallyConsumedDerivedWidthTop(depth)
      }
    }
  }

  test("derived widths preserve a helper-spelled formal as a value") {
    withTemporaryDirectory { directory =>
      val depth = HdlInt.param(
        "morphhdl_ceil_log2",
        default = 5,
        min = 1,
        max = 8
      )
      val verilog = emitComponent(
        directory,
        "derived_width_helper_name.v",
        new DerivedWidthTop(depth)
      )
      assert(verilog.contains("parameter integer morphhdl_ceil_log2 = 5"))
      assert(verilog.contains("clog2"))
      assert(!verilog.contains("(morphhdl_ceil_log2)("))
    }
  }

  test("a derived child input rejects a fixed direct parent connection") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "derived_input_fixed_parent.v",
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-WIDTH-MISMATCH"
      ) {
        val depth =
          HdlInt.param("PARENT_DEPTH", default = 5, min = 1, max = 8)
        new FixedDerivedInputTop(depth)
      }
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
