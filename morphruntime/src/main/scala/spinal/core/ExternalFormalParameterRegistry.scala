package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.collection.mutable

/** Definition-side identity and instance-side actual expression retained for
  * one explicit MorphHDL formal packed-width slot.
  *
  * `formal` belongs to the canonical child-module definition. `actual` belongs
  * to one concrete child instance in its parent scope. The two are deliberately
  * separate even though ordinary SpinalHDL elaborates only `formal.default`.
  */
final case class ExternalFormalParameterBinding(
    formal: ElaborationIntegerParameter,
    actual: ElaborationIntegerExpression,
    declarationKey: String,
    ownerClassName: String,
    sourceLocation: Option[String]
)

/** Opaque, per-component declaration capability for the typed ElabInt formal
  * path. Equality is deliberately JVM identity; rendered declaration keys and
  * component class names are not authority for this token.
  */
private[spinal] final class ExternalTypedFormalDeclarationToken private[core] ()

/** One typed formal capability retained against an exact component or leaf
  * identity. Canonical and concrete instances intentionally receive different
  * tokens; the backend relates them only through its exact canonical-instance
  * map and exact port layout.
  */
private[spinal] final case class ExternalTypedFormalBinding(
    binding: ExternalFormalParameterBinding,
    declarationToken: ExternalTypedFormalDeclarationToken
)

/** One component-local formal binding plus the exact definition-side width
  * expression observed on an explicitly attached leaf, when one exists.
  *
  * Component-only native Int formals have no packed definition expression.
  * Keeping that absence explicit is important: a reconstructed rootless schema
  * is not declaration authority for recovering an independently rooted leaf.
  */
private[core] final case class ExternalFormalInstanceBinding(
    binding: ExternalFormalParameterBinding,
    definitionExpression: Option[ElaborationIntegerExpression]
)

/** Weak identity key for a transient ParameterizedBitCount adapter value. */
private[core] final class ExternalFormalBitCountIdentityRef(
    value: ParameterizedBitCount,
    queue: ReferenceQueue[ParameterizedBitCount]
) extends WeakReference[ParameterizedBitCount](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalFormalBitCountIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** Weak identity key for the concrete native packed leaf carrying a formal. */
private[core] final class ExternalFormalLeafIdentityRef(
    value: BaseType,
    queue: ReferenceQueue[BaseType]
) extends WeakReference[BaseType](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalFormalLeafIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** Weak identity key for one active top-level elaboration graph. */
private[core] final class ExternalFormalRootIdentityRef(
    value: Component,
    queue: ReferenceQueue[Component]
) extends WeakReference[Component](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalFormalRootIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** Weak identity key for one concrete component instance. */
private[core] final class ExternalFormalComponentIdentityRef(
    value: Component,
    queue: ReferenceQueue[Component]
) extends WeakReference[Component](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalFormalComponentIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** MorphHDL-owned formal-to-actual sidecar registry.
  *
  * The frontend first associates one explicit formal declaration with the
  * transient ParameterizedBitCount returned by `formalParam(...).bits`. The
  * MorphHDL packed-data factories then transfer that association to the exact
  * native BitVector leaf by object identity. No native SpinalHDL source or data
  * type is changed.
  */
object ExternalFormalParameterRegistry {
  private[core] final case class PreparedLeafAttachment(
      owner: Component,
      data: Vector[BitVector],
      width: ParameterizedBitCount,
      binding: ExternalFormalParameterBinding,
      retainedWidth: ElaborationIntegerExpression,
      root: Component
  )

  private[core] final case class PreparedComponentAttachment(
      component: Component,
      binding: ExternalFormalParameterBinding,
      root: Component
  )

  private val bitCountQueue = new ReferenceQueue[ParameterizedBitCount]()
  private val leafQueue = new ReferenceQueue[BaseType]()
  private val rootQueue = new ReferenceQueue[Component]()
  private val componentQueue = new ReferenceQueue[Component]()

  private val pending = mutable.HashMap.empty[
    ExternalFormalBitCountIdentityRef,
    ExternalFormalParameterBinding
  ]
  private val retained = mutable.HashMap.empty[
    ExternalFormalLeafIdentityRef,
    ExternalFormalParameterBinding
  ]
  private val typedRetained = mutable.HashMap.empty[
    ExternalFormalLeafIdentityRef,
    ExternalTypedFormalBinding
  ]
  private val declarations = mutable.HashMap.empty[
    ExternalFormalRootIdentityRef,
    mutable.HashMap[String, ExternalFormalParameterBinding]
  ]
  private val instanceBindings = mutable.HashMap.empty[
    ExternalFormalComponentIdentityRef,
    mutable.HashMap[String, Vector[ExternalFormalInstanceBinding]]
  ]
  private val typedInstanceBindings = mutable.HashMap.empty[
    ExternalFormalComponentIdentityRef,
    Vector[ExternalTypedFormalBinding]
  ]

  private def reapBitCounts(): Unit = {
    var reference = bitCountQueue.poll().asInstanceOf[ExternalFormalBitCountIdentityRef]
    while (reference != null) {
      pending.remove(reference)
      reference = bitCountQueue.poll().asInstanceOf[ExternalFormalBitCountIdentityRef]
    }
  }

  private def reapLeaves(): Unit = {
    var reference = leafQueue.poll().asInstanceOf[ExternalFormalLeafIdentityRef]
    while (reference != null) {
      retained.remove(reference)
      typedRetained.remove(reference)
      reference = leafQueue.poll().asInstanceOf[ExternalFormalLeafIdentityRef]
    }
  }

  private def reapRoots(): Unit = {
    var reference = rootQueue.poll().asInstanceOf[ExternalFormalRootIdentityRef]
    while (reference != null) {
      declarations.remove(reference)
      reference = rootQueue.poll().asInstanceOf[ExternalFormalRootIdentityRef]
    }
  }

  private def reapComponents(): Unit = {
    var reference =
      componentQueue.poll().asInstanceOf[ExternalFormalComponentIdentityRef]
    while (reference != null) {
      instanceBindings.remove(reference)
      typedInstanceBindings.remove(reference)
      reference = componentQueue.poll().asInstanceOf[ExternalFormalComponentIdentityRef]
    }
  }

  /** Retain a formal declaration beside one transient symbolic bit count. */
  def retain(
      width: ParameterizedBitCount,
      binding: ExternalFormalParameterBinding
  ): ParameterizedBitCount = synchronized {
    if (width == null)
      throw new IllegalArgumentException("formal symbolic bit count must not be null")
    if (binding == null)
      throw new IllegalArgumentException("formal parameter binding must not be null")

    validateBinding(binding)
    validateFormalWidth(width, binding, "symbolic bit count")
    val root = validateDeclarationForCurrentDesign(binding)
    validatePendingBinding(width, binding)

    retainDeclaration(root, binding)
    retainPendingBinding(width, binding)
    width
  }

  /** Transfer pending formal metadata to one exact native packed leaf. */
  def attach[T <: BitVector](data: T, width: ParameterizedBitCount): T = synchronized {
    if (data == null)
      throw new IllegalArgumentException("formal packed leaf must not be null")
    if (width == null)
      throw new IllegalArgumentException("formal symbolic bit count must not be null")

    reapBitCounts()
    pending.get(new ExternalFormalBitCountIdentityRef(width, null)).foreach { binding =>
      val currentComponent = Option(Component.current).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-OWNER-MISSING",
          s"formal slot '${binding.formal.name}' was attached without one active Component owner",
          binding.sourceLocation
        )
      }
      validateExactOwner(currentComponent, data, binding)

      val retainedWidth = ParameterizedWidth.expressionOf(data).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-WIDTH-METADATA-MISSING",
          s"formal slot '${binding.formal.name}' lost its retained packed-width expression",
          binding.sourceLocation
        )
      }
      val expectedWidth = formalExpression(binding.formal, retainedWidth.sourceLocation)
      if (!equivalentCanonicalFormalSchema(retainedWidth, expectedWidth)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-WIDTH-SCHEMA-MISMATCH",
          s"packed leaf for formal slot '${binding.formal.name}' carries expression '${retainedWidth.verilog}' instead of the canonical formal expression '${binding.formal.name}'",
          binding.sourceLocation.orElse(retainedWidth.sourceLocation)
        )
      }

      validateInstanceBinding(currentComponent, binding, Some(retainedWidth))
      validateLeafBinding(data, binding)

      retainInstanceBinding(currentComponent, binding, Some(retainedWidth))
      retainLeafBinding(data, binding)
    }
    data
  }

  /** Attach an explicit formal to one exact native leaf after an untouched
    * constructor has returned. The supplied component identity is
    * authoritative; neither the concrete width nor an emitted name is used to
    * discover the owner or the leaf.
    */
  def attach[T <: BitVector](
      owner: Component,
      data: T,
      width: ParameterizedBitCount,
      binding: ExternalFormalParameterBinding
  ): T = synchronized {
    attachAll(owner, Vector(data), width, binding)
    data
  }

  /** Atomically attach one formal to every exact leaf in a prepared region. */
  private[core] def attachAll(
      owner: Component,
      data: Vector[BitVector],
      width: ParameterizedBitCount,
      binding: ExternalFormalParameterBinding
  ): Unit = synchronized {
    commitAttachAll(preflightAttachAll(owner, data, width, binding))
  }

  /** Validate the complete formal/width/owner publication without retaining a
    * declaration, component binding, leaf binding or symbolic width. The
    * native formalization transaction consumes this plan only after both its
    * formal and shadow sides have preflighted successfully.
    */
  private[core] def preflightAttachAll(
      owner: Component,
      data: Vector[BitVector],
      width: ParameterizedBitCount,
      binding: ExternalFormalParameterBinding
  ): PreparedLeafAttachment = synchronized {
    if (owner == null)
      throw new IllegalArgumentException("formal component owner must not be null")
    if (data == null || data.exists(_ == null))
      throw new IllegalArgumentException("formal packed leaves must not be null")
    if (data.isEmpty)
      throw new IllegalArgumentException("formal packed leaves must not be empty")
    if (width == null)
      throw new IllegalArgumentException("formal symbolic bit count must not be null")
    if (binding == null)
      throw new IllegalArgumentException("formal parameter binding must not be null")

    validateBinding(binding)
    val retainedWidth =
      validateFormalWidth(width, binding, "external native Int adapter")
    data.foreach(validateExactOwner(owner, _, binding))
    val root = validateDeclarationForDesign(owner, binding)
    validateInstanceBinding(owner, binding, Some(retainedWidth))
    data.foreach(validateLeafBinding(_, binding))
    validateWidthPublication(data, width, retainedWidth)

    PreparedLeafAttachment(owner, data, width, binding, retainedWidth, root)
  }

  /** Commit only a previously validated formal leaf plan. */
  private[core] def commitAttachAll(
      prepared: PreparedLeafAttachment
  ): Unit = synchronized {
    ParameterizedWidth.attachExistingAll(prepared.data, prepared.width)
    retainDeclaration(prepared.root, prepared.binding)
    retainInstanceBinding(
      prepared.owner,
      prepared.binding,
      Some(prepared.retainedWidth)
    )
    prepared.data.foreach(retainLeafBinding(_, prepared.binding))
  }

  /** Retain one explicit definition-formal/instance-actual pair against the
    * exact component object, independent of any later port traversal.
    */
  def retainComponent(
      component: Component,
      binding: ExternalFormalParameterBinding
  ): Unit = synchronized {
    commitRetainComponent(preflightRetainComponent(component, binding))
  }

  private[core] def preflightRetainComponent(
      component: Component,
      binding: ExternalFormalParameterBinding
  ): PreparedComponentAttachment = synchronized {
    if (component == null)
      throw new IllegalArgumentException("formal component must not be null")
    if (binding == null)
      throw new IllegalArgumentException("formal parameter binding must not be null")
    validateBinding(binding)
    val root = validateDeclarationForDesign(component, binding)
    validateInstanceBinding(component, binding, definitionExpression = None)
    PreparedComponentAttachment(component, binding, root)
  }

  private[core] def commitRetainComponent(
      prepared: PreparedComponentAttachment
  ): Unit = synchronized {
    retainDeclaration(prepared.root, prepared.binding)
    retainInstanceBinding(
      prepared.component,
      prepared.binding,
      definitionExpression = None
    )
  }

  /** Retain one Increment-53f typed formal through an opaque per-instance
    * capability. The exact component lookup and exact dependent port leaves are
    * authoritative; the legacy key/class fields created below are diagnostics
    * only and are never consulted by typed validation or hierarchy matching.
    */
  private[spinal] def retainTypedComponent(
      component: Component,
      formal: ElaborationIntegerParameter,
      actual: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  ): ExternalTypedFormalBinding = synchronized {
    if (component == null)
      throw new IllegalArgumentException("typed formal component must not be null")
    if (formal == null)
      throw new IllegalArgumentException("typed formal declaration must not be null")
    if (actual == null)
      throw new IllegalArgumentException("typed formal actual must not be null")
    if (sourceLocation == null)
      throw new IllegalArgumentException("typed formal source-location option must not be null")
    val binding = ExternalFormalParameterBinding(
      formal = formal,
      actual = actual,
      declarationKey = s"typed-elab::${formal.name}",
      ownerClassName = component.getClass.getName,
      sourceLocation = sourceLocation
    )
    validateTypedBindingPayload(formal, actual, sourceLocation)
    reapComponents()
    reapLeaves()

    val componentLookup = new ExternalFormalComponentIdentityRef(component, null)
    val existing = typedInstanceBindings.getOrElse(componentLookup, Vector.empty)
    existing.headOption.foreach { previous =>
      fail(
        "SPINAL-ELAB-FORMAL-TYPED-TOKEN-DUPLICATE",
        s"exact typed child component already retains opaque formal capability '${previous.binding.formal.name}' and cannot add '${formal.name}'",
        sourceLocation.orElse(previous.binding.sourceLocation)
      )
    }
    instanceBindings.get(componentLookup).filter(_.nonEmpty).foreach { _ =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-AUTHORITY-MIXED",
        s"exact child component cannot mix opaque typed formal '${formal.name}' with legacy formal authority",
        sourceLocation
      )
    }

    val token = new ExternalTypedFormalDeclarationToken()
    val retainedBinding = ExternalTypedFormalBinding(binding, token)
    val dependentPorts = component.getOrdredNodeIo.toVector.filter { port =>
      ParameterizedWidth.expressionOf(port).exists { expression =>
        expression.completedParameterRoots.exists(_ eq formal.declarationRoot)
      }
    }
    dependentPorts.foreach { port =>
      if (port.component ne component) {
        fail(
          "SPINAL-ELAB-FORMAL-TYPED-LEAF-OWNER-MISMATCH",
          s"typed formal slot '${formal.name}' selected a port outside its exact child component",
          sourceLocation
        )
      }
      typedRetained
        .get(new ExternalFormalLeafIdentityRef(port, null))
        .foreach { previous =>
          fail(
            "SPINAL-ELAB-FORMAL-TYPED-LEAF-CONFLICT",
            s"one exact child port is claimed by opaque typed formal capabilities '${previous.binding.formal.name}' and '${formal.name}'",
            sourceLocation.orElse(previous.binding.sourceLocation)
          )
        }
      retained
        .get(new ExternalFormalLeafIdentityRef(port, null))
        .foreach { previous =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-AUTHORITY-MIXED",
            s"one exact child port cannot mix opaque typed formal '${formal.name}' with legacy formal '${previous.formal.name}'",
            sourceLocation.orElse(previous.sourceLocation)
          )
        }
    }

    typedInstanceBindings.update(
      new ExternalFormalComponentIdentityRef(component, componentQueue),
      existing :+ retainedBinding
    )
    dependentPorts.foreach { port =>
      typedRetained.update(
        new ExternalFormalLeafIdentityRef(port, leafQueue),
        retainedBinding
      )
    }
    retainedBinding
  }

  /** Opaque typed capabilities for one exact component identity. */
  private[spinal] def typedBindingsOf(
      component: Component
  ): Vector[ExternalTypedFormalBinding] = synchronized {
    if (component == null) Vector.empty
    else {
      reapComponents()
      typedInstanceBindings
        .get(new ExternalFormalComponentIdentityRef(component, null))
        .getOrElse(Vector.empty)
    }
  }

  /** Opaque typed capability attached to one exact dependent port identity. */
  private[spinal] def typedBindingOf(
      data: BaseType
  ): Option[ExternalTypedFormalBinding] = synchronized {
    if (data == null) None
    else {
      reapLeaves()
      typedRetained.get(new ExternalFormalLeafIdentityRef(data, null))
    }
  }

  /** Exact component-identity bindings retained for diagnostics and adapters. */
  def bindingsOf(
      component: Component
  ): Vector[ExternalFormalParameterBinding] = synchronized {
    if (component == null) Vector.empty
    else {
      reapComponents()
      val values = instanceBindings
        .get(new ExternalFormalComponentIdentityRef(component, null))
        .toVector
        .flatMap(_.valuesIterator.flatMap(_.iterator))
        .map(_.binding)
      val typed = typedInstanceBindings
        .get(new ExternalFormalComponentIdentityRef(component, null))
        .toVector
        .flatten
        .map(_.binding)
      distinctBindings(values ++ typed)
        .sortBy(binding => (binding.formal.name, binding.declarationKey, binding.actual.verilog))
    }
  }

  /** Definition-formal and instance-actual metadata for one native leaf. */
  def bindingOf(data: BaseType): Option[ExternalFormalParameterBinding] = synchronized {
    if (data == null) None
    else {
      reapLeaves()
      typedRetained
        .get(new ExternalFormalLeafIdentityRef(data, null))
        .map(_.binding)
        .orElse(retained.get(new ExternalFormalLeafIdentityRef(data, null)))
        .orElse(recoverBinding(data))
    }
  }

  /** Retain each actual expression against the exact concrete component
    * instance that materialized its explicit formal. This prevents one child
    * instance from inheriting another instance's actual when both share the
    * same deterministic declaration identity.
    */
  private def retainInstanceBinding(
      component: Component,
      binding: ExternalFormalParameterBinding,
      definitionExpression: Option[ElaborationIntegerExpression]
  ): Unit = {
    val lookup = new ExternalFormalComponentIdentityRef(component, null)
    val byKey = instanceBindings.get(lookup).getOrElse {
      val created =
        mutable.HashMap.empty[String, Vector[ExternalFormalInstanceBinding]]
      instanceBindings.update(
        new ExternalFormalComponentIdentityRef(component, componentQueue),
        created
      )
      created
    }
    val existing = byKey.getOrElse(binding.declarationKey, Vector.empty)
    val incoming = ExternalFormalInstanceBinding(binding, definitionExpression)
    existing.indexWhere(candidate => equivalentBinding(candidate.binding, binding)) match {
      case index if index >= 0 =>
        (existing(index).definitionExpression, definitionExpression) match {
          case (None, Some(_)) =>
            byKey.update(
              binding.declarationKey,
              existing.updated(index, incoming)
            )
          case _ =>
        }
      case _ =>
        byKey.update(binding.declarationKey, existing :+ incoming)
    }
  }

  private def validateInstanceBinding(
      component: Component,
      binding: ExternalFormalParameterBinding,
      definitionExpression: Option[ElaborationIntegerExpression]
  ): Unit = {
    reapComponents()
    typedInstanceBindings
      .get(new ExternalFormalComponentIdentityRef(component, null))
      .filter(_.nonEmpty)
      .foreach { typed =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-AUTHORITY-MIXED",
          s"exact component cannot attach legacy formal '${binding.formal.name}' after opaque typed formal '${typed.head.binding.formal.name}'",
          binding.sourceLocation.orElse(typed.head.binding.sourceLocation)
        )
      }
    val existing = instanceBindings
      .get(new ExternalFormalComponentIdentityRef(component, null))
      .flatMap(_.get(binding.declarationKey))
      .getOrElse(Vector.empty)
    val incoming = ExternalFormalInstanceBinding(binding, definitionExpression)
    val actualExpressions =
      distinctExpressions((existing :+ incoming).map(_.binding.actual))
    if (actualExpressions.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-AMBIGUOUS",
        s"formal slot '${binding.formal.name}' of one exact component instance maps to multiple actual expressions: ${actualExpressions.map(_.verilog).sorted.mkString(", ")}",
        binding.sourceLocation.orElse(existing.flatMap(_.binding.sourceLocation).headOption)
      )
    }

    val definitionExpressions =
      distinctExpressions((existing :+ incoming).flatMap(_.definitionExpression))
    if (definitionExpressions.size > 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-AMBIGUOUS",
        s"formal slot '${binding.formal.name}' of one exact component instance carries multiple definition roots for expression '${binding.formal.name}'",
        binding.sourceLocation.orElse(existing.flatMap(_.binding.sourceLocation).headOption)
      )
    }
  }

  private def validateExactOwner(
      owner: Component,
      data: BaseType,
      binding: ExternalFormalParameterBinding
  ): Unit = {
    if (owner.getClass.getName != binding.ownerClassName) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-OWNER-MISMATCH",
        s"formal slot '${binding.formal.name}' belongs to '${binding.ownerClassName}' but was explicitly attached to '${owner.getClass.getName}'",
        binding.sourceLocation
      )
    }
    if (data.component ne owner) {
      val actual = Option(data.component).map(_.getClass.getName).getOrElse("<none>")
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-LEAF-OWNER-MISMATCH",
        s"formal slot '${binding.formal.name}' selected a native leaf owned by '$actual' instead of the exact component '${binding.ownerClassName}'",
        binding.sourceLocation
      )
    }
  }

  private def retainLeafBinding(
      data: BaseType,
      binding: ExternalFormalParameterBinding
  ): Unit = {
    val lookup = new ExternalFormalLeafIdentityRef(data, null)
    retained.get(lookup) match {
      case Some(_) =>
      case None =>
        retained.update(
          new ExternalFormalLeafIdentityRef(data, leafQueue),
          binding
        )
    }
  }

  private def validateLeafBinding(
      data: BaseType,
      binding: ExternalFormalParameterBinding
  ): Unit = {
    reapLeaves()
    typedRetained
      .get(new ExternalFormalLeafIdentityRef(data, null))
      .foreach { typed =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-AUTHORITY-MIXED",
          s"one exact native leaf cannot attach legacy formal '${binding.formal.name}' after opaque typed formal '${typed.binding.formal.name}'",
          binding.sourceLocation.orElse(typed.binding.sourceLocation)
        )
      }
    retained
      .get(new ExternalFormalLeafIdentityRef(data, null))
      .filterNot(equivalentBinding(_, binding))
      .foreach { existing =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-METADATA-CONFLICT",
          s"one native packed leaf carries conflicting declarations for formal slot '${binding.formal.name}'",
          binding.sourceLocation.orElse(existing.sourceLocation)
        )
      }
  }

  /** Mirror the conflict portion of ParameterizedWidth.attachExistingAll so a
    * formal transaction cannot consume its token before discovering a stale
    * or foreign retained-width claim. Concrete leaf widths were already
    * checked by the formal region preflight against retainedWidth.default.
    */
  private def validateWidthPublication(
      data: Vector[BitVector],
      width: ParameterizedBitCount,
      retainedWidth: ElaborationIntegerExpression
  ): Unit = {
    data.zipWithIndex.foreach { case (target, index) =>
      if (target.getBitsWidth != width.value) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-WITNESS-MISMATCH",
          s"existing symbolic-width target $index has concrete width ${target.getBitsWidth}, not validated width ${width.value}",
          width.sourceLocation.orElse(retainedWidth.sourceLocation)
        )
      }
      val existingParameter = ParameterizedWidth.parameterOf(target)
      val existingExpression = ParameterizedWidth.expressionOf(target)
      val compatible =
        existingParameter == width.parameter &&
          existingExpression.exists(
            ElabInt.equivalentExpression(_, retainedWidth)
          )
      if ((existingParameter.nonEmpty || existingExpression.nonEmpty) && !compatible) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-WIDTH-PROVENANCE-CONFLICT",
          "one exact native data leaf is associated with conflicting typed width expressions",
          width.sourceLocation.orElse(existingExpression.flatMap(_.sourceLocation))
        )
      }
    }
  }

  /** Recover a binding for clone-derived leaves from the symbolic width copied
    * by ParameterizedWidth and the exact owning component instance. No concrete
    * integer witness or emitted name participates in this lookup.
    */
  private def recoverBinding(
      data: BaseType
  ): Option[ExternalFormalParameterBinding] = {
    val component = data.component
    if (component == null) None
    else {
      reapComponents()
      val expression = ParameterizedWidth.expressionOf(data)
      val candidates = expression.toVector.flatMap { retainedWidth =>
        instanceBindings
          .get(new ExternalFormalComponentIdentityRef(component, null))
          .toVector
          .flatMap { byKey =>
            byKey.valuesIterator.flatMap(_.iterator)
          }
          .collect {
            case slot
                if slot.binding.ownerClassName == component.getClass.getName &&
                  slot.definitionExpression.exists(
                    equivalentExpression(retainedWidth, _)
                  ) =>
              slot.binding
          }
      }.toVector

      distinctBindings(candidates) match {
        case Vector() => None
        case Vector(binding) =>
          retained.update(
            new ExternalFormalLeafIdentityRef(data, leafQueue),
            binding
          )
          Some(binding)
        case ambiguous =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-AMBIGUOUS",
            s"clone-derived leaf in component '${component.definitionName}' matches " +
              "multiple explicit formal declarations: " +
              ambiguous.map(_.formal.name).distinct.sorted.mkString(", "),
            ambiguous.flatMap(_.sourceLocation).headOption
          )
      }
    }
  }

  private[core] def normalizedExpression(
      expression: ElaborationIntegerExpression
  ): ElaborationIntegerExpression = {
    val normalized = expression.copy(
      parameters = expression.parameters.distinct.sortBy(_.name),
      sourceLocation = None,
      parameterRoots = distinctRoots(expression.completedParameterRoots)
    )
    expression.preserveExactAuthorityOn(
      expression.preserveProjectionOn(
        normalized,
        "formal actual normalization"
      ),
      "formal actual normalization"
    )
  }

  private def normalizedSchema(
      expression: ElaborationIntegerExpression
  ): ElaborationIntegerExpression =
    normalizedExpression(expression).copy(
      parameterRoots = Vector.empty,
      exactDomain = None
    )

  /** Canonical module-definition schema projection. Callers must first prove
    * the shared formal declaration key and owner; instance-local declaration
    * roots are deliberately absent from this schema-only representation.
    */
  private[core] def normalizedDefinitionSchema(
      expression: ElaborationIntegerExpression
  ): ElaborationIntegerExpression = normalizedSchema(expression)

  private[core] def equivalentExpression(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean = {
    val leftRoots = distinctRoots(left.completedParameterRoots)
    val rightRoots = distinctRoots(right.completedParameterRoots)
    val rootsCompatible =
      leftRoots.size == rightRoots.size &&
        leftRoots.forall(root => rightRoots.exists(_ eq root))
    rootsCompatible &&
    ElabInt.equivalentExpression(
      normalizedExpression(left),
      normalizedExpression(right)
    )
  }

  /** Compare only the direct canonical formal schema after exact owner/slot
    * authority has already been established. This must never be used for leaf
    * recovery or actual-expression ambiguity checks.
    */
  private[core] def equivalentCanonicalFormalSchema(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean =
    isDirectFormal(left) && isDirectFormal(right) &&
      normalizedSchema(left) == normalizedSchema(right)

  private def isDirectFormal(
      expression: ElaborationIntegerExpression
  ): Boolean =
    expression.generateIndex.isEmpty && (expression.parameters match {
      case Vector(parameter) => expression.verilog == parameter.name
      case _                 => false
    })

  private[core] def distinctExpressions(
      expressions: Vector[ElaborationIntegerExpression]
  ): Vector[ElaborationIntegerExpression] =
    expressions.foldLeft(Vector.empty[ElaborationIntegerExpression]) {
      case (known, expression) if known.exists(equivalentExpression(_, expression)) =>
        known
      case (known, expression) => known :+ expression
    }

  private def distinctBindings(
      bindings: Vector[ExternalFormalParameterBinding]
  ): Vector[ExternalFormalParameterBinding] =
    bindings.foldLeft(Vector.empty[ExternalFormalParameterBinding]) {
      case (known, binding) if known.exists(equivalentBinding(_, binding)) =>
        known
      case (known, binding) => known :+ binding
    }

  private def distinctRoots(
      roots: Vector[ElaborationIntegerParameterRoot]
  ): Vector[ElaborationIntegerParameterRoot] =
    roots.foldLeft(Vector.empty[ElaborationIntegerParameterRoot]) {
      case (known, root) if known.exists(_ eq root) => known
      case (known, root)                            => known :+ root
    }

  private[core] def equivalentBinding(
      left: ExternalFormalParameterBinding,
      right: ExternalFormalParameterBinding
  ): Boolean =
    left.formal == right.formal &&
      equivalentExpression(left.actual, right.actual) &&
      left.declarationKey == right.declarationKey &&
      left.ownerClassName == right.ownerClassName

  private def validateDeclarationForCurrentDesign(
      binding: ExternalFormalParameterBinding
  ): Component = {
    val owner = Option(Component.current).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-OWNER-MISSING",
        s"formal slot '${binding.formal.name}' was materialized without one active Component owner",
        binding.sourceLocation
      )
    }
    validateDeclarationForDesign(owner, binding)
  }

  private def validateDeclarationForDesign(
      owner: Component,
      binding: ExternalFormalParameterBinding
  ): Component = {
    if (owner.getClass.getName != binding.ownerClassName) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-OWNER-MISMATCH",
        s"formal slot '${binding.formal.name}' belongs to '${binding.ownerClassName}' but was materialized in '${owner.getClass.getName}'",
        binding.sourceLocation
      )
    }
    var root = owner
    while (root.parent != null) root = root.parent

    reapRoots()
    val lookup = new ExternalFormalRootIdentityRef(root, null)
    declarations.get(lookup).foreach { byKey =>
      byKey.values
        .find { existing =>
          existing.ownerClassName == binding.ownerClassName &&
          existing.formal.name == binding.formal.name &&
          existing.declarationKey != binding.declarationKey
        }
        .foreach { existing =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-DUPLICATE-DECLARATION",
            s"component definition '${binding.ownerClassName}' declares formal slot '${binding.formal.name}' at multiple explicit call sites",
            binding.sourceLocation.orElse(existing.sourceLocation)
          )
        }

      byKey.get(binding.declarationKey) match {
        case Some(existing) if existing.formal.default != binding.formal.default =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-DEFAULT-CONFLICT",
            s"formal declaration '${binding.formal.name}' has defaults ${existing.formal.default} and ${binding.formal.default} in one elaboration graph",
            binding.sourceLocation.orElse(existing.sourceLocation)
          )
        case Some(existing)
            if existing.formal.minimum != binding.formal.minimum ||
              existing.formal.maximum != binding.formal.maximum =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-DOMAIN-CONFLICT",
            s"formal declaration '${binding.formal.name}' has domains [${existing.formal.minimum}, ${existing.formal.maximum}] and [${binding.formal.minimum}, ${binding.formal.maximum}] in one elaboration graph",
            binding.sourceLocation.orElse(existing.sourceLocation)
          )
        case Some(existing)
            if existing.formal.name != binding.formal.name ||
              existing.ownerClassName != binding.ownerClassName =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
            s"formal declaration identity '${binding.declarationKey}' maps to incompatible names or owners",
            binding.sourceLocation.orElse(existing.sourceLocation)
          )
        case _ =>
      }
    }
    root
  }

  private def retainDeclaration(
      root: Component,
      binding: ExternalFormalParameterBinding
  ): Unit = {
    val lookup = new ExternalFormalRootIdentityRef(root, null)
    val byKey = declarations.get(lookup).getOrElse {
      val created = mutable.HashMap.empty[String, ExternalFormalParameterBinding]
      declarations.update(new ExternalFormalRootIdentityRef(root, rootQueue), created)
      created
    }
    if (!byKey.contains(binding.declarationKey))
      byKey.update(binding.declarationKey, binding)
  }

  private def validatePendingBinding(
      width: ParameterizedBitCount,
      binding: ExternalFormalParameterBinding
  ): Unit = {
    reapBitCounts()
    pending
      .get(new ExternalFormalBitCountIdentityRef(width, null))
      .filterNot(equivalentBinding(_, binding))
      .foreach { existing =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-METADATA-CONFLICT",
          s"one symbolic bit count carries conflicting declarations for formal slot '${binding.formal.name}'",
          binding.sourceLocation.orElse(existing.sourceLocation)
        )
      }
  }

  private def retainPendingBinding(
      width: ParameterizedBitCount,
      binding: ExternalFormalParameterBinding
  ): Unit = {
    val lookup = new ExternalFormalBitCountIdentityRef(width, null)
    if (!pending.contains(lookup)) {
      pending.update(
        new ExternalFormalBitCountIdentityRef(width, bitCountQueue),
        binding
      )
    }
  }

  private[core] def validateBinding(
      binding: ExternalFormalParameterBinding
  ): Unit = {
    validateTypedBindingPayload(
      binding.formal,
      binding.actual,
      binding.sourceLocation
    )
    val formal = binding.formal
    if (binding.declarationKey == null || binding.declarationKey.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-IDENTITY-MISSING",
        s"formal slot '${formal.name}' has no deterministic declaration identity",
        binding.sourceLocation
      )
    }
    if (binding.ownerClassName == null || binding.ownerClassName.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-OWNER-MISSING",
        s"formal slot '${formal.name}' has no component-definition owner identity",
        binding.sourceLocation
      )
    }
  }

  /** Validate the schema and actual shared by both formal paths without
    * consulting legacy source/class identity text. The opaque typed path calls
    * this helper directly; only validateBinding adds key/owner requirements.
    */
  private def validateTypedBindingPayload(
      formal: ElaborationIntegerParameter,
      actual: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  ): Unit = {
    val identifier = "[A-Za-z_][A-Za-z0-9_]*".r
    if (
      sourceLocation == null ||
      sourceLocation.exists(_ == null)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SOURCE-OPTION-NULL",
        "formal parameter binding must retain a non-null source-location option",
        None
      )
    }
    if (formal == null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SCHEMA-NULL",
        "formal parameter binding must retain a non-null formal declaration",
        sourceLocation
      )
    }
    if (
      formal.name == null ||
      !identifier.pattern.matcher(formal.name).matches()
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-NAME-INVALID",
        s"formal parameter name '${formal.name}' is not a portable Verilog identifier",
        sourceLocation
      )
    }
    if (
      formal.default == null || formal.minimum == null ||
      formal.maximum == null || formal.minimum < 1 ||
      formal.maximum < formal.minimum ||
      formal.default < formal.minimum || formal.default > formal.maximum ||
      !formal.default.isValidInt || formal.maximum > BigInt(Int.MaxValue)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-DOMAIN-INVALID",
        s"formal slot '${formal.name}' must have a positive finite Int-sized domain containing default ${formal.default}",
        sourceLocation
      )
    }
    if (actual == null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-NULL",
        s"formal slot '${formal.name}' must retain a non-null actual expression",
        sourceLocation
      )
    }
    ElabInt.validateExpression(
      actual,
      "formal parameter actual expression"
    )
    if (!isCanonicalDirectParameterActual(actual)) {
      ElabInt.requireAuthoritativeIntegerDomain(
        actual,
        "formal parameter actual expression",
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-AUTHORITY-MISSING",
        requireExactExtrema = false
      )
    }
    if (
      actual.default != formal.default ||
      actual.minimum < formal.minimum || actual.maximum > formal.maximum ||
      actual.minimum < 1 || actual.maximum < actual.minimum
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-DOMAIN-UNSUPPORTED",
        s"actual expression '${actual.verilog}' in [${actual.minimum}, ${actual.maximum}] with default ${actual.default} is incompatible with formal '${formal.name}' in [${formal.minimum}, ${formal.maximum}] with default ${formal.default}",
        sourceLocation.orElse(actual.sourceLocation)
      )
    }
  }

  /** Preserve the legacy direct-parameter binding surface without allowing it
    * to become an authority recovery path for derived or copied exact-domain
    * expressions. The exact declaration schema and its one completed root are
    * the only authority in this compatibility case.
    */
  private def isCanonicalDirectParameterActual(
      actual: ElaborationIntegerExpression
  ): Boolean =
    actual.exactDomain.isEmpty && actual.generateIndex.isEmpty &&
      (actual.parameters match {
        case Vector(parameter) =>
          val roots = distinctRoots(actual.completedParameterRoots)
          actual.verilog == parameter.name &&
          actual.default == parameter.default &&
          actual.minimum == parameter.minimum &&
          actual.maximum == parameter.maximum &&
          roots.size == 1 && (roots.head eq parameter.declarationRoot)
        case _ => false
      })

  /** Validate every width field before a formal declaration is reserved. */
  private def validateFormalWidth(
      width: ParameterizedBitCount,
      binding: ExternalFormalParameterBinding,
      role: String
  ): ElaborationIntegerExpression = {
    val retainedWidth = ParameterizedWidth
      .validatedWidthExpression(width)
      .getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-WIDTH-METADATA-MISSING",
          s"formal slot '${binding.formal.name}' has no retained expression on its $role",
          binding.sourceLocation.orElse(width.sourceLocation)
        )
      }
    width.parameter match {
      case Some(parameter) if parameter eq binding.formal =>
      case _ =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-WIDTH-SCHEMA-MISMATCH",
          s"formal slot '${binding.formal.name}' is not the direct parameter carried by its $role",
          binding.sourceLocation
        )
    }
    if (width.value != binding.formal.default) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-WITNESS-MISMATCH",
        s"formal slot '${binding.formal.name}' concrete bit count ${width.value} does not match default ${binding.formal.default}",
        binding.sourceLocation
      )
    }
    val expectedWidth = formalExpression(binding.formal, retainedWidth.sourceLocation)
    if (!equivalentCanonicalFormalSchema(retainedWidth, expectedWidth)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-WIDTH-SCHEMA-MISMATCH",
        s"formal slot '${binding.formal.name}' carries expression '${retainedWidth.verilog}' instead of its canonical formal expression",
        binding.sourceLocation.orElse(retainedWidth.sourceLocation)
      )
    }
    retainedWidth
  }

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
      sourceLocation = sourceLocation
    )

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
