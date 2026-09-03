package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.lib._

private object NativeStreamFifoCCFormalEquivalenceFixture {
  private val ClockConfig = ClockDomainConfig(
    clockEdge = RISING,
    resetKind = ASYNC,
    resetActiveLevel = HIGH
  )

  /** Typed proof leg. Both payload width and depth reach the ordinary native
    * factory as ElabInts; no frontend FIFO adapter or concrete witness is
    * shared with the reference leg.
    */
  final class TypedTop(
      width: ElabInt,
      depth: ElabInt,
      bufferedPopReset: Boolean
  )
      extends Component {
    setDefinitionName(
      if (bufferedPopReset) "NativeStreamFifoCC57bTypedBuffered"
      else "NativeStreamFifoCC57bTypedDirect"
    )

    val io = new Bundle {
      val pushValid = in Bool ()
      val pushReady = out Bool ()
      val pushPayload = in Bits (width bits)
      val popValid = out Bool ()
      val popReady = in Bool ()
      val popPayload = out Bits (width bits)
      val pushOccupancy = out UInt (5 bits)
      val popOccupancy = out UInt (5 bits)
    }

    private val pushClock = ClockDomain.external("push", config = ClockConfig)
    private val popClock = ClockDomain.external("pop", config = ClockConfig)

    val fifo = spinal.lib.StreamFifoCC(
      HardType(Bits(width bits)),
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
    // Keep every child output on a direct full-port connection. The miter
    // masks the native payload don't-care by comparing it only while valid.
    io.popPayload := fifo.io.pop.payload
    io.pushOccupancy := fifo.io.pushOccupancy.resized
    io.popOccupancy := fifo.io.popOccupancy.resized
  }

  /** Independent ordinary-SpinalHDL reference. This leg accepts only Int and
    * is elaborated independently for each concrete width, depth and reset
    * topology.
    */
  final class ConcreteTop(
      width: Int,
      depth: Int,
      bufferedPopReset: Boolean
  )
      extends Component {
    require(width >= 1)
    require(depth >= 2 && (depth & (depth - 1)) == 0)
    setDefinitionName(
      s"NativeStreamFifoCC57bConcreteW${width}D${depth}" +
        (if (bufferedPopReset) "Buffered" else "Direct")
    )

    val io = new Bundle {
      val pushValid = in Bool ()
      val pushReady = out Bool ()
      val pushPayload = in Bits (width bits)
      val popValid = out Bool ()
      val popReady = in Bool ()
      val popPayload = out Bits (width bits)
      val pushOccupancy = out UInt (5 bits)
      val popOccupancy = out UInt (5 bits)
    }

    private val pushClock = ClockDomain.external("push", config = ClockConfig)
    private val popClock = ClockDomain.external("pop", config = ClockConfig)

    val fifo = new spinal.lib.StreamFifoCC(
      HardType(Bits(width bits)),
      depth,
      pushClock,
      popClock,
      bufferedPopReset
    )
    fifo.setDefinitionName(
      s"NativeStreamFifoCC57bConcreteCoreW${width}D${depth}" +
        (if (bufferedPopReset) "Buffered" else "Direct")
    )

    fifo.io.push.valid := io.pushValid
    fifo.io.push.payload := io.pushPayload
    io.pushReady := fifo.io.push.ready
    io.popValid := fifo.io.pop.valid
    fifo.io.pop.ready := io.popReady
    io.popPayload := fifo.io.pop.payload
    io.pushOccupancy := fifo.io.pushOccupancy.resized
    io.popOccupancy := fifo.io.popOccupancy.resized
  }
}

/** Relational proof for Increment 57b.
  *
  * Each typed width/depth specialization is compared with an independently
  * elaborated native-Int StreamFifoCC. Two deterministic asynchronous clock
  * schedules exercise both 2:1 directions, and both native pop-reset-buffer
  * modes are covered. The expensive solver matrix is deliberately opt-in; the
  * ordinary test lane still verifies that all independent witnesses can be
  * generated.
  */
class NativeStreamFifoCCFormalEquivalenceTests extends AnyFunSuite {
  import NativeStreamFifoCCFormalEquivalenceFixture._

  private final case class ClockRatio(
      name: String,
      pushPhaseBit: Int,
      popPhaseBit: Int
  )
  private final case class Configuration(
      width: Int,
      depth: Int,
      buffered: Boolean,
      ratio: ClockRatio
  )
  private final case class GeneratedDuts(
      typedByResetMode: Map[Boolean, Path],
      concreteByConfiguration: Map[(Int, Int, Boolean), Path]
  )
  private final case class PreparedDuts(candidate: Path, reference: Path)

  private val Widths = Vector(1, 5, 8, 32)
  private val Depths = Vector(2, 4, 8, 16)
  private val ResetModes = Vector(false, true)
  private val ClockRatios = Vector(
    ClockRatio("push_2x_pop", pushPhaseBit = 0, popPhaseBit = 1),
    ClockRatio("pop_2x_push", pushPhaseBit = 1, popPhaseBit = 0)
  )
  private val Configurations = for {
    width <- Widths
    depth <- Depths
    buffered <- ResetModes
    ratio <- ClockRatios
  } yield Configuration(width, depth, buffered, ratio)

  private val FormalGateEnvironment =
    "MORPHDL_RUN_STREAMFIFOCC_FORMAL_EQUIVALENCE"
  private val FormalWorkspaceEnvironment =
    "MORPHDL_STREAMFIFOCC_FORMAL_WORKSPACE"
  private val ParameterizedFile = "native_streamfifocc_57b_parameterized.v"
  private val ModuleDeclaration =
    """(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b""".r

  test(
    "formal references independently cover width and depth corners in both reset modes"
  ) {
    withTemporaryDirectory { directory =>
      validateGeneratedDuts(generateDuts(directory))
    }
  }

  test("formal matrix preserves width 8 CDC coverage and crosses every geometry corner") {
    assert(Widths == Vector(1, 5, 8, 32))
    assert(Depths == Vector(2, 4, 8, 16))
    assert(Configurations.size == 64)
    assert(Configurations.distinct.size == Configurations.size)
    assert(
      Configurations.count(_.width == 8) ==
        Depths.size * ResetModes.size * ClockRatios.size
    )
    for {
      width <- Widths
      depth <- Depths
      buffered <- ResetModes
      ratio <- ClockRatios
    } assert(Configurations.contains(Configuration(width, depth, buffered, ratio)))
  }

  test("formal miter connects each reference and typed DUT port exactly once") {
    val connectionPattern =
      """\.([A-Za-z_][A-Za-z0-9_$]*)\s*\(\s*([A-Za-z_][A-Za-z0-9_$]*)\s*\)""".r
    val referenceDataConnections = Vector(
      "io_pushValid" -> "push_valid",
      "io_pushReady" -> "reference_push_ready",
      "io_pushPayload" -> "push_payload",
      "io_popValid" -> "reference_pop_valid",
      "io_popReady" -> "pop_ready",
      "io_popPayload" -> "reference_pop_payload",
      "io_pushOccupancy" -> "reference_push_occupancy",
      "io_popOccupancy" -> "reference_pop_occupancy"
    )
    val typedDataConnections = Vector(
      "io_pushValid" -> "push_valid",
      "io_pushReady" -> "typed_push_ready",
      "io_pushPayload" -> "push_payload",
      "io_popValid" -> "typed_pop_valid",
      "io_popReady" -> "pop_ready",
      "io_popPayload" -> "typed_pop_payload",
      "io_pushOccupancy" -> "typed_push_occupancy",
      "io_popOccupancy" -> "typed_pop_occupancy"
    )

    def connectionsOf(miter: String, instance: String): Vector[(String, String)] = {
      val instancePattern =
        ("(?s)\\b" + java.util.regex.Pattern.quote(instance) +
          "\\s*\\((.*?)\\);").r
      val body = instancePattern
        .findFirstMatchIn(miter)
        .map(_.group(1))
        .getOrElse(fail(s"missing $instance in formal miter:\n$miter"))
      connectionPattern
        .findAllMatchIn(body)
        .map(value => value.group(1) -> value.group(2))
        .toVector
    }

    Configurations.foreach { configuration =>
      val miter = equivalenceMiter(configuration, mutatePopPayload = false)
      val normalizedMiter = compact(miter)
      val payloadRange = s"[${configuration.width - 1}:0]"
      val clockResetConnections =
        Vector(
          "push_clk" -> "push_clk",
          "push_reset" -> "push_reset",
          "pop_clk" -> "pop_clk"
        ) ++ (if (configuration.buffered) Vector.empty
              else Vector("pop_reset" -> "pop_reset"))
      assert(
        connectionsOf(miter, "reference_dut") ==
          referenceDataConnections ++ clockResetConnections
      )
      assert(
        connectionsOf(miter, "typed_dut") ==
          typedDataConnections ++ clockResetConnections
      )
      Vector(
        s"inputwire${payloadRange}push_payload",
        s"wire${payloadRange}reference_pop_payload",
        s"wire${payloadRange}typed_pop_payload",
        s"wire${payloadRange}typed_pop_payload_compared"
      ).foreach(token => assert(normalizedMiter.contains(token), miter))
    }

    val mutation = equivalenceMiter(
      Configuration(
        width = 5,
        depth = 2,
        buffered = false,
        ratio = ClockRatios.head
      ),
      mutatePopPayload = true
    )
    assert(compact(mutation).contains("typed_pop_payload^5'h10"), mutation)
  }

  test("formal DUT preparation releases BufferCC hierarchy before flattening") {
    def assertSingleOrderedRelease(script: String, top: String): Unit = {
      val hierarchyRelease =
        s"""hierarchy -check -top $top
           |setattr -unset keep_hierarchy
           |flatten""".stripMargin
      val first = script.indexOf(hierarchyRelease)
      assert(first >= 0, script)
      assert(script.indexOf(hierarchyRelease, first + 1) < 0, script)
      assert(
        script
          .split("\\r?\\n", -1)
          .count(_ == "setattr -unset keep_hierarchy") == 1,
        script
      )
    }

    ResetModes.foreach { buffered =>
      val candidate = candidatePreparationScript(
        Paths.get("typed.v"),
        Paths.get("candidate.il"),
        width = 5,
        depth = 2,
        buffered = buffered
      )
      assertSingleOrderedRelease(candidate, typedSourceTop(buffered))
      assert(
        candidate.contains(
          s"chparam -set WIDTH 5 -set DEPTH 2 ${typedSourceTop(buffered)}"
        ),
        candidate
      )

      val reference = referencePreparationScript(
        Paths.get("reference.v"),
        Paths.get("reference.il"),
        width = 5,
        depth = 2,
        buffered = buffered
      )
      assertSingleOrderedRelease(
        reference,
        concreteSourceTop(width = 5, depth = 2, buffered = buffered)
      )
    }
  }

  test("formal positive proof uses reachability PDR for the multiclock model") {
    val prepared =
      PreparedDuts(Paths.get("candidate.il"), Paths.get("reference.il"))
    val miter = Paths.get("miter.v")
    val config = positiveSby(
      prepared,
      miter,
      "miter"
    )
    val mutationConfig = mutationSby(
      prepared,
      miter,
      "miter"
    )

    assert("(?m)^mode prove$".r.findFirstIn(config).nonEmpty, config)
    assert("(?m)^multiclock on$".r.findFirstIn(config).nonEmpty, config)
    assert(
      "(?m)^abc lcorr; pdr$".r.findAllMatchIn(config).length == 1,
      config
    )
    assert(
      "(?m)^smtbmc yices$".r
        .findAllMatchIn(mutationConfig)
        .length == 1,
      mutationConfig
    )
    assert(!mutationConfig.contains("lcorr;"), mutationConfig)
    assert("(?m)^depth\\s+".r.findFirstIn(config).isEmpty, config)
    assert(!config.contains("smtbmc"), config)
    val proofPreparation =
      """hierarchy -check -top miter
        |setattr -unset keep_hierarchy
        |prep -top miter
        |memory_map
        |setundef -undriven -anyseq
        |setundef -init -zero
        |opt_clean
        |check -assert""".stripMargin
    Vector(config, mutationConfig).foreach { script =>
      val first = script.indexOf(proofPreparation)
      assert(first >= 0, script)
      assert(
        script.indexOf(proofPreparation, first + 1) < 0,
        script
      )
    }
  }

  test(
    "typed StreamFifoCC is formally equivalent for both asynchronous clock ratios with a live mutation control"
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

      val generated = generateDuts(directory)
      validateGeneratedDuts(generated)
      val prepared = (for {
        width <- Widths
        depth <- Depths
        buffered <- ResetModes
      } yield {
        val key = (width, depth, buffered)
        key -> prepareDuts(directory, generated, width, depth, buffered)
      }).toMap

      Configurations.foreach { configuration =>
        val miter = directory.resolve(
          s"streamfifocc_57b_${configurationStem(configuration)}.v"
        )
        write(
          miter,
          equivalenceMiter(configuration, mutatePopPayload = false)
        )
        val config = directory.resolve(
          s"streamfifocc_57b_${configurationStem(configuration)}.sby"
        )
        write(
          config,
          positiveSby(
            prepared(
              (
                configuration.width,
                configuration.depth,
                configuration.buffered
              )
            ),
            miter,
            miterTop(configuration)
          )
        )
        runSby(
          directory,
          config,
          expectedStatus = "PASS",
          requireCounterexample = false
        )
      }

      // Flip one observable payload bit after a real CDC transfer. Forced
      // valid/ready traffic makes the negative proof non-vacuous, while the
      // required VCD and assertion diagnostic prove it is a counterexample
      // rather than a tool/setup failure.
      val mutationConfiguration = Configuration(
        width = 5,
        depth = 2,
        buffered = false,
        ratio = ClockRatios.head
      )
      val mutationMiter =
        directory.resolve("streamfifocc_57b_payload_mutation.v")
      write(
        mutationMiter,
        equivalenceMiter(mutationConfiguration, mutatePopPayload = true)
      )
      val mutationConfig =
        directory.resolve("streamfifocc_57b_payload_mutation.sby")
      write(
        mutationConfig,
        mutationSby(
          prepared(
            (
              mutationConfiguration.width,
              mutationConfiguration.depth,
              mutationConfiguration.buffered
            )
          ),
          mutationMiter,
          miterTop(mutationConfiguration)
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
    // Elaborate every ordinary reference before entering MorphVerilog's typed
    // capture boundary. Besides keeping the witnesses independent, this makes
    // it impossible for the concrete sources to consume retained typed state.
    val concreteByConfiguration = (for {
      width <- Widths
      depth <- Depths
      buffered <- ResetModes
    } yield {
      val target =
        directory.resolve(
          s"concrete-w${width}-d${depth}-${resetMode(buffered)}"
        )
      Files.createDirectories(target)
      val file =
        s"native_streamfifocc_57b_concrete_w${width}_d${depth}_${resetMode(buffered)}.v"
      val config = generationConfig(target)
      config.netlistFileName = file
      SpinalVerilog(config) {
        new ConcreteTop(width, depth, buffered)
      }
      (width, depth, buffered) -> target.resolve(file)
    }).toMap

    val typedByResetMode = ResetModes.map { buffered =>
      val target = directory.resolve(s"typed-${resetMode(buffered)}")
      Files.createDirectories(target)
      val config = generationConfig(target)
      config.netlistFileName = ParameterizedFile
      val width = HdlInt
        .param(
          "WIDTH",
          default = BigInt(8),
          min = BigInt(1),
          max = BigInt(32)
        )
        .asElabInt
      val depth = HdlInt
        .param(
          "DEPTH",
          default = BigInt(8),
          min = BigInt(2),
          max = BigInt(16)
        )
        .asElabInt
      MorphVerilog(config) {
        new TypedTop(width, depth, buffered)
      }
      buffered -> target.resolve(ParameterizedFile)
    }.toMap

    GeneratedDuts(typedByResetMode, concreteByConfiguration)
  }

  private def validateGeneratedDuts(generated: GeneratedDuts): Unit = {
    val typedSources = generated.typedByResetMode.toVector.map {
      case (buffered, path) =>
        assert(Files.isRegularFile(path), s"typed proof source is missing: $path")
        val source = read(path)
        assert(
          source.contains(s"module ${typedSourceTop(buffered)} #("),
          source
        )
        val typedHeader = compact(moduleHeader(source, typedSourceTop(buffered)))
        val fifoHeader = compact(moduleHeader(source, "StreamFifoCC"))
        assert(typedHeader.contains("parameterintegerWIDTH=8"), source)
        assert(source.contains("parameter integer DEPTH = 8"), source)
        assert(source.contains("module StreamFifoCC #("), source)
        assert(fifoHeader.contains("parameterintegerWIDTH=8"), source)
        assert(fifoHeader.contains("parameterintegerDEPTH=8"), source)
        assert(typedHeader.contains("[WIDTH-1:0]io_pushPayload"), source)
        assert(typedHeader.contains("[WIDTH-1:0]io_popPayload"), source)
        assert(fifoHeader.contains("[WIDTH-1:0]io_push_payload"), source)
        assert(fifoHeader.contains("[WIDTH-1:0]io_pop_payload"), source)
        assert(compact(source).contains(".WIDTH(WIDTH)"), source)
        assert(source.contains(".DEPTH(DEPTH)"), source)
        assert(
          compact(source).contains(
            "reg[WIDTH-1:0]algorithm_ram[0:DEPTH-1];"
          ),
          source
        )
        assert(!source.contains("NativeIntShadow"), source)
        assert(
          moduleHeader(source, typedSourceTop(buffered)).contains("pop_reset") ==
            !buffered,
          s"typed reset topology does not match buffered=$buffered:\n$source"
        )
        source
    }
    assert(
      typedSources.toSet.size == ResetModes.size,
      "typed reset modes did not produce independent definitions"
    )

    val concreteSources = generated.concreteByConfiguration.toVector.map {
      case ((width, depth, buffered), path) =>
        assert(
          Files.isRegularFile(path),
          s"concrete proof source is missing: $path"
        )
        val source = read(path)
        val top = concreteSourceTop(width, depth, buffered)
        val core = concreteCoreSourceTop(width, depth, buffered)
        assert(
          source.contains(s"module $top")
        )
        assert(source.contains(s"module $core"), source)
        val topHeader = compact(moduleHeader(source, top))
        val coreHeader = compact(moduleHeader(source, core))
        val payloadRange = s"[${width - 1}:0]"
        Vector(
          s"inputwire${payloadRange}io_pushPayload",
          s"outputwire${payloadRange}io_popPayload"
        ).foreach(token => assert(topHeader.contains(token), source))
        Vector(
          s"inputwire${payloadRange}io_push_payload",
          s"outputwire${payloadRange}io_pop_payload"
        ).foreach(token => assert(coreHeader.contains(token), source))
        assert(
          compact(source).contains(
            s"reg${payloadRange}ram[0:${depth - 1}];"
          ),
          source
        )
        assert(!source.contains("parameter integer WIDTH"), source)
        assert(!source.contains("parameter integer DEPTH"), source)
        assert(!source.contains(".WIDTH("), source)
        assert(!source.contains(".DEPTH("), source)
        assert(
          !moduleNames(source).contains(typedSourceTop(buffered)),
          "typed and concrete proof legs share their top definition"
        )
        assert(
          moduleHeader(source, top).contains("pop_reset") == !buffered,
          s"concrete reset topology does not match buffered=$buffered at width $width depth $depth:\n$source"
        )
        source
    }
    assert(
      concreteSources.toSet.size == Widths.size * Depths.size * ResetModes.size,
      "concrete references were not independently specialized"
    )
  }

  private def prepareDuts(
      directory: Path,
      generated: GeneratedDuts,
      width: Int,
      depth: Int,
      buffered: Boolean
  ): PreparedDuts = {
    val stem = s"w${width}_d${depth}_${resetMode(buffered)}"
    val candidate = directory.resolve(s"streamfifocc_57b_candidate_$stem.il")
    val candidateScript =
      directory.resolve(s"streamfifocc_57b_prepare_candidate_$stem.ys")
    write(
      candidateScript,
      candidatePreparationScript(
        generated.typedByResetMode(buffered),
        candidate,
        width,
        depth,
        buffered
      )
    )
    runYosys(directory, candidateScript, candidate)

    val reference = directory.resolve(s"streamfifocc_57b_reference_$stem.il")
    val referenceScript =
      directory.resolve(s"streamfifocc_57b_prepare_reference_$stem.ys")
    write(
      referenceScript,
      referencePreparationScript(
        generated.concreteByConfiguration((width, depth, buffered)),
        reference,
        width,
        depth,
        buffered
      )
    )
    runYosys(directory, referenceScript, reference)

    PreparedDuts(candidate, reference)
  }

  private def candidatePreparationScript(
      source: Path,
      target: Path,
      width: Int,
      depth: Int,
      buffered: Boolean
  ): String =
    s"""read_verilog -defer ${yosysPath(source)}
       |chparam -set WIDTH $width -set DEPTH $depth ${typedSourceTop(buffered)}
       |hierarchy -check -top ${typedSourceTop(buffered)}
       |setattr -unset keep_hierarchy
       |flatten
       |proc
       |opt
       |memory_dff
       |memory_collect
       |opt_clean
       |check -assert
       |rename -top ${candidatePreparedTop(width, depth, buffered)}
       |write_rtlil ${yosysPath(target)}
       |""".stripMargin

  private def referencePreparationScript(
      source: Path,
      target: Path,
      width: Int,
      depth: Int,
      buffered: Boolean
  ): String =
    s"""read_verilog -defer ${yosysPath(source)}
       |hierarchy -check -top ${concreteSourceTop(width, depth, buffered)}
       |setattr -unset keep_hierarchy
       |flatten
       |proc
       |opt
       |memory_dff
       |memory_collect
       |opt_clean
       |check -assert
       |rename -top ${referencePreparedTop(width, depth, buffered)}
       |write_rtlil ${yosysPath(target)}
       |""".stripMargin

  private def equivalenceMiter(
      configuration: Configuration,
      mutatePopPayload: Boolean
  ): String = {
    val payloadRange = s"[${configuration.width - 1}:0]"
    val mutationMask = BigInt(1) << (configuration.width - 1)
    val comparedPayload =
      if (mutatePopPayload)
        s"(typed_pop_payload ^ ${configuration.width}'h${mutationMask.toString(16)})"
      else "typed_pop_payload"
    // A buffered StreamFifoCC intentionally derives its pop-domain reset from
    // push_reset, so SpinalHDL omits the otherwise unused external pop_reset
    // port from both independently emitted tops.
    val popResetConnection =
      if (configuration.buffered) ""
      else ",\n    .pop_reset(pop_reset)"
    val trafficAssumptions =
      if (mutatePopPayload)
        """      assume(push_valid);
          |      assume(pop_ready);
          |""".stripMargin
      else ""

    s"""module ${miterTop(configuration)} (
       |  input wire push_valid,
       |  input wire $payloadRange push_payload,
       |  input wire pop_ready
       |);
       |  reg [3:0] clock_phase;
       |  reg [2:0] reset_age;
       |  wire push_clk;
       |  wire pop_clk;
       |  wire push_reset;
       |  wire pop_reset;
       |  wire reference_push_ready;
       |  wire reference_pop_valid;
       |  wire $payloadRange reference_pop_payload;
       |  wire [4:0] reference_push_occupancy;
       |  wire [4:0] reference_pop_occupancy;
       |  wire typed_push_ready;
       |  wire typed_pop_valid;
       |  wire $payloadRange typed_pop_payload;
       |  wire $payloadRange typed_pop_payload_compared;
       |  wire [4:0] typed_push_occupancy;
       |  wire [4:0] typed_pop_occupancy;
       |
       |  initial begin
       |    clock_phase = 4'd0;
       |    reset_age = 3'd0;
       |  end
       |
       |  always @($$global_clock) begin
       |    clock_phase <= clock_phase + 4'd1;
       |    if (reset_age != 3'd7)
       |      reset_age <= reset_age + 3'd1;
       |  end
       |
       |  assign push_clk = clock_phase[${configuration.ratio.pushPhaseBit}];
       |  assign pop_clk = clock_phase[${configuration.ratio.popPhaseBit}];
       |  assign push_reset = (reset_age < 3'd3);
       |  assign pop_reset = (reset_age < 3'd4);
       |  assign typed_pop_payload_compared = $comparedPayload;
       |
       |  ${referencePreparedTop(configuration.width, configuration.depth, configuration.buffered)} reference_dut (
       |    .io_pushValid(push_valid),
       |    .io_pushReady(reference_push_ready),
       |    .io_pushPayload(push_payload),
       |    .io_popValid(reference_pop_valid),
       |    .io_popReady(pop_ready),
       |    .io_popPayload(reference_pop_payload),
       |    .io_pushOccupancy(reference_push_occupancy),
       |    .io_popOccupancy(reference_pop_occupancy),
       |    .push_clk(push_clk),
       |    .push_reset(push_reset),
       |    .pop_clk(pop_clk)$popResetConnection
       |  );
       |
       |  ${candidatePreparedTop(configuration.width, configuration.depth, configuration.buffered)} typed_dut (
       |    .io_pushValid(push_valid),
       |    .io_pushReady(typed_push_ready),
       |    .io_pushPayload(push_payload),
       |    .io_popValid(typed_pop_valid),
       |    .io_popReady(pop_ready),
       |    .io_popPayload(typed_pop_payload),
       |    .io_pushOccupancy(typed_push_occupancy),
       |    .io_popOccupancy(typed_pop_occupancy),
       |    .push_clk(push_clk),
       |    .push_reset(push_reset),
       |    .pop_clk(pop_clk)$popResetConnection
       |  );
       |
       |  always @($$global_clock) begin
       |    if (!push_reset && !pop_reset) begin
       |$trafficAssumptions      assert(reference_push_ready == typed_push_ready);
       |      assert(reference_pop_valid == typed_pop_valid);
       |      assert(reference_push_occupancy == typed_push_occupancy);
       |      assert(reference_pop_occupancy == typed_pop_occupancy);
       |      if (reference_pop_valid && typed_pop_valid)
       |        assert(reference_pop_payload == typed_pop_payload_compared);
       |    end
       |  end
       |endmodule
       |""".stripMargin
  }

  private def positiveSby(
      prepared: PreparedDuts,
      miter: Path,
      top: String
  ): String = {
    // An output-only k-induction hypothesis does not relate hidden RAM words:
    // it can therefore start from an unreachable state whose visible FIFO
    // state agrees but whose next readable word differs. PDR proves the safety
    // properties over states reachable from the initialized reset schedule.
    // BufferCC retains hierarchy for synthesis CDC metadata; release that
    // boundary before prep so the proof AIG keeps and flattens its output cones.
    // Latch correlation then merges only SAT/induction-proven equivalent state
    // across the independently generated DUTs before PDR checks every output.
    s"""[options]
       |mode prove
       |expect pass
       |multiclock on
       |timeout 600
       |
       |[engines]
       |abc lcorr; pdr
       |
       |[script]
       |read_rtlil ${prepared.candidate.getFileName}
       |read_rtlil ${prepared.reference.getFileName}
       |read_verilog -formal ${miter.getFileName}
       |hierarchy -check -top $top
       |setattr -unset keep_hierarchy
       |prep -top $top
       |memory_map
       |setundef -undriven -anyseq
       |setundef -init -zero
       |opt_clean
       |check -assert
       |
       |[files]
       |${prepared.candidate.toAbsolutePath}
       |${prepared.reference.toAbsolutePath}
       |${miter.toAbsolutePath}
       |""".stripMargin
  }

  private def mutationSby(
      prepared: PreparedDuts,
      miter: Path,
      top: String
  ): String =
    s"""[options]
       |mode bmc
       |expect fail
       |multiclock on
       |depth 64
       |timeout 180
       |
       |[engines]
       |smtbmc yices
       |
       |[script]
       |read_rtlil ${prepared.candidate.getFileName}
       |read_rtlil ${prepared.reference.getFileName}
       |read_verilog -formal ${miter.getFileName}
       |hierarchy -check -top $top
       |setattr -unset keep_hierarchy
       |prep -top $top
       |memory_map
       |setundef -undriven -anyseq
       |setundef -init -zero
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

    val workDirectory =
      directory.resolve(config.getFileName.toString.stripSuffix(".sby"))
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
      s"ambiguous formal status for ${config.getFileName}: ${statusLines.mkString(" | ")}\n$output"
    )
    val statusTokens = statusLines.head.split("\\s+").toVector
    assert(
      statusTokens.nonEmpty && statusTokens.tail.forall(_.matches("[0-9]+")),
      s"malformed formal status for ${config.getFileName}: ${statusLines.head}\n$output"
    )
    assert(
      statusTokens.head == expectedStatus,
      s"expected $expectedStatus for ${config.getFileName}, received ${statusTokens.head}:\n$output"
    )

    if (requireCounterexample) {
      val files = regularFiles(workDirectory)
      val traces = files.filter { path =>
        path.getFileName.toString.endsWith(".vcd") && Files.size(path) > 0L
      }
      assert(
        traces.nonEmpty,
        s"expected mutation counterexample has no non-empty VCD trace:\n$output"
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
        s"expected FAIL was not an assertion counterexample:\n$output\n$engineLogs"
      )
    }
  }

  private def runYosys(
      directory: Path,
      script: Path,
      expectedOutput: Path
  ): Unit = {
    val (exitCode, output) =
      run(directory, Seq("yosys", "-q", "-s", script.getFileName.toString))
    assert(
      exitCode == 0,
      s"Yosys preprocessing failed for ${script.getFileName}:\n$output"
    )
    assert(
      Files.isRegularFile(expectedOutput) && Files.size(expectedOutput) > 0L,
      s"Yosys produced no RTLIL for ${script.getFileName}:\n$output"
    )
  }

  private def requireFormalTool(
      directory: Path,
      command: Seq[String],
      label: String
  ): Unit = {
    val (exitCode, output) = run(directory, command)
    assert(
      exitCode == 0,
      s"required formal tool $label is unavailable (${command.mkString(" ")}):\n$output"
    )
  }

  private def generationConfig(directory: Path): SpinalConfig =
    SpinalConfig(targetDirectory = directory.toString)

  private def resetMode(buffered: Boolean): String =
    if (buffered) "buffered" else "direct"

  private def typedSourceTop(buffered: Boolean): String =
    if (buffered) "NativeStreamFifoCC57bTypedBuffered"
    else "NativeStreamFifoCC57bTypedDirect"

  private def concreteSourceTop(
      width: Int,
      depth: Int,
      buffered: Boolean
  ): String =
    s"NativeStreamFifoCC57bConcreteW${width}D${depth}" +
      (if (buffered) "Buffered" else "Direct")

  private def concreteCoreSourceTop(
      width: Int,
      depth: Int,
      buffered: Boolean
  ): String =
    s"NativeStreamFifoCC57bConcreteCoreW${width}D${depth}" +
      (if (buffered) "Buffered" else "Direct")

  private def candidatePreparedTop(
      width: Int,
      depth: Int,
      buffered: Boolean
  ): String =
    s"streamfifocc_57b_candidate_w${width}_d${depth}_${resetMode(buffered)}"

  private def referencePreparedTop(
      width: Int,
      depth: Int,
      buffered: Boolean
  ): String =
    s"streamfifocc_57b_reference_w${width}_d${depth}_${resetMode(buffered)}"

  private def configurationStem(configuration: Configuration): String =
    s"w${configuration.width}_d${configuration.depth}_${resetMode(configuration.buffered)}_${configuration.ratio.name}"

  private def miterTop(configuration: Configuration): String =
    s"streamfifocc_57b_miter_${configurationStem(configuration)}"

  private def moduleNames(source: String): Vector[String] =
    ModuleDeclaration.findAllMatchIn(source).map(_.group(1)).toVector

  private def compact(source: String): String =
    source.replaceAll("\\s+", "")

  private def moduleHeader(source: String, top: String): String = {
    val declaration = s"module $top"
    val start = source.indexOf(declaration)
    require(start >= 0, s"missing module declaration $top")
    val end = source.indexOf(");", start)
    require(end >= 0, s"unterminated module declaration $top")
    source.substring(start, end + 2)
  }

  private def yosysPath(path: Path): String = {
    val absolute = path.toAbsolutePath.toString.replace("\\", "/")
    require(
      !absolute.exists(character => character == '\n' || character == '\r'),
      s"formal path contains a line break: $absolute"
    )
    absolute
  }

  private def write(path: Path, value: String): Unit =
    Files.write(path, value.getBytes(StandardCharsets.UTF_8))

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

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

  private def regularFiles(directory: Path): Vector[Path] = {
    if (!Files.exists(directory)) Vector.empty
    else {
      val stream = Files.walk(directory)
      try stream.iterator().asScala.filter(Files.isRegularFile(_)).toVector
      finally stream.close()
    }
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-streamfifocc-57b-formal-")
    try body(directory)
    finally deleteRecursively(directory)
  }

  private def withFormalWorkspace(body: Path => Unit): Unit =
    sys.env.get(FormalWorkspaceEnvironment).filter(_.nonEmpty) match {
      case Some(value) =>
        val directory = Paths.get(value).toAbsolutePath
        Files.createDirectories(directory)
        body(directory)
      case None => withTemporaryDirectory(body)
    }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.exists(path)) {
      val stream = Files.walk(path)
      try {
        stream.iterator().asScala.toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(entry => Files.deleteIfExists(entry))
      } finally stream.close()
    }
  }
}
