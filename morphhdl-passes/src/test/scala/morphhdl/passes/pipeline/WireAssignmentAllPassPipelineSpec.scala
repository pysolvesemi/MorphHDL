package morphhdl.passes.pipeline

import morphhdl.ir.v1.CanonicalIrSchema
import morphhdl.ir.v1.Declaration
import morphhdl.ir.v1.DeclarationKind
import morphhdl.ir.v1.Design
import morphhdl.ir.v1.Driver
import morphhdl.ir.v1.DriverCoverage
import morphhdl.ir.v1.DriverId
import morphhdl.ir.v1.DriverKind
import morphhdl.ir.v1.IntExpr
import morphhdl.ir.v1.Module
import morphhdl.ir.v1.ModuleId
import morphhdl.ir.v1.NameOrigin
import morphhdl.ir.v1.Observability
import morphhdl.ir.v1.PackedType
import morphhdl.ir.v1.PackedValueSemantics
import morphhdl.ir.v1.PortDirection
import morphhdl.ir.v1.ReferenceId
import morphhdl.ir.v1.RtlBinaryOperator
import morphhdl.ir.v1.RtlExpr
import morphhdl.ir.v1.Scope
import morphhdl.ir.v1.ScopeId
import morphhdl.ir.v1.ScopeKind
import morphhdl.ir.v1.Signedness
import morphhdl.ir.v1.SymbolId
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.PassId
import morphhdl.passes.api.WireAliasPassConfiguration
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class WireAssignmentAllPassPipelineSpec
    extends AnyFunSuite
    with Matchers {
  private val moduleId = ModuleId.unsafe("module.all-pass")
  private val scopeId = ScopeId.unsafe("scope.all-pass")
  private val sourceId = SymbolId.unsafe("symbol.all-pass-source")
  private val otherId = SymbolId.unsafe("symbol.all-pass-other")
  private val unnamedId = SymbolId.unsafe("symbol.all-pass-unnamed")
  private val namedId = SymbolId.unsafe("symbol.all-pass-named")
  private val expressionId = SymbolId.unsafe("symbol.all-pass-expression")
  private val sinkId = SymbolId.unsafe("symbol.all-pass-sink")

  private val packedType = PackedType(
    width = IntExpr.Literal(BigInt(8)),
    signedness = Signedness.Unsigned,
    valueSemantics = PackedValueSemantics.BitVector
  )

  private def declaration(
      id: SymbolId,
      kind: DeclarationKind,
      origin: NameOrigin,
      externallyVisible: Boolean = false
  ): Declaration =
    Declaration(
      id = id,
      owner = scopeId,
      kind = kind,
      packedType = Some(packedType),
      nameOrigin = origin,
      sourceLocation = None,
      observability = Observability(
        complete = true,
        externallyVisible = externallyVisible
      )
    )

  private def reference(id: String, target: SymbolId): RtlExpr.Ref =
    RtlExpr.Ref(
      id = ReferenceId.unsafe(id),
      target = target,
      owner = scopeId
    )

  private def driver(
      id: String,
      target: SymbolId,
      value: RtlExpr
  ): Driver =
    Driver(
      id = DriverId.unsafe(id),
      owner = scopeId,
      target = target,
      kind = DriverKind.Continuous,
      coverage = DriverCoverage.FullObject,
      value = value
    )

  private def design: Design =
    Design(
      version = CanonicalIrSchema.schemaVersion,
      stage = CanonicalIrSchema.stage,
      top = moduleId,
      modules = Vector(
        Module(
          id = moduleId,
          logicalName = "AllPassGenericFixture",
          parameters = Vector.empty,
          scopes = Vector(
            Scope(
              id = scopeId,
              parent = None,
              kind = ScopeKind.Module
            )
          ),
          generateIndices = Vector.empty,
          declarations = Vector(
            declaration(
              sourceId,
              DeclarationKind.Port(PortDirection.Input),
              NameOrigin.Explicit("source"),
              externallyVisible = true
            ),
            declaration(
              otherId,
              DeclarationKind.Port(PortDirection.Input),
              NameOrigin.Explicit("other"),
              externallyVisible = true
            ),
            declaration(
              unnamedId,
              DeclarationKind.InternalCombinational,
              NameOrigin.Unnamed
            ),
            declaration(
              namedId,
              DeclarationKind.InternalCombinational,
              NameOrigin.Explicit("namedAlias")
            ),
            declaration(
              expressionId,
              DeclarationKind.InternalCombinational,
              NameOrigin.Unnamed
            ),
            declaration(
              sinkId,
              DeclarationKind.Port(PortDirection.Output),
              NameOrigin.Explicit("sink"),
              externallyVisible = true
            )
          ),
          drivers = Vector(
            driver(
              "driver.all-pass-unnamed",
              unnamedId,
              reference("reference.all-pass-unnamed-source", sourceId)
            ),
            driver(
              "driver.all-pass-named",
              namedId,
              reference("reference.all-pass-named-unnamed", unnamedId)
            ),
            driver(
              "driver.all-pass-expression",
              expressionId,
              RtlExpr.Binary(
                RtlBinaryOperator.BitwiseXor,
                reference("reference.all-pass-expression-named", namedId),
                reference("reference.all-pass-expression-other", otherId)
              )
            ),
            driver(
              "driver.all-pass-sink",
              sinkId,
              reference("reference.all-pass-sink-expression", expressionId)
            )
          )
        )
      )
    )

  test("one enabled flag executes every pass in the fixed production order") {
    val result = WireAliasPassPipeline.run(
      design,
      WireAliasPassConfiguration(enabled = true)
    )

    result.status shouldBe PassExecutionStatus.Changed
    result.executedPasses shouldBe PassId.allWireAssignmentPasses
    result.eliminationReports.map(_.eliminatedCount) shouldBe Vector(1, 1, 1)
    result.eliminated.size shouldBe 2
    result.eliminatedExpressions.size shouldBe 1
    result.eliminatedExpressions.head.rootOperator shouldBe
      "binary:bitwise-xor"

    val outputModule = result.output.modules.head
    outputModule.declarations.map(_.id).toSet shouldBe
      Set(sourceId, otherId, sinkId)
    outputModule.drivers.map(_.target) shouldBe Vector(sinkId)
    outputModule.drivers.head.value.referenceOccurrences.map(_.target).toSet shouldBe
      Set(sourceId, otherId)
    WireAliasPassPipeline.allPassId shouldBe
      "wire-alias-unnamed+wire-alias-named+wire-expression-unnamed"
  }

  test("all-pass pipeline reaches an idempotent three-stage fixed point") {
    val first = WireAliasPassPipeline.run(
      design,
      WireAliasPassConfiguration(enabled = true)
    )
    val second = WireAliasPassPipeline.run(
      first.output,
      WireAliasPassConfiguration(enabled = true)
    )

    second.status shouldBe PassExecutionStatus.Unchanged
    second.output shouldBe first.output
    second.executedPasses shouldBe PassId.allWireAssignmentPasses
    second.eliminationReports.map(_.eliminatedCount) shouldBe Vector(0, 0, 0)
  }

  test("one disabled flag executes no wire-assignment pass") {
    val input = design
    val result = WireAliasPassPipeline.run(
      input,
      WireAliasPassConfiguration(enabled = false)
    )

    result.status shouldBe PassExecutionStatus.Skipped
    result.output shouldBe input
    result.stages shouldBe empty
  }
}
