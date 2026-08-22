package morphhdl

import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._

import morphhdl.frontend.{
  formalComponent,
  formalRegion,
  HdlInt,
  NativeIntShadow,
  shadowInt
}

object ExternalNativeIntShadowProvenanceSmoke {
  final class Leaf(
      width: Int,
      duplicateLocal: Boolean = false,
      deriveLocal: Boolean = false
  ) extends Component {
    setDefinitionName("ExternalNativeIntShadowLeaf")

    @dontName
    val selectedArgument: Int =
      NativeIntShadow.captureArgument(width, "width")

    @dontName
    val selectedLocal: Int =
      if (deriveLocal) shadowInt(width + 1, "selectedLocal")
      else shadowInt(width, "selectedLocal")

    if (duplicateLocal) {
      @dontName
      val conflictingLocal: Int = shadowInt(width, "selectedLocal")
    }

    val din = in(UInt(selectedLocal bits))
    val dout = out(UInt(selectedLocal bits))
    val fixed = out(UInt(8 bits))

    dout := din
    fixed := 0
  }

  final class Top(
      leftWidth: HdlInt,
      rightWidth: HdlInt,
      duplicateLocal: Boolean = false,
      deriveLocal: Boolean = false
  ) extends Component {
    setDefinitionName("ExternalNativeIntShadowTop")

    val leftIn = in(morphhdl.frontend.UInt(leftWidth bits))
    val leftOut = out(morphhdl.frontend.UInt(leftWidth bits))
    val rightIn = in(morphhdl.frontend.UInt(rightWidth bits))
    val rightOut = out(morphhdl.frontend.UInt(rightWidth bits))

    val left = formalComponent(
      leftWidth,
      "WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(32)
    )(
      value => new Leaf(value, duplicateLocal, deriveLocal)
    )(
      leaf => Vector(leaf.din, leaf.dout)
    )
    left.setName("left")

    val right = formalComponent(
      rightWidth,
      "WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(32)
    )(
      value => new Leaf(value)
    )(
      leaf => Vector(leaf.din, leaf.dout)
    )
    right.setName("right")

    left.din := leftIn
    leftOut := left.dout
    right.din := rightIn
    rightOut := right.dout
  }

  final class RegionTop(width: HdlInt) extends Component {
    setDefinitionName("ExternalNativeIntShadowRegionTop")

    val payload = out(
      formalRegion(width) { value =>
        val local = shadowInt(value, "regionLocal")
        UInt(local bits)
      }
    )
    payload := 0
  }

  final class NestedOuter(width: Int) extends Component {
    setDefinitionName("ExternalNativeIntShadowNestedOuter")

    @dontName
    val selectedArgument = NativeIntShadow.captureArgument(width, "outerWidth")
    @dontName
    val beforeNested = shadowInt(width, "outerBefore")

    val innerWidth = HdlInt.param(
      "INNER_WIDTH",
      default = 4,
      min = 1,
      max = 8
    )
    val inner = out(
      formalRegion(innerWidth) { value =>
        val local = shadowInt(value, "innerLocal")
        UInt(local bits)
      }
    )
    inner := 0

    @dontName
    val afterNested = shadowInt(width, "outerAfter")
    val din = in(UInt(width bits))
    val dout = out(UInt(width bits))
    dout := din
  }

  final class NestedTop(width: HdlInt) extends Component {
    setDefinitionName("ExternalNativeIntShadowNestedTop")

    val din = in(morphhdl.frontend.UInt(width bits))
    val dout = out(morphhdl.frontend.UInt(width bits))
    val outer = formalComponent(
      width,
      "OUTER_WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(16)
    )(
      value => new NestedOuter(value)
    )(
      value => Vector(value.din, value.dout)
    )
    outer.din := din
    dout := outer.dout
  }
}

class ExternalNativeIntShadowProvenanceTests extends AnyFunSuite {
  import ExternalNativeIntShadowProvenanceSmoke._

  test("selected native Int argument and local retain witness plus definition/actual expressions") {
    withTemporaryDirectory { directory =>
      var top: Top = null
      val verilog = emitMorph(directory, "native_int_shadow.v") {
        val left = HdlInt.param("LEFT_WIDTH", default = 8, min = 1, max = 16)
        val right = HdlInt.param("RIGHT_WIDTH", default = 8, min = 2, max = 32)
        top = new Top(left, right)
        top
      }

      assert(verilog.contains("module ExternalNativeIntShadowLeaf #("))
      assert(verilog.contains(".WIDTH(LEFT_WIDTH)"))
      assert(verilog.contains(".WIDTH(RIGHT_WIDTH)"))
      assert(!verilog.contains("ExternalNativeIntShadowLeaf_1"))

      val leftRecord = oneRecord(top.left)
      val rightRecord = oneRecord(top.right)
      assert(leftRecord.binding.actual.verilog == "LEFT_WIDTH")
      assert(rightRecord.binding.actual.verilog == "RIGHT_WIDTH")
      assert(leftRecord.slots.map(_.token.name).toSet == Set("WIDTH", "width", "selectedLocal"))
      assert(rightRecord.slots.map(_.token.name).toSet == Set("WIDTH", "width", "selectedLocal"))

      leftRecord.slots.foreach { slot =>
        assert(slot.witness == 8)
        assert(slot.definitionExpression.verilog == "WIDTH")
        assert(slot.actualExpression.verilog == "LEFT_WIDTH")
      }
      rightRecord.slots.foreach { slot =>
        assert(slot.witness == 8)
        assert(slot.definitionExpression.verilog == "WIDTH")
        assert(slot.actualExpression.verilog == "RIGHT_WIDTH")
      }

      assert(top.left.selectedArgument == 8)
      assert(top.left.selectedLocal == 8)
      assert(top.right.selectedArgument == 8)
      assert(top.right.selectedLocal == 8)
    }
  }

  test("formalRegion retains selected direct local aliases on the exact Data identity") {
    withTemporaryDirectory { directory =>
      var top: RegionTop = null
      emitMorph(directory, "native_int_shadow_region.v") {
        val width = HdlInt.param("WIDTH", default = 6, min = 1, max = 12)
        top = new RegionTop(width)
        top
      }

      val record = ExternalNativeIntShadowRegistry.regionOf(top.payload).get
      assert(record.formalBinding.isEmpty)
      assert(record.slots.map(_.token.name).toSet == Set("regionArgument", "regionLocal"))
      record.slots.foreach { slot =>
        assert(slot.witness == 6)
        assert(slot.definitionExpression.verilog == "WIDTH")
        assert(slot.actualExpression.verilog == "WIDTH")
      }
    }
  }

  test("nested boundaries preserve exact stack ownership and return to the outer scope") {
    withTemporaryDirectory { directory =>
      var top: NestedTop = null
      emitMorph(directory, "native_int_shadow_nested.v") {
        val width = HdlInt.param("TOP_WIDTH", default = 8, min = 1, max = 16)
        top = new NestedTop(width)
        top
      }

      val outer = oneRecord(top.outer)
      assert(outer.parentBoundaryToken.isEmpty)
      assert(outer.slots.map(_.token.name).toSet ==
        Set("OUTER_WIDTH", "outerWidth", "outerBefore", "outerAfter"))

      val inner = ExternalNativeIntShadowRegistry.regionOf(top.outer.inner).get
      assert(inner.parentBoundaryToken.contains(outer.boundaryToken))
      assert(inner.slots.map(_.token.name).toSet == Set("regionArgument", "innerLocal"))
      assert(inner.slots.forall(_.definitionExpression.verilog == "INNER_WIDTH"))
      assert(outer.slots.forall(_.actualExpression.verilog == "TOP_WIDTH"))
    }
  }

  test("equal concrete witnesses never alias across exact component identities") {
    withTemporaryDirectory { directory =>
      var top: Top = null
      emitMorph(directory, "native_int_shadow_equal_witness.v") {
        val left = HdlInt.param("LEFT_WIDTH", default = 8, min = 1, max = 16)
        val right = HdlInt.param("RIGHT_WIDTH", default = 8, min = 1, max = 16)
        top = new Top(left, right)
        top
      }

      val leftRecord = oneRecord(top.left)
      val rightRecord = oneRecord(top.right)
      assert(leftRecord.slots.head.actualExpression.verilog == "LEFT_WIDTH")
      assert(rightRecord.slots.head.actualExpression.verilog == "RIGHT_WIDTH")
      assert(leftRecord != rightRecord)
      assert(ExternalNativeIntShadowRegistry.componentRecordsOf(top).isEmpty)
      assert(ExternalNativeIntShadowRegistry.regionOf(top.left.fixed).isEmpty)
    }
  }

  test("ordinary SpinalVerilog keeps the native API and concrete runtime values unchanged") {
    withTemporaryDirectory { directory =>
      var top: Top = null
      val verilog = emitConcrete(directory, "native_int_shadow_concrete.v") {
        val left = HdlInt.literal(BigInt(8))
        val right = HdlInt.literal(BigInt(8))
        top = new Top(left, right)
        top
      }

      assert(!verilog.contains("parameter integer"))
      assert(verilog.contains("[7:0]"))
      assert(top.left.selectedArgument == 8)
      assert(top.left.selectedLocal == 8)
    }
  }

  test("shadow provenance replay is deterministic across Spinal re-elaboration") {
    withTemporaryDirectory { first =>
      withTemporaryDirectory { second =>
        val firstSignature = signature(first)
        val secondSignature = signature(second)
        assert(firstSignature == secondSignature)
      }
    }
  }

  test("a selected derived native Int remains fail closed until Increment 50") {
    withTemporaryDirectory { directory =>
      val failure = expectFailure(directory, "native_int_shadow_derived.v") {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
        new Top(width, width, deriveLocal = true)
      }
      assert(failure.contains("MORPH-FRONTEND-NATIVE-INT-SHADOW-EXPRESSION-DEFERRED"))
    }
  }

  test("duplicate local slot names with different source identity are rejected") {
    withTemporaryDirectory { directory =>
      val failure = expectFailure(directory, "native_int_shadow_conflict.v") {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
        new Top(width, width, duplicateLocal = true)
      }
      assert(failure.contains("MORPH-FRONTEND-NATIVE-INT-SHADOW-SLOT-CONFLICT"))
    }
  }

  test("explicit local selection outside a formalization boundary is rejected") {
    withTemporaryDirectory { directory =>
      val failure = expectFailure(directory, "native_int_shadow_orphan.v") {
        new Component {
          setDefinitionName("ExternalNativeIntShadowOrphan")
          @dontName
          val orphan = shadowInt(8, "orphan")
          val value = out(UInt(8 bits))
          value := 0
        }
      }
      assert(failure.contains("MORPH-FRONTEND-NATIVE-INT-SHADOW-BOUNDARY-MISSING"))
    }
  }

  test("weak identity registry does not retain an unrelated same-width object") {
    withTemporaryDirectory { directory =>
      var top: Top = null
      emitMorph(directory, "native_int_shadow_weak_identity.v") {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
        top = new Top(width, width)
        top
      }

      val weak = new WeakReference[Component](top.left)
      assert(weak.get() eq top.left)
      assert(ExternalNativeIntShadowRegistry.componentRecordsOf(top.left).nonEmpty)
      assert(ExternalNativeIntShadowRegistry.regionOf(top.left.fixed).isEmpty)
      assert(ExternalNativeIntShadowRegistry.liveRecordCounts._1 >= 2)
    }
  }

  private def oneRecord(component: Component): ExternalNativeIntComponentShadowRecord = {
    val records = ExternalNativeIntShadowRegistry.componentRecordsOf(component)
    assert(records.size == 1, records)
    records.head
  }

  private def signature(directory: Path): Vector[String] = {
    var top: Top = null
    emitMorph(directory, "native_int_shadow_replay.v") {
      val left = HdlInt.param("LEFT_WIDTH", default = 8, min = 1, max = 16)
      val right = HdlInt.param("RIGHT_WIDTH", default = 8, min = 2, max = 32)
      top = new Top(left, right)
      top
    }
    Vector(top.left, top.right).flatMap { component =>
      oneRecord(component).slots.map { slot =>
        List(
          slot.token.kind.label,
          slot.token.name,
          slot.token.sourceLocation.replace('\\', '/'),
          slot.witness.toString,
          slot.definitionExpression.verilog,
          slot.actualExpression.verilog
        ).mkString("|")
      }
    }
  }

  private def emitMorph(
      directory: Path,
      filename: String
  )(component: => Component): String = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    MorphVerilog(config)(component)
    read(directory.resolve(filename))
  }

  private def emitConcrete(
      directory: Path,
      filename: String
  )(component: => Component): String = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    SpinalVerilog(config)(component)
    read(directory.resolve(filename))
  }

  private def expectFailure(
      directory: Path,
      filename: String
  )(component: => Component): String = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    MorphVerilog.tryGenerate(config)(component) match {
      case Left(failure) => failure.detail
      case Right(report) => fail(s"Expected failure, received $report")
    }
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-native-int-shadow-")
    try body(directory)
    finally deleteRecursively(directory)
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.exists(path)) {
      val entries = Files.walk(path)
      try {
        entries
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(value => Files.deleteIfExists(value))
      } finally entries.close()
    }
  }
}
