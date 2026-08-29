package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import morphhdl.frontend.HdlInt

/** Fixed external ABI used on both sides of the proof. */
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
    val pushOccupancy = out UInt (6 bits)
    val popOccupancy = out UInt (6 bits)
  }

  private val pushClockDomain = ClockDomain(
    clock = io.pushClock,
    reset = io.pushReset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = HIGH
    )
  )
  private val popClockDomain = ClockDomain(
    clock = io.popClock,
    reset = io.popReset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = HIGH
    )
  )

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
  io.popPayload := fifo.io.pop.payload
  fifo.io.pop.ready := io.popReady
  io.pushOccupancy := fifo.io.pushOccupancy.resized
  io.popOccupancy := fifo.io.popOccupancy.resized
}

/** Independently elaborated ordinary-SpinalHDL witness. */
final class NativeConcreteStreamFifoCCProofHarness(
    depth: Int,
    withPopBufferedReset: Boolean
) extends Component {
  require(depth >= 2 && (depth & (depth - 1)) == 0)
  setDefinitionName(
    s"NativeConcreteStreamFifoCCProofHarnessDepth${depth}" +
      (if (withPopBufferedReset) "Buffered" else "Direct")
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
    val pushOccupancy = out UInt (6 bits)
    val popOccupancy = out UInt (6 bits)
  }

  private val pushClockDomain = ClockDomain(
    clock = io.pushClock,
    reset = io.pushReset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = HIGH
    )
  )
  private val popClockDomain = ClockDomain(
    clock = io.popClock,
    reset = io.popReset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = HIGH
    )
  )

  val fifo = spinal.lib.StreamFifoCC(
    HardType(Bits(8 bits)),
    depth,
    pushClockDomain,
    popClockDomain,
    withPopBufferedReset
  )
  fifo.setDefinitionName(
    s"ConcreteStreamFifoCCDepth${depth}" +
      (if (withPopBufferedReset) "Buffered" else "Direct")
  )
  fifo.setName("fifo")

  fifo.io.push.valid := io.pushValid
  fifo.io.push.payload := io.pushPayload
  io.pushReady := fifo.io.push.ready
  io.popValid := fifo.io.pop.valid
  io.popPayload := fifo.io.pop.payload
  fifo.io.pop.ready := io.popReady
  io.pushOccupancy := fifo.io.pushOccupancy.resized
  io.popOccupancy := fifo.io.popOccupancy.resized
}

class NativeStreamFifoCCProofTests extends AnyFunSuite {
  private val Depths = Vector(4, 8, 16)
  private val ResetModes = Vector(false, true)
  private val GateEnvironment = "MORPHDL_RUN_STREAMFIFOCC_PROOF"
  private val WorkspaceEnvironment = "MORPHDL_STREAMFIFOCC_PROOF_WORKSPACE"

  private final case class Generated(
      candidate: Path,
      candidateTop: String,
      concrete: Path,
      concreteTop: String,
      depth: Int,
      buffered: Boolean
  )

  test("native StreamFifoCC passes independent-clock simulation and synthesis") {
    requireProofGate()
    withWorkspace { directory =>
      requireTool(directory, Seq("iverilog", "-V"), "Icarus Verilog")
      requireTool(directory, Seq("vvp", "-V"), "Icarus vvp")
      requireTool(directory, Seq("yosys", "-V"), "Yosys")
      ResetModes.foreach { buffered =>
        val candidate = generateCandidate(directory, buffered)
        Depths.foreach { depth =>
          runAsynchronousSimulation(directory, candidate, depth, buffered)
          runSynthesis(directory, candidate, depth, buffered)
        }
      }
    }
  }

  test("specialized MorphVerilog is sequentially equivalent to independent native witnesses") {
    requireProofGate()
    withWorkspace { directory =>
      requireTool(directory, Seq("yosys", "-V"), "Yosys")
      val generated = for {
        buffered <- ResetModes
        candidate = generateCandidate(directory, buffered)
        depth <- Depths
      } yield generateConcrete(directory, candidate, depth, buffered)

      generated.foreach { value =>
        val prepared = preparePair(directory, value, mutateReady = false)
        runEquivalence(directory, prepared._1, prepared._2, value, expectPass = true)
      }

      val mutation = generated.find(value => value.depth == 8 && !value.buffered).get
      val preparedMutation = preparePair(directory, mutation, mutateReady = true)
      runEquivalence(
        directory,
        preparedMutation._1,
        preparedMutation._2,
        mutation,
        expectPass = false
      )
    }
  }

  private def requireProofGate(): Unit = {
    if (!sys.env.get(GateEnvironment).contains("1")) {
      cancel(s"Set $GateEnvironment=1 only in the pinned proof environment")
    }
  }

  private def generateCandidate(directory: Path, buffered: Boolean): Path = {
    val mode = if (buffered) "buffered" else "direct"
    val target = directory.resolve(s"candidate-$mode")
    Files.createDirectories(target)
    val fileName = s"stream_fifocc_candidate_$mode.v"
    val config = SpinalConfig(targetDirectory = target.toString)
    config.netlistFileName = fileName
    val depth = HdlInt.param(
      "DEPTH",
      default = BigInt(8),
      min = BigInt(4),
      max = BigInt(16)
    )
    MorphVerilog(config) {
      new NativeParameterizedStreamFifoCCProofHarness(depth, buffered)
    }
    val file = target.resolve(fileName)
    assert(Files.isRegularFile(file), s"candidate output missing: $file")
    val text = read(file)
    assert(text.contains("parameter integer DEPTH = 8"))
    assert(text.contains("spinal.lib.StreamFifoCC") || text.contains("module StreamFifoCC"))
    file
  }

  private def generateConcrete(
      directory: Path,
      candidate: Path,
      depth: Int,
      buffered: Boolean
  ): Generated = {
    val mode = if (buffered) "buffered" else "direct"
    val target = directory.resolve(s"concrete-$mode-$depth")
    Files.createDirectories(target)
    val fileName = s"stream_fifocc_concrete_${mode}_$depth.v"
    val config = SpinalConfig(targetDirectory = target.toString)
    config.netlistFileName = fileName
    SpinalVerilog(config) {
      new NativeConcreteStreamFifoCCProofHarness(depth, buffered)
    }
    val file = target.resolve(fileName)
    assert(Files.isRegularFile(file), s"concrete output missing: $file")
    assert(!read(file).contains("parameter integer DEPTH"))
    Generated(
      candidate = candidate,
      candidateTop =
        if (buffered) "NativeParameterizedStreamFifoCCProofHarnessBuffered"
        else "NativeParameterizedStreamFifoCCProofHarnessDirect",
      concrete = file,
      concreteTop =
        s"NativeConcreteStreamFifoCCProofHarnessDepth${depth}" +
          (if (buffered) "Buffered" else "Direct"),
      depth = depth,
      buffered = buffered
    )
  }

  private def runAsynchronousSimulation(
      directory: Path,
      candidate: Path,
      depth: Int,
      buffered: Boolean
  ): Unit = {
    val mode = if (buffered) "buffered" else "direct"
    val top =
      if (buffered) "NativeParameterizedStreamFifoCCProofHarnessBuffered"
      else "NativeParameterizedStreamFifoCCProofHarnessDirect"
    val testbench = directory.resolve(s"tb_${mode}_$depth.v")
    write(testbench, simulationTestbench(top, depth))
    val executable = directory.resolve(s"sim_${mode}_$depth.out")
    runChecked(
      directory,
      Seq(
        "iverilog",
        "-g2001",
        "-s",
        "tb",
        "-o",
        executable.toString,
        candidate.toString,
        testbench.toString
      ),
      s"Icarus compile failed for depth=$depth buffered=$buffered"
    )
    val output = runChecked(
      directory,
      Seq("vvp", executable.toString),
      s"asynchronous simulation failed for depth=$depth buffered=$buffered"
    )
    assert(
      output.contains("STREAMFIFOCC_ASYNC_PASS"),
      s"simulation did not report completion for depth=$depth buffered=$buffered\n$output"
    )
  }

  private def runSynthesis(
      directory: Path,
      candidate: Path,
      depth: Int,
      buffered: Boolean
  ): Unit = {
    val mode = if (buffered) "buffered" else "direct"
    val top =
      if (buffered) "NativeParameterizedStreamFifoCCProofHarnessBuffered"
      else "NativeParameterizedStreamFifoCCProofHarnessDirect"
    val script = directory.resolve(s"synth_${mode}_$depth.ys")
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
    runChecked(
      directory,
      Seq("yosys", "-Q", "-s", script.toString),
      s"Yosys synthesis failed for depth=$depth buffered=$buffered"
    )
  }

  private def preparePair(
      directory: Path,
      generated: Generated,
      mutateReady: Boolean
  ): (Path, Path) = {
    val mode = if (generated.buffered) "buffered" else "direct"
    val suffix = if (mutateReady) "mutation" else "positive"
    val candidateIl =
      directory.resolve(s"candidate_${mode}_${generated.depth}_$suffix.il")
    val concreteIl =
      directory.resolve(s"concrete_${mode}_${generated.depth}_$suffix.il")

    val candidateInput =
      if (!mutateReady) generated.candidate
      else {
        val wrapper = directory.resolve(s"mutated_wrapper_${mode}_${generated.depth}.v")
        write(
          wrapper,
          mutationWrapper(generated.candidateTop, generated.depth)
        )
        wrapper
      }
    val candidateTop =
      if (mutateReady) s"MutatedCandidate${generated.depth}${if (generated.buffered) "Buffered" else "Direct"}"
      else generated.candidateTop
    val candidateScript = directory.resolve(s"prepare_candidate_${mode}_${generated.depth}_$suffix.ys")
    val readCandidate =
      if (mutateReady)
        s"read_verilog -defer ${yosysPath(generated.candidate)} ${yosysPath(candidateInput)}"
      else s"read_verilog -defer ${yosysPath(candidateInput)}"
    val parameterize =
      if (mutateReady) ""
      else s"chparam -set DEPTH ${generated.depth} $candidateTop\n"
    write(
      candidateScript,
      s"""$readCandidate
         |$parameterize|hierarchy -check -top $candidateTop
         |flatten
         |proc
         |opt_clean
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top morph_candidate
         |write_rtlil ${yosysPath(candidateIl)}
         |""".stripMargin
    )
    runChecked(directory, Seq("yosys", "-Q", "-s", candidateScript.toString), "candidate preparation failed")

    val concreteScript = directory.resolve(s"prepare_concrete_${mode}_${generated.depth}_$suffix.ys")
    write(
      concreteScript,
      s"""read_verilog -defer ${yosysPath(generated.concrete)}
         |hierarchy -check -top ${generated.concreteTop}
         |flatten
         |proc
         |opt_clean
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top native_reference
         |write_rtlil ${yosysPath(concreteIl)}
         |""".stripMargin
    )
    runChecked(directory, Seq("yosys", "-Q", "-s", concreteScript.toString), "concrete preparation failed")
    candidateIl -> concreteIl
  }

  private def runEquivalence(
      directory: Path,
      candidate: Path,
      concrete: Path,
      generated: Generated,
      expectPass: Boolean
  ): Unit = {
    val mode = if (generated.buffered) "buffered" else "direct"
    val suffix = if (expectPass) "positive" else "mutation"
    val script = directory.resolve(s"equiv_${mode}_${generated.depth}_$suffix.ys")
    write(
      script,
      s"""read_rtlil ${yosysPath(concrete)}
         |read_rtlil ${yosysPath(candidate)}
         |equiv_make native_reference morph_candidate equiv
         |hierarchy -check -top equiv
         |prep -top equiv
         |memory_map
         |opt_clean
         |equiv_simple -undef
         |equiv_induct -undef -seq 20
         |equiv_status -assert
         |""".stripMargin
    )
    val (code, output) = run(directory, Seq("yosys", "-Q", "-s", script.toString))
    if (expectPass) {
      assert(
        code == 0 && output.contains("Equivalence successfully proven"),
        s"formal equivalence failed for depth=${generated.depth} buffered=${generated.buffered}\n$output"
      )
    } else {
      assert(
        code != 0 || output.contains("failed") || output.contains("unproven"),
        s"mutation was not detected for depth=${generated.depth}\n$output"
      )
    }
  }

  private def simulationTestbench(top: String, depth: Int): String = {
    val count = depth * 2
    s"""`timescale 1ns/1ps
       |module tb;
       |  reg io_pushClock;
       |  reg io_pushReset;
       |  reg io_popClock;
       |  reg io_popReset;
       |  reg io_pushValid;
       |  wire io_pushReady;
       |  reg [7:0] io_pushPayload;
       |  wire io_popValid;
       |  reg io_popReady;
       |  wire [7:0] io_popPayload;
       |  wire [5:0] io_pushOccupancy;
       |  wire [5:0] io_popOccupancy;
       |  integer sent;
       |  integer received;
       |  integer popCycles;
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
       |  always #5 io_pushClock = ~io_pushClock;
       |  always #7 io_popClock = ~io_popClock;
       |
       |  initial begin
       |    io_pushClock = 1'b0;
       |    io_popClock = 1'b0;
       |    io_pushReset = 1'b1;
       |    io_popReset = 1'b1;
       |    io_pushValid = 1'b0;
       |    io_pushPayload = 8'h00;
       |    io_popReady = 1'b0;
       |    sent = 0;
       |    received = 0;
       |    popCycles = 0;
       |    #23 io_pushReset = 1'b0;
       |    #14 io_popReset = 1'b0;
       |  end
       |
       |  always @(negedge io_pushClock) begin
       |    if (!io_pushReset) begin
       |      if (sent < $count && (!io_pushValid || io_pushReady)) begin
       |        io_pushValid <= 1'b1;
       |        io_pushPayload <= sent[7:0];
       |      end else if (io_pushValid && io_pushReady) begin
       |        io_pushValid <= 1'b0;
       |      end
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
       |      io_popReady <= ((popCycles % 5) != 2);
       |    end
       |  end
       |
       |  always @(posedge io_popClock) begin
       |    if (!io_popReset && io_popValid && io_popReady) begin
       |      if (io_popPayload !== received[7:0]) begin
       |        $$display("STREAMFIFOCC_DATA_MISMATCH expected=%0d actual=%0d", received, io_popPayload);
       |        $$fatal(1);
       |      end
       |      received <= received + 1;
       |      if (received + 1 == $count) begin
       |        $$display("STREAMFIFOCC_ASYNC_PASS depth=$depth");
       |        #20 $$finish;
       |      end
       |    end
       |  end
       |
       |  initial begin
       |    #20000;
       |    $$display("STREAMFIFOCC_TIMEOUT sent=%0d received=%0d", sent, received);
       |    $$fatal(1);
       |  end
       |endmodule
       |""".stripMargin
  }

  private def mutationWrapper(candidateTop: String, depth: Int): String = {
    val mode = if (candidateTop.endsWith("Buffered")) "Buffered" else "Direct"
    s"""module MutatedCandidate${depth}$mode (
       |  input wire io_pushClock,
       |  input wire io_pushReset,
       |  input wire io_popClock,
       |  input wire io_popReset,
       |  input wire io_pushValid,
       |  output wire io_pushReady,
       |  input wire [7:0] io_pushPayload,
       |  output wire io_popValid,
       |  input wire io_popReady,
       |  output wire [7:0] io_popPayload,
       |  output wire [5:0] io_pushOccupancy,
       |  output wire [5:0] io_popOccupancy
       |);
       |  wire rawPushReady;
       |  assign io_pushReady = ~rawPushReady;
       |  $candidateTop #(.DEPTH($depth)) dut (
       |    .io_pushClock(io_pushClock), .io_pushReset(io_pushReset),
       |    .io_popClock(io_popClock), .io_popReset(io_popReset),
       |    .io_pushValid(io_pushValid), .io_pushReady(rawPushReady),
       |    .io_pushPayload(io_pushPayload), .io_popValid(io_popValid),
       |    .io_popReady(io_popReady), .io_popPayload(io_popPayload),
       |    .io_pushOccupancy(io_pushOccupancy), .io_popOccupancy(io_popOccupancy)
       |  );
       |endmodule
       |""".stripMargin
  }

  private def requireTool(directory: Path, command: Seq[String], name: String): Unit = {
    val (code, output) = run(directory, command)
    assert(code == 0, s"$name is unavailable\n$output")
  }

  private def runChecked(directory: Path, command: Seq[String], context: String): String = {
    val (code, output) = run(directory, command)
    assert(code == 0, s"$context\n$output")
    output
  }

  private def run(directory: Path, command: Seq[String]): (Int, String) = {
    val output = new StringBuilder
    val logger = ProcessLogger(
      line => output.append(line).append('\n'),
      line => output.append(line).append('\n')
    )
    val code = Process(command, directory.toFile).!(logger)
    code -> output.toString
  }

  private def withWorkspace(body: Path => Unit): Unit = {
    sys.env.get(WorkspaceEnvironment) match {
      case Some(value) =>
        val path = java.nio.file.Paths.get(value).toAbsolutePath
        Files.createDirectories(path)
        body(path)
      case None => withTemporaryDirectory(body)
    }
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-streamfifocc-proof-")
    try body(directory)
    finally {
      Files.walk(directory).iterator().asScala.toVector
        .sortBy(_.getNameCount)
        .reverse
        .foreach(Files.deleteIfExists)
    }
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def write(path: Path, value: String): Unit =
    Files.write(path, value.getBytes(StandardCharsets.UTF_8))

  private def yosysPath(path: Path): String =
    path.toAbsolutePath.toString.replace("\\", "/")
}
