package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import morphhdl.frontend.{HdlInt, StreamFifoCC => MorphStreamFifoCC}

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
    val pushOccupancy = out UInt (6 bits)
    val popOccupancy = out UInt (6 bits)
  }

  val pushCd = ClockDomain(
    clock = io.pushClock,
    reset = io.pushReset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )
  val popCd = ClockDomain(
    clock = io.popClock,
    reset = io.popReset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  val fifo = MorphStreamFifoCC(
    HardType(Bits(8 bits)),
    depth,
    pushCd,
    popCd,
    withPopBufferedReset = false
  )
  fifo.setName("fifo")
  fifo.io.push << io.push
  io.pop << fifo.io.pop
  io.pushOccupancy := fifo.io.pushOccupancy.resized
  io.popOccupancy := fifo.io.popOccupancy.resized
}

class ParameterizedStreamFifoCCTests extends AnyFunSuite {
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
      val first = directory.resolve("first")
      val replay = directory.resolve("replay")
      val concreteDirectory = directory.resolve("concrete")
      Files.createDirectories(first)
      Files.createDirectories(replay)
      Files.createDirectories(concreteDirectory)

      var generatedTop: NativeParameterizedStreamFifoCCHarness = null
      val firstConfig = config(first)
      firstConfig.netlistFileName = "stream_fifocc_parameterized.v"
      val report = MorphVerilog(firstConfig) {
        generatedTop = component()
        generatedTop
      }
      val firstRtl = first.resolve("stream_fifocc_parameterized.v")
      val parameterized = read(firstRtl)

      val replayConfig = config(replay)
      replayConfig.netlistFileName = "stream_fifocc_parameterized.v"
      MorphVerilog(replayConfig)(component())
      val replayRtl = replay.resolve("stream_fifocc_parameterized.v")
      assert(
        java.util.Arrays.equals(
          Files.readAllBytes(firstRtl),
          Files.readAllBytes(replayRtl)
        ),
        "native StreamFifoCC generation was not byte deterministic"
      )

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
      assert(!parameterized.contains("ParameterizedStreamFifoCC"))

      assert(!concrete.contains("parameter integer DEPTH"))
      assert(concrete.contains("[0:7]"))

      Vector(4, 8, 16).foreach { selectedDepth =>
        lintDepth(first, firstRtl, selectedDepth)
      }
    }
  }

  test("invalid native StreamFifoCC depth witnesses fail before construction") {
    withTemporaryDirectory { directory =>
      val config = this.config(directory)
      config.netlistFileName = "stream_fifocc_invalid.v"
      MorphVerilog.tryGenerate(config) {
        val depth = HdlInt.param(
          "DEPTH",
          default = BigInt(6),
          min = BigInt(4),
          max = BigInt(16)
        )
        new NativeParameterizedStreamFifoCCHarness(depth)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "MORPH-FRONTEND-NATIVE-STREAMFIFOCC-DEPTH-WITNESS-NOT-POWER-OF-TWO"
            )
          )
        case Right(report) =>
          fail(s"Expected non-power-of-two witness rejection, received $report")
      }
    }
  }

  private def config(directory: Path): SpinalConfig =
    SpinalConfig(targetDirectory = directory.toString)

  private def lintDepth(directory: Path, rtl: Path, selectedDepth: Int): Unit = {
    val command = Seq(
      "verilator",
      "--lint-only",
      "--language",
      "1364-2001",
      "-Wall",
      "-Wno-DECLFILENAME",
      "-Wno-WIDTH",
      "-Wno-UNUSED",
      "--top-module",
      "NativeParameterizedStreamFifoCCHarness",
      s"-GDEPTH=$selectedDepth",
      rtl.toString
    )
    val result = run(directory, command)
    assert(
      result._1 == 0,
      s"Verilator lint failed for StreamFifoCC DEPTH=$selectedDepth:\n${result._2}"
    )
  }

  private def run(directory: Path, command: Seq[String]): (Int, String) = {
    val output = new StringBuilder
    val status = Process(command, directory.toFile).!(
      ProcessLogger(
        line => output.append(line).append('\n'),
        line => output.append(line).append('\n')
      )
    )
    status -> output.toString
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-native-streamfifocc-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(Files.deleteIfExists)
      } finally stream.close()
    }
  }
}
