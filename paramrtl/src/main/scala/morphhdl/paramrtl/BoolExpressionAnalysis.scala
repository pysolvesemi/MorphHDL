package morphhdl.paramrtl

import morphhdl.paramrtl.BoolExpr.{And, Literal, Not, Or, ParameterRef}

sealed trait BoolExpressionFailure extends Product with Serializable

object BoolExpressionFailure {
  final case class UnresolvedParameter(name: String) extends BoolExpressionFailure
}

/** Exact typed-default evaluation used by Morph default-shape selection. */
object BoolExpressionAnalysis {
  import BoolExpressionFailure._

  def evaluateDefault(
      expression: BoolExpr,
      parameters: Map[String, BooleanParameter]
  ): Either[BoolExpressionFailure, Boolean] =
    parameterReferences(expression).find(name => !parameters.contains(name)) match {
      case Some(name) => Left(UnresolvedParameter(name))
      case None =>
        val defaultAssignments = parameters.iterator.map { case (name, parameter) => name -> parameter.default }.toMap
        Right(evaluate(expression, defaultAssignments))
    }

  def parameterReferences(expression: BoolExpr): Vector[String] = expression match {
    case Literal(_)         => Vector.empty
    case ParameterRef(name) => Vector(name)
    case Not(value)         => parameterReferences(value)
    case And(left, right)   => parameterReferences(left) ++ parameterReferences(right)
    case Or(left, right)    => parameterReferences(left) ++ parameterReferences(right)
  }

  private def evaluate(expression: BoolExpr, values: Map[String, Boolean]): Boolean = expression match {
    case Literal(value)         => value
    case ParameterRef(name)     => values(name)
    case Not(value)             => !evaluate(value, values)
    case And(left, right)       => evaluate(left, values) && evaluate(right, values)
    case Or(left, right)        => evaluate(left, values) || evaluate(right, values)
  }
}
