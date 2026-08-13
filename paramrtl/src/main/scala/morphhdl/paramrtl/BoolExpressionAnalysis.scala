package morphhdl.paramrtl

import morphhdl.paramrtl.BoolExpr.{
  And,
  Equal,
  GreaterThan,
  GreaterThanOrEqual,
  LessThan,
  LessThanOrEqual,
  Literal,
  Not,
  NotEqual,
  Or,
  ParameterRef
}

sealed trait BoolExpressionFailure extends Product with Serializable

object BoolExpressionFailure {
  final case class UnresolvedParameter(name: String) extends BoolExpressionFailure
  final case class InvalidIntegerExpression(failure: IntExpressionFailure) extends BoolExpressionFailure
}

/** Exact typed-default evaluation used by Morph default-shape selection. */
object BoolExpressionAnalysis {
  import BoolExpressionFailure._

  /** Source-compatible Boolean-only entry point. Integer comparisons still work for literals. */
  def evaluateDefault(
      expression: BoolExpr,
      parameters: Map[String, BooleanParameter]
  ): Either[BoolExpressionFailure, Boolean] =
    evaluateDefault(expression, parameters, Map.empty, Map.empty)

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
    parameterReferences(expression).find(name => !booleanParameters.contains(name)) match {
      case Some(name) => Left(UnresolvedParameter(name))
      case None =>
        integerOperandFailures(expression, integerParameters, localParameters).headOption match {
          case Some(failure) => Left(InvalidIntegerExpression(failure))
          case None =>
            val defaultAssignments = booleanParameters.iterator.map {
              case (name, parameter) => name -> parameter.default
            }.toMap
            Right(evaluate(expression, defaultAssignments, integerParameters, localParameters))
        }
    }

  def parameterReferences(expression: BoolExpr): Vector[String] = expression match {
    case Literal(_)         => Vector.empty
    case ParameterRef(name) => Vector(name)
    case LessThan(_, _)           => Vector.empty
    case LessThanOrEqual(_, _)    => Vector.empty
    case GreaterThan(_, _)        => Vector.empty
    case GreaterThanOrEqual(_, _) => Vector.empty
    case Equal(_, _)              => Vector.empty
    case NotEqual(_, _)           => Vector.empty
    case Not(value)       => parameterReferences(value)
    case And(left, right) => parameterReferences(left) ++ parameterReferences(right)
    case Or(left, right)  => parameterReferences(left) ++ parameterReferences(right)
  }

  def integerParameterReferences(expression: BoolExpr): Vector[String] =
    integerOperands(expression).flatMap(IntExpressionAnalysis.parameterReferences)

  def localParameterReferences(expression: BoolExpr): Vector[String] =
    integerOperands(expression).flatMap(IntExpressionAnalysis.localParameterReferences)

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

  private def integerOperandFailures(
      expression: BoolExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts]
  ): Vector[IntExpressionFailure] =
    integerOperands(expression).flatMap { operand =>
      IntExpressionAnalysis.analyze(operand, parameters, localParameters) match {
        case Left(failure) => Vector(failure)
        case Right(_)      => Vector.empty
      }
    }

  private def evaluate(
      expression: BoolExpr,
      booleanValues: Map[String, Boolean],
      integerParameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts]
  ): Boolean = expression match {
    case Literal(value)     => value
    case ParameterRef(name) => booleanValues(name)
    case LessThan(left, right) =>
      integerDefault(left, integerParameters, localParameters) <
        integerDefault(right, integerParameters, localParameters)
    case LessThanOrEqual(left, right) =>
      integerDefault(left, integerParameters, localParameters) <=
        integerDefault(right, integerParameters, localParameters)
    case GreaterThan(left, right) =>
      integerDefault(left, integerParameters, localParameters) >
        integerDefault(right, integerParameters, localParameters)
    case GreaterThanOrEqual(left, right) =>
      integerDefault(left, integerParameters, localParameters) >=
        integerDefault(right, integerParameters, localParameters)
    case Equal(left, right) =>
      integerDefault(left, integerParameters, localParameters) ==
        integerDefault(right, integerParameters, localParameters)
    case NotEqual(left, right) =>
      integerDefault(left, integerParameters, localParameters) !=
        integerDefault(right, integerParameters, localParameters)
    case Not(value)       => !evaluate(value, booleanValues, integerParameters, localParameters)
    case And(left, right) =>
      evaluate(left, booleanValues, integerParameters, localParameters) &&
        evaluate(right, booleanValues, integerParameters, localParameters)
    case Or(left, right) =>
      evaluate(left, booleanValues, integerParameters, localParameters) ||
        evaluate(right, booleanValues, integerParameters, localParameters)
  }

  private def integerDefault(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts]
  ): BigInt =
    IntExpressionAnalysis.analyze(expression, parameters, localParameters) match {
      case Right(facts) => facts.defaultValue
      case Left(failure) =>
        throw new IllegalStateException(s"Integer operand was not prevalidated: $failure")
    }
}
