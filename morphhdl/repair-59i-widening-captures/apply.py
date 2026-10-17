#!/usr/bin/env python3
"""Apply the bounded Increment 59i shape-changing capture composition slice."""
from __future__ import annotations

import hashlib
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OPERATOR = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionOperatorReplay.scala"
LEAF = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeLeafReplay.scala"
COMPOSITE = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala"
TEST = ROOT / "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeWideningCaptureTests.scala"

EXPECTED_BLOBS = {
    OPERATOR: "1a7d393f7a75e7e774ed6ac81dad06df8acccdac",
    LEAF: "8e1b38ec614d44ca39de9dda0737375d7d8f2c35",
    COMPOSITE: "637b94c85aa2843986e594ecb18d1fcf9a5f0165",
}


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def git_blob(data: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def replace_once(text: str, before: str, after: str, label: str) -> str:
    require(text.count(before) == 1, label + " anchor count changed: " + str(text.count(before)))
    return text.replace(before, after, 1)


def load_exact(path: Path) -> str:
    require(path.is_file() and not path.is_symlink(), "missing regular source: " + str(path))
    raw = path.read_bytes()
    require(git_blob(raw) == EXPECTED_BLOBS[path],
            "source predecessor changed: " + path.relative_to(ROOT).as_posix() +
            " expected=" + EXPECTED_BLOBS[path] + " found=" + git_blob(raw))
    return raw.decode()


def patch_operator() -> None:
    text = load_exact(OPERATOR)
    text = replace_once(
        text,
        '''  def certify(callback: UnvalidatedBalancedCallback, inputEvidence: Vector[Evidence]): Proof = {
    val operands = scalarOperands(callback)
''',
        '''  def certify(callback: UnvalidatedBalancedCallback, inputEvidence: Vector[Evidence]): Proof =
    certify(callback, inputEvidence, Vector.empty)

  /** Shape-changing composite leaves may additionally read exact, immutable
    * runtime capture identities. Capture widths stay fixed while the two pair
    * widths are substituted at every balanced stage.
    */
  def certify(callback: UnvalidatedBalancedCallback, inputEvidence: Vector[Evidence],
      captureEvidence: Vector[Evidence]): Proof = {
    val operands = scalarOperands(callback)
''',
        "capture-aware operator entry point",
    )
    text = replace_once(
        text,
        '''    if (operands(0) eq operands(1))
      fail("REPLAY-BODY-OPERANDS", "a reduction pair must retain two distinct operand identities")

    val declarations = callback.declarations
''',
        '''    if (operands(0) eq operands(1))
      fail("REPLAY-BODY-OPERANDS", "a reduction pair must retain two distinct operand identities")
    if (captureEvidence == null || captureEvidence.exists(_ == null))
      fail("REPLAY-CAPTURE-EVIDENCE", "runtime capture proof requires complete native input certificates")
    val captureInputs = new IdentityHashMap[BaseType, Int]()
    captureEvidence.zipWithIndex.foreach { case (evidence, index) =>
      evidence.requireValue(evidence.value)
      val captureKind = evidence.kind
      if ((evidence.owner ne owner) ||
          !((captureKind eq TypeBool) || (captureKind eq TypeBits) ||
            (captureKind eq TypeUInt) || (captureKind eq TypeSInt)))
        fail("REPLAY-CAPTURE-SHAPE", "runtime capture must retain its exact owner and native scalar type")
      if (operands.exists(_ eq evidence.value))
        fail("REPLAY-CAPTURE-ALIAS", "runtime capture cannot alias either reduction operand")
      if (!captureInputs.containsKey(evidence.value)) captureInputs.put(evidence.value, index)
    }

    val declarations = callback.declarations
''',
        "capture evidence validation",
    )
    text = replace_once(
        text,
        '''    val guards = ArrayBuffer.empty[() => Unit]
    inputEvidence.foreach { evidence => guards += (() => evidence.requireFreshness()) }

    val firstWidth = inputEvidence(0).width
''',
        '''    val guards = ArrayBuffer.empty[() => Unit]
    inputEvidence.foreach { evidence => guards += (() => evidence.requireFreshness()) }
    captureEvidence.foreach { evidence => guards += (() => evidence.requireFreshness()) }

    val firstWidth = inputEvidence(0).width
''',
        "capture freshness guards",
    )
    text = replace_once(
        text,
        '''        case leaf: BaseType if operands.exists(_ eq leaf) =>
          val index = operands.indexWhere(_ eq leaf)
          Node(kind, ("input", index), Set(index),
            (a, b) => if (index == 0) a else b,
            (a, b, _, _) => if (index == 0) a else b)
        case leaf: BaseType =>
''',
        '''        case leaf: BaseType if operands.exists(_ eq leaf) =>
          val index = operands.indexWhere(_ eq leaf)
          Node(kind, ("input", index), Set(index),
            (a, b) => if (index == 0) a else b,
            (a, b, _, _) => if (index == 0) a else b)
        case leaf: BaseType if captureInputs.containsKey(leaf) =>
          val index = captureInputs.get(leaf)
          val evidence = captureEvidence(index)
          Node(evidence.kind, ("capture", index, leaf.getClass, evidence.width.verilog), Set.empty,
            (_, _) => evidence.width,
            (_, _, _, _) => { evidence.requireValue(leaf); leaf })
        case leaf: BaseType =>
''',
        "captured scalar graph root",
    )
    text = replace_once(
        text,
        '''      case value: BaseType if !operands.exists(_ eq value) =>
''',
        '''      case value: BaseType if !operands.exists(_ eq value) && !captureInputs.containsKey(value) =>
''',
        "capture-aware root dereference",
    )
    OPERATOR.write_text(text)


def patch_leaf() -> None:
    text = load_exact(LEAF)
    text = replace_once(
        text,
        '''/** A shape-changing composite callback is admitted only when every output
  * leaf is an independent closed scalar graph of the corresponding two input
  * leaves. Scalar replay remains the sole arithmetic and width authority.
  * Shared locals, cross-field reads and hardware captures are rejected rather
  * than being reconstructed as an aggregate-specific algorithm.
  */
''',
        '''/** A shape-changing composite callback is admitted only when every output
  * leaf is an independent closed scalar graph of the corresponding two input
  * leaves. Scalar replay remains the sole arithmetic and width authority.
  * Exact read-only runtime captures may participate as fixed graph roots;
  * shared locals and cross-field reads remain rejected rather than being
  * reconstructed as an aggregate-specific algorithm.
  */
''',
        "capture-aware leaf proof documentation",
    )
    text = replace_once(
        text,
        '''  def certify(callback: UnvalidatedBalancedCallback,
      left: Vector[Evidence], right: Vector[Evidence]): Proof = {
    if (callback == null || left == null || right == null ||
''',
        '''  def certify(callback: UnvalidatedBalancedCallback,
      left: Vector[Evidence], right: Vector[Evidence],
      captures: Vector[Evidence] = Vector.empty): Proof = {
    if (callback == null || left == null || right == null || captures == null ||
''',
        "capture-aware leaf proof entry",
    )
    text = replace_once(
        text,
        '''      TypedBalancedReductionOperatorReplay.certify(partition, Vector(left(index), right(index)))
''',
        '''      TypedBalancedReductionOperatorReplay.certify(
        partition, Vector(left(index), right(index)), captures)
''',
        "capture-aware scalar partition proof",
    )
    LEAF.write_text(text)


def patch_composite() -> None:
    text = load_exact(COMPOSITE)
    text = replace_once(
        text,
        '''    if (shapeChanging) {
      if (schema.nonEmpty)
        fail("WIDENING-CAPTURE", "shape-changing composite leaves require a separate capture-composition proof")
      val leafProof = TypedBalancedReductionCompositeLeafReplay.certify(
        callback, inputs(0).evidence, inputs(1).evidence)
''',
        '''    if (shapeChanging) {
      val leafProof = TypedBalancedReductionCompositeLeafReplay.certify(
        callback, inputs(0).evidence, inputs(1).evidence, capturedEvidence)
''',
        "shape-changing capture proof handoff",
    )
    COMPOSITE.write_text(text)


TEST_SOURCE = r'''package spinal.core.internals

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
    ElabInt.fromExpression(ParameterizedWidth.expressionOf(value)
      .getOrElse(ElabInt.literal(value.getBitsWidth).expression))

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
  val reduced = values.reduceBalancedTree(
    (a: BalancedCapturedWideningValue, b: BalancedCapturedWideningValue) =>
      BalancedCapturedWideningFixture.combine(a, b, bias, factor))
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
      val callback = (a: BalancedCapturedWideningValue, b: BalancedCapturedWideningValue) =>
        BalancedCapturedWideningFixture.combine(a, b, bias, factor)
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
        math.max(math.max(left, 3) + 1, right) + 1
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
      headerWithDate = false, headerWithRepoHash = false, bitVectorWidthMax = 8192)
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

  test("capture permission does not authorize cross-field widening") {
    val width = HdlInt.param("WIDTH", 5, 1, 16)
    val error = intercept[Exception] {
      SpinalConfig(targetDirectory = Files.createTempDirectory("captured-cross-field-").toString,
        headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
        val initial = ElabInt.fromExpression(width.bits.expression.get)
        val values = Vec(BalancedCapturedWideningPair(initial), HdlInt.param("COUNT", 2, 1, 2))
        values.vec.foreach { value => value.left := 0; value.right := 0 }
        val bias = in UInt(3 bits)
        val callback = (a: BalancedCapturedWideningPair, b: BalancedCapturedWideningPair) => {
          val left = (a.right +^ bias) +^ b.left
          val right = (a.right +^ bias) +^ b.right
          val result = BalancedCapturedWideningPair(BalancedCapturedWideningFixture.typedWidth(left))
          result.left := left
          result.right := right
          result
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
'''


def write_test() -> None:
    require(not TEST.exists(), "focused widening-capture test already exists")
    TEST.parent.mkdir(parents=True, exist_ok=True)
    TEST.write_text(TEST_SOURCE)


def main() -> None:
    patch_operator()
    patch_leaf()
    patch_composite()
    write_test()
    subprocess.run(["git", "diff", "--check"], cwd=ROOT, check=True)
    print("59i shape-changing capture composition applied", flush=True)


if __name__ == "__main__":
    main()
