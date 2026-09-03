package morphhdl.passes.adapter

import morphhdl.ir.v1.BooleanParameter
import morphhdl.ir.v1.CanonicalIrHandoff
import morphhdl.ir.v1.CanonicalIrProfile
import morphhdl.ir.v1.CanonicalIrSchema
import morphhdl.ir.v1.CanonicalIrValidator
import morphhdl.ir.v1.Declaration
import morphhdl.ir.v1.Design
import morphhdl.ir.v1.Driver
import morphhdl.ir.v1.DriverId
import morphhdl.ir.v1.GenerateIndex
import morphhdl.ir.v1.GenerateIndexId
import morphhdl.ir.v1.IntegerParameter
import morphhdl.ir.v1.IrDiagnosticSet
import morphhdl.ir.v1.IrStage
import morphhdl.ir.v1.IrVersion
import morphhdl.ir.v1.Module
import morphhdl.ir.v1.ModuleId
import morphhdl.ir.v1.NameOrigin
import morphhdl.ir.v1.Observability
import morphhdl.ir.v1.PackedType
import morphhdl.ir.v1.Parameter
import morphhdl.ir.v1.ParameterId
import morphhdl.ir.v1.ReferenceId
import morphhdl.ir.v1.RtlExpr
import morphhdl.ir.v1.Scope
import morphhdl.ir.v1.ScopeId
import morphhdl.ir.v1.SourceLocation
import morphhdl.ir.v1.SymbolId
import morphhdl.ir.v1.ValidatedDesign

/** Canonical validation failure retained without translating diagnostic identity. */
final case class CanonicalIrAdapterFailure private[adapter] (
    diagnostics: IrDiagnosticSet
) {
  require(diagnostics != null, "canonical IR diagnostics must not be null")
  require(!diagnostics.isEmpty, "canonical IR adapter failures require diagnostics")
}

/**
  * Read-only facts for one exact canonical declaration identity.
  *
  * The adapter does not copy canonical metadata into a second pass IR. Every
  * accessor delegates to the validated v1 declaration, driver, and reference
  * objects supplied by MorphHDL.
  */
final case class CanonicalSymbolFacts private[adapter] (
    declaration: Declaration,
    drivers: Vector[Driver],
    references: Vector[RtlExpr.Ref]
) {
  def id: SymbolId = declaration.id
  def packedType: Option[PackedType] = declaration.packedType
  def nameOrigin: NameOrigin = declaration.nameOrigin
  def sourceLocation: Option[SourceLocation] = declaration.sourceLocation
  def observability: Observability = declaration.observability
}

/** Deterministic, identity-indexed view of one validated canonical module. */
final class CanonicalModuleView private[adapter] (val module: Module) {
  val parameters: Vector[Parameter] = module.parameters
  val scopes: Vector[Scope] = module.scopes
  val generateIndices: Vector[GenerateIndex] = module.generateIndices
  val declarations: Vector[Declaration] = module.declarations
  val drivers: Vector[Driver] = module.drivers
  val references: Vector[RtlExpr.Ref] =
    drivers.flatMap(_.value.referenceOccurrences).sortBy(_.id.value)

  private val parameterById: Map[ParameterId, Parameter] =
    parameters.map(value => value.id -> value).toMap
  private val scopeById: Map[ScopeId, Scope] =
    scopes.map(value => value.id -> value).toMap
  private val generateIndexById: Map[GenerateIndexId, GenerateIndex] =
    generateIndices.map(value => value.id -> value).toMap
  private val declarationById: Map[SymbolId, Declaration] =
    declarations.map(value => value.id -> value).toMap
  private val driverById: Map[DriverId, Driver] =
    drivers.map(value => value.id -> value).toMap
  private val referenceById: Map[ReferenceId, RtlExpr.Ref] =
    references.map(value => value.id -> value).toMap
  private val driversByTarget: Map[SymbolId, Vector[Driver]] =
    drivers.groupBy(_.target).map { case (target, values) =>
      target -> values.sortBy(_.id.value)
    }
  private val referencesByTarget: Map[SymbolId, Vector[RtlExpr.Ref]] =
    references.groupBy(_.target).map { case (target, values) =>
      target -> values.sortBy(_.id.value)
    }

  def id: ModuleId = module.id
  def sourceLocation: Option[SourceLocation] = module.sourceLocation

  def parameter(id: ParameterId): Option[Parameter] = parameterById.get(id)

  def integerParameter(id: ParameterId): Option[IntegerParameter] =
    parameter(id).collect { case value: IntegerParameter => value }

  def booleanParameter(id: ParameterId): Option[BooleanParameter] =
    parameter(id).collect { case value: BooleanParameter => value }

  def scope(id: ScopeId): Option[Scope] = scopeById.get(id)

  def generateIndex(id: GenerateIndexId): Option[GenerateIndex] =
    generateIndexById.get(id)

  def declaration(id: SymbolId): Option[Declaration] = declarationById.get(id)

  def driver(id: DriverId): Option[Driver] = driverById.get(id)

  def reference(id: ReferenceId): Option[RtlExpr.Ref] = referenceById.get(id)

  def driversTargeting(id: SymbolId): Vector[Driver] =
    driversByTarget.getOrElse(id, Vector.empty)

  def referencesTo(id: SymbolId): Vector[RtlExpr.Ref] =
    referencesByTarget.getOrElse(id, Vector.empty)

  def symbol(id: SymbolId): Option[CanonicalSymbolFacts] =
    declaration(id).map { value =>
      CanonicalSymbolFacts(value, driversTargeting(id), referencesTo(id))
    }
}

/**
  * Read-only canonical v1 handoff for optional pass analysis.
  *
  * Construction is possible only from a CanonicalIrValidator result. No
  * declaration, driver, reference, expression, parameter, or metadata item is
  * removed or rewritten by this adapter.
  */
final class CanonicalIrPassView private[adapter] (val validated: ValidatedDesign) {
  val design: Design = validated.value
  val modules: Vector[CanonicalModuleView] =
    design.modules.map(module => new CanonicalModuleView(module))

  private val moduleById: Map[ModuleId, CanonicalModuleView] =
    modules.map(value => value.id -> value).toMap
  private val parameterById: Map[ParameterId, Parameter] =
    modules.flatMap(_.parameters).map(value => value.id -> value).toMap
  private val scopeById: Map[ScopeId, Scope] =
    modules.flatMap(_.scopes).map(value => value.id -> value).toMap
  private val generateIndexById: Map[GenerateIndexId, GenerateIndex] =
    modules.flatMap(_.generateIndices).map(value => value.id -> value).toMap
  private val declarationById: Map[SymbolId, Declaration] =
    modules.flatMap(_.declarations).map(value => value.id -> value).toMap
  private val driverById: Map[DriverId, Driver] =
    modules.flatMap(_.drivers).map(value => value.id -> value).toMap
  private val referenceById: Map[ReferenceId, RtlExpr.Ref] =
    modules.flatMap(_.references).map(value => value.id -> value).toMap
  private val moduleBySymbol: Map[SymbolId, CanonicalModuleView] =
    modules.flatMap { moduleView =>
      moduleView.declarations.map(value => value.id -> moduleView)
    }.toMap

  def version: IrVersion = design.version
  def stage: IrStage = design.stage
  def top: ModuleId = design.top

  def module(id: ModuleId): Option[CanonicalModuleView] = moduleById.get(id)

  def parameter(id: ParameterId): Option[Parameter] = parameterById.get(id)

  def integerParameter(id: ParameterId): Option[IntegerParameter] =
    parameter(id).collect { case value: IntegerParameter => value }

  def booleanParameter(id: ParameterId): Option[BooleanParameter] =
    parameter(id).collect { case value: BooleanParameter => value }

  def scope(id: ScopeId): Option[Scope] = scopeById.get(id)

  def generateIndex(id: GenerateIndexId): Option[GenerateIndex] =
    generateIndexById.get(id)

  def declaration(id: SymbolId): Option[Declaration] = declarationById.get(id)

  def driver(id: DriverId): Option[Driver] = driverById.get(id)

  def reference(id: ReferenceId): Option[RtlExpr.Ref] = referenceById.get(id)

  def symbol(id: SymbolId): Option[CanonicalSymbolFacts] =
    moduleBySymbol.get(id).flatMap(_.symbol(id))
}

/** Adapter from the stable canonical v1 API into a read-only pass view. */
object CanonicalIrPassAdapter {
  val supportedVersion: IrVersion = CanonicalIrSchema.schemaVersion
  val supportedStage: IrStage = CanonicalIrSchema.stage

  /** Bind the validated production envelope published by MorphHDL. */
  def bind(handoff: CanonicalIrHandoff): CanonicalIrPassView = {
    require(handoff != null, "canonical IR handoff must not be null")
    require(
      handoff.profile == CanonicalIrProfile.SimpleWireAssignmentsV1,
      s"unsupported canonical IR producer profile '${handoff.profile.id}'"
    )
    require(
      handoff.profile.requiredFacets.subsetOf(handoff.completeFacets),
      s"canonical IR handoff '${handoff.profile.id}' is missing required completeness facets"
    )
    bindValidatedInternal(handoff.validated)
  }

  /**
    * Validate a hand-authored fixture or mutation oracle.
    *
    * Production integrations must consume [[CanonicalIrHandoff]] so their
    * bounded producer profile and completeness claims cannot be discarded.
    */
  def bindFixture(
      design: Design,
      maxErrors: Int = CanonicalIrValidator.DefaultMaximumDiagnostics
  ): Either[CanonicalIrAdapterFailure, CanonicalIrPassView] =
    CanonicalIrValidator.validate(design, maxErrors) match {
      case Right(validated) => Right(bindValidatedInternal(validated))
      case Left(diagnostics) => Left(CanonicalIrAdapterFailure(diagnostics))
    }

  @deprecated(
    "Use bind(CanonicalIrHandoff) for production; raw Design binding is fixture/mutation compatibility only",
    "Increment 58"
  )
  def bind(
      design: Design,
      maxErrors: Int = CanonicalIrValidator.DefaultMaximumDiagnostics
  ): Either[CanonicalIrAdapterFailure, CanonicalIrPassView] =
    bindFixture(design, maxErrors)

  @deprecated(
    "Use bind(CanonicalIrHandoff) for production; bare ValidatedDesign binding is compatibility-only",
    "Increment 58"
  )
  def bindValidated(validated: ValidatedDesign): CanonicalIrPassView =
    bindValidatedInternal(validated)

  private def bindValidatedInternal(validated: ValidatedDesign): CanonicalIrPassView = {
    require(validated != null, "validated canonical IR must not be null")
    new CanonicalIrPassView(validated)
  }
}
