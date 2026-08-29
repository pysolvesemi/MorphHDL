#!/usr/bin/env python3
from pathlib import Path

registry = Path(
    "morphruntime/src/main/scala/spinal/core/"
    "ExternalNativeIntShadowRegistry.scala"
)
fallback = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)


def require_unique(value: str, marker: str, label: str) -> None:
    count = value.count(marker)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")


def append_guard_to_nearest_if(
    value: str,
    anchor: str,
    guard: str,
    label: str,
) -> str:
    """Append one condition to the first complete `if` after `anchor`."""
    anchor_index = value.find(anchor)
    if anchor_index < 0:
        raise SystemExit(f"{label}: anchor is missing")

    block_start = value.find("              if (\n", anchor_index)
    if block_start < 0:
        raise SystemExit(f"{label}: following if block is missing")
    block_end = value.find("              ) {\n", block_start)
    if block_end < 0:
        raise SystemExit(f"{label}: following if block is unterminated")

    block = value[block_start:block_end]
    if guard in block:
        return value

    required = (
        "targetWidth.isSymbolic",
        "sourceWidth.isSymbolic",
        "!provenCapturedDomainEquivalent",
    )
    missing = [marker for marker in required if marker not in block]
    if missing:
        raise SystemExit(
            f"{label}: following if block is not the symbolic-width rejection; "
            f"missing {', '.join(missing)}"
        )

    trimmed = block.rstrip()
    if trimmed.endswith("&&") or trimmed.endswith("||"):
        raise SystemExit(f"{label}: following if block has an incomplete condition")
    rewritten = trimmed + " &&\n                " + guard + "\n"
    return value[:block_start] + rewritten + value[block_end:]


rv = registry.read_text()
if "def definitionExpressionRoot(" not in rv:
    marker = "  /** Execute one untouched constructor with an active shadow scope. */\n"
    method = '''  /**
    * Return the provenance root of one exact lowered native-Int expression.
    * Object identity is the only lookup key; rendered text and equal witnesses
    * are deliberately insufficient. Generic downstream analyses use this to
    * validate algebraically different widths over the complete retained domain.
    */
  private[core] def definitionExpressionRoot(
      lowered: ElaborationIntegerExpression
  ): Option[ParameterizedStructure.StructuralPredicateRoot] = synchronized {
    if (lowered == null) return None
    reapDefinitionExpressionEvidence()
    definitionExpressionEvidence
      .get(new ExternalNativeIntExpressionIdentityRef(lowered, null))
      .map(_.root)
  }

'''
    require_unique(rv, marker, "generic root-query insertion marker")
    rv = rv.replace(marker, method + marker, 1)
    registry.write_text(rv)

value = fallback.read_text()

captured_equivalence = '''              val provenCapturedDomainEquivalent =
                isProvenCapturedDomainWidthEquivalence(
                  assignment,
                  targetWidth,
                  sourceWidth
                )
'''
complete_equivalence = '''              val provenCompleteDomainEquivalent =
                widthInference.provesEquivalentOnCompleteRetainedDomain(
                  targetWidth,
                  sourceWidth
                )
'''
if "val provenCompleteDomainEquivalent =" not in value:
    require_unique(
        value,
        captured_equivalence,
        "generic complete-domain assignment marker",
    )
    value = value.replace(
        captured_equivalence,
        captured_equivalence + complete_equivalence,
        1,
    )

value = append_guard_to_nearest_if(
    value,
    "              val provenCompleteDomainEquivalent =\n",
    "!provenCompleteDomainEquivalent",
    "generic complete-domain rejection marker",
)

if "def provesEquivalentOnCompleteRetainedDomain(" not in value:
    marker = '''      def ofBase(baseType: BaseType): WidthExpr = {
'''
    method = '''      /**
        * Prove two independently retained width expressions equal over their
        * complete compiler-proven native-Int domain. This is deliberately
        * component-agnostic: it follows exact expression provenance, requires
        * one shared root identity, exhaustively evaluates the bounded domain,
        * and fails closed for unsupported expression nodes.
        */
      def provesEquivalentOnCompleteRetainedDomain(
          left: WidthExpr,
          right: WidthExpr
      ): Boolean = {
        if (!left.isSymbolic || !right.isSymbolic) return false
        if (left.default != right.default) return false

        val schemas = (left.parameters ++ right.parameters).groupBy(_.name)
        if (schemas.exists { case (_, declarations) =>
              declarations.distinct.size != 1
            }) return false

        val roots = (provenanceRoots(left) ++ provenanceRoots(right)).toVector
        if (roots.size != 1) return false
        val root = roots.head
        val rootSchemas = root.parameters.map(parameter => parameter.name -> parameter).toMap
        if (schemas.exists { case (name, declarations) =>
              !rootSchemas.get(name).contains(declarations.head)
            }) return false

        val domainSize = root.maximum - root.minimum + 1
        if (
          domainSize < 1 ||
          domainSize >
            ExternalNativeIntShadowRegistry.MaximumStructuralPredicateDomainSize
        ) return false

        var value = root.minimum
        while (value <= root.maximum) {
          val leftValue = evaluate(left, root, value)
          val rightValue = evaluate(right, root, value)
          if (
            leftValue.isEmpty || rightValue.isEmpty ||
            leftValue != rightValue || leftValue.exists(_ < 1)
          ) return false
          value += 1
        }
        true
      }

      private def provenanceRoots(
          expression: WidthExpr
      ): Set[ParameterizedStructure.StructuralPredicateRoot] = expression match {
        case retained: WidthRetained =>
          retained.origin.orElse(Option(retainedOrigins.get(retained)))
            .flatMap(ExternalNativeIntShadowRegistry.definitionExpressionRoot)
            .toSet
        case WidthBinary(_, left, right, _, _, _, _, _) =>
          provenanceRoots(left) ++ provenanceRoots(right)
        case WidthSelect(_, whenTrue, whenFalse, _, _, _) =>
          provenanceRoots(whenTrue) ++ provenanceRoots(whenFalse)
        case _ => Set.empty
      }

'''
    require_unique(value, marker, "generic WidthInference insertion marker")
    value = value.replace(marker, method + marker, 1)

old = '''        case retained: WidthRetained =>
          Option(retainedOrigins.get(retained)).flatMap(origin =>
            ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
              origin,
              root,
              value
            )
          )
'''
new = '''        case retained: WidthRetained =>
          retained.origin.orElse(Option(retainedOrigins.get(retained))).flatMap(origin =>
            ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
              origin,
              root,
              value
            )
          )
        case WidthParameter(parameter)
            if root.parameters == Vector(parameter) &&
              root.default == parameter.default &&
              root.minimum == parameter.minimum &&
              root.maximum == parameter.maximum => Some(value)
'''
if old in value:
    value = value.replace(old, new, 1)

old = '''        val value = WidthRetained(
          expression.verilog,
          expression.default,
          expression.minimum,
          expression.maximum,
          expression.parameters.distinct.sortBy(_.name)
        )
'''
new = '''        val value = WidthRetained(
          expression.verilog,
          expression.default,
          expression.minimum,
          expression.maximum,
          expression.parameters.distinct.sortBy(_.name),
          origin = Some(expression)
        )
'''
if old in value:
    value = value.replace(old, new, 1)

old = '''  private final case class WidthRetained(
      render: String,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      parameters: Vector[ElaborationIntegerParameter]
  ) extends WidthExpr {
'''
new = '''  private final case class WidthRetained(
      render: String,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      parameters: Vector[ElaborationIntegerParameter],
      origin: Option[ElaborationIntegerExpression] = None
  ) extends WidthExpr {
'''
if old in value:
    value = value.replace(old, new, 1)

fallback.write_text(value)
