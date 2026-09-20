package spinal.core {
  import spinal.lib.{Counter, Flow, Stream}

  /** Independent formal witnesses for the typed primitive-closure path.
    *
    * The MorphHDL leg keeps WIDTH and DEPTH symbolic through native resize,
    * Mem, Counter and child-formal APIs.  Each reference leg is elaborated
    * from ordinary Int/BigInt calls and has disjoint module names, so no
    * parameterized artifact can be reused as the reference.
    */
  object TypedPrimitiveClosureFormalEquivalenceFixture {
    final case class Witness(width: Int, depth: Int) {
      val suffix: String = s"W${width}D$depth"
    }

    final class TypedChild(width: ElabInt) extends Component {
      setDefinitionName("TypedPrimitiveClosureFormalChild")

      val din = in(Bits(width bits)).setName("din")
      val dout = out(Bits(width bits)).setName("dout")
      dout := ~din
    }

    final class TypedTop(width: ElabInt, depth: ElabInt) extends Component {
      setDefinitionName("TypedPrimitiveClosureFormalTop")

      val writeEnable = in(Bool()).setName("write_enable")
      val readEnable = in(Bool()).setName("read_enable")
      val increment = in(Bool()).setName("increment")
      val decrement = in(Bool()).setName("decrement")
      val address = in(UInt(3 bits)).setName("address")
      val writeData = in(Bits(16 bits)).setName("write_data")
      val hierarchyInput = in(Bits(width bits)).setName("hierarchy_input")
      val streamInValid = in(Bool()).setName("stream_in_valid")
      val streamInReady = out(Bool()).setName("stream_in_ready")
      val streamInPayload = in(Bits(width bits)).setName("stream_in_payload")
      val streamOutValid = out(Bool()).setName("stream_out_valid")
      val streamOutReady = in(Bool()).setName("stream_out_ready")
      val streamOutPayload = out(Bits(width bits)).setName("stream_out_payload")
      val flowInValid = in(Bool()).setName("flow_in_valid")
      val flowInPayload = in(Bits(width bits)).setName("flow_in_payload")
      val flowOutValid = out(Bool()).setName("flow_out_valid")
      val flowOutPayload = out(Bits(width bits)).setName("flow_out_payload")

      val readData = out(Bits(16 bits)).setName("read_data")
      val count = out(UInt(3 bits)).setName("count")
      val counterComplete = out(Bool()).setName("counter_complete")
      val rangeCount = out(UInt(4 bits)).setName("range_count")
      val rangeCounterComplete = out(Bool()).setName("range_counter_complete")
      val downCount = out(UInt(3 bits)).setName("down_count")
      val downCounterComplete = out(Bool()).setName("down_counter_complete")
      val bothCount = out(UInt(3 bits)).setName("both_count")
      val bothCounterComplete = out(Bool()).setName("both_counter_complete")
      val hierarchyOutput = out(Bits(width bits)).setName("hierarchy_output")

      val memory = Mem(Bits(width bits), depth).setName("memory")
      // A fixed three-bit top-level ABI covers the complete DEPTH domain.  The
      // native Mem port normalizer remains authoritative for its internal port.
      val memoryAddress = address
      val memoryWriteData = writeData
        .resize(width)
        .setName("memory_write_data")
      val readWord = memory.readSync(
        memoryAddress,
        enable = readEnable,
        readUnderWrite = readFirst
      )
      memory.write(
        memoryAddress,
        memoryWriteData,
        enable = writeEnable
      )

      // Native memories intentionally have unconstrained power-up contents.
      // This fixed-capacity validity mask is synchronously reset and exposes a
      // word only after the shared write stream has initialized that address.
      val validSlots = Reg(Bits(8 bits)) init (0)
      when(writeEnable) {
        validSlots(address) := True
      }
      val readWasValid = RegNextWhen(validSlots(address), readEnable) init (False)
      readData := Mux(readWasValid, readWord.resize(16), B(0, 16 bits))

      val counter = Counter(depth, increment)
      count := counter.value.resize(3)
      counterComplete := counter.willComplete

      // Exercise the native inclusive start/end overload independently of the
      // zero-based state-count entry point. Across the admitted DEPTH domain
      // this is the exact symbolic interval [2, DEPTH + 1].
      val rangeCounter = Counter(ElabInt.literal(2), depth + 1, increment)
      rangeCount := rangeCounter.value.resize(4)
      rangeCounterComplete := rangeCounter.willComplete

      // Exercise both remaining typed direction factories.  DEPTH=8 takes the
      // natural power-of-two path, while DEPTH=3 and DEPTH=5 take the compared
      // wrap path.  One symbolic definition therefore proves that the native
      // bidirectional selection is controlled by ElabBool rather than its
      // default witness.
      val downCounter = Counter.down(depth)
      when(decrement) {
        downCounter.decrement()
      }
      downCount := downCounter.value.resize(3)
      downCounterComplete := downCounter.willComplete

      val bothCounter = Counter.both(depth)
      when(increment) {
        bothCounter.increment()
      }
      when(decrement) {
        bothCounter.decrement()
      }
      bothCount := bothCounter.value.resize(3)
      bothCounterComplete := bothCounter.willComplete

      // Direct typed Stream/Flow users keep the ordinary pipe algorithms.  The
      // miter compares their handshake and valid-qualified payload behavior to
      // independent native concrete elaborations at every width witness.
      val streamSource = Stream(Bits(width bits))
      streamSource.valid := streamInValid
      streamSource.payload := streamInPayload
      streamInReady := streamSource.ready
      val streamPipe = streamSource.m2sPipe().s2mPipe().halfPipe()
      streamPipe.ready := streamOutReady
      streamOutValid := streamPipe.valid
      streamOutPayload := streamPipe.payload

      val flowSource = Flow(Bits(width bits))
      flowSource.valid := flowInValid
      flowSource.payload := flowInPayload
      val flowPipe = flowSource.m2sPipe()
      flowOutValid := flowPipe.valid
      flowOutPayload := flowPipe.payload

      val child = ElabFormalComponent
        .parameter(
          actual = width,
          name = "CHILD_WIDTH",
          minimum = BigInt(1),
          maximum = BigInt(16)
        )(childWidth => new TypedChild(childWidth))
        .setName("formal_child")
      child.din := hierarchyInput
      hierarchyOutput := child.dout
    }

    final class ConcreteChild(width: Int, suffix: String) extends Component {
      setDefinitionName(s"ConcretePrimitiveClosureFormalChild$suffix")

      val din = in(Bits(width bits)).setName("din")
      val dout = out(Bits(width bits)).setName("dout")
      dout := ~din
    }

    final class ConcreteTop(witness: Witness) extends Component {
      setDefinitionName(s"ConcretePrimitiveClosureFormalTop${witness.suffix}")

      val writeEnable = in(Bool()).setName("write_enable")
      val readEnable = in(Bool()).setName("read_enable")
      val increment = in(Bool()).setName("increment")
      val decrement = in(Bool()).setName("decrement")
      val address = in(UInt(3 bits)).setName("address")
      val writeData = in(Bits(16 bits)).setName("write_data")
      val hierarchyInput = in(Bits(witness.width bits)).setName("hierarchy_input")
      val streamInValid = in(Bool()).setName("stream_in_valid")
      val streamInReady = out(Bool()).setName("stream_in_ready")
      val streamInPayload = in(Bits(witness.width bits)).setName("stream_in_payload")
      val streamOutValid = out(Bool()).setName("stream_out_valid")
      val streamOutReady = in(Bool()).setName("stream_out_ready")
      val streamOutPayload = out(Bits(witness.width bits)).setName("stream_out_payload")
      val flowInValid = in(Bool()).setName("flow_in_valid")
      val flowInPayload = in(Bits(witness.width bits)).setName("flow_in_payload")
      val flowOutValid = out(Bool()).setName("flow_out_valid")
      val flowOutPayload = out(Bits(witness.width bits)).setName("flow_out_payload")

      val readData = out(Bits(16 bits)).setName("read_data")
      val count = out(UInt(3 bits)).setName("count")
      val counterComplete = out(Bool()).setName("counter_complete")
      val rangeCount = out(UInt(4 bits)).setName("range_count")
      val rangeCounterComplete = out(Bool()).setName("range_counter_complete")
      val downCount = out(UInt(3 bits)).setName("down_count")
      val downCounterComplete = out(Bool()).setName("down_counter_complete")
      val bothCount = out(UInt(3 bits)).setName("both_count")
      val bothCounterComplete = out(Bool()).setName("both_counter_complete")
      val hierarchyOutput = out(Bits(witness.width bits)).setName("hierarchy_output")

      val memory = Mem(Bits(witness.width bits), witness.depth).setName("memory")
      // The external ABI is sized for the largest tested witness.  An ordinary
      // concrete Mem keeps its authoritative exact native address width, so the
      // independent reference explicitly projects the shared ABI to that width.
      val memoryAddress = address.resize(memory.addressWidth)
      val readWord = memory.readSync(
        memoryAddress,
        enable = readEnable,
        readUnderWrite = readFirst
      )
      memory.write(
        memoryAddress,
        writeData.resize(witness.width),
        enable = writeEnable
      )

      val validSlots = Reg(Bits(8 bits)) init (0)
      when(writeEnable) {
        validSlots(address) := True
      }
      val readWasValid = RegNextWhen(validSlots(address), readEnable) init (False)
      readData := Mux(readWasValid, readWord.resize(16), B(0, 16 bits))

      val counter = Counter(BigInt(witness.depth), increment)
      count := counter.value.resize(3)
      counterComplete := counter.willComplete

      val rangeCounter = Counter(BigInt(2), BigInt(witness.depth + 1), increment)
      rangeCount := rangeCounter.value.resize(4)
      rangeCounterComplete := rangeCounter.willComplete

      val downCounter = Counter.down(BigInt(witness.depth))
      when(decrement) {
        downCounter.decrement()
      }
      downCount := downCounter.value.resize(3)
      downCounterComplete := downCounter.willComplete

      val bothCounter = Counter.both(BigInt(witness.depth))
      when(increment) {
        bothCounter.increment()
      }
      when(decrement) {
        bothCounter.decrement()
      }
      bothCount := bothCounter.value.resize(3)
      bothCounterComplete := bothCounter.willComplete

      val streamSource = Stream(Bits(witness.width bits))
      streamSource.valid := streamInValid
      streamSource.payload := streamInPayload
      streamInReady := streamSource.ready
      val streamPipe = streamSource.m2sPipe().s2mPipe().halfPipe()
      streamPipe.ready := streamOutReady
      streamOutValid := streamPipe.valid
      streamOutPayload := streamPipe.payload

      val flowSource = Flow(Bits(witness.width bits))
      flowSource.valid := flowInValid
      flowSource.payload := flowInPayload
      val flowPipe = flowSource.m2sPipe()
      flowOutValid := flowPipe.valid
      flowOutPayload := flowPipe.payload

      val child = new ConcreteChild(witness.width, witness.suffix)
        .setName("formal_child")
      child.din := hierarchyInput
      hierarchyOutput := child.dout
    }
  }
}

package morphhdl {
  import java.nio.charset.StandardCharsets
  import java.nio.file.{Files, Path, Paths}

  import scala.collection.JavaConverters._
  import scala.sys.process.{Process, ProcessLogger}

  import org.scalatest.funsuite.AnyFunSuite

  import morphhdl.frontend.HdlInt
  import spinal.core._

  class TypedPrimitiveClosureFormalEquivalenceTests extends AnyFunSuite {
    import TypedPrimitiveClosureFormalEquivalenceFixture._

    private val FormalGateEnvironment =
      "MORPHDL_RUN_TYPED_PRIMITIVE_CLOSURE_FORMAL_EQUIVALENCE"
    private val FormalWorkspaceEnvironment =
      "MORPHDL_TYPED_PRIMITIVE_FORMAL_WORKSPACE"
    private val ParameterizedFile = "typed_primitive_closure_parameterized.v"

    private val Witnesses = Vector(
      Witness(width = 5, depth = 1),
      Witness(width = 8, depth = 3),
      Witness(width = 13, depth = 5),
      Witness(width = 16, depth = 8)
    )

    private val ModuleDeclaration =
      """(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b""".r

    private final case class GeneratedDuts(
        parameterized: Path,
        concreteByWitness: Map[Witness, Path]
    )

    private final case class PreparedDuts(candidate: Path, concrete: Path)

    test("formal witnesses are independent native elaborations sharing one typed definition") {
      withTemporaryDirectory { directory =>
        validateGeneratedDuts(generateDuts(directory))
      }
    }

    test("typed primitive closure is formally equivalent with a genuine mutation control") {
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
        val prepared = Witnesses.map { witness =>
          witness -> prepareDuts(directory, generated, witness)
        }.toMap

        Witnesses.foreach { witness =>
          val miter = directory.resolve(
            s"typed_primitive_closure_${witness.suffix}_equivalence.v"
          )
          write(miter, equivalenceMiter(witness, mutateCandidateCount = false))
          val config = directory.resolve(
            s"typed_primitive_closure_${witness.suffix}_equivalence.sby"
          )
          write(
            config,
            positiveSby(prepared(witness), miter, miterModule(witness))
          )
          runSby(
            directory,
            config,
            expectedStatus = "PASS",
            requireCounterexample = false
          )
        }

        val mutationWitness = Witnesses.find(_.depth == 3).get
        val mutationMiter = directory.resolve(
          s"typed_primitive_closure_${mutationWitness.suffix}_mutation.v"
        )
        write(
          mutationMiter,
          equivalenceMiter(mutationWitness, mutateCandidateCount = true)
        )
        val mutationConfig = directory.resolve(
          s"typed_primitive_closure_${mutationWitness.suffix}_mutation.sby"
        )
        write(
          mutationConfig,
          mutationSby(
            prepared(mutationWitness),
            mutationMiter,
            miterModule(mutationWitness)
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
      val width = HdlInt
        .param(
          "WIDTH",
          default = BigInt(8),
          min = BigInt(1),
          max = BigInt(16)
        )
        .asElabInt
      val depth = HdlInt
        .param(
          "DEPTH",
          default = BigInt(5),
          min = BigInt(1),
          max = BigInt(8)
        )
        .asElabInt
      MorphVerilog(parameterizedConfig) {
        new TypedTop(width, depth)
      }
      val parameterized = parameterizedDirectory.resolve(ParameterizedFile)

      val concreteByWitness = Witnesses.map { witness =>
        val concreteDirectory =
          directory.resolve(s"concrete-${witness.suffix.toLowerCase}")
        Files.createDirectories(concreteDirectory)
        val file = s"typed_primitive_closure_concrete_${witness.suffix}.v"
        val concreteConfig = synchronousResetConfig(concreteDirectory)
        concreteConfig.netlistFileName = file
        SpinalVerilog(concreteConfig) {
          new ConcreteTop(witness)
        }
        witness -> concreteDirectory.resolve(file)
      }.toMap

      GeneratedDuts(parameterized, concreteByWitness)
    }

    private def validateGeneratedDuts(generated: GeneratedDuts): Unit = {
      val parameterized = read(generated.parameterized)
      val compact = parameterized.replaceAll("\\s+", "")
      assert(parameterized.contains("parameter integer WIDTH = 8"))
      assert(parameterized.contains("parameter integer DEPTH = 5"))
      assert(parameterized.contains("parameter integer CHILD_WIDTH = 8"))
      assert(parameterized.contains("module TypedPrimitiveClosureFormalTop #("))
      assert(parameterized.contains("module TypedPrimitiveClosureFormalChild #("))
      assert(compact.contains(".CHILD_WIDTH(WIDTH)"), parameterized)
      assert(compact.contains("reg[WIDTH-1:0]memory[0:DEPTH-1];"), parameterized)
      assert(!compact.contains("reg[(WIDTH*DEPTH)-1:0]memory;"), parameterized)
      Vector(
        "stream_in_payload",
        "stream_out_payload",
        "flow_in_payload",
        "flow_out_payload"
      ).foreach { name =>
        assert(compact.contains(s"[WIDTH-1:0]$name"), parameterized)
      }
      Vector(
        "down_count",
        "down_counter_complete",
        "both_count",
        "both_counter_complete"
      ).foreach(name => assert(parameterized.contains(name), parameterized))
      assert(!parameterized.contains("NativeIntShadow"))
      assert(
        moduleNames(parameterized).toSet == Set(
          "TypedPrimitiveClosureFormalTop",
          "TypedPrimitiveClosureFormalChild"
        )
      )

      val concreteSources = generated.concreteByWitness.toVector.map { case (witness, path) =>
        val source = read(path)
        assert(!source.contains("parameter integer WIDTH"))
        assert(!source.contains("parameter integer DEPTH"))
        assert(!source.contains("parameter integer CHILD_WIDTH"))
        val expected = Set(
          s"ConcretePrimitiveClosureFormalTop${witness.suffix}",
          s"ConcretePrimitiveClosureFormalChild${witness.suffix}"
        )
        assert(
          moduleNames(source).toSet == expected,
          s"Concrete ${witness.suffix} module inventory was ${moduleNames(source).sorted.mkString(", ")}"
        )
        source
      }
      assert(
        concreteSources.toSet.size == Witnesses.size,
        "Concrete references were not independently specialized"
      )
      val concreteModules = concreteSources.flatMap(moduleNames).toSet
      assert(
        concreteModules.intersect(moduleNames(parameterized).toSet).isEmpty,
        "Concrete and typed DUT legs share a module definition name"
      )
    }

    private def prepareDuts(
        directory: Path,
        generated: GeneratedDuts,
        witness: Witness
    ): PreparedDuts = {
      val candidate = directory.resolve(
        s"typed_primitive_candidate_${witness.suffix}.il"
      )
      val candidateScript = directory.resolve(
        s"prepare_typed_primitive_candidate_${witness.suffix}.ys"
      )
      write(
        candidateScript,
        s"""read_verilog -defer ${yosysPath(generated.parameterized)}
         |chparam -set WIDTH ${witness.width} -set DEPTH ${witness.depth} TypedPrimitiveClosureFormalTop
         |hierarchy -check -top TypedPrimitiveClosureFormalTop
         |flatten
         |proc
         |opt_clean
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top ${candidateFormalTop(witness)}
         |write_rtlil ${yosysPath(candidate)}
         |""".stripMargin
      )
      runYosys(directory, candidateScript, candidate)

      val concrete = directory.resolve(
        s"typed_primitive_reference_${witness.suffix}.il"
      )
      val concreteScript = directory.resolve(
        s"prepare_typed_primitive_reference_${witness.suffix}.ys"
      )
      write(
        concreteScript,
        s"""read_verilog -defer ${yosysPath(generated.concreteByWitness(witness))}
         |hierarchy -check -top ConcretePrimitiveClosureFormalTop${witness.suffix}
         |flatten
         |proc
         |opt_clean
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top ${concreteFormalTop(witness)}
         |write_rtlil ${yosysPath(concrete)}
         |""".stripMargin
      )
      runYosys(directory, concreteScript, concrete)

      PreparedDuts(candidate, concrete)
    }

    private def equivalenceMiter(
        witness: Witness,
        mutateCandidateCount: Boolean
    ): String = {
      val candidateCount =
        if (mutateCandidateCount) "(morph_count_raw ^ 3'b001)"
        else "morph_count_raw"

      s"""module ${miterModule(witness)} (
       |  input wire clk,
       |  input wire reset,
       |  input wire write_enable,
       |  input wire read_enable,
       |  input wire increment,
       |  input wire decrement,
       |  input wire [2:0] address,
       |  input wire [15:0] write_data,
       |  input wire [${witness.width - 1}:0] hierarchy_input,
       |  input wire stream_in_valid,
       |  input wire [${witness.width - 1}:0] stream_in_payload,
       |  input wire stream_out_ready,
       |  input wire flow_in_valid,
       |  input wire [${witness.width - 1}:0] flow_in_payload
       |);
       |  wire [15:0] concrete_read_data;
       |  wire [2:0] concrete_count;
       |  wire concrete_counter_complete;
       |  wire [3:0] concrete_range_count;
       |  wire concrete_range_counter_complete;
       |  wire [2:0] concrete_down_count;
       |  wire concrete_down_counter_complete;
       |  wire [2:0] concrete_both_count;
       |  wire concrete_both_counter_complete;
       |  wire [${witness.width - 1}:0] concrete_hierarchy_output;
       |  wire concrete_stream_in_ready;
       |  wire concrete_stream_out_valid;
       |  wire [${witness.width - 1}:0] concrete_stream_out_payload;
       |  wire concrete_flow_out_valid;
       |  wire [${witness.width - 1}:0] concrete_flow_out_payload;
       |  wire [15:0] morph_read_data;
       |  wire [2:0] morph_count_raw;
       |  wire [2:0] morph_count_compared;
       |  wire morph_counter_complete;
       |  wire [3:0] morph_range_count;
       |  wire morph_range_counter_complete;
       |  wire [2:0] morph_down_count;
       |  wire morph_down_counter_complete;
       |  wire [2:0] morph_both_count;
       |  wire morph_both_counter_complete;
       |  wire [${witness.width - 1}:0] morph_hierarchy_output;
       |  wire morph_stream_in_ready;
       |  wire morph_stream_out_valid;
       |  wire [${witness.width - 1}:0] morph_stream_out_payload;
       |  wire morph_flow_out_valid;
       |  wire [${witness.width - 1}:0] morph_flow_out_payload;
       |
       |  assign morph_count_compared = $candidateCount;
       |
       |  ${concreteFormalTop(witness)} concrete_dut (
       |    .write_enable(write_enable),
       |    .read_enable(read_enable),
       |    .increment(increment),
       |    .decrement(decrement),
       |    .address(address),
       |    .write_data(write_data),
       |    .hierarchy_input(hierarchy_input),
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
       |    .read_data(concrete_read_data),
       |    .count(concrete_count),
       |    .counter_complete(concrete_counter_complete),
       |    .range_count(concrete_range_count),
       |    .range_counter_complete(concrete_range_counter_complete),
       |    .down_count(concrete_down_count),
       |    .down_counter_complete(concrete_down_counter_complete),
       |    .both_count(concrete_both_count),
       |    .both_counter_complete(concrete_both_counter_complete),
       |    .hierarchy_output(concrete_hierarchy_output),
       |    .clk(clk),
       |    .reset(reset)
       |  );
       |
       |  ${candidateFormalTop(witness)} morph_dut (
       |    .write_enable(write_enable),
       |    .read_enable(read_enable),
       |    .increment(increment),
       |    .decrement(decrement),
       |    .address(address),
       |    .write_data(write_data),
       |    .hierarchy_input(hierarchy_input),
       |    .stream_in_valid(stream_in_valid),
       |    .stream_in_ready(morph_stream_in_ready),
       |    .stream_in_payload(stream_in_payload),
       |    .stream_out_valid(morph_stream_out_valid),
       |    .stream_out_ready(stream_out_ready),
       |    .stream_out_payload(morph_stream_out_payload),
       |    .flow_in_valid(flow_in_valid),
       |    .flow_in_payload(flow_in_payload),
       |    .flow_out_valid(morph_flow_out_valid),
       |    .flow_out_payload(morph_flow_out_payload),
       |    .read_data(morph_read_data),
       |    .count(morph_count_raw),
       |    .counter_complete(morph_counter_complete),
       |    .range_count(morph_range_count),
       |    .range_counter_complete(morph_range_counter_complete),
       |    .down_count(morph_down_count),
       |    .down_counter_complete(morph_down_counter_complete),
       |    .both_count(morph_both_count),
       |    .both_counter_complete(morph_both_counter_complete),
       |    .hierarchy_output(morph_hierarchy_output),
       |    .clk(clk),
       |    .reset(reset)
       |  );
       |
       |  always @* begin
       |    assume(address < ${witness.depth});
       |    if ($$initstate)
       |      assume(reset);
       |    if (!$$initstate) begin
       |      assert(concrete_read_data == morph_read_data);
       |      assert(concrete_count == morph_count_compared);
       |      assert(concrete_counter_complete == morph_counter_complete);
       |      assert(concrete_range_count == morph_range_count);
       |      assert(concrete_range_counter_complete == morph_range_counter_complete);
       |      assert(concrete_down_count == morph_down_count);
       |      assert(concrete_down_counter_complete == morph_down_counter_complete);
       |      assert(concrete_both_count == morph_both_count);
       |      assert(concrete_both_counter_complete == morph_both_counter_complete);
       |      assert(concrete_hierarchy_output == morph_hierarchy_output);
       |      assert(concrete_stream_in_ready == morph_stream_in_ready);
       |      assert(concrete_stream_out_valid == morph_stream_out_valid);
       |      if (concrete_stream_out_valid)
       |        assert(concrete_stream_out_payload == morph_stream_out_payload);
       |      assert(concrete_flow_out_valid == morph_flow_out_valid);
       |      if (concrete_flow_out_valid)
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

    private def miterModule(witness: Witness): String =
      s"TypedPrimitiveClosureFormalMiter${witness.suffix}"

    private def candidateFormalTop(witness: Witness): String =
      s"MorphTypedPrimitiveClosureCandidate${witness.suffix}"

    private def concreteFormalTop(witness: Witness): String =
      s"ConcreteTypedPrimitiveClosureReference${witness.suffix}"

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
      val directory =
        Files.createTempDirectory("morphhdl-typed-primitive-formal-")
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
}
