package morphhdl.ir.v1

import org.scalatest.funsuite.AnyFunSuite

final class CanonicalIrV1Spec extends AnyFunSuite {
  private val moduleId = ModuleId.unsafe("module.top")
  private val rootScopeId = ScopeId.unsafe("scope.top")
  private val blockScopeId = ScopeId.unsafe("scope.top.block")
  private val widthParameterId = ParameterId.unsafe("parameter.width")
  private val enableParameterId = ParameterId.unsafe("parameter.enable")
  private val sourceId = SymbolId.unsafe("symbol.source")
  private val aliasId = SymbolId.unsafe("symbol.alias")
  private val sinkId = SymbolId.unsafe("symbol.sink")
  private val aliasDriverId = DriverId.unsafe("driver.alias")
  private val sinkDriverId = DriverId.unsafe("driver.sink")
  private val sourceReferenceId = ReferenceId.unsafe("reference.alias.source")
  private val aliasReferenceId = ReferenceId.unsafe("reference.sink.alias")

  private def location(line: Int, column: Int = 1): SourceLocation =
    SourceLocation("src/CanonicalFixture.scala", line, column)

  private val packedBits = PackedType(
    IntExpr.Select(
      BoolExpr.ParameterRef(enableParameterId),
      IntExpr.ParameterRef(widthParameterId),
      IntExpr.Literal(BigInt(1))
    ),
    Signedness.Unsigned,
    PackedValueSemantics.BitVector
  )

  private def validDesign(): Design = {
    val width = IntegerParameter(
      widthParameterId,
      "WIDTH",
      default = BigInt(8),
      IntegerParameterDomain(
        minimum = BigInt(1),
        maximum = BigInt(16),
        admittedValues = Vector(BigInt(16), BigInt(1), BigInt(8))
      ),
      Some(location(4))
    )
    val enable = BooleanParameter(
      enableParameterId,
      "ENABLE",
      default = true,
      BooleanParameterDomain(Vector(true, false)),
      Some(location(5))
    )
    val root = Scope(
      rootScopeId,
      parent = None,
      ScopeKind.Module,
      label = Some("top"),
      sourceLocation = Some(location(8))
    )
    val block = Scope(
      blockScopeId,
      parent = Some(rootScopeId),
      ScopeKind.Block,
      label = Some("logic"),
      sourceLocation = Some(location(9))
    )
    val source = Declaration(
      sourceId,
      rootScopeId,
      DeclarationKind.Port(PortDirection.Input),
      Some(packedBits),
      NameOrigin.Explicit("source"),
      Some(location(12)),
      Observability(complete = true, externallyVisible = true)
    )
    val alias = Declaration(
      aliasId,
      rootScopeId,
      DeclarationKind.InternalCombinational,
      Some(packedBits),
      NameOrigin.Reflected("alias"),
      Some(location(13)),
      Observability.Unobserved,
      attributes = Vector(
        IrAttribute("zeta", Some("1"), AttributeKind.Backend, Some(location(13, 9))),
        IrAttribute("alpha", None, AttributeKind.Semantic, Some(location(13, 3)))
      ),
      comments = Vector(
        IrComment("later", Some(location(15))),
        IrComment("earlier", Some(location(14)))
      )
    )
    val sink = Declaration(
      sinkId,
      rootScopeId,
      DeclarationKind.Port(PortDirection.Output),
      Some(packedBits),
      NameOrigin.Explicit("sink"),
      Some(location(16)),
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
        Some(location(18, 18))
      ),
      Some(location(18))
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
        Some(location(19, 17))
      ),
      Some(location(19))
    )
    val module = Module(
      moduleId,
      "Top",
      parameters = Vector(width, enable),
      scopes = Vector(block, root),
      generateIndices = Vector.empty,
      declarations = Vector(sink, source, alias),
      drivers = Vector(sinkDriver, aliasDriver),
      sourceLocation = Some(location(3))
    )
    Design(
      CanonicalIrSchema.schemaVersion,
      CanonicalIrSchema.stage,
      moduleId,
      Vector(module)
    )
  }

  private def invalid(design: Design, maxErrors: Int = 256): IrDiagnosticSet =
    CanonicalIrValidator.validate(design, maxErrors) match {
      case Left(diagnostics) => diagnostics
      case Right(_)          => fail("expected canonical IR validation to fail")
    }

  private def validated(design: Design): ValidatedDesign =
    CanonicalIrValidator.validate(design) match {
      case Right(value)      => value
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }

  private def withPackedWidth(design: Design, width: IntExpr): Design = {
    val module = design.modules.head
    val declarations = module.declarations.map { declaration =>
      declaration.copy(
        packedType = declaration.packedType.map(_.copy(width = width))
      )
    }
    design.copy(modules = Vector(module.copy(declarations = declarations)))
  }

  test("a complete post-parameterization graph validates and normalizes") {
    val validated = CanonicalIrValidator.validate(validDesign()) match {
      case Right(value)       => value
      case Left(diagnostics)  => fail(diagnostics.values.mkString("\n"))
    }

    assert(validated.value.version == IrVersion.V1)
    assert(validated.value.stage == IrStage.PostParameterizationPreEmission)
    assert(validated.value.modules.head.parameters.map(_.id.value) == Vector(
      "parameter.enable",
      "parameter.width"
    ))
    assert(validated.value.modules.head.declarations.map(_.id.value) == Vector(
      "symbol.alias",
      "symbol.sink",
      "symbol.source"
    ))
  }

  test("direct references retain occurrence, target, owner, and source identity") {
    val driver = validDesign().modules.head.drivers.find(_.id == aliasDriverId).get
    val reference = driver.value.referenceOccurrences.head

    assert(driver.directReference.contains(sourceId))
    assert(reference.id == sourceReferenceId)
    assert(reference.target == sourceId)
    assert(reference.owner == rootScopeId)
    assert(reference.sourceLocation.contains(location(18, 18)))
  }

  test("unresolved reference targets fail closed at the occurrence location") {
    val design = validDesign()
    val module = design.modules.head
    val missing = SymbolId.unsafe("symbol.missing")
    val drivers = module.drivers.map {
      case driver if driver.id == sinkDriverId =>
        driver.copy(
          value = RtlExpr.Ref(
            aliasReferenceId,
            missing,
            rootScopeId,
            Some(location(19, 17))
          )
        )
      case driver => driver
    }
    val diagnostics = invalid(design.copy(modules = Vector(module.copy(drivers = drivers))))
    val failure = diagnostics.values.find(_.code == IrDiagnosticCode.RtlReferenceUnresolved).get

    assert(failure.location.contains(location(19, 17)))
  }

  test("duplicate declaration and reference identities are rejected") {
    val design = validDesign()
    val module = design.modules.head
    val duplicateSymbol = module.declarations.find(_.id == aliasId).get.copy(id = sourceId)
    val duplicateReferenceDrivers = module.drivers.map {
      case driver if driver.id == sinkDriverId =>
        driver.copy(
          value = RtlExpr.Ref(
            sourceReferenceId,
            aliasId,
            rootScopeId,
            Some(location(19, 17))
          )
        )
      case driver => driver
    }
    val malformed = design.copy(
      modules = Vector(
        module.copy(
          declarations = module.declarations :+ duplicateSymbol,
          drivers = duplicateReferenceDrivers
        )
      )
    )
    val codes = invalid(malformed).codes.toSet

    assert(codes.contains(IrDiagnosticCode.SymbolIdDuplicate))
    assert(codes.contains(IrDiagnosticCode.ReferenceIdDuplicate))
  }

  test("missing packed, naming, and observability metadata fails closed") {
    val design = validDesign()
    val module = design.modules.head
    val malformed = module.declarations.map {
      case declaration if declaration.id == aliasId =>
        declaration.copy(
          packedType = None,
          nameOrigin = NameOrigin.Unknown,
          observability = Observability(complete = false)
        )
      case declaration => declaration
    }
    val diagnostics = invalid(
      design.copy(modules = Vector(module.copy(declarations = malformed)))
    )
    val codes = diagnostics.codes.toSet

    assert(codes.contains(IrDiagnosticCode.PackedTypeMissing))
    assert(codes.contains(IrDiagnosticCode.NameOriginUnknown))
    assert(codes.contains(IrDiagnosticCode.ObservabilityIncomplete))
    assert(
      diagnostics.values
        .filter(diagnostic => codes.contains(diagnostic.code))
        .exists(_.location.contains(location(13)))
    )
  }

  test("packed-type option containers are mandatory even when no type is required") {
    val design = validDesign()
    val module = design.modules.head
    val declarations = module.declarations.map {
      case value if value.id == aliasId =>
        value.copy(
          kind = DeclarationKind.InstanceBoundary,
          packedType = null
        )
      case value => value
    }
    val diagnostics = invalid(
      design.copy(modules = Vector(module.copy(declarations = declarations)))
    )

    assert(diagnostics.codes.contains(IrDiagnosticCode.PackedTypeMissing))
  }

  test("empty or inconsistent parameter domains are rejected") {
    val design = validDesign()
    val module = design.modules.head
    val parameters = module.parameters.map {
      case value: IntegerParameter =>
        value.copy(
          domain = IntegerParameterDomain(BigInt(1), BigInt(16), Vector.empty)
        )
      case value => value
    }
    val codes = invalid(
      design.copy(modules = Vector(module.copy(parameters = parameters)))
    ).codes.toSet

    assert(codes.contains(IrDiagnosticCode.ParameterDomainEmpty))
    assert(codes.contains(IrDiagnosticCode.ParameterDefaultOutsideDomain))
  }

  test("packed value semantics are mandatory and independent of signedness") {
    val design = validDesign()
    val module = design.modules.head
    val malformed = module.declarations.map {
      case declaration if declaration.id == aliasId =>
        declaration.copy(
          packedType = Some(
            PackedType(
              IntExpr.Literal(BigInt(8)),
              Signedness.Unsigned,
              null
            )
          )
        )
      case declaration => declaration
    }
    val diagnostics = invalid(
      design.copy(modules = Vector(module.copy(declarations = malformed)))
    )

    assert(diagnostics.codes.contains(IrDiagnosticCode.PackedValueSemanticsMissing))
    assert(
      diagnostics.values
        .find(_.code == IrDiagnosticCode.PackedValueSemanticsMissing)
        .flatMap(_.location)
        .contains(location(13))
    )
  }

  test("normalization is deterministic without repairing invalid data") {
    val design = validDesign()
    val module = design.modules.head
    val reorderedDeclarations = module.declarations.reverse.map { declaration =>
      declaration.copy(
        attributes = declaration.attributes.reverse,
        comments = declaration.comments.reverse
      )
    }
    val reordered = design.copy(
      modules = Vector(
        module.copy(
          parameters = module.parameters.reverse,
          scopes = module.scopes.reverse,
          declarations = reorderedDeclarations,
          drivers = module.drivers.reverse
        )
      )
    )

    assert(CanonicalIrNormalizer.normalize(design) == CanonicalIrNormalizer.normalize(reordered))
  }

  test("attribute normalization distinguishes absent and present empty values") {
    val design = validDesign()
    val module = design.modules.head
    val attributes = Vector(
      IrAttribute(
        "same",
        None,
        AttributeKind.Semantic,
        Some(location(24))
      ),
      IrAttribute(
        "same",
        Some(""),
        AttributeKind.Semantic,
        Some(location(24))
      )
    )

    def decorated(values: Vector[IrAttribute]): Design =
      design.copy(
        modules = Vector(
          module.copy(
            declarations = module.declarations.map {
              case value if value.id == aliasId =>
                value.copy(attributes = values)
              case value => value
            },
            drivers = module.drivers.map {
              case value if value.id == aliasDriverId =>
                value.copy(attributes = values)
              case value => value
            }
          )
        )
      )

    val forward = validated(decorated(attributes)).value
    val reversed = validated(decorated(attributes.reverse)).value
    assert(forward == reversed)

    val normalizedModule = forward.modules.head
    assert(
      normalizedModule.declarations.find(_.id == aliasId).get.attributes.map(_.value) ==
        Vector(None, Some(""))
    )
    assert(
      normalizedModule.drivers.find(_.id == aliasDriverId).get.attributes.map(_.value) ==
        Vector(None, Some(""))
    )
  }

  test("diagnostics are explicitly bounded and end with a stable truncation marker") {
    val design = validDesign()
    val module = design.modules.head
    val malformed = module.declarations.map {
      case declaration if declaration.id == aliasId =>
        declaration.copy(
          packedType = Some(
            PackedType(
              IntExpr.Literal(BigInt(0)),
              null,
              null
            )
          ),
          nameOrigin = NameOrigin.Unknown,
          observability = Observability(complete = false),
          attributes = Vector(IrAttribute("", Some(""), null, Some(location(13)))),
          comments = Vector(IrComment("", Some(location(13))))
        )
      case declaration => declaration
    }
    val diagnostics = invalid(
      design.copy(modules = Vector(module.copy(declarations = malformed))),
      maxErrors = 3
    )

    assert(diagnostics.size <= 3)
    assert(diagnostics.codes.contains(IrDiagnosticCode.DiagnosticLimitReached))
  }

  test("an invalid diagnostic limit is retained without traversing the design") {
    Vector(validDesign(), null.asInstanceOf[Design]).foreach { design =>
      Vector(0, -1).foreach { limit =>
        CanonicalIrValidator.validate(design, maxErrors = limit) match {
          case Left(diagnostics) =>
            assert(diagnostics.codes == Vector(IrDiagnosticCode.DiagnosticLimitInvalid))
          case Right(_) =>
            fail(s"expected diagnostic limit $limit to fail")
        }
      }
    }
  }

  test("every identity is mandatory and malformed identity values fail closed") {
    val design = validDesign()
    val module = design.modules.head
    val malformedParameters = module.parameters.map {
      case value: IntegerParameter =>
        value.copy(id = null.asInstanceOf[ParameterId])
      case value                   => value
    }
    val malformedScopes = module.scopes.map {
      case value if value.id == blockScopeId =>
        value.copy(id = null.asInstanceOf[ScopeId])
      case value                             => value
    }
    val malformedDeclarations = module.declarations.map {
      case value if value.id == aliasId =>
        value.copy(id = null.asInstanceOf[SymbolId])
      case value                        => value
    }
    val malformedDrivers = module.drivers.map {
      case value if value.id == aliasDriverId =>
        value.copy(id = null.asInstanceOf[DriverId])
      case value if value.id == sinkDriverId =>
        value.copy(
          value = RtlExpr.Ref(
            sourceReferenceId.copy(value = "bad reference id"),
            aliasId,
            rootScopeId,
            Some(location(19, 17))
          )
        )
      case value => value
    }
    val missingIndex = GenerateIndex(
      id = null.asInstanceOf[GenerateIndexId],
      owner = rootScopeId,
      name = "i",
      minimum = BigInt(0),
      maximum = BigInt(1),
      sourceLocation = Some(location(20))
    )
    val anonymousModule = Module(
      id = null.asInstanceOf[ModuleId],
      logicalName = "Anonymous",
      parameters = Vector.empty,
      scopes = Vector(
        Scope(
          ScopeId.unsafe("scope.anonymous"),
          None,
          ScopeKind.Module,
          sourceLocation = Some(location(30))
        )
      ),
      generateIndices = Vector.empty,
      declarations = Vector.empty,
      drivers = Vector.empty,
      sourceLocation = Some(location(29))
    )
    val malformed = design.copy(
      modules = Vector(
        module.copy(
          parameters = malformedParameters,
          scopes = malformedScopes,
          generateIndices = Vector(missingIndex),
          declarations = malformedDeclarations,
          drivers = malformedDrivers
        ),
        anonymousModule
      )
    )
    val codes = invalid(malformed).codes.toSet

    assert(codes.contains(IrDiagnosticCode.ModuleIdMissing))
    assert(codes.contains(IrDiagnosticCode.ParameterIdMissing))
    assert(codes.contains(IrDiagnosticCode.ScopeIdMissing))
    assert(codes.contains(IrDiagnosticCode.GenerateIndexIdMissing))
    assert(codes.contains(IrDiagnosticCode.SymbolIdMissing))
    assert(codes.contains(IrDiagnosticCode.DriverIdMissing))
    assert(codes.contains(IrDiagnosticCode.ReferenceIdInvalid))
  }

  test("malformed identity use sites fail closed without hashing null values") {
    val design = validDesign()
    val module = design.modules.head
    val missingScope = null.asInstanceOf[ScopeId]
    val missingSymbol = null.asInstanceOf[SymbolId]
    val missingParameter = null.asInstanceOf[ParameterId]
    val missingIndex = null.asInstanceOf[GenerateIndexId]
    val missingReference = null.asInstanceOf[ReferenceId]
    val malformedWidth = IntExpr.Select(
      BoolExpr.ParameterRef(missingParameter),
      IntExpr.ParameterRef(missingParameter),
      IntExpr.GenerateIndexRef(missingIndex)
    )
    val scopes = module.scopes.map {
      case value if value.id == blockScopeId =>
        value.copy(parent = Some(missingScope))
      case value => value
    }
    val generateIndex = GenerateIndex(
      GenerateIndexId.unsafe("index.malformed-owner"),
      missingScope,
      "i",
      BigInt(0),
      BigInt(1),
      Some(location(21))
    )
    val declarations = module.declarations.map { value =>
      value.copy(
        owner = missingScope,
        packedType = value.packedType.map(_.copy(width = malformedWidth))
      )
    }
    val drivers = module.drivers.map { value =>
      value.copy(
        owner = missingScope,
        target = missingSymbol,
        value = RtlExpr.Ref(
          missingReference,
          missingSymbol,
          missingScope,
          Some(location(22))
        )
      )
    }
    val malformed = design.copy(
      top = null.asInstanceOf[ModuleId],
      modules = Vector(
        module.copy(
          scopes = scopes,
          generateIndices = Vector(generateIndex),
          declarations = declarations,
          drivers = drivers
        )
      )
    )
    val codes = invalid(malformed).codes.toSet

    assert(codes.contains(IrDiagnosticCode.ModuleIdMissing))
    assert(codes.contains(IrDiagnosticCode.TopModuleUnresolved))
    assert(codes.contains(IrDiagnosticCode.ScopeParentUnresolved))
    assert(codes.contains(IrDiagnosticCode.GenerateIndexOwnerUnresolved))
    assert(codes.contains(IrDiagnosticCode.DeclarationScopeUnresolved))
    assert(codes.contains(IrDiagnosticCode.DriverScopeUnresolved))
    assert(codes.contains(IrDiagnosticCode.DriverTargetUnresolved))
    assert(codes.contains(IrDiagnosticCode.ReferenceIdMissing))
    assert(codes.contains(IrDiagnosticCode.ReferenceOwnerUnresolved))
    assert(codes.contains(IrDiagnosticCode.RtlReferenceUnresolved))
    assert(codes.contains(IrDiagnosticCode.ParameterUnresolved))
    assert(codes.contains(IrDiagnosticCode.GenerateIndexUnresolved))
  }

  test("scope, parameter, and generate identities are unique design-wide") {
    val design = validDesign()
    val module = design.modules.head
    val generateScopeId = ScopeId.unsafe("scope.shared.generate")
    val generateIndexId = GenerateIndexId.unsafe("index.shared")
    val generateScope = Scope(
      generateScopeId,
      Some(rootScopeId),
      ScopeKind.Generate,
      sourceLocation = Some(location(21))
    )
    val generateIndex = GenerateIndex(
      generateIndexId,
      generateScopeId,
      "i",
      BigInt(0),
      BigInt(1),
      Some(location(21, 8))
    )
    val first = module.copy(
      scopes = module.scopes :+ generateScope,
      generateIndices = Vector(generateIndex)
    )
    val sharedWidth = module.parameters.collectFirst {
      case value: IntegerParameter => value
    }.get
    val second = Module(
      ModuleId.unsafe("module.other"),
      "Other",
      parameters = Vector(sharedWidth.copy(name = "OTHER_WIDTH")),
      scopes = Vector(
        Scope(
          rootScopeId,
          None,
          ScopeKind.Module,
          sourceLocation = Some(location(31))
        ),
        generateScope.copy(sourceLocation = Some(location(32)))
      ),
      generateIndices = Vector(generateIndex.copy(sourceLocation = Some(location(32, 8)))),
      declarations = Vector.empty,
      drivers = Vector.empty,
      sourceLocation = Some(location(30))
    )
    val codes = invalid(design.copy(modules = Vector(first, second))).codes.toSet

    assert(codes.contains(IrDiagnosticCode.ParameterIdDuplicate))
    assert(codes.contains(IrDiagnosticCode.ScopeIdDuplicate))
    assert(codes.contains(IrDiagnosticCode.GenerateIndexIdDuplicate))
  }

  test("exact sparse domains do not manufacture a zero divisor") {
    val design = validDesign()
    val module = design.modules.head
    val parameters = module.parameters.map {
      case value: IntegerParameter =>
        value.copy(
          default = BigInt(1),
          domain = IntegerParameterDomain(
            BigInt(-1),
            BigInt(1),
            Vector(BigInt(-1), BigInt(1))
          )
        )
      case value => value
    }
    val expression = IntExpr.Add(
      IntExpr.Divide(
        IntExpr.Literal(BigInt(8)),
        IntExpr.ParameterRef(widthParameterId)
      ),
      IntExpr.Literal(BigInt(9))
    )
    val widened = withPackedWidth(
      design.copy(modules = Vector(module.copy(parameters = parameters))),
      expression
    )

    validated(widened)
  }

  test("exact evaluation preserves repeated-root and conditional correlation") {
    val design = validDesign()
    val module = design.modules.head
    val parameters = module.parameters.map {
      case value: IntegerParameter =>
        value.copy(
          default = BigInt(1),
          domain = IntegerParameterDomain(
            BigInt(-1),
            BigInt(1),
            Vector(BigInt(-1), BigInt(1))
          )
        )
      case value => value
    }
    val reference = IntExpr.ParameterRef(widthParameterId)
    val expression = IntExpr.Select(
      BoolExpr.Equal(reference, reference),
      IntExpr.Multiply(reference, reference),
      IntExpr.Literal(BigInt(0))
    )
    val correlated = withPackedWidth(
      design.copy(modules = Vector(module.copy(parameters = parameters))),
      expression
    )

    validated(correlated)
  }

  test("exact evaluation respects guarded branch correlation") {
    val design = validDesign()
    val module = design.modules.head
    val parameters = module.parameters.map {
      case value: IntegerParameter =>
        value.copy(
          default = BigInt(1),
          domain = IntegerParameterDomain(
            BigInt(0),
            BigInt(1),
            Vector(BigInt(0), BigInt(1))
          )
        )
      case value => value
    }
    val reference = IntExpr.ParameterRef(widthParameterId)
    val expression = IntExpr.Select(
      BoolExpr.NotEqual(reference, IntExpr.Literal(BigInt(0))),
      IntExpr.Divide(IntExpr.Literal(BigInt(8)), reference),
      IntExpr.Literal(BigInt(1))
    )
    val guarded = withPackedWidth(
      design.copy(modules = Vector(module.copy(parameters = parameters))),
      expression
    )

    validated(guarded)
  }

  test("exact evaluation rejects a reachable guarded branch failure") {
    val design = validDesign()
    val module = design.modules.head
    val parameters = module.parameters.map {
      case value: IntegerParameter =>
        value.copy(
          default = BigInt(1),
          domain = IntegerParameterDomain(
            BigInt(0),
            BigInt(1),
            Vector(BigInt(0), BigInt(1))
          )
        )
      case value => value
    }
    val reference = IntExpr.ParameterRef(widthParameterId)
    val expression = IntExpr.Select(
      BoolExpr.Equal(reference, IntExpr.Literal(BigInt(0))),
      IntExpr.Divide(IntExpr.Literal(BigInt(8)), reference),
      IntExpr.Literal(BigInt(1))
    )
    val reachable = withPackedWidth(
      design.copy(modules = Vector(module.copy(parameters = parameters))),
      expression
    )
    val diagnostic = invalid(reachable).values
      .find(_.code == IrDiagnosticCode.IntegerDivisorMayBeZero)
      .get

    assert(diagnostic.path.takeRight(2) == Vector("when-true", "right"))
  }

  test("exact assignment evaluation fails closed at its public case bound") {
    val design = validDesign()
    val module = design.modules.head
    val values = (0 to 256).map(BigInt(_)).toVector
    val widenedWidth = module.parameters.map {
      case value: IntegerParameter =>
        value.copy(
          default = BigInt(8),
          domain = IntegerParameterDomain(BigInt(0), BigInt(256), values)
        )
      case value => value
    }
    val secondId = ParameterId.unsafe("parameter.second")
    val second = IntegerParameter(
      secondId,
      "SECOND",
      default = BigInt(8),
      domain = IntegerParameterDomain(BigInt(0), BigInt(256), values),
      sourceLocation = Some(location(6))
    )
    val expression = IntExpr.Add(
      IntExpr.ParameterRef(widthParameterId),
      IntExpr.ParameterRef(secondId)
    )
    val bounded = withPackedWidth(
      design.copy(
        modules = Vector(module.copy(parameters = widenedWidth :+ second))
      ),
      expression
    )

    val diagnostics = invalid(bounded)
    assert(diagnostics.codes.contains(IrDiagnosticCode.ExactEvaluationLimitReached))
  }

  test("lexical scope and generate-index visibility fail closed") {
    val design = validDesign()
    val module = design.modules.head
    val generateScopeId = ScopeId.unsafe("scope.generate")
    val siblingScopeId = ScopeId.unsafe("scope.sibling")
    val generateIndexId = GenerateIndexId.unsafe("index.generate")
    val generateScope = Scope(
      generateScopeId,
      Some(rootScopeId),
      ScopeKind.Generate,
      sourceLocation = Some(location(21))
    )
    val siblingScope = Scope(
      siblingScopeId,
      Some(rootScopeId),
      ScopeKind.Block,
      sourceLocation = Some(location(22))
    )
    val orphanScope = Scope(
      ScopeId.unsafe("scope.orphan"),
      None,
      ScopeKind.Block,
      sourceLocation = Some(location(23))
    )
    val generateIndex = GenerateIndex(
      generateIndexId,
      generateScopeId,
      "i",
      BigInt(0),
      BigInt(3),
      Some(location(21, 8))
    )
    val wrongOwnerIndex = GenerateIndex(
      GenerateIndexId.unsafe("index.wrong-owner"),
      siblingScopeId,
      "j",
      BigInt(0),
      BigInt(1),
      Some(location(22, 8))
    )
    val declarations = module.declarations.map {
      case value if value.id == aliasId =>
        value.copy(
          owner = siblingScopeId,
          packedType = value.packedType.map(
            _.copy(width = IntExpr.GenerateIndexRef(generateIndexId))
          )
        )
      case value if value.id == sourceId => value.copy(owner = siblingScopeId)
      case value                         => value
    }
    val malformed = design.copy(
      modules = Vector(
        module.copy(
          scopes = module.scopes ++ Vector(generateScope, siblingScope, orphanScope),
          generateIndices = Vector(generateIndex, wrongOwnerIndex),
          declarations = declarations
        )
      )
    )
    val codes = invalid(malformed).codes.toSet

    assert(codes.contains(IrDiagnosticCode.ScopeParentRequired))
    assert(codes.contains(IrDiagnosticCode.GenerateIndexOwnerKindInvalid))
    assert(codes.contains(IrDiagnosticCode.GenerateIndexNotVisible))
    assert(codes.contains(IrDiagnosticCode.DriverTargetNotVisible))
    assert(codes.contains(IrDiagnosticCode.RtlReferenceNotVisible))
  }

  test("driver attributes and comments validate and normalize deterministically") {
    val design = validDesign()
    val module = design.modules.head
    val drivers = module.drivers.map {
      case value if value.id == aliasDriverId =>
        value.copy(
          attributes = Vector(
            IrAttribute("zeta", Some("1"), AttributeKind.Backend, Some(location(18, 9))),
            IrAttribute("alpha", None, AttributeKind.Semantic, Some(location(18, 3)))
          ),
          comments = Vector(
            IrComment("later driver comment", Some(location(20))),
            IrComment("earlier driver comment", Some(location(19)))
          )
        )
      case value => value
    }
    val normalized = validated(
      design.copy(modules = Vector(module.copy(drivers = drivers)))
    ).value.modules.head.drivers.find(_.id == aliasDriverId).get

    assert(normalized.attributes.map(_.name) == Vector("zeta", "alpha"))
    assert(normalized.comments.map(_.text) == Vector(
      "earlier driver comment",
      "later driver comment"
    ))

    val malformedDrivers = drivers.map {
      case value if value.id == aliasDriverId =>
        value.copy(
          attributes = Vector(
            IrAttribute("", None, null, Some(location(18, 3))),
            IrAttribute(
              "bad-value",
              Some(null),
              AttributeKind.Semantic,
              Some(location(18, 4))
            )
          ),
          comments = Vector(IrComment("", Some(location(18, 3))))
        )
      case value => value
    }
    val codes = invalid(
      design.copy(modules = Vector(module.copy(drivers = malformedDrivers)))
    ).codes.toSet
    assert(codes.contains(IrDiagnosticCode.AttributeNameMissing))
    assert(codes.contains(IrDiagnosticCode.AttributeValueMissing))
    assert(codes.contains(IrDiagnosticCode.AttributeKindMissing))
    assert(codes.contains(IrDiagnosticCode.CommentTextMissing))
  }
}
