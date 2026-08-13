package morphhdl.paramrtl

import morphhdl.paramrtl.BoolExpr.{
  And,
  Equal,
  GreaterThan,
  GreaterThanOrEqual,
  LessThan,
  LessThanOrEqual,
  Literal,
  LocalParameterRef,
  Not,
  NotEqual,
  Or,
  ParameterRef
}

sealed trait BoolExpressionFailure extends Product with Serializable

object BoolExpressionFailure {
  final case class UnresolvedParameter(name: String) extends BoolExpressionFailure
  final case class UnresolvedLocalParameter(name: String) extends BoolExpressionFailure
  final case class InvalidIntegerExpression(failure: IntExpressionFailure) extends BoolExpressionFailure
}

final case class BooleanLocalParameterFacts(defaultValue: Boolean)

/** Exact typed-default evaluation used by Morph default-shape selection. */
object BoolExpressionAnalysis {
  import BoolExpressionFailure._

  /** Source-compatible Boolean-only entry point. Integer comparisons still work for literals. */
  def evaluateDefault(
      expression: BoolExpr,
      parameters: Map[String, BooleanParameter]
  ): Either[BoolExpressionFailure, Boolean] =
    evaluateDefault(expression, parameters, Map.empty, Map.empty, Map.empty, Map.empty)

  /**
    * Evaluates the exact default witness after validating every Boolean reference and every
    * integer-comparison operand. Integer operands are analyzed over their complete legal domains,
    * so unsafe division and modulo are rejected even when Boolean short-circuiting would otherwise
    * hide the comparison at the default point.
    */
  def evaluateDefault(
      expression: BoolExpr,
      booleanParameters: Map[String, BooleanParameter],
      integerParameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts]
  ): Either[BoolExpressionFailure, Boolean] =
    evaluateDefault(
      expression,
      booleanParameters,
      integerParameters,
      localParameters,
      Map.empty,
      Map.empty
    )

  def evaluateDefault(
      expression: BoolExpr,
      booleanParameters: Map[String, BooleanParameter],
      integerParameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      generateIndices: Map[String, IntExprFacts]
  ): Either[BoolExpressionFailure, Boolean] =
    evaluateDefault(
      expression,
      booleanParameters,
      integerParameters,
      localParameters,
      Map.empty,
      generateIndices
    )

  def evaluateDefault(
      expression: BoolExpr,
      booleanParameters: Map[String, BooleanParameter],
      integerParameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      booleanLocalParameters: Map[String, BooleanLocalParameterFacts],
      generateIndices: Map[String, IntExprFacts]
  ): Either[BoolExpressionFailure, Boolean] =
    parameterReferences(expression).find(name => !booleanParameters.contains(name)) match {
      case Some(name) => Left(UnresolvedParameter(name))
      case None =>
        booleanLocalParameterReferences(expression)
          .find(name => !booleanLocalParameters.contains(name)) match {
          case Some(name) => Left(UnresolvedLocalParameter(name))
          case None =>
            evaluateChecked(
              expression,
              booleanParameters,
              integerParameters,
              localParameters,
              booleanLocalParameters,
              generateIndices
            )
        }
    }

  def parameterReferences(expression: BoolExpr): Vector[String] = expression match {
    case Literal(_) | LocalParameterRef(_) => Vector.empty
    case ParameterRef(name)                => Vector(name)
    case LessThan(left, right) =>
      IntExpressionAnalysis.booleanParameterReferences(left) ++
        IntExpressionAnalysis.booleanParameterReferences(right)
    case LessThanOrEqual(left, right) =>
      IntExpressionAnalysis.booleanParameterReferences(left) ++
        IntExpressionAnalysis.booleanParameterReferences(right)
    case GreaterThan(left, right) =>
      IntExpressionAnalysis.booleanParameterReferences(left) ++
        IntExpressionAnalysis.booleanParameterReferences(right)
    case GreaterThanOrEqual(left, right) =>
      IntExpressionAnalysis.booleanParameterReferences(left) ++
        IntExpressionAnalysis.booleanParameterReferences(right)
    case Equal(left, right) =>
      IntExpressionAnalysis.booleanParameterReferences(left) ++
        IntExpressionAnalysis.booleanParameterReferences(right)
    case NotEqual(left, right) =>
      IntExpressionAnalysis.booleanParameterReferences(left) ++
        IntExpressionAnalysis.booleanParameterReferences(right)
    case Not(value)       => parameterReferences(value)
    case And(left, right) => parameterReferences(left) ++ parameterReferences(right)
    case Or(left, right)  => parameterReferences(left) ++ parameterReferences(right)
  }

  def integerParameterReferences(expression: BoolExpr): Vector[String] =
    integerOperands(expression).flatMap(IntExpressionAnalysis.parameterReferences)

  def localParameterReferences(expression: BoolExpr): Vector[String] =
    integerOperands(expression).flatMap(IntExpressionAnalysis.localParameterReferences)

  def booleanLocalParameterReferences(expression: BoolExpr): Vector[String] = expression match {
    case Literal(_) | ParameterRef(_) => Vector.empty
    case LocalParameterRef(name)      => Vector(name)
    case LessThan(left, right) =>
      IntExpressionAnalysis.booleanLocalParameterReferences(left) ++
        IntExpressionAnalysis.booleanLocalParameterReferences(right)
    case LessThanOrEqual(left, right) =>
      IntExpressionAnalysis.booleanLocalParameterReferences(left) ++
        IntExpressionAnalysis.booleanLocalParameterReferences(right)
    case GreaterThan(left, right) =>
      IntExpressionAnalysis.booleanLocalParameterReferences(left) ++
        IntExpressionAnalysis.booleanLocalParameterReferences(right)
    case GreaterThanOrEqual(left, right) =>
      IntExpressionAnalysis.booleanLocalParameterReferences(left) ++
        IntExpressionAnalysis.booleanLocalParameterReferences(right)
    case Equal(left, right) =>
      IntExpressionAnalysis.booleanLocalParameterReferences(left) ++
        IntExpressionAnalysis.booleanLocalParameterReferences(right)
    case NotEqual(left, right) =>
      IntExpressionAnalysis.booleanLocalParameterReferences(left) ++
        IntExpressionAnalysis.booleanLocalParameterReferences(right)
    case Not(value)       => booleanLocalParameterReferences(value)
    case And(left, right) => booleanLocalParameterReferences(left) ++ booleanLocalParameterReferences(right)
    case Or(left, right)  => booleanLocalParameterReferences(left) ++ booleanLocalParameterReferences(right)
  }

  private def integerOperands(expression: BoolExpr): Vector[IntExpr] = expression match {
    case LessThan(left, right)           => Vector(left, right)
    case LessThanOrEqual(left, right)    => Vector(left, right)
    case GreaterThan(left, right)        => Vector(left, right)
    case GreaterThanOrEqual(left, right) => Vector(left, right)
    case Equal(left, right)              => Vector(left, right)
    case NotEqual(left, right)           => Vector(left, right)
    case Not(value)                      => integerOperands(value)
    case And(left, right)                => integerOperands(left) ++ integerOperands(right)
    case Or(left, right)                 => integerOperands(left) ++ integerOperands(right)
    case _                               => Vector.empty
  }

  private def evaluateChecked(
      expression: BoolExpr,
      booleanParameters: Map[String, BooleanParameter],
      integerParameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      booleanLocalParameters: Map[String, BooleanLocalParameterFacts],
      generateIndices: Map[String, IntExprFacts]
  ): Either[BoolExpressionFailure, Boolean] = expression match {
    case Literal(value) => Right(value)
    case ParameterRef(name) => Right(booleanParameters(name).default)
    case LocalParameterRef(name) => Right(booleanLocalParameters(name).defaultValue)
    case LessThan(left, right) =>
      compareChecked(
        left,
        right,
        booleanParameters,
        integerParameters,
        localParameters,
        booleanLocalParameters,
        generateIndices
      )(_ < _)
    case LessThanOrEqual(left, right) =>
      compareChecked(
        left,
        right,
        booleanParameters,
        integerParameters,
        localParameters,
        booleanLocalParameters,
        generateIndices
      )(_ <= _)
    case GreaterThan(left, right) =>
      compareChecked(
        left,
        right,
        booleanParameters,
        integerParameters,
        localParameters,
        booleanLocalParameters,
        generateIndices
      )(_ > _)
    case GreaterThanOrEqual(left, right) =>
      compareChecked(
        left,
        right,
        booleanParameters,
        integerParameters,
        localParameters,
        booleanLocalParameters,
        generateIndices
      )(_ >= _)
    case Equal(left, right) =>
      compareChecked(
        left,
        right,
        booleanParameters,
        integerParameters,
        localParameters,
        booleanLocalParameters,
        generateIndices
      )(_ == _)
    case NotEqual(left, right) =>
      compareChecked(
        left,
        right,
        booleanParameters,
        integerParameters,
        localParameters,
        booleanLocalParameters,
        generateIndices
      )(_ != _)
    case Not(value) =>
      evaluateChecked(
        value,
        booleanParameters,
        integerParameters,
        localParameters,
        booleanLocalParameters,
        generateIndices
      ).map(!_)
    case And(left, right) =>
      val leftResult =
        evaluateChecked(
          left,
          booleanParameters,
          integerParameters,
          localParameters,
          booleanLocalParameters,
          generateIndices
        )
      val rightResult =
        evaluateChecked(
          right,
          booleanParameters,
          integerParameters,
          localParameters,
          booleanLocalParameters,
          generateIndices
        )
      leftResult.flatMap(leftValue => rightResult.map(rightValue => leftValue && rightValue))
    case Or(left, right) =>
      val leftResult =
        evaluateChecked(
          left,
          booleanParameters,
          integerParameters,
          localParameters,
          booleanLocalParameters,
          generateIndices
        )
      val rightResult =
        evaluateChecked(
          right,
          booleanParameters,
          integerParameters,
          localParameters,
          booleanLocalParameters,
          generateIndices
        )
      leftResult.flatMap(leftValue => rightResult.map(rightValue => leftValue || rightValue))
  }

  private def compareChecked(
      left: IntExpr,
      right: IntExpr,
      booleanParameters: Map[String, BooleanParameter],
      integerParameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      booleanLocalParameters: Map[String, BooleanLocalParameterFacts],
      generateIndices: Map[String, IntExprFacts]
  )(compare: (BigInt, BigInt) => Boolean): Either[BoolExpressionFailure, Boolean] = {
    val leftResult = analyzeInteger(
      left,
      booleanParameters,
      integerParameters,
      localParameters,
      booleanLocalParameters,
      generateIndices
    )
    val rightResult = analyzeInteger(
      right,
      booleanParameters,
      integerParameters,
      localParameters,
      booleanLocalParameters,
      generateIndices
    )
    leftResult.flatMap(leftValue => rightResult.map(rightValue => compare(leftValue, rightValue)))
  }

  private def analyzeInteger(
      expression: IntExpr,
      booleanParameters: Map[String, BooleanParameter],
      integerParameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      booleanLocalParameters: Map[String, BooleanLocalParameterFacts],
      generateIndices: Map[String, IntExprFacts]
  ): Either[BoolExpressionFailure, BigInt] =
    IntExpressionAnalysis
      .analyze(
        expression,
        integerParameters,
        localParameters,
        booleanParameters,
        booleanLocalParameters,
        generateIndices
      )
      .left
      .map(InvalidIntegerExpression)
      .map(_.defaultValue)
}
