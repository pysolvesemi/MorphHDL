#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphruntime/src/main/scala/spinal/core/"
    "ExternalNativeIntShadowRegistry.scala"
)
value = path.read_text()


def replace_between(start_marker: str, end_marker: str, replacement: str, label: str) -> None:
    global value
    start = value.find(start_marker)
    if start < 0:
        raise SystemExit(label + " start marker is missing")
    end = value.find(end_marker, start)
    if end < 0:
        raise SystemExit(label + " end marker is missing")
    value = value[:start] + replacement + value[end:]


# Active boundaries retain the explicitly selected constructor argument name.
old = '''  private final class ActiveBoundary(
      val expression: ElaborationIntegerExpression,
      val definitionExpression: ElaborationIntegerExpression,
      val token: ExternalNativeIntFormalizationToken,
      val parentToken: Option[ExternalNativeIntFormalizationToken]
  ) {
'''
new = '''  private final class ActiveBoundary(
      val expression: ElaborationIntegerExpression,
      val definitionExpression: ElaborationIntegerExpression,
      val token: ExternalNativeIntFormalizationToken,
      val parentToken: Option[ExternalNativeIntFormalizationToken],
      val argumentName: String
  ) {
'''
value = value.replace(old, new)

old = '''    val boundary = new ActiveBoundary(
      expression = expression,
      definitionExpression = definitionExpression,
      token = token,
      parentToken = previous.headOption.map(_.token)
    )
'''
new = '''    val boundary = new ActiveBoundary(
      expression = expression,
      definitionExpression = definitionExpression,
      token = token,
      parentToken = previous.headOption.map(_.token),
      argumentName = argumentName
    )
'''
value = value.replace(old, new)

replace_between(
    "  def captureArgumentTracked(\n",
    "  /** Compiler hook: select one local whose source reference is already proven. */\n",
    '''  def captureArgumentTracked(
      value: Int,
      name: String,
      reference: String,
      sourceLocation: String
  ): Int = withBoundaryOrValue(value, false, name, sourceLocation) { boundary =>
    if (name != boundary.argumentName) value
    else {
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
  }

''',
    "captureArgumentTracked",
)

replace_between(
    "  def captureLocalTracked(\n",
    "  /** Compiler hook: retain one direct immutable alias. */\n",
    '''  def captureLocalTracked(
      value: Int,
      name: String,
      sourceReference: String,
      resultReference: String,
      sourceLocation: String,
      requireBoundary: Boolean
  ): Int = withBoundaryOrValue(value, requireBoundary, name, sourceLocation) { boundary =>
    if (!boundary.trackedValues.contains(sourceReference)) value
    else {
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
  }

''',
    "captureLocalTracked",
)

replace_between(
    "  def aliasTracked(\n",
    "  /** Compiler hook for addition, subtraction, multiplication, division, remainder and min/max. */\n",
    '''  def aliasTracked(
      value: Int,
      name: String,
      sourceReference: String,
      resultReference: String,
      sourceLocation: String
  ): Int = withBoundaryOrValue(value, false, name, sourceLocation) { boundary =>
    if (!boundary.trackedValues.contains(sourceReference)) value
    else {
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
  }

''',
    "aliasTracked",
)

replace_between(
    "  def binaryTracked(\n",
    "  /** Compiler hook for negation and logarithm helpers. */\n",
    '''  def binaryTracked(
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
      val hasTrackedOperand =
        boundary.trackedValues.contains(leftReference) ||
          boundary.trackedValues.contains(rightReference)
      if (!hasTrackedOperand) result
      else {
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
        val tracked = ExternalNativeIntShadowTrackedValue(
          result,
          expression,
          sourceLocation
        )
        validateWitness(boundary, tracked, sourceLocation, s"native Int '$operation'")
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
  }

''',
    "binaryTracked",
)

replace_between(
    "  def unaryTracked(\n",
    "  /** Compiler hook for native Int comparisons. */\n",
    '''  def unaryTracked(
      operation: String,
      value: Int,
      valueReference: String,
      resultReference: String,
      name: String,
      sourceLocation: String
  ): Int = {
    val result = nativeUnary(operation, value, sourceLocation)
    withBoundaryOrValue(result, false, name, sourceLocation) { boundary =>
      if (!boundary.trackedValues.contains(valueReference)) result
      else {
        val source = resolveTracked(
          boundary,
          value,
          valueReference,
          literal = false,
          sourceLocation,
          role = s"operand of '$operation'"
        )
        val expression = ExternalNativeIntRelativeExpression.unary(
          operation,
          source.expression
        )
        val tracked = ExternalNativeIntShadowTrackedValue(
          result,
          expression,
          sourceLocation
        )
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
  }

''',
    "unaryTracked",
)

replace_between(
    "  def comparisonTracked(\n",
    "  /** Compiler hook for the native SpinalHDL isPow2 helper. */\n",
    '''  def comparisonTracked(
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
      val hasTrackedOperand =
        boundary.trackedValues.contains(leftReference) ||
          boundary.trackedValues.contains(rightReference)
      if (!hasTrackedOperand) result
      else {
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
  }

''',
    "comparisonTracked",
)

replace_between(
    "  def powerOfTwoTracked(\n",
    "  /** Compiler hook for `&&` and `||` over one or more proven predicates. */\n",
    '''  def powerOfTwoTracked(
      value: Int,
      valueReference: String,
      resultReference: String,
      name: String,
      sourceLocation: String
  ): Boolean = {
    val result = value > 0 && Integer.bitCount(value) == 1
    withBoundaryOrBoolean(result) { boundary =>
      if (!boundary.trackedValues.contains(valueReference)) result
      else {
        val source = resolveTracked(
          boundary,
          value,
          valueReference,
          literal = false,
          sourceLocation,
          role = "power-of-two operand"
        )
        val predicate = PowerOfTwo(source.expression)
        val actual = lowerPredicate(boundary, predicate, sourceLocation)
        if (actual.default != result) {
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-DEFAULT-MISMATCH",
            s"native power-of-two witness $result disagrees with symbolic default ${actual.default}",
            Option(sourceLocation).filter(_.nonEmpty)
          )
        }
        validateReference(resultReference, sourceLocation, "power-of-two result")
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
  }

''',
    "powerOfTwoTracked",
)

replace_between(
    "  def booleanBinaryTracked(\n",
    "  /** Compiler hook for Boolean negation of a proven predicate. */\n",
    '''  def booleanBinaryTracked(
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
      case other => throw new IllegalArgumentException(
        s"unsupported native Boolean operation '$other'"
      )
    }
    withBoundaryOrBoolean(result) { boundary =>
      val hasTrackedOperand =
        boundary.predicates.contains(leftReference) ||
          boundary.predicates.contains(rightReference)
      if (!hasTrackedOperand) result
      else {
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
  }

''',
    "booleanBinaryTracked",
)

replace_between(
    "  def booleanNotTracked(\n",
    "  /** Compiler hook for the native `Boolean.toInt` helper used in geometry. */\n",
    '''  def booleanNotTracked(
      value: Boolean,
      valueReference: String,
      valueConcrete: Boolean,
      resultReference: String,
      name: String,
      sourceLocation: String
  ): Boolean = {
    val result = !value
    withBoundaryOrBoolean(result) { boundary =>
      if (!boundary.predicates.contains(valueReference)) result
      else {
        val operand = resolvePredicate(
          boundary,
          value,
          valueReference,
          valueConcrete,
          sourceLocation,
          "operand of Boolean negation"
        )
        val predicate = ExternalNativeIntRelativePredicate.not(operand)
        val actual = lowerPredicate(boundary, predicate, sourceLocation)
        if (actual.default != result) {
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-DEFAULT-MISMATCH",
            s"native Boolean negation witness $result disagrees with definition predicate default ${actual.default}",
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
  }

''',
    "booleanNotTracked",
)

replace_between(
    "  def booleanToIntTracked(\n",
    "  /**\n    * Resolve one exact tracked integer in definition scope.",
    '''  def booleanToIntTracked(
      value: Boolean,
      valueReference: String,
      valueConcrete: Boolean,
      resultReference: String,
      name: String,
      sourceLocation: String
  ): Int = {
    val result = if (value) 1 else 0
    withBoundaryOrValue(result, false, name, sourceLocation) { boundary =>
      if (!boundary.predicates.contains(valueReference)) result
      else {
        val predicate = resolvePredicate(
          boundary,
          value,
          valueReference,
          valueConcrete,
          sourceLocation,
          "Boolean.toInt operand"
        )
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
  }

  /**
    * Resolve one exact tracked integer in definition scope.''',
    "booleanToIntTracked",
)

replace_between(
    "  def definitionExpressionTracked(\n",
    "  /**\n    * Resolve one compiler-proven descending Scala range",
    '''  def definitionExpressionTracked(
      reference: String,
      witness: Int,
      sourceLocation: String,
      positiveWidth: Boolean = false
  ): Option[ElaborationIntegerExpression] = currentBoundary.flatMap { boundary =>
    if (!boundary.trackedValues.contains(reference)) None
    else {
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
      Some(definition)
    }
  }

  /**
    * Resolve one compiler-proven descending Scala range''',
    "definitionExpressionTracked",
)

# Unsupported operations on unrelated compiler-instrumented values are ignored;
# tracked values retain their original stable error.
old = '''    } else {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-REFERENCE-STALE",
        s"unsupported-use diagnostic referenced stale or foreign provenance '$reference'",
        Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
      )
    }
'''
value = value.replace(old, '''    } else {
      ()
    }
''')

path.write_text(value)
