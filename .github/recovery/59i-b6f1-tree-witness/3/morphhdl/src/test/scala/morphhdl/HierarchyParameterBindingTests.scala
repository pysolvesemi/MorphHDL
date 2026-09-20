package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.{formalParam, HdlInt}

object HierarchyParameterBindingSmoke {
  final class Leaf(width: HdlInt) extends Component {
    setDefinitionName("NativeHierarchyLeaf")
    addAttribute("keep_hierarchy", "TRUE")
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

  /** Native-Int child whose public packed width is derived from its formal. */
  final class DerivedWidthLeaf(actualDepth: HdlInt) extends Component {
    setDefinitionName("NativeHierarchyDerivedWidthLeaf")

    @dontName private val depth = formalParam(
      actualDepth,
      "DEPTH",
      minimum = BigInt(1),
      maximum = BigInt(8)
    )
    val din = in(morphhdl.frontend.UInt(depth bits))
    val occupancy = out(
      morphhdl.frontend.UInt(
        (depth + HdlInt.literal(1)).addressWidth bits
      )
    )
    occupancy := 0
  }

  /** Fixed-width parent adapter used to expose the emitted child carrier. */
  final class DerivedWidthTop(depth: HdlInt) extends Component {
    setDefinitionName("NativeHierarchyDerivedWidthTop")

    val din = in(morphhdl.frontend.UInt(depth bits))
    val occupancy = out(UInt(4 bits))
    val leaf = new DerivedWidthLeaf(depth)
    leaf.setName("leaf")

    leaf.din := din
    occupancy := leaf.occupancy.resized
  }

  /** Proves that a legal formal name cannot capture a helper call token. */
  final class DerivedWidthHelperCollisionLeaf(actualDepth: HdlInt)
      extends Component {
    setDefinitionName("NativeHierarchyDerivedWidthHelperCollisionLeaf")

    @dontName private val depth = formalParam(
      actualDepth,
      "clog2",
      minimum = BigInt(1),
      maximum = BigInt(8)
    )
    val din = in(morphhdl.frontend.UInt(depth bits))
    val occupancy = out(
      morphhdl.frontend.UInt(
        (depth + HdlInt.literal(1)).addressWidth bits
      )
    )
    occupancy := 0
  }

  final class DerivedWidthHelperCollisionTop(depth: HdlInt)
      extends Component {
    setDefinitionName("NativeHierarchyDerivedWidthHelperCollisionTop")

    val din = in(morphhdl.frontend.UInt(depth bits))
    val occupancy = out(UInt(4 bits))
    val leaf = new DerivedWidthHelperCollisionLeaf(depth)
    leaf.setName("leaf")

    leaf.din := din
    occupancy := leaf.occupancy.resized
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
        """(?m)^  \(\* keep_hierarchy = "TRUE" \*\) NativeHierarchyLeaf #\("""
          .r
          .findAllMatchIn(verilog)
          .size == 2
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

  test("derived symbolic child ports rewrite their parent carrier and connection") {
    withTemporaryDirectory { directory =>
      val depth = HdlInt.param("TOP_DEPTH", default = 5, min = 1, max = 8)
      val verilog = emitComponent(
        directory,
        "native_hierarchy_derived_width.v",
        new DerivedWidthTop(depth)
      )
      val compact = verilog.replaceAll("\\s+", "")

      assert(verilog.contains("module NativeHierarchyDerivedWidthLeaf #("))
      assert(verilog.contains("parameter integer DEPTH = 5"))
      assert(verilog.contains("module NativeHierarchyDerivedWidthTop #("))
      assert(verilog.contains("parameter integer TOP_DEPTH = 5"))
      assert(verilog.contains(".DEPTH(TOP_DEPTH)"))
      assert(
        compact.contains(
          "wire[clog2(((TOP_DEPTH)+1),1)-1:0]leaf_occupancy;"
        ),
        verilog
      )
      assert(
        compact.contains(
          ".occupancy(leaf_occupancy[clog2(((TOP_DEPTH)+1),1)-1:0])"
        ),
        verilog
      )
      assert(!compact.contains("wire[2:0]leaf_occupancy;"), verilog)
    }
  }

  test("derived symbolic child ports substitute a compound parent actual once") {
    withTemporaryDirectory { directory =>
      val base = HdlInt.param("BASE", default = 4, min = 1, max = 7)
      val verilog = emitComponent(
        directory,
        "native_hierarchy_derived_compound_width.v",
        new DerivedWidthTop(base + HdlInt.literal(1))
      )
      val compact = verilog.replaceAll("\\s+", "")

      assert(verilog.contains(".DEPTH((BASE + 1))"))
      assert(
        compact.contains(
          "wire[clog2((((BASE+1))+1),1)-1:0]leaf_occupancy;"
        ),
        verilog
      )
      assert(
        compact.contains(
          ".occupancy(leaf_occupancy[clog2((((BASE+1))+1),1)-1:0])"
        ),
        verilog
      )
    }
  }

  test("derived width substitution rejects a same-scoped helper and formal name") {
    withTemporaryDirectory { directory =>
      val depth = HdlInt.param("TOP_DEPTH", default = 5, min = 1, max = 8)
      expectFailure(
        directory,
        "native_hierarchy_derived_helper_collision.v",
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-DERIVED-WIDTH-IDENTIFIER-COLLISION"
      ) {
        new DerivedWidthHelperCollisionTop(depth)
      }
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
