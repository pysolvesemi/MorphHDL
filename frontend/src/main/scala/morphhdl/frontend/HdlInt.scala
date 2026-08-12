package morphhdl.frontend

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Literal, Multiply, ParameterRef}
import morphhdl.paramrtl.{IntConstraint, IntExpr, IntegerParameter}

final class HdlInt private[frontend] (
    private[frontend] val witness: BigInt,
    private[frontend] val expression: IntExpr,
    private[frontend] val declaration: Option[ParameterToken],
    private[frontend] val parameters: Set[ParameterToken],
    private[frontend] val scope: Option[ScopeToken],
    private[frontend] val origin: SourceOrigin
) extends scala.math.ScalaNumber {
  def *(that: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt = {
    requireUsable("integer multiplication")
    that.requireUsable("integer multiplication")
    val resultOrigin = SourceOrigin.capture
    val resultScope = HdlInt.mergeScopes(scope, that.scope, resultOrigin)
    new HdlInt(
      witness * that.witness,
      Multiply(expression, that.expression),
      declaration = None,
      parameters = parameters ++ that.parameters,
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
        s"$consumer cannot depend on a generate index in Increment 6",
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
  def literal(value: BigInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt =
    new HdlInt(
      value,
      Literal(value),
      declaration = None,
      parameters = Set.empty,
      scope = None,
      origin = SourceOrigin.capture
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
      scope = None,
      origin = token.origin
    )
  }

  private[frontend] def fromGenerateIndex(
      witness: BigInt,
      expression: IntExpr,
      scope: ScopeToken,
      parameters: Set[ParameterToken],
      origin: SourceOrigin
  ): HdlInt =
    new HdlInt(
      witness,
      expression,
      declaration = None,
      parameters = parameters,
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
