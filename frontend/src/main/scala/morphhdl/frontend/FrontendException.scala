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
    case "MORPH-FRONTEND-ADDRESS-WIDTH-WITNESS-NONPOSITIVE" =>
      "Choose a positive concrete witness and declare the full symbolic input domain as strictly positive before using addressWidth."
    case "MORPH-FRONTEND-CEIL-LOG2-WITNESS-NONPOSITIVE" =>
      "Choose a positive concrete witness and declare the full symbolic input domain as strictly positive before using ceilLog2."
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
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-MIXED" |
        "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-MIXED" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-MIXED" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-MIXED" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-MIXED" |
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
    case "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-NESTED" =>
      "Emit the asynchronous-reset enabled register as a top-level module item outside all generate regions."
    case "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-MULTIPLE" =>
      "Emit one asynchronous-reset enabled-register process per module-item capture."
    case "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-LABEL-INVALID" |
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-CLOCK-INVALID" |
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-RESET-INVALID" |
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-ENABLE-INVALID" |
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-TARGET-INVALID" |
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-VALUE-INVALID" =>
      "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`."
    case "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-CLOCK-NULL" |
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-CLOCK-NOT-REF" =>
      "Pass a non-null ref(name) clock to emitAsynchronousEnabledRegister."
    case "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-RESET-NULL" |
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-RESET-NOT-REF" =>
      "Pass a non-null ref(name) reset to emitAsynchronousEnabledRegister."
    case "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-ENABLE-NULL" |
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-ENABLE-NOT-REF" =>
      "Pass a non-null ref(name) enable to emitAsynchronousEnabledRegister."
    case "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-ASSIGNMENT-NULL" =>
      "Pass one proceduralAssign(target, ref(data)) to emitAsynchronousEnabledRegister."
    case "MORPH-FRONTEND-SINGLE-PORT-MEMORY-NESTED" =>
      "Emit the synchronous read-first single-port memory as a top-level module item outside all generate regions."
    case "MORPH-FRONTEND-SINGLE-PORT-MEMORY-MULTIPLE" =>
      "Emit one synchronous read-first single-port memory per module-item capture."
    case "MORPH-FRONTEND-SINGLE-PORT-MEMORY-MIXED" =>
      "Use a separate module definition instead of mixing the memory with another module item or generate region."
    case "MORPH-FRONTEND-SINGLE-PORT-MEMORY-LABEL-INVALID" |
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-NAME-INVALID" |
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-CLOCK-INVALID" |
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-ENABLE-INVALID" |
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-ENABLE-INVALID" |
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ADDRESS-INVALID" |
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-DATA-INVALID" |
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-DATA-INVALID" =>
      "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`."
    case "MORPH-FRONTEND-SINGLE-PORT-MEMORY-CLOCK-NULL" |
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-CLOCK-NOT-REF" =>
      "Pass a non-null ref(name) clock to emitSynchronousReadFirstSinglePortMemory."
    case "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-ENABLE-NULL" |
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-ENABLE-NOT-REF" =>
      "Pass a non-null ref(name) read enable to emitSynchronousReadFirstSinglePortMemory."
    case "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-ENABLE-NULL" |
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-ENABLE-NOT-REF" =>
      "Pass a non-null ref(name) write enable to emitSynchronousReadFirstSinglePortMemory."
    case "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ADDRESS-NULL" |
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ADDRESS-NOT-REF" =>
      "Pass a non-null ref(name) address to emitSynchronousReadFirstSinglePortMemory."
    case "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-DATA-NULL" |
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-DATA-NOT-REF" =>
      "Pass a non-null ref(name) write data value to emitSynchronousReadFirstSinglePortMemory."
    case "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-DATA-NULL" |
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-DATA-NOT-REF" =>
      "Pass a non-null ref(name) read data target to emitSynchronousReadFirstSinglePortMemory."
    case "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ELEMENT-TYPE-NULL" =>
      "Pass a non-null packedBits(width) element type to emitSynchronousReadFirstSinglePortMemory."
    case "MORPH-FRONTEND-SINGLE-PORT-MEMORY-DEPTH-NULL" =>
      "Pass a non-null loop-invariant HdlInt depth to emitSynchronousReadFirstSinglePortMemory."
    case "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-NESTED" =>
      "Emit the synchronous counter as a top-level module item outside all generate regions."
    case "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-MULTIPLE" =>
      "Emit one synchronous counter process per module-item capture."
    case "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-LABEL-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-CLOCK-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-RESET-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-ENABLE-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-COUNT-INVALID" =>
      "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`."
    case "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-CLOCK-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-CLOCK-NOT-REF" =>
      "Pass a non-null ref(name) clock to emitSynchronousCounter."
    case "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-RESET-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-RESET-NOT-REF" =>
      "Pass a non-null ref(name) reset to emitSynchronousCounter."
    case "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-ENABLE-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-ENABLE-NOT-REF" =>
      "Pass a non-null ref(name) enable to emitSynchronousCounter."
    case "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-COUNT-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-COUNT-NOT-REF" =>
      "Pass a non-null ref(name) count output to emitSynchronousCounter."
    case "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-LIMIT-NULL" =>
      "Pass the exact non-null HdlInt.param limit handle to emitSynchronousCounter."
    case "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-LIMIT-NOT-PUBLIC-PARAMETER" =>
      "Pass the exact unmodified HdlInt.param handle declared by this counter module."
    case "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-LIMIT-WITNESS-NONPOSITIVE" =>
      "Choose a positive limit witness and declare its full finite domain with a minimum of at least 1."
    case "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-NESTED" =>
      "Emit the synchronous read-first simple dual-port memory as a top-level module item outside all generate regions."
    case "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-MULTIPLE" =>
      "Emit one synchronous read-first simple dual-port memory per module-item capture."
    case "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-LABEL-INVALID" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-NAME-INVALID" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-CLOCK-INVALID" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-ENABLE-INVALID" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-ENABLE-INVALID" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-ADDRESS-INVALID" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-ADDRESS-INVALID" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-DATA-INVALID" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-DATA-INVALID" =>
      "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`."
    case "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-CLOCK-NULL" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-CLOCK-NOT-REF" =>
      "Pass a non-null ref(name) clock to emitSynchronousReadFirstSimpleDualPortMemory."
    case "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-ENABLE-NULL" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-ENABLE-NOT-REF" =>
      "Pass a non-null ref(name) read enable to emitSynchronousReadFirstSimpleDualPortMemory."
    case "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-ENABLE-NULL" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-ENABLE-NOT-REF" =>
      "Pass a non-null ref(name) write enable to emitSynchronousReadFirstSimpleDualPortMemory."
    case "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-ADDRESS-NULL" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-ADDRESS-NOT-REF" =>
      "Pass a non-null ref(name) read address to emitSynchronousReadFirstSimpleDualPortMemory."
    case "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-ADDRESS-NULL" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-ADDRESS-NOT-REF" =>
      "Pass a non-null ref(name) write address to emitSynchronousReadFirstSimpleDualPortMemory."
    case "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-DATA-NULL" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-DATA-NOT-REF" =>
      "Pass a non-null ref(name) write data value to emitSynchronousReadFirstSimpleDualPortMemory."
    case "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-DATA-NULL" |
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-DATA-NOT-REF" =>
      "Pass a non-null ref(name) read data target to emitSynchronousReadFirstSimpleDualPortMemory."
    case "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-ELEMENT-TYPE-NULL" =>
      "Pass a non-null packedBits(width) element type to emitSynchronousReadFirstSimpleDualPortMemory."
    case "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-DEPTH-NULL" =>
      "Pass a non-null loop-invariant HdlInt depth to emitSynchronousReadFirstSimpleDualPortMemory."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-NESTED" =>
      "Emit the synchronous stream FIFO as a top-level module item outside all generate regions."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-MULTIPLE" =>
      "Emit one synchronous stream FIFO per module-item capture."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-LABEL-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-NAME-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-CLOCK-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-RESET-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-PUSH-VALID-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-PUSH-READY-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-PUSH-DATA-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-POP-VALID-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-POP-READY-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-POP-DATA-INVALID" =>
      "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-CLOCK-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-CLOCK-NOT-REF" =>
      "Pass a non-null ref(name) clock to emitSynchronousStreamFifo."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-RESET-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-RESET-NOT-REF" =>
      "Pass a non-null ref(name) reset to emitSynchronousStreamFifo."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-PUSH-VALID-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-PUSH-VALID-NOT-REF" =>
      "Pass a non-null ref(name) push-valid input to emitSynchronousStreamFifo."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-PUSH-READY-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-PUSH-READY-NOT-REF" =>
      "Pass a non-null ref(name) push-ready output to emitSynchronousStreamFifo."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-PUSH-DATA-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-PUSH-DATA-NOT-REF" =>
      "Pass a non-null ref(name) push-data input to emitSynchronousStreamFifo."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-POP-VALID-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-POP-VALID-NOT-REF" =>
      "Pass a non-null ref(name) pop-valid output to emitSynchronousStreamFifo."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-POP-READY-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-POP-READY-NOT-REF" =>
      "Pass a non-null ref(name) pop-ready input to emitSynchronousStreamFifo."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-POP-DATA-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-POP-DATA-NOT-REF" =>
      "Pass a non-null ref(name) pop-data output to emitSynchronousStreamFifo."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-ELEMENT-TYPE-NULL" =>
      "Pass a non-null packedBits(width) element type to emitSynchronousStreamFifo."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-DEPTH-NULL" =>
      "Pass the exact unmodified non-null HdlInt.param handle declared by this FIFO module."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-DEPTH-NOT-PUBLIC-PARAMETER" =>
      "Pass the exact unmodified HdlInt.param handle declared by this FIFO module."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO-DEPTH-WITNESS-NONPOSITIVE" =>
      "Choose a positive depth witness and declare its finite domain with a minimum of at least 1."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-NESTED" =>
      "Emit the synchronous stream m2s pipe as a top-level module item outside all generate regions."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-MULTIPLE" =>
      "Emit one synchronous stream m2s pipe per module-item capture."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-LABEL-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-CLOCK-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-RESET-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-PUSH-VALID-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-PUSH-READY-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-PUSH-DATA-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-POP-VALID-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-POP-READY-INVALID" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-POP-DATA-INVALID" =>
      "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-CLOCK-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-CLOCK-NOT-REF" =>
      "Pass a non-null ref(name) clock to emitSynchronousStreamM2sPipe."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-RESET-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-RESET-NOT-REF" =>
      "Pass a non-null ref(name) reset to emitSynchronousStreamM2sPipe."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-PUSH-VALID-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-PUSH-VALID-NOT-REF" =>
      "Pass a non-null ref(name) push-valid input to emitSynchronousStreamM2sPipe."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-PUSH-READY-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-PUSH-READY-NOT-REF" =>
      "Pass a non-null ref(name) push-ready output to emitSynchronousStreamM2sPipe."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-PUSH-DATA-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-PUSH-DATA-NOT-REF" =>
      "Pass a non-null ref(name) push-data input to emitSynchronousStreamM2sPipe."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-POP-VALID-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-POP-VALID-NOT-REF" =>
      "Pass a non-null ref(name) pop-valid output to emitSynchronousStreamM2sPipe."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-POP-READY-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-POP-READY-NOT-REF" =>
      "Pass a non-null ref(name) pop-ready input to emitSynchronousStreamM2sPipe."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-POP-DATA-NULL" |
        "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-POP-DATA-NOT-REF" =>
      "Pass a non-null ref(name) pop-data output to emitSynchronousStreamM2sPipe."
    case "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE-ELEMENT-TYPE-NULL" =>
      "Pass a non-null packedBits(width) element type to emitSynchronousStreamM2sPipe."
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
