package morphhdl.frontend

import spinal.core.ExternalNativeIntShadowRegistry

/** Runtime hooks used by MorphHDL's native-Int provenance instrumentation. */
object NativeIntShadow {
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
      SourceOrigin(file, line).rendered
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
      SourceOrigin(file, line).rendered,
      requireBoundary = false
    )
}

/**
  * Explicit selection of one native `Int` local at a formalization boundary.
  * The returned Scala value is bit-for-bit identical to the input. Increment
  * 49 accepts only direct aliases of the boundary witness; arithmetic remains
  * deliberately fail-closed until Increment 50.
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
