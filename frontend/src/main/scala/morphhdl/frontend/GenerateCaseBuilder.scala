package morphhdl.frontend

/**
  * The ordered literal choices and mandatory default continuation of one
  * parameter-aware generate-case capture.
  */
final class GenerateCaseBuilder private[frontend] (
    private[frontend] val token: GenerateCaseToken
) {
  def choice(value: BigInt, label: String)(body: => Unit)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): GenerateCaseBuilder = {
    val origin = SourceOrigin.capture
    HdlRange.requireIdentifier(label, "generate-case choice label", origin)
    FrontendSession.addGenerateCaseChoice(token, value, label, body, origin)
    this
  }

  def default(label: String)(body: => Unit)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): Unit = {
    val origin = SourceOrigin.capture
    HdlRange.requireIdentifier(label, "generate-case default label", origin)
    FrontendSession.completeGenerateCase(token, label, body, origin)
  }
}
