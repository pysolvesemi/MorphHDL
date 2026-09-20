package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import spinal.core._

/** Standalone nested registers exercise indexed writes without a whole-value
  * combinational default. Native references retain ordinary Int Vec semantics.
  */
object NamedFieldNestedRegisterFixture {
  import NamedFieldVecFixture.{NativePixel, Pixel, config, parameter}

  final case class Row(width: ElabInt, blueWidth: ElabInt, inner: ElabInt) extends Bundle {
    val colors = Vec(Pixel(width, blueWidth), inner)
  }
  final case class NativeRow(width: Int, blueWidth: Int, inner: Int) extends Bundle {
    val colors = Vec(NativePixel(width, blueWidth), inner)
    require(colors.size == inner)
    require(colors.asInstanceOf[Data].flatten.map(_.getBitsWidth).sum == inner * (2 * width + blueWidth + 1))
  }

  final class Candidate(width: ElabInt, blueWidth: ElabInt, count: ElabInt, inner: ElabInt)
      extends Component {
    setDefinitionName("NamedFieldNestedRegister")
    val clk = in(Bool())
    val enable = in(Bool())
    val outerIndex = in(UInt(64 bits))
    val innerIndex = in(UInt(64 bits))
    val replacement = in(Pixel(width, blueWidth))
    val result = out(Vec(Row(width, blueWidth, inner), count))
    val area = new ClockingArea(ClockDomain(clock = clk)) {
      val storage = Reg(Vec(Row(width, blueWidth, inner), count))
        .setName("storage").dontSimplifyIt()
      when(enable) { storage(outerIndex).colors(innerIndex) := replacement }
    }
    result := area.storage
  }

  final class Reference(width: Int, blueWidth: Int, count: Int, inner: Int) extends Component {
    require(width > 0 && blueWidth > 0 && count > 0 && inner > 0)
    setDefinitionName(s"NativeFieldNestedRegister_w${width}_b${blueWidth}_n${count}_i$inner")
    val clk = in(Bool())
    val enable = in(Bool())
    val outerIndex = in(UInt(64 bits))
    val innerIndex = in(UInt(64 bits))
    val replacement = in(NativePixel(width, blueWidth))
    val result = out(Vec(NativeRow(width, blueWidth, inner), count))
    val outerInRange = outerIndex.resize(65) < U(count, 65 bits)
    val innerInRange = innerIndex.resize(65) < U(inner, 65 bits)
    val area = new ClockingArea(ClockDomain(clock = clk)) {
      val storage = Reg(Vec(NativeRow(width, blueWidth, inner), count))
        .setName("storage").dontSimplifyIt()
      when(enable && outerInRange && innerInRange) {
        storage(outerIndex.resized).colors(innerIndex.resized) := replacement
      }
    }
    result := area.storage
  }

  def candidate(directory: Path, named: Boolean): Unit = {
    val base = config(directory, "NamedFieldNestedRegister.v")
    val selected = if (named) MorphNamedFieldVectors.enable(base) else MorphNamedFieldVectors.disable(base)
    MorphVerilog(MorphSignedCasts.enable(selected)) {
      new Candidate(parameter("WIDTH", 5, 5), parameter("BLUE_WIDTH", 3, 5),
        parameter("COUNT", 1, 3), parameter("INNER", 1, 3))
    }
  }
}

object NamedFieldNestedRegisterArtifactWriter {
  import NamedFieldNestedRegisterFixture._
  import NamedFieldVecFixture.config

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "provide a nested-register qualification output directory")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    candidate(root.resolve("candidate"), named = true)
    candidate(root.resolve("legacy"), named = false)
    val entries = for {
      (width, blueWidth) <- Vector((1, 5), (5, 3))
      count <- Vector(1, 3)
      inner <- Vector(1, 3)
    } yield {
      val stem = s"register_w${width}_b${blueWidth}_n${count}_i$inner"
      val module = s"NativeFieldNestedRegister_w${width}_b${blueWidth}_n${count}_i$inner"
      val directory = root.resolve(s"reference/$stem")
      SpinalVerilog(config(directory, module + ".v")) {
        new Reference(width, blueWidth, count, inner)
      }
      s"""    {"kind":"nested-register","width":$width,"blue_width":$blueWidth,"count":$count,"inner":$inner,"reference_module":"$module","reference_rtl":"reference/$stem/$module.v"}"""
    }
    val manifest = "{\n  \"scope\":\"named-field-nested-register-native-equivalence\",\n" +
      "  \"candidate_default\":{\"width\":5,\"blue_width\":3,\"count\":1,\"inner\":1},\n" +
      "  \"dimension_order\":\"outer-major-inner-minor-element-zero-low\",\n" +
      "  \"configurations\":[\n" + entries.mkString(",\n") + "\n  ]\n}\n"
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
