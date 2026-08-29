#!/usr/bin/env python3
from pathlib import Path
import re

path = Path(
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"
)
value = path.read_text()


def matching_brace(text: str, opening: int) -> int:
    depth = 0
    in_string = False
    escaped = False
    line_comment = False
    block_comment = 0
    i = opening
    while i < len(text):
        c = text[i]
        n = text[i + 1] if i + 1 < len(text) else ""
        if line_comment:
            if c == "\n":
                line_comment = False
            i += 1
            continue
        if block_comment:
            if c == "/" and n == "*":
                block_comment += 1
                i += 2
                continue
            if c == "*" and n == "/":
                block_comment -= 1
                i += 2
                continue
            i += 1
            continue
        if in_string:
            if escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif c == '"':
                in_string = False
            i += 1
            continue
        if c == "/" and n == "/":
            line_comment = True
            i += 2
            continue
        if c == "/" and n == "*":
            block_comment = 1
            i += 2
            continue
        if c == '"':
            in_string = True
            i += 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise SystemExit("unbalanced Scala brace")


def method_region(name: str):
    patterns = [f"private def {name}(", f"private[core] def {name}("]
    starts = [value.find(pattern) for pattern in patterns]
    starts = [start for start in starts if start >= 0]
    if len(starts) != 1:
        raise SystemExit(f"{name}: expected one method, found {len(starts)}")
    start = starts[0]
    opening = value.find("{", start)
    if opening < 0:
        raise SystemExit(f"{name}: no body")
    return start, opening, matching_brace(value, opening)


def inject_entry(name: str, code: str, sentinel: str):
    global value
    if sentinel in value:
        return
    _, opening, _ = method_region(name)
    value = value[: opening + 1] + "\n" + code + value[opening + 1 :]


def remove_guard_containing(method: str, diagnostic: str):
    global value
    start, opening, end = method_region(method)
    region = value[opening + 1 : end]
    marker = region.find(diagnostic)
    if marker < 0:
        return
    absolute_marker = opening + 1 + marker
    candidate = value.rfind("\n      if (", opening, absolute_marker)
    if candidate < 0:
        candidate = value.rfind("\n    if (", opening, absolute_marker)
    if candidate < 0:
        raise SystemExit(f"{method}: diagnostic guard start not found")
    guard_start = candidate + 1
    guard_open = value.find("{", guard_start, absolute_marker)
    if guard_open < 0:
        raise SystemExit(f"{method}: diagnostic guard body not found")
    guard_end = matching_brace(value, guard_open)
    while guard_end + 1 < len(value) and value[guard_end + 1] in " \t":
        guard_end += 1
    if guard_end + 1 < len(value) and value[guard_end + 1] == "\n":
        guard_end += 1
    value = value[:guard_start] + value[guard_end + 1 :]


if "concreteCandidateValues" not in value:
    raise SystemExit("candidate argument runtime must be materialized first")

if "concretePredicateValues" not in value:
    marker = "    val concreteCandidateValues = mutable.LinkedHashMap.empty[String, Int]\n"
    if value.count(marker) != 1:
        raise SystemExit("concrete candidate map marker not found")
    value = value.replace(
        marker,
        marker + "    val concretePredicateValues = mutable.LinkedHashMap.empty[String, Boolean]\n",
        1,
    )

if "private def expressionDependsOnRoot(" not in value:
    marker = "  private def withBoundaryOrValue(\n"
    if value.count(marker) != 1:
        raise SystemExit("taint helper insertion marker not found")
    helpers = '''  /** Generic, component-neutral symbolic taint classification. */
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
    value = value.replace(marker, helpers + marker, 1)

# Parse parameter names from actual method headers, then inject central
# classification at method entry.
for method, type_name, expression_field, action, source in (
    (
        "retainTracked",
        "ExternalNativeIntShadowTrackedValue",
        "expression",
        "rememberConcreteValue",
        "value.sourceLocation",
    ),
    (
        "retainPredicate",
        "ExternalNativeIntShadowPendingPredicate",
        "predicate",
        "rememberConcretePredicate",
        "value.token.sourceLocation",
    ),
):
    start, opening, _ = method_region(method)
    header = value[start:opening]
    match = re.search(
        rf"([A-Za-z_][A-Za-z0-9_]*)\s*:\s*{type_name}", header
    )
    if not match:
        raise SystemExit(f"{method}: typed parameter not found")
    parameter = match.group(1)
    if parameter != "value":
        source = source.replace("value.", parameter + ".")
    predicate = (
        f"expressionDependsOnRoot({parameter}.{expression_field})"
        if method == "retainTracked"
        else f"predicateDependsOnRoot({parameter}.{expression_field})"
    )
    injection = (
        f"    if (!{predicate}) {{\n"
        f"      {action}(boundary, reference, {parameter}.witness, {source})\n"
        "      return\n"
        "    }\n"
    )
    sentinel = f"{action}(boundary, reference, {parameter}.witness"
    inject_entry(method, injection, sentinel)

if "if (!expressionDependsOnRoot(expression)) return" not in value:
    inject_entry(
        "retainSlot",
        "    if (!expressionDependsOnRoot(expression)) return\n",
        "if (!expressionDependsOnRoot(expression)) return",
    )

# Approved concrete predicates are resolved only through exact compiler
# references and matching witnesses.
start, opening, end = method_region("resolvePredicate")
region = value[opening:end]
if "boundary.concretePredicateValues" not in region:
    old = '''        case None =>
          fail(
            "MORPH-FRONTEND-NATIVE-INT-SHADOW-REFERENCE-UNRESOLVED",
            s"$role uses unbound or foreign predicate reference '$reference'",
            Option(sourceLocation).filter(_.nonEmpty).orElse(sourceOf(boundary.token))
          )
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
'''
    if region.count(old) != 1:
        raise SystemExit("resolvePredicate unresolved-reference marker not found")
    region = region.replace(old, new, 1)
    value = value[:opening] + region + value[end:]

remove_guard_containing(
    "binaryTracked", "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-OPERAND-UNPROVEN"
)
remove_guard_containing(
    "booleanBinaryTracked", "MORPH-FRONTEND-NATIVE-INT-PREDICATE-OPERAND-UNPROVEN"
)
remove_guard_containing(
    "booleanNotTracked", "MORPH-FRONTEND-NATIVE-INT-PREDICATE-OPERAND-UNPROVEN"
)
remove_guard_containing(
    "booleanToIntTracked", "MORPH-FRONTEND-NATIVE-INT-PREDICATE-OPERAND-UNPROVEN"
)

path.write_text(value)

# Genericity guard: the runtime proof engine cannot recognize witnesses by
# library component, source line, internal signal or emitted name.
guard = Path("morphhdl/scripts/check-generic-native-int-compiler.sh")
if guard.exists():
    text = guard.read_text()
    runtime = "morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"
    clause = f'''\nif grep -En 'StreamFifo(CC)?|BufferCC|pushToPopGray|popToPushGray' {runtime}; then
  echo "component-specific recognition leaked into generic native taint runtime" >&2
  exit 1
fi
'''
    if clause not in text:
        guard.write_text(text + clause)
