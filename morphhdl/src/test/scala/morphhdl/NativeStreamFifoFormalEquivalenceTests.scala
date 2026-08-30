package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import morphhdl.frontend.HdlInt

/** An ordinary SpinalHDL witness whose FIFO depth is a native Int literal.
  *
  * The fixed-width outer ABI intentionally matches
  * [[NativeParameterizedStreamFifoHarness]]. Keeping the complete hierarchy in
  * the proof makes parent/child parameter forwarding and derived port widths
  * part of the equivalence contract.
  */
final class NativeConcreteStreamFifoFormalHarness(depth: Int) extends Component {
  require(Vector(1, 3, 5, 8).contains(depth))
  setDefinitionName(s"NativeConcreteStreamFifoFormalHarnessDepth$depth")

  val io = new Bundle {
    val push = slave Stream (Bits(8 bits))
    val pop = master Stream (Bits(8 bits))
    val flush = in Bool ()
    val occupancy = out UInt (4 bits)
    val availability = out UInt (4 bits)
  }

  // This must resolve the real native-Int StreamFifo overload. Giving the
  // concrete child a disjoint definition name prevents a formal tool from
  // accidentally resolving both DUT legs to the parameterized child module.
  val fifo = spinal.lib.StreamFifo(
    HardType(Bits(8 bits)),
    depth
  )
  fifo.setDefinitionName(s"ConcreteStreamFifoDepth$depth")
  fifo.setName("fifo")
  fifo.io.push << io.push
  io.pop << fifo.io.pop
  fifo.io.flush := io.flush
  io.occupancy := fifo.io.occupancy.resized
  io.availability := fifo.io.availability.resized
}

class NativeStreamFifoFormalEquivalenceTests extends AnyFunSuite {
  private val Depths = Vector(1, 3, 5, 8)
  private val FormalGateEnvironment =
    "MORPHDL_RUN_STREAMFIFO_FORMAL_EQUIVALENCE"
  private val FormalWorkspaceEnvironment =
    "MORPHDL_STREAMFIFO_FORMAL_WORKSPACE"
  private val ParameterizedFile = "stream_fifo_parameterized_formal.v"

  private val ModuleDeclaration =
    """(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b""".r

  private final case class GeneratedDuts(
      parameterized: Path,
      concreteByDepth: Map[Int, Path]
  )

  private final case class PreparedDuts(
      candidate: Path,
      concrete: Path
  )

  test("formal witnesses are independent native-Int elaborations sharing one Morph definition") {
    withTemporaryDirectory { directory =>
      validateGeneratedDuts(generateDuts(directory))
    }
  }

  test("one Morph StreamFifo is formally equivalent to all concrete native-Int witnesses") {
    if (!sys.env.get(FormalGateEnvironment).contains("1")) {
      cancel(
        s"Set $FormalGateEnvironment=1 only in the pinned formal container"
      )
    }

    withFormalWorkspace { directory =>
      requireFormalTool(directory, Seq("yosys", "-V"), "Yosys")
      requireFormalTool(directory, Seq("sby", "-h"), "SymbiYosys")
      requireFormalTool(
        directory,
        Seq("yices-smt2", "--version"),
        "Yices SMT2"
      )
      requireFormalTool(
        directory,
        Seq("yosys", "-Q", "-p", "help abc"),
        "Yosys ABC integration"
      )

      val generated = generateDuts(directory)
      validateGeneratedDuts(generated)
      val preparedByDepth = Depths.map { depth =>
        depth -> prepareDuts(directory, generated, depth)
      }.toMap

      Depths.foreach { depth =>
        val miter = directory.resolve(s"stream_fifo_equivalence_depth_$depth.v")
        write(miter, equivalenceMiter(depth, mutateCandidateReady = false))
        val config = directory.resolve(s"stream_fifo_equivalence_depth_$depth.sby")
        write(
          config,
          positiveSby(
            preparedByDepth(depth),
            miter,
            miterModule(depth)
          )
        )
        runSby(
          directory,
          config,
          expectedStatus = "PASS",
          requireCounterexample = false
        )
      }

      val mutationDepth = 3
      val mutationMiter =
        directory.resolve("stream_fifo_equivalence_depth_3_mutation.v")
      write(
        mutationMiter,
        equivalenceMiter(mutationDepth, mutateCandidateReady = true)
      )
      val mutationConfig =
        directory.resolve("stream_fifo_equivalence_depth_3_mutation.sby")
      write(
        mutationConfig,
        mutationSby(
          preparedByDepth(mutationDepth),
          mutationMiter,
          miterModule(mutationDepth)
        )
      )
      runSby(
        directory,
        mutationConfig,
        expectedStatus = "FAIL",
        requireCounterexample = true
      )
    }
  }

  private def generateDuts(directory: Path): GeneratedDuts = {
    val parameterizedDirectory = directory.resolve("parameterized")
    Files.createDirectories(parameterizedDirectory)
    val parameterizedConfig = synchronousResetConfig(parameterizedDirectory)
    parameterizedConfig.netlistFileName = ParameterizedFile
    val depth = HdlInt.param(
      "DEPTH",
      default = BigInt(5),
      min = BigInt(1),
      max = BigInt(8)
    )
    MorphVerilog(parameterizedConfig) {
      new NativeParameterizedStreamFifoHarness(depth)
    }
    val parameterized = parameterizedDirectory.resolve(ParameterizedFile)

    val concreteByDepth = Depths.map { selectedDepth =>
      // A separate target directory and ordinary SpinalVerilog call is part of
      // the independence boundary; no candidate artifact is reused here.
      val concreteDirectory =
        directory.resolve(s"concrete-depth-$selectedDepth")
      Files.createDirectories(concreteDirectory)
      val concreteConfig = synchronousResetConfig(concreteDirectory)
      val file = s"stream_fifo_concrete_depth_$selectedDepth.v"
      concreteConfig.netlistFileName = file
      SpinalVerilog(concreteConfig) {
        new NativeConcreteStreamFifoFormalHarness(selectedDepth)
      }
      selectedDepth -> concreteDirectory.resolve(file)
    }.toMap

    GeneratedDuts(parameterized, concreteByDepth)
  }

  private def validateGeneratedDuts(generated: GeneratedDuts): Unit = {
    val parameterized = read(generated.parameterized)
    assert(parameterized.contains("parameter integer DEPTH = 5"))
    assert(parameterized.contains("module NativeParameterizedStreamFifoHarness #("))
    assert(parameterized.contains(".DEPTH(DEPTH)"))
    assert(
      """(?m)^\s*wire\s+\[2:0\]\s+logic_push_onRam_write_payload_address\s*;\s*$""".r
        .findFirstIn(parameterized)
        .nonEmpty,
      "The Morph FIFO write address did not retain the validated three-bit memory boundary"
    )
    assert(
      moduleNames(parameterized).toSet == Set(
        "NativeParameterizedStreamFifoHarness",
        "StreamFifo"
      )
    )

    val allConcreteModules = generated.concreteByDepth.toVector.flatMap { case (depth, path) =>
      val concrete = read(path)
      assert(
        !concrete.contains("parameter integer DEPTH"),
        s"Concrete DEPTH=$depth witness unexpectedly retained a DEPTH formal"
      )
      assert(!concrete.contains(".DEPTH("))
      val expected = Set(
        s"NativeConcreteStreamFifoFormalHarnessDepth$depth",
        s"ConcreteStreamFifoDepth$depth"
      )
      val actual = moduleNames(concrete).toSet
      assert(
        actual == expected,
        s"Concrete DEPTH=$depth module inventory was ${actual.toVector.sorted.mkString(", ")}"
      )
      actual
    }.toSet

    assert(
      allConcreteModules.intersect(moduleNames(parameterized).toSet).isEmpty,
      "Concrete and MorphHDL DUT legs share a module definition name"
    )
    assert(
      generated.concreteByDepth.values.map(path => read(path)).toSet.size ==
        Depths.size,
      "Concrete witnesses were not independently specialized by native Int depth"
    )
  }

  /** Elaborate and flatten the independently generated DUT legs in separate
    * Yosys processes. This makes their module identities disjoint before the
    * formal miter is loaded and prevents any preprocessing pass from
    * correlating their independent uninitialized memories or registers.
    */
  private def prepareDuts(
      directory: Path,
      generated: GeneratedDuts,
      depth: Int
  ): PreparedDuts = {
    val candidate = directory.resolve(s"morph_candidate_depth_$depth.il")
    val candidateScript =
      directory.resolve(s"prepare_morph_candidate_depth_$depth.ys")
    write(
      candidateScript,
      s"""read_verilog -defer ${yosysPath(generated.parameterized)}
         |chparam -set DEPTH $depth NativeParameterizedStreamFifoHarness
         |hierarchy -check -top NativeParameterizedStreamFifoHarness
         |flatten
         |proc
         |opt_clean
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top ${candidateFormalTop(depth)}
         |write_rtlil ${yosysPath(candidate)}
         |""".stripMargin
    )
    runYosys(directory, candidateScript, candidate)

    val concrete = directory.resolve(s"concrete_reference_depth_$depth.il")
    val concreteScript =
      directory.resolve(s"prepare_concrete_reference_depth_$depth.ys")
    write(
      concreteScript,
      s"""read_verilog -defer ${yosysPath(generated.concreteByDepth(depth))}
         |hierarchy -check -top NativeConcreteStreamFifoFormalHarnessDepth$depth
         |flatten
         |proc
         |opt_clean
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top ${concreteFormalTop(depth)}
         |write_rtlil ${yosysPath(concrete)}
         |""".stripMargin
    )
    runYosys(directory, concreteScript, concrete)

    PreparedDuts(candidate, concrete)
  }

  /** An assertion miter for the complete generated top-level harnesses.
    *
    * The initial transition forces the shared synchronous reset and performs
    * no comparison. Assertions start in the following state, after both DUTs
    * have consumed that reset. Reset is a common unconstrained input on all
    * later transitions.
    */
  private def equivalenceMiter(
      depth: Int,
      mutateCandidateReady: Boolean
  ): String = {
    val candidateReady =
      if (mutateCandidateReady) "(morph_push_ready_raw ^ 1'b1)"
      else "morph_push_ready_raw"

    s"""module ${miterModule(depth)} (
       |  input wire clk,
       |  input wire reset,
       |  input wire push_valid,
       |  input wire [7:0] push_payload,
       |  input wire pop_ready,
       |  input wire flush
       |);
       |  wire concrete_push_ready;
       |  wire concrete_pop_valid;
       |  wire [7:0] concrete_pop_payload;
       |  wire [3:0] concrete_occupancy;
       |  wire [3:0] concrete_availability;
       |  wire morph_push_ready_raw;
       |  wire morph_pop_valid;
       |  wire [7:0] morph_pop_payload;
       |  wire [3:0] morph_occupancy;
       |  wire [3:0] morph_availability;
       |  wire morph_push_ready_compared;
       |
       |  assign morph_push_ready_compared = $candidateReady;
       |
       |  ${concreteFormalTop(depth)} concrete_dut (
       |    .io_push_valid(push_valid),
       |    .io_push_ready(concrete_push_ready),
       |    .io_push_payload(push_payload),
       |    .io_pop_valid(concrete_pop_valid),
       |    .io_pop_ready(pop_ready),
       |    .io_pop_payload(concrete_pop_payload),
       |    .io_flush(flush),
       |    .io_occupancy(concrete_occupancy),
       |    .io_availability(concrete_availability),
       |    .clk(clk),
       |    .reset(reset)
       |  );
       |
       |  ${candidateFormalTop(depth)} morph_dut (
       |    .io_push_valid(push_valid),
       |    .io_push_ready(morph_push_ready_raw),
       |    .io_push_payload(push_payload),
       |    .io_pop_valid(morph_pop_valid),
       |    .io_pop_ready(pop_ready),
       |    .io_pop_payload(morph_pop_payload),
       |    .io_flush(flush),
       |    .io_occupancy(morph_occupancy),
       |    .io_availability(morph_availability),
       |    .clk(clk),
       |    .reset(reset)
       |  );
       |
       |  always @* begin
       |    if ($$initstate)
       |      assume(reset);
       |    if (!$$initstate) begin
       |      assert(concrete_push_ready == morph_push_ready_compared);
       |      assert(concrete_pop_valid == morph_pop_valid);
       |      assert(concrete_occupancy == morph_occupancy);
       |      assert(concrete_availability == morph_availability);
       |      if (concrete_pop_valid && morph_pop_valid)
       |        assert(concrete_pop_payload == morph_pop_payload);
       |    end
       |  end
       |endmodule
       |""".stripMargin
  }

  private def positiveSby(
      prepared: PreparedDuts,
      miter: Path,
      top: String
  ): String =
    s"""[options]
       |mode prove
       |expect pass
       |multiclock off
       |timeout 600
       |
       |[engines]
       |abc pdr
       |
       |[script]
       |read_rtlil ${prepared.candidate.getFileName}
       |read_rtlil ${prepared.concrete.getFileName}
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
       |${prepared.concrete.toAbsolutePath}
       |${miter.toAbsolutePath}
       |""".stripMargin

  private def mutationSby(
      prepared: PreparedDuts,
      miter: Path,
      top: String
  ): String =
    s"""[options]
       |mode bmc
       |depth 6
       |expect fail
       |multiclock off
       |timeout 120
       |
       |[engines]
       |smtbmc yices
       |
       |[script]
       |read_rtlil ${prepared.candidate.getFileName}
       |read_rtlil ${prepared.concrete.getFileName}
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
       |${prepared.concrete.toAbsolutePath}
       |${miter.toAbsolutePath}
       |""".stripMargin

  private def runSby(
      directory: Path,
      config: Path,
      expectedStatus: String,
      requireCounterexample: Boolean
  ): Unit = {
    val (exitCode, output) = run(
      directory,
      Seq("sby", "-f", config.getFileName.toString)
    )
    assert(
      exitCode == 0,
      s"SymbiYosys did not complete with expected status $expectedStatus for ${config.getFileName}:\n$output"
    )

    val stem = config.getFileName.toString.stripSuffix(".sby")
    val workDirectory = directory.resolve(stem)
    val statusFile = workDirectory.resolve("status")
    assert(
      Files.isRegularFile(statusFile),
      s"SymbiYosys published no status for ${config.getFileName}:\n$output"
    )
    val statusLines = read(statusFile)
      .split("\\r?\\n", -1)
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toVector
    assert(
      statusLines.size == 1,
      s"SymbiYosys published an ambiguous status file for ${config.getFileName}: ${statusLines.mkString(" | ")}\n$output"
    )
    val statusTokens = statusLines.head.split("\\s+").toVector
    assert(
      statusTokens.nonEmpty &&
        statusTokens.tail.forall(_.matches("[0-9]+")),
      s"SymbiYosys published a malformed status for ${config.getFileName}: ${statusLines.head}\n$output"
    )
    val actualStatus = statusTokens.head
    assert(
      actualStatus == expectedStatus,
      s"Expected formal $expectedStatus for ${config.getFileName}, received $actualStatus:\n$output"
    )

    if (requireCounterexample) {
      val files = regularFiles(workDirectory)
      val traces = files.filter(path => path.getFileName.toString.endsWith(".vcd"))
      assert(
        traces.exists(path => Files.size(path) > 0L),
        s"Expected formal FAIL had no non-empty counterexample trace:\n$output"
      )
      val engineLogs = files
        .filter { path =>
          val name = path.getFileName.toString
          name.endsWith(".txt") || name.endsWith(".log")
        }
        .map(read)
        .mkString("\n")
      assert(
        engineLogs.contains("Assert failed in"),
        s"Expected formal FAIL was not caused by an assertion counterexample:\n$output\n$engineLogs"
      )
    }
  }

  private def runYosys(
      directory: Path,
      script: Path,
      expectedOutput: Path
  ): Unit = {
    val (exitCode, output) = run(
      directory,
      Seq("yosys", "-q", "-s", script.getFileName.toString)
    )
    assert(
      exitCode == 0,
      s"Yosys preprocessing failed for ${script.getFileName}:\n$output"
    )
    assert(
      Files.isRegularFile(expectedOutput) && Files.size(expectedOutput) > 0L,
      s"Yosys preprocessing published no RTLIL for ${script.getFileName}:\n$output"
    )
  }

  private def requireFormalTool(
      directory: Path,
      command: Seq[String],
      label: String
  ): Unit = {
    val (exitCode, output) = run(directory, command)
    assert(
      exitCode == 0 && output.trim.nonEmpty,
      s"Required formal tool $label is unavailable or unhealthy (${command.mkString(" ")}):\n$output"
    )
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

  private def miterModule(depth: Int): String =
    s"NativeStreamFifoFormalMiterDepth$depth"

  private def candidateFormalTop(depth: Int): String =
    s"MorphStreamFifoFormalCandidateDepth$depth"

  private def concreteFormalTop(depth: Int): String =
    s"ConcreteStreamFifoFormalReferenceDepth$depth"

  private def yosysPath(path: Path): String = {
    val absolute = path.toAbsolutePath.normalize.toString
    require(
      !absolute.exists(character => character.isWhitespace || character == '"'),
      s"Formal workspace path is not safely representable in a Yosys script: $absolute"
    )
    absolute
  }

  private def moduleNames(verilog: String): Vector[String] =
    ModuleDeclaration.findAllMatchIn(verilog).map(_.group(1)).toVector

  private def run(directory: Path, command: Seq[String]): (Int, String) = {
    val log = new StringBuilder
    val exitCode = Process(command, directory.toFile).!(
      ProcessLogger(
        line => log.append(line).append('\n'),
        line => log.append(line).append('\n')
      )
    )
    exitCode -> log.toString
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def write(path: Path, content: String): Unit =
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))

  private def regularFiles(directory: Path): Vector[Path] = {
    val stream = Files.walk(directory)
    try stream.iterator().asScala.filter(Files.isRegularFile(_)).toVector
    finally stream.close()
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory =
      Files.createTempDirectory("morphhdl-streamfifo-formal-equivalence-")
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

  private def withFormalWorkspace(body: Path => Unit): Unit =
    sys.env.get(FormalWorkspaceEnvironment).filter(_.nonEmpty) match {
      case Some(configured) =>
        val directory = java.nio.file.Paths.get(configured).toAbsolutePath
        Files.createDirectories(directory)
        body(directory)
      case None =>
        withTemporaryDirectory(body)
    }
}
