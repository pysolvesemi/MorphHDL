package morphhdl.frontend

import spinal.core.ExternalNativeIntShadowRegistry

/** Runtime hooks used by MorphHDL's native-Int provenance instrumentation. */
object NativeIntShadow {
  private def rendered(file: String, line: Int): String =
    SourceOrigin(file, line).rendered

  /** Source-aware convenience form used by explicitly selected native code. */
  def captureArgument(value: Int, name: String)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): Int = captureArgument(value, name, file.value, line.value)

  /**
    * Compiler/runtime hook for a selected constructor argument. Outside a
    * formalization boundary it is an intentional no-op so ordinary native code
    * keeps exactly the same runtime `Int` behavior.
    */
  def captureArgument(
      value: Int,
      name: String,
      file: String,
      line: Int
  ): Int =
    ExternalNativeIntShadowRegistry.captureArgument(
      value,
      name,
      rendered(file, line)
    )

  /** Source-aware convenience form for an explicitly selected local alias. */
  def captureLocal(value: Int, name: String)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): Int = captureLocal(value, name, file.value, line.value)

  /** Compiler/runtime hook for an explicitly selected direct local alias. */
  def captureLocal(
      value: Int,
      name: String,
      file: String,
      line: Int
  ): Int =
    ExternalNativeIntShadowRegistry.captureLocal(
      value,
      name,
      rendered(file, line),
      requireBoundary = false
    )

  /** Compiler-inserted exact-source root selection. */
  def compilerTrackArgument(
      value: Int,
      name: String,
      reference: String,
      file: String,
      line: Int
  ): Int =
    ExternalNativeIntShadowRegistry.captureArgumentTracked(
      value,
      name,
      reference,
      rendered(file, line)
    )

  /** Compiler-inserted exact-source selection of a direct or derived local. */
  def compilerTrackLocal(
      value: Int,
      name: String,
      sourceReference: String,
      resultReference: String,
      file: String,
      line: Int
  ): Int =
    ExternalNativeIntShadowRegistry.captureLocalTracked(
      value,
      name,
      sourceReference,
      resultReference,
      rendered(file, line),
      requireBoundary = true
    )

  /** Compiler-inserted immutable alias propagation. */
  def compilerAlias(
      value: Int,
      name: String,
      sourceReference: String,
      resultReference: String,
      file: String,
      line: Int
  ): Int =
    ExternalNativeIntShadowRegistry.aliasTracked(
      value,
      name,
      sourceReference,
      resultReference,
      rendered(file, line)
    )

  /** Compiler-inserted bounded binary native-Int expression. */
  def compilerBinary(
      operation: String,
      left: Int,
      leftReference: String,
      leftLiteral: Boolean,
      right: Int,
      rightReference: String,
      rightLiteral: Boolean,
      resultReference: String,
      name: String,
      file: String,
      line: Int
  ): Int =
    ExternalNativeIntShadowRegistry.binaryTracked(
      operation,
      left,
      leftReference,
      leftLiteral,
      right,
      rightReference,
      rightLiteral,
      resultReference,
      name,
      rendered(file, line)
    )

  /** Compiler-inserted bounded unary/helper native-Int expression. */
  def compilerUnary(
      operation: String,
      value: Int,
      valueReference: String,
      resultReference: String,
      name: String,
      file: String,
      line: Int
  ): Int =
    ExternalNativeIntShadowRegistry.unaryTracked(
      operation,
      value,
      valueReference,
      resultReference,
      name,
      rendered(file, line)
    )

  /** Compiler-inserted native-Int comparison. */
  def compilerComparison(
      operation: String,
      left: Int,
      leftReference: String,
      leftLiteral: Boolean,
      right: Int,
      rightReference: String,
      rightLiteral: Boolean,
      resultReference: String,
      name: String,
      file: String,
      line: Int
  ): Boolean =
    ExternalNativeIntShadowRegistry.comparisonTracked(
      operation,
      left,
      leftReference,
      leftLiteral,
      right,
      rightReference,
      rightLiteral,
      resultReference,
      name,
      rendered(file, line)
    )

  /** Compiler-inserted power-of-two predicate. */
  def compilerPowerOfTwo(
      value: Int,
      valueReference: String,
      resultReference: String,
      name: String,
      file: String,
      line: Int
  ): Boolean =
    ExternalNativeIntShadowRegistry.powerOfTwoTracked(
      value,
      valueReference,
      resultReference,
      name,
      rendered(file, line)
    )

  /** Fail closed only when the referenced Int is live in an active boundary. */
  def compilerUnsupportedInt(
      reference: String,
      code: String,
      detail: String,
      file: String,
      line: Int
  )(nativeValue: => Int): Int = {
    ExternalNativeIntShadowRegistry.rejectTracked(
      reference,
      code,
      detail,
      rendered(file, line)
    )
    nativeValue
  }

  /** Fail closed only when the referenced predicate is live in an active boundary. */
  def compilerUnsupportedBoolean(
      reference: String,
      code: String,
      detail: String,
      file: String,
      line: Int
  )(nativeValue: => Boolean): Boolean = {
    ExternalNativeIntShadowRegistry.rejectTracked(
      reference,
      code,
      detail,
      rendered(file, line)
    )
    nativeValue
  }

  /** Reject boxing or generic-container escape of proven shadow provenance. */
  def compilerBoxing[A](
      reference: String,
      detail: String,
      file: String,
      line: Int
  )(nativeValue: => A): A = {
    ExternalNativeIntShadowRegistry.rejectTracked(
      reference,
      "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-BOXING-UNSUPPORTED",
      detail,
      rendered(file, line)
    )
    nativeValue
  }

  /** Reject mutation or assignment escape of proven shadow provenance. */
  def compilerMutableInt(
      reference: String,
      detail: String,
      file: String,
      line: Int
  )(nativeValue: => Int): Int = {
    ExternalNativeIntShadowRegistry.rejectTracked(
      reference,
      "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-MUTABLE-ESCAPE",
      detail,
      rendered(file, line)
    )
    nativeValue
  }
}

/**
  * Explicit selection of one native `Int` local at a formalization boundary.
  * The returned Scala value is bit-for-bit identical to the input. Increment
  * 49 accepts direct aliases, while Increment 50 allows compiler-proven derived
  * expressions rooted at the same exact boundary.
  */
object shadowInt {
  def apply(value: Int, name: String)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): Int =
    ExternalNativeIntShadowRegistry.captureLocal(
      value,
      name,
      SourceOrigin.capture.rendered,
      requireBoundary = true
    )
}
