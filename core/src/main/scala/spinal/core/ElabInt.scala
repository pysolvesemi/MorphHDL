package spinal.core

/** A bounded elaboration-time integer that retains both one concrete witness
  * and its exact parameter expression.
  *
  * `ElabInt` is deliberately not convertible to Scala `Int`. Native APIs that
  * support parameters accept it explicitly and may use [[witness]] only at the
  * reviewed concrete SpinalHDL construction boundary.
  */
final class ElabInt private[core] (
    private[core] val expression: ElaborationIntegerExpression
) {
  ElabInt.validateExpression(expression, "ElabInt")

  private[spinal] def witness: Int = projectedExpression("ElabInt witness").default.toInt
  def minimum: BigInt = projectedExpression("ElabInt minimum").minimum
  def maximum: BigInt = projectedExpression("ElabInt maximum").maximum
  def parameters: Vector[ElaborationIntegerParameter] = expression.parameters
  def sourceLocation: Option[String] = expression.sourceLocation
  def isConcrete: Boolean = expression.parameters.isEmpty
  def isDomainConstant: Boolean = minimum == maximum

  /** Prove this exact definition-side expression before a native library
    * adapter is allowed to consume its retained bounds.  The object method
    * deliberately observes the unprojected carrier; callers which subsequently
    * project it must still validate that narrower expression at its use site.
    */
  private[spinal] def requireAuthoritativeIntegerDomain(
      role: String,
      failureCode: String,
      requireExactExtrema: Boolean
  ): Option[ElaborationExactDomain[BigInt]] =
    ElabInt.requireAuthoritativeIntegerDomain(
      expression,
      role,
      failureCode,
      requireExactExtrema
    )

  /** Validate the untouched definition-side carrier before projection can
    * normalize its witness or interval, then validate the exact projected
    * carrier under the consumer's existing extrema policy.  This ordering is
    * what prevents malformed public summaries from laundering themselves
    * through an otherwise legitimate branch projection.
    */
  private[spinal] def authoritativeProjectedExpression(
      role: String,
      failureCode: String,
      requireProjectedExactExtrema: Boolean
  ): ElaborationIntegerExpression = {
    val symbolic = expression.parameters.nonEmpty
    if (symbolic)
      requireAuthoritativeIntegerDomain(
        role,
        failureCode,
        requireExactExtrema = false
      )
    val projected = projectedExpression(role)
    if (symbolic)
      ElabInt.requireAuthoritativeIntegerDomain(
        projected,
        role,
        failureCode,
        requireProjectedExactExtrema
      )
    projected
  }

  /** Expression projected only for the currently captured structural branch. */
  private[spinal] def projectedExpression(
      role: String
  ): ElaborationIntegerExpression =
    ElabInt.projectExpression(expression, role)

  def +(that: ElabInt): ElabInt = ElabInt.add(this, that)
  def +(that: Int): ElabInt = this + ElabInt.literal(that)
  def -(that: ElabInt): ElabInt = ElabInt.subtract(this, that)
  def -(that: Int): ElabInt = this - ElabInt.literal(that)
  def *(that: ElabInt): ElabInt = ElabInt.multiply(this, that)
  def *(that: Int): ElabInt = this * ElabInt.literal(that)
  def /(that: ElabInt): ElabInt = ElabInt.divide(this, that)
  def /(that: Int): ElabInt = this / ElabInt.literal(that)
  def %(that: ElabInt): ElabInt = ElabInt.modulo(this, that)
  def %(that: Int): ElabInt = this % ElabInt.literal(that)

  /** Typed ceiling logarithm which retains this bounded expression. */
  def log2Up: ElabInt = ElabInt.log2UpValue(this)

  /** Minimum positive packed width which can address every value below this
    * positive bound. Unlike [[log2Up]], a bound of one has width one.
    */
  def addressWidth: ElabInt = ElabInt.addressWidthValue(this)

  /** Typed power-of-two predicate over this bounded expression. */
  def isPow2: ElabBool = ElabInt.isPow2Value(this)

  /** Typed integer value `2 ^ this`. */
  def pow2: ElabInt = ElabInt.pow2Value(this)

  def <(that: ElabInt): ElabBool = ElabInt.compare("<", this, that)
  def <(that: Int): ElabBool = this < ElabInt.literal(that)
  def <=(that: ElabInt): ElabBool = ElabInt.compare("<=", this, that)
  def <=(that: Int): ElabBool = this <= ElabInt.literal(that)
  def >(that: ElabInt): ElabBool = ElabInt.compare(">", this, that)
  def >(that: Int): ElabBool = this > ElabInt.literal(that)
  def >=(that: ElabInt): ElabBool = ElabInt.compare(">=", this, that)
  def >=(that: Int): ElabBool = this >= ElabInt.literal(that)

  /** Typed equality used by the pre-typer natural-syntax bridge. */
  def elabEq(that: ElabInt): ElabBool = ElabInt.equal(this, that)
  def elabEq(that: Int): ElabBool = elabEq(ElabInt.literal(that))

  /** Typed inequality used by the pre-typer natural-syntax bridge. */
  def elabNe(that: ElabInt): ElabBool = !elabEq(that)
  def elabNe(that: Int): ElabBool = !elabEq(that)

  /** SpinalHDL packed-width marker which retains this expression. */
  def bit: ParameterizedBitCount = toParameterizedBitCount("bit width")
  def bits: ParameterizedBitCount = toParameterizedBitCount("bit width")

  /** Constant-only slice count required by the current native adapters. */
  def slices: SlicesCount = new SlicesCount(constantInt("slice count"))

  /** Finite witness range used by native helpers which inspect every element.
    * The exact symbolic bound stays on this carrier; callers must not retain
    * the returned Range as a replacement parameter expression.
    */
  private[spinal] def finiteRangeFromZero(role: String): Range = {
    val projected = projectedExpression(role)
    if (projected.minimum < 0 || projected.maximum > BigInt(Int.MaxValue)) {
      ElabInt.fail(
        "SPINAL-ELAB-INT-RANGE-DOMAIN-INVALID",
        s"$role expression '${expression.verilog}' must remain in the finite non-negative Int domain, but reaches [${projected.minimum}, ${projected.maximum}]",
        expression.sourceLocation
      )
    }
    if (projected.minimum != projected.maximum) {
      ElabInt.fail(
        "SPINAL-ELAB-INT-RANGE-DOMAIN-NOT-CONSTANT",
        s"$role expression '${expression.verilog}' varies over [${projected.minimum}, ${projected.maximum}] in the active branch and cannot be witness-unrolled",
        expression.sourceLocation
      )
    }
    0 until projected.default.toInt
  }

  private[spinal] def constantInt(role: String): Int = {
    val projected = projectedExpression(role)
    if (!isDomainConstant) {
      ElabInt.fail(
        "SPINAL-ELAB-INT-DOMAIN-NOT-CONSTANT",
        s"$role expression '${expression.verilog}' varies over [${projected.minimum}, ${projected.maximum}]",
        expression.sourceLocation
      )
    }
    if (!projected.default.isValidInt) {
      ElabInt.fail(
        "SPINAL-ELAB-INT-WITNESS-OUT-OF-RANGE",
        s"$role witness ${projected.default} does not fit Scala Int",
        expression.sourceLocation
      )
    }
    projected.default.toInt
  }

  private[spinal] def constantBigInt(role: String): BigInt = {
    val projected = projectedExpression(role)
    constantInt(role)
    projected.default
  }

  private[spinal] def toParameterizedBitCount(
      role: String
  ): ParameterizedBitCount = {
    ElaborationWidthAuthority.requireAuthoritative(
      expression, role, "SPINAL-PARAMETERIZED-VERILOG-WIDTH-EXACT-DOMAIN-REQUIRED"
    )
    val projected = ElaborationWidthAuthority.project(expression, role)
    if (projected.minimum < 1 || projected.maximum < projected.minimum) {
      ElabInt.fail(
        "SPINAL-ELAB-INT-WIDTH-DOMAIN-INVALID",
        s"$role expression '${expression.verilog}' must remain positive, but reaches [${projected.minimum}, ${projected.maximum}]",
        expression.sourceLocation
      )
    }
    if (projected.maximum > BigInt(Int.MaxValue)) {
      ElabInt.fail(
        "SPINAL-ELAB-INT-WIDTH-DOMAIN-TOO-LARGE",
        s"$role expression '${expression.verilog}' exceeds the Scala Int width domain",
        expression.sourceLocation
      )
    }
    val direct = projected.parameters match {
      case Vector(parameter)
          if projected.exactDomain.exists(domain =>
            !ElaborationDomainContext.constrains(domain.root) &&
              projected.verilog == parameter.name &&
              projected.generateIndex.isEmpty &&
              domain.parameter == parameter &&
              (projected.completedParameterRoots match {
                case Vector(root) => root eq domain.root
                case _            => false
              }) &&
              domain.evaluations.forall { case (rootValue, result) =>
                rootValue == result
              }
          ) =>
        Some(parameter)
      case _ => None
    }
    ParameterizedBitCount(
      value = projected.default.toInt,
      parameter = direct,
      sourceLocation = expression.sourceLocation,
      expression = if (projected.parameters.nonEmpty) Some(projected) else None
    )
  }

  override def toString: String =
    s"ElabInt(${expression.verilog}, witness=${expression.default})"
}

/** A typed Boolean predicate over one or more bounded elaboration integers. */
final class ElabBool private[core] (
    private[core] val expression: ElaborationBooleanExpression,
    private[core] val truth: ElabBool.Truth
) {
  private[spinal] def witness: Boolean =
    projectedExpression("ElabBool witness").default
  def parameters: Vector[ElaborationIntegerParameter] = expression.parameters
  def sourceLocation: Option[String] = expression.sourceLocation
  def isAlwaysTrue: Boolean = ElabBool.projectedTruth(this) == ElabBool.AlwaysTrue
  def isAlwaysFalse: Boolean = ElabBool.projectedTruth(this) == ElabBool.AlwaysFalse
  def isSymbolic: Boolean = ElabBool.projectedTruth(this) == ElabBool.Unknown

  private[spinal] def projectedExpression(
      role: String
  ): ElaborationBooleanExpression =
    ElabBool.projectExpression(expression, role)

  def unary_! : ElabBool = ElabBool.not(this)
  def &&(that: ElabBool): ElabBool = ElabBool.and(this, that)
  def &&(that: Boolean): ElabBool = this && ElabBool.literal(that)
  def ||(that: ElabBool): ElabBool = ElabBool.or(this, that)
  def ||(that: Boolean): ElabBool = this || ElabBool.literal(that)

  /** Retain this predicate as the integer expression 0 or 1. */
  def toElabInt: ElabInt = ElabBool.toElabInt(this)

  override def toString: String =
    s"ElabBool(${expression.verilog}, witness=${expression.default})"
}

object ElabBool {
  private[core] sealed trait Truth
  private[core] case object AlwaysTrue extends Truth
  private[core] case object AlwaysFalse extends Truth
  private[core] case object Unknown extends Truth

  def literal(value: Boolean): ElabBool =
    new ElabBool(
      ElaborationBooleanExpression(
        verilog = if (value) "1'b1" else "1'b0",
        default = value,
        parameters = Vector.empty
      ),
      if (value) AlwaysTrue else AlwaysFalse
    )

  private[core] def apply(
      expression: ElaborationBooleanExpression,
      truth: Truth
  ): ElabBool = {
    if (expression == null)
      throw new IllegalArgumentException("ElabBool expression must not be null")
    val certified =
      ElabInt.attachDerivedExactAuthority(expression, "ElabBool expression")
    ElabInt.validateExpression(certified, "ElabBool expression")
    val normalized = ElabInt.withCompleteParameterRoots(certified)
    ElabInt.validateExpression(normalized, "ElabBool expression")
    new ElabBool(normalized, truth)
  }

  private[core] def derived(
      expression: ElaborationBooleanExpression,
      truth: Truth,
      role: String
  ): ElabBool =
    apply(
      ElabInt.authorizeDerivedProjection(
        ElabInt.attachDerivedExactAuthority(expression, role),
        role
      ),
      truth
    )

  private[spinal] def projectExpression(
      expression: ElaborationBooleanExpression,
      role: String
  ): ElaborationBooleanExpression = {
    ElabInt.requireAuthoritativeBooleanDomain(
      expression,
      role,
      "SPINAL-ELAB-BOOL-EXACT-DOMAIN-REQUIRED"
    )
    expression.exactDomain match {
      case Some(domain) =>
        val requested = ElaborationDomainContext.admitted(domain)
        ElabInt.requireProjectionSubset(
          expression.projectionProvenance,
          domain,
          requested,
          role,
          expression.sourceLocation
        )
        val admitted = ElaborationDomainContext.requireEvidence(
          domain,
          role,
          expression.sourceLocation
        )
        if (admitted.isEmpty) {
          ElabInt.fail(
            "SPINAL-ELAB-DOMAIN-PROJECTION-EMPTY",
            s"$role predicate '${expression.verilog}' has no value in the active branch",
            expression.sourceLocation
          )
        }
        val representative = ElaborationDomainContext.representative(domain)
        val projectedDefault = domain.evaluate(representative).getOrElse {
          ElabInt.fail(
            "SPINAL-ELAB-DOMAIN-EVIDENCE-INCOMPLETE",
            s"$role predicate '${expression.verilog}' has no evaluation at $representative",
            expression.sourceLocation
          )
        }
        val projectedDomain = ElaborationExactDomain.checkedPartial(
          domain.root,
          domain.parameter,
          domain.evaluations.filter { case (rootValue, _) =>
            admitted.contains(rootValue)
          },
          expression.sourceLocation,
          role
        )
        expression
          .copy(default = projectedDefault, exactDomain = Some(projectedDomain))
          .attachProjection(
            projectedDomain,
            admitted,
            representative,
            role,
            expression.sourceLocation
          )
      case None =>
        expression.parameterRoots.find(ElaborationDomainContext.constrains).foreach { root =>
          ElabInt.fail(
            "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING",
            s"$role predicate '${expression.verilog}' lacks exact evidence for active root '${root.name}'",
            expression.sourceLocation.orElse(root.sourceLocation)
          )
        }
        expression
    }
  }

  private[core] def projectedTruth(value: ElabBool): Truth = {
    ElabInt.requireAuthoritativeBooleanDomain(
      value.expression,
      "typed Boolean truth projection",
      "SPINAL-ELAB-BOOL-EXACT-DOMAIN-REQUIRED"
    )
    value.expression.exactDomain match {
      case Some(domain) =>
        val requested = ElaborationDomainContext.admitted(domain)
        ElabInt.requireProjectionSubset(
          value.expression.projectionProvenance,
          domain,
          requested,
          "typed Boolean truth projection",
          value.sourceLocation
        )
        val admitted = ElaborationDomainContext.requireEvidence(
          domain,
          "typed Boolean truth projection",
          value.sourceLocation
        )
        if (admitted.isEmpty) {
          ElabInt.fail(
            "SPINAL-ELAB-DOMAIN-PROJECTION-EMPTY",
            s"typed Boolean predicate '${value.expression.verilog}' has no value in the active branch",
            value.sourceLocation
          )
        }
        val results = admitted.toVector.map { rootValue =>
          domain.evaluate(rootValue).getOrElse {
            ElabInt.fail(
              "SPINAL-ELAB-DOMAIN-EVIDENCE-INCOMPLETE",
              s"typed Boolean predicate '${value.expression.verilog}' has no evaluation at $rootValue",
              value.sourceLocation
            )
          }
        }.distinct
        results match {
          case Vector(true)  => AlwaysTrue
          case Vector(false) => AlwaysFalse
          case _             => Unknown
        }
      case None => value.truth
    }
  }

  private def not(value: ElabBool): ElabBool = {
    val truth = value.truth match {
      case AlwaysTrue  => AlwaysFalse
      case AlwaysFalse => AlwaysTrue
      case Unknown     => Unknown
    }
    derived(
      ElaborationBooleanExpression(
        verilog = s"!(${value.expression.verilog})",
        default = !value.expression.default,
        parameters = value.expression.parameters,
        sourceLocation = value.expression.sourceLocation,
        parameterRoots = value.expression.parameterRoots,
        exactDomain = mapDomain(value.expression)(result => !result)
      ),
      truth,
      "typed Boolean negation"
    )
  }

  private def and(left: ElabBool, right: ElabBool): ElabBool = {
    val truth = (left.truth, right.truth) match {
      case (AlwaysFalse, _) | (_, AlwaysFalse) => AlwaysFalse
      case (AlwaysTrue, AlwaysTrue)            => AlwaysTrue
      case _                                   => Unknown
    }
    derived(
      ElaborationBooleanExpression(
        verilog = s"((${left.expression.verilog}) && (${right.expression.verilog}))",
        default = left.expression.default && right.expression.default,
        parameters = ElabInt.mergeParameters(
          left.expression.parameters,
          right.expression.parameters,
          left.expression.sourceLocation.orElse(right.expression.sourceLocation)
        ),
        sourceLocation = left.expression.sourceLocation.orElse(right.expression.sourceLocation),
        parameterRoots = ElabInt.mergeParameterRoots(
          left.expression.parameterRoots,
          right.expression.parameterRoots
        ),
        exactDomain = combineDomains(left, right)(_ && _)
      ),
      truth,
      "typed Boolean conjunction"
    )
  }

  private def or(left: ElabBool, right: ElabBool): ElabBool = {
    val truth = (left.truth, right.truth) match {
      case (AlwaysTrue, _) | (_, AlwaysTrue) => AlwaysTrue
      case (AlwaysFalse, AlwaysFalse)        => AlwaysFalse
      case _                                 => Unknown
    }
    derived(
      ElaborationBooleanExpression(
        verilog = s"((${left.expression.verilog}) || (${right.expression.verilog}))",
        default = left.expression.default || right.expression.default,
        parameters = ElabInt.mergeParameters(
          left.expression.parameters,
          right.expression.parameters,
          left.expression.sourceLocation.orElse(right.expression.sourceLocation)
        ),
        sourceLocation = left.expression.sourceLocation.orElse(right.expression.sourceLocation),
        parameterRoots = ElabInt.mergeParameterRoots(
          left.expression.parameterRoots,
          right.expression.parameterRoots
        ),
        exactDomain = combineDomains(left, right)(_ || _)
      ),
      truth,
      "typed Boolean disjunction"
    )
  }

  private def toElabInt(value: ElabBool): ElabInt = {
    requireAuthoritativeSource(value.expression, "Boolean-to-integer expression")
    val bounds = value.truth match {
      case AlwaysTrue  => BigInt(1) -> BigInt(1)
      case AlwaysFalse => BigInt(0) -> BigInt(0)
      case Unknown     => BigInt(0) -> BigInt(1)
    }
    ElabInt.fromDerivedExpression(
      ElaborationIntegerExpression(
        verilog = s"((${value.expression.verilog}) ? 1 : 0)",
        default = if (value.expression.default) BigInt(1) else BigInt(0),
        minimum = bounds._1,
        maximum = bounds._2,
        parameters = value.expression.parameters,
        sourceLocation = value.expression.sourceLocation,
        parameterRoots = value.expression.parameterRoots,
        exactDomain = value.expression.exactDomain.map { domain =>
          requireSourceProjection(
            value.expression,
            domain,
            "Boolean-to-integer expression"
          )
          val evaluations = ElabInt
            .activeDomainEvaluations(
              domain,
              "Boolean-to-integer expression",
              value.expression.sourceLocation
            )
            .map { case (rootValue, result) =>
              rootValue -> (if (result) BigInt(1) else BigInt(0))
            }
          ElabInt.checkedDerivedDomain(
            domain,
            evaluations,
            value.expression.sourceLocation,
            "Boolean-to-integer expression"
          )
        }
      ),
      "Boolean-to-integer expression"
    )
  }

  private def requireSourceProjection(
      expression: ElaborationBooleanExpression,
      domain: ElaborationExactDomain[Boolean],
      role: String
  ): Unit =
    ElabInt.requireProjectionSubset(
      expression.projectionProvenance,
      domain,
      ElaborationDomainContext.admitted(domain),
      role,
      expression.sourceLocation
    )

  /** A typed derivation must never be the operation which first turns public
    * Boolean metadata into declaration authority. Validate the untouched
    * source before checkedDerivedDomain is allowed to construct a trusted
    * result domain.
    */
  private def requireAuthoritativeSource(
      expression: ElaborationBooleanExpression,
      role: String
  ): Unit =
    ElabInt.requireAuthoritativeBooleanDomain(
      expression,
      role,
      "SPINAL-ELAB-BOOL-EXACT-DOMAIN-REQUIRED"
    )

  private def mapDomain(
      expression: ElaborationBooleanExpression
  )(operation: Boolean => Boolean): Option[ElaborationExactDomain[Boolean]] = {
    requireAuthoritativeSource(expression, "typed Boolean expression")
    expression.exactDomain.map { value =>
      requireSourceProjection(expression, value, "typed Boolean expression")
      val evaluations = ElabInt
        .activeDomainEvaluations(
          value,
          "typed Boolean expression",
          value.root.sourceLocation
        )
        .map { case (rootValue, result) =>
          rootValue -> operation(result)
        }
      ElabInt.checkedDerivedDomain(
        value,
        evaluations,
        value.root.sourceLocation,
        "typed Boolean expression"
      )
    }
  }

  private def combineDomains(
      left: ElabBool,
      right: ElabBool
  )(operation: (Boolean, Boolean) => Boolean): Option[
    ElaborationExactDomain[Boolean]
  ] = {
    requireAuthoritativeSource(left.expression, "typed Boolean expression")
    requireAuthoritativeSource(right.expression, "typed Boolean expression")
    left.expression.exactDomain.foreach { domain =>
      requireSourceProjection(left.expression, domain, "typed Boolean expression")
    }
    right.expression.exactDomain.foreach { domain =>
      requireSourceProjection(right.expression, domain, "typed Boolean expression")
    }
    (left.expression.exactDomain, right.expression.exactDomain) match {
      case (Some(leftDomain), Some(rightDomain)) if leftDomain.root eq rightDomain.root =>
        if (leftDomain.universe != rightDomain.universe) {
          ElabInt.requireNoLostExactCorrelation(
            left.expression.exactDomain,
            right.expression.exactDomain,
            left.expression.parameters.nonEmpty,
            right.expression.parameters.nonEmpty,
            "typed Boolean expression",
            left.sourceLocation.orElse(right.sourceLocation)
          )
          None
        } else {
          val location = left.sourceLocation.orElse(right.sourceLocation)
          val leftValues = ElabInt.activeDomainEvaluations(
            leftDomain,
            "typed Boolean expression",
            location
          )
          val rightValues = ElabInt
            .activeDomainEvaluations(
              rightDomain,
              "typed Boolean expression",
              location
            )
            .toMap
          Some(
            ElabInt.checkedDerivedDomain(
              leftDomain,
              leftValues.map { case (rootValue, leftValue) =>
                rootValue -> operation(leftValue, rightValues(rootValue))
              },
              location,
              "typed Boolean expression"
            )
          )
        }
      case (Some(domain), None) if right.expression.parameters.isEmpty =>
        val location = left.sourceLocation.orElse(right.sourceLocation)
        val evaluations = ElabInt
          .activeDomainEvaluations(
            domain,
            "typed Boolean expression",
            location
          )
          .map { case (rootValue, leftValue) =>
            rootValue -> operation(leftValue, right.expression.default)
          }
        Some(
          ElabInt.checkedDerivedDomain(
            domain,
            evaluations,
            location,
            "typed Boolean expression"
          )
        )
      case (None, Some(domain)) if left.expression.parameters.isEmpty =>
        val location = left.sourceLocation.orElse(right.sourceLocation)
        val evaluations = ElabInt
          .activeDomainEvaluations(
            domain,
            "typed Boolean expression",
            location
          )
          .map { case (rootValue, rightValue) =>
            rootValue -> operation(left.expression.default, rightValue)
          }
        Some(
          ElabInt.checkedDerivedDomain(
            domain,
            evaluations,
            location,
            "typed Boolean expression"
          )
        )
      case _ =>
        ElabInt.requireNoLostExactCorrelation(
          left.expression.exactDomain,
          right.expression.exactDomain,
          left.expression.parameters.nonEmpty,
          right.expression.parameters.nonEmpty,
          "typed Boolean expression",
          left.sourceLocation.orElse(right.sourceLocation)
        )
        None
    }
  }
}

object ElabInt {
  val MaximumExactDomainSize: BigInt =
    ElaborationExactDomain.MaximumDomainSize

  def literal(value: Int): ElabInt = fromBigInt(BigInt(value))

  def fromBigInt(value: BigInt): ElabInt = {
    if (value == null)
      fail(
        "SPINAL-ELAB-INT-LITERAL-NULL",
        "elaboration integer literal must not be null",
        None
      )
    if (!value.isValidInt)
      fail(
        "SPINAL-ELAB-INT-LITERAL-OUT-OF-RANGE",
        s"elaboration integer literal $value does not fit Scala Int",
        None
      )
    fromExpression(
      ElaborationIntegerExpression(
        verilog = value.toString,
        default = value,
        minimum = value,
        maximum = value,
        parameters = Vector.empty
      )
    )
  }

  def fromExpression(expression: ElaborationIntegerExpression): ElabInt = {
    validateExpression(expression, "ElabInt expression")
    new ElabInt(withCompleteParameterRoots(expression))
  }

  /** Test-only/internal constructor for malformed exact-domain fixtures which
    * need to exercise a deeper consumer diagnostic. Public callers must use
    * [[fromExpression]], where copied exact metadata is rejected.
    */
  private[spinal] def fromTrustedExactExpressionForTest(
      expression: ElaborationIntegerExpression
  ): ElabInt = {
    val certified = authenticateExactExpressionForTest(expression)
    validateExpression(certified, "trusted exact-domain test fixture")
    new ElabInt(withCompleteParameterRoots(certified))
  }

  private[spinal] def authenticateExactExpressionForTest(
      expression: ElaborationIntegerExpression
  ): ElaborationIntegerExpression =
    attachDerivedExactAuthority(expression, "trusted exact-domain test fixture")

  /** Fail closed before one retained integer expression authorizes native
    * geometry or values.
    *
    * A parameter-free carrier is authoritative only when it is the canonical
    * numeric literal.  A symbolic carrier must retain one exact declaration
    * root, the exact same parameter-schema object, and one unique evaluation
    * for every value in its full domain or in its still-authorized branch
    * projection.  Rendered names and case-class-equal schema copies never
    * recover that authority.
    *
    * Exact evaluation results must stay inside the retained interval and the
    * representative result must equal the concrete witness. Some generic
    * integer operations intentionally retain conservative interval bounds for
    * correlated expressions, so callers choose whether exact extrema are also
    * mandatory.
    */
  private[spinal] def requireAuthoritativeIntegerDomain(
      expression: ElaborationIntegerExpression,
      role: String,
      failureCode: String,
      requireExactExtrema: Boolean
  ): Option[ElaborationExactDomain[BigInt]] = {
    validateExpression(expression, role)
    val source = expression.sourceLocation

    def reject(detail: String): Nothing =
      fail(failureCode, s"$role $detail", source)

    val roots = distinctParameterRoots(expression.completedParameterRoots)
    if (expression.parameters.isEmpty) {
      if (
        roots.nonEmpty || expression.exactDomain.nonEmpty ||
        expression.generateIndex.nonEmpty ||
        expression.minimum != expression.default ||
        expression.maximum != expression.default ||
        expression.verilog.trim != expression.default.toString
      )
        reject(
          s"parameter-free expression '${expression.verilog}' is not one canonical numeric literal"
        )
      return None
    }

    if (expression.generateIndex.nonEmpty)
      reject(
        s"symbolic expression '${expression.verilog}' also depends on a generate index"
      )

    val domain = expression.exactDomain.getOrElse {
      reject(
        s"symbolic expression '${expression.verilog}' lacks complete exact-domain evidence"
      )
    }
    val schema = expression.parameters match {
      case Vector(value) => value
      case _ =>
        reject(
          s"symbolic expression '${expression.verilog}' does not retain exactly one parameter schema"
        )
    }
    if (
      roots.size != 1 || (roots.head ne domain.root) ||
      (schema ne domain.parameter) || schema.name != domain.root.name ||
      !domain.root.isAuthoritativeSchema(schema)
    )
      reject(
        s"symbolic expression '${expression.verilog}' exact evidence does not retain its one authoritative declaration root and schema identity"
      )

    requireProjectionSubset(
      expression.projectionProvenance,
      domain,
      ElaborationDomainContext.admitted(domain),
      role,
      source
    )

    val evaluations = domain.evaluations
    if (evaluations.exists(entry => entry == null || entry._1 == null || entry._2 == null))
      reject(
        s"symbolic expression '${expression.verilog}' contains null exact-domain evidence"
      )

    val keys = evaluations.map(_._1)
    val universe = domain.universe
    val projection = expression.projectionProvenance
    val requiredValues = projection match {
      case Some(value) =>
        if (
          (value.root ne domain.root) || value.admitted.isEmpty ||
          !value.admitted.subsetOf(universe)
        )
          reject(
            s"symbolic expression '${expression.verilog}' carries foreign or invalid projection authority"
          )
        val representative =
          if (value.admitted.contains(schema.default)) schema.default
          else value.admitted.min
        if (value.representative != representative)
          reject(
            s"symbolic expression '${expression.verilog}' carries a non-deterministic projection representative"
          )
        value.admitted
      case None =>
        if (domain.evidenceValues != universe)
          reject(
            s"symbolic expression '${expression.verilog}' carries branch-partial evidence without projection authority"
          )
        universe
    }
    if (keys.size != keys.distinct.size || keys.toSet != requiredValues)
      reject(
        s"symbolic expression '${expression.verilog}' does not evaluate every authorized root value exactly once"
      )

    val results = evaluations.map(_._2)
    results
      .zip(keys)
      .collectFirst {
        case (result, rootValue)
            if !result.isValidInt || result < expression.minimum ||
              result > expression.maximum =>
          rootValue -> result
      }
      .foreach { case (rootValue, result) =>
        reject(
          s"symbolic expression '${expression.verilog}' evaluates to $result at ${schema.name}=$rootValue outside its retained Int interval [${expression.minimum}, ${expression.maximum}]"
        )
      }
    if (
      requireExactExtrema &&
      (results.min != expression.minimum || results.max != expression.maximum)
    )
      reject(
        s"symbolic expression '${expression.verilog}' retained interval [${expression.minimum}, ${expression.maximum}] does not equal its exact extrema [${results.min}, ${results.max}]"
      )

    val representative = projection
      .map(_.representative)
      .getOrElse(schema.default)
    if (!domain.evaluate(representative).contains(expression.default))
      reject(
        s"symbolic expression '${expression.verilog}' concrete witness ${expression.default} does not equal its exact representative evaluation"
      )
    Some(domain)
  }

  /** Boolean counterpart of [[requireAuthoritativeIntegerDomain]]. A
    * parameter-free predicate may retain any fully authored concrete Boolean
    * expression, but it must not smuggle symbolic roots or exact evidence. A
    * symbolic predicate must retain one authoritative declaration root and
    * schema, exactly one result for every full-domain or identity-authorized
    * projected root value, and the exact representative default.
    */
  private[spinal] def requireAuthoritativeBooleanDomain(
      expression: ElaborationBooleanExpression,
      role: String,
      failureCode: String
  ): Option[ElaborationExactDomain[Boolean]] = {
    validateExpression(expression, role)
    val source = expression.sourceLocation

    def reject(detail: String): Nothing =
      fail(failureCode, s"$role $detail", source)

    val roots = distinctParameterRoots(expression.completedParameterRoots)
    if (expression.parameters.isEmpty) {
      if (
        roots.nonEmpty || expression.exactDomain.nonEmpty ||
        expression.projectionProvenance.nonEmpty
      )
        reject(
          s"parameter-free predicate '${expression.verilog}' carries symbolic exact-domain authority"
        )
      return None
    }

    val domain = expression.exactDomain.getOrElse {
      reject(
        s"symbolic predicate '${expression.verilog}' lacks complete exact-domain evidence"
      )
    }
    val schema = expression.parameters match {
      case Vector(value) => value
      case _ =>
        reject(
          s"symbolic predicate '${expression.verilog}' does not retain exactly one parameter schema"
        )
    }
    if (
      roots.size != 1 || (roots.head ne domain.root) ||
      (schema ne domain.parameter) || schema.name != domain.root.name ||
      !domain.root.isAuthoritativeSchema(schema)
    )
      reject(
        s"symbolic predicate '${expression.verilog}' exact evidence does not retain its one authoritative declaration root and schema identity"
      )

    requireProjectionSubset(
      expression.projectionProvenance,
      domain,
      ElaborationDomainContext.admitted(domain),
      role,
      source
    )

    val evaluations = domain.evaluations
    evaluations.zipWithIndex.foreach { case (entry, index) =>
      if (entry == null)
        reject(
          s"symbolic predicate '${expression.verilog}' contains a null exact-domain entry at index $index"
        )
      if (
        entry.productElement(0) == null ||
        entry.productElement(1) == null
      )
        reject(
          s"symbolic predicate '${expression.verilog}' contains null exact-domain evidence at index $index"
        )
    }

    val keys = evaluations.map(_._1)
    val universe = domain.universe
    val projection = expression.projectionProvenance
    val requiredValues = projection match {
      case Some(value) =>
        if (
          (value.root ne domain.root) || value.admitted.isEmpty ||
          !value.admitted.subsetOf(universe)
        )
          reject(
            s"symbolic predicate '${expression.verilog}' carries foreign or invalid projection authority"
          )
        val representative =
          if (value.admitted.contains(schema.default)) schema.default
          else value.admitted.min
        if (value.representative != representative)
          reject(
            s"symbolic predicate '${expression.verilog}' carries a non-deterministic projection representative"
          )
        value.admitted
      case None =>
        if (domain.evidenceValues != universe)
          reject(
            s"symbolic predicate '${expression.verilog}' carries branch-partial evidence without projection authority"
          )
        universe
    }
    if (keys.size != keys.distinct.size || keys.toSet != requiredValues)
      reject(
        s"symbolic predicate '${expression.verilog}' does not evaluate every authorized root value exactly once"
      )

    val representative = projection
      .map(_.representative)
      .getOrElse(schema.default)
    if (!domain.evaluate(representative).contains(expression.default))
      reject(
        s"symbolic predicate '${expression.verilog}' concrete witness ${expression.default} does not equal its exact representative evaluation"
      )
    Some(domain)
  }

  /** Preserve private branch authority across one trusted typed operation.
    * Public case-class construction and copying never call these helpers. The
    * derived summary is normalized to the exact active evaluator before the
    * provenance is attached, matching the ordinary projection path.
    */
  private[core] def attachDerivedExactAuthority(
      expression: ElaborationIntegerExpression,
      role: String
  ): ElaborationIntegerExpression =
    expression.exactDomain match {
      case Some(domain) => expression.attachExactAuthority(domain, role)
      case None         => expression
    }

  private[core] def attachDerivedExactAuthority(
      expression: ElaborationBooleanExpression,
      role: String
  ): ElaborationBooleanExpression =
    expression.exactDomain match {
      case Some(domain) => expression.attachExactAuthority(domain, role)
      case None         => expression
    }

  private[core] def authorizeDerivedProjection(
      expression: ElaborationIntegerExpression,
      role: String
  ): ElaborationIntegerExpression =
    expression.exactDomain match {
      case Some(domain) if domain.evidenceValues != domain.universe =>
        val admitted = domain.evidenceValues
        val representative =
          if (admitted.contains(domain.parameter.default))
            domain.parameter.default
          else admitted.min
        val results = domain.evaluations.map(_._2)
        expression
          .copy(
            default = domain.evaluate(representative).get,
            minimum = results.min,
            maximum = results.max
          )
          .attachProjection(
            domain,
            admitted,
            representative,
            role,
            expression.sourceLocation
          )
      case _ => expression
    }

  private[core] def authorizeDerivedProjection(
      expression: ElaborationBooleanExpression,
      role: String
  ): ElaborationBooleanExpression =
    expression.exactDomain match {
      case Some(domain) if domain.evidenceValues != domain.universe =>
        val admitted = domain.evidenceValues
        val representative =
          if (admitted.contains(domain.parameter.default))
            domain.parameter.default
          else admitted.min
        expression
          .copy(default = domain.evaluate(representative).get)
          .attachProjection(
            domain,
            admitted,
            representative,
            role,
            expression.sourceLocation
          )
      case _ => expression
    }

  private[core] def fromDerivedExpression(
      expression: ElaborationIntegerExpression,
      role: String
  ): ElabInt = {
    val certified = attachDerivedExactAuthority(expression, role)
    validateExpression(certified, role)
    val authorized = authorizeDerivedProjection(certified, role)
    val completed = withCompleteParameterRoots(authorized)
    validateExpression(completed, role)

    if (completed.parameters.isEmpty) {
      if (
        completed.completedParameterRoots.nonEmpty ||
        completed.exactDomain.nonEmpty ||
        completed.generateIndex.nonEmpty ||
        completed.projectionProvenance.nonEmpty ||
        completed.minimum != completed.default ||
        completed.maximum != completed.default
      ) {
        fail(
          "SPINAL-ELAB-INT-DERIVED-CONCRETE-AUTHORITY-INVALID",
          s"$role parameter-free result '${completed.verilog}' must reduce to one rootless exact Int literal before it can authorize another typed operation",
          completed.sourceLocation
        )
      }
      // A trusted typed derivation has already validated every source before it
      // reaches this helper. Collapse its parameter-free result to the same
      // canonical literal used by the public literal constructor. Public raw
      // expressions never enter this path, so their authored text cannot be
      // laundered into concrete authority.
      fromBigInt(completed.default)
    } else new ElabInt(completed)
  }

  /** Attach exhaustive, single-root evidence produced from a typed frontend
    * AST.  The root is taken from the expression's explicit provenance, never
    * recovered from its rendered Verilog text.
    */
  def fromSingleRootExpression(
      expression: ElaborationIntegerExpression,
      evaluations: Vector[(BigInt, BigInt)],
      permit: ExternalCompilerPermit
  ): ElabInt = {
    ExternalCompilerPermit.requireAnalyzedSingleRoot(
      permit,
      expression,
      evaluations
    )
    fromSingleRootExpressionTrusted(expression, evaluations)
  }

  /** Raw expression/table pairs are self-consistent metadata, not proof that
    * the rendered expression came from the frontend AST analyzer.
    */
  def fromSingleRootExpression(
      expression: ElaborationIntegerExpression,
      evaluations: Vector[(BigInt, BigInt)]
  ): ElabInt =
    ParameterizedVerilogException.fail(
      "SPINAL-ELAB-INT-ANALYZED-SOURCE-AUTHORIZATION-REQUIRED",
      "single-root exact-domain publication requires one opaque frontend-analysis permit",
      Option(expression).flatMap(_.sourceLocation)
    )

  /** Internal typed derivations already own the AST/domain construction and do
    * not cross the analyzed-frontend metadata boundary.
    */
  private[spinal] def fromSingleRootExpressionTrusted(
      expression: ElaborationIntegerExpression,
      evaluations: Vector[(BigInt, BigInt)]
  ): ElabInt = {
    validateExpression(expression, "single-root ElabInt expression")
    if (expression.exactDomain.nonEmpty) {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-RECERTIFICATION-UNSUPPORTED",
        s"single-root ElabInt expression '${expression.verilog}' already carries exact evidence and cannot replace its evaluator",
        expression.sourceLocation
      )
    }
    val normalized = withCompleteParameterRoots(expression)
    val root = normalized.parameterRoots match {
      case Vector(value) => value
      case values =>
        fail(
          "SPINAL-ELAB-DOMAIN-ROOT-COUNT-UNSUPPORTED",
          s"single-root ElabInt expression '${normalized.verilog}' retains ${values.size} roots",
          normalized.sourceLocation
        )
    }
    val parameter = normalized.parameters.find(_.name == root.name).getOrElse {
      fail(
        "SPINAL-ELAB-DOMAIN-ROOT-SCHEMA-MISSING",
        s"single-root ElabInt expression '${normalized.verilog}' has no schema for exact root '${root.name}'",
        normalized.sourceLocation.orElse(root.sourceLocation)
      )
    }
    val domain = ElaborationExactDomain.checked(
      root,
      parameter,
      evaluations,
      normalized.sourceLocation,
      s"single-root expression '${normalized.verilog}'"
    )
    domain.evaluations
      .collectFirst {
        case (rootValue, result) if !result.isValidInt => rootValue -> result
      }
      .foreach { case (rootValue, result) =>
        fail(
          "SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-OUT-OF-RANGE",
          s"single-root expression '${normalized.verilog}' evaluates to $result at ${parameter.name}=$rootValue, outside the Scala Int domain",
          normalized.sourceLocation
        )
      }
    domain.evaluations
      .collectFirst {
        case (rootValue, result) if result < normalized.minimum || result > normalized.maximum =>
          (rootValue, result)
      }
      .foreach { case (rootValue, result) =>
        fail(
          "SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-OUTSIDE-BOUNDS",
          s"single-root expression '${normalized.verilog}' evaluates to $result at ${parameter.name}=$rootValue, outside retained bounds [${normalized.minimum}, ${normalized.maximum}]",
          normalized.sourceLocation
        )
      }
    domain.evaluate(parameter.default) match {
      case Some(value) if value == normalized.default =>
      case Some(value) =>
        fail(
          "SPINAL-ELAB-DOMAIN-WITNESS-MISMATCH",
          s"single-root expression '${normalized.verilog}' evaluates to $value at ${parameter.name}=${parameter.default}, not retained default ${normalized.default}",
          normalized.sourceLocation
        )
      case None =>
        fail(
          "SPINAL-ELAB-DOMAIN-EVIDENCE-INCOMPLETE",
          s"single-root expression '${normalized.verilog}' has no default evaluation",
          normalized.sourceLocation
        )
    }
    val exactResults = domain.evaluations.map(_._2)
    val exactExpression = normalized
      .copy(
        minimum = exactResults.min,
        maximum = exactResults.max,
        exactDomain = Some(domain)
      )
      .attachExactAuthority(domain, "single-root ElabInt expression")
    new ElabInt(exactExpression)
  }

  /** Definition-side direct formal used by typed native component adapters. */
  private[spinal] def directParameter(
      parameter: ElaborationIntegerParameter,
      sourceLocation: Option[String]
  ): ElabInt = {
    if (parameter == null)
      throw new IllegalArgumentException("direct ElabInt parameter must not be null")
    val root = parameter.declarationRoot
    fromSingleRootExpressionTrusted(
      ElaborationIntegerExpression(
        verilog = parameter.name,
        default = parameter.default,
        minimum = parameter.minimum,
        maximum = parameter.maximum,
        parameters = Vector(parameter),
        sourceLocation = sourceLocation,
        parameterRoots = Vector(root)
      ),
      ElaborationExactDomain
        .boundedValues(parameter.minimum, parameter.maximum)
        .map(value => value -> value)
    )
  }

  private[spinal] def projectExpression(
      expression: ElaborationIntegerExpression,
      role: String
  ): ElaborationIntegerExpression = {
    if (ElaborationWidthAuthority.isRetained(expression))
      return ElaborationWidthAuthority.project(expression, role)
    // Projection is the common authority boundary for witness, extrema,
    // constant-only helpers and every concrete-overload delegation. Validate
    // parameter-free carriers here as well: only a canonical literal (including
    // a trusted derivation normalized by fromDerivedExpression) may be consumed.
    requireAuthoritativeIntegerDomain(
      expression,
      role,
      "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING",
      requireExactExtrema = false
    )
    expression.exactDomain match {
      case Some(domain) =>
        val requested = ElaborationDomainContext.admitted(domain)
        requireProjectionSubset(
          expression.projectionProvenance,
          domain,
          requested,
          role,
          expression.sourceLocation
        )
        val admitted = ElaborationDomainContext.requireEvidence(
          domain,
          role,
          expression.sourceLocation
        )
        if (admitted.isEmpty) {
          fail(
            "SPINAL-ELAB-DOMAIN-PROJECTION-EMPTY",
            s"$role expression '${expression.verilog}' has no value in the active branch",
            expression.sourceLocation
          )
        }
        val evaluated = admitted.toVector.map { rootValue =>
          domain.evaluate(rootValue).getOrElse {
            fail(
              "SPINAL-ELAB-DOMAIN-EVIDENCE-INCOMPLETE",
              s"$role expression '${expression.verilog}' has no evaluation at $rootValue",
              expression.sourceLocation
            )
          }
        }
        val representative = ElaborationDomainContext.representative(domain)
        val projectedDefault = domain.evaluate(representative).getOrElse {
          fail(
            "SPINAL-ELAB-DOMAIN-EVIDENCE-INCOMPLETE",
            s"$role expression '${expression.verilog}' has no evaluation at $representative",
            expression.sourceLocation
          )
        }
        val projectedDomain = ElaborationExactDomain.checkedPartial(
          domain.root,
          domain.parameter,
          domain.evaluations.filter { case (rootValue, _) =>
            admitted.contains(rootValue)
          },
          expression.sourceLocation,
          role
        )
        expression
          .copy(
            default = projectedDefault,
            minimum = evaluated.min,
            maximum = evaluated.max,
            exactDomain = Some(projectedDomain)
          )
          .attachProjection(
            projectedDomain,
            admitted,
            representative,
            role,
            expression.sourceLocation
          )
      case None =>
        expression.parameterRoots.find(ElaborationDomainContext.constrains).foreach { root =>
          fail(
            "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING",
            s"$role expression '${expression.verilog}' lacks exact evidence for active root '${root.name}'",
            expression.sourceLocation.orElse(root.sourceLocation)
          )
        }
        expression
    }
  }

  /** Neutral exact evaluator used by captured-domain backend proofs. */
  private[spinal] def evaluateExact(
      expression: ElaborationIntegerExpression,
      root: ElaborationIntegerParameterRoot,
      rootValue: BigInt
  ): Option[BigInt] =
    Option(expression)
      .flatMap(_.exactDomain)
      .filter(domain => domain.root eq root)
      .flatMap(_.evaluate(rootValue))

  /** Retain the total packed width of one native Data value. */
  def packedWidthOf(data: Data): ElabInt = {
    if (data == null)
      fail(
        "SPINAL-ELAB-WIDTH-DATA-NULL",
        "packedWidthOf received a null Data value",
        None
      )
    data match {
      case vector: Vec[_] if ParameterizedVec.shapeOf(vector).nonEmpty =>
        return ParameterizedVec
          .logicalPackedWidthExpressionOf(vector)
          .map(fromExpression)
          .getOrElse {
            val shape = ParameterizedVec.shapeOf(vector).get
            fail(
              "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
              s"typed Vec packed width '${shape.elementLeaves.map(_.width.verilog).mkString(" + ")} times ${shape.depth.verilog}' combines independently sourced symbolic roots",
              shape.sourceLocation
            )
          }
      case _ =>
    }
    val leaves = data.flatten.toVector
    leaves
      .map { leaf =>
        ParameterizedWidth
          .expressionOf(leaf)
          .map(fromExpression)
          .getOrElse(literal(leaf.getBitsWidth))
      }
      .reduceOption(_ + _)
      .getOrElse(literal(0))
  }

  /** Current typed native-library contract: relational geometry may use one
    * symbolic root plus literals, or multiple occurrences of the same root.
    */
  def requireSingleSymbolicRoot(role: String, values: ElabInt*): Unit = {
    val parameters = values.toVector.flatMap(_.expression.parameters)
    val grouped = parameters.groupBy(_.name)
    grouped.foreach { case (name, schemas) =>
      if (schemas.distinct.size != 1) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-SCHEMA-CONFLICT",
          s"$role observes conflicting declarations for parameter '$name'",
          values.toVector.flatMap(_.sourceLocation).headOption
        )
      }
    }
    val roots = distinctParameterRoots(
      values.toVector.flatMap(_.expression.parameterRoots)
    )
    if (roots.size > 1) {
      fail(
        "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
        s"$role currently accepts one symbolic root, but found independently sourced declarations ${roots.map(_.name).sorted.mkString(", ")}",
        roots.flatMap(_.sourceLocation).headOption
      )
    }
  }

  private def add(left: ElabInt, right: ElabInt): ElabInt =
    binary(
      "+",
      left,
      right,
      left.minimum + right.minimum,
      left.maximum + right.maximum,
      projectedDefault(left) + projectedDefault(right)
    )

  private def subtract(left: ElabInt, right: ElabInt): ElabInt =
    binary(
      "-",
      left,
      right,
      left.minimum - right.maximum,
      left.maximum - right.minimum,
      projectedDefault(left) - projectedDefault(right)
    )

  private def multiply(left: ElabInt, right: ElabInt): ElabInt = {
    val candidates = Vector(
      left.minimum * right.minimum,
      left.minimum * right.maximum,
      left.maximum * right.minimum,
      left.maximum * right.maximum
    )
    binary(
      "*",
      left,
      right,
      candidates.min,
      candidates.max,
      projectedDefault(left) * projectedDefault(right)
    )
  }

  private def divide(left: ElabInt, right: ElabInt): ElabInt = {
    if (left.minimum < 0 || right.minimum <= 0) {
      fail(
        "SPINAL-ELAB-INT-DIVISION-DOMAIN-UNSUPPORTED",
        s"division '${left.expression.verilog} / ${right.expression.verilog}' requires a non-negative dividend and positive divisor over the active domain",
        left.sourceLocation.orElse(right.sourceLocation)
      )
    }
    binary(
      "/",
      left,
      right,
      left.minimum / right.maximum,
      left.maximum / right.minimum,
      projectedDefault(left) / projectedDefault(right)
    )
  }

  private def modulo(left: ElabInt, right: ElabInt): ElabInt = {
    if (left.minimum < 0 || right.minimum <= 0) {
      fail(
        "SPINAL-ELAB-INT-MODULO-DOMAIN-UNSUPPORTED",
        s"modulo '${left.expression.verilog} % ${right.expression.verilog}' requires a non-negative dividend and positive divisor over the active domain",
        left.sourceLocation.orElse(right.sourceLocation)
      )
    }
    val default = projectedDefault(left) % projectedDefault(right)
    val concrete = left.isConcrete && right.isConcrete
    val maximum = (right.maximum - 1).min(left.maximum).max(BigInt(0))
    binary(
      "%",
      left,
      right,
      if (concrete) default else BigInt(0),
      if (concrete) default else maximum,
      default
    )
  }

  private def log2UpValue(value: ElabInt): ElabInt = {
    if (value.minimum < 0) {
      fail(
        "SPINAL-ELAB-INT-LOG2-DOMAIN-NEGATIVE",
        s"log2Up input '${value.expression.verilog}' reaches ${value.minimum}",
        value.sourceLocation
      )
    }
    def evaluate(input: BigInt): BigInt =
      if (input == 0) BigInt(0) else BigInt((input - 1).bitLength)
    fromDerivedExpression(
      ElaborationIntegerExpression(
        verilog = s"morphhdl_ceil_log2(${value.expression.verilog})",
        default = evaluate(projectedDefault(value)),
        minimum = evaluate(value.minimum),
        maximum = evaluate(value.maximum),
        parameters = value.expression.parameters,
        sourceLocation = value.sourceLocation,
        parameterRoots = value.expression.parameterRoots,
        exactDomain = mapIntegerDomain(value.expression.exactDomain)(evaluate)
      ),
      "typed ceiling-logarithm expression"
    )
  }

  private def addressWidthValue(value: ElabInt): ElabInt = {
    if (value.minimum < 1) {
      fail(
        "SPINAL-ELAB-INT-ADDRESS-WIDTH-DOMAIN-NONPOSITIVE",
        s"addressWidth input '${value.expression.verilog}' reaches ${value.minimum}",
        value.sourceLocation
      )
    }
    def evaluate(input: BigInt): BigInt =
      BigInt(math.max(1, (input - 1).bitLength))
    fromDerivedExpression(
      ElaborationIntegerExpression(
        verilog = s"morphhdl_address_width(${value.expression.verilog})",
        default = evaluate(projectedDefault(value)),
        minimum = evaluate(value.minimum),
        maximum = evaluate(value.maximum),
        parameters = value.expression.parameters,
        sourceLocation = value.sourceLocation,
        parameterRoots = value.expression.parameterRoots,
        exactDomain = mapIntegerDomain(value.expression.exactDomain)(evaluate)
      ),
      "typed address-width expression"
    )
  }

  private def isPow2Value(value: ElabInt): ElabBool = {
    if (value.expression.parameters.nonEmpty)
      value.requireAuthoritativeIntegerDomain(
        role = "typed power-of-two predicate",
        failureCode = "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING",
        requireExactExtrema = false
      )
    def evaluate(input: BigInt): Boolean = input >= 0 && input.bitCount == 1
    val exactDomain = value.expression.exactDomain.map { domain =>
      val evaluations = activeDomainEvaluations(
        domain,
        "typed power-of-two predicate",
        value.sourceLocation
      ).map { case (rootValue, result) =>
        rootValue -> evaluate(result)
      }
      checkedDerivedDomain(
        domain,
        evaluations,
        value.sourceLocation,
        "typed power-of-two predicate"
      )
    }
    val truth = exactDomain match {
      case Some(domain) if domain.evaluations.forall(_._2) => ElabBool.AlwaysTrue
      case Some(domain) if domain.evaluations.forall { case (_, result) => !result } =>
        ElabBool.AlwaysFalse
      case _ if value.isDomainConstant =>
        if (evaluate(projectedDefault(value))) ElabBool.AlwaysTrue
        else ElabBool.AlwaysFalse
      case _ => ElabBool.Unknown
    }
    ElabBool.derived(
      ElaborationBooleanExpression(
        verilog =
          s"((${value.expression.verilog} > 0) && ((${value.expression.verilog} & (${value.expression.verilog} - 1)) == 0))",
        default = evaluate(projectedDefault(value)),
        parameters = value.expression.parameters,
        sourceLocation = value.sourceLocation,
        parameterRoots = value.expression.parameterRoots,
        exactDomain = exactDomain
      ),
      truth,
      "typed power-of-two predicate"
    )
  }

  private def pow2Value(value: ElabInt): ElabInt = {
    if (value.minimum < 0 || value.maximum > 30) {
      fail(
        "SPINAL-ELAB-INT-POW2-DOMAIN-UNSUPPORTED",
        s"typed power-of-two exponent '${value.expression.verilog}' must remain in [0, 30], but reaches [${value.minimum}, ${value.maximum}]",
        value.sourceLocation
      )
    }
    def evaluate(exponent: BigInt): BigInt = BigInt(1) << exponent.toInt
    fromDerivedExpression(
      ElaborationIntegerExpression(
        verilog = s"(1 << (${value.expression.verilog}))",
        default = evaluate(projectedDefault(value)),
        minimum = evaluate(value.minimum),
        maximum = evaluate(value.maximum),
        parameters = value.expression.parameters,
        sourceLocation = value.sourceLocation,
        parameterRoots = value.expression.parameterRoots,
        exactDomain = mapIntegerDomain(value.expression.exactDomain)(evaluate)
      ),
      "typed power-of-two expression"
    )
  }

  private def binary(
      operation: String,
      left: ElabInt,
      right: ElabInt,
      minimum: BigInt,
      maximum: BigInt,
      default: BigInt
  ): ElabInt = {
    val location = left.sourceLocation.orElse(right.sourceLocation)
    if (minimum > maximum || default < minimum || default > maximum) {
      fail(
        "SPINAL-ELAB-INT-EXPRESSION-DOMAIN-INVALID",
        s"expression '${left.expression.verilog} $operation ${right.expression.verilog}' has default $default outside [$minimum, $maximum]",
        location
      )
    }
    fromDerivedExpression(
      ElaborationIntegerExpression(
        verilog = s"(${left.expression.verilog} $operation ${right.expression.verilog})",
        default = default,
        minimum = minimum,
        maximum = maximum,
        parameters = mergeParameters(
          left.expression.parameters,
          right.expression.parameters,
          location
        ),
        sourceLocation = location,
        parameterRoots = mergeParameterRoots(
          left.expression.parameterRoots,
          right.expression.parameterRoots
        ),
        exactDomain = combineIntegerDomains(left, right) { case (l, r) =>
          operation match {
            case "+" => l + r
            case "-" => l - r
            case "*" => l * r
            case "/" => l / r
            case "%" => l % r
            case other =>
              throw new IllegalArgumentException(s"unsupported exact integer operation '$other'")
          }
        }
      ),
      "typed integer expression"
    )
  }

  private def equal(left: ElabInt, right: ElabInt): ElabBool = {
    val equivalent = equivalentExpression(left.expression, right.expression)
    val disjoint = left.maximum < right.minimum || right.maximum < left.minimum
    val truth =
      if (equivalent) ElabBool.AlwaysTrue
      else if (disjoint) ElabBool.AlwaysFalse
      else if (left.isDomainConstant && right.isDomainConstant) {
        if (left.witness == right.witness) ElabBool.AlwaysTrue
        else ElabBool.AlwaysFalse
      } else ElabBool.Unknown
    ElabBool.derived(
      ElaborationBooleanExpression(
        verilog = s"((${left.expression.verilog}) == (${right.expression.verilog}))",
        default = projectedDefault(left) == projectedDefault(right),
        parameters = mergeParameters(
          left.expression.parameters,
          right.expression.parameters,
          left.sourceLocation.orElse(right.sourceLocation)
        ),
        sourceLocation = left.sourceLocation.orElse(right.sourceLocation),
        parameterRoots = mergeParameterRoots(
          left.expression.parameterRoots,
          right.expression.parameterRoots
        ),
        exactDomain = combineIntegerBooleanDomains(left, right)(_ == _)
      ),
      truth,
      "typed equality predicate"
    )
  }

  private def compare(
      operation: String,
      left: ElabInt,
      right: ElabInt
  ): ElabBool = {
    val witness = operation match {
      case "<"   => projectedDefault(left) < projectedDefault(right)
      case "<="  => projectedDefault(left) <= projectedDefault(right)
      case ">"   => projectedDefault(left) > projectedDefault(right)
      case ">="  => projectedDefault(left) >= projectedDefault(right)
      case other => throw new IllegalArgumentException(s"unsupported comparison '$other'")
    }
    val equivalent = equivalentExpression(left.expression, right.expression)
    val truth =
      if (equivalent) {
        operation match {
          case "<" | ">"   => ElabBool.AlwaysFalse
          case "<=" | ">=" => ElabBool.AlwaysTrue
        }
      } else {
        operation match {
          case "<" if left.maximum < right.minimum   => ElabBool.AlwaysTrue
          case "<" if left.minimum >= right.maximum  => ElabBool.AlwaysFalse
          case "<=" if left.maximum <= right.minimum => ElabBool.AlwaysTrue
          case "<=" if left.minimum > right.maximum  => ElabBool.AlwaysFalse
          case ">" if left.minimum > right.maximum   => ElabBool.AlwaysTrue
          case ">" if left.maximum <= right.minimum  => ElabBool.AlwaysFalse
          case ">=" if left.minimum >= right.maximum => ElabBool.AlwaysTrue
          case ">=" if left.maximum < right.minimum  => ElabBool.AlwaysFalse
          case _                                     => ElabBool.Unknown
        }
      }
    ElabBool.derived(
      ElaborationBooleanExpression(
        verilog = s"((${left.expression.verilog}) $operation (${right.expression.verilog}))",
        default = witness,
        parameters = mergeParameters(
          left.expression.parameters,
          right.expression.parameters,
          left.sourceLocation.orElse(right.sourceLocation)
        ),
        sourceLocation = left.sourceLocation.orElse(right.sourceLocation),
        parameterRoots = mergeParameterRoots(
          left.expression.parameterRoots,
          right.expression.parameterRoots
        ),
        exactDomain = combineIntegerBooleanDomains(left, right) { case (l, r) =>
          operation match {
            case "<"  => l < r
            case "<=" => l <= r
            case ">"  => l > r
            case ">=" => l >= r
          }
        }
      ),
      truth,
      "typed comparison predicate"
    )
  }

  private def projectedDefault(value: ElabInt): BigInt =
    value.projectedExpression("typed elaboration operation").default

  private def mapIntegerDomain(
      domain: Option[ElaborationExactDomain[BigInt]]
  )(operation: BigInt => BigInt): Option[ElaborationExactDomain[BigInt]] =
    domain.map { value =>
      val evaluations = activeDomainEvaluations(
        value,
        "typed integer expression",
        value.root.sourceLocation
      ).map { case (rootValue, result) =>
        rootValue -> operation(result)
      }
      checkedIntegerDerivedDomain(
        value,
        evaluations,
        value.root.sourceLocation,
        "typed integer expression"
      )
    }

  private def combineIntegerDomains(
      left: ElabInt,
      right: ElabInt
  )(operation: (BigInt, BigInt) => BigInt): Option[
    ElaborationExactDomain[BigInt]
  ] =
    combineDomains(left, right, operation, "typed integer expression").map { domain =>
      requireIntegerResultsInRange(
        domain,
        "typed integer expression",
        left.sourceLocation.orElse(right.sourceLocation)
      )
      domain
    }

  private def combineIntegerBooleanDomains(
      left: ElabInt,
      right: ElabInt
  )(operation: (BigInt, BigInt) => Boolean): Option[
    ElaborationExactDomain[Boolean]
  ] =
    combineDomains(left, right, operation, "typed Boolean expression")

  private def combineDomains[A](
      left: ElabInt,
      right: ElabInt,
      operation: (BigInt, BigInt) => A,
      role: String
  ): Option[ElaborationExactDomain[A]] =
    (left.expression.exactDomain, right.expression.exactDomain) match {
      case (Some(leftDomain), Some(rightDomain)) if leftDomain.root eq rightDomain.root =>
        if (leftDomain.universe != rightDomain.universe) {
          requireNoLostExactCorrelation(
            left.expression.exactDomain,
            right.expression.exactDomain,
            left.expression.parameters.nonEmpty,
            right.expression.parameters.nonEmpty,
            role,
            left.sourceLocation.orElse(right.sourceLocation)
          )
          None
        } else {
          val location = left.sourceLocation.orElse(right.sourceLocation)
          val leftValues = activeDomainEvaluations(
            leftDomain,
            role,
            location
          )
          val rightValues = activeDomainEvaluations(
            rightDomain,
            role,
            location
          ).toMap
          Some(
            checkedDerivedDomain(
              leftDomain,
              leftValues.map { case (rootValue, leftValue) =>
                rootValue -> operation(leftValue, rightValues(rootValue))
              },
              location,
              role
            )
          )
        }
      case (Some(domain), None) if right.expression.parameters.isEmpty =>
        val location = left.sourceLocation.orElse(right.sourceLocation)
        val evaluations = activeDomainEvaluations(
          domain,
          role,
          location
        ).map { case (rootValue, leftValue) =>
          rootValue -> operation(leftValue, right.expression.default)
        }
        Some(
          checkedDerivedDomain(
            domain,
            evaluations,
            location,
            role
          )
        )
      case (None, Some(domain)) if left.expression.parameters.isEmpty =>
        val location = left.sourceLocation.orElse(right.sourceLocation)
        val evaluations = activeDomainEvaluations(
          domain,
          role,
          location
        ).map { case (rootValue, rightValue) =>
          rootValue -> operation(left.expression.default, rightValue)
        }
        Some(
          checkedDerivedDomain(
            domain,
            evaluations,
            location,
            role
          )
        )
      case _ =>
        requireNoLostExactCorrelation(
          left.expression.exactDomain,
          right.expression.exactDomain,
          left.expression.parameters.nonEmpty,
          right.expression.parameters.nonEmpty,
          role,
          left.sourceLocation.orElse(right.sourceLocation)
        )
        None
    }

  /** Exact integer results must remain representable by emitted Verilog integers. */
  private[core] def checkedIntegerDerivedDomain(
      source: ElaborationExactDomain[_],
      evaluations: Vector[(BigInt, BigInt)],
      sourceLocation: Option[String],
      role: String
  ): ElaborationExactDomain[BigInt] = {
    val domain = checkedDerivedDomain(source, evaluations, sourceLocation, role)
    requireIntegerResultsInRange(domain, role, sourceLocation)
    domain
  }

  private def requireIntegerResultsInRange(
      domain: ElaborationExactDomain[BigInt],
      role: String,
      sourceLocation: Option[String]
  ): Unit =
    domain.evaluations
      .collectFirst {
        case (rootValue, result) if result == null || !result.isValidInt =>
          rootValue -> result
      }
      .foreach { case (rootValue, result) =>
        fail(
          "SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-OUT-OF-RANGE",
          s"$role evaluates to $result at ${domain.parameter.name}=$rootValue, outside the Scala/Verilog integer domain",
          sourceLocation.orElse(domain.root.sourceLocation)
        )
      }

  /** A narrowed exact carrier must never be silently downgraded when another
    * declaration root makes exact correlation impossible.
    */
  private[core] def requireNoLostPartialCorrelation(
      left: Option[ElaborationExactDomain[_]],
      right: Option[ElaborationExactDomain[_]],
      role: String,
      sourceLocation: Option[String]
  ): Unit = {
    val domains = Vector(left, right).flatten
    domains
      .find { domain =>
        domain.evidenceValues != domain.universe ||
        ElaborationDomainContext.constrains(domain.root)
      }
      .foreach { domain =>
        fail(
          "SPINAL-ELAB-DOMAIN-PARTIAL-CORRELATION-UNSUPPORTED",
          s"$role cannot combine branch-narrowed evidence for '${domain.parameter.name}' with an independently sourced expression",
          sourceLocation.orElse(domain.root.sourceLocation)
        )
      }
  }

  /** Exact single-root evidence must never be downgraded by a binary operation. */
  private[core] def requireNoLostExactCorrelation(
      left: Option[ElaborationExactDomain[_]],
      right: Option[ElaborationExactDomain[_]],
      leftSymbolic: Boolean,
      rightSymbolic: Boolean,
      role: String,
      sourceLocation: Option[String]
  ): Unit = {
    requireNoLostPartialCorrelation(left, right, role, sourceLocation)
    val unsupported = (left, right) match {
      case (Some(l), Some(r)) =>
        (l.root ne r.root) || l.universe != r.universe
      case (Some(_), None) => rightSymbolic
      case (None, Some(_)) => leftSymbolic
      case (None, None)    => false
    }
    if (unsupported) {
      val names = Vector(left, right).flatten.map(_.parameter.name).distinct
      fail(
        "SPINAL-ELAB-DOMAIN-EXACT-CORRELATION-UNSUPPORTED",
        s"$role cannot correlate exact typed evidence${if (names.nonEmpty) s" for ${names.mkString(", ")}" else ""} with an independent symbolic expression",
        sourceLocation.orElse(Vector(left, right).flatten.iterator.flatMap(_.root.sourceLocation).toVector.headOption)
      )
    }
  }

  /** Select only values which are both admitted and covered at this site. */
  private[core] def activeDomainEvaluations[A](
      domain: ElaborationExactDomain[A],
      role: String,
      sourceLocation: Option[String]
  ): Vector[(BigInt, A)] = {
    val admitted = ElaborationDomainContext.requireEvidence(
      domain,
      role,
      sourceLocation
    )
    if (admitted.isEmpty) {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-EMPTY",
        s"$role for '${domain.parameter.name}' has no value in the active branch",
        sourceLocation.orElse(domain.root.sourceLocation)
      )
    }
    domain.evaluations.filter { case (rootValue, _) =>
      admitted.contains(rootValue)
    }
  }

  /** Preserve whether derived exact evidence covers the full or active domain. */
  private[core] def checkedDerivedDomain[A](
      source: ElaborationExactDomain[_],
      evaluations: Vector[(BigInt, A)],
      sourceLocation: Option[String],
      role: String
  ): ElaborationExactDomain[A] = {
    if (!source.root.isAuthoritativeSchema(source.parameter)) {
      fail(
        "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING",
        s"$role cannot derive exact evidence from an unbound or replacement declaration schema",
        sourceLocation.orElse(source.root.sourceLocation)
      )
    }
    val covered = evaluations.map(_._1).toSet
    if (covered == source.universe) {
      ElaborationExactDomain.checked(
        source.root,
        source.parameter,
        evaluations,
        sourceLocation,
        role
      )
    } else {
      ElaborationExactDomain.checkedPartial(
        source.root,
        source.parameter,
        evaluations,
        sourceLocation,
        role
      )
    }
  }

  private[core] def mergeParameters(
      left: Vector[ElaborationIntegerParameter],
      right: Vector[ElaborationIntegerParameter],
      sourceLocation: Option[String]
  ): Vector[ElaborationIntegerParameter] = {
    if (left == null || right == null) {
      fail(
        "SPINAL-ELAB-INT-PARAMETER-SCHEMA-NULL",
        "typed elaboration parameter collection must not be null",
        sourceLocation
      )
    }
    val values = left ++ right
    validateParameterSchemas(values, sourceLocation)
    values.groupBy(_.name).foreach { case (name, schemas) =>
      if (schemas.distinct.size != 1) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-SCHEMA-CONFLICT",
          s"parameter '$name' has conflicting typed elaboration declarations",
          sourceLocation
        )
      }
    }
    values.groupBy(_.name).toVector.map(_._2.head).sortBy(_.name)
  }

  private def validateParameterSchemas(
      parameters: Vector[ElaborationIntegerParameter],
      sourceLocation: Option[String]
  ): Unit = {
    val portableIdentifier = "[A-Za-z_][A-Za-z0-9_]*".r
    parameters.zipWithIndex.foreach { case (parameter, index) =>
      if (parameter == null) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-SCHEMA-NULL",
          s"typed elaboration parameter schema at index $index must not be null",
          sourceLocation
        )
      }
      if (
        parameter.name == null ||
        !portableIdentifier.pattern.matcher(parameter.name).matches()
      ) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-NAME-INVALID",
          s"typed elaboration parameter name '${parameter.name}' is not a portable Verilog identifier",
          sourceLocation
        )
      }
      if (
        parameter.default == null ||
        parameter.minimum == null ||
        parameter.maximum == null ||
        !parameter.default.isValidInt ||
        parameter.minimum > parameter.maximum ||
        parameter.default < parameter.minimum ||
        parameter.default > parameter.maximum
      ) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-DOMAIN-INVALID",
          s"typed elaboration parameter '${parameter.name}' must have an Int-sized default inside its non-empty bounded domain [${parameter.minimum}, ${parameter.maximum}], received ${parameter.default}",
          sourceLocation
        )
      }
    }
  }

  private[core] def equivalentExpression(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean =
    left.verilog == right.verilog &&
      left.default == right.default &&
      left.minimum == right.minimum &&
      left.maximum == right.maximum &&
      left.parameters == right.parameters &&
      left.generateIndex == right.generateIndex &&
      sameParameterRoots(left.parameterRoots, right.parameterRoots) &&
      sameExactDomain(left.exactDomain, right.exactDomain) &&
      sameProjection(left.projectionProvenance, right.projectionProvenance)

  /** Exhaustive same-root value-function equivalence.
    *
    * This deliberately ignores only authored rendering.  Parameter schemas,
    * declaration-root identities, generate-index context, summaries and every
    * exact-domain evaluation must still agree.  Partial evidence additionally
    * requires the same live projection provenance; a complete domain may
    * compare an unprojected expression with an explicitly full-domain
    * projection because neither narrows the admitted root values.
    */
  private[core] def equivalentExactFunction(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean = {
    if (left == null || right == null) return false

    val sameSummary =
      left.default == right.default &&
        left.minimum == right.minimum &&
        left.maximum == right.maximum &&
        left.parameters == right.parameters &&
        left.generateIndex == right.generateIndex &&
        sameParameterRoots(
          left.completedParameterRoots,
          right.completedParameterRoots
        )
    if (!sameSummary) return false

    (left.exactDomain, right.exactDomain) match {
      case (Some(l), Some(r))
          if (l.root eq r.root) &&
            l.parameter == r.parameter &&
            l.universe == r.universe &&
            l.evaluations == r.evaluations =>
        val complete =
          l.evidenceValues == l.universe &&
            r.evidenceValues == r.universe
        val exactProjection =
          (left.projectionProvenance, right.projectionProvenance) match {
            case (Some(a), Some(b)) => a.sameAs(b)
            case (None, None)       => complete
            case _                  => false
          }
        val redundantFullProjection = complete &&
          nonNarrowingProjection(left.projectionProvenance, l) &&
          nonNarrowingProjection(right.projectionProvenance, r)
        exactProjection || redundantFullProjection

      // Parameter-free expressions and legacy values without exact evidence
      // retain the stricter authored-identity rule.
      case (None, None) => equivalentExpression(left, right)
      case _            => false
    }
  }

  private def sameExactDomain[A](
      left: Option[ElaborationExactDomain[A]],
      right: Option[ElaborationExactDomain[A]]
  ): Boolean =
    (left, right) match {
      case (None, None) => true
      case (Some(l), Some(r)) =>
        (l.root eq r.root) &&
        l.parameter == r.parameter &&
        l.universe == r.universe &&
        l.evaluations == r.evaluations
      case _ => false
    }

  private[core] def requireProjectionSubset[A](
      existing: Option[ElaborationProjectionProvenance],
      domain: ElaborationExactDomain[A],
      admitted: Set[BigInt],
      role: String,
      sourceLocation: Option[String]
  ): Unit = {
    if (
      domain.evidenceValues != domain.universe &&
      existing.isEmpty
    ) {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-IDENTITY-MISSING",
        s"$role carries branch-partial exact evidence without its original projection authority",
        sourceLocation.orElse(domain.root.sourceLocation)
      )
    }
    existing.foreach { projection =>
      if (
        (projection.root ne domain.root) ||
        !admitted.subsetOf(projection.admitted)
      ) {
        fail(
          "SPINAL-ELAB-DOMAIN-PROJECTION-SCOPE-EXPANSION",
          s"$role cannot expand branch projection from ${projection.admitted.toVector.sorted
              .mkString(", ")} to ${admitted.toVector.sorted.mkString(", ")}",
          sourceLocation.orElse(projection.root.sourceLocation)
        )
      }
    }
  }

  private def sameProjection(
      left: Option[ElaborationProjectionProvenance],
      right: Option[ElaborationProjectionProvenance]
  ): Boolean =
    (left, right) match {
      case (None, None)                      => true
      case (Some(l), Some(r))                => l.sameAs(r)
      case (Some(_), None) | (None, Some(_)) => false
    }

  private def nonNarrowingProjection[A](
      projection: Option[ElaborationProjectionProvenance],
      domain: ElaborationExactDomain[A]
  ): Boolean =
    projection.forall(value => (value.root eq domain.root) && value.admitted == domain.universe)

  private[core] def withCompleteParameterRoots(
      expression: ElaborationIntegerExpression
  ): ElaborationIntegerExpression = {
    val completed = expression.completedParameterRoots
    if (completed == expression.parameterRoots) expression
    else
      expression.preserveExactAuthorityOn(
        expression.preserveProjectionOn(
          expression.copy(parameterRoots = completed),
          "integer parameter-root normalization"
        ),
        "integer parameter-root normalization"
      )
  }

  private[core] def withCompleteParameterRoots(
      expression: ElaborationBooleanExpression
  ): ElaborationBooleanExpression = {
    val completed = expression.completedParameterRoots
    if (completed == expression.parameterRoots) expression
    else
      expression.preserveExactAuthorityOn(
        expression.preserveProjectionOn(
          expression.copy(parameterRoots = completed),
          "Boolean parameter-root normalization"
        ),
        "Boolean parameter-root normalization"
      )
  }

  private[core] def mergeParameterRoots(
      left: Vector[ElaborationIntegerParameterRoot],
      right: Vector[ElaborationIntegerParameterRoot]
  ): Vector[ElaborationIntegerParameterRoot] =
    distinctParameterRoots(left ++ right)

  /** Fail closed when one emitted parameter name denotes multiple declarations. */
  private[spinal] def validateParameterRootInventory(
      role: String,
      expressions: Vector[ElaborationIntegerExpression]
  ): Unit = {
    if (expressions == null) {
      fail(
        "SPINAL-ELAB-INT-PARAMETER-ROOT-NULL",
        s"$role must retain a non-null expression inventory",
        None
      )
    }
    val associated = expressions.flatMap { expression =>
      validateExpression(expression, role)
      expression.completedParameterRoots.map(root => expression -> root)
    }
    associated
      .groupBy(_._2.name)
      .collectFirst {
        case (name, values) if distinctParameterRoots(values.map(_._2)).size > 1 =>
          name -> values
      }
      .foreach { case (name, values) =>
        fail(
          "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
          s"$role combines independently sourced declarations for parameter '$name'",
          values.iterator
            .flatMap { case (expression, root) =>
              root.sourceLocation.orElse(expression.sourceLocation)
            }
            .toVector
            .headOption
        )
      }
  }

  private def distinctParameterRoots(
      roots: Vector[ElaborationIntegerParameterRoot]
  ): Vector[ElaborationIntegerParameterRoot] =
    roots.foldLeft(Vector.empty[ElaborationIntegerParameterRoot]) {
      case (known, root) if known.exists(_ eq root) => known
      case (known, root)                            => known :+ root
    }

  private def sameParameterRoots(
      left: Vector[ElaborationIntegerParameterRoot],
      right: Vector[ElaborationIntegerParameterRoot]
  ): Boolean = {
    val leftDistinct = distinctParameterRoots(left)
    val rightDistinct = distinctParameterRoots(right)
    leftDistinct.size == rightDistinct.size &&
    leftDistinct.forall(root => rightDistinct.exists(_ eq root))
  }

  private[core] def validateExpression(
      expression: ElaborationIntegerExpression,
      role: String
  ): Unit = {
    if (expression == null)
      throw new IllegalArgumentException(s"$role must not be null")
    if (
      expression.sourceLocation == null ||
      expression.sourceLocation.exists(_ == null)
    ) {
      fail(
        "SPINAL-ELAB-INT-SOURCE-OPTION-NULL",
        s"$role must retain a non-null source-location option",
        None
      )
    }
    if (
      expression.generateIndex == null ||
      expression.generateIndex.exists(_ == null)
    ) {
      fail(
        "SPINAL-ELAB-INT-GENERATE-INDEX-OPTION-NULL",
        s"$role must retain a non-null generate-index option",
        expression.sourceLocation
      )
    }
    if (expression.verilog == null || expression.verilog.trim.isEmpty) {
      fail(
        "SPINAL-ELAB-INT-EXPRESSION-INVALID",
        s"$role must retain a non-empty Verilog expression",
        expression.sourceLocation
      )
    }
    if (
      expression.default == null ||
      expression.minimum == null ||
      expression.maximum == null ||
      !expression.default.isValidInt ||
      expression.minimum > expression.maximum ||
      expression.default < expression.minimum ||
      expression.default > expression.maximum
    ) {
      fail(
        "SPINAL-ELAB-INT-DOMAIN-INVALID",
        s"$role '${expression.verilog}' has default ${expression.default} outside [${expression.minimum}, ${expression.maximum}] or outside Scala Int",
        expression.sourceLocation
      )
    }
    if (expression.parameters == null) {
      fail(
        "SPINAL-ELAB-INT-PARAMETER-SCHEMA-NULL",
        s"$role '${expression.verilog}' must retain a non-null parameter collection",
        expression.sourceLocation
      )
    }
    if (expression.parameterRoots == null) {
      fail(
        "SPINAL-ELAB-INT-PARAMETER-ROOT-NULL",
        s"$role '${expression.verilog}' must retain a non-null parameter-root collection",
        expression.sourceLocation
      )
    }
    if (expression.exactDomain == null) {
      fail(
        "SPINAL-ELAB-DOMAIN-EVIDENCE-OPTION-NULL",
        s"$role '${expression.verilog}' must retain a non-null exact-domain option",
        expression.sourceLocation
      )
    }
    if (expression.exactDomain.nonEmpty && !expression.hasExactAuthority) {
      fail(
        "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-MISSING",
        s"$role '${expression.verilog}' carries copied or unauthenticated exact-domain evidence",
        expression.sourceLocation
      )
    }
    mergeParameters(expression.parameters, Vector.empty, expression.sourceLocation)
    validateParameterRoots(
      expression.verilog,
      expression.parameters,
      expression.parameterRoots,
      expression.sourceLocation,
      role
    )
    expression.exactDomain.foreach { domain =>
      if (!expression.completedParameterRoots.exists(_ eq domain.root)) {
        fail(
          "SPINAL-ELAB-DOMAIN-ROOT-IDENTITY-MISMATCH",
          s"$role '${expression.verilog}' exact evidence belongs to a foreign declaration root",
          expression.sourceLocation.orElse(domain.root.sourceLocation)
        )
      }
    }
  }

  private def validateParameterRoots(
      verilog: String,
      parameters: Vector[ElaborationIntegerParameter],
      roots: Vector[ElaborationIntegerParameterRoot],
      sourceLocation: Option[String],
      role: String
  ): Unit = {
    roots.foreach { root =>
      if (root == null) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-ROOT-NULL",
          s"$role '$verilog' carries a null parameter root",
          sourceLocation
        )
      }
      if (
        root.sourceLocation == null ||
        root.sourceLocation.exists(_ == null)
      ) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-ROOT-SOURCE-OPTION-NULL",
          s"$role '$verilog' carries a parameter root with a null source-location option",
          sourceLocation
        )
      }
      if (!parameters.exists(_.name == root.name)) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-ROOT-UNKNOWN",
          s"$role '$verilog' carries provenance for unknown parameter '${root.name}'",
          root.sourceLocation.orElse(sourceLocation)
        )
      }
    }
    val distinctRoots = distinctParameterRoots(roots)
    distinctRoots
      .groupBy(_.name)
      .collectFirst {
        case (name, declarations) if declarations.size > 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
          s"$role '$verilog' combines independently sourced declarations for parameter '$name'",
          distinctRoots
            .filter(_.name == name)
            .flatMap(_.sourceLocation)
            .headOption
            .orElse(sourceLocation)
        )
      }
  }

  private[core] def validateExpression(
      expression: ElaborationBooleanExpression,
      role: String
  ): Unit = {
    if (expression == null)
      throw new IllegalArgumentException(s"$role must not be null")
    if (
      expression.sourceLocation == null ||
      expression.sourceLocation.exists(_ == null)
    ) {
      fail(
        "SPINAL-ELAB-BOOL-SOURCE-OPTION-NULL",
        s"$role must retain a non-null source-location option",
        None
      )
    }
    if (expression.verilog == null || expression.verilog.trim.isEmpty) {
      fail(
        "SPINAL-ELAB-BOOL-EXPRESSION-INVALID",
        s"$role must retain a non-empty Verilog expression",
        expression.sourceLocation
      )
    }
    if (expression.parameters == null) {
      fail(
        "SPINAL-ELAB-INT-PARAMETER-SCHEMA-NULL",
        s"$role '${expression.verilog}' must retain a non-null parameter collection",
        expression.sourceLocation
      )
    }
    if (expression.parameterRoots == null) {
      fail(
        "SPINAL-ELAB-INT-PARAMETER-ROOT-NULL",
        s"$role '${expression.verilog}' must retain a non-null parameter-root collection",
        expression.sourceLocation
      )
    }
    if (expression.exactDomain == null) {
      fail(
        "SPINAL-ELAB-DOMAIN-EVIDENCE-OPTION-NULL",
        s"$role '${expression.verilog}' must retain a non-null exact-domain option",
        expression.sourceLocation
      )
    }
    if (expression.exactDomain.nonEmpty && !expression.hasExactAuthority) {
      fail(
        "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-MISSING",
        s"$role '${expression.verilog}' carries copied or unauthenticated exact-domain evidence",
        expression.sourceLocation
      )
    }
    mergeParameters(expression.parameters, Vector.empty, expression.sourceLocation)
    validateParameterRoots(
      expression.verilog,
      expression.parameters,
      expression.parameterRoots,
      expression.sourceLocation,
      role
    )
    expression.exactDomain.foreach { domain =>
      if (!expression.completedParameterRoots.exists(_ eq domain.root)) {
        fail(
          "SPINAL-ELAB-DOMAIN-ROOT-IDENTITY-MISMATCH",
          s"$role '${expression.verilog}' exact evidence belongs to a foreign declaration root",
          expression.sourceLocation.orElse(domain.root.sourceLocation)
        )
      }
    }
  }

  private[core] def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing = ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
