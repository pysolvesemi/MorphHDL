#!/usr/bin/env python3
"""Write the source-bound nested-Vec widening development suite.

This is deliberately a test-only probe.  It does not change production or claim
59i closure; promotion requires generated-RTL/native equivalence afterwards.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEST = ROOT / "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionNestedVecWideningTests.scala"

SOURCE = r'''package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

final case class BalancedNestedWideningLane(sumWidth: ElabInt, productWidth: ElabInt)
    extends Bundle {
  val sum = UInt(sumWidth bits)
  val product = SInt(productWidth bits)
}

final case class BalancedNestedWideningValue(sumWidth: ElabInt, productWidth: ElabInt)
    extends Bundle {
  val lanes = Vec(BalancedNestedWideningLane(sumWidth, productWidth), 2)
  val valid = Bool()
}

private object BalancedNestedWideningFixture {
  def typedWidth(value: BaseType): ElabInt =
    ElabInt.fromExpression(ParameterizedWidth.expressionOf(value)
      .getOrElse(ElabInt.literal(value.getBitsWidth).expression))

  def combine(a: BalancedNestedWideningValue,
      b: BalancedNestedWideningValue): BalancedNestedWideningValue = {
    val sums = Vector.tabulate(2)(index => a.lanes(index).sum +^ b.lanes(index).sum)
    val products = Vector.tabulate(2)(index => a.lanes(index).product * b.lanes(index).product)
    val result = BalancedNestedWideningValue(typedWidth(sums.head), typedWidth(products.head))
    for (index <- 0 until 2) {
      result.lanes(index).sum := sums(index)
      result.lanes(index).product := products(index)
    }
    result.valid := a.valid | b.valid
    result
  }

  def initialize(value: BalancedNestedWideningValue): Unit = {
    for (index <- 0 until 2) {
      value.lanes(index).sum := 0
      value.lanes(index).product := 0
    }
    value.valid := False
  }
}

private final class BalancedNestedVecWideningPublic(width: HdlInt, count: HdlInt,
    moduleName: String) extends Component {
  setDefinitionName(moduleName)
  private val initial = ElabInt.fromExpression(width.bits.expression.get)
  val values = in(Vec(BalancedNestedWideningValue(initial, initial), count)).setName("values")
  val reduced = values.reduceBalancedTree(
    (a: BalancedNestedWideningValue, b: BalancedNestedWideningValue) =>
      BalancedNestedWideningFixture.combine(a, b))
  val result = out(cloneOf(reduced)).setName("result")
  result := reduced
}

class TypedBalancedReductionNestedVecWideningTests extends AnyFunSuite {
  private def native[T <: Data]: ElabBalancedReduction.Native[T] =
    (values, operation, bridge) =>
      new TraversableOnceAnyPimped[T](values).reduceBalancedTree(operation, bridge)

  private def details(error: Throwable): String =
    if (error == null) "" else Option(error.getMessage).getOrElse("") + "\n" + details(error.getCause)

  test("two inner Vec lanes retain independent recursive widening through singleton and odd tails") {
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    val count = HdlInt.param("COUNT", 1, 1, 5)
    var hasWidening = false
    var widths = Vector.empty[(Int, Int, Int, Int)]
    var paths = Vector.empty[String]
    SpinalConfig(targetDirectory = Files.createTempDirectory("nested-vec-widening-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val initial = ElabInt.fromExpression(width.bits.expression.get)
      val values = Vec(BalancedNestedWideningValue(initial, initial), count)
      values.vec.foreach(BalancedNestedWideningFixture.initialize)
      val certificate = TypedBalancedReductionCompositeReplay.capture(values,
        (a: BalancedNestedWideningValue, b: BalancedNestedWideningValue) =>
          BalancedNestedWideningFixture.combine(a, b),
        (value: BalancedNestedWideningValue, _: Int) => value,
        native[BalancedNestedWideningValue])
      hasWidening = certificate.hasWidening
      paths = certificate.captured.result.flattenLocalName.toVector
      widths = (1 to 5).map { size =>
        val result = certificate.replay(values.vec.take(size).toVector)
        (result.lanes(0).sum.getBitsWidth, result.lanes(1).sum.getBitsWidth,
          result.lanes(0).product.getBitsWidth, result.lanes(1).product.getBitsWidth)
      }.toVector
      certificate.requireFreshness()
    })
    assert(hasWidening)
    assert(paths.exists(_.contains("lanes_0_sum")) && paths.exists(_.contains("lanes_1_product")), paths)
    for (size <- 1 to 5) {
      val expectedSum = 5 + (BigInt(size) - 1).bitLength
      val expectedProduct = 5 * size
      assert(widths(size - 1) == (expectedSum, expectedSum, expectedProduct, expectedProduct),
        s"size=$size widths=${widths(size - 1)}")
    }
  }

  test("public parameterized RTL retains WIDTH COUNT and both recursive lane paths") {
    val directory = Files.createTempDirectory("nested-vec-widening-public-")
    val file = "BalancedNestedVecWideningPublic.v"
    val config = SpinalConfig(targetDirectory = directory.toString, bitVectorWidthMax = 8192)
    config.netlistFileName = file
    MorphVerilog(config) {
      new BalancedNestedVecWideningPublic(HdlInt.param("WIDTH", 5, 1, 32),
        HdlInt.param("COUNT", 1, 1, 5), "BalancedNestedVecWideningPublic")
    }
    val path = directory.resolve(file)
    assert(Files.isRegularFile(path))
    val rtl = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    assert(rtl.contains("parameter") && rtl.contains("WIDTH") && rtl.contains("COUNT"), rtl)
    assert(rtl.contains("morphhdl_balanced_") && rtl.contains("generate"), rtl)
    assert(rtl.contains("result_lanes_0_sum") && rtl.contains("result_lanes_1_product"), rtl)
  }

  test("cross-lane widening substitution remains rejected") {
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    val error = intercept[Exception] {
      SpinalConfig(targetDirectory = Files.createTempDirectory("nested-vec-widening-reject-").toString,
        headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
        val initial = ElabInt.fromExpression(width.bits.expression.get)
        val values = Vec(BalancedNestedWideningValue(initial, initial),
          HdlInt.param("COUNT", 2, 1, 2))
        values.vec.foreach(BalancedNestedWideningFixture.initialize)
        TypedBalancedReductionCompositeReplay.capture(values,
          (a: BalancedNestedWideningValue, b: BalancedNestedWideningValue) => {
            val sums = Vector.tabulate(2)(index => a.lanes(index).sum +^ b.lanes(index).sum)
            val products = Vector.tabulate(2)(index => a.lanes(index).product * b.lanes(index).product)
            val result = BalancedNestedWideningValue(
              BalancedNestedWideningFixture.typedWidth(sums.head),
              BalancedNestedWideningFixture.typedWidth(products.head))
            result.lanes(0).sum := sums(1)
            result.lanes(1).sum := sums(0)
            result.lanes(0).product := products(0)
            result.lanes(1).product := products(1)
            result.valid := a.valid | b.valid
            result
          }, (value: BalancedNestedWideningValue, _: Int) => value,
          native[BalancedNestedWideningValue])
      })
    }
    val message = details(error)
    assert(message.contains("COMPOSITE") || message.contains("EXTERNAL-READ") ||
      message.contains("FIELD"), message)
  }
}
'''


def main() -> None:
    if TEST.exists():
        if TEST.read_text() != SOURCE:
            raise RuntimeError("nested-Vec widening focused test already exists with different bytes")
        print("59i nested-Vec widening focused test already matches")
        return
    TEST.parent.mkdir(parents=True, exist_ok=True)
    TEST.write_text(SOURCE)
    print("59i nested-Vec widening focused test written")


if __name__ == "__main__":
    main()
