package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Comparator
import scala.collection.JavaConverters._
import morphhdl.{MorphNamedFieldVectors, MorphVerilog}
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

/** Ordinary hierarchy wiring around the existing 59i combined implementation.
  * The parent neither rebuilds a reduction tree nor performs adapter arithmetic.
  */
final class BalancedCombinedGeneratedChildTop(width: HdlInt, tagWidth: HdlInt,
    coordWidth: HdlInt, count: HdlInt, mode: HdlInt,
    topName: String, childName: String) extends Component {
  setDefinitionName(topName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val records = in(Vec(BalancedCompositeRecord(width, tagWidth, coordWidth), count)).setName("records")
  val signedRecords = in(Vec(BalancedCompositeComplex(width), count)).setName("signedRecords")
  val selected = out(BalancedCompositeRecord(width, tagWidth, coordWidth)).setName("selected")
  val delayed = out(BalancedCompositeRecord(width, tagWidth, coordWidth)).setName("delayed")
  val signedSelected = out(BalancedCompositeComplex(width)).setName("signedSelected")

  val child = new BalancedCombinedScopedRecords(width, tagWidth, coordWidth,
    count, mode, childName).setName("child")
  child.clk := clk
  child.reset := reset
  child.enable := enable
  child.records := records
  child.signedRecords := signedRecords
  selected := child.selected
  delayed := child.delayed
  signedSelected := child.signedSelected
}

class TypedBalancedReductionGeneratedChildCombinationTests extends AnyFunSuite {
  private def clean(directory: Path): Unit = {
    if (Files.exists(directory)) {
      val stream = Files.walk(directory)
      try stream.sorted(Comparator.reverseOrder()).iterator().asScala.foreach(Files.delete)
      finally stream.close()
    }
    Files.createDirectories(directory)
  }

  private def readVerilog(directory: Path): String = {
    val stream = Files.walk(directory)
    try stream.iterator().asScala.filter(path => Files.isRegularFile(path) &&
      path.getFileName.toString.endsWith(".v")).toVector
      .sortBy(_.toString).map(path => new String(Files.readAllBytes(path),
        StandardCharsets.UTF_8)).mkString("\n")
    finally stream.close()
  }

  private def parameterized(layout: String): String = {
    val directory = Files.createTempDirectory("balanced-generated-child-" + layout + "-")
    val topName = "BalancedCombinedGeneratedChildTop_" + layout
    val childName = "BalancedCombinedGeneratedChild_" + layout
    val base = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, headerWithRepoHash = false, bitVectorWidthMax = 65536)
    base.netlistFileName = topName + ".v"
    val config = if (layout == "fields") MorphNamedFieldVectors.enable(base) else base
    MorphVerilog(config) {
      new BalancedCombinedGeneratedChildTop(
        HdlInt.param("WIDTH", 5, 1, 32),
        HdlInt.param("TAG_WIDTH", 3, 1, 32),
        HdlInt.param("COORD_WIDTH", 7, 1, 32),
        HdlInt.param("COUNT", 1, 1, 17),
        HdlInt.param("MODE", 0, 0, 1), topName, childName)
    }
    val rtl = readVerilog(directory)
    assert(rtl.nonEmpty, "parameterized publication produced no Verilog")
    rtl
  }

  test("combined reduction publishes once through a packed parameterized child") {
    val rtl = parameterized("packed")
    assert("(?m)^module\\s+BalancedCombinedGeneratedChildTop_packed\\b".r
      .findAllIn(rtl).size == 1, rtl)
    assert("(?m)^module\\s+BalancedCombinedGeneratedChild_packed\\b".r
      .findAllIn(rtl).size == 1, rtl)
    assert("BalancedCombinedGeneratedChild_packed\\s*#\\s*\\(".r
      .findFirstIn(rtl).nonEmpty, rtl)
    Vector("WIDTH", "TAG_WIDTH", "COORD_WIDTH", "COUNT", "MODE",
      "g_minimum", "g_maximum", "g_registered_min", "g_registered_max",
      "morphhdl_balanced_").foreach(value => assert(rtl.contains(value), value + "\n" + rtl))
    assert(!rtl.contains("records_key"), rtl)
  }

  test("named-field vectors remain field preserving across the parameterized child") {
    val rtl = parameterized("fields")
    assert("(?m)^module\\s+BalancedCombinedGeneratedChildTop_fields\\b".r
      .findAllIn(rtl).size == 1, rtl)
    assert("(?m)^module\\s+BalancedCombinedGeneratedChild_fields\\b".r
      .findAllIn(rtl).size == 1, rtl)
    assert("BalancedCombinedGeneratedChild_fields\\s*#\\s*\\(".r
      .findFirstIn(rtl).nonEmpty, rtl)
    Vector("records_key", "records_tag", "records_x", "records_y",
      "signedRecords_real", "signedRecords_imag", "selected_key",
      "delayed_tag", "signedSelected_real").foreach(value =>
      assert(rtl.contains(value), value + "\n" + rtl))
  }

  test("all-literal ordinary SpinalVerilog preserves concrete hierarchy without HDL parameters") {
    val directory = Files.createTempDirectory("balanced-generated-child-concrete-")
    val topName = "BalancedCombinedGeneratedChildTop_concrete"
    val childName = "BalancedCombinedGeneratedChild_concrete"
    val config = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, headerWithRepoHash = false, bitVectorWidthMax = 65536)
    config.netlistFileName = topName + ".v"
    config.generateVerilog(new BalancedCombinedGeneratedChildTop(
      HdlInt.literal(5), HdlInt.literal(3), HdlInt.literal(7),
      HdlInt.literal(3), HdlInt.literal(0), topName, childName))
    val rtl = readVerilog(directory)
    assert(rtl.nonEmpty, "ordinary concrete publication produced no Verilog")
    assert("(?m)^module\\s+BalancedCombinedGeneratedChildTop_concrete\\b".r
      .findAllIn(rtl).size == 1, rtl)
    assert("(?m)^module\\s+BalancedCombinedGeneratedChild_concrete\\b".r
      .findAllIn(rtl).size == 1, rtl)
    assert("module\\s+BalancedCombinedGeneratedChild(?:Top)?_concrete\\s*#\\s*\\(".r
      .findFirstIn(rtl).isEmpty, rtl)
    assert("BalancedCombinedGeneratedChild_concrete\\s*#\\s*\\(".r
      .findFirstIn(rtl).isEmpty, rtl)
  }
}
