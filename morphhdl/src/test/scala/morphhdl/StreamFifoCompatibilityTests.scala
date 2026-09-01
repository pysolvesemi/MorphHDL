package morphhdl

import java.lang.reflect.Modifier
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

/** Compatibility coverage for the native StreamFifo typed-depth migration.
  *
  * The public Int APIs are deliberately exercised independently of the typed
  * entry point.  This catches constructor/default-getter ABI drift as well as
  * accidental parameter retention or RTL changes for ordinary concrete calls.
  */
class StreamFifoCompatibilityTests extends AnyFunSuite {
  private val Depths = Vector(1, 3, 5, 8)

  private val LegacyConstructorParameterTypes: Vector[Class[_]] = Vector(
    classOf[HardType[_]],
    java.lang.Integer.TYPE,
    java.lang.Boolean.TYPE,
    java.lang.Boolean.TYPE,
    java.lang.Boolean.TYPE,
    java.lang.Boolean.TYPE,
    java.lang.Boolean.TYPE,
    classOf[Function0[_]]
  )

  private val LegacyApplyParameterTypes: Vector[Class[_]] = Vector(
    classOf[HardType[_]],
    java.lang.Integer.TYPE,
    java.lang.Integer.TYPE,
    java.lang.Boolean.TYPE,
    classOf[Function0[_]]
  )

  test("legacy Int constructor and companion JVM contracts remain available") {
    val fifoClass = classOf[StreamFifo[_]]
    val constructor = fifoClass.getConstructor(
      LegacyConstructorParameterTypes: _*
    )
    assert(Modifier.isPublic(constructor.getModifiers))
    assert(constructor.getParameterTypes.toVector == LegacyConstructorParameterTypes)

    val depthAccessor = fifoClass.getMethod("depth")
    assert(Modifier.isPublic(depthAccessor.getModifiers))
    assert(depthAccessor.getParameterTypes.isEmpty)
    assert(depthAccessor.getReturnType == java.lang.Integer.TYPE)

    val companion = StreamFifo
    val companionClass = companion.getClass
    val legacyApply = companionClass.getMethod(
      "apply",
      LegacyApplyParameterTypes: _*
    )
    assert(Modifier.isPublic(legacyApply.getModifiers))
    assert(legacyApply.getParameterTypes.toVector == LegacyApplyParameterTypes)
    assert(legacyApply.getReturnType == fifoClass)

    val constructorBooleanDefaults = Vector(
      3 -> false,
      4 -> false,
      5 -> true,
      6 -> false,
      7 -> false
    )
    constructorBooleanDefaults.foreach { case (position, expected) =>
      val method = companionClass.getMethod(
        s"$$lessinit$$greater$$default$$$position"
      )
      assert(method.getParameterTypes.isEmpty)
      assert(method.getReturnType == java.lang.Boolean.TYPE)
      assert(method.invoke(companion) == Boolean.box(expected))
    }
    val constructorPayloadDefault = companionClass.getMethod(
      "$lessinit$greater$default$8"
    )
    assert(constructorPayloadDefault.getParameterTypes.isEmpty)
    assert(constructorPayloadDefault.getReturnType == None.getClass)
    assert(constructorPayloadDefault.invoke(companion) == None)

    val applyLatencyDefault = companionClass.getMethod("apply$default$3")
    assert(applyLatencyDefault.getReturnType == java.lang.Integer.TYPE)
    assert(applyLatencyDefault.invoke(companion) == Int.box(2))
    val applyFMaxDefault = companionClass.getMethod("apply$default$4")
    assert(applyFMaxDefault.getReturnType == java.lang.Boolean.TYPE)
    assert(applyFMaxDefault.invoke(companion) == Boolean.box(false))
    val applyPayloadDefault = companionClass.getMethod("apply$default$5")
    assert(applyPayloadDefault.getReturnType == None.getClass)
    assert(applyPayloadDefault.invoke(companion) == None)
  }

  test("legacy named and omitted constructor arguments still elaborate") {
    withTemporaryDirectory { directory =>
      val config = concreteConfig(directory)
      config.netlistFileName = "legacy_named_stream_fifo.v"
      SpinalVerilog(config)(new LegacyNamedConstructorTop)

      val bytes = Files.readAllBytes(directory.resolve(config.netlistFileName))
      val verilog = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
      assert(verilog.contains("module LegacyNamedStreamFifoTop ("))
      assert(!verilog.contains("parameter integer DEPTH"))
      assert(!verilog.contains("typed_vec_write_index"))
      assert(!verilog.contains("typed_vec_write_target"))
      assert(!verilog.contains("typed_vec_write_data"))
      assert(!verilog.contains("typed_vec_read_data"))
      assert(!verilog.contains("typed_storage_pop_index"))
    }
  }

  test("concrete Int constructor, Int companion and typed literal are byte-identical") {
    withTemporaryDirectory { directory =>
      Depths.foreach { depth =>
        val constructor = emitConcrete(directory, depth, ConstructorEntry)
        val intCompanion = emitConcrete(directory, depth, IntCompanionEntry)
        val typedLiteral = emitConcrete(directory, depth, TypedLiteralEntry)

        assert(
          java.util.Arrays.equals(
            Files.readAllBytes(constructor),
            Files.readAllBytes(intCompanion)
          ),
          s"legacy constructor and companion RTL differed at depth $depth"
        )
        assert(
          java.util.Arrays.equals(
            Files.readAllBytes(constructor),
            Files.readAllBytes(typedLiteral)
          ),
          s"legacy Int and concrete typed RTL differed at depth $depth"
        )

        val verilog = new String(
          Files.readAllBytes(constructor),
          java.nio.charset.StandardCharsets.UTF_8
        )
        assert(!verilog.contains("parameter integer DEPTH"))
        assert(!verilog.contains(".DEPTH("))
      }
    }
  }

  test("full-config typed literal Vec storage is byte-identical to native Int construction") {
    withTemporaryDirectory { directory =>
      Depths.foreach { depth =>
        val native = emitConfiguredVecConcrete(directory, depth, typed = false)
        val typed = emitConfiguredVecConcrete(directory, depth, typed = true)

        assert(
          java.util.Arrays.equals(
            Files.readAllBytes(native),
            Files.readAllBytes(typed)
          ),
          s"full-config native Int and typed-literal Vec RTL differed at depth $depth"
        )

        val verilog = new String(
          Files.readAllBytes(typed),
          java.nio.charset.StandardCharsets.UTF_8
        )
        Vector(
          "typed_vec_write_index",
          "typed_vec_write_target",
          "typed_vec_write_data",
          "typed_vec_read_data",
          "typed_storage_pop_index"
        ).foreach(name => assert(!verilog.contains(name), verilog))
      }
    }
  }

  test("legacy concrete depth zero retains its native bypass elaboration") {
    withTemporaryDirectory { directory =>
      val constructor = emitConcrete(directory, 0, ConstructorEntry)
      val intCompanion = emitConcrete(directory, 0, IntCompanionEntry)
      val typedLiteral = emitConcrete(directory, 0, TypedLiteralEntry)

      assert(
        java.util.Arrays.equals(
          Files.readAllBytes(constructor),
          Files.readAllBytes(intCompanion)
        )
      )
      assert(
        java.util.Arrays.equals(
          Files.readAllBytes(constructor),
          Files.readAllBytes(typedLiteral)
        )
      )

      val verilog = new String(
        Files.readAllBytes(constructor),
        java.nio.charset.StandardCharsets.UTF_8
      )
      assert(!verilog.contains("parameter integer DEPTH"))
      assert(verilog.contains("assign io_push_ready = io_pop_ready;"))
      assert(verilog.contains("assign io_pop_valid = io_push_valid;"))
      assert(verilog.contains("assign io_pop_payload = io_push_payload;"))
    }
  }

  private sealed trait EntryPoint {
    def directoryName: String
  }
  private case object ConstructorEntry extends EntryPoint {
    override val directoryName = "constructor"
  }
  private case object IntCompanionEntry extends EntryPoint {
    override val directoryName = "int-companion"
  }
  private case object TypedLiteralEntry extends EntryPoint {
    override val directoryName = "typed-literal"
  }

  private final class ConcreteParityTop(depthValue: Int, entry: EntryPoint) extends Component {
    setDefinitionName(s"ConcreteStreamFifoParityDepth$depthValue")

    val io = new Bundle {
      val push = slave(Stream(Bits(8 bits)))
      val pop = master(Stream(Bits(8 bits)))
      val flush = in Bool ()
      val occupancy = out UInt (4 bits)
      val availability = out UInt (4 bits)
    }

    val fifo = entry match {
      case ConstructorEntry =>
        new StreamFifo(HardType(Bits(8 bits)), depthValue)
      case IntCompanionEntry =>
        StreamFifo(HardType(Bits(8 bits)), depthValue)
      case TypedLiteralEntry =>
        StreamFifo(HardType(Bits(8 bits)), ElabInt.literal(depthValue))
    }

    fifo.io.push << io.push
    io.pop << fifo.io.pop
    fifo.io.flush := io.flush
    io.occupancy := fifo.io.occupancy.resized
    io.availability := fifo.io.availability.resized
  }

  private final class ConfiguredVecParityTop(
      depthValue: Int,
      typed: Boolean
  ) extends Component {
    setDefinitionName(s"ConfiguredVecStreamFifoParityDepth$depthValue")

    val io = new Bundle {
      val push = slave(Stream(Bits(8 bits)))
      val pop = master(Stream(Bits(8 bits)))
      val flush = in Bool ()
    }

    private val payloadType = HardType(Bits(8 bits))
    val fifo =
      if (typed)
        StreamFifo(
          payloadType,
          ElabInt.literal(depthValue),
          withAsyncRead = true,
          withBypass = true,
          allowExtraMsb = false,
          forFMax = true,
          useVec = true,
          initPayload = None
        )
      else
        new StreamFifo(
          payloadType,
          depthValue,
          withAsyncRead = true,
          withBypass = true,
          allowExtraMsb = false,
          forFMax = true,
          useVec = true,
          initPayload = None
        )

    fifo.io.push << io.push
    io.pop << fifo.io.pop
    fifo.io.flush := io.flush
  }

  private final class LegacyNamedConstructorTop extends Component {
    setDefinitionName("LegacyNamedStreamFifoTop")

    val io = new Bundle {
      val push = slave(Stream(Bits(8 bits)))
      val pop = master(Stream(Bits(8 bits)))
      val flush = in Bool ()
    }

    // Reordered names plus omitted allowExtraMsb/forFMax/initPayload defaults
    // specifically target the legacy Int auxiliary-constructor surface.
    val fifo = new StreamFifo(
      dataType = HardType(Bits(8 bits)),
      depth = 3,
      withBypass = true,
      withAsyncRead = true,
      useVec = true
    )
    require(fifo.depth == 3)

    fifo.io.push << io.push
    io.pop << fifo.io.pop
    fifo.io.flush := io.flush
  }

  private def emitConcrete(
      root: Path,
      depth: Int,
      entry: EntryPoint
  ): Path = {
    val directory = root.resolve(s"depth-$depth-${entry.directoryName}")
    Files.createDirectories(directory)
    val config = concreteConfig(directory)
    config.netlistFileName = s"concrete_stream_fifo_depth_$depth.v"
    SpinalVerilog(config)(new ConcreteParityTop(depth, entry))
    directory.resolve(config.netlistFileName)
  }

  private def emitConfiguredVecConcrete(
      root: Path,
      depth: Int,
      typed: Boolean
  ): Path = {
    val directory = root.resolve(
      s"depth-$depth-configured-vec-${if (typed) "typed" else "native"}"
    )
    Files.createDirectories(directory)
    val config = concreteConfig(directory)
    config.netlistFileName = s"configured_vec_stream_fifo_depth_$depth.v"
    SpinalVerilog(config)(new ConfiguredVecParityTop(depth, typed))
    directory.resolve(config.netlistFileName)
  }

  private def concreteConfig(directory: Path): SpinalConfig =
    SpinalConfig(
      targetDirectory = directory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    )

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-streamfifo-compatibility-")
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
