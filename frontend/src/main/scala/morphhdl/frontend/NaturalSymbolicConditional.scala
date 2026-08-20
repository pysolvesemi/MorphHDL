package morphhdl.frontend

import morphhdl.paramrtl.BoolExpr.{And, Literal, Not}

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
    * Lowers a source-level symbolic `if / else if / ... / else` chain without
    * nesting structural captures. Each source alternative becomes a sibling
    * generate-if guarded by the original condition and the negation of all
    * preceding conditions. The guards are therefore mutually exclusive and
    * preserve source-order branch selection.
    */
  def selectSymbolicChain[T](
      alternatives: Seq[(HdlBool, () => T, String, Int)],
      otherwise: () => T,
      otherwiseFile: String,
      otherwiseLine: Int
  ): T = {
    if (alternatives.isEmpty)
      FrontendException.failAt(
        "MORPH-FRONTEND-SYMBOLIC-CONDITIONAL-CHAIN-EMPTY",
        "a symbolic else-if chain requires at least one guarded alternative",
        SourceOrigin(otherwiseFile, otherwiseLine)
      )

    if (!spinal.core.ParameterizedStructure.captureEnabled) {
      alternatives.collectFirst { case (condition, body, _, _) if condition.witness => body() }
        .getOrElse(otherwise())
    } else {
      val values = Array.fill[Option[T]](alternatives.size)(None)
      var otherwiseValue: Option[T] = None
      var remaining = literalAt(true, SourceOrigin(otherwiseFile, otherwiseLine))

      alternatives.zipWithIndex.foreach { case ((condition, body, file, line), index) =>
        val origin = SourceOrigin(file, line)
        val effective = if (index == 0) condition else andAt(remaining, condition, origin)
        val builder = NativeStructuralFrontend.startGenerateIf(
          effective,
          None,
          { values(index) = Some(body()); () },
          origin
        )
        builder.nativeToken.otherwise((), origin)
        remaining = andAt(remaining, notAt(condition, origin), origin)
      }

      val defaultOrigin = SourceOrigin(otherwiseFile, otherwiseLine)
      val defaultBuilder = NativeStructuralFrontend.startGenerateIf(
        remaining,
        None,
        { otherwiseValue = Some(otherwise()); () },
        defaultOrigin
      )
      defaultBuilder.nativeToken.otherwise((), defaultOrigin)

      alternatives.indexWhere(_._1.witness) match {
        case index if index >= 0 => values(index).get
        case _                   => otherwiseValue.get
      }
    }
  }

  private def literalAt(value: Boolean, origin: SourceOrigin): HdlBool =
    new HdlBool(
      witness = value,
      expression = Literal(value),
      declaration = None,
      localDeclaration = None,
      parameters = Set.empty,
      integerParameters = Set.empty,
      localParameters = Set.empty,
      booleanLocalParameters = Set.empty,
      origin = origin
    )

  private def notAt(value: HdlBool, origin: SourceOrigin): HdlBool =
    new HdlBool(
      witness = !value.witness,
      expression = Not(value.expression),
      declaration = None,
      localDeclaration = None,
      parameters = value.parameters,
      integerParameters = value.integerParameters,
      localParameters = value.localParameters,
      booleanLocalParameters = value.booleanLocalParameters,
      origin = origin
    )

  private def andAt(left: HdlBool, right: HdlBool, origin: SourceOrigin): HdlBool =
    new HdlBool(
      witness = left.witness && right.witness,
      expression = And(left.expression, right.expression),
      declaration = None,
      localDeclaration = None,
      parameters = left.parameters ++ right.parameters,
      integerParameters = left.integerParameters ++ right.integerParameters,
      localParameters = left.localParameters ++ right.localParameters,
      booleanLocalParameters = left.booleanLocalParameters ++ right.booleanLocalParameters,
      origin = origin
    )
}
