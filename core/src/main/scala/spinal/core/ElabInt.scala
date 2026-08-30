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

  private[spinal] def witness: Int = expression.default.toInt
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
  private[spinal] def witness: Boolean = expression.default
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
    ElabInt.validateExpression(expression, "ElabBool expression")
    val normalized = ElabInt.withCompleteParameterRoots(expression)
    ElabInt.validateExpression(normalized, "ElabBool expression")
    new ElabBool(normalized, truth)
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
        sourceLocation = value.expression.sourceLocation,
        parameterRoots = value.expression.parameterRoots
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
        sourceLocation = left.expression.sourceLocation.orElse(right.expression.sourceLocation),
        parameterRoots = ElabInt.mergeParameterRoots(
          left.expression.parameterRoots,
          right.expression.parameterRoots
        )
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
        sourceLocation = left.expression.sourceLocation.orElse(right.expression.sourceLocation),
        parameterRoots = ElabInt.mergeParameterRoots(
          left.expression.parameterRoots,
          right.expression.parameterRoots
        )
      ),
      truth
    )
  }
}

object ElabInt {
  def literal(value: Int): ElabInt = fromBigInt(BigInt(value))

  def fromBigInt(value: BigInt): ElabInt = {
    if (value == null)
      fail(
        "SPINAL-ELAB-INT-LITERAL-NULL",
        "elaboration integer literal must not be null",
        None
      )
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
    new ElabInt(withCompleteParameterRoots(expression))
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
    val roots = distinctParameterRoots(
      values.toVector.flatMap(_.expression.parameterRoots)
    )
    if (roots.size > 1) {
      fail(
        "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
        s"$role currently accepts one symbolic root, but found independently sourced declarations ${roots.map(_.name).sorted.mkString(", ")}",
        roots.flatMap(_.sourceLocation).headOption
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
        sourceLocation = location,
        parameterRoots = mergeParameterRoots(
          left.expression.parameterRoots,
          right.expression.parameterRoots
        )
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
        sourceLocation = left.sourceLocation.orElse(right.sourceLocation),
        parameterRoots = mergeParameterRoots(
          left.expression.parameterRoots,
          right.expression.parameterRoots
        )
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
    val equivalent = equivalentExpression(left.expression, right.expression)
    val truth =
      if (equivalent) {
        operation match {
          case "<" | ">"   => ElabBool.AlwaysFalse
          case "<=" | ">=" => ElabBool.AlwaysTrue
        }
      } else {
        operation match {
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
        sourceLocation = left.sourceLocation.orElse(right.sourceLocation),
        parameterRoots = mergeParameterRoots(
          left.expression.parameterRoots,
          right.expression.parameterRoots
        )
      ),
      truth
    )
  }

  private[core] def mergeParameters(
      left: Vector[ElaborationIntegerParameter],
      right: Vector[ElaborationIntegerParameter],
      sourceLocation: Option[String]
  ): Vector[ElaborationIntegerParameter] = {
    if (left == null || right == null) {
      fail(
        "SPINAL-ELAB-INT-PARAMETER-SCHEMA-NULL",
        "typed elaboration parameter collection must not be null",
        sourceLocation
      )
    }
    val values = left ++ right
    validateParameterSchemas(values, sourceLocation)
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

  private def validateParameterSchemas(
      parameters: Vector[ElaborationIntegerParameter],
      sourceLocation: Option[String]
  ): Unit = {
    val portableIdentifier = "[A-Za-z_][A-Za-z0-9_]*".r
    parameters.zipWithIndex.foreach { case (parameter, index) =>
      if (parameter == null) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-SCHEMA-NULL",
          s"typed elaboration parameter schema at index $index must not be null",
          sourceLocation
        )
      }
      if (
        parameter.name == null ||
        !portableIdentifier.pattern.matcher(parameter.name).matches()
      ) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-NAME-INVALID",
          s"typed elaboration parameter name '${parameter.name}' is not a portable Verilog identifier",
          sourceLocation
        )
      }
      if (
        parameter.default == null ||
        parameter.minimum == null ||
        parameter.maximum == null ||
        !parameter.default.isValidInt ||
        parameter.minimum > parameter.maximum ||
        parameter.default < parameter.minimum ||
        parameter.default > parameter.maximum
      ) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-DOMAIN-INVALID",
          s"typed elaboration parameter '${parameter.name}' must have an Int-sized default inside its non-empty bounded domain [${parameter.minimum}, ${parameter.maximum}], received ${parameter.default}",
          sourceLocation
        )
      }
    }
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
      left.generateIndex == right.generateIndex &&
      sameParameterRoots(left.parameterRoots, right.parameterRoots)

  private[core] def withCompleteParameterRoots(
      expression: ElaborationIntegerExpression
  ): ElaborationIntegerExpression = {
    val completed = expression.completedParameterRoots
    if (completed == expression.parameterRoots) expression
    else expression.copy(parameterRoots = completed)
  }

  private[core] def withCompleteParameterRoots(
      expression: ElaborationBooleanExpression
  ): ElaborationBooleanExpression = {
    val completed = expression.completedParameterRoots
    if (completed == expression.parameterRoots) expression
    else expression.copy(parameterRoots = completed)
  }

  private[core] def mergeParameterRoots(
      left: Vector[ElaborationIntegerParameterRoot],
      right: Vector[ElaborationIntegerParameterRoot]
  ): Vector[ElaborationIntegerParameterRoot] =
    distinctParameterRoots(left ++ right)

  /** Fail closed when one emitted parameter name denotes multiple declarations. */
  private[spinal] def validateParameterRootInventory(
      role: String,
      expressions: Vector[ElaborationIntegerExpression]
  ): Unit = {
    if (expressions == null) {
      fail(
        "SPINAL-ELAB-INT-PARAMETER-ROOT-NULL",
        s"$role must retain a non-null expression inventory",
        None
      )
    }
    val associated = expressions.flatMap { expression =>
      validateExpression(expression, role)
      expression.completedParameterRoots.map(root => expression -> root)
    }
    associated
      .groupBy(_._2.name)
      .collectFirst {
        case (name, values)
            if distinctParameterRoots(values.map(_._2)).size > 1 =>
          name -> values
      }
      .foreach { case (name, values) =>
        fail(
          "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
          s"$role combines independently sourced declarations for parameter '$name'",
          values.iterator
            .flatMap { case (expression, root) =>
              root.sourceLocation.orElse(expression.sourceLocation)
            }
            .toVector
            .headOption
        )
      }
  }

  private def distinctParameterRoots(
      roots: Vector[ElaborationIntegerParameterRoot]
  ): Vector[ElaborationIntegerParameterRoot] =
    roots.foldLeft(Vector.empty[ElaborationIntegerParameterRoot]) {
      case (known, root) if known.exists(_ eq root) => known
      case (known, root)                           => known :+ root
    }

  private def sameParameterRoots(
      left: Vector[ElaborationIntegerParameterRoot],
      right: Vector[ElaborationIntegerParameterRoot]
  ): Boolean = {
    val leftDistinct = distinctParameterRoots(left)
    val rightDistinct = distinctParameterRoots(right)
    leftDistinct.size == rightDistinct.size &&
    leftDistinct.forall(root => rightDistinct.exists(_ eq root))
  }

  private[core] def validateExpression(
      expression: ElaborationIntegerExpression,
      role: String
  ): Unit = {
    if (expression == null)
      throw new IllegalArgumentException(s"$role must not be null")
    if (
      expression.sourceLocation == null ||
      expression.sourceLocation.exists(_ == null)
    ) {
      fail(
        "SPINAL-ELAB-INT-SOURCE-OPTION-NULL",
        s"$role must retain a non-null source-location option",
        None
      )
    }
    if (
      expression.generateIndex == null ||
      expression.generateIndex.exists(_ == null)
    ) {
      fail(
        "SPINAL-ELAB-INT-GENERATE-INDEX-OPTION-NULL",
        s"$role must retain a non-null generate-index option",
        expression.sourceLocation
      )
    }
    if (expression.verilog == null || expression.verilog.trim.isEmpty) {
      fail(
        "SPINAL-ELAB-INT-EXPRESSION-INVALID",
        s"$role must retain a non-empty Verilog expression",
        expression.sourceLocation
      )
    }
    if (
      expression.default == null ||
      expression.minimum == null ||
      expression.maximum == null ||
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
    if (expression.parameters == null) {
      fail(
        "SPINAL-ELAB-INT-PARAMETER-SCHEMA-NULL",
        s"$role '${expression.verilog}' must retain a non-null parameter collection",
        expression.sourceLocation
      )
    }
    if (expression.parameterRoots == null) {
      fail(
        "SPINAL-ELAB-INT-PARAMETER-ROOT-NULL",
        s"$role '${expression.verilog}' must retain a non-null parameter-root collection",
        expression.sourceLocation
      )
    }
    mergeParameters(expression.parameters, Vector.empty, expression.sourceLocation)
    validateParameterRoots(
      expression.verilog,
      expression.parameters,
      expression.parameterRoots,
      expression.sourceLocation,
      role
    )
  }

  private def validateParameterRoots(
      verilog: String,
      parameters: Vector[ElaborationIntegerParameter],
      roots: Vector[ElaborationIntegerParameterRoot],
      sourceLocation: Option[String],
      role: String
  ): Unit = {
    roots.foreach { root =>
      if (root == null) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-ROOT-NULL",
          s"$role '$verilog' carries a null parameter root",
          sourceLocation
        )
      }
      if (
        root.sourceLocation == null ||
        root.sourceLocation.exists(_ == null)
      ) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-ROOT-SOURCE-OPTION-NULL",
          s"$role '$verilog' carries a parameter root with a null source-location option",
          sourceLocation
        )
      }
      if (!parameters.exists(_.name == root.name)) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-ROOT-UNKNOWN",
          s"$role '$verilog' carries provenance for unknown parameter '${root.name}'",
          root.sourceLocation.orElse(sourceLocation)
        )
      }
    }
    val distinctRoots = distinctParameterRoots(roots)
    distinctRoots.groupBy(_.name).collectFirst {
      case (name, declarations) if declarations.size > 1 => name
    }.foreach { name =>
      fail(
        "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
        s"$role '$verilog' combines independently sourced declarations for parameter '$name'",
        distinctRoots
          .filter(_.name == name)
          .flatMap(_.sourceLocation)
          .headOption
          .orElse(sourceLocation)
      )
    }
  }

  private[core] def validateExpression(
      expression: ElaborationBooleanExpression,
      role: String
  ): Unit = {
    if (expression == null)
      throw new IllegalArgumentException(s"$role must not be null")
    if (
      expression.sourceLocation == null ||
      expression.sourceLocation.exists(_ == null)
    ) {
      fail(
        "SPINAL-ELAB-BOOL-SOURCE-OPTION-NULL",
        s"$role must retain a non-null source-location option",
        None
      )
    }
    if (expression.verilog == null || expression.verilog.trim.isEmpty) {
      fail(
        "SPINAL-ELAB-BOOL-EXPRESSION-INVALID",
        s"$role must retain a non-empty Verilog expression",
        expression.sourceLocation
      )
    }
    if (expression.parameters == null) {
      fail(
        "SPINAL-ELAB-INT-PARAMETER-SCHEMA-NULL",
        s"$role '${expression.verilog}' must retain a non-null parameter collection",
        expression.sourceLocation
      )
    }
    if (expression.parameterRoots == null) {
      fail(
        "SPINAL-ELAB-INT-PARAMETER-ROOT-NULL",
        s"$role '${expression.verilog}' must retain a non-null parameter-root collection",
        expression.sourceLocation
      )
    }
    mergeParameters(expression.parameters, Vector.empty, expression.sourceLocation)
    validateParameterRoots(
      expression.verilog,
      expression.parameters,
      expression.parameterRoots,
      expression.sourceLocation,
      role
    )
  }

  private[core] def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing = ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
