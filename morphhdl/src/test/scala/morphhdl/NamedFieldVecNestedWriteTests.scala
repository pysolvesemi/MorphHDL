package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

/** Source-shaped write admission. Actual value, priority and complete-domain
  * semantics are checked by the independent native HDL qualification matrix.
  */
class NamedFieldVecNestedWriteTests extends AnyFunSuite {
  import NamedFieldVecFixture._

  private case class Row(width: ElabInt, blueWidth: ElabInt, inner: ElabInt) extends Bundle {
    val colors = Vec(Pixel(width, blueWidth), inner)
  }
  private case class Plane(width: ElabInt, blueWidth: ElabInt, inner: ElabInt) extends Bundle {
    val rows = Vec(Row(width, blueWidth, inner), inner)
  }

  private def configuration(directory: Path, named: Boolean): SpinalConfig = {
    val base = config(directory, "NestedWrite.v")
    MorphSignedCasts.enable(if (named) MorphNamedFieldVectors.enable(base)
                           else MorphNamedFieldVectors.disable(base))
  }

  private def generate(named: Boolean)(body: => Component): String = {
    val directory = Files.createTempDirectory("59c-nested-write-")
    MorphVerilog(configuration(directory, named))(body)
    new String(Files.readAllBytes(directory.resolve("NestedWrite.v")), StandardCharsets.UTF_8)
  }

  private def reject(diagnostic: String, named: Boolean = true)(body: => Component): Unit = {
    val directory = Files.createTempDirectory("59c-nested-write-reject-")
    MorphVerilog.tryGenerate(configuration(directory, named))(body) match {
      case Left(failure) => assert(failure.detail.contains(diagnostic), failure.detail)
      case Right(report) => fail(s"unsupported nested write published: $report")
    }
    assert(!Files.exists(directory.resolve("NestedWrite.v")), "rejected source published partial RTL")
  }

  private class Writes(outerDynamic: Boolean, innerDynamic: Boolean, ordered: Boolean = false,
                       literals: Boolean = false) extends Component {
    setDefinitionName("NestedWrite")
    val width = if (literals) ElabInt.literal(5) else parameter("WIDTH", 5, 8)
    val blueWidth = parameter("BLUE_WIDTH", 3, 8)
    val count = parameter("COUNT", 1, 3)
    val inner = parameter("INNER", 1, 3)
    val values = in(Vec(Row(width, blueWidth, inner), count))
    val replacement = in(Pixel(width, blueWidth))
    val outerIndex = in(UInt(64 bits))
    val innerIndex = in(UInt(64 bits))
    val staticEnable = in(Bool())
    val innerEnable = in(Bool())
    val outerEnable = in(Bool())
    val enable = in(Bool())
    val result = out(Vec(Row(width, blueWidth, inner), count))
    val storage = cloneOf(values).setName("storage").dontSimplifyIt()
    storage := values
    if (ordered) {
      when(staticEnable) { storage(0).colors(0) := replacement }
      when(innerEnable) {
        if (literals) storage(0).colors(innerIndex).red := U(0, 5 bits)
        else storage(0).colors(innerIndex) := replacement
      }
      when(outerEnable) {
        if (literals) storage(outerIndex).colors(0).red := U(0, 5 bits)
        else storage(outerIndex).colors(0) := replacement
      }
    }
    when(enable) {
      val row = if (outerDynamic) storage(outerIndex) else storage(0)
      if (innerDynamic) {
        if (literals) row.colors(innerIndex).red := U(0, 5 bits)
        else row.colors(innerIndex) := replacement
      } else row.colors(0) := replacement
    }
    result := storage
  }

  for {
    named <- Vector(true, false)
    outerDynamic <- Vector(false, true)
    innerDynamic <- Vector(false, true)
  } test(s"nested write outer dynamic=$outerDynamic inner dynamic=$innerDynamic named=$named") {
    val text = generate(named)(new Writes(outerDynamic, innerDynamic))
    assert(text.contains("COUNT") && text.contains("INNER"), text)
    if (named) assert(text.contains("storage_colors_red"), text)
    if (outerDynamic) assert(text.contains("outerIndex") && text.contains("<"), text)
    if (innerDynamic) assert(text.contains("innerIndex") && text.contains("<"), text)
    assert(text.contains("enable"), text)
  }

  for (named <- Vector(true, false)) {
    test(s"three nested dynamic Vec axes retain independent coordinates named=$named") {
      val text = generate(named) {
        new Component {
          setDefinitionName("NestedWrite")
          val width = parameter("WIDTH", 5, 8)
          val blueWidth = parameter("BLUE_WIDTH", 3, 8)
          val count = parameter("COUNT", 1, 3)
          val inner = parameter("INNER", 1, 3)
          val values = in(Vec(Plane(width, blueWidth, inner), count))
          val replacement = in(Pixel(width, blueWidth))
          val outerIndex = in(UInt(64 bits))
          val middleIndex = in(UInt(64 bits))
          val innerIndex = in(UInt(64 bits))
          val enable = in(Bool())
          val result = out(Vec(Plane(width, blueWidth, inner), count))
          val storage = cloneOf(values).setName("storage").dontSimplifyIt()
          storage := values
          when(enable) { storage(outerIndex).rows(middleIndex).colors(innerIndex) := replacement }
          result := storage
        }
      }
      Vector("outerIndex", "middleIndex", "innerIndex", "COUNT", "INNER").foreach { name =>
        assert(text.contains(name), text)
      }
      if (named) assert(text.contains("storage_rows_colors_blue"), text)
    }

    test(s"ordered nested writes sharing one exact source retain authored priority named=$named") {
      val text = generate(named)(new Writes(outerDynamic = true, innerDynamic = true, ordered = true))
      Vector("staticEnable", "innerEnable", "outerEnable", "enable").foreach { name =>
        assert(text.contains(name), text)
      }
      assert(text.contains("replacement_red"), text)
    }

    test(s"direct nested registers retain enable and nonblocking updates named=$named") {
      val text = generate(named)(new Registered(initialize = false, reset = false))
      assert(text.contains("posedge clk"), text)
      assert(text.contains("<="), text)
      assert(text.contains("enable") && text.contains("outerIndex") && text.contains("innerIndex"), text)
    }
  }

  private class Registered(initialize: Boolean, reset: Boolean) extends Component {
    setDefinitionName("NestedWrite")
    val width = if (initialize || reset) ElabInt.literal(5) else parameter("WIDTH", 5, 8)
    val blueWidth = if (initialize || reset) ElabInt.literal(3) else parameter("BLUE_WIDTH", 3, 8)
    val count = parameter("COUNT", 1, 3)
    val inner = parameter("INNER", 1, 3)
    val clk = in(Bool())
    val resetInput = in(Bool())
    val enable = in(Bool())
    val outerIndex = in(UInt(64 bits))
    val innerIndex = in(UInt(64 bits))
    val replacement = in(Pixel(width, blueWidth))
    val result = out(Vec(Row(width, blueWidth, inner), count))
    val domain = if (reset) ClockDomain(clock = clk, reset = resetInput)
                 else if (initialize) ClockDomain(clock = clk, config = ClockDomainConfig(resetKind = BOOT))
                 else ClockDomain(clock = clk)
    val area = new ClockingArea(domain) {
      val storage = Reg(Vec(Row(width, blueWidth, inner), count)).setName("storage").dontSimplifyIt()
      if (initialize || reset) storage.asInstanceOf[Data].flatten.foreach {
        case value: UInt => value.init(0)
        case value: SInt => value.init(0)
        case value: Bool => value.init(False)
        case _ =>
      }
      when(enable) { storage(outerIndex).colors(innerIndex) := replacement }
    }
    result := area.storage
  }

  test("nested register initialization remains outside inherited write admission") {
    reject("DYNAMIC-WRITE-CONTROL-UNSUPPORTED")(new Registered(initialize = true, reset = false))
  }

  test("nested register reset remains outside inherited write admission") {
    reject("DYNAMIC-WRITE-CONTROL-UNSUPPORTED")(new Registered(initialize = false, reset = true))
  }

  for (named <- Vector(true, false)) {
    test(s"normalized unnamed nested literals cannot replace captured source identity named=$named") {
      reject("STATIC-WRITE-EVIDENCE-MISMATCH", named) {
        new Writes(outerDynamic = true, innerDynamic = true, ordered = true, literals = true)
      }
    }
  }
}
