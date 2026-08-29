#!/usr/bin/env python3
from pathlib import Path


def once(value: str, old: str, new: str, label: str) -> str:
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return value.replace(old, new, 1)

registry = Path(
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"
)
value = registry.read_text()

if "val selectedArgumentName: String" not in value:
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
      val selectedArgumentName: String
  ) {
'''
    value = once(value, old, new, "active boundary selected argument")

    old = '''    val trackedValues = mutable.LinkedHashMap.empty[
      String,
      ExternalNativeIntShadowTrackedValue
    ]
'''
    new = '''    val trackedValues = mutable.LinkedHashMap.empty[
      String,
      ExternalNativeIntShadowTrackedValue
    ]
    val concreteCandidateValues = mutable.LinkedHashMap.empty[String, Int]
'''
    value = once(value, old, new, "concrete candidate registry")

    old = '''      token = token,
      parentToken = previous.headOption.map(_.token)
    )
'''
    new = '''      token = token,
      parentToken = previous.headOption.map(_.token),
      selectedArgumentName = argumentName
    )
'''
    value = once(value, old, new, "active boundary construction")

if "def captureCandidateArgumentTracked(" not in value:
    marker = '''  /** Compiler hook: select and source-track one constructor argument. */
'''
    method = '''  /**
    * Compiler hook for every typed native Int constructor candidate. Only the
    * argument explicitly selected by the active formalization boundary becomes
    * the symbolic root. Other candidates are retained by exact compiler
    * reference as approved constants so nested ordinary native constructors do
    * not become accidental roots and cannot be confused by equal witnesses.
    */
  def captureCandidateArgumentTracked(
      value: Int,
      name: String,
      reference: String,
      sourceLocation: String
  ): Int = currentBoundary match {
    case None => value
    case Some(boundary) if boundary.selectedArgumentName == name =>
      captureArgumentTracked(value, name, reference, sourceLocation)
    case Some(boundary) =>
      validateReference(reference, sourceLocation, "candidate constructor argument")
      boundary.concreteCandidateValues.get(reference) match {
        case Some(existing) if existing != value =>
          fail(
            "MORPH-FRONTEND-NATIVE-INT-CANDIDATE-WITNESS-CONFLICT",
            s"candidate constructor reference '$reference' changed from $existing to $value",
            Option(sourceLocation).filter(_.nonEmpty)
          )
        case _ => boundary.concreteCandidateValues.update(reference, value)
      }
      value
  }

'''
    value = once(value, marker, method + marker, "candidate argument hook")

# Resolve unselected typed constructor candidates only by their exact compiler
# reference and witness. Unknown references remain hard errors.
old = '''        case None =>
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-REFERENCE-UNRESOLVED",
            s"$role uses unbound or foreign provenance reference '$reference'",
            Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
          )
      }
    } else if (literal) {
'''
new = '''        case None
            if boundary.concreteCandidateValues
              .get(reference)
              .contains(witness) =>
          ExternalNativeIntShadowTrackedValue(
            witness,
            Literal(BigInt(witness)),
            sourceLocation
          )
        case None =>
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-REFERENCE-UNRESOLVED",
            s"$role uses unbound or foreign provenance reference '$reference'",
            Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
          )
      }
    } else if (literal) {
'''
if old in value:
    value = value.replace(old, new, 1)
elif "boundary.concreteCandidateValues" not in value[value.find("private def resolveTracked"):]:
    raise SystemExit("candidate resolution marker not found")

# A binary operation over only approved concrete candidates is ordinary Scala
# elaboration, not an unsupported symbolic expression. Preserve the previous
# fail-closed behavior for references that were neither selected nor approved.
old = '''      if (
        leftValue.expression == ExternalNativeIntRelativeExpression.Literal(BigInt(left)) &&
        rightValue.expression == ExternalNativeIntRelativeExpression.Literal(BigInt(right))
      ) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-OPERAND-UNPROVEN",
          s"native Int operation '$operation' has no proven symbolic operand",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
'''
new = '''      if (
        leftValue.expression == ExternalNativeIntRelativeExpression.Literal(BigInt(left)) &&
        rightValue.expression == ExternalNativeIntRelativeExpression.Literal(BigInt(right)) &&
        !boundary.concreteCandidateValues.get(leftReference).contains(left) &&
        !boundary.concreteCandidateValues.get(rightReference).contains(right)
      ) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-OPERAND-UNPROVEN",
          s"native Int operation '$operation' has no proven symbolic operand",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
'''
if old in value:
    value = value.replace(old, new, 1)
elif "concreteCandidateValues.get(leftReference)" not in value:
    raise SystemExit("concrete candidate binary guard marker not found")

registry.write_text(value)

plugin = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
plugin_value = plugin.read_text()
plugin_value = plugin_value.replace(
    'selectedHelperMethod("captureArgumentTracked")',
    'selectedHelperMethod("captureCandidateArgumentTracked")'
)
plugin_value = plugin_value.replace(
    'helperMethod("captureArgumentTracked")',
    'helperMethod("captureCandidateArgumentTracked")'
)
if "captureCandidateArgumentTracked" not in plugin_value:
    raise SystemExit("compiler constructor argument hook was not generalized")
plugin.write_text(plugin_value)
