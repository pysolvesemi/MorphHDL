package morphhdl.frontend

/** The mandatory `otherwise` continuation of one guarded generate-if capture. */
final class GenerateIfBuilder private[frontend] (
    private[frontend] val token: GenerateIfToken
) {
  def otherwise(body: => Unit)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): Unit =
    FrontendSession.completeGenerateIf(token, body, SourceOrigin.capture)
}
