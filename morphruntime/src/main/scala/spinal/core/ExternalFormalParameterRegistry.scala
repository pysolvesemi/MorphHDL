package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.collection.mutable

/**
  * Definition-side identity and instance-side actual expression retained for
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

/**
  * One component-local formal binding plus the exact definition-side width
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

/**
  * MorphHDL-owned formal-to-actual sidecar registry.
  *
  * The frontend first associates one explicit formal declaration with the
  * transient ParameterizedBitCount returned by `formalParam(...).bits`. The
  * MorphHDL packed-data factories then transfer that association to the exact
  * native BitVector leaf by object identity. No native SpinalHDL source or data
  * type is changed.
  */
object ExternalFormalParameterRegistry {
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
  private val declarations = mutable.HashMap.empty[
    ExternalFormalRootIdentityRef,
    mutable.HashMap[String, ExternalFormalParameterBinding]
  ]
  private val instanceBindings = mutable.HashMap.empty[
    ExternalFormalComponentIdentityRef,
    mutable.HashMap[String, Vector[ExternalFormalInstanceBinding]]
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
      reference =
        componentQueue.poll().asInstanceOf[ExternalFormalComponentIdentityRef]
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

  /**
    * Attach an explicit formal to one exact native leaf after an untouched
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

    data.foreach(
      ParameterizedWidth.attachValidated(_, width, Some(retainedWidth))
    )
    retainDeclaration(root, binding)
    retainInstanceBinding(owner, binding, Some(retainedWidth))
    data.foreach(retainLeafBinding(_, binding))
  }

  /**
    * Retain one explicit definition-formal/instance-actual pair against the
    * exact component object, independent of any later port traversal.
    */
  def retainComponent(
      component: Component,
      binding: ExternalFormalParameterBinding
  ): Unit = synchronized {
    if (component == null)
      throw new IllegalArgumentException("formal component must not be null")
    if (binding == null)
      throw new IllegalArgumentException("formal parameter binding must not be null")
    validateBinding(binding)
    val root = validateDeclarationForDesign(component, binding)
    validateInstanceBinding(component, binding, definitionExpression = None)

    retainDeclaration(root, binding)
    retainInstanceBinding(component, binding, definitionExpression = None)
  }

  /** Exact component-identity bindings retained for diagnostics and adapters. */
  def bindingsOf(
      component: Component
  ): Vector[ExternalFormalParameterBinding] = synchronized {
    if (component == null) Vector.empty
    else {
      reapComponents()
      instanceBindings
        .get(new ExternalFormalComponentIdentityRef(component, null))
        .toVector
        .flatMap(_.valuesIterator.flatMap(_.iterator))
        .map(_.binding)
        .distinct
        .sortBy(binding => (binding.formal.name, binding.declarationKey, binding.actual.verilog))
    }
  }

  /** Definition-formal and instance-actual metadata for one native leaf. */
  def bindingOf(data: BaseType): Option[ExternalFormalParameterBinding] = synchronized {
    if (data == null) None
    else {
      reapLeaves()
      retained
        .get(new ExternalFormalLeafIdentityRef(data, null))
        .orElse(recoverBinding(data))
    }
  }

  /**
    * Retain each actual expression against the exact concrete component
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

  /**
    * Recover a binding for clone-derived leaves from the symbolic width copied
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

      candidates.distinct match {
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
  ): ElaborationIntegerExpression =
    expression.copy(
      parameters = expression.parameters.distinct.sortBy(_.name),
      sourceLocation = None,
      parameterRoots = distinctRoots(expression.completedParameterRoots)
    )

  private def normalizedSchema(
      expression: ElaborationIntegerExpression
  ): ElaborationIntegerExpression =
    normalizedExpression(expression).copy(parameterRoots = Vector.empty)

  /**
    * Canonical module-definition schema projection. Callers must first prove
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
    rootsCompatible && normalizedSchema(left) == normalizedSchema(right)
  }

  /**
    * Compare only the direct canonical formal schema after exact owner/slot
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

  private def distinctExpressions(
      expressions: Vector[ElaborationIntegerExpression]
  ): Vector[ElaborationIntegerExpression] =
    expressions.foldLeft(Vector.empty[ElaborationIntegerExpression]) {
      case (known, expression)
          if known.exists(equivalentExpression(_, expression)) =>
        known
      case (known, expression) => known :+ expression
    }

  private def distinctRoots(
      roots: Vector[ElaborationIntegerParameterRoot]
  ): Vector[ElaborationIntegerParameterRoot] =
    roots.foldLeft(Vector.empty[ElaborationIntegerParameterRoot]) {
      case (known, root) if known.exists(_ eq root) => known
      case (known, root)                           => known :+ root
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
      byKey.values.find { existing =>
        existing.ownerClassName == binding.ownerClassName &&
        existing.formal.name == binding.formal.name &&
        existing.declarationKey != binding.declarationKey
      }.foreach { existing =>
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
    val identifier = "[A-Za-z_][A-Za-z0-9_]*".r
    if (
      binding.sourceLocation == null ||
      binding.sourceLocation.exists(_ == null)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SOURCE-OPTION-NULL",
        "formal parameter binding must retain a non-null source-location option",
        None
      )
    }
    val formal = binding.formal
    if (formal == null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SCHEMA-NULL",
        "formal parameter binding must retain a non-null formal declaration",
        binding.sourceLocation
      )
    }
    if (
      formal.name == null ||
      !identifier.pattern.matcher(formal.name).matches()
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-NAME-INVALID",
        s"formal parameter name '${formal.name}' is not a portable Verilog identifier",
        binding.sourceLocation
      )
    }
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
        binding.sourceLocation
      )
    }
    val actual = binding.actual
    if (actual == null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-NULL",
        s"formal slot '${formal.name}' must retain a non-null actual expression",
        binding.sourceLocation
      )
    }
    ElabInt.validateExpression(
      actual,
      "formal parameter actual expression"
    )
    if (
      actual.default != formal.default ||
      actual.minimum < formal.minimum || actual.maximum > formal.maximum ||
      actual.minimum < 1 || actual.maximum < actual.minimum
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-DOMAIN-UNSUPPORTED",
        s"actual expression '${actual.verilog}' in [${actual.minimum}, ${actual.maximum}] with default ${actual.default} is incompatible with formal '${formal.name}' in [${formal.minimum}, ${formal.maximum}] with default ${formal.default}",
        binding.sourceLocation.orElse(actual.sourceLocation)
      )
    }
  }

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
