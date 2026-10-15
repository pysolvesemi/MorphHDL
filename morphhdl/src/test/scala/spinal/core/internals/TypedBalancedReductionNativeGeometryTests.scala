package spinal.core.internals

import java.nio.file.Files
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

/** Exercise geometry on the actual native +| graph, including exact-node
  * mutations. No generated-RTL mutation coverage is implied by this suite.
  */
class TypedBalancedReductionNativeGeometryTests extends AnyFunSuite {
  private def elaborate(body: => Unit): Unit =
    SpinalConfig(targetDirectory = Files.createTempDirectory("native-geometry-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      body
      val keep = out Bool()
      keep := False
    })

  private def capture(operation: (UInt, UInt) => UInt = (a: UInt, b: UInt) => a +| b)
      : (UnvalidatedBalancedCallback, TypedBalancedReductionScalarGraphReplay.Proof) = {
    val width = HdlInt.param("WIDTH", 5, 1, 9)
    val values = Vec(UInt(width bits), HdlInt.param("COUNT", 2, 2, 2))
    values.foreach(_ := 0)
    val native: ElabBalancedReduction.Native[UInt] = (elements, operation, bridge) =>
      new TraversableOnceAnyPimped[UInt](elements).reduceBalancedTree(operation, bridge)
    val callback = TypedBalancedReductionCapture(values, operation,
      (value: UInt, _: Int) => value, native).rows.head.operator.get
    val evidence = callback.operands.map(value =>
      TypedBalancedReductionValueEvidence.input(value.asInstanceOf[BaseType]))
    val proof = TypedBalancedReductionScalarGraphReplay.certify(callback, evidence)
    (callback, proof)
  }

  private def ranges(callback: UnvalidatedBalancedCallback): Vector[BitVectorRangedAccessFixed] =
    callback.assignments.map(_.source).collect { case value: BitVectorRangedAccessFixed => value }

  private def fills(callback: UnvalidatedBalancedCallback): Vector[BitVectorLiteral] =
    callback.assignments.map(_.source).collect {
      case value: BitVectorLiteral if NativeWidthProvenance.fillOf(value).nonEmpty => value
    }

  private def stale(proof: TypedBalancedReductionScalarGraphReplay.Proof): Unit = {
    val error = intercept[IllegalArgumentException](proof.validateFreshness())
    assert(error.getMessage.contains("STALE"), error.getMessage)
  }

  private def withChangedWidthAtSingleton(source: BitVector)(body: => Unit): Unit = {
    val original = NativeWidthProvenance.widthOf(source).get
    val replacement = ElaborationWidthAuthority.subtract(
      ElabInt.literal((2 * original.default).toInt).expression, original)
    assert(original.default == replacement.default)
    assert(original.minimum == replacement.minimum && original.maximum == replacement.maximum)
    assert(!ElaborationWidthAuthority.equivalent(original, replacement))
    // Exercise a changed exact native object without weakening normal public
    // attachment's provenance-conflict guard. This helper is reserved for the
    // native mux clone construction path in production.
    ParameterizedWidth.retainNativeMuxWidth(source, Some(replacement))
    try {
      val root = original.completedParameterRoots.head
      val default = original.parameters.head.default
      ElaborationDomainContext.withAdmitted(root, Set(default), None) {
        assert(ElaborationWidthAuthority.equivalent(original, replacement))
        body
      }
    } finally ParameterizedWidth.retainNativeMuxWidth(source, Some(original))
  }

  test("native saturation retains its independent source-relative slice and fill domains") {
    elaborate {
      val (callback, proof) = capture()
      val width = ParameterizedWidth.expressionOf(callback.operands.head.asInstanceOf[BaseType]).get
      assert(ElaborationWidthAuthority.equivalent(proof.resultWidth, width))
      val selected = ranges(callback)
      assert(selected.size == 2)
      selected.foreach { access =>
        val geometry = NativeWidthProvenance.rangeOf(access).get
        val sourceWidth = NativeWidthProvenance.widthOf(access.source).get
        assert(geometry.validFor(sourceWidth))
        assert(geometry.high(sourceWidth).default == access.hi)
        assert(geometry.low(sourceWidth).default == access.lo)
      }
      val literal = fills(callback).head
      val fill = NativeWidthProvenance.fillOf(literal).get
      assert(fill.source.nonEmpty)
      assert(NativeWidthProvenance.fillWidthOf(literal).get eq fill.width)
      assert(ElaborationWidthAuthority.equivalent(fill.width, width))
      val other = HdlInt.param("OTHER_WIDTH", 5, 1, 9).bits.expression.get
      assert(width.default == other.default)
      assert(!ElaborationWidthAuthority.equivalent(width, other))
      val widenedOther = ElaborationWidthAuthority.add(other, ElabInt.literal(2).expression)
      val replayWidth = proof.resultWidthFor(widenedOther, widenedOther)
      assert(ElaborationWidthAuthority.equivalent(replayWidth, widenedOther))
      assert(!ElaborationWidthAuthority.equivalent(replayWidth, width))
      proof.validateFreshness()
    }
  }

  test("actual native saturation range mutations invalidate query and scalar freshness") {
    elaborate {
      val (callback, proof) = capture()
      ranges(callback).foreach { access =>
        val original = NativeWidthProvenance.rangeOf(access).get
        val hi = access.hi
        val lo = access.lo
        access.hi = hi + 1
        try { assert(NativeWidthProvenance.rangeOf(access).isEmpty); stale(proof) }
        finally access.hi = hi
        assert(NativeWidthProvenance.rangeOf(access).contains(original))
        proof.validateFreshness()
        access.lo = lo + 1
        try { assert(NativeWidthProvenance.rangeOf(access).isEmpty); stale(proof) }
        finally access.lo = lo
        proof.validateFreshness()
      }
    }
  }

  test("same-default independent source replacement cannot inherit native range geometry") {
    elaborate {
      val (callback, proof) = capture()
      val other = UInt(HdlInt.param("OTHER_WIDTH", 5, 1, 9) bits)
      other := 0
      val replacement = other +^ other
      val access = ranges(callback).head
      val source = access.source
      assert(replacement.getBitsWidth == source.getWidth)
      access.source = replacement
      try { assert(NativeWidthProvenance.rangeOf(access).isEmpty); stale(proof) }
      finally access.source = source
      assert(NativeWidthProvenance.rangeOf(access).nonEmpty)
      proof.validateFreshness()
    }
  }

  test("range freshness rejects a changed full width function even on an equal singleton") {
    elaborate {
      val (callback, proof) = capture()
      val access = ranges(callback).head
      val geometry = NativeWidthProvenance.rangeOf(access).get
      withChangedWidthAtSingleton(access.source.asInstanceOf[BitVector]) {
        assert(NativeWidthProvenance.rangeOf(access).isEmpty)
        stale(proof)
      }
      assert(NativeWidthProvenance.rangeOf(access).contains(geometry))
      proof.validateFreshness()
    }
  }

  test("fill freshness rejects a changed full source function even on an equal singleton") {
    elaborate {
      val (callback, proof) = capture()
      val literal = fills(callback).head
      val geometry = NativeWidthProvenance.fillOf(literal).get
      withChangedWidthAtSingleton(geometry.source.get) {
        assert(NativeWidthProvenance.fillOf(literal).isEmpty)
        stale(proof)
      }
      assert(NativeWidthProvenance.fillOf(literal).contains(geometry))
      proof.validateFreshness()
    }
  }

  test("fresh all-ones construction does not inherit a stale source-relative width record") {
    elaborate {
      val source = UInt(HdlInt.param("WIDTH", 5, 2, 8) bits)
      source := 0
      val target = NativeWidthProvenance.retainRelativeWidth(source, UInt(4 bits), -1)
      val originalWidth = NativeWidthProvenance.widthOf(target).get
      withChangedWidthAtSingleton(source) {
        target.setAll()
        val assigned = target.head.source.asInstanceOf[UInt]
        val literal = assigned.head.source.asInstanceOf[UIntLiteral]
        val geometry = NativeWidthProvenance.fillOf(literal).get
        assert(geometry.source.isEmpty)
        assert(ElabInt.equivalentExpression(geometry.width, originalWidth))
      }
    }
  }

  test("actual all-ones value and width mutations invalidate exact literal provenance") {
    elaborate {
      val (callback, proof) = capture()
      val literal = fills(callback).head
      val original = NativeWidthProvenance.fillOf(literal).get
      val value = literal.value
      literal.value = value ^ 1
      try { assert(NativeWidthProvenance.fillOf(literal).isEmpty); stale(proof) }
      finally literal.value = value
      assert(NativeWidthProvenance.fillOf(literal).contains(original))
      proof.validateFreshness()
      val bits = literal.bitCount
      literal.bitCount = bits + 1
      try { assert(NativeWidthProvenance.fillOf(literal).isEmpty); stale(proof) }
      finally literal.bitCount = bits
      proof.validateFreshness()
    }
  }

  test("independent equal-default width conflicts remain rejected on native saturation operands") {
    elaborate {
      val (callback, proof) = capture()
      val operand = callback.operands.head.asInstanceOf[UInt]
      val before = ParameterizedWidth.expressionOf(operand).get
      val other = HdlInt.param("OTHER_WIDTH", 5, 1, 9)
      assert(before.default == other.bits.expression.get.default)
      val error = intercept[Exception](ParameterizedWidth.attach(operand, other.bits))
      assert(error.getMessage.contains("WIDTH-PROVENANCE-CONFLICT"), error.getMessage)
      assert(ParameterizedWidth.expressionOf(operand).get eq before)
      proof.validateFreshness()
    }
  }

  test("ordinary native zero-width setAll keeps its original concrete behavior") {
    elaborate {
      val zero = UInt(0 bits)
      val result = zero.setAll()
      assert(result eq zero)
      assert(zero.getBitsWidth == 0)
      val assigned = zero.head.source.asInstanceOf[UInt]
      val literal = assigned.head.source.asInstanceOf[UIntLiteral]
      assert(literal.value == 0)
      assert(NativeWidthProvenance.fillOf(literal).isEmpty)
    }
  }

  test("ordinary native saturation preserves a legal witness without certifying an invalid symbolic domain") {
    elaborate {
      val input = UInt(HdlInt.param("WIDTH", 5, 1, 9) bits)
      input := 0
      val result = input.sat(2)
      assert(result.getBitsWidth == 3)
      assert(ParameterizedWidth.expressionOf(result).isEmpty)
    }
  }

  test("ordinary witness acceptance does not certify saturation outside the full symbolic domain") {
    elaborate {
      val error = intercept[IllegalArgumentException](capture((a: UInt, _: UInt) => a.sat(2)))
      assert(error.getMessage.contains("SELECT-DOMAIN"), error.getMessage)
    }
  }
}
