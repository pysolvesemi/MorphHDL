package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}
import java.util.IdentityHashMap

import scala.collection.mutable

/** Deterministic source token for one explicit native-Int formalization call. */
final case class ExternalNativeIntFormalizationToken(
    callSite: String,
    valueOrigin: String,
    role: String
)

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

/**
  * External identity/lifetime sidecar for native `Int` APIs.
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
      reference =
        regionQueue.poll().asInstanceOf[ExternalNativeIntRegionIdentityRef]
    }
  }

  private def reapComponents(): Unit = {
    var reference =
      componentQueue.poll().asInstanceOf[ExternalNativeIntComponentIdentityRef]
    while (reference != null) {
      components.remove(reference)
      reference =
        componentQueue.poll().asInstanceOf[ExternalNativeIntComponentIdentityRef]
    }
  }

  /** Attach one retained expression to the exact Data returned by a region. */
  def attachRegion[T <: Data](
      owner: Component,
      data: T,
      expression: ElaborationIntegerExpression,
      token: ExternalNativeIntFormalizationToken,
      formalBinding: Option[ExternalFormalParameterBinding]
  ): T = synchronized {
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
    attachPrepared(owner, Vector(prepared))
    retainRegions(Vector(prepared))
    data
  }

  /**
    * Attach one component-definition formal and its parent-scope actual to the
    * exact returned child and exact selected packed ports.
    */
  def attachComponent[C <: Component](
      parent: Component,
      component: C,
      geometry: Iterable[Data],
      binding: ExternalFormalParameterBinding,
      token: ExternalNativeIntFormalizationToken
  ): C = synchronized {
    if (parent == null) {
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-PARENT-MISSING",
        "formalComponent requires one active parent Component",
        sourceOf(token)
      )
    }
    if (component == null) {
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-RESULT-NULL",
        "formalComponent constructor returned null",
        sourceOf(token)
      )
    }
    if (binding == null) {
      throw new IllegalArgumentException("formal component binding must not be null")
    }
    ExternalFormalParameterRegistry.validateBinding(binding)
    if (component.parent ne parent) {
      val actualParent =
        Option(component.parent).map(_.getClass.getName).getOrElse("<none>")
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-PARENT-MISMATCH",
        s"formalComponent returned a component owned by '$actualParent' instead of the exact active parent '${parent.getClass.getName}'",
        sourceOf(token)
      )
    }
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

    // Definition-side packed geometry must be byte-for-byte canonical across
    // instances. The binding retains each call-site location for diagnostics;
    // the formal width expression intentionally omits that instance location.
    val expression = formalExpression(binding.formal, sourceLocation = None)
    validateCommon(component, expression, token)
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

    val componentRecord = preflightComponentBinding(
      component = component,
      binding = binding,
      token = token,
      regionCount = prepared.size,
      packedLeafCount = prepared.map(_.leaves.size).sum
    )
    attachPrepared(component, prepared)
    retainComponentBinding(component, componentRecord)
    retainRegions(prepared)
    component
  }

  /**
    * Retain a native component formal that controls storage or structural
    * choices without pretending that the scalar value is a packed port width.
    * The child constructor's shadow capture and internal symbolic registries
    * remain responsible for proving the definition-side parameter dependency.
    */
  def attachComponentParameter[C <: Component](
      parent: Component,
      component: C,
      binding: ExternalFormalParameterBinding,
      token: ExternalNativeIntFormalizationToken
  ): C = synchronized {
    if (parent == null) {
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-PARENT-MISSING",
        "formalComponent.parameter requires one active parent Component",
        sourceOf(token)
      )
    }
    if (component == null) {
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-RESULT-NULL",
        "formalComponent.parameter constructor returned null",
        sourceOf(token)
      )
    }
    if (binding == null) {
      throw new IllegalArgumentException("formal component binding must not be null")
    }
    ExternalFormalParameterRegistry.validateBinding(binding)
    if (component.parent ne parent) {
      val actualParent =
        Option(component.parent).map(_.getClass.getName).getOrElse("<none>")
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-PARENT-MISMATCH",
        s"formalComponent.parameter returned a component owned by '$actualParent' instead of the exact active parent '${parent.getClass.getName}'",
        sourceOf(token)
      )
    }

    val expression = formalExpression(binding.formal, sourceLocation = None)
    validateCommon(component, expression, token)
    validateFormal(component, expression, binding)
    val componentRecord = preflightComponentBinding(
      component = component,
      binding = binding,
      token = token,
      regionCount = 0,
      packedLeafCount = 0
    )
    ExternalFormalParameterRegistry.retainComponent(component, binding)
    retainComponentBinding(component, componentRecord)
    component
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
          ) || value.token != token ||
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
    if (!existing.exists(value =>
          ExternalFormalParameterRegistry.equivalentBinding(
            value.binding,
            incoming.binding
          ) && value.token == incoming.token
        )) {
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

  private def attachPrepared(
      owner: Component,
      prepared: Vector[PreparedRegion]
  ): Unit = {
    prepared.headOption.foreach { first =>
      first.record.formalBinding match {
        case Some(binding) =>
          val width = bitCount(first.record.expression)
          ExternalFormalParameterRegistry.attachAll(
            owner,
            prepared.flatMap(_.leaves),
            width,
            binding
          )
        case None =>
          val validated = prepared.map { region =>
            val width = bitCount(region.record.expression)
            val expression = ParameterizedWidth.validatedWidthExpression(width)
            (region, width, expression)
          }
          validated.foreach { case (region, width, expression) =>
            region.leaves.foreach(
              ParameterizedWidth.attachValidated(_, width, expression)
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
    if (!ExternalFormalParameterRegistry.equivalentCanonicalFormalSchema(
          expression,
          expected
        )) {
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
    left.token == right.token &&
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
