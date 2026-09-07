package morphhdl.passes.pipeline

import morphhdl.ir.v1._
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.PassId
import morphhdl.passes.api.WireAliasPassConfiguration
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class WireAssignmentAllPassPipelineSpec extends AnyFunSuite with Matchers {
  private val moduleId = ModuleId.unsafe("module.all-pass")
  private val scopeId = ScopeId.unsafe("scope.all-pass")
  private val sourceId = SymbolId.unsafe("symbol.all-pass-source")
  private val otherId = SymbolId.unsafe("symbol.all-pass-other")
  private val unnamedId = SymbolId.unsafe("symbol.all-pass-unnamed")
  private val namedId = SymbolId.unsafe("symbol.all-pass-named")
  private val expressionId = SymbolId.unsafe("symbol.all-pass-expression")
  private val sinkId = SymbolId.unsafe("symbol.all-pass-sink")
  private val packedType = PackedType(IntExpr.Literal(BigInt(8)), Signedness.Unsigned, PackedValueSemantics.BitVector)

  private def declaration(id: SymbolId, kind: DeclarationKind, origin: NameOrigin,
      externallyVisible: Boolean = false): Declaration =
    Declaration(id, scopeId, kind, Some(packedType), origin, None,
      Observability(complete = true, externallyVisible = externallyVisible))

  private def reference(id: String, target: SymbolId): RtlExpr.Ref =
    RtlExpr.Ref(ReferenceId.unsafe(id), target, scopeId)

  private def driver(id: String, target: SymbolId, value: RtlExpr): Driver =
    Driver(DriverId.unsafe(id), scopeId, target, DriverKind.Continuous, DriverCoverage.FullObject, value)

  private def design: Design =
    Design(CanonicalIrSchema.schemaVersion, CanonicalIrSchema.stage, moduleId,
      Vector(Module(moduleId, "AllPassGenericFixture", Vector.empty,
        Vector(Scope(scopeId, None, ScopeKind.Module)), Vector.empty,
        Vector(
          declaration(sourceId, DeclarationKind.Port(PortDirection.Input), NameOrigin.Explicit("source"), externallyVisible = true),
          declaration(otherId, DeclarationKind.Port(PortDirection.Input), NameOrigin.Explicit("other"), externallyVisible = true),
          declaration(unnamedId, DeclarationKind.InternalCombinational, NameOrigin.Unnamed),
          declaration(namedId, DeclarationKind.InternalCombinational, NameOrigin.Explicit("namedAlias")),
          declaration(expressionId, DeclarationKind.InternalCombinational, NameOrigin.Unnamed),
          declaration(sinkId, DeclarationKind.Port(PortDirection.Output), NameOrigin.Explicit("sink"), externallyVisible = true)
        ),
        Vector(
          driver("driver.all-pass-unnamed", unnamedId, reference("reference.all-pass-unnamed-source", sourceId)),
          driver("driver.all-pass-named", namedId, reference("reference.all-pass-named-unnamed", unnamedId)),
          driver("driver.all-pass-expression", expressionId,
            RtlExpr.Binary(RtlBinaryOperator.BitwiseXor,
              reference("reference.all-pass-expression-named", namedId),
              reference("reference.all-pass-expression-other", otherId))),
          driver("driver.all-pass-sink", sinkId, reference("reference.all-pass-sink-expression", expressionId))
        ))))

  test("one enabled flag executes every pass in the fixed production order") {
    val result = WireAliasPassPipeline.run(design, WireAliasPassConfiguration(enabled = true))
    result.status shouldBe PassExecutionStatus.Changed
    result.executedPasses shouldBe PassId.allWireAssignmentPasses
    result.eliminationReports.map(_.eliminatedCount) shouldBe Vector(1, 1, 1, 0)
    result.eliminated.size shouldBe 2
    result.eliminatedExpressions.size shouldBe 1
    result.eliminatedExpressions.head.rootOperator shouldBe "binary:bitwise-xor"
    result.simplifiedExpressions shouldBe empty
    val outputModule = result.output.modules.head
    outputModule.declarations.map(_.id).toSet shouldBe Set(sourceId, otherId, sinkId)
    outputModule.drivers.map(_.target) shouldBe Vector(sinkId)
    outputModule.drivers.head.value.referenceOccurrences.map(_.target).toSet shouldBe Set(sourceId, otherId)
    WireAliasPassPipeline.allPassId shouldBe
      "wire-alias-unnamed+wire-alias-named+wire-expression-unnamed+constant-operand-simplification"
  }

  test("historical three-stage proof selection retains its original reports") {
    val result = WireAliasPassPipeline.run(design,
      WireAliasPassConfiguration.selectedForTesting(PassId.historicalWireAssignmentPasses: _*))
    result.status shouldBe PassExecutionStatus.Changed
    result.executedPasses shouldBe PassId.historicalWireAssignmentPasses
    result.eliminationReports.map(_.eliminatedCount) shouldBe Vector(1, 1, 1)
    WireAliasPassPipeline.historicalAllPassId shouldBe
      "wire-alias-unnamed+wire-alias-named+wire-expression-unnamed"
  }

  test("all-pass pipeline reaches an idempotent four-stage fixed point") {
    val first = WireAliasPassPipeline.run(design, WireAliasPassConfiguration(enabled = true))
    val second = WireAliasPassPipeline.run(first.output, WireAliasPassConfiguration(enabled = true))
    second.status shouldBe PassExecutionStatus.Unchanged
    second.output shouldBe first.output
    second.executedPasses shouldBe PassId.allWireAssignmentPasses
    second.eliminationReports.map(_.changedCount) shouldBe Vector(0, 0, 0, 0)
  }

  test("constant simplification operates inside the expression-inlining assignment fence") {
    val input = design.copy(modules = design.modules.map(module => module.copy(drivers = module.drivers.map {
      case value if value.target == expressionId => value.copy(value =
        RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd,
          RtlExpr.Binary(RtlBinaryOperator.GreaterThan,
            reference("reference.predicate.named", namedId), reference("reference.predicate.other", otherId)),
          RtlExpr.Literal(BigInt(1), 1)))
      case value => value
    })))
    val result = WireAliasPassPipeline.run(input, WireAliasPassConfiguration(enabled = true))
    withClue(result.diagnostics.mkString("; ")) { result.isSuccess shouldBe true }
    result.eliminationReports.map(_.eliminatedCount) shouldBe Vector(1, 1, 1, 0)
    result.simplifiedExpressions.size shouldBe 1
    result.output.modules.head.drivers.head.value match {
      case RtlExpr.Resize(RtlExpr.Binary(RtlBinaryOperator.GreaterThan, _, _), size, Signedness.Unsigned) =>
        size shouldBe IntExpr.Literal(BigInt(8))
      case other => fail(s"assignment fence or simplified predicate was lost: $other")
    }
    val again = WireAliasPassPipeline.run(result.output, WireAliasPassConfiguration(enabled = true))
    again.status shouldBe PassExecutionStatus.Unchanged
    again.output shouldBe result.output
  }

  test("a simplification exposing an earlier named alias closes in the same invocation") {
    val base = design.modules.head
    val input = design.copy(modules = Vector(base.copy(
      declarations = base.declarations.filter(d => Set(sourceId, namedId, sinkId).contains(d.id)),
      drivers = Vector(
        driver("driver.exposed-alias", namedId, RtlExpr.Binary(RtlBinaryOperator.ShiftLeft,
          reference("reference.exposed-source", sourceId), RtlExpr.Literal(BigInt(0), 8))),
        driver("driver.exposed-sink", sinkId, reference("reference.exposed-alias", namedId))
      )
    )))
    val first = WireAliasPassPipeline.run(input, WireAliasPassConfiguration(enabled = true))
    withClue(first.diagnostics.mkString("; ")) { first.isSuccess shouldBe true }
    first.executedPasses shouldBe PassId.allWireAssignmentPasses
    first.eliminationReports.map(_.eliminatedCount) shouldBe Vector(0, 1, 0, 0)
    first.eliminationReports.map(_.simplifiedCount) shouldBe Vector(0, 0, 0, 1)
    first.output.modules.head.declarations.map(_.id).toSet shouldBe Set(sourceId, sinkId)
    first.output.modules.head.drivers.head.value.directReference shouldBe Some(sourceId)
    WireAliasPassPipeline.run(input, WireAliasPassConfiguration(enabled = true)) shouldBe first
    val second = WireAliasPassPipeline.run(first.output, WireAliasPassConfiguration(enabled = true))
    second.output shouldBe first.output
    second.status shouldBe PassExecutionStatus.Unchanged
    second.eliminationReports.forall(_.changedCount == 0) shouldBe true
  }

  test("one disabled flag executes no wire-assignment pass") {
    val input = design
    val result = WireAliasPassPipeline.run(input, WireAliasPassConfiguration(enabled = false))
    result.status shouldBe PassExecutionStatus.Skipped
    result.output shouldBe input
    result.stages shouldBe empty
    result.simplifiedExpressions shouldBe empty
  }
}
