package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import morphhdl.frontend.HdlInt

final class NativeParameterizedStreamFifoHarness(depth: HdlInt) extends Component {
  setDefinitionName("NativeParameterizedStreamFifoHarness")

  val io = new Bundle {
    val push = slave Stream (Bits(8 bits))
    val pop = master Stream (Bits(8 bits))
    val flush = in Bool ()
    val occupancy = out UInt (4 bits)
    val availability = out UInt (4 bits)
  }

  val fifo = spinal.lib.StreamFifo(
    HardType(Bits(8 bits)),
    depth.asElabInt
  )
  fifo.setName("fifo")
  fifo.io.push << io.push
  io.pop << fifo.io.pop
  fifo.io.flush := io.flush
  io.occupancy := fifo.io.occupancy.resized
  io.availability := fifo.io.availability.resized
}

final class UnsafeStructuralAssignmentHarness(depth: HdlInt) extends Component {
  setDefinitionName("UnsafeStructuralAssignmentHarness")
  val observed = out(Bool())
  observed := False
  if (depth > 1) observed := True
  else observed := False
}

object SymbolicStreamFifoFormalHelperHarness {
  sealed trait Operation {
    def id: String
    def invoke(fifo: StreamFifo[Bits]): Unit
  }

  case object CheckLastPush extends Operation {
    override val id = "check-last-push"
    override def invoke(fifo: StreamFifo[Bits]): Unit = {
      fifo.formalCheckLastPush(_.orR)
      ()
    }
  }

  case object CheckRam extends Operation {
    override val id = "check-ram"
    override def invoke(fifo: StreamFifo[Bits]): Unit = {
      fifo.formalCheckRam(_.orR)
      ()
    }
  }

  case object Contains extends Operation {
    override val id = "contains"
    override def invoke(fifo: StreamFifo[Bits]): Unit = {
      fifo.formalContains(_.orR)
      ()
    }
  }

  case object Count extends Operation {
    override val id = "count"
    override def invoke(fifo: StreamFifo[Bits]): Unit = {
      fifo.formalCount(_.orR)
      ()
    }
  }

  case object FullToEmpty extends Operation {
    override val id = "full-to-empty"
    override def invoke(fifo: StreamFifo[Bits]): Unit = {
      fifo.formalFullToEmpty()
      ()
    }
  }

  val Operations = Vector(CheckLastPush, CheckRam, Contains, Count, FullToEmpty)
}

final class SymbolicStreamFifoFormalHelperHarness(
    depth: HdlInt,
    operation: SymbolicStreamFifoFormalHelperHarness.Operation
) extends Component {
  val fifo = spinal.lib.StreamFifo(
    HardType(Bits(8 bits)),
    depth.asElabInt
  )
  operation.invoke(fifo)
}

class ParameterizedStreamFifoDepthTests extends AnyFunSuite {
  private val SymbolicFormalDepthCode =
    "SPINAL-ELAB-STREAMFIFO-FORMAL-SYMBOLIC-DEPTH-UNSUPPORTED"

  private val ExpectedStreamFifoModuleInventory =
    Vector("NativeParameterizedStreamFifoHarness", "StreamFifo").sorted

  private val ModuleDeclaration =
    """(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b""".r

  private def streamFifoModuleInventory(verilog: String): Vector[String] =
    ModuleDeclaration
      .findAllMatchIn(verilog)
      .map(_.group(1))
      .filter(_.contains("StreamFifo"))
      .toVector
      .sorted

  private def nativeStreamFifoDefinition(verilog: String): String =
    "(?ms)^\\s*module\\s+StreamFifo\\b.*?^\\s*endmodule\\b".r
      .findFirstIn(verilog)
      .getOrElse(fail("Native StreamFifo module definition is missing"))

  private def component(default: Int = 5): NativeParameterizedStreamFifoHarness = {
    val depth = HdlInt.param(
      "DEPTH",
      default = BigInt(default),
      min = BigInt(1),
      max = BigInt(8)
    )
    new NativeParameterizedStreamFifoHarness(depth)
  }

  test("default depth one still captures the native storage alternative") {
    withTemporaryDirectory { directory =>
      val config = synchronousResetConfig(directory)
      config.netlistFileName = "stream_fifo_default_one.v"
      val report = MorphVerilog(config)(component(default = 1))
      val verilog = read(directory.resolve(config.netlistFileName))
      val nativeStreamFifo = nativeStreamFifoDefinition(verilog)

      assert(report.parameters.map(parameter => parameter.name -> parameter.default) == Vector("DEPTH" -> BigInt(1)))
      assert(verilog.contains("parameter integer DEPTH = 1"))
      assert(verilog.contains(".DEPTH(DEPTH)"))
      assert(
        verilog.contains("[0:DEPTH-1]") ||
          verilog.contains("[0:(DEPTH - 1)]")
      )
      assert("""DEPTH\s*\)?\s*>\s*\(?\s*1""".r.findFirstIn(nativeStreamFifo).nonEmpty)
      assert(
        streamFifoModuleInventory(verilog) == ExpectedStreamFifoModuleInventory
      )
    }
  }

  test("one native StreamFifo definition preserves depths 1, 3, 5 and 8") {
    withTemporaryDirectory { directory =>
      val parameterizedDirectory = directory.resolve("parameterized")
      val concreteDirectory = directory.resolve("concrete")
      Files.createDirectories(parameterizedDirectory)
      Files.createDirectories(concreteDirectory)

      val parameterizedConfig = synchronousResetConfig(parameterizedDirectory)
      parameterizedConfig.netlistFileName = "stream_fifo_parameterized_depth.v"
      val parameterizedReport = MorphVerilog(parameterizedConfig)(component())
      val parameterizedRtl =
        parameterizedDirectory.resolve("stream_fifo_parameterized_depth.v")
      val parameterized = read(parameterizedRtl)

      val replayDirectory = directory.resolve("parameterized-replay")
      Files.createDirectories(replayDirectory)
      val replayConfig = synchronousResetConfig(replayDirectory)
      replayConfig.netlistFileName = "stream_fifo_parameterized_depth.v"
      MorphVerilog(replayConfig)(component())
      val replayRtl =
        replayDirectory.resolve("stream_fifo_parameterized_depth.v")
      assert(
        java.util.Arrays.equals(
          Files.readAllBytes(parameterizedRtl),
          Files.readAllBytes(replayRtl)
        ),
        "identical native StreamFifo generation was not byte deterministic"
      )

      val concreteConfig = synchronousResetConfig(concreteDirectory)
      concreteConfig.netlistFileName = "stream_fifo_parameterized_depth.v"
      SpinalVerilog(concreteConfig)(component())
      val concrete =
        read(concreteDirectory.resolve("stream_fifo_parameterized_depth.v"))

      val depthParameter = parameterizedReport.parameters.find(_.name == "DEPTH")
      assert(depthParameter.nonEmpty)
      assert(depthParameter.get.default == BigInt(5))
      assert(
        depthParameter.get.constraints == Vector(
          paramrtl.IntConstraint.MinInclusive(BigInt(1)),
          paramrtl.IntConstraint.MaxInclusive(BigInt(8))
        )
      )
      val streamFifoModules = streamFifoModuleInventory(parameterized)
      assert(
        streamFifoModules == ExpectedStreamFifoModuleInventory,
        s"Unexpected StreamFifo module inventory: ${streamFifoModules.mkString(", ")}"
      )
      assert(parameterized.contains("module NativeParameterizedStreamFifoHarness #("))
      assert(parameterized.contains("parameter integer DEPTH = 5"))
      assert(parameterized.contains(".DEPTH(DEPTH)"))
      assert(
        parameterized.contains("[0:DEPTH-1]") ||
          parameterized.contains("[0:(DEPTH - 1)]")
      )
      // Typed log2Up preserves log2Up(1) == 0.  Its use as a packed width is
      // valid here because the declaration is owned by the DEPTH > 1 branch.
      assert(parameterized.contains("clog2(DEPTH, 0)"))
      assert(parameterized.contains("function integer clog2;"))
      assert(!parameterized.contains("morphhdl_address_width"))
      assert(!parameterized.contains("morphhdl_ceil_log2"))
      val nativeStreamFifo = nativeStreamFifoDefinition(parameterized)
      val depthOneCondition =
        """DEPTH\s*\)?\s*==\s*\(?\s*1""".r.findFirstMatchIn(nativeStreamFifo)
      val storageCondition =
        """DEPTH\s*\)?\s*>\s*\(?\s*1""".r.findFirstMatchIn(nativeStreamFifo)
      assert(nativeStreamFifo.contains("generate"))
      assert(depthOneCondition.nonEmpty)
      assert(storageCondition.nonEmpty)
      assert(
        nativeStreamFifo.contains("DEPTH & (DEPTH - 1)") ||
          nativeStreamFifo.contains("DEPTH & (DEPTH-1)")
      )
      val logicAlternative = storageCondition.get.start
      val powerOfTwoAlternative = nativeStreamFifo.indexOf("DEPTH &")
      assert(logicAlternative >= 0 && powerOfTwoAlternative > logicAlternative)
      assert(parameterized.contains("io_push_ready"))
      assert(parameterized.contains("io_pop_valid"))
      assert(parameterized.contains("io_occupancy"))
      assert(parameterized.contains("io_availability"))
      assert(!parameterized.contains("ParamRTL"))
      assert(!parameterized.contains("rewriteParameterizedStreamFifoDepth"))

      assert(!concrete.contains("parameter integer DEPTH"))
      assert(concrete.contains("[0:4]"))
      assert(concrete.contains("[7:0]"))

      val rtl = parameterizedDirectory.resolve("stream_fifo_parameterized_depth.v")
      Vector(1, 3, 5, 8).foreach { selectedDepth =>
        lintDepth(parameterizedDirectory, rtl, selectedDepth)
        simulateDepth(parameterizedDirectory, rtl, selectedDepth)
        synthesizeDepth(parameterizedDirectory, rtl, selectedDepth)
      }
    }
  }

  test("mutually-exclusive capture never hides an outside assignment") {
    withTemporaryDirectory { directory =>
      val config = synchronousResetConfig(directory)
      config.netlistFileName = "unsafe_structural_assignment.v"
      MorphVerilog.tryGenerate(config) {
        val depth = HdlInt.param(
          "DEPTH",
          default = BigInt(5),
          min = BigInt(1),
          max = BigInt(8)
        )
        new UnsafeStructuralAssignmentHarness(depth)
      } match {
        case Left(failure) =>
          assert(failure.detail.contains("ASSIGNMENT OVERLAP"))
        case Right(report) =>
          fail(s"Expected inherited overlap failure, received $report")
      }
    }
  }

  test("compound bounded depth retains its exact formal actual") {
    withTemporaryDirectory { directory =>
      val config = synchronousResetConfig(directory)
      config.netlistFileName = "stream_fifo_compound_depth.v"
      val report = MorphVerilog(config) {
        val base = HdlInt.param(
          "BASE",
          default = BigInt(4),
          min = BigInt(1),
          max = BigInt(7)
        )
        new NativeParameterizedStreamFifoHarness(
          base + HdlInt.literal(BigInt(1))
        )
      }
      val verilog = read(directory.resolve("stream_fifo_compound_depth.v"))
      val compact = verilog.replaceAll("\\s+", "")
      assert(report.parameters.map(_.name) == Vector("BASE"))
      val baseParameter = report.parameters.head
      assert(baseParameter.default == BigInt(4))
      assert(
        baseParameter.constraints == Vector(
          paramrtl.IntConstraint.MinInclusive(BigInt(1)),
          paramrtl.IntConstraint.MaxInclusive(BigInt(7))
        )
      )
      assert(compact.contains(".DEPTH((BASE+1))"))
      val streamFifoModules = streamFifoModuleInventory(verilog)
      assert(
        streamFifoModules == ExpectedStreamFifoModuleInventory,
        s"Unexpected StreamFifo module inventory: ${streamFifoModules.mkString(", ")}"
      )
    }
  }

  test("symbolic formal helpers fail closed independently of the default witness") {
    import SymbolicStreamFifoFormalHelperHarness._

    withTemporaryDirectory { directory =>
      val observedCodes = for {
        default <- Vector(1, 5)
        operation <- Operations
      } yield {
        val target = directory.resolve(s"default-$default-${operation.id}")
        Files.createDirectories(target)
        val config = synchronousResetConfig(target)
        config.netlistFileName = "symbolic_stream_fifo_formal_helper.v"
        val depth = HdlInt.param(
          "DEPTH",
          default = BigInt(default),
          min = BigInt(1),
          max = BigInt(8)
        )

        MorphVerilog.tryGenerate(config) {
          new SymbolicStreamFifoFormalHelperHarness(depth, operation)
        } match {
          case Left(failure) =>
            assert(
              failure.detail.contains(SymbolicFormalDepthCode),
              failure.detail
            )
            SymbolicFormalDepthCode
          case Right(report) =>
            fail(
              s"Expected $SymbolicFormalDepthCode for default $default and ${operation.id}, received $report"
            )
        }
      }

      assert(observedCodes.toSet == Set(SymbolicFormalDepthCode))
    }
  }

  private def lintDepth(
      directory: Path,
      rtl: Path,
      selectedDepth: Int
  ): Unit = {
    val depthSpecificWarnings =
      if (selectedDepth == 1) Seq("-Wno-CMPCONST")
      else {
        // Native StreamFifo keeps inherited dead helper nets in ordinary
        // concrete output. Legacy Verilator reports that family as UNUSED,
        // while current Verilator splits it into finer unused categories.
        // This leaves UNDRIVEN and the existing non-unused warning policy unchanged.
        Seq("-Wno-UNUSED")
      }
    val command = Seq(
      "verilator",
      "--lint-only",
      "--language",
      "1364-2001",
      "-Wall",
      "-Wno-DECLFILENAME",
      "-Wno-WIDTH"
    ) ++ depthSpecificWarnings ++ Seq(
      "--top-module",
      "NativeParameterizedStreamFifoHarness",
      s"-GDEPTH=$selectedDepth",
      rtl.toString
    )
    val result = run(directory, command)
    assert(
      result._1 == 0,
      s"Verilator lint failed for DEPTH=$selectedDepth:\n${result._2}"
    )
  }

  private def simulateDepth(
      directory: Path,
      rtl: Path,
      selectedDepth: Int
  ): Unit = {
    val testbench = directory.resolve(s"StreamFifoDepth${selectedDepth}Tb.v")
    val executable = directory.resolve(s"StreamFifoDepth${selectedDepth}Tb.out")
    val source =
      s"""`timescale 1ns/1ps
         |module StreamFifoDepth${selectedDepth}Tb;
         |  localparam integer DEPTH = $selectedDepth;
         |  reg clk = 1'b0;
         |  reg reset = 1'b1;
         |  reg io_push_valid = 1'b0;
         |  wire io_push_ready;
         |  reg [7:0] io_push_payload = 8'h00;
         |  wire io_pop_valid;
         |  reg io_pop_ready = 1'b0;
         |  wire [7:0] io_pop_payload;
         |  reg io_flush = 1'b0;
         |  wire [3:0] io_occupancy;
         |  wire [3:0] io_availability;
         |  integer capacity;
         |  integer sent;
         |  integer received;
         |  integer timeout;
         |
         |  always #5 clk = ~clk;
         |
         |  StreamFifo #(
         |    .DEPTH(DEPTH)
         |  ) dut (
         |    .io_push_valid(io_push_valid),
         |    .io_push_ready(io_push_ready),
         |    .io_push_payload(io_push_payload),
         |    .io_pop_valid(io_pop_valid),
         |    .io_pop_ready(io_pop_ready),
         |    .io_pop_payload(io_pop_payload),
         |    .io_flush(io_flush),
         |    .io_occupancy(io_occupancy),
         |    .io_availability(io_availability),
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
         |    input [255:0] reason;
         |    begin
         |      $$display("FAIL depth=%0d: %0s", DEPTH, reason);
         |      $$display("STATE sent=%0d received=%0d ready=%b valid=%b occupancy=%0d availability=%0d",
         |        sent, received, io_push_ready, io_pop_valid, io_occupancy, io_availability);
         |      $$finish(2);
         |    end
         |  endtask
         |
         |  initial begin
         |    repeat (3) tick;
         |    reset = 1'b0;
         |    tick;
         |    if (io_occupancy !== 0) fail("reset occupancy mismatch");
         |    if (io_availability !== DEPTH) fail("reset availability mismatch");
         |
         |    capacity = DEPTH;
         |    for (sent = 0; sent < capacity; sent = sent + 1) begin
         |      io_push_payload = 8'h40 + sent;
         |      io_push_valid = 1'b1;
         |      timeout = 0;
         |      while (!io_push_ready && timeout < 50) begin
         |        tick;
         |        timeout = timeout + 1;
         |      end
         |      if (!io_push_ready) fail("push timeout");
         |      tick;
         |      if (io_occupancy !== (sent + 1)) fail("occupancy mismatch after push");
         |      if (io_availability !== (DEPTH - sent - 1)) fail("availability mismatch after push");
         |    end
         |    io_push_valid = 1'b0;
         |    tick;
         |    if (io_push_ready !== 1'b0) fail("fifo did not report full");
         |    if (io_occupancy !== capacity) fail("full occupancy mismatch");
         |    if (io_availability !== 0) fail("full availability mismatch");
         |
         |    io_pop_ready = 1'b1;
         |    received = 0;
         |    timeout = 0;
         |    while (received < capacity && timeout < 200) begin
         |      if (io_pop_valid) begin
         |        if (io_pop_payload !== (8'h40 + received))
         |          fail("payload ordering mismatch");
         |        received = received + 1;
         |      end
         |      tick;
         |      if (io_occupancy !== (capacity - received)) fail("occupancy mismatch after pop");
         |      if (io_availability !== received) fail("availability mismatch after pop");
         |      timeout = timeout + 1;
         |    end
         |    if (received != capacity) fail("pop timeout");
         |    io_pop_ready = 1'b0;
         |    tick;
         |    if (io_pop_valid !== 1'b0) fail("fifo did not become empty");
         |    if (io_occupancy !== 0) fail("empty occupancy mismatch");
         |    if (io_availability !== DEPTH) fail("empty availability mismatch");
         |
         |    io_push_payload = 8'hA5;
         |    io_push_valid = 1'b1;
         |    timeout = 0;
         |    while (!io_push_ready && timeout < 50) begin
         |      tick;
         |      timeout = timeout + 1;
         |    end
         |    if (!io_push_ready) fail("post-drain push timeout");
         |    tick;
         |    if (io_occupancy !== 1) fail("post-drain occupancy mismatch");
         |    if (io_availability !== (DEPTH - 1)) fail("post-drain availability mismatch");
         |    io_push_valid = 1'b0;
         |    io_flush = 1'b1;
         |    tick;
         |    io_flush = 1'b0;
         |    tick;
         |    if (io_pop_valid !== 1'b0) fail("flush did not discard queued data");
         |    if (io_occupancy !== 0) fail("flush occupancy mismatch");
         |    if (io_availability !== DEPTH) fail("flush availability mismatch");
         |
         |    $$display("PASS depth=%0d", DEPTH);
         |    $$finish;
         |  end
         |endmodule
         |""".stripMargin
    Files.write(testbench, source.getBytes(StandardCharsets.UTF_8))

    val compileLog = run(
      directory,
      Seq(
        "iverilog",
        "-g2001",
        "-s",
        s"StreamFifoDepth${selectedDepth}Tb",
        "-o",
        executable.toString,
        rtl.toString,
        testbench.toString
      )
    )
    assert(compileLog._1 == 0, compileLog._2)
    val simulationLog = run(directory, Seq("vvp", executable.toString))
    if (
      simulationLog._1 != 0 ||
      !simulationLog._2.contains(s"PASS depth=$selectedDepth")
    ) {
      println(s"--- BEGIN PARAMETERIZED FIFO RTL depth=$selectedDepth ---")
      println(read(rtl))
      println(s"--- END PARAMETERIZED FIFO RTL depth=$selectedDepth ---")
    }
    assert(simulationLog._1 == 0, simulationLog._2)
    assert(
      simulationLog._2.contains(s"PASS depth=$selectedDepth"),
      simulationLog._2
    )
  }

  private def synthesizeDepth(
      directory: Path,
      rtl: Path,
      selectedDepth: Int
  ): Unit = {
    val script = directory.resolve(s"stream_fifo_depth_$selectedDepth.ys")
    Files.write(
      script,
      s"""read_verilog -defer ${rtl.toString}
         |chparam -set DEPTH $selectedDepth StreamFifo
         |hierarchy -check -top StreamFifo
         |synth -top StreamFifo
         |check -assert
         |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    )
    val result = run(directory, Seq("yosys", "-q", "-s", script.toString))
    assert(result._1 == 0, result._2)
  }

  private def synchronousResetConfig(directory: Path): SpinalConfig =
    SpinalConfig(
      targetDirectory = directory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    )

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

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-streamfifo-depth-test-")
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
