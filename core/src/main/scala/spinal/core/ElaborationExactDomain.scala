package spinal.core

/**
  * Exact, bounded evaluation evidence for one typed elaboration expression.
  *
  * The root is compared by JVM identity.  Rendered names, equal schemas and
  * equal witnesses never establish correlation between declarations.
  */
private[spinal] final case class ElaborationExactDomain[A](
    root: ElaborationIntegerParameterRoot,
    parameter: ElaborationIntegerParameter,
    evaluations: Vector[(BigInt, A)]
) {
  require(root ne null, "exact elaboration domain root must not be null")
  require(parameter ne null, "exact elaboration domain parameter must not be null")
  require(evaluations ne null, "exact elaboration domain evaluations must not be null")

  private[core] lazy val byRootValue: Map[BigInt, A] = evaluations.toMap
  private[core] lazy val evidenceValues: Set[BigInt] = byRootValue.keySet
  private[core] lazy val universe: Set[BigInt] =
    ElaborationExactDomain
      .boundedValues(parameter.minimum, parameter.maximum)
      .toSet

  private[spinal] def evaluate(rootValue: BigInt): Option[A] =
    byRootValue.get(rootValue)
}

private[spinal] object ElaborationExactDomain {
  /** Shared fail-closed cap for exhaustive typed structural evidence. */
  val MaximumDomainSize: BigInt = BigInt(65536)

  def checked[A](
      root: ElaborationIntegerParameterRoot,
      parameter: ElaborationIntegerParameter,
      evaluations: Vector[(BigInt, A)],
      sourceLocation: Option[String],
      role: String
  ): ElaborationExactDomain[A] =
    checkedCoverage(
      root,
      parameter,
      evaluations,
      sourceLocation,
      role,
      requireComplete = true
    )

  /**
    * Retain evidence which is defined only in the currently admitted branch.
    * Projection rejects this carrier if it is later observed from a wider
    * domain, so excluded invalid points can never be treated as evaluations.
    */
  private[core] def checkedPartial[A](
      root: ElaborationIntegerParameterRoot,
      parameter: ElaborationIntegerParameter,
      evaluations: Vector[(BigInt, A)],
      sourceLocation: Option[String],
      role: String
  ): ElaborationExactDomain[A] =
    checkedCoverage(
      root,
      parameter,
      evaluations,
      sourceLocation,
      role,
      requireComplete = false
    )

  private def checkedCoverage[A](
      root: ElaborationIntegerParameterRoot,
      parameter: ElaborationIntegerParameter,
      evaluations: Vector[(BigInt, A)],
      sourceLocation: Option[String],
      role: String,
      requireComplete: Boolean
  ): ElaborationExactDomain[A] = {
    if (root == null || parameter == null || evaluations == null) {
      fail(
        "SPINAL-ELAB-DOMAIN-EVIDENCE-NULL",
        s"$role must retain a non-null root, parameter and evaluation table",
        sourceLocation
      )
    }
    if (
      parameter.minimum == null || parameter.maximum == null ||
      parameter.default == null
    ) {
      fail(
        "SPINAL-ELAB-DOMAIN-EVIDENCE-NULL",
        s"$role parameter '${parameter.name}' must retain non-null bounds and default",
        sourceLocation
      )
    }
    val expected = boundedValues(
      parameter.minimum,
      parameter.maximum,
      sourceLocation,
      role
    )
    if (evaluations.exists(_ == null)) {
      fail(
        "SPINAL-ELAB-DOMAIN-EVIDENCE-NULL",
        s"$role contains a null evaluation entry",
        sourceLocation
      )
    }
    if (root.name != parameter.name) {
      fail(
        "SPINAL-ELAB-DOMAIN-ROOT-SCHEMA-MISMATCH",
        s"$role root '${root.name}' does not match parameter '${parameter.name}'",
        sourceLocation.orElse(root.sourceLocation)
      )
    }
    evaluations.zipWithIndex.foreach { case ((rootValue, result), index) =>
      if (rootValue == null) {
        fail(
          "SPINAL-ELAB-DOMAIN-EVIDENCE-ROOT-VALUE-NULL",
          s"$role contains a null root value at evaluation index $index",
          sourceLocation
        )
      }
      if (result == null) {
        fail(
          "SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-NULL",
          s"$role contains a null result at root value $rootValue",
          sourceLocation
        )
      }
    }
    val keys = evaluations.map(_._1)
    if (keys.distinct.size != keys.size) {
      fail(
        "SPINAL-ELAB-DOMAIN-EVIDENCE-DUPLICATE",
        s"$role contains duplicate root values",
        sourceLocation
      )
    }
    val unexpected = keys.toSet -- expected.toSet
    if (unexpected.nonEmpty) {
      fail(
        "SPINAL-ELAB-DOMAIN-EVIDENCE-OUTSIDE-UNIVERSE",
        s"$role contains root values outside [${parameter.minimum}, ${parameter.maximum}]: ${unexpected.toVector.sorted.mkString(", ")}",
        sourceLocation
      )
    }
    if (requireComplete && keys.toSet != expected.toSet) {
      fail(
        "SPINAL-ELAB-DOMAIN-EVIDENCE-INCOMPLETE",
        s"$role must evaluate every value in [${parameter.minimum}, ${parameter.maximum}] exactly once",
        sourceLocation
      )
    }
    if (!requireComplete && keys.isEmpty) {
      fail(
        "SPINAL-ELAB-DOMAIN-EVIDENCE-EMPTY",
        s"$role must evaluate at least one value in its active branch",
        sourceLocation
      )
    }
    ElaborationExactDomain(
      root,
      parameter,
      evaluations.sortBy(_._1)
    )
  }

  def direct(
      root: ElaborationIntegerParameterRoot,
      parameter: ElaborationIntegerParameter,
      sourceLocation: Option[String],
      role: String
  ): ElaborationExactDomain[BigInt] = {
    val values = boundedValues(
      parameter.minimum,
      parameter.maximum,
      sourceLocation,
      role
    )
    checked(
      root,
      parameter,
      values.map(value => value -> value),
      sourceLocation,
      role
    )
  }

  private[core] def boundedValues(
      minimum: BigInt,
      maximum: BigInt
  ): Vector[BigInt] =
    boundedValues(minimum, maximum, None, "exact elaboration domain")

  private def boundedValues(
      minimum: BigInt,
      maximum: BigInt,
      sourceLocation: Option[String],
      role: String
  ): Vector[BigInt] = {
    if (minimum == null || maximum == null) {
      fail(
        "SPINAL-ELAB-DOMAIN-EVIDENCE-NULL",
        s"$role must retain non-null domain bounds",
        sourceLocation
      )
    }
    val size = maximum - minimum + 1
    if (size < 1 || size > MaximumDomainSize) {
      fail(
        "SPINAL-ELAB-DOMAIN-SIZE-UNSUPPORTED",
        s"$role domain [$minimum, $maximum] has size $size, above the exhaustive limit $MaximumDomainSize",
        sourceLocation
      )
    }
    val builder = Vector.newBuilder[BigInt]
    var value = minimum
    while (value <= maximum) {
      builder += value
      value += 1
    }
    builder.result()
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}

/** Branch-local construction projection for exact typed elaboration values. */
private[spinal] object ElaborationDomainContext {
  private final case class Constraint(
      root: ElaborationIntegerParameterRoot,
      admitted: Set[BigInt]
  )

  private val active = new ThreadLocal[List[Constraint]]()

  def withAdmitted[T](
      root: ElaborationIntegerParameterRoot,
      values: Set[BigInt],
      sourceLocation: Option[String]
  )(body: => T): T = {
    if (root == null || values == null || values.isEmpty) {
      fail(
        "SPINAL-ELAB-DOMAIN-BRANCH-EMPTY",
        "typed branch capture requires one non-null root and a non-empty admitted value set",
        sourceLocation
      )
    }
    val previous = Option(active.get()).getOrElse(Nil)
    val inherited = previous.collect {
      case constraint if constraint.root eq root => constraint.admitted
    }
    val narrowed = inherited.foldLeft(values)(_ intersect _)
    if (narrowed.isEmpty) {
      fail(
        "SPINAL-ELAB-DOMAIN-BRANCH-EMPTY",
        s"typed branch capture for '${root.name}' has an empty nested domain",
        sourceLocation.orElse(root.sourceLocation)
      )
    }
    active.set(Constraint(root, narrowed) :: previous)
    try body
    finally {
      if (previous.isEmpty) active.remove()
      else active.set(previous)
    }
  }

  def admitted[A](domain: ElaborationExactDomain[A]): Set[BigInt] = {
    val current = Option(active.get()).getOrElse(Nil).collect {
      case constraint if constraint.root eq domain.root => constraint.admitted
    }
    current.foldLeft(domain.universe)(_ intersect _)
  }

  /**
    * Require every root value admitted at the observation site to have an
    * evaluation. Partial evidence is legal only while its originating narrowed
    * branch remains active.
    */
  def requireEvidence[A](
      domain: ElaborationExactDomain[A],
      role: String,
      sourceLocation: Option[String]
  ): Set[BigInt] = {
    val values = admitted(domain)
    val missing = values -- domain.evidenceValues
    if (missing.nonEmpty) {
      fail(
        "SPINAL-ELAB-DOMAIN-EVIDENCE-SCOPE-MISMATCH",
        s"$role for '${domain.parameter.name}' is defined only for root values ${domain.evidenceValues.toVector.sorted.mkString(", ")}, but the active domain also admits ${missing.toVector.sorted.mkString(", ")}",
        sourceLocation.orElse(domain.root.sourceLocation)
      )
    }
    values
  }

  def representative[A](domain: ElaborationExactDomain[A]): BigInt = {
    val values = requireEvidence(
      domain,
      "typed expression evidence",
      domain.root.sourceLocation
    )
    if (values.isEmpty) {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-EMPTY",
        s"typed expression for '${domain.parameter.name}' has no value in the active branch",
        domain.root.sourceLocation
      )
    }
    if (values.contains(domain.parameter.default)) domain.parameter.default
    else values.min
  }

  def constrains(root: ElaborationIntegerParameterRoot): Boolean =
    Option(active.get())
      .getOrElse(Nil)
      .exists(constraint => constraint.root eq root)

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
