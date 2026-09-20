package morphhdl

import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import morphhdl.frontend.HdlInt

/** Ordinary native StreamFifoCC fixture used by the Increment 57.a source,
  * geometry and CDC-metadata tests. Clock domains and reset policy stay
  * ordinary Scala values; only the FIFO depth crosses the typed boundary.
  */
final class NativeStreamFifoCCParameterizedHarness(
    depth: ElabInt,
    bufferedPopReset: Boolean
) extends Component {
  setDefinitionName(
    if (bufferedPopReset) "NativeStreamFifoCCParameterizedBuffered"
    else "NativeStreamFifoCCParameterizedDirect"
  )

  val io = new Bundle {
    val pushClock = in Bool ()
    val pushReset = in Bool ()
    val popClock = in Bool ()
    val popReset = in Bool ()
    val push = slave Stream (Bits(8 bits))
    val pop = master Stream (Bits(8 bits))
    val pushOccupancy = out UInt (5 bits)
    val popOccupancy = out UInt (5 bits)
  }

  private val config = ClockDomainConfig(
    clockEdge = RISING,
    resetKind = ASYNC,
    resetActiveLevel = HIGH
  )
  private val pushCd = ClockDomain(io.pushClock, io.pushReset, config = config)
  private val popCd = ClockDomain(io.popClock, io.popReset, config = config)

  val fifo = StreamFifoCC(
    HardType(Bits(8 bits)),
    depth,
    pushCd,
    popCd,
    bufferedPopReset
  )
  fifo.setName("fifo")
  fifo.io.push << io.push
  io.pop << fifo.io.pop
  io.pushOccupancy := fifo.io.pushOccupancy.resized
  io.popOccupancy := fifo.io.popOccupancy.resized
}

/** Native CDC FIFO with independently retained payload-width and depth roots. */
final class NativeStreamFifoCCWidthDepthHarness(
    width: HdlInt,
    depth: HdlInt
) extends Component {
  setDefinitionName("NativeStreamFifoCCWidthDepth")

  private val elabWidth = width.asElabInt
  val io = new Bundle {
    val pushClock = in Bool ()
    val pushReset = in Bool ()
    val popClock = in Bool ()
    val popReset = in Bool ()
    val push = slave Stream (Bits(elabWidth bits))
    val pop = master Stream (Bits(elabWidth bits))
  }

  private val config = ClockDomainConfig(
    clockEdge = RISING,
    resetKind = ASYNC,
    resetActiveLevel = HIGH
  )
  private val pushCd = ClockDomain(io.pushClock, io.pushReset, config = config)
  private val popCd = ClockDomain(io.popClock, io.popReset, config = config)

  val fifo = StreamFifoCC(
    HardType(Bits(elabWidth bits)),
    depth.asElabInt,
    pushCd,
    popCd,
    withPopBufferedReset = false
  )
  fifo.io.push << io.push
  io.pop << fifo.io.pop
}

/** A symbolic payload width with an ordinary Int depth exercises the native
  * memory-role path independently from retained depth geometry.
  */
final class NativeStreamFifoCCWidthStaticDepthHarness(
    width: HdlInt,
    depth: Int
) extends Component {
  setDefinitionName("NativeStreamFifoCCWidthStaticDepth")

  private val elabWidth = width.asElabInt
  val io = new Bundle {
    val pushClock = in Bool ()
    val pushReset = in Bool ()
    val popClock = in Bool ()
    val popReset = in Bool ()
    val push = slave Stream (Bits(elabWidth bits))
    val pop = master Stream (Bits(elabWidth bits))
  }

  private val config = ClockDomainConfig(
    clockEdge = RISING,
    resetKind = ASYNC,
    resetActiveLevel = HIGH
  )
  private val pushCd = ClockDomain(io.pushClock, io.pushReset, config = config)
  private val popCd = ClockDomain(io.popClock, io.popReset, config = config)

  val fifo = StreamFifoCC(
    HardType(Bits(elabWidth bits)),
    depth,
    pushCd,
    popCd,
    withPopBufferedReset = false
  )
  fifo.io.push << io.push
  io.pop << fifo.io.pop
}

/** Two independently parameterized aggregate-payload FIFOs deliberately share
  * the same ClockDomain objects. Each FIFO must retain its own generated reset
  * synchronizer; a design-global cache entry would illegally couple the two
  * child definitions and their independent legal-depth alternatives.
  */
final class NativeStreamFifoCCAggregateSharedClockHarness(
    depthA: HdlInt,
    depthB: HdlInt
) extends Component {
  setDefinitionName("NativeStreamFifoCCAggregateSharedClock")

  private def payloadType = Vec(Bits(4 bits), 2)

  val io = new Bundle {
    val pushClock = in Bool ()
    val pushReset = in Bool ()
    val popClock = in Bool ()
    val popReset = in Bool ()
    val pushA = slave Stream (payloadType)
    val popA = master Stream (payloadType)
    val pushB = slave Stream (payloadType)
    val popB = master Stream (payloadType)
  }

  private val config = ClockDomainConfig(
    clockEdge = RISING,
    resetKind = ASYNC,
    resetActiveLevel = HIGH
  )
  private val pushCd = ClockDomain(io.pushClock, io.pushReset, config = config)
  private val popCd = ClockDomain(io.popClock, io.popReset, config = config)

  val fifoA = StreamFifoCC(
    HardType(payloadType),
    depthA.asElabInt,
    pushCd,
    popCd,
    withPopBufferedReset = true
  )
  val fifoB = StreamFifoCC(
    HardType(payloadType),
    depthB.asElabInt,
    pushCd,
    popCd,
    withPopBufferedReset = true
  )

  fifoA.io.push << io.pushA
  io.popA << fifoA.io.pop
  fifoB.io.push << io.pushB
  io.popB << fifoB.io.pop
}

/** Preserve the native BOOT reset policy on the owner-local typed reset path. */
final class NativeStreamFifoCCBootResetHarness(depth: HdlInt)
    extends Component {
  setDefinitionName("NativeStreamFifoCCBootReset")

  val io = new Bundle {
    val pushClock = in Bool ()
    val popClock = in Bool ()
    val popReset = in Bool ()
    val push = slave Stream (Bits(8 bits))
    val pop = master Stream (Bits(8 bits))
  }

  private val pushCd = ClockDomain(
    clock = io.pushClock,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = BOOT,
      resetActiveLevel = HIGH
    )
  )
  private val popCd = ClockDomain(
    clock = io.popClock,
    reset = io.popReset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = HIGH
    )
  )

  val fifo = StreamFifoCC(
    HardType(Bits(8 bits)),
    depth.asElabInt,
    pushCd,
    popCd,
    withPopBufferedReset = true
  )
  fifo.io.push << io.push
  io.pop << fifo.io.pop
}

/** Exercise one typed CDC call surface selected by an ordinary static value. */
final class NativeStreamFifoCCTypedCallSurfaceHarness(
    depth: HdlInt,
    surface: Int
)
    extends Component {
  require(surface >= 0 && surface <= 2, s"unsupported typed call surface $surface")
  setDefinitionName("NativeStreamFifoCCTypedCallSurface")

  val io = new Bundle {
    val pushClock = in Bool ()
    val pushReset = in Bool ()
    val popClock = in Bool ()
    val popReset = in Bool ()

    val push = slave Stream (Bits(8 bits))
    val pop = master Stream (Bits(8 bits))
    val occupied = out UInt (5 bits)
  }

  private val config = ClockDomainConfig(
    clockEdge = RISING,
    resetKind = ASYNC,
    resetActiveLevel = HIGH
  )
  private val pushCd = ClockDomain(io.pushClock, io.pushReset, config = config)
  private val popCd = ClockDomain(io.popClock, io.popReset, config = config)
  private val elabDepth = depth.asElabInt

  surface match {
    case 0 =>
      val fifo = StreamFifoCC(io.push, io.pop, elabDepth, pushCd, popCd)
      io.occupied := fifo.io.pushOccupancy.resized
    case 1 =>
      io.pop << io.push.queue(elabDepth, pushCd, popCd)
      io.occupied := 0
    case 2 =>
      val occupiedResult =
        io.push.queueWithPushOccupancy(elabDepth, pushCd, popCd)
      io.pop << occupiedResult._1
      io.occupied := occupiedResult._2.resized
  }
}

/** Typed formal-observation fixture. The global clock is intentionally
  * independent from both CDC clocks, matching the native formalAsserts API.
  */
final class NativeStreamFifoCCTypedFormalHarness(depth: HdlInt)
    extends Component {
  setDefinitionName("NativeStreamFifoCCTypedFormalHarness")

  val io = new Bundle {
    val pushClock = in Bool ()
    val pushReset = in Bool ()
    val popClock = in Bool ()
    val popReset = in Bool ()
    val globalClock = in Bool ()
    val globalReset = in Bool ()
    val push = slave Stream (Bits(8 bits))
    val pop = master Stream (Bits(8 bits))
  }

  private val config = ClockDomainConfig(
    clockEdge = RISING,
    resetKind = ASYNC,
    resetActiveLevel = HIGH
  )
  private val pushCd = ClockDomain(io.pushClock, io.pushReset, config = config)
  private val popCd = ClockDomain(io.popClock, io.popReset, config = config)
  private val globalCd =
    ClockDomain(io.globalClock, io.globalReset, config = config)

  val fifo = StreamFifoCC(
    HardType(Bits(8 bits)),
    depth.asElabInt,
    pushCd,
    popCd,
    withPopBufferedReset = false
  )
  fifo.io.push << io.push
  io.pop << fifo.io.pop

  val assertionComposite: Composite[StreamFifoCC[Bits]] =
    fifo.formalAsserts(globalCd)
}

/** Isolated typed BufferCC fixture for the by-name initialization contract.
  * The callback deliberately lives outside the child so the test can count
  * evaluations without inspecting BufferCC implementation details.
  */
final class NativeTypedBufferCCInitHarness(
    width: HdlInt,
    stages: Int,
    onInitEvaluation: () => Unit
) extends Component {
  setDefinitionName("NativeTypedBufferCCInitHarness")

  private val elabWidth = width.asElabInt
  val io = new Bundle {
    val dataIn = in Bits (elabWidth bits)
    val dataOut = out Bits (elabWidth bits)
  }

  val buffer = new BufferCC(
    Bits(elabWidth bits),
    {
      onInitEvaluation()
      B(0)
    },
    bufferDepth = Some(stages),
    randBoot = false,
    inputAttributes = List(),
    allBufAttributes = List()
  ).setDefinitionName("NativeTypedBufferCCInit")

  buffer.io.dataIn := io.dataIn
  io.dataOut := buffer.io.dataOut
}

/** A retained register whose initializer and ordinary clear assignment emit
  * the same native witness literal. The fallback must authorize both graph
  * edges before rewriting either one to the public WIDTH expression.
  */
final class NativeRetainedZeroCardinalityHarness(width: HdlInt)
    extends Component {
  setDefinitionName("NativeRetainedZeroCardinality")

  private val elabWidth = width.asElabInt
  val io = new Bundle {
    val clock = in Bool ()
    val reset = in Bool ()
    val clear = in Bool ()
    val value = out UInt (elabWidth bits)
  }

  private val testClockDomain = ClockDomain(
    io.clock,
    io.reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = HIGH
    )
  )
  val clocked = new ClockingArea(testClockDomain) {
    val state = Reg(UInt(elabWidth bits)) init (0)
    when(io.clear) {
      state := 0
    }
    io.value := state
  }
}

/** Persistent, tool-agnostic emitter used by later specialization smoke jobs.
  * It intentionally keeps both reset-policy artifacts in the requested
  * directory rather than using the temporary-directory test harness.
  */
object NativeStreamFifoCCParameterizedSmoke {
  def main(args: Array[String]): Unit = {
    require(
      args.length == 1,
      "usage: NativeStreamFifoCCParameterizedSmoke <target-directory>"
    )
    val directory = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(directory)

    Vector(false, true).foreach { buffered =>
      val mode = if (buffered) "buffered" else "direct"
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = s"native_streamfifocc_parameterized_$mode.v"
      val depth = HdlInt.param(
        "DEPTH",
        default = BigInt(8),
        min = BigInt(2),
        max = BigInt(16)
      )
      MorphVerilog(config) {
        new NativeStreamFifoCCParameterizedHarness(
          depth.asElabInt,
          buffered
        )
      }
    }
  }
}

class NativeStreamFifoCCParameterizedTests extends AnyFunSuite {
  private val LegalDepths = Vector(2, 4, 8, 16)
  private val ConcreteParityDepths = Vector(2, 16)

  private val ModuleDeclaration =
    """(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b""".r
  private val RangedDeclaration =
    """(?m)^\s*(?:\(\*.*?\*\)\s*)?(?:wire|reg)\s+\[([^\]]+)\]\s+([A-Za-z_][A-Za-z0-9_$]*)\s*(?:/\*.*?\*/\s*)?;\s*$""".r
  private val ConstantShiftAssignment =
    """(?m)^\s*assign\s+([A-Za-z_][A-Za-z0-9_$]*)\s*=\s*.*?>{2,3}\s*(1|2|4|8|16)\s*\)*\s*;\s*$""".r

  private sealed trait ConcreteEntry {
    def directoryName: String
  }
  private case object ConstructorEntry extends ConcreteEntry {
    override val directoryName = "constructor"
  }
  private case object IntCompanionEntry extends ConcreteEntry {
    override val directoryName = "int-companion"
  }
  private case object TypedLiteralEntry extends ConcreteEntry {
    override val directoryName = "typed-literal"
  }

  private final class ConcreteParityHarness(
      depthValue: Int,
      bufferedPopReset: Boolean,
      entry: ConcreteEntry
  ) extends Component {
    setDefinitionName(
      s"ConcreteStreamFifoCCDepth${depthValue}${if (bufferedPopReset) "Buffered" else "Direct"}"
    )

    val io = new Bundle {
      val pushClock = in Bool ()
      val pushReset = in Bool ()
      val popClock = in Bool ()
      val popReset = in Bool ()
      val push = slave Stream (Bits(8 bits))
      val pop = master Stream (Bits(8 bits))
      val pushOccupancy = out UInt (5 bits)
      val popOccupancy = out UInt (5 bits)
    }

    private val config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = HIGH
    )
    private val pushCd = ClockDomain(io.pushClock, io.pushReset, config = config)
    private val popCd = ClockDomain(io.popClock, io.popReset, config = config)

    val fifo = entry match {
      case ConstructorEntry =>
        new StreamFifoCC(
          HardType(Bits(8 bits)),
          depthValue,
          pushCd,
          popCd,
          bufferedPopReset
        )
      case IntCompanionEntry =>
        StreamFifoCC(
          HardType(Bits(8 bits)),
          depthValue,
          pushCd,
          popCd,
          bufferedPopReset
        )
      case TypedLiteralEntry =>
        StreamFifoCC(
          HardType(Bits(8 bits)),
          ElabInt.literal(depthValue),
          pushCd,
          popCd,
          bufferedPopReset
        )
    }

    fifo.setName("fifo")
    fifo.io.push << io.push
    io.pop << fifo.io.pop
    io.pushOccupancy := fifo.io.pushOccupancy.resized
    io.popOccupancy := fifo.io.popOccupancy.resized
  }

  private final class LegacyNamedDefaultHarness extends Component {
    setDefinitionName("LegacyNamedDefaultStreamFifoCC")

    val io = new Bundle {
      val pushClock = in Bool ()
      val pushReset = in Bool ()
      val popClock = in Bool ()
      val popReset = in Bool ()
      val push = slave Stream (Bits(8 bits))
      val pop = master Stream (Bits(8 bits))
    }

    private val config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = HIGH
    )
    private val pushCd = ClockDomain(io.pushClock, io.pushReset, config = config)
    private val popCd = ClockDomain(io.popClock, io.popReset, config = config)

    // Reordered names and an omitted reset-policy argument specifically
    // exercise the legacy auxiliary-constructor/default-getter source shape.
    val fifo = new StreamFifoCC(
      popClock = popCd,
      depth = 4,
      dataType = HardType(Bits(8 bits)),
      pushClock = pushCd
    )
    require(fifo.depth == 4)
    require(fifo.ptrWidth == 3)
    require(fifo.withPopBufferedReset)

    fifo.io.push << io.push
    io.pop << fifo.io.pop
  }

  test("legacy JVM and public typed StreamFifoCC call surfaces remain available") {
    val fifoClass = classOf[StreamFifoCC[_]]
    val hardTypeClass = classOf[HardType[_]]
    val clockDomainClass = classOf[ClockDomain]
    val elabIntClass = classOf[ElabInt]
    val streamClass = classOf[spinal.lib.Stream[_]]
    val compositeClass = classOf[Composite[_]]

    val legacyConstructor = fifoClass.getConstructor(
      hardTypeClass,
      java.lang.Integer.TYPE,
      clockDomainClass,
      clockDomainClass,
      java.lang.Boolean.TYPE
    )
    assert(Modifier.isPublic(legacyConstructor.getModifiers))
    Vector(
      "depth" -> java.lang.Integer.TYPE,
      "ptrWidth" -> java.lang.Integer.TYPE,
      "pushClock" -> clockDomainClass,
      "popClock" -> clockDomainClass,
      "withPopBufferedReset" -> java.lang.Boolean.TYPE
    ).foreach { case (name, returnType) =>
      val accessor = fifoClass.getMethod(name)
      assert(Modifier.isPublic(accessor.getModifiers), name)
      assert(accessor.getParameterTypes.isEmpty, name)
      assert(accessor.getReturnType == returnType, name)
    }

    val formalAsserts = fifoClass.getMethod("formalAsserts", clockDomainClass)
    assert(Modifier.isPublic(formalAsserts.getModifiers))
    assert(formalAsserts.getReturnType == compositeClass)

    val companion = StreamFifoCC
    val companionClass = companion.getClass
    def publicApply(parameterTypes: Class[_]*): Unit = {
      val method = companionClass.getMethod("apply", parameterTypes: _*)
      assert(Modifier.isPublic(method.getModifiers), method.toString)
      assert(method.getReturnType == fifoClass, method.toString)
    }

    publicApply(
      hardTypeClass,
      java.lang.Integer.TYPE,
      clockDomainClass,
      clockDomainClass
    )
    publicApply(
      hardTypeClass,
      java.lang.Integer.TYPE,
      clockDomainClass,
      clockDomainClass,
      java.lang.Boolean.TYPE
    )
    publicApply(hardTypeClass, elabIntClass, clockDomainClass, clockDomainClass)
    publicApply(
      hardTypeClass,
      elabIntClass,
      clockDomainClass,
      clockDomainClass,
      java.lang.Boolean.TYPE
    )
    publicApply(
      streamClass,
      streamClass,
      java.lang.Integer.TYPE,
      clockDomainClass,
      clockDomainClass
    )
    publicApply(
      streamClass,
      streamClass,
      elabIntClass,
      clockDomainClass,
      clockDomainClass
    )

    val defaultGetter =
      companionClass.getMethod("$lessinit$greater$default$5")
    assert(Modifier.isPublic(defaultGetter.getModifiers))
    assert(defaultGetter.getParameterTypes.isEmpty)
    assert(defaultGetter.getReturnType == java.lang.Boolean.TYPE)
    ScopeProperty.sandbox {
      assert(defaultGetter.invoke(companion) == Boolean.box(true))
      val restore = ClockDomain.crossClockBufferPushToPopResetGen.set(false)
      try assert(defaultGetter.invoke(companion) == Boolean.box(false))
      finally restore.restore()
    }

    def publicStreamMethod(
        name: String,
        depthType: Class[_],
        returnType: Class[_]
    ): Unit = {
      val method = streamClass.getMethod(
        name,
        depthType,
        clockDomainClass,
        clockDomainClass
      )
      assert(Modifier.isPublic(method.getModifiers), method.toString)
      assert(method.getReturnType == returnType, method.toString)
    }
    publicStreamMethod("queue", java.lang.Integer.TYPE, streamClass)
    publicStreamMethod("queue", elabIntClass, streamClass)
    publicStreamMethod(
      "queueWithPushOccupancy",
      java.lang.Integer.TYPE,
      classOf[Tuple2[_, _]]
    )
    publicStreamMethod(
      "queueWithPushOccupancy",
      elabIntClass,
      classOf[Tuple2[_, _]]
    )
  }

  test("legacy named constructor defaults still elaborate parameter-free") {
    withTemporaryDirectory { directory =>
      val config = generationConfig(directory, "legacy_named_streamfifocc.v")
      val report = SpinalVerilog(config)(new LegacyNamedDefaultHarness)
      val rtl = read(directory.resolve(config.netlistFileName))

      assert(report.toplevel.fifo.depth == 4)
      assert(report.toplevel.fifo.ptrWidth == 3)
      assert(report.toplevel.fifo.withPopBufferedReset)
      assert(rtl.contains("module LegacyNamedDefaultStreamFifoCC ("), rtl)
      assert(!rtl.contains("parameter integer DEPTH"), rtl)
      assert(!rtl.contains(".DEPTH("), rtl)
    }
  }

  test("Int constructor companion and typed literal retain concrete RTL parity") {
    withTemporaryDirectory { directory =>
      for {
        depth <- ConcreteParityDepths
        buffered <- Vector(false, true)
      } {
        val constructor =
          emitConcrete(directory, depth, buffered, ConstructorEntry)
        val intCompanion =
          emitConcrete(directory, depth, buffered, IntCompanionEntry)
        val typedLiteral =
          emitConcrete(directory, depth, buffered, TypedLiteralEntry)

        assertSameBytes(
          constructor,
          intCompanion,
          s"constructor/Int companion depth=$depth buffered=$buffered"
        )
        assertSameBytes(
          constructor,
          typedLiteral,
          s"Int/typed literal depth=$depth buffered=$buffered"
        )

        val rtl = read(constructor)
        assert(!rtl.contains("parameter integer DEPTH"), rtl)
        assert(!rtl.contains(".DEPTH("), rtl)
        assert(!rtl.contains("stream_fifocc_full_mask"), rtl)
        assert(!rtl.contains("stream_fifocc_write_address"), rtl)
      }
    }
  }

  test("symbolic payload width and depth share one native StreamFifoCC definition") {
    withTemporaryDirectory { directory =>
      def emit(
          target: Path
      ): (Path, MorphSingleSourceVerilogReport, NativeStreamFifoCCWidthDepthHarness) = {
        Files.createDirectories(target)
        val config = generationConfig(
          target,
          "native_streamfifocc_width_depth.v"
        )
        val width =
          HdlInt.param("WIDTH", default = 5, min = 1, max = 32)
        val depth =
          HdlInt.param("DEPTH", default = 8, min = 2, max = 16)
        var top: NativeStreamFifoCCWidthDepthHarness = null
        val report = MorphVerilog(config) {
          top = new NativeStreamFifoCCWidthDepthHarness(width, depth)
          top
        }
        (target.resolve(config.netlistFileName), report, top)
      }

      val first = emit(directory.resolve("first"))
      val replay = emit(directory.resolve("replay"))
      assertSameBytes(first._1, replay._1, "joint WIDTH/DEPTH replay")

      val rtl = read(first._1)
      val compact = compactWhitespace(rtl)
      val fifoRtl = moduleDefinition(rtl, "StreamFifoCC")
      val fifoCompact = compactWhitespace(fifoRtl)
      assert(first._2.parameters.map(_.name).toSet == Set("WIDTH", "DEPTH"))
      assert(rtl.contains("parameter integer WIDTH = 5"), rtl)
      assert(rtl.contains("parameter integer DEPTH = 8"), rtl)
      assert(compact.contains(".WIDTH(WIDTH)"), rtl)
      assert(compact.contains(".DEPTH(DEPTH)"), rtl)
      assert(fifoCompact.contains("[WIDTH-1:0]io_push_payload"), fifoRtl)
      assert(fifoCompact.contains("[WIDTH-1:0]io_pop_payload"), fifoRtl)
      assertNamedDeclarationUsesWidth(
        fifoRtl,
        "algorithm_pushCC_writeData",
        "WIDTH-1:0"
      )
      assert(
        fifoCompact.contains("reg[WIDTH-1:0]algorithm_ram[0:DEPTH-1]") ||
          fifoCompact.contains("reg[WIDTH-1:0]algorithm_ram[0:(DEPTH-1)]"),
        fifoRtl
      )

      val guardPositions = allIndicesOf(fifoCompact, "DEPTH&(DEPTH-1)")
      assert(guardPositions.size >= 2, fifoRtl)
      assertSensitizedInvalidInterface(
        fifoCompact.substring(guardPositions(1)),
        Vector("io_pop_payload"),
        fifoRtl
      )
      Vector(first._3.fifo.io.push.payload, first._3.fifo.io.pop.payload)
        .foreach { payload =>
          val retainedWidth = widthOfExpr(payload)
          assert(payload.getBitsWidth == 5, payload.toString)
          assert(retainedWidth.parameters.map(_.name) == Vector("WIDTH"))
        }
      assert(!rtl.contains("ParameterizedStreamFifoCC"), rtl)
      assert(!rtl.contains("NativeIntShadow"), rtl)
    }
  }

  test("symbolic payload width retains named native memory roles at static depth") {
    withTemporaryDirectory { directory =>
      val config = generationConfig(
        directory,
        "native_streamfifocc_width_static_depth.v"
      )
      val width = HdlInt.param("WIDTH", default = 5, min = 1, max = 32)
      var top: NativeStreamFifoCCWidthStaticDepthHarness = null
      val report = MorphVerilog(config) {
        top = new NativeStreamFifoCCWidthStaticDepthHarness(width, depth = 8)
        top
      }
      val rtl = read(directory.resolve(config.netlistFileName))
      val compact = compactWhitespace(rtl)
      val fifoRtl = moduleDefinition(rtl, "StreamFifoCC")
      val fifoCompact = compactWhitespace(fifoRtl)

      assert(report.parameters.map(_.name) == Vector("WIDTH"))
      assert(rtl.contains("parameter integer WIDTH = 5"), rtl)
      assert(!rtl.contains("parameter integer DEPTH"), rtl)
      assert(compact.contains(".WIDTH(WIDTH)"), rtl)
      assert(!compact.contains(".DEPTH("), rtl)
      assert(fifoCompact.contains("[WIDTH-1:0]io_push_payload"), fifoRtl)
      assert(fifoCompact.contains("[WIDTH-1:0]io_pop_payload"), fifoRtl)
      assertNamedDeclarationUsesWidth(
        fifoRtl,
        "pushCC_writeData",
        "WIDTH-1:0"
      )
      assert(fifoRtl.contains("stream_fifocc_write_address"), fifoRtl)
      assert(fifoCompact.contains("reg[WIDTH-1:0]ram[0:7]"), fifoRtl)
      assert(top.fifo.depth == 8)
      assert(top.fifo.ram.wordCount == 8)
      val retainedWidth = widthOfExpr(top.fifo.io.push.payload)
      assert(top.fifo.io.push.payload.getBitsWidth == 5)
      assert(retainedWidth.parameters.map(_.name) == Vector("WIDTH"))
      assert(!rtl.contains("ParameterizedStreamFifoCC"), rtl)
      assert(!rtl.contains("NativeIntShadow"), rtl)
    }
  }

  test("one deterministic native definition retains exact CDC geometry") {
    withTemporaryDirectory { directory =>
      val first = emitParameterized(directory.resolve("first"), buffered = false)
      val replay =
        emitParameterized(directory.resolve("replay"), buffered = false)
      assertSameBytes(first._1, replay._1, "direct reset parameterized replay")

      val rtl = read(first._1)
      val compact = compactWhitespace(rtl)
      val fifoRtl = moduleDefinition(rtl, "StreamFifoCC")
      val fifoCompact = compactWhitespace(fifoRtl)
      val depthParameter = first._2.parameters.find(_.name == "DEPTH")

      assert(depthParameter.nonEmpty)
      assert(depthParameter.get.default == BigInt(8))
      assert(
        depthParameter.get.constraints == Vector(
          paramrtl.IntConstraint.MinInclusive(BigInt(2)),
          paramrtl.IntConstraint.MaxInclusive(BigInt(16))
        )
      )
      assert(moduleNames(rtl).count(_ == "StreamFifoCC") == 1, rtl)
      assert(rtl.contains("module StreamFifoCC #("), rtl)
      assert(rtl.contains("parameter integer DEPTH = 8"), rtl)
      assert(compact.contains(".DEPTH(DEPTH)"), rtl)
      assert(
        fifoCompact.contains("[0:DEPTH-1]") ||
          fifoCompact.contains("[0:(DEPTH-1)]"),
        fifoRtl
      )
      assert(fifoCompact.contains("clog2(DEPTH,1)"), fifoRtl)
      assert(fifoCompact.contains("clog2((DEPTH+1),"), fifoRtl)
      assertNamedDeclarationUsesWidth(
        fifoRtl,
        "stream_fifocc_write_address",
        "clog2(DEPTH,1)"
      )
      assert(
        fifoRtl.contains("DEPTH & (DEPTH - 1)") ||
          fifoRtl.contains("DEPTH & (DEPTH-1)"),
        fifoRtl
      )
      assert(!rtl.contains("ParameterizedStreamFifoCC"), rtl)
      assert(!rtl.contains("NativeIntShadow"), rtl)

      val top = first._3
      assert(top.fifo.depth == 8)
      assert(top.fifo.ptrWidth == 4)
      assert(top.fifo.ram.wordCount == 8)
      Vector[Data](
        top.fifo.popToPushGray,
        top.fifo.pushToPopGray,
        top.fifo.pushCC.pushPtr,
        top.fifo.pushCC.pushPtrPlus,
        top.fifo.pushCC.pushPtrGray,
        top.fifo.pushCC.popPtrGray,
        top.fifo.popCC.popPtr,
        top.fifo.popCC.popPtrPlus,
        top.fifo.popCC.popPtrGray,
        top.fifo.popCC.pushPtrGray,
        top.fifo.popCC.ptrToPush,
        top.fifo.popCC.ptrToOccupancy
      ).foreach(
        assertRetainedWidth(
          _,
          defaultWidth = 4,
          minimum = 2,
          maximum = 5,
          parameterName = "DEPTH"
        )
      )
      assertRetainedWidth(
        top.fifo.popCC.addressGen.payload,
        defaultWidth = 3,
        minimum = 1,
        maximum = 4,
        parameterName = "DEPTH"
      )
      Vector[Data](
        top.fifo.io.pushOccupancy,
        top.fifo.io.popOccupancy
      ).foreach(
        assertRetainedWidth(
          _,
          defaultWidth = 4,
          minimum = 2,
          maximum = 5,
          parameterName = "DEPTH"
        )
      )
      val memoryAddressWidth = top.fifo.ram.addressWidthExpr
      assert(memoryAddressWidth.parameters.map(_.name) == Vector("DEPTH"))
      assert(top.fifo.ram.addressWidth == 3)

      Vector(
        "popToPushGray",
        "pushToPopGray",
        "algorithm_pushCC_pushPtr",
        "stream_fifocc_push_pointer_one",
        "algorithm_pushCC_pushPtrPlus",
        "algorithm_pushCC_pushPtrGrayNext",
        "algorithm_pushCC_pushPtrGray",
        "algorithm_pushCC_popPtrGray",
        "algorithm_popCC_popPtr",
        "stream_fifocc_pop_pointer_one",
        "algorithm_popCC_popPtrPlus",
        "algorithm_popCC_popPtrGray",
        "algorithm_popCC_pushPtrGray",
        "algorithm_popCC_ptrToPush",
        "algorithm_popCC_ptrToOccupancy",
        "algorithm_popCC_decodedPushPtr"
      ).foreach(
        assertNamedDeclarationUsesWidth(
          fifoRtl,
          _,
          "clog2(DEPTH,0)+1"
        )
      )

      val expectedGeometry = Map(
        2 -> ((1, 2, 2)),
        4 -> ((2, 3, 3)),
        8 -> ((3, 4, 4)),
        16 -> ((4, 5, 5))
      )
      LegalDepths.foreach { depth =>
        val addressWidth = scala.math.max(1, log2Up(depth))
        val pointerWidth = log2Up(depth) + 1
        val occupancyWidth = log2Up(depth + 1)
        assert(
          (addressWidth, pointerWidth, occupancyWidth) ==
            expectedGeometry(depth)
        )
      }
    }
  }

  test("derived power-of-two root remains the exact child DEPTH actual") {
    withTemporaryDirectory { directory =>
      val first = emitDerived(directory.resolve("first"))
      val replay = emitDerived(directory.resolve("replay"))
      assertSameBytes(first, replay, "derived power-of-two replay")

      val rtl = read(first)
      val compact = compactWhitespace(rtl)
      val topRtl = moduleDefinition(rtl, "NativeStreamFifoCCDerivedDepth")
      assert(rtl.contains("parameter integer DEPTH_LOG2 = 3"), rtl)
      assert(!topRtl.contains("parameter integer DEPTH ="), rtl)
      assert(compact.contains(".DEPTH((1<<(DEPTH_LOG2)))"), rtl)
      assert(moduleNames(rtl).count(_ == "StreamFifoCC") == 1, rtl)
    }
  }

  test("typed companion connected factory and Stream CDC helpers each emit the native definition") {
    withTemporaryDirectory { directory =>
      Vector("connected", "queue", "queue-with-occupancy").zipWithIndex.foreach {
        case (name, surface) =>
          val target = directory.resolve(name)
          Files.createDirectories(target)
          val config = generationConfig(target, s"typed_$name.v")
          val depth = HdlInt.param("DEPTH", default = 8, min = 2, max = 16)
          MorphVerilog(config)(
            new NativeStreamFifoCCTypedCallSurfaceHarness(depth, surface)
          )
          val rtl = read(target.resolve(config.netlistFileName))
          val compact = compactWhitespace(rtl)

          assert(moduleNames(rtl).count(_ == "StreamFifoCC") == 1, rtl)
          assert(countOccurrences(compact, ".DEPTH(DEPTH)") == 1, rtl)
          assert(!rtl.contains("ParameterizedStreamFifoCC"), rtl)
      }
    }
  }

  test("typed formalAsserts retains Composite ABI and module-scope observations over guarded logic") {
    withTemporaryDirectory { directory =>
      val config = generationConfig(
        directory,
        "typed_streamfifocc_formal_asserts.v"
      )
      val depth = HdlInt.param("DEPTH", default = 8, min = 2, max = 16)
      var top: NativeStreamFifoCCTypedFormalHarness = null
      MorphVerilog(config) {
        top = new NativeStreamFifoCCTypedFormalHarness(depth)
        top
      }

      assert(top.assertionComposite != null)
      assert(top.assertionComposite.self eq top.fifo)

      val rtl = read(directory.resolve(config.netlistFileName))
      val fifoRtl = moduleDefinition(rtl, "StreamFifoCC")
      val compact = compactWhitespace(fifoRtl)
      val observationNames = Vector(
        "formal_stream_fifocc_push_checks",
        "formal_stream_fifocc_pop_checks",
        "formal_stream_fifocc_global_checks"
      )
      val firstGenerate = compact.indexOf("generate")
      assert(firstGenerate >= 0, fifoRtl)
      assert(
        countOccurrences(compact, "assert(formal_stream_fifocc_") == 3,
        fifoRtl
      )
      observationNames.foreach { name =>
        val assertion = s"assert($name);"
        assert(countOccurrences(compact, assertion) == 1, fifoRtl)
        assert(compact.indexOf(assertion) < firstGenerate, fifoRtl)
      }

      val guardPositions = allIndicesOf(compact, "DEPTH&(DEPTH-1)")
      assert(guardPositions.size >= 2, fifoRtl)
      val legalBody = compact.substring(guardPositions.head, guardPositions(1))
      val invalidBody = compact.substring(guardPositions(1))
      assert(!legalBody.contains("assert("), fifoRtl)
      Vector(
        "stream_fifocc_formal_push_decode",
        "stream_fifocc_formal_pop_decode",
        "stream_fifocc_formal_forwarded_decode",
        "stream_fifocc_ready_depth",
        "stream_fifocc_full_depth"
      ).foreach { name =>
        assert(legalBody.contains(name), s"$name escaped the legal owner:\n$fifoRtl")
      }
      observationNames.foreach { name =>
        assert(legalBody.contains(s"$name="), fifoRtl)
        assert(invalidBody.contains(s"$name=1'b1;"), fifoRtl)
      }
    }
  }

  test("exact legal domain uses the public typed companion without an invalid formal owner") {
    withTemporaryDirectory { directory =>
      def emit(
          target: Path
      ): (Path, NativeStreamFifoCCTypedFormalHarness) = {
        Files.createDirectories(target)
        val config = generationConfig(
          target,
          "exact_legal_typed_streamfifocc_formal_asserts.v"
        )
        val depth = HdlInt.param(
          "DEPTH",
          default = BigInt(2),
          min = BigInt(2),
          max = BigInt(2)
        )
        var top: NativeStreamFifoCCTypedFormalHarness = null
        MorphVerilog(config) {
          top = new NativeStreamFifoCCTypedFormalHarness(depth)
          top
        }
        (target.resolve(config.netlistFileName), top)
      }

      val first = emit(directory.resolve("first"))
      val replay = emit(directory.resolve("replay"))
      assertSameBytes(first._1, replay._1, "exact-domain typed formal replay")
      assert(first._2.assertionComposite != null)
      assert(first._2.assertionComposite.self eq first._2.fifo)

      val rtl = read(first._1)
      val fifoRtl = moduleDefinition(rtl, "StreamFifoCC")
      val compactFifo = compactWhitespace(fifoRtl)

      assert(rtl.contains("parameter integer DEPTH = 2"), rtl)
      assert(countOccurrences(compactWhitespace(rtl), ".DEPTH(DEPTH)") == 1, rtl)
      assert(!fifoRtl.contains("generate"), fifoRtl)
      assert(!compactFifo.contains("DEPTH&(DEPTH-1)"), fifoRtl)
      assert(!compactFifo.contains("io_push_ready=1'b0;"), fifoRtl)
      assert(!compactFifo.contains("formal_stream_fifocc_push_checks=1'b1;"), fifoRtl)

      Vector(
        "algorithm_ram",
        "algorithm_pushCC_pushPtr",
        "algorithm_popCC_popPtr",
        "stream_fifocc_formal_push_decode",
        "stream_fifocc_formal_pop_decode",
        "stream_fifocc_formal_forwarded_decode"
      ).foreach(name => assert(fifoRtl.contains(name), fifoRtl))
      Vector(
        "formal_stream_fifocc_push_checks",
        "formal_stream_fifocc_pop_checks",
        "formal_stream_fifocc_global_checks"
      ).foreach { name =>
        assert(countOccurrences(compactFifo, s"assert($name);") == 1, fifoRtl)
      }
    }
  }

  test("typed BufferCC evaluates by-name init exactly once per retained stage") {
    withTemporaryDirectory { directory =>
      val config = generationConfig(directory, "typed_buffercc_init.v")
      val width = HdlInt.param("WIDTH", default = 4, min = 2, max = 8)
      val stageCount = 3
      var evaluations = 0
      var top: NativeTypedBufferCCInitHarness = null

      MorphVerilog(config) {
        top = new NativeTypedBufferCCInitHarness(
          width,
          stageCount,
          () => evaluations += 1
        )
        top
      }

      assert(top != null)
      assert(top.buffer.finalBufferDepth == stageCount)
      assert(top.buffer.buffers.length == stageCount)
      assert(
        evaluations == stageCount,
        s"typed BufferCC evaluated its by-name init $evaluations times for $stageCount retained stages"
      )

      val rtl = read(directory.resolve(config.netlistFileName))
      val bufferRtl = compactWhitespace(
        moduleDefinition(rtl, "NativeTypedBufferCCInit")
      )
      assert(rtl.contains("parameter integer WIDTH = 4"), rtl)
      assert(countOccurrences(compactWhitespace(rtl), ".WIDTH(WIDTH)") == 1, rtl)
      (0 until stageCount).foreach { index =>
        assert(
          bufferRtl.contains(s"[WIDTH-1:0]buffers_$index"),
          bufferRtl
        )
      }
      assert(
        countOccurrences(bufferRtl, "<={WIDTH{1'b0}};") == stageCount,
        bufferRtl
      )
      assert(!bufferRtl.contains("4'b0000"), bufferRtl)
    }
  }

  test("retained zero rewriting preserves exact graph-to-emission cardinality") {
    withTemporaryDirectory { directory =>
      val config = generationConfig(
        directory,
        "retained_zero_cardinality.v"
      )
      var top: NativeRetainedZeroCardinalityHarness = null
      MorphVerilog(config) {
        top = new NativeRetainedZeroCardinalityHarness(
          HdlInt.param("WIDTH", default = 8, min = 2, max = 16)
        )
        top
      }

      assert(top != null)
      val rtl = read(directory.resolve(config.netlistFileName))
      val module = compactWhitespace(
        moduleDefinition(rtl, "NativeRetainedZeroCardinality")
      )
      val stateName = top.clocked.state.getName()
      assert(rtl.contains("parameter integer WIDTH = 8"), rtl)
      assert(
        countOccurrences(module, s"$stateName<={WIDTH{1'b0}};") == 2,
        module
      )
      assert(!module.contains(s"$stateName<=8'h00;"), module)
    }
  }

  test("BufferCC blackbox phase rejects retained width before witness freezing") {
    withTemporaryDirectory { directory =>
      val typedCode =
        "SPINAL-BUFFER-CC-BLACKBOX-TYPED-WIDTH-UNSUPPORTED"
      val typedDirectory = directory.resolve("typed")
      Files.createDirectories(typedDirectory)
      val typedConfig = generationConfig(
        typedDirectory,
        "typed_buffercc_blackbox.v"
      ).addTransformationPhase(new PhaseBufferCCBB)
      val morphFailure = MorphVerilog.tryGenerate(typedConfig) {
        new NativeTypedBufferCCInitHarness(
          HdlInt.param("WIDTH", default = 4, min = 2, max = 8),
          2,
          () => ()
        )
      } match {
        case Left(failure) => failure
        case Right(report) =>
          fail(s"typed BufferCC blackbox phase unexpectedly succeeded: $report")
      }
      assert(morphFailure.detail.contains(typedCode), morphFailure.detail)
      val morphCause = morphFailure.cause.collect {
        case error: ParameterizedVerilogException => error
      }
      assert(
        morphCause.exists(_.code == typedCode),
        s"MorphVerilog did not preserve $typedCode as its cause: ${morphFailure.cause}"
      )
      assert(!Files.exists(typedDirectory.resolve(typedConfig.netlistFileName)))

      val rawDirectory = directory.resolve("raw")
      Files.createDirectories(rawDirectory)
      val rawConfig = generationConfig(
        rawDirectory,
        "raw_typed_buffercc_blackbox.v"
      ).addTransformationPhase(new PhaseBufferCCBB)
      val rawFailure = intercept[ParameterizedVerilogException] {
        SpinalVerilog(rawConfig) {
          new NativeTypedBufferCCInitHarness(
            HdlInt.param("WIDTH", default = 4, min = 2, max = 8),
            2,
            () => ()
          )
        }
      }
      assert(rawFailure.code == typedCode, rawFailure.getMessage)
      assert(!Files.exists(rawDirectory.resolve(rawConfig.netlistFileName)))

      val retryDirectory = directory.resolve("ordinary-retry")
      Files.createDirectories(retryDirectory)
      var retryPhaseRuns = 0
      val retryConfig = generationConfig(
        retryDirectory,
        "ordinary_retry_control.v"
      ).addTransformationPhase(new spinal.core.internals.PhaseNetlist {
        override def impl(
            pc: spinal.core.internals.PhaseContext
        ): Unit = {
          retryPhaseRuns += 1
          if (retryPhaseRuns == 1) {
            throw new IllegalStateException("ordinary retry control")
          }
        }
      })
      SpinalVerilog(retryConfig) {
        new NativeTypedBufferCCInitHarness(
          HdlInt.literal(BigInt(4)),
          2,
          () => ()
        )
      }
      assert(
        retryPhaseRuns == 2,
        s"ordinary Spinal failure ran the control phase $retryPhaseRuns times"
      )
      assert(Files.exists(retryDirectory.resolve(retryConfig.netlistFileName)))

      val concreteDirectory = directory.resolve("concrete")
      Files.createDirectories(concreteDirectory)
      val concreteConfig = generationConfig(
        concreteDirectory,
        "concrete_buffercc_blackbox.v"
      ).addTransformationPhase(new PhaseBufferCCBB)
      SpinalVerilog(concreteConfig) {
        new NativeTypedBufferCCInitHarness(
          HdlInt.literal(BigInt(4)),
          2,
          () => ()
        )
      }
      val concreteRtl =
        compactWhitespace(read(concreteDirectory.resolve(concreteConfig.netlistFileName)))
      assert(concreteRtl.contains("BufferCCBlackBox#("), concreteRtl)
      assert(concreteRtl.contains(".WIDTH(4)"), concreteRtl)
    }
  }

  test("attributed BufferCC children retain exact binding CDC tags and reset topology") {
    withTemporaryDirectory { directory =>
      val directFirst =
        emitParameterized(directory.resolve("direct-first"), buffered = false)
      val directReplay =
        emitParameterized(directory.resolve("direct-replay"), buffered = false)
      val bufferedFirst =
        emitParameterized(directory.resolve("buffered-first"), buffered = true)
      val bufferedReplay =
        emitParameterized(directory.resolve("buffered-replay"), buffered = true)

      assertSameBytes(directFirst._1, directReplay._1, "direct reset replay")
      assertSameBytes(
        bufferedFirst._1,
        bufferedReplay._1,
        "buffered reset replay"
      )
      assert(
        !java.util.Arrays.equals(
          Files.readAllBytes(directFirst._1),
          Files.readAllBytes(bufferedFirst._1)
        ),
        "the two static reset topologies unexpectedly emitted identical RTL"
      )

      assertResetTopology(directFirst._3, buffered = false)
      assertResetTopology(bufferedFirst._3, buffered = true)

      Vector(directFirst, bufferedFirst).foreach { emission =>
        val rtl = read(emission._1)
        val fifoRtl = moduleDefinition(rtl, "StreamFifoCC")
        val fifoCompact = compactWhitespace(fifoRtl)
        val widthBindings = fifoRtl
          .split("\\r?\\n")
          .filter(_.contains(".WIDTH("))
          .map(compactWhitespace)
          .toVector
        assert(widthBindings.size == 2, fifoRtl)
        assert(
          widthBindings.forall(line =>
            line.contains("clog2(DEPTH,0)") && line.contains("+1")
          ),
          widthBindings.mkString("\n")
        )

        val guardPositions = allIndicesOf(fifoCompact, "DEPTH&(DEPTH-1)")
        assert(guardPositions.size >= 2, fifoRtl)
        if (emission._3.fifo.withPopBufferedReset) {
          val resetBufferToken = "(*keep_hierarchy=\"TRUE\"*)BufferCC"
          assert(
            countOccurrences(fifoCompact, resetBufferToken) == 1,
            fifoRtl
          )
          val resetBufferIndex = fifoCompact.indexOf(resetBufferToken)
          assert(
            guardPositions.head < resetBufferIndex &&
              resetBufferIndex < guardPositions(1),
            s"the optional pop-reset BufferCC escaped the legal-depth generate branch:\n$fifoRtl"
          )
        }
        Vector(
          "StreamFifoCCPopToPushBufferCC",
          "StreamFifoCCPushToPopBufferCC"
        ).foreach { name =>
          assert(moduleNames(rtl).count(_ == name) == 1, rtl)
          val bufferRtl = compactWhitespace(moduleDefinition(rtl, name))
          assert(bufferRtl.contains("parameterintegerWIDTH=4"), bufferRtl)
          assert(bufferRtl.contains("[WIDTH-1:0]io_dataIn"), bufferRtl)
          assert(bufferRtl.contains("[WIDTH-1:0]io_dataOut"), bufferRtl)
          assert(bufferRtl.contains("[WIDTH-1:0]buffers_0"), bufferRtl)
          assert(bufferRtl.contains("[WIDTH-1:0]buffers_1"), bufferRtl)
          assert(
            countOccurrences(bufferRtl, "<={WIDTH{1'b0}};") == 2,
            bufferRtl
          )
          assert(!bufferRtl.contains("4'b0000"), bufferRtl)

          val instanceToken =
            s"""(*keep_hierarchy="TRUE"*)${name}#("""
          val instanceIndex = fifoCompact.indexOf(instanceToken)
          assert(instanceIndex >= 0, fifoRtl)
          assert(
            guardPositions.head < instanceIndex &&
              instanceIndex < guardPositions(1),
            s"$name instance escaped the legal-depth generate branch:\n$fifoRtl"
          )
        }
        assertGrayShiftGeometry(fifoRtl)
      }
    }
  }

  test("aggregate FIFOs sharing clocks retain independent legal owners and reset buffers") {
    withTemporaryDirectory { directory =>
      def emit(
          target: Path
      ): (Path, NativeStreamFifoCCAggregateSharedClockHarness) = {
        Files.createDirectories(target)
        val config = generationConfig(
          target,
          "aggregate_shared_clock_streamfifocc.v"
        )
        val depthA = HdlInt.param(
          "DEPTH_A",
          default = BigInt(4),
          min = BigInt(2),
          max = BigInt(5)
        )
        val depthB = HdlInt.param(
          "DEPTH_B",
          default = BigInt(4),
          min = BigInt(2),
          max = BigInt(16)
        )
        var top: NativeStreamFifoCCAggregateSharedClockHarness = null
        MorphVerilog(config) {
          top = new NativeStreamFifoCCAggregateSharedClockHarness(
            depthA,
            depthB
          )
          top
        }
        (target.resolve(config.netlistFileName), top)
      }

      val first = emit(directory.resolve("first"))
      val replay = emit(directory.resolve("replay"))
      assertSameBytes(first._1, replay._1, "aggregate shared-clock replay")

      val top = first._2
      def resetBufferOf(fifo: StreamFifoCC[_]): BufferCC[_] = {
        val candidates = fifo.children.collect {
          case buffer: BufferCC[_]
              if buffer.allBufAttributes.exists {
                case tag: crossClockFalsePath =>
                  tag.destType == TimingEndpointType.RESET
                case _ => false
              } =>
            buffer
        }.toVector
        assert(
          candidates.size == 1,
          s"${fifo.definitionName} reset buffers: ${candidates.map(_.definitionName)}"
        )
        candidates.head
      }

      val resetA = resetBufferOf(top.fifoA)
      val resetB = resetBufferOf(top.fifoB)
      assert(!(top.fifoA.finalPopCd eq top.fifoA.popClock))
      assert(!(top.fifoB.finalPopCd eq top.fifoB.popClock))
      assert(!(top.fifoA.finalPopCd eq top.fifoB.finalPopCd))
      assert(!(top.fifoA.finalPopCd.reset eq top.fifoB.finalPopCd.reset))
      assert(top.fifoA.finalPopCd.reset.getComponent() eq top.fifoA)
      assert(top.fifoB.finalPopCd.reset.getComponent() eq top.fifoB)
      assert(resetA.parent eq top.fifoA)
      assert(resetB.parent eq top.fifoB)
      assert(!(resetA eq resetB))
      Vector(top.fifoA, top.fifoB).foreach { fifo =>
        val buffers = fifo.children.collect {
          case buffer: BufferCC[_] => buffer
        }.toVector
        assert(
          buffers.size == 3,
          s"${fifo.definitionName} did not own both pointer synchronizers and its reset synchronizer: ${buffers.map(_.definitionName)}"
        )
      }

      val rtl = read(first._1)
      val compact = compactWhitespace(rtl)
      val fifoDefinitionNames = moduleNames(rtl).filter(name =>
        name == "StreamFifoCC" || name.matches("StreamFifoCC_[0-9]+")
      )
      assert(
        fifoDefinitionNames.toSet == Set("StreamFifoCC", "StreamFifoCC_1"),
        rtl
      )
      Vector(
        "StreamFifoCCPopToPushBufferCC",
        "StreamFifoCCPushToPopBufferCC"
      ).foreach(name => assert(moduleNames(rtl).count(_ == name) == 1, rtl))
      assert(countOccurrences(compact, ".DEPTH(DEPTH_A)") == 1, rtl)
      assert(countOccurrences(compact, ".DEPTH(DEPTH_B)") == 1, rtl)

      fifoDefinitionNames.foreach { definitionName =>
        val fifoRtl = compactWhitespace(moduleDefinition(rtl, definitionName))
        val guardPositions = allIndicesOf(fifoRtl, "DEPTH&(DEPTH-1)")
        assert(guardPositions.size >= 2, fifoRtl)
        val resetBufferToken = "(*keep_hierarchy=\"TRUE\"*)BufferCC"
        assert(
          countOccurrences(fifoRtl, resetBufferToken) == 1,
          fifoRtl
        )
        val resetBufferIndex = fifoRtl.indexOf(resetBufferToken)
        assert(
          guardPositions.head < resetBufferIndex &&
            resetBufferIndex < guardPositions(1),
          s"$definitionName reset synchronizer escaped its legal owner:\n$fifoRtl"
        )
        val invalidBody = fifoRtl.substring(guardPositions(1))
        assertSensitizedInvalidInterface(
          invalidBody,
          Vector("io_pop_payload_0", "io_pop_payload_1"),
          fifoRtl
        )
      }
    }
  }

  test("same-topology unnamed FIFO parents canonicalize by legal-depth bucket") {
    withTemporaryDirectory { directory =>
      def emit(
          target: Path,
          defaultDepth: BigInt,
          maximumA: BigInt,
          maximumB: BigInt
      ): Path = {
        Files.createDirectories(target)
        val config = generationConfig(
          target,
          "same_topology_parent_streamfifocc.v"
        )
        val depthA = HdlInt.param(
          "DEPTH_A",
          default = defaultDepth,
          min = BigInt(2),
          max = maximumA
        )
        val depthB = HdlInt.param(
          "DEPTH_B",
          default = defaultDepth,
          min = BigInt(2),
          max = maximumB
        )
        MorphVerilog(config) {
          new NativeStreamFifoCCAggregateSharedClockHarness(depthA, depthB)
        }
        target.resolve(config.netlistFileName)
      }

      def fifoDefinitionNames(rtl: String): Vector[String] =
        moduleNames(rtl).filter(name =>
          name == "StreamFifoCC" || name.matches("StreamFifoCC_[0-9]+")
        )

      def assertBindingsAndCanonicalBuffers(rtl: String): Unit = {
        val compact = compactWhitespace(rtl)
        assert(countOccurrences(compact, ".DEPTH(DEPTH_A)") == 1, rtl)
        assert(countOccurrences(compact, ".DEPTH(DEPTH_B)") == 1, rtl)
        Vector(
          "StreamFifoCCPopToPushBufferCC",
          "StreamFifoCCPushToPopBufferCC"
        ).foreach(name => assert(moduleNames(rtl).count(_ == name) == 1, rtl))
      }

      val mergedFirst = emit(
        directory.resolve("merged-first"),
        defaultDepth = BigInt(8),
        maximumA = BigInt(8),
        maximumB = BigInt(15)
      )
      val mergedReplay = emit(
        directory.resolve("merged-replay"),
        defaultDepth = BigInt(8),
        maximumA = BigInt(8),
        maximumB = BigInt(15)
      )
      assertSameBytes(
        mergedFirst,
        mergedReplay,
        "same-bucket parent schema replay"
      )
      val mergedRtl = read(mergedFirst)
      assert(fifoDefinitionNames(mergedRtl) == Vector("StreamFifoCC"), mergedRtl)
      assertBindingsAndCanonicalBuffers(mergedRtl)
      val mergedFifo = compactWhitespace(
        moduleDefinition(mergedRtl, "StreamFifoCC")
      )
      assert(
        countOccurrences(
          mergedFifo,
          "spinal_stream_fifocc_legal_depth_ceiling=8"
        ) == 1,
        mergedFifo
      )

      val splitFirst = emit(
        directory.resolve("split-first"),
        defaultDepth = BigInt(16),
        maximumA = BigInt(16),
        maximumB = BigInt(32)
      )
      val splitReplay = emit(
        directory.resolve("split-replay"),
        defaultDepth = BigInt(16),
        maximumA = BigInt(16),
        maximumB = BigInt(32)
      )
      assertSameBytes(
        splitFirst,
        splitReplay,
        "different-bucket parent schema replay"
      )
      val splitRtl = read(splitFirst)
      assert(
        fifoDefinitionNames(splitRtl).toSet ==
          Set("StreamFifoCC", "StreamFifoCC_1"),
        splitRtl
      )
      assertBindingsAndCanonicalBuffers(splitRtl)
      val firstFifo = compactWhitespace(
        moduleDefinition(splitRtl, "StreamFifoCC")
      )
      val secondFifo = compactWhitespace(
        moduleDefinition(splitRtl, "StreamFifoCC_1")
      )
      assert(
        countOccurrences(
          firstFifo,
          "spinal_stream_fifocc_legal_depth_ceiling=16"
        ) == 1,
        firstFifo
      )
      assert(
        countOccurrences(
          secondFifo,
          "spinal_stream_fifocc_legal_depth_ceiling=32"
        ) == 1,
        secondFifo
      )
    }
  }

  test("typed owner-local reset path preserves native BOOT semantics") {
    withTemporaryDirectory { directory =>
      val config = generationConfig(directory, "boot_reset_streamfifocc.v")
      val depth = HdlInt.param("DEPTH", default = 8, min = 2, max = 16)
      var top: NativeStreamFifoCCBootResetHarness = null
      MorphVerilog(config) {
        top = new NativeStreamFifoCCBootResetHarness(depth)
        top
      }

      val fifo = top.fifo
      assert(!(fifo.finalPopCd eq fifo.popClock))
      assert(fifo.finalPopCd.clock eq fifo.popClock.clock)
      assert(
        fifo.finalPopCd.config == fifo.popClock.config.copy(resetKind = BOOT)
      )
      assert(fifo.finalPopCd.reset == null)
      assert(fifo.finalPopCd.softReset == null)
      assert(fifo.popCC.clockDomain eq fifo.finalPopCd)
      val buffers = fifo.children.collect {
        case buffer: BufferCC[_] => buffer
      }.toVector
      assert(
        buffers.size == 2,
        s"BOOT reset unexpectedly created a reset synchronizer: ${buffers.map(_.definitionName)}"
      )
      buffers.foreach(buffer => assert(buffer.parent eq fifo))

      val rtl = read(directory.resolve(config.netlistFileName))
      val fifoRtl = compactWhitespace(moduleDefinition(rtl, "StreamFifoCC"))
      assert(countOccurrences(fifoRtl, "(*keep_hierarchy=\"TRUE\"*)BufferCC") == 0, fifoRtl)
    }
  }

  test("unauthoritative oversized and otherwise illegal depths fail before RTL publication") {
    withTemporaryDirectory { directory =>
      val missingExactSchema = ElaborationIntegerParameter(
        "DEPTH_WITHOUT_EXACT_DOMAIN",
        default = BigInt(8),
        minimum = BigInt(2),
        maximum = BigInt(16)
      )
      val missingExactDepth = ElabInt.fromExpression(
        ElaborationIntegerExpression(
          verilog = missingExactSchema.name,
          default = missingExactSchema.default,
          minimum = missingExactSchema.minimum,
          maximum = missingExactSchema.maximum,
          parameters = Vector(missingExactSchema)
        )
      )
      expectMorphFailure(
        directory.resolve("missing-exact-domain"),
        "missing_exact_domain.v",
        missingExactDepth,
        "SPINAL-STREAM-FIFO-CC-DEPTH-EXACT-DOMAIN-REQUIRED"
      )

      // The StreamFifoCC max-domain check is defensive: public typed
      // publication rejects an exact result outside Scala Int first.
      val oversizedFailure = intercept[ParameterizedVerilogException] {
        val oversizedRoot =
          HdlInt.param("OVERSIZED_DEPTH_ROOT", default = 0, min = 0, max = 1)
        ((oversizedRoot * HdlInt.literal(BigInt(Int.MaxValue))) +
          HdlInt.literal(8)).asElabInt
      }
      assert(
        oversizedFailure.code ==
          "SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-OUT-OF-RANGE"
      )

      expectMorphFailure(
        directory.resolve("low-domain"),
        "low_domain.v",
        HdlInt.param("DEPTH", default = 2, min = 1, max = 16).asElabInt,
        "SPINAL-STREAM-FIFO-CC-DEPTH-DOMAIN-INVALID"
      )
      expectMorphFailure(
        directory.resolve("bad-default"),
        "bad_default.v",
        HdlInt.param("DEPTH", default = 3, min = 2, max = 16).asElabInt,
        "SPINAL-STREAM-FIFO-CC-DEPTH-DEFAULT-INVALID"
      )

      Vector(1, 3).foreach { depth =>
        val failure = intercept[AssertionError] {
          val target = directory.resolve(s"concrete-$depth")
          Files.createDirectories(target)
          SpinalVerilog(generationConfig(target, s"concrete_$depth.v"))(
            new ConcreteParityHarness(
              depth,
              bufferedPopReset = false,
              ConstructorEntry
            )
          )
        }
        assert(
          failure.getMessage.contains(
            "The depth of the StreamFifoCC must be a power of 2 and equal or bigger than 2"
          )
        )
      }

      val nullFailure = intercept[IllegalArgumentException] {
        val target = directory.resolve("null")
        Files.createDirectories(target)
        SpinalVerilog(generationConfig(target, "null_depth.v"))(
          new NativeStreamFifoCCParameterizedHarness(
            null.asInstanceOf[ElabInt],
            bufferedPopReset = false
          )
        )
      }
      assert(nullFailure.getMessage.contains("non-null ElabInt depth"))
    }
  }

  test("continuous depth domains guard the algorithm and tie invalid overrides inert") {
    withTemporaryDirectory { directory =>
      val emitted = emitParameterized(directory, buffered = false)
      val fifoRtl = moduleDefinition(read(emitted._1), "StreamFifoCC")
      val compact = compactWhitespace(fifoRtl)
      val powerOfTwoTest = "DEPTH&(DEPTH-1)"
      val guardPositions = allIndicesOf(compact, powerOfTwoTest)

      assert(fifoRtl.contains("generate"), fifoRtl)
      assert(guardPositions.size >= 2, fifoRtl)
      val legalBody = compact.substring(guardPositions.head, guardPositions(1))
      val invalidBody = compact.substring(guardPositions(1))
      assertSensitizedInvalidInterface(
        invalidBody,
        Vector("io_pop_payload"),
        fifoRtl
      )
      assert(legalBody.contains("popToPushGray="), fifoRtl)
      assert(legalBody.contains("pushToPopGray="), fifoRtl)
      assert(!invalidBody.contains("popToPushGray"), fifoRtl)
      assert(!invalidBody.contains("pushToPopGray"), fifoRtl)

      val memoryIndex = compact.indexOf("[0:DEPTH-1]") match {
        case -1    => compact.indexOf("[0:(DEPTH-1)]")
        case value => value
      }
      assert(
        guardPositions.head < memoryIndex && memoryIndex < guardPositions(1),
        "the RAM escaped the legal-depth generate branch"
      )
    }
  }

  private def assertResetTopology(
      top: NativeStreamFifoCCParameterizedHarness,
      buffered: Boolean
  ): Unit = {
    val fifo = top.fifo
    assert(fifo.withPopBufferedReset == buffered)

    if (buffered) {
      assert(!(fifo.finalPopCd eq fifo.popClock))
      assert(fifo.finalPopCd.clock eq fifo.popClock.clock)
      assert(!(fifo.finalPopCd.reset eq fifo.popClock.reset))
    } else {
      assert(fifo.finalPopCd eq fifo.popClock)
    }

    val buffers = fifo.children.collect { case buffer: BufferCC[_] => buffer }.toVector
    val pointerBuffers = buffers.filter(buffer =>
      buffer.definitionName == "StreamFifoCCPopToPushBufferCC" ||
        buffer.definitionName == "StreamFifoCCPushToPopBufferCC"
    )
    assert(
      pointerBuffers.map(_.definitionName).sorted == Vector(
        "StreamFifoCCPopToPushBufferCC",
        "StreamFifoCCPushToPopBufferCC"
      )
    )
    assert(
      buffers.size == (if (buffered) 3 else 2),
      buffers.map(_.definitionName)
    )

    pointerBuffers.foreach { buffer =>
      assert(buffer.bufferDepth.isEmpty)
      assert(!buffer.randBoot)
      assert(buffer.finalBufferDepth == 2)
      assert(buffer.allBufAttributes.isEmpty)
      val delayTags = buffer.inputAttributes.collect {
        case tag: crossClockMaxDelay => tag
      }
      assert(delayTags.size == 1)
      assert(delayTags.head.cycles == 1)
      assert(!delayTags.head.useTargetClock)
      Vector[Data](buffer.io.dataIn, buffer.io.dataOut).foreach(
        assertRetainedWidth(
          _,
          defaultWidth = 4,
          minimum = 2,
          maximum = 5,
          parameterName = "WIDTH"
        )
      )
      assert(buffer.buffers.length == 2)
      assert(buffer.buffers(0).hasTag(crossClockDomain))
      val retainedDelay =
        buffer.buffers(0).getTag(classOf[crossClockMaxDelay])
      assert(
        retainedDelay.exists(tag => tag.cycles == 1 && !tag.useTargetClock)
      )
      assert(buffer.buffers(1).hasTag(crossClockBuffer))
    }

    val popToPush = pointerBuffers.find(
      _.definitionName == "StreamFifoCCPopToPushBufferCC"
    ).get
    val pushToPop = pointerBuffers.find(
      _.definitionName == "StreamFifoCCPushToPopBufferCC"
    ).get
    assert(popToPush.clockDomain.get eq fifo.pushClock)
    assert(pushToPop.clockDomain.get eq fifo.finalPopCd)

    if (buffered) {
      val resetBuffer = buffers.filterNot(pointerBuffers.contains).head
      assert(resetBuffer.io.dataIn.getBitsWidth == 1)
      assert(resetBuffer.inputAttributes.isEmpty)
      val resetPaths = resetBuffer.allBufAttributes.collect {
        case tag: crossClockFalsePath => tag
      }
      assert(resetPaths.size == 1)
      assert(resetPaths.head.destType == TimingEndpointType.RESET)
      assert(resetPaths.head.source.nonEmpty)
      assert(resetBuffer.clockDomain.get.clock eq fifo.popClock.clock)
    }
  }

  private def assertGrayShiftGeometry(fifoRtl: String): Unit = {
    val declarations = RangedDeclaration
      .findAllMatchIn(fifoRtl)
      .map(value => value.group(2) -> compactWhitespace(value.group(1)))
      .toMap
    val shifts = ConstantShiftAssignment
      .findAllMatchIn(fifoRtl)
      .map(value => value.group(1) -> value.group(2).toInt)
      .toVector

    Vector(1, 2, 4).foreach { amount =>
      assert(
        shifts.count(_._2 == amount) >= 2,
        s"Gray decode is missing one or both domain-required shift-$amount stages:\n$fifoRtl"
      )
    }
    Vector(8, 16).foreach { amount =>
      assert(
        !shifts.exists(_._2 == amount),
        s"Gray decode retained unnecessary shift-$amount above the authoritative five-bit pointer maximum:\n$fifoRtl"
      )
    }
    shifts.foreach { case (target, _) =>
      val width = declarations.getOrElse(
        target,
        fail(s"shift target '$target' has no ranged declaration:\n$fifoRtl")
      )
      assert(
        width.contains("clog2(DEPTH,0)+1"),
        s"Gray shift target '$target' froze to width '$width'"
      )
    }
  }

  private def assertNamedDeclarationUsesWidth(
      verilog: String,
      nameFragment: String,
      widthFragment: String
  ): Unit = {
    val matches = RangedDeclaration
      .findAllMatchIn(verilog)
      .filter(_.group(2) == nameFragment)
      .map(value => compactWhitespace(value.group(1)) -> value.group(2))
      .toVector
    assert(matches.nonEmpty, s"missing declaration '$nameFragment':\n$verilog")
    assert(
      matches.forall(_._1.contains(widthFragment)),
      s"'$nameFragment' lost '$widthFragment': ${matches.mkString(", ")}"
    )
  }

  private def assertRetainedWidth(
      data: Data,
      defaultWidth: Int,
      minimum: BigInt,
      maximum: BigInt,
      parameterName: String
  ): Unit = {
    val width = widthOfExpr(data)
    assert(data.getBitsWidth == defaultWidth, data.toString)
    assert(width.parameters.map(_.name) == Vector(parameterName), width.toString)
    // Bounds for these nodes are owned by the legal-depth generate projection
    // (2, 4, 8, 16). Querying minimum/maximum after generation would attempt to
    // widen that owner back to the ambient continuous domain. The exact bounds
    // are instead proved by the emitted declarations and specialization matrix.
    assert(minimum <= BigInt(defaultWidth) && BigInt(defaultWidth) <= maximum)
  }

  private def emitParameterized(
      directory: Path,
      buffered: Boolean
  ): (Path, MorphSingleSourceVerilogReport, NativeStreamFifoCCParameterizedHarness) = {
    Files.createDirectories(directory)
    val config = generationConfig(
      directory,
      "native_streamfifocc_parameterized.v"
    )
    val depth = HdlInt.param(
      "DEPTH",
      default = BigInt(8),
      min = BigInt(2),
      max = BigInt(16)
    )
    var top: NativeStreamFifoCCParameterizedHarness = null
    val report = MorphVerilog(config) {
      top = new NativeStreamFifoCCParameterizedHarness(
        depth.asElabInt,
        buffered
      )
      top
    }
    (directory.resolve(config.netlistFileName), report, top)
  }

  private def emitDerived(directory: Path): Path = {
    Files.createDirectories(directory)
    val config = generationConfig(directory, "derived_streamfifocc_depth.v")
    val exponent = HdlInt.param(
      "DEPTH_LOG2",
      default = BigInt(3),
      min = BigInt(1),
      max = BigInt(4)
    )
    MorphVerilog(config) {
      val top = new NativeStreamFifoCCParameterizedHarness(
        exponent.asElabInt.pow2,
        bufferedPopReset = false
      )
      top.setDefinitionName("NativeStreamFifoCCDerivedDepth")
      top
    }
    directory.resolve(config.netlistFileName)
  }

  private def emitConcrete(
      root: Path,
      depth: Int,
      buffered: Boolean,
      entry: ConcreteEntry
  ): Path = {
    val directory = root.resolve(
      s"depth-$depth-${if (buffered) "buffered" else "direct"}-${entry.directoryName}"
    )
    Files.createDirectories(directory)
    val config = generationConfig(directory, s"concrete_streamfifocc_$depth.v")
    SpinalVerilog(config)(new ConcreteParityHarness(depth, buffered, entry))
    directory.resolve(config.netlistFileName)
  }

  private def expectMorphFailure(
      directory: Path,
      filename: String,
      depth: ElabInt,
      code: String
  ): Unit = {
    Files.createDirectories(directory)
    val config = generationConfig(directory, filename)
    MorphVerilog.tryGenerate(config) {
      new NativeStreamFifoCCParameterizedHarness(
        depth,
        bufferedPopReset = false
      )
    } match {
      case Left(failure) =>
        assert(
          failure.detail.contains(code),
          s"expected $code, received ${failure.detail}"
        )
      case Right(report) =>
        fail(s"expected $code, generation succeeded with $report")
    }
    assert(!Files.exists(directory.resolve(filename)))
  }

  private def generationConfig(directory: Path, filename: String): SpinalConfig = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    config
  }

  private def moduleNames(verilog: String): Vector[String] =
    ModuleDeclaration.findAllMatchIn(verilog).map(_.group(1)).toVector

  private def moduleDefinition(verilog: String, name: String): String =
    ("(?ms)^\\s*module\\s+" + java.util.regex.Pattern.quote(name) +
      "\\b.*?^\\s*endmodule\\b").r
      .findFirstIn(verilog)
      .getOrElse(fail(s"module '$name' is missing:\n$verilog"))

  private def compactWhitespace(value: String): String =
    value.replaceAll("\\s+", "")

  private def assertSensitizedInvalidInterface(
      invalidBody: String,
      payloadSignals: Vector[String],
      context: String
  ): Unit = {
    val inert = "stream_fifocc_invalid_inert"
    val inertAssignment =
      s"assign$inert=\\(io_push_valid&&([^;]+)\\);".r
        .findFirstMatchIn(invalidBody)
        .getOrElse(fail(s"invalid branch lacks its masked input carrier:\n$context"))
    assert(
      isZeroDrivenExpression(invalidBody, inertAssignment.group(1)),
      s"invalid branch carrier is not masked by zero:\n$context"
    )
    assert(invalidBody.contains(s"io_push_ready=$inert;"), context)
    assert(invalidBody.contains(s"io_pop_valid=$inert;"), context)
    (Vector("io_pushOccupancy", "io_popOccupancy") ++ payloadSignals)
      .foreach(signal =>
        assertSensitizedZeroOutput(invalidBody, inert, signal, context)
      )
  }

  private def assertSensitizedZeroOutput(
      invalidBody: String,
      inert: String,
      signal: String,
      context: String
  ): Unit = {
    val quotedSignal = java.util.regex.Pattern.quote(signal)
    val pattern = (
      s"always@\\(\\*\\)begin$quotedSignal=([^;]+);" +
        s"if\\($inert\\)begin$quotedSignal=([^;]+);endend"
    ).r
    val assignment = pattern.findFirstMatchIn(invalidBody).getOrElse {
      fail(s"invalid output '$signal' lacks retained zero sensitivity:\n$context")
    }
    val defaultValue = assignment.group(1)
    val sensitizedValue = assignment.group(2)
    Vector(defaultValue, sensitizedValue).foreach { value =>
      assert(
        isZeroDrivenExpression(invalidBody, value),
        s"invalid output '$signal' is driven by nonzero expression '$value':\n$context"
      )
    }
  }

  private def isZeroDrivenExpression(
      invalidBody: String,
      expression: String
  ): Boolean = {
    if (expression.matches("[0-9]+'[bhd]0+")) return true
    val WireOrSlice = "([A-Za-z_][A-Za-z0-9_$]*)(?:\\[[^\\]]+\\])?".r
    expression match {
      case WireOrSlice(wire) =>
        val quotedWire = java.util.regex.Pattern.quote(wire)
        s"assign$quotedWire=[0-9]+'[bhd]0+;".r
          .findFirstIn(invalidBody)
          .nonEmpty
      case _ => false
    }
  }

  private def allIndicesOf(value: String, needle: String): Vector[Int] = {
    require(needle.nonEmpty)
    val indices = Vector.newBuilder[Int]
    var from = 0
    var next = value.indexOf(needle, from)
    while (next >= 0) {
      indices += next
      from = next + needle.length
      next = value.indexOf(needle, from)
    }
    indices.result()
  }

  private def countOccurrences(value: String, needle: String): Int =
    allIndicesOf(value, needle).size

  private def assertSameBytes(left: Path, right: Path, role: String): Unit =
    assert(
      java.util.Arrays.equals(Files.readAllBytes(left), Files.readAllBytes(right)),
      s"$role was not byte deterministic"
    )

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-streamfifocc-57a-")
    try body(directory)
    finally {
      if (!sys.env.contains("MORPHDL_KEEP_STREAMFIFOCC_TEST_OUTPUT")) {
        val stream = Files.walk(directory)
        try
          stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach {
            path => Files.deleteIfExists(path)
          }
        finally stream.close()
      }
    }
  }
}
