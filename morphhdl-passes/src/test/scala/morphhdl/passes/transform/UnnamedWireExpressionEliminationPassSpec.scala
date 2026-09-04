package morphhdl.passes.transform

import morphhdl.ir.v1.CanonicalIrSchema
import morphhdl.ir.v1.Declaration
import morphhdl.ir.v1.DeclarationKind
import morphhdl.ir.v1.Design
import morphhdl.ir.v1.Driver
import morphhdl.ir.v1.DriverCoverage
import morphhdl.ir.v1.DriverId
import morphhdl.ir.v1.DriverKind
import morphhdl.ir.v1.IntExpr
import morphhdl.ir.v1.IntegerParameter
import morphhdl.ir.v1.IntegerParameterDomain
import morphhdl.ir.v1.Module
import morphhdl.ir.v1.ModuleId
import morphhdl.ir.v1.NameOrigin
import morphhdl.ir.v1.Observability
import morphhdl.ir.v1.PackedType
import morphhdl.ir.v1.PackedValueSemantics
import morphhdl.ir.v1.ParameterId
import morphhdl.ir.v1.PortDirection
import morphhdl.ir.v1.ReferenceId
import morphhdl.ir.v1.RtlBinaryOperator
import morphhdl.ir.v1.RtlExpr
import morphhdl.ir.v1.Scope
import morphhdl.ir.v1.ScopeId
import morphhdl.ir.v1.ScopeKind
import morphhdl.ir.v1.Signedness
import morphhdl.ir.v1.SourceLocation
import morphhdl.ir.v1.SymbolId
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.WireAliasPassConfiguration
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class UnnamedWireExpressionEliminationPassSpec
    extends AnyFunSuite
    with Matchers {
  private val moduleId = ModuleId.unsafe("module.expression")
  private val rootScope = ScopeId.unsafe("scope.expression-root")
  private val widthParameter = ParameterId.unsafe("parameter.expression-width")
  private val depthParameter = ParameterId.unsafe("parameter.expression-depth")
  private val leftId = SymbolId.unsafe("symbol.expression-left")
  private val rightId = SymbolId.unsafe("symbol.expression-right")
  private val aliasId = SymbolId.unsafe("symbol.expression-alias")
  private val sinkId = SymbolId.unsafe("symbol.expression-sink")
  private val aliasDriverId = DriverId.unsafe("driver.expression-alias")
  private val sinkDriverId = DriverId.unsafe("driver.expression-sink")
  private val enabled = WireAliasPassConfiguration(enabled = true)

  private val packedType = PackedType(
    IntExpr.ParameterRef(widthParameter),
    Signedness.Unsigned,
    PackedValueSemantics.BitVector
  )

  private def location(path: String, line: Int): SourceLocation =
    SourceLocation(path, line, 1)

  private def reference(id: String, target: SymbolId, path: String): RtlExpr.Ref =
    RtlExpr.Ref(ReferenceId.unsafe(id), target, rootScope, Some(location(path, 20)))

  private def declaration(
      id: SymbolId,
      kind: DeclarationKind,
      origin: NameOrigin,
      path: String,
      externallyVisible: Boolean = false
  ): Declaration = Declaration(
    id,
    rootScope,
    kind,
    Some(packedType),
    origin,
    Some(location(path, 10)),
    Observability(complete = true, externallyVisible = externallyVisible)
  )

  private def baseDesign(
      moduleName: String = "GenericExpressionFixture",
      path: String = "src/GenericExpressionFixture.scala"
  ): Design = {
    val expression = RtlExpr.Binary(
      RtlBinaryOperator.BitwiseOr,
      reference("reference.expression-left", leftId, path),
      reference("reference.expression-right", rightId, path)
    )
    Design(
      CanonicalIrSchema.schemaVersion,
      CanonicalIrSchema.stage,
      moduleId,
      Vector(
        Module(
          moduleId,
          moduleName,
          Vector(
            IntegerParameter(
              depthParameter,
              "DEPTH",
              BigInt(5),
              IntegerParameterDomain(
                BigInt(1),
                BigInt(8),
                (1 to 8).map(BigInt(_)).toVector
              )
            ),
            IntegerParameter(
              widthParameter,
              "WIDTH",
              BigInt(8),
              IntegerParameterDomain(
                BigInt(1),
                BigInt(64),
                (1 to 64).map(BigInt(_)).toVector
              )
            )
          ),
          Vector(Scope(rootScope, None, ScopeKind.Module)),
          Vector.empty,
          Vector(
            declaration(
              leftId,
              DeclarationKind.Port(PortDirection.Input),
              NameOrigin.Explicit("left"),
              path,
              externallyVisible = true
            ),
            declaration(
              rightId,
              DeclarationKind.Port(PortDirection.Input),
              NameOrigin.Explicit("right"),
              path,
              externallyVisible = true
            ),
            declaration(
              aliasId,
              DeclarationKind.InternalCombinational,
              NameOrigin.Unnamed,
              path
            ),
            declaration(
              sinkId,
              DeclarationKind.Port(PortDirection.Output),
              NameOrigin.Explicit("sink"),
              path,
              externallyVisible = true
            )
          ),
          Vector(
            Driver(
              aliasDriverId,
              rootScope,
              aliasId,
              DriverKind.Continuous,
              DriverCoverage.FullObject,
              expression,
              Some(location(path, 20))
            ),
            Driver(
              sinkDriverId,
              rootScope,
              sinkId,
              DriverKind.Continuous,
              DriverCoverage.FullObject,
              reference("reference.expression-sink-alias", aliasId, path),
              Some(location(path, 21))
            )
          )
        )
      )
    )
  }

  private def updateDriver(design: Design, id: DriverId)(f: Driver => Driver): Design =
    design.copy(modules = design.modules.map { module =>
      module.copy(drivers = module.drivers.map(value => if (value.id == id) f(value) else value))
    })

  private def updateModule(design: Design)(f: Module => Module): Design =
    design.copy(modules = design.modules.map(f))

  private def moduleOf(design: Design): Module = design.modules.find(_.id == moduleId).get

  test("unnamed expression elimination is disabled by the master flag by default") {
    val design = baseDesign()
    val result = UnnamedWireExpressionEliminationPass.run(design)
    result.status shouldBe PassExecutionStatus.Skipped
    result.output shouldBe design
  }

  test("continuous unnamed expression is copied to its receiver and removed") {
    val result = UnnamedWireExpressionEliminationPass.run(baseDesign(), enabled)
    val module = moduleOf(result.output)
    result.status shouldBe PassExecutionStatus.Changed
    module.declarations.map(_.id) should not contain aliasId
    module.drivers.map(_.id) should not contain aliasDriverId
    val sink = module.drivers.find(_.id == sinkDriverId).get.value
    sink shouldBe a[RtlExpr.Resize]
    sink.referenceOccurrences.map(_.target) shouldBe Vector(leftId, rightId)
    sink.referenceOccurrences.map(_.id).distinct.size shouldBe 2
    result.eliminationReport.inlinedExpressions.map(_.replacementCount) shouldBe Vector(1)
  }

  test("direct reference remains owned by the preceding alias pass") {
    val direct = updateDriver(baseDesign(), aliasDriverId)(
      _.copy(value = reference("reference.expression-direct", leftId, "src/Direct.scala"))
    )
    val result = UnnamedWireExpressionEliminationPass.run(direct, enabled)
    result.status shouldBe PassExecutionStatus.Unchanged
    moduleOf(result.output).declarations.map(_.id) should contain(aliasId)
    result.eliminationReport.inlinedExpressions shouldBe empty
  }

  test("procedural definition is retained") {
    val procedural = updateDriver(baseDesign(), aliasDriverId)(
      _.copy(kind = DriverKind.Procedural)
    )
    val result = UnnamedWireExpressionEliminationPass.run(procedural, enabled)
    result.status shouldBe PassExecutionStatus.Unchanged
    result.eliminationReport.rejected.map(_.reasonCode) should contain(
      UnnamedWireExpressionSafetyReason.DriverNotContinuous
    )
  }

  test("procedural receiver is retained to preserve always-block scheduling") {
    val procedural = updateDriver(baseDesign(), sinkDriverId)(
      _.copy(kind = DriverKind.Procedural)
    )
    val result = UnnamedWireExpressionEliminationPass.run(procedural, enabled)
    result.status shouldBe PassExecutionStatus.Unchanged
    result.eliminationReport.rejected.map(_.reasonCode) should contain(
      UnnamedWireExpressionSafetyReason.ReceiverNotContinuous
    )
    moduleOf(result.output).declarations.map(_.id) should contain(aliasId)
  }

  test("fanout receives independent expression reference identities") {
    val secondSinkId = SymbolId.unsafe("symbol.expression-sink-two")
    val secondDriverId = DriverId.unsafe("driver.expression-sink-two")
    val design = updateModule(baseDesign()) { module =>
      module.copy(
        declarations = module.declarations :+ declaration(
          secondSinkId,
          DeclarationKind.Port(PortDirection.Output),
          NameOrigin.Explicit("sinkTwo"),
          "src/Fanout.scala",
          externallyVisible = true
        ),
        drivers = module.drivers :+ Driver(
          secondDriverId,
          rootScope,
          secondSinkId,
          DriverKind.Continuous,
          DriverCoverage.FullObject,
          reference("reference.expression-sink-two-alias", aliasId, "src/Fanout.scala")
        )
      )
    }
    val result = UnnamedWireExpressionEliminationPass.run(design, enabled)
    val module = moduleOf(result.output)
    val firstIds = module.drivers.find(_.id == sinkDriverId).get.value.referenceOccurrences.map(_.id)
    val secondIds = module.drivers.find(_.id == secondDriverId).get.value.referenceOccurrences.map(_.id)
    result.status shouldBe PassExecutionStatus.Changed
    firstIds.toSet.intersect(secondIds.toSet) shouldBe empty
    result.eliminationReport.inlinedExpressions.head.replacementCount shouldBe 2
  }

  test("fixed point is idempotent and preserves the complete WIDTH DEPTH domain") {
    val first = UnnamedWireExpressionEliminationPass.run(baseDesign(), enabled)
    val second = UnnamedWireExpressionEliminationPass.run(first.output, enabled)
    val parameters = moduleOf(first.output).parameters.collect {
      case value: IntegerParameter => value.name -> value.domain.admittedValues
    }.toMap
    first.status shouldBe PassExecutionStatus.Changed
    second.status shouldBe PassExecutionStatus.Unchanged
    second.output shouldBe first.output
    parameters("WIDTH").size shouldBe 64
    parameters("DEPTH").size shouldBe 8
    parameters("WIDTH").size * parameters("DEPTH").size shouldBe 512
  }

  test("component name and source path do not affect expression decisions") {
    val first = UnnamedWireExpressionEliminationPass.run(
      baseDesign("FirstUnrelatedBlock", "src/first/Logic.scala"),
      enabled
    )
    val second = UnnamedWireExpressionEliminationPass.run(
      baseDesign("SecondUnrelatedBlock", "elsewhere/Other.scala"),
      enabled
    )
    first.status shouldBe second.status
    first.eliminationReport.inlinedExpressions.map(_.replacementCount) shouldBe
      second.eliminationReport.inlinedExpressions.map(_.replacementCount)
    moduleOf(first.output).declarations.map(_.id) shouldBe
      moduleOf(second.output).declarations.map(_.id)
  }

  test("invalid canonical input fails closed with the original design") {
    val invalid = baseDesign().copy(modules = Vector.empty)
    val result = UnnamedWireExpressionEliminationPass.run(invalid, enabled)
    result.status shouldBe PassExecutionStatus.Failed
    result.output shouldBe invalid
    result.hasErrors shouldBe true
  }
}
