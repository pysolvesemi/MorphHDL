package morphhdl.passes.safety

import morphhdl.ir.v1.AttributeKind
import morphhdl.ir.v1.BoolExpr
import morphhdl.ir.v1.BooleanParameter
import morphhdl.ir.v1.BooleanParameterDomain
import morphhdl.ir.v1.CanonicalIrSchema
import morphhdl.ir.v1.Declaration
import morphhdl.ir.v1.DeclarationKind
import morphhdl.ir.v1.Design
import morphhdl.ir.v1.Driver
import morphhdl.ir.v1.DriverCoverage
import morphhdl.ir.v1.DriverId
import morphhdl.ir.v1.DriverKind
import morphhdl.ir.v1.GenerateIndex
import morphhdl.ir.v1.GenerateIndexId
import morphhdl.ir.v1.IntExpr
import morphhdl.ir.v1.IntegerParameter
import morphhdl.ir.v1.IntegerParameterDomain
import morphhdl.ir.v1.IrAttribute
import morphhdl.ir.v1.IrComment
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
import morphhdl.passes.adapter.CanonicalIrPassAdapter
import morphhdl.passes.adapter.CanonicalIrPassView
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class WireAliasSafetyGateSpec extends AnyFunSuite with Matchers {
  private val moduleId = ModuleId.unsafe("module.generic")
  private val rootScopeId = ScopeId.unsafe("scope.root")
  private val innerScopeId = ScopeId.unsafe("scope.inner")
  private val siblingScopeId = ScopeId.unsafe("scope.sibling")
  private val widthParameterId = ParameterId.unsafe("parameter.width")
  private val featureParameterId = ParameterId.unsafe("parameter.feature")
  private val secondWidthParameterId = ParameterId.unsafe("parameter.second-width")
  private val generateIndexId = GenerateIndexId.unsafe("generate.lane")
  private val sourceId = SymbolId.unsafe("symbol.source")
  private val aliasId = SymbolId.unsafe("symbol.alias")
  private val sinkId = SymbolId.unsafe("symbol.sink")
  private val aliasDriverId = DriverId.unsafe("driver.alias")
  private val sinkDriverId = DriverId.unsafe("driver.sink")
  private val sourceDriverId = DriverId.unsafe("driver.source")
  private val sourceReferenceId = ReferenceId.unsafe("reference.alias.source")
  private val aliasReferenceId = ReferenceId.unsafe("reference.sink.alias")

  private val widthDomain = IntegerParameterDomain(
    minimum = BigInt(1),
    maximum = BigInt(8),
    admittedValues = Vector(BigInt(8), BigInt(1), BigInt(4), BigInt(2))
  )

  private val defaultAliasType = PackedType(
    IntExpr.ParameterRef(widthParameterId),
    Signedness.Unsigned,
    PackedValueSemantics.BitVector
  )

  private val defaultSourceType = PackedType(
    IntExpr.Add(IntExpr.ParameterRef(widthParameterId), IntExpr.Literal(BigInt(0))),
    Signedness.Unsigned,
    PackedValueSemantics.BitVector
  )

  private def location(path: String, line: Int): SourceLocation =
    SourceLocation(path, line, 1)

  private def integerParameter(
      id: ParameterId,
      name: String,
      domain: IntegerParameterDomain = widthDomain
  ): IntegerParameter =
    IntegerParameter(
      id,
      name,
      default = domain.admittedValues.head,
      domain = domain,
      sourceLocation = Some(location("src/GenericBlock.scala", 3))
    )

  private def baseDesign(
      moduleName: String = "GenericBlock",
      sourcePath: String = "src/GenericBlock.scala",
      aliasType: PackedType = defaultAliasType,
      sourceType: PackedType = defaultSourceType
  ): Design = {
    val root = Scope(
      rootScopeId,
      parent = None,
      kind = ScopeKind.Module,
      label = Some("root"),
      sourceLocation = Some(location(sourcePath, 5))
    )
    val source = Declaration(
      sourceId,
      rootScopeId,
      DeclarationKind.Port(PortDirection.Input),
      Some(sourceType),
      NameOrigin.Explicit("source"),
      Some(location(sourcePath, 10)),
      Observability(complete = true, externallyVisible = true)
    )
    val alias = Declaration(
      aliasId,
      rootScopeId,
      DeclarationKind.InternalCombinational,
      Some(aliasType),
      NameOrigin.Unnamed,
      Some(location(sourcePath, 11)),
      Observability.Unobserved
    )
    val sink = Declaration(
      sinkId,
      rootScopeId,
      DeclarationKind.Port(PortDirection.Output),
      Some(aliasType),
      NameOrigin.Explicit("sink"),
      Some(location(sourcePath, 12)),
      Observability(complete = true, externallyVisible = true)
    )
    val aliasDriver = Driver(
      aliasDriverId,
      rootScopeId,
      aliasId,
      DriverKind.Continuous,
      DriverCoverage.FullObject,
      RtlExpr.Ref(
        sourceReferenceId,
        sourceId,
        rootScopeId,
        Some(location(sourcePath, 14))
      ),
      Some(location(sourcePath, 14))
    )
    val sinkDriver = Driver(
      sinkDriverId,
      rootScopeId,
      sinkId,
      DriverKind.Continuous,
      DriverCoverage.FullObject,
      RtlExpr.Ref(
        aliasReferenceId,
        aliasId,
        rootScopeId,
        Some(location(sourcePath, 15))
      ),
      Some(location(sourcePath, 15))
    )
    val module = Module(
      moduleId,
      moduleName,
      parameters = Vector(
        BooleanParameter(
          featureParameterId,
          "FEATURE",
          default = false,
          domain = BooleanParameterDomain(Vector(true, false)),
          sourceLocation = Some(location(sourcePath, 4))
        ),
        integerParameter(widthParameterId, "WIDTH")
      ),
      scopes = Vector(root),
      generateIndices = Vector.empty,
      declarations = Vector(sink, alias, source),
      drivers = Vector(sinkDriver, aliasDriver),
      sourceLocation = Some(location(sourcePath, 2))
    )
    Design(
      CanonicalIrSchema.schemaVersion,
      CanonicalIrSchema.stage,
      moduleId,
      Vector(module)
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

  private def bound(design: Design): CanonicalIrPassView =
    CanonicalIrPassAdapter.bind(design) match {
      case Right(value) => value
      case Left(failure) => fail(failure.diagnostics.values.mkString("\n"))
    }

  private def assessment(
      design: Design,
      configuration: AliasSafetyConfiguration = AliasSafetyConfiguration()
  ): AliasSafetyAssessment = {
    val view = bound(design)
    WireAliasSafetyGate.assess(view.module(moduleId).get, aliasId, configuration)
  }

  private def rejectionCodes(value: AliasSafetyAssessment): Set[String] =
    value.violations.map(_.code).toSet

  test("eligible direct alias proves semantic packed-type equality over the complete domain") {
    val value = assessment(baseDesign())

    value.isEligible shouldBe true
    value.sourceSymbol shouldBe Some(sourceId)
    value.violations shouldBe empty
    value.typeEvidence shouldBe Some(
      PackedTypeEquivalenceEvidence(
        checkedBindings = 4,
        minimumWidth = BigInt(1),
        maximumWidth = BigInt(8),
        domainDigest = value.typeEvidence.get.domainDigest
      )
    )
    value.typeEvidence.get.domainDigest should fullyMatch regex "[0-9a-f]{64}"
  }

  test("one mismatching admitted binding rejects otherwise compatible packed types") {
    val conditionalType = defaultAliasType.copy(
      width = IntExpr.Select(
        BoolExpr.ParameterRef(featureParameterId),
        IntExpr.ParameterRef(widthParameterId),
        IntExpr.Add(IntExpr.ParameterRef(widthParameterId), IntExpr.Literal(BigInt(1)))
      )
    )
    val value = assessment(baseDesign(aliasType = conditionalType))

    value.isEligible shouldBe false
    rejectionCodes(value) should contain(AliasSafetyReason.PackedWidthDomainMismatch)
    value.violations
      .find(_.code == AliasSafetyReason.PackedWidthDomainMismatch)
      .get
      .message should include("boolean:parameter.feature=false")
    value.typeEvidence shouldBe None
  }

  test("signedness and value semantics are independently exact") {
    val signedSource = defaultSourceType.copy(signedness = Signedness.Signed)
    rejectionCodes(assessment(baseDesign(sourceType = signedSource))) should contain(
      AliasSafetyReason.PackedSignednessMismatch
    )

    val integerSource = defaultSourceType.copy(
      valueSemantics = PackedValueSemantics.UnsignedInteger
    )
    rejectionCodes(assessment(baseDesign(sourceType = integerSource))) should contain(
      AliasSafetyReason.PackedValueSemanticsMismatch
    )
  }

  test("driver ownership coverage and direct-reference exclusions fail closed") {
    val noDriver = updateModule(baseDesign()) { module =>
      module.copy(drivers = module.drivers.filterNot(_.id == aliasDriverId))
    }
    rejectionCodes(assessment(noDriver)) should contain(AliasSafetyReason.DriverCardinality)

    val duplicateDriver = updateModule(baseDesign()) { module =>
      module.copy(
        drivers = module.drivers :+ module.drivers
          .find(_.id == aliasDriverId)
          .get
          .copy(
            id = DriverId.unsafe("driver.alias.second"),
            value = RtlExpr.Ref(
              ReferenceId.unsafe("reference.alias.source.second"),
              sourceId,
              rootScopeId
            )
          )
      )
    }
    rejectionCodes(assessment(duplicateDriver)) should contain(
      AliasSafetyReason.DriverCardinality
    )

    val procedural = updateDriver(baseDesign(), aliasDriverId)(
      _.copy(kind = DriverKind.Procedural)
    )
    rejectionCodes(assessment(procedural)) should contain(
      AliasSafetyReason.DriverNotContinuous
    )

    val partial = updateDriver(baseDesign(), aliasDriverId)(
      _.copy(coverage = DriverCoverage.Partial)
    )
    rejectionCodes(assessment(partial)) should contain(
      AliasSafetyReason.DriverNotFullObject
    )

    val expression = updateDriver(baseDesign(), aliasDriverId)(
      _.copy(
        value = RtlExpr.Binary(
          RtlBinaryOperator.BitwiseOr,
          RtlExpr.Ref(sourceReferenceId, sourceId, rootScopeId),
          RtlExpr.Literal(BigInt(0), 1)
        )
      )
    )
    rejectionCodes(assessment(expression)) should contain(
      AliasSafetyReason.DriverNotDirectReference
    )
  }

  test("every retained observability contract blocks elimination") {
    val cases = Vector[(Observability, String)](
      Observability(complete = true, externallyVisible = true) -> AliasSafetyReason.ExternallyVisible,
      Observability(complete = true, keep = true) -> AliasSafetyReason.Keep,
      Observability(complete = true, dontTouch = true) -> AliasSafetyReason.DontTouch,
      Observability(complete = true, probe = true) -> AliasSafetyReason.Probe,
      Observability(complete = true, preserve = true) -> AliasSafetyReason.Preserve,
      Observability(complete = true, publicExport = true) -> AliasSafetyReason.PublicExport,
      Observability(complete = true, blackBoxBoundary = true) -> AliasSafetyReason.BlackBoxBoundary,
      Observability(complete = true, hierarchyBoundary = true) -> AliasSafetyReason.HierarchyBoundary
    )

    cases.foreach { case (observability, expectedCode) =>
      val design = updateDeclaration(baseDesign(), aliasId)(
        _.copy(observability = observability)
      )
      withClue(expectedCode) {
        rejectionCodes(assessment(design)) should contain(expectedCode)
      }
    }
  }

  test("declaration and assignment comments and attributes cannot be discarded") {
    val attribute = IrAttribute(
      "preserve_point",
      Some("true"),
      AttributeKind.Semantic,
      Some(location("src/GenericBlock.scala", 11))
    )
    val comment = IrComment(
      "required debug contract",
      Some(location("src/GenericBlock.scala", 11))
    )
    val declarationContracts = updateDeclaration(baseDesign(), aliasId)(
      _.copy(attributes = Vector(attribute), comments = Vector(comment))
    )
    rejectionCodes(assessment(declarationContracts)) should contain allOf (
      AliasSafetyReason.DeclarationAttributes,
      AliasSafetyReason.DeclarationComments
    )

    val driverContracts = updateDriver(baseDesign(), aliasDriverId)(
      _.copy(attributes = Vector(attribute), comments = Vector(comment))
    )
    rejectionCodes(assessment(driverContracts)) should contain allOf (
      AliasSafetyReason.DriverAttributes,
      AliasSafetyReason.DriverComments
    )
  }

  test("clock reset memory inout and instance-boundary sources are excluded") {
    val sourceKinds = Vector(
      DeclarationKind.Memory,
      DeclarationKind.Clock,
      DeclarationKind.Reset,
      DeclarationKind.InstanceBoundary,
      DeclarationKind.Port(PortDirection.InOut)
    )

    sourceKinds.foreach { kind =>
      val design = updateDeclaration(baseDesign(), sourceId)(_.copy(kind = kind))
      withClue(kind.label) {
        rejectionCodes(assessment(design)) should contain(
          AliasSafetyReason.SourceKindExcluded
        )
      }
    }
  }

  test("bidirectional memory-port and instance-port consumers remain observable") {
    val useKinds = Vector(
      DriverKind.Bidirectional -> AliasSafetyReason.BidirectionalUse,
      DriverKind.MemoryPort -> AliasSafetyReason.MemoryPortUse,
      DriverKind.InstancePort -> AliasSafetyReason.InstanceBoundaryUse
    )

    useKinds.foreach { case (kind, code) =>
      val design = updateDriver(baseDesign(), sinkDriverId)(_.copy(kind = kind))
      withClue(kind.label) {
        rejectionCodes(assessment(design)) should contain(code)
      }
    }
  }

  test("clock reset and tri-state control uses fail closed without name inference") {
    val explicitControlKinds = Vector(
      DeclarationKind.Clock -> AliasSafetyReason.ClockUse,
      DeclarationKind.Reset -> AliasSafetyReason.ResetUse,
      DeclarationKind.Port(PortDirection.InOut) -> AliasSafetyReason.TriStateControlUse
    )

    explicitControlKinds.foreach { case (kind, code) =>
      val design = updateDeclaration(baseDesign(), sinkId)(_.copy(kind = kind))
      withClue(kind.label) {
        rejectionCodes(assessment(design)) should contain(code)
      }
    }

    val hiddenTriStateControl = updateDriver(baseDesign(), sinkDriverId) { driver =>
      driver.copy(
        kind = DriverKind.Bidirectional,
        value = RtlExpr.Ref(
          ReferenceId.unsafe("reference.sink.source.bidirectional"),
          sourceId,
          rootScopeId
        )
      )
    }
    rejectionCodes(assessment(hiddenTriStateControl)) should contain(
      AliasSafetyReason.TriStateControlUse
    )
  }

  test("procedural contexts are rejected until clock and reset roles are explicit") {
    val proceduralConsumer = updateDriver(baseDesign(), sinkDriverId)(
      _.copy(kind = DriverKind.Procedural)
    )

    rejectionCodes(assessment(proceduralConsumer)) should contain(
      AliasSafetyReason.ControlUseUnproven
    )
  }

  test("replacement is rejected when the direct source is not visible at an alias use") {
    val design = updateModule(baseDesign()) { module =>
      val inner = Scope(
        innerScopeId,
        parent = Some(rootScopeId),
        kind = ScopeKind.Block,
        label = Some("producer")
      )
      val movedSource = module.declarations.map {
        case declaration if declaration.id == sourceId =>
          declaration.copy(
            owner = innerScopeId,
            kind = DeclarationKind.InternalCombinational,
            observability = Observability.Unobserved
          )
        case declaration => declaration
      }
      val movedAliasDriver = module.drivers.map {
        case driver if driver.id == aliasDriverId =>
          driver.copy(
            owner = innerScopeId,
            value = RtlExpr.Ref(sourceReferenceId, sourceId, innerScopeId)
          )
        case driver => driver
      }
      module.copy(
        scopes = module.scopes :+ inner,
        declarations = movedSource,
        drivers = movedAliasDriver
      )
    }

    rejectionCodes(assessment(design)) should contain(
      AliasSafetyReason.IllegalScopeReplacement
    )
  }

  test("continuous dependency cycles are rejected and registered feedback fails closed on control roles") {
    val continuousCycle = updateModule(baseDesign()) { module =>
      val declarations = module.declarations.map {
        case declaration if declaration.id == sourceId =>
          declaration.copy(
            kind = DeclarationKind.InternalCombinational,
            observability = Observability.Unobserved
          )
        case declaration => declaration
      }
      val sourceDriver = Driver(
        sourceDriverId,
        rootScopeId,
        sourceId,
        DriverKind.Continuous,
        DriverCoverage.FullObject,
        RtlExpr.Ref(
          ReferenceId.unsafe("reference.source.alias"),
          aliasId,
          rootScopeId
        )
      )
      module.copy(declarations = declarations, drivers = module.drivers :+ sourceDriver)
    }
    rejectionCodes(assessment(continuousCycle)) should contain(
      AliasSafetyReason.CombinationalCycle
    )

    val registeredFeedback = updateModule(baseDesign()) { module =>
      val declarations = module.declarations.map {
        case declaration if declaration.id == sourceId =>
          declaration.copy(
            kind = DeclarationKind.Register,
            observability = Observability.Unobserved
          )
        case declaration => declaration
      }
      val sourceDriver = Driver(
        sourceDriverId,
        rootScopeId,
        sourceId,
        DriverKind.Procedural,
        DriverCoverage.FullObject,
        RtlExpr.Ref(
          ReferenceId.unsafe("reference.source.alias.registered"),
          aliasId,
          rootScopeId
        )
      )
      module.copy(declarations = declarations, drivers = module.drivers :+ sourceDriver)
    }
    val registeredAssessment = assessment(registeredFeedback)
    rejectionCodes(registeredAssessment) should not contain (
      AliasSafetyReason.CombinationalCycle
    )
    rejectionCodes(registeredAssessment) should contain(
      AliasSafetyReason.ControlUseUnproven
    )
  }

  test("exact domain expansion is bounded and fails closed before partial sampling") {
    val secondDomain = IntegerParameterDomain(
      minimum = BigInt(1),
      maximum = BigInt(4),
      admittedValues = Vector(BigInt(1), BigInt(2), BigInt(3), BigInt(4))
    )
    val expression = IntExpr.Add(
      IntExpr.ParameterRef(widthParameterId),
      IntExpr.ParameterRef(secondWidthParameterId)
    )
    val design = updateModule(
      baseDesign(
        aliasType = defaultAliasType.copy(width = expression),
        sourceType = defaultSourceType.copy(width = IntExpr.Add(expression, IntExpr.Literal(0)))
      )
    ) { module =>
      module.copy(
        parameters = module.parameters :+ integerParameter(
          secondWidthParameterId,
          "SECOND_WIDTH",
          secondDomain
        )
      )
    }

    val value = assessment(
      design,
      AliasSafetyConfiguration(maximumDomainBindings = 8)
    )
    rejectionCodes(value) should contain(AliasSafetyReason.DomainExpansionLimit)
    value.typeEvidence shouldBe None
  }

  test("generate-index domains are enumerated completely") {
    val generatedWidth = IntExpr.Add(
      IntExpr.GenerateIndexRef(generateIndexId),
      IntExpr.Literal(BigInt(1))
    )
    val design: Design = updateModule(
      baseDesign(
        aliasType = defaultAliasType.copy(width = generatedWidth),
        sourceType = defaultSourceType.copy(
          width = IntExpr.Add(generatedWidth, IntExpr.Literal(BigInt(0)))
        )
      )
    ) { module =>
      val generateScope = Scope(
        innerScopeId,
        parent = Some(rootScopeId),
        kind = ScopeKind.Generate,
        label = Some("lane")
      )
      val movedDeclarations = module.declarations.map(
        _.copy(owner = innerScopeId)
      )
      val movedDrivers = module.drivers.map { driver =>
        driver.copy(
          owner = innerScopeId,
          value = driver.value match {
            case reference: RtlExpr.Ref => reference.copy(owner = innerScopeId)
            case other                  => other
          }
        )
      }
      module.copy(
        scopes = module.scopes :+ generateScope,
        generateIndices = Vector(
          GenerateIndex(
            generateIndexId,
            innerScopeId,
            "lane",
            minimum = BigInt(0),
            maximum = BigInt(3)
          )
        ),
        declarations = movedDeclarations,
        drivers = movedDrivers
      )
    }

    val value = assessment(design)
    value.isEligible shouldBe true
    value.typeEvidence.get.checkedBindings shouldBe 4
    value.typeEvidence.get.minimumWidth shouldBe BigInt(1)
    value.typeEvidence.get.maximumWidth shouldBe BigInt(4)
  }

  test("complex integer and Boolean width algebra is evaluated without structural shortcuts") {
    val selected = IntExpr.Select(
      BoolExpr.And(
        BoolExpr.ParameterRef(featureParameterId),
        BoolExpr.IsPow2(IntExpr.ParameterRef(widthParameterId))
      ),
      IntExpr.Add(
        IntExpr.AddressWidth(IntExpr.ParameterRef(widthParameterId)),
        IntExpr.Literal(BigInt(1))
      ),
      IntExpr.Add(
        IntExpr.Modulo(
          IntExpr.Multiply(IntExpr.ParameterRef(widthParameterId), IntExpr.Literal(BigInt(3))),
          IntExpr.Literal(BigInt(5))
        ),
        IntExpr.Literal(BigInt(1))
      )
    )
    val aliasType = defaultAliasType.copy(width = selected)
    val sourceType = defaultSourceType.copy(
      width = IntExpr.Max(selected, IntExpr.Min(selected, selected))
    )
    val value = assessment(baseDesign(aliasType = aliasType, sourceType = sourceType))

    value.isEligible shouldBe true
    value.typeEvidence.get.checkedBindings shouldBe 8
  }

  test("analysis is deterministic under canonical reordering and idempotent on repeat") {
    val originalView = bound(baseDesign())
    val originalDesign = originalView.design
    val first = WireAliasSafetyGate.analyze(originalView)
    val second = WireAliasSafetyGate.analyze(originalView)

    first shouldBe second
    first.semanticDigest shouldBe second.semanticDigest
    originalView.design shouldBe originalDesign
    first.assessments.map(_.aliasSymbol) shouldBe Vector(aliasId)

    val reordered = updateModule(baseDesign()) { module =>
      module.copy(
        parameters = module.parameters.reverse,
        scopes = module.scopes.reverse,
        declarations = module.declarations.reverse,
        drivers = module.drivers.reverse
      )
    }
    val reorderedReport = WireAliasSafetyGate.analyze(bound(reordered))
    reorderedReport shouldBe first
    reorderedReport.semanticDigest shouldBe first.semanticDigest
  }

  test("component names and source paths cannot select eligibility") {
    val libraryNamed = WireAliasSafetyGate.analyze(
      bound(baseDesign(moduleName = "LibraryNamedBlock", sourcePath = "src/LibraryNamedBlock.scala"))
    )
    val unrelated = WireAliasSafetyGate.analyze(
      bound(baseDesign(moduleName = "UnrelatedUnit", sourcePath = "other/UnrelatedUnit.scala"))
    )

    libraryNamed.eligible.map(value => (value.aliasSymbol, value.sourceSymbol)) shouldBe
      unrelated.eligible.map(value => (value.aliasSymbol, value.sourceSymbol))
    libraryNamed.assessments.flatMap(_.violations.map(_.code)) shouldBe
      unrelated.assessments.flatMap(_.violations.map(_.code))
    libraryNamed.semanticDigest shouldBe unrelated.semanticDigest
  }

  test("non-wire candidates are rejected when assessed directly") {
    val design = updateDeclaration(baseDesign(), aliasId)(
      _.copy(kind = DeclarationKind.Register)
    )
    rejectionCodes(assessment(design)) should contain(
      AliasSafetyReason.AliasNotInternalCombinational
    )
  }

  test("multiple rejection reasons and reports have stable canonical order") {
    val design = updateDeclaration(baseDesign(), aliasId) { declaration =>
      declaration.copy(
        observability = Observability(
          complete = true,
          keep = true,
          probe = true,
          preserve = true
        ),
        comments = Vector(IrComment("retained"))
      )
    }
    val value = assessment(design)

    value.violations.map(_.code) shouldBe Vector(
      AliasSafetyReason.Keep,
      AliasSafetyReason.Probe,
      AliasSafetyReason.Preserve,
      AliasSafetyReason.DeclarationComments
    )
    WireAliasSafetyReport(Vector(value, value)).normalized.semanticDigest shouldBe
      WireAliasSafetyReport(Vector(value, value)).normalized.semanticDigest
  }

  test("a sibling-scope source cannot leak through an outer alias") {
    val design = updateModule(baseDesign()) { module =>
      val inner = Scope(
        innerScopeId,
        parent = Some(rootScopeId),
        kind = ScopeKind.Block,
        label = Some("producer")
      )
      val sibling = Scope(
        siblingScopeId,
        parent = Some(rootScopeId),
        kind = ScopeKind.Block,
        label = Some("consumer")
      )
      val declarations = module.declarations.map {
        case value if value.id == sourceId =>
          value.copy(
            owner = innerScopeId,
            kind = DeclarationKind.InternalCombinational,
            observability = Observability.Unobserved
          )
        case value if value.id == sinkId => value.copy(owner = siblingScopeId)
        case value                       => value
      }
      val drivers = module.drivers.map {
        case value if value.id == aliasDriverId =>
          value.copy(
            owner = innerScopeId,
            value = RtlExpr.Ref(sourceReferenceId, sourceId, innerScopeId)
          )
        case value if value.id == sinkDriverId =>
          value.copy(
            owner = siblingScopeId,
            value = RtlExpr.Ref(aliasReferenceId, aliasId, siblingScopeId)
          )
        case value => value
      }
      module.copy(
        scopes = module.scopes ++ Vector(inner, sibling),
        declarations = declarations,
        drivers = drivers
      )
    }

    rejectionCodes(assessment(design)) should contain(
      AliasSafetyReason.IllegalScopeReplacement
    )
  }
}
