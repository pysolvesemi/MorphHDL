package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import morphhdl.{MorphNamedFieldVectors, MorphVerilog}
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

object CompositeWideningPublicationHelpers {
  def typedWidth(value: BaseType): ElabInt =
    ElabInt.widthOf(value)

  def combine(a: BalancedCompositeWideningValue,
      b: BalancedCompositeWideningValue): BalancedCompositeWideningValue = {
    val unsignedSum = a.unsignedSum +^ b.unsignedSum
    val unsignedProduct = a.unsignedProduct * b.unsignedProduct
    val signedSum = a.signedSum +^ b.signedSum
    val signedProduct = a.signedProduct * b.signedProduct
    val result = BalancedCompositeWideningValue(typedWidth(unsignedSum), typedWidth(unsignedProduct),
      typedWidth(signedSum), typedWidth(signedProduct))
    result.unsignedSum := unsignedSum
    result.unsignedProduct := unsignedProduct
    result.signedSum := signedSum
    result.signedProduct := signedProduct
    result
  }
}

final class CompositeWideningPublicationHardware(width: HdlInt, count: HdlInt,
    moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val initial = ElabInt.fromExpression(width.bits.expression.get)
  val values = in(Vec(BalancedCompositeWideningValue(initial, initial, initial, initial), count))
    .setName("values")
  val reduced = values.reduceBalancedTree(
    (a: BalancedCompositeWideningValue, b: BalancedCompositeWideningValue) =>
      CompositeWideningPublicationHelpers.combine(a, b))
  val result = out(BalancedCompositeWideningValue(
    CompositeWideningPublicationHelpers.typedWidth(reduced.unsignedSum),
    CompositeWideningPublicationHelpers.typedWidth(reduced.unsignedProduct),
    CompositeWideningPublicationHelpers.typedWidth(reduced.signedSum),
    CompositeWideningPublicationHelpers.typedWidth(reduced.signedProduct))).setName("result")
  result := reduced
}

class TypedBalancedReductionCompositeWideningPublicationTests extends AnyFunSuite {
  private def emit(layout: String, defaultCount: Int): String = {
    val directory = Files.createTempDirectory("composite-widening-publication-")
    val name = s"CompositeWidening_${layout}_d$defaultCount"
    val base = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false,
      bitVectorWidthMax = 65536)
    base.netlistFileName = name + ".v"
    val config = if (layout == "fields") MorphNamedFieldVectors.enable(base) else base
    MorphVerilog(config) {
      new CompositeWideningPublicationHardware(HdlInt.param("WIDTH", 5, 1, 32),
        HdlInt.param("COUNT", defaultCount, 1, 5), name)
    }
    val file = directory.resolve(name + ".v")
    assert(Files.isRegularFile(file), "widening candidate was not emitted")
    new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
  }

  for (layout <- Vector("packed", "fields"); default <- Vector(1, 5)) {
    test(s"publish independent widening Bundle leaves: $layout default=$default") {
      val rtl = emit(layout, default)
      Vector("WIDTH", "COUNT", "result_unsignedSum", "result_unsignedProduct",
        "result_signedSum", "result_signedProduct", "morphhdl_balanced_1_stage_0")
        .foreach(token => assert(rtl.contains(token), s"missing $token\n$rtl"))
      if (layout == "fields")
        Vector("values_unsignedSum", "values_unsignedProduct", "values_signedSum",
          "values_signedProduct").foreach(token => assert(rtl.contains(token), rtl))
      var depth = 0
      "\\b(generate|endgenerate)\\b".r.findAllIn(rtl).foreach {
        case "generate" => depth += 1; assert(depth == 1, "nested generate region\n" + rtl)
        case "endgenerate" => depth -= 1; assert(depth == 0, "unbalanced generate region\n" + rtl)
      }
      assert(depth == 0)
    }
  }
}
