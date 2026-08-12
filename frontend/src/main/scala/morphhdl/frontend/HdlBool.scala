package morphhdl.frontend

import morphhdl.paramrtl.BoolExpr
import morphhdl.paramrtl.BoolExpr.{And, Literal, Not, Or, ParameterRef}
import morphhdl.paramrtl.BooleanParameter

/** A Boolean concrete witness paired with a guarded symbolic expression. */
final class HdlBool private[frontend] (
    private[frontend] val witness: Boolean,
    private[frontend] val expression: BoolExpr,
    private[frontend] val declaration: Option[BooleanParameterToken],
    private[frontend] val parameters: Set[BooleanParameterToken],
    private[frontend] val origin: SourceOrigin
) {
  def unary_!(implicit file: sourcecode.File, line: sourcecode.Line): HdlBool =
    new HdlBool(
      !witness,
      Not(expression),
      declaration = None,
      parameters = parameters,
      origin = SourceOrigin.capture
    )

  def &&(that: HdlBool)(implicit file: sourcecode.File, line: sourcecode.Line): HdlBool =
    binary(that, And.apply)(_ && _)

  def ||(that: HdlBool)(implicit file: sourcecode.File, line: sourcecode.Line): HdlBool =
    binary(that, Or.apply)(_ || _)

  private def binary(
      that: HdlBool,
      operation: (BoolExpr, BoolExpr) => BoolExpr
  )(witnessOperation: (Boolean, Boolean) => Boolean)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlBool =
    new HdlBool(
      witnessOperation(witness, that.witness),
      operation(expression, that.expression),
      declaration = None,
      parameters = parameters ++ that.parameters,
      origin = SourceOrigin.capture
    )

  override def equals(that: Any): Boolean =
    FrontendException.failAt(
      "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED",
      s"symbolic Boolean expression '$expression' cannot be compared with ${HdlBool.describe(that)}",
      origin
    )

  override def hashCode: Int =
    FrontendException.failAt(
      "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED",
      s"symbolic Boolean expression '$expression' cannot be hashed by Scala",
      origin
    )

  override def toString: String = "HdlBool(<dual-valued>)"
}

object HdlBool {
  def literal(value: Boolean)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlBool =
    new HdlBool(
      value,
      Literal(value),
      declaration = None,
      parameters = Set.empty,
      origin = SourceOrigin.capture
    )

  def param(name: String, default: Boolean)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlBool = {
    val declaration = BooleanParameter(name, default)
    val token = new BooleanParameterToken(declaration, SourceOrigin.capture)
    new HdlBool(
      default,
      ParameterRef(name),
      declaration = Some(token),
      parameters = Set(token),
      origin = token.origin
    )
  }

  private def describe(value: Any): String = value match {
    case _: HdlBool => "another HdlBool"
    case null       => "null"
    case other      => s"a ${other.getClass.getName} value"
  }
}
