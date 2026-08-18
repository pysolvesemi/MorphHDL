package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.regex.Pattern

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend._

object StructuralGenerateControlSmoke {
  final class LaneSink extends Component {
    setDefinitionName("StructuralLaneSink")

    val din = in(morphhdl.frontend.Bits(8 bits))
    val observed = out(Bool())

    observed := din.orR
  }

  final class StructuralLoopTop(lanes: HdlInt) extends Component {
    setDefinitionName("StructuralLoopTop")

    val packedIn = in(morphhdl.frontend.Bits(32 bits))
    val alive = out(Bool())

    alive := packedIn.orR

    (0 until lanes).named("g_lane", "lane").foreach { lane =>
      val laneWire = morphhdl.frontend.Bits(8 bits)
      laneWire := packedIn(
        lane * HdlInt.literal(BigInt(8)),
        HdlInt.literal(BigInt(8))
      )

      val sink = new LaneSink
      sink.din := laneWire
    }
  }

  final class InclusiveLoopTop(last: HdlInt) extends Component {
    setDefinitionName("InclusiveLoopTop")

    val packedIn = in(morphhdl.frontend.Bits(32 bits))
    val alive = out(Bool())

    alive := packedIn.orR

    (0 to last).named("g_inclusive", "inclusive").foreach { index =>
      val laneWire = morphhdl.frontend.Bits(8 bits)
      laneWire := packedIn(
        index * HdlInt.literal(BigInt(8)),
        HdlInt.literal(BigInt(8))
      )

      val sink = new LaneSink
      sink.din := laneWire
    }
  }

  final class StructuralVecTop(lanes: HdlInt) extends Component {
    setDefinitionName("StructuralVecTop")

    val laneIn = in(morphhdl.frontend.Vec(morphhdl.frontend.Bits(8 bits), 4))
    val alive = out(Bool())

    alive := laneIn(0).orR

    (0 until lanes).named("g_vec_lane", "lane").foreach { index =>
      val laneWire = morphhdl.frontend.Bits(8 bits)
      laneWire := laneIn(index)

      val sink = new LaneSink
      sink.din := laneWire
    }
  }

  final class StructuralConditionalTop(enable: HdlBool, mode: HdlInt)
      extends Component {
    setDefinitionName("StructuralConditionalTop")

    val din = in(morphhdl.frontend.Bits(8 bits))
    val alive = out(Bool())

    alive := din.orR

    enable.generateIf("g_enabled", "g_disabled") {
      attachSink(din)
    }.otherwise {
      attachSink(~din)
    }

    mode.generateCase
      .choice(BigInt(0), "g_mode_zero") {
        attachSink(din)
      }
      .choice(BigInt(1), "g_mode_one") {
        attachSink(~din)
      }
      .default("g_mode_other") {
        attachSink(din)
      }

    private def attachSink(value: Bits): Unit = {
      val branchWire = morphhdl.frontend.Bits(8 bits)
      branchWire := value

      val sink = new LaneSink
      sink.din := branchWire
    }
  }

  def loopComponent(): Component = {
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 4)
    new StructuralLoopTop(lanes)
  }

  def inclusiveComponent(): Component = {
    val last = HdlInt.param("LAST", default = 3, min = 0, max = 3)
    new InclusiveLoopTop(last)
  }

  def vecComponent(): Component = {
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 4)
    new StructuralVecTop(lanes)
  }

  def conditionalComponent(): Component = {
    val enable = HdlBool.param("ENABLE", default = true)
    val mode = HdlInt.param("MODE", default = 1, min = 0, max = 2)
    new StructuralConditionalTop(enable, mode)
  }

  def emitMorph(
      directory: Path,
      filename: String,
      component: => Component
  ): String = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    MorphVerilog(config)(component)
    read(directory.resolve(filename))
  }

  def emitConcrete(
      directory: Path,
      filename: String,
      component: => Component
  ): String = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    SpinalVerilog(config)(component)
    read(directory.resolve(filename))
  }

  def emitAll(directory: Path): Unit = {
    emitMorph(directory, "structural_loop.v", loopComponent())
    emitMorph(directory, "structural_inclusive.v", inclusiveComponent())
    emitMorph(directory, "structural_vec.v", vecComponent())
    emitMorph(directory, "structural_conditionals.v", conditionalComponent())
  }

  def main(args: Array[String]): Unit = {
    val directory = args.toList match {
      case "--output-dir" :: value :: Nil => Paths.get(value)
      case _ =>
        throw new IllegalArgumentException(
          "Usage: StructuralGenerateControlSmoke --output-dir <directory>"
        )
    }
    emitAll(directory)
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
}

class StructuralGenerateControlTests extends AnyFunSuite {
  import StructuralGenerateControlSmoke._

  test("ordinary until loops lower declarations slices connections and child instances into one generate-for") {
    withTemporaryDirectory { directory =>
      val parameterized = emitMorph(
        directory.resolve("parameterized"),
        "structural_loop.v",
        loopComponent()
      )
      val concrete = emitConcrete(
        directory.resolve("concrete"),
        "structural_loop.v",
        loopComponent()
      )

      assert(parameterized.contains("module StructuralLoopTop #("))
      assert(parameterized.contains("parameter integer LANES = 4"))
      assert(parameterized.contains("genvar lane;"))
      assert(
        parameterized.contains(
          "for (lane = 0; lane < LANES; lane = lane + 1) begin : g_lane"
        )
      )
      assert(parameterized.contains("packedIn[(lane * 8) +: 8]"))
      assert(instanceCount(parameterized, "StructuralLaneSink") == 1)
      assert(moduleCount(parameterized, "StructuralLaneSink") == 1)
      assert(!parameterized.contains("ParamRTL"))
      assert(!parameterized.contains("parameterizedDesign"))

      assert(!concrete.contains("genvar lane"))
      assert(!concrete.contains("begin : g_lane"))
      assert(instanceCount(concrete, "StructuralLaneSink") == 4)
      val compactConcrete = concrete.replaceAll("\\s+", "")
      Vector("[7:0]", "[15:8]", "[23:16]", "[31:24]").foreach { slice =>
        assert(compactConcrete.contains(slice), s"concrete witness lost slice $slice")
      }
    }
  }

  test("inclusive to ranges retain an end-plus-one generate bound") {
    withTemporaryDirectory { directory =>
      val verilog = emitMorph(
        directory,
        "structural_inclusive.v",
        inclusiveComponent()
      )

      assert(verilog.contains("parameter integer LAST = 3"))
      assert(
        verilog.contains(
          "for (inclusive = 0; inclusive < (LAST + 1); inclusive = inclusive + 1) begin : g_inclusive"
        )
      )
      assert(verilog.contains("packedIn[(inclusive * 8) +: 8]"))
      assert(instanceCount(verilog, "StructuralLaneSink") == 1)
    }
  }

  test("generate-index Vec selection covers every admitted static element") {
    withTemporaryDirectory { directory =>
      val verilog = emitMorph(directory, "structural_vec.v", vecComponent())

      assert(verilog.contains("parameter integer LANES = 4"))
      assert(verilog.contains("case (lane)"))
      Vector(0, 1, 2, 3).foreach { index =>
        assert(verilog.contains(s"$index: begin : g_vec_$index"))
      }
      assert(verilog.contains("default: begin : g_vec_default"))
      assert(instanceCount(verilog, "StructuralLaneSink") == 4)
      assert(!verilog.contains("ParamRTL"))
    }
  }

  test("parameter-controlled generate-if and generate-case retain ordinary branch hardware") {
    withTemporaryDirectory { directory =>
      val parameterized = emitMorph(
        directory.resolve("parameterized"),
        "structural_conditionals.v",
        conditionalComponent()
      )
      val concrete = emitConcrete(
        directory.resolve("concrete"),
        "structural_conditionals.v",
        conditionalComponent()
      )

      assert(parameterized.contains("parameter integer ENABLE = 1"))
      assert(parameterized.contains("parameter integer MODE = 1"))
      assert(parameterized.contains("if ((ENABLE == 1)) begin : g_enabled"))
      assert(parameterized.contains("end else begin : g_disabled"))
      assert(parameterized.contains("case (MODE)"))
      assert(parameterized.contains("0: begin : g_mode_zero"))
      assert(parameterized.contains("1: begin : g_mode_one"))
      assert(parameterized.contains("default: begin : g_mode_other"))
      assert(instanceCount(parameterized, "StructuralLaneSink") == 5)

      assert(!concrete.contains("begin : g_enabled"))
      assert(!concrete.contains("case (MODE)"))
      assert(instanceCount(concrete, "StructuralLaneSink") == 2)
    }
  }

  test("Scala-only generate bodies fail explicitly instead of silently specializing") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "scala_side_effect.v"
      val result = MorphVerilog.tryGenerate(config) {
        val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 4)
        new Component {
          setDefinitionName("ScalaSideEffectTop")
          val scalaValues = scala.collection.mutable.ArrayBuffer.empty[Int]
          (0 until lanes).foreach { _ =>
            scalaValues += 1
          }
        }
      }

      result match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SCALA-SIDE-EFFECT-UNSUPPORTED"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected unsupported Scala-side-effect failure, received $report")
      }
      assert(!Files.exists(directory.resolve("scala_side_effect.v")))
    }
  }

  test("unfinished structural conditionals fail with a stable continuation diagnostic") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "missing_otherwise.v"
      val result = MorphVerilog.tryGenerate(config) {
        val enable = HdlBool.param("ENABLE", default = true)
        new Component {
          setDefinitionName("MissingOtherwiseTop")
          val din = in(morphhdl.frontend.Bits(8 bits))
          val alive = out(Bool())
          alive := din.orR

          enable.generateIf("g_yes", "g_no") {
            val branchWire = morphhdl.frontend.Bits(8 bits)
            branchWire := din
            val sink = new LaneSink
            sink.din := branchWire
          }
        }
      }

      result match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUATION-MISSING"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected missing-continuation failure, received $report")
      }
      assert(!Files.exists(directory.resolve("missing_otherwise.v")))
    }
  }

  private def instanceCount(verilog: String, moduleName: String): Int =
    ("(?m)^\\s+" + Pattern.quote(moduleName) + "\\b").r
      .findAllMatchIn(verilog)
      .size

  private def moduleCount(verilog: String, moduleName: String): Int =
    ("(?m)^module\\s+" + Pattern.quote(moduleName) + "\\b").r
      .findAllMatchIn(verilog)
      .size

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-structural-generate-test-")
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
