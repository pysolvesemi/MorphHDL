package morphhdl.frontend

import spinal.core.{ExternalNativeIntShadowRegistry, ParameterizedStructure}

/**
  * Compiler-plugin bridge for ordinary Scala `if` syntax whose Boolean
  * predicate is proven to originate from a shadow-native `Int` expression.
  *
  * The ordinary Scala Boolean remains the concrete SpinalHDL witness. During
  * MorphVerilog capture, the exact predicate reference is resolved in the
  * active formalization boundary and every source alternative is retained as a
  * structural Verilog-2001 region. Increment 52 permits recursive capture and
  * provides runtime guards for Scala effects that are unsafe when every source
  * alternative is elaborated.
  */
object NativeIntSymbolicConditional {
  private final case class CaptureFrame(origin: SourceOrigin)

  private val activeCaptures = new ThreadLocal[List[CaptureFrame]]

  def selectSymbolic[T](
      condition: Boolean,
      predicateReference: String,
      sourceFile: String,
      sourceLine: Int
  )(ifTrue: => T)(ifFalse: => T): T = {
    val origin = SourceOrigin(sourceFile, sourceLine)
    if (!ParameterizedStructure.captureEnabled) {
      if (condition) ifTrue else ifFalse
    } else withCapture(origin) {
      captureOne(
        condition,
        predicateReference,
        origin,
        origin,
        () => ifTrue,
        () => ifFalse
      )
    }
  }

  /**
    * Retain one source-ordered `if / else if / ... / else` chain. Consecutive
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
    } else withCapture(SourceOrigin(ordered.head._4, ordered.head._5)) {
      def capture(index: Int): T = {
        val (conditionThunk, reference, body, file, line) = ordered(index)
        val origin = SourceOrigin(file, line)
        val falseOrigin =
          if (index + 1 < ordered.size) {
            val (_, _, _, nextFile, nextLine) = ordered(index + 1)
            SourceOrigin(nextFile, nextLine)
          } else defaultOrigin
        val falseBody = () => {
          if (index + 1 < ordered.size) capture(index + 1)
          else otherwise()
        }
        captureOne(
          conditionThunk(),
          reference,
          origin,
          falseOrigin,
          body,
          falseBody
        )
      }
      capture(0)
    }
  }

  /**
    * Preserve the ordinary Scala behavior outside structural capture, but fail
    * before an unsafe effect executes while MorphVerilog is elaborating every
    * source alternative. The compiler inserts this guard only inside a proven
    * native symbolic alternative.
    */
  def rejectEffect[T](
      code: String,
      detail: String,
      sourceFile: String,
      sourceLine: Int
  )(nativeValue: => T): T = {
    if (ParameterizedStructure.captureEnabled && captureActive) {
      FrontendException.failAt(
        code,
        detail,
        SourceOrigin(sourceFile, sourceLine)
      )
    }
    nativeValue
  }

  private def captureOne[T](
      condition: Boolean,
      predicateReference: String,
      origin: SourceOrigin,
      falseOrigin: SourceOrigin,
      ifTrue: () => T,
      ifFalse: () => T
  ): T = {
    val expression = ExternalNativeIntShadowRegistry.definitionPredicateTracked(
      predicateReference,
      condition,
      origin.rendered
    )
    var trueValue: Option[T] = None
    var falseValue: Option[T] = None
    val builder = NativeStructuralFrontend.startGenerateIfExpression(
      condition,
      expression,
      names = None,
      whenTrue = { trueValue = Some(ifTrue()); () },
      origin = origin
    )
    builder.nativeToken.otherwise(
      { falseValue = Some(ifFalse()); () },
      falseOrigin
    )
    if (condition) trueValue.get else falseValue.get
  }

  private def captureActive: Boolean =
    Option(activeCaptures.get()).exists(_.nonEmpty)

  private def withCapture[T](origin: SourceOrigin)(body: => T): T = {
    val previous = Option(activeCaptures.get()).getOrElse(Nil)
    activeCaptures.set(CaptureFrame(origin) :: previous)
    try body
    finally {
      if (previous.isEmpty) activeCaptures.remove()
      else activeCaptures.set(previous)
    }
  }
}
