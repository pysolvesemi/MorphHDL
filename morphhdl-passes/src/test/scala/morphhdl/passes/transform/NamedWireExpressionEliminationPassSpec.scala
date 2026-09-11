package morphhdl.passes.transform

import morphhdl.ir.v1._
import morphhdl.passes.api.AliasNameOrigin
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.PassId
import morphhdl.passes.api.WireAliasPassConfiguration
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class NamedWireExpressionEliminationPassSpec
    extends AnyFunSuite
    with Matchers {
  private val moduleId = ModuleId.unsafe("module.named-expression")
  private val scopeId = ScopeId.unsafe("scope.named-expression")
  private val leftId = SymbolId.unsafe("symbol.named-expression-left")
  private val rightId = SymbolId.unsafe("symbol.named-expression-right")
  private val expressionId = SymbolId.unsafe("symbol.named-expression-value")
  private val sinkId = SymbolId.unsafe("symbol.named-expression-sink")
  private val expressionDriverId = DriverId.unsafe("driver.named-expression-value")
  private val sinkDriverId = DriverId.unsafe("driver.named-expression-sink")
  private val packedType = PackedType(
    IntExpr.Literal(BigInt(8)),
    Signedness.Unsigned,
    PackedValueSemantics.BitVector
  )
  private val enabled = WireAliasPassConfiguration.selectedForTesting(
    PassId.NamedWireExpressionElimination
  )

  private def declaration(
      id: SymbolId,
      kind: DeclarationKind,
      origin: NameOrigin,
      observability: Observability = Observability.Unobserved
  ): Declaration =
    Declaration(
      id,
      scopeId,
      kind,
      Some(packedType),
      origin,
      None,
      observability
    )

  private def reference(id: String, target: SymbolId): RtlExpr.Ref =
    RtlExpr.Ref(ReferenceId.unsafe(id), target, scopeId)

  private def design(
      origin: NameOrigin,
      observability: Observability = Observability.Unobserved,
      expressionDriverKind: DriverKind = DriverKind.Continuous,
      sinkDriverKind: DriverKind = DriverKind.Continuous,
      expression: RtlExpr = null
  ): Design = {
    val value = Option(expression).getOrElse(
      RtlExpr.Binary(
        RtlBinaryOperator.BitwiseXor,
        reference("reference.named-expression-left", leftId),
        reference("reference.named-expression-right", rightId)
      )
    )
    Design(
      CanonicalIrSchema.schemaVersion,
      CanonicalIrSchema.stage,
      moduleId,
      Vector(
        Module(
          moduleId,
          "NamedExpressionFixture",
          Vector.empty,
          Vector(Scope(scopeId, None, ScopeKind.Module)),
          Vector.empty,
          Vector(
            declaration(
              leftId,
              DeclarationKind.Port(PortDirection.Input),
              NameOrigin.Explicit("left"),
              Observability(complete = true, externallyVisible = true)
            ),
            declaration(
              rightId,
              DeclarationKind.Port(PortDirection.Input),
              NameOrigin.Explicit("right"),
              Observability(complete = true, externallyVisible = true)
            ),
            declaration(
              expressionId,
              DeclarationKind.InternalCombinational,
              origin,
              observability
            ),
            declaration(
              sinkId,
              DeclarationKind.Port(PortDirection.Output),
              NameOrigin.Explicit("sink"),
              Observability(complete = true, externallyVisible = true)
            )
          ),
          Vector(
            Driver(
              expressionDriverId,
              scopeId,
              expressionId,
              expressionDriverKind,
              DriverCoverage.FullObject,
              value
            ),
            Driver(
              sinkDriverId,
              scopeId,
              sinkId,
              sinkDriverKind,
              DriverCoverage.FullObject,
              reference("reference.named-expression-sink", expressionId)
            )
          )
        )
      )
    )
  }

  private def moduleOf(value: Design): Module = value.modules.head

  test("named expression pass is disabled by the common flag by default") {
    val input = design(NameOrigin.Explicit("calculatedValue"))
    val result = NamedWireExpressionEliminationPass.run(input)

    result.status shouldBe PassExecutionStatus.Skipped
    result.output shouldBe input
    result.eliminationReport.passId shouldBe PassId.NamedWireExpressionElimination
  }

  test("explicit reflected and generated expressions share the proven inlining engine") {
    val origins = Vector[(NameOrigin, AliasNameOrigin)](
      NameOrigin.Explicit("calculatedValue") ->
        AliasNameOrigin.Explicit("calculatedValue"),
      NameOrigin.Reflected("reflectedValue") ->
        AliasNameOrigin.Reflected("reflectedValue"),
      NameOrigin.Generated -> AliasNameOrigin.Generated
    )

    origins.foreach { case (origin, expectedEvidence) =>
      val result = NamedWireExpressionEliminationPass.run(design(origin), enabled)

      withClue(origin) { result.status shouldBe PassExecutionStatus.Changed }
      moduleOf(result.output).declarations.map(_.id) should not contain expressionId
      moduleOf(result.output).drivers.map(_.id) should not contain expressionDriverId
      result.eliminationReport.eliminatedExpressions.map(_.nameOrigin) shouldBe
        Vector(expectedEvidence)
      val sink = moduleOf(result.output).drivers.find(_.id == sinkDriverId).get
      sink.value match {
        case RtlExpr.Resize(value: RtlExpr.Binary, IntExpr.Literal(width), Signedness.Unsigned) =>
          width shouldBe BigInt(8)
          value.referenceOccurrences.map(_.target).toSet shouldBe Set(leftId, rightId)
          value.referenceOccurrences.foreach(_.id.value should include("wa09-inline"))
        case other => fail(s"expected fenced inlined binary expression, observed $other")
      }
    }
  }

  test("classification uses origin metadata rather than generated-looking name text") {
    val explicit = NamedWireExpressionEliminationPass.run(
      design(NameOrigin.Explicit("_zz")),
      enabled
    )
    val generated = NamedWireExpressionEliminationPass.run(
      design(NameOrigin.Generated),
      enabled
    )

    explicit.status shouldBe PassExecutionStatus.Changed
    explicit.eliminationReport.eliminatedExpressions.head.nameOrigin shouldBe
      AliasNameOrigin.Explicit("_zz")
    generated.status shouldBe PassExecutionStatus.Changed
    generated.eliminationReport.eliminatedExpressions.head.nameOrigin shouldBe
      AliasNameOrigin.Generated
  }

  test("actual unnamed stays historical and unknown provenance fails closed") {
    val unnamedInput = design(NameOrigin.Unnamed)
    val unnamed = NamedWireExpressionEliminationPass.run(unnamedInput, enabled)
    unnamed.status shouldBe PassExecutionStatus.Unchanged
    unnamed.output shouldBe unnamedInput.normalized
    unnamed.eliminationReport.isEmpty shouldBe true

    val unknownInput = design(NameOrigin.Unknown)
    val unknown = NamedWireExpressionEliminationPass.run(unknownInput, enabled)
    unknown.status shouldBe PassExecutionStatus.Failed
    unknown.output shouldBe unknownInput
    unknown.hasErrors shouldBe true
  }

  test("observed or procedural named expressions fail closed") {
    val observed = NamedWireExpressionEliminationPass.run(
      design(
        NameOrigin.Explicit("keptValue"),
        observability = Observability(complete = true, keep = true)
      ),
      enabled
    )
    observed.status shouldBe PassExecutionStatus.Unchanged
    observed.eliminationReport.rejected.map(_.reasonCode) should contain(
      NamedWireExpressionSafetyReason.Observability
    )

    val proceduralDriver = NamedWireExpressionEliminationPass.run(
      design(
        NameOrigin.Explicit("proceduralValue"),
        expressionDriverKind = DriverKind.Procedural
      ),
      enabled
    )
    proceduralDriver.eliminationReport.rejected.map(_.reasonCode) should contain(
      NamedWireExpressionSafetyReason.DriverNotContinuous
    )

    val proceduralReceiver = NamedWireExpressionEliminationPass.run(
      design(
        NameOrigin.Explicit("proceduralReceiverValue"),
        sinkDriverKind = DriverKind.Procedural
      ),
      enabled
    )
    proceduralReceiver.eliminationReport.rejected.map(_.reasonCode) should contain(
      NamedWireExpressionSafetyReason.ReceiverProcedural
    )
  }

  test("direct aliases remain owned by the direct alias passes") {
    val input = design(
      NameOrigin.Explicit("directAlias"),
      expression = reference("reference.named-expression-direct", leftId)
    )
    val result = NamedWireExpressionEliminationPass.run(input, enabled)

    result.status shouldBe PassExecutionStatus.Unchanged
    result.eliminationReport.isEmpty shouldBe true
    moduleOf(result.output).declarations.map(_.id) should contain(expressionId)
  }

  test("execution is deterministic and idempotent") {
    val input = design(NameOrigin.Reflected("calculatedValue"))
    val first = NamedWireExpressionEliminationPass.run(input, enabled)
    val repeated = NamedWireExpressionEliminationPass.run(input, enabled)
    val fixedPoint = NamedWireExpressionEliminationPass.run(first.output, enabled)

    first shouldBe repeated
    fixedPoint.status shouldBe PassExecutionStatus.Unchanged
    fixedPoint.output shouldBe first.output
  }
}
