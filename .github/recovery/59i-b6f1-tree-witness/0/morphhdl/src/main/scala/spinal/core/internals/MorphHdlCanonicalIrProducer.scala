package spinal.core.internals

import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable.ArrayBuffer

import morphhdl.ir.v1.{
  CanonicalIrHandoff,
  CanonicalIrHandoffFailure,
  CanonicalIrSchema,
  CanonicalIrValidator,
  Declaration,
  DeclarationKind,
  Design,
  Driver,
  DriverCoverage,
  DriverId,
  DriverKind,
  IntegerParameter,
  IntegerParameterDomain,
  IntExpr,
  IrAttribute,
  IrComment,
  Module,
  ModuleId,
  NameOrigin,
  Observability,
  PackedType,
  PackedValueSemantics,
  ParameterId,
  PortDirection,
  ReferenceId,
  RtlExpr,
  Scope,
  ScopeId,
  ScopeKind,
  Signedness,
  SymbolId,
  AttributeKind => IrAttributeKind
}
import spinal.core.{
  AttributeFlag,
  AttributeInteger,
  AttributeString,
  BaseType,
  Bits,
  BlackBox,
  Bool,
  ClockEnableTag,
  ClockTag,
  CommentTag,
  Component,
  COMMENT_ATTRIBUTE,
  COMMENT_TYPE_ATTRIBUTE,
  DEFAULT_ATTRIBUTE,
  ElaborationIntegerExpression,
  ElaborationIntegerParameter,
  ElaborationIntegerParameterRoot,
  Nameable,
  ParameterizedWidth,
  ResetTag,
  SInt,
  UInt
}

/** Stable fail-closed diagnostic raised by the bounded production producer. */
final class MorphHdlCanonicalIrProducerException(
    val code: String,
    val detail: String
) extends IllegalArgumentException(s"[$code] $detail")

/**
  * Generation-scoped result cell installed into a normal SpinalHDL phase plan.
  *
  * The mutable cell is orchestration-only. The value it publishes is the
  * immutable validated handoff and is exposed only after generation succeeds.
  * A native diagnostic retry may replace an unpublished attempt in the same
  * generation.
  */
final class MorphHdlCanonicalIrCapture private[internals] () {
  private val retained = new AtomicReference[CanonicalIrHandoff]()
  private val retainedPlan = new AtomicReference[Vector[String]]()

  private[internals] def retainPlan(value: Vector[String]): Unit = {
    if (value == null || value.exists(_ == null))
      throw new IllegalArgumentException("canonical IR phase plan must not contain null")
    retainedPlan.set(value)
  }

  private[internals] def complete(value: CanonicalIrHandoff): Unit = {
    if (value == null)
      throw new IllegalArgumentException("canonical IR capture cannot retain null")
    retained.set(value)
  }

  def isComplete: Boolean = retained.get() != null

  def handoff: CanonicalIrHandoff = {
    val value = retained.get()
    if (value == null)
      throw new IllegalStateException(
        "canonical IR was not captured by a successful pre-emission phase"
      )
    value
  }

  def phaseClassNames: Vector[String] = {
    val value = retainedPlan.get()
    if (value == null)
      throw new IllegalStateException("canonical IR capture phase plan is unavailable")
    value
  }
}

/**
  * Direct native-graph producer for the bounded simple-wire canonical profile.
  *
  * This producer deliberately supports only one flat, combinational module
  * with full-object root-scope assignments from direct references or exact
  * literals. Every other native construct fails closed. Parameter identity and
  * domains come only from retained typed roots and exact evidence; rendered
  * expression text and emitted HDL are never inspected. Exact structured
  * source positions are not present on this native boundary, so the bounded
  * profile leaves source-location fields empty instead of parsing line-comment
  * or diagnostic strings. Native declaration attribute and comment objects are
  * copied directly, and every native elimination blocker is conservatively
  * represented by the corresponding `Observability` contract.
  */
object MorphHdlCanonicalIrProducer {
  private val ModuleIdentifier = ModuleId.unsafe("module.0")
  private val RootScopeIdentifier = ScopeId.unsafe("scope.0")

  private final case class ParameterEntry(
      root: ElaborationIntegerParameterRoot,
      schema: ElaborationIntegerParameter,
      id: ParameterId,
      admittedValues: Vector[BigInt]
  )

  private final case class DirectWidthEvidence(
      root: ElaborationIntegerParameterRoot,
      admittedValues: Vector[BigInt]
  )

  /** Retains the immutable graph before native unnamed-alias simplification. */
  private final class GraphSnapshot {
    private val retained = new AtomicReference[CanonicalIrHandoff]()

    def capture(top: Component, keepNamedDeclarations: Boolean): Unit = {
      def produce(value: Component): CanonicalIrHandoff =
        MorphHdlCanonicalIrProducer.produce(value, keepNamedDeclarations)
      retained.set(produce(top))
    }

    def handoff: CanonicalIrHandoff = {
      val value = retained.get()
      if (value == null)
        fail(
          "MORPH-IR-PRODUCER-GRAPH-SNAPSHOT-MISSING",
          "post-validation canonical capture requires the pre-simplification graph snapshot"
        )
      value
    }
  }

  private final class GraphSnapshotPhase(snapshot: GraphSnapshot)
      extends PhaseMisc {
    override def impl(pc: PhaseContext): Unit =
      snapshot.capture(
        pc.topLevel,
        keepNamedDeclarations = !pc.config.removePruned
      )
  }

  private final class CapturePhase(
      capture: MorphHdlCanonicalIrCapture,
      snapshot: GraphSnapshot,
      phasePlan: () => Vector[String]
  ) extends PhaseMisc {
    override def impl(pc: PhaseContext): Unit = {
      val top = pc.topLevel
      if (top == null)
        fail(
          "MORPH-IR-PRODUCER-TOP-MISSING",
          "pre-emission canonical capture requires one elaborated top component"
        )
      val retainedPlan = phasePlan()
      validateFinalPhasePlan(retainedPlan)
      capture.retainPlan(retainedPlan)
      capture.complete(snapshot.handoff)
    }
  }

  def newCapture(): MorphHdlCanonicalIrCapture =
    new MorphHdlCanonicalIrCapture()

  /** Install the graph snapshot before alias simplification and its release after validation. */
  def install(
      capture: MorphHdlCanonicalIrCapture
  )(phases: ArrayBuffer[Phase]): Unit = {
    if (capture == null)
      throw new IllegalArgumentException("canonical IR capture must not be null")
    if (phases == null)
      throw new IllegalArgumentException("native phase plan must not be null")
    if (phases.exists(_ == null))
      fail(
        "MORPH-IR-PRODUCER-PHASE-PLAN-INVALID",
        "canonical capture requires a native phase plan without null entries"
      )

    val crossClock = phases.zipWithIndex.collect {
      case (phase, index) if phase.getClass == classOf[PhaseCheckCrossClock] => index
    }.toVector
    if (crossClock.size != 1)
      fail(
        "MORPH-IR-PRODUCER-PHASE-PLAN-INVALID",
        s"canonical capture requires exactly one PhaseCheckCrossClock, found ${crossClock.size}"
      )

    val boundary = crossClock.head + 1
    val later = phases.drop(boundary)
    if (
      !later.exists(_.isInstanceOf[PhasePropagateNames]) ||
      !later.exists(_.isInstanceOf[PhaseAllocateNames]) ||
      !later.exists(_.isInstanceOf[PhaseVerilog])
    )
      fail(
        "MORPH-IR-PRODUCER-PHASE-PLAN-INVALID",
        "canonical capture requires name propagation, name allocation and Verilog emission after cross-clock validation"
      )

    val propagation = phases.zipWithIndex.collect {
      case (phase, index) if phase.isInstanceOf[PhasePropagateNames] => index
    }.toVector
    val allocation = phases.zipWithIndex.collect {
      case (phase, index) if phase.isInstanceOf[PhaseAllocateNames] => index
    }.toVector
    val emission = phases.zipWithIndex.collect {
      case (phase, index) if phase.isInstanceOf[PhaseVerilog] => index
    }.toVector
    if (propagation.size != 1 || allocation.size != 1 || emission.size != 1)
      fail(
        "MORPH-IR-PRODUCER-PHASE-PLAN-INVALID",
        "canonical capture requires exactly one name-propagation, name-allocation and Verilog-emission phase"
      )
    if (!(
      crossClock.head < propagation.head &&
        propagation.head < allocation.head &&
        allocation.head < emission.head
    ))
      fail(
        "MORPH-IR-PRODUCER-PHASE-PLAN-INVALID",
        "canonical capture requires cross-clock validation before ordered name propagation, name allocation and Verilog emission"
      )

    val reflection = phases.zipWithIndex.collect {
      case (phase, index) if phase.getClass == classOf[PhaseNameNodesByReflection] =>
        index
    }.toVector
    val widthInference = phases.zipWithIndex.collect {
      case (phase, index) if phase.getClass == classOf[PhaseInferWidth] => index
    }.toVector
    val normalization = phases.zipWithIndex.collect {
      case (phase, index) if phase.getClass == classOf[PhaseNormalizeNodeInputs] =>
        index
    }.toVector
    val simplification = phases.zipWithIndex.collect {
      case (phase, index) if phase.getClass == classOf[PhaseSimplifyNodes] => index
    }.toVector
    if (
      reflection.size != 1 || widthInference.size != 1 ||
      normalization.size != 1 || simplification.size != 1 ||
      !(reflection.head < widthInference.head &&
        widthInference.head < normalization.head &&
        normalization.head < simplification.head &&
        simplification.head < crossClock.head)
    )
      fail(
        "MORPH-IR-PRODUCER-PHASE-PLAN-INVALID",
        "canonical graph snapshot requires unique ordered width normalization and node simplification before cross-clock validation"
      )
    val aliasRemoval = phases.zipWithIndex.collect {
      case (phase, index)
          if normalization.head < index && index < simplification.head &&
            phase.getClass == classOf[PhaseRemoveIntermediateUnnameds] =>
        index
    }.toVector
    if (aliasRemoval.size != 1)
      fail(
        "MORPH-IR-PRODUCER-PHASE-PLAN-INVALID",
        s"canonical graph snapshot requires exactly one unnamed-intermediate removal between width normalization and node simplification, found ${aliasRemoval.size}"
      )

    val snapshot = new GraphSnapshot()
    phases.insert(aliasRemoval.head, new GraphSnapshotPhase(snapshot))
    val captureBoundary = phases.indexWhere(
      _.getClass == classOf[PhaseCheckCrossClock]
    ) + 1

    phases.insert(
      captureBoundary,
      new CapturePhase(
        capture,
        snapshot,
        () => phases.toVector.map(_.getClass.getName)
      )
    )
  }

  private def validateFinalPhasePlan(phases: Vector[String]): Unit = {
    if (phases == null || phases.exists(_ == null))
      fail(
        "MORPH-IR-PRODUCER-PHASE-PLAN-INVALID",
        "canonical capture requires a complete non-null final phase plan"
      )
    def uniqueIndex(name: String): Int = {
      val indices = phases.zipWithIndex.collect {
        case (candidate, index) if candidate == name => index
      }
      if (indices.size != 1)
        fail(
          "MORPH-IR-PRODUCER-PHASE-PLAN-INVALID",
          s"canonical capture requires exactly one $name in the final phase plan"
        )
      indices.head
    }

    val crossClock = uniqueIndex(classOf[PhaseCheckCrossClock].getName)
    val capture = uniqueIndex(classOf[CapturePhase].getName)
    val snapshot = uniqueIndex(classOf[GraphSnapshotPhase].getName)
    val reflection = uniqueIndex(classOf[PhaseNameNodesByReflection].getName)
    val widthInference = uniqueIndex(classOf[PhaseInferWidth].getName)
    val normalization = uniqueIndex(classOf[PhaseNormalizeNodeInputs].getName)
    val simplification = uniqueIndex(classOf[PhaseSimplifyNodes].getName)
    val propagation = uniqueIndex(classOf[PhasePropagateNames].getName)
    val allocation = uniqueIndex(classOf[PhaseAllocateNames].getName)
    val emission = uniqueIndex(classOf[PhaseVerilog].getName)
    val aliasRemoval = phases.zipWithIndex.collect {
      case (candidate, index)
          if candidate == classOf[PhaseRemoveIntermediateUnnameds].getName &&
            normalization < index && index < simplification =>
        index
    }
    if (
      aliasRemoval.size != 1 || snapshot + 1 != aliasRemoval.head ||
      !(reflection < widthInference && widthInference < normalization &&
        normalization < snapshot && snapshot < simplification &&
        simplification < crossClock) ||
      capture != crossClock + 1 ||
      !(capture < propagation && propagation < allocation && allocation < emission)
    )
      fail(
        "MORPH-IR-PRODUCER-PHASE-PLAN-INVALID",
        "canonical capture must remain immediately after cross-clock validation and before ordered naming and emission"
      )
  }

  /** Build and validate one immutable handoff directly from the live native graph. */
  private def produce(
      top: Component,
      keepNamedDeclarations: Boolean
  ): CanonicalIrHandoff = {
    if (top == null)
      fail("MORPH-IR-PRODUCER-TOP-MISSING", "canonical producer top must not be null")
    if (top.isInstanceOf[BlackBox] && top.asInstanceOf[BlackBox].isBlackBox)
      fail(
        "MORPH-IR-PRODUCER-BLACKBOX-UNSUPPORTED",
        "simple-wire profile does not support a blackbox top"
      )
    if (top.children.nonEmpty)
      fail(
        "MORPH-IR-PRODUCER-HIERARCHY-UNSUPPORTED",
        "simple-wire profile requires exactly one module and no child components"
      )

    val statements = top.dslBody.statementIterable.toVector
    statements.collectFirst { case _: TreeStatement => () }.foreach { _ =>
      fail(
        "MORPH-IR-PRODUCER-NESTED-SCOPE-UNSUPPORTED",
        "simple-wire profile supports only root-scope declarations and assignments"
      )
    }

    val nativeDeclarations = statements.collect { case value: BaseType => value }
    statements.foreach {
      case _: BaseType               => ()
      case _: DataAssignmentStatement => ()
      case _: spinal.core.Mem[_] =>
        fail(
          "MORPH-IR-PRODUCER-MEMORY-UNSUPPORTED",
          "simple-wire profile does not support memories or memory ports"
        )
      case other =>
        fail(
          "MORPH-IR-PRODUCER-STATEMENT-UNSUPPORTED",
          s"simple-wire profile does not support native statement kind ${other.getClass.getName}"
        )
    }

    val parameterEntries = ArrayBuffer.empty[ParameterEntry]
    val declarationIds = new java.util.IdentityHashMap[BaseType, SymbolId]()

    def parameterIdFor(
        root: ElaborationIntegerParameterRoot,
        schema: ElaborationIntegerParameter,
        admittedValues: Vector[BigInt]
    ): ParameterId = {
      val existing = parameterEntries.find(entry => entry.root eq root)
      existing match {
        case Some(entry) =>
          if ((entry.schema ne schema) || entry.admittedValues != admittedValues)
            fail(
              "MORPH-IR-PRODUCER-PARAMETER-IDENTITY-CONFLICT",
              "one typed parameter root is associated with conflicting schema or exact-domain evidence"
            )
          entry.id
        case None =>
          val id = ParameterId.unsafe(s"parameter.${parameterEntries.size}")
          parameterEntries += ParameterEntry(root, schema, id, admittedValues)
          id
      }
    }

    def directWidth(value: BaseType): IntExpr =
      ParameterizedWidth.expressionOf(value) match {
        case None => IntExpr.Literal(BigInt(value.getBitsWidth))
        case Some(expression) =>
          val direct = ParameterizedWidth.parameterOf(value).getOrElse {
            fail(
              "MORPH-IR-PRODUCER-WIDTH-EXPRESSION-UNSUPPORTED",
              "simple-wire profile supports only literal widths or one exact direct typed parameter"
            )
          }
          val evidence = validateDirectWidth(expression, direct)
          IntExpr.ParameterRef(
            parameterIdFor(evidence.root, direct, evidence.admittedValues)
          )
      }

    val declarations = nativeDeclarations.zipWithIndex.map { case (value, index) =>
      if (value.isReg)
        fail(
          "MORPH-IR-PRODUCER-REGISTER-UNSUPPORTED",
          "simple-wire profile does not support registers"
        )
      if (value.isAnalog || value.isInOut)
        fail(
          "MORPH-IR-PRODUCER-INOUT-UNSUPPORTED",
          "simple-wire profile does not support analog or inout declarations"
        )

      val id = SymbolId.unsafe(s"symbol.$index")
      declarationIds.put(value, id)
      val kind = declarationKind(value)

      val packed = value match {
        case _: Bool =>
          PackedType(
            IntExpr.Literal(BigInt(1)),
            Signedness.Unsigned,
            PackedValueSemantics.Boolean
          )
        case _: Bits =>
          PackedType(
            directWidth(value),
            Signedness.Unsigned,
            PackedValueSemantics.BitVector
          )
        case _: UInt =>
          PackedType(
            directWidth(value),
            Signedness.Unsigned,
            PackedValueSemantics.UnsignedInteger
          )
        case _: SInt =>
          PackedType(
            directWidth(value),
            Signedness.Signed,
            PackedValueSemantics.SignedInteger
          )
        case _ =>
          fail(
            "MORPH-IR-PRODUCER-PACKED-TYPE-UNSUPPORTED",
            s"simple-wire profile does not support native declaration type ${value.getClass.getName}"
          )
      }

      Declaration(
        id = id,
        owner = RootScopeIdentifier,
        kind = kind,
        packedType = Some(packed),
        nameOrigin = nameOrigin(value),
        sourceLocation = None,
        observability = Observability(
          complete = true,
          externallyVisible = value.isInput || value.isOutput,
          keep =
            keepNamedDeclarations && value.isNamed &&
              (value.namePriority >= Nameable.USER_WEAK || value.isVital),
          preserve =
            value.dontSimplify || value.isVital ||
              value.existsTag(tag => !tag.canSymplifyHost),
          publicExport = value.hasTag(spinal.core.Verilator.public)
        ),
        attributes = declarationAttributes(value),
        comments = declarationComments(value)
      )
    }

    var referenceIndex = 0
    val nativeAssignments = statements.collect { case value: DataAssignmentStatement => value }
    val assignedTargets = new java.util.IdentityHashMap[BaseType, java.lang.Boolean]()
    val drivers = nativeAssignments.zipWithIndex.map { case (assignment, index) =>
      val target = assignment.target match {
        case value: BaseType if assignment.finalTarget eq value => value
        case _ =>
          fail(
            "MORPH-IR-PRODUCER-PARTIAL-ASSIGNMENT-UNSUPPORTED",
            "simple-wire profile supports only full-object assignment targets"
          )
      }
      if (assignedTargets.put(target, java.lang.Boolean.TRUE) != null)
        fail(
          "MORPH-IR-PRODUCER-ASSIGNMENT-OVERRIDE-UNSUPPORTED",
          "simple-wire profile does not support ordered repeated assignments to one declaration"
        )
      if (!(assignment.parentScope eq top.dslBody))
        fail(
          "MORPH-IR-PRODUCER-NESTED-SCOPE-UNSUPPORTED",
          "simple-wire profile supports only root-scope assignments"
        )
      val targetId = Option(declarationIds.get(target)).getOrElse {
        fail(
          "MORPH-IR-PRODUCER-TARGET-UNRESOLVED",
          "full-object assignment target is not a captured declaration identity"
        )
      }

      val value = assignment.source match {
        case source: BaseType =>
          val sourceId = Option(declarationIds.get(source)).getOrElse {
            fail(
              "MORPH-IR-PRODUCER-REFERENCE-UNRESOLVED",
              "direct assignment source is not a captured declaration identity"
            )
          }
          val id = ReferenceId.unsafe(s"reference.$referenceIndex")
          referenceIndex += 1
          RtlExpr.Ref(id, sourceId, RootScopeIdentifier)
        case literal: Literal if !literal.hasPoison() =>
          val width = literal match {
            case value: BitVectorLiteral => value.getWidth
            case _: BoolLiteral          => 1
            case _ =>
              fail(
                "MORPH-IR-PRODUCER-LITERAL-UNSUPPORTED",
                s"simple-wire profile does not support literal kind ${literal.getClass.getName}"
              )
          }
          RtlExpr.Literal(
            literal.getValue(),
            width,
            literal.isInstanceOf[SIntLiteral]
          )
        case other =>
          fail(
            "MORPH-IR-PRODUCER-EXPRESSION-UNSUPPORTED",
            s"simple-wire profile supports only direct references or exact literals, observed ${other.getClass.getName}"
          )
      }

      Driver(
        id = DriverId.unsafe(s"driver.$index"),
        owner = RootScopeIdentifier,
        target = targetId,
        kind = DriverKind.Continuous,
        coverage = DriverCoverage.FullObject,
        value = value
      )
    }

    val parameters = parameterEntries.map { entry =>
      val admitted = boundedValues(
        entry.schema.minimum,
        entry.schema.maximum,
        entry.admittedValues
      )
      IntegerParameter(
        id = entry.id,
        name = entry.schema.name,
        default = entry.schema.default,
        domain = IntegerParameterDomain(
          minimum = entry.schema.minimum,
          maximum = entry.schema.maximum,
          admittedValues = admitted
        ),
        sourceLocation = None
      )
    }.toVector

    val module = Module(
      id = ModuleIdentifier,
      logicalName = Option(top.definitionName).filter(_.nonEmpty).getOrElse("anonymous"),
      parameters = parameters,
      scopes = Vector(
        Scope(
          id = RootScopeIdentifier,
          parent = None,
          kind = ScopeKind.Module,
          label = None,
          sourceLocation = None
        )
      ),
      generateIndices = Vector.empty,
      declarations = declarations,
      drivers = drivers,
      sourceLocation = None
    )
    val design = Design(
      version = CanonicalIrSchema.schemaVersion,
      stage = CanonicalIrSchema.stage,
      top = ModuleIdentifier,
      modules = Vector(module)
    )

    CanonicalIrHandoff.create(design) match {
      case Right(handoff) => handoff
      case Left(failure)  => failValidation(failure)
    }
  }

  private def validateDirectWidth(
      expression: ElaborationIntegerExpression,
      direct: ElaborationIntegerParameter
  ): DirectWidthEvidence = {
    parameterDomainCardinality(direct.minimum, direct.maximum)
    val root = expression.completedParameterRoots match {
      case Vector(value) => value
      case _ =>
        fail(
          "MORPH-IR-PRODUCER-PARAMETER-ROOT-UNSUPPORTED",
          "direct typed width must retain exactly one declaration root"
        )
    }
    val schemaMatches = expression.parameters match {
      case Vector(value) => value eq direct
      case _             => false
    }
    val exactDomain = Option(expression.exactDomain).flatten
    val evidenceMatches =
      expression.exactDomain != null && expression.exactDomain.exists { domain =>
      (domain.root eq root) &&
      (domain.parameter eq direct) &&
      domain.hasCompleteCoverage &&
      domain.evaluations.forall { case (rootValue, result) => rootValue == result }
      }
    if (
      !schemaMatches || !evidenceMatches || !expression.hasExactAuthority ||
      !root.isAuthoritativeSchema(direct) || expression.generateIndex.nonEmpty ||
      expression.default != direct.default || expression.minimum != direct.minimum ||
      expression.maximum != direct.maximum
    )
      fail(
        "MORPH-IR-PRODUCER-PARAMETER-EVIDENCE-INVALID",
        "direct typed width lacks one complete identity-domain root and schema proof"
      )
    DirectWidthEvidence(
      root,
      exactDomain.get.evaluations.map(_._1)
    )
  }

  private def nameOrigin(value: BaseType): NameOrigin =
    if (value.isUnnamed) NameOrigin.Unnamed
    else {
      val retainedName = value.getName("")
      if (retainedName.isEmpty) NameOrigin.Generated
      else
        value.namePriority match {
          case Nameable.USER_SET | Nameable.USER_WEAK =>
            NameOrigin.Explicit(retainedName)
          case Nameable.DATAMODEL_STRONG | Nameable.DATAMODEL_WEAK =>
            NameOrigin.Reflected(retainedName)
          case Nameable.REMOVABLE =>
            NameOrigin.Generated
          case priority =>
            fail(
              "MORPH-IR-PRODUCER-NAME-PRIORITY-UNSUPPORTED",
              s"simple-wire profile cannot classify native name-priority category $priority"
            )
        }
    }

  private def declarationKind(value: BaseType): DeclarationKind = {
    val clock = value.hasTag(classOf[ClockTag])
    val reset = value.hasTag(classOf[ResetTag])
    val clockEnable = value.hasTag(classOf[ClockEnableTag])
    val controlKinds = Vector(clock, reset, clockEnable).count(identity)
    if (controlKinds > 1)
      fail(
        "MORPH-IR-PRODUCER-CONTROL-KIND-AMBIGUOUS",
        "simple-wire profile requires one unambiguous declaration control role"
      )
    if (clock) DeclarationKind.Clock
    else if (reset) DeclarationKind.Reset
    else if (clockEnable)
      fail(
        "MORPH-IR-PRODUCER-CLOCK-ENABLE-UNSUPPORTED",
        "simple-wire profile has no canonical v1 clock-enable declaration kind"
      )
    else if (value.isInput) DeclarationKind.Port(PortDirection.Input)
    else if (value.isOutput) DeclarationKind.Port(PortDirection.Output)
    else if (value.isDirectionLess) DeclarationKind.InternalCombinational
    else
      fail(
        "MORPH-IR-PRODUCER-DIRECTION-UNSUPPORTED",
        "simple-wire profile encountered an unsupported declaration direction"
      )
  }

  private def declarationAttributes(value: BaseType): Vector[IrAttribute] =
    value.instanceAttributes.toVector.map { attribute =>
      val attributeValue = attribute match {
        case _: AttributeFlag          => None
        case string: AttributeString   => Some(string.value)
        case integer: AttributeInteger => Some(integer.value.toString)
        case other =>
          fail(
            "MORPH-IR-PRODUCER-ATTRIBUTE-UNSUPPORTED",
            s"simple-wire profile does not support native attribute kind ${other.getClass.getName}"
          )
      }
      val kind = attribute.attributeKind() match {
        case DEFAULT_ATTRIBUTE      => IrAttributeKind.Backend
        case COMMENT_ATTRIBUTE      => IrAttributeKind.CommentStyle
        case COMMENT_TYPE_ATTRIBUTE => IrAttributeKind.CommentStyle
        case other =>
          fail(
            "MORPH-IR-PRODUCER-ATTRIBUTE-KIND-UNSUPPORTED",
            s"simple-wire profile does not support native attribute classification ${Option(other).map(_.getClass.getName).getOrElse("<null>")}"
          )
      }
      IrAttribute(
        name = attribute.getName,
        value = attributeValue,
        kind = kind,
        sourceLocation = None
      )
    }

  private def declarationComments(value: BaseType): Vector[IrComment] =
    value.getTags().toVector.collect {
      case comment: CommentTag =>
        IrComment(comment.comment, sourceLocation = None)
    }

  private def boundedValues(
      minimum: BigInt,
      maximum: BigInt,
      exactValues: Vector[BigInt]
  ): Vector[BigInt] = {
    val cardinality = parameterDomainCardinality(minimum, maximum)
    if (
      exactValues == null || exactValues.exists(_ == null) ||
      exactValues.distinct.size != exactValues.size ||
      BigInt(exactValues.size) != cardinality || exactValues.isEmpty ||
      exactValues.min != minimum || exactValues.max != maximum
    )
      fail(
        "MORPH-IR-PRODUCER-PARAMETER-DOMAIN-EVIDENCE-INVALID",
        "simple-wire profile requires one retained exact value for every admitted integer parameter point"
      )
    exactValues.sorted
  }

  private def parameterDomainCardinality(
      minimum: BigInt,
      maximum: BigInt
  ): BigInt = {
    if (minimum == null || maximum == null || minimum > maximum)
      fail(
        "MORPH-IR-PRODUCER-PARAMETER-DOMAIN-INVALID",
        "simple-wire profile requires ordered, non-null integer parameter bounds"
      )
    val cardinality = maximum - minimum + 1
    if (cardinality > BigInt(CanonicalIrValidator.MaximumParameterDomainSize))
      fail(
        "MORPH-IR-PRODUCER-PARAMETER-DOMAIN-TOO-LARGE",
        s"simple-wire profile admits at most ${CanonicalIrValidator.MaximumParameterDomainSize} exact integer values, observed $cardinality"
      )
    cardinality
  }

  private def failValidation(failure: CanonicalIrHandoffFailure): Nothing =
    fail(
      "MORPH-IR-PRODUCER-CANONICAL-VALIDATION-FAILED",
      failure match {
        case CanonicalIrHandoffFailure.Validation(diagnostics) =>
          diagnostics.values
            .map(value => s"${value.code}@${value.pathString}")
            .mkString(", ")
        case CanonicalIrHandoffFailure.Contract(code, detail) =>
          s"$code: $detail"
      }
    )

  private def fail(code: String, detail: String): Nothing =
    throw new MorphHdlCanonicalIrProducerException(code, detail)
}
