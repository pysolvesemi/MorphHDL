package spinal.core

/** Kind of one selected or derived native Scala `Int` value. */
sealed trait ExternalNativeIntShadowKind {
  def label: String
}

object ExternalNativeIntShadowKind {
  case object ConstructorArgument extends ExternalNativeIntShadowKind {
    override val label: String = "constructor-argument"
  }

  case object LocalValue extends ExternalNativeIntShadowKind {
    override val label: String = "local-value"
  }
}

/** Deterministic identity for one native `Int` slot. */
final case class ExternalNativeIntShadowSlotToken(
    name: String,
    kind: ExternalNativeIntShadowKind,
    sourceLocation: String
)

/** One native `Int` witness with definition-side and actual-side expressions. */
final case class ExternalNativeIntShadowSlot(
    token: ExternalNativeIntShadowSlotToken,
    witness: Int,
    definitionExpression: ElaborationIntegerExpression,
    actualExpression: ElaborationIntegerExpression
)

/** Deterministic identity for one native Boolean predicate. */
final case class ExternalNativeIntShadowPredicateToken(
    name: String,
    operation: String,
    sourceLocation: String
)

/** One native Boolean witness with definition-side and actual-side predicates. */
final case class ExternalNativeIntShadowPredicate(
    token: ExternalNativeIntShadowPredicateToken,
    witness: Boolean,
    definitionExpression: ElaborationBooleanExpression,
    actualExpression: ElaborationBooleanExpression
)

/** Shadow provenance retained against one exact native child Component. */
final case class ExternalNativeIntComponentShadowRecord(
    boundaryToken: ExternalNativeIntFormalizationToken,
    parentBoundaryToken: Option[ExternalNativeIntFormalizationToken],
    ownerClassName: String,
    binding: ExternalFormalParameterBinding,
    slots: Vector[ExternalNativeIntShadowSlot],
    predicates: Vector[ExternalNativeIntShadowPredicate] = Vector.empty
)

/** Shadow provenance retained against one exact native Data region. */
final case class ExternalNativeIntRegionShadowRecord(
    boundaryToken: ExternalNativeIntFormalizationToken,
    parentBoundaryToken: Option[ExternalNativeIntFormalizationToken],
    ownerClassName: String,
    formalBinding: Option[ExternalFormalParameterBinding],
    slots: Vector[ExternalNativeIntShadowSlot],
    predicates: Vector[ExternalNativeIntShadowPredicate] = Vector.empty
)

private[core] sealed trait ExternalNativeIntRelativeExpression

private[core] object ExternalNativeIntRelativeExpression {
  case object Root extends ExternalNativeIntRelativeExpression
  final case class Literal(value: BigInt) extends ExternalNativeIntRelativeExpression
  final case class Add(left: ExternalNativeIntRelativeExpression, right: ExternalNativeIntRelativeExpression)
      extends ExternalNativeIntRelativeExpression
  final case class Subtract(left: ExternalNativeIntRelativeExpression, right: ExternalNativeIntRelativeExpression)
      extends ExternalNativeIntRelativeExpression
  final case class Multiply(left: ExternalNativeIntRelativeExpression, right: ExternalNativeIntRelativeExpression)
      extends ExternalNativeIntRelativeExpression
  final case class Divide(left: ExternalNativeIntRelativeExpression, right: ExternalNativeIntRelativeExpression)
      extends ExternalNativeIntRelativeExpression
  final case class Modulo(left: ExternalNativeIntRelativeExpression, right: ExternalNativeIntRelativeExpression)
      extends ExternalNativeIntRelativeExpression
  final case class Min(left: ExternalNativeIntRelativeExpression, right: ExternalNativeIntRelativeExpression)
      extends ExternalNativeIntRelativeExpression
  final case class Max(left: ExternalNativeIntRelativeExpression, right: ExternalNativeIntRelativeExpression)
      extends ExternalNativeIntRelativeExpression
  final case class Negate(value: ExternalNativeIntRelativeExpression)
      extends ExternalNativeIntRelativeExpression
  final case class CeilLog2(value: ExternalNativeIntRelativeExpression)
      extends ExternalNativeIntRelativeExpression
  final case class AddressWidth(value: ExternalNativeIntRelativeExpression)
      extends ExternalNativeIntRelativeExpression
  final case class Log2Down(value: ExternalNativeIntRelativeExpression)
      extends ExternalNativeIntRelativeExpression
  final case class BooleanToInt(value: ExternalNativeIntRelativePredicate)
      extends ExternalNativeIntRelativeExpression

  final case class Facts(
      verilog: String,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      parameters: Vector[ElaborationIntegerParameter],
      parameterRoots: Vector[ElaborationIntegerParameterRoot] = Vector.empty
  ) {
    def expression(sourceLocation: String): ElaborationIntegerExpression =
      ElaborationIntegerExpression(
        verilog = verilog,
        default = default,
        minimum = minimum,
        maximum = maximum,
        parameters = parameters,
        sourceLocation = Option(sourceLocation).filter(_.nonEmpty),
        parameterRoots = parameterRoots
      )
  }

  sealed trait Failure {
    def code: String
    def detail: String
  }

  final case class DivisorMayBeZero(operator: String, minimum: BigInt, maximum: BigInt)
      extends Failure {
    override val code: String = "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-DIVISOR-ZERO-DOMAIN"
    override val detail: String =
      s"native Int '$operator' divisor domain [$minimum, $maximum] includes zero"
  }

  final case class OperandMustBePositive(operation: String, minimum: BigInt) extends Failure {
    override val code: String = "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-HELPER-DOMAIN-NONPOSITIVE"
    override val detail: String =
      s"native Int helper '$operation' requires a positive complete domain, but minimum is $minimum"
  }

  final case class DomainOutsideInt(operation: String, minimum: BigInt, maximum: BigInt)
      extends Failure {
    override val code: String = "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-DOMAIN-OVERFLOW"
    override val detail: String =
      s"native Int operation '$operation' has complete domain [$minimum, $maximum] outside Scala Int"
  }

  def binary(
      operation: String,
      left: ExternalNativeIntRelativeExpression,
      right: ExternalNativeIntRelativeExpression
  ): ExternalNativeIntRelativeExpression = operation match {
    case "+"   => Add(left, right)
    case "-"   => Subtract(left, right)
    case "*"   => Multiply(left, right)
    case "/"   => Divide(left, right)
    case "%"   => Modulo(left, right)
    case "min" => Min(left, right)
    case "max" => Max(left, right)
    case other => throw new IllegalArgumentException(s"unsupported native Int shadow binary operation '$other'")
  }

  def unary(
      operation: String,
      value: ExternalNativeIntRelativeExpression
  ): ExternalNativeIntRelativeExpression = operation match {
    case "negate"       => Negate(value)
    case "ceilLog2" | "log2Up" => CeilLog2(value)
    case "addressWidth"          => AddressWidth(value)
    case "log2Down"              => Log2Down(value)
    case other => throw new IllegalArgumentException(s"unsupported native Int shadow unary operation '$other'")
  }

  def booleanToInt(
      value: ExternalNativeIntRelativePredicate
  ): ExternalNativeIntRelativeExpression = BooleanToInt(value)

  /**
    * A native helper such as `log2Up(depth)` has witness zero at depth one even
    * when the declaration using it exists only in a `depth > 1` alternative.
    * The concrete Spinal graph already proved that alternative. Retain a
    * portable one-bit minimum for declaration geometry without changing the
    * arithmetic expression used in comparisons or values.
    */
  def positiveWidth(
      expression: ExternalNativeIntRelativeExpression
  ): ExternalNativeIntRelativeExpression = expression match {
    case Root | _: Literal => expression
    case Add(left, right) => Add(positiveWidth(left), positiveWidth(right))
    case Subtract(left, right) =>
      Subtract(positiveWidth(left), positiveWidth(right))
    case Multiply(left, right) =>
      Multiply(positiveWidth(left), positiveWidth(right))
    case Divide(left, right) => Divide(positiveWidth(left), positiveWidth(right))
    case Modulo(left, right) => Modulo(positiveWidth(left), positiveWidth(right))
    case Min(left, right) => Min(positiveWidth(left), positiveWidth(right))
    case Max(left, right) => Max(positiveWidth(left), positiveWidth(right))
    case Negate(value) => Negate(positiveWidth(value))
    case CeilLog2(value) => AddressWidth(positiveWidth(value))
    case AddressWidth(value) => AddressWidth(positiveWidth(value))
    case Log2Down(value) => Log2Down(positiveWidth(value))
    case BooleanToInt(value) => BooleanToInt(value)
  }

  def lower(
      expression: ExternalNativeIntRelativeExpression,
      root: ElaborationIntegerExpression
  ): Either[Failure, Facts] = {
    def checked(operation: String, facts: Facts): Either[Failure, Facts] =
      if (
        facts.minimum < BigInt(Int.MinValue) ||
        facts.maximum > BigInt(Int.MaxValue)
      ) Left(DomainOutsideInt(operation, facts.minimum, facts.maximum))
      else Right(facts)

    def mergeParameters(left: Facts, right: Facts): Vector[ElaborationIntegerParameter] =
      (left.parameters ++ right.parameters)
        .groupBy(_.name)
        .toVector
        .sortBy(_._1)
        .map { case (_, values) => values.head }

    def loop(value: ExternalNativeIntRelativeExpression): Either[Failure, Facts] = value match {
      case Root =>
        checked(
          "root",
          Facts(
            root.verilog,
            root.default,
            root.minimum,
            root.maximum,
            root.parameters,
            root.completedParameterRoots
          )
        )
      case Literal(number) =>
        checked("literal", Facts(number.toString, number, number, number, Vector.empty))
      case Add(left, right) =>
        for {
          l <- loop(left)
          r <- loop(right)
          out <- checked(
            "+",
            Facts(
              s"(${l.verilog} + ${r.verilog})",
              l.default + r.default,
              l.minimum + r.minimum,
              l.maximum + r.maximum,
              mergeParameters(l, r),
              ElabInt.mergeParameterRoots(l.parameterRoots, r.parameterRoots)
            )
          )
        } yield out
      case Subtract(left, right) =>
        for {
          l <- loop(left)
          r <- loop(right)
          out <- checked(
            "-",
            Facts(
              s"(${l.verilog} - ${r.verilog})",
              l.default - r.default,
              l.minimum - r.maximum,
              l.maximum - r.minimum,
              mergeParameters(l, r),
              ElabInt.mergeParameterRoots(l.parameterRoots, r.parameterRoots)
            )
          )
        } yield out
      case Multiply(left, right) =>
        for {
          l <- loop(left)
          r <- loop(right)
          products = Vector(
            l.minimum * r.minimum,
            l.minimum * r.maximum,
            l.maximum * r.minimum,
            l.maximum * r.maximum
          )
          out <- checked(
            "*",
            Facts(
              s"(${l.verilog} * ${r.verilog})",
              l.default * r.default,
              products.min,
              products.max,
              mergeParameters(l, r),
              ElabInt.mergeParameterRoots(l.parameterRoots, r.parameterRoots)
            )
          )
        } yield out
      case Divide(left, right) =>
        for {
          l <- loop(left)
          r <- loop(right)
          _ <- if (r.minimum <= 0 && r.maximum >= 0)
            Left(DivisorMayBeZero("/", r.minimum, r.maximum))
          else Right(())
          numerators = Vector(l.minimum, l.maximum) ++
            (if (l.minimum <= 0 && l.maximum >= 0) Vector(BigInt(0)) else Vector.empty)
          denominators = Vector(r.minimum, r.maximum)
          values = for (a <- numerators; b <- denominators) yield a / b
          out <- checked(
            "/",
            Facts(
              s"(${l.verilog} / ${r.verilog})",
              l.default / r.default,
              values.min,
              values.max,
              mergeParameters(l, r),
              ElabInt.mergeParameterRoots(l.parameterRoots, r.parameterRoots)
            )
          )
        } yield out
      case Modulo(left, right) =>
        for {
          l <- loop(left)
          r <- loop(right)
          _ <- if (r.minimum <= 0 && r.maximum >= 0)
            Left(DivisorMayBeZero("%", r.minimum, r.maximum))
          else Right(())
          maxAbs = r.minimum.abs.max(r.maximum.abs)
          limit = (maxAbs - 1).max(BigInt(0))
          minimum = if (l.minimum < 0) -limit else BigInt(0)
          maximum = if (l.maximum > 0) limit else BigInt(0)
          out <- checked(
            "%",
            Facts(
              s"(${l.verilog} % ${r.verilog})",
              l.default % r.default,
              minimum,
              maximum,
              mergeParameters(l, r),
              ElabInt.mergeParameterRoots(l.parameterRoots, r.parameterRoots)
            )
          )
        } yield out
      case Min(left, right) =>
        for {
          l <- loop(left)
          r <- loop(right)
          out <- checked(
            "min",
            Facts(
              s"((${l.verilog}) < (${r.verilog}) ? (${l.verilog}) : (${r.verilog}))",
              l.default.min(r.default),
              l.minimum.min(r.minimum),
              l.maximum.min(r.maximum),
              mergeParameters(l, r),
              ElabInt.mergeParameterRoots(l.parameterRoots, r.parameterRoots)
            )
          )
        } yield out
      case Max(left, right) =>
        for {
          l <- loop(left)
          r <- loop(right)
          out <- checked(
            "max",
            Facts(
              s"((${l.verilog}) > (${r.verilog}) ? (${l.verilog}) : (${r.verilog}))",
              l.default.max(r.default),
              l.minimum.max(r.minimum),
              l.maximum.max(r.maximum),
              mergeParameters(l, r),
              ElabInt.mergeParameterRoots(l.parameterRoots, r.parameterRoots)
            )
          )
        } yield out
      case Negate(operand) =>
        for {
          value <- loop(operand)
          out <- checked(
            "negate",
            Facts(
              s"(-${value.verilog})",
              -value.default,
              -value.maximum,
              -value.minimum,
              value.parameters,
              value.parameterRoots
            )
          )
        } yield out
      case CeilLog2(operand) =>
        loop(operand).flatMap { value =>
          if (value.minimum < 1) Left(OperandMustBePositive("ceilLog2", value.minimum))
          else {
            val out = Facts(
              s"morphhdl_ceil_log2(${value.verilog})",
              ceilLog2(value.default),
              ceilLog2(value.minimum),
              ceilLog2(value.maximum),
              value.parameters,
              value.parameterRoots
            )
            checked("ceilLog2", out)
          }
        }
      case AddressWidth(operand) =>
        loop(operand).flatMap { value =>
          if (value.minimum < 1) Left(OperandMustBePositive("addressWidth", value.minimum))
          else {
            val out = Facts(
              s"morphhdl_address_width(${value.verilog})",
              addressWidth(value.default),
              addressWidth(value.minimum),
              addressWidth(value.maximum),
              value.parameters,
              value.parameterRoots
            )
            checked("addressWidth", out)
          }
        }
      case Log2Down(operand) =>
        loop(operand).flatMap { value =>
          if (value.minimum < 1) Left(OperandMustBePositive("log2Down", value.minimum))
          else {
            val out = Facts(
              s"morphhdl_log2_down(${value.verilog})",
              log2Down(value.default),
              log2Down(value.minimum),
              log2Down(value.maximum),
              value.parameters,
              value.parameterRoots
            )
            checked("log2Down", out)
          }
        }
      case BooleanToInt(predicate) =>
        ExternalNativeIntRelativePredicate
          .lower(predicate, root, root.sourceLocation.getOrElse("<native-int-shadow>"))
          .flatMap { value =>
            checked(
              "boolean-to-int",
              Facts(
                s"((${value.verilog}) ? 1 : 0)",
                if (value.default) BigInt(1) else BigInt(0),
                BigInt(0),
                BigInt(1),
                value.parameters,
                value.parameterRoots
              )
            )
          }
    }

    loop(expression)
  }

  /** Exact witness evaluation used only for bounded structural-domain proofs. */
  private[core] def evaluate(
      expression: ExternalNativeIntRelativeExpression,
      root: BigInt
  ): Option[BigInt] = expression match {
    case Root           => Some(root)
    case Literal(value) => Some(value)
    case Add(left, right) =>
      for (l <- evaluate(left, root); r <- evaluate(right, root)) yield l + r
    case Subtract(left, right) =>
      for (l <- evaluate(left, root); r <- evaluate(right, root)) yield l - r
    case Multiply(left, right) =>
      for (l <- evaluate(left, root); r <- evaluate(right, root)) yield l * r
    case Divide(left, right) =>
      for {
        l <- evaluate(left, root)
        r <- evaluate(right, root)
        if r != 0
      } yield l / r
    case Modulo(left, right) =>
      for {
        l <- evaluate(left, root)
        r <- evaluate(right, root)
        if r != 0
      } yield l % r
    case Min(left, right) =>
      for (l <- evaluate(left, root); r <- evaluate(right, root)) yield l.min(r)
    case Max(left, right) =>
      for (l <- evaluate(left, root); r <- evaluate(right, root)) yield l.max(r)
    case Negate(value) => evaluate(value, root).map(-_)
    case CeilLog2(value) =>
      evaluate(value, root).filter(_ > 0).map(ceilLog2)
    case AddressWidth(value) =>
      evaluate(value, root).filter(_ > 0).map(addressWidth)
    case Log2Down(value) =>
      evaluate(value, root).filter(_ > 0).map(log2Down)
    case BooleanToInt(value) =>
      ExternalNativeIntRelativePredicate
        .evaluate(value, root)
        .map(result => if (result) BigInt(1) else BigInt(0))
  }

  private def ceilLog2(value: BigInt): BigInt = BigInt((value - 1).bitLength)
  private def addressWidth(value: BigInt): BigInt = ceilLog2(value).max(BigInt(1))
  private def log2Down(value: BigInt): BigInt = BigInt(value.bitLength - 1)
}

private[core] sealed trait ExternalNativeIntRelativePredicate

private[core] object ExternalNativeIntRelativePredicate {
  import ExternalNativeIntRelativeExpression.{Facts, Failure}

  final case class Comparison(
      operation: String,
      left: ExternalNativeIntRelativeExpression,
      right: ExternalNativeIntRelativeExpression
  ) extends ExternalNativeIntRelativePredicate

  final case class PowerOfTwo(value: ExternalNativeIntRelativeExpression)
      extends ExternalNativeIntRelativePredicate

  final case class Constant(value: Boolean)
      extends ExternalNativeIntRelativePredicate

  final case class And(
      left: ExternalNativeIntRelativePredicate,
      right: ExternalNativeIntRelativePredicate
  ) extends ExternalNativeIntRelativePredicate

  final case class Or(
      left: ExternalNativeIntRelativePredicate,
      right: ExternalNativeIntRelativePredicate
  ) extends ExternalNativeIntRelativePredicate

  final case class Not(value: ExternalNativeIntRelativePredicate)
      extends ExternalNativeIntRelativePredicate

  def binary(
      operation: String,
      left: ExternalNativeIntRelativePredicate,
      right: ExternalNativeIntRelativePredicate
  ): ExternalNativeIntRelativePredicate = operation match {
    case "&&" => And(left, right)
    case "||" => Or(left, right)
    case other => throw new IllegalArgumentException(
      s"unsupported native Boolean shadow operation '$other'"
    )
  }

  def not(value: ExternalNativeIntRelativePredicate): ExternalNativeIntRelativePredicate =
    Not(value)

  def lower(
      predicate: ExternalNativeIntRelativePredicate,
      root: ElaborationIntegerExpression,
      sourceLocation: String
  ): Either[Failure, ElaborationBooleanExpression] = predicate match {
    case Comparison(operation, left, right) =>
      for {
        l <- ExternalNativeIntRelativeExpression.lower(left, root)
        r <- ExternalNativeIntRelativeExpression.lower(right, root)
      } yield {
        val symbol = operation match {
          case "<"  => "<"
          case "<=" => "<="
          case ">"  => ">"
          case ">=" => ">="
          case "==" => "=="
          case "!=" => "!="
          case other => throw new IllegalArgumentException(
            s"unsupported native Int shadow comparison '$other'"
          )
        }
        val default = operation match {
          case "<"  => l.default < r.default
          case "<=" => l.default <= r.default
          case ">"  => l.default > r.default
          case ">=" => l.default >= r.default
          case "==" => l.default == r.default
          case "!=" => l.default != r.default
        }
        ElaborationBooleanExpression(
          verilog = s"(${l.verilog} $symbol ${r.verilog})",
          default = default,
          parameters = mergeParameters(l, r),
          sourceLocation = Option(sourceLocation).filter(_.nonEmpty),
          parameterRoots = ElabInt.mergeParameterRoots(
            l.parameterRoots,
            r.parameterRoots
          )
        )
      }
    case PowerOfTwo(value) =>
      ExternalNativeIntRelativeExpression.lower(value, root).map { facts =>
        ElaborationBooleanExpression(
          verilog =
            s"((${facts.verilog} > 0) && ((${facts.verilog} & (${facts.verilog} - 1)) == 0))",
          default = isPowerOfTwo(facts.default),
          parameters = facts.parameters,
          sourceLocation = Option(sourceLocation).filter(_.nonEmpty),
          parameterRoots = facts.parameterRoots
        )
      }
    case Constant(value) =>
      Right(
        ElaborationBooleanExpression(
          verilog = if (value) "1'b1" else "1'b0",
          default = value,
          parameters = Vector.empty,
          sourceLocation = Option(sourceLocation).filter(_.nonEmpty)
        )
      )
    case And(left, right) =>
      for {
        l <- lower(left, root, sourceLocation)
        r <- lower(right, root, sourceLocation)
      } yield ElaborationBooleanExpression(
        verilog = s"((${l.verilog}) && (${r.verilog}))",
        default = l.default && r.default,
        parameters = mergeParameters(l.parameters, r.parameters),
        sourceLocation = Option(sourceLocation).filter(_.nonEmpty),
        parameterRoots = ElabInt.mergeParameterRoots(
          l.parameterRoots,
          r.parameterRoots
        )
      )
    case Or(left, right) =>
      for {
        l <- lower(left, root, sourceLocation)
        r <- lower(right, root, sourceLocation)
      } yield ElaborationBooleanExpression(
        verilog = s"((${l.verilog}) || (${r.verilog}))",
        default = l.default || r.default,
        parameters = mergeParameters(l.parameters, r.parameters),
        sourceLocation = Option(sourceLocation).filter(_.nonEmpty),
        parameterRoots = ElabInt.mergeParameterRoots(
          l.parameterRoots,
          r.parameterRoots
        )
      )
    case Not(value) =>
      lower(value, root, sourceLocation).map { operand =>
        ElaborationBooleanExpression(
          verilog = s"(!(${operand.verilog}))",
          default = !operand.default,
          parameters = operand.parameters,
          sourceLocation = Option(sourceLocation).filter(_.nonEmpty),
          parameterRoots = operand.parameterRoots
        )
      }
  }

  /** Exact witness evaluation used only for bounded structural-domain proofs. */
  private[core] def evaluate(
      predicate: ExternalNativeIntRelativePredicate,
      root: BigInt
  ): Option[Boolean] = predicate match {
    case Comparison(operation, left, right) =>
      for {
        l <- ExternalNativeIntRelativeExpression.evaluate(left, root)
        r <- ExternalNativeIntRelativeExpression.evaluate(right, root)
        result <- operation match {
          case "<"  => Some(l < r)
          case "<=" => Some(l <= r)
          case ">"  => Some(l > r)
          case ">=" => Some(l >= r)
          case "==" => Some(l == r)
          case "!=" => Some(l != r)
          case _    => None
        }
      } yield result
    case PowerOfTwo(value) =>
      ExternalNativeIntRelativeExpression
        .evaluate(value, root)
        .map(number => number > 0 && number.bitCount == 1)
    case Constant(value) => Some(value)
    case And(left, right) =>
      for (l <- evaluate(left, root); r <- evaluate(right, root)) yield l && r
    case Or(left, right) =>
      for (l <- evaluate(left, root); r <- evaluate(right, root)) yield l || r
    case Not(value) => evaluate(value, root).map(!_)
  }

  private def mergeParameters(left: Facts, right: Facts): Vector[ElaborationIntegerParameter] =
    mergeParameters(left.parameters, right.parameters)

  private def mergeParameters(
      left: Vector[ElaborationIntegerParameter],
      right: Vector[ElaborationIntegerParameter]
  ): Vector[ElaborationIntegerParameter] =
    (left ++ right)
      .groupBy(_.name)
      .toVector
      .sortBy(_._1)
      .map { case (_, values) => values.head }

  private def isPowerOfTwo(value: BigInt): Boolean = value > 0 && value.bitCount == 1
}
