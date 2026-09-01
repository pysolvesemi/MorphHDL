package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.collection.mutable

import spinal.core.internals.{DataAssignmentStatement, Resize}

/** Elaboration metadata for one public integer parameter used directly as a
  * packed width.
  *
  * The concrete `default` remains the width used by ordinary SpinalHDL
  * elaboration and validation. MorphHDL retains the symbolic identity in an
  * external object-identity registry rather than modifying native data types.
  */
final case class ElaborationIntegerParameter(
    name: String,
    default: BigInt,
    minimum: BigInt,
    maximum: BigInt
) {

  /** Stable identity for callers which retain this exact direct declaration. */
  private[spinal] lazy val declarationRoot: ElaborationIntegerParameterRoot =
    ElaborationIntegerParameterRoot.fresh(name)
}

/** Identity-bearing provenance for one declaration of an elaboration-time
  * parameter. Two declarations can intentionally have the same public name
  * and schema; they are still independent roots until a caller explicitly
  * proves otherwise by carrying this exact object through derived expressions.
  */
final class ElaborationIntegerParameterRoot private (
    val name: String,
    val sourceLocation: Option[String]
) {
  private[this] var authoritativeSchema: ElaborationIntegerParameter = null

  /** Trusted exact-domain construction binds one declaration root to one
    * exact schema object. Public expression/domain copying can retain the root
    * reference, but it can never bind a replacement schema during validation.
    */
  private[spinal] def bindAuthoritativeSchema(
      schema: ElaborationIntegerParameter,
      role: String,
      useLocation: Option[String]
  ): Unit = synchronized {
    if (schema == null) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-DOMAIN-ROOT-SCHEMA-IDENTITY-CONFLICT",
        s"$role cannot bind declaration root '$name' to a null schema",
        useLocation.orElse(sourceLocation)
      )
    }
    if (authoritativeSchema eq null) authoritativeSchema = schema
    else if (authoritativeSchema ne schema) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-DOMAIN-ROOT-SCHEMA-IDENTITY-CONFLICT",
        s"$role cannot bind declaration root '$name' to a replacement schema object",
        useLocation.orElse(sourceLocation)
      )
    }
  }

  /** Validation is read-only: an unbound or differently bound root is never
    * repaired from public metadata.
    */
  private[spinal] def isAuthoritativeSchema(
      schema: ElaborationIntegerParameter
  ): Boolean = synchronized {
    (authoritativeSchema ne null) && (authoritativeSchema eq schema)
  }

  override def toString: String = s"ElaborationIntegerParameterRoot($name)"
}

object ElaborationIntegerParameterRoot {

  /** Allocate provenance for one exact frontend parameter declaration. */
  def fresh(
      name: String,
      sourceLocation: Option[String] = None
  ): ElaborationIntegerParameterRoot = {
    require(name != null && name.nonEmpty, "parameter-root name must not be empty")
    if (sourceLocation == null || sourceLocation.exists(_ == null)) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-INT-PARAMETER-ROOT-SOURCE-OPTION-NULL",
        s"parameter root '$name' must retain a non-null source-location option",
        None
      )
    }
    new ElaborationIntegerParameterRoot(name, sourceLocation)
  }
}

/** Backend-neutral integer expression retained during ordinary SpinalHDL
  * elaboration for symbolic widths, hierarchy, structure, processes and memory
  * geometry.
  *
  * `default` is the concrete witness used by the native SpinalHDL graph.
  * Before structural projection, `minimum` and `maximum` describe the complete
  * admitted parameter domain. An exact branch projection may narrow all three
  * fields and records its admitted root values privately on that exact
  * expression object; a case-class copy deliberately loses that authority.
  */
final case class ElaborationIntegerExpression(
    verilog: String,
    default: BigInt,
    minimum: BigInt,
    maximum: BigInt,
    parameters: Vector[ElaborationIntegerParameter],
    generateIndex: Option[String] = None,
    sourceLocation: Option[String] = None,
    parameterRoots: Vector[ElaborationIntegerParameterRoot] = Vector.empty,
    private[spinal] val exactDomain: Option[
      ElaborationExactDomain[BigInt]
    ] = None
) {
  @transient private[this] var _projectionProvenance: ElaborationProjectionProvenance = null
  @transient private[this] var _exactAuthorityDomain: ElaborationExactDomain[BigInt] = null

  /** Certify exact evidence on this JVM object only. Generated case-class
    * copies deliberately start without this stamp even though Scala copies the
    * constructor field containing the domain.
    */
  private[spinal] def attachExactAuthority(
      domain: ElaborationExactDomain[BigInt],
      role: String
  ): ElaborationIntegerExpression = synchronized {
    if (domain == null || !exactDomain.exists(_ eq domain)) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-IDENTITY-MISMATCH",
        s"$role cannot certify an exact domain that is not retained by expression '$verilog'",
        sourceLocation
      )
    }
    if ((_exactAuthorityDomain ne null) && (_exactAuthorityDomain ne domain)) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-CONFLICT",
        s"$role cannot replace exact authority on expression '$verilog'",
        sourceLocation
      )
    }
    _exactAuthorityDomain = domain
    this
  }

  private[spinal] def hasExactAuthority: Boolean = synchronized {
    exactDomain match {
      case Some(domain) => _exactAuthorityDomain eq domain
      case None         => _exactAuthorityDomain eq null
    }
  }

  private[spinal] def preserveExactAuthorityOn(
      target: ElaborationIntegerExpression,
      role: String
  ): ElaborationIntegerExpression = synchronized {
    if (target == null)
      throw new IllegalArgumentException(s"$role exact-authority target must not be null")
    exactDomain match {
      case Some(domain) if _exactAuthorityDomain eq domain =>
        target.attachExactAuthority(domain, role)
      case _ => target
    }
  }

  /** Attach construction provenance to this exact projected expression.
    * Generated case-class copies start with an empty slot by design.
    */
  private[spinal] def attachProjection(
      domain: ElaborationExactDomain[BigInt],
      admitted: Set[BigInt],
      representative: BigInt,
      role: String,
      sourceLocation: Option[String]
  ): ElaborationIntegerExpression = synchronized {
    val incoming = ElaborationProjectionProvenance.integer(
      this,
      domain,
      admitted,
      representative,
      role,
      sourceLocation
    )
    if (
      (_projectionProvenance ne null) &&
      !_projectionProvenance.sameAs(incoming)
    ) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-CONFLICT",
        s"$role expression '$verilog' already carries conflicting exact projection provenance",
        sourceLocation.orElse(this.sourceLocation)
      )
    }
    _projectionProvenance = incoming
    attachExactAuthority(domain, role)
    this
  }

  /** Projection provenance for this expression object only. */
  private[spinal] def projectionProvenance: Option[ElaborationProjectionProvenance] = synchronized {
    Option(_projectionProvenance)
  }

  /** Explicitly preserve private provenance across one trusted normalization. */
  private[spinal] def preserveProjectionOn(
      target: ElaborationIntegerExpression,
      role: String
  ): ElaborationIntegerExpression =
    projectionProvenance match {
      case None => target
      case Some(projection) =>
        val domain = target.exactDomain match {
          case Some(value)
              if exactDomain.exists(_ eq value) &&
                (value.root eq projection.root) =>
            value
          case _ =>
            ParameterizedVerilogException.fail(
              "SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-IDENTITY-MISMATCH",
              s"$role cannot transfer projection provenance to a different exact-domain object",
              target.sourceLocation.orElse(sourceLocation)
            )
        }
        target.attachProjection(
          domain,
          projection.admitted,
          projection.representative,
          role,
          target.sourceLocation.orElse(sourceLocation)
        )
    }

  /** Complete provenance allocated once for this exact expression object.
    * Re-converting the same carrier therefore preserves declaration identity,
    * and copies preserve the identities of their exact parameter objects.
    */
  private[core] lazy val completedParameterRoots: Vector[
    ElaborationIntegerParameterRoot
  ] = {
    val rootedNames = parameterRoots.map(_.name).toSet
    parameterRoots ++ parameters
      .filterNot(parameter => rootedNames.contains(parameter.name))
      .map(_.declarationRoot)
  }
}

/** Boolean counterpart used by retained parameter-controlled metadata. */
final case class ElaborationBooleanExpression(
    verilog: String,
    default: Boolean,
    parameters: Vector[ElaborationIntegerParameter],
    sourceLocation: Option[String] = None,
    parameterRoots: Vector[ElaborationIntegerParameterRoot] = Vector.empty,
    private[spinal] val exactDomain: Option[
      ElaborationExactDomain[Boolean]
    ] = None
) {
  @transient private[this] var _projectionProvenance: ElaborationProjectionProvenance = null
  @transient private[this] var _exactAuthorityDomain: ElaborationExactDomain[Boolean] = null

  private[spinal] def attachExactAuthority(
      domain: ElaborationExactDomain[Boolean],
      role: String
  ): ElaborationBooleanExpression = synchronized {
    if (domain == null || !exactDomain.exists(_ eq domain)) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-IDENTITY-MISMATCH",
        s"$role cannot certify an exact domain that is not retained by predicate '$verilog'",
        sourceLocation
      )
    }
    if ((_exactAuthorityDomain ne null) && (_exactAuthorityDomain ne domain)) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-CONFLICT",
        s"$role cannot replace exact authority on predicate '$verilog'",
        sourceLocation
      )
    }
    _exactAuthorityDomain = domain
    this
  }

  private[spinal] def hasExactAuthority: Boolean = synchronized {
    exactDomain match {
      case Some(domain) => _exactAuthorityDomain eq domain
      case None         => _exactAuthorityDomain eq null
    }
  }

  private[spinal] def preserveExactAuthorityOn(
      target: ElaborationBooleanExpression,
      role: String
  ): ElaborationBooleanExpression = synchronized {
    if (target == null)
      throw new IllegalArgumentException(s"$role exact-authority target must not be null")
    exactDomain match {
      case Some(domain) if _exactAuthorityDomain eq domain =>
        target.attachExactAuthority(domain, role)
      case _ => target
    }
  }

  /** Boolean counterpart of exact integer projection attachment. */
  private[spinal] def attachProjection(
      domain: ElaborationExactDomain[Boolean],
      admitted: Set[BigInt],
      representative: BigInt,
      role: String,
      sourceLocation: Option[String]
  ): ElaborationBooleanExpression = synchronized {
    val incoming = ElaborationProjectionProvenance.boolean(
      this,
      domain,
      admitted,
      representative,
      role,
      sourceLocation
    )
    if (
      (_projectionProvenance ne null) &&
      !_projectionProvenance.sameAs(incoming)
    ) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-CONFLICT",
        s"$role predicate '$verilog' already carries conflicting exact projection provenance",
        sourceLocation.orElse(this.sourceLocation)
      )
    }
    _projectionProvenance = incoming
    attachExactAuthority(domain, role)
    this
  }

  /** Projection provenance for this predicate object only. */
  private[spinal] def projectionProvenance: Option[ElaborationProjectionProvenance] = synchronized {
    Option(_projectionProvenance)
  }

  /** Boolean counterpart of trusted projection-preserving normalization. */
  private[spinal] def preserveProjectionOn(
      target: ElaborationBooleanExpression,
      role: String
  ): ElaborationBooleanExpression =
    projectionProvenance match {
      case None => target
      case Some(projection) =>
        val domain = target.exactDomain match {
          case Some(value)
              if exactDomain.exists(_ eq value) &&
                (value.root eq projection.root) =>
            value
          case _ =>
            ParameterizedVerilogException.fail(
              "SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-IDENTITY-MISMATCH",
              s"$role cannot transfer projection provenance to a different exact-domain object",
              target.sourceLocation.orElse(sourceLocation)
            )
        }
        target.attachProjection(
          domain,
          projection.admitted,
          projection.representative,
          role,
          target.sourceLocation.orElse(sourceLocation)
        )
    }

  /** Boolean counterpart of integer-expression identity completion. */
  private[core] lazy val completedParameterRoots: Vector[
    ElaborationIntegerParameterRoot
  ] = {
    val rootedNames = parameterRoots.map(_.name).toSet
    parameterRoots ++ parameters
      .filterNot(parameter => rootedNames.contains(parameter.name))
      .map(_.declarationRoot)
  }
}

/** A concrete witness bit count with an optional bounded symbolic expression. */
final case class ParameterizedBitCount(
    value: Int,
    parameter: Option[ElaborationIntegerParameter],
    sourceLocation: Option[String] = None,
    expression: Option[ElaborationIntegerExpression] = None
)

object ParameterizedBitCount {
  def apply(
      value: Int,
      parameter: ElaborationIntegerParameter
  ): ParameterizedBitCount =
    new ParameterizedBitCount(value, Some(parameter), sourceLocation = None)

  def apply(
      value: Int,
      parameter: ElaborationIntegerParameter,
      sourceLocation: Option[String]
  ): ParameterizedBitCount =
    new ParameterizedBitCount(value, Some(parameter), sourceLocation)
}

private[core] final case class RetainedWidth(
    directParameter: Option[ElaborationIntegerParameter],
    expression: Option[ElaborationIntegerExpression],
    sourceLocation: Option[String]
)

/** Weak key with identity, rather than hardware equality, semantics. */
private[core] final class RetainedWidthIdentityRef(
    value: BaseType,
    queue: ReferenceQueue[BaseType]
) extends WeakReference[BaseType](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: RetainedWidthIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** Weak identity key for one exact native Resize expression. */
private[core] final class RetainedResizeIdentityRef(
    value: Resize,
    queue: ReferenceQueue[Resize]
) extends WeakReference[Resize](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: RetainedResizeIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** MorphHDL-owned symbolic-width registry and native-factory adapters.
  *
  * Native data and factory algorithms remain authoritative. The audited typed
  * `Bits`, `UInt` and `SInt` overloads attach retained geometry to the value
  * returned by those factories; this registry associates that geometry with
  * concrete native objects by identity. Clone-sensitive APIs still delegate to
  * the ordinary SpinalHDL algorithms.
  */
object ParameterizedWidth {

  /** Capture-only marker for one exact typed Resize carrier. MorphHDL consumes
    * and removes it before native input normalization; unlike tagAutoResize it
    * must never change native width inference or assignment semantics.
    */
  private[spinal] object TypedResizeCaptureTag extends SpinalTag

  private val PortableParameterName = "[A-Za-z_][A-Za-z0-9_]*".r
  private val queue = new ReferenceQueue[BaseType]()
  private val retained = mutable.HashMap.empty[RetainedWidthIdentityRef, RetainedWidth]
  private val resizeQueue = new ReferenceQueue[Resize]()
  private val retainedResizes = mutable.HashMap.empty[
    RetainedResizeIdentityRef,
    ElaborationIntegerExpression
  ]

  private def reap(): Unit = {
    var reference = queue.poll().asInstanceOf[RetainedWidthIdentityRef]
    while (reference != null) {
      retained.remove(reference)
      reference = queue.poll().asInstanceOf[RetainedWidthIdentityRef]
    }
  }

  private def reapResizes(): Unit = {
    var reference = resizeQueue.poll().asInstanceOf[RetainedResizeIdentityRef]
    while (reference != null) {
      retainedResizes.remove(reference)
      reference = resizeQueue.poll().asInstanceOf[RetainedResizeIdentityRef]
    }
  }

  private def retainResize(
      resize: Resize,
      expression: ElaborationIntegerExpression
  ): Unit = synchronized {
    reapResizes()
    val lookup = new RetainedResizeIdentityRef(resize, null)
    retainedResizes.get(lookup) match {
      case Some(existing) if ElabInt.equivalentExpression(existing, expression) => ()
      case Some(existing) =>
        ParameterizedVerilogException.fail(
          "SPINAL-PARAMETERIZED-VERILOG-RESIZE-PROVENANCE-CONFLICT",
          s"one exact native Resize target is associated with conflicting typed expressions '${existing.verilog}' and '${expression.verilog}'",
          expression.sourceLocation.orElse(existing.sourceLocation)
        )
      case None =>
        retainedResizes.update(
          new RetainedResizeIdentityRef(resize, resizeQueue),
          expression
        )
    }
  }

  private def metadataOf(data: BaseType): Option[RetainedWidth] = synchronized {
    reap()
    retained.get(new RetainedWidthIdentityRef(data, null))
  }

  private def retain(data: BaseType, metadata: RetainedWidth): Unit = synchronized {
    reap()
    val lookup = new RetainedWidthIdentityRef(data, null)
    retained.get(lookup) match {
      case Some(existing) if equivalentMetadata(existing, metadata) => ()
      case Some(existing) =>
        ParameterizedVerilogException.fail(
          "SPINAL-PARAMETERIZED-VERILOG-WIDTH-PROVENANCE-CONFLICT",
          "one exact native data leaf is associated with conflicting typed width expressions",
          metadata.sourceLocation.orElse(existing.sourceLocation)
        )
      case None =>
        retained.update(new RetainedWidthIdentityRef(data, queue), metadata)
    }
  }

  private def equivalentMetadata(
      left: RetainedWidth,
      right: RetainedWidth
  ): Boolean =
    left.directParameter == right.directParameter &&
      ((left.expression, right.expression) match {
        case (None, None)       => true
        case (Some(l), Some(r)) => ElabInt.equivalentExpression(l, r)
        case _                  => false
      })

  private def completeExpression(
      expression: ElaborationIntegerExpression
  ): ElaborationIntegerExpression = {
    val roots = expression.completedParameterRoots
    if (roots == expression.parameterRoots) expression
    else
      expression.preserveExactAuthorityOn(
        expression.preserveProjectionOn(
          expression.copy(parameterRoots = roots),
          "parameterized-width root normalization"
        ),
        "parameterized-width root normalization"
      )
  }

  private def retainedExpression(
      width: ParameterizedBitCount
  ): Option[ElaborationIntegerExpression] =
    width.expression.map(completeExpression).orElse {
      width.parameter.map { parameter =>
        ElaborationIntegerExpression(
          verilog = parameter.name,
          default = parameter.default,
          minimum = parameter.minimum,
          maximum = parameter.maximum,
          parameters = Vector(parameter),
          sourceLocation = width.sourceLocation,
          parameterRoots = Vector(parameter.declarationRoot)
        )
      }
    }

  private def validateParameterSchema(
      parameter: ElaborationIntegerParameter,
      sourceLocation: Option[String]
  ): Unit = {
    if (parameter == null) {
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-NULL",
        "symbolic width carries a null parameter declaration",
        sourceLocation
      )
    }
    if (
      parameter.name == null ||
      !PortableParameterName.pattern.matcher(parameter.name).matches()
    ) {
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-NAME-INVALID",
        s"parameter name '${parameter.name}' is not a portable Verilog identifier",
        sourceLocation
      )
    }
    if (
      parameter.default == null || parameter.minimum == null ||
      parameter.maximum == null || parameter.minimum < 0 ||
      parameter.maximum < parameter.minimum ||
      parameter.default < parameter.minimum || parameter.default > parameter.maximum ||
      !parameter.default.isValidInt || parameter.maximum > BigInt(Int.MaxValue)
    ) {
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-DOMAIN-INVALID",
        s"parameter '${parameter.name}' must have a non-negative bounded Scala Int domain with its default inside that domain",
        sourceLocation
      )
    }
  }

  private def isExactDirectParameterExpression(
      parameter: ElaborationIntegerParameter,
      expression: ElaborationIntegerExpression
  ): Boolean =
    expression.verilog.trim == parameter.name &&
      (expression.parameters match {
        case Vector(expressionParameter) => expressionParameter eq parameter
        case _                           => false
      }) &&
      expression.generateIndex.isEmpty &&
      expression.default == parameter.default &&
      expression.minimum == parameter.minimum &&
      expression.maximum == parameter.maximum

  /** Validate all redundant concrete and symbolic width fields before mutating
    * the native value. Public case-class construction must not permit an
    * incoherent witness to reach the retained-width registry.
    */
  private def validateWidth(
      width: ParameterizedBitCount
  ): Option[ElaborationIntegerExpression] = {
    if (width == null)
      throw new IllegalArgumentException("symbolic bit count must not be null")
    if (
      width.sourceLocation == null ||
      width.sourceLocation.exists(_ == null)
    ) {
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-SOURCE-OPTION-NULL",
        "symbolic width source-location option must not be null",
        None
      )
    }
    if (width.parameter == null) {
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-OPTION-NULL",
        "symbolic width parameter option must not be null",
        width.sourceLocation
      )
    }
    if (width.value < 1) {
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXPRESSION-DOMAIN-NONPOSITIVE",
        s"concrete width ${width.value} must be positive",
        width.sourceLocation
      )
    }
    if (width.expression == null) {
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXPRESSION-OPTION-NULL",
        "symbolic width expression option must not be null",
        width.sourceLocation
      )
    }
    if (width.parameter.exists(_ == null)) {
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-NULL",
        "symbolic width carries a null direct parameter declaration",
        width.sourceLocation
      )
    }
    if (width.expression.exists(_ == null)) {
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXPRESSION-NULL",
        "symbolic width carries a null retained expression",
        width.sourceLocation
      )
    }

    width.parameter.foreach(validateParameterSchema(_, width.sourceLocation))
    width.expression.foreach { expression =>
      ElabInt.validateExpression(expression, "parameterized bit-count expression")
      expression.parameters.foreach(
        validateParameterSchema(_, expression.sourceLocation.orElse(width.sourceLocation))
      )
    }
    width.parameter.foreach { parameter =>
      if (parameter.default != BigInt(width.value)) {
        ParameterizedVerilogException.fail(
          "SPINAL-PARAMETERIZED-VERILOG-WITNESS-MISMATCH",
          s"concrete width ${width.value} does not match direct parameter '${parameter.name}' default ${parameter.default}",
          width.sourceLocation
        )
      }
    }
    for {
      parameter <- width.parameter
      expression <- width.expression
    } {
      if (!isExactDirectParameterExpression(parameter, expression)) {
        ParameterizedVerilogException.fail(
          "SPINAL-PARAMETERIZED-VERILOG-DIRECT-PARAMETER-EXPRESSION-MISMATCH",
          s"direct parameter '${parameter.name}' does not match retained expression '${expression.verilog}' and its bounds",
          expression.sourceLocation.orElse(width.sourceLocation)
        )
      }
    }
    width.expression.foreach { value =>
      if (value.parameters.nonEmpty) {
        // The legacy direct-parameter case is authoritative by construction:
        // its emitted text, complete schema and declaration root are the same
        // public parameter. Every derived symbolic expression must instead
        // retain exhaustive typed evaluation evidence.
        val safeLegacyDirect = value.exactDomain.isEmpty && width.parameter.exists { parameter =>
          isExactDirectParameterExpression(parameter, value) &&
          (value.completedParameterRoots match {
            case Vector(root) => root eq parameter.declarationRoot
            case _            => false
          })
        }
        if (!safeLegacyDirect)
          ElabInt.requireAuthoritativeIntegerDomain(
            value,
            "parameterized bit-count expression",
            "SPINAL-PARAMETERIZED-VERILOG-WIDTH-EXACT-DOMAIN-REQUIRED",
            requireExactExtrema = false
          )
      }
    }

    val expression = retainedExpression(width)
    expression.foreach { value =>
      ElabInt.validateExpression(value, "parameterized bit-count expression")
      if (value.minimum < 1) {
        ParameterizedVerilogException.fail(
          "SPINAL-PARAMETERIZED-VERILOG-EXPRESSION-DOMAIN-NONPOSITIVE",
          s"width expression '${value.verilog}' reaches ${value.minimum}; every retained width must stay positive",
          value.sourceLocation.orElse(width.sourceLocation)
        )
      }
      if (value.maximum > BigInt(Int.MaxValue)) {
        ParameterizedVerilogException.fail(
          "SPINAL-PARAMETERIZED-VERILOG-EXPRESSION-DOMAIN-TOO-LARGE",
          s"width expression '${value.verilog}' reaches ${value.maximum}, above the Scala Int width domain",
          value.sourceLocation.orElse(width.sourceLocation)
        )
      }
      if (value.default != BigInt(width.value)) {
        ParameterizedVerilogException.fail(
          "SPINAL-PARAMETERIZED-VERILOG-WITNESS-MISMATCH",
          s"concrete width ${width.value} does not match retained expression default ${value.default}",
          value.sourceLocation.orElse(width.sourceLocation)
        )
      }
    }
    expression
  }

  /** Attach a symbolic width to one concrete native bit vector. */
  def attach[T <: BitVector](data: T, width: ParameterizedBitCount): T = {
    if (data == null) throw new IllegalArgumentException("symbolic-width target must not be null")
    val expression = validatedWidthExpression(width)
    attachValidated(data, width, expression)
  }

  /** Commit one width whose complete symbolic metadata was already validated. */
  private[spinal] def attachValidated[T <: BitVector](
      data: T,
      width: ParameterizedBitCount,
      expression: Option[ElaborationIntegerExpression]
  ): T = {
    data.setWidth(width.value)
    if (expression.exists(_.parameters.nonEmpty)) {
      retain(
        data,
        RetainedWidth(width.parameter, expression, width.sourceLocation)
      )
    }
    data
  }

  /** Attach one typed target width to a native resize result and retain the
    * exact internal Resize node before weak-clone normalization can remove the
    * result object. The association is by JVM identity and is generic across
    * all native algorithms.
    */
  def attachResize[T <: BitVector](data: T, width: ElabInt): T = {
    if (width == null)
      throw new IllegalArgumentException("typed resize width must not be null")
    val result = attach(data, width.toParameterizedBitCount("typed resize"))
    val expression = expressionOf(result).getOrElse(width.expression)
    if (expression.parameters.nonEmpty && result.hasOnlyOneStatement) {
      result.head match {
        case assignment: DataAssignmentStatement
            if (assignment.target eq result) &&
              (assignment.finalTarget eq result) =>
          assignment.source match {
            case resize: Resize if resize.size == result.getBitsWidth =>
              if (expression.default != BigInt(resize.size)) {
                ParameterizedVerilogException.fail(
                  "SPINAL-PARAMETERIZED-VERILOG-RESIZE-WITNESS-MISMATCH",
                  s"native Resize target ${resize.size} does not match typed width default ${expression.default}",
                  expression.sourceLocation
                )
              }
              // A witness-sized Resize may be normalized away before
              // publication. This private marker lets MorphHDL retain exact
              // identities without making the resize native-auto-resizable.
              result.addTag(TypedResizeCaptureTag)
              retainResize(resize, expression)
            case _ =>
          }
        case _ =>
      }
    }
    result
  }

  /** Validate a typed resize before the native resize node is constructed. */
  private[spinal] def validatedResizeWitness(width: ElabInt): Int = {
    if (width == null)
      throw new IllegalArgumentException("typed resize width must not be null")
    width.toParameterizedBitCount("typed resize").value
  }

  /** Look up one typed target width only by exact native Resize identity. */
  def resizeExpressionOf(
      resize: Resize
  ): Option[ElaborationIntegerExpression] = synchronized {
    if (resize == null) None
    else {
      reapResizes()
      retainedResizes.get(new RetainedResizeIdentityRef(resize, null))
    }
  }

  /** Validate a symbolic width and return the exact retained expression. */
  private[spinal] def validatedWidthExpression(
      width: ParameterizedBitCount
  ): Option[ElaborationIntegerExpression] =
    validateWidth(width)

  /** MorphHDL shadow factories; each delegates to the untouched native factory. */
  private[spinal] def validatedWidthWitness(width: ParameterizedBitCount): Int = {
    validatedWidthExpression(width)
    width.value
  }

  def Bits(width: ParameterizedBitCount): spinal.core.Bits =
    attach(spinal.core.Bits(BitCount(validatedWidthWitness(width))), width)
  def Bits(width: BitCount): spinal.core.Bits = spinal.core.Bits(width)

  def UInt(width: ParameterizedBitCount): spinal.core.UInt =
    attach(spinal.core.UInt(BitCount(validatedWidthWitness(width))), width)
  def UInt(width: BitCount): spinal.core.UInt = spinal.core.UInt(width)

  def SInt(width: ParameterizedBitCount): spinal.core.SInt =
    attach(spinal.core.SInt(BitCount(validatedWidthWitness(width))), width)
  def SInt(width: BitCount): spinal.core.SInt = spinal.core.SInt(width)

  /** Copy registry ownership between already-created native leaves. */
  def copy(from: BaseType, to: BaseType): Unit = {
    if (from == null || to == null)
      throw new IllegalArgumentException("symbolic-width copy requires non-null leaves")
    metadataOf(from).foreach(retain(to, _))
  }

  /** Copy concrete and symbolic leaf geometry in deterministic data-model order.
    * This is the external replacement for the former native `BaseType.clone`
    * hook.
    */
  def copyShape[T <: Data](from: T, to: T): T = {
    if (from == null || to == null)
      throw new IllegalArgumentException("symbolic shape copy requires non-null data")
    val sourceLeaves = from.flatten.toVector
    val targetLeaves = to.flatten.toVector
    if (sourceLeaves.size != targetLeaves.size) {
      throw new IllegalArgumentException(
        s"symbolic shape clone changed leaf count ${sourceLeaves.size} -> ${targetLeaves.size}"
      )
    }
    sourceLeaves.zip(targetLeaves).zipWithIndex.foreach { case ((source, target), index) =>
      if (source.getClass != target.getClass) {
        throw new IllegalArgumentException(
          s"symbolic shape clone changed leaf $index from ${source.getClass.getName} " +
            s"to ${target.getClass.getName}"
        )
      }
      (source, target) match {
        case (sourceVector: BitVector, targetVector: BitVector) =>
          targetVector.setWidth(sourceVector.getBitsWidth)
        case _ =>
      }
      copy(source, target)
    }
    to
  }

  /** Native clone algorithm plus external concrete/symbolic shape propagation. */
  def cloneOf[T <: Data](data: T): T =
    copyShape(data, spinal.core.cloneOf(data))

  /** Native HardType algorithm supplied with an externally shape-preserving
    * generator. A stable template is cloned on every invocation.
    */
  def HardType[T <: Data](dataType: => T): spinal.core.HardType[T] = {
    val template = dataType
    new spinal.core.HardType[T](cloneOf(template))
  }

  /** Untouched native register algorithm driven by the retained HardType. */
  def Reg[T <: Data](dataType: => T): T = spinal.core.Reg(HardType(dataType))

  /** Untouched native Vec algorithm driven by the retained HardType. */
  def Vec[T <: Data](dataType: => T, size: Int): spinal.core.Vec[T] =
    spinal.core.Vec(HardType(dataType), size)

  /** Typed-depth overload; literal depths delegate inside the native Vec
    * factory, while symbolic depths retain factorized Vec geometry.
    */
  def Vec[T <: Data](dataType: => T, size: ElabInt): spinal.core.Vec[T] = {
    if (size == null)
      throw new IllegalArgumentException("typed Vec size must not be null")
    if (size.isConcrete) Vec(dataType, size.witness)
    else {
      // Match the Int helper above: after create has proved depth authority,
      // evaluate the authored generator once and clone one stable,
      // shape-preserving HardType through the native Vec construction path.
      lazy val retainedType = HardType(dataType)
      ParameterizedVec.create(size, "typed Vec size")(retainedType())
    }
  }

  def Vec[T <: Data](dataType: spinal.core.HardType[T], size: Int): spinal.core.Vec[T] =
    spinal.core.Vec(dataType, size)

  def Vec[T <: Data](
      dataType: spinal.core.HardType[T],
      size: ElabInt
  ): spinal.core.Vec[T] = {
    if (size == null)
      throw new IllegalArgumentException("typed Vec size must not be null")
    if (size.isConcrete) Vec(dataType, size.witness)
    else ParameterizedVec.create(size, "typed Vec size")(dataType())
  }

  def isRetained(data: BaseType): Boolean = metadataOf(data).nonEmpty

  def parameterOf(data: BaseType): Option[ElaborationIntegerParameter] =
    metadataOf(data).flatMap(_.directParameter)

  def expressionOf(data: BaseType): Option[ElaborationIntegerExpression] =
    metadataOf(data).flatMap(_.expression)

  def sourceLocationOf(data: BaseType): Option[String] =
    metadataOf(data).flatMap(_.sourceLocation)

  def leavesOf(data: Data): Vector[BaseType] =
    data.flatten.filter(expressionOf(_).exists(_.parameters.nonEmpty)).toVector

  def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
    val leaves = scala.collection.mutable.ArrayBuffer.empty[BaseType]
    component.dslBody.walkLeafStatements {
      case baseType: BaseType if expressionOf(baseType).exists(_.parameters.nonEmpty) =>
        leaves += baseType
      case _ =>
    }
    val associated = leaves.flatMap { baseType =>
      expressionOf(baseType).toVector.flatMap(
        _.parameters.map(parameter => baseType -> parameter)
      )
    }
    val values = associated.map(_._2)
    values
      .groupBy(_.name)
      .collectFirst {
        case (name, schemas) if schemas.distinct.size != 1 => name
      }
      .foreach { name =>
        ParameterizedVerilogException.fail(
          "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
          s"parameter '$name' has conflicting declarations on component '${component.definitionName}'",
          associated.find(_._2.name == name).flatMap { case (baseType, _) =>
            sourceLocationOf(baseType)
          }
        )
      }
    val associatedRoots = leaves.flatMap { baseType =>
      expressionOf(baseType).toVector.flatMap(
        _.parameterRoots.map(root => baseType -> root)
      )
    }
    associatedRoots
      .groupBy(_._2.name)
      .collectFirst {
        case (name, roots)
            if roots
              .map(_._2)
              .foldLeft(Vector.empty[ElaborationIntegerParameterRoot]) {
                case (known, root) if known.exists(_ eq root) => known
                case (known, root)                            => known :+ root
              }
              .size > 1 =>
          name
      }
      .foreach { name =>
        ParameterizedVerilogException.fail(
          "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
          s"component '${component.definitionName}' combines independently sourced declarations for parameter '$name'",
          associatedRoots.find(_._2.name == name).flatMap { case (baseType, _) =>
            sourceLocationOf(baseType)
          }
        )
      }
    values.distinct.sortBy(_.name).toVector
  }
}

final class ParameterizedVerilogException(
    val code: String,
    val detail: String,
    val sourceLocation: Option[String] = None
) extends IllegalArgumentException(
      s"[$code] ${Option(sourceLocation).flatten.map(_ + ": ").getOrElse("")}$detail"
    )

private[core] object ParameterizedVerilogException {
  def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    throw new ParameterizedVerilogException(
      code,
      detail,
      Option(sourceLocation).flatten
    )
}
