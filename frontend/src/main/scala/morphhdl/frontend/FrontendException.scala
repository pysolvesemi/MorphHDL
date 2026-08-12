package morphhdl.frontend

final class FrontendException(
    val code: String,
    val detail: String,
    val origin: SourceOrigin,
    val suggestion: String
) extends IllegalArgumentException(
      s"[$code] ${origin.rendered}: $detail Suggested replacement: $suggestion"
    ) {
  val sourceLocation: String = origin.rendered
  val suggestedReplacement: String = suggestion
}

private[frontend] object FrontendException {
  def fail(code: String, detail: String)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): Nothing =
    failAt(code, detail, SourceOrigin.capture)

  def failAt(code: String, detail: String, origin: SourceOrigin): Nothing =
    throw new FrontendException(code, detail, origin, suggestionFor(code))

  private def suggestionFor(code: String): String = code match {
    case "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED" =>
      "Use a static Scala condition, or wait for the parameter-aware HdlBool comparison API."
    case "MORPH-FRONTEND-SYMBOLIC-CONVERSION-UNSUPPORTED" =>
      "Keep the value as HdlInt or GenIndex and pass it to a supported parameter-aware API."
    case "MORPH-FRONTEND-GENINDEX-CONSUMER-UNSUPPORTED" =>
      "Use the generate index only as an indexedPartSelect offset in Increment 6."
    case "MORPH-FRONTEND-CROSS-SCOPE-EXPRESSION" =>
      "Keep each symbolic expression within one generate-loop scope."
    case "MORPH-FRONTEND-GENINDEX-ESCAPED" =>
      "Construct and consume the generate-index expression inside its loop body."
    case "MORPH-FRONTEND-NESTED-GENERATE-UNSUPPORTED" =>
      "Flatten the loops or emit one supported generate loop."
    case "MORPH-FRONTEND-GENERATE-START-UNSUPPORTED" =>
      "Rewrite the loop as `0 until count` and adjust the indexed offset."
    case "MORPH-FRONTEND-GENERATE-COUNT-NONPOSITIVE" =>
      "Choose a positive default witness and declare a minimum of at least 1."
    case "MORPH-FRONTEND-GENERATE-COUNT-TOO-LARGE" =>
      "Choose a default witness within the Scala Int range."
    case "MORPH-FRONTEND-INVALID-GENERATE-NAME" =>
      "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`."
    case "MORPH-FRONTEND-NOT-A-PUBLIC-PARAMETER" =>
      "Declare the value with HdlInt.param before using integerParameter."
    case "MORPH-FRONTEND-PARAMETER-TOKEN-MISMATCH" =>
      "Reuse the exact HdlInt.param value declared by this module."
    case "MORPH-FRONTEND-PARAMETER-NOT-DECLARED" =>
      "Declare the referenced HdlInt.param value in this module."
    case "MORPH-FRONTEND-PARAMETER-NAME-DUPLICATE" =>
      "Give every public parameter in a module a unique name."
    case "MORPH-FRONTEND-GENERATE-NAME-DUPLICATE" =>
      "Use .named(label = ..., index = ...) with unique stable identifiers."
    case "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE" | "MORPH-FRONTEND-MISSING-COLLECTOR" =>
      "Emit module items inside ParamRtlFrontend.captureItems."
    case "MORPH-FRONTEND-SESSION-NESTED" =>
      "Complete the current frontend session before starting another one."
    case "MORPH-FRONTEND-SESSION-MISSING" =>
      "Run the loop through the concrete or parameterized frontend entry point."
    case _ =>
      "Replace the unsupported operation with a static value or a supported parameter-aware API."
  }
}
