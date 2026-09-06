package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

private[internals] final class BalancedWideningInferredOutput(width: HdlInt, count: HdlInt)
    extends Component {
  setDefinitionName("BalancedWideningInferredOutput")
  val values = in(Vec(UInt(width bits), count))
  val result = out(UInt())
  result := values.reduceBalancedTree((a: UInt, b: UInt) => a +^ b)
}

class TypedBalancedReductionWideningPublicationTests extends AnyFunSuite {
  private def generate(defaultWidth: Int = 5, defaultCount: Int = 1): String = {
    val path = TypedBalancedReductionWideningArtifactWriter.candidate(
      Files.createTempDirectory("balanced-widening-"), defaultWidth, defaultCount)
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }

  test("singleton default preserves independent geometry and all native scalar operations") {
    val text = generate()
    assert("parameter\\s+(?:integer\\s+)?WIDTH\\s*=\\s*5\\b".r.findFirstIn(text).nonEmpty, text)
    assert("parameter\\s+(?:integer\\s+)?COUNT\\s*=\\s*1\\b".r.findFirstIn(text).nonEmpty, text)
    assert(text.linesIterator.count(_.trim.startsWith("module BalancedWidening")) == 1, text)
    for (name <- Vector("uSum", "sSum", "uProduct", "sProduct", "uMin", "uMax",
        "sMin", "sMax", "uResize", "sResize", "uSymbolicResize", "sSymbolicResize", "rSum", "rSignedSum")) {
      assert(text.linesIterator.exists(line => line.contains("output") &&
        ("\\b" + name + "\\b").r.findFirstIn(line).nonEmpty), name + "\n" + text)
    }
    assert(text.contains("genvar") && text.contains("begin : tail"), text)
  }

  test("alternate default retains symbolic WIDTH and COUNT instead of its native result witness") {
    val text = generate(8, 5)
    assert("parameter\\s+(?:integer\\s+)?WIDTH\\s*=\\s*8\\b".r.findFirstIn(text).nonEmpty, text)
    assert("parameter\\s+(?:integer\\s+)?COUNT\\s*=\\s*5\\b".r.findFirstIn(text).nonEmpty, text)
    val product = text.linesIterator.find(line => line.contains("output") &&
      "\\buProduct\\b".r.findFirstIn(line).nonEmpty).getOrElse(fail(text))
    assert(product.contains("WIDTH") && product.contains("COUNT"), product)
    val sum = text.linesIterator.find(line => line.contains("output") &&
      "\\buSum\\b".r.findFirstIn(line).nonEmpty).getOrElse(fail(text))
    assert(sum.contains("WIDTH") && sum.contains("COUNT"), sum)
  }

  test("independent repeated widening publication is byte deterministic") {
    assert(generate() == generate())
    assert(generate(8, 5) == generate(8, 5))
  }

  test("the independent native five element product preserves narrow odd-tail bridge inputs") {
    val directory = Files.createTempDirectory("balanced-widening-native-shape-")
    val report = TypedBalancedReductionWideningArtifactWriter.config(directory, "reference.v", nativeReference = true)
      .generateVerilog(new BalancedWideningReference(5, 5))
    assert(report.toplevel.productStages.toVector ==
      Vector(0 -> 10, 0 -> 10, 0 -> 5, 1 -> 20, 1 -> 5, 2 -> 25))
    assert(report.toplevel.uProduct.getWidth == 25)
    assert(report.toplevel.sProduct.getWidth == 25)
  }

  test("native output width and signed leaf kinds remain exact at singleton and count boundaries") {
    for (count <- Vector(1, 2, 3, 5, 8, 9, 16, 17)) {
      val directory = Files.createTempDirectory("balanced-widening-native-width-")
      val report = TypedBalancedReductionWideningArtifactWriter.config(directory, "reference.v", nativeReference = true)
        .generateVerilog(new BalancedWideningReference(5, count))
      val native = report.toplevel
      val sumWidth = 5 + BigInt(count - 1).bitLength
      assert(native.uSum.getWidth == sumWidth)
      assert(native.sSum.getWidth == sumWidth)
      assert(native.uProduct.getWidth == 5 * count)
      assert(native.sProduct.getWidth == 5 * count)
      assert(native.uResize.getWidth == 5)
      assert(native.sResize.getWidth == 5)
      assert(native.uSymbolicResize.getWidth == 6)
      assert(native.sSymbolicResize.getWidth == 6)
      assert(native.registered.rSum.getWidth == sumWidth)
      assert(native.registered.rSignedSum.getWidth == sumWidth)
      assert(native.sSum.isInstanceOf[SInt] && native.sProduct.isInstanceOf[SInt])
      assert(native.uSum.isInstanceOf[UInt] && native.uProduct.isInstanceOf[UInt])
    }
  }

  test("an ordinary inferred output assignment retains the reduction's symbolic terminal width") {
    val directory = Files.createTempDirectory("balanced-widening-inferred-")
    MorphVerilog(TypedBalancedReductionWideningArtifactWriter.config(directory, "inferred.v")) {
      new BalancedWideningInferredOutput(HdlInt.param("WIDTH", 5, 1, 32),
        HdlInt.param("COUNT", 1, 1, 17))
    }
    val text = new String(Files.readAllBytes(directory.resolve("inferred.v")), StandardCharsets.UTF_8)
    val port = text.linesIterator.find(line => line.contains("output") &&
      "\\bresult\\b".r.findFirstIn(line).nonEmpty).getOrElse(fail(text))
    assert(port.contains("WIDTH") && port.contains("COUNT"), port)
  }
}
