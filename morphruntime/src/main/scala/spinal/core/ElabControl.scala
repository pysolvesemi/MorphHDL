package spinal.core

/**
  * Runtime half of the typed elaboration control-flow bridge.
  *
  * The compiler plugin routes only conditions already declared as
  * [[ElabBool]] here. Concrete Booleans never enter this API. Domain-constant
  * predicates are folded immediately; a genuinely symbolic predicate captures
  * both ordinary SpinalHDL alternatives for later Verilog generate lowering.
  */
object ElabControl {
  private val ElseIfMarkerPrefix = "morphhdl_else_if_"

  def selectSymbolic[T](
      condition: ElabBool,
      sourceFile: String,
      sourceLine: Int
  )(ifTrue: => T)(ifFalse: => T): T = {
    requireConditionValue(condition, sourceFile, sourceLine, "if condition")
    if (!ParameterizedStructure.captureEnabled) {
      if (condition.witness) ifTrue else ifFalse
    } else if (condition.isAlwaysTrue) {
      ifTrue
    } else if (condition.isAlwaysFalse) {
      ifFalse
    } else {
      captureOne(
        condition,
        sourceFile,
        sourceLine,
        sourceFile,
        sourceLine,
        continuation = false,
        () => ifTrue,
        () => ifFalse
      )
    }
  }

  /** Typed counterpart of SpinalHDL's host-language `.generate` helper. */
  def generateSymbolic[T](
      condition: ElabBool,
      sourceFile: String,
      sourceLine: Int
  )(body: => T): T =
    selectSymbolic(condition, sourceFile, sourceLine)(body)(null.asInstanceOf[T])

  /** Preserve one source-ordered typed `if / else if / ... / else` chain. */
  def selectSymbolicChain[T](
      alternatives: Seq[(ElabBool, () => T, String, Int)],
      otherwise: () => T,
      otherwiseFile: String,
      otherwiseLine: Int
  ): T = {
    val ordered = Option(alternatives).getOrElse(Seq.empty).toVector
    if (ordered.isEmpty) {
      fail(
        "SPINAL-ELAB-CONTROL-CHAIN-EMPTY",
        "typed symbolic else-if chain requires at least one alternative",
        rendered(otherwiseFile, otherwiseLine)
      )
    }
    ordered.foreach { case (condition, _, file, line) =>
      requireConditionValue(condition, file, line, "else-if condition")
    }

    def concrete(index: Int): T = {
      val (condition, body, _, _) = ordered(index)
      if (condition.witness) body()
      else if (index + 1 < ordered.size) concrete(index + 1)
      else otherwise()
    }

    if (!ParameterizedStructure.captureEnabled) concrete(0)
    else {
      def capture(index: Int): T = {
        val (condition, body, file, line) = ordered(index)
        if (condition.isAlwaysTrue) body()
        else if (condition.isAlwaysFalse) {
          if (index + 1 < ordered.size) capture(index + 1)
          else otherwise()
        } else {
          val continuation = index + 1 < ordered.size
          val falseFile =
            if (continuation) ordered(index + 1)._3 else otherwiseFile
          val falseLine =
            if (continuation) ordered(index + 1)._4 else otherwiseLine
          captureOne(
            condition,
            file,
            line,
            falseFile,
            falseLine,
            continuation,
            body,
            () => {
              if (continuation) capture(index + 1)
              else otherwise()
            }
          )
        }
      }
      capture(0)
    }
  }

  /** Typed replacement for one-argument `require`. */
  def requireCondition(
      condition: ElabBool,
      sourceFile: String,
      sourceLine: Int
  ): Unit =
    requireCondition(
      condition,
      "requirement failed",
      sourceFile,
      sourceLine
    )

  /** Typed replacement for two-argument `require`. */
  def requireCondition(
      condition: ElabBool,
      message: => Any,
      sourceFile: String,
      sourceLine: Int
  ): Unit = {
    requireConditionValue(condition, sourceFile, sourceLine, "require condition")
    if (!ParameterizedStructure.captureEnabled) {
      Predef.require(condition.witness, message)
    } else if (condition.isAlwaysFalse) {
      fail(
        "SPINAL-ELAB-REQUIRE-ALWAYS-FALSE",
        String.valueOf(message),
        rendered(sourceFile, sourceLine)
      )
    } else if (!condition.isAlwaysTrue) {
      fail(
        "SPINAL-ELAB-REQUIRE-DOMAIN-UNPROVEN",
        s"typed requirement '${condition.expression.verilog}' is not proven true over its complete parameter domain: ${String.valueOf(message)}",
        rendered(sourceFile, sourceLine)
      )
    }
  }

  private def captureOne[T](
      condition: ElabBool,
      sourceFile: String,
      sourceLine: Int,
      falseFile: String,
      falseLine: Int,
      continuation: Boolean,
      ifTrue: () => T,
      ifFalse: () => T
  ): T = {
    val component = Option(Component.current).getOrElse {
      fail(
        "SPINAL-ELAB-CONTROL-COMPONENT-MISSING",
        "typed symbolic conditional requires an active Component",
        rendered(sourceFile, sourceLine)
      )
    }
    var trueValue: Option[T] = None
    var falseValue: Option[T] = None
    val trueBlock = ParameterizedStructure.captureBlock(
      component,
      Some(rendered(sourceFile, sourceLine))
    ) {
      trueValue = Some(ifTrue())
      ()
    }
    val pending = ParameterizedStructure.beginPending(
      component,
      "typed-generate-if",
      Some(rendered(sourceFile, sourceLine))
    )
    val falseBlock = ParameterizedStructure.captureBlock(
      component,
      Some(rendered(falseFile, falseLine))
    ) {
      falseValue = Some(ifFalse())
      ()
    }
    val base = generatedIfBase(sourceFile, sourceLine)
    ParameterizedStructure.registerIf(
      pending,
      condition.expression,
      base + "_true",
      (if (continuation) ElseIfMarkerPrefix else "") + base + "_false",
      trueBlock,
      falseBlock,
      Some(rendered(sourceFile, sourceLine))
    )
    if (condition.witness) trueValue.get else falseValue.get
  }

  private def requireConditionValue(
      condition: ElabBool,
      file: String,
      line: Int,
      role: String
  ): Unit = {
    if (condition == null) {
      fail(
        "SPINAL-ELAB-CONTROL-CONDITION-NULL",
        s"$role must not be null",
        rendered(file, line)
      )
    }
    if (condition.expression.default != condition.witness) {
      fail(
        "SPINAL-ELAB-CONTROL-WITNESS-MISMATCH",
        s"$role witness ${condition.witness} disagrees with expression default ${condition.expression.default}",
        rendered(file, line)
      )
    }
  }

  private def generatedIfBase(file: String, line: Int): String = {
    val normalized = Option(file).getOrElse("source").replace('\\', '/')
    val fileName = normalized.substring(normalized.lastIndexOf('/') + 1)
    val stem = fileName.lastIndexOf('.') match {
      case index if index > 0 => fileName.substring(0, index)
      case _                  => fileName
    }
    val safeStem = stem.replaceAll("[^A-Za-z0-9_]", "_") match {
      case value if value.nonEmpty && value.charAt(0).isDigit => "_" + value
      case value if value.nonEmpty                            => value
      case _                                                  => "source"
    }
    s"g_if_${safeStem}_l$line"
  }

  private def rendered(file: String, line: Int): String =
    s"${Option(file).filter(_.nonEmpty).getOrElse("<typed-elaboration>")}:${math.max(1, line)}"

  private def fail(code: String, detail: String, source: String): Nothing =
    ParameterizedVerilogException.fail(code, detail, Some(source))
}
