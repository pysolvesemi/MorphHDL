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
import spinal.core.{
  Component,
  ElaborationIntegerParameter,
  ExternalFormalParameterBinding,
  ExternalFormalParameterRegistry,
  ParameterizedBitCount,
  ParameterizedMemoryDepth
}

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
    private[frontend] val origin: SourceOrigin,
    private[frontend] val formalBinding: Option[ExternalFormalParameterBinding] = None
) extends scala.math.ScalaNumber {

  /** Supply the concrete witness to ordinary SpinalHDL while retaining either
    * a direct parameter or a complete bounded packed-width expression.
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
          booleanLocalParameters.nonEmpty || scope.nonEmpty || formalBinding.nonEmpty
        ) {
          FrontendException.failAt(
            "MORPH-FRONTEND-SPINAL-WIDTH-PROVENANCE-UNSUPPORTED",
            "the literal width carries unsupported or ambiguous symbolic provenance",
            useOrigin
          )
        }
        validateSpinalWidthDomain(value, value, value, "literal SpinalHDL width", useOrigin)
        return ParameterizedBitCount(
          value.toInt,
          parameter = None,
          sourceLocation = Some(useOrigin.rendered)
        )
      case _ =>
    }

    val directToken = (expression, declaration) match {
      case (ParameterRef(name), Some(value)) if value.declaration.name == name =>
        Some(value)
      case _ => None
    }

    directToken match {
      case Some(token)
          if parameters.size == 1 && parameters.exists(_ eq token) &&
            booleanParameters.isEmpty && localDeclaration.isEmpty &&
            localParameters.isEmpty && booleanLocalParameters.isEmpty && scope.isEmpty =>
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
        validateSpinalWidthDomain(
          parameter.default,
          minimum,
          maximum,
          s"parameter '${parameter.name}'",
          useOrigin
        )
        if (parameter.default != witness) {
          FrontendException.failAt(
            "MORPH-FRONTEND-SPINAL-WIDTH-DEFAULT-INVALID",
            s"parameter '${parameter.name}' default ${parameter.default} must equal its concrete witness $witness",
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
        val schema = formalBinding match {
          case Some(binding) => binding.formal
          case None =>
            ElaborationIntegerParameter(
              parameter.name,
              parameter.default,
              minimum,
              maximum
            )
        }
        val width = ParameterizedBitCount(
          parameter.default.toInt,
          parameter = Some(schema),
          sourceLocation = Some(useOrigin.rendered),
          expression = Some(
            spinal.core.ElaborationIntegerExpression(
              verilog = parameter.name,
              default = parameter.default,
              minimum = minimum,
              maximum = maximum,
              parameters = Vector(schema),
              sourceLocation = Some(useOrigin.rendered),
              parameterRoots = Vector(token.elaborationRoot)
            )
          )
        )
        formalBinding match {
          case Some(binding) => ExternalFormalParameterRegistry.retain(width, binding)
          case None          => width
        }
      case _ =>
        if (formalBinding.nonEmpty) {
          FrontendException.failAt(
            "MORPH-FRONTEND-FORMAL-PARAMETER-NOT-DIRECT",
            "a formal packed-width slot must remain one direct explicit formal parameter",
            useOrigin
          )
        }
        val retained = StructuralExpressionBridge.width(
          this,
          "SpinalHDL packed width"
        )
        validateSpinalWidthDomain(
          retained.default,
          retained.minimum,
          retained.maximum,
          s"packed-width expression '${retained.verilog}'",
          useOrigin
        )
        if (retained.default != witness) {
          FrontendException.failAt(
            "MORPH-FRONTEND-SPINAL-WIDTH-DEFAULT-INVALID",
            s"packed-width expression '${retained.verilog}' default ${retained.default} does not match concrete witness $witness",
            useOrigin
          )
        }
        ParameterizedBitCount(
          witness.toInt,
          parameter = None,
          sourceLocation = Some(useOrigin.rendered),
          expression = if (retained.parameters.nonEmpty) Some(retained) else None
        )
    }
  }

  /** Cross the approved typed native-library boundary without collapsing this
    * value to Scala `Int`.
    */
  def asElabInt: spinal.core.ElabInt = {
    val retained =
      StructuralExpressionBridge.width(this, "typed elaboration integer")
    StructuralExpressionBridge.singleRootEvaluations(this) match {
      case Some(evaluations) =>
        spinal.core.ElabInt.fromSingleRootExpression(retained, evaluations)
      case None => spinal.core.ElabInt.fromExpression(retained)
    }
  }

  /** Retain one bounded native Mem word-count expression. */
  private[frontend] def toParameterizedMemoryDepth(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): ParameterizedMemoryDepth = {
    val useOrigin = SourceOrigin.capture
    requireLoopInvariant("SpinalHDL memory depth")
    val retained = StructuralExpressionBridge.width(
      this,
      "SpinalHDL memory depth"
    )
    if (
      retained.default != witness || retained.minimum < 1 ||
      retained.maximum < retained.minimum ||
      retained.maximum > BigInt(Int.MaxValue) || !witness.isValidInt
    ) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SPINAL-MEMORY-DEPTH-DOMAIN-INVALID",
        s"memory depth '${retained.verilog}' must have concrete witness $witness and a finite positive Int-sized domain",
        useOrigin
      )
    }
    ParameterizedMemoryDepth(
      witness.toInt,
      retained.copy(sourceLocation = Some(useOrigin.rendered)),
      sourceLocation = Some(useOrigin.rendered)
    )
  }

  private def validateSpinalWidthDomain(
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      role: String,
      origin: SourceOrigin
  ): Unit = {
    if (minimum < 1 || maximum < minimum) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SPINAL-WIDTH-DOMAIN-NONPOSITIVE",
        s"$role must have a non-empty domain whose minimum is at least 1",
        origin
      )
    }
    if (maximum > BigInt(Int.MaxValue)) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SPINAL-WIDTH-DOMAIN-TOO-LARGE",
        s"$role maximum $maximum is outside SpinalHDL's Int-sized width domain",
        origin
      )
    }
    if (default < minimum || default > maximum || !default.isValidInt) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SPINAL-WIDTH-DEFAULT-INVALID",
        s"$role default $default must lie inside [$minimum, $maximum]",
        origin
      )
    }
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

  /** Returns the minimum packed width that can address every element of this
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

  /** Returns the mathematical ceiling of log base two while retaining this
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

  import scala.language.implicitConversions

  /** Allow ordinary Mem(wordType, depth) to retain an HdlInt depth. */
  implicit def hdlIntToParameterizedMemoryDepth(
      value: HdlInt
  )(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): ParameterizedMemoryDepth =
    value.toParameterizedMemoryDepth(file, line)

  /** Adds SpinalHDL's ordinary `width bits` spelling only to an actual
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

  private[frontend] def formal(
      actual: HdlInt,
      name: String,
      minimum: BigInt,
      maximum: BigInt,
      origin: SourceOrigin
  ): HdlInt = {
    val owner = Option(Component.current).getOrElse {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-PARAMETER-OWNER-MISSING",
        s"formal parameter '$name' must be declared inside one active Component definition",
        origin
      )
    }
    val ownerClassName = owner.getClass.getName
    val binding = formalBindingForOwner(
      actual,
      name,
      minimum,
      maximum,
      ownerClassName,
      declarationKey = s"$ownerClassName@${origin.rendered}::$name",
      origin = origin
    )

    val declaration = IntegerParameter(
      name,
      binding.formal.default,
      Vector[IntConstraint](MinInclusive(minimum), MaxInclusive(maximum))
    )
    val token = new ParameterToken(declaration, origin)
    new HdlInt(
      binding.formal.default,
      ParameterRef(name),
      declaration = Some(token),
      parameters = Set(token),
      booleanParameters = Set.empty,
      localDeclaration = None,
      localParameters = Set.empty,
      booleanLocalParameters = Set.empty,
      scope = None,
      origin = origin,
      formalBinding = Some(binding)
    )
  }

  /** Prove one HdlInt can cross an untouched native Int API as a positive,
    * bounded concrete witness while retaining its complete symbolic geometry.
    */
  private[frontend] def nativeIntExpression(
      actual: HdlInt,
      role: String,
      origin: SourceOrigin
  ): spinal.core.ElaborationIntegerExpression = {
    if (actual eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-NATIVE-INT-ACTUAL-NULL",
        s"$role requires one non-null HdlInt expression",
        origin
      )
    }
    actual.requireLoopInvariant(role)
    val retained = StructuralExpressionBridge.width(actual, role)
    if (
      retained.default != actual.witness ||
      retained.minimum < 1 || retained.maximum < retained.minimum ||
      retained.maximum > BigInt(Int.MaxValue) ||
      retained.default < retained.minimum || retained.default > retained.maximum ||
      !retained.default.isValidInt
    ) {
      FrontendException.failAt(
        "MORPH-FRONTEND-NATIVE-INT-GEOMETRY-DOMAIN-INVALID",
        s"$role expression '${retained.verilog}' must have concrete witness ${actual.witness} and a finite positive Int-sized domain, received default ${retained.default} in [${retained.minimum}, ${retained.maximum}]",
        origin
      )
    }
    actual.formalBinding match {
      case Some(binding) =>
        retained.parameters match {
          case Vector(parameter)
              if parameter == binding.formal &&
                retained.verilog == binding.formal.name =>
            retained.copy(parameters = Vector(binding.formal))
          case _ =>
            FrontendException.failAt(
              "MORPH-FRONTEND-FORMAL-PARAMETER-NOT-DIRECT",
              s"$role must retain the direct explicit formal parameter '${binding.formal.name}'",
              origin
            )
        }
      case None => retained
    }
  }

  /** Build the provisional canonical definition root used while an untouched
    * native child constructor is still executing. Final component attachment
    * revalidates this expression against the owner-specific formal binding.
    */
  private[frontend] def provisionalFormalExpression(
      actual: spinal.core.ElaborationIntegerExpression,
      name: String,
      minimum: BigInt,
      maximum: BigInt,
      origin: SourceOrigin
  ): spinal.core.ElaborationIntegerExpression = {
    if (actual eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-PARAMETER-ACTUAL-NULL",
        "formal parameter construction requires one non-null instance actual expression",
        origin
      )
    }
    if (
      name == null ||
      !PortableIdentifier.pattern.matcher(name).matches()
    ) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-PARAMETER-NAME-INVALID",
        s"formal parameter name '$name' is not a portable Verilog identifier",
        origin
      )
    }
    if (minimum < 1 || maximum < minimum || maximum > BigInt(Int.MaxValue)) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-PARAMETER-DOMAIN-INVALID",
        s"formal parameter '$name' requires a positive non-empty Int-sized domain, received [$minimum, $maximum]",
        origin
      )
    }
    if (
      actual.minimum < minimum || actual.maximum > maximum ||
      actual.default < minimum || actual.default > maximum
    ) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-PARAMETER-ACTUAL-DOMAIN-UNSUPPORTED",
        s"actual expression '${actual.verilog}' in [${actual.minimum}, ${actual.maximum}] with default ${actual.default} is incompatible with formal '$name' in [$minimum, $maximum]",
        origin
      )
    }
    val formal = spinal.core.ElaborationIntegerParameter(
      name,
      actual.default,
      minimum,
      maximum
    )
    spinal.core.ElaborationIntegerExpression(
      verilog = name,
      default = actual.default,
      minimum = minimum,
      maximum = maximum,
      parameters = Vector(formal),
      sourceLocation = Some(origin.rendered)
    )
  }

  /** Build a definition-side formal for an explicitly supplied component
    * owner. Unlike `formalParam`, this helper does not depend on
    * `Component.current`; it is used after an untouched native Int constructor
    * has returned its exact component object.
    */
  private[frontend] def formalBindingForOwner(
      actual: HdlInt,
      name: String,
      minimum: BigInt,
      maximum: BigInt,
      ownerClassName: String,
      declarationKey: String,
      origin: SourceOrigin,
      provisionalFormal: Option[ElaborationIntegerParameter] = None
  ): ExternalFormalParameterBinding = {
    if (actual eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-PARAMETER-ACTUAL-NULL",
        "formal parameter construction requires one non-null instance actual expression",
        origin
      )
    }
    if (
      name == null ||
      !PortableIdentifier.pattern.matcher(name).matches()
    ) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-PARAMETER-NAME-INVALID",
        s"formal parameter name '$name' is not a portable Verilog identifier",
        origin
      )
    }
    if (minimum < 1 || maximum < minimum || maximum > BigInt(Int.MaxValue)) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-PARAMETER-DOMAIN-INVALID",
        s"formal parameter '$name' requires a positive non-empty Int-sized domain, received [$minimum, $maximum]",
        origin
      )
    }
    if (ownerClassName == null || ownerClassName.isEmpty) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-PARAMETER-OWNER-MISSING",
        s"formal parameter '$name' requires one component-definition owner identity",
        origin
      )
    }
    if (declarationKey == null || declarationKey.isEmpty) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-PARAMETER-IDENTITY-MISSING",
        s"formal parameter '$name' requires one deterministic declaration identity",
        origin
      )
    }
    if (provisionalFormal == null || provisionalFormal.exists(_ == null)) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-PARAMETER-PROVISIONAL-NULL",
        s"formal parameter '$name' requires a non-null provisional-formal option",
        origin
      )
    }
    if (actual.formalBinding.nonEmpty) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-PARAMETER-NESTED",
        s"formal parameter '$name' cannot use another component-definition formal as its instance actual",
        origin
      )
    }

    provisionalFormal.foreach { formal =>
      if (
        formal.name != name || formal.default != actual.witness ||
        formal.minimum != minimum || formal.maximum != maximum
      ) {
        FrontendException.failAt(
          "MORPH-FRONTEND-FORMAL-PARAMETER-PROVISIONAL-SCHEMA-MISMATCH",
          s"provisional formal '${formal.name}' with default ${formal.default} in [${formal.minimum}, ${formal.maximum}] does not match requested formal '$name' with default ${actual.witness} in [$minimum, $maximum]",
          origin
        )
      }
    }

    val retainedActual = nativeIntExpression(
      actual,
      s"formal parameter '$name' actual",
      origin
    )
    if (retainedActual.minimum < minimum || retainedActual.maximum > maximum) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-PARAMETER-ACTUAL-DOMAIN-UNSUPPORTED",
        s"actual expression '${retainedActual.verilog}' in [${retainedActual.minimum}, ${retainedActual.maximum}] with default ${retainedActual.default} is incompatible with formal '$name' in [$minimum, $maximum]",
        origin
      )
    }

    val formal = provisionalFormal.getOrElse(
      ElaborationIntegerParameter(
        name,
        retainedActual.default,
        minimum,
        maximum
      )
    )
    ExternalFormalParameterBinding(
      formal = formal,
      actual = retainedActual,
      declarationKey = declarationKey,
      ownerClassName = ownerClassName,
      sourceLocation = Some(origin.rendered)
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
    case (None, value)                => value
    case (value, None)                => value
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
