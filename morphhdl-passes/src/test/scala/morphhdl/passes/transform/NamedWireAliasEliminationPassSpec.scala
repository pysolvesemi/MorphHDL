package morphhdl.passes.transform

import morphhdl.ir.v1.AttributeKind
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
import morphhdl.ir.v1.IrAttribute
import morphhdl.ir.v1.IrComment
import morphhdl.ir.v1.IrDiagnosticCode
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
import morphhdl.passes.api.AliasNameOrigin
import morphhdl.passes.api.DiagnosticSeverity
import morphhdl.passes.api.IrSymbolId
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.PassResult
import morphhdl.passes.api.WireAliasPassConfiguration
import morphhdl.passes.safety.AliasSafetyReason
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class NamedWireAliasEliminationPassSpec
    extends AnyFunSuite
    with Matchers {
  private val moduleId = ModuleId.unsafe("module.generic")
  private val rootScopeId = ScopeId.unsafe("scope.root")
  private val widthParameterId = ParameterId.unsafe("parameter.width")
  private val depthParameterId = ParameterId.unsafe("parameter.depth")
  private val sourceId = SymbolId.unsafe("symbol.source")
  private val otherSourceId = SymbolId.unsafe("symbol.other-source")
  private val aliasId = SymbolId.unsafe("symbol.alias")
  private val secondAliasId = SymbolId.unsafe("symbol.alias-second")
  private val sinkId = SymbolId.unsafe("symbol.sink")
  private val aliasDriverId = DriverId.unsafe("driver.alias")
  private val secondAliasDriverId = DriverId.unsafe("driver.alias-second")
  private val sinkDriverId = DriverId.unsafe("driver.sink")
  private val sourceReferenceId = ReferenceId.unsafe("reference.alias.source")
  private val aliasReferenceId = ReferenceId.unsafe("reference.sink.alias")
  private val secondAliasReferenceId = ReferenceId.unsafe("reference.sink.alias-second")

  private val enabled = WireAliasPassConfiguration(
    eliminateNamedAliases = true
  )

  private val widthDomain = IntegerParameterDomain(
    minimum = BigInt(1),
    maximum = BigInt(64),
    admittedValues = (1 to 64).map(value => BigInt(value)).toVector
  )
  private val depthDomain = IntegerParameterDomain(
    minimum = BigInt(1),
    maximum = BigInt(8),
    admittedValues = (1 to 8).map(value => BigInt(value)).toVector
  )

  private val packedType = PackedType(
    width = IntExpr.ParameterRef(widthParameterId),
    signedness = Signedness.Unsigned,
    valueSemantics = PackedValueSemantics.BitVector
  )
  private val sourcePackedType = packedType.copy(
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

  private def baseDesign(
      aliasOrigin: NameOrigin = NameOrigin.Explicit("namedAlias"),
      aliasObservability: Observability = Observability.Unobserved,
      moduleName: String = "GenericAliasFixture",
      sourcePath: String = "src/GenericAliasFixture.scala"
  ): Design = {
    val root = Scope(
      id = rootScopeId,
      parent = None,
      kind = ScopeKind.Module,
      label = Some("root"),
      sourceLocation = Some(location(sourcePath, 6))
    )
    val source = Declaration(
      id = sourceId,
      owner = rootScopeId,
      kind = DeclarationKind.Port(PortDirection.Input),
      packedType = Some(sourcePackedType),
      nameOrigin = NameOrigin.Explicit("source"),
      sourceLocation = Some(location(sourcePath, 10)),
      observability = Observability(complete = true, externallyVisible = true)
    )
    val alias = Declaration(
      id = aliasId,
      owner = rootScopeId,
      kind = DeclarationKind.InternalCombinational,
      packedType = Some(packedType),
      nameOrigin = aliasOrigin,
      sourceLocation = Some(location(sourcePath, 11)),
      observability = aliasObservability
    )
    val sink = Declaration(
      id = sinkId,
      owner = rootScopeId,
      kind = DeclarationKind.Port(PortDirection.Output),
      packedType = Some(packedType),
      nameOrigin = NameOrigin.Explicit("sink"),
      sourceLocation = Some(location(sourcePath, 12)),
      observability = Observability(complete = true, externallyVisible = true)
    )
    val aliasDriver = Driver(
      id = aliasDriverId,
      owner = rootScopeId,
      target = aliasId,
      kind = DriverKind.Continuous,
      coverage = DriverCoverage.FullObject,
      value = RtlExpr.Ref(
        id = sourceReferenceId,
        target = sourceId,
        owner = rootScopeId,
        sourceLocation = Some(location(sourcePath, 14))
      ),
      sourceLocation = Some(location(sourcePath, 14))
    )
    val sinkDriver = Driver(
      id = sinkDriverId,
      owner = rootScopeId,
      target = sinkId,
      kind = DriverKind.Continuous,
      coverage = DriverCoverage.FullObject,
      value = RtlExpr.Ref(
        id = aliasReferenceId,
        target = aliasId,
        owner = rootScopeId,
        sourceLocation = Some(location(sourcePath, 15))
      ),
      sourceLocation = Some(location(sourcePath, 15))
    )
    val module = Module(
      id = moduleId,
      logicalName = moduleName,
      parameters = Vector(
        parameter(depthParameterId, "DEPTH", BigInt(5), depthDomain, sourcePath),
        parameter(widthParameterId, "WIDTH", BigInt(8), widthDomain, sourcePath)
      ),
      scopes = Vector(root),
      generateIndices = Vector.empty,
      declarations = Vector(sink, alias, source),
      drivers = Vector(sinkDriver, aliasDriver),
      sourceLocation = Some(location(sourcePath, 2))
    )
    Design(
      version = CanonicalIrSchema.schemaVersion,
      stage = CanonicalIrSchema.stage,
      top = moduleId,
      modules = Vector(module)
    )
  }

  private def updateModule(design: Design)(operation: Module => Module): Design =
    design.copy(modules = design.modules.map(operation))

  private def updateDeclaration(
      design: Design,
      id: SymbolId
  )(operation: Declaration => Declaration): Design =
    updateModule(design) { module =>
      module.copy(
        declarations = module.declarations.map { declaration =>
          if (declaration.id == id) operation(declaration) else declaration
        }
      )
    }

  private def updateDriver(
      design: Design,
      id: DriverId
  )(operation: Driver => Driver): Design =
    updateModule(design) { module =>
      module.copy(
        drivers = module.drivers.map { driver =>
          if (driver.id == id) operation(driver) else driver
        }
      )
    }

  private def moduleOf(design: Design): Module =
    design.modules.find(_.id == moduleId).get

  private def run(design: Design): PassResult[Design] =
    NamedWireAliasEliminationPass.run(design, enabled)

  test("named elimination is disabled by default") {
    val design = baseDesign()
    val result = NamedWireAliasEliminationPass.run(design)

    result.status shouldBe PassExecutionStatus.Skipped
    result.output shouldBe design
    result.diagnostics shouldBe empty
    result.eliminationReport.isEmpty shouldBe true
  }

  test("eligible explicitly named alias is removed by exact symbol identity") {
    val design = baseDesign()
    val originalReference = moduleOf(design)
      .drivers
      .find(_.id == sinkDriverId)
      .get
      .value
      .asInstanceOf[RtlExpr.Ref]
    val result = run(design)
    val outputModule = moduleOf(result.output)
    val rewrittenReference = outputModule
      .drivers
      .find(_.id == sinkDriverId)
      .get
      .value
      .asInstanceOf[RtlExpr.Ref]

    result.status shouldBe PassExecutionStatus.Changed
    outputModule.declarations.map(_.id) should not contain aliasId
    outputModule.drivers.map(_.id) should not contain aliasDriverId
    rewrittenReference.target shouldBe sourceId
    rewrittenReference.id shouldBe originalReference.id
    rewrittenReference.owner shouldBe originalReference.owner
    rewrittenReference.sourceLocation shouldBe originalReference.sourceLocation
    result.eliminationReport.eliminated.map(_.aliasSymbol) shouldBe Vector(
      IrSymbolId.unsafe(aliasId.value)
    )
    result.eliminationReport.eliminated.head.sourceSymbol shouldBe
      IrSymbolId.unsafe(sourceId.value)
    result.eliminationReport.eliminated.head.nameOrigin shouldBe
      AliasNameOrigin.Explicit("namedAlias")
    result.eliminationReport.eliminated.head.location shouldBe
      Some(morphhdl.passes.api.SourceLocation("src/GenericAliasFixture.scala", 11, 1))
    result.diagnostics.exists(_.message.contains("without transferring the removed name")) shouldBe true
  }

  test("recursive expression rewriting preserves every reference identity") {
    val sourcePath = "src/RecursiveFixture.scala"
    val nested = RtlExpr.Binary(
      RtlBinaryOperator.BitwiseXor,
      RtlExpr.Ref(
        aliasReferenceId,
        aliasId,
        rootScopeId,
        Some(location(sourcePath, 20))
      ),
      RtlExpr.Mux(
        RtlExpr.Literal(BigInt(1), width = 1),
        RtlExpr.Ref(
          secondAliasReferenceId,
          aliasId,
          rootScopeId,
          Some(location(sourcePath, 21))
        ),
        RtlExpr.Ref(
          ReferenceId.unsafe("reference.sink.source"),
          sourceId,
          rootScopeId,
          Some(location(sourcePath, 22))
        )
      )
    )
    val design = updateDriver(
      baseDesign(sourcePath = sourcePath),
      sinkDriverId
    )(_.copy(value = nested))
    val originalIds = nested.referenceOccurrences.map(_.id)
    val result = run(design)
    val rewritten = moduleOf(result.output).drivers.find(_.id == sinkDriverId).get.value

    result.status shouldBe PassExecutionStatus.Changed
    rewritten.referenceOccurrences.map(_.id) shouldBe originalIds
    rewritten.referenceOccurrences.map(_.target).distinct shouldBe Vector(sourceId)
  }

  test("only exact alias identity changes while neighboring symbols remain untouched") {
    val sourcePath = "src/IdentityFixture.scala"
    val otherSource = Declaration(
      id = otherSourceId,
      owner = rootScopeId,
      kind = DeclarationKind.Port(PortDirection.Input),
      packedType = Some(packedType),
      nameOrigin = NameOrigin.Explicit("otherSource"),
      sourceLocation = Some(location(sourcePath, 13)),
      observability = Observability(complete = true, externallyVisible = true)
    )
    val expression = RtlExpr.Binary(
      RtlBinaryOperator.BitwiseOr,
      RtlExpr.Ref(aliasReferenceId, aliasId, rootScopeId),
      RtlExpr.Ref(
        ReferenceId.unsafe("reference.sink.other-source"),
        otherSourceId,
        rootScopeId
      )
    )
    val design = updateModule(baseDesign(sourcePath = sourcePath)) { module =>
      module.copy(
        declarations = module.declarations :+ otherSource,
        drivers = module.drivers.map { driver =>
          if (driver.id == sinkDriverId) driver.copy(value = expression) else driver
        }
      )
    }
    val result = run(design)
    val targets = moduleOf(result.output)
      .drivers
      .find(_.id == sinkDriverId)
      .get
      .value
      .referenceOccurrences
      .map(_.target)

    targets shouldBe Vector(sourceId, otherSourceId)
  }

  test("only explicit source names are candidates and emitted-name text is not classification") {
    val explicit = run(baseDesign(aliasOrigin = NameOrigin.Explicit("_zz_7")))
    explicit.status shouldBe PassExecutionStatus.Changed
    explicit.eliminationReport.eliminated.head.nameOrigin shouldBe
      AliasNameOrigin.Explicit("_zz_7")

    val retainedOrigins = Vector[NameOrigin](
      NameOrigin.Unnamed,
      NameOrigin.Reflected("keptAlias"),
      NameOrigin.Generated
    )
    retainedOrigins.foreach { origin =>
      val result = run(baseDesign(aliasOrigin = origin))
      val outputModule = moduleOf(result.output)

      result.status shouldBe PassExecutionStatus.Unchanged
      result.eliminationReport.isEmpty shouldBe true
      outputModule.declarations.map(_.id) should contain(aliasId)
      outputModule.drivers.map(_.id) should contain(aliasDriverId)
    }
  }

  test("unsafe explicitly named alias is retained with deterministic rejection evidence") {
    val design = baseDesign(
      aliasObservability = Observability(complete = true, keep = true)
    )
    val result = run(design)

    result.status shouldBe PassExecutionStatus.Unchanged
    moduleOf(result.output).declarations.map(_.id) should contain(aliasId)
    result.eliminationReport.eliminated shouldBe empty
    result.eliminationReport.rejected.map(_.reasonCode) should contain(
      AliasSafetyReason.Keep
    )
    result.diagnostics.exists { diagnostic =>
      diagnostic.severity == DiagnosticSeverity.Warning &&
      diagnostic.message.contains(AliasSafetyReason.Keep)
    } shouldBe true
  }

  test("public hierarchical preservation probe attribute comment and source contracts are retained") {
    val observabilityCases = Vector[(Observability, String)](
      Observability(complete = true, externallyVisible = true) -> AliasSafetyReason.ExternallyVisible,
      Observability(complete = true, keep = true) -> AliasSafetyReason.Keep,
      Observability(complete = true, dontTouch = true) -> AliasSafetyReason.DontTouch,
      Observability(complete = true, probe = true) -> AliasSafetyReason.Probe,
      Observability(complete = true, preserve = true) -> AliasSafetyReason.Preserve,
      Observability(complete = true, publicExport = true) -> AliasSafetyReason.PublicExport,
      Observability(complete = true, hierarchyBoundary = true) -> AliasSafetyReason.HierarchyBoundary
    )
    observabilityCases.foreach { case (observability, reason) =>
      val result = run(baseDesign(aliasObservability = observability))
      withClue(reason) {
        result.status shouldBe PassExecutionStatus.Unchanged
        result.eliminationReport.rejected.map(_.reasonCode) should contain(reason)
      }
    }

    val attribute = IrAttribute(
      "preserve_point",
      Some("true"),
      AttributeKind.Semantic,
      Some(location("src/NamedContractFixture.scala", 11))
    )
    val comment = IrComment(
      "required named waveform contract",
      Some(location("src/NamedContractFixture.scala", 11))
    )
    val declarationContracts = updateDeclaration(baseDesign(), aliasId)(
      _.copy(attributes = Vector(attribute), comments = Vector(comment))
    )
    val declarationResult = run(declarationContracts)
    declarationResult.eliminationReport.rejected.map(_.reasonCode) shouldBe Vector(
      AliasSafetyReason.DeclarationAttributes
    )
    val declarationDiagnostics = declarationResult.diagnostics.map(_.message)
    declarationDiagnostics.exists(
      _.contains(AliasSafetyReason.DeclarationAttributes)
    ) shouldBe true
    declarationDiagnostics.exists(
      _.contains(AliasSafetyReason.DeclarationComments)
    ) shouldBe true

    val driverContracts = updateDriver(baseDesign(), aliasDriverId)(
      _.copy(attributes = Vector(attribute), comments = Vector(comment))
    )
    val driverResult = run(driverContracts)
    driverResult.eliminationReport.rejected.map(_.reasonCode) shouldBe Vector(
      AliasSafetyReason.DriverAttributes
    )
    val driverDiagnostics = driverResult.diagnostics.map(_.message)
    driverDiagnostics.exists(
      _.contains(AliasSafetyReason.DriverAttributes)
    ) shouldBe true
    driverDiagnostics.exists(
      _.contains(AliasSafetyReason.DriverComments)
    ) shouldBe true
  }

  test("shared parameterized witness proof contract covers the complete WIDTH and DEPTH domain") {
    val result = run(baseDesign())

    result.status shouldBe PassExecutionStatus.Changed
    moduleOf(result.output).parameters.collect {
      case value: IntegerParameter => value.id -> value.domain.admittedValues
    }.toMap shouldBe Map(
      widthParameterId -> widthDomain.admittedValues,
      depthParameterId -> depthDomain.admittedValues
    )
    widthDomain.admittedValues.size * depthDomain.admittedValues.size shouldBe 512
  }

  test("alias chains reach a fixed point and the pass is idempotent") {
    val sourcePath = "src/ChainFixture.scala"
    val secondAlias = Declaration(
      id = secondAliasId,
      owner = rootScopeId,
      kind = DeclarationKind.InternalCombinational,
      packedType = Some(packedType),
      nameOrigin = NameOrigin.Explicit("secondNamedAlias"),
      sourceLocation = Some(location(sourcePath, 12)),
      observability = Observability.Unobserved
    )
    val secondAliasDriver = Driver(
      id = secondAliasDriverId,
      owner = rootScopeId,
      target = secondAliasId,
      kind = DriverKind.Continuous,
      coverage = DriverCoverage.FullObject,
      value = RtlExpr.Ref(
        secondAliasReferenceId,
        aliasId,
        rootScopeId,
        Some(location(sourcePath, 16))
      ),
      sourceLocation = Some(location(sourcePath, 16))
    )
    val design = updateModule(baseDesign(sourcePath = sourcePath)) { module =>
      module.copy(
        declarations = module.declarations :+ secondAlias,
        drivers = module.drivers.map { driver =>
          if (driver.id == sinkDriverId) {
            driver.copy(
              value = RtlExpr.Ref(
                aliasReferenceId,
                secondAliasId,
                rootScopeId,
                Some(location(sourcePath, 17))
              )
            )
          } else driver
        } :+ secondAliasDriver
      )
    }
    val first = run(design)
    val second = run(first.output)
    val outputModule = moduleOf(first.output)

    first.status shouldBe PassExecutionStatus.Changed
    first.eliminationReport.eliminated.map(_.aliasSymbol).toSet shouldBe Set(
      IrSymbolId.unsafe(aliasId.value),
      IrSymbolId.unsafe(secondAliasId.value)
    )
    outputModule.declarations.map(_.id) should contain only (sourceId, sinkId)
    outputModule.drivers.map(_.id) should contain only sinkDriverId
    outputModule.drivers.head.value.directReference shouldBe Some(sourceId)
    second.status shouldBe PassExecutionStatus.Unchanged
    second.output shouldBe first.output
    second.eliminationReport.isEmpty shouldBe true
  }

  test("all surviving names and metadata remain unchanged") {
    val design = baseDesign(moduleName = "NamePreservationFixture")
    val inputModule = moduleOf(design.normalized)
    val result = run(design)
    val outputModule = moduleOf(result.output)

    outputModule.logicalName shouldBe inputModule.logicalName
    outputModule.parameters shouldBe inputModule.parameters
    outputModule.scopes shouldBe inputModule.scopes
    outputModule.generateIndices shouldBe inputModule.generateIndices
    outputModule.sourceLocation shouldBe inputModule.sourceLocation
    outputModule.declarations shouldBe inputModule.declarations.filterNot(_.id == aliasId)
    outputModule.declarations.find(_.id == sourceId).get.nameOrigin shouldBe
      NameOrigin.Explicit("source")
    outputModule.declarations.find(_.id == sinkId).get.nameOrigin shouldBe
      NameOrigin.Explicit("sink")
    outputModule.declarations.exists(_.nameOrigin == NameOrigin.Explicit("namedAlias")) shouldBe false

    val originalSinkDriver = inputModule.drivers.find(_.id == sinkDriverId).get
    val outputSinkDriver = outputModule.drivers.find(_.id == sinkDriverId).get
    outputSinkDriver.copy(value = originalSinkDriver.value) shouldBe originalSinkDriver
  }

  test("invalid canonical input fails closed without a partial rewrite") {
    val valid = baseDesign()
    val invalid = updateModule(valid) { module =>
      val alias = module.declarations.find(_.id == aliasId).get
      module.copy(declarations = module.declarations :+ alias)
    }
    val result = run(invalid)

    result.status shouldBe PassExecutionStatus.Failed
    result.output shouldBe invalid
    result.eliminationReport.isEmpty shouldBe true
    result.diagnostics.map(_.code) should contain(IrDiagnosticCode.SymbolIdDuplicate)
    result.diagnostics.forall(_.severity == DiagnosticSeverity.Error) shouldBe true
  }

  test("normalized output reports and diagnostics are deterministic") {
    val design = baseDesign()
    val shuffled = updateModule(design) { module =>
      module.copy(
        parameters = module.parameters.reverse,
        scopes = module.scopes.reverse,
        declarations = module.declarations.reverse,
        drivers = module.drivers.reverse
      )
    }
    val first = run(design)
    val second = run(shuffled)

    first.output shouldBe second.output
    first.eliminationReport shouldBe second.eliminationReport
    first.diagnostics shouldBe second.diagnostics
  }

  test("component names and source paths do not affect explicit-name classification") {
    val first = run(
      baseDesign(
        moduleName = "FirstGenericBlock",
        sourcePath = "src/FirstGenericBlock.scala"
      )
    )
    val second = run(
      baseDesign(
        moduleName = "UnrelatedBlock",
        sourcePath = "different/location/UnrelatedBlock.scala"
      )
    )

    first.eliminationReport.eliminated.map(value =>
      value.aliasSymbol -> value.sourceSymbol
    ) shouldBe second.eliminationReport.eliminated.map(value =>
      value.aliasSymbol -> value.sourceSymbol
    )
    moduleOf(first.output).declarations.map(_.id) shouldBe
      moduleOf(second.output).declarations.map(_.id)
    moduleOf(first.output).drivers.map(_.id) shouldBe
      moduleOf(second.output).drivers.map(_.id)
  }
}
