package spinal.core

import scala.collection.mutable

/** Runtime half of the typed elaboration control-flow bridge.
  *
  * The compiler plugin routes only conditions already declared as
  * [[ElabBool]] here. Concrete Booleans never enter this API. Domain-constant
  * predicates are folded immediately; a genuinely symbolic predicate captures
  * both ordinary SpinalHDL alternatives for later Verilog generate lowering.
  */
object ElabControl {
  private val ElseIfMarkerPrefix = "morphhdl_else_if_"
  private object GeneratedIfOrdinalStorageKey

  private final class GeneratedIfOrdinals {
    val byBase = mutable.HashMap.empty[String, Int]
  }

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
  )(body: => T): T = {
    requireConditionValue(condition, sourceFile, sourceLine, "generate condition")
    if (!ParameterizedStructure.captureEnabled) {
      if (condition.witness) body else null.asInstanceOf[T]
    } else if (condition.isAlwaysTrue) {
      body
    } else if (condition.isAlwaysFalse) {
      null.asInstanceOf[T]
    } else {
      captureGenerate(condition, sourceFile, sourceLine, () => body)
    }
  }

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
        s"typed requirement '${condition.expression.verilog}' is not proven true over its complete parameter domain: ${String
            .valueOf(message)}",
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
    val source = Some(rendered(sourceFile, sourceLine))
    val exact = condition.expression.exactDomain.getOrElse {
      fail(
        "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING",
        s"typed conditional '${condition.expression.verilog}' lacks exact single-root evidence",
        rendered(sourceFile, sourceLine)
      )
    }
    val admitted = ElaborationDomainContext.admitted(exact)
    val trueValues = admitted.filter(value => exact.evaluate(value).contains(true))
    val falseValues = admitted.filter(value => exact.evaluate(value).contains(false))
    if (trueValues.isEmpty || falseValues.isEmpty) {
      fail(
        "SPINAL-ELAB-CONTROL-DOMAIN-CLASSIFICATION-INCONSISTENT",
        s"typed conditional '${condition.expression.verilog}' was captured although its active domain is constant",
        rendered(sourceFile, sourceLine)
      )
    }
    val projectedCondition = condition.projectedExpression("typed conditional")
    val selectedWitness = condition.witness
    val predicateDomain =
      ParameterizedStructure.typedPredicateDomainOf(component, condition.expression)
    var trueValue: Option[T] = None
    var falseValue: Option[T] = None
    val trueBlock = ParameterizedStructure.captureExactBlock(
      component,
      exact.root,
      trueValues,
      source
    ) {
      trueValue = Some(ifTrue())
      ()
    }
    val pending = ParameterizedStructure.beginPending(
      component,
      "typed-generate-if",
      Some(rendered(sourceFile, sourceLine))
    )
    val falseBlock = ParameterizedStructure.captureExactBlock(
      component,
      exact.root,
      falseValues,
      Some(rendered(falseFile, falseLine))
    ) {
      falseValue = Some(ifFalse())
      ()
    }
    val base = nextGeneratedIfBase(component, sourceFile, sourceLine)
    ParameterizedStructure.registerIf(
      pending,
      projectedCondition,
      base + "_true",
      (if (continuation) ElseIfMarkerPrefix else "") + base + "_false",
      trueBlock,
      falseBlock,
      source,
      Some(predicateDomain)
    )
    if (selectedWitness) trueValue.get else falseValue.get
  }

  private def captureGenerate[T](
      condition: ElabBool,
      sourceFile: String,
      sourceLine: Int,
      body: () => T
  ): T = {
    val component = Option(Component.current).getOrElse {
      fail(
        "SPINAL-ELAB-CONTROL-COMPONENT-MISSING",
        "typed symbolic generate requires an active Component",
        rendered(sourceFile, sourceLine)
      )
    }
    val source = Some(rendered(sourceFile, sourceLine))
    val exact = condition.expression.exactDomain.getOrElse {
      fail(
        "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING",
        s"typed generate '${condition.expression.verilog}' lacks exact single-root evidence",
        rendered(sourceFile, sourceLine)
      )
    }
    val admitted = ElaborationDomainContext.admitted(exact)
    val trueValues = admitted.filter(value => exact.evaluate(value).contains(true))
    val falseValues = admitted.filter(value => exact.evaluate(value).contains(false))
    if (trueValues.isEmpty || falseValues.isEmpty) {
      fail(
        "SPINAL-ELAB-CONTROL-DOMAIN-CLASSIFICATION-INCONSISTENT",
        s"typed generate '${condition.expression.verilog}' was captured although its active domain is constant",
        rendered(sourceFile, sourceLine)
      )
    }
    val projectedCondition = condition.projectedExpression("typed generate")
    val selectedWitness = condition.witness
    val predicateDomain =
      ParameterizedStructure.typedPredicateDomainOf(component, condition.expression)
    var capturedValue: Option[T] = None
    val trueBlock = ParameterizedStructure.captureExactBlock(
      component,
      exact.root,
      trueValues,
      source
    ) {
      capturedValue = Some(body())
      ()
    }
    val pending = ParameterizedStructure.beginPending(
      component,
      "typed-generate-if",
      source
    )
    val falseBlock = ParameterizedStructuralSynthetic.emptyBlock(source)
    val base = nextGeneratedIfBase(component, sourceFile, sourceLine)
    ParameterizedStructure.registerIf(
      pending,
      projectedCondition,
      base + "_true",
      base + "_false",
      trueBlock,
      falseBlock,
      source,
      Some(predicateDomain)
    )
    if (selectedWitness) capturedValue.get else null.asInstanceOf[T]
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

  /** Keep the existing source-derived label for the first typed conditional at
    * one call site, then disambiguate repeated native-library invocations by
    * exact component-local capture order. Labels remain presentation only;
    * structural ownership and expression identity never depend on the suffix.
    */
  private def nextGeneratedIfBase(
      component: Component,
      file: String,
      line: Int
  ): String = {
    val base = generatedIfBase(file, line)
    val ordinals = component.userCache
      .getOrElseUpdate(
        GeneratedIfOrdinalStorageKey,
        new GeneratedIfOrdinals
      )
      .asInstanceOf[GeneratedIfOrdinals]
    val ordinal = ordinals.byBase.getOrElse(base, 0) + 1
    ordinals.byBase(base) = ordinal
    if (ordinal == 1) base else s"${base}_$ordinal"
  }

  private def rendered(file: String, line: Int): String =
    s"${Option(file).filter(_.nonEmpty).getOrElse("<typed-elaboration>")}:${math.max(1, line)}"

  private def fail(code: String, detail: String, source: String): Nothing =
    ParameterizedVerilogException.fail(code, detail, Some(source))
}
