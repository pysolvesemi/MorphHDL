package morphhdl.ir.v1

/** A declared completeness capability of one production canonical-IR snapshot. */
sealed trait CanonicalIrFacet extends Product with Serializable {
  def id: String
}

object CanonicalIrFacet {
  /** Complete declaration records, including packed types, name origins,
    * observability, attributes, comments and any available source locations.
    */
  case object Declarations extends CanonicalIrFacet {
    override val id: String = "declarations"
  }
  case object ContinuousDrivers extends CanonicalIrFacet {
    override val id: String = "continuous-drivers"
  }
  case object ReferenceOccurrences extends CanonicalIrFacet {
    override val id: String = "reference-occurrences"
  }
  case object TypedParametersAndPackedTypes extends CanonicalIrFacet {
    override val id: String = "typed-parameters-and-packed-types"
  }
  case object NameOrigins extends CanonicalIrFacet {
    override val id: String = "name-origins"
  }
  case object Observability extends CanonicalIrFacet {
    override val id: String = "observability"
  }
}

/** A bounded producer contract, distinct from the canonical schema version. */
sealed trait CanonicalIrProfile extends Product with Serializable {
  def id: String
  def requiredFacets: Set[CanonicalIrFacet]
}

object CanonicalIrProfile {
  /**
    * One flat module containing packed declarations and root-scope, full-object
    * continuous assignments whose values are direct references or literals.
    */
  case object SimpleWireAssignmentsV1 extends CanonicalIrProfile {
    override val id: String = "simple-wire-assignments-v1"
    override val requiredFacets: Set[CanonicalIrFacet] = Set(
      CanonicalIrFacet.Declarations,
      CanonicalIrFacet.ContinuousDrivers,
      CanonicalIrFacet.ReferenceOccurrences,
      CanonicalIrFacet.TypedParametersAndPackedTypes,
      CanonicalIrFacet.NameOrigins,
      CanonicalIrFacet.Observability
    )
  }
}

/** Stable failure codes for construction of the production handoff envelope. */
object CanonicalIrHandoffFailureCode {
  val ValidationFailed = "MORPH-IR-HANDOFF-VALIDATION-FAILED"
  val ValidatedDesignMissing = "MORPH-IR-HANDOFF-VALIDATED-DESIGN-MISSING"
  val ProfileMissing = "MORPH-IR-HANDOFF-PROFILE-MISSING"
  val FacetsMissing = "MORPH-IR-HANDOFF-FACETS-MISSING"
  val FacetMissing = "MORPH-IR-HANDOFF-FACET-MISSING"
  val ProfileShapeMismatch = "MORPH-IR-HANDOFF-PROFILE-SHAPE-MISMATCH"
}

sealed trait CanonicalIrHandoffFailure extends Product with Serializable {
  def code: String
  def detail: String
}

object CanonicalIrHandoffFailure {
  final case class Validation(diagnostics: IrDiagnosticSet)
      extends CanonicalIrHandoffFailure {
    require(diagnostics != null, "canonical IR diagnostics must not be null")
    require(!diagnostics.isEmpty, "canonical IR validation failure requires diagnostics")
    override val code: String = CanonicalIrHandoffFailureCode.ValidationFailed
    override val detail: String = "canonical IR validation failed"
  }

  final case class Contract(code: String, detail: String)
      extends CanonicalIrHandoffFailure {
    require(code != null && code.nonEmpty, "handoff failure code must not be empty")
    require(detail != null && detail.nonEmpty, "handoff failure detail must not be empty")
  }
}

/**
  * Validated, immutable production publication at the canonical v1 handoff.
  *
  * The profile and explicit completeness facets prevent a bounded producer
  * from implying coverage of native constructs it did not capture. Optional
  * pass consumers receive the validator's normalized graph directly; there is
  * no second pass IR and no reconstruction from emitted target text.
  */
final class CanonicalIrHandoff private[v1] (
    val validated: ValidatedDesign,
    val profile: CanonicalIrProfile,
    val completeFacets: Set[CanonicalIrFacet]
) {
  def design: Design = validated.value
}

object CanonicalIrHandoff {
  val productionProfile: CanonicalIrProfile =
    CanonicalIrProfile.SimpleWireAssignmentsV1

  val productionFacets: Set[CanonicalIrFacet] =
    productionProfile.requiredFacets

  def create(
      design: Design,
      profile: CanonicalIrProfile = productionProfile,
      completeFacets: Set[CanonicalIrFacet] = productionFacets,
      maxErrors: Int = CanonicalIrValidator.DefaultMaximumDiagnostics
  ): Either[CanonicalIrHandoffFailure, CanonicalIrHandoff] =
    CanonicalIrValidator.validate(design, maxErrors) match {
      case Left(diagnostics) =>
        Left(CanonicalIrHandoffFailure.Validation(diagnostics))
      case Right(validated) =>
        fromValidated(validated, profile, completeFacets)
    }

  def fromValidated(
      validated: ValidatedDesign,
      profile: CanonicalIrProfile = productionProfile,
      completeFacets: Set[CanonicalIrFacet] = productionFacets
  ): Either[CanonicalIrHandoffFailure, CanonicalIrHandoff] = {
    if (validated == null)
      Left(
        CanonicalIrHandoffFailure.Contract(
          CanonicalIrHandoffFailureCode.ValidatedDesignMissing,
          "validated canonical IR must not be null"
        )
      )
    else if (profile == null)
      Left(
        CanonicalIrHandoffFailure.Contract(
          CanonicalIrHandoffFailureCode.ProfileMissing,
          "canonical IR producer profile must not be null"
        )
      )
    else if (completeFacets == null || completeFacets.exists(_ == null))
      Left(
        CanonicalIrHandoffFailure.Contract(
          CanonicalIrHandoffFailureCode.FacetsMissing,
          "canonical IR completeness facets must be a non-null set of non-null values"
        )
      )
    else {
      val missing = profile.requiredFacets -- completeFacets
      if (missing.nonEmpty)
        Left(
          CanonicalIrHandoffFailure.Contract(
            CanonicalIrHandoffFailureCode.FacetMissing,
            s"canonical IR profile '${profile.id}' is missing required facets: " +
              missing.toVector.map(_.id).sorted.mkString(", ")
          )
        )
      else
        profileShapeViolation(validated.value, profile) match {
          case Some(detail) =>
            Left(
              CanonicalIrHandoffFailure.Contract(
                CanonicalIrHandoffFailureCode.ProfileShapeMismatch,
                s"canonical IR profile '${profile.id}' rejected the snapshot: $detail"
              )
            )
          case None =>
            Right(
              new CanonicalIrHandoff(
                validated,
                profile,
                completeFacets.toVector.sortBy(_.id).toSet
              )
            )
        }
    }
  }

  private def profileShapeViolation(
      design: Design,
      profile: CanonicalIrProfile
  ): Option[String] = profile match {
    case CanonicalIrProfile.SimpleWireAssignmentsV1 =>
      simpleWireAssignmentsViolation(design)
  }

  private def simpleWireAssignmentsViolation(design: Design): Option[String] = {
    if (design.modules.size != 1)
      Some("exactly one module is required")
    else {
      val module = design.modules.head
      if (module.scopes.size != 1)
        Some("exactly one root module scope is required")
      else {
        val root = module.scopes.head
        if (root.parent.nonEmpty || root.kind != ScopeKind.Module)
          Some("the only scope must be a parentless module scope")
        else if (module.generateIndices.nonEmpty)
          Some("generate indices are not supported")
        else {
          module.declarations.collectFirst {
            case declaration if declaration.owner != root.id =>
              "every declaration must belong to the root module scope"
            case declaration if !isSimpleWireDeclaration(declaration) =>
              "only input/output ports and internal combinational declarations are supported"
            case declaration if !hasSimpleWirePackedType(declaration) =>
              "every packed type must match Bool, Bits, UInt or SInt with a supported width"
            case declaration
                if isPort(declaration) &&
                  !declaration.observability.externallyVisible =>
              "every input and output port must be externally visible"
            case declaration
                if declaration.observability.blackBoxBoundary ||
                  declaration.observability.hierarchyBoundary =>
              "blackbox and hierarchy boundaries are not supported"
          } match {
            case some @ Some(_) => some
            case None =>
              profileParameterViolation(module) match {
                case some @ Some(_) => some
                case None =>
                  val duplicateTarget = module.drivers
                    .groupBy(_.target)
                    .collectFirst { case (_, drivers) if drivers.size > 1 => () }
                  if (duplicateTarget.nonEmpty)
                    Some("ordered repeated assignments to one declaration are not supported")
                  else {
                    val structuralDriverViolation = module.drivers.collectFirst {
                      case driver if driver.owner != root.id =>
                        "every driver must belong to the root module scope"
                      case driver if !isWritableTarget(module, driver.target) =>
                        "continuous drivers may target only outputs or internal combinational declarations"
                      case driver if driver.kind != DriverKind.Continuous =>
                        "only continuous drivers are supported"
                      case driver if driver.coverage != DriverCoverage.FullObject =>
                        "only full-object drivers are supported"
                      case driver if !isSimpleWireExpression(driver.value) =>
                        "driver values must be direct references or exact literals"
                    }
                    structuralDriverViolation.orElse(
                      module.drivers.iterator
                        .map(driver => driverValueViolation(module, driver))
                        .collectFirst { case Some(detail) => detail }
                    )
                  }
              }
          }
        }
      }
    }
  }

  private def isSimpleWireDeclaration(declaration: Declaration): Boolean =
    declaration.kind match {
      case DeclarationKind.Port(PortDirection.Input)  => true
      case DeclarationKind.Port(PortDirection.Output) => true
      case DeclarationKind.InternalCombinational     => true
      case _                                         => false
    }

  private def isPort(declaration: Declaration): Boolean =
    declaration.kind match {
      case DeclarationKind.Port(_) => true
      case _                       => false
    }

  private def hasSimpleWirePackedType(declaration: Declaration): Boolean =
    declaration.packedType.exists { packedType =>
      val supportedWidth = packedType.width match {
        case IntExpr.Literal(_)      => true
        case IntExpr.ParameterRef(_) => true
        case _                       => false
      }
      packedType.valueSemantics match {
        case PackedValueSemantics.Boolean =>
          packedType.signedness == Signedness.Unsigned &&
            packedType.width == IntExpr.Literal(BigInt(1))
        case PackedValueSemantics.BitVector =>
          packedType.signedness == Signedness.Unsigned && supportedWidth
        case PackedValueSemantics.UnsignedInteger =>
          packedType.signedness == Signedness.Unsigned && supportedWidth
        case PackedValueSemantics.SignedInteger =>
          packedType.signedness == Signedness.Signed && supportedWidth
      }
    }

  private def profileParameterViolation(module: Module): Option[String] = {
    val referenced = module.declarations.flatMap { declaration =>
      declaration.packedType.toVector.flatMap { packedType =>
        packedType.width match {
          case IntExpr.ParameterRef(parameter) => Vector(parameter)
          case _                               => Vector.empty
        }
      }
    }.toSet
    module.parameters.collectFirst {
      case _: BooleanParameter =>
        "only integer parameters used directly as packed widths are supported"
      case parameter: IntegerParameter if !referenced.contains(parameter.id) =>
        "every integer parameter must be used directly as a packed width"
    }
  }

  private def isSimpleWireExpression(expression: RtlExpr): Boolean =
    expression match {
      case _: RtlExpr.Ref     => true
      case _: RtlExpr.Literal => true
      case _                  => false
    }

  private def isWritableTarget(module: Module, target: SymbolId): Boolean =
    module.declarations.find(_.id == target).exists { declaration =>
      declaration.kind match {
        case DeclarationKind.Port(PortDirection.Output) => true
        case DeclarationKind.InternalCombinational      => true
        case _                                          => false
      }
    }

  private def driverValueViolation(
      module: Module,
      driver: Driver
  ): Option[String] = {
    val declarations = module.declarations.map(value => value.id -> value).toMap
    val targetType = declarations(driver.target).packedType.get
    driver.value match {
      case reference: RtlExpr.Ref =>
        val sourceType = declarations(reference.target).packedType.get
        if (packedTypesEquivalent(module, sourceType, targetType)) None
        else
          Some(
            "direct reference source and target packed types must be semantically equal over the complete parameter domain"
          )
      case literal: RtlExpr.Literal =>
        if (literalMatches(module, targetType, literal)) None
        else Some("a literal must exactly match its target packed type and every admitted width")
      case _ => None
    }
  }

  private def packedTypesEquivalent(
      module: Module,
      source: PackedType,
      target: PackedType
  ): Boolean = {
    def admittedWidths(expression: IntExpr): Vector[BigInt] = expression match {
      case IntExpr.Literal(value) => Vector(value)
      case IntExpr.ParameterRef(id) =>
        module.parameters.collectFirst {
          case parameter: IntegerParameter if parameter.id == id =>
            parameter.domain.admittedValues
        }.getOrElse(Vector.empty)
      case _ => Vector.empty
    }

    source.signedness == target.signedness &&
    source.valueSemantics == target.valueSemantics &&
    (source.width == target.width || {
      val sourceWidths = admittedWidths(source.width)
      val targetWidths = admittedWidths(target.width)
      sourceWidths.nonEmpty && targetWidths.nonEmpty &&
      sourceWidths.forall(left => targetWidths.forall(right => left == right))
    })
  }

  private def literalMatches(
      module: Module,
      targetType: PackedType,
      literal: RtlExpr.Literal
  ): Boolean = {
    val widthMatches = targetType.width match {
      case IntExpr.Literal(value) => value == BigInt(literal.width)
      case IntExpr.ParameterRef(id) =>
        module.parameters.collectFirst {
          case parameter: IntegerParameter if parameter.id == id =>
            parameter.domain.admittedValues.forall(_ == BigInt(literal.width))
        }.getOrElse(false)
      case _ => false
    }
    val signedTarget = targetType.valueSemantics == PackedValueSemantics.SignedInteger
    val valueFits =
      if (literal.signed)
        literal.value.bitLength + (if (literal.value == 0) 0 else 1) <= literal.width
      else
        literal.value >= 0 && literal.value.bitLength <= literal.width
    val booleanValueMatches =
      targetType.valueSemantics != PackedValueSemantics.Boolean ||
        literal.value == 0 || literal.value == 1
    widthMatches && literal.signed == signedTarget && valueFits && booleanValueMatches
  }
}

/** Read-only publication callback invoked only after generation succeeds. */
trait CanonicalIrPublisher {
  def publish(handoff: CanonicalIrHandoff): Unit
}
