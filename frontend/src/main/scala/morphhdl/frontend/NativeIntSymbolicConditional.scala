package morphhdl.frontend

import spinal.core.ParameterizedStructure

/** Compiler-plugin bridge for ordinary Scala `if` syntax whose Boolean
  * predicate is proven to originate from an Increment 50 shadow-native `Int`
  * expression.
  *
  * The ordinary Scala Boolean remains the concrete SpinalHDL witness. During
  * MorphVerilog capture, the exact predicate reference is resolved in the
  * active Increment 47 boundary and both source alternatives are retained as
  * structural Verilog-2001 regions.
  */
object NativeIntSymbolicConditional {
  private val ElseIfMarkerPrefix = "morphhdl_else_if_"
  private val activeCaptureDepth = new ThreadLocal[java.lang.Integer]
  private val MaximumCaptureDepth = 64

  def selectSymbolic[T](
      condition: Boolean,
      predicateReference: String,
      sourceFile: String,
      sourceLine: Int
  )(ifTrue: => T)(ifFalse: => T): T = {
    val origin = SourceOrigin(sourceFile, sourceLine)
    if (!ParameterizedStructure.captureEnabled) {
      if (condition) ifTrue else ifFalse
    } else
      withCapture(origin) {
        captureOne(
          condition,
          predicateReference,
          origin,
          origin,
          None,
          () => ifTrue,
          () => ifFalse
        )
      }
  }

  /** Retain one source-ordered `if / else if / ... / else` chain. Consecutive
    * proven native predicates become one recursively nested structural region;
    * an ordinary Scala conditional in the final else body remains concrete.
    */
  def selectSymbolicChain[T](
      alternatives: Seq[(() => Boolean, String, () => T, String, Int)],
      otherwise: () => T,
      otherwiseFile: String,
      otherwiseLine: Int
  ): T = {
    val ordered = alternatives.toVector
    val defaultOrigin = SourceOrigin(otherwiseFile, otherwiseLine)
    if (ordered.isEmpty) {
      FrontendException.failAt(
        "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-CHAIN-EMPTY",
        "a native symbolic else-if chain requires at least one proven predicate",
        defaultOrigin
      )
    }

    if (!ParameterizedStructure.captureEnabled) {
      def select(index: Int): T = {
        val (condition, _, body, _, _) = ordered(index)
        if (condition()) body()
        else if (index + 1 < ordered.size) select(index + 1)
        else otherwise()
      }
      select(0)
    } else
      withCapture(SourceOrigin(ordered.head._4, ordered.head._5)) {
        def capture(index: Int): T = {
          val (conditionThunk, reference, body, file, line) = ordered(index)
          val origin = SourceOrigin(file, line)
          val continuation = index + 1 < ordered.size
          val falseOrigin =
            if (continuation) {
              val (_, _, _, nextFile, nextLine) = ordered(index + 1)
              SourceOrigin(nextFile, nextLine)
            } else defaultOrigin
          val falseBody = () => {
            if (continuation) capture(index + 1)
            else otherwise()
          }
          val names =
            if (continuation) Some(elseIfContinuationNames(origin))
            else None
          captureOne(
            conditionThunk(),
            reference,
            origin,
            falseOrigin,
            names,
            body,
            falseBody
          )
        }
        capture(0)
      }
  }

  private def captureOne[T](
      condition: Boolean,
      predicateReference: String,
      origin: SourceOrigin,
      falseOrigin: SourceOrigin,
      names: Option[GenerateIfNames],
      ifTrue: () => T,
      ifFalse: () => T
  ): T = {
    var trueValue: Option[T] = None
    var falseValue: Option[T] = None
    val builder = NativeStructuralFrontend.startGenerateIfExpression(
      condition,
      predicateReference,
      names = names,
      whenTrue = { trueValue = Some(ifTrue()); () },
      origin = origin
    )
    builder.nativeToken.otherwise(
      { falseValue = Some(ifFalse()); () },
      falseOrigin
    )
    if (condition) trueValue.get else falseValue.get
  }

  private def elseIfContinuationNames(origin: SourceOrigin): GenerateIfNames = {
    val generated = NativeStructuralFrontend.generatedIfNames(origin)
    GenerateIfNames(
      generated.whenTrue,
      ElseIfMarkerPrefix + generated.whenFalse
    )
  }

  /** Guard one compiler-classified Scala side effect. Ordinary concrete
    * SpinalHDL keeps its source behavior. MorphVerilog rejects the effect
    * before evaluating the retained alternative, so rejected capture cannot
    * mutate external state or perform I/O while discovering source branches.
    */
  def guardAlternative[T](
      code: String,
      detail: String,
      sourceFile: String,
      sourceLine: Int
  )(body: => T): T = {
    if (ParameterizedStructure.captureEnabled) {
      FrontendException.failAt(
        code,
        detail,
        SourceOrigin(sourceFile, sourceLine)
      )
    }
    body
  }

  private def withCapture[T](origin: SourceOrigin)(body: => T): T = {
    val current = activeCaptureDepth.get()
    val previous = if (current == null) 0 else current.intValue()
    if (previous >= MaximumCaptureDepth) {
      FrontendException.failAt(
        "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-DEPTH-EXCEEDED",
        s"native symbolic control-flow nesting exceeds the bounded depth $MaximumCaptureDepth",
        origin
      )
    }
    activeCaptureDepth.set(java.lang.Integer.valueOf(previous + 1))
    try body
    finally {
      if (previous == 0) activeCaptureDepth.remove()
      else activeCaptureDepth.set(java.lang.Integer.valueOf(previous))
    }
  }
}
