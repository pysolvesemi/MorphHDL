#!/usr/bin/env python3
"""Install a component-agnostic symbolic width-equivalence proof.

The repair deliberately knows nothing about StreamFifo, StreamFifoCC, BufferCC,
RAM signal names, or emitted Verilog names.  It authorizes an assignment only
when both symbolic width expressions carry compiler-retained provenance for the
same exact bounded formal root and evaluate to the same positive width at every
legal root value.
"""
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / "morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"
FALLBACK = ROOT / "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"


def replace_once(value: str, old: str, new: str, label: str) -> str:
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one marker, found {count}")
    return value.replace(old, new, 1)


runtime = RUNTIME.read_text()
if "definitionExpressionRootOf(" not in runtime:
    marker = '''  /** Execute one untouched constructor with an active shadow scope. */
'''
    addition = '''  /**
    * Return the exact bounded formal root that owns one compiler-retained
    * definition expression.  Identity, rather than rendered text or a numeric
    * witness, is the lookup key.  Callers therefore cannot manufacture a proof
    * by spelling an equivalent expression or by choosing the same default.
    */
  private[core] def definitionExpressionRootOf(
      lowered: ElaborationIntegerExpression
  ): Option[ParameterizedStructure.StructuralPredicateRoot] = synchronized {
    if (lowered == null) None
    else {
      reapDefinitionExpressionEvidence()
      definitionExpressionEvidence
        .get(new ExternalNativeIntExpressionIdentityRef(lowered, null))
        .map(_.root)
    }
  }

'''
    runtime = replace_once(
        runtime,
        marker,
        addition + marker,
        "definition-expression root API",
    )
    RUNTIME.write_text(runtime)

fallback = FALLBACK.read_text()
if "provesEquivalentAcrossCompleteDomain" not in fallback:
    old_facts = '''              val provenCapturedDomainEquivalent =
                isProvenCapturedDomainWidthEquivalence(
                  assignment,
                  targetWidth,
                  sourceWidth
                )
'''
    new_facts = old_facts + '''              val provenCompleteDomainEquivalent =
                widthInference.provesEquivalentAcrossCompleteDomain(
                  targetWidth,
                  sourceWidth
                )
'''
    fallback = replace_once(
        fallback,
        old_facts,
        new_facts,
        "assignment complete-domain fact",
    )

    old_symbolic_guard = '''                !provenAutoResize && !provenModularUpdate &&
                !provenCapturedDomainEquivalent
'''
    new_symbolic_guard = '''                !provenAutoResize && !provenModularUpdate &&
                !provenCapturedDomainEquivalent &&
                !provenCompleteDomainEquivalent
'''
    fallback = replace_once(
        fallback,
        old_symbolic_guard,
        new_symbolic_guard,
        "symbolic assignment guard",
    )

    evaluate_end = '''        case _ => None
      }

      def ofBase(baseType: BaseType): WidthExpr = {
'''
    proof = '''        case _ => None
      }

      /**
        * Prove equality of two symbolic widths over their complete shared
        * compiler-retained domain.  This is intentionally component agnostic:
        * only exact expression provenance, one exact root identity, matching
        * parameter schemas, bounded exhaustive evaluation, and positive equal
        * results can authorize the assignment.
        */
      def provesEquivalentAcrossCompleteDomain(
          left: WidthExpr,
          right: WidthExpr
      ): Boolean = {
        if (
          !left.isSymbolic || !right.isSymbolic ||
          left.default != right.default ||
          left.parameters.distinct.sortBy(_.name) !=
            right.parameters.distinct.sortBy(_.name)
        ) return false

        val roots = new IdentityHashMap[
          ParameterizedStructure.StructuralPredicateRoot,
          java.lang.Boolean
        ]()
        var complete = true

        def collect(expression: WidthExpr): Unit = expression match {
          case retained: WidthRetained =>
            Option(retainedOrigins.get(retained))
              .flatMap(ExternalNativeIntShadowRegistry.definitionExpressionRootOf)
              .fold(complete = false)(root =>
                roots.put(root, java.lang.Boolean.TRUE)
              )
          case _: WidthParameter =>
            // A bare parameter has no compiler-retained native expression
            // identity in this proof path.  Refuse to infer one by name.
            complete = false
          case _: WidthLiteral =>
          case value: WidthBinary =>
            collect(value.left)
            collect(value.right)
          case value: WidthSelect =>
            // WidthSelect stores a rendered condition, not an identity-backed
            // Boolean evaluator.  Fail closed until such provenance exists.
            complete = false
            collect(value.whenTrue)
            collect(value.whenFalse)
        }

        collect(left)
        collect(right)
        if (!complete || roots.size != 1) return false

        val root = roots.keySet().iterator().next()
        val schema = root.parameters.distinct.sortBy(_.name)
        if (left.parameters.distinct.sortBy(_.name) != schema) return false

        val domainSize = root.maximum - root.minimum + 1
        if (
          domainSize < 1 ||
          domainSize > ExternalNativeIntShadowRegistry.MaximumStructuralPredicateDomainSize
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

      def ofBase(baseType: BaseType): WidthExpr = {
'''
    fallback = replace_once(
        fallback,
        evaluate_end,
        proof,
        "complete-domain proof implementation",
    )
    FALLBACK.write_text(fallback)

# The generic backend must never contain component-specific recognition.
for path in (RUNTIME, FALLBACK):
    value = path.read_text()
    for forbidden in ("StreamFifo", "StreamFifoCC", "BufferCC"):
        if forbidden in value:
            raise SystemExit(
                f"generic proof boundary violation: {path} contains {forbidden}"
            )
