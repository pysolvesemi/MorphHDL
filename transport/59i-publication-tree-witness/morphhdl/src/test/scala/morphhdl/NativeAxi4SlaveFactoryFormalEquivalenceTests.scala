package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.regex.Pattern

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4SlaveFactory}

import morphhdl.frontend.HdlInt

/** Independent ordinary-SpinalVerilog witness using only the authoritative
  * concrete BigInt register-map overloads.
  */
final class NativeConcreteAxi4SlaveFactoryTop(offset: Int) extends Component {
  require(offset >= 0x010 && offset <= 0x070)
  setDefinitionName(s"NativeConcreteAxi4SlaveFactoryTopOffset$offset")

  private val config = Axi4Config(
    addressWidth = 12,
    dataWidth = 32,
    idWidth = 2
  )

  val io = new Bundle {
    val axi = slave(Axi4(config))
    val observedBase = out UInt (32 bits)
    val observedNext = out UInt (32 bits)
    val observedFixed = out UInt (32 bits)
    val observedReadEvent = out Bool ()
    val observedWriteEvent = out Bool ()
  }

  val baseRegister = Reg(UInt(32 bits)) init (0)
  val nextRegister = Reg(UInt(32 bits)) init (0)
  val fixedRegister = Reg(UInt(32 bits)) init (0)
  val readEvent = Reg(Bool()) init (False)
  val writeEvent = Reg(Bool()) init (False)

  io.observedBase := baseRegister
  io.observedNext := nextRegister
  io.observedFixed := fixedRegister
  io.observedReadEvent := readEvent
  io.observedWriteEvent := writeEvent

  val factory = Axi4SlaveFactory(io.axi)
  factory.write(baseRegister, BigInt(offset))
  factory.read(baseRegister, BigInt(offset))
  factory.readAndWrite(nextRegister, BigInt(offset + 4))
  factory.onRead(BigInt(offset + 8)) { readEvent := True }
  factory.onWrite(BigInt(offset + 8)) { writeEvent := True }
  factory.readAndWrite(fixedRegister, BigInt(0x080))
}

class NativeAxi4SlaveFactoryFormalEquivalenceTests extends AnyFunSuite {
  private val Offsets = Vector(0x010, 0x040, 0x070)
  private val FormalGateEnvironment =
    "MORPHDL_RUN_NATIVE_AXI4_FORMAL_EQUIVALENCE"
  private val FormalWorkspaceEnvironment =
    "MORPHDL_NATIVE_AXI4_FORMAL_WORKSPACE"
  private val ParameterizedFile = "native_axi4_parameterized_formal.v"

  private val ModuleDeclaration =
    """(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b""".r

  private final case class GeneratedDuts(
      parameterized: Path,
      concreteByOffset: Map[Int, Path]
  )

  private final case class PreparedDuts(
      candidate: Path,
      concrete: Path
  )

  test(
    "formal witnesses are independent native-Int Axi4SlaveFactory elaborations sharing one Morph definition"
  ) {
    withTemporaryDirectory { directory =>
      validateGeneratedDuts(generateDuts(directory))
    }
  }

  test(
    "one Morph Axi4SlaveFactory definition is formally equivalent to all concrete native-Int witnesses"
  ) {
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
      val preparedByOffset = Offsets.map { offset =>
        offset -> prepareDuts(directory, generated, offset)
      }.toMap

      Offsets.foreach { offset =>
        val miter = directory.resolve(
          s"native_axi4_equivalence_offset_$offset.v"
        )
        write(miter, equivalenceMiter(offset, mutateObservedBase = false))
        val config = directory.resolve(
          s"native_axi4_equivalence_offset_$offset.sby"
        )
        write(
          config,
          positiveSby(
            preparedByOffset(offset),
            miter,
            miterModule(offset)
          )
        )
        runSby(
          directory,
          config,
          expectedStatus = "PASS",
          requireCounterexample = false
        )
      }

      val mutationOffset = 0x040
      val mutationMiter =
        directory.resolve("native_axi4_equivalence_offset_64_mutation.v")
      write(
        mutationMiter,
        equivalenceMiter(mutationOffset, mutateObservedBase = true)
      )
      val mutationConfig =
        directory.resolve("native_axi4_equivalence_offset_64_mutation.sby")
      write(
        mutationConfig,
        mutationSby(
          preparedByOffset(mutationOffset),
          mutationMiter,
          miterModule(mutationOffset)
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
    val offsetWord = HdlInt.param(
      "OFFSET_WORD",
      default = BigInt(16),
      min = BigInt(4),
      max = BigInt(28)
    ).asElabInt
    MorphVerilog(parameterizedConfig) {
      new NativeAxi4SlaveFactoryParameterizedTop(offsetWord * 4)
    }
    val parameterized = parameterizedDirectory.resolve(ParameterizedFile)

    val concreteByOffset = Offsets.map { selectedOffset =>
      val concreteDirectory =
        directory.resolve(s"concrete-offset-$selectedOffset")
      Files.createDirectories(concreteDirectory)
      val concreteConfig = synchronousResetConfig(concreteDirectory)
      val file = s"native_axi4_concrete_offset_$selectedOffset.v"
      concreteConfig.netlistFileName = file
      SpinalVerilog(concreteConfig) {
        new NativeConcreteAxi4SlaveFactoryTop(selectedOffset)
      }
      selectedOffset -> concreteDirectory.resolve(file)
    }.toMap

    GeneratedDuts(parameterized, concreteByOffset)
  }

  private def validateGeneratedDuts(generated: GeneratedDuts): Unit = {
    val parameterized = read(generated.parameterized)
    assert(parameterized.contains("parameter integer OFFSET_WORD = 16"))
    assert(
      parameterized.contains(
        "module NativeAxi4SlaveFactoryParameterizedTop #("
      )
    )
    assert(containsMultipliedOffset(parameterized))
    assert(containsDerivedByteOffset(parameterized, 4))
    assert(containsDerivedByteOffset(parameterized, 8))
    assert(containsAddressLiteral(parameterized, 0x080))
    assert(
      moduleNames(parameterized).toSet ==
        Set("NativeAxi4SlaveFactoryParameterizedTop")
    )

    val allConcreteModules =
      generated.concreteByOffset.toVector.flatMap { case (offset, path) =>
        val concrete = read(path)
        assert(
          !concrete.contains("parameter integer OFFSET_WORD"),
          s"Concrete offset $offset unexpectedly retained OFFSET_WORD"
        )
        assert(containsAddressLiteral(concrete, offset))
        assert(containsAddressLiteral(concrete, offset + 4))
        assert(containsAddressLiteral(concrete, offset + 8))
        assert(containsAddressLiteral(concrete, 0x080))
        val expected = Set(s"NativeConcreteAxi4SlaveFactoryTopOffset$offset")
        val actual = moduleNames(concrete).toSet
        assert(
          actual == expected,
          s"Concrete offset $offset module inventory was ${actual.toVector.sorted.mkString(", ")}"
        )
        actual
      }.toSet

    assert(
      allConcreteModules.intersect(moduleNames(parameterized).toSet).isEmpty,
      "Concrete and MorphHDL AXI4 DUT legs share a module definition name"
    )
    assert(
      generated.concreteByOffset.values.map(path => read(path)).toSet.size ==
        Offsets.size,
      "Concrete AXI4 witnesses were not independently specialized by native Int offset"
    )
  }

  private def prepareDuts(
      directory: Path,
      generated: GeneratedDuts,
      offset: Int
  ): PreparedDuts = {
    val candidate =
      directory.resolve(s"morph_axi4_candidate_offset_$offset.il")
    val candidateScript =
      directory.resolve(s"prepare_morph_axi4_candidate_offset_$offset.ys")
    require(offset % 4 == 0)
    val offsetWord = offset / 4
    write(
      candidateScript,
      s"""read_verilog -defer ${yosysPath(generated.parameterized)}
         |chparam -set OFFSET_WORD $offsetWord NativeAxi4SlaveFactoryParameterizedTop
         |hierarchy -check -top NativeAxi4SlaveFactoryParameterizedTop
         |flatten
         |proc
         |opt_clean
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top ${candidateFormalTop(offset)}
         |write_rtlil ${yosysPath(candidate)}
         |""".stripMargin
    )
    runYosys(directory, candidateScript, candidate)

    val concrete =
      directory.resolve(s"concrete_axi4_reference_offset_$offset.il")
    val concreteScript =
      directory.resolve(s"prepare_concrete_axi4_reference_offset_$offset.ys")
    write(
      concreteScript,
      s"""read_verilog -defer ${yosysPath(generated.concreteByOffset(offset))}
         |hierarchy -check -top NativeConcreteAxi4SlaveFactoryTopOffset$offset
         |flatten
         |proc
         |opt_clean
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top ${concreteFormalTop(offset)}
         |write_rtlil ${yosysPath(concrete)}
         |""".stripMargin
    )
    runYosys(directory, concreteScript, concrete)

    PreparedDuts(candidate, concrete)
  }

  private def equivalenceMiter(
      offset: Int,
      mutateObservedBase: Boolean
  ): String = {
    val comparedObservedBase =
      if (mutateObservedBase) "(morph_observed_base ^ 32'h00000001)"
      else "morph_observed_base"

    s"""module ${miterModule(offset)} (
       |  input wire clk,
       |  input wire reset,
       |  input wire axi_aw_valid,
       |  input wire [11:0] axi_aw_addr,
       |  input wire [1:0] axi_aw_id,
       |  input wire [3:0] axi_aw_region,
       |  input wire [7:0] axi_aw_len,
       |  input wire [2:0] axi_aw_size,
       |  input wire [1:0] axi_aw_burst,
       |  input wire axi_aw_lock,
       |  input wire [3:0] axi_aw_cache,
       |  input wire [3:0] axi_aw_qos,
       |  input wire [2:0] axi_aw_prot,
       |  input wire axi_w_valid,
       |  input wire [31:0] axi_w_data,
       |  input wire [3:0] axi_w_strb,
       |  input wire axi_w_last,
       |  input wire axi_b_ready,
       |  input wire axi_ar_valid,
       |  input wire [11:0] axi_ar_addr,
       |  input wire [1:0] axi_ar_id,
       |  input wire [3:0] axi_ar_region,
       |  input wire [7:0] axi_ar_len,
       |  input wire [2:0] axi_ar_size,
       |  input wire [1:0] axi_ar_burst,
       |  input wire axi_ar_lock,
       |  input wire [3:0] axi_ar_cache,
       |  input wire [3:0] axi_ar_qos,
       |  input wire [2:0] axi_ar_prot,
       |  input wire axi_r_ready
       |);
       |  wire concrete_aw_ready;
       |  wire concrete_w_ready;
       |  wire concrete_b_valid;
       |  wire [1:0] concrete_b_id;
       |  wire [1:0] concrete_b_resp;
       |  wire concrete_ar_ready;
       |  wire concrete_r_valid;
       |  wire [31:0] concrete_r_data;
       |  wire [1:0] concrete_r_id;
       |  wire [1:0] concrete_r_resp;
       |  wire concrete_r_last;
       |  wire [31:0] concrete_observed_base;
       |  wire [31:0] concrete_observed_next;
       |  wire [31:0] concrete_observed_fixed;
       |  wire concrete_observed_read_event;
       |  wire concrete_observed_write_event;
       |
       |  wire morph_aw_ready;
       |  wire morph_w_ready;
       |  wire morph_b_valid;
       |  wire [1:0] morph_b_id;
       |  wire [1:0] morph_b_resp;
       |  wire morph_ar_ready;
       |  wire morph_r_valid;
       |  wire [31:0] morph_r_data;
       |  wire [1:0] morph_r_id;
       |  wire [1:0] morph_r_resp;
       |  wire morph_r_last;
       |  wire [31:0] morph_observed_base;
       |  wire [31:0] morph_observed_next;
       |  wire [31:0] morph_observed_fixed;
       |  wire morph_observed_read_event;
       |  wire morph_observed_write_event;
       |  wire [31:0] morph_observed_base_compared;
       |
       |  assign morph_observed_base_compared = $comparedObservedBase;
       |
       |  ${concreteFormalTop(offset)} concrete_dut (
       |    .io_axi_aw_valid(axi_aw_valid),
       |    .io_axi_aw_ready(concrete_aw_ready),
       |    .io_axi_aw_payload_addr(axi_aw_addr),
       |    .io_axi_aw_payload_id(axi_aw_id),
       |    .io_axi_aw_payload_region(axi_aw_region),
       |    .io_axi_aw_payload_len(axi_aw_len),
       |    .io_axi_aw_payload_size(axi_aw_size),
       |    .io_axi_aw_payload_burst(axi_aw_burst),
       |    .io_axi_aw_payload_lock(axi_aw_lock),
       |    .io_axi_aw_payload_cache(axi_aw_cache),
       |    .io_axi_aw_payload_qos(axi_aw_qos),
       |    .io_axi_aw_payload_prot(axi_aw_prot),
       |    .io_axi_w_valid(axi_w_valid),
       |    .io_axi_w_ready(concrete_w_ready),
       |    .io_axi_w_payload_data(axi_w_data),
       |    .io_axi_w_payload_strb(axi_w_strb),
       |    .io_axi_w_payload_last(axi_w_last),
       |    .io_axi_b_valid(concrete_b_valid),
       |    .io_axi_b_ready(axi_b_ready),
       |    .io_axi_b_payload_id(concrete_b_id),
       |    .io_axi_b_payload_resp(concrete_b_resp),
       |    .io_axi_ar_valid(axi_ar_valid),
       |    .io_axi_ar_ready(concrete_ar_ready),
       |    .io_axi_ar_payload_addr(axi_ar_addr),
       |    .io_axi_ar_payload_id(axi_ar_id),
       |    .io_axi_ar_payload_region(axi_ar_region),
       |    .io_axi_ar_payload_len(axi_ar_len),
       |    .io_axi_ar_payload_size(axi_ar_size),
       |    .io_axi_ar_payload_burst(axi_ar_burst),
       |    .io_axi_ar_payload_lock(axi_ar_lock),
       |    .io_axi_ar_payload_cache(axi_ar_cache),
       |    .io_axi_ar_payload_qos(axi_ar_qos),
       |    .io_axi_ar_payload_prot(axi_ar_prot),
       |    .io_axi_r_valid(concrete_r_valid),
       |    .io_axi_r_ready(axi_r_ready),
       |    .io_axi_r_payload_data(concrete_r_data),
       |    .io_axi_r_payload_id(concrete_r_id),
       |    .io_axi_r_payload_resp(concrete_r_resp),
       |    .io_axi_r_payload_last(concrete_r_last),
       |    .io_observedBase(concrete_observed_base),
       |    .io_observedNext(concrete_observed_next),
       |    .io_observedFixed(concrete_observed_fixed),
       |    .io_observedReadEvent(concrete_observed_read_event),
       |    .io_observedWriteEvent(concrete_observed_write_event),
       |    .clk(clk),
       |    .reset(reset)
       |  );
       |
       |  ${candidateFormalTop(offset)} morph_dut (
       |    .io_axi_aw_valid(axi_aw_valid),
       |    .io_axi_aw_ready(morph_aw_ready),
       |    .io_axi_aw_payload_addr(axi_aw_addr),
       |    .io_axi_aw_payload_id(axi_aw_id),
       |    .io_axi_aw_payload_region(axi_aw_region),
       |    .io_axi_aw_payload_len(axi_aw_len),
       |    .io_axi_aw_payload_size(axi_aw_size),
       |    .io_axi_aw_payload_burst(axi_aw_burst),
       |    .io_axi_aw_payload_lock(axi_aw_lock),
       |    .io_axi_aw_payload_cache(axi_aw_cache),
       |    .io_axi_aw_payload_qos(axi_aw_qos),
       |    .io_axi_aw_payload_prot(axi_aw_prot),
       |    .io_axi_w_valid(axi_w_valid),
       |    .io_axi_w_ready(morph_w_ready),
       |    .io_axi_w_payload_data(axi_w_data),
       |    .io_axi_w_payload_strb(axi_w_strb),
       |    .io_axi_w_payload_last(axi_w_last),
       |    .io_axi_b_valid(morph_b_valid),
       |    .io_axi_b_ready(axi_b_ready),
       |    .io_axi_b_payload_id(morph_b_id),
       |    .io_axi_b_payload_resp(morph_b_resp),
       |    .io_axi_ar_valid(axi_ar_valid),
       |    .io_axi_ar_ready(morph_ar_ready),
       |    .io_axi_ar_payload_addr(axi_ar_addr),
       |    .io_axi_ar_payload_id(axi_ar_id),
       |    .io_axi_ar_payload_region(axi_ar_region),
       |    .io_axi_ar_payload_len(axi_ar_len),
       |    .io_axi_ar_payload_size(axi_ar_size),
       |    .io_axi_ar_payload_burst(axi_ar_burst),
       |    .io_axi_ar_payload_lock(axi_ar_lock),
       |    .io_axi_ar_payload_cache(axi_ar_cache),
       |    .io_axi_ar_payload_qos(axi_ar_qos),
       |    .io_axi_ar_payload_prot(axi_ar_prot),
       |    .io_axi_r_valid(morph_r_valid),
       |    .io_axi_r_ready(axi_r_ready),
       |    .io_axi_r_payload_data(morph_r_data),
       |    .io_axi_r_payload_id(morph_r_id),
       |    .io_axi_r_payload_resp(morph_r_resp),
       |    .io_axi_r_payload_last(morph_r_last),
       |    .io_observedBase(morph_observed_base),
       |    .io_observedNext(morph_observed_next),
       |    .io_observedFixed(morph_observed_fixed),
       |    .io_observedReadEvent(morph_observed_read_event),
       |    .io_observedWriteEvent(morph_observed_write_event),
       |    .clk(clk),
       |    .reset(reset)
       |  );
       |
       |  always @* begin
       |    if ($$initstate)
       |      assume(reset);
       |    if (!$$initstate) begin
       |      assert(concrete_aw_ready == morph_aw_ready);
       |      assert(concrete_w_ready == morph_w_ready);
       |      assert(concrete_b_valid == morph_b_valid);
       |      assert(concrete_ar_ready == morph_ar_ready);
       |      assert(concrete_r_valid == morph_r_valid);
       |      assert(concrete_observed_base == morph_observed_base_compared);
       |      assert(concrete_observed_next == morph_observed_next);
       |      assert(concrete_observed_fixed == morph_observed_fixed);
       |      assert(concrete_observed_read_event == morph_observed_read_event);
       |      assert(concrete_observed_write_event == morph_observed_write_event);
       |      if (concrete_b_valid && morph_b_valid) begin
       |        assert(concrete_b_id == morph_b_id);
       |        assert(concrete_b_resp == morph_b_resp);
       |      end
       |      if (concrete_r_valid && morph_r_valid) begin
       |        assert(concrete_r_data == morph_r_data);
       |        assert(concrete_r_id == morph_r_id);
       |        assert(concrete_r_resp == morph_r_resp);
       |        assert(concrete_r_last == morph_r_last);
       |      end
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
    val statusLines = read(statusFile).split("\\r?\\n", -1).iterator
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
      val traces =
        files.filter(path => path.getFileName.toString.endsWith(".vcd"))
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

  private def miterModule(offset: Int): String =
    s"NativeAxi4SlaveFactoryFormalMiterOffset$offset"

  private def candidateFormalTop(offset: Int): String =
    s"MorphNativeAxi4SlaveFactoryFormalCandidateOffset$offset"

  private def concreteFormalTop(offset: Int): String =
    s"ConcreteNativeAxi4SlaveFactoryFormalReferenceOffset$offset"

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

  private def containsAddressLiteral(verilog: String, value: Int): Boolean = {
    val decimal = Pattern
      .compile(
        "(?<![A-Za-z0-9_])" + Pattern.quote(value.toString) +
          "(?![A-Za-z0-9_])"
      )
      .matcher(verilog)
      .find()
    val nativeHex = f"12'h$value%03x"
    decimal || verilog.toLowerCase.contains(nativeHex)
  }

  private def containsMultipliedOffset(verilog: String): Boolean =
    verilog.replaceAll("\\s+", "").contains("OFFSET_WORD*4")

  private def containsDerivedByteOffset(
      verilog: String,
      addend: Int
  ): Boolean = {
    val compact = verilog.replaceAll("\\s+", "")
    compact.contains(s"(OFFSET_WORD*4)+$addend")
  }

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
      Files.createTempDirectory("morphhdl-native-axi4-formal-equivalence-")
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
