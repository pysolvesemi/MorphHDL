package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.MorphVerilog
import morphhdl.frontend.{HdlInt, HdlIntRangeStart}
import spinal.lib.{CountOne, Counter, Flow, Stream, master, slave}

private object TypedPrimitiveClosureFixture {
  final class TypedMemory(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("TypedPrimitiveMemory")

    val memory = Mem(UInt(width bits), depth).setName("mem")
    val readEnable = in(Bool()).setName("read_enable")
    val writeEnable = in(Bool()).setName("write_enable")
    val address = in(memory.addressType()).setName("address")
    val writeData = in(UInt(width bits)).setName("write_data")
    val readData = out(UInt(width bits)).setName("read_data")

    val readWord = memory.readSync(
      address,
      enable = readEnable,
      readUnderWrite = readFirst
    )
    memory.write(address, writeData, enable = writeEnable)
    readData := readWord
  }

  final class TypedStateCounter(stateCount: ElabInt) extends Component {
    setDefinitionName("TypedPrimitiveStateCounter")

    val increment = in(Bool()).setName("increment")
    val decrement = in(Bool()).setName("decrement")
    val count = out(UInt(stateCount.addressWidth bits)).setName("count")
    val downCount = out(UInt(stateCount.addressWidth bits)).setName("down_count")
    val bothCount = out(UInt(stateCount.addressWidth bits)).setName("both_count")
    val counter = Counter(stateCount, increment)
    count := counter.value

    val downCounter = Counter.down(stateCount)
    when(decrement) {
      downCounter.decrement()
    }
    downCount := downCounter.value

    val bothCounter = Counter.both(stateCount)
    when(increment) {
      bothCounter.increment()
    }
    when(decrement) {
      bothCounter.decrement()
    }
    bothCount := bothCounter.value
  }

  final class TypedLimitCounter(limit: ElabInt) extends Component {
    setDefinitionName("TypedPrimitiveLimitCounter")

    val increment = in(Bool()).setName("increment")
    val count = out(UInt((limit + 1).addressWidth bits)).setName("count")
    val counter = Counter(ElabInt.literal(2), limit, increment)
    count := counter.value
  }

  final class LiteralParity(useTypedLiterals: Boolean) extends Component {
    setDefinitionName("TypedPrimitiveLiteralParity")

    val readEnable = in(Bool()).setName("read_enable")
    val writeEnable = in(Bool()).setName("write_enable")
    val increment = in(Bool()).setName("increment")
    val decrement = in(Bool()).setName("decrement")
    val address = in(UInt(2 bits)).setName("address")
    val writeData = in(UInt(8 bits)).setName("write_data")
    val readData = out(UInt(8 bits)).setName("read_data")
    val count = out(UInt(3 bits)).setName("count")
    val rangeCount = out(UInt(4 bits)).setName("range_count")
    val downCount = out(UInt(3 bits)).setName("down_count")
    val bothCount = out(UInt(3 bits)).setName("both_count")

    val memory =
      if (useTypedLiterals) Mem(UInt(8 bits), ElabInt.literal(4))
      else Mem(UInt(8 bits), 4)
    memory.setName("mem")
    val readWord = memory.readSync(
      address,
      enable = readEnable,
      readUnderWrite = readFirst
    )
    memory.write(address, writeData, enable = writeEnable)
    readData := readWord

    val counter =
      if (useTypedLiterals) Counter(ElabInt.literal(5), increment)
      else Counter(BigInt(5), increment)
    count := counter.value.resized

    val rangeCounter =
      if (useTypedLiterals)
        Counter(ElabInt.literal(2), ElabInt.literal(6), increment)
      else Counter(BigInt(2), BigInt(6), increment)
    rangeCount := rangeCounter.value.resized

    val downCounter =
      if (useTypedLiterals) Counter.down(ElabInt.literal(5))
      else Counter.down(BigInt(5))
    when(decrement) {
      downCounter.decrement()
    }
    downCount := downCounter.value.resized

    val bothCounter =
      if (useTypedLiterals) Counter.both(ElabInt.literal(5))
      else Counter.both(BigInt(5))
    when(increment) {
      bothCounter.increment()
    }
    when(decrement) {
      bothCounter.decrement()
    }
    bothCount := bothCounter.value.resized
  }

  final class LiteralDepthSymbolicElementMemory(
      width: ElabInt,
      useTypedLiteral: Boolean
  ) extends Component {
    setDefinitionName("TypedPrimitiveLiteralDepthSymbolicElementMemory")

    private val wordType = HardType(UInt(width bits))
    val memory =
      if (useTypedLiteral) Mem(wordType, ElabInt.literal(4))
      else Mem(wordType, 4)
    memory.setName("mem")

    val readEnable = in(Bool()).setName("read_enable")
    val writeEnable = in(Bool()).setName("write_enable")
    val address = in(UInt(2 bits)).setName("address")
    val writeData = in(UInt(width bits)).setName("write_data")
    val readData = out(UInt(width bits)).setName("read_data")

    val readWord = memory.readSync(
      address,
      enable = readEnable,
      readUnderWrite = readFirst
    )
    memory.write(address, writeData, enable = writeEnable)
    readData := readWord
  }

  final class ConcreteAddressTypeOrdering(useMemoryAddressType: Boolean) extends Component {
    setDefinitionName("ConcreteAddressTypeOrdering")

    val memory = Mem(UInt(8 bits), 5).setName("mem")
    private val independentEagerAddressType: HardType[UInt] =
      if (useMemoryAddressType) null
      else HardType(UInt(memory.addressWidth bits))

    // These declarations intentionally separate Mem construction from the
    // first public use of memory.addressType.
    val intervening = in(Bits(7 bits)).setName("intervening")
    val interveningEcho = out(Bits(7 bits)).setName("intervening_echo")
    interveningEcho := intervening

    private val selectedAddressType =
      if (useMemoryAddressType) memory.addressType
      else independentEagerAddressType
    val address = in(selectedAddressType()).setName("address")
    val readData = out(UInt(8 bits)).setName("read_data")
    readData := memory.readAsync(address)
  }

  final class TypedDataPaths(width: ElabInt) extends Component {
    setDefinitionName("TypedPrimitiveDataPaths")

    val resizeIn = in(Bits(width bits)).setName("resize_in")
    val resizeOut = out(Bits((width + 1) bits)).setName("resize_out")
    val resized = resizeIn.resize(width + 1).setName("resized")
    resized.dontSimplifyIt()
    resizeOut := resized

    val fixedIn = in(Bits(8 bits)).setName("fixed_in")
    val fixedSlice = out(Bits(2 bits)).setName("fixed_slice")
    fixedSlice := fixedIn.subdivideIn(ElabInt.literal(4).slices)(0)

    val streamIn = slave(Stream(UInt(width bits))).setName("stream_in")
    val streamOut = master(Stream(UInt(width bits))).setName("stream_out")
    val streamM2s = streamIn.m2sPipe().setName("stream_m2s")
    val streamS2m = streamM2s.s2mPipe().setName("stream_s2m")
    val streamHalf = streamS2m.halfPipe().setName("stream_half")
    streamOut << streamHalf

    val flowIn = slave(Flow(UInt(width bits))).setName("flow_in")
    val flowOut = master(Flow(UInt(width bits))).setName("flow_out")
    val flowM2s = flowIn.m2sPipe().setName("flow_m2s")
    flowOut << flowM2s
  }

  final class LiteralPipes(useTypedLiterals: Boolean) extends Component {
    setDefinitionName("TypedPrimitiveLiteralPipes")

    private def payloadType: UInt =
      if (useTypedLiterals) UInt(ElabInt.literal(8) bits)
      else UInt(8 bits)

    val streamIn = slave(Stream(payloadType)).setName("stream_in")
    val streamOut = master(Stream(payloadType)).setName("stream_out")
    streamOut << streamIn.m2sPipe().s2mPipe().halfPipe()

    val flowIn = slave(Flow(payloadType)).setName("flow_in")
    val flowOut = master(Flow(payloadType)).setName("flow_out")
    flowOut << flowIn.m2sPipe()
  }

  final class TypedFormalChild(width: ElabInt) extends Component {
    setDefinitionName("TypedPrimitiveFormalChild")
    val din = in(Bits(width bits)).setName("din")
    val dout = out(Bits(width bits)).setName("dout")
    dout := din
  }

  final class TypedFormalTop(width: ElabInt) extends Component {
    setDefinitionName("TypedPrimitiveFormalTop")
    val din = in(Bits(width bits)).setName("din")
    val dout = out(Bits(width bits)).setName("dout")
    val child = ElabFormalComponent
      .parameter(
        width,
        "CHILD_WIDTH",
        minimum = BigInt(1),
        maximum = BigInt(16)
      )(childWidth => new TypedFormalChild(childWidth))
      .setName("child")
    child.din := din
    dout := child.dout
  }

  final class FiniteRanges(lanes: HdlInt) extends Component {
    setDefinitionName("TypedPrimitiveFiniteRanges")
    val din = in(morphhdl.frontend.Bits(64 bits)).setName("din")
    val dout = out(morphhdl.frontend.Bits(64 bits)).setName("dout")

    dout := 0
    (0 until lanes).named("p_lane", "lane").foreach { lane =>
      val byteWidth = HdlInt.literal(BigInt(8))
      dout(lane * byteWidth, byteWidth) := din(lane * byteWidth, byteWidth)
    }

    (0 until lanes).named("g_lane", "lane_index").foreach { lane =>
      val byteWidth = HdlInt.literal(BigInt(8))
      val laneWire =
        morphhdl.frontend.Bits(8 bits).setName("structural_lane_wire")
      laneWire := din(lane * byteWidth, byteWidth)
      laneWire.dontSimplifyIt()
    }
  }

  final class FiniteVecDepthMismatch(count: ElabInt) extends Component {
    val vector = Vec(Bits(8 bits), 2)
    ElabFiniteRange.foreach(count, "finite Vec mismatch") { index =>
      index(vector) := B(0, 8 bits)
    }
  }

  final class FiniteMemDepthMismatch(count: ElabInt) extends Component {
    val memory = Mem(Bits(8 bits), 2)
    ElabFiniteRange.foreach(count, "finite Mem mismatch") { index =>
      val observed = Bits(8 bits)
      observed := index(memory)
      observed.dontSimplifyIt()
    }
  }

  final class FiniteFoldWidthMismatch(count: ElabInt) extends Component {
    val source = in(Bits((count + 1) bits))
    val observed = out(UInt((count + 1).addressWidth bits))
    observed := ElabFiniteRange
      .countOne(source, count)(CountOne(source))
      .resized
  }

  final class FiniteFoldMultipleDriver(count: ElabInt) extends Component {
    val source = in(Bits(count bits))
    val observed = out(UInt((count + 1).addressWidth bits))
    val folded = ElabFiniteRange.countOne(source, count)(CountOne(source))
    folded.allowOverride()
    folded := 1
    observed := folded
  }

  final class FiniteMemSameAddress(count: ElabInt) extends Component {
    setDefinitionName("TypedPrimitiveFiniteMemSameAddress")
    val selectedChecks = out(Vec(Bool(), count)).setName("selected_checks")
    val unrelatedChecks = out(Vec(Bool(), count)).setName("unrelated_checks")
    val memory = Mem(Bits(8 bits), count).setName("finite_mem")

    ElabFiniteRange.foreach(count, "finite Mem exact identity") { index =>
      index(selectedChecks) := index(memory).orR
      val unrelated = memory.readAsync(
        U(0, memory.nativePortAddressWidth bits)
      )
      index(unrelatedChecks) := unrelated.orR
    }
  }

  final case class FiniteCompositeWord() extends Bundle {
    val low = Bits(4 bits)
    val high = Bits(4 bits)
  }

  final class FiniteCompositeMem(count: ElabInt) extends Component {
    setDefinitionName("TypedPrimitiveFiniteCompositeMem")
    val checks = out(Vec(Bool(), count)).setName("composite_checks")
    val memory = Mem(FiniteCompositeWord(), count)
      .setName("finite_composite_mem")

    ElabFiniteRange.foreach(count, "finite composite Mem identity") { index =>
      index(checks) := index(memory).asBits.orR
    }
  }

  final class OwnerRootMismatch(left: ElabInt, right: ElabInt) extends Component {
    val keep = out(Bool())
    keep := False
    val owner = ParameterizedStructure.currentOwner(left, "root mismatch owner")
    ParameterizedStructure.captureInto(owner, right, "root mismatch extension") {
      keep := True
    }
  }

  final class OwnerOverlap(control: ElabInt) extends Component {
    val keep = out(Bool())
    keep := False
    val owner = ParameterizedStructure.currentOwner(control, "overlap owner")
    ParameterizedStructure.requireOwnerCoverage(
      this,
      control,
      Seq(owner, owner),
      "overlap coverage"
    )
  }

  final class OwnerIncomplete(control: ElabInt) extends Component {
    val keep = out(Bool())
    keep := False
    var owner: ParameterizedStructuralOwner = null
    control.elabEq(1).generate {
      owner = ParameterizedStructure.currentOwner(control, "partial owner")
      val branch = Bool()
      branch := True
      branch.dontSimplifyIt()
    }
    ParameterizedStructure.requireOwnerCoverage(
      this,
      control,
      Seq(owner),
      "partial coverage"
    )
  }

  final class OwnerForeignActiveRoot(
      branchControl: ElabInt,
      foreignControl: ElabInt
  ) extends Component {
    val keep = out(Bool())
    keep := False
    var foreignOwner: ParameterizedStructuralOwner = null
    branchControl.elabEq(1).generate {
      val branchMarker = Bool()
      branchMarker := True
      branchMarker.dontSimplifyIt()
      foreignOwner = ParameterizedStructure.currentOwner(
        foreignControl,
        "foreign active-root owner"
      )
    }
    ParameterizedStructure.captureInto(
      foreignOwner,
      foreignControl,
      "foreign active-root extension"
    ) {
      keep := True
    }
  }

  /** Both roots are live in the nested DomainContext, but only the inner
    * root owns the active capture id. The outer root must not borrow it.
    */
  final class OwnerNestedActiveRoot(
      outerControl: ElabInt,
      innerControl: ElabInt
  ) extends Component {
    val keep = out(Bool())
    keep := False
    var borrowedOwner: ParameterizedStructuralOwner = null
    outerControl.elabEq(1).generate {
      innerControl.elabEq(1).generate {
        val branchMarker = Bool()
        branchMarker := True
        branchMarker.dontSimplifyIt()
        borrowedOwner = ParameterizedStructure.currentOwner(
          outerControl,
          "nested outer-root owner"
        )
      }
    }
  }

  final class OwnerProducer(control: ElabInt) extends Component {
    val keep = out(Bool())
    keep := False
    val owner = ParameterizedStructure.currentOwner(control, "producer owner")
  }

  final class OwnerConsumer(
      control: ElabInt,
      owner: ParameterizedStructuralOwner
  ) extends Component {
    val keep = out(Bool())
    keep := False
    ParameterizedStructure.captureInto(owner, control, "unrelated consumer") {
      keep := True
    }
  }

  final class OwnerUnrelatedComponents(control: ElabInt) extends Component {
    val producer = new OwnerProducer(control)
    val consumer = new OwnerConsumer(control, producer.owner)
  }
}

class TypedPrimitiveClosureTests extends AnyFunSuite {
  import TypedPrimitiveClosureFixture._

  private val AddressWitnesses = Vector(1 -> 1, 3 -> 2, 5 -> 3, 8 -> 3)

  test("typed addressWidth is exact and positive at depths 1, 3, 5 and 8") {
    val depth = parameter("DEPTH", default = 1, minimum = 1, maximum = 8)
    val width = depth.addressWidth
    val domain = width.expression.exactDomain.getOrElse(
      fail("typed addressWidth lost exact-domain evidence")
    )

    assert(width.expression.verilog == "morphhdl_address_width(DEPTH)")
    assert(width.minimum == 1)
    assert(width.maximum == 3)
    AddressWitnesses.foreach { case (selectedDepth, expectedWidth) =>
      assert(domain.evaluate(BigInt(selectedDepth)).contains(BigInt(expectedWidth)))
      ElaborationDomainContext.withAdmitted(
        domain.root,
        Set(BigInt(selectedDepth)),
        sourceLocation = None
      ) {
        assert(width.witness == expectedWidth)
        assert(width.bits.value == expectedWidth)
      }
    }

    AddressWitnesses.foreach { case (value, expected) =>
      assert(
        ElabInt.literal(value).addressWidth.constantInt(s"addressWidth($value)") ==
          expected
      )
    }
  }

  test("direct typed Mem keeps an unpacked symbolic memory and portable address geometry") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 8, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      var retainedAddressWidth: Option[ElaborationIntegerExpression] = None
      val (report, verilog, rtl) = emitMorph(
        directory,
        "typed_primitive_memory.v", {
          val dut = new TypedMemory(width, depth)
          retainedAddressWidth = ParameterizedWidth.expressionOf(dut.address)
          dut
        }
      )
      val compact = compactWhitespace(verilog)
      val addressExpression = retainedAddressWidth.getOrElse(
        fail("typed Mem addressType lost its retained width expression")
      )

      assert(report.parameters.map(_.name).toSet == Set("DEPTH", "WIDTH"))
      assert(addressExpression.verilog == "morphhdl_address_width(DEPTH)")
      assert(addressExpression.default == 3)
      assert(addressExpression.minimum == 1)
      assert(addressExpression.maximum == 3)
      assert(compact.contains("reg[WIDTH-1:0]mem[0:DEPTH-1];"), verilog)
      assert(!compact.contains("reg[(WIDTH*DEPTH)-1:0]mem;"), verilog)
      assert(compact.contains("[clog2(DEPTH,1)-1:0]address"), verilog)
      assert(verilog.contains("function integer clog2;"), verilog)

      if (commandAvailable("iverilog"))
        AddressWitnesses.foreach { case (selectedDepth, _) =>
          compileMemorySpecialization(directory, rtl, selectedDepth)
        }
      if (commandAvailable("yosys"))
        AddressWitnesses.foreach { case (selectedDepth, _) =>
          synthesizeMemorySpecialization(directory, rtl, selectedDepth)
        }
    }
  }

  test("typed Counter retains state-count and inclusive-limit expressions") {
    withTemporaryDirectory { directory =>
      val stateCount =
        parameter("STATE_COUNT", default = 1, minimum = 1, maximum = 8)
      val (_, stateVerilog, _) = emitMorph(
        directory.resolve("state"),
        "typed_state_counter.v",
        new TypedStateCounter(stateCount)
      )
      val stateCompact = compactWhitespace(stateVerilog)
      assert(stateVerilog.contains("parameter integer STATE_COUNT = 1"))
      assert(stateCompact.contains("[clog2(STATE_COUNT,1)-1:0]count"))
      assert(stateCompact.contains("[clog2(STATE_COUNT,1)-1:0]down_count"))
      assert(stateCompact.contains("[clog2(STATE_COUNT,1)-1:0]both_count"))
      assert(stateVerilog.contains("function integer clog2;"))

      val limit = parameter("LIMIT", default = 5, minimum = 2, maximum = 8)
      val (_, limitVerilog, _) = emitMorph(
        directory.resolve("limit"),
        "typed_limit_counter.v",
        new TypedLimitCounter(limit)
      )
      val limitCompact = compactWhitespace(limitVerilog)
      assert(limitVerilog.contains("parameter integer LIMIT = 5"))
      assert(limitCompact.contains("clog2((LIMIT+1),1)"), limitVerilog)
      val upperCarrier =
        "(?m)^\\s*assign\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s*=\\s*\\(LIMIT\\)\\s*;\\s*$".r
          .findFirstMatchIn(limitVerilog)
          .map(_.group(1))
          .getOrElse(fail(s"missing exact retained LIMIT carrier:\n$limitVerilog"))
      assert(
        limitCompact.contains(s"counter_value==$upperCarrier"),
        limitVerilog
      )
    }
  }

  test("typed literal Mem and Counter preserve ordinary concrete RTL") {
    withTemporaryDirectory { directory =>
      var ordinaryGraph = Vector.empty[String]
      val ordinary = emitConcrete(
        directory.resolve("ordinary"),
        "literal_parity.v", {
          val dut = new LiteralParity(useTypedLiterals = false)
          ordinaryGraph = concreteMemoryGraph(dut.memory)
          dut
        }
      )
      var typedGraph = Vector.empty[String]
      val typed = emitConcrete(
        directory.resolve("typed"),
        "literal_parity.v", {
          val dut = new LiteralParity(useTypedLiterals = true)
          typedGraph = concreteMemoryGraph(dut.memory)
          dut
        }
      )

      assert(typed == ordinary)
      assert(typedGraph == ordinaryGraph)
      assert(typedGraph.contains("parameterized=false"))
      assert(!typed.contains("parameter integer"))
      assert(typed.contains("mem [0:3]"))
    }
  }

  test("typed literal Mem preserves native HardType evaluation and Int depth acceptance") {
    var ordinaryCalls = 0
    withSpinalElaboration {
      val memory = Mem(
        HardType {
          ordinaryCalls += 1
          UInt(8 bits)
        },
        4
      )
      assert(memory.wordCount == 4)
    }

    var typedCalls = 0
    withSpinalElaboration {
      val memory = Mem(
        HardType {
          typedCalls += 1
          UInt(8 bits)
        },
        ElabInt.literal(4)
      )
      assert(memory.wordCount == 4)
      assert(ParameterizedMemory.metadataOf(memory).isEmpty)
    }

    var typedWidthCalls = 0
    withSpinalElaboration {
      val width = parameter("WIDTH", default = 8, minimum = 1, maximum = 16)
      val memory = Mem(
        HardType {
          typedWidthCalls += 1
          UInt(width bits)
        },
        ElabInt.literal(4)
      )
      assert(memory.wordCount == 4)
      assert(ParameterizedMemory.metadataOf(memory).isEmpty)
    }

    def depthOutcome(depth: Int, useTypedLiteral: Boolean): Option[String] =
      try {
        withSpinalElaboration {
          val memory =
            if (useTypedLiteral) Mem(UInt(8 bits), ElabInt.literal(depth))
            else Mem(UInt(8 bits), depth)
          assert(memory.wordCount == depth)
        }
        None
      } catch {
        case failure: Throwable => Some(failure.getClass.getName)
      }

    Vector(0, -1).foreach { depth =>
      assert(
        depthOutcome(depth, useTypedLiteral = true) ==
          depthOutcome(depth, useTypedLiteral = false)
      )
    }

    assert(ordinaryCalls == 1)
    assert(typedCalls == ordinaryCalls)
    assert(typedWidthCalls == ordinaryCalls)
  }

  test("typed literal Mem with symbolic elements preserves native graph and RTL") {
    withTemporaryDirectory { directory =>
      def emitCase(
          target: Path,
          useTypedLiteral: Boolean
      ): (Vector[String], Array[Byte], String) = {
        val width = parameter("WIDTH", default = 8, minimum = 1, maximum = 16)
        var graph = Vector.empty[String]
        val (_, verilog, rtl) = emitMorph(
          target,
          "literal_symbolic_element_mem.v", {
            val dut = new LiteralDepthSymbolicElementMemory(
              width,
              useTypedLiteral
            )
            graph = concreteMemoryGraph(dut.memory) :+
              s"parameterizedTag=${dut.memory.getTag(classOf[ParameterizedMemoryTag]).nonEmpty}"
            dut
          }
        )
        (graph, Files.readAllBytes(rtl), verilog)
      }

      val ordinary = emitCase(directory.resolve("ordinary"), useTypedLiteral = false)
      val typed = emitCase(directory.resolve("typed"), useTypedLiteral = true)

      assert(typed._1 == ordinary._1)
      assert(typed._1.contains("parameterized=false"))
      assert(typed._1.contains("parameterizedTag=false"))
      assert(java.util.Arrays.equals(typed._2, ordinary._2))
      assert(typed._3.contains("parameter integer WIDTH = 8"), typed._3)
      assert(
        compactWhitespace(typed._3).contains("reg[WIDTH-1:0]mem[0:3];"),
        typed._3
      )
    }
  }

  test("Counter retains the public five-argument stepOne ABI") {
    val methods = classOf[Counter].getMethods.filter(_.getName == "stepOne")
    assert(methods.exists(_.getParameterCount == 5))
    assert(!methods.exists(_.getParameterCount == 7))
  }

  test("ordinary Mem keeps eager addressType ordering and concrete RTL parity") {
    withTemporaryDirectory { directory =>
      val independent = emitConcrete(
        directory.resolve("independent"),
        "address_type_ordering.v",
        new ConcreteAddressTypeOrdering(useMemoryAddressType = false)
      )
      val native = emitConcrete(
        directory.resolve("native"),
        "address_type_ordering.v",
        new ConcreteAddressTypeOrdering(useMemoryAddressType = true)
      )

      assert(native == independent)
      assert(compactWhitespace(native).contains("[2:0]address"), native)
    }
  }

  test("Mem port geometry preserves concrete formulas and typed products") {
    var concreteGetWidths = Vector.empty[Int]
    var concreteNormalizedWidths = Vector.empty[Int]
    withSpinalElaboration {
      val memory = Mem(Bits(24 bits), 5)
      val read = MemReadSync(
        memory,
        UInt(5 bits),
        width = 8,
        enable = True,
        readUnderWrite = dontCare,
        clockDomain = ClockDomain.current
      ).addTag(AllowMixedWidth)
      val write = MemWrite(
        memory,
        UInt(5 bits),
        Bits(8 bits),
        mask = null,
        enable = True,
        width = 8,
        clockDomain = ClockDomain.current
      ).addTag(AllowMixedWidth)

      concreteGetWidths = Vector(read.getAddressWidth, write.getAddressWidth)
      read.normalizeInputs
      write.normalizeInputs
      concreteNormalizedWidths = Vector(read.address.getWidth, write.address.getWidth)
    }
    assert(concreteGetWidths == Vector(4, 4))
    assert(concreteNormalizedWidths == Vector(5, 5))

    val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
    var typedPortWidth: Option[ElabInt] = None
    withSpinalElaboration {
      val memory = Mem(Bits(24 bits), depth)
      typedPortWidth = memory.parameterizedPortAddressWidth(aspectRatio = 3)
    }
    val retained = typedPortWidth.getOrElse(
      fail("typed Mem port lost its retained address geometry")
    )
    assert(
      retained.expression.verilog ==
        "morphhdl_address_width((DEPTH * 3))"
    )
    assert(retained.witness == 4)
    assert(retained.minimum == 2)
    assert(retained.maximum == 5)

    val narrowedDepth =
      parameter("NARROWED_DEPTH", default = 3, minimum = 1, maximum = 8)
    val narrowedDomain = narrowedDepth.expression.exactDomain.getOrElse(
      fail("narrowed Mem depth lacks exact-domain evidence")
    )
    var narrowedWitness: Option[Int] = None
    var narrowedEvidence = Set.empty[BigInt]
    withSpinalElaboration {
      var memory: Mem[Bits] = null
      ElaborationDomainContext.withAdmitted(
        narrowedDomain.root,
        (2 to 8).map(value => BigInt(value)).toSet,
        sourceLocation = None
      ) {
        memory = Mem(Bits(24 bits), narrowedDepth)
      }
      narrowedEvidence = ParameterizedMemory
        .metadataOf(memory)
        .flatMap(_.depth.exactDomain)
        .map(_.evidenceValues)
        .getOrElse(Set.empty)
      narrowedWitness = memory.parameterizedPortAddressWidthWitness(aspectRatio = 3)
    }
    assert(
      narrowedEvidence == (2 to 8).map(value => BigInt(value)).toSet
    )
    assert(narrowedWitness.contains(4))
  }

  test("typed literal Stream and Flow pipes preserve byte-identical concrete RTL") {
    withTemporaryDirectory { directory =>
      val ordinary = emitConcrete(
        directory.resolve("ordinary"),
        "literal_pipes.v",
        new LiteralPipes(useTypedLiterals = false)
      )
      val typed = emitConcrete(
        directory.resolve("typed"),
        "literal_pipes.v",
        new LiteralPipes(useTypedLiterals = true)
      )

      assert(typed == ordinary)
    }
  }

  test("fixed slices, typed resize, Stream and Flow retain their native shapes") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 8, minimum = 1, maximum = 16)
      val (_, verilog, _) = emitMorph(
        directory,
        "typed_data_paths.v",
        new TypedDataPaths(width)
      )
      val compact = compactWhitespace(verilog)

      Vector(
        "resize_in",
        "stream_in_payload",
        "stream_out_payload",
        "stream_m2s_payload",
        "stream_s2m_payload",
        "stream_half_payload",
        "flow_in_payload",
        "flow_out_payload",
        "flow_m2s_payload"
      ).foreach(name => assert(compact.contains(s"[WIDTH-1:0]$name"), verilog))
      assert(compact.contains("[(WIDTH+1)-1:0]resize_out"), verilog)
      assert(compact.contains("[1:0]fixed_slice"), verilog)
      assert(compact.contains("stream_in_valid"))
      assert(compact.contains("stream_in_ready"))
      assert(compact.contains("flow_in_valid"))
    }
  }

  test("public symbolic widths and typed resize reject missing exact evidence before attachment") {
    var widthError: ParameterizedVerilogException = null
    var resizeError: ParameterizedVerilogException = null
    var launderedWidthError: ParameterizedVerilogException = null
    var launderedResizeError: ParameterizedVerilogException = null
    var copiedSchemaWidthError: ParameterizedVerilogException = null
    var widthRegistered = true
    var directRetained = false
    var concreteDerivedWidth = -1
    withSpinalElaboration {
      val schema = ElaborationIntegerParameter(
        "INEXACT_PUBLIC_WIDTH",
        default = 104,
        minimum = 101,
        maximum = 104
      )
      val root =
        ElaborationIntegerParameterRoot.fresh("INEXACT_PUBLIC_WIDTH")
      val inexact = ElaborationIntegerExpression(
        verilog = "INEXACT_PUBLIC_WIDTH - 100",
        default = 4,
        minimum = 4,
        maximum = 4,
        parameters = Vector(schema),
        parameterRoots = Vector(root)
      )
      val target = UInt()
      widthError = intercept[ParameterizedVerilogException] {
        ParameterizedWidth.attach(
          target,
          ParameterizedBitCount(
            value = 4,
            parameter = None,
            expression = Some(inexact)
          )
        )
      }
      widthRegistered = ParameterizedWidth.expressionOf(target).nonEmpty

      resizeError = intercept[ParameterizedVerilogException] {
        Bits(4 bits).resize(ElabInt.fromExpression(inexact))
      }

      val launderedSchema = ElaborationIntegerParameter(
        "LAUNDERED_PUBLIC_WIDTH",
        default = 2,
        minimum = 1,
        maximum = 3
      )
      val launderedRoot =
        ElaborationIntegerParameterRoot.fresh("LAUNDERED_PUBLIC_WIDTH")
      ElaborationExactDomain.checked[BigInt](
        launderedRoot,
        launderedSchema,
        Vector(
          BigInt(1) -> BigInt(1),
          BigInt(2) -> BigInt(2),
          BigInt(3) -> BigInt(3)
        ),
        sourceLocation = None,
        role = "public width root authority"
      )
      val laundered = ElabInt.fromTrustedExactExpressionForTest(
        ElaborationIntegerExpression(
          verilog = "LAUNDERED_PUBLIC_WIDTH",
          default = 2,
          minimum = 1,
          maximum = 3,
          parameters = Vector(launderedSchema),
          parameterRoots = Vector(launderedRoot),
          exactDomain = Some(
            ElaborationExactDomain[BigInt](
              launderedRoot,
              launderedSchema,
              Vector(
                BigInt(1) -> BigInt(4),
                BigInt(2) -> BigInt(5),
                BigInt(3) -> BigInt(6)
              )
            )
          )
        )
      )
      launderedWidthError = intercept[ParameterizedVerilogException] {
        UInt(laundered.bits)
      }
      launderedResizeError = intercept[ParameterizedVerilogException] {
        Bits(2 bits).resize(laundered)
      }

      val copiedSchema = launderedSchema.copy()
      val copiedSchemaWidth = ElabInt.fromTrustedExactExpressionForTest(
        ElaborationIntegerExpression(
          verilog = "LAUNDERED_PUBLIC_WIDTH",
          default = 2,
          minimum = 1,
          maximum = 3,
          parameters = Vector(copiedSchema),
          parameterRoots = Vector(launderedRoot),
          exactDomain = Some(
            ElaborationExactDomain[BigInt](
              launderedRoot,
              copiedSchema,
              Vector(
                BigInt(1) -> BigInt(1),
                BigInt(2) -> BigInt(2),
                BigInt(3) -> BigInt(3)
              )
            )
          )
        )
      )
      copiedSchemaWidthError = intercept[ParameterizedVerilogException] {
        UInt(copiedSchemaWidth.bits)
      }

      concreteDerivedWidth = UInt(((ElabInt.literal(8) + 4) / 3).bits).getBitsWidth

      val directSchema = ElaborationIntegerParameter(
        "DIRECT_PUBLIC_WIDTH",
        default = 4,
        minimum = 1,
        maximum = 8
      )
      directRetained = ParameterizedWidth
        .expressionOf(UInt(ParameterizedBitCount(4, directSchema)))
        .exists(_.verilog == "DIRECT_PUBLIC_WIDTH")
    }
    Vector(
      widthError,
      resizeError,
      launderedWidthError,
      launderedResizeError,
      copiedSchemaWidthError
    ).foreach { error =>
      assert(
        error.code ==
          "SPINAL-PARAMETERIZED-VERILOG-WIDTH-EXACT-DOMAIN-REQUIRED"
      )
    }
    assert(!widthRegistered)
    assert(directRetained)
    assert(concreteDerivedWidth == 4)
  }

  test("one direct typed child formal binds its parent expression") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 8, minimum = 1, maximum = 16)
      val (_, verilog, _) = emitMorph(
        directory,
        "typed_formal_child.v",
        new TypedFormalTop(width)
      )
      val compact = compactWhitespace(verilog)

      assert(verilog.contains("module TypedPrimitiveFormalChild #("))
      assert(verilog.contains("parameter integer CHILD_WIDTH = 8"))
      assert(compact.contains(".CHILD_WIDTH(WIDTH)"), verilog)
      assert(compact.contains("[CHILD_WIDTH-1:0]din"), verilog)
      assert(compact.contains("[WIDTH-1:0]din"), verilog)
    }
  }

  test("finite structural and procedural ranges keep distinct Verilog forms") {
    withTemporaryDirectory { directory =>
      val lanes = HdlInt.param("LANES", default = 5, min = 1, max = 8)
      val (_, verilog, rtl) = emitMorph(
        directory,
        "typed_finite_ranges.v",
        new FiniteRanges(lanes)
      )
      val original = Files.readAllBytes(rtl)

      assert(verilog.contains("genvar lane_index;"), verilog)
      assert(
        verilog.contains(
          "for (lane_index = 0; lane_index < LANES; lane_index = lane_index + 1) begin : g_lane"
        ),
        verilog
      )
      assert(verilog.contains("integer lane;"), verilog)
      assert(verilog.contains("always @(*) begin"), verilog)
      assert(
        verilog.contains(
          "for (lane = 0; lane < LANES; lane = lane + 1) begin : p_lane"
        ),
        verilog
      )

      Vector(1, 3, 5, 8).foreach { selectedLanes =>
        if (commandAvailable("verilator"))
          lintFiniteRangeSpecialization(directory, rtl, selectedLanes)
        if (commandAvailable("iverilog") && commandAvailable("vvp"))
          simulateFiniteRangeSpecialization(directory, rtl, selectedLanes)
        if (commandAvailable("yosys"))
          synthesizeFiniteRangeSpecialization(directory, rtl, selectedLanes)
        assert(
          java.util.Arrays.equals(original, Files.readAllBytes(rtl)),
          "finite-range specialization rewrote the one parameterized module"
        )
      }
    }
  }

  test("typed finite Mem selection rewrites only its exact retained read port") {
    withTemporaryDirectory { directory =>
      val depth = parameter("DEPTH", default = 3, minimum = 1, maximum = 8)
      val (_, verilog, _) = emitMorph(
        directory,
        "finite_mem_exact_identity.v",
        new FiniteMemSameAddress(depth)
      )
      val access =
        "(?m)^\\s*assign\\s+[A-Za-z_][A-Za-z0-9_$]*\\s*=\\s*finite_mem\\s*\\[\\s*([^\\]]+)\\s*\\]\\s*;\\s*$".r
          .findAllMatchIn(verilog)
          .map(_.group(1).trim)
          .toVector

      assert(access.size == 2, verilog)
      val generated =
        access.filter(_.contains("finite_Mem_exact_identity_index_"))
      assert(generated.size == 1, verilog)

      val native = access.filterNot(generated.toSet)
      assert(native.size == 1, verilog)
      val nativeAddressCarrier = native.head
      assert(
        nativeAddressCarrier.matches("[A-Za-z_][A-Za-z0-9_$]*"),
        verilog
      )
      val nativeAddressAssignments =
        ("(?m)^\\s*assign\\s+" +
          java.util.regex.Pattern.quote(nativeAddressCarrier) +
          "\\s*=\\s*([^;]+)\\s*;\\s*$").r
          .findAllMatchIn(verilog)
          .map(_.group(1).replaceAll("\\s+", ""))
          .toVector
      assert(nativeAddressAssignments.size == 1, verilog)
      assert(
        nativeAddressAssignments.head.matches(
          "(?:0|[0-9]+'[sS]?[bBoOdDhH]0+)"
        ),
        verilog
      )
    }
  }

  test("typed finite composite Mem retains one complete packed read lineage") {
    withTemporaryDirectory { directory =>
      val depth = parameter("DEPTH", default = 3, minimum = 1, maximum = 8)
      val (_, verilog, _) = emitMorph(
        directory,
        "finite_composite_mem.v",
        new FiniteCompositeMem(depth)
      )
      val compact = compactWhitespace(verilog)
      assert(compact.contains("finite_composite_mem[finite_composite_Mem_identity_index_"), verilog)
      assert(!compact.contains("finite_composite_mem[0]"), verilog)
    }
  }

  test("symbolic finite ranges fail closed outside structural capture") {
    val count = parameter("COUNT", default = 3, minimum = 1, maximum = 8)
    val failure = intercept[ParameterizedVerilogException] {
      ElabFiniteRange.foreach(count, "uncaptured symbolic finite range") { _ =>
        ()
      }
    }
    assert(
      failure.code ==
        "SPINAL-ELAB-FINITE-RANGE-SYMBOLIC-CAPTURE-REQUIRED"
    )
  }

  test("finite Vec Mem and fold geometry mismatches fail by exact expression") {
    withTemporaryDirectory { directory =>
      def count: ElabInt =
        parameter("COUNT", default = 2, minimum = 1, maximum = 4)

      expectMorphFailure(
        directory.resolve("vec"),
        "finite_vec_mismatch.v",
        new FiniteVecDepthMismatch(count),
        "SPINAL-ELAB-FINITE-RANGE-VEC-DEPTH-MISMATCH"
      )
      expectMorphFailure(
        directory.resolve("mem"),
        "finite_mem_mismatch.v",
        new FiniteMemDepthMismatch(count),
        "SPINAL-ELAB-FINITE-RANGE-MEM-DEPTH-MISMATCH"
      )
      expectMorphFailure(
        directory.resolve("fold"),
        "finite_fold_mismatch.v",
        new FiniteFoldWidthMismatch(count),
        "SPINAL-ELAB-FINITE-FOLD-WIDTH-MISMATCH"
      )
    }
  }

  test("finite population count rejects an overridden retained anchor") {
    withTemporaryDirectory { directory =>
      val count = parameter("COUNT", default = 3, minimum = 1, maximum = 8)
      expectMorphFailure(
        directory,
        "finite_fold_override.v",
        new FiniteFoldMultipleDriver(count),
        "SPINAL-PARAMETERIZED-VERILOG-FINITE-FOLD-ANCHOR-CARDINALITY-MISMATCH"
      )
    }
  }

  test("structural owners reject wrong roots overlap incomplete domains and siblings") {
    withTemporaryDirectory { directory =>
      def control: ElabInt =
        parameter("DEPTH", default = 1, minimum = 1, maximum = 4)

      expectMorphFailure(
        directory.resolve("root"),
        "owner_root_mismatch.v",
        new OwnerRootMismatch(
          control,
          parameter("OTHER", default = 1, minimum = 1, maximum = 4)
        ),
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-ROOT-MISMATCH"
      )
      expectMorphFailure(
        directory.resolve("overlap"),
        "owner_overlap.v",
        new OwnerOverlap(control),
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-COVERAGE-OVERLAP"
      )
      expectMorphFailure(
        directory.resolve("incomplete"),
        "owner_incomplete.v",
        new OwnerIncomplete(control),
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-COVERAGE-MISMATCH"
      )
      expectMorphFailure(
        directory.resolve("sibling"),
        "owner_sibling.v",
        new OwnerUnrelatedComponents(control),
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-COMPONENT-MISMATCH"
      )
    }
  }

  test("structural owner cannot borrow an active capture id from a foreign root") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("owner_foreign_active_root.v")
      val failure = MorphVerilog.tryGenerate(
        config(directory, rtl.getFileName.toString)
      ) {
        new OwnerForeignActiveRoot(
          parameter("OWNER_BRANCH_A", default = 1, minimum = 1, maximum = 2),
          parameter("OWNER_BRANCH_B", default = 1, minimum = 1, maximum = 2)
        )
      } match {
        case Left(value)  => value
        case Right(value) => fail(s"expected foreign active-root rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-ACTIVE-ROOT-MISMATCH"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "foreign active-root owner published partial RTL")
    }
  }

  test("nested exact roots cannot lend the inner capture id to the outer owner") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("owner_nested_active_root.v")
      val failure = MorphVerilog.tryGenerate(
        config(directory, rtl.getFileName.toString)
      ) {
        new OwnerNestedActiveRoot(
          parameter("OWNER_OUTER", default = 1, minimum = 1, maximum = 2),
          parameter("OWNER_INNER", default = 1, minimum = 1, maximum = 2)
        )
      } match {
        case Left(value)  => value
        case Right(value) => fail(s"expected nested owner-root rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-ACTIVE-ROOT-MISMATCH"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "nested owner-root borrowing published partial RTL")
    }
  }

  test("symbolic slice counts fail closed while literal slices stay concrete") {
    assert(ElabInt.literal(4).slices.value == 4)

    val count = parameter("SLICE_COUNT", default = 4, minimum = 1, maximum = 8)
    val failure = intercept[ParameterizedVerilogException] {
      count.slices
    }
    assert(failure.code == "SPINAL-ELAB-INT-DOMAIN-NOT-CONSTANT")
  }

  test("non-positive address, Mem and Counter domains fail before construction") {
    val invalid = parameter("BAD_DEPTH", default = 1, minimum = 0, maximum = 8)
    val addressError = intercept[ParameterizedVerilogException] {
      invalid.addressWidth
    }
    assert(
      addressError.code ==
        "SPINAL-ELAB-INT-ADDRESS-WIDTH-DOMAIN-NONPOSITIVE"
    )

    var memoryError: ParameterizedVerilogException = null
    var counterError: ParameterizedVerilogException = null
    withSpinalElaboration {
      memoryError = intercept[ParameterizedVerilogException] {
        Mem(UInt(8 bits), invalid)
      }
      counterError = intercept[ParameterizedVerilogException] {
        Counter(invalid)
      }
    }
    assert(
      memoryError.code == "SPINAL-ELAB-INT-MEMORY-DEPTH-DOMAIN-INVALID"
    )
    assert(counterError.code.startsWith("SPINAL-ELAB-COUNTER-"))
  }

  test("typed Counter inclusive bounds reject negative and independent domains") {
    var copiedSchemaEvidence: ParameterizedVerilogException = null
    var narrowedSchemaEvidence: ParameterizedVerilogException = null
    withSpinalElaboration {
      val schema = ElaborationIntegerParameter(
        "COUNTER_FOREIGN_SCHEMA",
        default = 4,
        minimum = 2,
        maximum = 8
      )
      val root =
        ElaborationIntegerParameterRoot.fresh("COUNTER_FOREIGN_SCHEMA")
      def malformed(
          evidenceSchema: ElaborationIntegerParameter,
          evaluations: Vector[(BigInt, BigInt)],
          minimum: BigInt,
          maximum: BigInt
      ): ElabInt =
        ElabInt.fromTrustedExactExpressionForTest(
          ElaborationIntegerExpression(
            verilog = "COUNTER_FOREIGN_SCHEMA",
            default = 4,
            minimum = minimum,
            maximum = maximum,
            parameters = Vector(schema),
            parameterRoots = Vector(root),
            exactDomain = Some(
              ElaborationExactDomain(root, evidenceSchema, evaluations)
            )
          )
        )

      val equalButCopiedSchema = schema.copy()
      copiedSchemaEvidence = intercept[ParameterizedVerilogException] {
        Counter(
          malformed(
            equalButCopiedSchema,
            (2 to 8).toVector.map(value => BigInt(value) -> BigInt(value)),
            minimum = 2,
            maximum = 8
          )
        )
      }

      val narrowedSchema = schema.copy(minimum = 4, maximum = 4)
      narrowedSchemaEvidence = intercept[ParameterizedVerilogException] {
        Counter(
          malformed(
            narrowedSchema,
            Vector(BigInt(4) -> BigInt(4)),
            minimum = 4,
            maximum = 4
          )
        )
      }
    }
    Vector(copiedSchemaEvidence, narrowedSchemaEvidence).foreach { error =>
      assert(error.code == "SPINAL-ELAB-COUNTER-EXACT-DOMAIN-REQUIRED")
    }

    var duplicateMissingEvidence: ParameterizedVerilogException = null
    withSpinalElaboration {
      val schema = ElaborationIntegerParameter(
        "COUNTER_INEXACT_STATE_COUNT",
        default = 4,
        minimum = 3,
        maximum = 5
      )
      val root =
        ElaborationIntegerParameterRoot.fresh("COUNTER_INEXACT_STATE_COUNT")
      val sameLengthIncomplete = ElaborationExactDomain(
        root,
        schema,
        Vector(
          BigInt(3) -> BigInt(3),
          BigInt(3) -> BigInt(3),
          BigInt(4) -> BigInt(4)
        )
      )
      val stateCount = ElabInt.fromTrustedExactExpressionForTest(
        ElaborationIntegerExpression(
          verilog = "COUNTER_INEXACT_STATE_COUNT",
          default = 4,
          minimum = 3,
          maximum = 5,
          parameters = Vector(schema),
          parameterRoots = Vector(root),
          exactDomain = Some(sameLengthIncomplete)
        )
      )
      duplicateMissingEvidence = intercept[ParameterizedVerilogException] {
        Counter(stateCount)
      }
    }
    assert(
      duplicateMissingEvidence.code ==
        "SPINAL-ELAB-COUNTER-EXACT-DOMAIN-REQUIRED"
    )

    var negativeStart: ParameterizedVerilogException = null
    withSpinalElaboration {
      val start = parameter("COUNTER_START", default = 2, minimum = -1, maximum = 4)
      negativeStart = intercept[ParameterizedVerilogException] {
        Counter(start, start + 4)
      }
    }
    assert(negativeStart.code == "SPINAL-ELAB-COUNTER-START-DOMAIN-NEGATIVE")

    var negativeEnd: ParameterizedVerilogException = null
    withSpinalElaboration {
      val endBase = parameter("COUNTER_END_BASE", default = 4, minimum = 1, maximum = 8)
      negativeEnd = intercept[ParameterizedVerilogException] {
        Counter(ElabInt.literal(0), endBase - 2)
      }
    }
    assert(negativeEnd.code == "SPINAL-ELAB-COUNTER-END-DOMAIN-NEGATIVE")

    var inconsistent: ParameterizedVerilogException = null
    withSpinalElaboration {
      val end = parameter("COUNTER_END", default = 4, minimum = 2, maximum = 4)
      inconsistent = intercept[ParameterizedVerilogException] {
        Counter(ElabInt.literal(5), end)
      }
    }
    assert(
      inconsistent.code ==
        "SPINAL-ELAB-COUNTER-STATE-COUNT-DOMAIN-NONPOSITIVE"
    )

    var independent: ParameterizedVerilogException = null
    withSpinalElaboration {
      val start = parameter("COUNTER_START", default = 2, minimum = 0, maximum = 4)
      val end = parameter("COUNTER_END", default = 5, minimum = 2, maximum = 8)
      independent = intercept[ParameterizedVerilogException] {
        Counter(start, end)
      }
    }
    assert(
      independent.code ==
        "SPINAL-ELAB-DOMAIN-EXACT-CORRELATION-UNSUPPORTED"
    )

    var ordinalTwo: ParameterizedVerilogException = null
    withSpinalElaboration {
      val stateCount =
        parameter("COUNTER_CONSTANT_STATE_COUNT", default = 8, minimum = 2, maximum = 8)
      val counter = Counter(stateCount)
      ordinalTwo = intercept[ParameterizedVerilogException] {
        counter.loadOrdinal(2)
      }
    }
    assert(
      ordinalTwo.code ==
        "SPINAL-ELAB-COUNTER-ORDINAL-DOMAIN-OUT-OF-RANGE"
    )

    var ordinalSeven: ParameterizedVerilogException = null
    withSpinalElaboration {
      val stateCount =
        parameter("COUNTER_CONSTANT_STATE_COUNT", default = 8, minimum = 2, maximum = 8)
      val counter = Counter(stateCount)
      ordinalSeven = intercept[ParameterizedVerilogException] {
        counter.loadOrdinal(BigInt(7))
      }
    }
    assert(
      ordinalSeven.code ==
        "SPINAL-ELAB-COUNTER-ORDINAL-DOMAIN-OUT-OF-RANGE"
    )

    var negativeOrdinal: ParameterizedVerilogException = null
    withSpinalElaboration {
      val stateCount =
        parameter("COUNTER_CONSTANT_STATE_COUNT", default = 8, minimum = 2, maximum = 8)
      val counter = Counter(stateCount)
      negativeOrdinal = intercept[ParameterizedVerilogException] {
        counter.loadOrdinal(-1)
      }
    }
    assert(
      negativeOrdinal.code == "SPINAL-ELAB-COUNTER-ORDINAL-NEGATIVE"
    )

    var initialSeven: ParameterizedVerilogException = null
    withSpinalElaboration {
      val stateCount =
        parameter("COUNTER_CONSTANT_STATE_COUNT", default = 8, minimum = 2, maximum = 8)
      val counter = Counter(stateCount)
      initialSeven = intercept[ParameterizedVerilogException] {
        counter.init(7)
      }
    }
    assert(
      initialSeven.code ==
        "SPINAL-ELAB-COUNTER-INIT-WIDTH-INSUFFICIENT"
    )

    var negativeInitial: ParameterizedVerilogException = null
    withSpinalElaboration {
      val stateCount =
        parameter("COUNTER_CONSTANT_STATE_COUNT", default = 8, minimum = 2, maximum = 8)
      val counter = Counter(stateCount)
      negativeInitial = intercept[ParameterizedVerilogException] {
        counter.init(-1)
      }
    }
    assert(negativeInitial.code == "SPINAL-ELAB-COUNTER-INIT-NEGATIVE")

    var initialOutsideShrinkingDomain: ParameterizedVerilogException = null
    withSpinalElaboration {
      val stateCount =
        parameter("COUNTER_INIT_STATE_COUNT", default = 4, minimum = 3, maximum = 4)
      val counter = Counter(stateCount)
      initialOutsideShrinkingDomain = intercept[ParameterizedVerilogException] {
        counter.init(3)
      }
    }
    assert(
      initialOutsideShrinkingDomain.code ==
        "SPINAL-ELAB-COUNTER-INIT-DOMAIN-OUT-OF-RANGE"
    )

    var initialBelowStart: ParameterizedVerilogException = null
    withSpinalElaboration {
      val start =
        parameter("COUNTER_INIT_START", default = 2, minimum = 2, maximum = 3)
      val counter = Counter(start, start + 3)
      initialBelowStart = intercept[ParameterizedVerilogException] {
        counter.init(1)
      }
      counter.init(3)
      counter.init(5)
    }
    assert(
      initialBelowStart.code ==
        "SPINAL-ELAB-COUNTER-INIT-DOMAIN-OUT-OF-RANGE"
    )

    withSpinalElaboration {
      val stateCount =
        parameter("COUNTER_CONSTANT_STATE_COUNT", default = 8, minimum = 2, maximum = 8)
      val counter = Counter(stateCount)
      counter.loadOrdinal(1)
      counter.init(1)
    }

    // The legacy concrete path deliberately retains its native reset semantics.
    withSpinalElaboration {
      Counter(BigInt(3)).init(3)
    }
  }

  test("uintLike rejects negative and minimum-width-insufficient typed constants") {
    var negative: ParameterizedVerilogException = null
    var insufficient: ParameterizedVerilogException = null
    var inexact: ParameterizedVerilogException = null
    var negativeSymbolic: ParameterizedVerilogException = null
    var overflowSymbolic: ParameterizedVerilogException = null
    var distinctRoots: ParameterizedVerilogException = null
    var retainedFittingWidth: Option[ElaborationIntegerExpression] = None
    var retainedCorrelatedWidth: Option[ElaborationIntegerExpression] = None
    withSpinalElaboration {
      negative = intercept[ParameterizedVerilogException] {
        ElabValue.uintLike(
          ElabInt.literal(-1),
          UInt(4 bits),
          "negative_uint_constant"
        )
      }

      val width = parameter(
        "UINT_LIKE_WIDTH",
        default = 4,
        minimum = 3,
        maximum = 5
      )
      val prototype = UInt(width bits)
      insufficient = intercept[ParameterizedVerilogException] {
        ElabValue.uintLike(
          ElabInt.literal(8),
          prototype,
          "minimum_width_overflow"
        )
      }
      val fitting = ElabValue.uintLike(
        ElabInt.literal(7),
        prototype,
        "minimum_width_fitting"
      )
      retainedFittingWidth = ParameterizedWidth.expressionOf(fitting)

      val inexactSchema = ElaborationIntegerParameter(
        "INEXACT_UINT_VALUE",
        default = 1,
        minimum = 0,
        maximum = 3
      )
      val inexactRoot =
        ElaborationIntegerParameterRoot.fresh("INEXACT_UINT_VALUE")
      val inexactValue = ElabInt.fromExpression(
        ElaborationIntegerExpression(
          verilog = "INEXACT_UINT_VALUE",
          default = 1,
          minimum = 0,
          maximum = 3,
          parameters = Vector(inexactSchema),
          parameterRoots = Vector(inexactRoot)
        )
      )
      inexact = intercept[ParameterizedVerilogException] {
        val carrier = UInt(2 bits)
        carrier.assignFrom(
          spinal.core.internals.UIntLiteral(BigInt(1), null, 2)
        )
        ExternalParameterizedValueRegistry.attach(
          carrier,
          inexactValue.expression,
          witness = 1,
          sourceLocation = None
        )
      }

      val signed =
        parameter("SIGNED_UINT_VALUE", default = 1, minimum = -1, maximum = 2)
      negativeSymbolic = intercept[ParameterizedVerilogException] {
        ElabValue.uintLike(signed, UInt(2 bits), "negative_symbolic_value")
      }

      val overflowing =
        parameter("OVERFLOWING_UINT_VALUE", default = 3, minimum = 0, maximum = 8)
      overflowSymbolic = intercept[ParameterizedVerilogException] {
        ElabValue.uintLike(
          overflowing,
          UInt(3 bits),
          "overflowing_symbolic_value"
        )
      }

      val correlated =
        parameter("CORRELATED_UINT_WIDTH", default = 2, minimum = 1, maximum = 3)
      val correlatedValue = ElabValue.uintLike(
        correlated,
        UInt(correlated bits),
        "correlated_symbolic_value"
      )
      retainedCorrelatedWidth = ParameterizedWidth.expressionOf(correlatedValue)

      val sharedSchema = ElaborationIntegerParameter(
        "DISTINCT_UINT_DOMAIN",
        default = 2,
        minimum = 1,
        maximum = 3
      )
      def exactOn(root: ElaborationIntegerParameterRoot): ElabInt =
        ElabInt.fromSingleRootExpressionTrusted(
          ElaborationIntegerExpression(
            verilog = "DISTINCT_UINT_DOMAIN",
            default = 2,
            minimum = 1,
            maximum = 3,
            parameters = Vector(sharedSchema),
            parameterRoots = Vector(root)
          ),
          Vector(BigInt(1) -> BigInt(1), BigInt(2) -> BigInt(2), BigInt(3) -> BigInt(3))
        )
      val distinctValue = exactOn(
        ElaborationIntegerParameterRoot.fresh("DISTINCT_UINT_DOMAIN")
      )
      val distinctWidth = exactOn(
        ElaborationIntegerParameterRoot.fresh("DISTINCT_UINT_DOMAIN")
      )
      distinctRoots = intercept[ParameterizedVerilogException] {
        ElabValue.uintLike(
          distinctValue,
          UInt(distinctWidth bits),
          "distinct_root_symbolic_value"
        )
      }
    }

    assert(
      negative.code ==
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT"
    )
    assert(
      insufficient.code ==
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT"
    )
    assert(
      inexact.code ==
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-EXACT-DOMAIN-REQUIRED"
    )
    assert(
      negativeSymbolic.code ==
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-DOMAIN-UNSUPPORTED"
    )
    assert(
      overflowSymbolic.code ==
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT"
    )
    assert(
      distinctRoots.code ==
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT"
    )
    assert(retainedFittingWidth.exists(_.verilog == "UINT_LIKE_WIDTH"))
    assert(
      retainedCorrelatedWidth.exists(_.verilog == "CORRELATED_UINT_WIDTH")
    )
  }

  test("uintLike rejects malformed raw exact evidence before projection normalization") {
    var failure: ParameterizedVerilogException = null
    var retainedValueRegistered = true
    withSpinalElaboration {
      val schema = ElaborationIntegerParameter(
        "RAW_EXACT_VALUE",
        default = 2,
        minimum = 1,
        maximum = 3
      )
      val root =
        ElaborationIntegerParameterRoot.fresh("RAW_EXACT_VALUE")
      val forged = ElabInt.fromTrustedExactExpressionForTest(
        ElaborationIntegerExpression(
          verilog = "RAW_EXACT_VALUE",
          default = 2,
          minimum = 1,
          maximum = 3,
          parameters = Vector(schema),
          parameterRoots = Vector(root),
          exactDomain = Some(
            ElaborationExactDomain.checked[BigInt](
              root,
              schema,
              Vector(
                BigInt(1) -> BigInt(4),
                BigInt(2) -> BigInt(5),
                BigInt(3) -> BigInt(6)
              ),
              sourceLocation = None,
              role = "malformed raw typed UInt value"
            )
          )
        )
      )

      failure = intercept[ParameterizedVerilogException] {
        ElabValue.uintLike(
          forged,
          UInt(3 bits),
          "raw_exact_value"
        )
      }
      retainedValueRegistered = ExternalParameterizedValueRegistry.valuesOf(Component.current).nonEmpty
    }

    assert(
      failure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-EXACT-DOMAIN-REQUIRED"
    )
    assert(!retainedValueRegistered)
  }

  test("retained UInt attach rejects malformed summaries before registration") {
    var inconsistent: ParameterizedVerilogException = null
    var rangedRootless: ParameterizedVerilogException = null
    var noncanonicalRootless: ParameterizedVerilogException = null
    var duplicateExact: ParameterizedVerilogException = null
    var foreignExactRoot: ParameterizedVerilogException = null
    var foreignExactSchema: ParameterizedVerilogException = null
    var looseExactExtrema: ParameterizedVerilogException = null
    var mismatchedExactDefault: ParameterizedVerilogException = null
    var inconsistentRegistered = true
    var rangedRootlessRegistered = true
    var noncanonicalRootlessRegistered = true
    var duplicateExactRegistered = true
    var foreignExactRootRegistered = true
    var foreignExactSchemaRegistered = true
    var looseExactExtremaRegistered = true
    var mismatchedExactDefaultRegistered = true
    var validRootlessAccepted = false

    withSpinalElaboration {
      val carrierWidth = parameter(
        "ROOTLESS_VALUE_CARRIER_WIDTH",
        default = 4,
        minimum = 4,
        maximum = 5
      )
      def retainedCarrier(
          name: String,
          witness: BigInt = BigInt(8)
      ): UInt = {
        val carrier = UInt(carrierWidth bits).setName(name)
        carrier.assignFrom(
          spinal.core.internals.UIntLiteral(witness, null, 4)
        )
        carrier
      }

      val inconsistentCarrier = retainedCarrier("inconsistent_rootless_value")
      inconsistent = intercept[ParameterizedVerilogException] {
        ExternalParameterizedValueRegistry.attach(
          inconsistentCarrier,
          ElaborationIntegerExpression(
            verilog = "8",
            default = 8,
            minimum = 0,
            maximum = 0,
            parameters = Vector.empty
          ),
          witness = 8,
          sourceLocation = None
        )
      }
      inconsistentRegistered = ExternalParameterizedValueRegistry.recordOf(inconsistentCarrier).nonEmpty

      val rangedRootlessCarrier = retainedCarrier("ranged_rootless_value")
      rangedRootless = intercept[ParameterizedVerilogException] {
        ExternalParameterizedValueRegistry.attach(
          rangedRootlessCarrier,
          ElaborationIntegerExpression(
            verilog = "8",
            default = 8,
            minimum = 0,
            maximum = 8,
            parameters = Vector.empty
          ),
          witness = 8,
          sourceLocation = None
        )
      }
      rangedRootlessRegistered = ExternalParameterizedValueRegistry.recordOf(rangedRootlessCarrier).nonEmpty

      val noncanonicalCarrier = retainedCarrier("noncanonical_rootless_value")
      noncanonicalRootless = intercept[ParameterizedVerilogException] {
        ExternalParameterizedValueRegistry.attach(
          noncanonicalCarrier,
          ElaborationIntegerExpression(
            verilog = "UNDECLARED_RETAINED_VALUE",
            default = 8,
            minimum = 8,
            maximum = 8,
            parameters = Vector.empty
          ),
          witness = 8,
          sourceLocation = None
        )
      }
      noncanonicalRootlessRegistered = ExternalParameterizedValueRegistry
        .recordOf(noncanonicalCarrier)
        .nonEmpty

      val validCarrier = retainedCarrier("valid_rootless_value")
      ExternalParameterizedValueRegistry.attach(
        validCarrier,
        ElabInt.literal(8).expression,
        witness = 8,
        sourceLocation = None
      )
      validRootlessAccepted = ExternalParameterizedValueRegistry
        .recordOf(validCarrier)
        .exists(record => record.witness == 8 && record.expression.parameters.isEmpty)

      val schema = ElaborationIntegerParameter(
        "DUPLICATE_EXACT_VALUE",
        default = 1,
        minimum = 0,
        maximum = 1
      )
      val root =
        ElaborationIntegerParameterRoot.fresh("DUPLICATE_EXACT_VALUE")
      val duplicateDomain = ElaborationExactDomain[BigInt](
        root,
        schema,
        Vector(
          BigInt(0) -> BigInt(0),
          BigInt(1) -> BigInt(1),
          BigInt(1) -> BigInt(1)
        )
      )
      val duplicateExpression = ElabInt.authenticateExactExpressionForTest(
        ElaborationIntegerExpression(
          verilog = "DUPLICATE_EXACT_VALUE",
          default = 1,
          minimum = 0,
          maximum = 1,
          parameters = Vector(schema),
          parameterRoots = Vector(root),
          exactDomain = Some(duplicateDomain)
        )
      )
      val duplicateCarrier = UInt(2 bits).setName("duplicate_exact_value")
      duplicateCarrier.assignFrom(
        spinal.core.internals.UIntLiteral(BigInt(1), null, 2)
      )
      duplicateExact = intercept[ParameterizedVerilogException] {
        ExternalParameterizedValueRegistry.attach(
          duplicateCarrier,
          duplicateExpression,
          witness = 1,
          sourceLocation = None
        )
      }
      duplicateExactRegistered = ExternalParameterizedValueRegistry.recordOf(duplicateCarrier).nonEmpty

      val authoritativeRoot =
        ElaborationIntegerParameterRoot.fresh("DUPLICATE_EXACT_VALUE")
      val foreignRoot =
        ElaborationIntegerParameterRoot.fresh("DUPLICATE_EXACT_VALUE")
      val foreignDomain = ElaborationExactDomain[BigInt](
        foreignRoot,
        schema,
        Vector(BigInt(0) -> BigInt(0), BigInt(1) -> BigInt(1))
      )
      val foreignExpression = ElabInt.authenticateExactExpressionForTest(
        ElaborationIntegerExpression(
          verilog = "DUPLICATE_EXACT_VALUE",
          default = 1,
          minimum = 0,
          maximum = 1,
          parameters = Vector(schema),
          parameterRoots = Vector(authoritativeRoot),
          exactDomain = Some(foreignDomain)
        )
      )
      val foreignCarrier = UInt(2 bits).setName("foreign_exact_root_value")
      foreignCarrier.assignFrom(
        spinal.core.internals.UIntLiteral(BigInt(1), null, 2)
      )
      foreignExactRoot = intercept[ParameterizedVerilogException] {
        ExternalParameterizedValueRegistry.attach(
          foreignCarrier,
          foreignExpression,
          witness = 1,
          sourceLocation = None
        )
      }
      foreignExactRootRegistered = ExternalParameterizedValueRegistry.recordOf(foreignCarrier).nonEmpty

      val authoritativeSchema = ElaborationIntegerParameter(
        "FOREIGN_EXACT_SCHEMA_VALUE",
        default = 1,
        minimum = 0,
        maximum = 1
      )
      val equalButForeignSchema = authoritativeSchema.copy()
      val schemaRoot =
        ElaborationIntegerParameterRoot.fresh("FOREIGN_EXACT_SCHEMA_VALUE")
      val schemaDomain = ElaborationExactDomain[BigInt](
        schemaRoot,
        authoritativeSchema,
        Vector(BigInt(0) -> BigInt(0), BigInt(1) -> BigInt(1))
      )
      val foreignSchemaExpression = ElabInt.authenticateExactExpressionForTest(
        ElaborationIntegerExpression(
          verilog = "FOREIGN_EXACT_SCHEMA_VALUE",
          default = 1,
          minimum = 0,
          maximum = 1,
          parameters = Vector(equalButForeignSchema),
          parameterRoots = Vector(schemaRoot),
          exactDomain = Some(schemaDomain)
        )
      )
      val foreignSchemaCarrier =
        retainedCarrier("foreign_exact_schema_value", witness = 1)
      foreignExactSchema = intercept[ParameterizedVerilogException] {
        ExternalParameterizedValueRegistry.attach(
          foreignSchemaCarrier,
          foreignSchemaExpression,
          witness = 1,
          sourceLocation = None
        )
      }
      foreignExactSchemaRegistered = ExternalParameterizedValueRegistry
        .recordOf(foreignSchemaCarrier)
        .nonEmpty

      val extremaSchema = ElaborationIntegerParameter(
        "LOOSE_EXACT_EXTREMA_VALUE",
        default = 1,
        minimum = 0,
        maximum = 1
      )
      val extremaRoot =
        ElaborationIntegerParameterRoot.fresh("LOOSE_EXACT_EXTREMA_VALUE")
      val looseExtremaExpression = ElabInt.authenticateExactExpressionForTest(
        ElaborationIntegerExpression(
          verilog = "LOOSE_EXACT_EXTREMA_VALUE",
          default = 1,
          minimum = 0,
          maximum = 2,
          parameters = Vector(extremaSchema),
          parameterRoots = Vector(extremaRoot),
          exactDomain = Some(
            ElaborationExactDomain[BigInt](
              extremaRoot,
              extremaSchema,
              Vector(BigInt(0) -> BigInt(0), BigInt(1) -> BigInt(1))
            )
          )
        )
      )
      val looseExtremaCarrier =
        retainedCarrier("loose_exact_extrema_value", witness = 1)
      looseExactExtrema = intercept[ParameterizedVerilogException] {
        ExternalParameterizedValueRegistry.attach(
          looseExtremaCarrier,
          looseExtremaExpression,
          witness = 1,
          sourceLocation = None
        )
      }
      looseExactExtremaRegistered = ExternalParameterizedValueRegistry
        .recordOf(looseExtremaCarrier)
        .nonEmpty

      val defaultSchema = ElaborationIntegerParameter(
        "MISMATCHED_EXACT_DEFAULT_VALUE",
        default = 1,
        minimum = 0,
        maximum = 1
      )
      val defaultRoot = ElaborationIntegerParameterRoot.fresh(
        "MISMATCHED_EXACT_DEFAULT_VALUE"
      )
      val mismatchedDefaultExpression = ElabInt.authenticateExactExpressionForTest(
        ElaborationIntegerExpression(
          verilog = "1 - MISMATCHED_EXACT_DEFAULT_VALUE",
          default = 1,
          minimum = 0,
          maximum = 1,
          parameters = Vector(defaultSchema),
          parameterRoots = Vector(defaultRoot),
          exactDomain = Some(
            ElaborationExactDomain[BigInt](
              defaultRoot,
              defaultSchema,
              Vector(BigInt(0) -> BigInt(1), BigInt(1) -> BigInt(0))
            )
          )
        )
      )
      val mismatchedDefaultCarrier =
        retainedCarrier("mismatched_exact_default_value", witness = 1)
      mismatchedExactDefault = intercept[ParameterizedVerilogException] {
        ExternalParameterizedValueRegistry.attach(
          mismatchedDefaultCarrier,
          mismatchedDefaultExpression,
          witness = 1,
          sourceLocation = None
        )
      }
      mismatchedExactDefaultRegistered = ExternalParameterizedValueRegistry
        .recordOf(mismatchedDefaultCarrier)
        .nonEmpty
    }

    assert(inconsistent.code == "SPINAL-ELAB-INT-DOMAIN-INVALID")
    assert(
      rangedRootless.code ==
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-EXACT-DOMAIN-REQUIRED"
    )
    assert(
      duplicateExact.code ==
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-EXACT-DOMAIN-REQUIRED"
    )
    assert(
      noncanonicalRootless.code ==
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-EXACT-DOMAIN-REQUIRED"
    )
    assert(
      foreignExactRoot.code ==
        "SPINAL-ELAB-DOMAIN-ROOT-IDENTITY-MISMATCH"
    )
    Vector(
      foreignExactSchema,
      looseExactExtrema,
      mismatchedExactDefault
    ).foreach { failure =>
      assert(
        failure.code ==
          "SPINAL-PARAMETERIZED-VERILOG-VALUE-EXACT-DOMAIN-REQUIRED"
      )
    }
    assert(!inconsistentRegistered)
    assert(!rangedRootlessRegistered)
    assert(!noncanonicalRootlessRegistered)
    assert(!duplicateExactRegistered)
    assert(!foreignExactRootRegistered)
    assert(!foreignExactSchemaRegistered)
    assert(!looseExactExtremaRegistered)
    assert(!mismatchedExactDefaultRegistered)
    assert(validRootlessAccepted)
  }

  private def parameter(
      name: String,
      default: Int,
      minimum: Int,
      maximum: Int
  ): ElabInt =
    HdlInt
      .param(
        name,
        default = BigInt(default),
        min = BigInt(minimum),
        max = BigInt(maximum)
      )
      .asElabInt

  private def concreteMemoryGraph(memory: Mem[_]): Vector[String] = {
    val ports = Vector.newBuilder[String]
    memory.foreachStatements {
      case read: MemReadSync =>
        ports +=
          s"readSync:${read.getWidth}:${read.getWordsCount}:${read.getAddressWidth}"
      case write: MemWrite =>
        ports +=
          s"write:${write.getWidth}:${write.getWordsCount}:${write.getAddressWidth}"
      case other => ports += s"other:${other.getClass.getName}"
    }
    Vector(
      s"parameterized=${ParameterizedMemory.metadataOf(memory).nonEmpty}",
      s"words=${memory.wordCount}",
      s"width=${memory.getWidth}",
      s"addressWidth=${memory.addressWidth}",
      s"addressTypeWidth=${memory.addressType.getBitsWidth}"
    ) ++ ports.result()
  }

  private def config(directory: Path, filename: String): SpinalConfig = {
    Files.createDirectories(directory)
    val value = SpinalConfig(targetDirectory = directory.toString)
    value.netlistFileName = filename
    value
  }

  private def emitMorph(
      directory: Path,
      filename: String,
      component: => Component
  ): (morphhdl.MorphSingleSourceVerilogReport, String, Path) = {
    val report = MorphVerilog(config(directory, filename))(component)
    val path = directory.resolve(filename)
    (report, read(path), path)
  }

  private def emitConcrete(
      directory: Path,
      filename: String,
      component: => Component
  ): String = {
    SpinalVerilog(config(directory, filename))(component)
    read(directory.resolve(filename))
  }

  private def expectMorphFailure(
      directory: Path,
      filename: String,
      component: => Component,
      code: String
  ): Unit = {
    val value = config(directory, filename)
    MorphVerilog.tryGenerate(value)(component) match {
      case Left(failure) =>
        assert(
          failure.detail.contains(code),
          s"expected $code, received ${failure.detail}"
        )
      case Right(report) =>
        fail(s"expected $code, generation succeeded with $report")
    }
  }

  private def withSpinalElaboration(body: => Unit): Unit =
    withTemporaryDirectory { directory =>
      SpinalVerilog(config(directory, "invalid_domains.v")) {
        new Component {
          val keep = out(Bool())
          keep := False
          body
        }
      }
    }

  private def compileMemorySpecialization(
      directory: Path,
      rtl: Path,
      depth: Int
  ): Unit = {
    val top = s"TypedPrimitiveMemoryDepth$depth"
    val wrapper = directory.resolve(s"$top.v")
    val source =
      s"""module $top;
         |  TypedPrimitiveMemory #(.WIDTH(8), .DEPTH($depth)) dut();
         |endmodule
         |""".stripMargin
    Files.write(wrapper, source.getBytes(StandardCharsets.UTF_8))
    val result = run(
      directory,
      Seq(
        "iverilog",
        "-g2001",
        "-s",
        top,
        "-o",
        directory.resolve(s"$top.out").toString,
        rtl.toString,
        wrapper.toString
      )
    )
    assert(result._1 == 0, s"iverilog DEPTH=$depth failed:\n${result._2}")
  }

  private def synthesizeMemorySpecialization(
      directory: Path,
      rtl: Path,
      depth: Int
  ): Unit = {
    val script = directory.resolve(s"memory_depth_$depth.ys")
    val source =
      s"""read_verilog -defer ${rtl.toAbsolutePath}
         |chparam -set WIDTH 8 -set DEPTH $depth TypedPrimitiveMemory
         |hierarchy -check -top TypedPrimitiveMemory
         |proc
         |memory
         |check
         |""".stripMargin
    Files.write(script, source.getBytes(StandardCharsets.UTF_8))
    val result = run(
      directory,
      Seq("yosys", "-q", "-s", script.toString)
    )
    assert(result._1 == 0, s"yosys DEPTH=$depth failed:\n${result._2}")
  }

  private def lintFiniteRangeSpecialization(
      directory: Path,
      rtl: Path,
      lanes: Int
  ): Unit = {
    val result = run(
      directory,
      Seq(
        "verilator",
        "--lint-only",
        "--language",
        "1364-2001",
        "-Wall",
        "-Wno-DECLFILENAME",
        "-Wno-UNUSED",
        "--top-module",
        "TypedPrimitiveFiniteRanges",
        s"-GLANES=$lanes",
        rtl.toString
      )
    )
    assert(result._1 == 0, s"verilator LANES=$lanes failed:\n${result._2}")
  }

  private def synthesizeFiniteRangeSpecialization(
      directory: Path,
      rtl: Path,
      lanes: Int
  ): Unit = {
    val script = directory.resolve(s"finite_ranges_lanes_$lanes.ys")
    val source =
      s"""read_verilog -defer -noautowire ${rtl.toAbsolutePath}
         |chparam -set LANES $lanes TypedPrimitiveFiniteRanges
         |hierarchy -check -top TypedPrimitiveFiniteRanges
         |proc
         |check -assert
         |synth -top TypedPrimitiveFiniteRanges
         |check -assert
         |""".stripMargin
    Files.write(script, source.getBytes(StandardCharsets.UTF_8))
    val result = run(directory, Seq("yosys", "-q", "-s", script.toString))
    assert(result._1 == 0, s"yosys LANES=$lanes failed:\n${result._2}")
  }

  private def simulateFiniteRangeSpecialization(
      directory: Path,
      rtl: Path,
      lanes: Int
  ): Unit = {
    val top = s"TypedPrimitiveFiniteRangesLanes${lanes}Tb"
    val testbench = directory.resolve(s"$top.v")
    val executable = directory.resolve(s"$top.out")
    val input = BigInt("0123456789abcdef", 16)
    val mask = (BigInt(1) << (lanes * 8)) - 1
    val expected = input & mask
    val inputHex = input.toString(16).reverse.padTo(16, '0').reverse.mkString
    val expectedHex = expected.toString(16).reverse.padTo(16, '0').reverse.mkString
    val source =
      s"""module $top;
         |  reg [63:0] din;
         |  wire [63:0] dout;
         |  TypedPrimitiveFiniteRanges #(.LANES($lanes)) dut (
         |    .din(din),
         |    .dout(dout)
         |  );
         |  initial begin
         |    din = 64'h$inputHex;
         |    #1;
         |    if (dout !== 64'h$expectedHex) begin
         |      $$display("FAIL finite range LANES=%0d dout=%h", $lanes, dout);
         |      $$finish(2);
         |    end
         |    $$display("PASS finite range LANES=%0d", $lanes);
         |    $$finish;
         |  end
         |endmodule
         |""".stripMargin
    Files.write(testbench, source.getBytes(StandardCharsets.UTF_8))
    val compile = run(
      directory,
      Seq(
        "iverilog",
        "-g2001",
        "-Wall",
        "-s",
        top,
        "-o",
        executable.toString,
        rtl.toString,
        testbench.toString
      )
    )
    assert(compile._1 == 0, s"iverilog LANES=$lanes failed:\n${compile._2}")
    val simulation = run(directory, Seq("vvp", executable.toString))
    assert(simulation._1 == 0, s"vvp LANES=$lanes failed:\n${simulation._2}")
    assert(simulation._2.contains(s"PASS finite range LANES=$lanes"), simulation._2)
  }

  private def commandAvailable(name: String): Boolean =
    Process(Seq("sh", "-c", s"command -v $name >/dev/null 2>&1")).! == 0

  private def run(directory: Path, command: Seq[String]): (Int, String) = {
    val output = new StringBuilder
    val logger = ProcessLogger(
      line => output.append(line).append('\n'),
      line => output.append(line).append('\n')
    )
    Process(command, directory.toFile).!(logger) -> output.toString
  }

  private def compactWhitespace(value: String): String =
    value.replaceAll("\\s+", "")

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory[T](body: Path => T): T = {
    val directory = Files.createTempDirectory("morphhdl-typed-primitive-closure-")
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

}
