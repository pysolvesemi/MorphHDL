package spinal.core

/** Test-classpath-only access for legacy root-identity fixtures which exercise
  * raw metadata normalization without reopening those sinks in production.
  */
object ParameterizedStructureTestAccess {
  def registerIf(
      pending: ParameterizedStructuralPending,
      condition: ElaborationBooleanExpression,
      whenTrueLabel: String,
      whenFalseLabel: String,
      whenTrue: ParameterizedStructuralBlock,
      whenFalse: ParameterizedStructuralBlock,
      sourceLocation: Option[String],
      predicateDomain: Option[
        ParameterizedStructure.StructuralPredicateDomain
      ] = None
  ): Unit =
    ParameterizedStructure.registerIf(
      pending,
      condition,
      whenTrueLabel,
      whenFalseLabel,
      whenTrue,
      whenFalse,
      sourceLocation,
      predicateDomain
    )

  def registerFor(
      component: Component,
      label: String,
      indexName: String,
      count: ElaborationIntegerExpression,
      body: ParameterizedStructuralBlock,
      sourceLocation: Option[String]
  ): Unit =
    ParameterizedStructure.registerFor(
      component,
      label,
      indexName,
      count,
      body,
      sourceLocation
    )

  def registerCase(
      pending: ParameterizedStructuralPending,
      selector: ElaborationIntegerExpression,
      choices: Vector[(BigInt, String, ParameterizedStructuralBlock)],
      defaultLabel: String,
      defaultBody: ParameterizedStructuralBlock,
      sourceLocation: Option[String]
  ): Unit =
    ParameterizedStructure.registerCase(
      pending,
      selector,
      choices,
      defaultLabel,
      defaultBody,
      sourceLocation
    )
}
