#!/usr/bin/env python3
from pathlib import Path
import shutil

root = Path('.')
support = root / 'morphhdl/support/increment-53e'
production = root / 'morphhdl/src/main/scala/spinal/core/internals'
tests = root / 'morphhdl/src/test/scala/spinal/core/internals'

shutil.copyfile(
    support / 'ExternalParameterizedDomainEquivalence.scala.template',
    production / 'ExternalParameterizedDomainEquivalence.scala',
)
shutil.copyfile(
    support / 'ExternalParameterizedDomainEquivalenceTests.scala.template',
    tests / 'ExternalParameterizedDomainEquivalenceTests.scala',
)

runtime = root / 'morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala'
value = runtime.read_text()

start = value.find('  private def definitionExpressionEvidenceOf(\n')
end = value.find(
    '\n  /**\n    * Evaluate one exact lowered expression',
    start,
)
if start < 0 or end < 0:
    raise SystemExit('generic provenance evidence lookup was not materialized')

replacement = '''  private def sameLoweredDefinitionExpression(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean =
    left.verilog == right.verilog &&
      left.default == right.default &&
      left.minimum == right.minimum &&
      left.maximum == right.maximum &&
      left.parameters.distinct.sortBy(_.name) ==
        right.parameters.distinct.sortBy(_.name)

  private def sameStructuralRootSchema(
      left: ParameterizedStructure.StructuralPredicateRoot,
      right: ParameterizedStructure.StructuralPredicateRoot
  ): Boolean =
    left.verilog == right.verilog &&
      left.default == right.default &&
      left.minimum == right.minimum &&
      left.maximum == right.maximum &&
      left.parameters.distinct.sortBy(_.name) ==
        right.parameters.distinct.sortBy(_.name)

  private def definitionExpressionEvidenceCandidates(
      lowered: ElaborationIntegerExpression
  ): Vector[DefinitionExpressionEvidence] = {
    val exact = definitionExpressionEvidence.get(
      new ExternalNativeIntExpressionIdentityRef(lowered, null)
    ).toVector
    if (exact.nonEmpty) exact
    else {
      definitionExpressionEvidence.iterator.flatMap {
        case (reference, evidence) =>
          Option(reference.get())
            .filter(candidate =>
              sameLoweredDefinitionExpression(candidate, lowered)
            )
            .map(_ => evidence)
      }.toVector
    }
  }

  /**
    * Resolve one unambiguous semantic provenance record for an immutable copy
    * of a lowered native-Int expression. Root object identity is deliberately
    * not required here: canonical/actual hierarchy normalization may copy the
    * expression and its root. Instead, every candidate must have the same
    * structured relative AST and the same complete formal root schema.
    */
  private def definitionExpressionEvidenceOf(
      lowered: ElaborationIntegerExpression
  ): Option[DefinitionExpressionEvidence] = {
    val candidates = definitionExpressionEvidenceCandidates(lowered)
    candidates.headOption.filter { first =>
      candidates.forall(candidate =>
        sameStructuralRootSchema(candidate.root, first.root) &&
          candidate.expression == first.expression
      )
    }
  }
'''
value = value[:start] + replacement + value[end:]

old_strict = '''    definitionExpressionEvidenceOf(lowered)
      .filter(_.root eq root)
      .flatMap(evidence =>
'''
new_strict = '''    definitionExpressionEvidenceCandidates(lowered)
      .filter(_.root eq root)
      .distinct
      .headOption
      .flatMap(evidence =>
'''
if old_strict in value:
    value = value.replace(old_strict, new_strict, 1)
elif new_strict not in value:
    raise SystemExit('captured-domain evaluation lost strict root identity')

runtime.write_text(value)

fallback = production / 'ExternalParameterizedVerilogNativeFallback.scala'
value = fallback.read_text()

method_start = value.find(
    '    private def isProvenCompleteDomainWidthEquivalence(\n'
)
method_end = value.find(
    '\n    /**\n      * Distinct symbolic width expressions',
    method_start,
)
if method_start < 0 or method_end < 0:
    raise SystemExit('generic complete-domain method was not materialized')

method = '''    private def isProvenCompleteDomainWidthEquivalence(
        left: WidthExpr,
        right: WidthExpr
    ): Boolean = {
      if (!left.isSymbolic || !right.isSymbolic) return false
      val all = (left.parameters ++ right.parameters)
        .groupBy(_.name)
        .toVector
        .sortBy(_._1)
      if (all.exists { case (_, declarations) => declarations.distinct.size != 1 })
        return false
      val parameters = all.map(_._2.head)

      def evaluate(
          expression: WidthExpr,
          values: Map[String, BigInt]
      ): Option[BigInt] =
        widthInference.evaluateAtParameters(expression, values).orElse {
          ExternalParameterizedDomainEquivalence.evaluateRetainedExpression(
            ElaborationIntegerExpression(
              verilog = expression.render,
              default = expression.default,
              minimum = expression.minimum,
              maximum = expression.maximum,
              parameters = expression.parameters,
              sourceLocation = None
            ),
            values
          )
        }

      ExternalParameterizedDomainEquivalence.provePositiveEquality(
        parameters,
        left.default,
        right.default
      )(
        values => evaluate(left, values),
        values => evaluate(right, values)
      )
    }
'''
value = value[:method_start] + method + value[method_end:]

search_from = value.find('      def evaluateAtParameters(\n')
retained_start = value.find(
    '          case retained: WidthRetained =>\n',
    search_from,
)
retained_end = value.find(
    '          case binary: WidthBinary =>\n',
    retained_start,
)
if search_from < 0 or retained_start < 0 or retained_end < 0:
    raise SystemExit('generic WidthRetained evaluator was not materialized')

retained = '''          case retained: WidthRetained =>
            val origin = Option(retainedOrigins.get(retained))
            val retainedExpression = origin.getOrElse(
              ElaborationIntegerExpression(
                verilog = retained.render,
                default = retained.default,
                minimum = retained.minimum,
                maximum = retained.maximum,
                parameters = retained.parameters,
                sourceLocation = None
              )
            )
            val nativeValue = origin.flatMap(value =>
              ExternalNativeIntShadowRegistry
                .evaluateDefinitionExpressionAtParameters(value, values)
            )
            val directFormalValue = {
              val declarations = retainedExpression.parameters.distinct
              if (declarations.size != 1) None
              else {
                val declaration = declarations.head
                val isDirectFormal =
                  retainedExpression.verilog == declaration.name &&
                    retainedExpression.default == declaration.default &&
                    retainedExpression.minimum == declaration.minimum &&
                    retainedExpression.maximum == declaration.maximum
                if (!isDirectFormal) None
                else values.get(declaration.name)
              }
            }
            nativeValue
              .orElse(directFormalValue)
              .orElse(
                ExternalParameterizedDomainEquivalence
                  .evaluateRetainedExpression(retainedExpression, values)
              )
              .flatMap(value => checked(value, retained))
'''
value = value[:retained_start] + retained + value[retained_end:]

fallback.write_text(value)
