#!/usr/bin/env python3
from pathlib import Path

registry = Path(
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"
)
fallback = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)

registry_text = registry.read_text()
if "def definitionExpressionRootOf(" not in registry_text:
    marker = "  /** Execute one untouched constructor with an active shadow scope. */\n"
    if registry_text.count(marker) != 1:
        raise SystemExit(
            f"generic expression-root insertion marker count={registry_text.count(marker)}"
        )
    method = '''  /**
    * Return the exact retained definition-domain root for one lowered native
    * integer expression. The lookup is by lowered-expression object identity;
    * rendered text, equal witnesses and component names are never discovery
    * keys. Callers can therefore prove relationships between independently
    * lowered native expressions without introducing library-specific rules.
    */
  private[core] def definitionExpressionRootOf(
      lowered: ElaborationIntegerExpression
  ): Option[ParameterizedStructure.StructuralPredicateRoot] = synchronized {
    if (lowered == null) return None
    reapDefinitionExpressionEvidence()
    definitionExpressionEvidence
      .get(new ExternalNativeIntExpressionIdentityRef(lowered, null))
      .map(_.root)
  }

'''
    registry_text = registry_text.replace(marker, method + marker, 1)
registry.write_text(registry_text)

fallback_text = fallback.read_text()
if "provenRetainedDomainEquivalent" not in fallback_text:
    old = '''              val provenCapturedDomainEquivalent =
                isProvenCapturedDomainWidthEquivalence(
                  assignment,
                  targetWidth,
                  sourceWidth
                )
              if (
                targetWidth.isSymbolic && sourceWidth.isSymbolic &&
                targetWidth != sourceWidth && !nativeCounterNext &&
                !provenAutoResize && !provenModularUpdate &&
                !provenCapturedDomainEquivalent
              ) {
'''
    new = '''              val provenCapturedDomainEquivalent =
                isProvenCapturedDomainWidthEquivalence(
                  assignment,
                  targetWidth,
                  sourceWidth
                )
              val provenRetainedDomainEquivalent =
                widthInference.provenEquivalentOverSharedDomain(
                  targetWidth,
                  sourceWidth
                )
              if (
                targetWidth.isSymbolic && sourceWidth.isSymbolic &&
                targetWidth != sourceWidth && !nativeCounterNext &&
                !provenAutoResize && !provenModularUpdate &&
                !provenCapturedDomainEquivalent &&
                !provenRetainedDomainEquivalent
              ) {
'''
    if fallback_text.count(old) != 1:
        raise SystemExit(
            f"generic assignment-equivalence marker count={fallback_text.count(old)}"
        )
    fallback_text = fallback_text.replace(old, new, 1)

if "def provenEquivalentOverSharedDomain(" not in fallback_text:
    marker = "      def ofBase(baseType: BaseType): WidthExpr = {\n"
    if fallback_text.count(marker) != 1:
        raise SystemExit(
            f"generic WidthInference insertion marker count={fallback_text.count(marker)}"
        )
    method = '''      /**
        * Prove equality of two independently retained symbolic widths over one
        * exact shared native-Int definition domain. This is deliberately
        * component agnostic: roots and leaf expressions are recovered only by
        * compiler-retained object identity, and every admitted root value is
        * evaluated. Missing provenance, mixed roots, oversized domains,
        * undefined values and non-positive widths all fail closed.
        */
      def provenEquivalentOverSharedDomain(
          left: WidthExpr,
          right: WidthExpr
      ): Boolean = {
        if (left == right) return true
        if (
          !left.isSymbolic || !right.isSymbolic ||
          left.default != right.default
        ) return false

        val roots = new IdentityHashMap[
          ParameterizedStructure.StructuralPredicateRoot,
          java.lang.Boolean
        ]()
        var missingProvenance = false

        def collect(value: WidthExpr): Unit = value match {
          case retained: WidthRetained =>
            val origin = Option(retainedOrigins.get(retained))
            val root = origin.flatMap(
              ExternalNativeIntShadowRegistry.definitionExpressionRootOf
            )
            root match {
              case Some(value) => roots.put(value, java.lang.Boolean.TRUE)
              case None        => missingProvenance = true
            }
          case WidthBinary(_, l, r, _, _, _, _, _) =>
            collect(l)
            collect(r)
          case WidthSelect(_, whenTrue, whenFalse, _, _, _) =>
            collect(whenTrue)
            collect(whenFalse)
          case _: WidthParameter => missingProvenance = true
          case _: WidthLiteral   =>
        }

        collect(left)
        collect(right)
        if (missingProvenance || roots.size != 1) return false

        val root = roots.keySet().iterator().next()
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

'''
    fallback_text = fallback_text.replace(marker, method + marker, 1)
fallback.write_text(fallback_text)
