package spinal.core

import morphhdl.frontend.{AnalyzedFrontendBoolean, AnalyzedFrontendInteger}

/** Narrow module bridge from an opaque completed frontend analysis to the core
  * permit type.  Its input cannot be assembled from public EIE metadata.
  */
object ExternalAnalyzedFrontendPermitIssuer {
  /** Canonical construction normalization, independent of optional RTL passes.
    * The opaque pair proves that the direct predicate and the integer select
    * came from the same AST. Preserve the established ingress checks before
    * transferring its exact Boolean result metadata to that direct rendering.
    */
  def boolean(analyzed: AnalyzedFrontendBoolean): ElabBool = {
    if (analyzed == null)
      throw new IllegalArgumentException("analyzed Boolean wrapper must not be null")
    val (predicate, encoded) = analyzed.claim()
    val integer = encoded.singleRootEvaluations match {
      case Some(evaluations) =>
        ElabInt.fromSingleRootExpression(
          encoded.expression,
          evaluations,
          singleRoot(encoded)
        )
      case None => ElabInt.fromExpression(encoded.expression)
    }
    val validated = integer.elabEq(1)
    ElabBool.derived(
      validated.expression.copy(verilog = predicate.verilog),
      validated.truth,
      "analyzed frontend Boolean normalization"
    )
  }

  def singleRoot(
      analyzed: AnalyzedFrontendInteger
  ): ExternalCompilerPermit = {
    if (analyzed == null)
      throw new IllegalArgumentException("analyzed integer wrapper must not be null")
    analyzed.requireAnalyzerAuthentication()
    val (sourceIdentity, expression, evaluations) = analyzed.claimSingleRoot()
    ExternalCompilerPermit.analyzedSingleRoot(
      sourceIdentity,
      expression,
      evaluations
    )
  }
}
