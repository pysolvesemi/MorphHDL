package morphhdl.frontend

/**
  * The ordered literal choices and mandatory default continuation of one
  * parameter-aware generate-case capture.
  */
final class GenerateCaseBuilder private[frontend] (
    private[frontend] val token: GenerateCaseToken,
    private[frontend] val nativeToken: NativeGenerateCaseToken
) {
  private[frontend] def this(token: GenerateCaseToken) = this(token, null)
  private[frontend] def this(token: NativeGenerateCaseToken) = this(null, token)

  def choice(value: BigInt, label: String)(body: => Unit)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): GenerateCaseBuilder = {
    val origin = SourceOrigin.capture
    HdlRange.requireIdentifier(label, "generate-case choice label", origin)
    if (nativeToken ne null) nativeToken.choice(value, label, body, origin)
    else FrontendSession.addGenerateCaseChoice(token, value, label, body, origin)
    this
  }

  def default(label: String)(body: => Unit)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): Unit = {
    val origin = SourceOrigin.capture
    HdlRange.requireIdentifier(label, "generate-case default label", origin)
    if (nativeToken ne null) nativeToken.default(label, body, origin)
    else FrontendSession.completeGenerateCase(token, label, body, origin)
  }
}
