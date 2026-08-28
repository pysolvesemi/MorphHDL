#!/usr/bin/env python3
from pathlib import Path

runtime_path = Path(
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"
)
fallback_path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)

runtime = runtime_path.read_text()
runtime_marker = "  /** Execute one untouched constructor with an active shadow scope. */\n"
if runtime.count(runtime_marker) != 1:
    raise SystemExit("native Int constructor marker is ambiguous")

runtime_helper = '''  /**
    * Return the exact retained bounded-domain root for one lowered native Int
    * expression. Discovery remains identity-only; rendered Verilog and equal
    * concrete witnesses are never lookup keys.
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
runtime = runtime.replace(runtime_marker, runtime_helper + runtime_marker, 1)
runtime_path.write_text(runtime)

fallback = fallback_path.read_text()
validation_old = '''              val provenCapturedDomainEquivalent =
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
validation_new = '''              val provenCapturedDomainEquivalent =
                isProvenCapturedDomainWidthEquivalence(
                  assignment,
                  targetWidth,
                  sourceWidth
                )
              val provenFullDomainEquivalent =
                widthInference.provesEquivalentOverSharedRoot(
                  targetWidth,
                  sourceWidth
                )
              if (
                targetWidth.isSymbolic && sourceWidth.isSymbolic &&
                targetWidth != sourceWidth && !nativeCounterNext &&
                !provenAutoResize && !provenModularUpdate &&
                !provenCapturedDomainEquivalent &&
                !provenFullDomainEquivalent
              ) {
'''
if fallback.count(validation_old) != 1:
    raise SystemExit("symbolic assignment validation marker is ambiguous")
fallback = fallback.replace(validation_old, validation_new, 1)

inference_marker = '''      def ofBase(baseType: BaseType): WidthExpr = {
'''
if fallback.count(inference_marker) != 1:
    raise SystemExit("WidthInference ofBase marker is ambiguous")

inference_helper = '''      private def retainedRoots(
          expression: WidthExpr
      ): Vector[ParameterizedStructure.StructuralPredicateRoot] =
        expression match {
          case retained: WidthRetained =>
            Option(retainedOrigins.get(retained))
              .flatMap(
                ExternalNativeIntShadowRegistry.definitionExpressionRootOf
              )
              .toVector
          case WidthBinary(_, left, right, _, _, _, _, _) =>
            retainedRoots(left) ++ retainedRoots(right)
          case WidthSelect(_, whenTrue, whenFalse, _, _, _) =>
            retainedRoots(whenTrue) ++ retainedRoots(whenFalse)
          case _ => Vector.empty
        }

      private def sharedRetainedRoot(
          left: WidthExpr,
          right: WidthExpr
      ): Option[ParameterizedStructure.StructuralPredicateRoot] = {
        val roots = retainedRoots(left) ++ retainedRoots(right)
        roots.headOption.filter(root => roots.forall(_ eq root))
      }

      /**
        * Prove two independently retained width formulas equal over the full
        * finite domain of their exact common native-Int root. This is not an
        * algebraic text rewrite: every retained leaf and the shared root are
        * recovered by object identity, and unsupported nodes fail closed.
        */
      def provesEquivalentOverSharedRoot(
          left: WidthExpr,
          right: WidthExpr
      ): Boolean = {
        if (!left.isSymbolic || !right.isSymbolic) return false
        if (left.default != right.default) return false
        if (left.parameters != right.parameters) return false

        sharedRetainedRoot(left, right).exists { root =>
          val domainSize = root.maximum - root.minimum + 1
          if (
            root.parameters != left.parameters ||
            domainSize < 1 ||
            domainSize >
              ExternalNativeIntShadowRegistry.MaximumStructuralPredicateDomainSize
          ) false
          else {
            var value = root.minimum
            var proven = true
            while (value <= root.maximum && proven) {
              val leftValue = evaluate(left, root, value)
              val rightValue = evaluate(right, root, value)
              proven =
                leftValue.nonEmpty && leftValue == rightValue &&
                leftValue.exists(_ > 0)
              value += 1
            }
            proven
          }
        }
      }

'''
fallback = fallback.replace(inference_marker, inference_helper + inference_marker, 1)
fallback_path.write_text(fallback)
