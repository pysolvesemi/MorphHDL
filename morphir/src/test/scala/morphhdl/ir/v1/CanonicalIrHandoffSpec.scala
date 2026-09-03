package morphhdl.ir.v1

import org.scalatest.funsuite.AnyFunSuite

final class CanonicalIrHandoffSpec extends AnyFunSuite {
  private val moduleId = ModuleId.unsafe("module.fixture")
  private val scopeId = ScopeId.unsafe("scope.fixture")
  private val inputId = SymbolId.unsafe("symbol.input")
  private val outputId = SymbolId.unsafe("symbol.output")
  private val driverId = DriverId.unsafe("driver.output")
  private val secondDriverId = DriverId.unsafe("driver.output.second")
  private val parameterId = ParameterId.unsafe("parameter.width")

  private def validDesign: Design =
    Design(
      version = CanonicalIrSchema.schemaVersion,
      stage = CanonicalIrSchema.stage,
      top = moduleId,
      modules = Vector(
        Module(
          id = moduleId,
          logicalName = "GenericFixture",
          parameters = Vector.empty,
          scopes = Vector(Scope(scopeId, None, ScopeKind.Module)),
          generateIndices = Vector.empty,
          declarations = Vector(
            Declaration(
              id = inputId,
              owner = scopeId,
              kind = DeclarationKind.Port(PortDirection.Input),
              packedType = Some(
                PackedType(
                  IntExpr.Literal(BigInt(1)),
                  Signedness.Unsigned,
                  PackedValueSemantics.Boolean
                )
              ),
              nameOrigin = NameOrigin.Explicit("input"),
              sourceLocation = None,
              observability = Observability(
                complete = true,
                externallyVisible = true
              )
            )
          ),
          drivers = Vector.empty
        )
      )
    )

  private def assertProfileShapeRejected(label: String, design: Design): Unit = {
    val validated = CanonicalIrValidator.validate(design) match {
      case Right(value) => value
      case Left(diagnostics) =>
        fail(s"$label was not a generally valid canonical design:\n${diagnostics.values.mkString("\n")}")
    }
    val failure = CanonicalIrHandoff.fromValidated(validated) match {
      case Left(value) => value
      case Right(_) => fail(s"expected the simple-wire profile to reject $label")
    }
    assert(failure.code == CanonicalIrHandoffFailureCode.ProfileShapeMismatch)
  }

  test("production handoff exposes only a validated normalized v1 snapshot") {
    val handoff = CanonicalIrHandoff.create(validDesign) match {
      case Right(value) => value
      case Left(failure) => fail(failure.toString)
    }

    assert(handoff.profile == CanonicalIrProfile.SimpleWireAssignmentsV1)
    assert(handoff.completeFacets == CanonicalIrHandoff.productionFacets)
    assert(handoff.design eq handoff.validated.value)
    assert(handoff.design.version == CanonicalIrSchema.schemaVersion)
    assert(handoff.design.stage == IrStage.PostParameterizationPreEmission)
  }

  test("handoff refuses to overstate an incomplete bounded producer profile") {
    val incomplete = CanonicalIrHandoff.productionFacets -
      CanonicalIrFacet.ReferenceOccurrences
    val failure = CanonicalIrHandoff.create(
      validDesign,
      completeFacets = incomplete
    ) match {
      case Left(value) => value
      case Right(_)    => fail("expected missing completeness facet to fail")
    }

    assert(failure.code == CanonicalIrHandoffFailureCode.FacetMissing)
    assert(failure.detail.contains(CanonicalIrFacet.ReferenceOccurrences.id))
  }

  test("handoff retains canonical validator diagnostics for a wrong stage") {
    val failure = CanonicalIrHandoff.create(validDesign.copy(stage = null)) match {
      case Left(value: CanonicalIrHandoffFailure.Validation) => value
      case Left(other) => fail(s"expected validation failure, received $other")
      case Right(_)    => fail("expected wrong stage to fail")
    }

    assert(failure.code == CanonicalIrHandoffFailureCode.ValidationFailed)
    assert(failure.diagnostics.codes.contains(IrDiagnosticCode.StageMismatch))
  }

  test("production profile rejects generally valid snapshots outside its bounded shape") {
    val base = validDesign
    val module = base.modules.head
    val output = Declaration(
      id = outputId,
      owner = scopeId,
      kind = DeclarationKind.Port(PortDirection.Output),
      packedType = Some(
        PackedType(
          IntExpr.Literal(BigInt(1)),
          Signedness.Unsigned,
          PackedValueSemantics.Boolean
        )
      ),
      nameOrigin = NameOrigin.Explicit("output"),
      sourceLocation = None,
      observability = Observability(complete = true, externallyVisible = true)
    )
    val unsupportedExpression = RtlExpr.Binary(
      RtlBinaryOperator.BitwiseAnd,
      RtlExpr.Literal(BigInt(0), width = 1),
      RtlExpr.Literal(BigInt(1), width = 1)
    )
    val binaryDesign = base.copy(
      modules = Vector(
        module.copy(
          declarations = module.declarations :+ output,
          drivers = Vector(
            Driver(
              id = driverId,
              owner = scopeId,
              target = outputId,
              kind = DriverKind.Continuous,
              coverage = DriverCoverage.FullObject,
              value = unsupportedExpression
            )
          )
        )
      )
    )
    val validated = CanonicalIrValidator.validate(binaryDesign) match {
      case Right(value) => value
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }

    val failure = CanonicalIrHandoff.fromValidated(validated) match {
      case Left(value) => value
      case Right(_) => fail("expected the simple-wire profile to reject an operator")
    }

    assert(failure.code == CanonicalIrHandoffFailureCode.ProfileShapeMismatch)
    assert(failure.detail.contains("direct references or exact literals"))
  }

  test("production profile rejects procedural drivers and nested scopes") {
    val base = validDesign
    val module = base.modules.head
    val procedural = module.copy(
      drivers = Vector(
        Driver(
          id = driverId,
          owner = scopeId,
          target = inputId,
          kind = DriverKind.Procedural,
          coverage = DriverCoverage.FullObject,
          value = RtlExpr.Literal(BigInt(0), width = 1)
        )
      )
    )
    val nested = module.copy(
      scopes = module.scopes :+ Scope(
        ScopeId.unsafe("scope.fixture.block"),
        Some(scopeId),
        ScopeKind.Block
      )
    )

    Vector(
      base.copy(modules = Vector(procedural)),
      base.copy(modules = Vector(nested))
    ).foreach { design =>
      val failure = CanonicalIrHandoff.create(design) match {
        case Left(value) => value
        case Right(_) => fail("expected the simple-wire profile shape to reject the snapshot")
      }
      assert(failure.code == CanonicalIrHandoffFailureCode.ProfileShapeMismatch)
    }
  }

  test("production profile admits only producer-representable declarations and parameters") {
    val base = validDesign
    val module = base.modules.head
    val declaration = module.declarations.head
    val booleanParameter = BooleanParameter(
      parameterId,
      "FEATURE",
      default = false,
      domain = BooleanParameterDomain(Vector(false, true))
    )
    val unusedNegativeParameter = IntegerParameter(
      parameterId,
      "WIDTH",
      default = BigInt(-1),
      domain = IntegerParameterDomain(
        BigInt(-2),
        BigInt(-1),
        Vector(BigInt(-2), BigInt(-1))
      )
    )
    val directWidthParameter = IntegerParameter(
      parameterId,
      "WIDTH",
      default = BigInt(2),
      domain = IntegerParameterDomain(BigInt(2), BigInt(2), Vector(BigInt(2)))
    )
    val cases = Vector(
      "blackbox observability" -> module.copy(
        declarations = Vector(
          declaration.copy(
            observability = declaration.observability.copy(blackBoxBoundary = true)
          )
        )
      ),
      "a non-external port" -> module.copy(
        declarations = Vector(
          declaration.copy(
            observability = declaration.observability.copy(
              externallyVisible = false
            )
          )
        )
      ),
      "a Boolean parameter" -> module.copy(parameters = Vector(booleanParameter)),
      "an unused integer parameter" -> module.copy(
        parameters = Vector(unusedNegativeParameter)
      ),
      "a signed bit-vector declaration" -> module.copy(
        declarations = Vector(
          declaration.copy(
            packedType = Some(
              PackedType(
                IntExpr.Literal(BigInt(1)),
                Signedness.Signed,
                PackedValueSemantics.BitVector
              )
            )
          )
        )
      ),
      "a compound packed width" -> module.copy(
        parameters = Vector(directWidthParameter),
        declarations = Vector(
          declaration.copy(
            packedType = Some(
              PackedType(
                IntExpr.Add(
                  IntExpr.ParameterRef(parameterId),
                  IntExpr.Literal(BigInt(0))
                ),
                Signedness.Unsigned,
                PackedValueSemantics.BitVector
              )
            )
          )
        )
      )
    )

    cases.foreach { case (label, rejectedModule) =>
      assertProfileShapeRejected(label, base.copy(modules = Vector(rejectedModule)))
    }
  }

  test("production profile admits only exact writable full-object assignments") {
    val base = validDesign
    val module = base.modules.head
    val boolType = module.declarations.head.packedType.get
    val output = Declaration(
      id = outputId,
      owner = scopeId,
      kind = DeclarationKind.Port(PortDirection.Output),
      packedType = Some(boolType),
      nameOrigin = NameOrigin.Explicit("output"),
      sourceLocation = None,
      observability = Observability(complete = true, externallyVisible = true)
    )
    def driver(
        id: DriverId,
        target: SymbolId,
        value: RtlExpr,
        coverage: DriverCoverage = DriverCoverage.FullObject
    ): Driver = Driver(
      id = id,
      owner = scopeId,
      target = target,
      kind = DriverKind.Continuous,
      coverage = coverage,
      value = value
    )
    val exactZero = RtlExpr.Literal(BigInt(0), width = 1)
    val exactOne = RtlExpr.Literal(BigInt(1), width = 1)
    val widerOutput = output.copy(
      packedType = Some(
        PackedType(
          IntExpr.Literal(BigInt(2)),
          Signedness.Unsigned,
          PackedValueSemantics.BitVector
        )
      )
    )
    val cases = Vector(
      "an input target" -> module.copy(
        drivers = Vector(driver(driverId, inputId, exactZero))
      ),
      "repeated targets" -> module.copy(
        declarations = module.declarations :+ output,
        drivers = Vector(
          driver(driverId, outputId, exactZero),
          driver(secondDriverId, outputId, exactOne)
        )
      ),
      "partial coverage" -> module.copy(
        declarations = module.declarations :+ output,
        drivers = Vector(
          driver(driverId, outputId, exactZero, DriverCoverage.Partial)
        )
      ),
      "a mismatched direct reference" -> module.copy(
        declarations = module.declarations :+ widerOutput,
        drivers = Vector(
          driver(
            driverId,
            outputId,
            RtlExpr.Ref(
              ReferenceId.unsafe("reference.mismatched"),
              inputId,
              scopeId
            )
          )
        )
      ),
      "an unrepresentable literal" -> module.copy(
        declarations = module.declarations :+ output,
        drivers = Vector(
          driver(
            driverId,
            outputId,
            RtlExpr.Literal(BigInt(1000), width = 1)
          )
        )
      )
    )

    cases.foreach { case (label, rejectedModule) =>
      assertProfileShapeRejected(label, base.copy(modules = Vector(rejectedModule)))
    }
  }

  test("direct references preserve correlation through an identical width parameter") {
    val secondParameterId = ParameterId.unsafe("parameter.width.second")
    val widthParameter = IntegerParameter(
      parameterId,
      "WIDTH",
      default = BigInt(1),
      domain = IntegerParameterDomain(
        BigInt(1),
        BigInt(2),
        Vector(BigInt(1), BigInt(2))
      )
    )
    val secondWidthParameter = widthParameter.copy(
      id = secondParameterId,
      name = "SECOND_WIDTH"
    )
    def declaration(
        id: SymbolId,
        direction: PortDirection,
        width: IntExpr,
        name: String
    ): Declaration = Declaration(
      id = id,
      owner = scopeId,
      kind = DeclarationKind.Port(direction),
      packedType = Some(
        PackedType(
          width,
          Signedness.Unsigned,
          PackedValueSemantics.BitVector
        )
      ),
      nameOrigin = NameOrigin.Explicit(name),
      sourceLocation = None,
      observability = Observability(complete = true, externallyVisible = true)
    )
    val input = declaration(
      inputId,
      PortDirection.Input,
      IntExpr.ParameterRef(parameterId),
      "input"
    )
    val correlatedOutput = declaration(
      outputId,
      PortDirection.Output,
      IntExpr.ParameterRef(parameterId),
      "output"
    )
    val directDriver = Driver(
      id = driverId,
      owner = scopeId,
      target = outputId,
      kind = DriverKind.Continuous,
      coverage = DriverCoverage.FullObject,
      value = RtlExpr.Ref(
        ReferenceId.unsafe("reference.parameterized"),
        inputId,
        scopeId
      )
    )
    val baseModule = validDesign.modules.head.copy(
      parameters = Vector(widthParameter),
      declarations = Vector(input, correlatedOutput),
      drivers = Vector(directDriver)
    )

    CanonicalIrHandoff.create(validDesign.copy(modules = Vector(baseModule))) match {
      case Right(_) => ()
      case Left(failure) =>
        fail(s"identical parameter width references must stay correlated: $failure")
    }

    val independentOutput = correlatedOutput.copy(
      packedType = Some(
        PackedType(
          IntExpr.ParameterRef(secondParameterId),
          Signedness.Unsigned,
          PackedValueSemantics.BitVector
        )
      )
    )
    assertProfileShapeRejected(
      "independently varying width parameters",
      validDesign.copy(
        modules = Vector(
          baseModule.copy(
            parameters = Vector(widthParameter, secondWidthParameter),
            declarations = Vector(input, independentOutput)
          )
        )
      )
    )
  }

  test("handoff retains stable validator diagnostics for a missing design") {
    val failure = CanonicalIrHandoff.create(null) match {
      case Left(value: CanonicalIrHandoffFailure.Validation) => value
      case Left(other) => fail(s"expected validation failure, received $other")
      case Right(_)    => fail("expected missing design to fail")
    }

    assert(failure.code == CanonicalIrHandoffFailureCode.ValidationFailed)
    assert(failure.detail == "canonical IR validation failed")
    assert(failure.diagnostics.codes == Vector(IrDiagnosticCode.DesignMissing))
  }

  test("validated handoff construction fails closed on missing envelope metadata") {
    val validated = CanonicalIrValidator.validate(validDesign) match {
      case Right(value) => value
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }

    val missingValidated = CanonicalIrHandoff.fromValidated(null) match {
      case Left(value) => value
      case Right(_)    => fail("expected missing validated design to fail")
    }
    assert(
      missingValidated.code == CanonicalIrHandoffFailureCode.ValidatedDesignMissing
    )

    val missingProfile = CanonicalIrHandoff.fromValidated(
      validated,
      profile = null
    ) match {
      case Left(value) => value
      case Right(_)    => fail("expected missing profile to fail")
    }
    assert(missingProfile.code == CanonicalIrHandoffFailureCode.ProfileMissing)

    val missingFacets = CanonicalIrHandoff.fromValidated(
      validated,
      completeFacets = null
    ) match {
      case Left(value) => value
      case Right(_)    => fail("expected missing facets to fail")
    }
    assert(missingFacets.code == CanonicalIrHandoffFailureCode.FacetsMissing)

    val nullFacet = CanonicalIrHandoff.fromValidated(
      validated,
      completeFacets = Set[CanonicalIrFacet](null)
    ) match {
      case Left(value) => value
      case Right(_)    => fail("expected null facet to fail")
    }
    assert(nullFacet.code == CanonicalIrHandoffFailureCode.FacetsMissing)
  }
}
