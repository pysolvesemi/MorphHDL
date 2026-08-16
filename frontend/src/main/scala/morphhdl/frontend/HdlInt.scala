package morphhdl.frontend

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.BoolExpr.{
  Equal => BoolEqual,
  GreaterThan,
  GreaterThanOrEqual,
  LessThan,
  LessThanOrEqual,
  NotEqual
}
import morphhdl.paramrtl.IntExpr.{
  Add,
  AddressWidth,
  CeilLog2,
  Divide,
  Literal,
  LocalParameterRef,
  Max,
  Min,
  Modulo,
  Multiply,
  Negate,
  ParameterRef,
  Select,
  Subtract
}
import morphhdl.paramrtl.{IntConstraint, IntExpr, IntegerLocalParameter, IntegerParameter}
import spinal.core.{ElaborationIntegerParameter, ParameterizedBitCount}

final class HdlInt private[frontend] (
    private[frontend] val witness: BigInt,
    private[frontend] val expression: IntExpr,
    private[frontend] val declaration: Option[ParameterToken],
    private[frontend] val parameters: Set[ParameterToken],
    private[frontend] val booleanParameters: Set[BooleanParameterToken],
    private[frontend] val localDeclaration: Option[LocalParameterToken],
    private[frontend] val localParameters: Set[LocalParameterToken],
    private[frontend] val booleanLocalParameters: Set[BooleanLocalParameterToken],
    private[frontend] val scope: Option[ScopeToken],
    private[frontend] val origin: SourceOrigin
) extends scala.math.ScalaNumber {
  /**
    * Retain a direct public integer parameter while supplying its concrete
    * default to SpinalHDL's ordinary width inference and validation phases.
    *
    * An `HdlInt.literal` remains an ordinary untagged concrete width. For
    * symbolic data shapes this deliberately accepts only an unmodified
    * `HdlInt.param`; expression propagation remains fail-closed until the
    * generic expression increment.
    */
  private[frontend] def toParameterizedBitCount(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): ParameterizedBitCount = {
    val useOrigin = SourceOrigin.capture
    requireLoopInvariant("SpinalHDL bit-vector width")

    (expression, declaration) match {
      case (Literal(value), None) =>
        if (
          value != witness || parameters.nonEmpty || booleanParameters.nonEmpty ||
          localDeclaration.nonEmpty || localParameters.nonEmpty ||
          booleanLocalParameters.nonEmpty || scope.nonEmpty
        ) {
          FrontendException.failAt(
            "MORPH-FRONTEND-SPINAL-WIDTH-PROVENANCE-UNSUPPORTED",
            "the literal width carries unsupported or ambiguous symbolic provenance",
            useOrigin
          )
        }
        if (value < 1) {
          FrontendException.failAt(
            "MORPH-FRONTEND-SPINAL-WIDTH-DOMAIN-NONPOSITIVE",
            s"literal SpinalHDL width must be at least 1, but found $value",
            useOrigin
          )
        }
        if (value > BigInt(Int.MaxValue)) {
          FrontendException.failAt(
            "MORPH-FRONTEND-SPINAL-WIDTH-DOMAIN-TOO-LARGE",
            s"literal SpinalHDL width $value is outside SpinalHDL's Int-sized width domain",
            useOrigin
          )
        }
        return ParameterizedBitCount(
          value.toInt,
          parameter = None,
          sourceLocation = Some(useOrigin.rendered)
        )
      case _ =>
    }

    val token = (expression, declaration) match {
      case (ParameterRef(name), Some(value)) if value.declaration.name == name => value
      case _ =>
        FrontendException.failAt(
          "MORPH-FRONTEND-SPINAL-WIDTH-NOT-DIRECT-PARAMETER",
          "the SpinalHDL symbolic-width bridge accepts only an unmodified HdlInt.param value",
          useOrigin
        )
    }

    if (
      parameters.size != 1 || !parameters.exists(_ eq token) ||
      booleanParameters.nonEmpty || localDeclaration.nonEmpty ||
      localParameters.nonEmpty || booleanLocalParameters.nonEmpty || scope.nonEmpty
    ) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SPINAL-WIDTH-PROVENANCE-UNSUPPORTED",
        "the direct width parameter carries unsupported or ambiguous symbolic provenance",
        useOrigin
      )
    }

    val parameter = token.declaration
    val minimums = parameter.constraints.collect { case MinInclusive(value) => value }
    val maximums = parameter.constraints.collect { case MaxInclusive(value) => value }
    if (minimums.isEmpty || maximums.isEmpty) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SPINAL-WIDTH-DOMAIN-UNBOUNDED",
        s"parameter '${parameter.name}' must declare finite minimum and maximum width bounds",
        useOrigin
      )
    }

    val minimum = minimums.max
    val maximum = maximums.min
    if (minimum < 1 || maximum < minimum) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SPINAL-WIDTH-DOMAIN-NONPOSITIVE",
        s"parameter '${parameter.name}' must have a non-empty width domain whose minimum is at least 1",
        useOrigin
      )
    }
    if (maximum > BigInt(Int.MaxValue)) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SPINAL-WIDTH-DOMAIN-TOO-LARGE",
        s"parameter '${parameter.name}' has maximum $maximum outside SpinalHDL's Int-sized width domain",
        useOrigin
      )
    }
    if (parameter.default < minimum || parameter.default > maximum || witness != parameter.default) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SPINAL-WIDTH-DEFAULT-INVALID",
        s"parameter '${parameter.name}' default ${parameter.default} must be its concrete witness and lie in [$minimum, $maximum]",
        useOrigin
      )
    }
    if (
      parameter.name == null ||
      !HdlInt.PortableIdentifier.pattern.matcher(parameter.name).matches()
    ) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SPINAL-WIDTH-NAME-INVALID",
        s"parameter name '${parameter.name}' is not a portable Verilog identifier",
        useOrigin
      )
    }

    ParameterizedBitCount(
      parameter.default.toInt,
      parameter = Some(
        ElaborationIntegerParameter(parameter.name, parameter.default, minimum, maximum)
      ),
      sourceLocation = Some(useOrigin.rendered)
    )
  }

  def +(that: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt =
    binary(that, "integer addition", Add.apply)(_ + _)

  def -(that: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt =
    binary(that, "integer subtraction", Subtract.apply)(_ - _)

  def *(that: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt = {
    binary(that, "integer multiplication", Multiply.apply)(_ * _)
  }

  def /(that: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt = {
    val resultOrigin = SourceOrigin.capture
    binaryAt(
      that,
      "integer division",
      Divide.apply,
      resultOrigin,
      zeroDivisorRole = Some("division")
    )(_ / _)
  }

  def %(that: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt = {
    val resultOrigin = SourceOrigin.capture
    binaryAt(
      that,
      "integer remainder",
      Modulo.apply,
      resultOrigin,
      zeroDivisorRole = Some("remainder")
    )(_ % _)
  }

  /** Mathematical minimum retained as an elaboration-time integer expression. */
  def min(that: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt =
    binary(that, "integer minimum", Min.apply)(_.min(_))

  /** Mathematical maximum retained as an elaboration-time integer expression. */
  def max(that: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt =
    binary(that, "integer maximum", Max.apply)(_.max(_))

  def <(that: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlBool =
    comparison(that, "integer less-than comparison", LessThan.apply)(_ < _)

  def <=(that: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlBool =
    comparison(that, "integer less-than-or-equal comparison", LessThanOrEqual.apply)(_ <= _)

  def >(that: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlBool =
    comparison(that, "integer greater-than comparison", GreaterThan.apply)(_ > _)

  def >=(that: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlBool =
    comparison(that, "integer greater-than-or-equal comparison", GreaterThanOrEqual.apply)(_ >= _)

  /** Symbolic equality; Scala `==` intentionally remains fail-closed. */
  def hdlEq(that: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlBool =
    comparison(that, "integer equality comparison", BoolEqual.apply)(_ == _)

  /** Symbolic inequality; Scala `!=` intentionally remains fail-closed. */
  def hdlNe(that: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlBool =
    comparison(that, "integer inequality comparison", NotEqual.apply)(_ != _)

  def unary_-(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt = {
    requireUsable("integer negation")
    val resultOrigin = SourceOrigin.capture
    new HdlInt(
      -witness,
      Negate(expression),
      declaration = None,
      parameters = parameters,
      booleanParameters = booleanParameters,
      localDeclaration = None,
      localParameters = localParameters,
      booleanLocalParameters = booleanLocalParameters,
      scope = scope,
      origin = resultOrigin
    )
  }

  /**
    * Returns the minimum packed width that can address every element of this
    * positive size, while retaining the size as a symbolic ParamRTL
    * expression. A size of one deliberately has an address width of one.
    */
  def addressWidth(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt = {
    val resultOrigin = SourceOrigin.capture

    // Check structural safety before inspecting the concrete witness. This
    // keeps a loop-variant value from being accepted merely because the
    // current elaboration iteration happens to carry a positive witness.
    requireLoopInvariant("address-width computation")
    if (witness <= 0) {
      FrontendException.failAt(
        "MORPH-FRONTEND-ADDRESS-WIDTH-WITNESS-NONPOSITIVE",
        s"addressWidth requires a positive concrete witness, but found $witness",
        resultOrigin
      )
    }

    new HdlInt(
      BigInt(math.max(1, (witness - 1).bitLength)),
      AddressWidth(expression),
      declaration = None,
      parameters = parameters,
      booleanParameters = booleanParameters,
      localDeclaration = None,
      localParameters = localParameters,
      booleanLocalParameters = booleanLocalParameters,
      scope = scope,
      origin = resultOrigin
    )
  }

  /**
    * Returns the mathematical ceiling of log base two while retaining this
    * positive value as a symbolic ParamRTL expression. In contrast to
    * `addressWidth`, `ceilLog2(1)` is zero.
    */
  def ceilLog2(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt = {
    val resultOrigin = SourceOrigin.capture

    // Check structural safety before inspecting the concrete witness. This
    // keeps a loop-variant value from being accepted merely because the
    // current elaboration iteration happens to carry a positive witness.
    requireLoopInvariant("ceiling-log2 computation")
    if (witness <= 0) {
      FrontendException.failAt(
        "MORPH-FRONTEND-CEIL-LOG2-WITNESS-NONPOSITIVE",
        s"ceilLog2 requires a positive concrete witness, but found $witness",
        resultOrigin
      )
    }

    new HdlInt(
      BigInt((witness - 1).bitLength),
      CeilLog2(expression),
      declaration = None,
      parameters = parameters,
      booleanParameters = booleanParameters,
      localDeclaration = None,
      localParameters = localParameters,
      booleanLocalParameters = booleanLocalParameters,
      scope = scope,
      origin = resultOrigin
    )
  }

  private def binary(
      that: HdlInt,
      consumer: String,
      operation: (IntExpr, IntExpr) => IntExpr
  )(witnessOperation: (BigInt, BigInt) => BigInt)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlInt =
    binaryAt(that, consumer, operation, SourceOrigin.capture)(witnessOperation)

  private def comparison(
      that: HdlInt,
      consumer: String,
      operation: (IntExpr, IntExpr) => morphhdl.paramrtl.BoolExpr
  )(witnessOperation: (BigInt, BigInt) => Boolean)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlBool = {
    val resultOrigin = SourceOrigin.capture
    requireLoopInvariant(consumer)
    that.requireLoopInvariant(consumer)
    HdlBool.comparison(
      witnessOperation(witness, that.witness),
      operation(expression, that.expression),
      parameters ++ that.parameters,
      booleanParameters ++ that.booleanParameters,
      localParameters ++ that.localParameters,
      booleanLocalParameters ++ that.booleanLocalParameters,
      resultOrigin
    )
  }

  private def binaryAt(
      that: HdlInt,
      consumer: String,
      operation: (IntExpr, IntExpr) => IntExpr,
      resultOrigin: SourceOrigin,
      zeroDivisorRole: Option[String] = None
  )(witnessOperation: (BigInt, BigInt) => BigInt): HdlInt = {
    requireUsable(consumer)
    that.requireUsable(consumer)
    val resultScope = HdlInt.mergeScopes(scope, that.scope, resultOrigin)
    zeroDivisorRole.foreach { role =>
      if (that.witness == 0) {
        FrontendException.failAt(
          "MORPH-FRONTEND-DIVISOR-WITNESS-ZERO",
          s"integer $role has a zero concrete witness divisor",
          resultOrigin
        )
      }
    }
    new HdlInt(
      witnessOperation(witness, that.witness),
      operation(expression, that.expression),
      declaration = None,
      parameters = parameters ++ that.parameters,
      booleanParameters = booleanParameters ++ that.booleanParameters,
      localDeclaration = None,
      localParameters = localParameters ++ that.localParameters,
      booleanLocalParameters = booleanLocalParameters ++ that.booleanLocalParameters,
      scope = resultScope,
      origin = resultOrigin
    )
  }

  private[frontend] def requireUsable(consumer: String): Unit =
    scope.foreach(FrontendSession.requireActiveScope(_, consumer, origin))

  private[frontend] def requireLoopInvariant(consumer: String): Unit = {
    requireUsable(consumer)
    if (scope.nonEmpty) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENINDEX-CONSUMER-UNSUPPORTED",
        s"$consumer cannot depend on a generate index in the current frontend surface",
        origin
      )
    }
  }

  override def equals(that: Any): Boolean = {
    requireUsable("symbolic comparison")
    FrontendException.failAt(
      "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED",
      s"symbolic integer expression '$expression' cannot be compared with ${HdlInt.describe(that)}",
      origin
    )
  }

  override def hashCode: Int = {
    requireUsable("symbolic hashing")
    FrontendException.failAt(
      "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED",
      s"symbolic integer expression '$expression' cannot be hashed by Scala",
      origin
    )
  }

  override def intValue(): Int = conversionFailure("Int")
  override def longValue(): Long = conversionFailure("Long")
  override def floatValue(): Float = conversionFailure("Float")
  override def doubleValue(): Double = conversionFailure("Double")
  override def isWhole(): Boolean = true
  override def underlying(): Object = this

  private def conversionFailure[A](target: String): A = {
    requireUsable(s"conversion to Scala $target")
    FrontendException.failAt(
      "MORPH-FRONTEND-SYMBOLIC-CONVERSION-UNSUPPORTED",
      s"symbolic integer expression '$expression' cannot be converted to Scala $target",
      origin
    )
  }

  override def toString: String = "HdlInt(<dual-valued>)"
}

object HdlInt {
  private val PortableIdentifier = "[A-Za-z_][A-Za-z0-9_]*".r

  /**
    * Adds SpinalHDL's ordinary `width bits` spelling only to an actual
    * `HdlInt`. Keeping this syntax in the receiver type's implicit scope
    * prevents the existing `Int => HdlInt` conversion from competing with
    * SpinalHDL's single-step `Int => IntBuilder` conversion for expressions
    * such as `UInt(8 bits)`.
    */
  implicit final class HdlIntBitCountOps(private val value: HdlInt) extends AnyVal {
    def bits(implicit
        file: sourcecode.File,
        line: sourcecode.Line
    ): ParameterizedBitCount =
      value.toParameterizedBitCount(file, line)
  }

  def literal(value: BigInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt =
    new HdlInt(
      value,
      Literal(value),
      declaration = None,
      parameters = Set.empty,
      booleanParameters = Set.empty,
      localDeclaration = None,
      localParameters = Set.empty,
      booleanLocalParameters = Set.empty,
      scope = None,
      origin = SourceOrigin.capture
    )

  private[frontend] def literalAt(
      value: BigInt,
      origin: SourceOrigin
  ): HdlInt =
    new HdlInt(
      value,
      Literal(value),
      declaration = None,
      parameters = Set.empty,
      booleanParameters = Set.empty,
      localDeclaration = None,
      localParameters = Set.empty,
      booleanLocalParameters = Set.empty,
      scope = None,
      origin = origin
    )

  def param(
      name: String,
      default: BigInt,
      min: BigInt,
      max: BigInt = BigInt(Int.MaxValue)
  )(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt = {
    val declaration = IntegerParameter(
      name,
      default,
      Vector[IntConstraint](MinInclusive(min), MaxInclusive(max))
    )
    val token = new ParameterToken(declaration, SourceOrigin.capture)
    new HdlInt(
      default,
      ParameterRef(name),
      declaration = Some(token),
      parameters = Set(token),
      booleanParameters = Set.empty,
      localDeclaration = None,
      localParameters = Set.empty,
      booleanLocalParameters = Set.empty,
      scope = None,
      origin = token.origin
    )
  }

  private[frontend] def select(
      condition: HdlBool,
      whenTrue: HdlInt,
      whenFalse: HdlInt,
      origin: SourceOrigin
  ): HdlInt = {
    if (whenTrue eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-INTEGER-SELECT-BRANCH-NULL",
        "integer selection true branch must not be null",
        origin
      )
    }
    if (whenFalse eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-INTEGER-SELECT-BRANCH-NULL",
        "integer selection false branch must not be null",
        origin
      )
    }

    // Deliberately inspect both branches before selecting the concrete witness:
    // an inactive alternative must not hide a loop-variant or escaped index.
    whenTrue.requireLoopInvariant("integer selection true branch")
    whenFalse.requireLoopInvariant("integer selection false branch")

    new HdlInt(
      if (condition.witness) whenTrue.witness else whenFalse.witness,
      Select(
        condition.expression,
        whenTrue.expression,
        whenFalse.expression
      ),
      declaration = None,
      parameters = condition.integerParameters ++ whenTrue.parameters ++ whenFalse.parameters,
      booleanParameters = condition.parameters ++
        whenTrue.booleanParameters ++ whenFalse.booleanParameters,
      localDeclaration = None,
      localParameters = condition.localParameters ++
        whenTrue.localParameters ++ whenFalse.localParameters,
      booleanLocalParameters = condition.booleanLocalParameters ++
        whenTrue.booleanLocalParameters ++ whenFalse.booleanLocalParameters,
      scope = None,
      origin = origin
    )
  }

  private[frontend] def local(
      name: String,
      value: HdlInt,
      origin: SourceOrigin
  ): HdlInt = {
    val token = new LocalParameterToken(
      IntegerLocalParameter(name, value.expression),
      parameters = value.parameters,
      booleanParameters = value.booleanParameters,
      dependencies = value.localParameters ++ value.booleanLocalParameters,
      origin = origin
    )
    new HdlInt(
      value.witness,
      LocalParameterRef(name),
      declaration = None,
      parameters = value.parameters,
      booleanParameters = value.booleanParameters,
      localDeclaration = Some(token),
      localParameters = value.localParameters + token,
      booleanLocalParameters = value.booleanLocalParameters,
      scope = None,
      origin = origin
    )
  }

  private[frontend] def fromGenerateIndex(
      witness: BigInt,
      expression: IntExpr,
      scope: ScopeToken,
      parameters: Set[ParameterToken],
      booleanParameters: Set[BooleanParameterToken],
      localParameters: Set[LocalParameterToken],
      booleanLocalParameters: Set[BooleanLocalParameterToken],
      origin: SourceOrigin
  ): HdlInt =
    new HdlInt(
      witness,
      expression,
      declaration = None,
      parameters = parameters,
      booleanParameters = booleanParameters,
      localDeclaration = None,
      localParameters = localParameters,
      booleanLocalParameters = booleanLocalParameters,
      scope = Some(scope),
      origin = origin
    )

  private[frontend] def mergeScopes(
      left: Option[ScopeToken],
      right: Option[ScopeToken],
      origin: SourceOrigin
  ): Option[ScopeToken] = (left, right) match {
    case (None, value) => value
    case (value, None) => value
    case (Some(x), Some(y)) if x eq y => Some(x)
    case (Some(_), Some(_)) =>
      FrontendException.failAt(
        "MORPH-FRONTEND-CROSS-SCOPE-EXPRESSION",
        "an integer expression cannot combine generate indices from different scopes",
        origin
      )
  }

  private def describe(value: Any): String = value match {
    case _: HdlInt   => "another HdlInt"
    case _: GenIndex => "a GenIndex"
    case null        => "null"
    case other       => s"a ${other.getClass.getName} value"
  }
}
