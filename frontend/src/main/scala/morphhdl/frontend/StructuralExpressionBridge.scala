package morphhdl.frontend

import morphhdl.paramrtl._
import morphhdl.paramrtl.BoolExpr
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr
import spinal.core.{ElaborationBooleanExpression, ElaborationIntegerExpression, ElaborationIntegerParameter}

/** Converts the guarded frontend expressions into backend-neutral core metadata. */
private[frontend] object StructuralExpressionBridge {
  final case class GenerateIndexFacts(
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt
  )

  def integer(
      value: HdlInt,
      role: String,
      generateIndices: Map[String, GenerateIndexFacts]
  ): ElaborationIntegerExpression =
    integerImpl(value, role, generateIndices, allowPortableLogHelper = false)

  private def integerImpl(
      value: HdlInt,
      role: String,
      generateIndices: Map[String, GenerateIndexFacts],
      allowPortableLogHelper: Boolean
  ): ElaborationIntegerExpression = {
    if (value eq null) {
      FrontendException.fail(
        "MORPH-FRONTEND-STRUCTURAL-INTEGER-NULL",
        s"$role requires a non-null HdlInt"
      )
    }
    value.requireUsable(role)
    rejectLocalParameters(
      role,
      value.localParameters.nonEmpty,
      value.booleanLocalParameters.nonEmpty,
      value.origin
    )

    val integerFacts = value.parameters.toVector.map { token =>
      val declaration = token.declaration
      val facts = IntExpressionAnalysis.parameterFacts(declaration).getOrElse {
        FrontendException.failAt(
          "MORPH-FRONTEND-STRUCTURAL-PARAMETER-DOMAIN-INVALID",
          s"parameter '${declaration.name}' has an empty or unbounded structural domain",
          value.origin
        )
      }
      declaration.name -> facts
    }.toMap
    val booleanParameters =
      value.booleanParameters.toVector.map(token => token.declaration.name -> token.declaration).toMap
    val indexFacts = generateIndices.map { case (name, facts) =>
      name -> IntExprFacts(
        facts.default,
        IntInterval(Some(facts.minimum), Some(facts.maximum))
      )
    }

    val facts = IntExpressionAnalysis
      .analyze(
        value.expression,
        integerFacts,
        localParameters = Map.empty,
        booleanParameters = booleanParameters,
        generateIndices = indexFacts,
        booleanLocalParameters = Map.empty
      )
      .fold(
        failure =>
          FrontendException.failAt(
            "MORPH-FRONTEND-STRUCTURAL-EXPRESSION-UNPROVEN",
            s"$role expression '${value.expression}' is not proven over its complete domain: $failure",
            value.origin
          ),
        identity
      )
    val minimum = facts.interval.lower.getOrElse {
      FrontendException.failAt(
        "MORPH-FRONTEND-STRUCTURAL-EXPRESSION-UNBOUNDED",
        s"$role expression '${value.expression}' has no finite minimum",
        value.origin
      )
    }
    val maximum = facts.interval.upper.getOrElse {
      FrontendException.failAt(
        "MORPH-FRONTEND-STRUCTURAL-EXPRESSION-UNBOUNDED",
        s"$role expression '${value.expression}' has no finite maximum",
        value.origin
      )
    }
    val parameters =
      schemas(value.parameters, value.booleanParameters, value.origin)
    val indexName = value.scope.map(_.indexName)
    indexName.foreach { name =>
      if (!generateIndices.contains(name)) {
        FrontendException.failAt(
          "MORPH-FRONTEND-STRUCTURAL-GENINDEX-CONTEXT-MISSING",
          s"$role references generate index '$name' outside its native structural capture",
          value.origin
        )
      }
    }

    ElaborationIntegerExpression(
      verilog = renderInteger(value.expression, value.origin, allowPortableLogHelper),
      default = facts.defaultValue,
      minimum = minimum,
      maximum = maximum,
      parameters = parameters,
      generateIndex = indexName,
      sourceLocation = Some(value.origin.rendered),
      parameterRoots = parameterRoots(
        value.parameters,
        value.booleanParameters
      )
    )
  }

  def integer(
      value: HdlInt,
      role: String
  ): ElaborationIntegerExpression =
    integer(value, role, NativeStructuralFrontend.currentGenerateIndices)

  def width(
      value: HdlInt,
      role: String
  ): ElaborationIntegerExpression =
    integerImpl(
      value,
      role,
      NativeStructuralFrontend.currentGenerateIndices,
      allowPortableLogHelper = true
    )

  /** Exhaustive evaluation of one bounded frontend AST for the neutral typed
    * elaboration carrier.  This is rooted in the exact ParameterToken object;
    * rendered identifiers are not used to infer provenance.
    */
  def singleRootEvaluations(
      value: HdlInt
  ): Option[Vector[(BigInt, BigInt)]] = {
    if (
      value == null || value.parameters.size != 1 ||
      value.booleanParameters.nonEmpty || value.localDeclaration.nonEmpty ||
      value.localParameters.nonEmpty || value.booleanLocalParameters.nonEmpty ||
      value.scope.nonEmpty
    ) return None

    val token = value.parameters.head
    val parameterFacts =
      IntExpressionAnalysis.parameterFacts(token.declaration).getOrElse(return None)
    val minimum = parameterFacts.interval.lower.getOrElse(return None)
    val maximum = parameterFacts.interval.upper.getOrElse(return None)
    val size = maximum - minimum + 1
    if (size < 1 || size > spinal.core.ElabInt.MaximumExactDomainSize)
      return None

    val builder = Vector.newBuilder[(BigInt, BigInt)]
    var rootValue = minimum
    while (rootValue <= maximum) {
      val point = IntExprFacts(rootValue, IntInterval.point(rootValue))
      val facts = IntExpressionAnalysis
        .analyze(
          value.expression,
          parameters = Map(token.declaration.name -> point),
          localParameters = Map.empty,
          booleanParameters = Map.empty,
          generateIndices = Map.empty,
          booleanLocalParameters = Map.empty
        )
        .fold(_ => return None, identity)
      builder += rootValue -> facts.defaultValue
      rootValue += 1
    }
    Some(builder.result())
  }

  def integer(
      value: GenIndex,
      role: String
  ): ElaborationIntegerExpression = {
    if (value eq null) {
      FrontendException.fail(
        "MORPH-FRONTEND-STRUCTURAL-GENINDEX-NULL",
        s"$role requires a non-null GenIndex"
      )
    }
    integer(
      HdlInt.fromGenerateIndex(
        value.witness,
        IntExpr.GenerateIndexRef(value.token.indexName),
        value.token,
        parameters = Set.empty,
        booleanParameters = Set.empty,
        localParameters = Set.empty,
        booleanLocalParameters = Set.empty,
        origin = value.origin
      ),
      role
    )
  }

  def boolean(
      value: HdlBool,
      role: String
  ): ElaborationBooleanExpression = {
    if (value eq null) {
      FrontendException.fail(
        "MORPH-FRONTEND-STRUCTURAL-BOOLEAN-NULL",
        s"$role requires a non-null HdlBool"
      )
    }
    rejectLocalParameters(
      role,
      value.localParameters.nonEmpty,
      value.booleanLocalParameters.nonEmpty,
      value.origin
    )
    ElaborationBooleanExpression(
      verilog = renderBoolean(value.expression, value.origin),
      default = value.witness,
      parameters = schemas(value.integerParameters, value.parameters, value.origin),
      sourceLocation = Some(value.origin.rendered),
      parameterRoots = parameterRoots(
        value.integerParameters,
        value.parameters
      )
    )
  }

  private def schemas(
      integers: Set[ParameterToken],
      booleans: Set[BooleanParameterToken],
      origin: SourceOrigin
  ): Vector[ElaborationIntegerParameter] = {
    val values =
      integers.toVector.map(integerSchema(_, origin)) ++
        booleans.toVector.map(booleanSchema)
    val grouped = values.groupBy(_.name)
    grouped
      .collectFirst {
        case (name, declarations) if declarations.distinct.size != 1 => name
      }
      .foreach { name =>
        FrontendException.failAt(
          "MORPH-FRONTEND-STRUCTURAL-PARAMETER-SCHEMA-CONFLICT",
          s"parameter '$name' has conflicting integer/Boolean structural declarations",
          origin
        )
      }
    grouped.toVector.map(_._2.head).sortBy(_.name)
  }

  private def parameterRoots(
      integers: Set[ParameterToken],
      booleans: Set[BooleanParameterToken]
  ) =
    (integers.toVector.map(_.elaborationRoot) ++
      booleans.toVector.map(_.elaborationRoot)).sortBy(root => (root.name, root.sourceLocation.getOrElse("")))

  private def integerSchema(
      token: ParameterToken,
      origin: SourceOrigin
  ): ElaborationIntegerParameter = {
    val declaration = token.declaration
    val minimums = declaration.constraints.collect { case MinInclusive(value) =>
      value
    }
    val maximums = declaration.constraints.collect { case MaxInclusive(value) =>
      value
    }
    if (minimums.isEmpty || maximums.isEmpty) {
      FrontendException.failAt(
        "MORPH-FRONTEND-STRUCTURAL-PARAMETER-DOMAIN-UNBOUNDED",
        s"parameter '${declaration.name}' requires finite minimum and maximum bounds for structural lowering",
        origin
      )
    }
    val minimum = minimums.max
    val maximum = maximums.min
    if (
      minimum > maximum ||
      declaration.default < minimum ||
      declaration.default > maximum
    ) {
      FrontendException.failAt(
        "MORPH-FRONTEND-STRUCTURAL-PARAMETER-DOMAIN-INVALID",
        s"parameter '${declaration.name}' default ${declaration.default} is outside [$minimum, $maximum]",
        origin
      )
    }
    ElaborationIntegerParameter(
      declaration.name,
      declaration.default,
      minimum,
      maximum
    )
  }

  private def booleanSchema(
      token: BooleanParameterToken
  ): ElaborationIntegerParameter =
    ElaborationIntegerParameter(
      token.declaration.name,
      if (token.declaration.default) BigInt(1) else BigInt(0),
      minimum = BigInt(0),
      maximum = BigInt(1)
    )

  private def rejectLocalParameters(
      role: String,
      hasIntegerLocals: Boolean,
      hasBooleanLocals: Boolean,
      origin: SourceOrigin
  ): Unit =
    if (hasIntegerLocals || hasBooleanLocals) {
      FrontendException.failAt(
        "MORPH-FRONTEND-STRUCTURAL-LOCAL-PARAMETER-UNSUPPORTED",
        s"$role cannot reference frontend local parameters until native local-parameter retention is integrated",
        origin
      )
    }

  private def renderInteger(
      expression: IntExpr,
      origin: SourceOrigin,
      allowPortableLogHelper: Boolean
  ): String = expression match {
    case IntExpr.Literal(value)         => value.toString
    case IntExpr.ParameterRef(name)     => name
    case IntExpr.GenerateIndexRef(name) => name
    case IntExpr.LocalParameterRef(name) =>
      FrontendException.failAt(
        "MORPH-FRONTEND-STRUCTURAL-LOCAL-PARAMETER-UNSUPPORTED",
        s"structural integer expression references local parameter '$name'",
        origin
      )
    case IntExpr.Negate(value) =>
      s"-(${renderInteger(value, origin, allowPortableLogHelper)})"
    case IntExpr.Add(left, right) =>
      binary(left, "+", right, origin, allowPortableLogHelper)
    case IntExpr.Subtract(left, right) =>
      binary(left, "-", right, origin, allowPortableLogHelper)
    case IntExpr.Multiply(left, right) =>
      binary(left, "*", right, origin, allowPortableLogHelper)
    case IntExpr.Divide(left, right) =>
      binary(left, "/", right, origin, allowPortableLogHelper)
    case IntExpr.Modulo(left, right) =>
      binary(left, "%", right, origin, allowPortableLogHelper)
    case IntExpr.Min(left, right) =>
      val l = renderInteger(left, origin, allowPortableLogHelper)
      val r = renderInteger(right, origin, allowPortableLogHelper)
      s"(($l) < ($r) ? ($l) : ($r))"
    case IntExpr.Max(left, right) =>
      val l = renderInteger(left, origin, allowPortableLogHelper)
      val r = renderInteger(right, origin, allowPortableLogHelper)
      s"(($l) > ($r) ? ($l) : ($r))"
    case IntExpr.Select(condition, whenTrue, whenFalse) =>
      s"((${renderBoolean(condition, origin, allowPortableLogHelper)}) ? (${renderInteger(whenTrue, origin, allowPortableLogHelper)}) : (${renderInteger(whenFalse, origin, allowPortableLogHelper)}))"
    case IntExpr.AddressWidth(value) if allowPortableLogHelper =>
      s"clog2(${renderInteger(value, origin, allowPortableLogHelper)}, 1)"
    case IntExpr.CeilLog2(value) if allowPortableLogHelper =>
      s"clog2(${renderInteger(value, origin, allowPortableLogHelper)}, 0)"
    case IntExpr.AddressWidth(_) | IntExpr.CeilLog2(_) =>
      FrontendException.failAt(
        "MORPH-FRONTEND-STRUCTURAL-INTEGER-OPERATOR-UNSUPPORTED",
        s"structural expression '$expression' requires a helper function that is not yet admitted inside generate control",
        origin
      )
  }

  private def binary(
      left: IntExpr,
      operator: String,
      right: IntExpr,
      origin: SourceOrigin,
      allowPortableLogHelper: Boolean
  ): String =
    s"(${renderInteger(left, origin, allowPortableLogHelper)} $operator ${renderInteger(right, origin, allowPortableLogHelper)})"

  private def renderBoolean(
      expression: BoolExpr,
      origin: SourceOrigin,
      allowPortableLogHelper: Boolean = false
  ): String = expression match {
    case BoolExpr.Literal(value) =>
      if (value) "1'b1" else "1'b0"
    case BoolExpr.ParameterRef(name) =>
      s"($name == 1)"
    case BoolExpr.LocalParameterRef(name) =>
      FrontendException.failAt(
        "MORPH-FRONTEND-STRUCTURAL-LOCAL-PARAMETER-UNSUPPORTED",
        s"structural Boolean expression references local parameter '$name'",
        origin
      )
    case BoolExpr.LessThan(left, right) =>
      compare(left, "<", right, origin, allowPortableLogHelper)
    case BoolExpr.LessThanOrEqual(left, right) =>
      compare(left, "<=", right, origin, allowPortableLogHelper)
    case BoolExpr.GreaterThan(left, right) =>
      compare(left, ">", right, origin, allowPortableLogHelper)
    case BoolExpr.GreaterThanOrEqual(left, right) =>
      compare(left, ">=", right, origin, allowPortableLogHelper)
    case BoolExpr.Equal(left, right) =>
      compare(left, "==", right, origin, allowPortableLogHelper)
    case BoolExpr.NotEqual(left, right) =>
      compare(left, "!=", right, origin, allowPortableLogHelper)
    case BoolExpr.Not(value) =>
      s"!(${renderBoolean(value, origin, allowPortableLogHelper)})"
    case BoolExpr.And(left, right) =>
      s"((${renderBoolean(left, origin, allowPortableLogHelper)}) && (${renderBoolean(right, origin, allowPortableLogHelper)}))"
    case BoolExpr.Or(left, right) =>
      s"((${renderBoolean(left, origin, allowPortableLogHelper)}) || (${renderBoolean(right, origin, allowPortableLogHelper)}))"
  }

  private def compare(
      left: IntExpr,
      operator: String,
      right: IntExpr,
      origin: SourceOrigin,
      allowPortableLogHelper: Boolean
  ): String =
    s"((${renderInteger(left, origin, allowPortableLogHelper)}) $operator (${renderInteger(right, origin, allowPortableLogHelper)}))"
}
