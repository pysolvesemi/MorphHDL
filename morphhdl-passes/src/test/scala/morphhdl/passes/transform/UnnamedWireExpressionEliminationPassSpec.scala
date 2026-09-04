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
import morphhdl.ir.v1.RtlUnaryOperator
import morphhdl.ir.v1.Scope
import morphhdl.ir.v1.ScopeId
import morphhdl.ir.v1.ScopeKind
import morphhdl.ir.v1.Signedness
import morphhdl.ir.v1.SourceLocation
import morphhdl.ir.v1.SymbolId
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.PassId
import morphhdl.passes.api.WireAliasPassConfiguration
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class UnnamedWireExpressionEliminationPassSpec
    extends AnyFunSuite
    with Matchers {
  private val moduleId = ModuleId.unsafe("module.expression")
  private val rootScopeId = ScopeId.unsafe("scope.expression-root")
  private val widthParameterId = ParameterId.unsafe("parameter.expression-width")
  private val sourceId = SymbolId.unsafe("symbol.expression-source")
  private val otherSourceId = SymbolId.unsafe("symbol.expression-other-source")
  private val aliasId = SymbolId.unsafe("symbol.expression-alias")
  private val sinkId = SymbolId.unsafe("symbol.expression-sink")
  private val secondSinkId = SymbolId.unsafe("symbol.expression-second-sink")
  private val aliasDriverId = DriverId.unsafe("driver.expression-alias")
  private val sinkDriverId = DriverId.unsafe("driver.expression-sink")
  private val secondSinkDriverId = DriverId.unsafe("driver.expression-second-sink")

  private val enabled = WireAliasPassConfiguration(enabled = true)

  private val widthDomain = IntegerParameterDomain(
    minimum = BigInt(1),
    maximum = BigInt(64),
    admittedValues = (1 to 64).map(value => BigInt(value)).toVector
  )

  private val packedType = PackedType(
    width = IntExpr.ParameterRef(widthParameterId),
    signedness = Signedness.Unsigned,
    valueSemantics = PackedValueSemantics.BitVector
  )

  private def location(path: String, line: Int): SourceLocation =
    SourceLocation(path, line, 1)

  private def declaration(
      id: SymbolId,
      kind: DeclarationKind,
      origin: NameOrigin,
      path: String,
      line: Int,
      observability: Observability = Observability.Unobserved
  ): Declaration =
    Declaration(
      id = id,
      owner = rootScopeId,
      kind = kind,
      packedType = Some(packedType),
      nameOrigin = origin,
      sourceLocation = Some(location(path, line)),
      observability = observability
    )

  private def reference(
      id: String,
      target: SymbolId,
      path: String,
      line: Int
  ): RtlExpr.Ref =
    RtlExpr.Ref(
      id = ReferenceId.unsafe(id),
      target = target,
      owner = rootScopeId,
      sourceLocation = Some(location(path, line))
    )

  private def defaultExpression(path: String): RtlExpr =
    RtlExpr.Binary(
      RtlBinaryOperator.BitwiseXor,
      reference("reference.expression.left", sourceId, path, 20),
      RtlExpr.Unary(
        RtlUnaryOperator.BitwiseNot,
        reference("reference.expression.right", otherSourceId, path, 20)
      )
    )

  private def design(
      moduleName: String = "GenericExpressionFixture",
      path: String = "src/GenericExpressionFixture.scala",
      aliasOrigin: NameOrigin = NameOrigin.Unnamed,
      aliasObservability: Observability = Observability.Unobserved,
      aliasDriverKind: DriverKind = DriverKind.Continuous,
      receiverDriverKind: DriverKind = DriverKind.Continuous,
      expression: RtlExpr = null,
      includeSecondReceiver: Boolean = true
  ): Design = {
    val source = declaration(
      sourceId,
      DeclarationKind.Port(PortDirection.Input),
      NameOrigin.Explicit("source"),
      path,
      10,
      Observability(complete = true, externallyVisible = true)
    )
    val otherSource = declaration(
      otherSourceId,
      DeclarationKind.Port(PortDirection.Input),
      NameOrigin.Explicit("otherSource"),
      path,
      11,
      Observability(complete = true, externallyVisible = true)
    )
    val alias = declaration(
      aliasId,
      DeclarationKind.InternalCombinational,
      aliasOrigin,
      path,
      12,
      aliasObservability
    )
    val sink = declaration(
      sinkId,
      DeclarationKind.Port(PortDirection.Output),
      NameOrigin.Explicit("sink"),
      path,
      13,
      Observability(complete = true, externallyVisible = true)
    )
    val secondSink = declaration(
      secondSinkId,
      DeclarationKind.Port(PortDirection.Output),
      NameOrigin.Explicit("secondSink"),
      path,
      14,
      Observability(complete = true, externallyVisible = true)
    )

    val aliasDriver = Driver(
      id = aliasDriverId,
      owner = rootScopeId,
      target = aliasId,
      kind = aliasDriverKind,
      coverage = DriverCoverage.FullObject,
      value = Option(expression).getOrElse(defaultExpression(path)),
      sourceLocation = Some(location(path, 20))
    )
    val sinkDriver = Driver(
      id = sinkDriverId,
      owner = rootScopeId,
      target = sinkId,
      kind = receiverDriverKind,
      coverage = DriverCoverage.FullObject,
      value = RtlExpr.Concat(
        Vector(reference("reference.expression.sink", aliasId, path, 24))
      ),
      sourceLocation = Some(location(path, 24))
    )
    val secondSinkDriver = Driver(
      id = secondSinkDriverId,
      owner = rootScopeId,
      target = secondSinkId,
      kind = DriverKind.Continuous,
      coverage = DriverCoverage.FullObject,
      value = reference("reference.expression.second-sink", aliasId, path, 25),
      sourceLocation = Some(location(path, 25))
    )

    Design(
      version = CanonicalIrSchema.schemaVersion,
      stage = CanonicalIrSchema.stage,
      top = moduleId,
      modules = Vector(
        Module(
          id = moduleId,
          logicalName = moduleName,
          parameters = Vector(
            IntegerParameter(
              id = widthParameterId,
              name = "WIDTH",
              default = BigInt(8),
              domain = widthDomain,
              sourceLocation = Some(location(path, 4))
            )
          ),
          scopes = Vector(
            Scope(
              id = rootScopeId,
              parent = None,
              kind = ScopeKind.Module,
              label = Some("root"),
              sourceLocation = Some(location(path, 6))
            )
          ),
          generateIndices = Vector.empty,
          declarations = Vector(source, otherSource, alias, sink, secondSink),
          drivers = Vector(aliasDriver, sinkDriver) ++
            (if (includeSecondReceiver) Vector(secondSinkDriver) else Vector.empty),
          sourceLocation = Some(location(path, 2))
        )
      )
    )
  }

  private def moduleOf(value: Design): Module = value.modules.head

  private def driverOf(value: Design, id: DriverId): Driver =
    moduleOf(value).drivers.find(_.id == id).get

  test("unnamed expression pass is disabled by the common flag by default") {
    val input = design()
    val result = UnnamedWireExpressionEliminationPass.run(input)

    result.status shouldBe PassExecutionStatus.Skipped
    result.output shouldBe input
    result.eliminationReport.passId shouldBe PassId.UnnamedWireExpressionElimination
    result.eliminationReport.eliminatedCount shouldBe 0
  }

  test("binary expression is cloned into every continuous receiver and the temporary is removed") {
    val input = design()
    val result = UnnamedWireExpressionEliminationPass.run(input, enabled)

    result.status shouldBe PassExecutionStatus.Changed
    moduleOf(result.output).declarations.map(_.id) should not contain aliasId
    moduleOf(result.output).drivers.map(_.id) should not contain aliasDriverId
    result.eliminationReport.eliminated shouldBe empty
    result.eliminationReport.eliminatedExpressions.size shouldBe 1

    val evidence = result.eliminationReport.eliminatedExpressions.head
    evidence.aliasSymbol.value shouldBe aliasId.value
    evidence.rootOperator shouldBe "binary:bitwise-xor"
    evidence.expressionNodeCount shouldBe 4
    evidence.receiverCount shouldBe 2
    evidence.referencedSymbols.map(_.value) shouldBe Vector(
      otherSourceId.value,
      sourceId.value
    ).sorted

    val references = Vector(
      driverOf(result.output, sinkDriverId),
      driverOf(result.output, secondSinkDriverId)
    ).flatMap(_.value.referenceOccurrences)
    references.map(_.target).toSet shouldBe Set(sourceId, otherSourceId)
    references.map(_.id).distinct.size shouldBe references.size
    references.map(_.owner).distinct shouldBe Vector(rootScopeId)
    references.foreach(value => value.id.value should include("wa07-inline"))

    driverOf(result.output, secondSinkDriverId).value match {
      case RtlExpr.Resize(value, width, signedness) =>
        value shouldBe a[RtlExpr.Binary]
        width shouldBe IntExpr.ParameterRef(widthParameterId)
        signedness shouldBe Signedness.Unsigned
      case other => fail(s"expected assignment-boundary resize fence, observed $other")
    }
  }

  test("literal RHS is an expression and is inlined with the assignment type fence") {
    val fixedType = packedType.copy(width = IntExpr.Literal(BigInt(8)))
    val input = design(
      expression = RtlExpr.Literal(BigInt(165), 8),
      includeSecondReceiver = false
    ).copy(
      modules = design(
        expression = RtlExpr.Literal(BigInt(165), 8),
        includeSecondReceiver = false
      ).modules.map { module =>
        module.copy(
          parameters = Vector.empty,
          declarations = module.declarations.map(_.copy(packedType = Some(fixedType)))
        )
      }
    )

    val result = UnnamedWireExpressionEliminationPass.run(input, enabled)

    result.status shouldBe PassExecutionStatus.Changed
    result.eliminationReport.eliminatedExpressions.head.rootOperator shouldBe "literal"
    driverOf(result.output, sinkDriverId).value.referenceOccurrences shouldBe empty
  }

  test("direct reference aliases remain the responsibility of the previous pass") {
    val input = design(
      expression = reference(
        "reference.expression.direct-source",
        sourceId,
        "src/Direct.scala",
        20
      )
    )
    val result = UnnamedWireExpressionEliminationPass.run(input, enabled)

    result.status shouldBe PassExecutionStatus.Unchanged
    result.output shouldBe input.normalized
    result.eliminationReport.isEmpty shouldBe true
  }

  test("assignment to an unnamed temporary inside an always block is retained") {
    val input = design(aliasDriverKind = DriverKind.Procedural)
    val result = UnnamedWireExpressionEliminationPass.run(input, enabled)

    result.status shouldBe PassExecutionStatus.Unchanged
    moduleOf(result.output).declarations.map(_.id) should contain(aliasId)
    result.eliminationReport.rejected.map(_.reasonCode) should contain(
      UnnamedWireExpressionSafetyReason.DriverNotContinuous
    )
  }

  test("continuous temporary used by an always-block assignment is retained") {
    val input = design(
      receiverDriverKind = DriverKind.Procedural,
      includeSecondReceiver = false
    )
    val result = UnnamedWireExpressionEliminationPass.run(input, enabled)

    result.status shouldBe PassExecutionStatus.Unchanged
    moduleOf(result.output).declarations.map(_.id) should contain(aliasId)
    result.eliminationReport.rejected.map(_.reasonCode) should contain(
      UnnamedWireExpressionSafetyReason.ReceiverProcedural
    )
  }

  test("named and observable expression temporaries are outside the pass") {
    val named = UnnamedWireExpressionEliminationPass.run(
      design(aliasOrigin = NameOrigin.Explicit("debugExpression")),
      enabled
    )
    named.status shouldBe PassExecutionStatus.Unchanged
    named.eliminationReport.isEmpty shouldBe true

    val kept = UnnamedWireExpressionEliminationPass.run(
      design(
        aliasObservability = Observability(complete = true, keep = true)
      ),
      enabled
    )
    kept.status shouldBe PassExecutionStatus.Unchanged
    kept.eliminationReport.rejected.map(_.reasonCode) should contain(
      UnnamedWireExpressionSafetyReason.Observability
    )
  }

  test("self-referential expression is retained") {
    val path = "src/SelfReference.scala"
    val input = design(
      expression = RtlExpr.Unary(
        RtlUnaryOperator.BitwiseNot,
        reference("reference.expression.self", aliasId, path, 20)
      )
    )
    val result = UnnamedWireExpressionEliminationPass.run(input, enabled)

    result.status shouldBe PassExecutionStatus.Unchanged
    result.eliminationReport.rejected.map(_.reasonCode) should contain(
      UnnamedWireExpressionSafetyReason.SourceSelfReference
    )
  }

  test("repeated execution is deterministic and reaches an idempotent fixed point") {
    val input = design()
    val first = UnnamedWireExpressionEliminationPass.run(input, enabled)
    val repeated = UnnamedWireExpressionEliminationPass.run(input, enabled)
    val fixedPoint = UnnamedWireExpressionEliminationPass.run(first.output, enabled)

    first shouldBe repeated
    first.normalized shouldBe first
    fixedPoint.status shouldBe PassExecutionStatus.Unchanged
    fixedPoint.output shouldBe first.output
    fixedPoint.eliminationReport.eliminatedCount shouldBe 0
  }

  test("module names and source paths do not affect expression decisions") {
    val first = UnnamedWireExpressionEliminationPass.run(
      design("FirstModule", "src/first/Logic.scala"),
      enabled
    )
    val second = UnnamedWireExpressionEliminationPass.run(
      design("UnrelatedModule", "elsewhere/Other.scala"),
      enabled
    )

    first.status shouldBe second.status
    first.eliminationReport.eliminatedExpressions.map(value =>
      (value.aliasSymbol, value.rootOperator, value.expressionNodeCount, value.receiverCount)
    ) shouldBe second.eliminationReport.eliminatedExpressions.map(value =>
      (value.aliasSymbol, value.rootOperator, value.expressionNodeCount, value.receiverCount)
    )
    moduleOf(first.output).declarations.map(_.id) shouldBe
      moduleOf(second.output).declarations.map(_.id)
  }

  test("shared parameterized witness proof contract covers the complete WIDTH domain") {
    val result = UnnamedWireExpressionEliminationPass.run(design(), enabled)
    val parameter = moduleOf(result.output).parameters.head.asInstanceOf[IntegerParameter]

    result.status shouldBe PassExecutionStatus.Changed
    parameter.domain.admittedValues shouldBe
      (1 to 64).map(value => BigInt(value)).toVector
    result.eliminationReport.eliminatedExpressions.head.receiverCount shouldBe 2
  }

  test("invalid canonical input fails closed and publishes the original design") {
    val invalid = design().copy(modules = Vector.empty)
    val result = UnnamedWireExpressionEliminationPass.run(invalid, enabled)

    result.status shouldBe PassExecutionStatus.Failed
    result.output shouldBe invalid
    result.hasErrors shouldBe true
    result.eliminationReport.eliminatedCount shouldBe 0
  }
}
