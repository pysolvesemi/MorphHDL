package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import spinal.core._
import spinal.lib._

import morphhdl.frontend.HdlInt
import morphhdl.frontend.HdlInt.hdlIntToParameterizedMemoryDepth

/**
  * Fixed-ABI proof harness around the exact native StreamFifoCC returned by the
  * MorphHDL frontend boundary. Only DEPTH is symbolic; the native FIFO and all
  * CDC structure remain owned by spinal.lib.StreamFifoCC.
  */
final class NativeParameterizedStreamFifoCCProofHarness(
    depth: HdlInt,
    withPopBufferedReset: Boolean
) extends Component {
  setDefinitionName(
    if (withPopBufferedReset)
      "NativeParameterizedStreamFifoCCProofHarnessBuffered"
    else "NativeParameterizedStreamFifoCCProofHarnessDirect"
  )

  val io = new Bundle {
    val pushClock = in Bool ()
    val pushReset = in Bool ()
    val popClock = in Bool ()
    val popReset = in Bool ()
    val pushValid = in Bool ()
    val pushReady = out Bool ()
    val pushPayload = in Bits (8 bits)
    val popValid = out Bool ()
    val popReady = in Bool ()
    val popPayload = out Bits (8 bits)
    val pushOccupancy = out UInt (5 bits)
    val popOccupancy = out UInt (5 bits)
  }

  private val cdConfig = ClockDomainConfig(
    clockEdge = RISING,
    resetKind = ASYNC,
    resetActiveLevel = HIGH
  )
  private val pushCd = ClockDomain(
    clock = io.pushClock,
    reset = io.pushReset,
    config = cdConfig
  )
  private val popCd = ClockDomain(
    clock = io.popClock,
    reset = io.popReset,
    config = cdConfig
  )

  val fifo = morphhdl.frontend.StreamFifoCC(
    HardType(Bits(8 bits)),
    depth,
    pushCd,
    popCd,
    withPopBufferedReset
  )
  fifo.setName("fifo")

  fifo.io.push.valid := io.pushValid
  fifo.io.push.payload := io.pushPayload
  io.pushReady := fifo.io.push.ready
  io.popValid := fifo.io.pop.valid
  fifo.io.pop.ready := io.popReady
  io.popPayload := fifo.io.pop.payload
  io.pushOccupancy := fifo.io.pushOccupancy.resized
  io.popOccupancy := fifo.io.popOccupancy.resized
}

/** Independently elaborated ordinary SpinalHDL witness. */
final class NativeConcreteStreamFifoCCProofHarness(
    depth: Int,
    withPopBufferedReset: Boolean
) extends Component {
  require(depth >= 2 && (depth & (depth - 1)) == 0)
  setDefinitionName(
    s"NativeConcreteStreamFifoCCProofHarnessDepth${depth}" +
      (if (withPopBufferedReset) "Buffered" else "Direct")
  )

  val io = new Bundle {
    val pushClock = in Bool ()
    val pushReset = in Bool ()
    val popClock = in Bool ()
    val popReset = in Bool ()
    val pushValid = in Bool ()
    val pushReady = out Bool ()
    val pushPayload = in Bits (8 bits)
    val popValid = out Bool ()
    val popReady = in Bool ()
    val popPayload = out Bits (8 bits)
    val pushOccupancy = out UInt (5 bits)
    val popOccupancy = out UInt (5 bits)
  }

  private val cdConfig = ClockDomainConfig(
    clockEdge = RISING,
    resetKind = ASYNC,
    resetActiveLevel = HIGH
  )
  private val pushCd = ClockDomain(
    clock = io.pushClock,
    reset = io.pushReset,
    config = cdConfig
  )
  private val popCd = ClockDomain(
    clock = io.popClock,
    reset = io.popReset,
    config = cdConfig
  )

  val fifo = spinal.lib.StreamFifoCC(
    HardType(Bits(8 bits)),
    depth,
    pushCd,
    popCd,
    withPopBufferedReset
  )
  fifo.setDefinitionName(
    s"ConcreteStreamFifoCCDepth${depth}" +
      (if (withPopBufferedReset) "Buffered" else "Direct")
  )
  fifo.setName("fifo")

  fifo.io.push.valid := io.pushValid
  fifo.io.push.payload := io.pushPayload
  io.pushReady := fifo.io.push.ready
  io.popValid := fifo.io.pop.valid
  fifo.io.pop.ready := io.popReady
  io.popPayload := fifo.io.pop.payload
  io.pushOccupancy := fifo.io.pushOccupancy.resized
  io.popOccupancy := fifo.io.popOccupancy.resized
}

private[morphhdl] object NativeStreamFifoCCProofSupport {
  val Depths: Vector[Int] = Vector(4, 8, 16)
  val Modes: Vector[Boolean] = Vector(false, true)

  def candidateTop(buffered: Boolean): String =
    if (buffered) "NativeParameterizedStreamFifoCCProofHarnessBuffered"
    else "NativeParameterizedStreamFifoCCProofHarnessDirect"

  def concreteTop(depth: Int, buffered: Boolean): String =
    s"NativeConcreteStreamFifoCCProofHarnessDepth${depth}" +
      (if (buffered) "Buffered" else "Direct")

  def modeName(buffered: Boolean): String = if (buffered) "buffered" else "direct"

  def config(directory: Path, fileName: String): SpinalConfig = {
    val value = SpinalConfig(
      mode = Verilog,
      targetDirectory = directory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )
    value.netlistFileName = fileName
    value
  }

  def generateCandidate(directory: Path, buffered: Boolean): Path = {
    Files.createDirectories(directory)
    val file = s"stream_fifocc_parameterized_${modeName(buffered)}.v"
    val depth = HdlInt.param(
      "DEPTH",
      default = BigInt(8),
      min = BigInt(4),
      max = BigInt(16)
    )
    MorphVerilog(config(directory, file)) {
      new NativeParameterizedStreamFifoCCProofHarness(depth, buffered)
    }
    directory.resolve(file)
  }

  def generateConcrete(
      directory: Path,
      depth: Int,
      buffered: Boolean
  ): Path = {
    Files.createDirectories(directory)
    val file = s"stream_fifocc_concrete_${depth}_${modeName(buffered)}.v"
    SpinalVerilog(config(directory, file)) {
      new NativeConcreteStreamFifoCCProofHarness(depth, buffered)
    }
    directory.resolve(file)
  }

  def write(path: Path, value: String): Unit =
    Files.write(path, value.getBytes(StandardCharsets.UTF_8))

  def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  def run(directory: Path, command: Seq[String]): (Int, String) = {
    val output = new StringBuilder
    val logger = ProcessLogger(
      line => output.append(line).append('\n'),
      line => output.append(line).append('\n')
    )
    val code = Process(command, directory.toFile).!(logger)
    code -> output.toString
  }

  def requireTool(directory: Path, command: Seq[String], label: String): Unit = {
    val (code, output) = run(directory, command)
    assert(code == 0, s"$label is unavailable:\n$output")
  }

  def yosysPath(path: Path): String =
    path.toAbsolutePath.toString.replace("\\", "/")

  def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-streamfifocc-proof-")
    try body(directory)
    finally {
      Files.walk(directory).iterator().asScala.toVector
        .sortBy(_.getNameCount)
        .reverse
        .foreach(path => Files.deleteIfExists(path))
    }
  }
}
