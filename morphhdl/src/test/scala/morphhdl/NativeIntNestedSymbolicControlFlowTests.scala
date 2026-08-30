package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._

import morphhdl.frontend.{formalComponent, HdlInt, NativeIntShadow}

object NativeIntNestedSymbolicControlFlowSmoke {
  object ExternalState {
    var count = 0
  }

  final class Sink extends Component {
    setDefinitionName("NativeIntNestedControlSink")
    val din = in(Bits(8 bits))
    val observed = out(Bool())
    observed := din.orR
  }

  abstract class LeafBase(width: Int, definitionName: String) extends Component {
    setDefinitionName(definitionName)
    val payloadIn = in(Bits(width bits))
    val payloadOut = out(Bits(width bits))
    val control = in(Bits(8 bits))
    payloadOut := payloadIn

    protected final def attach(name: String): Bool =
      attach(name, control)

    protected final def attach(name: String, value: Bits): Bool = {
      val sink = new Sink
      sink.setName(name)
      sink.din := value
      sink.observed
    }
  }

  final class NestedHardwareLeaf(width: Int)
      extends LeafBase(width, "NativeIntNestedHardwareLeaf") {
    @dontName val root = NativeIntShadow.captureArgument(width, "root")

    if (root > 16) {
      @dontName val adjusted = root - 1
      if (adjusted > 20) {
        val memoryArea = new Area {
          val storage = Mem(Bits(8 bits), 2)
          storage.setName("nested_storage")
          val address = control(0).asUInt
          storage.write(
            address = address,
            data = control,
            enable = True
          )
          val read = storage.readAsync(address)
          read.setName("nested_storage_read")

          val clocked = new ClockingArea(ClockDomain.current) {
            val delayed = RegNext(read)
            delayed.setName("nested_delayed")
          }
          payloadOut(0) := attach("nested_memory_sink", clocked.delayed)
        }
      } else {
        for (index <- 0 until 2) {
          val selected = control
          payloadOut(index) := attach(s"nested_loop_sink_$index", selected)
        }
      }
    } else {
      val fallbackArea = new Area {
        payloadOut(0) := attach("nested_fallback_sink", control)
      }
    }
  }

  final class MutationLeaf(width: Int)
      extends LeafBase(width, "NativeIntMutationLeaf") {
    @dontName val root = NativeIntShadow.captureArgument(width, "root")
    if (root > 8) {
      ExternalState.count = ExternalState.count + 1
      payloadOut(0) := attach("mutation_sink")
    } else payloadOut(0) := attach("mutation_else_sink")
  }

  final class IoLeaf(width: Int)
      extends LeafBase(width, "NativeIntIoLeaf") {
    @dontName val root = NativeIntShadow.captureArgument(width, "root")
    if (root > 8) {
      println("unsafe native symbolic capture I/O")
      payloadOut(0) := attach("io_sink")
    } else payloadOut(0) := attach("io_else_sink")
  }

  final class ReflectionLeaf(width: Int)
      extends LeafBase(width, "NativeIntReflectionLeaf") {
    @dontName val root = NativeIntShadow.captureArgument(width, "root")
    if (root > 8) {
      @dontName val reflected = classOf[String].getDeclaredMethods.length
      payloadOut(0) := attach("reflection_sink")
    } else payloadOut(0) := attach("reflection_else_sink")
  }

  final class NondeterministicLeaf(width: Int)
      extends LeafBase(width, "NativeIntNondeterministicLeaf") {
    @dontName val root = NativeIntShadow.captureArgument(width, "root")
    if (root > 8) {
      @dontName val randomValue = scala.util.Random.nextInt()
      payloadOut(0) := attach("random_sink")
    } else payloadOut(0) := attach("random_else_sink")
  }

  final class ControlEffectLeaf(width: Int)
      extends LeafBase(width, "NativeIntControlEffectLeaf") {
    @dontName val root = NativeIntShadow.captureArgument(width, "root")
    if (root > 8) {
      throw new IllegalStateException("unsafe captured throw")
    } else payloadOut(0) := attach("control_else_sink")
  }

  final class ArbitraryEffectLeaf(width: Int)
      extends LeafBase(width, "NativeIntArbitraryEffectLeaf") {
    @dontName val root = NativeIntShadow.captureArgument(width, "root")
    if (root > 8) {
      this.synchronized {
        payloadOut(0) := attach("synchronized_sink")
      }
    } else payloadOut(0) := attach("synchronized_else_sink")
  }

  final class NestedTop(width: HdlInt) extends Component {
    setDefinitionName("NativeIntNestedControlTop")
    val payloadIn = in(morphhdl.frontend.Bits(width bits))
    val payloadOut = out(morphhdl.frontend.Bits(width bits))
    val control = in(Bits(8 bits))

    val leaf = formalComponent(width, "WIDTH", BigInt(1), BigInt(32))(
      value => new NestedHardwareLeaf(value)
    )(value => Vector(value.payloadIn, value.payloadOut))
    leaf.payloadIn := payloadIn
    leaf.control := control
    payloadOut := leaf.payloadOut
  }

  final class UnsafeTop(width: HdlInt, mode: String) extends Component {
    setDefinitionName("NativeIntUnsafeControlTop")
    val payloadIn = in(morphhdl.frontend.Bits(width bits))
    val payloadOut = out(morphhdl.frontend.Bits(width bits))
    val control = in(Bits(8 bits))

    val leaf: LeafBase = mode match {
      case "mutation" => formalComponent(width, "WIDTH", BigInt(1), BigInt(16))(
        value => new MutationLeaf(value)
      )(value => Vector(value.payloadIn, value.payloadOut))
      case "io" => formalComponent(width, "WIDTH", BigInt(1), BigInt(16))(
        value => new IoLeaf(value)
      )(value => Vector(value.payloadIn, value.payloadOut))
      case "reflection" => formalComponent(width, "WIDTH", BigInt(1), BigInt(16))(
        value => new ReflectionLeaf(value)
      )(value => Vector(value.payloadIn, value.payloadOut))
      case "nondeterminism" => formalComponent(width, "WIDTH", BigInt(1), BigInt(16))(
        value => new NondeterministicLeaf(value)
      )(value => Vector(value.payloadIn, value.payloadOut))
      case "control" => formalComponent(width, "WIDTH", BigInt(1), BigInt(16))(
        value => new ControlEffectLeaf(value)
      )(value => Vector(value.payloadIn, value.payloadOut))
      case "arbitrary" => formalComponent(width, "WIDTH", BigInt(1), BigInt(16))(
        value => new ArbitraryEffectLeaf(value)
      )(value => Vector(value.payloadIn, value.payloadOut))
      case other => throw new IllegalArgumentException(other)
    }

    leaf.payloadIn := payloadIn
    leaf.control := control
    payloadOut := leaf.payloadOut
  }
}

class NativeIntNestedSymbolicControlFlowTests extends AnyFunSuite {
  import NativeIntNestedSymbolicControlFlowSmoke._

  test("nested alternatives retain loops locals registers memory Areas ClockingAreas naming and assignments") {
    withTemporaryDirectory { directory =>
      var top: NestedTop = null
      val verilog = emitMorph(directory, "native_int_nested_hardware.v") {
        val width = HdlInt.param("WIDTH", default = 18, min = 1, max = 32)
        top = new NestedTop(width)
        top
      }
      val geometryExpression = ExternalNativeIntFormalizationRegistry
        .regionOf(top.leaf.payloadIn)
        .getOrElse(fail("missing exact formalized child-port region"))
        .expression
      val geometryRoot = ExternalNativeIntCompletedRootTestProbe(
        geometryExpression
      )
        .headOption
        .getOrElse(fail("formalized child port lost its declaration root"))
      val definitionRoots = ExternalNativeIntShadowRegistry
        .componentRecordsOf(top.leaf)
        .flatMap(record =>
          record.slots.flatMap(slot =>
            ExternalNativeIntCompletedRootTestProbe(slot.definitionExpression)
          ) ++ record.predicates.flatMap(predicate =>
            ExternalNativeIntCompletedRootTestProbe(predicate.definitionExpression)
          )
        )
      assert(definitionRoots.nonEmpty)
      assert(definitionRoots.forall(root => root eq geometryRoot))
      val compact = verilog.replaceAll("\\s+", "")
      assert(compact.contains("WIDTH>16") || compact.contains("(WIDTH)>(16)"))
      assert(
        compact.contains("(WIDTH-1)>20") ||
        compact.contains("((WIDTH-1)>20)") ||
        compact.contains("((WIDTH)-(1))>(20)")
      )
      assert(instanceCount(verilog, "NativeIntNestedControlSink") == 4)
      assert(verilog.contains("nested_loop_sink_0"))
      assert(verilog.contains("nested_loop_sink_1"))
      assert(verilog.contains("nested_memory_sink"))
      assert(verilog.contains("nested_fallback_sink"))
      assert(verilog.contains("nested_storage"))
      assert(verilog.contains("nested_storage_read"))
      assert(verilog.contains("always @"))
    }
  }

  test("ordinary SpinalVerilog executes only the concrete nested witness path") {
    withTemporaryDirectory { directory =>
      val verilog = emitConcrete(directory, "native_int_nested_concrete.v") {
        new NestedTop(HdlInt.literal(18))
      }
      assert(!verilog.contains("parameter integer"))
      assert(!verilog.contains("generate"))
      assert(instanceCount(verilog, "NativeIntNestedControlSink") == 2)
    }
  }

  test("captured alternatives reject mutable Scala state before mutation") {
    ExternalState.count = 0
    val failure = failureFor("mutation")
    assert(failure.contains(
      "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-MUTABLE-STATE-UNSUPPORTED"
    ))
    assert(ExternalState.count == 0)
  }

  test("captured alternatives reject I/O reflection and nondeterminism") {
    assert(failureFor("io").contains(
      "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-IO-UNSUPPORTED"
    ))
    assert(failureFor("reflection").contains(
      "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-REFLECTION-UNSUPPORTED"
    ))
    assert(failureFor("nondeterminism").contains(
      "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-NONDETERMINISM-UNSUPPORTED"
    ))
  }

  test("captured alternatives reject control and arbitrary Scala effects") {
    assert(failureFor("control").contains(
      "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-CONTROL-EFFECT-UNSUPPORTED"
    ))
    assert(failureFor("arbitrary").contains(
      "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-ARBITRARY-EFFECT-UNSUPPORTED"
    ))
  }

  test("rejected effects remain ordinary concrete behavior when their branch is not selected") {
    ExternalState.count = 0
    withTemporaryDirectory { directory =>
      val verilog = emitConcrete(directory, "native_int_mutation_concrete.v") {
        new UnsafeTop(HdlInt.literal(4), "mutation")
      }
      assert(!verilog.contains("generate"))
      assert(ExternalState.count == 0)
      assert(instanceCount(verilog, "NativeIntNestedControlSink") == 1)
    }
  }

  test("nested symbolic control-flow replay is deterministic") {
    withTemporaryDirectory { first =>
      withTemporaryDirectory { second =>
        val firstVerilog = emitMorph(first, "native_int_nested_replay.v") {
          val width = HdlInt.param("WIDTH", default = 18, min = 1, max = 32)
          new NestedTop(width)
        }
        val secondVerilog = emitMorph(second, "native_int_nested_replay.v") {
          val width = HdlInt.param("WIDTH", default = 18, min = 1, max = 32)
          new NestedTop(width)
        }
        assert(firstVerilog == secondVerilog)
      }
    }
  }

  private def failureFor(mode: String): String = withTemporaryDirectory { directory =>
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = s"native_int_nested_failure_$mode.v"
    MorphVerilog.tryGenerate(config) {
      val width = HdlInt.param("WIDTH", default = 4, min = 1, max = 16)
      new UnsafeTop(width, mode)
    } match {
      case Left(failure) => failure.detail
      case Right(report) => fail(s"Expected failure, received $report")
    }
  }

  private def emitMorph(
      directory: Path,
      filename: String
  )(component: => Component): String = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    MorphVerilog(config)(component)
    read(directory.resolve(filename))
  }

  private def emitConcrete(
      directory: Path,
      filename: String
  )(component: => Component): String = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    SpinalVerilog(config)(component)
    read(directory.resolve(filename))
  }

  private def instanceCount(verilog: String, moduleName: String): Int =
    ("(?m)^\\s*" + java.util.regex.Pattern.quote(moduleName) + "\\s+").r
      .findAllMatchIn(verilog)
      .length

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-native-int-nested-")
    try body(directory)
    finally deleteRecursively(directory)
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.exists(path)) {
      val entries = Files.walk(path)
      try {
        entries
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(value => Files.deleteIfExists(value))
      } finally entries.close()
    }
  }
}
