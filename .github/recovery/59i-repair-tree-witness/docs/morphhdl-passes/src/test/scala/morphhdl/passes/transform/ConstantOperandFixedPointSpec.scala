package morphhdl.passes.transform

import morphhdl.ir.v1._
import morphhdl.passes.api.PassExecutionStatus
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class ConstantOperandFixedPointSpec extends AnyFunSuite with Matchers {
  private val moduleId = ModuleId.unsafe("module.local-fixed-point")
  private val scopeId = ScopeId.unsafe("scope.local-fixed-point")
  private val sourceId = SymbolId.unsafe("symbol.z-source")
  private val sinkId = SymbolId.unsafe("symbol.a-sink")
  private val packed = PackedType(IntExpr.Literal(BigInt(1)), Signedness.Unsigned,
    PackedValueSemantics.Boolean)
  private val source = RtlExpr.Ref(ReferenceId.unsafe("reference.source"), sourceId, scopeId)
  private val predicate = RtlExpr.Binary(RtlBinaryOperator.Equal, source, RtlExpr.Literal(BigInt(1), 1))
  private val inverse = RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, predicate)

  private def fixture(expression: RtlExpr): Design =
    Design(CanonicalIrSchema.schemaVersion, CanonicalIrSchema.stage, moduleId,
      Vector(Module(moduleId, "GenericLocalFixedPoint", Vector.empty,
        Vector(Scope(scopeId, None, ScopeKind.Module)), Vector.empty,
        Vector(
          Declaration(sourceId, scopeId, DeclarationKind.Port(PortDirection.Input), Some(packed),
            NameOrigin.Explicit("source"), None, Observability(complete = true, externallyVisible = true)),
          Declaration(sinkId, scopeId, DeclarationKind.Port(PortDirection.Output), Some(packed),
            NameOrigin.Explicit("sink"), None, Observability(complete = true, externallyVisible = true))
        ),
        Vector(Driver(DriverId.unsafe("driver.sink"), scopeId, sinkId,
          DriverKind.Continuous, DriverCoverage.FullObject, expression)))))

  test("XOR-with-ones closes newly exposed double inversion in one invocation") {
    val input = fixture(RtlExpr.Binary(RtlBinaryOperator.BitwiseXor,
      inverse, RtlExpr.Literal(BigInt(1), 1)))
    val first = ConstantOperandSimplificationPass.run(input)
    withClue(first.diagnostics.mkString("; ")) { first.isSuccess shouldBe true }
    first.status shouldBe PassExecutionStatus.Changed
    first.output.modules.head.drivers.head.value shouldBe predicate
    first.rewrites.map(_.rule).toSet shouldBe Set("bitwise-xor-ones", "double-bitwise-negation")
    val second = ConstantOperandSimplificationPass.run(first.output)
    second.output shouldBe first.output
    second.status shouldBe PassExecutionStatus.Unchanged
    second.rewrites shouldBe empty
    ConstantOperandSimplificationPass.run(input) shouldBe first
  }

  test("RHS simplification preserves input declaration order and all surviving metadata") {
    val input = fixture(RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd,
      predicate, RtlExpr.Literal(BigInt(0), 1)))
    val result = ConstantOperandSimplificationPass.run(input)
    result.isSuccess shouldBe true
    result.status shouldBe PassExecutionStatus.Changed
    result.output.modules.head.declarations shouldBe input.modules.head.declarations
    result.output.modules.head.declarations.map(_.id) shouldBe Vector(sourceId, sinkId)
    result.output.modules.head.copy(drivers = input.modules.head.drivers) shouldBe input.modules.head
  }
}
