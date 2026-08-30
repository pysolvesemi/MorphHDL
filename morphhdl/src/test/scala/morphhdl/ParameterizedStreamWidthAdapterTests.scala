package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import morphhdl.frontend.HdlInt

/**
  * Direct typed-elaboration application around the authoritative native
  * `spinal.lib.StreamWidthAdapter` algorithm. No native-Int shadow provenance
  * or component-specific adapter implementation participates in this fixture.
  */
object ParameterizedStreamWidthAdapterSmoke {
  /**
    * One application component invokes the existing native adapter in all
    * three width relations. Geometry remains typed from the public HdlInt
    * construction boundary through the native StreamWidthAdapter algorithm.
    */
  final class Top(
      equalWidth: ElabInt,
      downWidth: ElabInt,
      upWidth: ElabInt
  ) extends Component {
    setDefinitionName("NativeStreamWidthAdapterTop")

    val equalInputValid = in Bool()
    val equalInputReady = out Bool()
    val equalInputPayload = in Bits (equalWidth bits)
    val equalOutputValid = out Bool()
    val equalOutputReady = in Bool()
    val equalOutputPayload = out Bits (equalWidth bits)

    val downInputValid = in Bool()
    val downInputReady = out Bool()
    val downInputPayload = in Bits (downWidth bits)
    val downOutputValid = out Bool()
    val downOutputReady = in Bool()
    val downOutputPayload = out Bits (8 bits)

    val upInputValid = in Bool()
    val upInputReady = out Bool()
    val upInputPayload = in Bits (8 bits)
    val upOutputValid = out Bool()
    val upOutputReady = in Bool()
    val upOutputPayload = out Bits (upWidth bits)

    val equalInput = Stream(Bits(equalWidth bits))
    val equalOutput = Stream(Bits(equalWidth bits))
    equalInput.valid := equalInputValid
    equalInput.payload := equalInputPayload
    equalInputReady := equalInput.ready
    equalOutputValid := equalOutput.valid
    equalOutputPayload := equalOutput.payload
    equalOutput.ready := equalOutputReady
    StreamWidthAdapter(equalInput, equalOutput, LITTLE, padding = true)

    val downInput = Stream(Bits(downWidth bits))
    val downOutput = Stream(Bits(8 bits))
    downInput.valid := downInputValid
    downInput.payload := downInputPayload
    downInputReady := downInput.ready
    downOutputValid := downOutput.valid
    downOutputPayload := downOutput.payload
    downOutput.ready := downOutputReady
    StreamWidthAdapter(downInput, downOutput, LITTLE, padding = true)

    val upInput = Stream(Bits(8 bits))
    val upOutput = Stream(Bits(upWidth bits))
    upInput.valid := upInputValid
    upInput.payload := upInputPayload
    upInputReady := upInput.ready
    upOutputValid := upOutput.valid
    upOutputPayload := upOutput.payload
    upOutput.ready := upOutputReady
    StreamWidthAdapter(upInput, upOutput, LITTLE, padding = true)
  }

  /** Two unrelated typed width roots must fail before native elaboration. */
  final class IndependentRootTop(
      inputWidth: ElabInt,
      outputWidth: ElabInt
  ) extends Component {
    setDefinitionName("NativeStreamWidthAdapterIndependentRootTop")

    val io = new Bundle {
      val input = slave(Stream(Bits(inputWidth bits)))
      val output = master(Stream(Bits(outputWidth bits)))
    }

    StreamWidthAdapter(io.input, io.output, LITTLE, padding = true)
  }

  def component(): Top =
    new Top(
      equalWidth = HdlInt.param(
        "EQ_WIDTH",
        default = 8,
        min = 1,
        max = 32
      ).asElabInt,
      downWidth = HdlInt.param(
        "DOWN_WIDTH",
        default = 12,
        min = 9,
        max = 16
      ).asElabInt,
      upWidth = HdlInt.param(
        "UP_WIDTH",
        default = 12,
        min = 9,
        max = 16
      ).asElabInt
    )
}

class ParameterizedStreamWidthAdapterTests extends AnyFunSuite {
  import ParameterizedStreamWidthAdapterSmoke._

  private val ModuleDeclaration =
    """(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b""".r

  test("typed native StreamWidthAdapter emits one parameterized top covering all three native modes") {
    withTemporaryDirectory { directory =>
      val parameterizedDirectory = directory.resolve("parameterized")
      val replayDirectory = directory.resolve("replay")
      val concreteDirectory = directory.resolve("concrete")
      Files.createDirectories(parameterizedDirectory)
      Files.createDirectories(replayDirectory)
      Files.createDirectories(concreteDirectory)

      val parameterizedConfig = config(parameterizedDirectory)
      parameterizedConfig.netlistFileName = "native_stream_width_adapter.v"
      val report = MorphVerilog(parameterizedConfig)(component())
      val parameterizedPath =
        parameterizedDirectory.resolve("native_stream_width_adapter.v")
      val parameterized = read(parameterizedPath)

      val replayConfig = config(replayDirectory)
      replayConfig.netlistFileName = "native_stream_width_adapter.v"
      MorphVerilog(replayConfig)(component())
      assert(
        java.util.Arrays.equals(
          Files.readAllBytes(parameterizedPath),
          Files.readAllBytes(replayDirectory.resolve("native_stream_width_adapter.v"))
        ),
        "native StreamWidthAdapter parameterized generation was not deterministic"
      )

      val concreteConfig = config(concreteDirectory)
      concreteConfig.netlistFileName = "native_stream_width_adapter.v"
      SpinalVerilog(concreteConfig)(component())
      val concrete = read(concreteDirectory.resolve("native_stream_width_adapter.v"))

      assert(
        report.parameters.map(_.name) ==
          Vector("DOWN_WIDTH", "EQ_WIDTH", "UP_WIDTH")
      )
      assert(parameterized.contains("module NativeStreamWidthAdapterTop #("))
      assert(parameterized.contains("parameter integer EQ_WIDTH = 8"))
      assert(parameterized.contains("parameter integer DOWN_WIDTH = 12"))
      assert(parameterized.contains("parameter integer UP_WIDTH = 12"))
      assert(hasWidth(parameterized, "equalInputPayload", "EQ_WIDTH"))
      assert(hasWidth(parameterized, "downInputPayload", "DOWN_WIDTH"))
      assert(hasWidth(parameterized, "upOutputPayload", "UP_WIDTH"))

      val inventory = ModuleDeclaration
        .findAllMatchIn(parameterized)
        .map(_.group(1))
        .toVector
        .sorted
      assert(
        inventory == Vector("NativeStreamWidthAdapterTop"),
        s"Unexpected native StreamWidthAdapter module inventory: ${inventory.mkString(", ")}"
      )

      assert(!parameterized.contains("ParamRTL"))
      assert(!parameterized.contains("MorphStreamWidthAdapter"))
      assert(!parameterized.contains("ParameterizedStreamWidthAdapter"))
      assert(!parameterized.contains("rewriteParameterizedStreamWidthAdapter"))
      assert(!parameterized.contains("NativeIntShadow"))
      assert(!parameterized.contains("compilerTrackArgument"))

      assert(!concrete.contains("parameter integer EQ_WIDTH"))
      assert(!concrete.contains("parameter integer DOWN_WIDTH"))
      assert(!concrete.contains("parameter integer UP_WIDTH"))
      assert(hasConcreteWidth(concrete, "equalInputPayload", 8))
      assert(hasConcreteWidth(concrete, "downInputPayload", 12))
      assert(hasConcreteWidth(concrete, "upOutputPayload", 12))

      Vector(
        (5, 9, 9),
        (8, 12, 12),
        (16, 16, 16)
      ).foreach { case (equalWidth, downWidth, upWidth) =>
        lintWitness(
          parameterizedDirectory,
          parameterizedPath,
          equalWidth,
          downWidth,
          upWidth
        )
        simulateWitness(
          parameterizedDirectory,
          parameterizedPath,
          equalWidth,
          downWidth,
          upWidth
        )
        synthesizeWitness(
          parameterizedDirectory,
          parameterizedPath,
          equalWidth,
          downWidth,
          upWidth
        )
      }
    }
  }

  test("a native adapter invocation rejects an unproven second symbolic root") {
  withTemporaryDirectory { directory =>
    val generationConfig = config(directory)
    generationConfig.netlistFileName = "native_stream_width_adapter_independent.v"
    val first = HdlInt.param("FIRST_WIDTH", default = 12, min = 9, max = 16)
    val second = HdlInt.param("SECOND_WIDTH", default = 12, min = 9, max = 16)
    val result = MorphVerilog.tryGenerate(generationConfig) {
      // The second independent symbolic width is attached to a different
      // exact Data identity. The active INPUT_WIDTH boundary must reject it
      // instead of matching the equal concrete default.
      new IndependentRootTop(first.asElabInt, second.asElabInt)
    }
    result match {
      case Left(failure) =>
        assert(
          failure.detail.contains("SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED"),
          failure.detail
        )
      case Right(report) =>
        fail(s"Expected independent-width provenance rejection, received $report")
    }
  }
}

  private def hasWidth(
      verilog: String,
      signal: String,
      parameter: String
  ): Boolean = {
    val compact = verilog.replaceAll("\\s+", "")
    compact.contains(s"[${parameter}-1:0]$signal") ||
    compact.contains(s"[(${parameter}-1):0]$signal")
  }

  private def hasConcreteWidth(
      verilog: String,
      signal: String,
      width: Int
  ): Boolean = {
    val compact = verilog.replaceAll("\\s+", "")
    compact.contains(s"[${width - 1}:0]$signal")
  }

  private def lintWitness(
      directory: Path,
      rtl: Path,
      equalWidth: Int,
      downWidth: Int,
      upWidth: Int
  ): Unit = {
    val result = run(
      directory,
      Seq(
        "verilator",
        "--lint-only",
        "--language",
        "1364-2001",
        "-Wall",
        "-Wno-DECLFILENAME",
        "-Wno-WIDTH",
        "-Wno-UNUSED",
        "-Wno-UNDRIVEN",
        "--top-module",
        "NativeStreamWidthAdapterTop",
        s"-GEQ_WIDTH=$equalWidth",
        s"-GDOWN_WIDTH=$downWidth",
        s"-GUP_WIDTH=$upWidth",
        rtl.toString
      )
    )
    assert(
      result._1 == 0,
      s"Verilator lint failed for EQ=$equalWidth DOWN=$downWidth UP=$upWidth:\n${result._2}"
    )
  }

  private def simulateWitness(
      directory: Path,
      rtl: Path,
      equalWidth: Int,
      downWidth: Int,
      upWidth: Int
  ): Unit = {
    val suffix = s"${equalWidth}_${downWidth}_${upWidth}"
    val module = s"NativeStreamWidthAdapterTb_$suffix"
    val testbench = directory.resolve(s"$module.v")
    val executable = directory.resolve(s"$module.out")
    val source =
      s"""`timescale 1ns/1ps
         |module $module;
         |  localparam integer EQ_WIDTH = $equalWidth;
         |  localparam integer DOWN_WIDTH = $downWidth;
         |  localparam integer UP_WIDTH = $upWidth;
         |
         |  reg clk = 1'b0;
         |  reg reset = 1'b1;
         |
         |  reg equalInputValid = 1'b0;
         |  wire equalInputReady;
         |  reg [EQ_WIDTH-1:0] equalInputPayload = {EQ_WIDTH{1'b0}};
         |  wire equalOutputValid;
         |  reg equalOutputReady = 1'b0;
         |  wire [EQ_WIDTH-1:0] equalOutputPayload;
         |
         |  reg downInputValid = 1'b0;
         |  wire downInputReady;
         |  reg [DOWN_WIDTH-1:0] downInputPayload = {DOWN_WIDTH{1'b0}};
         |  wire downOutputValid;
         |  reg downOutputReady = 1'b0;
         |  wire [7:0] downOutputPayload;
         |
         |  reg upInputValid = 1'b0;
         |  wire upInputReady;
         |  reg [7:0] upInputPayload = 8'h00;
         |  wire upOutputValid;
         |  reg upOutputReady = 1'b0;
         |  wire [UP_WIDTH-1:0] upOutputPayload;
         |
         |  reg [15:0] downWord;
         |  reg [7:0] downExpectedHigh;
         |  reg [15:0] upCombined;
         |
         |  always #5 clk = ~clk;
         |
         |  NativeStreamWidthAdapterTop #(
         |    .EQ_WIDTH(EQ_WIDTH),
         |    .DOWN_WIDTH(DOWN_WIDTH),
         |    .UP_WIDTH(UP_WIDTH)
         |  ) dut (
         |    .equalInputValid(equalInputValid),
         |    .equalInputReady(equalInputReady),
         |    .equalInputPayload(equalInputPayload),
         |    .equalOutputValid(equalOutputValid),
         |    .equalOutputReady(equalOutputReady),
         |    .equalOutputPayload(equalOutputPayload),
         |    .downInputValid(downInputValid),
         |    .downInputReady(downInputReady),
         |    .downInputPayload(downInputPayload),
         |    .downOutputValid(downOutputValid),
         |    .downOutputReady(downOutputReady),
         |    .downOutputPayload(downOutputPayload),
         |    .upInputValid(upInputValid),
         |    .upInputReady(upInputReady),
         |    .upInputPayload(upInputPayload),
         |    .upOutputValid(upOutputValid),
         |    .upOutputReady(upOutputReady),
         |    .upOutputPayload(upOutputPayload),
         |    .clk(clk),
         |    .reset(reset)
         |  );
         |
         |  task tick;
         |    begin
         |      @(posedge clk);
         |      #1;
         |    end
         |  endtask
         |
         |  task fail;
         |    input [511:0] reason;
         |    begin
         |      $$display("FAIL EQ=%0d DOWN=%0d UP=%0d: %0s", EQ_WIDTH, DOWN_WIDTH, UP_WIDTH, reason);
         |      $$finish(2);
         |    end
         |  endtask
         |
         |  initial begin
         |    repeat (3) tick;
         |    reset = 1'b0;
         |    tick;
         |
         |    equalInputPayload = 32'hA5C35A96;
         |    equalInputValid = 1'b1;
         |    equalOutputReady = 1'b0;
         |    #1;
         |    if (equalOutputValid !== 1'b1) fail("equal valid mismatch");
         |    if (equalInputReady !== 1'b0) fail("equal backpressure mismatch");
         |    if (equalOutputPayload !== equalInputPayload) fail("equal payload mismatch while stalled");
         |    tick;
         |    equalOutputReady = 1'b1;
         |    #1;
         |    if (equalInputReady !== 1'b1) fail("equal ready mismatch");
         |    if (equalOutputPayload !== equalInputPayload) fail("equal payload mismatch");
         |    tick;
         |    equalInputValid = 1'b0;
         |    equalOutputReady = 1'b0;
         |
         |    downWord = 16'hC3D4;
         |    downExpectedHigh = (downWord >> 8) & ((16'h1 << (DOWN_WIDTH - 8)) - 1);
         |    downInputPayload = downWord[DOWN_WIDTH-1:0];
         |    downInputValid = 1'b1;
         |    downOutputReady = 1'b0;
         |    #1;
         |    if (downOutputValid !== 1'b1) fail("downsize valid mismatch");
         |    if (downInputReady !== 1'b0) fail("downsize first stall accepted input");
         |    if (downOutputPayload !== downWord[7:0]) fail("downsize low beat mismatch while stalled");
         |    tick;
         |    downOutputReady = 1'b1;
         |    #1;
         |    if (downInputReady !== 1'b0) fail("downsize accepted input before final beat");
         |    if (downOutputPayload !== downWord[7:0]) fail("downsize low beat mismatch");
         |    tick;
         |    downOutputReady = 1'b0;
         |    #1;
         |    if (downOutputValid !== 1'b1) fail("downsize final beat lost while stalled");
         |    if (downInputReady !== 1'b0) fail("downsize final stall accepted input");
         |    if (downOutputPayload !== downExpectedHigh) fail("downsize high beat mismatch while stalled");
         |    tick;
         |    downOutputReady = 1'b1;
         |    #1;
         |    if (downInputReady !== 1'b1) fail("downsize did not complete on final beat");
         |    if (downOutputPayload !== downExpectedHigh) fail("downsize high beat mismatch");
         |    tick;
         |    downInputValid = 1'b0;
         |    downOutputReady = 1'b0;
         |
         |    upInputValid = 1'b1;
         |    upInputPayload = 8'hA1;
         |    upOutputReady = 1'b0;
         |    #1;
         |    if (upInputReady !== 1'b1) fail("upsize first word was not accepted");
         |    if (upOutputValid !== 1'b0) fail("upsize output asserted too early");
         |    tick;
         |    upInputPayload = 8'hB2;
         |    upCombined = {8'hB2, 8'hA1};
         |    #1;
         |    if (upOutputValid !== 1'b1) fail("upsize output valid mismatch");
         |    if (upInputReady !== 1'b0) fail("upsize ignored output stall");
         |    if (upOutputPayload !== upCombined[UP_WIDTH-1:0]) fail("upsize payload mismatch while stalled");
         |    tick;
         |    upOutputReady = 1'b1;
         |    #1;
         |    if (upInputReady !== 1'b1) fail("upsize did not complete with output");
         |    if (upOutputPayload !== upCombined[UP_WIDTH-1:0]) fail("upsize payload mismatch");
         |    tick;
         |    upInputValid = 1'b0;
         |    upOutputReady = 1'b0;
         |    tick;
         |
         |    $$display("PASS EQ=%0d DOWN=%0d UP=%0d", EQ_WIDTH, DOWN_WIDTH, UP_WIDTH);
         |    $$finish;
         |  end
         |endmodule
         |""".stripMargin
    Files.write(testbench, source.getBytes(StandardCharsets.UTF_8))

    val compile = run(
      directory,
      Seq(
        "iverilog",
        "-g2001",
        "-s",
        module,
        "-o",
        executable.toString,
        rtl.toString,
        testbench.toString
      )
    )
    assert(compile._1 == 0, compile._2)
    val simulation = run(directory, Seq("vvp", executable.toString))
    assert(simulation._1 == 0, simulation._2)
    assert(
      simulation._2.contains(
        s"PASS EQ=$equalWidth DOWN=$downWidth UP=$upWidth"
      ),
      simulation._2
    )
  }

  private def synthesizeWitness(
      directory: Path,
      rtl: Path,
      equalWidth: Int,
      downWidth: Int,
      upWidth: Int
  ): Unit = {
    val script = directory.resolve(
      s"native_stream_width_adapter_${equalWidth}_${downWidth}_${upWidth}.ys"
    )
    Files.write(
      script,
      s"""read_verilog -defer ${rtl.toString}
         |chparam -set EQ_WIDTH $equalWidth -set DOWN_WIDTH $downWidth -set UP_WIDTH $upWidth NativeStreamWidthAdapterTop
         |hierarchy -check -top NativeStreamWidthAdapterTop
         |synth -top NativeStreamWidthAdapterTop
         |check -assert
         |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    )
    val result = run(directory, Seq("yosys", "-q", "-s", script.toString))
    assert(
      result._1 == 0,
      s"Yosys synthesis failed for EQ=$equalWidth DOWN=$downWidth UP=$upWidth:\n${result._2}"
    )
  }

  private def run(directory: Path, command: Seq[String]): (Int, String) = {
    val log = new StringBuilder
    val status = Process(command, directory.toFile).!(
      ProcessLogger(
        line => log.append(line).append('\n'),
        line => log.append(line).append('\n')
      )
    )
    status -> log.toString
  }

  private def config(directory: Path): SpinalConfig =
    SpinalConfig(
      targetDirectory = directory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    )

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-stream-width-adapter-")
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
