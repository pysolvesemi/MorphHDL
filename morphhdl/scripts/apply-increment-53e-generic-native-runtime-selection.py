#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphruntime/src/main/scala/spinal/core/"
    "ExternalNativeIntShadowRegistry.scala"
)
value = path.read_text()

# Record which constructor argument the explicit MorphHDL boundary selected.
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
if old in value:
    value = value.replace(old, new, 1)

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
if old in value:
    value = value.replace(old, new, 1)

# Every native constructor Int may be compiler-instrumented. Only the argument
# explicitly selected by formalComponent/formalRegion becomes the symbolic root.
old = '''  ): Int = withBoundaryOrValue(value, false, name, sourceLocation) { boundary =>
    validateReference(reference, sourceLocation, "constructor argument")
    requireRootWitness(boundary, value, name, sourceLocation)
'''
new = '''  ): Int = withBoundaryOrValue(value, false, name, sourceLocation) { boundary =>
    if (name != boundary.argumentName) value
    else {
      validateReference(reference, sourceLocation, "constructor argument")
      requireRootWitness(boundary, value, name, sourceLocation)
'''
if old in value:
    value = value.replace(old, new, 1)
    tail = '''    retainSlot(
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
'''
    replacement = '''    retainSlot(
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

  /** Compiler hook: select one local whose source reference is already proven. */
'''
    if tail not in value:
        raise SystemExit("captureArgumentTracked closing marker is ambiguous")
    value = value.replace(tail, replacement, 1)

# Generic instrumentation must be a no-op for an unrelated immutable alias.
old = '''  ): Int = withBoundaryOrValue(value, requireBoundary, name, sourceLocation) { boundary =>
    val source = resolveTracked(
      boundary,
      value,
      sourceReference,
'''
new = '''  ): Int = withBoundaryOrValue(value, requireBoundary, name, sourceLocation) { boundary =>
    if (!boundary.trackedValues.contains(sourceReference)) value
    else {
      val source = resolveTracked(
        boundary,
        value,
        sourceReference,
'''
# This shape occurs in captureLocalTracked only. Close it before aliasTracked.
if old in value:
    value = value.replace(old, new, 1)
    marker = '''    retainSlot(
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
'''
    replacement = '''      retainSlot(
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

  /** Compiler hook: retain one direct immutable alias. */
'''
    if marker not in value:
        raise SystemExit("captureLocalTracked closing marker is ambiguous")
    value = value.replace(marker, replacement, 1)

old = '''  ): Int = withBoundaryOrValue(value, false, name, sourceLocation) { boundary =>
    val source = resolveTracked(
      boundary,
      value,
      sourceReference,
'''
new = '''  ): Int = withBoundaryOrValue(value, false, name, sourceLocation) { boundary =>
    if (!boundary.trackedValues.contains(sourceReference)) value
    else {
      val source = resolveTracked(
        boundary,
        value,
        sourceReference,
'''
if old in value:
    value = value.replace(old, new, 1)
    marker = '''    retainSlot(
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
'''
    replacement = '''      retainSlot(
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

  /** Compiler hook for addition, subtraction, multiplication, division, remainder and min/max. */
'''
    if marker not in value:
        raise SystemExit("aliasTracked closing marker is ambiguous")
    value = value.replace(marker, replacement, 1)

# Arithmetic/comparison helpers return the native result when no operand belongs
# to the selected root. If exactly one operand is tracked, the existing
# fail-closed resolver still requires every other non-literal operand to be
# proven before combining it with the root.
def wrap_method_body(signature_tail: str, first_statement: str, closing_marker: str, label: str):
    global value
    old = signature_tail + first_statement
    if old not in value:
        return
    new = signature_tail + '''    val hasTrackedOperand =
      boundary.trackedValues.contains(leftReference) ||
        boundary.trackedValues.contains(rightReference)
    if (!hasTrackedOperand) result
    else {
''' + first_statement
    value = value.replace(old, new, 1)
    if closing_marker not in value:
        raise SystemExit(label + " closing marker is ambiguous")
    value = value.replace(closing_marker, closing_marker.replace("    result\n  }", "      result\n    }\n  }"), 1)

# Apply guarded returns with direct, method-specific anchors.
old = '''    withBoundaryOrValue(result, false, name, sourceLocation) { boundary =>
      val leftValue = resolveTracked(
'''
new = '''    withBoundaryOrValue(result, false, name, sourceLocation) { boundary =>
      val hasTrackedOperand =
        boundary.trackedValues.contains(leftReference) ||
          boundary.trackedValues.contains(rightReference)
      if (!hasTrackedOperand) result
      else {
        val leftValue = resolveTracked(
'''
# First occurrence is binaryTracked.
if old in value:
    value = value.replace(old, new, 1)
    marker = '''      retainSlot(
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
'''
    replacement = '''        retainSlot(
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

  /** Compiler hook for negation and address/log2 helpers. */
'''
    if marker not in value:
        raise SystemExit("binaryTracked closing marker is ambiguous")
    value = value.replace(marker, replacement, 1)

old = '''    withBoundaryOrValue(result, false, name, sourceLocation) { boundary =>
      val source = resolveTracked(
        boundary,
        value,
        valueReference,
'''
new = '''    withBoundaryOrValue(result, false, name, sourceLocation) { boundary =>
      if (!boundary.trackedValues.contains(valueReference)) result
      else {
        val source = resolveTracked(
          boundary,
          value,
          valueReference,
'''
if old in value:
    value = value.replace(old, new, 1)
    marker = '''      retainSlot(
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
'''
    replacement = '''        retainSlot(
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

  /** Compiler hook for native Int comparisons. */
'''
    if marker not in value:
        raise SystemExit("unaryTracked closing marker is ambiguous")
    value = value.replace(marker, replacement, 1)

old = '''    withBoundaryOrBoolean(result) { boundary =>
      val leftValue = resolveTracked(
'''
new = '''    withBoundaryOrBoolean(result) { boundary =>
      val hasTrackedOperand =
        boundary.trackedValues.contains(leftReference) ||
          boundary.trackedValues.contains(rightReference)
      if (!hasTrackedOperand) result
      else {
        val leftValue = resolveTracked(
'''
if old in value:
    value = value.replace(old, new, 1)
    marker = '''      retainPredicate(
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
'''
    replacement = '''        retainPredicate(
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

  /** Compiler hook for a native power-of-two helper. */
'''
    if marker not in value:
        raise SystemExit("comparisonTracked closing marker is ambiguous")
    value = value.replace(marker, replacement, 1)

old = '''    withBoundaryOrBoolean(result) { boundary =>
      val source = resolveTracked(
        boundary,
        value,
        valueReference,
'''
new = '''    withBoundaryOrBoolean(result) { boundary =>
      if (!boundary.trackedValues.contains(valueReference)) result
      else {
        val source = resolveTracked(
          boundary,
          value,
          valueReference,
'''
if old in value:
    value = value.replace(old, new, 1)
    marker = '''      retainPredicate(
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
'''
    replacement = '''        retainPredicate(
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

  /** Compiler hook for `&&` and `||` over one or more proven predicates. */
'''
    if marker not in value:
        raise SystemExit("powerOfTwoTracked closing marker is ambiguous")
    value = value.replace(marker, replacement, 1)

# Definition queries are optional for unrelated compiler-instrumented integers.
old = '''  ): Option[ElaborationIntegerExpression] = currentBoundary.map { boundary =>
    validateReference(reference, sourceLocation, "definition expression")
'''
new = '''  ): Option[ElaborationIntegerExpression] = currentBoundary.flatMap { boundary =>
    if (!boundary.trackedValues.contains(reference)) None
    else {
      validateReference(reference, sourceLocation, "definition expression")
'''
if old in value:
    value = value.replace(old, new, 1)
    marker = '''    retainDefinitionExpressionEvidence(
      definition,
      boundary.structuralPredicateRoot,
      relative
    )
    definition
  }

  /**
    * Resolve one compiler-proven descending Scala range while its native
'''
    replacement = '''      retainDefinitionExpressionEvidence(
        definition,
        boundary.structuralPredicateRoot,
        relative
      )
      Some(definition)
    }
  }

  /**
    * Resolve one compiler-proven descending Scala range while its native
'''
    if marker not in value:
        raise SystemExit("definitionExpressionTracked closing marker is ambiguous")
    value = value.replace(marker, replacement, 1)

# An unsupported-use callback for an unrelated native integer is a no-op. A
# selected/tracked value retains the original fail-closed diagnostic.
old = '''    } else {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SHADOW-REFERENCE-STALE",
        s"unsupported-use diagnostic referenced stale or foreign provenance '$reference'",
        Option(sourceLocation).filter(_.nonEmpty)
      )
    }
'''
new = '''    } else {
      ()
    }
'''
if old in value:
    value = value.replace(old, new, 1)

path.write_text(value)
