package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.{MorphNamedFieldVectors, MorphVerilog}
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

/** Fixed-width unsigned saturation combined with other whole-record fields.
  * The unchanged native callback remains the sole datapath description.
  */
final case class BalancedSaturatingRecord(width: HdlInt, tagWidth: HdlInt) extends Bundle {
  val value = UInt(width bits)
  val tag = Bits(tagWidth bits)
  val valid = Bool()
}

final class BalancedSaturatingReduction(width: HdlInt, tagWidth: HdlInt,
    count: HdlInt, moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val values = in(Vec(BalancedSaturatingRecord(width, tagWidth), count)).setName("values")
  val result = out(BalancedSaturatingRecord(width, tagWidth)).setName("result")

  result := values.reduceBalancedTree((a: BalancedSaturatingRecord, b: BalancedSaturatingRecord) => {
    val combined = cloneOf(a)
    combined.value := a.value +| b.value
    combined.tag := a.tag ^ b.tag
    combined.valid := a.valid | b.valid
    combined
  })
}

object TypedBalancedReductionCompositeSaturationArtifacts {
  val layouts = Vector("packed", "fields")

  private def config(directory: Path, file: String): SpinalConfig = {
    Files.createDirectories(directory)
    val result = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, headerWithRepoHash = false, bitVectorWidthMax = 8192)
    result.netlistFileName = file
    result
  }

  def emit(root: Path, layout: String): Path = {
    require(layouts.contains(layout))
    val module = "BalancedSaturatingReduction_" + layout
    val file = module + ".v"
    val base = config(root.resolve(layout), file)
    val selected = if (layout == "fields") MorphNamedFieldVectors.enable(base) else base
    MorphVerilog(selected) {
      new BalancedSaturatingReduction(
        HdlInt.param("WIDTH", 5, 1, 32),
        HdlInt.param("TAG_WIDTH", 3, 1, 16),
        HdlInt.param("COUNT", 5, 1, 17),
        module)
    }
    val path = root.resolve(layout).resolve(file)
    require(Files.isRegularFile(path), "saturation candidate was not emitted: " + path)
    path
  }
}

class TypedBalancedReductionCompositeSaturationTests extends AnyFunSuite {
  private def text(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def outputRoot(): Path = Option(System.getenv("MORPHHDL_59I_SAT_OUTPUT"))
    .filter(_.nonEmpty).map(Paths.get(_)).getOrElse(Files.createTempDirectory("balanced-saturation-"))

  test("unsigned saturation composes with whole-record reduction in packed and field layouts") {
    val root = outputRoot()
    for (layout <- TypedBalancedReductionCompositeSaturationArtifacts.layouts) {
      val rtl = text(TypedBalancedReductionCompositeSaturationArtifacts.emit(root, layout))
      assert(rtl.contains("WIDTH = 5"), rtl)
      assert(rtl.contains("TAG_WIDTH = 3"), rtl)
      assert(rtl.contains("COUNT = 5"), rtl)
      assert(rtl.contains("morphhdl_balanced_"), rtl)
      assert(rtl.contains("result_value") && rtl.contains("result_tag") && rtl.contains("result_valid"), rtl)
      if (layout == "fields") {
        assert(rtl.contains("values_value") && rtl.contains("values_tag") && rtl.contains("values_valid"), rtl)
      }
      var depth = 0
      "\\b(generate|endgenerate)\\b".r.findAllIn(rtl).foreach {
        case "generate" => depth += 1; assert(depth == 1, "nested generate region\n" + rtl)
        case "endgenerate" => depth -= 1; assert(depth == 0, "unbalanced generate region\n" + rtl)
      }
      assert(depth == 0)
    }
  }

  test("singleton and odd concrete reductions retain native saturation semantics") {
    val directory = Files.createTempDirectory("balanced-saturation-concrete-")
    val file = "BalancedSaturatingConcrete.v"
    val config = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, headerWithRepoHash = false)
    config.netlistFileName = file
    SpinalVerilog(config) {
      new BalancedSaturatingReduction(HdlInt.literal(3), HdlInt.literal(2),
        HdlInt.literal(5), "BalancedSaturatingConcrete")
    }
    val rtl = text(directory.resolve(file))
    assert(rtl.contains("module BalancedSaturatingConcrete"), rtl)
    assert(!rtl.contains("parameter"), rtl)
    assert(rtl.contains("result_value") && rtl.contains("result_valid"), rtl)
  }
}
