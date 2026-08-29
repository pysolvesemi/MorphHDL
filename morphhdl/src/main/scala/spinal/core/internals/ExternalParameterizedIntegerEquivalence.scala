package spinal.core.internals

import spinal.core._

/**
  * Generic MorphHDL proof for two independently captured native integer
  * expressions.
  *
  * The proof is intentionally independent of Components, library classes,
  * source files, signal names and emitted HDL. Each expression must carry exact
  * compiler-retained native-Int provenance. The expressions are evaluated
  * through their own captured ASTs over the complete finite formal domain.
  * Missing provenance, incompatible schemas, unsupported multi-root
  * expressions and oversized domains fail closed.
  */
private[internals] object ExternalParameterizedIntegerEquivalence {
  private final case class RootSchema(
      parameter: ElaborationIntegerParameter,
      minimum: BigInt,
      maximum: BigInt
  )

  private def rootSchemaOf(
      expression: ElaborationIntegerExpression
  ): Option[RootSchema] =
    ExternalNativeIntShadowRegistry
      .definitionExpressionRootOf(expression)
      .flatMap { root =>
        val parameters = root.parameters.distinct
        if (parameters.size != 1) None
        else {
          val parameter = parameters.head
          val schemaMatchesExpression =
            expression.parameters.contains(parameter) &&
            root.minimum == parameter.minimum &&
            root.maximum == parameter.maximum
          if (!schemaMatchesExpression) None
          else Some(RootSchema(parameter, root.minimum, root.maximum))
        }
      }

  /**
    * Return true only when exact bounded evaluation proves equality and a
    * positive result for every legal value of one identical formal schema.
    */
  def proves(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean = {
    if (left == null || right == null) return false
    (rootSchemaOf(left), rootSchemaOf(right)) match {
      case (Some(leftRoot), Some(rightRoot))
          if leftRoot.parameter == rightRoot.parameter &&
            leftRoot.minimum == rightRoot.minimum &&
            leftRoot.maximum == rightRoot.maximum =>
        val size = leftRoot.maximum - leftRoot.minimum + 1
        if (
          size < 1 ||
          size > ExternalNativeIntShadowRegistry.MaximumStructuralPredicateDomainSize
        ) return false

        var value = leftRoot.minimum
        while (value <= leftRoot.maximum) {
          val leftValue =
            ExternalNativeIntShadowRegistry
              .evaluateDefinitionExpressionInOwnDomain(left, value)
          val rightValue =
            ExternalNativeIntShadowRegistry
              .evaluateDefinitionExpressionInOwnDomain(right, value)
          if (
            leftValue.isEmpty || rightValue.isEmpty ||
            leftValue != rightValue || leftValue.exists(_ <= 0)
          ) return false
          value += 1
        }
        true
      case _ => false
    }
  }
}
