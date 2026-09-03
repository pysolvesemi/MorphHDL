package morphhdl.passes.safety

import java.security.MessageDigest

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import morphhdl.ir.v1.BoolExpr
import morphhdl.ir.v1.BooleanParameter
import morphhdl.ir.v1.Declaration
import morphhdl.ir.v1.DeclarationKind
import morphhdl.ir.v1.Driver
import morphhdl.ir.v1.DriverCoverage
import morphhdl.ir.v1.DriverKind
import morphhdl.ir.v1.GenerateIndex
import morphhdl.ir.v1.GenerateIndexId
import morphhdl.ir.v1.IntExpr
import morphhdl.ir.v1.IntegerParameter
import morphhdl.ir.v1.ModuleId
import morphhdl.ir.v1.NameOrigin
import morphhdl.ir.v1.PackedType
import morphhdl.ir.v1.ParameterId
import morphhdl.ir.v1.PortDirection
import morphhdl.ir.v1.RtlExpr
import morphhdl.ir.v1.ScopeId
import morphhdl.ir.v1.SourceLocation
import morphhdl.ir.v1.SymbolId
import morphhdl.passes.adapter.CanonicalIrPassView
import morphhdl.passes.adapter.CanonicalModuleView

/** Resource limits for an exact, fail-closed alias safety proof. */
final case class AliasSafetyConfiguration(
    maximumDomainBindings: Int = 65536,
    maximumPow2Exponent: Int = 4096
) {
  require(maximumDomainBindings >= 1, "maximum domain bindings must be positive")
  require(maximumPow2Exponent >= 0, "maximum pow2 exponent must be non-negative")
}

/** Stable rejection identifiers shared by both future transformation passes. */
object AliasSafetyReason {
  val AliasNotInternalCombinational = "WA03-ALIAS-NOT-INTERNAL-COMBINATIONAL"
  val NameOriginUnproven = "WA03-NAME-ORIGIN-UNPROVEN"
  val ObservabilityIncomplete = "WA03-OBSERVABILITY-INCOMPLETE"
  val ExternallyVisible = "WA03-EXTERNALLY-VISIBLE"
  val Keep = "WA03-KEEP"
  val DontTouch = "WA03-DONT-TOUCH"
  val Probe = "WA03-PROBE"
  val Preserve = "WA03-PRESERVE"
  val PublicExport = "WA03-PUBLIC-EXPORT"
  val BlackBoxBoundary = "WA03-BLACK-BOX-BOUNDARY"
  val HierarchyBoundary = "WA03-HIERARCHY-BOUNDARY"
  val DeclarationAttributes = "WA03-DECLARATION-ATTRIBUTES"
  val DeclarationComments = "WA03-DECLARATION-COMMENTS"
  val DriverCardinality = "WA03-DRIVER-CARDINALITY"
  val DriverNotContinuous = "WA03-DRIVER-NOT-CONTINUOUS"
  val DriverNotFullObject = "WA03-DRIVER-NOT-FULL-OBJECT"
  val DriverNotDirectReference = "WA03-DRIVER-NOT-DIRECT-REFERENCE"
  val DriverAttributes = "WA03-DRIVER-ATTRIBUTES"
  val DriverComments = "WA03-DRIVER-COMMENTS"
  val SourceSelfReference = "WA03-SOURCE-SELF-REFERENCE"
  val SourceUnresolved = "WA03-SOURCE-UNRESOLVED"
  val SourceKindExcluded = "WA03-SOURCE-KIND-EXCLUDED"
  val PackedTypeMissing = "WA03-PACKED-TYPE-MISSING"
  val PackedSignednessMismatch = "WA03-PACKED-SIGNEDNESS-MISMATCH"
  val PackedValueSemanticsMismatch = "WA03-PACKED-VALUE-SEMANTICS-MISMATCH"
  val PackedWidthDomainMismatch = "WA03-PACKED-WIDTH-DOMAIN-MISMATCH"
  val PackedWidthDomainUnproven = "WA03-PACKED-WIDTH-DOMAIN-UNPROVEN"
  val DomainExpansionLimit = "WA03-DOMAIN-EXPANSION-LIMIT"
  val IllegalScopeReplacement = "WA03-ILLEGAL-SCOPE-REPLACEMENT"
  val CombinationalCycle = "WA03-COMBINATIONAL-CYCLE"
  val ClockUse = "WA03-CLOCK-USE"
  val ResetUse = "WA03-RESET-USE"
  val TriStateControlUse = "WA03-TRI-STATE-CONTROL-USE"
  val ControlUseUnproven = "WA03-CONTROL-USE-UNPROVEN"
  val BidirectionalUse = "WA03-BIDIRECTIONAL-USE"
  val MemoryPortUse = "WA03-MEMORY-PORT-USE"
  val InstanceBoundaryUse = "WA03-INSTANCE-BOUNDARY-USE"

  private val Ordered: Vector[String] = Vector(
    AliasNotInternalCombinational,
    NameOriginUnproven,
    ObservabilityIncomplete,
    ExternallyVisible,
    Keep,
    DontTouch,
    Probe,
    Preserve,
    PublicExport,
    BlackBoxBoundary,
    HierarchyBoundary,
    DeclarationAttributes,
    DeclarationComments,
    DriverCardinality,
    DriverNotContinuous,
    DriverNotFullObject,
    DriverNotDirectReference,
    DriverAttributes,
    DriverComments,
    SourceSelfReference,
    SourceUnresolved,
    SourceKindExcluded,
    PackedTypeMissing,
    PackedSignednessMismatch,
    PackedValueSemanticsMismatch,
    PackedWidthDomainMismatch,
    PackedWidthDomainUnproven,
    DomainExpansionLimit,
    IllegalScopeReplacement,
    CombinationalCycle,
    ClockUse,
    ResetUse,
    TriStateControlUse,
    ControlUseUnproven,
    BidirectionalUse,
    MemoryPortUse,
    InstanceBoundaryUse
  )

  private val Rank: Map[String, Int] = Ordered.zipWithIndex.toMap

  private[safety] def rank(code: String): Int = Rank.getOrElse(code, Int.MaxValue)
}

/** One deterministic reason that an exact symbol identity is not removable. */
final case class AliasSafetyViolation(
    code: String,
    message: String,
    relatedSymbol: Option[SymbolId] = None
) {
  require(Option(code).exists(_.trim.nonEmpty), "alias safety code must be non-empty")
  require(Option(message).exists(_.trim.nonEmpty), "alias safety message must be non-empty")
}

/** Evidence that both packed widths agree over every admitted binding. */
final case class PackedTypeEquivalenceEvidence(
    checkedBindings: Int,
    minimumWidth: BigInt,
    maximumWidth: BigInt,
    domainDigest: String
) {
  require(checkedBindings >= 1, "a packed-type proof requires at least one binding")
  require(minimumWidth >= 1, "a packed-type proof requires a positive minimum width")
  require(maximumWidth >= minimumWidth, "packed-type proof width bounds are invalid")
  require(Option(domainDigest).exists(_.matches("[0-9a-f]{64}")), "domain digest must be SHA-256")
}

/** Complete read-only safety result for one canonical declaration identity. */
final case class AliasSafetyAssessment(
    moduleId: ModuleId,
    aliasSymbol: SymbolId,
    sourceSymbol: Option[SymbolId],
    nameOrigin: NameOrigin,
    sourceLocation: Option[SourceLocation],
    typeEvidence: Option[PackedTypeEquivalenceEvidence],
    violations: Vector[AliasSafetyViolation]
) {
  def isEligible: Boolean = violations.isEmpty

  def normalized: AliasSafetyAssessment =
    copy(
      violations = violations.distinct.sortBy { violation =>
        (
          AliasSafetyReason.rank(violation.code),
          violation.code,
          violation.relatedSymbol.map(_.value).getOrElse(""),
          violation.message
        )
      }
    )

  private[safety] def semanticText: String = {
    val normalizedValue = normalized
    val evidenceText = normalizedValue.typeEvidence match {
      case Some(value) =>
        Vector(
          value.checkedBindings.toString,
          value.minimumWidth.toString,
          value.maximumWidth.toString,
          value.domainDigest
        ).mkString(":")
      case None => "-"
    }
    val violationText = normalizedValue.violations
      .map { value =>
        Vector(
          value.code,
          value.relatedSymbol.map(_.value).getOrElse(""),
          value.message
        ).mkString(":")
      }
      .mkString("|")
    Vector(
      moduleId.value,
      aliasSymbol.value,
      sourceSymbol.map(_.value).getOrElse(""),
      WireAliasSafetyGate.nameOriginKey(nameOrigin),
      evidenceText,
      violationText
    ).mkString(";")
  }
}

/** Stable report produced without changing the validated canonical design. */
final case class WireAliasSafetyReport(assessments: Vector[AliasSafetyAssessment]) {
  def normalized: WireAliasSafetyReport =
    copy(
      assessments = assessments
        .map(_.normalized)
        .sortBy(value => (value.moduleId.value, value.aliasSymbol.value))
    )

  def eligible: Vector[AliasSafetyAssessment] = normalized.assessments.filter(_.isEligible)
  def rejected: Vector[AliasSafetyAssessment] = normalized.assessments.filterNot(_.isEligible)

  /** Path- and component-name-independent signature for deterministic decisions. */
  def semanticDigest: String =
    WireAliasSafetyGate.sha256(normalized.assessments.map(_.semanticText).mkString("\n"))
}

/**
  * Shared fail-closed eligibility gate for the two authorized alias passes.
  *
  * This object performs analysis only. It never rewrites, removes, or renames a
  * canonical declaration, driver, reference, expression, scope, or parameter.
  */
object WireAliasSafetyGate {
  def analyze(
      view: CanonicalIrPassView,
      configuration: AliasSafetyConfiguration = AliasSafetyConfiguration()
  ): WireAliasSafetyReport = {
    require(view != null, "canonical pass view must not be null")
    require(configuration != null, "alias safety configuration must not be null")

    val assessments = view.modules.flatMap { module =>
      module.declarations
        .filter(_.kind == DeclarationKind.InternalCombinational)
        .sortBy(_.id.value)
        .map(declaration => assess(module, declaration.id, configuration))
    }
    WireAliasSafetyReport(assessments).normalized
  }

  def assess(
      module: CanonicalModuleView,
      aliasSymbol: SymbolId,
      configuration: AliasSafetyConfiguration = AliasSafetyConfiguration()
  ): AliasSafetyAssessment = {
    require(module != null, "canonical module view must not be null")
    require(aliasSymbol != null, "alias symbol identity must not be null")
    require(configuration != null, "alias safety configuration must not be null")

    val violations = ArrayBuffer.empty[AliasSafetyViolation]
    val declaration = module.declaration(aliasSymbol)
    var sourceSymbol = Option.empty[SymbolId]
    var typeEvidence = Option.empty[PackedTypeEquivalenceEvidence]

    declaration match {
      case None =>
        violations += violation(
          AliasSafetyReason.SourceUnresolved,
          s"candidate declaration '${aliasSymbol.value}' is not present in module '${module.id.value}'"
        )
      case Some(alias) =>
        validateAliasMetadata(alias, violations)
        val drivers = module.driversTargeting(aliasSymbol)
        if (drivers.size != 1) {
          violations += violation(
            AliasSafetyReason.DriverCardinality,
            s"alias requires exactly one driver, observed ${drivers.size}"
          )
        } else {
          val driver = drivers.head
          validateDriverMetadata(driver, violations)
          driver.value match {
            case RtlExpr.Ref(_, target, _, _) =>
              sourceSymbol = Some(target)
              if (target == aliasSymbol) {
                violations += violation(
                  AliasSafetyReason.SourceSelfReference,
                  "alias driver directly references the alias itself",
                  Some(target)
                )
              }
              module.declaration(target) match {
                case None =>
                  violations += violation(
                    AliasSafetyReason.SourceUnresolved,
                    s"direct source '${target.value}' is not declared in the owning module",
                    Some(target)
                  )
                case Some(source) =>
                  validateSourceKind(source, violations)
                  validateUsageContexts(module, aliasSymbol, violations)
                  validateReplacementScopes(module, aliasSymbol, source, violations)
                  if (createsCombinationalCycle(module, aliasSymbol, target)) {
                    violations += violation(
                      AliasSafetyReason.CombinationalCycle,
                      s"substituting source '${target.value}' for alias '${aliasSymbol.value}' is not cycle-free",
                      Some(target)
                    )
                  }
                  provePackedTypeEquivalence(
                    module,
                    alias,
                    source,
                    configuration
                  ) match {
                    case Right(evidence) => typeEvidence = Some(evidence)
                    case Left(value)     => violations += value
                  }
              }
            case _ =>
              violations += violation(
                AliasSafetyReason.DriverNotDirectReference,
                "alias driver must be one exact symbol reference"
              )
          }
        }
    }

    val aliasNameOrigin = declaration.map(_.nameOrigin).getOrElse(NameOrigin.Unknown)
    val aliasLocation = declaration.flatMap(_.sourceLocation)
    AliasSafetyAssessment(
      moduleId = module.id,
      aliasSymbol = aliasSymbol,
      sourceSymbol = sourceSymbol,
      nameOrigin = aliasNameOrigin,
      sourceLocation = aliasLocation,
      typeEvidence = typeEvidence,
      violations = violations.toVector
    ).normalized
  }

  private def validateAliasMetadata(
      alias: Declaration,
      violations: ArrayBuffer[AliasSafetyViolation]
  ): Unit = {
    if (alias.kind != DeclarationKind.InternalCombinational) {
      violations += violation(
        AliasSafetyReason.AliasNotInternalCombinational,
        s"candidate kind '${alias.kind.label}' is outside the simple-wire alias contract"
      )
    }
    if (!alias.nameOrigin.isKnown) {
      violations += violation(
        AliasSafetyReason.NameOriginUnproven,
        "candidate naming provenance is not proven"
      )
    }

    val observability = alias.observability
    if (!observability.complete) {
      violations += violation(
        AliasSafetyReason.ObservabilityIncomplete,
        "candidate observability metadata is incomplete"
      )
    }
    val exclusions = Vector(
      observability.externallyVisible -> (AliasSafetyReason.ExternallyVisible -> "candidate is externally visible"),
      observability.keep -> (AliasSafetyReason.Keep -> "candidate carries keep semantics"),
      observability.dontTouch -> (AliasSafetyReason.DontTouch -> "candidate carries dontTouch semantics"),
      observability.probe -> (AliasSafetyReason.Probe -> "candidate is a probe endpoint"),
      observability.preserve -> (AliasSafetyReason.Preserve -> "candidate carries preservation semantics"),
      observability.publicExport -> (AliasSafetyReason.PublicExport -> "candidate is publicly exported"),
      observability.blackBoxBoundary -> (AliasSafetyReason.BlackBoxBoundary -> "candidate crosses a black-box boundary"),
      observability.hierarchyBoundary -> (AliasSafetyReason.HierarchyBoundary -> "candidate crosses a hierarchy boundary")
    )
    exclusions.foreach { case (active, (code, message)) =>
      if (active) violations += violation(code, message)
    }

    if (alias.attributes.nonEmpty) {
      violations += violation(
        AliasSafetyReason.DeclarationAttributes,
        s"removing the alias would discard ${alias.attributes.size} declaration attribute(s)"
      )
    }
    if (alias.comments.nonEmpty) {
      violations += violation(
        AliasSafetyReason.DeclarationComments,
        s"removing the alias would discard ${alias.comments.size} declaration comment(s)"
      )
    }
  }

  private def validateDriverMetadata(
      driver: Driver,
      violations: ArrayBuffer[AliasSafetyViolation]
  ): Unit = {
    if (driver.kind != DriverKind.Continuous) {
      violations += violation(
        AliasSafetyReason.DriverNotContinuous,
        s"alias driver kind '${driver.kind.label}' is not continuous"
      )
    }
    if (driver.coverage != DriverCoverage.FullObject) {
      violations += violation(
        AliasSafetyReason.DriverNotFullObject,
        s"alias driver coverage '${driver.coverage.label}' is not full-object"
      )
    }
    if (driver.attributes.nonEmpty) {
      violations += violation(
        AliasSafetyReason.DriverAttributes,
        s"removing the alias assignment would discard ${driver.attributes.size} driver attribute(s)"
      )
    }
    if (driver.comments.nonEmpty) {
      violations += violation(
        AliasSafetyReason.DriverComments,
        s"removing the alias assignment would discard ${driver.comments.size} driver comment(s)"
      )
    }
  }

  private def validateSourceKind(
      source: Declaration,
      violations: ArrayBuffer[AliasSafetyViolation]
  ): Unit = {
    val allowed = source.kind match {
      case DeclarationKind.Port(PortDirection.Input)  => true
      case DeclarationKind.Port(PortDirection.Output) => true
      case DeclarationKind.InternalCombinational      => true
      case DeclarationKind.Register                   => true
      case _                                          => false
    }
    if (!allowed) {
      violations += violation(
        AliasSafetyReason.SourceKindExcluded,
        s"source kind '${source.kind.label}' is excluded from direct alias substitution",
        Some(source.id)
      )
    }
  }

  private def validateUsageContexts(
    module: CanonicalModuleView,
    aliasSymbol: SymbolId,
    violations: ArrayBuffer[AliasSafetyViolation]
): Unit = {
    val aliasOwner = module.declaration(aliasSymbol).map(_.owner)

    module.drivers.sortBy(_.id.value).foreach { driver =>
      val directlyConsumesAlias =
        driver.value.referenceOccurrences.exists(_.target == aliasSymbol)
      val aliasVisibleFromDriver =
        aliasOwner.exists(owner => scopeIsAncestor(module, owner, driver.owner))

      if (directlyConsumesAlias) {
        driver.kind match {
          case DriverKind.Bidirectional =>
            violations += violation(
              AliasSafetyReason.BidirectionalUse,
              s"alias is consumed by bidirectional driver '${driver.id.value}'"
            )
          case DriverKind.MemoryPort =>
            violations += violation(
              AliasSafetyReason.MemoryPortUse,
              s"alias is consumed by memory-port driver '${driver.id.value}'"
            )
          case DriverKind.InstancePort =>
            violations += violation(
              AliasSafetyReason.InstanceBoundaryUse,
              s"alias is consumed by instance-port driver '${driver.id.value}'"
            )
          case _ =>
        }

        module.declaration(driver.target).foreach { target =>
          target.kind match {
            case DeclarationKind.Clock =>
              violations += violation(
                AliasSafetyReason.ClockUse,
                s"alias directly drives clock declaration '${target.id.value}'",
                Some(target.id)
              )
            case DeclarationKind.Reset =>
              violations += violation(
                AliasSafetyReason.ResetUse,
                s"alias directly drives reset declaration '${target.id.value}'",
                Some(target.id)
              )
            case DeclarationKind.Port(PortDirection.InOut) =>
              violations += violation(
                AliasSafetyReason.TriStateControlUse,
                s"alias directly participates in inout declaration '${target.id.value}'",
                Some(target.id)
              )
            case _ =>
          }
        }
      }

      if (aliasVisibleFromDriver) {
        driver.kind match {
          case DriverKind.Procedural =>
            violations += violation(
              AliasSafetyReason.ControlUseUnproven,
              s"canonical v1 does not prove that procedural driver '${driver.id.value}' cannot use the alias as a clock or reset control"
            )
          case DriverKind.Bidirectional =>
            violations += violation(
              AliasSafetyReason.TriStateControlUse,
              s"canonical v1 does not prove that bidirectional driver '${driver.id.value}' cannot use the alias as a tri-state control"
            )
          case _ =>
        }
      }
    }
  }

  private def validateReplacementScopes(
      module: CanonicalModuleView,
      aliasSymbol: SymbolId,
      source: Declaration,
      violations: ArrayBuffer[AliasSafetyViolation]
  ): Unit = {
    module.referencesTo(aliasSymbol).sortBy(_.id.value).foreach { reference =>
      if (!scopeIsAncestor(module, source.owner, reference.owner)) {
        violations += violation(
          AliasSafetyReason.IllegalScopeReplacement,
          s"source '${source.id.value}' is not visible from reference '${reference.id.value}' in scope '${reference.owner.value}'",
          Some(source.id)
        )
      }
    }
  }

  private def scopeIsAncestor(
      module: CanonicalModuleView,
      ancestor: ScopeId,
      descendant: ScopeId
  ): Boolean = {
    var current = Option(descendant)
    var visited = Set.empty[ScopeId]
    while (current.nonEmpty) {
      val value = current.get
      if (value == ancestor) return true
      if (visited.contains(value)) return false
      visited += value
      current = module.scope(value).flatMap(_.parent)
    }
    false
  }

  /**
    * The alias has an edge to its direct source. A substitution is cycle-free
    * exactly when the continuous dependency graph has no path back from that
    * source to the alias.
    */
  private def createsCombinationalCycle(
      module: CanonicalModuleView,
      aliasSymbol: SymbolId,
      sourceSymbol: SymbolId
  ): Boolean = {
    if (aliasSymbol == sourceSymbol) return true

    val edges = mutable.Map.empty[SymbolId, Vector[SymbolId]]
    module.drivers
      .filter(_.kind == DriverKind.Continuous)
      .sortBy(_.id.value)
      .foreach { driver =>
        if (isCombinationalNode(module, driver.target)) {
          val dependencies = driver.value.referencedSymbols.distinct.sortBy(_.value)
          val current = edges.getOrElse(driver.target, Vector.empty)
          edges.update(
            driver.target,
            (current ++ dependencies).distinct.sortBy(_.value)
          )
        }
      }

    val pending = mutable.Stack[SymbolId](sourceSymbol)
    val visited = mutable.Set.empty[SymbolId]
    while (pending.nonEmpty) {
      val current = pending.pop()
      if (current == aliasSymbol) return true
      if (!visited.contains(current)) {
        visited += current
        edges.getOrElse(current, Vector.empty).reverse.foreach(value => pending.push(value))
      }
    }
    false
  }

  private def isCombinationalNode(
      module: CanonicalModuleView,
      symbol: SymbolId
  ): Boolean =
    module.declaration(symbol).exists { declaration =>
      declaration.kind match {
        case DeclarationKind.InternalCombinational      => true
        case DeclarationKind.Port(PortDirection.Output) => true
        case _                                          => false
      }
    }

  private def provePackedTypeEquivalence(
      module: CanonicalModuleView,
      alias: Declaration,
      source: Declaration,
      configuration: AliasSafetyConfiguration
  ): Either[AliasSafetyViolation, PackedTypeEquivalenceEvidence] = {
    (alias.packedType, source.packedType) match {
      case (Some(aliasType), Some(sourceType)) =>
        if (aliasType.signedness != sourceType.signedness) {
          Left(
            violation(
              AliasSafetyReason.PackedSignednessMismatch,
              "alias and source packed signedness differ",
              Some(source.id)
            )
          )
        } else if (aliasType.valueSemantics != sourceType.valueSemantics) {
          Left(
            violation(
              AliasSafetyReason.PackedValueSemanticsMismatch,
              "alias and source packed value semantics differ",
              Some(source.id)
            )
          )
        } else {
          proveWidthDomain(
            module,
            aliasType,
            sourceType,
            source.id,
            configuration
          )
        }
      case _ =>
        Left(
          violation(
            AliasSafetyReason.PackedTypeMissing,
            "alias and source require complete packed-type metadata",
            Some(source.id)
          )
        )
    }
  }

  private final case class DomainReferences(
      integerParameters: Set[ParameterId] = Set.empty,
      booleanParameters: Set[ParameterId] = Set.empty,
      generateIndices: Set[GenerateIndexId] = Set.empty
  ) {
    def ++(other: DomainReferences): DomainReferences =
      DomainReferences(
        integerParameters ++ other.integerParameters,
        booleanParameters ++ other.booleanParameters,
        generateIndices ++ other.generateIndices
      )
  }

  private sealed trait AxisValue {
    def rendered: String
    def addTo(binding: EvaluationBinding): EvaluationBinding
  }

  private final case class IntegerParameterValue(id: ParameterId, value: BigInt)
      extends AxisValue {
    override def rendered: String = value.toString
    override def addTo(binding: EvaluationBinding): EvaluationBinding =
      binding.copy(
        integerParameters = binding.integerParameters + (id -> value),
        rendered = binding.rendered :+ (s"integer:${id.value}" -> rendered)
      )
  }

  private final case class BooleanParameterValue(id: ParameterId, value: Boolean)
      extends AxisValue {
    override def rendered: String = if (value) "true" else "false"
    override def addTo(binding: EvaluationBinding): EvaluationBinding =
      binding.copy(
        booleanParameters = binding.booleanParameters + (id -> value),
        rendered = binding.rendered :+ (s"boolean:${id.value}" -> rendered)
      )
  }

  private final case class GenerateIndexValue(id: GenerateIndexId, value: BigInt)
      extends AxisValue {
    override def rendered: String = value.toString
    override def addTo(binding: EvaluationBinding): EvaluationBinding =
      binding.copy(
        generateIndices = binding.generateIndices + (id -> value),
        rendered = binding.rendered :+ (s"generate:${id.value}" -> rendered)
      )
  }

  private final case class DomainAxis(key: String, values: Vector[AxisValue])

  private final case class EvaluationBinding(
      integerParameters: Map[ParameterId, BigInt],
      booleanParameters: Map[ParameterId, Boolean],
      generateIndices: Map[GenerateIndexId, BigInt],
      rendered: Vector[(String, String)]
  ) {
    def text: String = rendered.map { case (key, value) => s"$key=$value" }.mkString(",")
  }

  private object EvaluationBinding {
    val Empty: EvaluationBinding =
      EvaluationBinding(Map.empty, Map.empty, Map.empty, Vector.empty)
  }

  private def proveWidthDomain(
      module: CanonicalModuleView,
      aliasType: PackedType,
      sourceType: PackedType,
      sourceSymbol: SymbolId,
      configuration: AliasSafetyConfiguration
  ): Either[AliasSafetyViolation, PackedTypeEquivalenceEvidence] = {
    val references = intReferences(aliasType.width) ++ intReferences(sourceType.width)
    buildAxes(module, references, configuration) match {
      case Left(value) => Left(value)
      case Right(axes) =>
        val bindingCount = axes.foldLeft(BigInt(1)) { (count, axis) =>
          count * BigInt(axis.values.size)
        }
        if (bindingCount > configuration.maximumDomainBindings) {
          Left(
            violation(
              AliasSafetyReason.DomainExpansionLimit,
              s"complete packed-width proof requires $bindingCount bindings; configured limit is ${configuration.maximumDomainBindings}",
              Some(sourceSymbol)
            )
          )
        } else {
          val bindings = axes.foldLeft(Vector(EvaluationBinding.Empty)) {
            case (partial, axis) =>
              for {
                binding <- partial
                value <- axis.values
              } yield value.addTo(binding)
          }
          val effectiveBindings = if (bindings.isEmpty) Vector(EvaluationBinding.Empty) else bindings
          val proofLines = Vector.newBuilder[String]
          var minimum = Option.empty[BigInt]
          var maximum = Option.empty[BigInt]
          var failure = Option.empty[AliasSafetyViolation]

          val iterator = effectiveBindings.iterator
          while (iterator.hasNext && failure.isEmpty) {
            val binding = iterator.next()
            val aliasWidth = evaluateInt(aliasType.width, binding, configuration)
            val sourceWidth = evaluateInt(sourceType.width, binding, configuration)
            (aliasWidth, sourceWidth) match {
              case (Right(aliasValue), Right(sourceValue)) =>
                proofLines += s"${binding.text}|$aliasValue|$sourceValue"
                if (aliasValue != sourceValue) {
                  failure = Some(
                    violation(
                      AliasSafetyReason.PackedWidthDomainMismatch,
                      s"packed widths differ at admitted binding {${binding.text}}: alias=$aliasValue source=$sourceValue",
                      Some(sourceSymbol)
                    )
                  )
                } else if (aliasValue < 1) {
                  failure = Some(
                    violation(
                      AliasSafetyReason.PackedWidthDomainUnproven,
                      s"packed width is not positive at admitted binding {${binding.text}}: $aliasValue",
                      Some(sourceSymbol)
                    )
                  )
                } else {
                  minimum = Some(minimum.fold(aliasValue)(_.min(aliasValue)))
                  maximum = Some(maximum.fold(aliasValue)(_.max(aliasValue)))
                }
              case (Left(message), _) =>
                failure = Some(
                  violation(
                    AliasSafetyReason.PackedWidthDomainUnproven,
                    s"alias packed width cannot be evaluated at admitted binding {${binding.text}}: $message",
                    Some(sourceSymbol)
                  )
                )
              case (_, Left(message)) =>
                failure = Some(
                  violation(
                    AliasSafetyReason.PackedWidthDomainUnproven,
                    s"source packed width cannot be evaluated at admitted binding {${binding.text}}: $message",
                    Some(sourceSymbol)
                  )
                )
            }
          }

          failure match {
            case Some(value) => Left(value)
            case None =>
              Right(
                PackedTypeEquivalenceEvidence(
                  checkedBindings = effectiveBindings.size,
                  minimumWidth = minimum.get,
                  maximumWidth = maximum.get,
                  domainDigest = sha256(proofLines.result().mkString("\n"))
                )
              )
          }
        }
    }
  }

  private def buildAxes(
      module: CanonicalModuleView,
      references: DomainReferences,
      configuration: AliasSafetyConfiguration
  ): Either[AliasSafetyViolation, Vector[DomainAxis]] = {
    val axes = Vector.newBuilder[DomainAxis]

    references.integerParameters.toVector.sortBy(_.value).foreach { id =>
      module.integerParameter(id) match {
        case Some(parameter) => axes += integerAxis(parameter)
        case None =>
          return Left(
            violation(
              AliasSafetyReason.PackedWidthDomainUnproven,
              s"integer parameter '${id.value}' has no complete admitted domain"
            )
          )
      }
    }
    references.booleanParameters.toVector.sortBy(_.value).foreach { id =>
      module.booleanParameter(id) match {
        case Some(parameter) => axes += booleanAxis(parameter)
        case None =>
          return Left(
            violation(
              AliasSafetyReason.PackedWidthDomainUnproven,
              s"Boolean parameter '${id.value}' has no complete admitted domain"
            )
          )
      }
    }
    references.generateIndices.toVector.sortBy(_.value).foreach { id =>
      module.generateIndex(id) match {
        case Some(index) =>
          val count = index.maximum - index.minimum + 1
          if (count > configuration.maximumDomainBindings) {
            return Left(
              violation(
                AliasSafetyReason.DomainExpansionLimit,
                s"generate index '${id.value}' requires $count admitted bindings; configured limit is ${configuration.maximumDomainBindings}"
              )
            )
          }
          axes += generateAxis(index)
        case None =>
          return Left(
            violation(
              AliasSafetyReason.PackedWidthDomainUnproven,
              s"generate index '${id.value}' has no complete admitted domain"
            )
          )
      }
    }
    Right(axes.result().sortBy(_.key))
  }

  private def integerAxis(parameter: IntegerParameter): DomainAxis =
    DomainAxis(
      s"integer:${parameter.id.value}",
      parameter.domain.admittedValues.sorted.map(IntegerParameterValue(parameter.id, _))
    )

  private def booleanAxis(parameter: BooleanParameter): DomainAxis =
    DomainAxis(
      s"boolean:${parameter.id.value}",
      parameter.domain.admittedValues
        .sortBy(value => if (value) 1 else 0)
        .map(BooleanParameterValue(parameter.id, _))
    )

  private def generateAxis(index: GenerateIndex): DomainAxis = {
    val count = (index.maximum - index.minimum + 1).toInt
    DomainAxis(
      s"generate:${index.id.value}",
      Vector.tabulate(count) { offset =>
        GenerateIndexValue(index.id, index.minimum + offset)
      }
    )
  }

  private def intReferences(expression: IntExpr): DomainReferences = expression match {
    case IntExpr.Literal(_)          => DomainReferences()
    case IntExpr.ParameterRef(id)    => DomainReferences(integerParameters = Set(id))
    case IntExpr.GenerateIndexRef(id) => DomainReferences(generateIndices = Set(id))
    case IntExpr.Negate(value)       => intReferences(value)
    case IntExpr.Add(left, right)    => intReferences(left) ++ intReferences(right)
    case IntExpr.Subtract(left, right) => intReferences(left) ++ intReferences(right)
    case IntExpr.Multiply(left, right) => intReferences(left) ++ intReferences(right)
    case IntExpr.Divide(left, right) => intReferences(left) ++ intReferences(right)
    case IntExpr.Modulo(left, right) => intReferences(left) ++ intReferences(right)
    case IntExpr.Min(left, right)    => intReferences(left) ++ intReferences(right)
    case IntExpr.Max(left, right)    => intReferences(left) ++ intReferences(right)
    case IntExpr.Select(condition, yes, no) =>
      boolReferences(condition) ++ intReferences(yes) ++ intReferences(no)
    case IntExpr.CeilLog2(value)     => intReferences(value)
    case IntExpr.AddressWidth(value) => intReferences(value)
    case IntExpr.Pow2(value)         => intReferences(value)
  }

  private def boolReferences(expression: BoolExpr): DomainReferences = expression match {
    case BoolExpr.Literal(_)       => DomainReferences()
    case BoolExpr.ParameterRef(id) => DomainReferences(booleanParameters = Set(id))
    case BoolExpr.LessThan(left, right) => intReferences(left) ++ intReferences(right)
    case BoolExpr.LessThanOrEqual(left, right) => intReferences(left) ++ intReferences(right)
    case BoolExpr.GreaterThan(left, right) => intReferences(left) ++ intReferences(right)
    case BoolExpr.GreaterThanOrEqual(left, right) => intReferences(left) ++ intReferences(right)
    case BoolExpr.Equal(left, right) => intReferences(left) ++ intReferences(right)
    case BoolExpr.NotEqual(left, right) => intReferences(left) ++ intReferences(right)
    case BoolExpr.IsPow2(value) => intReferences(value)
    case BoolExpr.Not(value) => boolReferences(value)
    case BoolExpr.And(left, right) => boolReferences(left) ++ boolReferences(right)
    case BoolExpr.Or(left, right) => boolReferences(left) ++ boolReferences(right)
  }

  private def evaluateInt(
      expression: IntExpr,
      binding: EvaluationBinding,
      configuration: AliasSafetyConfiguration
  ): Either[String, BigInt] = expression match {
    case IntExpr.Literal(value) => Right(value)
    case IntExpr.ParameterRef(id) =>
      binding.integerParameters.get(id) match {
        case Some(value) => Right(value)
        case None        => Left(s"integer parameter '${id.value}' is unbound")
      }
    case IntExpr.GenerateIndexRef(id) =>
      binding.generateIndices.get(id) match {
        case Some(value) => Right(value)
        case None        => Left(s"generate index '${id.value}' is unbound")
      }
    case IntExpr.Negate(value) => unaryInt(value, binding, configuration)(value => -value)
    case IntExpr.Add(left, right) => binaryInt(left, right, binding, configuration)(_ + _)
    case IntExpr.Subtract(left, right) => binaryInt(left, right, binding, configuration)(_ - _)
    case IntExpr.Multiply(left, right) => binaryInt(left, right, binding, configuration)(_ * _)
    case IntExpr.Divide(left, right) =>
      binaryIntChecked(left, right, binding, configuration) { (numerator, denominator) =>
        if (denominator == 0) Left("division by zero") else Right(numerator / denominator)
      }
    case IntExpr.Modulo(left, right) =>
      binaryIntChecked(left, right, binding, configuration) { (numerator, denominator) =>
        if (denominator == 0) Left("modulo by zero") else Right(numerator % denominator)
      }
    case IntExpr.Min(left, right) => binaryInt(left, right, binding, configuration)((leftValue, rightValue) => leftValue.min(rightValue))
    case IntExpr.Max(left, right) => binaryInt(left, right, binding, configuration)((leftValue, rightValue) => leftValue.max(rightValue))
    case IntExpr.Select(condition, yes, no) =>
      evaluateBool(condition, binding, configuration) match {
        case Right(true)  => evaluateInt(yes, binding, configuration)
        case Right(false) => evaluateInt(no, binding, configuration)
        case Left(error)  => Left(error)
      }
    case IntExpr.CeilLog2(value) =>
      evaluateInt(value, binding, configuration) match {
        case Right(result) if result > 0 => Right(ceilLog2(result))
        case Right(result)               => Left(s"ceil-log2 operand $result is not positive")
        case Left(error)                 => Left(error)
      }
    case IntExpr.AddressWidth(value) =>
      evaluateInt(value, binding, configuration) match {
        case Right(result) if result > 0 => Right(ceilLog2(result).max(BigInt(1)))
        case Right(result)               => Left(s"address-width operand $result is not positive")
        case Left(error)                 => Left(error)
      }
    case IntExpr.Pow2(value) =>
      evaluateInt(value, binding, configuration) match {
        case Right(result) if result < 0 => Left(s"pow2 exponent $result is negative")
        case Right(result) if result > configuration.maximumPow2Exponent =>
          Left(
            s"pow2 exponent $result exceeds configured limit ${configuration.maximumPow2Exponent}"
          )
        case Right(result) => Right(BigInt(1) << result.toInt)
        case Left(error)   => Left(error)
      }
  }

  private def evaluateBool(
      expression: BoolExpr,
      binding: EvaluationBinding,
      configuration: AliasSafetyConfiguration
  ): Either[String, Boolean] = expression match {
    case BoolExpr.Literal(value) => Right(value)
    case BoolExpr.ParameterRef(id) =>
      binding.booleanParameters.get(id) match {
        case Some(value) => Right(value)
        case None        => Left(s"Boolean parameter '${id.value}' is unbound")
      }
    case BoolExpr.LessThan(left, right) => compareInt(left, right, binding, configuration)(_ < _)
    case BoolExpr.LessThanOrEqual(left, right) => compareInt(left, right, binding, configuration)(_ <= _)
    case BoolExpr.GreaterThan(left, right) => compareInt(left, right, binding, configuration)(_ > _)
    case BoolExpr.GreaterThanOrEqual(left, right) => compareInt(left, right, binding, configuration)(_ >= _)
    case BoolExpr.Equal(left, right) => compareInt(left, right, binding, configuration)(_ == _)
    case BoolExpr.NotEqual(left, right) => compareInt(left, right, binding, configuration)(_ != _)
    case BoolExpr.IsPow2(value) =>
      evaluateInt(value, binding, configuration) match {
        case Right(result) => Right(result > 0 && (result & (result - 1)) == 0)
        case Left(error)   => Left(error)
      }
    case BoolExpr.Not(value) =>
      evaluateBool(value, binding, configuration) match {
        case Right(result) => Right(!result)
        case Left(error)   => Left(error)
      }
    case BoolExpr.And(left, right) =>
      binaryBool(left, right, binding, configuration)(_ && _)
    case BoolExpr.Or(left, right) =>
      binaryBool(left, right, binding, configuration)(_ || _)
  }

  private def unaryInt(
      value: IntExpr,
      binding: EvaluationBinding,
      configuration: AliasSafetyConfiguration
  )(operation: BigInt => BigInt): Either[String, BigInt] =
    evaluateInt(value, binding, configuration) match {
      case Right(result) => Right(operation(result))
      case Left(error)   => Left(error)
    }

  private def binaryInt(
      left: IntExpr,
      right: IntExpr,
      binding: EvaluationBinding,
      configuration: AliasSafetyConfiguration
  )(operation: (BigInt, BigInt) => BigInt): Either[String, BigInt] =
    binaryIntChecked(left, right, binding, configuration) { (leftValue, rightValue) =>
      Right(operation(leftValue, rightValue))
    }

  private def binaryIntChecked(
      left: IntExpr,
      right: IntExpr,
      binding: EvaluationBinding,
      configuration: AliasSafetyConfiguration
  )(operation: (BigInt, BigInt) => Either[String, BigInt]): Either[String, BigInt] =
    evaluateInt(left, binding, configuration) match {
      case Left(error) => Left(error)
      case Right(leftValue) =>
        evaluateInt(right, binding, configuration) match {
          case Left(error)       => Left(error)
          case Right(rightValue) => operation(leftValue, rightValue)
        }
    }

  private def compareInt(
      left: IntExpr,
      right: IntExpr,
      binding: EvaluationBinding,
      configuration: AliasSafetyConfiguration
  )(operation: (BigInt, BigInt) => Boolean): Either[String, Boolean] =
    evaluateInt(left, binding, configuration) match {
      case Left(error) => Left(error)
      case Right(leftValue) =>
        evaluateInt(right, binding, configuration) match {
          case Left(error)       => Left(error)
          case Right(rightValue) => Right(operation(leftValue, rightValue))
        }
    }

  private def binaryBool(
      left: BoolExpr,
      right: BoolExpr,
      binding: EvaluationBinding,
      configuration: AliasSafetyConfiguration
  )(operation: (Boolean, Boolean) => Boolean): Either[String, Boolean] =
    evaluateBool(left, binding, configuration) match {
      case Left(error) => Left(error)
      case Right(leftValue) =>
        evaluateBool(right, binding, configuration) match {
          case Left(error)       => Left(error)
          case Right(rightValue) => Right(operation(leftValue, rightValue))
        }
    }

  private def ceilLog2(value: BigInt): BigInt =
    if (value == 1) BigInt(0) else BigInt((value - 1).bitLength)

  private[safety] def nameOriginKey(value: NameOrigin): String = value match {
    case NameOrigin.Unnamed          => "unnamed"
    case NameOrigin.Explicit(name)   => s"explicit:$name"
    case NameOrigin.Reflected(name)  => s"reflected:$name"
    case NameOrigin.Generated        => "generated"
    case NameOrigin.Unknown          => "unknown"
  }

  private def violation(
      code: String,
      message: String,
      relatedSymbol: Option[SymbolId] = None
  ): AliasSafetyViolation = AliasSafetyViolation(code, message, relatedSymbol)

  private[safety] def sha256(value: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest
      .digest(value.getBytes("UTF-8"))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
  }
}
