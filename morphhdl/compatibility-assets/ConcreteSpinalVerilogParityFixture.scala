package morphhdl.compatibility

import java.nio.file.{Files, Path, Paths}

import scala.collection.mutable

import spinal.core._
import spinal.lib._

/**
  * Source fixture compiled unchanged against both the selected upstream
  * SpinalHDL baseline and MorphHDL.  Keep this file limited to ordinary,
  * concrete SpinalHDL APIs which exist in both trees.
  *
  * This is intentionally an external compatibility asset.  The parity gate
  * copies it into tester/src/test for compilation; it is not part of any
  * published production artifact.
  */
object ConcreteSpinalVerilogParityClient {
  final case class StructuredPayload() extends Bundle {
    val data = UInt(11 bits)
    val delta = SInt(7 bits)
    val flags = Bits(3 bits)
  }

  final class PrimitiveAndProcessFixture extends Component {
    val io = new Bundle {
      val bitsIn = in Bits (13 bits)
      val uintIn = in UInt (11 bits)
      val sintIn = in SInt (10 bits)
      val enable = in Bool ()
      val select = in Bool ()

      val bitsOut = out Bits (17 bits)
      val uintOut = out UInt (16 bits)
      val sintOut = out SInt (15 bits)
      val accumulator = out UInt (16 bits)
      val state = out Bits (3 bits)
    }

    val widenedBits = io.bitsIn.resize(17)
    val rotatedBits = widenedBits.rotateLeft(3)
    io.bitsOut := rotatedBits ^ B(0x1555, 17 bits)

    val widenedUInt = io.uintIn.resize(15)
    val uintExpression = widenedUInt +^ U(7, 15 bits)
    io.uintOut := uintExpression

    val widenedSInt = io.sintIn.resize(14)
    val sintExpression = widenedSInt +^ S(-3, 14 bits)
    io.sintOut := sintExpression

    val accumulator = Reg(UInt(16 bits)) init (0)
    val state = Reg(Bits(3 bits)) init (0)

    when(io.enable) {
      accumulator := accumulator + io.uintIn.resize(16)
      state := state(1 downto 0) ## io.select
    } elsewhen (io.select) {
      accumulator := accumulator - U(1, 16 bits)
      state := B"3'b101"
    } otherwise {
      state := state.rotateLeft(1)
    }

    switch(io.bitsIn(2 downto 0)) {
      is(B"3'b000") {
        accumulator := 0
      }
      is(B"3'b111") {
        accumulator := accumulator ^ U(0x55aa, 16 bits)
      }
      default {
      }
    }

    io.accumulator := accumulator
    io.state := state
  }

  final class StructuredChild(payloadType: HardType[StructuredPayload]) extends Component {
    val io = new Bundle {
      val input = in(payloadType())
      val output = out(payloadType())
    }

    val localCopy = cloneOf(io.input)
    localCopy := io.input
    io.output.data := localCopy.data + U(1, 11 bits)
    io.output.delta := localCopy.delta.resize(7)
    io.output.flags := localCopy.flags.reversed
  }

  final class StructureAndHierarchyFixture extends Component {
    private val payloadType = HardType(StructuredPayload())

    val io = new Bundle {
      val input = in(payloadType())
      val vectorInput = in(Vec(payloadType(), 3))
      val output = out(payloadType())
      val vectorOutput = out(Vec(payloadType(), 3))
    }

    val child0 = new StructuredChild(payloadType)
    val child1 = new StructuredChild(payloadType)
    val child2 = new StructuredChild(payloadType)
    child0.io.input := io.vectorInput(0)
    child1.io.input := io.vectorInput(1)
    child2.io.input := io.vectorInput(2)
    io.vectorOutput(0) := child0.io.output
    io.vectorOutput(1) := child1.io.output
    io.vectorOutput(2) := child2.io.output

    val selected = cloneOf(io.input)
    selected := io.input
    io.output.data := child1.io.output.data
    io.output.delta := selected.delta
    io.output.flags := selected.flags
  }

  final class MemoryFixture extends Component {
    val io = new Bundle {
      val writeEnable = in Bool ()
      val writeAddress = in UInt (3 bits)
      val writeData = in Bits (16 bits)
      val readEnable = in Bool ()
      val readAddress = in UInt (3 bits)
      val syncData = out Bits (16 bits)
      val asyncData = out Bits (16 bits)
    }

    val memory = Mem(Bits(16 bits), 5)
    memory.write(
      address = io.writeAddress,
      data = io.writeData,
      enable = io.writeEnable
    )
    io.syncData := memory.readSync(
      address = io.readAddress,
      enable = io.readEnable
    )
    io.asyncData := memory.readAsync(io.readAddress)
  }

  final class CounterStreamFlowFixture extends Component {
    val io = new Bundle {
      val tick = in Bool ()
      val clear = in Bool ()
      val streamInput = slave Stream (UInt(12 bits))
      val streamOutput = master Stream (UInt(12 bits))
      val flowInput = slave Flow (Bits(9 bits))
      val flowOutput = master Flow (Bits(9 bits))
      val count = out UInt (3 bits)
      val overflow = out Bool ()
    }

    val counter = Counter(7)
    when(io.tick) {
      counter.increment()
    }
    when(io.clear) {
      counter.clear()
    }
    io.count := counter.value
    io.overflow := counter.willOverflowIfInc

    val mappedStream = io.streamInput.translateWith(io.streamInput.payload + U(1, 12 bits))
    io.streamOutput << mappedStream.m2sPipe()

    val mappedFlow = io.flowInput.translateWith(io.flowInput.payload ^ B(0x12, 9 bits))
    io.flowOutput << mappedFlow.stage()
  }

  final class CounterVariantsFixture extends Component {
    val io = new Bundle {
      val boundedIncrement = in Bool ()
      val downDecrement = in Bool ()
      val bothIncrement = in Bool ()
      val bothDecrement = in Bool ()
      val clear = in Bool ()

      val boundedValue = out UInt (4 bits)
      val boundedAtUpper = out Bool ()
      val downValue = out UInt (4 bits)
      val downAtLower = out Bool ()
      val bothValue = out UInt (3 bits)
      val bothComplete = out Bool ()
      val freeRunValue = out UInt (4 bits)
    }

    val bounded = Counter(3, 10)
    when(io.boundedIncrement) {
      bounded.increment()
    }

    val down = Counter.down(9)
    when(io.downDecrement) {
      down.decrement()
    }

    val both = Counter.both(6)
    when(io.bothIncrement) {
      both.increment()
    }
    when(io.bothDecrement) {
      both.decrement()
    }

    when(io.clear) {
      bounded.clear()
      down.clear()
      both.clear()
    }

    val freeRun = CounterFreeRun(10)

    io.boundedValue := bounded.value
    io.boundedAtUpper := bounded.willOverflowIfInc
    io.downValue := down.value
    io.downAtLower := down.willUnderflowIfDec
    io.bothValue := both.value
    io.bothComplete := both.willComplete
    io.freeRunValue := freeRun.value
  }

  final class StreamWidthAdapterFixture extends Component {
    val io = new Bundle {
      val downInput = slave Stream (Bits(24 bits))
      val downOutput = master Stream (Bits(8 bits))
      val upInput = slave Stream (Bits(8 bits))
      val upOutput = master Stream (Bits(24 bits))
    }

    StreamWidthAdapter(io.downInput, io.downOutput, LOWER_FIRST)
    StreamWidthAdapter(io.upInput, io.upOutput, HIGHER_FIRST)
  }

  final class StreamFifoFixture(depth: Int) extends Component {
    val io = new Bundle {
      val push = slave Stream (Bits(12 bits))
      val pop = master Stream (Bits(12 bits))
      val flush = in Bool ()
      val occupancy = out UInt (log2Up(depth + 1) bits)
      val availability = out UInt (log2Up(depth + 1) bits)
    }

    val fifo = StreamFifo(Bits(12 bits), depth)
    fifo.io.push << io.push
    io.pop << fifo.io.pop
    fifo.io.flush := io.flush
    io.occupancy := fifo.io.occupancy
    io.availability := fifo.io.availability
  }

  final class ConfiguredStreamFifoFixture(
      depth: Int,
      withAsyncRead: Boolean,
      withBypass: Boolean,
      forFMax: Boolean,
      useVec: Boolean
  ) extends Component {
    val io = new Bundle {
      val push = slave Stream (Bits(12 bits))
      val pop = master Stream (Bits(12 bits))
      val flush = in Bool ()
      val occupancy = out UInt (log2Up(depth + 1) bits)
      val availability = out UInt (log2Up(depth + 1) bits)
    }

    val fifo = new StreamFifo(
      dataType = Bits(12 bits),
      depth = depth,
      withAsyncRead = withAsyncRead,
      withBypass = withBypass,
      allowExtraMsb = true,
      forFMax = forFMax,
      useVec = useVec
    )
    fifo.io.push << io.push
    io.pop << fifo.io.pop
    fifo.io.flush := io.flush
    io.occupancy := fifo.io.occupancy
    io.availability := fifo.io.availability
  }
}

object ConcreteSpinalVerilogParityFixture {
  import ConcreteSpinalVerilogParityClient._

  private def deterministicBaseConfig: SpinalConfig =
    SpinalConfig(
      oneFilePerComponent = true,
      nameWhenByFile = false,
      genLineComments = false,
      noRandBoot = true,
      headerWithDate = false,
      headerWithRepoHash = false,
      rtlHeader = null,
      withTimescale = false,
      printFilelist = false,
      reportIncludeSourceLocation = false
    )

  /**
    * SpinalConfig is a case class with mutable collection fields.  A plain
    * copy aliases them, so each generation receives independent collections.
    */
  private def isolatedConfig(base: SpinalConfig, target: Path): SpinalConfig =
    base.copy(
      flags = mutable.HashSet(base.flags.toSeq: _*),
      debugComponents = mutable.HashSet(base.debugComponents.toSeq: _*),
      targetDirectory = target.toString,
      phasesInserters = base.phasesInserters.clone(),
      transformationPhases = base.transformationPhases.clone(),
      memBlackBoxers = base.memBlackBoxers.clone(),
      scopeProperties = base.scopeProperties.clone()
    )

  private def generate[T <: Component](root: Path, name: String)(component: => T): Unit = {
    val target = root.resolve(name)
    Files.createDirectories(target)
    SpinalVerilog(isolatedConfig(deterministicBaseConfig, target))(component)
  }

  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 1, "expected exactly one generated-output directory")
    val root = Paths.get(arguments(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)

    generate(root, "primitive-process")(new PrimitiveAndProcessFixture)
    generate(root, "structure-hierarchy")(new StructureAndHierarchyFixture)
    generate(root, "memory")(new MemoryFixture)
    generate(root, "counter-stream-flow")(new CounterStreamFlowFixture)
    generate(root, "counter-variants")(new CounterVariantsFixture)
    generate(root, "stream-width-adapter")(new StreamWidthAdapterFixture)
    Seq(0, 1, 3, 5, 8).foreach { depth =>
      generate(root, "stream-fifo-depth-" + depth)(new StreamFifoFixture(depth))
    }
    generate(root, "stream-fifo-fmax-depth-5")(
      new ConfiguredStreamFifoFixture(
        depth = 5,
        withAsyncRead = false,
        withBypass = false,
        forFMax = true,
        useVec = false
      )
    )
    generate(root, "stream-fifo-async-ram-bypass-depth-5")(
      new ConfiguredStreamFifoFixture(
        depth = 5,
        withAsyncRead = true,
        withBypass = true,
        forFMax = false,
        useVec = false
      )
    )
    generate(root, "stream-fifo-async-vec-bypass-depth-5")(
      new ConfiguredStreamFifoFixture(
        depth = 5,
        withAsyncRead = true,
        withBypass = true,
        forFMax = false,
        useVec = true
      )
    )
  }
}
