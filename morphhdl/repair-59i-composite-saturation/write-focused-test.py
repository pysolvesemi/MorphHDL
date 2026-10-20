#!/usr/bin/env python3
"""Write a test-only probe for native saturated composite reduction callbacks."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEST = ROOT / "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeSaturationTests.scala"

SOURCE = r'''package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

final case class BalancedSaturatingValue(width: ElabInt) extends Bundle {
  val unsigned = UInt(width bits)
  val signed = SInt(width bits)
  val tag = Bits(width bits)
  val valid = Bool()
}

private object BalancedSaturatingFixture {
  def combine(a: BalancedSaturatingValue, b: BalancedSaturatingValue): BalancedSaturatingValue = {
    val result = cloneOf(a)
    result.unsigned := a.unsigned +| b.unsigned
    result.signed := a.signed +| b.signed
    result.tag := a.tag ^ b.tag
    result.valid := a.valid | b.valid
    result
  }

  def initialize(value: BalancedSaturatingValue): Unit = {
    value.unsigned := 0
    value.signed := 0
    value.tag := 0
    value.valid := False
  }
}

private final class BalancedSaturatingPublic(width: HdlInt, count: HdlInt,
    moduleName: String) extends Component {
  setDefinitionName(moduleName)
  private val typedWidth = ElabInt.fromExpression(width.bits.expression.get)
  val values = in(Vec(BalancedSaturatingValue(typedWidth), count)).setName("values")
  val result = out(BalancedSaturatingValue(typedWidth)).setName("result")
  result := values.reduceBalancedTree(
    (a: BalancedSaturatingValue, b: BalancedSaturatingValue) =>
      BalancedSaturatingFixture.combine(a, b))
}

class TypedBalancedReductionCompositeSaturationTests extends AnyFunSuite {
  private def native[T <: Data]: ElabBalancedReduction.Native[T] =
    (values, operation, bridge) =>
      new TraversableOnceAnyPimped[T](values).reduceBalancedTree(operation, bridge)

  private def details(error: Throwable): String =
    if (error == null) "" else Option(error.getMessage).getOrElse("") + "\n" + details(error.getCause)

  test("native UInt and SInt saturated additions replay across singleton and odd tails") {
    val width = HdlInt.param("WIDTH", 5, 2, 32)
    val count = HdlInt.param("COUNT", 1, 1, 5)
    var paths = Vector.empty[String]
    var widths = Vector.empty[(Int, Int, Int)]
    SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-saturation-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val typedWidth = ElabInt.fromExpression(width.bits.expression.get)
      val values = Vec(BalancedSaturatingValue(typedWidth), count)
      values.vec.foreach(BalancedSaturatingFixture.initialize)
      val certificate = TypedBalancedReductionCompositeReplay.capture(values,
        (a: BalancedSaturatingValue, b: BalancedSaturatingValue) =>
          BalancedSaturatingFixture.combine(a, b),
        (value: BalancedSaturatingValue, _: Int) => value,
        native[BalancedSaturatingValue])
      paths = certificate.captured.result.flattenLocalName.toVector
      widths = (1 to 5).map { active =>
        val value = certificate.replay(values.vec.take(active).toVector)
        (value.unsigned.getBitsWidth, value.signed.getBitsWidth, value.tag.getBitsWidth)
      }.toVector
      certificate.requireFreshness()
    })
    assert(paths.toSet == Set("unsigned", "signed", "tag", "valid"), paths)
    assert(widths.forall(_ == (5, 5, 5)), widths)
  }

  test("parameterized publication retains saturated arithmetic at WIDTH and COUNT overrides") {
    val directory = Files.createTempDirectory("balanced-saturation-public-")
    val file = "BalancedSaturatingPublic.v"
    val config = SpinalConfig(targetDirectory = directory.toString, bitVectorWidthMax = 8192)
    config.netlistFileName = file
    MorphVerilog(config) {
      new BalancedSaturatingPublic(HdlInt.param("WIDTH", 5, 2, 32),
        HdlInt.param("COUNT", 1, 1, 5), "BalancedSaturatingPublic")
    }
    val path = directory.resolve(file)
    assert(Files.isRegularFile(path))
    val rtl = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    assert(rtl.contains("parameter") && rtl.contains("WIDTH") && rtl.contains("COUNT"), rtl)
    assert(rtl.contains("morphhdl_balanced_") && rtl.contains("generate"), rtl)
    assert(rtl.contains("result_unsigned") && rtl.contains("result_signed"), rtl)
  }

  test("saturation callback cannot write an operand or exchange fields") {
    val width = HdlInt.param("WIDTH", 5, 2, 32)
    val error = intercept[Exception] {
      SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-saturation-reject-").toString,
        headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
        val typedWidth = ElabInt.fromExpression(width.bits.expression.get)
        val values = Vec(BalancedSaturatingValue(typedWidth), HdlInt.param("COUNT", 2, 1, 2))
        values.vec.foreach(BalancedSaturatingFixture.initialize)
        TypedBalancedReductionCompositeReplay.capture(values,
          (a: BalancedSaturatingValue, b: BalancedSaturatingValue) => {
            val result = cloneOf(a)
            a.tag := b.tag
            result.unsigned := a.unsigned +| b.unsigned
            result.signed := a.signed +| b.signed
            result.tag := a.tag ^ b.tag
            result.valid := a.valid | b.valid
            result
          }, (value: BalancedSaturatingValue, _: Int) => value,
          native[BalancedSaturatingValue])
      })
    }
    val message = details(error)
    assert(message.contains("WRITE") || message.contains("OPERAND") ||
      message.contains("EXTERNAL") || message.contains("COMPOSITE"), message)
  }
}
'''


def main() -> None:
    if TEST.exists():
        if TEST.read_text() != SOURCE:
            raise RuntimeError("composite saturation focused test exists with different bytes")
        print("59i composite saturation focused test already matches")
        return
    TEST.parent.mkdir(parents=True, exist_ok=True)
    TEST.write_text(SOURCE)
    print("59i composite saturation focused test written")


if __name__ == "__main__":
    main()
