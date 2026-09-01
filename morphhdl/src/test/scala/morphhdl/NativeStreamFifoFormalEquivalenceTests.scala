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

/** Independent native-Int reference for every public StreamFifo formal helper.
  * The storage choice is a Scala literal on both proof legs; only the typed leg
  * below retains DEPTH as an elaboration parameter.
  */
final class NativeConcreteStreamFifoFormalHelperHarness(
    depth: Int,
    useVecStorage: Boolean
) extends Component {
  require(Vector(1, 3, 5, 8).contains(depth))
  private val storage = if (useVecStorage) "Vec" else "Mem"
  setDefinitionName(
    s"NativeConcreteStreamFifoFormalHelperHarness${storage}Depth$depth"
  )

  val io = new Bundle {
    val push = slave Stream (Bits(8 bits))
    val pop = master Stream (Bits(8 bits))
    val flush = in Bool ()
    val needle = in Bits (8 bits)
    val occupancy = out UInt (4 bits)
    val lastPush = out Bool ()
    val ramChecks = out Bits (depth bits)
    val contains = out Bool ()
    val count = out UInt ((log2Up(depth + 1) + 1) bits)
    val wordContains = out Bool ()
    val wordCount = out UInt ((log2Up(depth + 1) + 1) bits)
  }

  val fifo = spinal.core.formal.FormalDut {
    new StreamFifo(
      HardType(Bits(8 bits)),
      depth,
      withAsyncRead = useVecStorage,
      withBypass = false,
      allowExtraMsb = true,
      forFMax = false,
      useVec = useVecStorage,
      initPayload = None
    )
  }
  fifo.setDefinitionName(s"ConcreteFormalHelperFifo${storage}Depth$depth")
  fifo.io.push << io.push
  io.pop << fifo.io.pop
  fifo.io.flush := io.flush
  io.occupancy := fifo.io.occupancy.resized
  io.lastPush := fifo.formalCheckLastPush(_.orR)
  io.ramChecks := fifo.formalCheckRam(_.orR).asBits
  io.contains := fifo.formalContains(_.orR)
  io.count := fifo.formalCount(_.orR).resized
  io.wordContains := fifo.formalContains(io.needle)
  io.wordCount := fifo.formalCount(io.needle).resized
}

/** One typed formal-helper definition specialized independently by the formal
  * tool at each admitted DEPTH. This is deliberately not an ElabInt literal
  * wrapper around the native reference above.
  */
final class TypedStreamFifoFormalHelperHarness(
    depth: HdlInt,
    useVecStorage: Boolean
) extends Component {
  private val typedDepth = depth.asElabInt
  private val storage = if (useVecStorage) "Vec" else "Mem"
  setDefinitionName(s"TypedStreamFifoFormalHelperHarness$storage")

  val io = new Bundle {
    val push = slave Stream (Bits(8 bits))
    val pop = master Stream (Bits(8 bits))
    val flush = in Bool ()
    val needle = in Bits (8 bits)
    val occupancy = out UInt (4 bits)
    val lastPush = out Bool ()
    val ramChecks = out Bits (typedDepth bits)
    val contains = out Bool ()
    val count = out UInt (((typedDepth + 1).addressWidth + 1) bits)
    val wordContains = out Bool ()
    val wordCount = out UInt (((typedDepth + 1).addressWidth + 1) bits)
  }

  val fifo = StreamFifo(
    HardType(Bits(8 bits)),
    typedDepth,
    withAsyncRead = useVecStorage,
    withBypass = false,
    allowExtraMsb = true,
    forFMax = false,
    useVec = useVecStorage,
    initPayload = None
  )
  fifo.io.push << io.push
  io.pop << fifo.io.pop
  fifo.io.flush := io.flush
  io.occupancy := fifo.io.occupancy.resized
  io.lastPush := fifo.formalCheckLastPush(_.orR)
  io.ramChecks := fifo.formalCheckRam(_.orR).asBits
  io.contains := fifo.formalContains(_.orR)
  io.count := fifo.formalCount(_.orR).resized
  io.wordContains := fifo.formalContains(io.needle)
  io.wordCount := fifo.formalCount(io.needle).resized
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

  private final case class GeneratedFormalHelperDuts(
      candidateByStorage: Map[Boolean, Path],
      concreteByStorageAndDepth: Map[(Boolean, Int), Path]
  )

  private final case class PreparedFormalHelperDuts(
      mem: PreparedDuts,
      vec: PreparedDuts
  )

  test("formal witnesses are independent native-Int elaborations sharing one Morph definition") {
    withTemporaryDirectory { directory =>
      validateGeneratedDuts(generateDuts(directory))
    }
  }

  test("formal-helper witnesses are independent native-Int and typed Mem Vec elaborations") {
    withTemporaryDirectory { directory =>
      validateGeneratedFormalHelperDuts(generateFormalHelperDuts(directory))
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

  test("typed-specialized formal helpers match native-Int Mem and Vec witnesses at depths 1 3 5 and 8") {
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

      val generated = generateFormalHelperDuts(directory)
      validateGeneratedFormalHelperDuts(generated)
      val preparedByDepth = Depths.map { depth =>
        depth -> PreparedFormalHelperDuts(
          mem = prepareFormalHelperDuts(
            directory,
            generated,
            depth,
            useVecStorage = false
          ),
          vec = prepareFormalHelperDuts(
            directory,
            generated,
            depth,
            useVecStorage = true
          )
        )
      }.toMap

      Depths.foreach { depth =>
        val miter = directory.resolve(
          s"stream_fifo_formal_helper_equivalence_depth_$depth.v"
        )
        write(
          miter,
          formalHelperEquivalenceMiter(
            depth,
            mutateMemCandidateWordContains = false
          )
        )
        val config = directory.resolve(
          s"stream_fifo_formal_helper_equivalence_depth_$depth.sby"
        )
        write(
          config,
          formalHelperPositiveSby(
            preparedByDepth(depth),
            miter,
            formalHelperMiterModule(depth)
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
      val mutationMiter = directory.resolve(
        "stream_fifo_formal_helper_equivalence_depth_3_mutation.v"
      )
      write(
        mutationMiter,
        formalHelperEquivalenceMiter(
          mutationDepth,
          mutateMemCandidateWordContains = true
        )
      )
      val mutationConfig = directory.resolve(
        "stream_fifo_formal_helper_equivalence_depth_3_mutation.sby"
      )
      write(
        mutationConfig,
        formalHelperMutationSby(
          preparedByDepth(mutationDepth),
          mutationMiter,
          formalHelperMiterModule(mutationDepth)
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

  private def generateFormalHelperDuts(
      directory: Path
  ): GeneratedFormalHelperDuts = {
    val candidateByStorage = Vector(false, true).map { useVecStorage =>
      val storage = storageStem(useVecStorage)
      val candidateDirectory =
        directory.resolve(s"formal-helper-typed-$storage")
      Files.createDirectories(candidateDirectory)
      val config = synchronousResetConfig(candidateDirectory)
      config.netlistFileName = s"stream_fifo_formal_helper_typed_$storage.v"
      val depth = HdlInt.param(
        "DEPTH",
        default = BigInt(5),
        min = BigInt(1),
        max = BigInt(8)
      )
      MorphVerilog(config) {
        new TypedStreamFifoFormalHelperHarness(depth, useVecStorage)
      }
      useVecStorage -> candidateDirectory.resolve(config.netlistFileName)
    }.toMap

    val concreteByStorageAndDepth = (for {
      useVecStorage <- Vector(false, true)
      depth <- Depths
    } yield {
      val storage = storageStem(useVecStorage)
      val concreteDirectory =
        directory.resolve(s"formal-helper-native-$storage-depth-$depth")
      Files.createDirectories(concreteDirectory)
      val config = synchronousResetConfig(concreteDirectory)
      config.netlistFileName = s"stream_fifo_formal_helper_native_${storage}_depth_$depth.v"
      SpinalVerilog(config) {
        new NativeConcreteStreamFifoFormalHelperHarness(
          depth,
          useVecStorage
        )
      }
      (useVecStorage -> depth) -> concreteDirectory.resolve(config.netlistFileName)
    }).toMap

    GeneratedFormalHelperDuts(
      candidateByStorage,
      concreteByStorageAndDepth
    )
  }

  private def validateGeneratedFormalHelperDuts(
      generated: GeneratedFormalHelperDuts
  ): Unit = {
    assert(generated.candidateByStorage.keySet == Set(false, true))
    assert(
      generated.concreteByStorageAndDepth.keySet ==
        (for {
          useVecStorage <- Set(false, true)
          depth <- Depths
        } yield useVecStorage -> depth)
    )

    generated.candidateByStorage.foreach { case (useVecStorage, path) =>
      val storage = storageClassStem(useVecStorage)
      val candidate = read(path)
      assert(candidate.contains("parameter integer DEPTH = 5"), candidate)
      assert(
        candidate.contains(s"module TypedStreamFifoFormalHelperHarness$storage #("),
        candidate
      )
      assert(candidate.contains("formal_last_push"), candidate)
      assert(candidate.contains("formal_ram_check"), candidate)
      assert(candidate.contains("typed_formal_ram_mask_one"), candidate)
      assert(candidate.contains("morphhdl_finite_fold_index_"), candidate)
      assert(!candidate.contains("formal_ram_mask_view"), candidate)
      assert(
        """formal_ram_mask(?:_[0-9]+)?\s*\[\s*stream_fifo_formal_ram_mask_index_[A-Za-z0-9_$]+\s*\+:\s*1\s*\]""".r
          .findFirstIn(candidate)
          .nonEmpty,
        candidate
      )
      assert(
        """formal_ram_mask(?:_[0-9]+)?\s*\[\s*0\s*\]""".r
          .findFirstIn(candidate)
          .isEmpty,
        candidate
      )
      assert(
        """(?m)^\s*(?:wire|reg)\s+(?:\[[^\]]+\]\s+)?[A-Za-z_][A-Za-z0-9_$]*formal_ram_check[A-Za-z0-9_$]*\s*\[""".r
          .findFirstIn(candidate)
          .isEmpty,
        candidate
      )
      assert(
        candidate.contains("typed_formal_word_count") &&
          candidate.contains("typed_formal_predicate_count"),
        candidate
      )
    }

    generated.concreteByStorageAndDepth.foreach { case ((useVecStorage, depth), path) =>
      val storage = storageClassStem(useVecStorage)
      val concrete = read(path)
      assert(!concrete.contains("parameter integer DEPTH"), concrete)
      assert(
        concrete.contains(
          s"module NativeConcreteStreamFifoFormalHelperHarness${storage}Depth$depth"
        ),
        concrete
      )
      assert(
        depth == 1 ||
          ("\\[" + (depth - 1) + ":0\\]\\s+io_ramChecks").r
            .findFirstIn(concrete)
            .nonEmpty,
        concrete
      )
    }

    assert(
      generated.concreteByStorageAndDepth.values.map(read).toSet.size ==
        Depths.size * 2,
      "formal-helper references were not eight independent native-Int elaborations"
    )
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

  private def prepareFormalHelperDuts(
      directory: Path,
      generated: GeneratedFormalHelperDuts,
      depth: Int,
      useVecStorage: Boolean
  ): PreparedDuts = {
    val storage = storageStem(useVecStorage)
    val candidate = directory.resolve(
      s"formal_helper_${storage}_candidate_depth_$depth.il"
    )
    val candidateScript = directory.resolve(
      s"prepare_formal_helper_${storage}_candidate_depth_$depth.ys"
    )
    write(
      candidateScript,
      s"""read_verilog -defer ${yosysPath(generated.candidateByStorage(useVecStorage))}
         |chparam -set DEPTH $depth ${typedFormalHelperTop(useVecStorage)}
         |hierarchy -check -top ${typedFormalHelperTop(useVecStorage)}
         |flatten
         |proc
         |opt_clean
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top ${candidateFormalHelperTop(useVecStorage, depth)}
         |write_rtlil ${yosysPath(candidate)}
         |""".stripMargin
    )
    runYosys(directory, candidateScript, candidate)

    val concrete = directory.resolve(
      s"formal_helper_${storage}_reference_depth_$depth.il"
    )
    val concreteScript = directory.resolve(
      s"prepare_formal_helper_${storage}_reference_depth_$depth.ys"
    )
    write(
      concreteScript,
      s"""read_verilog -defer ${yosysPath(generated.concreteByStorageAndDepth(useVecStorage -> depth))}
         |hierarchy -check -top ${nativeFormalHelperTop(useVecStorage, depth)}
         |flatten
         |proc
         |opt_clean
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top ${concreteFormalHelperTop(useVecStorage, depth)}
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

  private def formalHelperEquivalenceMiter(
      depth: Int,
      mutateMemCandidateWordContains: Boolean
  ): String = {
    val countWidth = log2Up(depth + 1) + 1
    val memCandidateWordContains =
      if (mutateMemCandidateWordContains)
        "(mem_candidate_word_contains ^ 1'b1)"
      else "mem_candidate_word_contains"

    def wires(prefix: String): String =
      s"""  wire ${prefix}_push_ready;
         |  wire ${prefix}_pop_valid;
         |  wire [7:0] ${prefix}_pop_payload;
         |  wire [3:0] ${prefix}_occupancy;
         |  wire ${prefix}_last_push;
         |  wire [${depth - 1}:0] ${prefix}_ram_checks;
         |  wire ${prefix}_contains;
         |  wire [${countWidth - 1}:0] ${prefix}_count;
         |  wire ${prefix}_word_contains;
         |  wire [${countWidth - 1}:0] ${prefix}_word_count;
         |""".stripMargin

    def instance(top: String, name: String, prefix: String): String =
      s"""  $top $name (
         |    .io_push_valid(push_valid),
         |    .io_push_ready(${prefix}_push_ready),
         |    .io_push_payload(push_payload),
         |    .io_pop_valid(${prefix}_pop_valid),
         |    .io_pop_ready(pop_ready),
         |    .io_pop_payload(${prefix}_pop_payload),
         |    .io_flush(flush),
         |    .io_needle(needle),
         |    .io_occupancy(${prefix}_occupancy),
         |    .io_lastPush(${prefix}_last_push),
         |    .io_ramChecks(${prefix}_ram_checks),
         |    .io_contains(${prefix}_contains),
         |    .io_count(${prefix}_count),
         |    .io_wordContains(${prefix}_word_contains),
         |    .io_wordCount(${prefix}_word_count),
         |    .clk(clk),
         |    .reset(reset)
         |  );
         |""".stripMargin

    s"""module ${formalHelperMiterModule(depth)} (
       |  input wire clk,
       |  input wire reset,
       |  input wire push_valid,
       |  input wire [7:0] push_payload,
       |  input wire pop_ready,
       |  input wire flush,
       |  input wire [7:0] needle
       |);
       |${wires("mem_reference")}${wires("mem_candidate")}${wires("vec_reference")}${wires("vec_candidate")}
       |${instance(concreteFormalHelperTop(useVecStorage = false, depth = depth), "mem_reference_dut", "mem_reference")}
       |${instance(
        candidateFormalHelperTop(useVecStorage = false, depth = depth),
        "mem_candidate_dut",
        "mem_candidate"
      )}
       |${instance(concreteFormalHelperTop(useVecStorage = true, depth = depth), "vec_reference_dut", "vec_reference")}
       |${instance(candidateFormalHelperTop(useVecStorage = true, depth = depth), "vec_candidate_dut", "vec_candidate")}
       |  always @* begin
       |    if ($$initstate)
       |      assume(reset);
       |    if (!$$initstate) begin
       |      assert(mem_reference_push_ready == mem_candidate_push_ready);
       |      assert(mem_reference_pop_valid == mem_candidate_pop_valid);
       |      assert(mem_reference_occupancy == mem_candidate_occupancy);
       |      if (mem_reference_pop_valid && mem_candidate_pop_valid)
       |        assert(mem_reference_pop_payload == mem_candidate_pop_payload);
       |      if ((mem_reference_occupancy != 0) && (mem_candidate_occupancy != 0))
       |        assert(mem_reference_last_push == mem_candidate_last_push);
       |      assert(mem_reference_ram_checks == mem_candidate_ram_checks);
       |      assert(mem_reference_contains == mem_candidate_contains);
       |      assert(mem_reference_count == mem_candidate_count);
       |      assert(mem_reference_word_contains == $memCandidateWordContains);
       |      assert(mem_reference_word_count == mem_candidate_word_count);
       |
       |      assert(vec_reference_push_ready == vec_candidate_push_ready);
       |      assert(vec_reference_pop_valid == vec_candidate_pop_valid);
       |      assert(vec_reference_occupancy == vec_candidate_occupancy);
       |      if (vec_reference_pop_valid && vec_candidate_pop_valid)
       |        assert(vec_reference_pop_payload == vec_candidate_pop_payload);
       |      if ((vec_reference_occupancy != 0) && (vec_candidate_occupancy != 0))
       |        assert(vec_reference_last_push == vec_candidate_last_push);
       |      assert(vec_reference_ram_checks == vec_candidate_ram_checks);
       |      assert(vec_reference_contains == vec_candidate_contains);
       |      assert(vec_reference_count == vec_candidate_count);
       |      assert(vec_reference_word_contains == vec_candidate_word_contains);
       |      assert(vec_reference_word_count == vec_candidate_word_count);
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

  private def formalHelperPositiveSby(
      prepared: PreparedFormalHelperDuts,
      miter: Path,
      top: String
  ): String =
    formalHelperSby(
      prepared,
      miter,
      top,
      mode = "prove",
      depth = None,
      expected = "pass",
      engine = "abc pdr",
      timeout = 600
    )

  private def formalHelperMutationSby(
      prepared: PreparedFormalHelperDuts,
      miter: Path,
      top: String
  ): String =
    formalHelperSby(
      prepared,
      miter,
      top,
      mode = "bmc",
      depth = Some(6),
      expected = "fail",
      engine = "smtbmc yices",
      timeout = 120
    )

  private def formalHelperSby(
      prepared: PreparedFormalHelperDuts,
      miter: Path,
      top: String,
      mode: String,
      depth: Option[Int],
      expected: String,
      engine: String,
      timeout: Int
  ): String = {
    val depthOption = depth.map(value => s"depth $value\n").getOrElse("")
    val inputs = Vector(
      prepared.mem.candidate,
      prepared.mem.concrete,
      prepared.vec.candidate,
      prepared.vec.concrete
    )
    val reads = inputs
      .map(path => s"read_rtlil ${path.getFileName}")
      .mkString("\n")
    val files = (inputs :+ miter)
      .map(_.toAbsolutePath.toString)
      .mkString("\n")
    s"""[options]
       |mode $mode
       |${depthOption}expect $expected
       |multiclock off
       |timeout $timeout
       |
       |[engines]
       |$engine
       |
       |[script]
       |$reads
       |read_verilog -formal ${miter.getFileName}
       |hierarchy -check -top $top
       |prep -top $top
       |memory_map
       |setundef -undriven -anyseq
       |opt_clean
       |check -assert
       |
       |[files]
       |$files
       |""".stripMargin
  }

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

  private def storageStem(useVecStorage: Boolean): String =
    if (useVecStorage) "vec" else "mem"

  private def storageClassStem(useVecStorage: Boolean): String =
    if (useVecStorage) "Vec" else "Mem"

  private def typedFormalHelperTop(useVecStorage: Boolean): String =
    s"TypedStreamFifoFormalHelperHarness${storageClassStem(useVecStorage)}"

  private def nativeFormalHelperTop(
      useVecStorage: Boolean,
      depth: Int
  ): String =
    s"NativeConcreteStreamFifoFormalHelperHarness${storageClassStem(useVecStorage)}Depth$depth"

  private def candidateFormalHelperTop(
      useVecStorage: Boolean,
      depth: Int
  ): String =
    s"TypedStreamFifoFormalHelper${storageClassStem(useVecStorage)}CandidateDepth$depth"

  private def concreteFormalHelperTop(
      useVecStorage: Boolean,
      depth: Int
  ): String =
    s"NativeStreamFifoFormalHelper${storageClassStem(useVecStorage)}ReferenceDepth$depth"

  private def formalHelperMiterModule(depth: Int): String =
    s"NativeStreamFifoFormalHelperMiterDepth$depth"

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
