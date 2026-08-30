package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.collection.mutable

import ExternalNativeIntRelativeExpression.{AddressWidth, CeilLog2, Literal, Root}
import ExternalNativeIntRelativePredicate.{Comparison, Constant, PowerOfTwo}

/** Immutable capture returned after one boundary constructor has completed.
  * The constructor is package-private so callers cannot fabricate provenance.
  */
final class ExternalNativeIntShadowCapture[A] private[core] (
    val result: A,
    private[core] val expression: ElaborationIntegerExpression,
    private[core] val definitionExpression: ElaborationIntegerExpression,
    private[core] val token: ExternalNativeIntFormalizationToken,
    private[core] val parentToken: Option[ExternalNativeIntFormalizationToken],
    private[core] val pendingSlots: Vector[ExternalNativeIntShadowPendingSlot],
    private[core] val pendingPredicates: Vector[ExternalNativeIntShadowPendingPredicate]
)

private[core] final case class ExternalNativeIntShadowPendingSlot(
    token: ExternalNativeIntShadowSlotToken,
    witness: Int,
    expression: ExternalNativeIntRelativeExpression
)

private[core] final case class ExternalNativeIntShadowPendingPredicate(
    token: ExternalNativeIntShadowPredicateToken,
    witness: Boolean,
    predicate: ExternalNativeIntRelativePredicate
)

private[core] final case class ExternalNativeIntShadowTrackedValue(
    witness: Int,
    expression: ExternalNativeIntRelativeExpression,
    sourceLocation: String
)

private[core] final class ExternalNativeIntShadowComponentIdentityRef(
    value: Component,
    queue: ReferenceQueue[Component]
) extends WeakReference[Component](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalNativeIntShadowComponentIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

private[core] final class ExternalNativeIntShadowRegionIdentityRef(
    value: Data,
    queue: ReferenceQueue[Data]
) extends WeakReference[Data](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalNativeIntShadowRegionIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** Weak identity key for one exact lowered native-Int expression. */
private[core] final class ExternalNativeIntExpressionIdentityRef(
    value: ElaborationIntegerExpression,
    queue: ReferenceQueue[ElaborationIntegerExpression]
) extends WeakReference[ElaborationIntegerExpression](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalNativeIntExpressionIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** MorphHDL-owned shadow provenance registry for native Scala `Int` values.
  *
  * A boundary keeps a thread-local stack only while an untouched constructor is
  * executing. Ordinary `Int` and `Boolean` values stay unchanged. The compiler
  * supplies deterministic source references for proven values, so this registry
  * never discovers provenance by matching equal numeric witnesses. The ordinary
  * `Int` value is never boxed, replaced or used as a discovery key. Increment 49
  * deliberately accepts only direct aliases; Increment 50 extends that contract
  * only for compiler-proven expressions.
  * The ordinary `Int` value is never boxed, replaced or used as a lookup key.
  * Increment 49 deliberately accepts only direct aliases of the boundary witness.
  *
  * Stable domain diagnostics include
  * MORPH-FRONTEND-NATIVE-INT-EXPRESSION-DOMAIN-OVERFLOW and
  * MORPH-FRONTEND-NATIVE-INT-EXPRESSION-DIVISOR-ZERO-DOMAIN. Escape diagnostics
  * include MORPH-FRONTEND-NATIVE-INT-EXPRESSION-BOXING-UNSUPPORTED and
  * MORPH-FRONTEND-NATIVE-INT-EXPRESSION-MUTABLE-ESCAPE.
  */
object ExternalNativeIntShadowRegistry {
  private[core] val MaximumStructuralPredicateDomainSize =
    ElaborationExactDomain.MaximumDomainSize

  private final case class DefinitionExpressionEvidence(
      root: ParameterizedStructure.StructuralPredicateRoot,
      expression: ExternalNativeIntRelativeExpression
  )

  private final class ActiveBoundary(
      val expression: ElaborationIntegerExpression,
      val definitionExpression: ElaborationIntegerExpression,
      val token: ExternalNativeIntFormalizationToken,
      val parentToken: Option[ExternalNativeIntFormalizationToken]
  ) {
    val structuralPredicateRoot = new ParameterizedStructure.StructuralPredicateRoot(
      definitionExpression.verilog,
      definitionExpression.default,
      definitionExpression.minimum,
      definitionExpression.maximum,
      definitionExpression.parameters
    )
    val slots = mutable.LinkedHashMap.empty[
      (ExternalNativeIntShadowKind, String),
      ExternalNativeIntShadowPendingSlot
    ]
    val predicates = mutable.LinkedHashMap.empty[
      String,
      ExternalNativeIntShadowPendingPredicate
    ]
    val trackedValues = mutable.LinkedHashMap.empty[
      String,
      ExternalNativeIntShadowTrackedValue
    ]
  }

  private val active = new ThreadLocal[List[ActiveBoundary]]
  private val componentQueue = new ReferenceQueue[Component]()
  private val regionQueue = new ReferenceQueue[Data]()
  private val definitionExpressionQueue =
    new ReferenceQueue[ElaborationIntegerExpression]()
  private val components = mutable.HashMap.empty[
    ExternalNativeIntShadowComponentIdentityRef,
    Vector[ExternalNativeIntComponentShadowRecord]
  ]
  private val regions = mutable.HashMap.empty[
    ExternalNativeIntShadowRegionIdentityRef,
    ExternalNativeIntRegionShadowRecord
  ]
  private val definitionExpressionEvidence = mutable.HashMap.empty[
    ExternalNativeIntExpressionIdentityRef,
    DefinitionExpressionEvidence
  ]

  private def reapDefinitionExpressionEvidence(): Unit = {
    var reference = definitionExpressionQueue
      .poll()
      .asInstanceOf[ExternalNativeIntExpressionIdentityRef]
    while (reference != null) {
      definitionExpressionEvidence.remove(reference)
      reference = definitionExpressionQueue
        .poll()
        .asInstanceOf[ExternalNativeIntExpressionIdentityRef]
    }
  }

  private def retainDefinitionExpressionEvidence(
      lowered: ElaborationIntegerExpression,
      root: ParameterizedStructure.StructuralPredicateRoot,
      expression: ExternalNativeIntRelativeExpression
  ): Unit = synchronized {
    reapDefinitionExpressionEvidence()
    val key = new ExternalNativeIntExpressionIdentityRef(lowered, null)
    definitionExpressionEvidence.get(key) match {
      case Some(existing) if (existing.root ne root) || existing.expression != expression =>
        fail(
          "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-PROVENANCE-CONFLICT",
          "one exact lowered native Int expression carries incompatible bounded-domain provenance",
          lowered.sourceLocation
        )
      case Some(_) =>
      case None =>
        definitionExpressionEvidence.update(
          new ExternalNativeIntExpressionIdentityRef(
            lowered,
            definitionExpressionQueue
          ),
          DefinitionExpressionEvidence(root, expression)
        )
    }
  }

  /** Evaluate one exact lowered expression against its compiler-retained native
    * Int AST. Both the expression and predicate root must match by identity;
    * rendered Verilog text and equal numeric witnesses are never discovery
    * keys.
    */
  private[core] def evaluateDefinitionExpression(
      lowered: ElaborationIntegerExpression,
      root: ParameterizedStructure.StructuralPredicateRoot,
      value: BigInt
  ): Option[BigInt] = synchronized {
    if (
      lowered == null || root == null || value < root.minimum ||
      value > root.maximum
    ) return None
    reapDefinitionExpressionEvidence()
    definitionExpressionEvidence
      .get(new ExternalNativeIntExpressionIdentityRef(lowered, null))
      .filter(_.root eq root)
      .flatMap(evidence =>
        ExternalNativeIntRelativeExpression.evaluate(
          evidence.expression,
          value
        )
      )
      .filter(result => result >= lowered.minimum && result <= lowered.maximum)
  }

  /** Execute one untouched constructor with an active shadow scope. */
  def capture[A](
      expression: ElaborationIntegerExpression,
      token: ExternalNativeIntFormalizationToken,
      argumentName: String
  )(body: => A): ExternalNativeIntShadowCapture[A] =
    captureWithDefinition(
      expression = expression,
      definitionExpression = expression,
      token = token,
      argumentName = argumentName
    )(body)

  /** Execute a native child constructor while retaining separate definition
    * and instance roots. Increment 51 uses the definition root immediately for
    * structural branch capture; final attachment proves it matches the
    * canonical formal created for the returned child Component.
    */
  def captureWithDefinition[A](
      expression: ElaborationIntegerExpression,
      definitionExpression: ElaborationIntegerExpression,
      token: ExternalNativeIntFormalizationToken,
      argumentName: String
  )(body: => A): ExternalNativeIntShadowCapture[A] = {
    validateBoundaryExpression(expression, token)
    validateBoundaryExpression(definitionExpression, token)
    if (definitionExpression.default != expression.default) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-DEFINITION-DEFAULT-MISMATCH",
        s"native Int boundary '${token.role}' definition default ${definitionExpression.default} disagrees with actual default ${expression.default}",
        sourceOf(token)
      )
    }
    validateName(argumentName, token.callSite, "constructor argument")

    val previous = Option(active.get()).getOrElse(Nil)
    val boundary = new ActiveBoundary(
      expression = expression,
      definitionExpression = definitionExpression,
      token = token,
      parentToken = previous.headOption.map(_.token)
    )
    active.set(boundary :: previous)
    try {
      recordDirect(
        value = expression.default.toInt,
        name = argumentName,
        kind = ExternalNativeIntShadowKind.ConstructorArgument,
        sourceLocation = token.callSite,
        requireBoundary = true
      )
      val result = body
      new ExternalNativeIntShadowCapture[A](
        result = result,
        expression = expression,
        definitionExpression = definitionExpression,
        token = token,
        parentToken = boundary.parentToken,
        pendingSlots = boundary.slots.values.toVector,
        pendingPredicates = boundary.predicates.values.toVector
      )
    } finally {
      val current = Option(active.get()).getOrElse(Nil)
      current match {
        case head :: tail if head eq boundary =>
          if (tail.isEmpty) active.remove() else active.set(tail)
        case _ =>
          active.remove()
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-SCOPE-CORRUPT",
            s"native Int shadow boundary '${token.role}' did not close in stack order",
            sourceOf(token)
          )
      }
    }
  }

  /** Execute one ordinary native-library method whose internal Scala `Int`
    * geometry is rooted in an already-retained symbolic packed width. Unlike
    * `captureWithDefinition`, this transient scope does not create a public
    * constructor slot or attach a replacement component; it only lets generic
    * compiler instrumentation retain the native method's own arithmetic and
    * predicates while the untouched method executes.
    */
  private[core] def withDefinitionExpressionBoundary[A](
      expression: ElaborationIntegerExpression,
      token: ExternalNativeIntFormalizationToken
  )(body: => A): A = {
    validateBoundaryExpression(expression, token)
    val previous = Option(active.get()).getOrElse(Nil)
    val boundary = new ActiveBoundary(
      expression = expression,
      definitionExpression = expression,
      token = token,
      parentToken = previous.headOption.map(_.token)
    )
    active.set(boundary :: previous)
    try body
    finally {
      val current = Option(active.get()).getOrElse(Nil)
      current match {
        case head :: tail if head eq boundary =>
          if (tail.isEmpty) active.remove() else active.set(tail)
        case _ =>
          active.remove()
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-SCOPE-CORRUPT",
            s"native width-function boundary '${token.role}' did not close in stack order",
            sourceOf(token)
          )
      }
    }
  }

  /** Retain one native `widthOf` result by exact compiler reference. A concrete
    * payload width becomes a literal. A symbolic payload width must be exactly
    * the active method root; distinct symbolic roots are rejected instead of
    * being inferred from equal witnesses.
    */
  private[core] def widthQueryTracked(
      witness: Int,
      expression: Option[ElaborationIntegerExpression],
      reference: String,
      name: String,
      sourceLocation: String
  ): Int = withBoundaryOrValue(
    witness,
    requireBoundary = false,
    name,
    sourceLocation
  ) { boundary =>
    validateReference(reference, sourceLocation, "native widthOf result")
    if (witness < 0) {
      fail(
        "MORPH-FRONTEND-NATIVE-WIDTH-FUNCTION-WITNESS-INVALID",
        s"native widthOf result '$name' must be non-negative, received $witness",
        Option(sourceLocation).filter(_.nonEmpty)
      )
    }
    val relative = expression match {
      case None => Literal(BigInt(witness))
      case Some(retained) if equivalentExpression(retained, boundary.definitionExpression) =>
        if (retained.default != BigInt(witness)) {
          fail(
            "MORPH-FRONTEND-NATIVE-WIDTH-FUNCTION-WITNESS-MISMATCH",
            s"native widthOf result '$name' witness $witness disagrees with retained symbolic default ${retained.default}",
            Option(sourceLocation).filter(_.nonEmpty).orElse(retained.sourceLocation)
          )
        }
        Root
      case Some(retained) =>
        fail(
          "MORPH-FRONTEND-NATIVE-WIDTH-FUNCTION-ROOT-AMBIGUOUS",
          s"native width function mixes independent symbolic roots '${boundary.definitionExpression.verilog}' and '${retained.verilog}'; multi-root formalization is not yet enabled",
          Option(sourceLocation).filter(_.nonEmpty).orElse(retained.sourceLocation)
        )
    }
    val tracked = ExternalNativeIntShadowTrackedValue(
      witness = witness,
      expression = relative,
      sourceLocation = sourceLocation
    )
    validateWitness(boundary, tracked, sourceLocation, "native widthOf result")
    retainTracked(boundary, reference, tracked)
    witness
  }

  /** Increment 49 compatibility hook for a direct selected argument. */
  def captureArgument(
      value: Int,
      name: String,
      sourceLocation: String
  ): Int =
    recordDirect(
      value,
      name,
      ExternalNativeIntShadowKind.ConstructorArgument,
      sourceLocation,
      requireBoundary = false
    )

  /** Increment 49 compatibility hook for a direct selected local alias. */
  def captureLocal(
      value: Int,
      name: String,
      sourceLocation: String,
      requireBoundary: Boolean
  ): Int =
    recordDirect(
      value,
      name,
      ExternalNativeIntShadowKind.LocalValue,
      sourceLocation,
      requireBoundary
    )

  /** Compiler hook: select and source-track one constructor argument. */
  def captureArgumentTracked(
      value: Int,
      name: String,
      reference: String,
      sourceLocation: String
  ): Int = withBoundaryOrValue(value, false, name, sourceLocation) { boundary =>
    validateReference(reference, sourceLocation, "constructor argument")
    requireRootWitness(boundary, value, name, sourceLocation)
    val tracked = ExternalNativeIntShadowTrackedValue(value, Root, sourceLocation)
    retainTracked(boundary, reference, tracked)
    retainSlot(
      boundary,
      ExternalNativeIntShadowKind.ConstructorArgument,
      name,
      value,
      Root,
      sourceLocation
    )
    value
  }

  /** Compiler hook: select one local whose source reference is already proven. */
  def captureLocalTracked(
      value: Int,
      name: String,
      sourceReference: String,
      resultReference: String,
      sourceLocation: String,
      requireBoundary: Boolean
  ): Int = withBoundaryOrValue(value, requireBoundary, name, sourceLocation) { boundary =>
    val source = resolveTracked(
      boundary,
      value,
      sourceReference,
      literal = false,
      sourceLocation,
      role = s"selected local '$name'"
    )
    validateReference(resultReference, sourceLocation, "selected local result")
    val tracked = source.copy(witness = value, sourceLocation = sourceLocation)
    validateWitness(boundary, tracked, sourceLocation, s"selected local '$name'")
    retainTracked(boundary, resultReference, tracked)
    retainSlot(
      boundary,
      ExternalNativeIntShadowKind.LocalValue,
      name,
      value,
      tracked.expression,
      sourceLocation
    )
    value
  }

  /** Compiler hook: retain one direct immutable alias. */
  def aliasTracked(
      value: Int,
      name: String,
      sourceReference: String,
      resultReference: String,
      sourceLocation: String
  ): Int = withBoundaryOrValue(value, false, name, sourceLocation) { boundary =>
    val source = resolveTracked(
      boundary,
      value,
      sourceReference,
      literal = false,
      sourceLocation,
      role = s"native Int alias '$name'"
    )
    validateReference(resultReference, sourceLocation, "alias result")
    val tracked = source.copy(witness = value, sourceLocation = sourceLocation)
    validateWitness(boundary, tracked, sourceLocation, s"native Int alias '$name'")
    retainTracked(boundary, resultReference, tracked)
    retainSlot(
      boundary,
      ExternalNativeIntShadowKind.LocalValue,
      name,
      value,
      tracked.expression,
      sourceLocation
    )
    value
  }

  /** Compiler hook for addition, subtraction, multiplication, division, remainder and min/max. */
  def binaryTracked(
      operation: String,
      left: Int,
      leftReference: String,
      leftLiteral: Boolean,
      right: Int,
      rightReference: String,
      rightLiteral: Boolean,
      resultReference: String,
      name: String,
      sourceLocation: String
  ): Int = {
    val result = nativeBinary(operation, left, right, sourceLocation)
    withBoundaryOrValue(result, false, name, sourceLocation) { boundary =>
      val leftValue = resolveTracked(
        boundary,
        left,
        leftReference,
        leftLiteral,
        sourceLocation,
        role = s"left operand of '$operation'"
      )
      val rightValue = resolveTracked(
        boundary,
        right,
        rightReference,
        rightLiteral,
        sourceLocation,
        role = s"right operand of '$operation'"
      )
      if (
        leftValue.expression == ExternalNativeIntRelativeExpression.Literal(BigInt(left)) &&
        rightValue.expression == ExternalNativeIntRelativeExpression.Literal(BigInt(right))
      ) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-OPERAND-UNPROVEN",
          s"native Int operation '$operation' has no proven symbolic operand",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
      val expression = ExternalNativeIntRelativeExpression.binary(
        operation,
        leftValue.expression,
        rightValue.expression
      )
      val tracked = ExternalNativeIntShadowTrackedValue(result, expression, sourceLocation)
      validateWitness(boundary, tracked, sourceLocation, s"native Int operation '$operation'")
      validateReference(resultReference, sourceLocation, "binary result")
      retainTracked(boundary, resultReference, tracked)
      retainSlot(
        boundary,
        ExternalNativeIntShadowKind.LocalValue,
        name,
        result,
        expression,
        sourceLocation
      )
      result
    }
  }

  /** Compiler hook for negation and address/log2 helpers. */
  def unaryTracked(
      operation: String,
      value: Int,
      valueReference: String,
      resultReference: String,
      name: String,
      sourceLocation: String
  ): Int = {
    val result = nativeUnary(operation, value, sourceLocation)
    withBoundaryOrValue(result, false, name, sourceLocation) { boundary =>
      val source = resolveTracked(
        boundary,
        value,
        valueReference,
        literal = false,
        sourceLocation,
        role = s"operand of '$operation'"
      )
      val expression = ExternalNativeIntRelativeExpression.unary(operation, source.expression)
      val tracked = ExternalNativeIntShadowTrackedValue(result, expression, sourceLocation)
      validateWitness(boundary, tracked, sourceLocation, s"native Int helper '$operation'")
      validateReference(resultReference, sourceLocation, "unary result")
      retainTracked(boundary, resultReference, tracked)
      retainSlot(
        boundary,
        ExternalNativeIntShadowKind.LocalValue,
        name,
        result,
        expression,
        sourceLocation
      )
      result
    }
  }

  /** Compiler hook for native Int comparisons. */
  def comparisonTracked(
      operation: String,
      left: Int,
      leftReference: String,
      leftLiteral: Boolean,
      right: Int,
      rightReference: String,
      rightLiteral: Boolean,
      resultReference: String,
      name: String,
      sourceLocation: String
  ): Boolean = {
    val result = nativeComparison(operation, left, right)
    withBoundaryOrBoolean(result) { boundary =>
      val leftValue = resolveTracked(
        boundary,
        left,
        leftReference,
        leftLiteral,
        sourceLocation,
        role = s"left operand of '$operation'"
      )
      val rightValue = resolveTracked(
        boundary,
        right,
        rightReference,
        rightLiteral,
        sourceLocation,
        role = s"right operand of '$operation'"
      )
      val predicate = Comparison(operation, leftValue.expression, rightValue.expression)
      val actual = lowerPredicate(boundary, predicate, sourceLocation)
      if (actual.default != result) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-SHADOW-DEFAULT-MISMATCH",
          s"native predicate '$operation' witness $result disagrees with symbolic default ${actual.default}",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
      validateReference(resultReference, sourceLocation, "predicate result")
      retainPredicate(
        boundary,
        resultReference,
        ExternalNativeIntShadowPendingPredicate(
          ExternalNativeIntShadowPredicateToken(name, operation, sourceLocation),
          result,
          predicate
        )
      )
      result
    }
  }

  /** Compiler hook for the native SpinalHDL isPow2 helper. */
  def powerOfTwoTracked(
      value: Int,
      valueReference: String,
      resultReference: String,
      name: String,
      sourceLocation: String
  ): Boolean = {
    val result = value > 0 && Integer.bitCount(value) == 1
    withBoundaryOrBoolean(result) { boundary =>
      val source = resolveTracked(
        boundary,
        value,
        valueReference,
        literal = false,
        sourceLocation,
        role = "isPow2 operand"
      )
      val predicate = PowerOfTwo(source.expression)
      val actual = lowerPredicate(boundary, predicate, sourceLocation)
      if (actual.default != result) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-SHADOW-DEFAULT-MISMATCH",
          s"native isPow2 witness $result disagrees with symbolic default ${actual.default}",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
      validateReference(resultReference, sourceLocation, "isPow2 result")
      retainPredicate(
        boundary,
        resultReference,
        ExternalNativeIntShadowPendingPredicate(
          ExternalNativeIntShadowPredicateToken(name, "isPow2", sourceLocation),
          result,
          predicate
        )
      )
      result
    }
  }

  /** Compiler hook for `&&` and `||` over one or more proven predicates. */
  def booleanBinaryTracked(
      operation: String,
      left: Boolean,
      leftReference: String,
      leftConcrete: Boolean,
      right: Boolean,
      rightReference: String,
      rightConcrete: Boolean,
      resultReference: String,
      name: String,
      sourceLocation: String
  ): Boolean = {
    val result = operation match {
      case "&&" => left && right
      case "||" => left || right
      case other =>
        throw new IllegalArgumentException(
          s"unsupported native Boolean operation '$other'"
        )
    }
    withBoundaryOrBoolean(result) { boundary =>
      val leftPredicate = resolvePredicate(
        boundary,
        left,
        leftReference,
        leftConcrete,
        sourceLocation,
        s"left operand of '$operation'"
      )
      val rightPredicate = resolvePredicate(
        boundary,
        right,
        rightReference,
        rightConcrete,
        sourceLocation,
        s"right operand of '$operation'"
      )
      if (
        leftPredicate.isInstanceOf[Constant] &&
        rightPredicate.isInstanceOf[Constant]
      ) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-PREDICATE-OPERAND-UNPROVEN",
          s"native Boolean operation '$operation' has no proven symbolic operand",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
      val predicate = ExternalNativeIntRelativePredicate.binary(
        operation,
        leftPredicate,
        rightPredicate
      )
      val actual = lowerPredicate(boundary, predicate, sourceLocation)
      if (actual.default != result) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-SHADOW-DEFAULT-MISMATCH",
          s"native Boolean operation '$operation' witness $result disagrees with symbolic default ${actual.default}",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
      validateReference(resultReference, sourceLocation, "Boolean result")
      retainPredicate(
        boundary,
        resultReference,
        ExternalNativeIntShadowPendingPredicate(
          ExternalNativeIntShadowPredicateToken(name, operation, sourceLocation),
          result,
          predicate
        )
      )
      result
    }
  }

  /** Compiler hook for Boolean negation of a proven predicate. */
  def booleanNotTracked(
      value: Boolean,
      valueReference: String,
      valueConcrete: Boolean,
      resultReference: String,
      name: String,
      sourceLocation: String
  ): Boolean = {
    val result = !value
    withBoundaryOrBoolean(result) { boundary =>
      val operand = resolvePredicate(
        boundary,
        value,
        valueReference,
        valueConcrete,
        sourceLocation,
        "operand of Boolean negation"
      )
      if (operand.isInstanceOf[Constant]) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-PREDICATE-OPERAND-UNPROVEN",
          "native Boolean negation has no proven symbolic operand",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
      val predicate = ExternalNativeIntRelativePredicate.not(operand)
      val actual = lowerPredicate(boundary, predicate, sourceLocation)
      if (actual.default != result) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-SHADOW-DEFAULT-MISMATCH",
          s"native Boolean negation witness $result disagrees with symbolic default ${actual.default}",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
      validateReference(resultReference, sourceLocation, "Boolean negation result")
      retainPredicate(
        boundary,
        resultReference,
        ExternalNativeIntShadowPendingPredicate(
          ExternalNativeIntShadowPredicateToken(name, "!", sourceLocation),
          result,
          predicate
        )
      )
      result
    }
  }

  /** Compiler hook for the native `Boolean.toInt` helper used in geometry. */
  def booleanToIntTracked(
      value: Boolean,
      valueReference: String,
      valueConcrete: Boolean,
      resultReference: String,
      name: String,
      sourceLocation: String
  ): Int = {
    val result = if (value) 1 else 0
    withBoundaryOrValue(result, false, name, sourceLocation) { boundary =>
      val predicate = resolvePredicate(
        boundary,
        value,
        valueReference,
        valueConcrete,
        sourceLocation,
        "Boolean.toInt operand"
      )
      if (predicate.isInstanceOf[Constant]) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-PREDICATE-OPERAND-UNPROVEN",
          "native Boolean.toInt has no proven symbolic operand",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
      val expression = ExternalNativeIntRelativeExpression.booleanToInt(predicate)
      val tracked = ExternalNativeIntShadowTrackedValue(result, expression, sourceLocation)
      validateWitness(boundary, tracked, sourceLocation, "Boolean.toInt")
      validateReference(resultReference, sourceLocation, "Boolean.toInt result")
      retainTracked(boundary, resultReference, tracked)
      retainSlot(
        boundary,
        ExternalNativeIntShadowKind.LocalValue,
        name,
        result,
        expression,
        sourceLocation
      )
      result
    }
  }

  /** Resolve one exact tracked integer in definition scope. Outside a live
    * formalization boundary this intentionally returns `None`, preserving
    * ordinary native-library behavior.
    */
  def definitionExpressionTracked(
      reference: String,
      witness: Int,
      sourceLocation: String,
      positiveWidth: Boolean = false
  ): Option[ElaborationIntegerExpression] = currentBoundary.map { boundary =>
    validateReference(reference, sourceLocation, "definition expression")
    val tracked = resolveTracked(
      boundary,
      witness,
      reference,
      literal = false,
      sourceLocation,
      role = "definition expression"
    )
    val relative =
      if (positiveWidth)
        ExternalNativeIntRelativeExpression.positiveWidth(tracked.expression)
      else tracked.expression
    val definition = lowerFinalExpression(
      relative,
      boundary.definitionExpression,
      sourceLocation
    )
    if (definition.default != BigInt(witness)) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-DEFAULT-MISMATCH",
        s"tracked definition expression witness $witness disagrees with symbolic default ${definition.default}",
        Option(sourceLocation).filter(_.nonEmpty).orElse(definition.sourceLocation)
      )
    }
    retainDefinitionExpressionEvidence(
      definition,
      boundary.structuralPredicateRoot,
      relative
    )
    definition
  }

  /** Resolve one proven native Boolean predicate in canonical definition scope.
    * Increment 51 consumes this only while the exact formalization boundary is
    * active, before the native child constructor returns.
    */
  def definitionPredicateTracked(
      reference: String,
      witness: Boolean,
      sourceLocation: String
  ): ElaborationBooleanExpression =
    definitionPredicateEvidenceTracked(reference, witness, sourceLocation)._1

  /** Return the lowered predicate together with optional exact bounded-domain
    * evidence.  Structural replay consumes the evidence only to prove that two
    * independently captured alternatives cannot be active together.
    */
  private[core] def definitionPredicateEvidenceTracked(
      reference: String,
      witness: Boolean,
      sourceLocation: String
  ): (
      ElaborationBooleanExpression,
      Option[ParameterizedStructure.StructuralPredicateDomain]
  ) = currentBoundary match {
    case None =>
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-BOUNDARY-MISSING",
        s"native symbolic conditional predicate '$reference' was evaluated outside an active formalization boundary",
        Option(sourceLocation).filter(_.nonEmpty)
      )
    case Some(boundary) =>
      validateReference(reference, sourceLocation, "symbolic conditional predicate")
      boundary.predicates.get(reference) match {
        case Some(pending) if pending.witness == witness =>
          val definition = lowerFinalPredicate(
            pending.predicate,
            boundary.definitionExpression,
            sourceLocation
          )
          if (definition.default != witness) {
            fail(
              "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-DEFAULT-MISMATCH",
              s"native symbolic conditional witness $witness disagrees with definition predicate default ${definition.default}",
              Option(sourceLocation).filter(_.nonEmpty).orElse(definition.sourceLocation)
            )
          }
          definition -> structuralPredicateDomain(boundary, pending.predicate)
        case Some(pending) =>
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-WITNESS-MISMATCH",
            s"native symbolic conditional witness $witness disagrees with retained predicate witness ${pending.witness}",
            Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
          )
        case None =>
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-REFERENCE-UNRESOLVED",
            s"native symbolic conditional uses unbound or foreign predicate reference '$reference'",
            Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
          )
      }
  }

  private def structuralPredicateDomain(
      boundary: ActiveBoundary,
      predicate: ExternalNativeIntRelativePredicate
  ): Option[ParameterizedStructure.StructuralPredicateDomain] = {
    val root = boundary.definitionExpression
    val size = root.maximum - root.minimum + 1
    if (size < 1 || size > MaximumStructuralPredicateDomainSize) return None

    val universe = Set.newBuilder[BigInt]
    val whenTrue = Set.newBuilder[BigInt]
    var value = root.minimum
    var complete = true
    while (value <= root.maximum && complete) {
      universe += value
      ExternalNativeIntRelativePredicate.evaluate(predicate, value) match {
        case Some(true)  => whenTrue += value
        case Some(false) =>
        case None        => complete = false
      }
      value += 1
    }
    if (!complete) None
    else
      Some(
        ParameterizedStructure.StructuralPredicateDomain(
          root = boundary.structuralPredicateRoot,
          universe = universe.result(),
          whenTrue = whenTrue.result()
        )
      )
  }

  /** Fail-closed hook for compiler-proven unsupported escape or mutation. */
  def rejectTracked(
      reference: String,
      code: String,
      detail: String,
      sourceLocation: String
  ): Unit = currentBoundary.foreach { boundary =>
    if (
      boundary.trackedValues.contains(reference) ||
      boundary.predicates.contains(reference)
    ) {
      fail(code, detail, Option(sourceLocation).filter(_.nonEmpty))
    } else {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-REFERENCE-STALE",
        s"unsupported-use diagnostic referenced stale or foreign provenance '$reference'",
        Option(sourceLocation).filter(_.nonEmpty)
      )
    }
  }

  /** Attach a completed capture to one exact native child Component. */
  def attachComponent[C <: Component](
      component: C,
      binding: ExternalFormalParameterBinding,
      capture: ExternalNativeIntShadowCapture[C]
  ): C = synchronized {
    if (component == null)
      throw new IllegalArgumentException("native Int shadow component must not be null")
    if (binding == null)
      throw new IllegalArgumentException("native Int shadow binding must not be null")
    if (capture == null)
      throw new IllegalArgumentException("native Int shadow capture must not be null")
    if (!(capture.result.asInstanceOf[AnyRef] eq component)) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-RESULT-MISMATCH",
        s"shadow capture '${capture.token.role}' was attached to a different Component identity",
        sourceOf(capture.token)
      )
    }
    if (component.getClass.getName != binding.ownerClassName) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-OWNER-MISMATCH",
        s"shadow capture '${capture.token.role}' belongs to '${component.getClass.getName}' but formal '${binding.formal.name}' belongs to '${binding.ownerClassName}'",
        binding.sourceLocation.orElse(sourceOf(capture.token))
      )
    }
    if (!equivalentExpression(capture.expression, binding.actual)) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-ACTUAL-MISMATCH",
        s"shadow capture '${capture.token.role}' retained '${capture.expression.verilog}' but the component actual is '${binding.actual.verilog}'",
        binding.sourceLocation.orElse(sourceOf(capture.token))
      )
    }

    val reconstructedDefinition = formalExpression(binding.formal)
    if (
      !ExternalFormalParameterRegistry.equivalentCanonicalFormalSchema(
        capture.definitionExpression,
        reconstructedDefinition
      )
    ) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-DEFINITION-MISMATCH",
        s"shadow capture '${capture.token.role}' retained definition root '${capture.definitionExpression.verilog}' but canonical formal is '${reconstructedDefinition.verilog}'",
        binding.sourceLocation.orElse(sourceOf(capture.token))
      )
    }
    // The reconstructed expression proves only the formal schema. Preserve the
    // capture's exact declaration root in every lowered definition-side slot.
    val definition = capture.definitionExpression
    val slots = finalizeSlots(capture, definition, binding.actual)
    val predicates = finalizePredicates(capture, definition, binding.actual)
    val incoming = ExternalNativeIntComponentShadowRecord(
      boundaryToken = capture.token,
      parentBoundaryToken = capture.parentToken,
      ownerClassName = component.getClass.getName,
      binding = binding,
      slots = slots,
      predicates = predicates
    )

    reapComponents()
    val lookup = new ExternalNativeIntShadowComponentIdentityRef(component, null)
    val existing = components.getOrElse(lookup, Vector.empty)
    existing.find(_.binding.formal.name == binding.formal.name) match {
      case Some(record) if !equivalentComponentRecord(record, incoming) =>
        fail(
          "MORPH-FRONTEND-NATIVE-INT-SHADOW-COMPONENT-CONFLICT",
          s"one exact Component received conflicting shadow provenance for formal '${binding.formal.name}'",
          binding.sourceLocation.orElse(sourceOf(capture.token))
        )
      case Some(_) =>
      case None =>
        components.update(
          new ExternalNativeIntShadowComponentIdentityRef(component, componentQueue),
          existing :+ incoming
        )
    }
    component
  }

  /** Attach a completed capture to one exact native Data region. */
  def attachRegion[T <: Data](
      owner: Component,
      data: T,
      formalBinding: Option[ExternalFormalParameterBinding],
      capture: ExternalNativeIntShadowCapture[T]
  ): T = synchronized {
    if (owner == null)
      throw new IllegalArgumentException("native Int shadow region owner must not be null")
    if (data == null)
      throw new IllegalArgumentException("native Int shadow Data region must not be null")
    if (capture == null)
      throw new IllegalArgumentException("native Int shadow capture must not be null")
    if (!(capture.result.asInstanceOf[AnyRef] eq data)) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-RESULT-MISMATCH",
        s"shadow capture '${capture.token.role}' was attached to a different Data identity",
        sourceOf(capture.token)
      )
    }

    val reconstructedDefinition = formalBinding.map(binding => formalExpression(binding.formal))
    if (
      !reconstructedDefinition.forall(definition =>
        ExternalFormalParameterRegistry.equivalentCanonicalFormalSchema(
          capture.definitionExpression,
          definition
        )
      )
    ) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-DEFINITION-MISMATCH",
        s"shadow capture '${capture.token.role}' retained definition root '${capture.definitionExpression.verilog}' but attached region definition is '${reconstructedDefinition
            .map(_.verilog)
            .getOrElse(capture.definitionExpression.verilog)}'",
        formalBinding.flatMap(_.sourceLocation).orElse(sourceOf(capture.token))
      )
    }
    // Schema reconstruction never replaces the exact capture provenance.
    val definition = capture.definitionExpression
    val actual = formalBinding.map(_.actual).getOrElse(capture.expression)
    val incoming = ExternalNativeIntRegionShadowRecord(
      boundaryToken = capture.token,
      parentBoundaryToken = capture.parentToken,
      ownerClassName = owner.getClass.getName,
      formalBinding = formalBinding,
      slots = finalizeSlots(capture, definition, actual),
      predicates = finalizePredicates(capture, definition, actual)
    )

    reapRegions()
    val lookup = new ExternalNativeIntShadowRegionIdentityRef(data, null)
    regions.get(lookup) match {
      case Some(record) if !equivalentRegionRecord(record, incoming) =>
        fail(
          "MORPH-FRONTEND-NATIVE-INT-SHADOW-REGION-CONFLICT",
          s"one exact Data region received conflicting shadow provenance for '${capture.token.role}'",
          sourceOf(capture.token)
        )
      case Some(_) =>
      case None =>
        regions.update(
          new ExternalNativeIntShadowRegionIdentityRef(data, regionQueue),
          incoming
        )
    }
    data
  }

  def componentRecordsOf(
      component: Component
  ): Vector[ExternalNativeIntComponentShadowRecord] = synchronized {
    if (component == null) Vector.empty
    else {
      reapComponents()
      components
        .getOrElse(
          new ExternalNativeIntShadowComponentIdentityRef(component, null),
          Vector.empty
        )
        .sortBy(record => (record.binding.formal.name, record.binding.declarationKey))
    }
  }

  def regionOf(data: Data): Option[ExternalNativeIntRegionShadowRecord] = synchronized {
    if (data == null) None
    else {
      reapRegions()
      regions.get(new ExternalNativeIntShadowRegionIdentityRef(data, null))
    }
  }

  def liveRecordCounts: (Int, Int) = synchronized {
    reapComponents()
    reapRegions()
    components.size -> regions.size
  }

  /** True only while one exact native formalization constructor is executing. */
  def boundaryActive: Boolean = currentBoundary.nonEmpty

  /** Domain-constant branch folding is local to an ordinary native
    * width-function call. Constructor boundaries such as StreamFifo retain
    * their established structural capture even when a narrowed caller domain
    * makes one predicate constant.
    */
  private[core] def nativeWidthFunctionBoundaryActive: Boolean =
    currentBoundary.exists(_.token.role == "nativeWidthFunction")

  private def currentBoundary: Option[ActiveBoundary] =
    Option(active.get()).getOrElse(Nil).headOption

  private def withBoundaryOrValue(
      value: Int,
      requireBoundary: Boolean,
      name: String,
      sourceLocation: String
  )(body: ActiveBoundary => Int): Int = currentBoundary match {
    case Some(boundary) => body(boundary)
    case None if requireBoundary =>
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-BOUNDARY-MISSING",
        s"selected native Int '$name' requires an active formalComponent or formalRegion boundary",
        Option(sourceLocation).filter(_.nonEmpty)
      )
    case None => value
  }

  private def withBoundaryOrBoolean(
      value: Boolean
  )(body: ActiveBoundary => Boolean): Boolean = currentBoundary match {
    case Some(boundary) => body(boundary)
    case None           => value
  }

  private def recordDirect(
      value: Int,
      name: String,
      kind: ExternalNativeIntShadowKind,
      sourceLocation: String,
      requireBoundary: Boolean
  ): Int = withBoundaryOrValue(value, requireBoundary, name, sourceLocation) { boundary =>
    validateName(name, sourceLocation, kind.label)
    requireRootWitness(boundary, value, name, sourceLocation)
    retainSlot(boundary, kind, name, value, Root, sourceLocation)
    value
  }

  private def requireRootWitness(
      boundary: ActiveBoundary,
      value: Int,
      name: String,
      sourceLocation: String
  ): Unit = {
    val expected = boundary.expression.default.toInt
    if (value != expected) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-EXPRESSION-DEFERRED",
        s"selected native Int '$name' has witness $value, but boundary '${boundary.token.role}' has witness $expected; use a compiler-proven Increment 50 expression instead of guessing provenance from the result",
        Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
      )
    }
  }

  private def retainSlot(
      boundary: ActiveBoundary,
      kind: ExternalNativeIntShadowKind,
      name: String,
      witness: Int,
      expression: ExternalNativeIntRelativeExpression,
      sourceLocation: String
  ): Unit = {
    validateName(name, sourceLocation, kind.label)
    val key = kind -> name
    val incoming = ExternalNativeIntShadowPendingSlot(
      ExternalNativeIntShadowSlotToken(name, kind, sourceLocation),
      witness,
      expression
    )
    boundary.slots.get(key) match {
      case Some(existing)
          if kind == ExternalNativeIntShadowKind.ConstructorArgument &&
            existing.witness == incoming.witness &&
            existing.expression == incoming.expression &&
            existing.token.sourceLocation == boundary.token.callSite =>
        // `captureWithDefinition` seeds the public formal at the external call
        // site before the untouched constructor executes. When parser-phase
        // instrumentation later reaches the exact native constructor argument,
        // replace only that seed with the authoritative definition-source
        // identity. Other duplicate slot identities still fail closed.
        boundary.slots.update(key, incoming)
      case Some(existing) if existing != incoming =>
        fail(
          "MORPH-FRONTEND-NATIVE-INT-SHADOW-SLOT-CONFLICT",
          s"native Int shadow slot '${kind.label}:$name' was selected with conflicting source identity or expression",
          Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
        )
      case Some(_) =>
      case None    => boundary.slots.update(key, incoming)
    }
  }

  private def retainTracked(
      boundary: ActiveBoundary,
      reference: String,
      incoming: ExternalNativeIntShadowTrackedValue
  ): Unit = boundary.trackedValues.get(reference) match {
    case Some(existing) if existing != incoming =>
      fail(
        "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-ALIAS-CONFLICT",
        s"one compiler provenance reference '$reference' mapped to conflicting native Int expressions",
        Option(incoming.sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
      )
    case Some(_) =>
    case None    => boundary.trackedValues.update(reference, incoming)
  }

  private def retainPredicate(
      boundary: ActiveBoundary,
      reference: String,
      incoming: ExternalNativeIntShadowPendingPredicate
  ): Unit = boundary.predicates.get(reference) match {
    case Some(existing) if existing != incoming =>
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-PREDICATE-CONFLICT",
        s"one compiler predicate reference '$reference' mapped to conflicting expressions",
        Option(incoming.token.sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
      )
    case Some(_) =>
    case None    => boundary.predicates.update(reference, incoming)
  }

  private def resolveTracked(
      boundary: ActiveBoundary,
      witness: Int,
      reference: String,
      literal: Boolean,
      sourceLocation: String,
      role: String
  ): ExternalNativeIntShadowTrackedValue = {
    if (reference != null && reference.nonEmpty) {
      boundary.trackedValues.get(reference) match {
        case Some(value) if value.witness == witness => value
        case Some(value) =>
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-WITNESS-MISMATCH",
            s"$role witness $witness disagrees with tracked witness ${value.witness}",
            Option(sourceLocation).filter(_.nonEmpty)
          )
        case None =>
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-REFERENCE-UNRESOLVED",
            s"$role uses unbound or foreign provenance reference '$reference'",
            Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
          )
      }
    } else if (literal) {
      ExternalNativeIntShadowTrackedValue(
        witness,
        Literal(BigInt(witness)),
        sourceLocation
      )
    } else {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-OPERAND-UNPROVEN",
        s"$role is neither a compiler-proven shadow value nor an integer literal",
        Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
      )
    }
  }

  private def resolvePredicate(
      boundary: ActiveBoundary,
      witness: Boolean,
      reference: String,
      concrete: Boolean,
      sourceLocation: String,
      role: String
  ): ExternalNativeIntRelativePredicate = {
    if (reference != null && reference.nonEmpty) {
      boundary.predicates.get(reference) match {
        case Some(value) if value.witness == witness => value.predicate
        case Some(value) =>
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-WITNESS-MISMATCH",
            s"$role witness $witness disagrees with tracked witness ${value.witness}",
            Option(sourceLocation).filter(_.nonEmpty)
          )
        case None =>
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-REFERENCE-UNRESOLVED",
            s"$role uses unbound or foreign predicate reference '$reference'",
            Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
          )
      }
    } else if (concrete) Constant(witness)
    else {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-PREDICATE-OPERAND-UNPROVEN",
        s"$role is neither a compiler-proven predicate nor an approved concrete Boolean",
        Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
      )
    }
  }

  private def validateWitness(
      boundary: ActiveBoundary,
      tracked: ExternalNativeIntShadowTrackedValue,
      sourceLocation: String,
      role: String
  ): Unit = {
    val actual = lowerExpression(boundary, tracked.expression, sourceLocation)
    if (actual.default != BigInt(tracked.witness)) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-DEFAULT-MISMATCH",
        s"$role witness ${tracked.witness} disagrees with symbolic default ${actual.default}",
        Option(sourceLocation).filter(_.nonEmpty).orElse(actual.sourceLocation)
      )
    }
  }

  private def lowerExpression(
      boundary: ActiveBoundary,
      expression: ExternalNativeIntRelativeExpression,
      sourceLocation: String
  ): ElaborationIntegerExpression =
    ExternalNativeIntRelativeExpression.lower(expression, boundary.expression) match {
      case Right(facts) => facts.expression(sourceLocation)
      case Left(problem) =>
        fail(problem.code, problem.detail, Option(sourceLocation).filter(_.nonEmpty))
    }

  private def lowerPredicate(
      boundary: ActiveBoundary,
      predicate: ExternalNativeIntRelativePredicate,
      sourceLocation: String
  ): ElaborationBooleanExpression =
    ExternalNativeIntRelativePredicate.lower(
      predicate,
      boundary.expression,
      sourceLocation
    ) match {
      case Right(value) => value
      case Left(problem) =>
        fail(problem.code, problem.detail, Option(sourceLocation).filter(_.nonEmpty))
    }

  private def finalizeSlots(
      capture: ExternalNativeIntShadowCapture[_],
      definition: ElaborationIntegerExpression,
      actual: ElaborationIntegerExpression
  ): Vector[ExternalNativeIntShadowSlot] =
    capture.pendingSlots
      .map { pending =>
        val definitionExpression = lowerFinalExpression(
          pending.expression,
          definition,
          pending.token.sourceLocation
        )
        val actualExpression = lowerFinalExpression(
          pending.expression,
          actual,
          pending.token.sourceLocation
        )
        if (
          BigInt(pending.witness) != definitionExpression.default ||
          BigInt(pending.witness) != actualExpression.default
        ) {
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-WITNESS-MISMATCH",
            s"shadow slot '${pending.token.name}' witness ${pending.witness} disagrees with definition default ${definitionExpression.default} or actual default ${actualExpression.default}",
            Option(pending.token.sourceLocation).filter(_.nonEmpty).orElse(sourceOf(capture.token))
          )
        }
        ExternalNativeIntShadowSlot(
          pending.token,
          pending.witness,
          definitionExpression,
          actualExpression
        )
      }
      .sortBy(slot => (slot.token.kind.label, slot.token.name, slot.token.sourceLocation))

  private def finalizePredicates(
      capture: ExternalNativeIntShadowCapture[_],
      definition: ElaborationIntegerExpression,
      actual: ElaborationIntegerExpression
  ): Vector[ExternalNativeIntShadowPredicate] =
    capture.pendingPredicates
      .map { pending =>
        val definitionExpression = lowerFinalPredicate(
          pending.predicate,
          definition,
          pending.token.sourceLocation
        )
        val actualExpression = lowerFinalPredicate(
          pending.predicate,
          actual,
          pending.token.sourceLocation
        )
        if (
          pending.witness != definitionExpression.default ||
          pending.witness != actualExpression.default
        ) {
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-DEFAULT-MISMATCH",
            s"shadow predicate '${pending.token.name}' witness ${pending.witness} disagrees with definition or actual default",
            Option(pending.token.sourceLocation).filter(_.nonEmpty).orElse(sourceOf(capture.token))
          )
        }
        ExternalNativeIntShadowPredicate(
          pending.token,
          pending.witness,
          definitionExpression,
          actualExpression
        )
      }
      .sortBy(predicate => (predicate.token.name, predicate.token.operation, predicate.token.sourceLocation))

  private def lowerFinalExpression(
      expression: ExternalNativeIntRelativeExpression,
      root: ElaborationIntegerExpression,
      sourceLocation: String
  ): ElaborationIntegerExpression =
    ExternalNativeIntRelativeExpression.lower(expression, root) match {
      case Right(facts) => facts.expression(sourceLocation)
      case Left(problem) =>
        fail(problem.code, problem.detail, Option(sourceLocation).filter(_.nonEmpty))
    }

  private def lowerFinalPredicate(
      predicate: ExternalNativeIntRelativePredicate,
      root: ElaborationIntegerExpression,
      sourceLocation: String
  ): ElaborationBooleanExpression =
    ExternalNativeIntRelativePredicate.lower(predicate, root, sourceLocation) match {
      case Right(value) => value
      case Left(problem) =>
        fail(problem.code, problem.detail, Option(sourceLocation).filter(_.nonEmpty))
    }

  private def nativeBinary(
      operation: String,
      left: Int,
      right: Int,
      sourceLocation: String
  ): Int = operation match {
    case "+"               => left + right
    case "-"               => left - right
    case "*"               => left * right
    case "/" if right != 0 => left / right
    case "%" if right != 0 => left % right
    case "/" | "%" =>
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-DIVISOR-WITNESS-ZERO",
        s"native Int '$operation' has a zero concrete divisor",
        Option(sourceLocation).filter(_.nonEmpty)
      )
    case "min" => math.min(left, right)
    case "max" => math.max(left, right)
    case other =>
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-OPERATION-UNSUPPORTED",
        s"unsupported native Int binary operation '$other'",
        Option(sourceLocation).filter(_.nonEmpty)
      )
  }

  private def nativeUnary(
      operation: String,
      value: Int,
      sourceLocation: String
  ): Int = operation match {
    case "negate"                           => -value
    case "ceilLog2" | "log2Up" if value > 0 => (BigInt(value) - 1).bitLength
    case "addressWidth" if value > 0        => math.max(1, (BigInt(value) - 1).bitLength)
    case "log2Down" if value > 0            => BigInt(value).bitLength - 1
    case "ceilLog2" | "log2Up" | "log2Down" | "addressWidth" =>
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-OPERAND-NONPOSITIVE",
        s"native Int helper '$operation' requires a positive witness, but found $value",
        Option(sourceLocation).filter(_.nonEmpty)
      )
    case other =>
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-OPERATION-UNSUPPORTED",
        s"unsupported native Int unary operation '$other'",
        Option(sourceLocation).filter(_.nonEmpty)
      )
  }

  private def nativeComparison(operation: String, left: Int, right: Int): Boolean =
    operation match {
      case "<"  => left < right
      case "<=" => left <= right
      case ">"  => left > right
      case ">=" => left >= right
      case "==" => left == right
      case "!=" => left != right
      case other =>
        throw new IllegalArgumentException(
          s"unsupported native Int comparison '$other'"
        )
    }

  private def validateBoundaryExpression(
      expression: ElaborationIntegerExpression,
      token: ExternalNativeIntFormalizationToken
  ): Unit = {
    if (expression == null)
      throw new IllegalArgumentException("native Int shadow expression must not be null")
    if (token == null)
      throw new IllegalArgumentException("native Int shadow token must not be null")
    if (
      expression.minimum < 1 || expression.maximum < expression.minimum ||
      expression.maximum > BigInt(Int.MaxValue) ||
      expression.default < expression.minimum || expression.default > expression.maximum ||
      !expression.default.isValidInt
    ) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-DOMAIN-INVALID",
        s"shadow boundary '${token.role}' expression '${expression.verilog}' requires a finite positive Int-sized domain containing default ${expression.default}",
        sourceOf(token).orElse(expression.sourceLocation)
      )
    }
  }

  private def validateName(
      name: String,
      sourceLocation: String,
      role: String
  ): Unit = {
    if (name == null || name.trim.isEmpty) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-NAME-INVALID",
        s"$role shadow selection requires one non-empty deterministic name",
        Option(sourceLocation).filter(_.nonEmpty)
      )
    }
    if (sourceLocation == null || sourceLocation.isEmpty) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-SOURCE-MISSING",
        s"$role shadow selection '$name' requires one deterministic source location",
        None
      )
    }
  }

  private def validateReference(
      reference: String,
      sourceLocation: String,
      role: String
  ): Unit = {
    if (reference == null || reference.trim.isEmpty) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-REFERENCE-INVALID",
        s"$role requires one non-empty compiler provenance reference",
        Option(sourceLocation).filter(_.nonEmpty)
      )
    }
  }

  private def reapComponents(): Unit = {
    var reference = componentQueue
      .poll()
      .asInstanceOf[ExternalNativeIntShadowComponentIdentityRef]
    while (reference != null) {
      components.remove(reference)
      reference = componentQueue
        .poll()
        .asInstanceOf[ExternalNativeIntShadowComponentIdentityRef]
    }
  }

  private def reapRegions(): Unit = {
    var reference = regionQueue
      .poll()
      .asInstanceOf[ExternalNativeIntShadowRegionIdentityRef]
    while (reference != null) {
      regions.remove(reference)
      reference = regionQueue
        .poll()
        .asInstanceOf[ExternalNativeIntShadowRegionIdentityRef]
    }
  }

  private def equivalentComponentRecord(
      left: ExternalNativeIntComponentShadowRecord,
      right: ExternalNativeIntComponentShadowRecord
  ): Boolean =
    left.boundaryToken == right.boundaryToken &&
      left.parentBoundaryToken == right.parentBoundaryToken &&
      left.ownerClassName == right.ownerClassName &&
      ExternalFormalParameterRegistry.equivalentBinding(left.binding, right.binding) &&
      equivalentSlots(left.slots, right.slots) &&
      equivalentPredicates(left.predicates, right.predicates)

  private def equivalentRegionRecord(
      left: ExternalNativeIntRegionShadowRecord,
      right: ExternalNativeIntRegionShadowRecord
  ): Boolean =
    left.boundaryToken == right.boundaryToken &&
      left.parentBoundaryToken == right.parentBoundaryToken &&
      left.ownerClassName == right.ownerClassName &&
      ((left.formalBinding, right.formalBinding) match {
        case (Some(x), Some(y)) => ExternalFormalParameterRegistry.equivalentBinding(x, y)
        case (None, None)       => true
        case _                  => false
      }) &&
      equivalentSlots(left.slots, right.slots) &&
      equivalentPredicates(left.predicates, right.predicates)

  private def equivalentSlots(
      left: Vector[ExternalNativeIntShadowSlot],
      right: Vector[ExternalNativeIntShadowSlot]
  ): Boolean =
    left.size == right.size && left.zip(right).forall { case (x, y) =>
      x.token == y.token && x.witness == y.witness &&
      equivalentExpression(x.definitionExpression, y.definitionExpression) &&
      equivalentExpression(x.actualExpression, y.actualExpression)
    }

  private def equivalentPredicates(
      left: Vector[ExternalNativeIntShadowPredicate],
      right: Vector[ExternalNativeIntShadowPredicate]
  ): Boolean =
    left.size == right.size && left.zip(right).forall { case (x, y) =>
      x.token == y.token && x.witness == y.witness &&
      equivalentBooleanExpression(x.definitionExpression, y.definitionExpression) &&
      equivalentBooleanExpression(x.actualExpression, y.actualExpression)
    }

  private def equivalentExpression(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean =
    ExternalFormalParameterRegistry.equivalentExpression(left, right)

  private[core] def equivalentBooleanExpression(
      left: ElaborationBooleanExpression,
      right: ElaborationBooleanExpression
  ): Boolean = ElabInt.equivalentBooleanExpression(left, right)

  private def formalExpression(
      formal: ElaborationIntegerParameter
  ): ElaborationIntegerExpression =
    ElaborationIntegerExpression(
      verilog = formal.name,
      default = formal.default,
      minimum = formal.minimum,
      maximum = formal.maximum,
      parameters = Vector(formal),
      sourceLocation = None
    )

  private def sourceOf(
      token: ExternalNativeIntFormalizationToken
  ): Option[String] = Option(token).map(_.callSite).filter(_.nonEmpty)

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
