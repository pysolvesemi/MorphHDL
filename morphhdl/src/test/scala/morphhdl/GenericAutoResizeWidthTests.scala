package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.HdlInt
import morphhdl.frontend.HdlInt.HdlIntBitCountOps

/**
  * Component-independent witness for native target-sized `.resized` edges.
  * One direction narrows or widens a symbolic UInt into a fixed target; the
  * other narrows or widens a fixed UInt into a symbolic target. No library
  * component, FIFO, CDC primitive or source-file identity participates.
  */
final class GenericAutoResizeWidthHarness(width: HdlInt) extends Component {
  setDefinitionName("GenericAutoResizeWidthHarness")

  val io = new Bundle {
    val symbolicInput = in(morphhdl.frontend.UInt(width.bits))
    val fixedInput = in UInt (6 bits)
    val fixedOutput = out UInt (6 bits)
    val symbolicOutput = out(morphhdl.frontend.UInt(width.bits))
  }

  io.fixedOutput := io.symbolicInput.resized
  io.symbolicOutput := io.fixedInput.resized
}

class GenericAutoResizeWidthTests extends AnyFunSuite {
  test("target-sized resize follows exact symbolic and fixed targets") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "generic_auto_resize_width.v"
      val width = HdlInt.param(
        "WIDTH",
        default = BigInt(8),
        min = BigInt(4),
        max = BigInt(16)
      )

      MorphVerilog(config) {
        new GenericAutoResizeWidthHarness(width)
      }

      val rtl = directory.resolve("generic_auto_resize_width.v")
      val verilog = read(rtl)
      assert(verilog.contains("module GenericAutoResizeWidthHarness #("))
      assert(verilog.contains("parameter integer WIDTH = 8"))
      assert(
        """(?m)^\s*input\s+wire\s+\[WIDTH-1:0\]\s+io_symbolicInput\s*,?\s*$""".r
          .findFirstIn(verilog)
          .nonEmpty
      )
      assert(
        """(?m)^\s*output\s+wire\s+\[WIDTH-1:0\]\s+io_symbolicOutput\s*;?\s*$""".r
          .findFirstIn(verilog)
          .nonEmpty
      )

      Vector(4, 8, 16).foreach { selectedWidth =>
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
            "GenericAutoResizeWidthHarness",
            s"-GWIDTH=$selectedWidth",
            rtl.toString
          )
        )
        assert(
          result._1 == 0,
          s"Verilator rejected generic target-sized resize WIDTH=$selectedWidth:\n${result._2}"
        )
      }
    }
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
    val directory = Files.createTempDirectory("morphhdl-generic-auto-resize-")
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
