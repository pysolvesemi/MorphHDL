package morphhdl.passes.transform

import morphhdl.ir.v1._
import org.scalatest.funsuite.AnyFunSuite

class AssignmentLowBitTruncationProofSpec extends AnyFunSuite {
  import AssignmentLowBitTruncationProof.{Definition, prove}

  private val scope = ScopeId.unsafe("scope.trunc-test")
  private val a = SymbolId.unsafe("symbol.trunc-test.a")
  private val b = SymbolId.unsafe("symbol.trunc-test.b")
  private val inner = SymbolId.unsafe("symbol.trunc-test.inner")
  private val outer = SymbolId.unsafe("symbol.trunc-test.outer")
  private def ref(symbol: SymbolId, occurrence: String = "one"): RtlExpr =
    RtlExpr.Ref(ReferenceId.unsafe("reference.trunc-test." + symbol.value + "." + occurrence), symbol, scope)
  private def width(value: Int): IntExpr = IntExpr.Literal(BigInt(value))
  private def fence(value: RtlExpr, bits: Int): RtlExpr =
    RtlExpr.Resize(value, width(bits), Signedness.Unsigned)
  private val sum = fence(RtlExpr.Binary(RtlBinaryOperator.Add, ref(a), ref(b)), 18)
  private val effective = IntExpr.ParameterRef(ParameterId.unsafe("parameter.trunc-test.out-width"))

  test("whole assignment absorbs low projection for each required width and the complete domain") {
    for (bits <- Vector(1, 12, 13, 17, 18)) {
      assert(prove(fence(sum, bits), sum, width(bits), 18, bits, bits).nonEmpty)
      assert(prove(RtlExpr.PartSelect(sum, width(0), width(bits)), sum,
        width(bits), 18, bits, bits).nonEmpty)
    }
    assert(prove(RtlExpr.Resize(sum, effective, Signedness.Unsigned), sum,
      effective, 18, 1, 18).nonEmpty)
  }

  test("recursive actual carrier definitions preserve every intermediate packed fence") {
    val innerValue = fence(RtlExpr.Binary(RtlBinaryOperator.Add, ref(a), ref(b)), 8)
    val outerValue = fence(RtlExpr.Binary(RtlBinaryOperator.ShiftRight, ref(inner),
      RtlExpr.Literal(BigInt(1), 3)), 18)
    val definitions = Vector(
      Definition(inner, 8, innerValue, NameOrigin.Unnamed),
      Definition(outer, 18, outerValue, NameOrigin.Generated))
    val expanded = fence(RtlExpr.Binary(RtlBinaryOperator.ShiftRight, fence(innerValue, 8),
      RtlExpr.Literal(BigInt(1), 3)), 18)
    assert(prove(fence(ref(outer), 13), expanded, width(13), 18, 13, 13, definitions).nonEmpty)
    val lostOverflow = fence(RtlExpr.Binary(RtlBinaryOperator.ShiftRight,
      RtlExpr.Binary(RtlBinaryOperator.Add, ref(a), ref(b)),
      RtlExpr.Literal(BigInt(1), 3)), 18)
    assert(prove(fence(ref(outer), 13), lostOverflow, width(13), 18, 13, 13, definitions).isEmpty)
  }

  test("copy occurrence IDs may change but symbol identity and scope may not") {
    val copied = fence(RtlExpr.Binary(RtlBinaryOperator.Add, ref(a, "copy"), ref(b, "copy")), 18)
    assert(prove(fence(sum, 13), copied, width(13), 18, 13, 13).nonEmpty)
    val wrongLeaf = fence(RtlExpr.Binary(RtlBinaryOperator.Add, ref(a), ref(a, "other")), 18)
    assert(prove(fence(sum, 13), wrongLeaf, width(13), 18, 13, 13).isEmpty)
    val foreignScope = RtlExpr.Ref(ReferenceId.unsafe("reference.foreign"), a, ScopeId.unsafe("scope.foreign"))
    val wrongScope = fence(RtlExpr.Binary(RtlBinaryOperator.Add, foreignScope, ref(b)), 18)
    assert(prove(fence(sum, 13), wrongScope, width(13), 18, 13, 13).isEmpty)
  }

  test("high slices, dynamic selects, nested receivers and widening do not inherit root permission") {
    assert(prove(RtlExpr.PartSelect(sum, width(1), width(13)), sum, width(13), 18, 13, 13).isEmpty)
    assert(prove(RtlExpr.BitSelect(sum, ref(a)), sum, width(1), 18, 1, 1).isEmpty)
    val nested = RtlExpr.Binary(RtlBinaryOperator.Add, fence(sum, 13), ref(b))
    assert(prove(nested, sum, width(13), 18, 13, 13).isEmpty)
    assert(prove(fence(sum, 19), sum, width(19), 18, 19, 19).isEmpty)
    assert(prove(RtlExpr.Resize(sum, effective, Signedness.Unsigned), sum,
      effective, 18, 1, 19).isEmpty)
    assert(prove(fence(sum, 12), sum, width(13), 18, 13, 13).isEmpty)
    assert(prove(fence(sum, 13), sum, width(13), 18, 0, 13).isEmpty)
  }

  test("signed boundaries and unproved preservation provenance fail closed") {
    assert(prove(RtlExpr.Resize(sum, width(13), Signedness.Signed), sum,
      width(13), 18, 13, 13).isEmpty)
    val signedInput = RtlExpr.Cast(sum, Signedness.Signed)
    assert(prove(fence(signedInput, 13), signedInput, width(13), 18, 13, 13).isEmpty)
    for (origin <- Vector(NameOrigin.Explicit("userCarrier"), NameOrigin.Reflected("reflected"), NameOrigin.Unknown)) {
      assert(prove(fence(ref(outer), 13), sum, width(13), 18, 13, 13,
        Vector(Definition(outer, 18, sum, origin))).isEmpty)
    }
  }

  test("cycle, duplicated symbol definitions and excessive expansion cannot issue a certificate") {
    val cycle = Vector(Definition(outer, 18, ref(inner), NameOrigin.Generated),
      Definition(inner, 18, ref(outer), NameOrigin.Unnamed))
    assert(prove(fence(ref(outer), 13), sum, width(13), 18, 13, 13, cycle).isEmpty)
    val definition = Definition(outer, 18, sum, NameOrigin.Generated)
    assert(prove(fence(ref(outer), 13), sum, width(13), 18, 13, 13,
      Vector(definition, definition)).isEmpty)
    val oversized = (0 until 150).foldLeft(sum: RtlExpr) { (value, _) =>
      RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, value)
    }
    assert(prove(fence(oversized, 13), oversized, width(13), 18, 13, 13).isEmpty)
  }

  test("only duplicate fences normalize; changed arithmetic or a removed nested slice is rejected") {
    assert(prove(fence(sum, 13), fence(sum, 18), width(13), 18, 13, 13).nonEmpty)
    val subtract = fence(RtlExpr.Binary(RtlBinaryOperator.Subtract, ref(a), ref(b)), 18)
    assert(prove(fence(sum, 13), subtract, width(13), 18, 13, 13).isEmpty)
    val selected = fence(RtlExpr.PartSelect(sum, width(3), width(8)), 18)
    assert(prove(fence(selected, 13), selected, width(13), 18, 13, 13).nonEmpty)
    assert(prove(fence(selected, 13), sum, width(13), 18, 13, 13).isEmpty)
  }
}
