package morphhdl.frontend

/** The mandatory `otherwise` continuation of one guarded generate-if capture. */
final class GenerateIfBuilder private[frontend] (
    private[frontend] val token: GenerateIfToken,
    private[frontend] val nativeToken: NativeGenerateIfToken
) {
  private[frontend] def this(token: GenerateIfToken) = this(token, null)
  private[frontend] def this(token: NativeGenerateIfToken) = this(null, token)

  def otherwise(body: => Unit)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): Unit = {
    val origin = SourceOrigin.capture
    if (nativeToken ne null) nativeToken.otherwise(body, origin)
    else FrontendSession.completeGenerateIf(token, body, origin)
  }
}
