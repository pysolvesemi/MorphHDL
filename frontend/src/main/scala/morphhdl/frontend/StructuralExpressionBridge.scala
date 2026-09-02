package morphhdl.frontend

import morphhdl.paramrtl._
import morphhdl.paramrtl.BoolExpr
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr
import spinal.core.{ElaborationBooleanExpression, ElaborationIntegerExpression, ElaborationIntegerParameter}

/** Opaque result of one completed frontend integer-AST analysis.  The visible
  * fields are immutable identities; construction alone is insufficient unless
  * the analyzer-private seal also validates.
  */
final class AnalyzedFrontendInteger private[frontend] (
    val sourceIdentity: AnyRef,
    val expression: ElaborationIntegerExpression,
    val singleRootEvaluations: Option[Vector[(BigInt, BigInt)]],
    private val analyzerSeal: AnyRef
) {
  private[this] var singleRootIssued = false

  def requireAnalyzerAuthentication(): Unit =
    if (!StructuralExpressionBridge.authenticates(analyzerSeal)) {
      FrontendException.failAt(
        "MORPH-FRONTEND-ANALYZED-INTEGER-AUTHORIZATION-INVALID",
        "analyzed integer wrapper was not constructed by the frontend AST analyzer",
        SourceOrigin("<analyzed-integer>", 1)
      )
    }

  def claimSingleRoot(): (
      AnyRef,
      ElaborationIntegerExpression,
      Vector[(BigInt, BigInt)]
  ) = synchronized {
    requireAnalyzerAuthentication()
    if (singleRootIssued) {
      FrontendException.failAt(
        "MORPH-FRONTEND-ANALYZED-INTEGER-AUTHORIZATION-CONSUMED",
        "analyzed integer wrapper already issued its single-root permit",
        SourceOrigin("<analyzed-integer>", 1)
      )
    }
    val evaluations = singleRootEvaluations.getOrElse {
      FrontendException.failAt(
        "MORPH-FRONTEND-ANALYZED-INTEGER-SINGLE-ROOT-EVIDENCE-MISSING",
        "analyzed integer wrapper has no exhaustive single-root evaluation table",
        SourceOrigin("<analyzed-integer>", 1)
      )
    }
    singleRootIssued = true
    (sourceIdentity, expression, evaluations)
  }
}

sealed abstract class AnalyzedStructuralIntegerKind private[frontend] (
    val label: String
)

object AnalyzedStructuralIntegerKind {
  case object ProcessRangeCount extends AnalyzedStructuralIntegerKind("process-range-count")
  case object StructuralCaseSelector extends AnalyzedStructuralIntegerKind("structural-case-selector")
  case object ProcessSliceOffset extends AnalyzedStructuralIntegerKind("process-slice-offset")
  case object ProcessSliceWidth extends AnalyzedStructuralIntegerKind("process-slice-width")
  case object StructuralSliceOffset extends AnalyzedStructuralIntegerKind("structural-slice-offset")
  case object StructuralSliceWidth extends AnalyzedStructuralIntegerKind("structural-slice-width")
  case object ProcessVecIndex extends AnalyzedStructuralIntegerKind("process-vec-index")
  case object StructuralVecIndex extends AnalyzedStructuralIntegerKind("structural-vec-index")
}

sealed abstract class AnalyzedStructuralBooleanKind private[frontend] (
    val label: String
)

object AnalyzedStructuralBooleanKind {
  case object StructuralIfCondition extends AnalyzedStructuralBooleanKind("structural-if-condition")
}

/** One analyzed structural integer publication, bound to its exact operation
  * kind and target identities.  The descriptive EIE is returned only by the
  * successful one-shot claim.
  */
final class AnalyzedStructuralInteger private[frontend] (
    val sourceIdentity: AnyRef,
    val expression: ElaborationIntegerExpression,
    private val publicationKind: AnalyzedStructuralIntegerKind,
    private val targetIdentities: Vector[AnyRef],
    private val origin: SourceOrigin,
    private val analyzerSeal: AnyRef
) {
  private[this] var consumed = false

  def claim(
      expectedKind: AnalyzedStructuralIntegerKind,
      expectedTargets: Vector[AnyRef]
  ): (AnyRef, ElaborationIntegerExpression) = synchronized {
    if (!StructuralExpressionBridge.authenticatesStructuralInteger(analyzerSeal)) {
      FrontendException.failAt(
        "MORPH-FRONTEND-ANALYZED-STRUCTURAL-INTEGER-AUTHORIZATION-INVALID",
        "structural integer wrapper was not constructed by the frontend AST analyzer",
        origin
      )
    }
    if ((expectedKind eq null) || !(publicationKind eq expectedKind)) {
      FrontendException.failAt(
        "MORPH-FRONTEND-ANALYZED-STRUCTURAL-INTEGER-KIND-MISMATCH",
        s"analyzed structural integer '${publicationKind.label}' cannot authorize '${Option(expectedKind).map(_.label).getOrElse("<null>")}' publication",
        origin
      )
    }
    if (!sameTargetIdentities(expectedTargets)) {
      FrontendException.failAt(
        "MORPH-FRONTEND-ANALYZED-STRUCTURAL-INTEGER-TARGET-MISMATCH",
        s"analyzed structural integer '${publicationKind.label}' was presented to a foreign publication target",
        origin
      )
    }
    if (consumed) {
      FrontendException.failAt(
        "MORPH-FRONTEND-ANALYZED-STRUCTURAL-INTEGER-AUTHORIZATION-CONSUMED",
        s"analyzed structural integer '${publicationKind.label}' was already published",
        origin
      )
    }
    consumed = true
    sourceIdentity -> expression
  }

  private def sameTargetIdentities(expected: Vector[AnyRef]): Boolean =
    (expected ne null) && expected.size == targetIdentities.size &&
      expected.zip(targetIdentities).forall { case (left, right) => left eq right }
}

final class AnalyzedStructuralBoolean private[frontend] (
    val sourceIdentity: AnyRef,
    val expression: ElaborationBooleanExpression,
    private val singleRootEvaluations: Option[Vector[(BigInt, Boolean)]],
    private val publicationKind: AnalyzedStructuralBooleanKind,
    private val targetIdentities: Vector[AnyRef],
    private val origin: SourceOrigin,
    private val analyzerSeal: AnyRef
) {
  private[this] var consumed = false

  def claim(
      expectedKind: AnalyzedStructuralBooleanKind,
      expectedTargets: Vector[AnyRef]
  ): (
      AnyRef,
      ElaborationBooleanExpression,
      Option[Vector[(BigInt, Boolean)]]
  ) = synchronized {
    if (!StructuralExpressionBridge.authenticatesStructuralBoolean(analyzerSeal)) {
      FrontendException.failAt(
        "MORPH-FRONTEND-ANALYZED-STRUCTURAL-BOOLEAN-AUTHORIZATION-INVALID",
        "structural Boolean wrapper was not constructed by the frontend AST analyzer",
        origin
      )
    }
    if ((expectedKind eq null) || !(publicationKind eq expectedKind)) {
      FrontendException.failAt(
        "MORPH-FRONTEND-ANALYZED-STRUCTURAL-BOOLEAN-KIND-MISMATCH",
        s"analyzed structural Boolean '${publicationKind.label}' cannot authorize '${Option(expectedKind).map(_.label).getOrElse("<null>")}' publication",
        origin
      )
    }
    if (!sameTargetIdentities(expectedTargets)) {
      FrontendException.failAt(
        "MORPH-FRONTEND-ANALYZED-STRUCTURAL-BOOLEAN-TARGET-MISMATCH",
        s"analyzed structural Boolean '${publicationKind.label}' was presented to a foreign publication target",
        origin
      )
    }
    if (consumed) {
      FrontendException.failAt(
        "MORPH-FRONTEND-ANALYZED-STRUCTURAL-BOOLEAN-AUTHORIZATION-CONSUMED",
        s"analyzed structural Boolean '${publicationKind.label}' was already published",
        origin
      )
    }
    consumed = true
    (sourceIdentity, expression, singleRootEvaluations)
  }

  private def sameTargetIdentities(expected: Vector[AnyRef]): Boolean =
    (expected ne null) && expected.size == targetIdentities.size &&
      expected.zip(targetIdentities).forall { case (left, right) => left eq right }
}

/** Converts the guarded frontend expressions into backend-neutral core metadata. */
private[frontend] object StructuralExpressionBridge {
  private object AnalyzerSeal
  private object StructuralIntegerAnalyzerSeal
  private object StructuralBooleanAnalyzerSeal

  private[frontend] def authenticates(value: AnyRef): Boolean =
    value eq AnalyzerSeal

  private[frontend] def authenticatesStructuralInteger(value: AnyRef): Boolean =
    value eq StructuralIntegerAnalyzerSeal

  private[frontend] def authenticatesStructuralBoolean(value: AnyRef): Boolean =
    value eq StructuralBooleanAnalyzerSeal

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

  /** Analyze and retain one exact integer carrier together with any exhaustive
    * single-root table derived from the same HdlInt AST.  No API accepts a raw
    * expression/table pair and turns it into this wrapper.
    */
  def analyzedWidth(
      value: HdlInt,
      role: String,
      sourceLocation: Option[String] = None
  ): AnalyzedFrontendInteger = {
    val analyzed = integerImpl(
      value,
      role,
      NativeStructuralFrontend.currentGenerateIndices,
      allowPortableLogHelper = true
    )
    val expression = sourceLocation match {
      case Some(location) => analyzed.copy(sourceLocation = Some(location))
      case None           => analyzed
    }
    new AnalyzedFrontendInteger(
      sourceIdentity = value,
      expression = expression,
      singleRootEvaluations = singleRootEvaluations(value),
      analyzerSeal = AnalyzerSeal
    )
  }

  def analyzedStructuralInteger(
      value: HdlInt,
      role: String,
      generateIndices: Map[String, GenerateIndexFacts],
      kind: AnalyzedStructuralIntegerKind,
      targets: Vector[AnyRef]
  ): AnalyzedStructuralInteger = {
    if (value eq null)
      throw new IllegalArgumentException("structural integer source must not be null")
    if (kind eq null)
      throw new IllegalArgumentException("structural integer publication kind must not be null")
    requirePublicationTargets(targets, role, value.origin)
    new AnalyzedStructuralInteger(
      sourceIdentity = value,
      expression = integerImpl(
        value,
        role,
        generateIndices,
        allowPortableLogHelper = false
      ),
      publicationKind = kind,
      targetIdentities = targets,
      origin = value.origin,
      analyzerSeal = StructuralIntegerAnalyzerSeal
    )
  }

  def analyzedStructuralBoolean(
      value: HdlBool,
      role: String,
      kind: AnalyzedStructuralBooleanKind,
      targets: Vector[AnyRef]
  ): AnalyzedStructuralBoolean = {
    if (value eq null)
      throw new IllegalArgumentException("structural Boolean source must not be null")
    if (kind eq null)
      throw new IllegalArgumentException("structural Boolean publication kind must not be null")
    requirePublicationTargets(targets, role, value.origin)
    new AnalyzedStructuralBoolean(
      sourceIdentity = value,
      expression = boolean(value, role),
      singleRootEvaluations = singleRootBooleanEvaluations(value),
      publicationKind = kind,
      targetIdentities = targets,
      origin = value.origin,
      analyzerSeal = StructuralBooleanAnalyzerSeal
    )
  }

  private def requirePublicationTargets(
      targets: Vector[AnyRef],
      role: String,
      origin: SourceOrigin
  ): Unit =
    if (
      (targets eq null) || targets.isEmpty ||
      targets.exists(target => target eq null)
    ) {
      FrontendException.failAt(
        "MORPH-FRONTEND-ANALYZED-STRUCTURAL-TARGET-MISSING",
        s"$role requires non-null exact publication targets",
        origin
      )
    }

  /** Exhaustive evaluation of one bounded frontend AST for the neutral typed
    * elaboration carrier. This is rooted in exactly one integer or Boolean
    * declaration-token object; rendered identifiers are not used to infer
    * provenance.
    */
  def singleRootEvaluations(
      value: HdlInt
  ): Option[Vector[(BigInt, BigInt)]] = {
    if (
      (value eq null) ||
      value.parameters.size + value.booleanParameters.size != 1 ||
      value.localDeclaration.nonEmpty ||
      value.localParameters.nonEmpty || value.booleanLocalParameters.nonEmpty ||
      value.scope.nonEmpty
    ) return None

    value.parameters.toVector match {
      case Vector(token) if value.booleanParameters.isEmpty =>
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

      case Vector() =>
        value.booleanParameters.toVector match {
          case Vector(token) =>
            val builder = Vector.newBuilder[(BigInt, BigInt)]
            var rootValue = BigInt(0)
            while (rootValue <= 1) {
              val facts = IntExpressionAnalysis
                .analyze(
                  value.expression,
                  parameters = Map.empty,
                  localParameters = Map.empty,
                  booleanParameters = Map(
                    token.declaration.name ->
                      token.declaration.copy(default = rootValue == 1)
                  ),
                  generateIndices = Map.empty,
                  booleanLocalParameters = Map.empty
                )
                .fold(_ => return None, identity)
              builder += rootValue -> facts.defaultValue
              rootValue += 1
            }
            Some(builder.result())
          case _ => None
        }

      case _ => None
    }
  }

  /** Exhaustive evaluation of one bounded frontend Boolean AST over its exact
    * declaration-token root.  This evidence never comes from rendered
    * identifiers: the sole admitted root is selected from the HdlBool's exact
    * ParameterToken or BooleanParameterToken identity.
    */
  private def singleRootBooleanEvaluations(
      value: HdlBool
  ): Option[Vector[(BigInt, Boolean)]] = {
    if (
      (value eq null) || value.localDeclaration.nonEmpty ||
      value.localParameters.nonEmpty || value.booleanLocalParameters.nonEmpty ||
      value.integerParameters.size + value.parameters.size != 1
    ) return None

    value.integerParameters.toVector match {
      case Vector(token) if value.parameters.isEmpty =>
        val parameterFacts =
          IntExpressionAnalysis.parameterFacts(token.declaration).getOrElse(return None)
        val minimum = parameterFacts.interval.lower.getOrElse(return None)
        val maximum = parameterFacts.interval.upper.getOrElse(return None)
        val size = maximum - minimum + 1
        if (size < 1 || size > spinal.core.ElabInt.MaximumExactDomainSize)
          return None

        val builder = Vector.newBuilder[(BigInt, Boolean)]
        var rootValue = minimum
        while (rootValue <= maximum) {
          val point = IntExprFacts(rootValue, IntInterval.point(rootValue))
          val result = BoolExpressionAnalysis
            .evaluateDefault(
              value.expression,
              booleanParameters = Map.empty,
              integerParameters = Map(token.declaration.name -> point),
              localParameters = Map.empty,
              generateIndices = Map.empty,
              booleanLocalParameters = Map.empty
            )
            .fold(_ => return None, identity)
          builder += rootValue -> result
          rootValue += 1
        }
        Some(builder.result())

      case Vector() =>
        value.parameters.toVector match {
          case Vector(token) =>
            val builder = Vector.newBuilder[(BigInt, Boolean)]
            var rootValue = BigInt(0)
            while (rootValue <= 1) {
              val result = BoolExpressionAnalysis
                .evaluateDefault(
                  value.expression,
                  booleanParameters = Map(
                    token.declaration.name ->
                      token.declaration.copy(default = rootValue == 1)
                  ),
                  integerParameters = Map.empty,
                  localParameters = Map.empty,
                  generateIndices = Map.empty,
                  booleanLocalParameters = Map.empty
                )
                .fold(_ => return None, identity)
              builder += rootValue -> result
              rootValue += 1
            }
            Some(builder.result())
          case _ => None
        }

      case _ => None
    }
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
    token.canonicalSchema(minimum, maximum)
  }

  private def booleanSchema(
      token: BooleanParameterToken
  ): ElaborationIntegerParameter =
    token.canonicalSchema

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
