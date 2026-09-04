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
import morphhdl.ir.v1.RtlExpr
import morphhdl.ir.v1.Scope
import morphhdl.ir.v1.ScopeId
import morphhdl.ir.v1.ScopeKind
import morphhdl.ir.v1.Signedness
import morphhdl.ir.v1.SourceLocation
import morphhdl.ir.v1.SymbolId
import morphhdl.passes.api.AliasNameOrigin
import morphhdl.passes.api.IrSymbolId
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.PassId
import morphhdl.passes.api.WireAliasPassConfiguration
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class WireAliasPassPipelineSpec extends AnyFunSuite with Matchers {
  private val moduleId = ModuleId.unsafe("module.pipeline")
  private val rootScopeId = ScopeId.unsafe("scope.pipeline-root")
  private val widthParameterId = ParameterId.unsafe("parameter.pipeline-width")
  private val depthParameterId = ParameterId.unsafe("parameter.pipeline-depth")
  private val sourceId = SymbolId.unsafe("symbol.pipeline-source")
  private val unnamedAliasId = SymbolId.unsafe("symbol.pipeline-unnamed")
  private val namedAliasId = SymbolId.unsafe("symbol.pipeline-named")
  private val directSinkId = SymbolId.unsafe("symbol.pipeline-direct-sink")
  private val nestedSinkId = SymbolId.unsafe("symbol.pipeline-nested-sink")
  private val unnamedDriverId = DriverId.unsafe("driver.pipeline-unnamed")
  private val namedDriverId = DriverId.unsafe("driver.pipeline-named")
  private val directSinkDriverId = DriverId.unsafe("driver.pipeline-direct-sink")
  private val nestedSinkDriverId = DriverId.unsafe("driver.pipeline-nested-sink")

  private val enabled = WireAliasPassConfiguration(enabled = true)
  private val depthDomain = IntegerParameterDomain(
    minimum = BigInt(1),
    maximum = BigInt(8),
    admittedValues = (1 to 8).map(value => BigInt(value)).toVector
  )

  private val aliasPackedType = PackedType(
    width = IntExpr.ParameterRef(widthParameterId),
    signedness = Signedness.Unsigned,
    valueSemantics = PackedValueSemantics.BitVector
  )
  private val sourcePackedType = aliasPackedType.copy(
    width = IntExpr.Add(
      IntExpr.ParameterRef(widthParameterId),
      IntExpr.Multiply(
        IntExpr.ParameterRef(depthParameterId),
        IntExpr.Literal(BigInt(0))
      )
    )
  )

  private def location(path: String, line: Int): SourceLocation =
    SourceLocation(path, line, 1)

  private def parameter(
      id: ParameterId,
      name: String,
      default: BigInt,
      domain: IntegerParameterDomain,
      sourcePath: String
  ): IntegerParameter =
    IntegerParameter(
      id = id,
      name = name,
      default = default,
      domain = domain,
      sourceLocation = Some(location(sourcePath, 4))
    )

  private def declaration(
      id: SymbolId,
      kind: DeclarationKind,
      origin: NameOrigin,
      packedType: PackedType,
      sourcePath: String,
      line: Int,
      externallyVisible: Boolean = false
  ): Declaration =
    Declaration(
      id = id,
      owner = rootScopeId,
      kind = kind,
      packedType = Some(packedType),
      nameOrigin = origin,
      sourceLocation = Some(location(sourcePath, line)),
      observability = Observability(
        complete = true,
        externallyVisible = externallyVisible
      )
    )

  private def reference(
      id: String,
      target: SymbolId,
      sourcePath: String,
      line: Int
  ): RtlExpr.Ref =
    RtlExpr.Ref(
      id = ReferenceId.unsafe(id),
      target = target,
      owner = rootScopeId,
      sourceLocation = Some(location(sourcePath, line))
    )

  private def driver(
      id: DriverId,
      target: SymbolId,
      value: RtlExpr,
      sourcePath: String,
      line: Int
  ): Driver =
    Driver(
      id = id,
      owner = rootScopeId,
      target = target,
      kind = DriverKind.Continuous,
      coverage = DriverCoverage.FullObject,
      value = value,
      sourceLocation = Some(location(sourcePath, line))
    )

  private def baseDesign(
      moduleName: String = "GenericPipelineFixture",
      sourcePath: String = "src/GenericPipelineFixture.scala"
  ): Design = {
    val root = Scope(
      id = rootScopeId,
      parent = None,
      kind = ScopeKind.Module,
      label = Some("root"),
      sourceLocation = Some(location(sourcePath, 6))
    )
    val source = declaration(
      id = sourceId,
      kind = DeclarationKind.Port(PortDirection.Input),
      origin = NameOrigin.Explicit("source"),
      packedType = sourcePackedType,
      sourcePath = sourcePath,
      line = 10,
      externallyVisible = true
    )
    val unnamed = declaration(
      id = unnamedAliasId,
      kind = DeclarationKind.InternalCombinational,
      origin = NameOrigin.Unnamed,
      packedType = aliasPackedType,
      sourcePath = sourcePath,
      line = 11
    )
    val named = declaration(
      id = namedAliasId,
      kind = DeclarationKind.InternalCombinational,
      origin = NameOrigin.Explicit("pipelineDebugAlias"),
      packedType = aliasPackedType,
      sourcePath = sourcePath,
      line = 12
    )
    val directSink = declaration(
      id = directSinkId,
      kind = DeclarationKind.Port(PortDirection.Output),
      origin = NameOrigin.Explicit("directSink"),
      packedType = aliasPackedType,
      sourcePath = sourcePath,
      line = 13,
      externallyVisible = true
    )
    val nestedSink = declaration(
      id = nestedSinkId,
      kind = DeclarationKind.Port(PortDirection.Output),
      origin = NameOrigin.Explicit("nestedSink"),
      packedType = aliasPackedType,
      sourcePath = sourcePath,
      line = 14,
      externallyVisible = true
    )

    val unnamedDriver = driver(
      id = unnamedDriverId,
      target = unnamedAliasId,
      value = reference(
        "reference.pipeline-unnamed-source",
        sourceId,
        sourcePath,
        20
      ),
      sourcePath = sourcePath,
      line = 20
    )
    val namedDriver = driver(
      id = namedDriverId,
      target = namedAliasId,
      value = reference(
        "reference.pipeline-named-unnamed",
        unnamedAliasId,
        sourcePath,
        21
      ),
      sourcePath = sourcePath,
      line = 21
    )
    val directSinkDriver = driver(
      id = directSinkDriverId,
      target = directSinkId,
      value = reference(
        "reference.pipeline-direct-named",
        namedAliasId,
        sourcePath,
        22
      ),
      sourcePath = sourcePath,
      line = 22
    )
    val nestedSinkDriver = driver(
      id = nestedSinkDriverId,
      target = nestedSinkId,
      value = RtlExpr.Concat(
        Vector(
          reference(
            "reference.pipeline-nested-named",
            namedAliasId,
            sourcePath,
            23
          )
        )
      ),
      sourcePath = sourcePath,
      line = 23
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
            parameter(
              depthParameterId,
              "DEPTH",
              BigInt(5),
              depthDomain,
              sourcePath
            ),
            parameter(
              widthParameterId,
              "WIDTH",
              BigInt(8),
              widthDomain,
              sourcePath
            )
          ),
          scopes = Vector(root),
          generateIndices = Vector.empty,
          declarations = Vector(
            nestedSink,
            named,
            source,
            directSink,
            unnamed
          ),
          drivers = Vector(
            nestedSinkDriver,
            namedDriver,
            directSinkDriver,
            unnamedDriver
          ),
          sourceLocation = Some(location(sourcePath, 2))
        )
      )
    )
  }

  private def moduleOf(design: Design): Module =
    design.modules.find(_.id == moduleId).get

  private def declarationIds(design: Design): Vector[SymbolId] =
    moduleOf(design).declarations.map(_.id)

  private def driverOf(design: Design, id: DriverId): Driver =
    moduleOf(design).drivers.find(_.id == id).get

  private def targets(expression: RtlExpr): Vector[SymbolId] =
    expression.referenceOccurrences.map(_.target)

  private def decisionSignature(
      result: WireAliasPipelineResult
  ): Vector[(PassId, Vector[(IrSymbolId, IrSymbolId, AliasNameOrigin)])] =
    result.stages.map { stage =>
      stage.eliminationReport.passId -> stage.eliminationReport.eliminated.map {
        value =>
          (value.aliasSymbol, value.sourceSymbol, value.nameOrigin)
      }
    }

  test("ordered pipeline is disabled by default") {
    val design = baseDesign()
    val result = WireAliasPassPipeline.run(design)

    result.status shouldBe PassExecutionStatus.Skipped
    result.output shouldBe design
    result.stages shouldBe empty
    result.executedPasses shouldBe empty
    result.eliminationReports shouldBe empty
  }

test("one master flag runs every registered pass") {
  val result = WireAliasPassPipeline.run(baseDesign(), enabled)

  result.status shouldBe PassExecutionStatus.Changed
  result.executedPasses shouldBe enabled.enabledPasses
  declarationIds(result.output) should not contain unnamedAliasId
  declarationIds(result.output) should not contain namedAliasId
  targets(driverOf(result.output, directSinkDriverId).value) shouldBe Vector(sourceId)
}

test("combined execution has fixed unnamed-then-named order for alias chains and fanout") {
    val result = WireAliasPassPipeline.run(baseDesign(), enabled)

    result.status shouldBe PassExecutionStatus.Changed
    result.executedPasses shouldBe Vector(
      PassId.UnnamedWireAliasElimination,
      PassId.NamedWireAliasElimination,
      PassId.UnnamedWireExpressionElimination
    )
    result.eliminationReports.map(_.eliminatedCount) shouldBe Vector(1, 1, 0)
    result.eliminationReports.head.eliminated.head.aliasSymbol shouldBe
      IrSymbolId.unsafe(unnamedAliasId.value)
    result.eliminationReports(1).eliminated.head.aliasSymbol shouldBe
      IrSymbolId.unsafe(namedAliasId.value)
    declarationIds(result.output) should not contain unnamedAliasId
    declarationIds(result.output) should not contain namedAliasId
    targets(driverOf(result.output, directSinkDriverId).value) shouldBe Vector(sourceId)
    targets(driverOf(result.output, nestedSinkDriverId).value) shouldBe Vector(sourceId)
    WireAliasPassPipeline.combinedPassId shouldBe
      "wire-alias-unnamed+wire-alias-named+wire-expression-unnamed"
  }

  test("pipeline reports are deterministic and support byte-identical repeated emission") {
    val design = baseDesign()
    val first = WireAliasPassPipeline.run(design, enabled)
    val second = WireAliasPassPipeline.run(design, enabled)

    first shouldBe second
    first.normalized shouldBe first
    first.diagnostics shouldBe second.diagnostics
    first.eliminationReports shouldBe second.eliminationReports
  }

  test("combined pipeline reaches idempotent IR at a fixed point") {
    val first = WireAliasPassPipeline.run(baseDesign(), enabled)
    val second = WireAliasPassPipeline.run(first.output, enabled)

    first.status shouldBe PassExecutionStatus.Changed
    second.status shouldBe PassExecutionStatus.Unchanged
    second.output shouldBe first.output
    second.executedPasses shouldBe Vector(
      PassId.UnnamedWireAliasElimination,
      PassId.NamedWireAliasElimination,
      PassId.UnnamedWireExpressionElimination
    )
    second.eliminated shouldBe empty
  }

  test("invalid canonical input fails closed with atomic rollback") {
    val invalid = baseDesign().copy(modules = Vector.empty)
    val result = WireAliasPassPipeline.run(invalid, enabled)

    result.status shouldBe PassExecutionStatus.Failed
    result.output shouldBe invalid
    result.isSuccess shouldBe false
    result.hasErrors shouldBe true
    result.stages.size shouldBe 1
    result.stages.head.output shouldBe invalid
    result.executedPasses shouldBe Vector(PassId.UnnamedWireAliasElimination)
  }

  test("surviving names metadata and reference identities remain unchanged") {
    val design = baseDesign()
    val before = moduleOf(design)
    val result = WireAliasPassPipeline.run(design, enabled)
    val after = moduleOf(result.output)
    val surviving = Set(sourceId, directSinkId, nestedSinkId)

    after.declarations.filter(value => surviving.contains(value.id)) shouldBe
      before.declarations.filter(value => surviving.contains(value.id)).sortBy(_.id.value)
    after.parameters shouldBe before.parameters.sortBy(_.id.value)
    after.scopes shouldBe before.scopes.sortBy(_.id.value)
    after.generateIndices shouldBe before.generateIndices

    val beforeDirect = driverOf(design, directSinkDriverId).value.referenceOccurrences.head
    val afterDirect = driverOf(result.output, directSinkDriverId).value.referenceOccurrences.head
    afterDirect.id shouldBe beforeDirect.id
    afterDirect.owner shouldBe beforeDirect.owner
    afterDirect.sourceLocation shouldBe beforeDirect.sourceLocation
    afterDirect.target shouldBe sourceId
  }

  test("component names and source paths do not affect pipeline decisions") {
    val first = WireAliasPassPipeline.run(
      baseDesign("FirstGenericModule", "src/first/Combinational.scala"),
      enabled
    )
    val second = WireAliasPassPipeline.run(
      baseDesign("UnrelatedModuleName", "elsewhere/OtherSource.scala"),
      enabled
    )

    decisionSignature(first) shouldBe decisionSignature(second)
    first.executedPasses shouldBe second.executedPasses
    declarationIds(first.output) shouldBe declarationIds(second.output)
  }

  test("shared parameterized witness proof contract covers the complete WIDTH and DEPTH domain") {
    val design = baseDesign()
    val result = WireAliasPassPipeline.run(design, enabled)
    val parameters = moduleOf(result.output).parameters.map(value => value.name -> value).toMap

    result.status shouldBe PassExecutionStatus.Changed
    result.executedPasses shouldBe enabled.enabledPasses
    parameters("WIDTH").asInstanceOf[IntegerParameter].domain.admittedValues shouldBe
      (1 to 64).map(value => BigInt(value)).toVector
    parameters("DEPTH").asInstanceOf[IntegerParameter].domain.admittedValues shouldBe
      (1 to 8).map(value => BigInt(value)).toVector
    result.eliminationReports.map(_.eliminatedCount) shouldBe Vector(1, 1, 0)
  }
}
