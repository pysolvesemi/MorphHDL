package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import morphhdl.frontend.{formalComponent, HdlInt}

/**
  * A MorphHDL parent around the real, untouched spinal.lib.StreamFifoCC.
  *
  * The child constructor remains the native Int API. MorphHDL supplies only
  * the external formal-to-actual binding and retained symbolic evidence.
  */
final class NativeParameterizedStreamFifoCCHarness(depth: HdlInt)
    extends Component {
  setDefinitionName("NativeParameterizedStreamFifoCCHarness")

  val io = new Bundle {
    val pushClock = in Bool()
    val pushReset = in Bool()
    val popClock = in Bool()
    val popReset = in Bool()
    val push = slave Stream (Bits(8 bits))
    val pop = master Stream (Bits(8 bits))
  }

  private val pushCd = ClockDomain(
    clock = io.pushClock,
    reset = io.pushReset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
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

  val fifo: spinal.lib.StreamFifoCC[Bits] = formalComponent.parameter(
    actual = depth,
    name = "DEPTH",
    minimum = BigInt(2),
    maximum = BigInt(8)
  ) { witness =>
    new spinal.lib.StreamFifoCC(
      dataType = HardType(Bits(8 bits)),
      depth = witness,
      pushClock = pushCd,
      popClock = popCd,
      withPopBufferedReset = false
    )
  }
  fifo.setName("fifo")

  fifo.io.push << io.push
  io.pop << fifo.io.pop
}

class ParameterizedStreamFifoCCDepthTests extends AnyFunSuite {
  private val OutputFile = "stream_fifo_cc_parameterized_depth.v"

  private val ModuleDeclaration =
    """(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b""".r

  private def component(): NativeParameterizedStreamFifoCCHarness = {
    val depth = HdlInt.param(
      "DEPTH",
      default = BigInt(4),
      min = BigInt(2),
      max = BigInt(8)
    )
    new NativeParameterizedStreamFifoCCHarness(depth)
  }

  test("real native StreamFifoCC reaches MorphHDL parameterized emission") {
    withWorkspace { directory =>
      val parameterizedDirectory = directory.resolve("parameterized")
      val concreteDirectory = directory.resolve("concrete")
      Files.createDirectories(parameterizedDirectory)
      Files.createDirectories(concreteDirectory)

      val parameterizedConfig = SpinalConfig(
        targetDirectory = parameterizedDirectory.toString
      )
      parameterizedConfig.netlistFileName = OutputFile
      val report = MorphVerilog(parameterizedConfig)(component())
      val parameterized = read(parameterizedDirectory.resolve(OutputFile))

      val concreteConfig = SpinalConfig(targetDirectory = concreteDirectory.toString)
      concreteConfig.netlistFileName = OutputFile
      SpinalVerilog(concreteConfig)(component())
      val concrete = read(concreteDirectory.resolve(OutputFile))

      val depthParameter = report.parameters.find(_.name == "DEPTH")
      assert(depthParameter.nonEmpty)
      assert(depthParameter.get.default == BigInt(4))
      assert(
        depthParameter.get.constraints == Vector(
          paramrtl.IntConstraint.MinInclusive(BigInt(2)),
          paramrtl.IntConstraint.MaxInclusive(BigInt(8))
        )
      )

      val modules = ModuleDeclaration
        .findAllMatchIn(parameterized)
        .map(_.group(1))
        .filter(_.contains("StreamFifoCC"))
        .toVector
        .sorted
      assert(
        modules == Vector(
          "NativeParameterizedStreamFifoCCHarness",
          "StreamFifoCC"
        ).sorted,
        s"Unexpected StreamFifoCC module inventory: ${modules.mkString(", ")}"
      )
      assert(parameterized.contains("module StreamFifoCC #("))
      assert(parameterized.contains("parameter integer DEPTH = 4"))
      assert(parameterized.contains(".DEPTH(DEPTH)"))
      assert(
        parameterized.contains("[0:DEPTH-1]") ||
          parameterized.contains("[0:(DEPTH - 1)]")
      )
      assert(parameterized.contains("function integer clog2;"))
      assert(parameterized.contains("clog2(DEPTH"))
      assert(!parameterized.contains("MorphStreamFifoCC"))
      assert(!parameterized.contains("ParamRTL"))

      assert(!concrete.contains("parameter integer DEPTH"))
      assert(!concrete.contains(".DEPTH("))
      assert(concrete.contains("[0:3]"))
    }
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withWorkspace(body: Path => Unit): Unit = {
    sys.env.get("MORPHDL_STREAMFIFOCC_DEBUG_DIR") match {
      case Some(configured) =>
        val directory = Paths.get(configured).toAbsolutePath.normalize()
        if (Files.exists(directory)) {
          val stream = Files.walk(directory)
          try {
            stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach {
              path => if (path != directory) Files.deleteIfExists(path)
            }
          } finally stream.close()
        }
        Files.createDirectories(directory)
        body(directory)
      case None =>
        val directory = Files.createTempDirectory("morphhdl-streamfifocc-depth-test-")
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
  }
}
