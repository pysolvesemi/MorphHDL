package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.HdlInt
import morphhdl.frontend.HdlInt.hdlIntToParameterizedMemoryDepth

final class NativeParameterizedStreamFifoCCHarness(depth: HdlInt)
    extends Component {
  val io = new Bundle {
    val pushClock = in Bool ()
    val pushReset = in Bool ()
    val popClock = in Bool ()
    val popReset = in Bool ()
    val push = slave Stream (Bits(8 bits))
    val pop = master Stream (Bits(8 bits))
    val pushOccupancy = out UInt (6 bits)
    val popOccupancy = out UInt (6 bits)
  }

  private val pushClockDomain = ClockDomain(
    clock = io.pushClock,
    reset = io.pushReset,
    config = ClockDomainConfig(resetKind = SYNC)
  )
  private val popClockDomain = ClockDomain(
    clock = io.popClock,
    reset = io.popReset,
    config = ClockDomainConfig(resetKind = SYNC)
  )

  val fifo = morphhdl.frontend.StreamFifoCC(
    dataType = HardType(Bits(8 bits)),
    depth = depth,
    pushClock = pushClockDomain,
    popClock = popClockDomain,
    withPopBufferedReset = false
  )

  fifo.io.push << io.push
  io.pop << fifo.io.pop
  io.pushOccupancy := fifo.io.pushOccupancy.resized
  io.popOccupancy := fifo.io.popOccupancy.resized
}

class ParameterizedStreamFifoCCTests extends AnyFunSuite {
  private val ModuleDeclaration =
    """(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b""".r

  private def config(directory: Path): SpinalConfig =
    SpinalConfig(
      mode = Verilog,
      targetDirectory = directory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC),
      onlyStdLogicVectorAtTopLevelIo = false,
      anonymSignalPrefix = "tmp"
    )

  private def component(): NativeParameterizedStreamFifoCCHarness = {
    val depth = HdlInt.param(
      "DEPTH",
      default = BigInt(8),
      min = BigInt(4),
      max = BigInt(16)
    )
    new NativeParameterizedStreamFifoCCHarness(depth)
  }

  test("one untouched native StreamFifoCC definition retains DEPTH 4, 8 and 16") {
    withTemporaryDirectory { directory =>
      val firstDirectory = directory.resolve("first")
      val secondDirectory = directory.resolve("second")
      val concreteDirectory = directory.resolve("concrete")
      Files.createDirectories(firstDirectory)
      Files.createDirectories(secondDirectory)
      Files.createDirectories(concreteDirectory)

      val firstConfig = config(firstDirectory)
      firstConfig.netlistFileName = "stream_fifocc_parameterized.v"
      val first = MorphVerilog(firstConfig)(component())
      val firstRtl = firstDirectory.resolve("stream_fifocc_parameterized.v")
      val parameterized = read(firstRtl)
      val parameterizedLines = parameterized.linesIterator.toVector

      val secondConfig = config(secondDirectory)
      secondConfig.netlistFileName = "stream_fifocc_parameterized.v"
      val second = MorphVerilog(secondConfig)(component())
      assert(
        parameterized == read(
          secondDirectory.resolve("stream_fifocc_parameterized.v")
        )
      )
      assert(first.verilog == second.verilog)

      val generatedTop = first.top.asInstanceOf[NativeParameterizedStreamFifoCCHarness]
      val report = first.parameterReport
      val concreteConfig = config(concreteDirectory)
      concreteConfig.netlistFileName = "stream_fifocc_concrete.v"
      SpinalVerilog(concreteConfig)(component())
      val concrete = read(concreteDirectory.resolve("stream_fifocc_concrete.v"))

      val depthParameter = report.parameters.find(_.name == "DEPTH")
      assert(depthParameter.nonEmpty)
      assert(depthParameter.get.default == BigInt(8))
      assert(
        depthParameter.get.constraints == Vector(
          paramrtl.IntConstraint.MinInclusive(BigInt(4)),
          paramrtl.IntConstraint.MaxInclusive(BigInt(16))
        )
      )
      assert(generatedTop.fifo.getClass.getName == "spinal.lib.StreamFifoCC")
      assert(parameterized.contains("module NativeParameterizedStreamFifoCCHarness #("))
      assert(parameterized.contains("module StreamFifoCC #("))
      assert(parameterized.contains("parameter integer DEPTH = 8"))
      assert(parameterized.contains(".DEPTH(DEPTH)"))
      assert(
        parameterized.contains("[0:DEPTH-1]") ||
          parameterized.contains("[0:(DEPTH - 1)]")
      )
      assert(parameterized.contains("clog2(DEPTH"))
      assert(parameterized.contains("function integer clog2;"))
      assert(parameterized.contains("pushPtrGray"))
      assert(parameterized.contains("popPtrGray"))
      assert(parameterized.contains("BufferCC") || parameterized.contains("buffercc"))
      assert(!parameterized.contains("ParamRTL"))
      assert(
        !ModuleDeclaration
          .findAllMatchIn(parameterized)
          .exists(_.group(1) == "ParameterizedStreamFifoCC")
      )

      Vector(
        "popToPushGray_buffercc_io_dataOut",
        "pushToPopGray_buffercc_io_dataOut"
      ).foreach { name =>
        val declarations = parameterizedLines.filter { line =>
          line.trim.startsWith("wire") && line.contains(name)
        }
        assert(
          declarations.size == 1,
          s"Expected one declaration for $name, found ${declarations.mkString(" | ")}"
        )
        assert(
          declarations.head.contains("clog2(DEPTH") &&
            declarations.head.contains("+ 1"),
          s"$name retained a concrete witness width: ${declarations.head}"
        )
      }

      val synchronizerRegisters = parameterizedLines.filter { line =>
        line.contains(" reg ") &&
        (line.contains("buffers_0") || line.contains("buffers_1"))
      }
      assert(
        synchronizerRegisters.size == 4,
        s"Expected four BufferCC synchronizer registers, found ${synchronizerRegisters.mkString(" | ")}"
      )
      assert(
        synchronizerRegisters.forall(_.contains("[WIDTH-1:0]")),
        s"BufferCC internal registers retained a concrete witness width: ${synchronizerRegisters.mkString(" | ")}"
      )

      assert(!concrete.contains("parameter integer DEPTH"))
      assert(concrete.contains("[0:7]"))

      Vector(4, 8, 16).foreach { selectedDepth =>
        lintDepth(first, firstRtl, selectedDepth)
      }
    }
  }

  test("invalid native StreamFifoCC depth witnesses fail before construction") {
    withTemporaryDirectory { directory =>
      val invalidConfig = config(directory)
      invalidConfig.netlistFileName = "stream_fifocc_invalid.v"
      val error = intercept[MorphVerilogException] {
        MorphVerilog(invalidConfig) {
          val depth = HdlInt.param(
            "DEPTH",
            default = BigInt(6),
            min = BigInt(4),
            max = BigInt(16)
          )
          new NativeParameterizedStreamFifoCCHarness(depth)
        }
      }
      assert(error.getMessage.contains("MORPH-FRONTEND-STREAM-FIFO-CC-DEPTH-INVALID"))
    }
  }

  private def lintDepth(
      result: MorphVerilogResult[NativeParameterizedStreamFifoCCHarness],
      rtl: Path,
      depth: Int
  ): Unit = {
    val command = Seq(
      "verilator",
      "--lint-only",
      "--language",
      "1364-2001",
      "-Wno-DECLFILENAME",
      "-Wno-UNUSED",
      "-Wno-PINMISSING",
      "--top-module",
      "NativeParameterizedStreamFifoCCHarness",
      s"-GDEPTH=$depth",
      rtl.toAbsolutePath.toString
    )
    val output = new StringBuilder
    val exit = Process(command).!(ProcessLogger(
      line => output.append(line).append('\n'),
      line => output.append(line).append('\n')
    ))
    assert(
      exit == 0,
      s"Verilator rejected native StreamFifoCC DEPTH=$depth:\n$output"
    )
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-native-streamfifocc-")
    try body(directory)
    finally deleteRecursively(directory)
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.notExists(path)) return
    val stream = Files.walk(path)
    try {
      val values = stream.iterator()
      val all = scala.collection.mutable.ArrayBuffer.empty[Path]
      while (values.hasNext) all += values.next()
      all.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
    } finally stream.close()
  }
}
