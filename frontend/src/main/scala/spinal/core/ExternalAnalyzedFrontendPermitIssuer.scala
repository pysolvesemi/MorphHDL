package spinal.core

import morphhdl.frontend.AnalyzedFrontendInteger

/** Narrow module bridge from an opaque completed frontend analysis to the core
  * permit type.  Its input cannot be assembled from public EIE metadata.
  */
object ExternalAnalyzedFrontendPermitIssuer {
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
