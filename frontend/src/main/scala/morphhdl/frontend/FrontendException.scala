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
      "Use hdlEq/hdlNe for HdlInt equality, or use a static Scala condition for unsupported symbolic equality."
    case "MORPH-FRONTEND-SYMBOLIC-CONVERSION-UNSUPPORTED" =>
      "Keep the value as HdlInt or GenIndex and pass it to a supported parameter-aware API."
    case "MORPH-FRONTEND-GENINDEX-CONSUMER-UNSUPPORTED" =>
      "Use the generate index only as an indexedPartSelect offset in the current frontend surface."
    case "MORPH-FRONTEND-CROSS-SCOPE-EXPRESSION" =>
      "Keep each symbolic expression within one generate-loop scope."
    case "MORPH-FRONTEND-GENINDEX-ESCAPED" =>
      "Construct and consume the generate-index expression inside its loop body."
    case "MORPH-FRONTEND-NESTED-GENERATE-UNSUPPORTED" =>
      "Flatten the loops or emit one supported generate loop."
    case "MORPH-FRONTEND-GENERATE-IF-OTHERWISE-MISSING" =>
      "Complete every generateIf with exactly one otherwise branch in the same capture."
    case "MORPH-FRONTEND-GENERATE-IF-OTHERWISE-DUPLICATE" =>
      "Supply one otherwise branch for each generateIf."
    case "MORPH-FRONTEND-GENERATE-IF-ESCAPED" =>
      "Complete the generateIf in the same frontend capture where it was created."
    case "MORPH-FRONTEND-GENERATE-IF-MULTIPLE" =>
      "Use one top-level generateIf per module-item capture."
    case "MORPH-FRONTEND-BOOLEAN-CONDITION-NULL" =>
      "Pass an HdlBool literal, expression or public parameter to generateIf."
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
    case "MORPH-FRONTEND-NOT-A-BOOLEAN-PARAMETER" =>
      "Declare the value with HdlBool.param before using booleanParameter."
    case "MORPH-FRONTEND-BOOLEAN-PARAMETER-TOKEN-MISMATCH" =>
      "Reuse the exact HdlBool.param value declared by this module."
    case "MORPH-FRONTEND-BOOLEAN-PARAMETER-NOT-DECLARED" =>
      "Declare the referenced HdlBool.param value with booleanParameter."
    case "MORPH-FRONTEND-BOOLEAN-PARAMETER-NAME-DUPLICATE" =>
      "Give every Boolean parameter in a module a unique name."
    case "MORPH-FRONTEND-PARAMETER-KIND-COLLISION" =>
      "Use distinct names for integer and Boolean public parameters."
    case "MORPH-FRONTEND-PARAMETER-KIND-MISMATCH" =>
      "Declare and use the parameter through one consistent symbolic type."
    case "MORPH-FRONTEND-NOT-A-LOCAL-PARAMETER" =>
      "Pass the exact HdlInt returned by localParam to integerLocalParameter."
    case "MORPH-FRONTEND-DIVISOR-WITNESS-ZERO" =>
      "Choose a non-zero concrete divisor witness; ParamRTL will also prove its full domain excludes zero."
    case "MORPH-FRONTEND-INVALID-LOCAL-PARAMETER-NAME" =>
      "Use a local-parameter identifier matching `[A-Za-z_][A-Za-z0-9_]*`."
    case "MORPH-FRONTEND-LOCAL-PARAMETER-IDENTITY-UNRESOLVED" =>
      "Create the declaration with integerLocalParameter from the exact localParam handle."
    case "MORPH-FRONTEND-LOCAL-PARAMETER-TOKEN-MISMATCH" =>
      "Reuse the exact localParam handle declared by this module."
    case "MORPH-FRONTEND-LOCAL-PARAMETER-NOT-DECLARED" =>
      "Add integerLocalParameter for every referenced localParam handle to this module."
    case "MORPH-FRONTEND-LOCAL-PARAMETER-NAME-DUPLICATE" =>
      "Give every local parameter in a module a unique name and declare each handle once."
    case "MORPH-FRONTEND-LOCAL-PARAMETER-DECLARATION-DUPLICATE" =>
      "Pass each integerLocalParameter declaration to moduleDef exactly once."
    case "MORPH-FRONTEND-LOCAL-PARAMETER-NAME-COLLISION" =>
      "Use distinct names for public and module-local parameters."
    case "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN" =>
      "Create a fresh localParam handle for each module definition."
    case "MORPH-FRONTEND-LOCAL-PARAMETER-CYCLE" =>
      "Define local parameters from previously created handles without a dependency cycle."
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
