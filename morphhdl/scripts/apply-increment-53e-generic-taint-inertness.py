#!/usr/bin/env python3
from pathlib import Path
import re

path = Path(
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"
)
value = path.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global value
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    value = value.replace(old, new, 1)

# This script follows the candidate-argument generalization. Keep it
# idempotent when that source was already published by an earlier gate.
if "concreteCandidateValues" not in value:
    raise SystemExit(
        "generic taint inertness requires candidate-argument selection first"
    )

if "concretePredicateValues" not in value:
    marker = '''    val concreteCandidateValues = mutable.LinkedHashMap.empty[String, Int]
'''
    addition = marker + '''    val concretePredicateValues = mutable.LinkedHashMap.empty[String, Boolean]
'''
    replace_once(marker, addition, "concrete predicate registry")

if "private def expressionDependsOnRoot(" not in value:
    marker = '''  private def currentBoundary: Option[ActiveBoundary] =
    Option(active.get()).getOrElse(Nil).headOption

'''
    helpers = '''  /**
    * Generic runtime taint classification for compiler-retained native integer
    * expressions. Product traversal deliberately has no component, method,
    * signal or source-file knowledge. Unknown leaf nodes fail closed by being
    * treated as root-dependent rather than silently concrete.
    */
  private def expressionDependsOnRoot(
      expression: ExternalNativeIntRelativeExpression
  ): Boolean = expression match {
    case ExternalNativeIntRelativeExpression.Root       => true
    case _: ExternalNativeIntRelativeExpression.Literal => false
    case product: Product =>
      val children = product.productIterator.collect {
        case value: ExternalNativeIntRelativeExpression =>
          expressionDependsOnRoot(value)
        case value: ExternalNativeIntRelativePredicate =>
          predicateDependsOnRoot(value)
      }.toVector
      if (children.isEmpty) true else children.exists(identity)
    case _ => true
  }

  private def predicateDependsOnRoot(
      predicate: ExternalNativeIntRelativePredicate
  ): Boolean = predicate match {
    case _: ExternalNativeIntRelativePredicate.Constant => false
    case product: Product =>
      val children = product.productIterator.collect {
        case value: ExternalNativeIntRelativeExpression =>
          expressionDependsOnRoot(value)
        case value: ExternalNativeIntRelativePredicate =>
          predicateDependsOnRoot(value)
      }.toVector
      if (children.isEmpty) true else children.exists(identity)
    case _ => true
  }

  private def rememberConcreteValue(
      boundary: ActiveBoundary,
      reference: String,
      witness: Int,
      sourceLocation: String
  ): Unit = {
    validateReference(reference, sourceLocation, "concrete native Int result")
    boundary.concreteCandidateValues.get(reference) match {
      case Some(existing) if existing != witness =>
        fail(
          "MORPH-FRONTEND-NATIVE-INT-CONCRETE-WITNESS-CONFLICT",
          s"concrete native Int reference '$reference' changed from $existing to $witness",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      case _ => boundary.concreteCandidateValues.update(reference, witness)
    }
  }

  private def rememberConcretePredicate(
      boundary: ActiveBoundary,
      reference: String,
      witness: Boolean,
      sourceLocation: String
  ): Unit = {
    validateReference(reference, sourceLocation, "concrete native Boolean result")
    boundary.concretePredicateValues.get(reference) match {
      case Some(existing) if existing != witness =>
        fail(
          "MORPH-FRONTEND-NATIVE-BOOLEAN-CONCRETE-WITNESS-CONFLICT",
          s"concrete native Boolean reference '$reference' changed from $existing to $witness",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      case _ => boundary.concretePredicateValues.update(reference, witness)
    }
  }

'''
    replace_once(marker, marker + helpers, "generic taint helpers")

# Centralize inert behavior: all existing compiler hooks may run broadly, but
# only Root-dependent expressions enter symbolic registries or pending slots.
def inject_method_guard(method: str, type_name: str, field: str, action: str) -> None:
    global value
    signature = re.compile(
        rf'''(  private def {method}\(.*?\n  \): Unit = \{{\n)''',
        re.S,
    )
    match = signature.search(value)
    if not match:
        if f"{action}(boundary" in value:
            return
        raise SystemExit(f"{method}: signature not found")
    body_start = match.end()
    header = match.group(1)
    parameter_match = re.search(
        rf'''([A-Za-z_][A-Za-z0-9_]*)\s*:\s*{re.escape(type_name)}''',
        header,
    )
    if not parameter_match:
        raise SystemExit(f"{method}: {type_name} parameter not found")
    parameter = parameter_match.group(1)
    guard = (
        f"    if (!{field}({parameter}.{('expression' if 'TrackedValue' in type_name else 'predicate')})) {{\n"
        f"      {action}(boundary, reference, {parameter}.witness, {parameter}.sourceLocation)\n"
        f"      return\n"
        f"    }}\n"
    )
    value = value[:body_start] + guard + value[body_start:]

# retainTracked and retainPredicate parameter layouts are stable public-runtime
# internals. Use focused regexes and fail if upstream changes them.
if "rememberConcreteValue(boundary, reference" not in value[value.find("private def retainTracked"):]:
    pattern = re.compile(
        r'''(  private def retainTracked\(\s*boundary: ActiveBoundary,\s*reference: String,\s*)([A-Za-z_][A-Za-z0-9_]*)(: ExternalNativeIntShadowTrackedValue\s*\): Unit = \{\n)''',
        re.S,
    )
    match = pattern.search(value)
    if not match:
        raise SystemExit("retainTracked signature not found")
    parameter = match.group(2)
    insertion = (
        match.group(0)
        + f"    if (!expressionDependsOnRoot({parameter}.expression)) {{\n"
        + f"      rememberConcreteValue(boundary, reference, {parameter}.witness, {parameter}.sourceLocation)\n"
        + "      return\n"
        + "    }\n"
    )
    value = value[:match.start()] + insertion + value[match.end():]

if "rememberConcretePredicate(boundary, reference" not in value[value.find("private def retainPredicate"):]:
    pattern = re.compile(
        r'''(  private def retainPredicate\(\s*boundary: ActiveBoundary,\s*reference: String,\s*)([A-Za-z_][A-Za-z0-9_]*)(: ExternalNativeIntShadowPendingPredicate\s*\): Unit = \{\n)''',
        re.S,
    )
    match = pattern.search(value)
    if not match:
        raise SystemExit("retainPredicate signature not found")
    parameter = match.group(2)
    insertion = (
        match.group(0)
        + f"    if (!predicateDependsOnRoot({parameter}.predicate)) {{\n"
        + f"      rememberConcretePredicate(boundary, reference, {parameter}.witness, {parameter}.token.sourceLocation)\n"
        + "      return\n"
        + "    }\n"
    )
    value = value[:match.start()] + insertion + value[match.end():]

# Pending local/argument slots are explanatory metadata, not discovery keys.
# Do not publish concrete-only computations as symbolic definition slots.
if "if (!expressionDependsOnRoot(expression)) return" not in value:
    pattern = re.compile(
        r'''(  private def retainSlot\(\s*boundary: ActiveBoundary,.*?\s*expression: ExternalNativeIntRelativeExpression,.*?\n  \): Unit = \{\n)''',
        re.S,
    )
    match = pattern.search(value)
    if not match:
        raise SystemExit("retainSlot signature not found")
    value = value[:match.end()] + \
        "    if (!expressionDependsOnRoot(expression)) return\n" + \
        value[match.end():]

# Resolve compiler-approved concrete predicate references by exact identity.
old = '''        case None =>
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-REFERENCE-UNRESOLVED",
            s"$role uses unbound or foreign predicate reference '$reference'",
            Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
          )
      }
    } else if (concrete) Constant(witness)
'''
new = '''        case None
            if boundary.concretePredicateValues
              .get(reference)
              .contains(witness) =>
          Constant(witness)
        case None =>
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-REFERENCE-UNRESOLVED",
            s"$role uses unbound or foreign predicate reference '$reference'",
            Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
          )
      }
    } else if (concrete) Constant(witness)
'''
if old in value:
    value = value.replace(old, new, 1)
elif "boundary.concretePredicateValues" not in value[value.find("private def resolvePredicate"):]:
    raise SystemExit("concrete predicate resolution marker not found")

# Broad generic instrumentation is allowed to observe ordinary concrete
# computations. Remove legacy checks that rejected an operation merely because
# neither operand depended on the selected root; central retain* classification
# now keeps those operations inert.
blocks = [
'''      if (
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
''',
'''      if (
        leftValue.expression == ExternalNativeIntRelativeExpression.Literal(BigInt(left)) &&
        rightValue.expression == ExternalNativeIntRelativeExpression.Literal(BigInt(right))
      ) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-OPERAND-UNPROVEN",
          s"native Int operation '$operation' has no proven symbolic operand",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
''',
'''      if (
        leftPredicate.isInstanceOf[Constant] &&
        rightPredicate.isInstanceOf[Constant]
      ) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-PREDICATE-OPERAND-UNPROVEN",
          s"native Boolean operation '$operation' has no proven symbolic operand",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
''',
'''      if (operand.isInstanceOf[Constant]) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-PREDICATE-OPERAND-UNPROVEN",
          "native Boolean negation has no proven symbolic operand",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
''',
'''      if (predicate.isInstanceOf[Constant]) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-PREDICATE-OPERAND-UNPROVEN",
          "native Boolean.toInt has no proven symbolic operand",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
'''
]
for block in blocks:
    value = value.replace(block, "", 1)

path.write_text(value)

# Extend the genericity guard to cover the central taint runtime itself.
guard = Path("morphhdl/scripts/check-generic-native-int-compiler.sh")
if guard.exists():
    text = guard.read_text()
    runtime = "morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"
    if runtime not in text:
        text += f'''\nif grep -En 'StreamFifo(CC)?|BufferCC|pushToPopGray|popToPushGray' {runtime}; then
  echo "component-specific recognition leaked into generic native taint runtime" >&2
  exit 1
fi
'''
        guard.write_text(text)
