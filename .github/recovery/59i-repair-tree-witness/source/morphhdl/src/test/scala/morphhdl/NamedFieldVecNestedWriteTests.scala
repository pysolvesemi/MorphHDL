package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._

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
    if (outerDynamic) assert(text.contains("outerIndex[1:0]") && text.contains("<"), text)
    if (innerDynamic) assert(text.contains("innerIndex[1:0]") && text.contains("<"), text)
    assert(text.contains("enable"), text)
  }

  private case class CoordinateRow(depth: ElabInt) extends Bundle {
    val words = Vec(UInt(5 bits), depth)
  }

  /** The same root proves address capacity pointwise. Its address width crosses
    * the three-bit coordinate width below, at, and above DEPTH=3.
    */
  private class CorrelatedCoordinateWidths extends Component {
    setDefinitionName("NestedWrite")
    val depth = parameter("DEPTH", 1, 5)
    val values = in(Vec(CoordinateRow(depth), depth))
    val outerIndex = in(UInt(depth bits))
    val innerIndex = in(UInt(depth bits))
    val enable = in(Bool())
    val replacement = in(UInt(5 bits))
    val result = out(Vec(CoordinateRow(depth), depth))
    val selected = out(UInt(5 bits))
    val storage = cloneOf(values).setName("storage").dontSimplifyIt()
    storage := values
    when(enable) { storage(outerIndex).words(innerIndex) := replacement }
    result := storage
    selected := storage(outerIndex).words(innerIndex)
  }

  private def executable(name: String): Option[String] =
    sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator).iterator
      .map(directory => Paths.get(directory, name)).find(Files.isExecutable(_)).map(_.toString)

  private def checkedProcess(directory: Path, name: String, command: Seq[String]): String = {
    val log = directory.resolve(name + ".log")
    val process = new ProcessBuilder(command.asJava).directory(directory.toFile)
      .redirectErrorStream(true).redirectOutput(log.toFile).start()
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      fail(s"$name exceeded its 60-second limit: $log")
    }
    val output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8)
    assert(process.exitValue() == 0, s"$name failed:\n$output")
    output
  }

  for (named <- Vector(true, false)) {
    test(s"nested symbolic address widths straddle coordinate width safely named=$named") {
      val directory = Files.createTempDirectory("59c-coordinate-width-")
      MorphVerilog(configuration(directory, named))(new CorrelatedCoordinateWidths)
      val rtl = directory.resolve("NestedWrite.v")
      val text = new String(Files.readAllBytes(rtl), StandardCharsets.UTF_8)
      assert(text.contains("(DEPTH) < 3 ? (DEPTH) : 3"), text)
      Vector("outerIndex", "innerIndex").foreach { address =>
        assert(text.contains(s"$address[(((DEPTH) < 3 ? (DEPTH) : 3))-1:0]"), text)
        assert(text.contains(s"{{(32 - (DEPTH)){1'b0}}, $address}"), text)
      }
      executable("verilator") match {
        case Some(verilator) =>
          for (depth <- 1 to 5) {
            checkedProcess(directory, s"lint-$depth",
              Seq(verilator, "--lint-only", "--language", "1364-2001", "--top-module", "NestedWrite",
                s"-GDEPTH=$depth", rtl.toString))
          }
        case _ => info("Verilator unavailable; symbolic-coordinate publication assertions completed")
      }
      (executable("iverilog"), executable("vvp")) match {
        case (Some(iverilog), Some(vvp)) =>
          // Constants are calculated independently from the emitted indexing
          // expressions. Invalid writes preserve all cells; reads clamp each axis.
          for (depth <- 1 to 5) {
            val packedWidth = depth * depth * 5
            val original = (0 until depth * depth).foldLeft(BigInt(0)) { (bits, cell) =>
              bits | (BigInt((cell + 7) % 31) << (cell * 5))
            }
            val addresses = ((0 until depth) ++ Seq(depth, (1 << depth) - 1, 1 << (depth - 1))).distinct
            val samples = for (enabled <- 0 to 1; outer <- addresses; inner <- addresses) yield {
              val offset = (outer * depth + inner) * 5
              val expected = if (enabled == 1 && outer < depth && inner < depth)
                (original & ~(BigInt(31) << offset)) | (BigInt(31) << offset)
              else original
              val selectedOffset = (outer.min(depth - 1) * depth + inner.min(depth - 1)) * 5
              val selectedValue = (expected >> selectedOffset) & 31
              s"""    enable = 1'd$enabled; outerIndex = $depth'd$outer; innerIndex = $depth'd$inner;
                 |    #1;
                 |    if (result !== $packedWidth'h${expected.toString(16)} || selected !== 5'd$selectedValue) begin
                 |      $$display("FAIL depth=$depth enable=$enabled outer=$outer inner=$inner"); $$finish(1);
                 |    end
                 |""".stripMargin
            }
            val valuesPort = if (named) "values_words" else "values"
            val resultPort = if (named) "result_words" else "result"
            val bench = s"""module CoordinateWidthBench;
              |  reg [$packedWidth-1:0] values;
              |  reg [$depth-1:0] outerIndex, innerIndex;
              |  reg enable;
              |  wire [$packedWidth-1:0] result;
              |  wire [4:0] selected;
              |  NestedWrite #(.DEPTH($depth)) dut(.$valuesPort(values), .outerIndex(outerIndex),
              |    .innerIndex(innerIndex), .enable(enable), .replacement(5'd31),
              |    .$resultPort(result), .selected(selected));
              |  initial begin
              |    values = $packedWidth'h${original.toString(16)};
              |${samples.mkString}
              |    $$display("PASS ${samples.size} samples"); $$finish;
              |  end
              |endmodule
              |""".stripMargin
            val benchPath = directory.resolve(s"depth-$depth.v")
            val binary = directory.resolve(s"depth-$depth.vvp")
            Files.write(benchPath, bench.getBytes(StandardCharsets.UTF_8))
            checkedProcess(directory, s"compile-$depth",
              Seq(iverilog, "-g2001", "-s", "CoordinateWidthBench", "-o", binary.toString,
                rtl.toString, benchPath.toString))
            val output = checkedProcess(directory, s"simulate-$depth", Seq(vvp, binary.toString))
            assert(output.contains(s"PASS ${samples.size} samples"), output)
          }
        case _ => info("Icarus unavailable; symbolic-coordinate publication assertions completed")
      }
    }
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
