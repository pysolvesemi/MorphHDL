package spinal.core

/** Opaque one-shot authority for an exact structural predicate analyzed by an
  * external typed frontend.  The permit is bound to every identity which
  * authorizes publication; equal rendered text or copied evaluation metadata
  * cannot reproduce it.
  */
final class ExternalStructuralPredicatePermit private[core] (
    private[core] val sourceIdentity: AnyRef,
    private[core] val conditionIdentity: ElaborationBooleanExpression,
    private[core] val evaluationsIdentity: AnyRef,
    private[core] val componentIdentity: Component,
    private[core] val operationIdentity: AnyRef
) {
  private[this] var consumed = false

  private[core] def claim(
      source: AnyRef,
      condition: ElaborationBooleanExpression,
      evaluations: AnyRef,
      component: Component,
      operation: AnyRef
  ): Boolean = synchronized {
    if (
      consumed || !(sourceIdentity eq source) ||
      !(conditionIdentity eq condition) || !(evaluationsIdentity eq evaluations) ||
      !(componentIdentity eq component) || !(operationIdentity eq operation)
    ) false
    else {
      consumed = true
      true
    }
  }
}

object ExternalStructuralPredicatePermit {

  /** Called only after the analyzer-sealed frontend wrapper has authenticated
    * and consumed its exact operation/component target claim.
    */
  private[core] def analyzed(
      sourceIdentity: AnyRef,
      condition: ElaborationBooleanExpression,
      evaluationsIdentity: AnyRef,
      componentIdentity: Component,
      operationIdentity: AnyRef
  ): ExternalStructuralPredicatePermit = {
    if (sourceIdentity == null)
      throw new IllegalArgumentException("analyzed Boolean source identity must not be null")
    if (condition == null)
      throw new IllegalArgumentException("analyzed Boolean condition must not be null")
    if (evaluationsIdentity == null)
      throw new IllegalArgumentException("analyzed Boolean evaluation table must not be null")
    if (componentIdentity == null)
      throw new IllegalArgumentException("analyzed Boolean component must not be null")
    if (operationIdentity == null)
      throw new IllegalArgumentException("analyzed Boolean operation must not be null")
    new ExternalStructuralPredicatePermit(
      sourceIdentity,
      condition,
      evaluationsIdentity,
      componentIdentity,
      operationIdentity
    )
  }

  private[spinal] def requireAnalyzed(
      permit: ExternalStructuralPredicatePermit,
      sourceIdentity: AnyRef,
      condition: ElaborationBooleanExpression,
      evaluationsIdentity: AnyRef,
      componentIdentity: Component,
      operationIdentity: AnyRef
  ): Unit = {
    if (permit == null) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-BOOL-ANALYZED-PREDICATE-AUTHORIZATION-REQUIRED",
        "analyzed structural predicate publication requires one opaque frontend-analysis permit",
        Option(condition).flatMap(_.sourceLocation)
      )
    }
    if (
      !permit.claim(
        sourceIdentity,
        condition,
        evaluationsIdentity,
        componentIdentity,
        operationIdentity
      )
    ) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-BOOL-ANALYZED-PREDICATE-AUTHORIZATION-MISMATCH",
        "analyzed structural predicate publication received a consumed, copied, stale, or foreign permit",
        Option(condition).flatMap(_.sourceLocation)
      )
    }
  }
}
