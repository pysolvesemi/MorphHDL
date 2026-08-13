package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{
  Add,
  Divide,
  Literal,
  LocalParameterRef,
  GenerateIndexRef,
  Modulo,
  Multiply,
  Negate,
  ParameterRef,
  Select,
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
  final case class UnresolvedBooleanParameter(name: String) extends IntExpressionFailure
  final case class UnresolvedLocalParameter(name: String) extends IntExpressionFailure
  final case class UnresolvedGenerateIndex(name: String) extends IntExpressionFailure
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
      localParameters: Map[String, IntExprFacts],
      generateIndices: Map[String, IntExprFacts] = Map.empty
  ): Either[IntExpressionFailure, IntExprFacts] =
    analyze(expression, parameters, localParameters, Map.empty, generateIndices)

  /**
    * Analyzes an integer expression over its complete legal domain. Conditional selections use
    * the exact Boolean default witness while conservatively hulling both value-branch domains.
    * The condition and both branches are evaluated before a failure is selected, so an inactive
    * invalid branch cannot be hidden by the default condition.
    */
  def analyze(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      booleanParameters: Map[String, BooleanParameter],
      generateIndices: Map[String, IntExprFacts]
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
    case GenerateIndexRef(name) =>
      generateIndices.get(name) match {
        case Some(facts) => Right(facts)
        case None        => Left(UnresolvedGenerateIndex(name))
      }
    case Negate(value) =>
      analyze(value, parameters, localParameters, booleanParameters, generateIndices).map { facts =>
        IntExprFacts(
          -facts.defaultValue,
          IntInterval(facts.interval.upper.map(-_), facts.interval.lower.map(-_))
        )
      }
    case Add(left, right) =>
      analyzeBinary(left, right, parameters, localParameters, booleanParameters, generateIndices) {
        (leftFacts, rightFacts) =>
        IntExprFacts(
          leftFacts.defaultValue + rightFacts.defaultValue,
          IntInterval(
            combine(leftFacts.interval.lower, rightFacts.interval.lower)(_ + _),
            combine(leftFacts.interval.upper, rightFacts.interval.upper)(_ + _)
          )
        )
      }
    case Subtract(left, right) =>
      analyzeBinary(left, right, parameters, localParameters, booleanParameters, generateIndices) {
        (leftFacts, rightFacts) =>
        IntExprFacts(
          leftFacts.defaultValue - rightFacts.defaultValue,
          IntInterval(
            combine(leftFacts.interval.lower, rightFacts.interval.upper)(_ - _),
            combine(leftFacts.interval.upper, rightFacts.interval.lower)(_ - _)
          )
        )
      }
    case Multiply(left, right) =>
      analyzeBinary(left, right, parameters, localParameters, booleanParameters, generateIndices) {
        (leftFacts, rightFacts) =>
        IntExprFacts(
          leftFacts.defaultValue * rightFacts.defaultValue,
          multiply(leftFacts.interval, rightFacts.interval)
        )
      }
    case Divide(left, right) =>
      analyzeDivisionLike(
        left,
        right,
        parameters,
        localParameters,
        booleanParameters,
        generateIndices,
        "/"
      ) { (leftFacts, rightFacts) =>
        IntExprFacts(
          leftFacts.defaultValue / rightFacts.defaultValue,
          divide(leftFacts.interval, rightFacts.interval)
        )
      }
    case Modulo(left, right) =>
      analyzeDivisionLike(
        left,
        right,
        parameters,
        localParameters,
        booleanParameters,
        generateIndices,
        "%"
      ) { (leftFacts, rightFacts) =>
        IntExprFacts(
          leftFacts.defaultValue % rightFacts.defaultValue,
          modulo(leftFacts.interval, rightFacts.interval)
        )
      }
    case Select(condition, whenTrue, whenFalse) =>
      val conditionResult = BoolExpressionAnalysis
        .evaluateDefault(condition, booleanParameters, parameters, localParameters, generateIndices)
        .left
        .map {
          case BoolExpressionFailure.UnresolvedParameter(name) => UnresolvedBooleanParameter(name)
          case BoolExpressionFailure.InvalidIntegerExpression(failure) => failure
        }
      val trueResult = analyze(whenTrue, parameters, localParameters, booleanParameters, generateIndices)
      val falseResult = analyze(whenFalse, parameters, localParameters, booleanParameters, generateIndices)
      conditionResult.flatMap { conditionDefault =>
        trueResult.flatMap { trueFacts =>
          falseResult.map { falseFacts =>
            IntExprFacts(
              if (conditionDefault) trueFacts.defaultValue else falseFacts.defaultValue,
              hull(trueFacts.interval, falseFacts.interval)
            )
          }
        }
      }
  }

  def parameterReferences(expression: IntExpr): Vector[String] = expression match {
    case Literal(_) | LocalParameterRef(_) | GenerateIndexRef(_) => Vector.empty
    case ParameterRef(name)                                      => Vector(name)
    case Negate(value)                                           => parameterReferences(value)
    case Add(left, right)      => parameterReferences(left) ++ parameterReferences(right)
    case Subtract(left, right) => parameterReferences(left) ++ parameterReferences(right)
    case Multiply(left, right) => parameterReferences(left) ++ parameterReferences(right)
    case Divide(left, right)   => parameterReferences(left) ++ parameterReferences(right)
    case Modulo(left, right)   => parameterReferences(left) ++ parameterReferences(right)
    case Select(condition, whenTrue, whenFalse) =>
      BoolExpressionAnalysis.integerParameterReferences(condition) ++
        parameterReferences(whenTrue) ++ parameterReferences(whenFalse)
  }

  def localParameterReferences(expression: IntExpr): Vector[String] = expression match {
    case Literal(_) | ParameterRef(_) | GenerateIndexRef(_) => Vector.empty
    case LocalParameterRef(name)                            => Vector(name)
    case Negate(value)                                      => localParameterReferences(value)
    case Add(left, right)      => localParameterReferences(left) ++ localParameterReferences(right)
    case Subtract(left, right) => localParameterReferences(left) ++ localParameterReferences(right)
    case Multiply(left, right) => localParameterReferences(left) ++ localParameterReferences(right)
    case Divide(left, right)   => localParameterReferences(left) ++ localParameterReferences(right)
    case Modulo(left, right)   => localParameterReferences(left) ++ localParameterReferences(right)
    case Select(condition, whenTrue, whenFalse) =>
      BoolExpressionAnalysis.localParameterReferences(condition) ++
        localParameterReferences(whenTrue) ++ localParameterReferences(whenFalse)
  }

  def booleanParameterReferences(expression: IntExpr): Vector[String] = expression match {
    case Literal(_) | ParameterRef(_) | LocalParameterRef(_) | GenerateIndexRef(_) => Vector.empty
    case Negate(value) => booleanParameterReferences(value)
    case Add(left, right) => booleanParameterReferences(left) ++ booleanParameterReferences(right)
    case Subtract(left, right) => booleanParameterReferences(left) ++ booleanParameterReferences(right)
    case Multiply(left, right) => booleanParameterReferences(left) ++ booleanParameterReferences(right)
    case Divide(left, right) => booleanParameterReferences(left) ++ booleanParameterReferences(right)
    case Modulo(left, right) => booleanParameterReferences(left) ++ booleanParameterReferences(right)
    case Select(condition, whenTrue, whenFalse) =>
      BoolExpressionAnalysis.parameterReferences(condition) ++
        booleanParameterReferences(whenTrue) ++ booleanParameterReferences(whenFalse)
  }

  private def analyzeBinary(
      left: IntExpr,
      right: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      booleanParameters: Map[String, BooleanParameter],
      generateIndices: Map[String, IntExprFacts]
  )(
      combineFacts: (IntExprFacts, IntExprFacts) => IntExprFacts
  ): Either[IntExpressionFailure, IntExprFacts] =
    analyze(left, parameters, localParameters, booleanParameters, generateIndices).flatMap { leftFacts =>
      analyze(right, parameters, localParameters, booleanParameters, generateIndices).map { rightFacts =>
        combineFacts(leftFacts, rightFacts)
      }
    }

  private def analyzeDivisionLike(
      left: IntExpr,
      right: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      booleanParameters: Map[String, BooleanParameter],
      generateIndices: Map[String, IntExprFacts],
      operator: String
  )(
      combineFacts: (IntExprFacts, IntExprFacts) => IntExprFacts
  ): Either[IntExpressionFailure, IntExprFacts] =
    analyze(left, parameters, localParameters, booleanParameters, generateIndices).flatMap { leftFacts =>
      analyze(right, parameters, localParameters, booleanParameters, generateIndices).flatMap { rightFacts =>
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

  private def hull(left: IntInterval, right: IntInterval): IntInterval =
    IntInterval(
      for { a <- left.lower; b <- right.lower } yield a.min(b),
      for { a <- left.upper; b <- right.upper } yield a.max(b)
    )

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
