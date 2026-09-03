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

  /**
    * Runtime-only access to the MorphHDL typed StreamFifoCC surface.
    *
    * This compatibility source must remain byte-identical when compiled against
    * the selected upstream tree, where ElabInt does not exist. Reflection lets
    * the current-runtime run exercise every public typed-literal companion and
    * Stream-helper entry point without introducing a source dependency which
    * the upstream compiler cannot resolve. Every reflected lookup checks the
    * exact erased signature, so a missing or accidentally changed typed overload
    * fails the parity gate instead of silently falling back to the legacy path.
    */
  private[compatibility] object TypedStreamFifoCCEntryPoints {
    private val elabIntClassName = "spinal.core.ElabInt"
    private val hardTypeClassName = "spinal.core.HardType"
    private val streamClassName = "spinal.lib.Stream"
    private val clockDomainClassName = "spinal.core.ClockDomain"

    private lazy val elabIntClass = Class.forName(elabIntClassName)
    private lazy val elabIntModule =
      Class.forName(elabIntClassName + "$").getField("MODULE$").get(null)
    private lazy val literalMethod =
      elabIntModule.getClass.getMethod("literal", java.lang.Integer.TYPE)
    private lazy val fifoCompanion =
      Class.forName("spinal.lib.StreamFifoCC$").getField("MODULE$").get(null)

    private def parameterNames(member: java.lang.reflect.Executable): Seq[String] =
      member.getParameterTypes.toSeq.map(_.getName)

    private def method(
        receiver: AnyRef,
        name: String,
        parameters: Seq[String]
    ): java.lang.reflect.Method = {
      val matches = receiver.getClass.getMethods.filter { candidate =>
        candidate.getName == name && parameterNames(candidate) == parameters
      }
      require(
        matches.length == 1,
        s"expected one reflected $name(${parameters.mkString(", ")}) entry point, found ${matches.length}"
      )
      matches.head
    }

    private lazy val typedFactory = method(
      fifoCompanion,
      "apply",
      Seq(
        hardTypeClassName,
        elabIntClassName,
        clockDomainClassName,
        clockDomainClassName
      )
    )

    private lazy val typedFactoryWithReset = method(
      fifoCompanion,
      "apply",
      Seq(
        hardTypeClassName,
        elabIntClassName,
        clockDomainClassName,
        clockDomainClassName,
        java.lang.Boolean.TYPE.getName
      )
    )

    private lazy val typedConnectedFactory = method(
      fifoCompanion,
      "apply",
      Seq(
        streamClassName,
        streamClassName,
        elabIntClassName,
        clockDomainClassName,
        clockDomainClassName
      )
    )

    def requireAvailable(): Unit = {
      elabIntClass
      literalMethod
      typedFactory
      typedFactoryWithReset
      typedConnectedFactory
      ()
    }

    private def literal(depth: Int): AnyRef =
      literalMethod.invoke(elabIntModule, Int.box(depth)).asInstanceOf[AnyRef]

    def factory(
        dataType: HardType[Bits],
        depth: Int,
        pushClock: ClockDomain,
        popClock: ClockDomain
    ): StreamFifoCC[Bits] =
      typedFactory
        .invoke(fifoCompanion, dataType, literal(depth), pushClock, popClock)
        .asInstanceOf[StreamFifoCC[Bits]]

    def factory(
        dataType: HardType[Bits],
        depth: Int,
        pushClock: ClockDomain,
        popClock: ClockDomain,
        withPopBufferedReset: Boolean
    ): StreamFifoCC[Bits] =
      typedFactoryWithReset
        .invoke(
          fifoCompanion,
          dataType,
          literal(depth),
          pushClock,
          popClock,
          Boolean.box(withPopBufferedReset)
        )
        .asInstanceOf[StreamFifoCC[Bits]]

    def connected(
        push: Stream[Bits],
        pop: Stream[Bits],
        depth: Int,
        pushClock: ClockDomain,
        popClock: ClockDomain
    ): StreamFifoCC[Bits] =
      typedConnectedFactory
        .invoke(fifoCompanion, push, pop, literal(depth), pushClock, popClock)
        .asInstanceOf[StreamFifoCC[Bits]]

    def queue(
        push: Stream[Bits],
        depth: Int,
        pushClock: ClockDomain,
        popClock: ClockDomain
    ): Stream[Bits] =
      method(
        push,
        "queue",
        Seq(elabIntClassName, clockDomainClassName, clockDomainClassName)
      ).invoke(push, literal(depth), pushClock, popClock)
        .asInstanceOf[Stream[Bits]]

    def queueWithPushOccupancy(
        push: Stream[Bits],
        depth: Int,
        pushClock: ClockDomain,
        popClock: ClockDomain
    ): (Stream[Bits], UInt) =
      method(
        push,
        "queueWithPushOccupancy",
        Seq(elabIntClassName, clockDomainClassName, clockDomainClassName)
      ).invoke(push, literal(depth), pushClock, popClock)
        .asInstanceOf[(Stream[Bits], UInt)]
  }

  /**
    * One fixed-topology concrete CDC matrix. The constructor instance always
    * uses the public legacy Int constructor as an ABI and RTL oracle. The
    * upstream run and the legacy current inventory use Int for every remaining
    * instance; the typed current inventory uses ElabInt.literal through each
    * exact public reflected entry point above.
    */
  final class StreamFifoCCParityFixture(
      depth: Int,
      withPopBufferedReset: Boolean,
      typed: Boolean
  ) extends Component {
    private val payloadType = HardType(Bits(12 bits))
    private val pushClock = ClockDomain.external("pushClock")
    private val popClock = ClockDomain.external("popClock")

    private def underResetPolicy[A](body: => A): A =
      ClockDomain.crossClockBufferPushToPopResetGen(withPopBufferedReset)(body)

    private def idlePush(name: String): Stream[Bits] = {
      val push = Stream(payloadType).setName(name)
      push.valid := False
      push.payload := 0
      push
    }

    private def idlePop(name: String): Stream[Bits] = {
      val pop = Stream(payloadType).setName(name)
      pop.ready := False
      pop.valid.setAsVital()
      pop.payload.setAsVital()
      pop
    }

    private def retain(fifo: StreamFifoCC[Bits]): Unit = {
      fifo.io.push.valid := False
      fifo.io.push.payload := 0
      fifo.io.pop.ready := False
      fifo.io.push.ready.setAsVital()
      fifo.io.pop.valid.setAsVital()
      fifo.io.pop.payload.setAsVital()
      fifo.io.pushOccupancy.setAsVital()
      fifo.io.popOccupancy.setAsVital()
    }

    // The ElabInt primary constructor is definition-side only. Keep the public
    // legacy constructor as the constructor parity oracle in both inventories;
    // the remaining instances exercise every public typed ingress.
    val constructorFifo = new StreamFifoCC(
      payloadType,
      depth,
      pushClock,
      popClock,
      withPopBufferedReset
    )
    retain(constructorFifo)

    val factoryFifo = underResetPolicy {
      if (typed)
        TypedStreamFifoCCEntryPoints.factory(
          payloadType,
          depth,
          pushClock,
          popClock
        )
      else
        StreamFifoCC(payloadType, depth, pushClock, popClock)
    }
    retain(factoryFifo)

    // Upstream has no explicit-reset companion overload. Its constructor is
    // the byte-parity oracle for the current typed explicit-reset factory.
    val explicitResetFactoryFifo =
      if (typed)
        TypedStreamFifoCCEntryPoints.factory(
          payloadType,
          depth,
          pushClock,
          popClock,
          withPopBufferedReset
        )
      else
        new StreamFifoCC(
          payloadType,
          depth,
          pushClock,
          popClock,
          withPopBufferedReset
        )
    retain(explicitResetFactoryFifo)

    val connectedPush = idlePush("connectedPush")
    val connectedPop = idlePop("connectedPop")
    val connectedFifo = underResetPolicy {
      if (typed)
        TypedStreamFifoCCEntryPoints.connected(
          connectedPush,
          connectedPop,
          depth,
          pushClock,
          popClock
        )
      else
        StreamFifoCC(
          connectedPush,
          connectedPop,
          depth,
          pushClock,
          popClock
        )
    }
    connectedFifo.io.push.ready.setAsVital()
    connectedFifo.io.pushOccupancy.setAsVital()
    connectedFifo.io.popOccupancy.setAsVital()

    val queuePush = idlePush("queuePush")
    val queuePop = underResetPolicy {
      if (typed)
        TypedStreamFifoCCEntryPoints.queue(
          queuePush,
          depth,
          pushClock,
          popClock
        )
      else
        queuePush.queue(depth, pushClock, popClock)
    }
    queuePop.ready := False
    queuePop.valid.setAsVital()
    queuePop.payload.setAsVital()

    val occupancyPush = idlePush("occupancyPush")
    val occupancyResult = underResetPolicy {
      if (typed)
        TypedStreamFifoCCEntryPoints.queueWithPushOccupancy(
          occupancyPush,
          depth,
          pushClock,
          popClock
        )
      else
        occupancyPush.queueWithPushOccupancy(depth, pushClock, popClock)
    }
    occupancyResult._1.ready := False
    occupancyResult._1.valid.setAsVital()
    occupancyResult._1.payload.setAsVital()
    occupancyResult._2.setAsVital()
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
    require(
      arguments.length == 1 || arguments.length == 2,
      "expected a generated-output directory and optional legacy|typed mode"
    )
    val root = Paths.get(arguments(0)).toAbsolutePath.normalize()
    val typedStreamFifoCC = arguments.lift(1) match {
      case None | Some("legacy") => false
      case Some("typed")         => true
      case Some(other)            => throw new IllegalArgumentException("unknown parity mode: " + other)
    }
    if (typedStreamFifoCC) TypedStreamFifoCCEntryPoints.requireAvailable()
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
    // "legacy" and "typed" identify the switchable public companion/helper
    // ingress. Both inventories retain the same legacy Int constructor oracle.
    Seq(2, 4, 8, 32).foreach { depth =>
      Seq(false, true).foreach { withPopBufferedReset =>
        val resetName = if (withPopBufferedReset) "buffered" else "separate"
        generate(
          root,
          "stream-fifocc-legacy-depth-" + depth + "-reset-" + resetName
        )(
          new StreamFifoCCParityFixture(
            depth,
            withPopBufferedReset,
            typed = false
          )
        )
        generate(
          root,
          "stream-fifocc-typed-depth-" + depth + "-reset-" + resetName
        )(
          new StreamFifoCCParityFixture(
            depth,
            withPopBufferedReset,
            typed = typedStreamFifoCC
          )
        )
      }
    }
  }
}
