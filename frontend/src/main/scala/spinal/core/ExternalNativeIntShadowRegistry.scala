package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.collection.mutable

/** Kind of one explicitly selected native Scala `Int` value. */
sealed trait ExternalNativeIntShadowKind {
  def label: String
}

object ExternalNativeIntShadowKind {
  case object ConstructorArgument extends ExternalNativeIntShadowKind {
    override val label: String = "constructor-argument"
  }

  case object LocalValue extends ExternalNativeIntShadowKind {
    override val label: String = "local-value"
  }
}

/** Deterministic identity for one selected native `Int` slot. */
final case class ExternalNativeIntShadowSlotToken(
    name: String,
    kind: ExternalNativeIntShadowKind,
    sourceLocation: String
)

/**
  * One selected native `Int` with both its unchanged witness and symbolic
  * definition/actual expressions.
  */
final case class ExternalNativeIntShadowSlot(
    token: ExternalNativeIntShadowSlotToken,
    witness: Int,
    definitionExpression: ElaborationIntegerExpression,
    actualExpression: ElaborationIntegerExpression
)

/** Shadow provenance retained against one exact native child Component. */
final case class ExternalNativeIntComponentShadowRecord(
    boundaryToken: ExternalNativeIntFormalizationToken,
    parentBoundaryToken: Option[ExternalNativeIntFormalizationToken],
    ownerClassName: String,
    binding: ExternalFormalParameterBinding,
    slots: Vector[ExternalNativeIntShadowSlot]
)

/** Shadow provenance retained against one exact native Data region. */
final case class ExternalNativeIntRegionShadowRecord(
    boundaryToken: ExternalNativeIntFormalizationToken,
    parentBoundaryToken: Option[ExternalNativeIntFormalizationToken],
    ownerClassName: String,
    formalBinding: Option[ExternalFormalParameterBinding],
    slots: Vector[ExternalNativeIntShadowSlot]
)

/**
  * Immutable capture returned after one boundary constructor has completed.
  * The constructor is package-private so callers cannot fabricate provenance.
  */
final class ExternalNativeIntShadowCapture[A] private[core] (
    val result: A,
    private[core] val expression: ElaborationIntegerExpression,
    private[core] val token: ExternalNativeIntFormalizationToken,
    private[core] val parentToken: Option[ExternalNativeIntFormalizationToken],
    private[core] val pendingSlots: Vector[ExternalNativeIntShadowPendingSlot]
)

private[core] final case class ExternalNativeIntShadowPendingSlot(
    token: ExternalNativeIntShadowSlotToken,
    witness: Int
)

private[core] final class ExternalNativeIntShadowComponentIdentityRef(
    value: Component,
    queue: ReferenceQueue[Component]
) extends WeakReference[Component](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalNativeIntShadowComponentIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

private[core] final class ExternalNativeIntShadowRegionIdentityRef(
    value: Data,
    queue: ReferenceQueue[Data]
) extends WeakReference[Data](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalNativeIntShadowRegionIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/**
  * MorphHDL-owned shadow provenance registry for native Scala `Int` values.
  *
  * A boundary keeps a thread-local stack only while an untouched constructor is
  * executing. The ordinary `Int` value is never boxed, replaced or used as a
  * lookup key. Explicitly selected argument/local slots are recorded by a
  * deterministic source token, and the completed immutable record is attached
  * to the exact returned Component or Data identity through weak keys.
  *
  * Increment 49 deliberately accepts only direct aliases of the boundary
  * witness. Arithmetic and predicate propagation are introduced by Increment
  * 50; a changed witness therefore fails closed instead of being guessed.
  */
object ExternalNativeIntShadowRegistry {
  private final class ActiveBoundary(
      val expression: ElaborationIntegerExpression,
      val token: ExternalNativeIntFormalizationToken,
      val parentToken: Option[ExternalNativeIntFormalizationToken]
  ) {
    val slots = mutable.LinkedHashMap.empty[
      (ExternalNativeIntShadowKind, String),
      ExternalNativeIntShadowPendingSlot
    ]
  }

  private val active = new ThreadLocal[List[ActiveBoundary]]
  private val componentQueue = new ReferenceQueue[Component]()
  private val regionQueue = new ReferenceQueue[Data]()
  private val components = mutable.HashMap.empty[
    ExternalNativeIntShadowComponentIdentityRef,
    Vector[ExternalNativeIntComponentShadowRecord]
  ]
  private val regions = mutable.HashMap.empty[
    ExternalNativeIntShadowRegionIdentityRef,
    ExternalNativeIntRegionShadowRecord
  ]

  /**
    * Execute one untouched constructor with an active shadow scope. The direct
    * constructor argument is selected automatically; additional direct local
    * aliases may be selected through `morphhdl.frontend.shadowInt`.
    */
  def capture[A](
      expression: ElaborationIntegerExpression,
      token: ExternalNativeIntFormalizationToken,
      argumentName: String
  )(body: => A): ExternalNativeIntShadowCapture[A] = {
    validateExpression(expression, token)
    validateName(argumentName, token.callSite, "constructor argument")

    val previous = Option(active.get()).getOrElse(Nil)
    val boundary = new ActiveBoundary(
      expression = expression,
      token = token,
      parentToken = previous.headOption.map(_.token)
    )
    active.set(boundary :: previous)
    try {
      record(
        value = expression.default.toInt,
        name = argumentName,
        kind = ExternalNativeIntShadowKind.ConstructorArgument,
        sourceLocation = token.callSite,
        requireBoundary = true
      )
      val result = body
      new ExternalNativeIntShadowCapture[A](
        result = result,
        expression = expression,
        token = token,
        parentToken = boundary.parentToken,
        pendingSlots = boundary.slots.values.toVector
      )
    } finally {
      val current = Option(active.get()).getOrElse(Nil)
      current match {
        case head :: tail if head eq boundary =>
          if (tail.isEmpty) active.remove() else active.set(tail)
        case _ =>
          active.remove()
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-SCOPE-CORRUPT",
            s"native Int shadow boundary '${token.role}' did not close in stack order",
            sourceOf(token)
          )
      }
    }
  }

  /** Compiler/runtime hook: record a selected argument when a boundary exists. */
  def captureArgument(
      value: Int,
      name: String,
      sourceLocation: String
  ): Int =
    record(
      value,
      name,
      ExternalNativeIntShadowKind.ConstructorArgument,
      sourceLocation,
      requireBoundary = false
    )

  /** Compiler/runtime hook: record a selected direct local alias. */
  def captureLocal(
      value: Int,
      name: String,
      sourceLocation: String,
      requireBoundary: Boolean
  ): Int =
    record(
      value,
      name,
      ExternalNativeIntShadowKind.LocalValue,
      sourceLocation,
      requireBoundary
    )

  /** Attach a completed capture to one exact native child Component. */
  def attachComponent[C <: Component](
      component: C,
      binding: ExternalFormalParameterBinding,
      capture: ExternalNativeIntShadowCapture[C]
  ): C = synchronized {
    if (component == null)
      throw new IllegalArgumentException("native Int shadow component must not be null")
    if (binding == null)
      throw new IllegalArgumentException("native Int shadow binding must not be null")
    if (capture == null)
      throw new IllegalArgumentException("native Int shadow capture must not be null")
    if (!(capture.result.asInstanceOf[AnyRef] eq component)) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-RESULT-MISMATCH",
        s"shadow capture '${capture.token.role}' was attached to a different Component identity",
        sourceOf(capture.token)
      )
    }
    if (component.getClass.getName != binding.ownerClassName) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-OWNER-MISMATCH",
        s"shadow capture '${capture.token.role}' belongs to '${component.getClass.getName}' but formal '${binding.formal.name}' belongs to '${binding.ownerClassName}'",
        binding.sourceLocation.orElse(sourceOf(capture.token))
      )
    }
    if (!equivalentExpression(capture.expression, binding.actual)) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-ACTUAL-MISMATCH",
        s"shadow capture '${capture.token.role}' retained '${capture.expression.verilog}' but the component actual is '${binding.actual.verilog}'",
        binding.sourceLocation.orElse(sourceOf(capture.token))
      )
    }

    val definition = formalExpression(binding.formal)
    val slots = finalizeSlots(capture, definition, binding.actual)
    val incoming = ExternalNativeIntComponentShadowRecord(
      boundaryToken = capture.token,
      parentBoundaryToken = capture.parentToken,
      ownerClassName = component.getClass.getName,
      binding = binding,
      slots = slots
    )

    reapComponents()
    val lookup = new ExternalNativeIntShadowComponentIdentityRef(component, null)
    val existing = components.getOrElse(lookup, Vector.empty)
    existing.find(_.binding.formal.name == binding.formal.name) match {
      case Some(record) if !equivalentComponentRecord(record, incoming) =>
        fail(
          "MORPH-FRONTEND-NATIVE-INT-SHADOW-COMPONENT-CONFLICT",
          s"one exact Component received conflicting shadow provenance for formal '${binding.formal.name}'",
          binding.sourceLocation.orElse(sourceOf(capture.token))
        )
      case Some(_) =>
      case None =>
        components.update(
          new ExternalNativeIntShadowComponentIdentityRef(component, componentQueue),
          existing :+ incoming
        )
    }
    component
  }

  /** Attach a completed capture to one exact native Data region. */
  def attachRegion[T <: Data](
      owner: Component,
      data: T,
      formalBinding: Option[ExternalFormalParameterBinding],
      capture: ExternalNativeIntShadowCapture[T]
  ): T = synchronized {
    if (owner == null)
      throw new IllegalArgumentException("native Int shadow region owner must not be null")
    if (data == null)
      throw new IllegalArgumentException("native Int shadow Data region must not be null")
    if (capture == null)
      throw new IllegalArgumentException("native Int shadow capture must not be null")
    if (!(capture.result.asInstanceOf[AnyRef] eq data)) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-RESULT-MISMATCH",
        s"shadow capture '${capture.token.role}' was attached to a different Data identity",
        sourceOf(capture.token)
      )
    }

    val definition = formalBinding match {
      case Some(binding) => formalExpression(binding.formal)
      case None          => capture.expression
    }
    val actual = formalBinding.map(_.actual).getOrElse(capture.expression)
    val incoming = ExternalNativeIntRegionShadowRecord(
      boundaryToken = capture.token,
      parentBoundaryToken = capture.parentToken,
      ownerClassName = owner.getClass.getName,
      formalBinding = formalBinding,
      slots = finalizeSlots(capture, definition, actual)
    )

    reapRegions()
    val lookup = new ExternalNativeIntShadowRegionIdentityRef(data, null)
    regions.get(lookup) match {
      case Some(record) if !equivalentRegionRecord(record, incoming) =>
        fail(
          "MORPH-FRONTEND-NATIVE-INT-SHADOW-REGION-CONFLICT",
          s"one exact Data region received conflicting shadow provenance for '${capture.token.role}'",
          sourceOf(capture.token)
        )
      case Some(_) =>
      case None =>
        regions.update(
          new ExternalNativeIntShadowRegionIdentityRef(data, regionQueue),
          incoming
        )
    }
    data
  }

  def componentRecordsOf(
      component: Component
  ): Vector[ExternalNativeIntComponentShadowRecord] = synchronized {
    if (component == null) Vector.empty
    else {
      reapComponents()
      components
        .getOrElse(
          new ExternalNativeIntShadowComponentIdentityRef(component, null),
          Vector.empty
        )
        .sortBy(record => (record.binding.formal.name, record.binding.declarationKey))
    }
  }

  def regionOf(data: Data): Option[ExternalNativeIntRegionShadowRecord] = synchronized {
    if (data == null) None
    else {
      reapRegions()
      regions.get(new ExternalNativeIntShadowRegionIdentityRef(data, null))
    }
  }

  /** Read-only live-record counts; queue draining occurs before reporting. */
  def liveRecordCounts: (Int, Int) = synchronized {
    reapComponents()
    reapRegions()
    components.size -> regions.size
  }

  private def record(
      value: Int,
      name: String,
      kind: ExternalNativeIntShadowKind,
      sourceLocation: String,
      requireBoundary: Boolean
  ): Int = {
    val stack = Option(active.get()).getOrElse(Nil)
    stack.headOption match {
      case None if requireBoundary =>
        fail(
          "MORPH-FRONTEND-NATIVE-INT-SHADOW-BOUNDARY-MISSING",
          s"selected native Int '$name' requires an active formalComponent or formalRegion boundary",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      case None => value
      case Some(boundary) =>
        validateName(name, sourceLocation, kind.label)
        val expected = boundary.expression.default.toInt
        if (value != expected) {
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-EXPRESSION-DEFERRED",
            s"selected native Int '$name' has witness $value, but boundary '${boundary.token.role}' has witness $expected; arithmetic and predicate propagation are deferred to Increment 50",
            Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
          )
        }
        val key = kind -> name
        val incoming = ExternalNativeIntShadowPendingSlot(
          token = ExternalNativeIntShadowSlotToken(name, kind, sourceLocation),
          witness = value
        )
        boundary.slots.get(key) match {
          case Some(existing) if existing != incoming =>
            fail(
              "MORPH-FRONTEND-NATIVE-INT-SHADOW-SLOT-CONFLICT",
              s"native Int shadow slot '${kind.label}:$name' was selected with conflicting source identity",
              Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
            )
          case Some(_) =>
          case None    => boundary.slots.update(key, incoming)
        }
        value
    }
  }

  private def finalizeSlots(
      capture: ExternalNativeIntShadowCapture[_],
      definition: ElaborationIntegerExpression,
      actual: ElaborationIntegerExpression
  ): Vector[ExternalNativeIntShadowSlot] = {
    if (!definition.default.isValidInt || !actual.default.isValidInt) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-WITNESS-INVALID",
        s"shadow capture '${capture.token.role}' requires Int-sized definition and actual defaults",
        sourceOf(capture.token)
      )
    }
    capture.pendingSlots.map { pending =>
      if (
        pending.witness != definition.default.toInt ||
        pending.witness != actual.default.toInt
      ) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-SHADOW-WITNESS-MISMATCH",
          s"shadow slot '${pending.token.name}' witness ${pending.witness} disagrees with definition default ${definition.default} or actual default ${actual.default}",
          Option(pending.token.sourceLocation).filter(_.nonEmpty).orElse(sourceOf(capture.token))
        )
      }
      ExternalNativeIntShadowSlot(
        token = pending.token,
        witness = pending.witness,
        definitionExpression = definition,
        actualExpression = actual
      )
    }.sortBy(slot => (slot.token.kind.label, slot.token.name, slot.token.sourceLocation))
  }

  private def validateExpression(
      expression: ElaborationIntegerExpression,
      token: ExternalNativeIntFormalizationToken
  ): Unit = {
    if (expression == null)
      throw new IllegalArgumentException("native Int shadow expression must not be null")
    if (token == null)
      throw new IllegalArgumentException("native Int shadow token must not be null")
    if (
      expression.minimum < 1 || expression.maximum < expression.minimum ||
      expression.maximum > BigInt(Int.MaxValue) ||
      expression.default < expression.minimum || expression.default > expression.maximum ||
      !expression.default.isValidInt
    ) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-DOMAIN-INVALID",
        s"shadow boundary '${token.role}' expression '${expression.verilog}' requires a finite positive Int-sized domain containing default ${expression.default}",
        sourceOf(token).orElse(expression.sourceLocation)
      )
    }
  }

  private def validateName(
      name: String,
      sourceLocation: String,
      role: String
  ): Unit = {
    if (name == null || name.trim.isEmpty) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-NAME-INVALID",
        s"$role shadow selection requires one non-empty deterministic name",
        Option(sourceLocation).filter(_.nonEmpty)
      )
    }
    if (sourceLocation == null || sourceLocation.isEmpty) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-SOURCE-MISSING",
        s"$role shadow selection '$name' requires one deterministic source location",
        None
      )
    }
  }

  private def reapComponents(): Unit = {
    var reference = componentQueue
      .poll()
      .asInstanceOf[ExternalNativeIntShadowComponentIdentityRef]
    while (reference != null) {
      components.remove(reference)
      reference = componentQueue
        .poll()
        .asInstanceOf[ExternalNativeIntShadowComponentIdentityRef]
    }
  }

  private def reapRegions(): Unit = {
    var reference = regionQueue
      .poll()
      .asInstanceOf[ExternalNativeIntShadowRegionIdentityRef]
    while (reference != null) {
      regions.remove(reference)
      reference = regionQueue
        .poll()
        .asInstanceOf[ExternalNativeIntShadowRegionIdentityRef]
    }
  }

  private def equivalentComponentRecord(
      left: ExternalNativeIntComponentShadowRecord,
      right: ExternalNativeIntComponentShadowRecord
  ): Boolean =
    left.boundaryToken == right.boundaryToken &&
      left.parentBoundaryToken == right.parentBoundaryToken &&
      left.ownerClassName == right.ownerClassName &&
      ExternalFormalParameterRegistry.equivalentBinding(left.binding, right.binding) &&
      equivalentSlots(left.slots, right.slots)

  private def equivalentRegionRecord(
      left: ExternalNativeIntRegionShadowRecord,
      right: ExternalNativeIntRegionShadowRecord
  ): Boolean =
    left.boundaryToken == right.boundaryToken &&
      left.parentBoundaryToken == right.parentBoundaryToken &&
      left.ownerClassName == right.ownerClassName &&
      ((left.formalBinding, right.formalBinding) match {
        case (Some(x), Some(y)) =>
          ExternalFormalParameterRegistry.equivalentBinding(x, y)
        case (None, None) => true
        case _            => false
      }) &&
      equivalentSlots(left.slots, right.slots)

  private def equivalentSlots(
      left: Vector[ExternalNativeIntShadowSlot],
      right: Vector[ExternalNativeIntShadowSlot]
  ): Boolean =
    left.size == right.size && left.zip(right).forall { case (x, y) =>
      x.token == y.token && x.witness == y.witness &&
      equivalentExpression(x.definitionExpression, y.definitionExpression) &&
      equivalentExpression(x.actualExpression, y.actualExpression)
    }

  private def equivalentExpression(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean =
    ExternalFormalParameterRegistry.equivalentExpression(left, right)

  private def formalExpression(
      formal: ElaborationIntegerParameter
  ): ElaborationIntegerExpression =
    ElaborationIntegerExpression(
      verilog = formal.name,
      default = formal.default,
      minimum = formal.minimum,
      maximum = formal.maximum,
      parameters = Vector(formal),
      sourceLocation = None
    )

  private def sourceOf(
      token: ExternalNativeIntFormalizationToken
  ): Option[String] = Option(token).map(_.callSite).filter(_.nonEmpty)

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
