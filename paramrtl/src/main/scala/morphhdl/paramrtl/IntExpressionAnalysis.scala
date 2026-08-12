package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{
  Add,
  Divide,
  Literal,
  LocalParameterRef,
  Modulo,
  Multiply,
  Negate,
  ParameterRef,
  Subtract
}

final case class IntInterval private[morphhdl] (
    lower: Option[BigInt],
    upper: Option[BigInt]
) {
  def isFinite: Boolean = lower.isDefined && upper.isDefined

  def contains(value: BigInt): Boolean =
    lower.forall(_ <= value) && upper.forall(_ >= value)

  def excludesZero: Boolean = lower.exists(_ > 0) || upper.exists(_ < 0)
}

object IntInterval {
  private[morphhdl] def point(value: BigInt): IntInterval =
    IntInterval(Some(value), Some(value))

  private[morphhdl] def bounded(lower: BigInt, upper: BigInt): Option[IntInterval] =
    if (lower <= upper) Some(IntInterval(Some(lower), Some(upper))) else None
}

final case class IntExprFacts(defaultValue: BigInt, interval: IntInterval)

sealed trait IntExpressionFailure extends Product with Serializable

object IntExpressionFailure {
  final case class UnresolvedParameter(name: String) extends IntExpressionFailure
  final case class UnresolvedLocalParameter(name: String) extends IntExpressionFailure
  final case class DivisorMayBeZero(operator: String, interval: IntInterval) extends IntExpressionFailure
}

private[morphhdl] object IntExpressionAnalysis {
  import IntExpressionFailure._

  def parameterFacts(parameter: IntegerParameter): Option[IntExprFacts] = {
    val minimums = parameter.constraints.collect { case MinInclusive(value) => value }
    val maximums = parameter.constraints.collect { case MaxInclusive(value) => value }
    val lower = if (minimums.isEmpty) None else Some(minimums.max)
    val upper = if (maximums.isEmpty) None else Some(maximums.min)

    val interval = IntInterval(lower, upper)
    if ((lower.isDefined && upper.isDefined && lower.get > upper.get) || !interval.contains(parameter.default)) None
    else Some(IntExprFacts(parameter.default, interval))
  }

  def analyze(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts]
  ): Either[IntExpressionFailure, IntExprFacts] = expression match {
    case Literal(value) => Right(IntExprFacts(value, IntInterval.point(value)))
    case ParameterRef(name) =>
      parameters.get(name) match {
        case Some(facts) => Right(facts)
        case None        => Left(UnresolvedParameter(name))
      }
    case LocalParameterRef(name) =>
      localParameters.get(name) match {
        case Some(facts) => Right(facts)
        case None        => Left(UnresolvedLocalParameter(name))
      }
    case Negate(value) =>
      analyze(value, parameters, localParameters).map { facts =>
        IntExprFacts(
          -facts.defaultValue,
          IntInterval(facts.interval.upper.map(-_), facts.interval.lower.map(-_))
        )
      }
    case Add(left, right) =>
      analyzeBinary(left, right, parameters, localParameters) { (leftFacts, rightFacts) =>
        IntExprFacts(
          leftFacts.defaultValue + rightFacts.defaultValue,
          IntInterval(
            combine(leftFacts.interval.lower, rightFacts.interval.lower)(_ + _),
            combine(leftFacts.interval.upper, rightFacts.interval.upper)(_ + _)
          )
        )
      }
    case Subtract(left, right) =>
      analyzeBinary(left, right, parameters, localParameters) { (leftFacts, rightFacts) =>
        IntExprFacts(
          leftFacts.defaultValue - rightFacts.defaultValue,
          IntInterval(
            combine(leftFacts.interval.lower, rightFacts.interval.upper)(_ - _),
            combine(leftFacts.interval.upper, rightFacts.interval.lower)(_ - _)
          )
        )
      }
    case Multiply(left, right) =>
      analyzeBinary(left, right, parameters, localParameters) { (leftFacts, rightFacts) =>
        IntExprFacts(
          leftFacts.defaultValue * rightFacts.defaultValue,
          multiply(leftFacts.interval, rightFacts.interval)
        )
      }
    case Divide(left, right) =>
      analyzeDivisionLike(left, right, parameters, localParameters, "/") { (leftFacts, rightFacts) =>
        IntExprFacts(
          leftFacts.defaultValue / rightFacts.defaultValue,
          divide(leftFacts.interval, rightFacts.interval)
        )
      }
    case Modulo(left, right) =>
      analyzeDivisionLike(left, right, parameters, localParameters, "%") { (leftFacts, rightFacts) =>
        IntExprFacts(
          leftFacts.defaultValue % rightFacts.defaultValue,
          modulo(leftFacts.interval, rightFacts.interval)
        )
      }
  }

  def parameterReferences(expression: IntExpr): Vector[String] = expression match {
    case Literal(_) | LocalParameterRef(_) => Vector.empty
    case ParameterRef(name)                => Vector(name)
    case Negate(value)                     => parameterReferences(value)
    case Add(left, right)                  => parameterReferences(left) ++ parameterReferences(right)
    case Subtract(left, right)             => parameterReferences(left) ++ parameterReferences(right)
    case Multiply(left, right)             => parameterReferences(left) ++ parameterReferences(right)
    case Divide(left, right)               => parameterReferences(left) ++ parameterReferences(right)
    case Modulo(left, right)               => parameterReferences(left) ++ parameterReferences(right)
  }

  def localParameterReferences(expression: IntExpr): Vector[String] = expression match {
    case Literal(_) | ParameterRef(_) => Vector.empty
    case LocalParameterRef(name)      => Vector(name)
    case Negate(value)                => localParameterReferences(value)
    case Add(left, right)             => localParameterReferences(left) ++ localParameterReferences(right)
    case Subtract(left, right)        => localParameterReferences(left) ++ localParameterReferences(right)
    case Multiply(left, right)        => localParameterReferences(left) ++ localParameterReferences(right)
    case Divide(left, right)          => localParameterReferences(left) ++ localParameterReferences(right)
    case Modulo(left, right)          => localParameterReferences(left) ++ localParameterReferences(right)
  }

  private def analyzeBinary(
      left: IntExpr,
      right: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts]
  )(
      combineFacts: (IntExprFacts, IntExprFacts) => IntExprFacts
  ): Either[IntExpressionFailure, IntExprFacts] =
    analyze(left, parameters, localParameters).flatMap { leftFacts =>
      analyze(right, parameters, localParameters).map { rightFacts =>
        combineFacts(leftFacts, rightFacts)
      }
    }

  private def analyzeDivisionLike(
      left: IntExpr,
      right: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      operator: String
  )(
      combineFacts: (IntExprFacts, IntExprFacts) => IntExprFacts
  ): Either[IntExpressionFailure, IntExprFacts] =
    analyze(left, parameters, localParameters).flatMap { leftFacts =>
      analyze(right, parameters, localParameters).flatMap { rightFacts =>
        if (rightFacts.defaultValue == 0 || !rightFacts.interval.excludesZero)
          Left(DivisorMayBeZero(operator, rightFacts.interval))
        else Right(combineFacts(leftFacts, rightFacts))
      }
    }

  private def combine(
      left: Option[BigInt],
      right: Option[BigInt]
  )(operation: (BigInt, BigInt) => BigInt): Option[BigInt] =
    for {
      leftValue <- left
      rightValue <- right
    } yield operation(leftValue, rightValue)

  private def multiply(left: IntInterval, right: IntInterval): IntInterval =
    finiteEndpoints(left, right) match {
      case Some((leftLower, leftUpper, rightLower, rightUpper)) =>
        val products = Vector(
          leftLower * rightLower,
          leftLower * rightUpper,
          leftUpper * rightLower,
          leftUpper * rightUpper
        )
        IntInterval(Some(products.min), Some(products.max))
      case None => IntInterval(None, None)
    }

  private def divide(left: IntInterval, right: IntInterval): IntInterval =
    finiteEndpoints(left, right) match {
      case Some((leftLower, leftUpper, rightLower, rightUpper)) =>
        val quotients = Vector(
          leftLower / rightLower,
          leftLower / rightUpper,
          leftUpper / rightLower,
          leftUpper / rightUpper
        ) ++ (if (left.contains(0)) Vector(BigInt(0)) else Vector.empty)
        IntInterval(Some(quotients.min), Some(quotients.max))
      case None => IntInterval(None, None)
    }

  private def modulo(left: IntInterval, right: IntInterval): IntInterval =
    (right.lower, right.upper) match {
      case (Some(rightLower), Some(rightUpper)) =>
        val maximumMagnitude = rightLower.abs.max(rightUpper.abs) - 1
        val lower = left.lower match {
          case Some(value) if value >= 0 => Some(BigInt(0))
          case _                         => Some(-maximumMagnitude)
        }
        val upper = left.upper match {
          case Some(value) if value <= 0 => Some(BigInt(0))
          case _                         => Some(maximumMagnitude)
        }
        IntInterval(lower, upper)
      case _ => IntInterval(None, None)
    }

  private def finiteEndpoints(
      left: IntInterval,
      right: IntInterval
  ): Option[(BigInt, BigInt, BigInt, BigInt)] =
    for {
      leftLower <- left.lower
      leftUpper <- left.upper
      rightLower <- right.lower
      rightUpper <- right.upper
    } yield (leftLower, leftUpper, rightLower, rightUpper)
}
