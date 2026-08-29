package spinal.core

/**
  * A bounded elaboration-time integer which retains both the concrete witness
  * used by ordinary SpinalHDL elaboration and the symbolic Verilog expression
  * used by MorphHDL publication.
  *
  * There is deliberately no implicit conversion from ElabInt to Int. Concrete
  * extraction is explicit and restricted to compatibility helpers which prove
  * the expression is constant over its complete admitted domain.
  */
final class ElabInt private[core] (
    val witness: Int,
    val expression: ElaborationIntegerExpression
) {
  require(expression != null, "ElabInt expression must not be null")
  require(expression.default == BigInt(witness), "ElabInt witness/default mismatch")
  require(
    expression.minimum <= expression.default && expression.default <= expression.maximum,
    "ElabInt default must lie inside its domain"
  )

  def +(that: ElabInt): ElabInt =
    ElabInt.binary(
      "+",
      this,
      that,
      BigInt(witness) + that.witness,
      expression.minimum + that.expression.minimum,
      expression.maximum + that.expression.maximum
    )
  def +(that: Int): ElabInt = this + ElabInt.literal(that)

  def -(that: ElabInt): ElabInt =
    ElabInt.binary(
      "-",
      this,
      that,
      BigInt(witness) - that.witness,
      expression.minimum - that.expression.maximum,
      expression.maximum - that.expression.minimum
    )
  def -(that: Int): ElabInt = this - ElabInt.literal(that)

  def *(that: ElabInt): ElabInt = {
    val candidates = Vector(
      expression.minimum * that.expression.minimum,
      expression.minimum * that.expression.maximum,
      expression.maximum * that.expression.minimum,
      expression.maximum * that.expression.maximum
    )
    ElabInt.binary(
      "*",
      this,
      that,
      BigInt(witness) * that.witness,
      candidates.min,
      candidates.max
    )
  }
  def *(that: Int): ElabInt = this * ElabInt.literal(that)

  def /(that: ElabInt): ElabInt = {
    require(
      that.expression.minimum > 0,
      s"ElabInt division requires a strictly positive divisor domain, found [${that.expression.minimum}, ${that.expression.maximum}]"
    )
    require(
      expression.minimum >= 0,
      s"ElabInt division currently requires a non-negative dividend domain, found [${expression.minimum}, ${expression.maximum}]"
    )
    ElabInt.binary(
      "/",
      this,
      that,
      BigInt(witness) / that.witness,
      expression.minimum / that.expression.maximum,
      expression.maximum / that.expression.minimum
    )
  }
  def /(that: Int): ElabInt = this / ElabInt.literal(that)

  def %(that: ElabInt): ElabInt = {
    require(
      that.expression.minimum > 0,
      s"ElabInt remainder requires a strictly positive divisor domain, found [${that.expression.minimum}, ${that.expression.maximum}]"
    )
    require(
      expression.minimum >= 0,
      s"ElabInt remainder currently requires a non-negative dividend domain, found [${expression.minimum}, ${expression.maximum}]"
    )
    val upper = (that.expression.maximum - 1).min(expression.maximum.max(BigInt(0)))
    ElabInt.binary(
      "%",
      this,
      that,
      BigInt(witness) % that.witness,
      BigInt(0),
      upper
    )
  }
  def %(that: Int): ElabInt = this % ElabInt.literal(that)

  def min(that: ElabInt): ElabInt =
    ElabInt.function(
      "min",
      this,
      that,
      math.min(witness, that.witness),
      expression.minimum.min(that.expression.minimum),
      expression.maximum.min(that.expression.maximum)
    )
  def min(that: Int): ElabInt = min(ElabInt.literal(that))

  def max(that: ElabInt): ElabInt =
    ElabInt.function(
      "max",
      this,
      that,
      math.max(witness, that.witness),
      expression.minimum.max(that.expression.minimum),
      expression.maximum.max(that.expression.maximum)
    )
  def max(that: Int): ElabInt = max(ElabInt.literal(that))

  def <(that: ElabInt): ElabBool =
    ElabBool.comparison(
      "<",
      this,
      that,
      witness < that.witness,
      if (expression.maximum < that.expression.minimum) Some(true)
      else if (expression.minimum >= that.expression.maximum) Some(false)
      else None
    )
  def <(that: Int): ElabBool = this < ElabInt.literal(that)

  def <=(that: ElabInt): ElabBool =
    ElabBool.comparison(
      "<=",
      this,
      that,
      witness <= that.witness,
      if (expression.maximum <= that.expression.minimum) Some(true)
      else if (expression.minimum > that.expression.maximum) Some(false)
      else None
    )
  def <=(that: Int): ElabBool = this <= ElabInt.literal(that)

  def >(that: ElabInt): ElabBool =
    ElabBool.comparison(
      ">",
      this,
      that,
      witness > that.witness,
      if (expression.minimum > that.expression.maximum) Some(true)
      else if (expression.maximum <= that.expression.minimum) Some(false)
      else None
    )
  def >(that: Int): ElabBool = this > ElabInt.literal(that)

  def >=(that: ElabInt): ElabBool =
    ElabBool.comparison(
      ">=",
      this,
      that,
      witness >= that.witness,
      if (expression.minimum >= that.expression.maximum) Some(true)
      else if (expression.maximum < that.expression.minimum) Some(false)
      else None
    )
  def >=(that: Int): ElabBool = this >= ElabInt.literal(that)

  /** More-specific overload selected instead of Any.==(Any) for ElabInt operands. */
  def ==(that: ElabInt): ElabBool = hdlEq(that)
  def ==(that: Int): ElabBool = hdlEq(ElabInt.literal(that))
  def !=(that: ElabInt): ElabBool = hdlNe(that)
  def !=(that: Int): ElabBool = hdlNe(ElabInt.literal(that))

  def hdlEq(that: ElabInt): ElabBool = {
    val same = ElabInt.equivalent(expression, that.expression)
    ElabBool.comparison(
      "==",
      this,
      that,
      witness == that.witness,
      if (same) Some(true)
      else if (
        expression.maximum < that.expression.minimum ||
        that.expression.maximum < expression.minimum
      ) Some(false)
      else None
    )
  }

  def hdlNe(that: ElabInt): ElabBool = !hdlEq(that)

  def isConstant: Boolean = expression.minimum == expression.maximum

  def constantWitness(role: String): Int = {
    if (!isConstant)
      throw new IllegalArgumentException(
        s"$role requires a compile-time constant ElabInt, found '${expression.verilog}' over [${expression.minimum}, ${expression.maximum}]"
      )
    witness
  }

  /** Preserve symbolic packed geometry through the existing MorphHDL bridge. */
  def bits: ParameterizedBitCount =
    ParameterizedBitCount(
      witness,
      parameter = None,
      sourceLocation = expression.sourceLocation,
      expression = if (expression.parameters.nonEmpty) Some(expression) else None
    )

  override def toString: String =
    s"ElabInt(${expression.verilog}, witness=$witness)"
}

object ElabInt {
  def literal(value: Int): ElabInt =
    fromExpression(
      ElaborationIntegerExpression(
        verilog = BigInt(value).toString,
        default = BigInt(value),
        minimum = BigInt(value),
        maximum = BigInt(value),
        parameters = Vector.empty,
        sourceLocation = None
      )
    )

  def parameter(
      name: String,
      default: Int,
      minimum: Int,
      maximum: Int,
      sourceLocation: Option[String] = None
  ): ElabInt = {
    require(
      name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*"),
      s"invalid ElabInt parameter name '$name'"
    )
    require(
      minimum <= default && default <= maximum,
      s"ElabInt parameter '$name' default $default is outside [$minimum, $maximum]"
    )
    val declaration = ElaborationIntegerParameter(
      name,
      BigInt(default),
      BigInt(minimum),
      BigInt(maximum)
    )
    fromExpression(
      ElaborationIntegerExpression(
        verilog = name,
        default = BigInt(default),
        minimum = BigInt(minimum),
        maximum = BigInt(maximum),
        parameters = Vector(declaration),
        sourceLocation = sourceLocation
      )
    )
  }

  def fromParameterizedBitCount(value: ParameterizedBitCount): ElabInt = {
    require(value != null, "ParameterizedBitCount must not be null")
    val retained = value.expression.orElse(value.parameter.map { parameter =>
      ElaborationIntegerExpression(
        verilog = parameter.name,
        default = parameter.default,
        minimum = parameter.minimum,
        maximum = parameter.maximum,
        parameters = Vector(parameter),
        sourceLocation = value.sourceLocation
      )
    }).getOrElse {
      ElaborationIntegerExpression(
        verilog = BigInt(value.value).toString,
        default = BigInt(value.value),
        minimum = BigInt(value.value),
        maximum = BigInt(value.value),
        parameters = Vector.empty,
        sourceLocation = value.sourceLocation
      )
    }
    fromExpression(retained)
  }

  def fromExpression(expression: ElaborationIntegerExpression): ElabInt = {
    require(
      expression.default.isValidInt,
      s"ElabInt default ${expression.default} does not fit Int"
    )
    new ElabInt(expression.default.toInt, expression)
  }

  private[core] def equivalent(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean =
    left.verilog == right.verilog &&
      left.default == right.default &&
      left.minimum == right.minimum &&
      left.maximum == right.maximum &&
      left.parameters == right.parameters

  private def binary(
      operation: String,
      left: ElabInt,
      right: ElabInt,
      witness: BigInt,
      minimum: BigInt,
      maximum: BigInt
  ): ElabInt =
    build(
      s"((${left.expression.verilog}) $operation (${right.expression.verilog}))",
      witness,
      minimum,
      maximum,
      left,
      right
    )

  private def function(
      operation: String,
      left: ElabInt,
      right: ElabInt,
      witness: BigInt,
      minimum: BigInt,
      maximum: BigInt
  ): ElabInt =
    build(
      s"$operation((${left.expression.verilog}), (${right.expression.verilog}))",
      witness,
      minimum,
      maximum,
      left,
      right
    )

  private def build(
      verilog: String,
      witness: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      left: ElabInt,
      right: ElabInt
  ): ElabInt = {
    require(witness.isValidInt, s"ElabInt witness $witness does not fit Int")
    val parameters = mergeParameters(
      left.expression.parameters,
      right.expression.parameters
    )
    val source = left.expression.sourceLocation.orElse(
      right.expression.sourceLocation
    )
    if (minimum == maximum)
      fromExpression(
        ElaborationIntegerExpression(
          verilog = minimum.toString,
          default = witness,
          minimum = minimum,
          maximum = maximum,
          parameters = Vector.empty,
          sourceLocation = source
        )
      )
    else
      fromExpression(
        ElaborationIntegerExpression(
          verilog = verilog,
          default = witness,
          minimum = minimum,
          maximum = maximum,
          parameters = parameters,
          sourceLocation = source
        )
      )
  }

  private[core] def mergeParameters(
      left: Vector[ElaborationIntegerParameter],
      right: Vector[ElaborationIntegerParameter]
  ): Vector[ElaborationIntegerParameter] = {
    val ordered = scala.collection.mutable.LinkedHashMap.empty[
      String,
      ElaborationIntegerParameter
    ]
    (left ++ right).foreach { incoming =>
      ordered.get(incoming.name) match {
        case Some(existing) if existing != incoming =>
          throw new IllegalArgumentException(
            s"ElabInt parameter '${incoming.name}' has conflicting declarations"
          )
        case Some(_) =>
        case None    => ordered.update(incoming.name, incoming)
      }
    }
    ordered.values.toVector
  }
}

/** A concrete Boolean witness paired with a typed symbolic predicate. */
final class ElabBool private[core] (
    val witness: Boolean,
    val expression: ElaborationBooleanExpression,
    val constant: Option[Boolean]
) {
  require(expression != null, "ElabBool expression must not be null")
  require(expression.default == witness, "ElabBool witness/default mismatch")
  require(
    constant.forall(_ == witness),
    "ElabBool constant classification disagrees with witness"
  )

  def unary_! : ElabBool =
    ElabBool.build(
      witness = !witness,
      verilog = s"!(${expression.verilog})",
      parameters = expression.parameters,
      sourceLocation = expression.sourceLocation,
      constant = constant.map(!_)
    )

  def &&(that: ElabBool): ElabBool =
    ElabBool.build(
      witness && that.witness,
      s"((${expression.verilog}) && (${that.expression.verilog}))",
      ElabInt.mergeParameters(
        expression.parameters,
        that.expression.parameters
      ),
      expression.sourceLocation.orElse(that.expression.sourceLocation),
      (constant, that.constant) match {
        case (Some(false), _) | (_, Some(false)) => Some(false)
        case (Some(true), value)                 => value
        case (value, Some(true))                 => value
        case _                                   => None
      }
    )
  def &&(that: Boolean): ElabBool = this && ElabBool.literal(that)

  def ||(that: ElabBool): ElabBool =
    ElabBool.build(
      witness || that.witness,
      s"((${expression.verilog}) || (${that.expression.verilog}))",
      ElabInt.mergeParameters(
        expression.parameters,
        that.expression.parameters
      ),
      expression.sourceLocation.orElse(that.expression.sourceLocation),
      (constant, that.constant) match {
        case (Some(true), _) | (_, Some(true)) => Some(true)
        case (Some(false), value)              => value
        case (value, Some(false))              => value
        case _                                 => None
      }
    )
  def ||(that: Boolean): ElabBool = this || ElabBool.literal(that)

  def constantWitness(role: String): Boolean = constant.getOrElse {
    throw new IllegalArgumentException(
      s"$role requires a domain-constant ElabBool, found '${expression.verilog}'"
    )
  }

  override def toString: String =
    s"ElabBool(${expression.verilog}, witness=$witness)"
}

object ElabBool {
  def literal(value: Boolean): ElabBool =
    build(
      value,
      if (value) "1'b1" else "1'b0",
      Vector.empty,
      None,
      Some(value)
    )

  private[core] def comparison(
      operation: String,
      left: ElabInt,
      right: ElabInt,
      witness: Boolean,
      constant: Option[Boolean]
  ): ElabBool =
    build(
      witness,
      s"((${left.expression.verilog}) $operation (${right.expression.verilog}))",
      ElabInt.mergeParameters(
        left.expression.parameters,
        right.expression.parameters
      ),
      left.expression.sourceLocation.orElse(right.expression.sourceLocation),
      constant
    )

  private[core] def build(
      witness: Boolean,
      verilog: String,
      parameters: Vector[ElaborationIntegerParameter],
      sourceLocation: Option[String],
      constant: Option[Boolean]
  ): ElabBool =
    new ElabBool(
      witness,
      ElaborationBooleanExpression(
        verilog = verilog,
        default = witness,
        parameters = parameters,
        sourceLocation = sourceLocation
      ),
      constant
    )
}
