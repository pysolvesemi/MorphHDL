#!/usr/bin/env python3
from pathlib import Path

registry_path = Path(
    "morphruntime/src/main/scala/spinal/core/"
    "ExternalNativeIntShadowRegistry.scala"
)
registry = registry_path.read_text()
registry_marker = "  private[core] def registerDerivedFromSnapshot(\n"
registry_method = '''  /**
    * Prove two retained native-Int definition expressions equal over the
    * complete bounded domain of their exact shared parameter root.
    *
    * This is deliberately identity based: both expressions must resolve to
    * registered AST evidence and the same StructuralPredicateRoot object. A
    * matching concrete witness, interval, or rendered Verilog string is never
    * sufficient by itself.
    */
  private[core] def proveDefinitionExpressionsEquivalent(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression,
      maximumDomainValues: BigInt
  ): Boolean = {
    if (left == null || right == null || maximumDomainValues < 1) false
    else {
      val commonRoot = for {
        leftEvidence <- definitionExpressionEvidence(left)
        rightEvidence <- definitionExpressionEvidence(right)
        if leftEvidence.root eq rightEvidence.root
      } yield leftEvidence.root

      commonRoot.exists { root =>
        val count = root.maximum - root.minimum + 1
        val sameEnvelope =
          left.default == right.default &&
          left.minimum == right.minimum &&
          left.maximum == right.maximum &&
          left.parameters.sortBy(_.name) == right.parameters.sortBy(_.name)

        if (!sameEnvelope || count < 1 || count > maximumDomainValues) false
        else {
          var candidate = root.minimum
          var equivalent = true
          while (equivalent && candidate <= root.maximum) {
            val leftValue = evaluateDefinitionExpression(left, root, candidate)
            val rightValue = evaluateDefinitionExpression(right, root, candidate)
            equivalent =
              leftValue.isDefined &&
              rightValue.isDefined &&
              leftValue == rightValue &&
              leftValue.exists(value =>
                value >= left.minimum && value <= left.maximum
              ) &&
              rightValue.exists(value =>
                value >= right.minimum && value <= right.maximum
              )
            candidate += 1
          }
          equivalent
        }
      }
    }
  }

'''
if registry.count(registry_marker) != 1:
    raise SystemExit(
        "bounded derived-width proof: registerDerivedFromSnapshot marker "
        f"count={registry.count(registry_marker)}"
    )
registry_path.write_text(
    registry.replace(registry_marker, registry_method + registry_marker, 1)
)

fallback_path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
fallback = fallback_path.read_text()

evaluate_marker = '''      def evaluate(
          width: WidthExpr,
          root: ParameterizedStructure.StructuralPredicateRoot,
          witness: BigInt
      ): Option[BigInt] = width match {
'''
origin_method = '''      def retainedOriginOf(
          width: WidthExpr
      ): Option[ElaborationIntegerExpression] = width match {
        case retained: WidthRetained => Option(retainedOrigins.get(retained))
        case _                       => None
      }

'''
if fallback.count(evaluate_marker) != 1:
    raise SystemExit(
        "bounded derived-width proof: WidthInference.evaluate marker "
        f"count={fallback.count(evaluate_marker)}"
    )
fallback = fallback.replace(
    evaluate_marker,
    origin_method + evaluate_marker,
    1
)

captured_marker = '''    private def isProvenCapturedDomainWidthEquivalence(
'''
bounded_method = '''    private def isProvenBoundedDefinitionWidthEquivalence(
        source: WidthExpr,
        target: WidthExpr
    ): Boolean = {
      val sameEnvelope =
        source.default == target.default &&
        source.minimum == target.minimum &&
        source.maximum == target.maximum &&
        source.parameters.sortBy(_.name) == target.parameters.sortBy(_.name)

      if (!sameEnvelope) false
      else {
        (widths.retainedOriginOf(source), widths.retainedOriginOf(target)) match {
          case (Some(sourceOrigin), Some(targetOrigin)) =>
            ExternalNativeIntShadowRegistry.proveDefinitionExpressionsEquivalent(
              sourceOrigin,
              targetOrigin,
              MaximumStructuralPredicateDomainSize
            )
          case _ => false
        }
      }
    }

'''
if fallback.count(captured_marker) != 1:
    raise SystemExit(
        "bounded derived-width proof: captured-domain marker "
        f"count={fallback.count(captured_marker)}"
    )
fallback = fallback.replace(
    captured_marker,
    bounded_method + captured_marker,
    1
)

compat_old = '''      isProvenAutoResizeAssignment(assignment, target, source) ||
      isProvenModularLiteralAssignment(assignment, target, source)
'''
compat_new = '''      isProvenAutoResizeAssignment(assignment, target, source) ||
      isProvenModularLiteralAssignment(assignment, target, source) ||
      isProvenBoundedDefinitionWidthEquivalence(source, target)
'''
if fallback.count(compat_old) != 1:
    raise SystemExit(
        "bounded derived-width proof: compatibility marker "
        f"count={fallback.count(compat_old)}"
    )
fallback = fallback.replace(compat_old, compat_new, 1)

validation_old = '''      val equivalentByCapturedDomain =
        isProvenCapturedDomainWidthEquivalence(assignment, source, target)
      val equivalentByParameterizedStructure =
'''
validation_new = '''      val equivalentByBoundedDefinition =
        isProvenBoundedDefinitionWidthEquivalence(source, target)
      val equivalentByCapturedDomain =
        isProvenCapturedDomainWidthEquivalence(assignment, source, target)
      val equivalentByParameterizedStructure =
'''
if fallback.count(validation_old) != 1:
    raise SystemExit(
        "bounded derived-width proof: validation declaration marker "
        f"count={fallback.count(validation_old)}"
    )
fallback = fallback.replace(validation_old, validation_new, 1)

if_old = '''          equivalentByAutoResize || equivalentByModularLiteral ||
          equivalentByCapturedDomain || equivalentByParameterizedStructure
'''
if_new = '''          equivalentByAutoResize || equivalentByModularLiteral ||
          equivalentByBoundedDefinition || equivalentByCapturedDomain ||
          equivalentByParameterizedStructure
'''
if fallback.count(if_old) != 1:
    raise SystemExit(
        "bounded derived-width proof: validation acceptance marker "
        f"count={fallback.count(if_old)}"
    )
fallback = fallback.replace(if_old, if_new, 1)

fallback_path.write_text(fallback)
