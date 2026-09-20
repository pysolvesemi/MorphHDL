package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.ProcessLogger
import scala.util.matching.Regex

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend._

object GenericProcessLoweringSmoke {
  final class GenericCombinational(width: HdlInt) extends Component {
    setDefinitionName("NativeGenericCombinationalProcess")

    val left = in(morphhdl.frontend.Bits(width bits))
    val right = in(morphhdl.frontend.Bits(width bits))
    val mode = in(morphhdl.frontend.UInt(2 bits))
    val invert = in(Bool())
    val result = out(morphhdl.frontend.Bits(width bits))

    result := left
    switch(mode) {
      is(0) {
        result := left
      }
      is(1) {
        result := right
      }
      default {
        when(invert) {
          result := ~right
        } otherwise {
          result := left ^ right
        }
      }
    }
  }

  final class GenericSequential(width: HdlInt) extends Component {
    setDefinitionName("NativeGenericSequentialProcess")

    val clear = in(Bool())
    val load = in(Bool())
    val din = in(morphhdl.frontend.Bits(width bits))
    val dout = out(morphhdl.frontend.Bits(width bits))

    val state = morphhdl.frontend.Reg(morphhdl.frontend.Bits(width bits)) init (0)
    when(clear) {
      state := 0
    } elsewhen (load) {
      state := din
    }
    dout := state
  }

  final class ProceduralLoopTop(lanes: HdlInt) extends Component {
    setDefinitionName("ProceduralLoopTop")

    val din = in(morphhdl.frontend.Bits(32 bits))
    val dout = out(morphhdl.frontend.Bits(32 bits))
    dout := B(0, 32 bits)
    (0 until lanes).named("p_lane", "lane").foreach { lane =>
      val laneWidth = HdlInt.literal(BigInt(8))
      dout(lane * laneWidth, laneWidth) :=
        din(lane * laneWidth, laneWidth)
    }
  }

  final class SequentialProceduralLoopTop(lanes: HdlInt) extends Component {
    setDefinitionName("SequentialProceduralLoopTop")

    val load = in(Bool())
    val din = in(morphhdl.frontend.Bits(32 bits))
    val dout = out(morphhdl.frontend.Bits(32 bits))
    val state = morphhdl.frontend.Reg(morphhdl.frontend.Bits(32 bits)) init (0)

    when(load) {
      (0 until lanes).named("p_word", "word").foreach { word =>
        val laneWidth = HdlInt.literal(BigInt(8))
        state(word * laneWidth, laneWidth) :=
          din(word * laneWidth, laneWidth)
      }
    }
    dout := state
  }

  final class NarrowSourceProceduralLoopTop(width: HdlInt, lanes: HdlInt) extends Component {
    setDefinitionName("NarrowSourceProceduralLoopMustFailClosed")

    val din = in(morphhdl.frontend.Bits(width bits))
    val dout = out(morphhdl.frontend.Bits(width bits))
    dout := 0
    (0 until lanes).named("p_narrow_lane", "narrow_lane").foreach { lane =>
      val laneWidth = HdlInt.literal(BigInt(4))
      dout(lane * laneWidth, laneWidth) :=
        din(lane * laneWidth, laneWidth)
    }
  }

  def genericCombinational(): Component = {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    new GenericCombinational(width)
  }

  def genericSequential(): Component = {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    new GenericSequential(width)
  }

  def proceduralLoop(): Component = {
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 4)
    new ProceduralLoopTop(lanes)
  }

  def sequentialProceduralLoop(): Component = {
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 4)
    new SequentialProceduralLoopTop(lanes)
  }
}

class GenericProcessLoweringTests extends AnyFunSuite {
  import GenericProcessLoweringSmoke._

  test("ordinary when switch and nested conditional statements reuse native combinational process emission") {
    withTemporaryDirectory { directory =>
      val parameterizedDirectory = directory.resolve("parameterized")
      val concreteDirectory = directory.resolve("concrete")
      Files.createDirectories(parameterizedDirectory)
      Files.createDirectories(concreteDirectory)

      val parameterized = emitMorph(
        parameterizedDirectory,
        "generic_combinational.v",
        genericCombinational()
      )._2
      val concrete = emitConcrete(
        concreteDirectory,
        "generic_combinational.v",
        genericCombinational()
      )

      assert(
        nativeModule(
          concretizeWidth(
            parameterized,
            "NativeGenericCombinationalProcess",
            width = 8
          )
        ) == nativeModule(concrete)
      )
      assert(parameterized.contains("parameter integer WIDTH = 8"))
      assert(parameterized.contains("always @(*) begin"))
      assert(parameterized.contains("case(mode)"))
      assert(parameterized.contains("if(invert) begin"))
      assert(
        parameterized.contains("result = (left ^ right);") ||
          parameterized.contains("result = left ^ right;")
      )
      assert(!parameterized.contains("ParamRTL"))
    }
  }

  test("ordinary initialized registers retain native clock enable condition and asynchronous reset semantics") {
    withTemporaryDirectory { directory =>
      val parameterizedDirectory = directory.resolve("parameterized")
      val concreteDirectory = directory.resolve("concrete")
      Files.createDirectories(parameterizedDirectory)
      Files.createDirectories(concreteDirectory)

      val parameterizedConfig = asynchronousResetConfig(parameterizedDirectory)
      val concreteConfig = asynchronousResetConfig(concreteDirectory)
      val parameterized = emitMorph(
        parameterizedDirectory,
        "generic_sequential.v",
        genericSequential(),
        parameterizedConfig
      )._2
      val concrete = emitConcrete(
        concreteDirectory,
        "generic_sequential.v",
        genericSequential(),
        concreteConfig
      )

      assert(
        nativeModule(
          concretizeWidth(
            parameterized,
            "NativeGenericSequentialProcess",
            width = 8
          )
        ) == nativeModule(concrete)
      )
      assert(parameterized.contains("always @(posedge clk or negedge resetn)"))
      assert(parameterized.contains("if(!resetn) begin"))
      assert(parameterized.contains("if(clear) begin"))
      assert(parameterized.contains("if(load) begin"))
      assert(parameterized.contains("state <= din;"))
      assert(
        parameterized
          .sliding("{WIDTH{1'b0}}".length)
          .count(_ == "{WIDTH{1'b0}}") == 2
      )
    }
  }

  test("a safe parameter-bounded assignment loop is classified as a combinational procedural for") {
    withTemporaryDirectory { directory =>
      val (report, verilog) = emitMorph(
        directory,
        "procedural_loop.v",
        proceduralLoop()
      )

      assert(report.parameters.map(_.name) == Vector("LANES"))
      assert(verilog.contains("parameter integer LANES = 4"))
      assert(verilog.contains("integer lane;"))
      assert(verilog.contains("always @(*) begin"))
      assert(
        verilog.contains(
          "for (lane = 0; lane < LANES; lane = lane + 1) begin : p_lane"
        )
      )
      assert(
        verilog.contains(
          "dout[(lane * 8) +: 8] = din[(lane * 8) +: 8];"
        )
      )
      assert(!verilog.contains("genvar lane"))
      assert(!verilog.contains("generate"))
      assert(!verilog.contains("MORPH_PROC_FOR"))

      simulateProceduralLoop(directory, directory.resolve("procedural_loop.v"))
    }
  }

  test("a safe parameter-bounded register loop stays beneath native clock reset and runtime control") {
    withTemporaryDirectory { directory =>
      val config = synchronousResetConfig(directory)
      val (_, verilog) = emitMorph(
        directory,
        "sequential_procedural_loop.v",
        sequentialProceduralLoop(),
        config
      )

      assert(verilog.contains("integer word;"))
      assert(verilog.contains("always @(posedge clk)"))
      assert(verilog.contains("if(reset) begin"))
      assert(verilog.contains("if(load) begin"))
      assert(
        verilog.contains(
          "for (word = 0; word < LANES; word = word + 1) begin : p_word"
        )
      )
      assert(
        verilog.contains(
          "state[(word * 8) +: 8] <= din[(word * 8) +: 8];"
        )
      )
      assert(!verilog.contains("genvar word"))
      assert(!verilog.contains("MORPH_PROC_FOR"))
    }
  }

  test("a witness-valid procedural slice rejects a narrower retained source domain") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("narrow_source_procedural_loop.v")
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = rtl.getFileName.toString
      val failure = MorphVerilog.tryGenerate(config) {
        val width = HdlInt.param("WIDTH", default = 16, min = 8, max = 16)
        val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 4)
        new NarrowSourceProceduralLoopTop(width, lanes)
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected complete-domain procedural-slice rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-DOMAIN-UNSUPPORTED"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "escaping procedural slice published partial RTL")
    }
  }

  test("procedural loop inventory rejects independent same-name roots") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "independent_process_roots.v"
      val result = MorphVerilog.tryGenerate(config) {
        val first = HdlInt.param("LANES", default = 2, min = 1, max = 2)
        val second = HdlInt.param("LANES", default = 2, min = 1, max = 2)
        new Component {
          setDefinitionName("IndependentProcessRoots")
          val din = in(morphhdl.frontend.Bits(4 bits))
          val firstOut = out(morphhdl.frontend.Bits(4 bits))
          val secondOut = out(morphhdl.frontend.Bits(4 bits))
          firstOut := 0
          secondOut := 0
          (0 until first).named("p_first", "first_index").foreach { index =>
            val width = HdlInt.literal(BigInt(1))
            firstOut(index * width, width) := din(index * width, width)
          }
          (0 until second).named("p_second", "second_index").foreach { index =>
            val width = HdlInt.literal(BigInt(1))
            secondOut(index * width, width) := din(index * width, width)
          }
        }
      }

      result match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED"
            )
          )
        case Right(report) => fail(s"Expected independent-root failure, received $report")
      }
    }
  }

  test("generic fallback does not suppress inherited no-driver and latch validation") {
    withTemporaryDirectory { directory =>
      val noDriverConfig = SpinalConfig(
        targetDirectory = directory.resolve("no-driver").toString
      )
      noDriverConfig.netlistFileName = "no_driver.v"
      val noDriver = MorphVerilog.tryGenerate(noDriverConfig) {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
        new Component {
          setDefinitionName("InvalidNoDriverProcess")
          val din = in(morphhdl.frontend.Bits(width bits))
          val dout = out(morphhdl.frontend.Bits(width bits))
          val alive = out(Bool())
          alive := din.orR
        }
      }
      assert(noDriver.isLeft)
      assert(!Files.exists(directory.resolve("no-driver").resolve("no_driver.v")))

      val latchConfig = SpinalConfig(
        targetDirectory = directory.resolve("latch").toString
      )
      latchConfig.netlistFileName = "latch.v"
      val latch = MorphVerilog.tryGenerate(latchConfig) {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
        new Component {
          setDefinitionName("InvalidLatchProcess")
          val enable = in(Bool())
          val din = in(morphhdl.frontend.Bits(width bits))
          val dout = out(morphhdl.frontend.Bits(width bits))
          when(enable) {
            dout := din
          }
        }
      }
      assert(latch.isLeft)
      assert(!Files.exists(directory.resolve("latch").resolve("latch.v")))
    }
  }

  private def emitMorph(
      directory: Path,
      filename: String,
      component: => Component,
      config: SpinalConfig = null
  ): (MorphSingleSourceVerilogReport, String) = {
    Files.createDirectories(directory)
    val useConfig =
      if (config == null) SpinalConfig(targetDirectory = directory.toString)
      else config
    useConfig.netlistFileName = filename
    val report = MorphVerilog(useConfig)(component)
    report -> read(directory.resolve(filename))
  }

  private def emitConcrete(
      directory: Path,
      filename: String,
      component: => Component,
      config: SpinalConfig = null
  ): String = {
    Files.createDirectories(directory)
    val useConfig =
      if (config == null) SpinalConfig(targetDirectory = directory.toString)
      else config
    useConfig.netlistFileName = filename
    SpinalVerilog(useConfig)(component)
    read(directory.resolve(filename))
  }

  private def asynchronousResetConfig(directory: Path): SpinalConfig =
    SpinalConfig(
      targetDirectory = directory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = LOW
      )
    )

  private def synchronousResetConfig(directory: Path): SpinalConfig =
    SpinalConfig(
      targetDirectory = directory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    )

  private def nativeModule(verilog: String): String =
    "(?m)^module\\s".r
      .findFirstMatchIn(verilog)
      .map(module => verilog.substring(module.start))
      .getOrElse(verilog)

  private def concretizeWidth(
      verilog: String,
      moduleName: String,
      width: Int
  ): String = {
    val header: Regex =
      ("(?s)module " + java.util.regex.Pattern.quote(moduleName) +
        " #\\(\\n.*?\\n\\) \\(").r
    header
      .replaceFirstIn(verilog, s"module $moduleName (")
      .replace("[WIDTH-1:0]", s"[${width - 1}:0]")
      .replace("{WIDTH{1'b0}}", s"${width}'h0")
  }

  private def simulateProceduralLoop(
      directory: Path,
      verilog: Path
  ): Unit = {
    val testbench = directory.resolve("procedural_loop_tb.v")
    val executable = directory.resolve("procedural_loop_tb.out")
    Files.write(
      testbench,
      """`timescale 1ns/1ps
        |module procedural_loop_tb;
        |  reg [31:0] din;
        |  wire [31:0] dout;
        |
        |  ProceduralLoopTop #(
        |    .LANES(2)
        |  ) dut (
        |    .din(din),
        |    .dout(dout)
        |  );
        |
        |  initial begin
        |    din = 32'hA1B2C3D4;
        |    #1;
        |    if (dout !== 32'h0000C3D4)
        |      $display("FAIL procedural loop dout=%h", dout);
        |    else
        |      $display("PASS procedural loop");
        |    $finish;
        |  end
        |endmodule
        |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    )

    val compileLog = new StringBuilder
    val compileResult = scala.sys.process
      .Process(
        Seq(
          "iverilog",
          "-g2001",
          "-s",
          "procedural_loop_tb",
          "-o",
          executable.toString,
          verilog.toString,
          testbench.toString
        ),
        directory.toFile
      )
      .!(ProcessLogger(line => compileLog.append(line).append('\n')))
    assert(compileResult == 0, compileLog.toString)

    val simulationLog = new StringBuilder
    val simulationResult = scala.sys.process
      .Process(
        Seq("vvp", executable.toString),
        directory.toFile
      )
      .!(ProcessLogger(line => simulationLog.append(line).append('\n')))
    assert(simulationResult == 0, simulationLog.toString)
    assert(simulationLog.toString.contains("PASS procedural loop"))
    assert(!simulationLog.toString.contains("FAIL"))
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-generic-process-test-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
