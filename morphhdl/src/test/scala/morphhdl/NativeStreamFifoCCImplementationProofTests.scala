package morphhdl

import java.nio.file.Path

import org.scalatest.funsuite.AnyFunSuite

import NativeStreamFifoCCProofSupport._

class NativeStreamFifoCCImplementationProofTests extends AnyFunSuite {
  test("native StreamFifoCC parameterization passes lint synthesis and independent-clock simulation") {
    withTemporaryDirectory { directory =>
      requireTool(directory, Seq("iverilog", "-V"), "Icarus Verilog")
      requireTool(directory, Seq("yosys", "-V"), "Yosys")

      Modes.foreach { buffered =>
        val candidate = generateCandidate(
          directory.resolve(s"candidate-${modeName(buffered)}"),
          buffered
        )
        val candidateText = read(candidate)
        assert(candidateText.contains("parameter integer DEPTH = 8"))
        assert(candidateText.contains("module StreamFifoCC"))
        assert(!candidateText.contains("module MorphStreamFifoCC"))

        Depths.foreach { depth =>
          lint(directory, candidate, candidateTop(buffered), depth)
          synthesize(directory, candidate, candidateTop(buffered), depth, buffered)
          simulate(directory, candidate, candidateTop(buffered), depth, buffered)

          val concrete = generateConcrete(
            directory.resolve(s"concrete-${depth}-${modeName(buffered)}"),
            depth,
            buffered
          )
          val concreteText = read(concrete)
          assert(!concreteText.contains("parameter integer DEPTH"))
          lintConcrete(directory, concrete, concreteTop(depth, buffered))
        }
      }
    }
  }

  private def lint(
      directory: Path,
      candidate: Path,
      top: String,
      depth: Int
  ): Unit = {
    val output = directory.resolve(s"lint-${top}-${depth}.out")
    val (code, log) = run(
      directory,
      Seq(
        "iverilog",
        "-g2001",
        "-s",
        top,
        s"-P$top.DEPTH=$depth",
        "-o",
        output.toString,
        candidate.toAbsolutePath.toString
      )
    )
    assert(code == 0, s"Verilog-2001 lint failed for $top DEPTH=$depth:\n$log")
  }

  private def lintConcrete(
      directory: Path,
      concrete: Path,
      top: String
  ): Unit = {
    val output = directory.resolve(s"lint-${top}.out")
    val (code, log) = run(
      directory,
      Seq(
        "iverilog",
        "-g2001",
        "-s",
        top,
        "-o",
        output.toString,
        concrete.toAbsolutePath.toString
      )
    )
    assert(code == 0, s"Concrete Verilog-2001 lint failed for $top:\n$log")
  }

  private def synthesize(
      directory: Path,
      candidate: Path,
      top: String,
      depth: Int,
      buffered: Boolean
  ): Unit = {
    val script = directory.resolve(s"synth-${depth}-${modeName(buffered)}.ys")
    write(
      script,
      s"""read_verilog -defer ${yosysPath(candidate)}
         |chparam -set DEPTH $depth $top
         |hierarchy -check -top $top
         |proc
         |memory
         |opt
         |check -assert
         |stat
         |""".stripMargin
    )
    val (code, log) = run(directory, Seq("yosys", "-Q", "-s", script.toString))
    assert(code == 0, s"Yosys synthesis failed for $top DEPTH=$depth:\n$log")
    assert(log.contains("Number of cells"), s"Yosys did not report a synthesized design:\n$log")
  }

  private def simulate(
      directory: Path,
      candidate: Path,
      top: String,
      depth: Int,
      buffered: Boolean
  ): Unit = {
    val bench = directory.resolve(s"tb-${depth}-${modeName(buffered)}.v")
    write(bench, simulationBench(top, depth))
    val executable = directory.resolve(s"sim-${depth}-${modeName(buffered)}.out")
    val (compileCode, compileLog) = run(
      directory,
      Seq(
        "iverilog",
        "-g2012",
        "-s",
        "tb",
        s"-P$top.DEPTH=$depth",
        "-o",
        executable.toString,
        candidate.toAbsolutePath.toString,
        bench.toString
      )
    )
    assert(
      compileCode == 0,
      s"Icarus compilation failed for $top DEPTH=$depth:\n$compileLog"
    )
    val (runCode, runLog) = run(directory, Seq("vvp", executable.toString))
    assert(runCode == 0, s"Independent-clock simulation failed:\n$runLog")
    assert(runLog.contains("STREAMFIFOCC_SIM_PASS"), s"Simulation did not close:\n$runLog")
  }

  private def simulationBench(top: String, depth: Int): String = {
    val transfers = depth * 3 + 7
    s"""`timescale 1ns/1ps
       |module tb;
       |  reg io_pushClock = 1'b0;
       |  reg io_pushReset = 1'b1;
       |  reg io_popClock = 1'b0;
       |  reg io_popReset = 1'b1;
       |  reg io_pushValid = 1'b0;
       |  wire io_pushReady;
       |  reg [7:0] io_pushPayload = 8'h00;
       |  wire io_popValid;
       |  reg io_popReady = 1'b0;
       |  wire [7:0] io_popPayload;
       |  wire [4:0] io_pushOccupancy;
       |  wire [4:0] io_popOccupancy;
       |
       |  integer sent = 0;
       |  integer received = 0;
       |  integer popCycles = 0;
       |  integer watchdog = 0;
       |
       |  always #5 io_pushClock = ~io_pushClock;
       |  always #7 io_popClock = ~io_popClock;
       |
       |  $top #(.DEPTH($depth)) dut (
       |    .io_pushClock(io_pushClock),
       |    .io_pushReset(io_pushReset),
       |    .io_popClock(io_popClock),
       |    .io_popReset(io_popReset),
       |    .io_pushValid(io_pushValid),
       |    .io_pushReady(io_pushReady),
       |    .io_pushPayload(io_pushPayload),
       |    .io_popValid(io_popValid),
       |    .io_popReady(io_popReady),
       |    .io_popPayload(io_popPayload),
       |    .io_pushOccupancy(io_pushOccupancy),
       |    .io_popOccupancy(io_popOccupancy)
       |  );
       |
       |  always @(negedge io_pushClock) begin
       |    if (io_pushReset) begin
       |      io_pushValid <= 1'b0;
       |      io_pushPayload <= 8'h00;
       |    end else if (sent < $transfers) begin
       |      io_pushValid <= 1'b1;
       |      io_pushPayload <= sent[7:0];
       |    end else begin
       |      io_pushValid <= 1'b0;
       |    end
       |  end
       |
       |  always @(posedge io_pushClock) begin
       |    if (!io_pushReset && io_pushValid && io_pushReady)
       |      sent <= sent + 1;
       |  end
       |
       |  always @(negedge io_popClock) begin
       |    if (io_popReset) begin
       |      io_popReady <= 1'b0;
       |      popCycles <= 0;
       |    end else begin
       |      popCycles <= popCycles + 1;
       |      io_popReady <= ((popCycles % 5) != 1) && ((popCycles % 7) != 3);
       |    end
       |  end
       |
       |  always @(posedge io_popClock) begin
       |    if (!io_popReset && io_popValid && io_popReady) begin
       |      if (io_popPayload !== received[7:0]) begin
       |        $$display("STREAMFIFOCC_ORDER_FAIL expected=%0d actual=%0d", received, io_popPayload);
       |        $$fatal(1);
       |      end
       |      received <= received + 1;
       |    end
       |  end
       |
       |  initial begin
       |    #23 io_pushReset = 1'b0;
       |    #18 io_popReset = 1'b0;
       |    while (received < $transfers && watchdog < 10000) begin
       |      #1 watchdog = watchdog + 1;
       |    end
       |    if (received != $transfers) begin
       |      $$display("STREAMFIFOCC_TIMEOUT sent=%0d received=%0d", sent, received);
       |      $$fatal(1);
       |    end
       |    repeat (12) @(posedge io_popClock);
       |    if (io_popValid !== 1'b0) begin
       |      $$display("STREAMFIFOCC_DRAIN_FAIL");
       |      $$fatal(1);
       |    end
       |    $$display("STREAMFIFOCC_SIM_PASS depth=$depth sent=%0d received=%0d", sent, received);
       |    $$finish;
       |  end
       |endmodule
       |""".stripMargin
  }
}
