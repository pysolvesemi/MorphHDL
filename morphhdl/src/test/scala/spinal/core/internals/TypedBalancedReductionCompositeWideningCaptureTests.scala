package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

final case class BalancedCapturedWideningValue(unsignedWidth: ElabInt, signedWidth: ElabInt)
    extends Bundle {
  val unsigned = UInt(unsignedWidth bits)
  val signedProduct = SInt(signedWidth bits)
}

final case class BalancedCapturedWideningPair(width: ElabInt) extends Bundle {
  val left = UInt(width bits)
  val right = UInt(width bits)
}

private object BalancedCapturedWideningFixture {
  def typedWidth(value: BaseType): ElabInt =
    ElabInt.widthOf(value)

  def combine(a: BalancedCapturedWideningValue, b: BalancedCapturedWideningValue,
      bias: UInt, factor: SInt): BalancedCapturedWideningValue = {
    // Keep each changing leaf independently separable by pair side. The capture
    // is a fixed read-only graph root, not a reconstructed elaboration value.
    val unsigned = (a.unsigned +^ bias) +^ b.unsigned
    val signedProduct = (a.signedProduct * factor) * b.signedProduct
    val result = BalancedCapturedWideningValue(typedWidth(unsigned), typedWidth(signedProduct))
    result.unsigned := unsigned
    result.signedProduct := signedProduct
    result
  }

  def initialize(value: BalancedCapturedWideningValue): Unit = {
    value.unsigned := 0
    value.signedProduct := 0
  }
}

private final class BalancedCapturedWideningPublic(width: HdlInt, biasWidth: HdlInt,
    factorWidth: HdlInt, count: HdlInt, moduleName: String) extends Component {
  setDefinitionName(moduleName)
  private val initial = ElabInt.fromExpression(width.bits.expression.get)
  val values = in(Vec(BalancedCapturedWideningValue(initial, initial), count)).setName("values")
  val bias = in(UInt(biasWidth bits)).setName("bias")
  val factor = in(SInt(factorWidth bits)).setName("factor")
  val reduced = {
    val capturedBias = bias
    val capturedFactor = factor
    values.reduceBalancedTree(
      (a: BalancedCapturedWideningValue, b: BalancedCapturedWideningValue) =>
        BalancedCapturedWideningFixture.combine(a, b, capturedBias, capturedFactor))
  }
  val result = out(cloneOf(reduced)).setName("result")
  result := reduced
}

class TypedBalancedReductionCompositeWideningCaptureTests extends AnyFunSuite {
  private def native[T <: Data]: ElabBalancedReduction.Native[T] =
    (values, operation, bridge) =>
      new TraversableOnceAnyPimped[T](values).reduceBalancedTree(operation, bridge)

  private def details(error: Throwable): String =
    Iterator.iterate(error)(_.getCause).takeWhile(_ != null)
      .map(value => Option(value.getMessage).getOrElse("")).mkString("\n")

  private def reduceWidths(count: Int, initial: Int)(combine: (Int, Int) => Int): Int = {
    var row = Vector.fill(count)(initial)
    while (row.size > 1) {
      row = row.grouped(2).map { pair =>
        if (pair.size == 2) combine(pair(0), pair(1)) else pair(0)
      }.toVector
    }
    row.head
  }

  test("shape-changing leaves retain exact runtime captures through every admitted count") {
    val width = HdlInt.param("WIDTH", 5, 1, 16)
    val count = HdlInt.param("COUNT", 1, 1, 5)
    var replayed = Vector.empty[(Int, Int)]
    var substituted = Vector.empty[BigInt]
    SpinalConfig(targetDirectory = Files.createTempDirectory("captured-widening-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val initial = ElabInt.fromExpression(width.bits.expression.get)
      val values = Vec(BalancedCapturedWideningValue(initial, initial), count)
      values.vec.foreach(BalancedCapturedWideningFixture.initialize)
      val bias = in UInt(3 bits)
      val factor = in SInt(2 bits)
      val callback = {
        val capturedBias = bias
        val capturedFactor = factor
        (a: BalancedCapturedWideningValue, b: BalancedCapturedWideningValue) =>
          BalancedCapturedWideningFixture.combine(a, b, capturedBias, capturedFactor)
      }
      val schema = TypedBalancedReductionCertifiedCallbackPolicy.requireSupportedCompositeOperator(callback)
      assert(schema.hardwareInputs.size == 2)
      assert(schema.hardwareInputs.exists(_ eq bias) && schema.hardwareInputs.exists(_ eq factor))
      val certificate = TypedBalancedReductionCompositeReplay.capture(values, callback,
        (value: BalancedCapturedWideningValue, _: Int) => value,
        native[BalancedCapturedWideningValue], Some(schema))
      assert(certificate.hasWidening)
      replayed = (1 to 5).map { size =>
        val result = certificate.replay(values.vec.take(size).toVector)
        result.unsigned.getBitsWidth -> result.signedProduct.getBitsWidth
      }.toVector
      substituted = certificate.stages.head.operators.head.resultWidthsFor(
        Vector(ElabInt.literal(4).expression, ElabInt.literal(6).expression),
        Vector(ElabInt.literal(8).expression, ElabInt.literal(10).expression)).map(_.default)
      certificate.requireFreshness()
    })
    for (size <- 1 to 5) {
      val unsigned = reduceWidths(size, 5) { (left, right) =>
        scala.math.max(scala.math.max(left, 3) + 1, right) + 1
      }
      val signed = reduceWidths(size, 5) { (left, right) => left + 2 + right }
      assert(replayed(size - 1) == (unsigned, signed),
        s"size=$size expected=${unsigned -> signed} found=${replayed(size - 1)}")
    }
    assert(substituted == Vector(BigInt(9), BigInt(18)), substituted)
  }

  test("public parameterized RTL keeps capture ports and symbolic widening roots") {
    val directory = Files.createTempDirectory("captured-widening-public-")
    val file = "BalancedCapturedWideningPublic.v"
    val config = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, bitVectorWidthMax = 8192)
    config.netlistFileName = file
    MorphVerilog(config) {
      new BalancedCapturedWideningPublic(
        HdlInt.param("WIDTH", 5, 1, 16), HdlInt.param("BIAS_WIDTH", 3, 1, 8),
        HdlInt.param("FACTOR_WIDTH", 2, 1, 8), HdlInt.param("COUNT", 1, 1, 5),
        "BalancedCapturedWideningPublic")
    }
    val path = directory.resolve(file)
    assert(Files.isRegularFile(path))
    val rtl = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    for (token <- Vector("WIDTH", "BIAS_WIDTH", "FACTOR_WIDTH", "COUNT", "bias", "factor",
        "result_unsigned", "result_signedProduct", "morphhdl_balanced_"))
      assert(rtl.contains(token), token + " missing\n" + rtl)
  }

  test("substituted Bundle clones retain symbolic roots without rewriting their source") {
    val width = HdlInt.param("WIDTH", 5, 1, 16)
    val otherWidth = HdlInt.param("OTHER_WIDTH", 5, 1, 16)
    SpinalConfig(targetDirectory = Files.createTempDirectory("captured-widening-clones-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val original = ElabInt.fromExpression(width.bits.expression.get)
      val other = ElabInt.fromExpression(otherWidth.bits.expression.get)
      val source = BalancedCapturedWideningValue(original, original)
      BalancedCapturedWideningFixture.initialize(source)
      val widths = Vector(other.expression, (original + ElabInt.literal(3)).expression)
      val shaped = TypedBalancedReductionCompositeReplay.cloneShape(source, widths)
        .asInstanceOf[BalancedCapturedWideningValue]
      BalancedCapturedWideningFixture.initialize(shaped)
      val nativeClone = cloneOf(shaped)
      nativeClone := shaped
      val externalClone = ParameterizedWidth.cloneOf(nativeClone)
      externalClone := nativeClone
      val factory = HardType(externalClone)
      val factoryClone = factory()
      factoryClone := externalClone
      for (value <- Vector(shaped, nativeClone, externalClone, factoryClone)) {
        assert(value.flatten.size == widths.size)
        value.flatten.toVector.zip(widths).foreach { case (leaf, expected) =>
          assert(leaf.component eq source.component)
          assert(ParameterizedWidth.expressionOf(leaf)
            .exists(ElaborationWidthAuthority.equivalent(_, expected)))
          assert(BigInt(leaf.getBitsWidth) == expected.default)
        }
      }
      source.flatten.foreach { leaf =>
        assert(ParameterizedWidth.expressionOf(leaf)
          .exists(ElaborationWidthAuthority.equivalent(_, original.expression)))
      }
      // WIDTH and OTHER_WIDTH deliberately have equal default witnesses.
      // Repeated native cloning must not erase their distinct symbolic roots.
      assert(!ElaborationWidthAuthority.equivalent(original.expression, other.expression))
      val conflict = intercept[Exception] {
        ParameterizedWidth.attach(source.unsigned, other.bits)
      }
      assert(details(conflict).contains("WIDTH-PROVENANCE-CONFLICT"), details(conflict))
    })
  }

  test("capture permission does not authorize cross-field widening") {
    val width = HdlInt.param("WIDTH", 5, 1, 16)
    val error = intercept[Exception] {
      SpinalConfig(targetDirectory = Files.createTempDirectory("captured-cross-field-").toString,
        headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
        val initial = ElabInt.fromExpression(width.bits.expression.get)
        val values = Vec(BalancedCapturedWideningPair(initial), HdlInt.param("COUNT", 2, 1, 2))
        values.vec.foreach { value => value.left := 0; value.right := 0 }
        val bias = in UInt(3 bits)
        val callback = {
          val capturedBias = bias
          (a: BalancedCapturedWideningPair, b: BalancedCapturedWideningPair) => {
            val left = (a.right +^ capturedBias) +^ b.left
            val right = (a.right +^ capturedBias) +^ b.right
            val result = BalancedCapturedWideningPair(BalancedCapturedWideningFixture.typedWidth(left))
            result.left := left
            result.right := right
            result
          }
        }
        val schema = TypedBalancedReductionCertifiedCallbackPolicy.requireSupportedCompositeOperator(callback)
        TypedBalancedReductionCompositeReplay.capture(values, callback,
          (value: BalancedCapturedWideningPair, _: Int) => value,
          native[BalancedCapturedWideningPair], Some(schema))
      })
    }
    val message = details(error)
    assert(message.contains("REPLAY-EXTERNAL-READ") || message.contains("COMPOSITE-WIDENING") ||
      message.contains("CROSS-FIELD"), message)
  }
}
