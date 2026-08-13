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
      "Flatten the structural regions and emit one top-level generate loop or conditional region."
    case "MORPH-FRONTEND-COMBINATIONAL-PROCESS-NESTED" =>
      "Emit the combinational process as a top-level module item outside all generate regions."
    case "MORPH-FRONTEND-COMBINATIONAL-PROCESS-MULTIPLE" =>
      "Emit one combinational process per module-item capture."
    case "MORPH-FRONTEND-COMBINATIONAL-PROCESS-MIXED" =>
      "Use a separate module definition instead of mixing this process with a generate region."
    case "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-NESTED" =>
      "Emit the synchronous register as a top-level module item outside all generate regions."
    case "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-MULTIPLE" =>
      "Emit one synchronous register process per module-item capture."
    case "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-MIXED" |
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-MIXED" |
        "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-MIXED" |
        "MORPH-FRONTEND-RUNTIME-PROCESS-MIXED" =>
      "Use a separate module definition instead of mixing runtime processes or module items."
    case "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-LABEL-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-CLOCK-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-RESET-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-TARGET-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-VALUE-INVALID" =>
      "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`."
    case "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-CLOCK-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-CLOCK-NOT-REF" =>
      "Pass a non-null ref(name) clock to emitSynchronousRegister."
    case "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-RESET-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-RESET-NOT-REF" =>
      "Pass a non-null ref(name) reset to emitSynchronousRegister."
    case "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-ASSIGNMENT-NULL" =>
      "Pass one proceduralAssign(target, ref(data)) to emitSynchronousRegister."
    case "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-NESTED" =>
      "Emit the asynchronous-reset register as a top-level module item outside all generate regions."
    case "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-MULTIPLE" =>
      "Emit one asynchronous-reset register process per module-item capture."
    case "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-LABEL-INVALID" |
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-CLOCK-INVALID" |
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-RESET-INVALID" |
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-TARGET-INVALID" |
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-VALUE-INVALID" =>
      "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`."
    case "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-CLOCK-NULL" |
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-CLOCK-NOT-REF" =>
      "Pass a non-null ref(name) clock to emitAsynchronousRegister."
    case "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-RESET-NULL" |
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-RESET-NOT-REF" =>
      "Pass a non-null ref(name) reset to emitAsynchronousRegister."
    case "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-ASSIGNMENT-NULL" =>
      "Pass one proceduralAssign(target, ref(data)) to emitAsynchronousRegister."
    case "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-NESTED" =>
      "Emit the synchronous enabled register as a top-level module item outside all generate regions."
    case "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-MULTIPLE" =>
      "Emit one synchronous enabled-register process per module-item capture."
    case "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-LABEL-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-CLOCK-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-RESET-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-ENABLE-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-TARGET-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-VALUE-INVALID" =>
      "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`."
    case "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-CLOCK-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-CLOCK-NOT-REF" =>
      "Pass a non-null ref(name) clock to emitSynchronousEnabledRegister."
    case "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-RESET-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-RESET-NOT-REF" =>
      "Pass a non-null ref(name) reset to emitSynchronousEnabledRegister."
    case "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-ENABLE-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-ENABLE-NOT-REF" =>
      "Pass a non-null ref(name) enable to emitSynchronousEnabledRegister."
    case "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-ASSIGNMENT-NULL" =>
      "Pass one proceduralAssign(target, ref(data)) to emitSynchronousEnabledRegister."
    case "MORPH-FRONTEND-COMBINATIONAL-LABEL-INVALID" |
        "MORPH-FRONTEND-COMBINATIONAL-TARGET-INVALID" |
        "MORPH-FRONTEND-COMBINATIONAL-VALUE-INVALID" |
        "MORPH-FRONTEND-COMBINATIONAL-CONDITION-INVALID" =>
      "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`."
    case "MORPH-FRONTEND-COMBINATIONAL-VALUE-NULL" |
        "MORPH-FRONTEND-COMBINATIONAL-VALUE-NOT-REF" =>
      "Pass a non-null ref(name) value to proceduralAssign."
    case "MORPH-FRONTEND-COMBINATIONAL-CONDITION-NULL" |
        "MORPH-FRONTEND-COMBINATIONAL-CONDITION-NOT-REF" =>
      "Pass a non-null ref(name) condition to emitCombinationalIf."
    case "MORPH-FRONTEND-COMBINATIONAL-BRANCH-NULL" |
        "MORPH-FRONTEND-COMBINATIONAL-ASSIGNMENT-NULL" =>
      "Pass non-null branch vectors containing only proceduralAssign values."
    case "MORPH-FRONTEND-GENERATE-IF-OTHERWISE-MISSING" =>
      "Complete every generateIf with exactly one otherwise branch in the same capture."
    case "MORPH-FRONTEND-GENERATE-IF-OTHERWISE-DUPLICATE" =>
      "Supply one otherwise branch for each generateIf."
    case "MORPH-FRONTEND-GENERATE-IF-ESCAPED" =>
      "Complete the generateIf in the same frontend capture where it was created."
    case "MORPH-FRONTEND-GENERATE-IF-MULTIPLE" =>
      "Use one top-level generateIf per module-item capture."
    case "MORPH-FRONTEND-GENERATE-CASE-DEFAULT-MISSING" =>
      "Complete every generateCase with exactly one default branch in the same capture."
    case "MORPH-FRONTEND-GENERATE-CASE-DEFAULT-DUPLICATE" =>
      "Supply one default branch for each generateCase."
    case "MORPH-FRONTEND-GENERATE-CASE-ESCAPED" =>
      "Complete the generateCase in the same frontend capture where it was created."
    case "MORPH-FRONTEND-GENERATE-CASE-MULTIPLE" =>
      "Use one top-level generateIf or generateCase per module-item capture."
    case "MORPH-FRONTEND-GENERATE-CASE-SELECTOR-NULL" =>
      "Pass an HdlInt literal, expression, public parameter or local parameter to generateCase."
    case "MORPH-FRONTEND-GENERATE-CASE-CHOICE-NULL" =>
      "Pass a non-null BigInt literal to generateCase.choice."
    case "MORPH-FRONTEND-GENERATE-CASE-CHOICE-DUPLICATE" =>
      "Give every generateCase choice a unique integer literal value."
    case "MORPH-FRONTEND-GENERATE-CASE-CHOICE-MISSING" =>
      "Add at least one literal choice before the mandatory default branch."
    case "MORPH-FRONTEND-GENERATE-CASE-COMPLETED" =>
      "Add all literal choices before completing generateCase with its default branch."
    case "MORPH-FRONTEND-BOOLEAN-CONDITION-NULL" =>
      "Pass an HdlBool literal, expression or public parameter to generateIf."
    case "MORPH-FRONTEND-BOOLEAN-PARAMETER-BINDING-NULL" =>
      "Pass an HdlBool literal, expression or public parameter to parameterBinding."
    case "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-NULL" =>
      "Pass an HdlBool literal, expression, public parameter or Boolean local to localParam."
    case "MORPH-FRONTEND-INTEGER-SELECT-BRANCH-NULL" =>
      "Pass an HdlInt expression or Int literal for both integer selection branches."
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
    case "MORPH-FRONTEND-NOT-A-BOOLEAN-LOCAL-PARAMETER" =>
      "Pass the exact HdlBool returned by localParam to booleanLocalParameter."
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
    case "MORPH-FRONTEND-LOCAL-PARAMETER-KIND-COLLISION" =>
      "Use distinct names for integer and Boolean local parameters."
    case "MORPH-FRONTEND-LOCAL-PARAMETER-KIND-MISMATCH" =>
      "Declare and use a local parameter through one consistent symbolic type."
    case "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-IDENTITY-UNRESOLVED" =>
      "Create the declaration with booleanLocalParameter from the exact localParam handle."
    case "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-TOKEN-MISMATCH" =>
      "Reuse the exact Boolean localParam handle declared by this module."
    case "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-NOT-DECLARED" =>
      "Add booleanLocalParameter for every referenced Boolean localParam handle."
    case "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-NAME-DUPLICATE" =>
      "Give every Boolean local parameter in a module a unique name."
    case "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-DECLARATION-DUPLICATE" =>
      "Pass each booleanLocalParameter declaration to moduleDef exactly once."
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
      "Use unique stable labels for generate branches and .named(label = ..., index = ...) loops."
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
