package morphhdl.frontend

import spinal.core.ParameterizedStructure

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
    if (ParameterizedStructure.captureEnabled) {
      var trueValue: Option[T] = None
      var falseValue: Option[T] = None
      val builder = NativeStructuralFrontend.startGenerateIf(
        condition,
        None,
        { trueValue = Some(ifTrue); () },
        origin
      )
      builder.nativeToken.otherwise({ falseValue = Some(ifFalse); () }, origin)
      if (condition.witness) trueValue.get
      else falseValue.get
    } else if (condition.witness) ifTrue
    else ifFalse
  }

  /**
    * Lowers a source-level symbolic `if / else if / ... / else` chain as
    * one recursively nested structural region. The Verilog backend may
    * therefore emit the original source-order `else if` syntax directly,
    * without sibling generate regions or accumulated dominance guards.
    */
  def selectSymbolicChain[T](
      alternatives: Seq[(HdlBool, () => T, String, Int)],
      otherwise: () => T,
      otherwiseFile: String,
      otherwiseLine: Int
  ): T = {
    val ordered = alternatives.toVector
    if (ordered.isEmpty)
      FrontendException.failAt(
        "MORPH-FRONTEND-SYMBOLIC-CONDITIONAL-CHAIN-EMPTY",
        "a symbolic else-if chain requires at least one guarded alternative",
        SourceOrigin(otherwiseFile, otherwiseLine)
      )

    if (!ParameterizedStructure.captureEnabled) {
      ordered.collectFirst { case (condition, body, _, _) if condition.witness => body() }
        .getOrElse(otherwise())
    } else {
      def capture(index: Int): T = {
        val (condition, body, file, line) = ordered(index)
        val origin = SourceOrigin(file, line)
        val falseOrigin =
          if (index + 1 < ordered.size) {
            val (_, _, nextFile, nextLine) = ordered(index + 1)
            SourceOrigin(nextFile, nextLine)
          } else SourceOrigin(otherwiseFile, otherwiseLine)
        var trueValue: Option[T] = None
        var falseValue: Option[T] = None
        val builder = NativeStructuralFrontend.startGenerateIf(
          condition,
          None,
          { trueValue = Some(body()); () },
          origin
        )
        builder.nativeToken.otherwise(
          {
            falseValue = Some(
              if (index + 1 < ordered.size) capture(index + 1)
              else otherwise()
            )
            ()
          },
          falseOrigin
        )
        if (condition.witness) trueValue.get
        else falseValue.get
      }

      capture(0)
    }
  }
}
