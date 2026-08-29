package spinal.core

/**
  * A bounded elaboration-time integer that retains both one concrete witness
  * and its exact parameter expression.
  *
  * `ElabInt` is deliberately not convertible to Scala `Int`. Native APIs that
  * support parameters accept it explicitly and may use [[witness]] only at the
  * reviewed concrete SpinalHDL construction boundary.
  */
final class ElabInt private[core] (
    private[core] val expression: ElaborationIntegerExpression
) {
  ElabInt.validateExpression(expression, "ElabInt")

  def witness: Int = expression.default.toInt
  def minimum: BigInt = expression.minimum
  def maximum: BigInt = expression.maximum
  def parameters: Vector[ElaborationIntegerParameter] = expression.parameters
  def sourceLocation: Option[String] = expression.sourceLocation
  def isConcrete: Boolean = expression.parameters.isEmpty
  def isDomainConstant: Boolean = expression.minimum == expression.maximum

  def +(that: ElabInt): ElabInt = ElabInt.add(this, that)
  def +(that: Int): ElabInt = this + ElabInt.literal(that)
  def -(that: ElabInt): ElabInt = ElabInt.subtract(this, that)
  def -(that: Int): ElabInt = this - ElabInt.literal(that)
  def *(that: ElabInt): ElabInt = ElabInt.multiply(this, that)
  def *(that: Int): ElabInt = this * ElabInt.literal(that)
  def /(that: ElabInt): ElabInt = ElabInt.divide(this, that)
  def /(that: Int): ElabInt = this / ElabInt.literal(that)
  def %(that: ElabInt): ElabInt = ElabInt.modulo(this, that)
  def %(that: Int): ElabInt = this % ElabInt.literal(that)

  def <(that: ElabInt): ElabBool = ElabInt.compare("<", this, that)
  def <(that: Int): ElabBool = this < ElabInt.literal(that)
  def <=(that: ElabInt): ElabBool = ElabInt.compare("<=", this, that)
  def <=(that: Int): ElabBool = this <= ElabInt.literal(that)
  def >(that: ElabInt): ElabBool = ElabInt.compare(">", this, that)
  def >(that: Int): ElabBool = this > ElabInt.literal(that)
  def >=(that: ElabInt): ElabBool = ElabInt.compare(">=", this, that)
  def >=(that: Int): ElabBool = this >= ElabInt.literal(that)

  /** Typed equality used by the pre-typer natural-syntax bridge. */
  def elabEq(that: ElabInt): ElabBool = ElabInt.equal(this, that)
  def elabEq(that: Int): ElabBool = elabEq(ElabInt.literal(that))

  /** Typed inequality used by the pre-typer natural-syntax bridge. */
  def elabNe(that: ElabInt): ElabBool = !elabEq(that)
  def elabNe(that: Int): ElabBool = !elabEq(that)

  /** SpinalHDL packed-width marker which retains this expression. */
  def bit: ParameterizedBitCount = toParameterizedBitCount("bit width")
  def bits: ParameterizedBitCount = toParameterizedBitCount("bit width")

  /** Constant-only slice count required by the current native adapters. */
  def slices: SlicesCount = new SlicesCount(constantInt("slice count"))

  private[spinal] def constantInt(role: String): Int = {
    if (!isDomainConstant) {
      ElabInt.fail(
        "SPINAL-ELAB-INT-DOMAIN-NOT-CONSTANT",
        s"$role expression '${expression.verilog}' varies over [${expression.minimum}, ${expression.maximum}]",
        expression.sourceLocation
      )
    }
    if (!expression.default.isValidInt) {
      ElabInt.fail(
        "SPINAL-ELAB-INT-WITNESS-OUT-OF-RANGE",
        s"$role witness ${expression.default} does not fit Scala Int",
        expression.sourceLocation
      )
    }
    expression.default.toInt
  }

  private[spinal] def constantBigInt(role: String): BigInt = {
    constantInt(role)
    expression.default
  }

  private[spinal] def toParameterizedBitCount(
      role: String
  ): ParameterizedBitCount = {
    if (expression.minimum < 1 || expression.maximum < expression.minimum) {
      ElabInt.fail(
        "SPINAL-ELAB-INT-WIDTH-DOMAIN-INVALID",
        s"$role expression '${expression.verilog}' must remain positive, but reaches [${expression.minimum}, ${expression.maximum}]",
        expression.sourceLocation
      )
    }
    if (expression.maximum > BigInt(Int.MaxValue)) {
      ElabInt.fail(
        "SPINAL-ELAB-INT-WIDTH-DOMAIN-TOO-LARGE",
        s"$role expression '${expression.verilog}' exceeds the Scala Int width domain",
        expression.sourceLocation
      )
    }
    val direct = expression.parameters match {
      case Vector(parameter) if expression.verilog.trim == parameter.name =>
        Some(parameter)
      case _ => None
    }
    ParameterizedBitCount(
      value = witness,
      parameter = direct,
      sourceLocation = expression.sourceLocation,
      expression = if (expression.parameters.nonEmpty) Some(expression) else None
    )
  }

  override def toString: String =
    s"ElabInt(${expression.verilog}, witness=${expression.default})"
}

/** A typed Boolean predicate over one or more bounded elaboration integers. */
final class ElabBool private[core] (
    private[core] val expression: ElaborationBooleanExpression,
    private[core] val truth: ElabBool.Truth
) {
  def witness: Boolean = expression.default
  def parameters: Vector[ElaborationIntegerParameter] = expression.parameters
  def sourceLocation: Option[String] = expression.sourceLocation
  def isAlwaysTrue: Boolean = truth == ElabBool.AlwaysTrue
  def isAlwaysFalse: Boolean = truth == ElabBool.AlwaysFalse
  def isSymbolic: Boolean = truth == ElabBool.Unknown

  def unary_! : ElabBool = ElabBool.not(this)
  def &&(that: ElabBool): ElabBool = ElabBool.and(this, that)
  def &&(that: Boolean): ElabBool = this && ElabBool.literal(that)
  def ||(that: ElabBool): ElabBool = ElabBool.or(this, that)
  def ||(that: Boolean): ElabBool = this || ElabBool.literal(that)

  override def toString: String =
    s"ElabBool(${expression.verilog}, witness=${expression.default})"
}

object ElabBool {
  private[core] sealed trait Truth
  private[core] case object AlwaysTrue extends Truth
  private[core] case object AlwaysFalse extends Truth
  private[core] case object Unknown extends Truth

  def literal(value: Boolean): ElabBool =
    new ElabBool(
      ElaborationBooleanExpression(
        verilog = if (value) "1'b1" else "1'b0",
        default = value,
        parameters = Vector.empty
      ),
      if (value) AlwaysTrue else AlwaysFalse
    )

  private[core] def apply(
      expression: ElaborationBooleanExpression,
      truth: Truth
  ): ElabBool = {
    if (expression == null)
      throw new IllegalArgumentException("ElabBool expression must not be null")
    new ElabBool(expression, truth)
  }

  private def not(value: ElabBool): ElabBool = {
    val truth = value.truth match {
      case AlwaysTrue  => AlwaysFalse
      case AlwaysFalse => AlwaysTrue
      case Unknown     => Unknown
    }
    apply(
      ElaborationBooleanExpression(
        verilog = s"!(${value.expression.verilog})",
        default = !value.expression.default,
        parameters = value.expression.parameters,
        sourceLocation = value.expression.sourceLocation
      ),
      truth
    )
  }

  private def and(left: ElabBool, right: ElabBool): ElabBool = {
    val truth = (left.truth, right.truth) match {
      case (AlwaysFalse, _) | (_, AlwaysFalse) => AlwaysFalse
      case (AlwaysTrue, AlwaysTrue)             => AlwaysTrue
      case _                                    => Unknown
    }
    apply(
      ElaborationBooleanExpression(
        verilog = s"((${left.expression.verilog}) && (${right.expression.verilog}))",
        default = left.expression.default && right.expression.default,
        parameters = ElabInt.mergeParameters(
          left.expression.parameters,
          right.expression.parameters,
          left.expression.sourceLocation.orElse(right.expression.sourceLocation)
        ),
        sourceLocation = left.expression.sourceLocation.orElse(right.expression.sourceLocation)
      ),
      truth
    )
  }

  private def or(left: ElabBool, right: ElabBool): ElabBool = {
    val truth = (left.truth, right.truth) match {
      case (AlwaysTrue, _) | (_, AlwaysTrue) => AlwaysTrue
      case (AlwaysFalse, AlwaysFalse)        => AlwaysFalse
      case _                                 => Unknown
    }
    apply(
      ElaborationBooleanExpression(
        verilog = s"((${left.expression.verilog}) || (${right.expression.verilog}))",
        default = left.expression.default || right.expression.default,
        parameters = ElabInt.mergeParameters(
          left.expression.parameters,
          right.expression.parameters,
          left.expression.sourceLocation.orElse(right.expression.sourceLocation)
        ),
        sourceLocation = left.expression.sourceLocation.orElse(right.expression.sourceLocation)
      ),
      truth
    )
  }
}

object ElabInt {
  def literal(value: Int): ElabInt = fromBigInt(BigInt(value))

  def fromBigInt(value: BigInt): ElabInt = {
    if (!value.isValidInt)
      fail(
        "SPINAL-ELAB-INT-LITERAL-OUT-OF-RANGE",
        s"elaboration integer literal $value does not fit Scala Int",
        None
      )
    fromExpression(
      ElaborationIntegerExpression(
        verilog = value.toString,
        default = value,
        minimum = value,
        maximum = value,
        parameters = Vector.empty
      )
    )
  }

  def fromExpression(expression: ElaborationIntegerExpression): ElabInt = {
    validateExpression(expression, "ElabInt expression")
    new ElabInt(expression)
  }

  /** Retain the total packed width of one native Data value. */
  def packedWidthOf(data: Data): ElabInt = {
    if (data == null)
      fail(
        "SPINAL-ELAB-WIDTH-DATA-NULL",
        "packedWidthOf received a null Data value",
        None
      )
    val leaves = data.flatten.toVector
    leaves
      .map { leaf =>
        ParameterizedWidth
          .expressionOf(leaf)
          .map(fromExpression)
          .getOrElse(literal(leaf.getBitsWidth))
      }
      .reduceOption(_ + _)
      .getOrElse(literal(0))
  }

  /**
    * Current typed native-library contract: relational geometry may use one
    * symbolic root plus literals, or multiple occurrences of the same root.
    */
  def requireSingleSymbolicRoot(role: String, values: ElabInt*): Unit = {
    val parameters = values.toVector.flatMap(_.expression.parameters)
    val grouped = parameters.groupBy(_.name)
    grouped.foreach { case (name, schemas) =>
      if (schemas.distinct.size != 1) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-SCHEMA-CONFLICT",
          s"$role observes conflicting declarations for parameter '$name'",
          values.toVector.flatMap(_.sourceLocation).headOption
        )
      }
    }
    if (grouped.size > 1) {
      fail(
        "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
        s"$role currently accepts one symbolic root, but found ${grouped.keys.toVector.sorted.mkString(", ")}",
        values.toVector.flatMap(_.sourceLocation).headOption
      )
    }
  }

  private def add(left: ElabInt, right: ElabInt): ElabInt =
    binary(
      "+",
      left,
      right,
      left.minimum + right.minimum,
      left.maximum + right.maximum,
      left.expression.default + right.expression.default
    )

  private def subtract(left: ElabInt, right: ElabInt): ElabInt =
    binary(
      "-",
      left,
      right,
      left.minimum - right.maximum,
      left.maximum - right.minimum,
      left.expression.default - right.expression.default
    )

  private def multiply(left: ElabInt, right: ElabInt): ElabInt = {
    val candidates = Vector(
      left.minimum * right.minimum,
      left.minimum * right.maximum,
      left.maximum * right.minimum,
      left.maximum * right.maximum
    )
    binary(
      "*",
      left,
      right,
      candidates.min,
      candidates.max,
      left.expression.default * right.expression.default
    )
  }

  private def divide(left: ElabInt, right: ElabInt): ElabInt = {
    if (left.minimum < 0 || right.minimum <= 0) {
      fail(
        "SPINAL-ELAB-INT-DIVISION-DOMAIN-UNSUPPORTED",
        s"division '${left.expression.verilog} / ${right.expression.verilog}' requires a non-negative dividend and positive divisor over the complete domain",
        left.sourceLocation.orElse(right.sourceLocation)
      )
    }
    binary(
      "/",
      left,
      right,
      left.minimum / right.maximum,
      left.maximum / right.minimum,
      left.expression.default / right.expression.default
    )
  }

  private def modulo(left: ElabInt, right: ElabInt): ElabInt = {
    if (left.minimum < 0 || right.minimum <= 0) {
      fail(
        "SPINAL-ELAB-INT-MODULO-DOMAIN-UNSUPPORTED",
        s"modulo '${left.expression.verilog} % ${right.expression.verilog}' requires a non-negative dividend and positive divisor over the complete domain",
        left.sourceLocation.orElse(right.sourceLocation)
      )
    }
    val maximum = (right.maximum - 1).min(left.maximum).max(BigInt(0))
    binary(
      "%",
      left,
      right,
      BigInt(0),
      maximum,
      left.expression.default % right.expression.default
    )
  }

  private def binary(
      operation: String,
      left: ElabInt,
      right: ElabInt,
      minimum: BigInt,
      maximum: BigInt,
      default: BigInt
  ): ElabInt = {
    val location = left.sourceLocation.orElse(right.sourceLocation)
    if (minimum > maximum || default < minimum || default > maximum) {
      fail(
        "SPINAL-ELAB-INT-EXPRESSION-DOMAIN-INVALID",
        s"expression '${left.expression.verilog} $operation ${right.expression.verilog}' has default $default outside [$minimum, $maximum]",
        location
      )
    }
    fromExpression(
      ElaborationIntegerExpression(
        verilog = s"(${left.expression.verilog} $operation ${right.expression.verilog})",
        default = default,
        minimum = minimum,
        maximum = maximum,
        parameters = mergeParameters(
          left.expression.parameters,
          right.expression.parameters,
          location
        ),
        sourceLocation = location
      )
    )
  }

  private def equal(left: ElabInt, right: ElabInt): ElabBool = {
    val equivalent = equivalentExpression(left.expression, right.expression)
    val disjoint = left.maximum < right.minimum || right.maximum < left.minimum
    val truth =
      if (equivalent) ElabBool.AlwaysTrue
      else if (disjoint) ElabBool.AlwaysFalse
      else if (left.isDomainConstant && right.isDomainConstant) {
        if (left.witness == right.witness) ElabBool.AlwaysTrue
        else ElabBool.AlwaysFalse
      } else ElabBool.Unknown
    ElabBool(
      ElaborationBooleanExpression(
        verilog = s"((${left.expression.verilog}) == (${right.expression.verilog}))",
        default = left.expression.default == right.expression.default,
        parameters = mergeParameters(
          left.expression.parameters,
          right.expression.parameters,
          left.sourceLocation.orElse(right.sourceLocation)
        ),
        sourceLocation = left.sourceLocation.orElse(right.sourceLocation)
      ),
      truth
    )
  }

  private def compare(
      operation: String,
      left: ElabInt,
      right: ElabInt
  ): ElabBool = {
    val witness = operation match {
      case "<"  => left.expression.default < right.expression.default
      case "<=" => left.expression.default <= right.expression.default
      case ">"  => left.expression.default > right.expression.default
      case ">=" => left.expression.default >= right.expression.default
      case other => throw new IllegalArgumentException(s"unsupported comparison '$other'")
    }
    val truth = operation match {
      case "<" if left.maximum < right.minimum  => ElabBool.AlwaysTrue
      case "<" if left.minimum >= right.maximum => ElabBool.AlwaysFalse
      case "<=" if left.maximum <= right.minimum => ElabBool.AlwaysTrue
      case "<=" if left.minimum > right.maximum  => ElabBool.AlwaysFalse
      case ">" if left.minimum > right.maximum  => ElabBool.AlwaysTrue
      case ">" if left.maximum <= right.minimum => ElabBool.AlwaysFalse
      case ">=" if left.minimum >= right.maximum => ElabBool.AlwaysTrue
      case ">=" if left.maximum < right.minimum  => ElabBool.AlwaysFalse
      case _                                      => ElabBool.Unknown
    }
    ElabBool(
      ElaborationBooleanExpression(
        verilog = s"((${left.expression.verilog}) $operation (${right.expression.verilog}))",
        default = witness,
        parameters = mergeParameters(
          left.expression.parameters,
          right.expression.parameters,
          left.sourceLocation.orElse(right.sourceLocation)
        ),
        sourceLocation = left.sourceLocation.orElse(right.sourceLocation)
      ),
      truth
    )
  }

  private[core] def mergeParameters(
      left: Vector[ElaborationIntegerParameter],
      right: Vector[ElaborationIntegerParameter],
      sourceLocation: Option[String]
  ): Vector[ElaborationIntegerParameter] = {
    val values = left ++ right
    values.groupBy(_.name).foreach { case (name, schemas) =>
      if (schemas.distinct.size != 1) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-SCHEMA-CONFLICT",
          s"parameter '$name' has conflicting typed elaboration declarations",
          sourceLocation
        )
      }
    }
    values.groupBy(_.name).toVector.map(_._2.head).sortBy(_.name)
  }

  private[core] def equivalentExpression(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean =
    left.verilog == right.verilog &&
      left.default == right.default &&
      left.minimum == right.minimum &&
      left.maximum == right.maximum &&
      left.parameters == right.parameters &&
      left.generateIndex == right.generateIndex

  private[core] def validateExpression(
      expression: ElaborationIntegerExpression,
      role: String
  ): Unit = {
    if (expression == null)
      throw new IllegalArgumentException(s"$role must not be null")
    if (
      !expression.default.isValidInt ||
      expression.minimum > expression.maximum ||
      expression.default < expression.minimum ||
      expression.default > expression.maximum
    ) {
      fail(
        "SPINAL-ELAB-INT-DOMAIN-INVALID",
        s"$role '${expression.verilog}' has default ${expression.default} outside [${expression.minimum}, ${expression.maximum}] or outside Scala Int",
        expression.sourceLocation
      )
    }
    mergeParameters(expression.parameters, Vector.empty, expression.sourceLocation)
  }

  private[core] def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing = ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
