package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{
  Add,
  AddressWidth,
  Divide,
  Literal,
  LocalParameterRef,
  GenerateIndexRef,
  Max,
  Min,
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
  final case class UnresolvedBooleanLocalParameter(name: String) extends IntExpressionFailure
  final case class UnresolvedLocalParameter(name: String) extends IntExpressionFailure
  final case class UnresolvedGenerateIndex(name: String) extends IntExpressionFailure
  final case class DivisorMayBeZero(operator: String, interval: IntInterval) extends IntExpressionFailure
  final case class AddressWidthOperandNotProvenPositive(interval: IntInterval)
      extends IntExpressionFailure
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
  ): Either[IntExpressionFailure, IntExprFacts] =
    analyze(expression, parameters, localParameters, booleanParameters, generateIndices, Map.empty)

  def analyze(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      booleanParameters: Map[String, BooleanParameter],
      generateIndices: Map[String, IntExprFacts],
      booleanLocalParameters: Map[String, Boolean]
  ): Either[IntExpressionFailure, IntExprFacts] =
    analyzeMemoized(
      expression,
      parameters,
      localParameters,
      booleanParameters,
      generateIndices,
      booleanLocalParameters,
      new java.util.IdentityHashMap[IntExpr, Either[IntExpressionFailure, IntExprFacts]]()
    )

  private def analyzeMemoized(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      booleanParameters: Map[String, BooleanParameter],
      generateIndices: Map[String, IntExprFacts],
      booleanLocalParameters: Map[String, Boolean],
      memo: java.util.IdentityHashMap[IntExpr, Either[IntExpressionFailure, IntExprFacts]]
  ): Either[IntExpressionFailure, IntExprFacts] = {
    final case class Frame(value: AnyRef, expanded: Boolean)
    final case class MissingBooleanReferences(
        publicParameter: Option[String],
        localParameter: Option[String]
    )
    val noMissingBooleanReferences = MissingBooleanReferences(None, None)
    val booleanMemo = new java.util.IdentityHashMap[BoolExpr, Either[IntExpressionFailure, Boolean]]()
    val integerBooleanReferences = new java.util.IdentityHashMap[IntExpr, MissingBooleanReferences]()
    val booleanReferences = new java.util.IdentityHashMap[BoolExpr, MissingBooleanReferences]()
    val work = scala.collection.mutable.ArrayBuffer(Frame(expression, expanded = false))

    def isDone(value: AnyRef): Boolean = value match {
      case integer: IntExpr => memo.containsKey(integer)
      case boolean: BoolExpr => booleanMemo.containsKey(boolean)
      case _ => true
    }
    def push(value: AnyRef): Unit = work += Frame(value, expanded = false)
    def facts(value: IntExpr): Either[IntExpressionFailure, IntExprFacts] = memo.get(value)
    def boolean(value: BoolExpr): Either[IntExpressionFailure, Boolean] = booleanMemo.get(value)
    def integerRefs(value: IntExpr): MissingBooleanReferences = integerBooleanReferences.get(value)
    def booleanRefs(value: BoolExpr): MissingBooleanReferences = booleanReferences.get(value)
    def merge(
        left: MissingBooleanReferences,
        right: MissingBooleanReferences
    ): MissingBooleanReferences =
      MissingBooleanReferences(
        left.publicParameter.orElse(right.publicParameter),
        left.localParameter.orElse(right.localParameter)
      )
    def mergeThree(
        first: MissingBooleanReferences,
        second: MissingBooleanReferences,
        third: MissingBooleanReferences
    ): MissingBooleanReferences = merge(merge(first, second), third)
    def binary(
        left: IntExpr,
        right: IntExpr
    )(operation: (IntExprFacts, IntExprFacts) => IntExprFacts): Either[IntExpressionFailure, IntExprFacts] =
      facts(left).flatMap(leftFacts => facts(right).map(rightFacts => operation(leftFacts, rightFacts)))
    def compare(
        left: IntExpr,
        right: IntExpr
    )(operation: (BigInt, BigInt) => Boolean): Either[IntExpressionFailure, Boolean] =
      facts(left).flatMap(leftFacts =>
        facts(right).map(rightFacts => operation(leftFacts.defaultValue, rightFacts.defaultValue))
      )

    while (work.nonEmpty) {
      val frame = work.remove(work.length - 1)
      if (!isDone(frame.value)) {
        if (!frame.expanded) {
          work += Frame(frame.value, expanded = true)
          frame.value match {
            case _: Literal | _: ParameterRef | _: LocalParameterRef | _: GenerateIndexRef =>
            case AddressWidth(operand) => push(operand)
            case Negate(operand)       => push(operand)
            case Add(left, right)      => push(right); push(left)
            case Subtract(left, right) => push(right); push(left)
            case Multiply(left, right) => push(right); push(left)
            case Divide(left, right)   => push(right); push(left)
            case Modulo(left, right)   => push(right); push(left)
            case Min(left, right)      => push(right); push(left)
            case Max(left, right)      => push(right); push(left)
            case Select(condition, whenTrue, whenFalse) =>
              push(whenFalse)
              push(whenTrue)
              push(condition)
            case _: BoolExpr.Literal | _: BoolExpr.ParameterRef | _: BoolExpr.LocalParameterRef =>
            case BoolExpr.Not(operand)       => push(operand)
            case BoolExpr.And(left, right)   => push(right); push(left)
            case BoolExpr.Or(left, right)    => push(right); push(left)
            case BoolExpr.LessThan(left, right)           => push(right); push(left)
            case BoolExpr.LessThanOrEqual(left, right)    => push(right); push(left)
            case BoolExpr.GreaterThan(left, right)        => push(right); push(left)
            case BoolExpr.GreaterThanOrEqual(left, right) => push(right); push(left)
            case BoolExpr.Equal(left, right)              => push(right); push(left)
            case BoolExpr.NotEqual(left, right)           => push(right); push(left)
          }
        } else {
          frame.value match {
            case value @ Literal(number) =>
              integerBooleanReferences.put(value, noMissingBooleanReferences)
              memo.put(value, Right(IntExprFacts(number, IntInterval.point(number))))
            case value @ ParameterRef(name) =>
              integerBooleanReferences.put(value, noMissingBooleanReferences)
              memo.put(value, parameters.get(name).toRight(UnresolvedParameter(name)))
            case value @ LocalParameterRef(name) =>
              integerBooleanReferences.put(value, noMissingBooleanReferences)
              memo.put(value, localParameters.get(name).toRight(UnresolvedLocalParameter(name)))
            case value @ GenerateIndexRef(name) =>
              integerBooleanReferences.put(value, noMissingBooleanReferences)
              memo.put(value, generateIndices.get(name).toRight(UnresolvedGenerateIndex(name)))
            case value @ AddressWidth(operand) =>
              integerBooleanReferences.put(value, integerRefs(operand))
              memo.put(
                value,
                facts(operand).flatMap { operandFacts =>
                  if (operandFacts.defaultValue < 1 || !operandFacts.interval.lower.exists(_ >= 1))
                    Left(AddressWidthOperandNotProvenPositive(operandFacts.interval))
                  else
                    Right(
                      IntExprFacts(
                        addressWidthValue(operandFacts.defaultValue),
                        IntInterval(
                          operandFacts.interval.lower.map(addressWidthValue),
                          operandFacts.interval.upper.map(addressWidthValue)
                        )
                      )
                    )
                }
              )
            case value @ Negate(operand) =>
              integerBooleanReferences.put(value, integerRefs(operand))
              memo.put(
                value,
                facts(operand).map { operandFacts =>
                  IntExprFacts(
                    -operandFacts.defaultValue,
                    IntInterval(operandFacts.interval.upper.map(-_), operandFacts.interval.lower.map(-_))
                  )
                }
              )
            case value @ Add(left, right) =>
              integerBooleanReferences.put(value, merge(integerRefs(left), integerRefs(right)))
              memo.put(
                value,
                binary(left, right) { (leftFacts, rightFacts) =>
                  IntExprFacts(
                    leftFacts.defaultValue + rightFacts.defaultValue,
                    IntInterval(
                      combine(leftFacts.interval.lower, rightFacts.interval.lower)(_ + _),
                      combine(leftFacts.interval.upper, rightFacts.interval.upper)(_ + _)
                    )
                  )
                }
              )
            case value @ Subtract(left, right) =>
              integerBooleanReferences.put(value, merge(integerRefs(left), integerRefs(right)))
              memo.put(
                value,
                binary(left, right) { (leftFacts, rightFacts) =>
                  IntExprFacts(
                    leftFacts.defaultValue - rightFacts.defaultValue,
                    IntInterval(
                      combine(leftFacts.interval.lower, rightFacts.interval.upper)(_ - _),
                      combine(leftFacts.interval.upper, rightFacts.interval.lower)(_ - _)
                    )
                  )
                }
              )
            case value @ Multiply(left, right) =>
              integerBooleanReferences.put(value, merge(integerRefs(left), integerRefs(right)))
              memo.put(
                value,
                binary(left, right) { (leftFacts, rightFacts) =>
                  IntExprFacts(
                    leftFacts.defaultValue * rightFacts.defaultValue,
                    multiply(leftFacts.interval, rightFacts.interval)
                  )
                }
              )
            case value @ Divide(left, right) =>
              integerBooleanReferences.put(value, merge(integerRefs(left), integerRefs(right)))
              memo.put(
                value,
                facts(left).flatMap { leftFacts =>
                  facts(right).flatMap { rightFacts =>
                    if (rightFacts.defaultValue == 0 || !rightFacts.interval.excludesZero)
                      Left(DivisorMayBeZero("/", rightFacts.interval))
                    else Right(IntExprFacts(leftFacts.defaultValue / rightFacts.defaultValue, divide(leftFacts.interval, rightFacts.interval)))
                  }
                }
              )
            case value @ Modulo(left, right) =>
              integerBooleanReferences.put(value, merge(integerRefs(left), integerRefs(right)))
              memo.put(
                value,
                facts(left).flatMap { leftFacts =>
                  facts(right).flatMap { rightFacts =>
                    if (rightFacts.defaultValue == 0 || !rightFacts.interval.excludesZero)
                      Left(DivisorMayBeZero("%", rightFacts.interval))
                    else Right(IntExprFacts(leftFacts.defaultValue % rightFacts.defaultValue, modulo(leftFacts.interval, rightFacts.interval)))
                  }
                }
              )
            case value @ Min(left, right) =>
              integerBooleanReferences.put(value, merge(integerRefs(left), integerRefs(right)))
              memo.put(
                value,
                binary(left, right) { (leftFacts, rightFacts) =>
                  IntExprFacts(
                    leftFacts.defaultValue.min(rightFacts.defaultValue),
                    IntInterval(
                      combine(leftFacts.interval.lower, rightFacts.interval.lower)(_.min(_)),
                      knownExtremum(leftFacts.interval.upper, rightFacts.interval.upper)(_.min(_))
                    )
                  )
                }
              )
            case value @ Max(left, right) =>
              integerBooleanReferences.put(value, merge(integerRefs(left), integerRefs(right)))
              memo.put(
                value,
                binary(left, right) { (leftFacts, rightFacts) =>
                  IntExprFacts(
                    leftFacts.defaultValue.max(rightFacts.defaultValue),
                    IntInterval(
                      knownExtremum(leftFacts.interval.lower, rightFacts.interval.lower)(_.max(_)),
                      combine(leftFacts.interval.upper, rightFacts.interval.upper)(_.max(_))
                    )
                  )
                }
              )
            case value @ Select(condition, whenTrue, whenFalse) =>
              val conditionReferences = booleanRefs(condition)
              integerBooleanReferences.put(
                value,
                mergeThree(conditionReferences, integerRefs(whenTrue), integerRefs(whenFalse))
              )
              val conditionResult: Either[IntExpressionFailure, Boolean] =
                conditionReferences.publicParameter match {
                  case Some(name) => Left(UnresolvedBooleanParameter(name))
                  case None => conditionReferences.localParameter match {
                    case Some(name) => Left(UnresolvedBooleanLocalParameter(name))
                    case None       => boolean(condition)
                  }
                }
              memo.put(
                value,
                conditionResult.flatMap { conditionDefault =>
                  facts(whenTrue).flatMap { trueFacts =>
                    facts(whenFalse).map { falseFacts =>
                      IntExprFacts(
                        if (conditionDefault) trueFacts.defaultValue else falseFacts.defaultValue,
                        hull(trueFacts.interval, falseFacts.interval)
                      )
                    }
                  }
                }
              )
            case value @ BoolExpr.Literal(result) =>
              booleanReferences.put(value, noMissingBooleanReferences)
              booleanMemo.put(value, Right(result))
            case value @ BoolExpr.ParameterRef(name) =>
              val missing = if (booleanParameters.contains(name)) None else Some(name)
              booleanReferences.put(value, MissingBooleanReferences(missing, None))
              booleanMemo.put(value, booleanParameters.get(name).map(_.default).toRight(UnresolvedBooleanParameter(name)))
            case value @ BoolExpr.LocalParameterRef(name) =>
              val missing = if (booleanLocalParameters.contains(name)) None else Some(name)
              booleanReferences.put(value, MissingBooleanReferences(None, missing))
              booleanMemo.put(value, booleanLocalParameters.get(name).toRight(UnresolvedBooleanLocalParameter(name)))
            case value @ BoolExpr.Not(operand) =>
              booleanReferences.put(value, booleanRefs(operand))
              booleanMemo.put(value, boolean(operand).map(result => !result))
            case value @ BoolExpr.And(left, right) =>
              booleanReferences.put(value, merge(booleanRefs(left), booleanRefs(right)))
              booleanMemo.put(value, boolean(left).flatMap(a => boolean(right).map(b => a && b)))
            case value @ BoolExpr.Or(left, right) =>
              booleanReferences.put(value, merge(booleanRefs(left), booleanRefs(right)))
              booleanMemo.put(value, boolean(left).flatMap(a => boolean(right).map(b => a || b)))
            case value @ BoolExpr.LessThan(left, right) =>
              booleanReferences.put(value, merge(integerRefs(left), integerRefs(right)))
              booleanMemo.put(value, compare(left, right)(_ < _))
            case value @ BoolExpr.LessThanOrEqual(left, right) =>
              booleanReferences.put(value, merge(integerRefs(left), integerRefs(right)))
              booleanMemo.put(value, compare(left, right)(_ <= _))
            case value @ BoolExpr.GreaterThan(left, right) =>
              booleanReferences.put(value, merge(integerRefs(left), integerRefs(right)))
              booleanMemo.put(value, compare(left, right)(_ > _))
            case value @ BoolExpr.GreaterThanOrEqual(left, right) =>
              booleanReferences.put(value, merge(integerRefs(left), integerRefs(right)))
              booleanMemo.put(value, compare(left, right)(_ >= _))
            case value @ BoolExpr.Equal(left, right) =>
              booleanReferences.put(value, merge(integerRefs(left), integerRefs(right)))
              booleanMemo.put(value, compare(left, right)(_ == _))
            case value @ BoolExpr.NotEqual(left, right) =>
              booleanReferences.put(value, merge(integerRefs(left), integerRefs(right)))
              booleanMemo.put(value, compare(left, right)(_ != _))
          }
        }
      }
    }
    memo.get(expression)
  }

  def parameterReferences(expression: IntExpr): Vector[String] =
    references(expression, IntegerParameterReference)

  def localParameterReferences(expression: IntExpr): Vector[String] =
    references(expression, IntegerLocalParameterReference)

  def booleanParameterReferences(expression: IntExpr): Vector[String] =
    references(expression, BooleanParameterReference)

  def booleanLocalParameterReferences(expression: IntExpr): Vector[String] =
    references(expression, BooleanLocalParameterReference)

  private val IntegerParameterReference = 0
  private val IntegerLocalParameterReference = 1
  private val BooleanParameterReference = 2
  private val BooleanLocalParameterReference = 3

  /**
    * Walks the mixed integer/Boolean graph once per object identity. This keeps reference
    * collection linear for expression DAGs while retaining the historical left-to-right order.
    */
  private def references(expression: AnyRef, referenceKind: Int): Vector[String] = {
    val result = Vector.newBuilder[String]
    val work = scala.collection.mutable.ArrayBuffer[AnyRef](expression)
    val seenIntegers = new java.util.IdentityHashMap[IntExpr, java.lang.Boolean]()
    val seenBooleans = new java.util.IdentityHashMap[BoolExpr, java.lang.Boolean]()

    def pushInteger(value: IntExpr): Unit = work += value
    def pushBoolean(value: BoolExpr): Unit = work += value

    while (work.nonEmpty) {
      work.remove(work.length - 1) match {
        case value: IntExpr if !seenIntegers.containsKey(value) =>
          seenIntegers.put(value, java.lang.Boolean.TRUE)
          value match {
            case Literal(_) | GenerateIndexRef(_) =>
            case ParameterRef(name) if referenceKind == IntegerParameterReference => result += name
            case ParameterRef(_) =>
            case LocalParameterRef(name) if referenceKind == IntegerLocalParameterReference => result += name
            case LocalParameterRef(_) =>
            case AddressWidth(operand) => pushInteger(operand)
            case Negate(operand)       => pushInteger(operand)
            case Add(left, right)      => pushInteger(right); pushInteger(left)
            case Subtract(left, right) => pushInteger(right); pushInteger(left)
            case Multiply(left, right) => pushInteger(right); pushInteger(left)
            case Divide(left, right)   => pushInteger(right); pushInteger(left)
            case Modulo(left, right)   => pushInteger(right); pushInteger(left)
            case Min(left, right)      => pushInteger(right); pushInteger(left)
            case Max(left, right)      => pushInteger(right); pushInteger(left)
            case Select(condition, whenTrue, whenFalse) =>
              pushInteger(whenFalse)
              pushInteger(whenTrue)
              pushBoolean(condition)
          }
        case value: BoolExpr if !seenBooleans.containsKey(value) =>
          seenBooleans.put(value, java.lang.Boolean.TRUE)
          value match {
            case BoolExpr.Literal(_) =>
            case BoolExpr.ParameterRef(name) if referenceKind == BooleanParameterReference => result += name
            case BoolExpr.ParameterRef(_) =>
            case BoolExpr.LocalParameterRef(name) if referenceKind == BooleanLocalParameterReference => result += name
            case BoolExpr.LocalParameterRef(_) =>
            case BoolExpr.Not(operand)       => pushBoolean(operand)
            case BoolExpr.And(left, right)   => pushBoolean(right); pushBoolean(left)
            case BoolExpr.Or(left, right)    => pushBoolean(right); pushBoolean(left)
            case BoolExpr.LessThan(left, right)           => pushInteger(right); pushInteger(left)
            case BoolExpr.LessThanOrEqual(left, right)    => pushInteger(right); pushInteger(left)
            case BoolExpr.GreaterThan(left, right)        => pushInteger(right); pushInteger(left)
            case BoolExpr.GreaterThanOrEqual(left, right) => pushInteger(right); pushInteger(left)
            case BoolExpr.Equal(left, right)              => pushInteger(right); pushInteger(left)
            case BoolExpr.NotEqual(left, right)           => pushInteger(right); pushInteger(left)
          }
        case _ =>
      }
    }
    result.result()
  }

  private def combine(
      left: Option[BigInt],
      right: Option[BigInt]
  )(operation: (BigInt, BigInt) => BigInt): Option[BigInt] =
    for {
      leftValue <- left
      rightValue <- right
    } yield operation(leftValue, rightValue)

  /**
    * An extremum can retain a one-sided bound from either operand: `min` is no greater than
    * either operand, and `max` is no less than either operand.
    */
  private def knownExtremum(
      left: Option[BigInt],
      right: Option[BigInt]
  )(operation: (BigInt, BigInt) => BigInt): Option[BigInt] =
    (left, right) match {
      case (Some(a), Some(b)) => Some(operation(a, b))
      case (some @ Some(_), None) => some
      case (None, some @ Some(_)) => some
      case (None, None) => None
    }

  private def addressWidthValue(value: BigInt): BigInt =
    if (value <= 2) BigInt(1) else BigInt((value - 1).bitLength)

  /** Iterative direct-nesting utilities shared by validation and target lowering. */
  private[morphhdl] def peelDirectAddressWidths(expression: IntExpr): (Int, IntExpr) = {
    var layers = 0
    var base = expression
    var peeling = true
    while (peeling) {
      base match {
        case AddressWidth(inner) =>
          layers += 1
          base = inner
        case _ => peeling = false
      }
    }
    layers -> base
  }

  private[morphhdl] def wrapDirectAddressWidths(base: IntExpr, layers: Int): IntExpr = {
    var result = base
    var remaining = layers
    while (remaining > 0) {
      result = AddressWidth(result)
      remaining -= 1
    }
    result
  }

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
