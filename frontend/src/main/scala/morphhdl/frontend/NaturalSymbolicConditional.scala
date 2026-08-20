package morphhdl.frontend

/** Typed bridge introduced by the MorphHDL compiler plugin for natural Scala `if` syntax. */
object NaturalSymbolicConditional {
  /**
    * Compiler-plugin entry point for an explicitly proven symbolic condition.
    * Ordinary Scala Boolean conditionals are deliberately left as raw Scala `if`
    * expressions by the compiler plugin and never pass through this method.
    */
  def selectSymbolic[T](
      condition: HdlBool,
      sourceFile: String,
      sourceLine: Int
  )(ifTrue: => T)(ifFalse: => T): T = {
    val origin = SourceOrigin(sourceFile, sourceLine)
    if (spinal.core.ParameterizedStructure.captureEnabled) {
      var trueValue: Option[T] = None
      var falseValue: Option[T] = None
      NativeStructuralFrontend
        .startGenerateIf(condition, None, { trueValue = Some(ifTrue); () }, origin)
        .otherwise({ falseValue = Some(ifFalse); () }, origin)
      if (condition.witness) trueValue.get
      else falseValue.get
    } else if (condition.witness) ifTrue
    else ifFalse
  }
}
