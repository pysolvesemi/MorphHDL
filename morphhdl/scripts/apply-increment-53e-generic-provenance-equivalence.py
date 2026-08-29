#!/usr/bin/env python3
from pathlib import Path

registry_path = Path(
    "morphruntime/src/main/scala/spinal/core/"
    "ExternalNativeIntShadowRegistry.scala"
)
registry = registry_path.read_text()

registry_marker = '''  /** Execute one untouched constructor with an active shadow scope. */
'''
if "definitionExpressionRootOf" not in registry:
    if registry.count(registry_marker) != 1:
        raise SystemExit(
            f"definition-expression provenance marker count={registry.count(registry_marker)}"
        )
    registry_methods = '''  /**
    * Return the exact compiler-retained definition root for one lowered native
    * integer expression. Expression identity, rather than rendered text or an
    * equal witness, is the lookup key.
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

  /**
    * Evaluate one lowered native integer expression against its own exact
    * compiler-retained AST and definition root. This is the cross-capture form
    * used by generic width-equivalence checking; it deliberately does not
    * accept a caller-selected root identity.
    */
  private[core] def evaluateDefinitionExpressionInOwnDomain(
      lowered: ElaborationIntegerExpression,
      value: BigInt
  ): Option[BigInt] = synchronized {
    if (lowered == null) return None
    reapDefinitionExpressionEvidence()
    definitionExpressionEvidence
      .get(new ExternalNativeIntExpressionIdentityRef(lowered, null))
      .filter(evidence =>
        value >= evidence.root.minimum && value <= evidence.root.maximum
      )
      .flatMap(evidence =>
        ExternalNativeIntRelativeExpression.evaluate(
          evidence.expression,
          value
        )
      )
      .filter(result => result >= lowered.minimum && result <= lowered.maximum)
  }

'''
    registry = registry.replace(
        registry_marker,
        registry_methods + registry_marker,
        1,
    )
    registry_path.write_text(registry)

backend_path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
backend = backend_path.read_text()

if "provesRetainedProvenanceEquivalence" in backend:
    raise SystemExit(0)

# Replace the temporary rendered-expression evaluator if it was materialized.
parser_start = backend.find("      private final class RetainedWidthEvaluator(")
of_base = backend.find("      def ofBase(baseType: BaseType): WidthExpr = {")
if of_base < 0:
    raise SystemExit("WidthInference.ofBase marker is missing")
if parser_start >= 0:
    if parser_start >= of_base:
        raise SystemExit("retained-width parser appears after WidthInference.ofBase")
    backend = backend[:parser_start] + backend[of_base:]

# Normalize the call site regardless of whether the temporary evaluator was
# published before this provenance-backed replacement runs.
backend = backend.replace(
    "widthInference.provesRetainedArithmeticEquivalence(\n",
    "widthInference.provesRetainedProvenanceEquivalence(\n",
)

call_marker = '''              val provenCapturedDomainEquivalent =
                isProvenCapturedDomainWidthEquivalence(
                  assignment,
                  targetWidth,
                  sourceWidth
                )
'''
if "provenRetainedArithmeticEquivalent" not in backend:
    if backend.count(call_marker) != 1:
        raise SystemExit(
            f"provenance width call marker count={backend.count(call_marker)}"
        )
    backend = backend.replace(
        call_marker,
        call_marker + '''              val provenRetainedArithmeticEquivalent =
                widthInference.provesRetainedProvenanceEquivalence(
                  targetWidth,
                  sourceWidth
                )
''',
        1,
    )
    old_guard = '''                !provenCapturedDomainEquivalent
'''
    new_guard = '''                !provenCapturedDomainEquivalent &&
                !provenRetainedArithmeticEquivalent
'''
    if backend.count(old_guard) < 1:
        raise SystemExit("provenance width assignment guard marker missing")
    backend = backend.replace(old_guard, new_guard, 1)

of_base = backend.find("      def ofBase(baseType: BaseType): WidthExpr = {")
if of_base < 0:
    raise SystemExit("WidthInference.ofBase marker disappeared")

implementation = '''      /**
        * Evaluate one generic WidthExpr using only exact retained native-Int
        * provenance and a caller-supplied formal assignment. No component,
        * library, source-file, signal or rendered-HDL name is a discovery key.
        */
      private def evaluateFromOwnProvenance(
          expression: WidthExpr,
          environment: Map[String, BigInt]
      ): Option[BigInt] = expression match {
        case WidthLiteral(value) => Some(value)
        case WidthParameter(parameter) => environment.get(parameter.name)
        case retained: WidthRetained =>
          Option(retainedOrigins.get(retained)).flatMap { origin =>
            ExternalNativeIntShadowRegistry
              .definitionExpressionRootOf(origin)
              .flatMap { root =>
                val rootParameters = root.parameters.distinct
                if (rootParameters.size != 1) None
                else {
                  val parameter = rootParameters.head
                  environment.get(parameter.name).flatMap { value =>
                    val schemaMatches =
                      retained.parameters.exists(_ == parameter) &&
                      value >= parameter.minimum && value <= parameter.maximum
                    if (!schemaMatches) None
                    else ExternalNativeIntShadowRegistry
                      .evaluateDefinitionExpressionInOwnDomain(origin, value)
                  }
                }
              }
          }
        case WidthBinary(operator, left, right, _, _, _, _, _) =>
          for {
            leftValue <- evaluateFromOwnProvenance(left, environment)
            rightValue <- evaluateFromOwnProvenance(right, environment)
            result <- operator match {
              case "+" => Some(leftValue + rightValue)
              case "-" => Some(leftValue - rightValue)
              case "*" => Some(leftValue * rightValue)
              case _   => None
            }
          } yield result
        case _: WidthSelect => None
      }

      /**
        * Prove two generic retained widths equal over the complete Cartesian
        * product of their finite declared formal domains. Each retained leaf is
        * evaluated through its own exact compiler-captured AST; equal text,
        * equal concrete defaults and component identity are insufficient.
        */
      def provesRetainedProvenanceEquivalence(
          left: WidthExpr,
          right: WidthExpr
      ): Boolean = {
        if (!left.isSymbolic || !right.isSymbolic) return false
        val declarations = (left.parameters ++ right.parameters).groupBy(_.name)
        if (declarations.exists { case (_, schemas) => schemas.distinct.size != 1 })
          return false
        val parameters = declarations.toVector.sortBy(_._1).map(_._2.head)
        if (parameters.isEmpty) return false
        val domainSize = parameters.foldLeft(BigInt(1)) { (size, parameter) =>
          val cardinality = parameter.maximum - parameter.minimum + 1
          if (cardinality < 1) return false
          size * cardinality
        }
        if (
          domainSize < 1 ||
          domainSize > ExternalNativeIntShadowRegistry.MaximumStructuralPredicateDomainSize
        ) return false

        def visit(
            parameterIndex: Int,
            environment: Map[String, BigInt]
        ): Boolean = {
          if (parameterIndex == parameters.size) {
            val leftValue = evaluateFromOwnProvenance(left, environment)
            val rightValue = evaluateFromOwnProvenance(right, environment)
            leftValue.nonEmpty && leftValue == rightValue &&
              leftValue.exists(_ > 0)
          } else {
            val parameter = parameters(parameterIndex)
            var value = parameter.minimum
            while (value <= parameter.maximum) {
              if (
                !visit(
                  parameterIndex + 1,
                  environment.updated(parameter.name, value)
                )
              ) return false
              value += 1
            }
            true
          }
        }

        visit(0, Map.empty[String, BigInt])
      }

'''
backend = backend[:of_base] + implementation + backend[of_base:]

if "RetainedWidthEvaluator" in backend:
    raise SystemExit("rendered-expression width evaluator was not fully removed")
backend_path.write_text(backend)
