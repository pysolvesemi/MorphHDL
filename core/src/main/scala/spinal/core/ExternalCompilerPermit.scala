package spinal.core

/** Opaque authority for one expression emitted by an analyzed frontend or by
  * the native-Int compiler bridge.
  *
  * Public rendered metadata is never authority by itself.  A permit is bound
  * to the exact analyzed carrier identities and is intentionally a plain final
  * reference object: diagnostic strings and structural equality cannot
  * reproduce it.
  */
final class ExternalCompilerPermit private[core] (
    private[core] val kind: ExternalCompilerPermit.Kind,
    private[core] val sourceIdentity: AnyRef,
    private[core] val integerExpression: ElaborationIntegerExpression,
    private[core] val evaluationsIdentity: AnyRef
) {
  private[this] var consumed = false

  private[core] def claimSingleRoot(
      expression: ElaborationIntegerExpression,
      evaluations: AnyRef
  ): Boolean = synchronized {
    if (
      consumed || !(kind eq ExternalCompilerPermit.AnalyzedSingleRoot) ||
      !(integerExpression eq expression) || !(evaluationsIdentity eq evaluations)
    ) false
    else {
      consumed = true
      true
    }
  }
}

object ExternalCompilerPermit {
  private[core] sealed abstract class Kind private[ExternalCompilerPermit] ()
  private[core] case object AnalyzedSingleRoot extends Kind

  /** Called only by the frontend module's opaque analyzed-wrapper bridge.  The
    * bridge accepts no raw expression/table pair.
    */
  private[core] def analyzedSingleRoot(
      sourceIdentity: AnyRef,
      expression: ElaborationIntegerExpression,
      evaluationsIdentity: AnyRef
  ): ExternalCompilerPermit = {
    if (sourceIdentity == null)
      throw new IllegalArgumentException("analyzed source identity must not be null")
    if (expression == null)
      throw new IllegalArgumentException("analyzed integer expression must not be null")
    if (evaluationsIdentity == null)
      throw new IllegalArgumentException("analyzed evaluation table identity must not be null")
    new ExternalCompilerPermit(
      AnalyzedSingleRoot,
      sourceIdentity,
      expression,
      evaluationsIdentity
    )
  }

  private[spinal] def requireAnalyzedSingleRoot(
      permit: ExternalCompilerPermit,
      expression: ElaborationIntegerExpression,
      evaluationsIdentity: AnyRef
  ): Unit = {
    if (permit == null) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-INT-ANALYZED-SOURCE-AUTHORIZATION-REQUIRED",
        "single-root exact-domain publication requires one opaque frontend-analysis permit",
        Option(expression).flatMap(_.sourceLocation)
      )
    }
    if (!permit.claimSingleRoot(expression, evaluationsIdentity)) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-INT-ANALYZED-SOURCE-AUTHORIZATION-MISMATCH",
        "single-root exact-domain publication received a consumed, copied, stale, or foreign analyzed-source permit",
        Option(expression).flatMap(_.sourceLocation)
      )
    }
  }
}
