package morphhdl.frontend

import scala.language.implicitConversions

import morphhdl.paramrtl.BoolExpr
import morphhdl.paramrtl.BoolExpr.{And, Literal, LocalParameterRef, Not, Or, ParameterRef}
import morphhdl.paramrtl.{BooleanLocalParameter, BooleanParameter}

private[frontend] final case class NaturalGenerateIfNames(
    whenTrue: String,
    whenFalse: Option[String],
    origin: SourceOrigin
)

/** A Boolean concrete witness paired with a guarded symbolic expression. */
final class HdlBool private[frontend] (
    private[frontend] val witness: Boolean,
    private[frontend] val expression: BoolExpr,
    private[frontend] val declaration: Option[BooleanParameterToken],
    private[frontend] val localDeclaration: Option[BooleanLocalParameterToken],
    private[frontend] val parameters: Set[BooleanParameterToken],
    private[frontend] val integerParameters: Set[ParameterToken],
    private[frontend] val localParameters: Set[LocalParameterToken],
    private[frontend] val booleanLocalParameters: Set[BooleanLocalParameterToken],
    private[frontend] val naturalGenerateNames: Option[NaturalGenerateIfNames],
    private[frontend] val origin: SourceOrigin
) {

  /** Cross the approved typed native-library boundary without collapsing this
    * value to Scala `Boolean`.
    *
    * The integer select is analyzed from the frontend AST and authenticated by
    * the same exact single-root evidence used for [[HdlInt.asElabInt]]. The
    * final typed comparison is a native derivation; no rendered expression,
    * concrete witness, or runtime call-site identity is used to recover
    * provenance.
    */
  def asElabBool: spinal.core.ElabBool = {
    val symbolic =
      parameters.nonEmpty || integerParameters.nonEmpty ||
        localDeclaration.nonEmpty || localParameters.nonEmpty ||
        booleanLocalParameters.nonEmpty

    if (!symbolic) spinal.core.ElabBool.literal(witness)
    else {
      val encoded = HdlInt.select(
        this,
        HdlInt.literalAt(BigInt(1), origin),
        HdlInt.literalAt(BigInt(0), origin),
        origin
      )
      encoded.asElabInt.elabEq(1)
    }
  }

  def unary_!(implicit file: sourcecode.File, line: sourcecode.Line): HdlBool =
    new HdlBool(
      !witness,
      Not(expression),
      declaration = None,
      localDeclaration = None,
      parameters = parameters,
      integerParameters = integerParameters,
      localParameters = localParameters,
      booleanLocalParameters = booleanLocalParameters,
      naturalGenerateNames = None,
      origin = SourceOrigin.capture
    )

  def &&(that: HdlBool)(implicit file: sourcecode.File, line: sourcecode.Line): HdlBool =
    binary(that, And.apply)(_ && _)

  def ||(that: HdlBool)(implicit file: sourcecode.File, line: sourcecode.Line): HdlBool =
    binary(that, Or.apply)(_ || _)

  /** Selects an integer expression without collapsing either symbolic branch.
    * The concrete witness follows this Boolean witness, while ParamRTL keeps
    * the condition and both alternatives.
    */
  def select(whenTrue: HdlInt, whenFalse: HdlInt)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlInt =
    HdlInt.select(this, whenTrue, whenFalse, SourceOrigin.capture)

  /** Names the true block of a non-final natural `if / else if` condition. */
  def named(whenTrueLabel: String)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlBool =
    withGenerateNames(whenTrueLabel, None, SourceOrigin.capture)

  /** Names both blocks of a simple natural `if / else`, or the true block and
    * terminal `else` block of the final predicate in an `else if` chain.
    */
  def named(whenTrueLabel: String, whenFalseLabel: String)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlBool =
    withGenerateNames(whenTrueLabel, Some(whenFalseLabel), SourceOrigin.capture)

  private def withGenerateNames(
      whenTrueLabel: String,
      whenFalseLabel: Option[String],
      namedOrigin: SourceOrigin
  ): HdlBool = {
    HdlRange.requireIdentifier(
      whenTrueLabel,
      "natural generate-if true label",
      namedOrigin
    )
    whenFalseLabel.foreach { label =>
      HdlRange.requireIdentifier(
        label,
        "natural generate-if false label",
        namedOrigin
      )
    }
    new HdlBool(
      witness = witness,
      expression = expression,
      declaration = declaration,
      localDeclaration = localDeclaration,
      parameters = parameters,
      integerParameters = integerParameters,
      localParameters = localParameters,
      booleanLocalParameters = booleanLocalParameters,
      naturalGenerateNames = Some(
        NaturalGenerateIfNames(whenTrueLabel, whenFalseLabel, namedOrigin)
      ),
      origin = origin
    )
  }

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
      localDeclaration = None,
      parameters = parameters ++ that.parameters,
      integerParameters = integerParameters ++ that.integerParameters,
      localParameters = localParameters ++ that.localParameters,
      booleanLocalParameters = booleanLocalParameters ++ that.booleanLocalParameters,
      naturalGenerateNames = None,
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

  /** One-way bridge into the native typed elaboration domain. Symbolic values
    * can never satisfy an API which accepts only Scala `Boolean`. As with the
    * integer bridge, exact target equality prevents adaptation to any result
    * type other than [[spinal.core.ElabBool]].
    */
  implicit def hdlBoolToElabBool[A](value: HdlBool)(implicit
      target: spinal.core.ElabBool =:= A
  ): A = {
    if (value eq null) {
      FrontendException.fail(
        "MORPH-FRONTEND-TYPED-BOOLEAN-NULL",
        "native typed library calls require a non-null HdlBool"
      )
    }
    target(value.asElabBool)
  }

  def literal(value: Boolean)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlBool =
    new HdlBool(
      value,
      Literal(value),
      declaration = None,
      localDeclaration = None,
      parameters = Set.empty,
      integerParameters = Set.empty,
      localParameters = Set.empty,
      booleanLocalParameters = Set.empty,
      naturalGenerateNames = None,
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
      localDeclaration = None,
      parameters = Set(token),
      integerParameters = Set.empty,
      localParameters = Set.empty,
      booleanLocalParameters = Set.empty,
      naturalGenerateNames = None,
      origin = token.origin
    )
  }

  private[frontend] def comparison(
      witness: Boolean,
      expression: BoolExpr,
      integerParameters: Set[ParameterToken],
      booleanParameters: Set[BooleanParameterToken],
      localParameters: Set[LocalParameterToken],
      booleanLocalParameters: Set[BooleanLocalParameterToken],
      origin: SourceOrigin
  ): HdlBool =
    new HdlBool(
      witness,
      expression,
      declaration = None,
      localDeclaration = None,
      parameters = booleanParameters,
      integerParameters = integerParameters,
      localParameters = localParameters,
      booleanLocalParameters = booleanLocalParameters,
      naturalGenerateNames = None,
      origin = origin
    )

  private[frontend] def local(
      name: String,
      value: HdlBool,
      origin: SourceOrigin
  ): HdlBool = {
    val token = new BooleanLocalParameterToken(
      BooleanLocalParameter(name, value.expression),
      parameters = value.integerParameters,
      booleanParameters = value.parameters,
      dependencies = value.localParameters ++ value.booleanLocalParameters,
      origin = origin
    )
    new HdlBool(
      value.witness,
      LocalParameterRef(name),
      declaration = None,
      localDeclaration = Some(token),
      parameters = value.parameters,
      integerParameters = value.integerParameters,
      localParameters = value.localParameters,
      booleanLocalParameters = value.booleanLocalParameters + token,
      naturalGenerateNames = None,
      origin = origin
    )
  }

  private def describe(value: Any): String = value match {
    case _: HdlBool => "another HdlBool"
    case null       => "null"
    case other      => s"a ${other.getClass.getName} value"
  }
}
