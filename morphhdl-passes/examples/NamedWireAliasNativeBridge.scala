package morphhdl.examples

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.mutable.ArrayBuffer

import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
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
import morphhdl.ir.v1.SymbolId
import morphhdl.passes.api.AliasNameOrigin
import morphhdl.passes.api.IrSymbolId
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.WireAliasPassConfiguration
import morphhdl.passes.transform.NamedWireAliasEliminationPass
import spinal.core._
import spinal.core.internals._

/**
  * Native adapter for the WA-05 canonical pass, shared by the proof witnesses
  * and the WA-08 production handoff before name allocation and Verilog emission.
  *
  * The bridge is deliberately conservative. It offers the canonical pass only
  * root-scope, full-object, direct BaseType aliases whose preservation, type,
  * use-context and cycle safety can be established from native object identity.
  * It never parses generated HDL and never recognizes a component or signal
  * name. Candidate names come from retained source/elaboration Nameable metadata.
  */
private[examples] final class NamedWireAliasNativePhase extends Phase {
  private var completed = false
  private var visited = 0
  private var eliminated = Vector.empty[Int]
  private var eliminatedNames = Vector.empty[String]
  private var deferredSourceOrigins = Vector.empty[String]
  private var rejected = Map.empty[String, Int]
  private var rewrittenReferences = 0

  def report: NamedWireAliasNativeReport = {
    if (!completed)
      throw new IllegalStateException("WA-05 native witness phase did not execute")
    NamedWireAliasNativeReport(
      visitedCandidates = visited,
      eliminatedOrdinals = eliminated,
      eliminatedNames = eliminatedNames,
      rejectedByReason = rejected,
      rewrittenReferences = rewrittenReferences
    )
  }

  override def hasNetlistImpact: Boolean = true

  /** Stable provenance evidence for successor preference witnesses. */
  private[examples] def deferredPreferenceSourceOrigins: Vector[String] = {
    if (!completed)
      throw new IllegalStateException("WA-05 native witness phase did not execute")
    deferredSourceOrigins
  }

  private sealed trait NativeRewritePlan
  private final case class ForwardRewrite(
      relation: NativeCandidate,
      proof: NativeProof
  ) extends NativeRewritePlan
  private final case class PreferredSourceRewrite(
      relation: NativeCandidate,
      relationProof: NativeProof,
      removed: NativeCandidate,
      removedProof: NativeProof
  ) extends NativeRewritePlan
  private case object DeferredExpressionRewrite extends NativeRewritePlan

  override def impl(pc: PhaseContext): Unit = {
    val eliminatedBuilder = Vector.newBuilder[Int]
    val eliminatedNameBuilder = Vector.newBuilder[String]
    val deferredOriginBuilder = Vector.newBuilder[String]
    val deferredSources = new java.util.IdentityHashMap[BaseType, java.lang.Boolean]()
    var nextOrdinal = 0
    var progress = true

    while (progress) {
      progress = false
      val candidates = candidateSnapshot(pc)
      val iterator = candidates.iterator
      while (iterator.hasNext && !progress) {
        val candidate = iterator.next()
        val ordinal = nextOrdinal
        nextOrdinal += 1
        visited += 1

        proveCandidate(pc, candidate) match {
          case Left(reason) =>
            rejected = rejected.updated(reason, rejected.getOrElse(reason, 0) + 1)
          case Right(proof) => selectRewrite(pc, candidate, proof) match {
            case Left(reason) =>
              rejected = rejected.updated(reason, rejected.getOrElse(reason, 0) + 1)
            case Right(DeferredExpressionRewrite) =>
              if (
                deferredSources.put(candidate.source, java.lang.Boolean.TRUE) == null
              ) {
                deferredOriginBuilder += NativeWireNameProvenance.origin(candidate.source)
                  .map(originLabel).getOrElse("unknown")
              }
              rejected = rejected.updated(
                "WA05-NATIVE-DEFERRED-PREFERRED-ALIAS",
                rejected.getOrElse("WA05-NATIVE-DEFERRED-PREFERRED-ALIAS", 0) + 1
              )
            case Right(plan: ForwardRewrite) =>
              applyCanonicalDecision(plan.relation, plan.proof) match {
                case Left(reason) =>
                  rejected = rejected.updated(reason, rejected.getOrElse(reason, 0) + 1)
                case Right(_) =>
                  val replacements = rewriteNativeIdentity(
                    plan.relation.component,
                    plan.relation.alias,
                    plan.relation.source,
                    plan.relation.assignment
                  )
                  rewrittenReferences += replacements
                  eliminatedBuilder += ordinal
                  eliminatedNameBuilder += plan.relation.displayName
                  progress = true
              }
            case Right(plan: PreferredSourceRewrite) =>
              applyCanonicalPreferenceDecision(plan) match {
                case Left(reason) =>
                  rejected = rejected.updated(reason, rejected.getOrElse(reason, 0) + 1)
                case Right(_) =>
                  val replacements = rewriteNativeIdentity(
                    plan.removed.component,
                    plan.removed.alias,
                    plan.removed.source,
                    plan.removed.assignment
                  )
                  rewrittenReferences += replacements
                  eliminatedBuilder += ordinal
                  eliminatedNameBuilder += plan.removed.displayName
                  progress = true
              }
          }
        }
      }
    }

    eliminated = eliminatedBuilder.result()
    eliminatedNames = eliminatedNameBuilder.result()
    deferredSourceOrigins = deferredOriginBuilder.result().sorted
    completed = true
  }

  private final case class NativeCandidate(
      component: Component,
      alias: BaseType,
      source: BaseType,
      nameOrigin: NameOrigin,
      displayName: String,
      assignment: DataAssignmentStatement,
      useStatements: Vector[Statement]
  )

  private final case class NativeProof(
      packedType: PackedType,
      parameters: Vector[IntegerParameter]
  )

  /** Shared fail-closed identity/metadata proof for a native expression phase. */
  private[examples] def expressionRemovalBlocker(
      pc: PhaseContext,
      component: Component,
      alias: BaseType,
      assignment: DataAssignmentStatement,
      useStatements: Vector[Statement]
  ): Option[String] = {
    if (!preservationMetadataAllows(pc, alias, assignment))
      Some("PRESERVATION")
    else if (hasClockDomainUse(pc, alias))
      Some("CLOCK-DOMAIN")
    else if (hasReferencedMetadata(pc, alias))
      Some("REFERENCED-METADATA")
    else if (NativeWireAssignmentMetadata.retains(alias))
      Some("REGISTERED-IDENTITY")
    else if (!useStatements.forall(allowedUse(component, alias, _)))
      Some("USE-CONTEXT")
    else None
  }

  private def candidateSnapshot(pc: PhaseContext): Vector[NativeCandidate] = {
    val values = Vector.newBuilder[NativeCandidate]
    // Include reads owned by other components so hierarchy uses fail the same
    // local continuous-use proof instead of disappearing from the inventory.
    val statements = pc.components().toVector.flatMap(statementsOf)
    pc.components().foreach { component =>
      component.dslBody.walkDeclarations {
        case alias: BaseType =>
          NativeWireNameProvenance.successorExpressionOrigin(alias).foreach { nameOrigin =>
            candidateForValue(alias, nameOrigin, statements).foreach(values += _)
          }
        case _ =>
      }
    }
    values.result()
  }

  private def candidateForValue(
      alias: BaseType,
      nameOrigin: NameOrigin,
      statements: Vector[Statement]
  ): Option[NativeCandidate] = {
    val provenanceMatches = nameOrigin match {
      case NameOrigin.Unnamed => alias.isUnnamed
      case NameOrigin.Explicit(_) | NameOrigin.Reflected(_) | NameOrigin.Generated =>
        alias.isNamed
      case NameOrigin.Unknown => false
    }
    if (
      !provenanceMatches || !alias.isComb || !alias.isDirectionLess ||
      alias.isAnalog || alias.isTypeNode || alias.parentScope == null ||
      !(alias.parentScope eq alias.rootScopeStatement) ||
      !alias.hasOnlyOneStatement
    ) return None

    alias.head match {
      case assignment: DataAssignmentStatement
          if assignment.parentScope != null &&
            (assignment.parentScope eq alias.rootScopeStatement) &&
            (assignment.target eq alias) &&
            (assignment.finalTarget eq alias) =>
        assignment.source match {
          case source: BaseType if (source ne alias) =>
            val uses = statements.filter(statement =>
              (statement ne assignment) && references(statement, alias) > 0
            )
            Some(
              NativeCandidate(
                alias.component,
                alias,
                source,
                nameOrigin,
                nameOrigin.explicitName
                  .orElse(Option(alias.getName(""))).getOrElse(""),
                assignment,
                uses
              )
            )
          case _ => None
        }
      case _ => None
    }
  }

  private def explicitSourceName(alias: BaseType): Option[String] = {
    // This phase precedes PhasePropagateNames and PhaseAllocateNames. At this
    // boundary Nameable retains Scala val/Bundle/Area elaboration names and
    // explicit setName names; no emitted identifier or name pattern is used.
    // Naming is not a preservation tag: ordinary production aliases need no
    // fixture marker, and all real tags still veto removal below.
    val sourceNamed = readPrivateByte(alias, "namePriority").exists {
      case Nameable.USER_SET | Nameable.USER_WEAK |
          Nameable.DATAMODEL_STRONG | Nameable.DATAMODEL_WEAK => true
      case _ => false
    }
    if (alias.isNamed && sourceNamed) Option(alias.getName()).filter(_.trim.nonEmpty)
    else None
  }

  private def selectRewrite(
      pc: PhaseContext,
      candidate: NativeCandidate,
      proof: NativeProof
  ): Either[String, NativeRewritePlan] = {
    val sourceOrigin = NativeWireNameProvenance.origin(candidate.source)
      .getOrElse(NameOrigin.Unknown)
    if (
      !candidate.source.isComb || !candidate.source.isDirectionLess ||
      !aliasIsPreferred(candidate, sourceOrigin)
    ) Right(ForwardRewrite(candidate, proof))
    else {
      val statements = pc.components().toVector.flatMap(statementsOf)
      val direct = candidateForValue(candidate.source, sourceOrigin, statements)
      direct.flatMap(value => proveCandidate(pc, value) match {
        case Right(valueProof) => Some(value -> valueProof)
        case Left(_)           => None
      }) match {
        case Some((sourceCandidate, sourceProof)) =>
          // Reverse orientation is deliberately bounded to exact whole-RHS
          // edges. Those assignment boundaries remain the native type fences;
          // the bridge never substitutes through a nested receiver expression.
          val wholeRelation = candidate.useStatements.forall(
            wholeRhsUse(_, candidate.alias)
          )
          val wholeSource = sourceCandidate.useStatements.forall(
            wholeRhsUse(_, sourceCandidate.alias)
          )
          if (!wholeRelation || !wholeSource)
            Left("WA05-NATIVE-PREFERENCE-NON-DIRECT-RECEIVER")
          else
            Right(
              PreferredSourceRewrite(candidate, proof, sourceCandidate, sourceProof)
            )
        case None if expressionSourceIsIndependentlyRemovable(
              pc,
              candidate.source,
              sourceOrigin
            ) =>
          Right(DeferredExpressionRewrite)
        case None => Right(ForwardRewrite(candidate, proof))
      }
    }
  }

  private def aliasIsPreferred(
      candidate: NativeCandidate,
      sourceOrigin: NameOrigin
  ): Boolean =
    (meaningfulName(candidate.nameOrigin), meaningfulName(sourceOrigin)) match {
      case (Some(aliasName), Some(sourceName)) =>
        implicitly[Ordering[(Int, String, String)]].lt(
          (aliasName.length, aliasName, candidate.displayName),
          (sourceName.length, sourceName, Option(candidate.source.getName("")).getOrElse(""))
        )
      case (Some(_), None) =>
        sourceOrigin == NameOrigin.Unnamed || sourceOrigin == NameOrigin.Generated
      case _ => false
    }

  private def meaningfulName(origin: NameOrigin): Option[String] = origin match {
    case NameOrigin.Explicit(name)  => Some(name)
    case NameOrigin.Reflected(name) => Some(name)
    case _                          => None
  }

  private def originLabel(origin: NameOrigin): String = origin match {
    case _: NameOrigin.Explicit  => "explicit"
    case _: NameOrigin.Reflected => "reflected"
    case NameOrigin.Generated    => "generated"
    case NameOrigin.Unnamed      => "unnamed"
    case NameOrigin.Unknown      => "unknown"
  }

  private def expressionSourceIsIndependentlyRemovable(
      pc: PhaseContext,
      source: BaseType,
      sourceOrigin: NameOrigin
  ): Boolean = sourceOrigin match {
    case NameOrigin.Unnamed =>
      new UnnamedWireExpressionNativePhase().isIndependentlyRemovable(pc, source)
    case NameOrigin.Explicit(_) | NameOrigin.Reflected(_) | NameOrigin.Generated =>
      new NamedWireExpressionNativePhase().isIndependentlyRemovable(pc, source)
    case NameOrigin.Unknown => false
  }

  private def wholeRhsUse(statement: Statement, value: BaseType): Boolean =
    statement match {
      case assignment: DataAssignmentStatement => assignment.source eq value
      case _                                   => false
    }

  private def proveCandidate(
      pc: PhaseContext,
      candidate: NativeCandidate
  ): Either[String, NativeProof] = {
    val alias = candidate.alias
    val source = candidate.source
    val assignment = candidate.assignment

    if ((source.component ne candidate.component) || source.parentScope == null)
      Left("WA05-NATIVE-SOURCE-BOUNDARY")
    else if (!(source.parentScope eq source.rootScopeStatement))
      Left("WA05-NATIVE-SOURCE-SCOPE")
    else if (source.isAnalog || source.isInOut)
      Left("WA05-NATIVE-SOURCE-KIND")
    else if (!preservationMetadataAllows(pc, alias, assignment))
      Left("WA05-NATIVE-PRESERVATION")
    else if (hasClockDomainUse(pc, alias))
      Left("WA05-NATIVE-CLOCK-DOMAIN")
    else if (hasReferencedMetadata(pc, alias))
      Left("WA05-NATIVE-REFERENCED-METADATA")
    else if (NativeWireAssignmentMetadata.retains(alias))
      Left("WA05-NATIVE-REGISTERED-IDENTITY")
    else if (!candidate.useStatements.forall(allowedUse(candidate.component, alias, _)))
      Left("WA05-NATIVE-USE-CONTEXT")
    else if (createsCycle(candidate.component, alias, source))
      Left("WA05-NATIVE-CYCLE")
    else
      packedTypeProof(alias, source) match {
        case Some(value) => Right(value)
        case None        => Left("WA05-NATIVE-PACKED-TYPE")
      }
  }

  private def preservationMetadataAllows(
      pc: PhaseContext,
      alias: BaseType,
      assignment: DataAssignmentStatement
  ): Boolean =
    !alias.isFrozen() &&
      alias.isEmptyOfTag &&
      // Source locations are ubiquitous compiler metadata. They are observable
      // preservation material only when the selected backend configuration
      // will actually emit line comments.
      (!pc.config.genLineComments || Option(assignment.locationString).forall(_.isEmpty)) &&
      !readPrivateBoolean(alias, "dontSimplify").getOrElse(true)

  private def hasClockDomainUse(pc: PhaseContext, alias: BaseType): Boolean =
    pc.components().exists { component =>
      def uses(domain: ClockDomain): Boolean =
        clockDomainUses(component, domain, alias)

      // Clock/reset tags are already protected. softReset and pulled clock
      // controls can also be referenced outside the driving-expression graph.
      var found = uses(component.clockDomain.get)
      component.dslBody.walkStatements { statement =>
        statement.foreachClockDomain(domain => if (uses(domain)) found = true)
      }
      found
    }

  private def clockDomainUses(
      component: Component,
      domain: ClockDomain,
      alias: BaseType
  ): Boolean =
    domain != null &&
      Vector(domain.clock, domain.reset, domain.softReset, domain.clockEnable)
        .filter(_ != null).exists { control =>
          (control eq alias) ||
            component.pulledDataCache.get(control).exists(value => value eq alias)
        }

  private def hasReferencedMetadata(pc: PhaseContext, alias: BaseType): Boolean =
    pc.components().exists { component =>
      def dataUses(value: Data): Boolean =
        value != null && value.flatten.exists(_ eq alias)
      def expressionUses(value: Expression): Boolean =
        value != null && referencedBaseTypes(value).exists(_ eq alias)
      def domainUses(value: ClockDomain): Boolean =
        clockDomainUses(component, value, alias)
      def statementUses(value: Statement): Boolean = {
        var found = references(value, alias) > 0
        value.foreachClockDomain(domain => if (domainUses(domain)) found = true)
        found
      }
      // Native metadata can reference a declaration without a driving-expression
      // edge. Preserve those identities; remapping only RTL reads would leave
      // timing constraints, initialization or retained native contracts dangling.
      def tagUses(tag: SpinalTag): Boolean = tag match {
        case value: crossClockFalsePath if value.getClass == classOf[crossClockFalsePath] => value.source.exists(_ eq alias)
        case value: ClockDomainTag if value.getClass == classOf[ClockDomainTag] => domainUses(value.clockDomain)
        case value: ClockDomainReportTag if value.getClass == classOf[ClockDomainReportTag] => domainUses(value.clockDomain)
        case value: ClockTag if value.getClass == classOf[ClockTag] => domainUses(value.clockDomain)
        case value: ResetTag if value.getClass == classOf[ResetTag] => domainUses(value.clockDomain)
        case value: ClockEnableTag if value.getClass == classOf[ClockEnableTag] => domainUses(value.clockDomain)
        case value: ClockSyncTag if value.getClass == classOf[ClockSyncTag] => (value.a eq alias) || (value.b eq alias)
        case value: ClockDrivedTag if value.getClass == classOf[ClockDrivedTag] => value.driver eq alias
        case value: ClockDriverTag if value.getClass == classOf[ClockDriverTag] => value.drived eq alias
        case value: DefaultTag if value.getClass == classOf[DefaultTag] => value.that eq alias
        case value: ExternalDriverTag if value.getClass == classOf[ExternalDriverTag] => dataUses(value.driver)
        case value: VarAssignementTag if value.getClass == classOf[VarAssignementTag] => dataUses(value.from)
        case value: GenericValue if value.getClass == classOf[GenericValue] => expressionUses(value.e)
        case value: SimInitTag if value.getClass == classOf[SimInitTag] => expressionUses(value.value)
        case value: PhaseNextifyTag if value.getClass == classOf[PhaseNextifyTag] => value.dest eq alias
        case value: MemReadBufferTag if value.getClass == classOf[MemReadBufferTag] =>
          (value.reg eq alias) || statementUses(value.rs) ||
            value.through.exists {
              case expression: Expression => expressionUses(expression)
              case statement: Statement => statementUses(statement)
              case _ => true
            }
        case value: MemBlackboxOf if value.getClass == classOf[MemBlackboxOf] =>
          var found = false
          value.mem.foreachStatements(statement => if (statementUses(statement)) found = true)
          found
        case value: Attribute if value.getClass == classOf[AttributeFlag] ||
            value.getClass == classOf[AttributeString] ||
            value.getClass == classOf[AttributeInteger] => false
        case value: IfDefTag if value.getClass == classOf[IfDefTag] => false
        case value: CommentTag if value.getClass == classOf[CommentTag] => false
        case value: CrossClockBufferDepth if value.getClass == classOf[CrossClockBufferDepth] => false
        case value: TagAFixTruncated if value.getClass == classOf[TagAFixTruncated] => false
        case value: MemSymbolesTag if value.getClass == classOf[MemSymbolesTag] =>
          (value.mapping eq null) || value.mapping.exists(mapping =>
            (mapping eq null) || mapping.getClass != classOf[MemSymbolesMapping])
        // Match flags by identity: user subclasses and custom equality cannot
        // borrow a native tag's reference-free metadata contract.
        case value if Vector[SpinalTag](
            spinal.core.Verilator.public, spinal.core.Verilator.tracing_off,
            spinal.core.Verilator.tracing_on, spinal.lib.KeepAttribute.keep,
            spinal.lib.KeepAttribute.syn_keep_verilog, spinal.lib.KeepAttribute.syn_keep_vhdl,
            allowDirectionLessIoTag, unsetRegIfNoAssignementTag, allowAssignmentOverride,
            allowFloating, allowOutOfRangeLiterals, dontObfuscate, noInit, unusedTag,
            noCombinatorialLoopCheck, noLatchCheck, noBackendCombMerge, noBackendSyncMerge,
            reportIncludeSourceLocation, crossClockDomain, crossClockBuffer, randomBoot,
            tagAutoResize, tagTruncated, tagAFixResized, AllowPartialyAssignedTag,
            AllowMixedWidth, IsInterface, uLogic, noNumericType, addDefaultGenericValue,
            spinal.core.sim.SimPublic, spinal.core.sim.TracingOff).exists(_ eq value) => false
        case value if NativeWireAssignmentMetadata.isReferenceFreeTag(value) => false
        // A custom tag can hide references outside constructor fields. Without
        // a known complete metadata contract, retain the candidate identity.
        case _ => true
      }
      def tagsUse(value: SpinalTagReady): Boolean = value.getTags().exists(tagUses)

      var found = tagsUse(component)
      val defaultDomain = component.clockDomain.get
      if (defaultDomain != null && tagsUse(defaultDomain)) found = true
      component.dslBody.walkStatements { statement =>
        statement match {
          case tagged: SpinalTagReady => if (tagsUse(tagged)) found = true
          case _ =>
        }
        statement.walkDrivingExpressions {
          case tagged: SpinalTagReady => if (tagsUse(tagged)) found = true
          case _ =>
        }
        statement.foreachClockDomain { domain =>
          if (domain != null && tagsUse(domain)) found = true
        }
      }
      found
    }

  private def allowedUse(
      component: Component,
      alias: BaseType,
      statement: Statement
  ): Boolean = statement match {
    // A root default assignment can still belong to an always block when its
    // receiver also has conditional or partial drivers. Require the complete
    // receiver to have exactly this one full-object continuous assignment.
    case assignment: DataAssignmentStatement
        if assignment.parentScope != null &&
          (assignment.parentScope eq assignment.rootScopeStatement) &&
          (assignment.target eq assignment.finalTarget) &&
          (assignment.finalTarget ne alias) &&
          (assignment.finalTarget.component eq component) &&
          assignment.finalTarget.hasOnlyOneStatement &&
          assignment.finalTarget.isComb &&
          !assignment.finalTarget.isAnalog &&
          !assignment.finalTarget.isInputOrInOut =>
      true
    case _ => false
  }

  private def packedTypeProof(
      alias: BaseType,
      source: BaseType
  ): Option[NativeProof] = {
    if (alias.getBitsWidth != source.getBitsWidth || alias.getBitsWidth < 1)
      return None

    val semantics = (packedSemantics(alias), packedSemantics(source)) match {
      case (Some(left), Some(right)) if left == right => left
      case _                                          => return None
    }

    (ParameterizedWidth.expressionOf(alias), ParameterizedWidth.expressionOf(source)) match {
      case (None, None) =>
        Some(
          NativeProof(
            PackedType(
              IntExpr.Literal(BigInt(alias.getBitsWidth)),
              semantics._1,
              semantics._2
            ),
            Vector.empty
          )
        )
      case (Some(left), Some(right)) if left eq right =>
        val minimum = left.minimum
        val maximum = left.maximum
        val size = maximum - minimum + 1
        if (
          minimum < 1 || maximum < minimum ||
          size > BigInt(morphhdl.ir.v1.CanonicalIrValidator.MaximumParameterDomainSize)
        ) None
        else {
          val parameterId = ParameterId.unsafe("parameter.native-width")
          val domain = (minimum to maximum).toVector
          Some(
            NativeProof(
              PackedType(
                IntExpr.ParameterRef(parameterId),
                semantics._1,
                semantics._2
              ),
              Vector(
                IntegerParameter(
                  id = parameterId,
                  name = "NATIVE_WIDTH",
                  default = left.default,
                  domain = IntegerParameterDomain(minimum, maximum, domain)
                )
              )
            )
          )
        }
      case _ => None
    }
  }

  private def packedSemantics(
      value: BaseType
  ): Option[(Signedness, PackedValueSemantics)] = value match {
    case _: Bool => Some(Signedness.Unsigned -> PackedValueSemantics.Boolean)
    case _: Bits => Some(Signedness.Unsigned -> PackedValueSemantics.BitVector)
    case _: UInt => Some(Signedness.Unsigned -> PackedValueSemantics.UnsignedInteger)
    case _: SInt => Some(Signedness.Signed -> PackedValueSemantics.SignedInteger)
    case _       => None
  }

  private def applyCanonicalDecision(
      candidate: NativeCandidate,
      proof: NativeProof
  ): Either[String, Unit] = {
    val moduleId = ModuleId.unsafe("module.native-witness")
    val scopeId = ScopeId.unsafe("scope.native-root")
    val sourceId = SymbolId.unsafe("symbol.native-source")
    val aliasId = SymbolId.unsafe("symbol.native-alias")
    val declarations = Vector.newBuilder[Declaration]
    val drivers = Vector.newBuilder[Driver]

    declarations += Declaration(
      id = sourceId,
      owner = scopeId,
      kind = sourceKind(candidate.source),
      packedType = Some(proof.packedType),
      nameOrigin = NativeWireNameProvenance.origin(candidate.source)
        .getOrElse(NameOrigin.Unknown),
      sourceLocation = None,
      observability = sourceObservability(candidate.source)
    )
    declarations += Declaration(
      id = aliasId,
      owner = scopeId,
      kind = DeclarationKind.InternalCombinational,
      packedType = Some(proof.packedType),
      nameOrigin = candidate.nameOrigin,
      sourceLocation = None,
      observability = Observability.Unobserved
    )
    drivers += Driver(
      id = DriverId.unsafe("driver.native-alias"),
      owner = scopeId,
      target = aliasId,
      kind = DriverKind.Continuous,
      coverage = DriverCoverage.FullObject,
      value = RtlExpr.Ref(
        id = ReferenceId.unsafe("reference.native-alias-source"),
        target = sourceId,
        owner = scopeId
      )
    )

    candidate.useStatements.indices.foreach { index =>
      val sinkId = SymbolId.unsafe(s"symbol.native-sink-$index")
      declarations += Declaration(
        id = sinkId,
        owner = scopeId,
        kind = DeclarationKind.Port(PortDirection.Output),
        packedType = Some(proof.packedType),
        nameOrigin = NameOrigin.Explicit(s"nativeSink$index"),
        sourceLocation = None,
        observability = Observability(complete = true, externallyVisible = true)
      )
      drivers += Driver(
        id = DriverId.unsafe(s"driver.native-sink-$index"),
        owner = scopeId,
        target = sinkId,
        kind = DriverKind.Continuous,
        coverage = DriverCoverage.FullObject,
        value = RtlExpr.Ref(
          id = ReferenceId.unsafe(s"reference.native-sink-$index-alias"),
          target = aliasId,
          owner = scopeId
        )
      )
    }

    val canonical = Design(
      version = CanonicalIrSchema.schemaVersion,
      stage = CanonicalIrSchema.stage,
      top = moduleId,
      modules = Vector(
        Module(
          id = moduleId,
          logicalName = "NativeWitnessModule",
          parameters = proof.parameters,
          scopes = Vector(
            Scope(
              id = scopeId,
              parent = None,
              kind = ScopeKind.Module,
              label = None,
              sourceLocation = None
            )
          ),
          generateIndices = Vector.empty,
          declarations = declarations.result(),
          drivers = drivers.result(),
          sourceLocation = None
        )
      )
    )

    val result = NamedWireAliasEliminationPass.run(
      canonical,
      WireAliasPassConfiguration.selectedForTesting(
      morphhdl.passes.api.PassId.NamedWireAliasElimination
    )
    )
    if (
      result.status == PassExecutionStatus.Changed &&
      result.eliminationReport.eliminated.map(_.aliasSymbol) ==
        Vector(IrSymbolId.unsafe(aliasId.value)) &&
      result.eliminationReport.eliminated.head.sourceSymbol ==
        IrSymbolId.unsafe(sourceId.value) &&
      result.eliminationReport.eliminated.head.nameOrigin == reportOrigin(candidate.nameOrigin)
    ) Right(())
    else Left("WA05-NATIVE-CANONICAL-DECISION")
  }

  /**
    * Validate reverse name-preference orientation with the actual two direct
    * native edges: `source := sourceSource; alias := source`. No representative
    * expression is synthesized. Whole-RHS receiver identities are reflected as
    * direct canonical sinks, so the canonical pass sees the same removable
    * source, provenance ordering, receiver cardinality and packed type.
    */
  private def applyCanonicalPreferenceDecision(
      plan: PreferredSourceRewrite
  ): Either[String, Unit] = {
    if (
      (plan.relation.source ne plan.removed.alias) ||
      plan.relationProof.packedType != plan.removedProof.packedType ||
      plan.relationProof.parameters != plan.removedProof.parameters
    ) return Left("WA05-NATIVE-PREFERENCE-CHAIN-TYPE")

    val moduleId = ModuleId.unsafe("module.native-preference-witness")
    val scopeId = ScopeId.unsafe("scope.native-preference-root")
    val terminalId = SymbolId.unsafe("symbol.native-preference-terminal")
    val sourceId = SymbolId.unsafe("symbol.native-preference-source")
    val aliasId = SymbolId.unsafe("symbol.native-preference-alias")
    val declarations = Vector.newBuilder[Declaration]
    val drivers = Vector.newBuilder[Driver]

    declarations += Declaration(
      terminalId,
      scopeId,
      sourceKind(plan.removed.source),
      Some(plan.relationProof.packedType),
      NativeWireNameProvenance.origin(plan.removed.source)
        .getOrElse(NameOrigin.Unknown),
      None,
      sourceObservability(plan.removed.source)
    )
    declarations += Declaration(
      sourceId,
      scopeId,
      DeclarationKind.InternalCombinational,
      Some(plan.relationProof.packedType),
      plan.removed.nameOrigin,
      None,
      Observability.Unobserved
    )
    declarations += Declaration(
      aliasId,
      scopeId,
      DeclarationKind.InternalCombinational,
      Some(plan.relationProof.packedType),
      plan.relation.nameOrigin,
      None,
      Observability.Unobserved
    )
    drivers += Driver(
      DriverId.unsafe("driver.native-preference-source"),
      scopeId,
      sourceId,
      DriverKind.Continuous,
      DriverCoverage.FullObject,
      RtlExpr.Ref(
        ReferenceId.unsafe("reference.native-preference-source-terminal"),
        terminalId,
        scopeId
      )
    )
    drivers += Driver(
      DriverId.unsafe("driver.native-preference-alias"),
      scopeId,
      aliasId,
      DriverKind.Continuous,
      DriverCoverage.FullObject,
      RtlExpr.Ref(
        ReferenceId.unsafe("reference.native-preference-alias-source"),
        sourceId,
        scopeId
      )
    )

    def addSinks(prefix: String, count: Int, target: SymbolId): Unit =
      (0 until count).foreach { index =>
        val sinkId = SymbolId.unsafe(s"symbol.native-preference-$prefix-sink-$index")
        declarations += Declaration(
          sinkId,
          scopeId,
          DeclarationKind.Port(PortDirection.Output),
          Some(plan.relationProof.packedType),
          NameOrigin.Explicit(s"nativePreference${prefix.capitalize}Sink$index"),
          None,
          Observability(complete = true, externallyVisible = true)
        )
        drivers += Driver(
          DriverId.unsafe(s"driver.native-preference-$prefix-sink-$index"),
          scopeId,
          sinkId,
          DriverKind.Continuous,
          DriverCoverage.FullObject,
          RtlExpr.Ref(
            ReferenceId.unsafe(
              s"reference.native-preference-$prefix-sink-$index-value"
            ),
            target,
            scopeId
          )
        )
      }

    addSinks("alias", plan.relation.useStatements.size, aliasId)
    addSinks(
      "source",
      plan.removed.useStatements.count(_ ne plan.relation.assignment),
      sourceId
    )

    val canonical = Design(
      CanonicalIrSchema.schemaVersion,
      CanonicalIrSchema.stage,
      moduleId,
      Vector(
        Module(
          moduleId,
          "NativePreferenceWitnessModule",
          plan.relationProof.parameters,
          Vector(Scope(scopeId, None, ScopeKind.Module)),
          Vector.empty,
          declarations.result(),
          drivers.result()
        )
      )
    )
    val result = NamedWireAliasEliminationPass.run(
      canonical,
      WireAliasPassConfiguration.selectedForTesting(
        morphhdl.passes.api.PassId.NamedWireAliasElimination
      )
    )
    val expectedOrigin = reportOrigin(plan.removed.nameOrigin)
    val selected = result.eliminationReport.eliminated.find { value =>
      value.aliasSymbol == IrSymbolId.unsafe(sourceId.value) &&
      value.sourceSymbol == IrSymbolId.unsafe(terminalId.value)
    }
    if (
      result.status == PassExecutionStatus.Changed &&
      selected.exists(_.nameOrigin == expectedOrigin)
    ) Right(())
    else Left("WA05-NATIVE-PREFERENCE-CANONICAL-DECISION")
  }

  private def sourceKind(value: BaseType): DeclarationKind =
    if (value.isInput) DeclarationKind.Port(PortDirection.Input)
    else if (value.isOutput) DeclarationKind.Port(PortDirection.Output)
    else if (value.isReg) DeclarationKind.Register
    else DeclarationKind.InternalCombinational

  private def sourceObservability(value: BaseType): Observability =
    Observability(
      complete = true,
      externallyVisible = value.isInput || value.isOutput
    )

  private def reportOrigin(value: NameOrigin): AliasNameOrigin = value match {
    case NameOrigin.Explicit(name)  => AliasNameOrigin.Explicit(name)
    case NameOrigin.Reflected(name) => AliasNameOrigin.Reflected(name)
    case NameOrigin.Generated       => AliasNameOrigin.Generated
    case NameOrigin.Unnamed         => AliasNameOrigin.Unnamed
    case other => throw new IllegalStateException(
      s"unsupported native named-alias report origin $other"
    )
  }

  private def rewriteNativeIdentity(
      component: Component,
      alias: BaseType,
      source: BaseType,
      aliasAssignment: DataAssignmentStatement
  ): Int = {
    val statements = statementsOf(component)
    var replacements = 0
    statements.foreach { statement =>
      if (statement ne aliasAssignment) {
        statement.walkRemapDrivingExpressions {
          case reference: BaseType if reference eq alias =>
            replacements += 1
            source
          case other => other
        }
      }
    }

    aliasAssignment.removeStatement()
    alias.removeStatement()

    val remaining = statementsOf(component).map(references(_, alias)).sum
    if (remaining != 0)
      throw new IllegalStateException(
        s"WA-05 native witness rewrite left $remaining reference(s) to a removed identity"
      )
    replacements
  }

  private def createsCycle(
      component: Component,
      alias: BaseType,
      source: BaseType
  ): Boolean = {
    val edges = scala.collection.mutable.LinkedHashMap.empty[BaseType, Vector[BaseType]]
    statementsOf(component).foreach {
      case assignment: DataAssignmentStatement
          if assignment.parentScope != null &&
            (assignment.parentScope eq assignment.rootScopeStatement) &&
            (assignment.target eq assignment.finalTarget) &&
            assignment.finalTarget.isComb =>
        val dependencies = referencedBaseTypes(assignment.source)
          .filter(_.component eq component)
          .distinct
        edges.update(assignment.finalTarget, dependencies)
      case _ =>
    }

    val pending = scala.collection.mutable.Stack[BaseType](source)
    val visited = scala.collection.mutable.HashSet.empty[BaseType]
    while (pending.nonEmpty) {
      val current = pending.pop()
      if (current eq alias) return true
      if (!visited.contains(current)) {
        visited += current
        edges.getOrElse(current, Vector.empty).reverse.foreach(pending.push)
      }
    }
    false
  }

  private def statementsOf(component: Component): Vector[Statement] = {
    val values = Vector.newBuilder[Statement]
    component.dslBody.walkStatements(values += _)
    values.result()
  }

  private def references(statement: Statement, target: BaseType): Int = {
    var count = 0
    statement.walkDrivingExpressions {
      case value: BaseType if value eq target => count += 1
      case _                                  =>
    }
    count
  }

  private def referencedBaseTypes(expression: Expression): Vector[BaseType] = {
    val values = Vector.newBuilder[BaseType]
    expression match {
      case value: BaseType => values += value
      case _               =>
    }
    expression.walkDrivingExpressions {
      case value: BaseType => values += value
      case _               =>
    }
    values.result()
  }

  private def readPrivateBoolean(
      value: AnyRef,
      name: String
  ): Option[Boolean] = {
    var current: Class[_] = value.getClass
    while (current != null) {
      try {
        val field = current.getDeclaredField(name)
        field.setAccessible(true)
        return Some(field.getBoolean(value))
      } catch {
        case _: NoSuchFieldException => current = current.getSuperclass
        case _: Throwable            => return None
      }
    }
    None
  }

  private def readPrivateByte(value: AnyRef, name: String): Option[Byte] = {
    var current: Class[_] = value.getClass
    while (current != null) {
      try {
        val field = current.getDeclaredField(name)
        field.setAccessible(true)
        return Some(field.getByte(value))
      } catch {
        case _: NoSuchFieldException => current = current.getSuperclass
        case _: Throwable            => return None
      }
    }
    None
  }
}

private[examples] final case class NamedWireAliasNativeReport(
    visitedCandidates: Int,
    eliminatedOrdinals: Vector[Int],
    eliminatedNames: Vector[String],
    rejectedByReason: Map[String, Int],
    rewrittenReferences: Int
) {
  def eliminatedCount: Int = eliminatedOrdinals.size

  def toJson: String = {
    val rejected = rejectedByReason.toVector.sortBy(_._1).map { case (key, value) =>
      s"    ${quote(key)}: $value"
    }
    Vector(
      "{",
      "  \"schema_version\": 1,",
      "  \"pass_id\": \"wire-alias-named\",",
      "  \"executed_before_name_allocation\": true,",
      s"""  "visited_candidates": $visitedCandidates,""",
      s"""  "eliminated_count": $eliminatedCount,""",
      s"""  "rewritten_reference_count": $rewrittenReferences,""",
      s"""  "eliminated_ordinals": [${eliminatedOrdinals.mkString(", ")}],""",
      s"""  "eliminated_names": [${eliminatedNames.map(quote).mkString(", ")}],""",
      "  \"rejected_by_reason\": {",
      rejected.mkString(",\n"),
      "  }",
      "}",
      ""
    ).mkString("\n")
  }

  private def quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private[examples] object NamedWireAliasWitnessPhasePlan {
  def install(
      config: SpinalConfig,
      phase: Option[NamedWireAliasNativePhase]
  ): Unit = {
    config.phasesInserters += { phases: ArrayBuffer[Phase] =>
      val nativeAliasPasses = phases.zipWithIndex.collect {
        case (value: PhaseRemoveIntermediateUnnameds, index) => index
      }
      if (nativeAliasPasses.size < 3)
        throw new IllegalStateException(
          s"WA-05 witness expected three native alias-removal phases, found ${nativeAliasPasses.size}"
        )

      val postWidthTypeCleanupIndex = nativeAliasPasses(1)
      phases.update(
        postWidthTypeCleanupIndex,
        new PhaseRemoveIntermediateUnnameds(true)
      )
      nativeAliasPasses.drop(3).reverse.foreach(index => phases.remove(index))
      val finalAliasCleanupIndex = nativeAliasPasses(2)
      phase match {
        case Some(value) => phases.update(finalAliasCleanupIndex, value)
        case None        => phases.remove(finalAliasCleanupIndex)
      }
    }
  }
}

/**
  * Emits either the common pre-pass StreamFifo reference or the candidate
  * produced after the actual WA-05 canonical decision and native identity
  * rewrite. Both legs use the same MorphHDL structured parameterized backend.
  */
object ParameterizedStreamFifoNamedPassWitness {
  def main(args: Array[String]): Unit = {
    if (args.length != 4)
      throw new IllegalArgumentException(
        "usage: MODE(reference|candidate) OUTPUT_DIRECTORY OUTPUT_FILE REPORT_FILE"
      )

    val mode = args(0)
    val outputDirectory = Paths.get(args(1)).toAbsolutePath.normalize
    val outputFile = args(2)
    val reportFile = Paths.get(args(3)).toAbsolutePath.normalize
    val phase = mode match {
      case "reference" => None
      case "candidate" => Some(new NamedWireAliasNativePhase)
      case other        => throw new IllegalArgumentException(s"unsupported witness mode '$other'")
    }

    Files.createDirectories(outputDirectory)
    Option(reportFile.getParent).foreach(path => Files.createDirectories(path))

    val config = SpinalConfig(
      targetDirectory = outputDirectory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    )
    config.netlistFileName = outputFile
    NamedWireAliasWitnessPhasePlan.install(config, phase)

    val width = HdlInt.param(
      "WIDTH",
      default = BigInt(8),
      min = BigInt(1),
      max = BigInt(64)
    )
    val depth = HdlInt.param(
      "DEPTH",
      default = BigInt(5),
      min = BigInt(1),
      max = BigInt(8)
    )

    val generated = MorphVerilog(morphhdl.MorphWireAssignmentPasses(config, enabled = false)) {
      new ParameterizedStreamFifo(width, depth)
    }
    val generatedPath = Paths
      .get(generated.generatedSourcesPaths.head)
      .toAbsolutePath
      .normalize
    val text = new String(Files.readAllBytes(generatedPath), StandardCharsets.UTF_8)
    if (!text.contains("parameter integer WIDTH") || !text.contains("parameter integer DEPTH"))
      throw new IllegalStateException(
        "WA-05 witness lost symbolic WIDTH or DEPTH during structured emission"
      )

    val json = phase match {
      case Some(value) =>
        val result = value.report
        if (result.eliminatedCount < 1)
          throw new IllegalStateException(
            "WA-05 witness phase executed but eliminated no named alias"
          )
        result.toJson
      case None =>
        """{
          |  "schema_version": 1,
          |  "mode": "common-pre-pass-reference",
          |  "native_full_alias_removal_suppressed": true
          |}
          |""".stripMargin
    }
    Files.write(reportFile, json.getBytes(StandardCharsets.UTF_8))
    println(generatedPath)
  }
}
