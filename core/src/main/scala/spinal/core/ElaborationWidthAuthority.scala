package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}
import scala.collection.mutable

/** Trusted, finite composition of packed-width expressions.
  *
  * The public operations accept already-authoritative typed expressions, never
  * text or a caller-provided evaluation table. Each result is certified by JVM
  * identity; copying its public case-class fields does not copy its authority.
  * Independent declaration roots are evaluated as a bounded Cartesian domain,
  * while repeated roots retain their exact correlation. This deliberately does
  * not extend the single-root contract of general elaboration integer APIs.
  */
object ElaborationWidthAuthority {
  private val Role = "symbolic width composition"
  private val Failure = "SPINAL-PARAMETERIZED-VERILOG-WIDTH-EXACT-DOMAIN-REQUIRED"

  private final case class Axis(
      domain: ElaborationExactDomain[_],
      values: Vector[BigInt]
  ) {
    def root: ElaborationIntegerParameterRoot = domain.root
    def parameter: ElaborationIntegerParameter = domain.parameter
  }

  private final case class Evidence(
      axes: Vector[Axis],
      values: Map[Vector[BigInt], BigInt]
  )

  private[core] final case class OwnerEvaluation(
      roots: Vector[ElaborationIntegerParameterRoot],
      rootValues: Vector[Vector[BigInt]],
      results: Map[Vector[BigInt], BigInt]
  )

  private final class Identity(
      value: ElaborationIntegerExpression,
      queue: ReferenceQueue[ElaborationIntegerExpression]
  ) extends WeakReference[ElaborationIntegerExpression](value, queue) {
    private val hash = System.identityHashCode(value)
    override def hashCode(): Int = hash
    override def equals(other: Any): Boolean = other match {
      case that: Identity =>
        (this eq that) || ((get ne null) && (get eq that.get))
      case _ => false
    }
  }

  private val queue = new ReferenceQueue[ElaborationIntegerExpression]()
  private val retained = mutable.HashMap.empty[Identity, Evidence]

  private def reap(): Unit = {
    var next = queue.poll()
    while (next != null) {
      retained.remove(next.asInstanceOf[Identity])
      next = queue.poll()
    }
  }

  private def evidenceOf(expression: ElaborationIntegerExpression): Option[Evidence] = synchronized {
    reap()
    retained.get(new Identity(expression, null))
  }

  private def retain(expression: ElaborationIntegerExpression, evidence: Evidence): Unit = synchronized {
    reap()
    retained.put(new Identity(expression, queue), evidence)
  }

  private[core] def isRetained(expression: ElaborationIntegerExpression): Boolean =
    expression != null && evidenceOf(expression).nonEmpty

  /** Recognize a typed certificate without treating legacy width summaries as
    * arithmetic authority. Recognized evidence is still validated at use.
    */
  def isAuthoritative(expression: ElaborationIntegerExpression): Boolean =
    expression != null && (isRetained(expression) ||
      (expression.exactDomain.nonEmpty && expression.hasExactAuthority) ||
      (expression.parameters.isEmpty && expression.generateIndex.isEmpty &&
        expression.parameterRoots.isEmpty && expression.exactDomain.isEmpty &&
        expression.verilog == expression.default.toString &&
        expression.minimum == expression.default && expression.maximum == expression.default))

  private[core] def hasCompleteDomain(expression: ElaborationIntegerExpression): Boolean =
    evidenceOf(expression) match {
      case Some(evidence) => evidence.axes.forall(axis => axis.values.toSet == axis.domain.universe)
      case None => expression.exactDomain.forall(_.hasCompleteCoverage)
    }

  private def fail(code: String, role: String, detail: String,
                   location: Option[String]): Nothing =
    ParameterizedVerilogException.fail(code, s"$role $detail", location)

  private def admitted(axis: Axis, role: String, code: String,
                       location: Option[String]): Vector[BigInt] = {
    val current = ElaborationDomainContext.admitted(axis.domain)
    if (current.isEmpty || !current.subsetOf(axis.values.toSet))
      fail(code, role,
        s"cannot use width evidence for '${axis.root.name}' outside its authorized branch domain",
        location.orElse(axis.root.sourceLocation))
    current.toVector.sorted
  }

  /** Validate authority and its branch scope without relaxing general integer
    * consumers. Positivity is checked separately at packed-width boundaries.
    */
  def requireAuthoritative(expression: ElaborationIntegerExpression,
                           role: String, failureCode: String): Unit = {
    ElabInt.validateExpression(expression, role)
    evidenceOf(expression) match {
      case None =>
        ElabInt.requireAuthoritativeIntegerDomain(expression, role, failureCode,
          requireExactExtrema = false)
      case Some(evidence) =>
        val roots = expression.completedParameterRoots
        if (expression.generateIndex.nonEmpty ||
            roots.size != evidence.axes.size ||
            expression.parameters.size != evidence.axes.size ||
            evidence.axes.exists { axis =>
              !roots.exists(_ eq axis.root) ||
                !expression.parameters.exists(_ eq axis.parameter) ||
                !axis.root.isAuthoritativeSchema(axis.parameter)
            })
          fail(failureCode, role, "lost its authoritative declaration identities",
            expression.sourceLocation)
        evidence.axes.foreach(admitted(_, role, failureCode, expression.sourceLocation))
    }
  }

  /** Recheck a certified width at its exact final native owner after capture.
    * This does not consult the now-finished construction branch. The caller
    * must resolve every requested declaration root through the existing exact
    * native-object ownership validator; no name or witness supplies ownership.
    */
  private[core] def ownerEvaluation(
      expression: ElaborationIntegerExpression,
      role: String,
      sourceLocation: Option[String]
  )(
      ownerValues: (ElaborationIntegerParameterRoot, Set[BigInt]) => Set[BigInt]
  ): Option[OwnerEvaluation] = {
    ElabInt.validateExpression(expression, role)
    evidenceOf(expression).map { evidence =>
      val axes = evidence.axes.map { axis =>
        val values = ownerValues(axis.root, axis.domain.universe)
        if (values.isEmpty || !values.subsetOf(axis.values.toSet))
          fail("SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-SCOPE-MISMATCH", role,
            s"width '${expression.verilog}' does not cover its exact owner domain for '${axis.root.name}'",
            sourceLocation.orElse(expression.sourceLocation))
        axis.copy(values = values.toVector.sorted)
      }
      val representative = axes.map { axis =>
        if (axis.values.contains(axis.parameter.default)) axis.parameter.default else axis.values.min
      }
      if (!evidence.values.get(representative).contains(expression.default))
        fail("SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-REPRESENTATIVE-MISMATCH", role,
          s"width '${expression.verilog}' default ${expression.default} does not match its exact owner's representative",
          sourceLocation.orElse(expression.sourceLocation))
      val results = keys(axes).map { key =>
        val result = evidence.values.getOrElse(key,
          fail("SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-INCOMPLETE", role,
            s"width '${expression.verilog}' is missing exact owner evidence", sourceLocation))
        if (result < expression.minimum || result > expression.maximum)
          fail("SPINAL-ELAB-DOMAIN-PROJECTION-BOUNDS-MISMATCH", role,
            s"width '${expression.verilog}' evaluates to $result outside its retained interval", sourceLocation)
        key -> result
      }.toMap
      OwnerEvaluation(axes.map(_.root), axes.map(_.values), results)
    }
  }

  private def source(expression: ElaborationIntegerExpression): Evidence = {
    requireAuthoritative(expression, Role, Failure)
    evidenceOf(expression) match {
      case Some(evidence) => active(evidence, expression.sourceLocation)
      case None => expression.exactDomain match {
        case None => Evidence(Vector.empty, Map(Vector.empty[BigInt] -> expression.default))
        case Some(domain) =>
          val values = ElaborationDomainContext.requireEvidence(domain, Role, expression.sourceLocation)
            .toVector.sorted
          Evidence(Vector(Axis(domain, values)), values.map { value =>
            Vector(value) -> domain.evaluate(value).get
          }.toMap)
      }
    }
  }

  private def predicate(expression: ElaborationBooleanExpression): Evidence = {
    ElabInt.requireAuthoritativeBooleanDomain(expression, Role, Failure)
    expression.exactDomain match {
      case None => Evidence(Vector.empty,
        Map(Vector.empty[BigInt] -> (if (expression.default) BigInt(1) else BigInt(0))))
      case Some(domain) =>
        val values = ElaborationDomainContext.requireEvidence(domain, Role, expression.sourceLocation)
          .toVector.sorted
        Evidence(Vector(Axis(domain, values)), values.map { value =>
          Vector(value) -> (if (domain.evaluate(value).get) BigInt(1) else BigInt(0))
        }.toMap)
    }
  }

  private def active(evidence: Evidence, location: Option[String]): Evidence = {
    val axes = evidence.axes.map(axis =>
      axis.copy(values = admitted(axis, Role, Failure, location)))
    if (axes == evidence.axes) evidence
    else Evidence(axes, evidence.values.filter { case (key, _) =>
      axes.indices.forall(index => axes(index).values.contains(key(index)))
    })
  }

  private def merge(sources: Vector[Evidence], location: Option[String]): Vector[Axis] = {
    val axes = sources.flatMap(_.axes).foldLeft(Vector.empty[Axis]) { (known, axis) =>
      known.find(_.root.name == axis.root.name) match {
        case Some(previous) if (previous.root ne axis.root) ||
            (previous.parameter ne axis.parameter) =>
          fail("SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED", Role,
            s"combines independently sourced declarations for parameter '${axis.root.name}'", location)
        case Some(previous) =>
          if (previous.values != axis.values)
            fail(Failure, Role, s"has incompatible branch evidence for '${axis.root.name}'", location)
          known
        case None => known :+ axis
      }
    }.sortBy(_.root.name)
    val size = axes.foldLeft(BigInt(1))((count, axis) => count * axis.values.size)
    if (size > ElaborationExactDomain.MaximumDomainSize)
      fail("SPINAL-ELAB-WIDTH-DOMAIN-TOO-LARGE", Role,
        s"Cartesian domain has $size combinations, above the exhaustive limit ${ElaborationExactDomain.MaximumDomainSize}",
        location)
    axes
  }

  private def keys(axes: Vector[Axis]): Vector[Vector[BigInt]] =
    axes.foldLeft(Vector(Vector.empty[BigInt])) { (prefixes, axis) =>
      prefixes.flatMap(prefix => axis.values.map(prefix :+ _))
    }

  private def valueAt(evidence: Evidence, axes: Vector[Axis], key: Vector[BigInt]): BigInt =
    evidence.values(evidence.axes.map(axis => key(axes.indexWhere(_.root eq axis.root))))

  private def publish(verilog: String, evidence: Evidence,
                      location: Option[String]): ElaborationIntegerExpression = {
    val results = evidence.values.values.toVector
    if (results.isEmpty || results.exists(value => !value.isValidInt))
      fail("SPINAL-ELAB-WIDTH-RESULT-OUT-OF-RANGE", Role,
        "must evaluate to a finite Scala Int throughout its authorized domain", location)
    val representative = evidence.axes.map { axis =>
      if (axis.values.contains(axis.parameter.default)) axis.parameter.default else axis.values.min
    }
    val default = evidence.values(representative)
    if (evidence.axes.isEmpty)
      ElaborationIntegerExpression(default.toString, default, default, default, Vector.empty,
        sourceLocation = location)
    else {
      val expression = ElaborationIntegerExpression(
        verilog, default, results.min, results.max,
        evidence.axes.map(_.parameter), sourceLocation = location,
        parameterRoots = evidence.axes.map(_.root))
      // Retain ordinary exact evidence for one root so existing single-root
      // consumers continue to work. Multiple roots remain width-specific.
      val certified = evidence.axes match {
        case Vector(axis) =>
          val domain = ElabInt.checkedDerivedDomain(axis.domain,
            axis.values.map(value => value -> evidence.values(Vector(value))), location, Role)
          expression.copy(exactDomain = Some(domain)).attachProjection(
            domain, axis.values.toSet, representative.head, Role, location)
        case _ => expression
      }
      retain(certified, evidence)
      certified
    }
  }

  private def binary(left: ElaborationIntegerExpression,
                     right: ElaborationIntegerExpression,
                     render: (String, String) => String,
                     evaluate: (BigInt, BigInt) => BigInt): ElaborationIntegerExpression = {
    val location = left.sourceLocation.orElse(right.sourceLocation)
    val l = source(left)
    val r = source(right)
    val axes = merge(Vector(l, r), location)
    val values = keys(axes).map(key =>
      key -> evaluate(valueAt(l, axes, key), valueAt(r, axes, key))).toMap
    publish(render(left.verilog, right.verilog), Evidence(axes, values), location)
  }

  def add(left: ElaborationIntegerExpression, right: ElaborationIntegerExpression): ElaborationIntegerExpression =
    if (equivalent(left, right)) binary(left, right, (l, _) => s"(2 * $l)", _ + _)
    else binary(left, right, (l, r) => s"($l + $r)", _ + _)

  /** Preserve the native multiplication/concatenation width transfer's operand
    * addition spelling while certifying it through the same exact-domain path.
    * General width composition may still factor repeated operands above.
    */
  private[core] def addNative(left: ElaborationIntegerExpression,
                              right: ElaborationIntegerExpression): ElaborationIntegerExpression =
    binary(left, right, (l, r) => s"($l + $r)", _ + _)

  def subtract(left: ElaborationIntegerExpression, right: ElaborationIntegerExpression): ElaborationIntegerExpression =
    binary(left, right, (l, r) => s"($l - $r)", _ - _)
  def multiply(left: ElaborationIntegerExpression, right: ElaborationIntegerExpression): ElaborationIntegerExpression =
    binary(left, right, (l, r) => s"($l * $r)", _ * _)
  def maximum(left: ElaborationIntegerExpression, right: ElaborationIntegerExpression): ElaborationIntegerExpression =
    extremum(left, right, maximum = true)
  def minimum(left: ElaborationIntegerExpression, right: ElaborationIntegerExpression): ElaborationIntegerExpression =
    extremum(left, right, maximum = false)

  private def extremum(left: ElaborationIntegerExpression,
                        right: ElaborationIntegerExpression,
                        maximum: Boolean): ElaborationIntegerExpression = {
    val l = source(left)
    val r = source(right)
    val location = left.sourceLocation.orElse(right.sourceLocation)
    // Validate all declaration identities before eliminating a dominated arm.
    val axes = merge(Vector(l, r), location)
    val evaluated = keys(axes).map(key =>
      (key, valueAt(l, axes, key), valueAt(r, axes, key)))
    def dominates(a: BigInt, b: BigInt): Boolean = if (maximum) a >= b else a <= b
    if (evaluated.forall { case (_, a, b) => dominates(a, b) }) project(left, Role)
    else if (evaluated.forall { case (_, a, b) => dominates(b, a) }) project(right, Role)
    else {
      val results = evaluated.map { case (key, a, b) =>
        key -> (if (maximum) a.max(b) else a.min(b))
      }.toMap
      val comparison = if (maximum) ">" else "<"
      publish(s"((${left.verilog} $comparison ${right.verilog}) ? ${left.verilog} : ${right.verilog})",
        Evidence(axes, results), location)
    }
  }

  def choose(condition: ElaborationBooleanExpression,
             whenTrue: ElaborationIntegerExpression,
             whenFalse: ElaborationIntegerExpression): ElaborationIntegerExpression = {
    val location = condition.sourceLocation.orElse(whenTrue.sourceLocation).orElse(whenFalse.sourceLocation)
    val predicateEvidence = predicate(condition)
    if (equivalent(whenTrue, whenFalse)) return project(whenTrue, Role)
    val yes = source(whenTrue)
    val no = source(whenFalse)
    val axes = merge(Vector(predicateEvidence, yes, no), location)
    val values = keys(axes).map { key =>
      key -> (if (valueAt(predicateEvidence, axes, key) != 0) valueAt(yes, axes, key)
              else valueAt(no, axes, key))
    }.toMap
    if (values.forall { case (key, value) => value == valueAt(yes, axes, key) })
      return project(whenTrue, Role)
    if (values.forall { case (key, value) => value == valueAt(no, axes, key) })
      return project(whenFalse, Role)
    publish(s"(${condition.verilog} ? ${whenTrue.verilog} : ${whenFalse.verilog})",
      Evidence(axes, values), location)
  }

  def add(left: ElabInt, right: ElabInt): ElabInt = ElabInt.fromExpression(add(left.expression, right.expression))
  def subtract(left: ElabInt, right: ElabInt): ElabInt = ElabInt.fromExpression(subtract(left.expression, right.expression))
  def multiply(left: ElabInt, right: ElabInt): ElabInt = ElabInt.fromExpression(multiply(left.expression, right.expression))
  def maximum(left: ElabInt, right: ElabInt): ElabInt = ElabInt.fromExpression(maximum(left.expression, right.expression))
  def minimum(left: ElabInt, right: ElabInt): ElabInt = ElabInt.fromExpression(minimum(left.expression, right.expression))
  def choose(condition: ElabBool, whenTrue: ElabInt, whenFalse: ElabInt): ElabInt =
    ElabInt.fromExpression(choose(condition.expression, whenTrue.expression, whenFalse.expression))

  /** Narrow an existing width certificate to the currently captured branches. */
  private[core] def project(expression: ElaborationIntegerExpression,
                            role: String): ElaborationIntegerExpression = {
    requireAuthoritative(expression, role, Failure)
    evidenceOf(expression) match {
      case None => ElabInt.projectExpression(expression, role)
      case Some(evidence) =>
        val projected = active(evidence, expression.sourceLocation)
        if (projected == evidence) expression
        else publish(expression.verilog, projected, expression.sourceLocation)
    }
  }

  /** Exhaustive value-function equality with exact declaration identities. */
  def equivalent(left: ElaborationIntegerExpression,
                 right: ElaborationIntegerExpression): Boolean = {
    val l = source(left)
    val r = source(right)
    if (l.axes.size != r.axes.size || l.axes.exists(axis =>
        !r.axes.exists(other => (other.root eq axis.root) &&
          (other.parameter eq axis.parameter) && other.values == axis.values))) false
    else {
      val axes = merge(Vector(l, r), left.sourceLocation.orElse(right.sourceLocation))
      keys(axes).forall(key => valueAt(l, axes, key) == valueAt(r, axes, key))
    }
  }

  /** A relation over all combinations of the exact participating roots. */
  private[core] def provesRelation(left: ElaborationIntegerExpression,
                                   right: ElaborationIntegerExpression)(
      relation: (BigInt, BigInt) => Boolean): Boolean = {
    val l = source(left)
    val r = source(right)
    val axes = merge(Vector(l, r), left.sourceLocation.orElse(right.sourceLocation))
    keys(axes).forall(key => relation(valueAt(l, axes, key), valueAt(r, axes, key)))
  }

  /** Minimum width on a typed active domain, without evaluating a witness-only
    * branch or manufacturing evidence for excluded points.
    */
  def minimumWhen(width: ElaborationIntegerExpression,
                  condition: ElaborationBooleanExpression): Option[BigInt] = {
    val value = source(width)
    val guard = predicate(condition)
    val axes = merge(Vector(value, guard), width.sourceLocation.orElse(condition.sourceLocation))
    val results = keys(axes).collect {
      case key if valueAt(guard, axes, key) != 0 => valueAt(value, axes, key)
    }
    if (results.isEmpty) None else Some(results.min)
  }

  def maximumWhen(width: ElaborationIntegerExpression,
                  condition: ElaborationBooleanExpression): Option[BigInt] = {
    val value = source(width)
    val guard = predicate(condition)
    val axes = merge(Vector(value, guard), width.sourceLocation.orElse(condition.sourceLocation))
    val results = keys(axes).collect {
      case key if valueAt(guard, axes, key) != 0 => valueAt(value, axes, key)
    }
    if (results.isEmpty) None else Some(results.max)
  }

  /** Exact evaluation by declaration identity, for backend width proofs. */
  def evaluate(expression: ElaborationIntegerExpression,
               bindings: Vector[(ElaborationIntegerParameterRoot, BigInt)]): Option[BigInt] = {
    val evidence = source(expression)
    val key = evidence.axes.map(axis => bindings.find(_._1 eq axis.root).map(_._2))
    if (key.exists(_.isEmpty)) None else evidence.values.get(key.map(_.get))
  }
}
