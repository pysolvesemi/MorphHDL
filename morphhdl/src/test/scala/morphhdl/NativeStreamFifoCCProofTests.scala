package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import morphhdl.frontend.HdlInt

final class NativeParameterizedStreamFifoCCProofHarness(
    depth: HdlInt,
    withPopBufferedReset: Boolean
) extends Component {
  setDefinitionName(
    if (withPopBufferedReset)
      "NativeParameterizedStreamFifoCCProofHarnessBuffered"
    else "NativeParameterizedStreamFifoCCProofHarnessDirect"
  )

  val io = new Bundle {
    val pushClock = in Bool ()
    val pushReset = in Bool ()
    val popClock = in Bool ()
    val popReset = in Bool ()
    val pushValid = in Bool ()
    val pushReady = out Bool ()
    val pushPayload = in Bits (8 bits)
    val popValid = out Bool ()
    val popReady = in Bool ()
    val popPayload = out Bits (8 bits)
  }

  val pushClockDomain = ClockDomain(clock = io.pushClock, reset = io.pushReset)
  val popClockDomain = ClockDomain(clock = io.popClock, reset = io.popReset)

  val fifo = morphhdl.frontend.StreamFifoCC(
    HardType(Bits(8 bits)),
    depth,
    pushClockDomain,
    popClockDomain,
    withPopBufferedReset
  )
  fifo.setName("fifo")

  fifo.io.push.valid := io.pushValid
  fifo.io.push.payload := io.pushPayload
  io.pushReady := fifo.io.push.ready
  io.popValid := fifo.io.pop.valid
  fifo.io.pop.ready := io.popReady
  io.popPayload := fifo.io.pop.payload
}

final class NativeConcreteStreamFifoCCProofHarness(
    depth: Int,
    withPopBufferedReset: Boolean
) extends Component {
  require(depth >= 2 && (depth & (depth - 1)) == 0)
  private val suffix = if (withPopBufferedReset) "Buffered" else "Direct"
  setDefinitionName(s"NativeConcreteStreamFifoCCProofHarness${suffix}Depth$depth")

  val io = new Bundle {
    val pushClock = in Bool ()
    val pushReset = in Bool ()
    val popClock = in Bool ()
    val popReset = in Bool ()
    val pushValid = in Bool ()
    val pushReady = out Bool ()
    val pushPayload = in Bits (8 bits)
    val popValid = out Bool ()
    val popReady = in Bool ()
    val popPayload = out Bits (8 bits)
  }

  val pushClockDomain = ClockDomain(clock = io.pushClock, reset = io.pushReset)
  val popClockDomain = ClockDomain(clock = io.popClock, reset = io.popReset)

  val fifo = spinal.lib.StreamFifoCC(
    HardType(Bits(8 bits)),
    depth,
    pushClockDomain,
    popClockDomain,
    withPopBufferedReset
  )
  fifo.setDefinitionName(s"ConcreteStreamFifoCC${suffix}Depth$depth")
  fifo.setName("fifo")

  fifo.io.push.valid := io.pushValid
  fifo.io.push.payload := io.pushPayload
  io.pushReady := fifo.io.push.ready
  io.popValid := fifo.io.pop.valid
  fifo.io.pop.ready := io.popReady
  io.popPayload := fifo.io.pop.payload
}

class NativeStreamFifoCCProofTests extends AnyFunSuite {
  private val Depths = Vector(4, 8, 16)
  private val Modes = Vector(false, true)
  private val ImplementationGate = "MORPHDL_RUN_STREAMFIFOCC_IMPLEMENTATION_PROOF"
  private val FormalGate = "MORPHDL_RUN_STREAMFIFOCC_FORMAL_EQUIVALENCE"
  private val Workspace = "MORPHDL_STREAMFIFOCC_PROOF_WORKSPACE"

  test("native StreamFifoCC independent-clock implementation proof") {
    if (!sys.env.get(ImplementationGate).contains("1")) {
      cancel(s"Set $ImplementationGate=1 in the pinned proof environment")
    }
    withWorkspace("implementation") { directory =>
      requireTool(directory, Seq("iverilog", "-V"), "Icarus Verilog")
      requireTool(directory, Seq("vvp", "-V"), "Icarus vvp")
      requireTool(directory, Seq("yosys", "-V"), "Yosys")
      Modes.foreach { buffered =>
        val generated = generateParameterized(directory, buffered)
        Depths.foreach { depth =>
          runSimulation(directory, generated, depth, buffered)
          runSynthesis(directory, generated, depth, buffered)
        }
      }
    }
  }

  test("native StreamFifoCC concrete witnesses are solver-equivalent") {
    if (!sys.env.get(FormalGate).contains("1")) {
      cancel(s"Set $FormalGate=1 in the pinned formal environment")
    }
    withWorkspace("formal") { directory =>
      requireTool(directory, Seq("yosys", "-V"), "Yosys")
      requireTool(directory, Seq("sby", "-h"), "SymbiYosys")
      requireTool(directory, Seq("yices-smt2", "--version"), "Yices SMT2")

      Modes.foreach { buffered =>
        val candidate = generateParameterized(directory, buffered)
        Depths.foreach { depth =>
          val concrete = generateConcrete(directory, depth, buffered)
          runFormal(directory, candidate, concrete, depth, buffered, mutate = false)
        }
      }

      val candidate = generateParameterized(directory, withPopBufferedReset = false)
      val concrete = generateConcrete(directory, depth = 8, withPopBufferedReset = false)
      runFormal(
        directory,
        candidate,
        concrete,
        depth = 8,
        withPopBufferedReset = false,
        mutate = true
      )
    }
  }

  private def generateParameterized(
      directory: Path,
      withPopBufferedReset: Boolean
  ): Path = {
    val suffix = if (withPopBufferedReset) "buffered" else "direct"
    val target = directory.resolve(s"parameterized-$suffix")
    Files.createDirectories(target)
    val config = SpinalConfig(mode = Verilog, targetDirectory = target.toString)
    val fileName = s"stream_fifocc_parameterized_$suffix.v"
    config.netlistFileName = fileName
    val depth = HdlInt.param(
      "DEPTH",
      default = BigInt(8),
      min = BigInt(4),
      max = BigInt(16)
    )
    MorphVerilog(config) {
      new NativeParameterizedStreamFifoCCProofHarness(depth, withPopBufferedReset)
    }
    val result = target.resolve(fileName)
    assert(Files.isRegularFile(result))
    result
  }

  private def generateConcrete(
      directory: Path,
      depth: Int,
      withPopBufferedReset: Boolean
  ): Path = {
    val suffix = if (withPopBufferedReset) "buffered" else "direct"
    val target = directory.resolve(s"concrete-$suffix-$depth")
    Files.createDirectories(target)
    val config = SpinalConfig(mode = Verilog, targetDirectory = target.toString)
    val fileName = s"stream_fifocc_concrete_${suffix}_$depth.v"
    config.netlistFileName = fileName
    SpinalVerilog(config) {
      new NativeConcreteStreamFifoCCProofHarness(depth, withPopBufferedReset)
    }
    val result = target.resolve(fileName)
    assert(Files.isRegularFile(result))
    result
  }

  private def runSimulation(
      directory: Path,
      generated: Path,
      depth: Int,
      withPopBufferedReset: Boolean
  ): Unit = {
    val suffix = if (withPopBufferedReset) "buffered" else "direct"
    val top = parameterizedTop(withPopBufferedReset)
    val testbench = directory.resolve(s"tb_${suffix}_$depth.v")
    write(testbench, simulationTestbench(top, depth))
    val executable = directory.resolve(s"sim_${suffix}_$depth.out")
    runChecked(
      directory,
      Seq(
        "iverilog",
        "-g2001",
        "-s",
        "tb",
        "-P",
        s"$top.DEPTH=$depth",
        "-o",
        executable.toString,
        generated.toString,
        testbench.toString
      ),
      s"Icarus compile DEPTH=$depth buffered=$withPopBufferedReset"
    )
    val output = runChecked(
      directory,
      Seq("vvp", executable.toString),
      s"Icarus simulation DEPTH=$depth buffered=$withPopBufferedReset"
    )
    assert(output.contains("STREAMFIFOCC_SIM_PASS"), output)
  }

  private def runSynthesis(
      directory: Path,
      generated: Path,
      depth: Int,
      withPopBufferedReset: Boolean
  ): Unit = {
    val suffix = if (withPopBufferedReset) "buffered" else "direct"
    val top = parameterizedTop(withPopBufferedReset)
    val script = directory.resolve(s"synth_${suffix}_$depth.ys")
    write(
      script,
      s"""read_verilog -defer ${yosysPath(generated)}
         |chparam -set DEPTH $depth $top
         |hierarchy -check -top $top
         |proc
         |memory
         |opt
         |check -assert
         |stat
         |""".stripMargin
    )
    runChecked(
      directory,
      Seq("yosys", "-Q", "-s", script.toString),
      s"Yosys synthesis DEPTH=$depth buffered=$withPopBufferedReset"
    )
  }

  private def runFormal(
      directory: Path,
      candidate: Path,
      concrete: Path,
      depth: Int,
      withPopBufferedReset: Boolean,
      mutate: Boolean
  ): Unit = {
    val suffix = if (withPopBufferedReset) "buffered" else "direct"
    val mutation = if (mutate) "_mutation" else ""
    val candidateIl = directory.resolve(s"candidate_${suffix}_${depth}.il")
    val concreteIl = directory.resolve(s"concrete_${suffix}_${depth}.il")
    val candidateTop = s"candidate_${suffix}_${depth}"
    val concreteTop = s"reference_${suffix}_${depth}"

    val candidateScript = directory.resolve(s"prepare_candidate_${suffix}_${depth}.ys")
    write(
      candidateScript,
      s"""read_verilog -defer ${yosysPath(candidate)}
         |chparam -set DEPTH $depth ${parameterizedTop(withPopBufferedReset)}
         |hierarchy -check -top ${parameterizedTop(withPopBufferedReset)}
         |flatten
         |proc
         |memory_dff
         |memory_collect
         |opt_clean
         |rename -top $candidateTop
         |write_rtlil ${yosysPath(candidateIl)}
         |""".stripMargin
    )
    runChecked(directory, Seq("yosys", "-Q", "-s", candidateScript.toString), "prepare candidate")

    val concreteOriginalTop = concreteTopName(depth, withPopBufferedReset)
    val concreteScript = directory.resolve(s"prepare_concrete_${suffix}_${depth}.ys")
    write(
      concreteScript,
      s"""read_verilog -defer ${yosysPath(concrete)}
         |hierarchy -check -top $concreteOriginalTop
         |flatten
         |proc
         |memory_dff
         |memory_collect
         |opt_clean
         |rename -top $concreteTop
         |write_rtlil ${yosysPath(concreteIl)}
         |""".stripMargin
    )
    runChecked(directory, Seq("yosys", "-Q", "-s", concreteScript.toString), "prepare concrete")

    val miter = directory.resolve(s"miter_${suffix}_${depth}${mutation}.v")
    val miterTop = s"stream_fifocc_miter_${suffix}_${depth}${mutation}"
    write(miter, equivalenceMiter(miterTop, candidateTop, concreteTop, mutate))
    val sby = directory.resolve(s"stream_fifocc_${suffix}_${depth}${mutation}.sby")
    write(sby, formalConfig(candidateIl, concreteIl, miter, miterTop, mutate))
    val output = runAllowFailure(directory, Seq("sby", "-f", sby.toString))
    val statusFile = directory.resolve(s"stream_fifocc_${suffix}_${depth}${mutation}/status")
    val status = if (Files.isRegularFile(statusFile)) read(statusFile).trim else "MISSING"
    if (mutate) {
      assert(output._1 != 0 || status == "FAIL", output._2)
      assert(status == "FAIL", s"mutation status=$status\n${output._2}")
      val engineDirectory = directory.resolve(
        s"stream_fifocc_${suffix}_${depth}${mutation}/engine_0"
      )
      assert(
        Files.walk(engineDirectory).iterator().asScala.exists(path =>
          path.getFileName.toString.endsWith(".vcd") ||
            path.getFileName.toString.endsWith(".yw")
        ),
        s"mutation produced no counterexample artifact in $engineDirectory"
      )
    } else {
      assert(output._1 == 0, output._2)
      assert(status == "PASS", s"formal status=$status\n${output._2}")
    }
  }

  private def simulationTestbench(top: String, depth: Int): String =
    s"""`timescale 1ns/1ps
       |module tb;
       |  reg pushClock = 1'b0;
       |  reg popClock = 1'b0;
       |  reg pushReset = 1'b1;
       |  reg popReset = 1'b1;
       |  reg pushValid = 1'b0;
       |  wire pushReady;
       |  reg [7:0] pushPayload = 8'h00;
       |  wire popValid;
       |  reg popReady = 1'b0;
       |  wire [7:0] popPayload;
       |  integer sent = 0;
       |  integer received = 0;
       |  integer timeout = 0;
       |  integer expected [0:63];
       |
       |  always #5 pushClock = ~pushClock;
       |  always #7 popClock = ~popClock;
       |
       |  $top #(.DEPTH($depth)) dut (
       |    .io_pushClock(pushClock),
       |    .io_pushReset(pushReset),
       |    .io_popClock(popClock),
       |    .io_popReset(popReset),
       |    .io_pushValid(pushValid),
       |    .io_pushReady(pushReady),
       |    .io_pushPayload(pushPayload),
       |    .io_popValid(popValid),
       |    .io_popReady(popReady),
       |    .io_popPayload(popPayload)
       |  );
       |
       |  always @(posedge pushClock) begin
       |    if (!pushReset && pushValid && pushReady) begin
       |      expected[sent] = pushPayload;
       |      sent = sent + 1;
       |    end
       |  end
       |
       |  always @(posedge popClock) begin
       |    if (!popReset && popValid && popReady) begin
       |      if (received >= sent) begin
       |        $$display("POP_WITHOUT_PUSH");
       |        $$fatal(1);
       |      end
       |      if (popPayload !== expected[received][7:0]) begin
       |        $$display("ORDER_ERROR index=%0d expected=%02x actual=%02x", received, expected[received], popPayload);
       |        $$fatal(1);
       |      end
       |      received = received + 1;
       |    end
       |  end
       |
       |  initial begin
       |    repeat (4) @(posedge pushClock);
       |    pushReset = 1'b0;
       |    repeat (3) @(posedge popClock);
       |    popReset = 1'b0;
       |
       |    fork
       |      begin : producer
       |        while (sent < $depth + 9) begin
       |          @(negedge pushClock);
       |          if (pushReady) begin
       |            pushValid = 1'b1;
       |            pushPayload = (sent * 8'h1d) ^ 8'ha7;
       |          end else begin
       |            pushValid = 1'b0;
       |          end
       |        end
       |        @(negedge pushClock);
       |        pushValid = 1'b0;
       |      end
       |      begin : consumer
       |        repeat ($depth + 3) @(posedge popClock);
       |        while (received < $depth + 9) begin
       |          @(negedge popClock);
       |          popReady = ((timeout % 5) != 1);
       |          timeout = timeout + 1;
       |          if (timeout > 4000) begin
       |            $$display("TIMEOUT sent=%0d received=%0d", sent, received);
       |            $$fatal(1);
       |          end
       |        end
       |        @(negedge popClock);
       |        popReady = 1'b0;
       |      end
       |    join
       |
       |    if (sent != $depth + 9 || received != sent) begin
       |      $$display("COUNT_ERROR sent=%0d received=%0d", sent, received);
       |      $$fatal(1);
       |    end
       |    $$display("STREAMFIFOCC_SIM_PASS depth=$depth");
       |    $$finish;
       |  end
       |endmodule
       |""".stripMargin

  private def equivalenceMiter(
      top: String,
      candidateTop: String,
      concreteTop: String,
      mutate: Boolean
  ): String = {
    val candidateReady = if (mutate) "(candidate_pushReady ^ 1'b1)" else "candidate_pushReady"
    s"""module $top (
       |  input wire pushClock,
       |  input wire pushReset,
       |  input wire popClock,
       |  input wire popReset,
       |  input wire pushValid,
       |  input wire [7:0] pushPayload,
       |  input wire popReady
       |);
       |  wire candidate_pushReady;
       |  wire candidate_popValid;
       |  wire [7:0] candidate_popPayload;
       |  wire reference_pushReady;
       |  wire reference_popValid;
       |  wire [7:0] reference_popPayload;
       |
       |  $candidateTop candidate (
       |    .io_pushClock(pushClock), .io_pushReset(pushReset),
       |    .io_popClock(popClock), .io_popReset(popReset),
       |    .io_pushValid(pushValid), .io_pushReady(candidate_pushReady),
       |    .io_pushPayload(pushPayload), .io_popValid(candidate_popValid),
       |    .io_popReady(popReady), .io_popPayload(candidate_popPayload)
       |  );
       |  $concreteTop reference (
       |    .io_pushClock(pushClock), .io_pushReset(pushReset),
       |    .io_popClock(popClock), .io_popReset(popReset),
       |    .io_pushValid(pushValid), .io_pushReady(reference_pushReady),
       |    .io_pushPayload(pushPayload), .io_popValid(reference_popValid),
       |    .io_popReady(popReady), .io_popPayload(reference_popPayload)
       |  );
       |
       |  always @* begin
       |    if ($$initstate) begin
       |      assume(pushReset);
       |      assume(popReset);
       |    end
       |    if (!$$initstate) begin
       |      assert(reference_pushReady == $candidateReady);
       |      assert(reference_popValid == candidate_popValid);
       |      if (reference_popValid && candidate_popValid)
       |        assert(reference_popPayload == candidate_popPayload);
       |    end
       |  end
       |endmodule
       |""".stripMargin
  }

  private def formalConfig(
      candidate: Path,
      concrete: Path,
      miter: Path,
      top: String,
      mutate: Boolean
  ): String = {
    val mode = if (mutate) "bmc" else "prove"
    val depth = if (mutate) "8" else "24"
    val expectation = if (mutate) "fail" else "pass"
    s"""[options]
       |mode $mode
       |depth $depth
       |expect $expectation
       |multiclock on
       |timeout 600
       |
       |[engines]
       |smtbmc yices
       |
       |[script]
       |read_rtlil ${candidate.getFileName}
       |read_rtlil ${concrete.getFileName}
       |read_verilog -formal ${miter.getFileName}
       |hierarchy -check -top $top
       |prep -top $top
       |memory_map
       |setundef -undriven -anyseq
       |opt_clean
       |check -assert
       |
       |[files]
       |${candidate.toAbsolutePath}
       |${concrete.toAbsolutePath}
       |${miter.toAbsolutePath}
       |""".stripMargin
  }

  private def parameterizedTop(buffered: Boolean): String =
    if (buffered) "NativeParameterizedStreamFifoCCProofHarnessBuffered"
    else "NativeParameterizedStreamFifoCCProofHarnessDirect"

  private def concreteTopName(depth: Int, buffered: Boolean): String = {
    val suffix = if (buffered) "Buffered" else "Direct"
    s"NativeConcreteStreamFifoCCProofHarness${suffix}Depth$depth"
  }

  private def requireTool(directory: Path, command: Seq[String], name: String): Unit = {
    val result = runAllowFailure(directory, command)
    assert(result._1 == 0, s"$name is unavailable:\n${result._2}")
  }

  private def runChecked(directory: Path, command: Seq[String], label: String): String = {
    val result = runAllowFailure(directory, command)
    assert(result._1 == 0, s"$label failed (${result._1}):\n${result._2}")
    result._2
  }

  private def runAllowFailure(directory: Path, command: Seq[String]): (Int, String) = {
    val output = new StringBuilder
    val logger = ProcessLogger(
      line => output.append(line).append('\n'),
      line => output.append(line).append('\n')
    )
    val exit = Process(command, directory.toFile).!(logger)
    exit -> output.toString
  }

  private def withWorkspace(name: String)(body: Path => Unit): Unit = {
    sys.env.get(Workspace) match {
      case Some(root) =>
        val directory = Path.of(root).resolve(name)
        Files.createDirectories(directory)
        body(directory)
      case None =>
        val directory = Files.createTempDirectory(s"morphhdl-streamfifocc-$name-")
        try body(directory)
        finally deleteRecursively(directory)
    }
  }

  private def write(path: Path, value: String): Unit =
    Files.write(path, value.getBytes(StandardCharsets.UTF_8))

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def yosysPath(path: Path): String = path.toAbsolutePath.toString.replace("\\", "/")

  private def deleteRecursively(path: Path): Unit = {
    if (Files.exists(path)) {
      Files.walk(path).iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
    }
  }
}
