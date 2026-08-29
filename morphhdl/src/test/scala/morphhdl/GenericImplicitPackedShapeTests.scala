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
  * An ordinary native SpinalHDL child with no MorphHDL constructor adapter and
  * no explicit formal parameter. Its concrete packed shape is propagated only
  * through full-width child-local assignments.
  */
final class GenericConcretePackedPipeline(width: Int) extends Component {
  setDefinitionName("GenericConcretePackedPipeline")

  val io = new Bundle {
    val input = in Bits (width bits)
    val output = out Bits (width bits)
  }

  val stage0 = Reg(Bits(width bits)) init (0)
  val stage1 = Reg(Bits(width bits)) init (0)
  stage0 := io.input
  stage1 := stage0
  io.output := stage1
}

/**
  * The parent alone owns the public symbolic width. The child receives only the
  * concrete witness, proving that hierarchy shape recovery is independent of
  * StreamFifo, StreamFifoCC, BufferCC, source-file names and class names.
  */
final class GenericImplicitPackedShapeHarness(width: HdlInt) extends Component {
  setDefinitionName("GenericImplicitPackedShapeHarness")

  val io = new Bundle {
    val input = in(morphhdl.frontend.Bits(width.bits))
    val output = out(morphhdl.frontend.Bits(width.bits))
  }

  val child = new GenericConcretePackedPipeline(8)
  child.setName("child")
  child.io.input := io.input
  io.output := child.io.output
}

class GenericImplicitPackedShapeTests extends AnyFunSuite {
  test("arbitrary native child packed lineage receives a child-local formal") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "generic_implicit_packed_shape.v"
      val width = HdlInt.param(
        "WIDTH",
        default = BigInt(8),
        min = BigInt(4),
        max = BigInt(16)
      )

      MorphVerilog(config) {
        new GenericImplicitPackedShapeHarness(width)
      }

      val rtl = directory.resolve("generic_implicit_packed_shape.v")
      val verilog = read(rtl)
      val lines = verilog.split("\n", -1).toVector

      assert(verilog.contains("module GenericImplicitPackedShapeHarness #("))
      assert(verilog.contains("module GenericConcretePackedPipeline #("))
      assert(verilog.contains("parameter integer WIDTH = 8"))
      assert(verilog.contains(".WIDTH(WIDTH)"))

      Vector("stage0", "stage1").foreach { name =>
        val declarations = lines.filter { line =>
          line.contains(" reg ") && line.contains(name)
        }
        assert(
          declarations.size == 1,
          s"Expected one declaration for $name, found ${declarations.mkString(" | ")}"
        )
        assert(
          declarations.head.contains("[WIDTH-1:0]"),
          s"$name retained a concrete witness width: ${declarations.head}"
        )
      }

      Vector(4, 8, 16).foreach { selectedWidth =>
        val command = Seq(
          "verilator",
          "--lint-only",
          "--language",
          "1364-2001",
          "-Wall",
          "-Wno-DECLFILENAME",
          "-Wno-UNUSED",
          "--top-module",
          "GenericImplicitPackedShapeHarness",
          s"-GWIDTH=$selectedWidth",
          rtl.toString
        )
        val result = run(directory, command)
        assert(
          result._1 == 0,
          s"Verilator lint failed for generic WIDTH=$selectedWidth:\n${result._2}"
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
    val directory = Files.createTempDirectory("morphhdl-generic-packed-lineage-")
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
