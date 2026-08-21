package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.{formalComponent, formalRegion, HdlInt}

object ExternalNativeIntFormalizationSmoke {
  /** Untouched native-looking component: its public geometry argument is Int. */
  final class NativeLeaf(width: Int) extends Component {
    setDefinitionName("ExternalNativeIntLeaf")

    val din = in(UInt(width bits))
    val dout = out(UInt(width bits))

    // Same concrete witness as the test parameters, but deliberately outside
    // formalComponent's exact geometry selection.
    val fixedIn = in(UInt(8 bits))
    val fixedOut = out(UInt(8 bits))

    dout := din
    fixedOut := fixedIn
  }

  final class Top(leftWidth: HdlInt, rightWidth: HdlInt) extends Component {
    setDefinitionName("ExternalNativeIntTop")

    val leftIn = in(formalRegion(leftWidth)(width => UInt(width bits)))
    val leftOut = out(formalRegion(leftWidth)(width => UInt(width bits)))
    val rightIn = in(formalRegion(rightWidth)(width => UInt(width bits)))
    val rightOut = out(formalRegion(rightWidth)(width => UInt(width bits)))

    val leftFixedIn = in(UInt(8 bits))
    val leftFixedOut = out(UInt(8 bits))
    val rightFixedIn = in(UInt(8 bits))
    val rightFixedOut = out(UInt(8 bits))

    val left = formalComponent(
      leftWidth,
      "WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(32)
    )(width => new NativeLeaf(width))(leaf => Vector(leaf.din, leaf.dout))
    left.setName("left")

    val right = formalComponent(
      rightWidth,
      "WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(32)
    )(width => new NativeLeaf(width))(leaf => Vector(leaf.din, leaf.dout))
    right.setName("right")

    left.din := leftIn
    leftOut := left.dout
    left.fixedIn := leftFixedIn
    leftFixedOut := left.fixedOut

    right.din := rightIn
    rightOut := right.dout
    right.fixedIn := rightFixedIn
    rightFixedOut := right.fixedOut
  }

  final case class Emission(verilog: String, top: Top)

  def component(): Top = {
    val leftWidth =
      HdlInt.param("LEFT_WIDTH", default = 8, min = 1, max = 16)
    val rightWidth =
      HdlInt.param("RIGHT_WIDTH", default = 8, min = 2, max = 32)
    new Top(leftWidth, rightWidth)
  }

  def emit(directory: Path, filename: String): Emission = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    var captured: Top = null
    MorphVerilog(config) {
      captured = component()
      captured
    }
    Emission(
      new String(
        Files.readAllBytes(directory.resolve(filename)),
        StandardCharsets.UTF_8
      ),
      captured
    )
  }
}

class ExternalNativeIntFormalizationTests extends AnyFunSuite {
  import ExternalNativeIntFormalizationSmoke._

  private final class MismatchedLeaf(width: Int) extends Component {
    setDefinitionName("ExternalNativeIntMismatchedLeaf")
    val din = in(UInt((width + 1) bits))
    val dout = out(UInt((width + 1) bits))
    dout := din
  }

  private final class MismatchedTop(actual: HdlInt) extends Component {
    setDefinitionName("ExternalNativeIntMismatchedTop")
    val leaf = formalComponent(
      actual,
      "WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(32)
    )(width => new MismatchedLeaf(width))(child => Vector(child.din, child.dout))
  }

  private final class EmptySelectionTop(actual: HdlInt) extends Component {
    setDefinitionName("ExternalNativeIntEmptySelectionTop")
    val leaf = formalComponent(
      actual,
      "WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(32)
    )(width => new NativeLeaf(width))(_ => Vector.empty)
  }

  private final class ForeignSelectionTop(actual: HdlInt) extends Component {
    setDefinitionName("ExternalNativeIntForeignSelectionTop")
    val parentPort = in(formalRegion(actual)(width => UInt(width bits)))
    val leaf = formalComponent(
      actual,
      "WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(32)
    )(width => new NativeLeaf(width))(_ => Vector(parentPort))
  }

  private final class ConflictingFormalizationTop(
      left: HdlInt,
      right: HdlInt
  ) extends Component {
    setDefinitionName("ExternalNativeIntConflictingFormalizationTop")

    // A malformed caller tries to attach one formal slot twice to the exact
    // same native child while supplying different symbolic actuals. Both
    // defaults are 8, proving that witness equality cannot hide the conflict.
    private val shared = new NativeLeaf(8)

    val first = formalComponent(
      left,
      "WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(32)
    )(_ => shared)(child => Vector(child.din, child.dout))

    val second = formalComponent(
      right,
      "WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(32)
    )(_ => shared)(child => Vector(child.din, child.dout))
  }

  test("native Int adapters retain exact formal geometry and canonical hierarchy") {
    withTemporaryDirectory { firstDirectory =>
      withTemporaryDirectory { secondDirectory =>
        val first = emit(firstDirectory, "external_native_int_formalization.v")
        val second = emit(secondDirectory, "external_native_int_formalization.v")
        val verilog = first.verilog

        assert(verilog == second.verilog)
        assert(
          "(?m)^module ExternalNativeIntLeaf\\b".r
            .findAllMatchIn(verilog)
            .size == 1
        )
        assert(
          "(?m)^  ExternalNativeIntLeaf #\\(".r
            .findAllMatchIn(verilog)
            .size == 2
        )
        assert(verilog.contains("module ExternalNativeIntLeaf #("))
        assert(verilog.contains("parameter integer WIDTH = 8"))
        assert(verilog.contains("module ExternalNativeIntTop #("))
        assert(verilog.contains("parameter integer LEFT_WIDTH = 8"))
        assert(verilog.contains("parameter integer RIGHT_WIDTH = 8"))
        assert(verilog.contains(".WIDTH(LEFT_WIDTH)"))
        assert(verilog.contains(".WIDTH(RIGHT_WIDTH)"))

        assert(hasDeclarationWidth(verilog, "din", "[WIDTH-1:0]"))
        assert(hasDeclarationWidth(verilog, "dout", "[WIDTH-1:0]"))
        assert(hasDeclarationWidth(verilog, "fixedIn", "[7:0]"))
        assert(hasDeclarationWidth(verilog, "fixedOut", "[7:0]"))
        assert(hasDeclarationWidth(verilog, "leftIn", "[LEFT_WIDTH-1:0]"))
        assert(hasDeclarationWidth(verilog, "leftOut", "[LEFT_WIDTH-1:0]"))
        assert(hasDeclarationWidth(verilog, "rightIn", "[RIGHT_WIDTH-1:0]"))
        assert(hasDeclarationWidth(verilog, "rightOut", "[RIGHT_WIDTH-1:0]"))
        assert(hasDeclarationWidth(verilog, "leftFixedIn", "[7:0]"))
        assert(hasDeclarationWidth(verilog, "rightFixedIn", "[7:0]"))
        assert(!verilog.contains("ExternalNativeIntLeaf_1"))
        assert(!verilog.contains("ParamRTL"))

        assertExactIdentity(first.top)
      }
    }
  }

  test("equal concrete witnesses with distinct symbolic actuals never alias") {
    withTemporaryDirectory { directory =>
      val emission = emit(directory, "external_native_int_identity.v")
      val left = ExternalNativeIntFormalizationRegistry
        .componentRecordsOf(emission.top.left)
        .head
        .binding
        .actual
      val right = ExternalNativeIntFormalizationRegistry
        .componentRecordsOf(emission.top.right)
        .head
        .binding
        .actual

      assert(left.default == 8)
      assert(right.default == 8)
      assert(left.verilog == "LEFT_WIDTH")
      assert(right.verilog == "RIGHT_WIDTH")
      assert(left != right)
    }
  }

  test("literal actuals keep a parameterized child but bind it to a literal") {
    withTemporaryDirectory { directory =>
      val literal = HdlInt.literal(BigInt(8))
      val verilog = emitComponent(
        directory,
        "external_native_int_literal.v",
        new Top(literal, literal),
        morph = true
      )
      assert(verilog.contains("module ExternalNativeIntLeaf #("))
      assert(
        "\\.WIDTH\\(8\\)".r.findAllMatchIn(verilog).size == 2,
        verilog
      )
      assert(!verilog.contains("parameter integer LEFT_WIDTH"))
      assert(!verilog.contains("parameter integer RIGHT_WIDTH"))
    }
  }

  test("ordinary SpinalVerilog ignores external metadata and remains concrete") {
    withTemporaryDirectory { directory =>
      val literal = HdlInt.literal(BigInt(8))
      val verilog = emitComponent(
        directory,
        "external_native_int_legacy.v",
        new Top(literal, literal),
        morph = false
      )
      assert(!verilog.contains("parameter integer"))
      assert(hasDeclarationWidth(verilog, "din", "[7:0]"))
      assert(hasDeclarationWidth(verilog, "dout", "[7:0]"))
      assert(hasDeclarationWidth(verilog, "leftIn", "[7:0]"))
      assert(hasDeclarationWidth(verilog, "rightIn", "[7:0]"))
    }
  }

  test("formalComponent rejects selected geometry whose witness disagrees") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "external_native_int_width_mismatch.v",
        "MORPH-FRONTEND-FORMAL-REGION-WITNESS-MISMATCH"
      ) {
        val actual = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
        new MismatchedTop(actual)
      }
    }
  }

  test("formalComponent rejects an empty exact-geometry selection") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "external_native_int_empty_selection.v",
        "MORPH-FRONTEND-FORMAL-COMPONENT-GEOMETRY-EMPTY"
      ) {
        val actual = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
        new EmptySelectionTop(actual)
      }
    }
  }

  test("formalComponent rejects Data owned by another component identity") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "external_native_int_foreign_selection.v",
        "MORPH-FRONTEND-FORMAL-REGION-OWNER-MISMATCH"
      ) {
        val actual = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
        new ForeignSelectionTop(actual)
      }
    }
  }

  test("one exact component rejects conflicting actuals for one formal slot") {
    withTemporaryDirectory { directory =>
      expectFailure(
        directory,
        "external_native_int_conflicting_formalization.v",
        "MORPH-FRONTEND-FORMAL-REGION-CONFLICT"
      ) {
        val left =
          HdlInt.param("LEFT_WIDTH", default = 8, min = 1, max = 16)
        val right =
          HdlInt.param("RIGHT_WIDTH", default = 8, min = 2, max = 32)
        new ConflictingFormalizationTop(left, right)
      }
    }
  }

  private def assertExactIdentity(top: Top): Unit = {
    val leftRecords =
      ExternalNativeIntFormalizationRegistry.componentRecordsOf(top.left)
    val rightRecords =
      ExternalNativeIntFormalizationRegistry.componentRecordsOf(top.right)

    assert(leftRecords.size == 1)
    assert(rightRecords.size == 1)
    assert(leftRecords.head.binding.formal.name == "WIDTH")
    assert(rightRecords.head.binding.formal.name == "WIDTH")
    assert(leftRecords.head.binding.actual.verilog == "LEFT_WIDTH")
    assert(rightRecords.head.binding.actual.verilog == "RIGHT_WIDTH")
    assert(leftRecords.head.regionCount == 2)
    assert(rightRecords.head.regionCount == 2)

    val leftBindings = ExternalFormalParameterRegistry.bindingsOf(top.left)
    val rightBindings = ExternalFormalParameterRegistry.bindingsOf(top.right)
    assert(leftBindings.size == 1)
    assert(rightBindings.size == 1)
    assert(leftBindings.head.actual.verilog == "LEFT_WIDTH")
    assert(rightBindings.head.actual.verilog == "RIGHT_WIDTH")
    assert(ExternalFormalParameterRegistry.bindingsOf(top).isEmpty)
    assert(ExternalNativeIntFormalizationRegistry.componentRecordsOf(top).isEmpty)

    assert(
      ExternalNativeIntFormalizationRegistry
        .regionOf(top.leftIn)
        .exists(_.expression.verilog == "LEFT_WIDTH")
    )
    assert(
      ExternalNativeIntFormalizationRegistry
        .regionOf(top.rightIn)
        .exists(_.expression.verilog == "RIGHT_WIDTH")
    )
    assert(
      ExternalNativeIntFormalizationRegistry
        .regionOf(top.left.din)
        .flatMap(_.formalBinding)
        .exists(_.actual.verilog == "LEFT_WIDTH")
    )
    assert(
      ExternalNativeIntFormalizationRegistry
        .regionOf(top.right.din)
        .flatMap(_.formalBinding)
        .exists(_.actual.verilog == "RIGHT_WIDTH")
    )

    // Equal concrete widths outside the exact selections remain unassociated.
    assert(
      ExternalNativeIntFormalizationRegistry.regionOf(top.left.fixedIn).isEmpty
    )
    assert(
      ExternalNativeIntFormalizationRegistry.regionOf(top.right.fixedIn).isEmpty
    )
    assert(
      ExternalNativeIntFormalizationRegistry.regionOf(top.leftFixedIn).isEmpty
    )
    assert(ParameterizedWidth.expressionOf(top.left.fixedIn).isEmpty)
    assert(ParameterizedWidth.expressionOf(top.right.fixedIn).isEmpty)
    assert(ParameterizedWidth.expressionOf(top.leftFixedIn).isEmpty)
  }

  private def emitComponent(
      directory: Path,
      filename: String,
      component: => Component,
      morph: Boolean
  ): String = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    if (morph) MorphVerilog(config)(component)
    else SpinalVerilog(config)(component)
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
    val directory = Files.createTempDirectory("morphhdl-native-int-formal-test-")
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
