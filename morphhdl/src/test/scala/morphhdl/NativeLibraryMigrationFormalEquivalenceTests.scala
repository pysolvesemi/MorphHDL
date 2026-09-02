package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.lib._

/** A direct typed Stream/Flow boundary for the Increment 57 pipeline proof.
  * Keeping the native interfaces as top-level ports prevents unrelated scalar
  * adapters from becoming part of the structural-selection proof.
  */
final class NativeLibraryMigrationTypedPipelineFormalTop(pipeMode: HdlInt)
    extends Component {
  setDefinitionName("NativeLibraryMigrationTypedPipelineFormalTop")

  private val mode = pipeMode.asElabInt
  private val useM2s = mode.elabEq(0)
  private val useS2m = mode.elabEq(1)
  private val useHalfRate = mode.elabEq(2)
  private val holdFlowPayload = mode.elabEq(1)

  val streamIn = slave(Stream(Bits(8 bits))).setName("stream_in")
  val streamOut = master(Stream(Bits(8 bits))).setName("stream_out")
  streamOut << streamIn.pipelined(useM2s, useS2m, useHalfRate)

  val flowIn = slave(Flow(Bits(8 bits))).setName("flow_in")
  val flowOut = master(Flow(Bits(8 bits))).setName("flow_out")
  flowOut << flowIn.m2sPipe(holdFlowPayload)
}

/** Independent native-Boolean witnesses for the symbolic Stream/Flow pipeline
  * controls above.
  *
  * The reference is elaborated once for each legal PIPE_MODE value. It neither
  * instantiates the typed fixture nor converts a typed value back to Boolean,
  * so the two formal legs cannot accidentally share the implementation under
  * review.
  */
final class NativeLibraryMigrationConcretePipelineFormalTop(mode: Int)
    extends Component {
  require(mode >= 0 && mode <= 2)
  setDefinitionName(s"NativeLibraryMigrationConcretePipelineFormalTopMode$mode")

  val streamIn = slave(Stream(Bits(8 bits))).setName("stream_in")
  val streamOut = master(Stream(Bits(8 bits))).setName("stream_out")
  streamOut << streamIn.pipelined(
    m2s = mode == 0,
    s2m = mode == 1,
    halfRate = mode == 2
  )

  val flowIn = slave(Flow(Bits(8 bits))).setName("flow_in")
  val flowOut = master(Flow(Bits(8 bits))).setName("flow_out")
  flowOut << flowIn.m2sPipe(holdPayload = mode == 1)
}

class NativeLibraryMigrationFormalEquivalenceTests extends AnyFunSuite {
  // Mode 2 covers the native halfPipe algorithm in addition to the two
  // independently registered handshake directions requested by the core
  // Increment 57 acceptance case.
  private val Modes = Vector(0, 1, 2)
  private val FormalGateEnvironment =
    "MORPHDL_RUN_NATIVE_LIBRARY_MIGRATION_FORMAL_EQUIVALENCE"
  private val FormalWorkspaceEnvironment =
    "MORPHDL_NATIVE_LIBRARY_MIGRATION_FORMAL_WORKSPACE"
  private val ParameterizedFile = "native_library_migration_pipeline_parameterized.v"
  private val TypedTop = "NativeLibraryMigrationTypedPipelineFormalTop"

  private val ModuleDeclaration =
    """(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b""".r

  private final case class GeneratedDuts(
      parameterized: Path,
      concreteByMode: Map[Int, Path]
  )

  private final case class PreparedDuts(candidate: Path, concrete: Path)

  test("formal witnesses independently cover all legal PIPE_MODE specializations") {
    withTemporaryDirectory { directory =>
      validateGeneratedDuts(generateDuts(directory))
    }
  }

  test("typed Stream and Flow pipeline controls are formally equivalent with a live mutation control") {
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
      val prepared = Modes.map { mode =>
        mode -> prepareDuts(directory, generated, mode)
      }.toMap

      Modes.foreach { mode =>
        val miter = directory.resolve(s"native_library_pipeline_mode_${mode}_equivalence.v")
        write(miter, equivalenceMiter(mode, mutateStreamPayload = false))
        val config = directory.resolve(s"native_library_pipeline_mode_${mode}_equivalence.sby")
        write(config, positiveSby(prepared(mode), miter, miterModule(mode)))
        runSby(
          directory,
          config,
          expectedStatus = "PASS",
          requireCounterexample = false
        )
      }

      // Mode zero has a registered M2S payload path. Flipping a payload bit at
      // that observable boundary must be found after a valid transaction; the
      // generated VCD proves that the equivalence assertions are not vacuous.
      val mutationMode = 0
      val mutationMiter = directory.resolve(
        s"native_library_pipeline_mode_${mutationMode}_mutation.v"
      )
      write(
        mutationMiter,
        equivalenceMiter(mutationMode, mutateStreamPayload = true)
      )
      val mutationConfig = directory.resolve(
        s"native_library_pipeline_mode_${mutationMode}_mutation.sby"
      )
      write(
        mutationConfig,
        mutationSby(
          prepared(mutationMode),
          mutationMiter,
          miterModule(mutationMode)
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
    MorphVerilog(parameterizedConfig) {
      new NativeLibraryMigrationTypedPipelineFormalTop(
        HdlInt.param("PIPE_MODE", default = 0, min = 0, max = 2)
      )
    }
    val parameterized = parameterizedDirectory.resolve(ParameterizedFile)

    val concreteByMode = Modes.map { mode =>
      val concreteDirectory = directory.resolve(s"concrete-mode-$mode")
      Files.createDirectories(concreteDirectory)
      val file = s"native_library_migration_pipeline_concrete_mode_$mode.v"
      val concreteConfig = synchronousResetConfig(concreteDirectory)
      concreteConfig.netlistFileName = file
      SpinalVerilog(concreteConfig) {
        new NativeLibraryMigrationConcretePipelineFormalTop(mode)
      }
      mode -> concreteDirectory.resolve(file)
    }.toMap

    GeneratedDuts(parameterized, concreteByMode)
  }

  private def validateGeneratedDuts(generated: GeneratedDuts): Unit = {
    val parameterized = read(generated.parameterized)
    val firstGenerate = parameterized.indexOf("\n  generate")
    assert(firstGenerate > 0, parameterized)
    val moduleScope = parameterized.substring(0, firstGenerate).replaceAll("\\s+", "")
    assert(parameterized.contains("parameter integer PIPE_MODE = 0"), parameterized)
    assert(parameterized.contains(s"module $TypedTop #("), parameterized)
    assert(moduleScope.contains("reg[0:0]stream_in_pipelinedSourceValid;"), parameterized)
    assert(moduleScope.contains("reg[7:0]stream_in_pipelinedSourcePayload;"), parameterized)
    assert(moduleScope.contains("regstream_in_pipelinedSourceReady;"), parameterized)
    Modes.foreach { mode =>
      assert(parameterized.contains(s"if (((PIPE_MODE) == ($mode)))"), parameterized)
    }
    assert(!parameterized.contains("NativeIntShadow"), parameterized)
    assert(
      moduleNames(parameterized).toSet == Set(TypedTop),
      s"Typed module inventory was ${moduleNames(parameterized).sorted.mkString(", ")}"
    )

    val concreteSources = generated.concreteByMode.toVector.map { case (mode, path) =>
      val source = read(path)
      assert(!source.contains("parameter integer PIPE_MODE"), source)
      val expected = Set(concreteTop(mode))
      assert(
        moduleNames(source).toSet == expected,
        s"Concrete mode $mode module inventory was ${moduleNames(source).sorted.mkString(", ")}"
      )
      source
    }
    assert(
      concreteSources.toSet.size == Modes.size,
      "Concrete pipeline references were not independently specialized"
    )
    assert(
      concreteSources.flatMap(moduleNames).toSet
        .intersect(moduleNames(parameterized).toSet)
        .isEmpty,
      "Concrete and typed DUT legs share a module definition name"
    )
  }

  private def prepareDuts(
      directory: Path,
      generated: GeneratedDuts,
      mode: Int
  ): PreparedDuts = {
    val candidate = directory.resolve(s"native_library_pipeline_candidate_mode_$mode.il")
    val candidateScript = directory.resolve(
      s"prepare_native_library_pipeline_candidate_mode_$mode.ys"
    )
    write(
      candidateScript,
      s"""read_verilog -defer ${yosysPath(generated.parameterized)}
         |chparam -set PIPE_MODE $mode $TypedTop
         |hierarchy -check -top $TypedTop
         |flatten
         |proc
         |opt_clean
         |check -assert
         |rename -top ${candidateFormalTop(mode)}
         |write_rtlil ${yosysPath(candidate)}
         |""".stripMargin
    )
    runYosys(directory, candidateScript, candidate)

    val concrete = directory.resolve(s"native_library_pipeline_reference_mode_$mode.il")
    val concreteScript = directory.resolve(
      s"prepare_native_library_pipeline_reference_mode_$mode.ys"
    )
    write(
      concreteScript,
      s"""read_verilog -defer ${yosysPath(generated.concreteByMode(mode))}
         |hierarchy -check -top ${concreteTop(mode)}
         |flatten
         |proc
         |opt_clean
         |check -assert
         |rename -top ${concreteFormalTop(mode)}
         |write_rtlil ${yosysPath(concrete)}
         |""".stripMargin
    )
    runYosys(directory, concreteScript, concrete)

    PreparedDuts(candidate, concrete)
  }

  private def equivalenceMiter(
      mode: Int,
      mutateStreamPayload: Boolean
  ): String = {
    val candidatePayload =
      if (mutateStreamPayload) "(morph_stream_out_payload_raw ^ 8'h01)"
      else "morph_stream_out_payload_raw"

    s"""module ${miterModule(mode)} (
       |  input wire clk,
       |  input wire reset,
       |  input wire stream_in_valid,
       |  input wire [7:0] stream_in_payload,
       |  input wire stream_out_ready,
       |  input wire flow_in_valid,
       |  input wire [7:0] flow_in_payload
       |);
       |  wire concrete_stream_in_ready;
       |  wire concrete_stream_out_valid;
       |  wire [7:0] concrete_stream_out_payload;
       |  wire concrete_flow_out_valid;
       |  wire [7:0] concrete_flow_out_payload;
       |  wire morph_stream_in_ready;
       |  wire morph_stream_out_valid;
       |  wire [7:0] morph_stream_out_payload_raw;
       |  wire [7:0] morph_stream_out_payload_compared;
       |  wire morph_flow_out_valid;
       |  wire [7:0] morph_flow_out_payload;
       |
       |  assign morph_stream_out_payload_compared = $candidatePayload;
       |
       |  ${concreteFormalTop(mode)} concrete_dut (
       |    .stream_in_valid(stream_in_valid),
       |    .stream_in_ready(concrete_stream_in_ready),
       |    .stream_in_payload(stream_in_payload),
       |    .stream_out_valid(concrete_stream_out_valid),
       |    .stream_out_ready(stream_out_ready),
       |    .stream_out_payload(concrete_stream_out_payload),
       |    .flow_in_valid(flow_in_valid),
       |    .flow_in_payload(flow_in_payload),
       |    .flow_out_valid(concrete_flow_out_valid),
       |    .flow_out_payload(concrete_flow_out_payload),
       |    .clk(clk),
       |    .reset(reset)
       |  );
       |
       |  ${candidateFormalTop(mode)} morph_dut (
       |    .stream_in_valid(stream_in_valid),
       |    .stream_in_ready(morph_stream_in_ready),
       |    .stream_in_payload(stream_in_payload),
       |    .stream_out_valid(morph_stream_out_valid),
       |    .stream_out_ready(stream_out_ready),
       |    .stream_out_payload(morph_stream_out_payload_raw),
       |    .flow_in_valid(flow_in_valid),
       |    .flow_in_payload(flow_in_payload),
       |    .flow_out_valid(morph_flow_out_valid),
       |    .flow_out_payload(morph_flow_out_payload),
       |    .clk(clk),
       |    .reset(reset)
       |  );
       |
       |  always @* begin
       |    if ($$initstate)
       |      assume(reset);
       |    if (!$$initstate) begin
       |      assert(concrete_stream_in_ready == morph_stream_in_ready);
       |      assert(concrete_stream_out_valid == morph_stream_out_valid);
       |      if (concrete_stream_out_valid && morph_stream_out_valid)
       |        assert(concrete_stream_out_payload == morph_stream_out_payload_compared);
       |      assert(concrete_flow_out_valid == morph_flow_out_valid);
       |      if (concrete_flow_out_valid && morph_flow_out_valid)
       |        assert(concrete_flow_out_payload == morph_flow_out_payload);
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
       |timeout 180
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
       |depth 4
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
      s"SymbiYosys published an ambiguous status for ${config.getFileName}: ${statusLines.mkString(" | ")}\n$output"
    )
    val statusTokens = statusLines.head.split("\\s+").toVector
    assert(
      statusTokens.nonEmpty && statusTokens.tail.forall(_.matches("[0-9]+")),
      s"SymbiYosys published a malformed status for ${config.getFileName}: ${statusLines.head}\n$output"
    )
    assert(
      statusTokens.head == expectedStatus,
      s"Expected formal $expectedStatus for ${config.getFileName}, received ${statusTokens.head}:\n$output"
    )

    if (requireCounterexample) {
      val files = regularFiles(workDirectory)
      val traces = files.filter(_.getFileName.toString.endsWith(".vcd"))
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

  private def miterModule(mode: Int): String =
    s"NativeLibraryMigrationPipelineFormalMiterMode$mode"

  private def candidateFormalTop(mode: Int): String =
    s"MorphNativeLibraryMigrationPipelineCandidateMode$mode"

  private def concreteFormalTop(mode: Int): String =
    s"ConcreteNativeLibraryMigrationPipelineReferenceMode$mode"

  private def concreteTop(mode: Int): String =
    s"NativeLibraryMigrationConcretePipelineFormalTopMode$mode"

  private def moduleNames(verilog: String): Vector[String] =
    ModuleDeclaration.findAllMatchIn(verilog).map(_.group(1)).toVector

  private def yosysPath(path: Path): String = {
    val absolute = path.toAbsolutePath.normalize.toString
    require(
      !absolute.exists(character => character.isWhitespace || character == '"'),
      s"Formal workspace path is not safely representable in a Yosys script: $absolute"
    )
    absolute
  }

  private def run(directory: Path, command: Seq[String]): (Int, String) = {
    val output = new StringBuilder
    val exitCode = Process(command, directory.toFile).!(
      ProcessLogger(
        line => output.append(line).append('\n'),
        line => output.append(line).append('\n')
      )
    )
    exitCode -> output.toString
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
    val directory = Files.createTempDirectory("morphhdl-native-library-formal-")
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
        val directory = Paths.get(configured).toAbsolutePath
        Files.createDirectories(directory)
        body(directory)
      case None =>
        withTemporaryDirectory(body)
    }
}
