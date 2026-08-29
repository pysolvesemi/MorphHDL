package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.HdlInt
import morphhdl.frontend.HdlInt.hdlIntToParameterizedMemoryDepth

/** Fixed public verification ABI around the exact native StreamFifoCC. */
final class MorphStreamFifoCCFormalHarness(
    depth: HdlInt,
    bufferedPopReset: Boolean
) extends Component {
  val io = new Bundle {
    val pushValid = in Bool ()
    val pushReady = out Bool ()
    val pushPayload = in Bits (8 bits)
    val popValid = out Bool ()
    val popReady = in Bool ()
    val popPayload = out Bits (8 bits)
    val pushOccupancy = out UInt (5 bits)
    val popOccupancy = out UInt (5 bits)
  }

  private val clockConfig = ClockDomainConfig(
    resetKind = ASYNC,
    resetActiveLevel = HIGH
  )
  private val pushClock = ClockDomain.external("push", config = clockConfig)
  private val popClock = ClockDomain.external("pop", config = clockConfig)

  // The frontend boundary must return this exact native class. It contributes
  // only parameter provenance; all FIFO and CDC behavior remains SpinalHDL's.
  val fifo = morphhdl.frontend.StreamFifoCC(
    HardType(Bits(8 bits)),
    depth,
    pushClock,
    popClock,
    bufferedPopReset
  )
  require(fifo.getClass == classOf[spinal.lib.StreamFifoCC[_]])

  fifo.io.push.valid := io.pushValid
  fifo.io.push.payload := io.pushPayload
  io.pushReady := fifo.io.push.ready
  io.popValid := fifo.io.pop.valid
  fifo.io.pop.ready := io.popReady
  io.popPayload := Mux(fifo.io.pop.valid, fifo.io.pop.payload, B(0, 8 bits))
  io.pushOccupancy := fifo.io.pushOccupancy.resized
  io.popOccupancy := fifo.io.popOccupancy.resized
}

/** Independently elaborated ordinary SpinalHDL reference. */
final class ConcreteStreamFifoCCFormalHarness(
    depth: Int,
    bufferedPopReset: Boolean
) extends Component {
  require(depth >= 2 && (depth & (depth - 1)) == 0)

  val io = new Bundle {
    val pushValid = in Bool ()
    val pushReady = out Bool ()
    val pushPayload = in Bits (8 bits)
    val popValid = out Bool ()
    val popReady = in Bool ()
    val popPayload = out Bits (8 bits)
    val pushOccupancy = out UInt (5 bits)
    val popOccupancy = out UInt (5 bits)
  }

  private val clockConfig = ClockDomainConfig(
    resetKind = ASYNC,
    resetActiveLevel = HIGH
  )
  private val pushClock = ClockDomain.external("push", config = clockConfig)
  private val popClock = ClockDomain.external("pop", config = clockConfig)

  val fifo = new spinal.lib.StreamFifoCC(
    HardType(Bits(8 bits)),
    depth,
    pushClock,
    popClock,
    bufferedPopReset
  )

  fifo.io.push.valid := io.pushValid
  fifo.io.push.payload := io.pushPayload
  io.pushReady := fifo.io.push.ready
  io.popValid := fifo.io.pop.valid
  fifo.io.pop.ready := io.popReady
  io.popPayload := Mux(fifo.io.pop.valid, fifo.io.pop.payload, B(0, 8 bits))
  io.pushOccupancy := fifo.io.pushOccupancy.resized
  io.popOccupancy := fifo.io.popOccupancy.resized
}

class GenericNativeStreamFifoCCFormalEquivalenceTests extends AnyFunSuite {
  private val Depths = Vector(4, 8, 16)
  private val ResetModes = Vector(false, true)
  private val FormalGate = "MORPHDL_RUN_STREAMFIFOCC_FORMAL_EQUIVALENCE"
  private val Workspace = "MORPHDL_STREAMFIFOCC_FORMAL_WORKSPACE"

  private final case class Generated(
      candidateByResetMode: Map[Boolean, Path],
      concreteByConfiguration: Map[(Int, Boolean), Path]
  )

  private final case class Prepared(candidate: Path, reference: Path)

  test("candidate and references are independent native elaborations") {
    withTemporaryDirectory { directory =>
      validateGenerated(generate(directory))
    }
  }

  test("all public outputs are solver-equivalent under independent clocks") {
    if (!sys.env.get(FormalGate).contains("1")) {
      cancel(s"Set $FormalGate=1 only in the pinned formal workflow")
    }

    withFormalWorkspace { directory =>
      requireTool(directory, Seq("yosys", "-V"), "Yosys")
      requireTool(directory, Seq("sby", "-h"), "SymbiYosys")
      requireTool(directory, Seq("boolector", "--version"), "Boolector")

      val generated = generate(directory)
      validateGenerated(generated)

      val prepared = (for {
        depth <- Depths
        buffered <- ResetModes
      } yield {
        val key = depth -> buffered
        key -> prepare(directory, generated, depth, buffered)
      }).toMap

      for {
        depth <- Depths
        buffered <- ResetModes
      } {
        val key = depth -> buffered
        val miter = directory.resolve(s"miter_${suffix(depth, buffered)}.v")
        write(miter, miterText(depth, buffered, mutateReady = false))
        val config = directory.resolve(s"prove_${suffix(depth, buffered)}.sby")
        write(config, proofConfig(prepared(key), miter, miterTop(depth, buffered)))
        runSby(directory, config, expectedStatus = "PASS", requireTrace = false)
      }

      val mutationKey = 4 -> false
      val mutation = directory.resolve("miter_ready_mutation.v")
      write(mutation, miterText(4, buffered = false, mutateReady = true))
      val mutationConfig = directory.resolve("prove_ready_mutation.sby")
      write(
        mutationConfig,
        mutationProofConfig(prepared(mutationKey), mutation, miterTop(4, false))
      )
      runSby(
        directory,
        mutationConfig,
        expectedStatus = "FAIL",
        requireTrace = true
      )
    }
  }

  private def generate(directory: Path): Generated = {
    val candidates = ResetModes.map { buffered =>
      val target = directory.resolve(s"candidate-${resetMode(buffered)}")
      Files.createDirectories(target)
      val config = SpinalConfig(targetDirectory = target.toString)
      val file = s"candidate_${resetMode(buffered)}.v"
      config.netlistFileName = file
      val depth = HdlInt.param(
        "DEPTH",
        default = BigInt(8),
        min = BigInt(4),
        max = BigInt(16)
      )
      MorphVerilog(config) {
        val top = new MorphStreamFifoCCFormalHarness(depth, buffered)
        top.setDefinitionName(candidateSourceTop(buffered))
        top
      }
      buffered -> target.resolve(file)
    }.toMap

    val references = (for {
      depth <- Depths
      buffered <- ResetModes
    } yield {
      val target = directory.resolve(s"reference-${suffix(depth, buffered)}")
      Files.createDirectories(target)
      val config = SpinalConfig(targetDirectory = target.toString)
      val file = s"reference_${suffix(depth, buffered)}.v"
      config.netlistFileName = file
      SpinalVerilog(config) {
        val top = new ConcreteStreamFifoCCFormalHarness(depth, buffered)
        top.setDefinitionName(referenceSourceTop(depth, buffered))
        top
      }
      (depth -> buffered) -> target.resolve(file)
    }).toMap

    Generated(candidates, references)
  }

  private def validateGenerated(generated: Generated): Unit = {
    generated.candidateByResetMode.foreach { case (buffered, path) =>
      assert(Files.isRegularFile(path))
      val text = read(path)
      assert(text.contains(s"module ${candidateSourceTop(buffered)} #("))
      assert(text.contains("parameter integer DEPTH = 8"))
      assert(text.contains(".DEPTH(DEPTH)"))
      PublicPorts.foreach(port => assert(text.contains(port), s"missing $port"))
    }

    generated.concreteByConfiguration.foreach {
      case ((depth, buffered), path) =>
        assert(Files.isRegularFile(path))
        val text = read(path)
        assert(text.contains(s"module ${referenceSourceTop(depth, buffered)}"))
        assert(!text.contains("parameter integer DEPTH"))
        assert(!text.contains(".DEPTH("))
        PublicPorts.foreach(port => assert(text.contains(port), s"missing $port"))
    }

    assert(generated.candidateByResetMode.values.map(read).toSet.size == 2)
    assert(generated.concreteByConfiguration.values.map(read).toSet.size == 6)
  }

  private def prepare(
      directory: Path,
      generated: Generated,
      depth: Int,
      buffered: Boolean
  ): Prepared = {
    val candidate = directory.resolve(s"candidate_${suffix(depth, buffered)}.il")
    val candidateScript = directory.resolve(s"prepare_candidate_${suffix(depth, buffered)}.ys")
    write(
      candidateScript,
      s"""read_verilog -defer ${yosysPath(generated.candidateByResetMode(buffered))}
         |chparam -set DEPTH $depth ${candidateSourceTop(buffered)}
         |hierarchy -check -top ${candidateSourceTop(buffered)}
         |flatten
         |proc
         |opt
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top ${candidatePreparedTop(depth, buffered)}
         |write_rtlil ${yosysPath(candidate)}
         |""".stripMargin
    )
    runYosys(directory, candidateScript)

    val reference = directory.resolve(s"reference_${suffix(depth, buffered)}.il")
    val referenceScript = directory.resolve(s"prepare_reference_${suffix(depth, buffered)}.ys")
    write(
      referenceScript,
      s"""read_verilog -defer ${yosysPath(generated.concreteByConfiguration(depth -> buffered))}
         |hierarchy -check -top ${referenceSourceTop(depth, buffered)}
         |flatten
         |proc
         |opt
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top ${referencePreparedTop(depth, buffered)}
         |write_rtlil ${yosysPath(reference)}
         |""".stripMargin
    )
    runYosys(directory, referenceScript)

    Prepared(candidate, reference)
  }

  private def miterText(
      depth: Int,
      buffered: Boolean,
      mutateReady: Boolean
  ): String = {
    val comparedReady =
      if (mutateReady) "(candidate_pushReady ^ 1'b1)"
      else "candidate_pushReady"

    s"""module ${miterTop(depth, buffered)} (
       |  input wire push_clk,
       |  input wire push_reset,
       |  input wire pop_clk,
       |  input wire pop_reset,
       |  input wire io_pushValid,
       |  input wire [7:0] io_pushPayload,
       |  input wire io_popReady
       |);
       |  wire reference_pushReady;
       |  wire reference_popValid;
       |  wire [7:0] reference_popPayload;
       |  wire [4:0] reference_pushOccupancy;
       |  wire [4:0] reference_popOccupancy;
       |  wire candidate_pushReady;
       |  wire candidate_popValid;
       |  wire [7:0] candidate_popPayload;
       |  wire [4:0] candidate_pushOccupancy;
       |  wire [4:0] candidate_popOccupancy;
       |
       |  ${referencePreparedTop(depth, buffered)} reference (
       |    .io_pushValid(io_pushValid),
       |    .io_pushReady(reference_pushReady),
       |    .io_pushPayload(io_pushPayload),
       |    .io_popValid(reference_popValid),
       |    .io_popReady(io_popReady),
       |    .io_popPayload(reference_popPayload),
       |    .io_pushOccupancy(reference_pushOccupancy),
       |    .io_popOccupancy(reference_popOccupancy),
       |    .push_clk(push_clk),
       |    .push_reset(push_reset),
       |    .pop_clk(pop_clk),
       |    .pop_reset(pop_reset)
       |  );
       |
       |  ${candidatePreparedTop(depth, buffered)} candidate (
       |    .io_pushValid(io_pushValid),
       |    .io_pushReady(candidate_pushReady),
       |    .io_pushPayload(io_pushPayload),
       |    .io_popValid(candidate_popValid),
       |    .io_popReady(io_popReady),
       |    .io_popPayload(candidate_popPayload),
       |    .io_pushOccupancy(candidate_pushOccupancy),
       |    .io_popOccupancy(candidate_popOccupancy),
       |    .push_clk(push_clk),
       |    .push_reset(push_reset),
       |    .pop_clk(pop_clk),
       |    .pop_reset(pop_reset)
       |  );
       |
       |  always @($$global_clock) begin
       |    if ($$initstate) begin
       |      assume(push_reset);
       |      assume(pop_reset);
       |    end
       |    if (!$$initstate) begin
       |      assert(reference_pushReady == $comparedReady);
       |      assert(reference_popValid == candidate_popValid);
       |      assert(reference_popPayload == candidate_popPayload);
       |      assert(reference_pushOccupancy == candidate_pushOccupancy);
       |      assert(reference_popOccupancy == candidate_popOccupancy);
       |    end
       |  end
       |endmodule
       |""".stripMargin
  }

  private def proofConfig(
      prepared: Prepared,
      miter: Path,
      top: String
  ): String =
    s"""[options]
       |mode prove
       |expect pass
       |multiclock on
       |depth 24
       |timeout 600
       |
       |[engines]
       |smtbmc boolector
       |
       |[script]
       |read_rtlil ${prepared.candidate.getFileName}
       |read_rtlil ${prepared.reference.getFileName}
       |read_verilog -formal ${miter.getFileName}
       |hierarchy -check -top $top
       |prep -top $top
       |memory_map
       |setundef -undriven -anyseq
       |opt_clean
       |check -assert
       |
       |[files]
       |${prepared.candidate.toAbsolutePath}
       |${prepared.reference.toAbsolutePath}
       |${miter.toAbsolutePath}
       |""".stripMargin

  private def mutationProofConfig(
      prepared: Prepared,
      miter: Path,
      top: String
  ): String =
    s"""[options]
       |mode bmc
       |expect fail
       |multiclock on
       |depth 8
       |timeout 120
       |
       |[engines]
       |smtbmc boolector
       |
       |[script]
       |read_rtlil ${prepared.candidate.getFileName}
       |read_rtlil ${prepared.reference.getFileName}
       |read_verilog -formal ${miter.getFileName}
       |hierarchy -check -top $top
       |prep -top $top
       |memory_map
       |setundef -undriven -anyseq
       |opt_clean
       |check -assert
       |
       |[files]
       |${prepared.candidate.toAbsolutePath}
       |${prepared.reference.toAbsolutePath}
       |${miter.toAbsolutePath}
       |""".stripMargin

  private def runSby(
      directory: Path,
      config: Path,
      expectedStatus: String,
      requireTrace: Boolean
  ): Unit = {
    val (code, output) = run(
      directory,
      Seq("sby", "-f", config.getFileName.toString)
    )
    val runDirectory = directory.resolve(
      config.getFileName.toString.stripSuffix(".sby")
    )
    val status = runDirectory.resolve("status")
    val actual = if (Files.isRegularFile(status)) read(status).trim else "MISSING"
    assert(actual == expectedStatus, s"expected $expectedStatus, got $actual\n$output")
    if (requireTrace) {
      val paths = Files.walk(runDirectory)
      try {
        assert(
          paths.iterator().asScala.exists(path => path.toString.endsWith(".vcd")),
          s"negative control had no counterexample trace\n$output"
        )
      } finally paths.close()
    } else {
      assert(code == 0, output)
    }
  }

  private def runYosys(directory: Path, script: Path): Unit = {
    val (code, output) = run(
      directory,
      Seq("yosys", "-Q", "-s", script.getFileName.toString)
    )
    assert(code == 0, output)
  }

  private def requireTool(
      directory: Path,
      command: Seq[String],
      name: String
  ): Unit = {
    val (code, output) = run(directory, command)
    assert(code == 0, s"$name is unavailable\n$output")
  }

  private def run(
      directory: Path,
      command: Seq[String]
  ): (Int, String) = {
    val output = new StringBuilder
    val logger = ProcessLogger(
      line => output.append(line).append('\n'),
      line => output.append(line).append('\n')
    )
    Process(command, directory.toFile).!(logger) -> output.toString
  }

  private val PublicPorts = Vector(
    "io_pushValid",
    "io_pushReady",
    "io_pushPayload",
    "io_popValid",
    "io_popReady",
    "io_popPayload",
    "io_pushOccupancy",
    "io_popOccupancy",
    "push_clk",
    "push_reset",
    "pop_clk",
    "pop_reset"
  )

  private def resetMode(buffered: Boolean): String =
    if (buffered) "buffered" else "direct"

  private def suffix(depth: Int, buffered: Boolean): String =
    s"d${depth}_${resetMode(buffered)}"

  private def candidateSourceTop(buffered: Boolean): String =
    s"MorphStreamFifoCC_${resetMode(buffered)}"

  private def referenceSourceTop(depth: Int, buffered: Boolean): String =
    s"ConcreteStreamFifoCC_${suffix(depth, buffered)}"

  private def candidatePreparedTop(depth: Int, buffered: Boolean): String =
    s"candidate_${suffix(depth, buffered)}"

  private def referencePreparedTop(depth: Int, buffered: Boolean): String =
    s"reference_${suffix(depth, buffered)}"

  private def miterTop(depth: Int, buffered: Boolean): String =
    s"miter_${suffix(depth, buffered)}"

  private def yosysPath(path: Path): String =
    path.toAbsolutePath.toString.replace("\\", "/")

  private def write(path: Path, value: String): Unit =
    Files.write(path, value.getBytes(StandardCharsets.UTF_8))

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-streamfifocc-formal-")
    try body(directory)
    finally deleteRecursively(directory)
  }

  private def withFormalWorkspace(body: Path => Unit): Unit =
    sys.env.get(Workspace) match {
      case Some(value) =>
        val directory = Paths.get(value).toAbsolutePath
        Files.createDirectories(directory)
        body(directory)
      case None => withTemporaryDirectory(body)
    }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.exists(path)) {
      val paths = Files.walk(path)
      try {
        paths.iterator().asScala.toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(entry => Files.deleteIfExists(entry))
      } finally paths.close()
    }
  }
}
