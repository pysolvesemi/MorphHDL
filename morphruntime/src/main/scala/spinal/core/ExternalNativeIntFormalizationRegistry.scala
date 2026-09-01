package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}
import java.util.IdentityHashMap

import scala.collection.mutable

/** Opaque one-use authority for one exact native-Int boundary.
  *
  * Diagnostic strings are intentionally readable but never participate in an
  * authorization decision. The private capability is bound to a typed kind,
  * the exact owner and exact actual/definition expression objects at issuance,
  * then proves the exact constructor result, formal binding and selected
  * geometry before either formalization registry commits. A successful claim
  * releases graph identities before the weak-key records retain this token.
  */
final class ExternalNativeIntFormalizationToken private[core] (
    private[core] val kind: ExternalNativeIntFormalizationToken.Kind,
    initialOwnerIdentity: AnyRef,
    initialActualExpressionIdentity: ElaborationIntegerExpression,
    initialDefinitionExpressionIdentity: ElaborationIntegerExpression,
    val callSite: String,
    val valueOrigin: String,
    val role: String
) {
  private[this] var retainedOwnerIdentity: AnyRef = initialOwnerIdentity
  private[this] var retainedActualExpressionIdentity: ElaborationIntegerExpression =
    initialActualExpressionIdentity
  private[this] var retainedDefinitionExpressionIdentity: ElaborationIntegerExpression =
    initialDefinitionExpressionIdentity
  private[this] var capturedResult: AnyRef = null
  private[this] var consumed = false

  /** Read-only preflight identities. Successful consumption releases their
    * strong references so records kept behind weak keys cannot retain the
    * native elaboration graph through this token. A replay observes capability
    * consumption instead of a nullable identity.
    */
  private[core] def actualExpressionIdentity: ElaborationIntegerExpression = synchronized {
    requireRetainedIdentity(retainedActualExpressionIdentity)
    retainedActualExpressionIdentity
  }

  private[core] def definitionExpressionIdentity: ElaborationIntegerExpression = synchronized {
    requireRetainedIdentity(retainedDefinitionExpressionIdentity)
    retainedDefinitionExpressionIdentity
  }

  private[core] def requireCapture(
      owner: AnyRef,
      actual: ElaborationIntegerExpression,
      definition: ElaborationIntegerExpression
  ): Unit = synchronized {
    if (
      consumed || (kind eq ExternalNativeIntFormalizationToken.NativeWidth) ||
      !(retainedOwnerIdentity eq owner) ||
      !(retainedActualExpressionIdentity eq actual) ||
      !(retainedDefinitionExpressionIdentity eq definition)
    )
      authorizationFailure(
        "MORPH-FRONTEND-NATIVE-INT-FORMALIZATION-CAPTURE-MISMATCH",
        "native Int capture received a consumed, foreign-kind, copied, or stale boundary capability"
      )
    if (capturedResult ne null)
      authorizationFailure(
        "MORPH-FRONTEND-NATIVE-INT-FORMALIZATION-CAPTURE-REPLAY",
        "native Int boundary capability has already completed one constructor capture"
      )
  }

  private[core] def bindCaptureResult(result: AnyRef): Unit = synchronized {
    if (result == null)
      authorizationFailure(
        "MORPH-FRONTEND-NATIVE-INT-FORMALIZATION-RESULT-NULL",
        "native Int boundary capability cannot bind a null constructor result"
      )
    if (consumed || (capturedResult ne null))
      authorizationFailure(
        "MORPH-FRONTEND-NATIVE-INT-FORMALIZATION-CAPTURE-REPLAY",
        "native Int boundary capability cannot bind more than one constructor result"
      )
    capturedResult = result
  }

  private[core] def claimFinal(
      expectedKind: ExternalNativeIntFormalizationToken.Kind,
      owner: AnyRef,
      target: AnyRef,
      actual: ElaborationIntegerExpression,
      definition: ElaborationIntegerExpression,
      formal: Option[AnyRef],
      geometry: Vector[AnyRef]
  ): Unit = synchronized {
    if (
      consumed || !(kind eq expectedKind) ||
      !(retainedOwnerIdentity eq owner) ||
      !(retainedActualExpressionIdentity eq actual) ||
      !(retainedDefinitionExpressionIdentity eq definition) ||
      !(capturedResult eq target) || target == null || formal == null ||
      formal.exists(_ == null) || geometry == null || geometry.exists(_ == null)
    )
      authorizationFailure(
        "MORPH-FRONTEND-NATIVE-INT-FORMALIZATION-TARGET-MISMATCH",
        "native Int boundary capability received a consumed, foreign, copied, or stale final target"
      )

    if (
      ((kind eq ExternalNativeIntFormalizationToken.Region) &&
        (geometry.size != 1 || !(geometry.head eq target))) ||
      ((kind eq ExternalNativeIntFormalizationToken.ComponentGeometry) &&
        geometry.isEmpty) ||
      ((kind eq ExternalNativeIntFormalizationToken.ComponentParameter) &&
        geometry.nonEmpty)
    )
      authorizationFailure(
        "MORPH-FRONTEND-NATIVE-INT-FORMALIZATION-GEOMETRY-MISMATCH",
        "native Int boundary capability received geometry incompatible with its typed boundary kind"
      )

    consumed = true
    releaseIdentityAuthority()
  }

  private[core] def claimNativeWidth(
      expression: ElaborationIntegerExpression
  ): Unit = synchronized {
    if (
      consumed || !(kind eq ExternalNativeIntFormalizationToken.NativeWidth) ||
      !(retainedOwnerIdentity eq expression) ||
      !(retainedActualExpressionIdentity eq expression) ||
      !(retainedDefinitionExpressionIdentity eq expression)
    )
      authorizationFailure(
        "MORPH-FRONTEND-NATIVE-WIDTH-FUNCTION-CAPABILITY-MISMATCH",
        "native width-function boundary received a consumed, copied, or foreign capability"
      )
    consumed = true
    releaseIdentityAuthority()
  }

  private def releaseIdentityAuthority(): Unit = {
    retainedOwnerIdentity = null
    retainedActualExpressionIdentity = null
    retainedDefinitionExpressionIdentity = null
    capturedResult = null
  }

  private def requireRetainedIdentity(identity: AnyRef): Unit = {
    if (consumed || (identity eq null))
      authorizationFailure(
        "MORPH-FRONTEND-NATIVE-INT-FORMALIZATION-TARGET-MISMATCH",
        "native Int boundary capability has already released its exact preflight identities"
      )
  }

  private def authorizationFailure(code: String, detail: String): Nothing =
    ParameterizedVerilogException.fail(
      code,
      detail,
      Option(callSite).filter(_.nonEmpty)
    )
}

object ExternalNativeIntFormalizationToken {
  private[core] sealed abstract class Kind private[ExternalNativeIntFormalizationToken] ()
  private[core] case object Region extends Kind
  private[core] case object ComponentGeometry extends Kind
  private[core] case object ComponentParameter extends Kind
  private[core] case object NativeWidth extends Kind

  private def issue(
      kind: Kind,
      owner: AnyRef,
      actual: ElaborationIntegerExpression,
      definition: ElaborationIntegerExpression,
      callSite: String,
      valueOrigin: String,
      role: String
  ): ExternalNativeIntFormalizationToken = {
    if (kind == null || owner == null || actual == null || definition == null)
      throw new IllegalArgumentException(
        "native Int formalization capability identities must not be null"
      )
    if (
      callSite == null || callSite.trim.isEmpty || valueOrigin == null ||
      valueOrigin.trim.isEmpty || role == null || role.trim.isEmpty
    )
      throw new IllegalArgumentException(
        "native Int formalization diagnostics must not be empty"
      )
    new ExternalNativeIntFormalizationToken(
      kind,
      owner,
      actual,
      definition,
      callSite,
      valueOrigin,
      role
    )
  }

  private[core] def region(
      owner: Component,
      expression: ElaborationIntegerExpression,
      callSite: String,
      valueOrigin: String,
      role: String
  ): ExternalNativeIntFormalizationToken =
    issue(Region, owner, expression, expression, callSite, valueOrigin, role)

  private[core] def componentGeometry(
      parent: Component,
      actual: ElaborationIntegerExpression,
      definition: ElaborationIntegerExpression,
      callSite: String,
      valueOrigin: String,
      role: String
  ): ExternalNativeIntFormalizationToken =
    issue(
      ComponentGeometry,
      parent,
      actual,
      definition,
      callSite,
      valueOrigin,
      role
    )

  private[core] def componentParameter(
      parent: Component,
      actual: ElaborationIntegerExpression,
      definition: ElaborationIntegerExpression,
      callSite: String,
      valueOrigin: String,
      role: String
  ): ExternalNativeIntFormalizationToken =
    issue(
      ComponentParameter,
      parent,
      actual,
      definition,
      callSite,
      valueOrigin,
      role
    )

  private[core] def nativeWidth(
      expression: ElaborationIntegerExpression,
      callSite: String,
      valueOrigin: String,
      role: String
  ): ExternalNativeIntFormalizationToken =
    issue(
      NativeWidth,
      expression,
      expression,
      expression,
      callSite,
      valueOrigin,
      role
    )
}

/** Exact returned/selected Data-region metadata retained outside SpinalHDL. */
final case class ExternalNativeIntRegionRecord(
    token: ExternalNativeIntFormalizationToken,
    ownerClassName: String,
    expression: ElaborationIntegerExpression,
    formalBinding: Option[ExternalFormalParameterBinding],
    packedLeafCount: Int
)

/** Exact child-component metadata retained by the `formalComponent` adapter. */
final case class ExternalNativeIntComponentRecord(
    token: ExternalNativeIntFormalizationToken,
    binding: ExternalFormalParameterBinding,
    regionCount: Int,
    packedLeafCount: Int
)

/** Weak identity key for one exact Data value returned by a formal region. */
private[core] final class ExternalNativeIntRegionIdentityRef(
    value: Data,
    queue: ReferenceQueue[Data]
) extends WeakReference[Data](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalNativeIntRegionIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** Weak identity key for one exact component returned by `formalComponent`. */
private[core] final class ExternalNativeIntComponentIdentityRef(
    value: Component,
    queue: ReferenceQueue[Component]
) extends WeakReference[Component](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalNativeIntComponentIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** External identity/lifetime sidecar for native `Int` APIs.
  *
  * The concrete witness is passed to untouched SpinalHDL before this registry
  * is called. Symbolic provenance is then attached only to exact Data and
  * Component objects explicitly supplied by the caller. No concrete integer,
  * emitted identifier, module name or source-code pattern participates in a
  * lookup. Weak keys bound metadata lifetime to the native elaboration graph.
  */
object ExternalNativeIntFormalizationRegistry {
  private final case class PreparedRegion(
      root: Data,
      leaves: Vector[BitVector],
      record: ExternalNativeIntRegionRecord
  )

  private sealed trait PreparedWidthPublication
  private final case class PreparedFormalWidthPublication(
      attachment: ExternalFormalParameterRegistry.PreparedLeafAttachment
  ) extends PreparedWidthPublication
  private final case class PreparedPlainWidthPublication(
      leaves: Vector[BitVector],
      width: ParameterizedBitCount
  ) extends PreparedWidthPublication

  private final case class PreparedRegionTransaction(
      owner: Component,
      regions: Vector[PreparedRegion],
      widthPublication: PreparedWidthPublication
  )

  private final case class PreparedComponentTransaction(
      component: Component,
      regions: PreparedRegionTransaction,
      record: ExternalNativeIntComponentRecord
  )

  private final case class PreparedComponentParameterTransaction(
      component: Component,
      formal: ExternalFormalParameterRegistry.PreparedComponentAttachment,
      record: ExternalNativeIntComponentRecord
  )

  private val regionQueue = new ReferenceQueue[Data]()
  private val componentQueue = new ReferenceQueue[Component]()
  private val regions = mutable.HashMap.empty[
    ExternalNativeIntRegionIdentityRef,
    ExternalNativeIntRegionRecord
  ]
  private val components = mutable.HashMap.empty[
    ExternalNativeIntComponentIdentityRef,
    Vector[ExternalNativeIntComponentRecord]
  ]

  private def reapRegions(): Unit = {
    var reference =
      regionQueue.poll().asInstanceOf[ExternalNativeIntRegionIdentityRef]
    while (reference != null) {
      regions.remove(reference)
      reference = regionQueue.poll().asInstanceOf[ExternalNativeIntRegionIdentityRef]
    }
  }

  private def reapComponents(): Unit = {
    var reference =
      componentQueue.poll().asInstanceOf[ExternalNativeIntComponentIdentityRef]
    while (reference != null) {
      components.remove(reference)
      reference = componentQueue.poll().asInstanceOf[ExternalNativeIntComponentIdentityRef]
    }
  }

  /** Attach one retained expression to the exact Data returned by a region. */
  private[spinal] def attachRegion[T <: Data](
      owner: Component,
      data: T,
      expression: ElaborationIntegerExpression,
      token: ExternalNativeIntFormalizationToken,
      formalBinding: Option[ExternalFormalParameterBinding]
  ): T = synchronized {
    val prepared = preflightRegionTransaction(
      owner,
      data,
      expression,
      token,
      formalBinding
    )
    commitRegionTransaction(prepared)
    data
  }

  private[spinal] def attachRegionAtomically[T <: Data](
      owner: Component,
      data: T,
      expression: ElaborationIntegerExpression,
      formalBinding: Option[ExternalFormalParameterBinding],
      capture: ExternalNativeIntShadowCapture[T]
  ): T = synchronized {
    if (capture == null)
      throw new IllegalArgumentException(
        "native Int atomic region capture must not be null"
      )
    val preparedFormal = preflightRegionTransaction(
      owner,
      data,
      expression,
      capture.token,
      formalBinding
    )
    val preparedShadow = ExternalNativeIntShadowRegistry.preflightRegion(
      owner,
      data,
      formalBinding,
      capture
    )
    capture.token.claimFinal(
      ExternalNativeIntFormalizationToken.Region,
      owner,
      data,
      expression,
      capture.definitionExpression,
      formalBinding.map(_.asInstanceOf[AnyRef]),
      Vector(data.asInstanceOf[AnyRef])
    )
    commitRegionTransaction(preparedFormal)
    ExternalNativeIntShadowRegistry.commitRegion(preparedShadow)
    data
  }

  /** Attach one component-definition formal and its parent-scope actual to the
    * exact returned child and exact selected packed ports.
    */
  private[spinal] def attachComponent[C <: Component](
      parent: Component,
      component: C,
      geometry: Iterable[Data],
      binding: ExternalFormalParameterBinding,
      token: ExternalNativeIntFormalizationToken
  ): C = synchronized {
    commitComponentTransaction(
      preflightComponentTransaction(parent, component, geometry, binding, token)
    )
    component
  }

  private[spinal] def attachComponentAtomically[C <: Component](
      parent: Component,
      component: C,
      geometry: Iterable[Data],
      binding: ExternalFormalParameterBinding,
      capture: ExternalNativeIntShadowCapture[C]
  ): C = synchronized {
    if (capture == null)
      throw new IllegalArgumentException(
        "native Int atomic component capture must not be null"
      )
    if (geometry == null)
      throw new IllegalArgumentException(
        "native Int atomic component geometry must not be null"
      )
    val exactGeometry = geometry.toVector
    val preparedFormal = preflightComponentTransaction(
      parent,
      component,
      exactGeometry,
      binding,
      capture.token
    )
    val preparedShadow = ExternalNativeIntShadowRegistry.preflightComponent(
      component,
      binding,
      capture
    )
    capture.token.claimFinal(
      ExternalNativeIntFormalizationToken.ComponentGeometry,
      parent,
      component,
      capture.expression,
      capture.definitionExpression,
      Some(binding),
      exactGeometry.map(_.asInstanceOf[AnyRef])
    )
    commitComponentTransaction(preparedFormal)
    ExternalNativeIntShadowRegistry.commitComponent(preparedShadow)
    component
  }

  /** Retain a native component formal that controls storage or structural
    * choices without pretending that the scalar value is a packed port width.
    * The child constructor's shadow capture and internal symbolic registries
    * remain responsible for proving the definition-side parameter dependency.
    */
  private[spinal] def attachComponentParameter[C <: Component](
      parent: Component,
      component: C,
      binding: ExternalFormalParameterBinding,
      token: ExternalNativeIntFormalizationToken
  ): C = synchronized {
    commitComponentParameterTransaction(
      preflightComponentParameterTransaction(
        parent,
        component,
        binding,
        token
      )
    )
    component
  }

  private[spinal] def attachComponentParameterAtomically[C <: Component](
      parent: Component,
      component: C,
      binding: ExternalFormalParameterBinding,
      capture: ExternalNativeIntShadowCapture[C]
  ): C = synchronized {
    if (capture == null)
      throw new IllegalArgumentException(
        "native Int atomic component-parameter capture must not be null"
      )
    val preparedFormal = preflightComponentParameterTransaction(
      parent,
      component,
      binding,
      capture.token
    )
    val preparedShadow = ExternalNativeIntShadowRegistry.preflightComponent(
      component,
      binding,
      capture
    )
    capture.token.claimFinal(
      ExternalNativeIntFormalizationToken.ComponentParameter,
      parent,
      component,
      capture.expression,
      capture.definitionExpression,
      Some(binding),
      Vector.empty
    )
    commitComponentParameterTransaction(preparedFormal)
    ExternalNativeIntShadowRegistry.commitComponent(preparedShadow)
    component
  }

  private def preflightRegionTransaction[T <: Data](
      owner: Component,
      data: T,
      expression: ElaborationIntegerExpression,
      token: ExternalNativeIntFormalizationToken,
      formalBinding: Option[ExternalFormalParameterBinding]
  ): PreparedRegionTransaction = {
    validateCommon(owner, expression, token)
    if (data == null) {
      fail(
        "MORPH-FRONTEND-FORMAL-REGION-RESULT-NULL",
        "formalRegion constructor returned null",
        sourceOf(token)
      )
    }
    if (formalBinding == null || formalBinding.exists(_ == null))
      throw new IllegalArgumentException(
        "formal region binding option must retain non-null values"
      )
    formalBinding.foreach { binding =>
      ExternalFormalParameterRegistry.validateBinding(binding)
      validateFormal(owner, expression, binding)
    }
    val prepared = prepareRegion(
      owner,
      data,
      expression,
      token,
      formalBinding,
      requireNativePort = false
    )
    preflightRegions(Vector(prepared))
    PreparedRegionTransaction(
      owner,
      Vector(prepared),
      preflightWidthPublication(owner, Vector(prepared))
    )
  }

  private def commitRegionTransaction(
      prepared: PreparedRegionTransaction
  ): Unit = {
    commitWidthPublication(prepared.widthPublication)
    retainRegions(prepared.regions)
  }

  private def preflightComponentTransaction[C <: Component](
      parent: Component,
      component: C,
      geometry: Iterable[Data],
      binding: ExternalFormalParameterBinding,
      token: ExternalNativeIntFormalizationToken
  ): PreparedComponentTransaction = {
    validateComponentTarget(parent, component, binding, token, parameterOnly = false)
    if (geometry == null) {
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-GEOMETRY-NULL",
        s"formalComponent slot '${binding.formal.name}' requires a non-null geometry selection",
        sourceOf(token)
      )
    }
    val roots = geometry.toVector
    if (roots.isEmpty) {
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-GEOMETRY-EMPTY",
        s"formalComponent slot '${binding.formal.name}' selected no native Data regions",
        sourceOf(token)
      )
    }
    if (roots.exists(_ == null)) {
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-GEOMETRY-NULL",
        s"formalComponent slot '${binding.formal.name}' selected a null native Data region",
        sourceOf(token)
      )
    }

    val expression = token.definitionExpressionIdentity
    validateCommon(component, expression, token)
    validateExactComponentBinding(token, binding, expression)
    validateFormal(component, expression, binding)
    val prepared = roots.map { root =>
      prepareRegion(
        component,
        root,
        expression,
        token,
        Some(binding),
        requireNativePort = true
      )
    }
    rejectDuplicateIdentity(prepared)
    preflightRegions(prepared)
    val regionTransaction = PreparedRegionTransaction(
      component,
      prepared,
      preflightWidthPublication(component, prepared)
    )
    val componentRecord = preflightComponentBinding(
      component = component,
      binding = binding,
      token = token,
      regionCount = prepared.size,
      packedLeafCount = prepared.map(_.leaves.size).sum
    )
    PreparedComponentTransaction(component, regionTransaction, componentRecord)
  }

  private def commitComponentTransaction(
      prepared: PreparedComponentTransaction
  ): Unit = {
    commitRegionTransaction(prepared.regions)
    retainComponentBinding(prepared.component, prepared.record)
  }

  private def preflightComponentParameterTransaction[C <: Component](
      parent: Component,
      component: C,
      binding: ExternalFormalParameterBinding,
      token: ExternalNativeIntFormalizationToken
  ): PreparedComponentParameterTransaction = {
    validateComponentTarget(parent, component, binding, token, parameterOnly = true)
    val expression = token.definitionExpressionIdentity
    validateCommon(component, expression, token)
    validateExactComponentBinding(token, binding, expression)
    validateFormal(component, expression, binding)
    val componentRecord = preflightComponentBinding(
      component = component,
      binding = binding,
      token = token,
      regionCount = 0,
      packedLeafCount = 0
    )
    val formal = ExternalFormalParameterRegistry.preflightRetainComponent(
      component,
      binding
    )
    PreparedComponentParameterTransaction(component, formal, componentRecord)
  }

  private def commitComponentParameterTransaction(
      prepared: PreparedComponentParameterTransaction
  ): Unit = {
    ExternalFormalParameterRegistry.commitRetainComponent(prepared.formal)
    retainComponentBinding(prepared.component, prepared.record)
  }

  private def validateComponentTarget[C <: Component](
      parent: Component,
      component: C,
      binding: ExternalFormalParameterBinding,
      token: ExternalNativeIntFormalizationToken,
      parameterOnly: Boolean
  ): Unit = {
    val label = if (parameterOnly) "formalComponent.parameter" else "formalComponent"
    if (parent == null) {
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-PARENT-MISSING",
        s"$label requires one active parent Component",
        sourceOf(token)
      )
    }
    if (component == null) {
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-RESULT-NULL",
        s"$label constructor returned null",
        sourceOf(token)
      )
    }
    if (binding == null)
      throw new IllegalArgumentException("formal component binding must not be null")
    ExternalFormalParameterRegistry.validateBinding(binding)
    if (component.parent ne parent) {
      val actualParent =
        Option(component.parent).map(_.getClass.getName).getOrElse("<none>")
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-PARENT-MISMATCH",
        s"$label returned a component owned by '$actualParent' instead of the exact active parent '${parent.getClass.getName}'",
        sourceOf(token)
      )
    }
  }

  private def validateExactComponentBinding(
      token: ExternalNativeIntFormalizationToken,
      binding: ExternalFormalParameterBinding,
      definition: ElaborationIntegerExpression
  ): Unit = {
    val exactFormal = definition.parameters match {
      case Vector(value) => value
      case _             => null
    }
    if (
      !(binding.actual eq token.actualExpressionIdentity) ||
      !(definition eq token.definitionExpressionIdentity) ||
      !(exactFormal eq binding.formal)
    ) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-FORMALIZATION-BINDING-MISMATCH",
        "native Int formalization received a copied or foreign actual, definition, or formal declaration identity",
        binding.sourceLocation.orElse(sourceOf(token))
      )
    }
  }

  private def preflightWidthPublication(
      owner: Component,
      prepared: Vector[PreparedRegion]
  ): PreparedWidthPublication = {
    val first = prepared.head
    val leaves = prepared.flatMap(_.leaves)
    val width = bitCount(first.record.expression)
    first.record.formalBinding match {
      case Some(binding) =>
        PreparedFormalWidthPublication(
          ExternalFormalParameterRegistry.preflightAttachAll(
            owner,
            leaves,
            width,
            binding
          )
        )
      case None =>
        validatePlainWidthPublication(leaves, width, first.record.expression)
        PreparedPlainWidthPublication(leaves, width)
    }
  }

  private def commitWidthPublication(
      prepared: PreparedWidthPublication
  ): Unit = prepared match {
    case PreparedFormalWidthPublication(attachment) =>
      ExternalFormalParameterRegistry.commitAttachAll(attachment)
    case PreparedPlainWidthPublication(leaves, width) =>
      ParameterizedWidth.attachExistingAll(leaves, width)
  }

  private def validatePlainWidthPublication(
      leaves: Vector[BitVector],
      width: ParameterizedBitCount,
      expression: ElaborationIntegerExpression
  ): Unit = {
    ParameterizedWidth.validatedWidthExpression(width)
    leaves.zipWithIndex.foreach { case (leaf, index) =>
      if (leaf.getBitsWidth != width.value) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-WITNESS-MISMATCH",
          s"existing symbolic-width target $index has concrete width ${leaf.getBitsWidth}, not validated width ${width.value}",
          width.sourceLocation.orElse(expression.sourceLocation)
        )
      }
      val existingParameter = ParameterizedWidth.parameterOf(leaf)
      val existingExpression = ParameterizedWidth.expressionOf(leaf)
      val compatible =
        existingParameter == width.parameter &&
          existingExpression.exists(ElabInt.equivalentExpression(_, expression))
      if ((existingParameter.nonEmpty || existingExpression.nonEmpty) && !compatible) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-WIDTH-PROVENANCE-CONFLICT",
          "one exact native data leaf is associated with conflicting typed width expressions",
          width.sourceLocation.orElse(existingExpression.flatMap(_.sourceLocation))
        )
      }
    }
  }

  /** Exact-region record; equal concrete witnesses on other objects do not match. */
  def regionOf(data: Data): Option[ExternalNativeIntRegionRecord] = synchronized {
    if (data == null) None
    else {
      reapRegions()
      regions.get(new ExternalNativeIntRegionIdentityRef(data, null))
    }
  }

  /** Exact-component records retained during the component's live graph lifetime. */
  def componentRecordsOf(
      component: Component
  ): Vector[ExternalNativeIntComponentRecord] = synchronized {
    if (component == null) Vector.empty
    else {
      reapComponents()
      components
        .getOrElse(
          new ExternalNativeIntComponentIdentityRef(component, null),
          Vector.empty
        )
        .sortBy(record => (record.binding.formal.name, record.binding.declarationKey))
    }
  }

  private def preflightComponentBinding(
      component: Component,
      binding: ExternalFormalParameterBinding,
      token: ExternalNativeIntFormalizationToken,
      regionCount: Int,
      packedLeafCount: Int
  ): ExternalNativeIntComponentRecord = {
    reapComponents()
    val componentLookup =
      new ExternalNativeIntComponentIdentityRef(component, null)
    val incoming = ExternalNativeIntComponentRecord(
      token = token,
      binding = binding,
      regionCount = regionCount,
      packedLeafCount = packedLeafCount
    )
    val existing = components.getOrElse(componentLookup, Vector.empty)
    existing.find(_.binding.formal.name == binding.formal.name) match {
      case Some(value)
          if !ExternalFormalParameterRegistry.equivalentBinding(
            value.binding,
            binding
          ) || !(value.token eq token) ||
            value.regionCount != incoming.regionCount ||
            value.packedLeafCount != incoming.packedLeafCount =>
        fail(
          "MORPH-FRONTEND-FORMAL-COMPONENT-CONFLICT",
          s"one exact component object received conflicting formalization for slot '${binding.formal.name}'",
          binding.sourceLocation.orElse(sourceOf(token))
        )
      case _ =>
    }
    incoming
  }

  private def retainComponentBinding(
      component: Component,
      incoming: ExternalNativeIntComponentRecord
  ): Unit = {
    val componentLookup =
      new ExternalNativeIntComponentIdentityRef(component, null)
    val existing = components.getOrElse(componentLookup, Vector.empty)
    if (
      !existing.exists(value =>
        ExternalFormalParameterRegistry.equivalentBinding(
          value.binding,
          incoming.binding
        ) && (value.token eq incoming.token)
      )
    ) {
      components.update(
        new ExternalNativeIntComponentIdentityRef(component, componentQueue),
        existing :+ incoming
      )
    }
  }

  private def prepareRegion(
      owner: Component,
      root: Data,
      expression: ElaborationIntegerExpression,
      token: ExternalNativeIntFormalizationToken,
      formalBinding: Option[ExternalFormalParameterBinding],
      requireNativePort: Boolean
  ): PreparedRegion = {
    val leaves = distinctPackedLeaves(root)
    if (leaves.isEmpty) {
      fail(
        "MORPH-FRONTEND-FORMAL-REGION-NO-PACKED-GEOMETRY",
        s"${token.role} selected no native BitVector leaves",
        sourceOf(token)
      )
    }

    val nativePorts = new IdentityHashMap[BaseType, java.lang.Boolean]()
    if (requireNativePort) {
      owner.getOrdredNodeIo.toVector.filterNot(_.isSuffix).foreach { port =>
        nativePorts.put(port, java.lang.Boolean.TRUE)
      }
    }

    leaves.foreach { leaf =>
      if (leaf.component ne owner) {
        val actualOwner =
          Option(leaf.component).map(_.getClass.getName).getOrElse("<none>")
        fail(
          "MORPH-FRONTEND-FORMAL-REGION-OWNER-MISMATCH",
          s"${token.role} selected a native leaf owned by '$actualOwner' instead of the exact component '${owner.getClass.getName}'",
          sourceOf(token)
        )
      }
      if (requireNativePort && nativePorts.get(leaf) == null) {
        fail(
          "MORPH-FRONTEND-FORMAL-COMPONENT-GEOMETRY-NOT-PORT",
          s"${token.role} selected a native leaf that is not an exact child IO port",
          sourceOf(token)
        )
      }
      if (
        !expression.default.isValidInt ||
        leaf.getBitsWidth != expression.default.toInt
      ) {
        fail(
          "MORPH-FRONTEND-FORMAL-REGION-WITNESS-MISMATCH",
          s"${token.role} selected a ${leaf.getBitsWidth}-bit native leaf, but the checked Int witness is ${expression.default}",
          sourceOf(token)
        )
      }
    }

    PreparedRegion(
      root = root,
      leaves = leaves,
      record = ExternalNativeIntRegionRecord(
        token = token,
        ownerClassName = owner.getClass.getName,
        expression = expression,
        formalBinding = formalBinding,
        packedLeafCount = leaves.size
      )
    )
  }

  private def distinctPackedLeaves(root: Data): Vector[BitVector] = {
    val seen = new IdentityHashMap[BitVector, java.lang.Boolean]()
    root.flatten.toVector.collect {
      case leaf: BitVector if seen.put(leaf, java.lang.Boolean.TRUE) == null => leaf
    }
  }

  private def rejectDuplicateIdentity(prepared: Vector[PreparedRegion]): Unit = {
    val roots = new IdentityHashMap[Data, java.lang.Boolean]()
    val leaves = new IdentityHashMap[BitVector, java.lang.Boolean]()
    prepared.foreach { region =>
      if (roots.put(region.root, java.lang.Boolean.TRUE) != null) {
        fail(
          "MORPH-FRONTEND-FORMAL-COMPONENT-GEOMETRY-DUPLICATE",
          s"${region.record.token.role} selected the same exact Data region more than once",
          sourceOf(region.record.token)
        )
      }
      region.leaves.foreach { leaf =>
        if (leaves.put(leaf, java.lang.Boolean.TRUE) != null) {
          fail(
            "MORPH-FRONTEND-FORMAL-COMPONENT-GEOMETRY-DUPLICATE",
            s"${region.record.token.role} selected the same exact packed leaf through multiple regions",
            sourceOf(region.record.token)
          )
        }
      }
    }
  }

  private def preflightRegions(prepared: Vector[PreparedRegion]): Unit = {
    reapRegions()
    prepared.foreach { region =>
      regions
        .get(new ExternalNativeIntRegionIdentityRef(region.root, null))
        .foreach { existing =>
          if (!equivalentRegion(existing, region.record)) {
            fail(
              "MORPH-FRONTEND-FORMAL-REGION-CONFLICT",
              s"one exact Data region received conflicting native-Int formalization '${existing.token.role}' and '${region.record.token.role}'",
              sourceOf(region.record.token)
            )
          }
        }
    }
  }

  private def retainRegions(prepared: Vector[PreparedRegion]): Unit = {
    prepared.foreach { region =>
      val lookup = new ExternalNativeIntRegionIdentityRef(region.root, null)
      if (!regions.contains(lookup)) {
        regions.update(
          new ExternalNativeIntRegionIdentityRef(region.root, regionQueue),
          region.record
        )
      }
    }
  }

  private def validateCommon(
      owner: Component,
      expression: ElaborationIntegerExpression,
      token: ExternalNativeIntFormalizationToken
  ): Unit = {
    if (owner == null)
      throw new IllegalArgumentException("native Int formalization owner must not be null")
    if (expression == null)
      throw new IllegalArgumentException("native Int expression must not be null")
    if (token == null)
      throw new IllegalArgumentException("native Int formalization token must not be null")
    ElabInt.validateExpression(
      expression,
      "native Int formalization expression"
    )
    if (
      expression.minimum < 1 || expression.maximum < expression.minimum ||
      expression.maximum > BigInt(Int.MaxValue) ||
      expression.default < expression.minimum ||
      expression.default > expression.maximum ||
      !expression.default.isValidInt
    ) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-GEOMETRY-DOMAIN-INVALID",
        s"${token.role} expression '${expression.verilog}' requires a finite positive Int-sized domain containing default ${expression.default}",
        sourceOf(token).orElse(expression.sourceLocation)
      )
    }
  }

  private def validateFormal(
      owner: Component,
      expression: ElaborationIntegerExpression,
      binding: ExternalFormalParameterBinding
  ): Unit = {
    if (owner.getClass.getName != binding.ownerClassName) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-OWNER-MISMATCH",
        s"formal slot '${binding.formal.name}' belongs to '${binding.ownerClassName}' but the exact adapter owner is '${owner.getClass.getName}'",
        binding.sourceLocation
      )
    }
    val expected = formalExpression(binding.formal, expression.sourceLocation)
    if (
      !ExternalFormalParameterRegistry.equivalentCanonicalFormalSchema(
        expression,
        expected
      )
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-WIDTH-SCHEMA-MISMATCH",
        s"formal slot '${binding.formal.name}' carries region expression '${expression.verilog}' instead of its direct definition formal",
        binding.sourceLocation.orElse(expression.sourceLocation)
      )
    }
  }

  private def bitCount(
      expression: ElaborationIntegerExpression
  ): ParameterizedBitCount = {
    val direct = expression.parameters match {
      case Vector(parameter) if expression.verilog == parameter.name => Some(parameter)
      case _                                                         => None
    }
    ParameterizedBitCount(
      value = expression.default.toInt,
      parameter = direct,
      sourceLocation = expression.sourceLocation,
      expression = if (expression.parameters.nonEmpty) Some(expression) else None
    )
  }

  private def equivalentRegion(
      left: ExternalNativeIntRegionRecord,
      right: ExternalNativeIntRegionRecord
  ): Boolean =
    (left.token eq right.token) &&
      left.ownerClassName == right.ownerClassName &&
      ExternalFormalParameterRegistry.equivalentExpression(
        left.expression,
        right.expression
      ) &&
      ((left.formalBinding, right.formalBinding) match {
        case (Some(x), Some(y)) =>
          ExternalFormalParameterRegistry.equivalentBinding(x, y)
        case (None, None) => true
        case _            => false
      }) &&
      left.packedLeafCount == right.packedLeafCount

  private def formalExpression(
      formal: ElaborationIntegerParameter,
      sourceLocation: Option[String]
  ): ElaborationIntegerExpression =
    ElaborationIntegerExpression(
      verilog = formal.name,
      default = formal.default,
      minimum = formal.minimum,
      maximum = formal.maximum,
      parameters = Vector(formal),
      sourceLocation = sourceLocation,
      parameterRoots = Vector(formal.declarationRoot)
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
