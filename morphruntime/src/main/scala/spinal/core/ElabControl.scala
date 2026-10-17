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
  /** Compiler-supplied lexical spelling for local hardware declarations.
    * Native naming priorities preserve explicit user names. Names never grant
    * ownership, width or structural access authority. Explicit weak names also
    * precede this compiler-supplied fallback; unlike a source field callback,
    * the fallback must not replace an existing authored local naming request.
    */
  def localValue[T](value: T, name: String): T = {
    val owner = Component.current
    if (owner != null && name != null && name.nonEmpty && !name.contains("$")) {
      value match {
        case data: Data if (data.component eq owner) && data.namePriority != Nameable.USER_WEAK =>
          owner.valCallback(data, name)
        case child: Component if (child.parent eq owner) && child.namePriority != Nameable.USER_WEAK =>
          owner.valCallback(child, name)
        case _ =>
      }
    }
    value
  }

  /** Scoped finite guards join statements or authenticated Bool-valued branches. */
  def selectScoped[T](condition: ElabScopedCondition, sourceFile: String, sourceLine: Int)
      (ifTrue: => T)(ifFalse: => T)(implicit result: ElabScopedBranchResult[T]): T = {
    require(condition != null, "scoped condition must not be null")
    result(condition, AutomaticGenerateLabels.conditionalRole(Component.current, "guard_l" + sourceLine))(ifTrue)(ifFalse)
  }

  /** A missing source else is an authenticated empty alternative. The authored
    * branch still goes through native capture and its side-effect checks. */
  def generateScoped(condition: ElabScopedCondition, sourceFile: String, sourceLine: Int)(body: => Unit): Unit = {
    require(condition != null, "scoped condition must not be null")
    condition.capture(AutomaticGenerateLabels.conditionalRole(Component.current, "guard_l" + sourceLine), emptyFalse = true)(body)(())
  }

  private val ElseIfMarkerPrefix = "else_if_"
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
  ): T = selectChain(alternatives, otherwise, otherwiseFile, otherwiseLine, false)

  /** Statement chain with an absent final else. Only the terminal alternative
    * is a one-sided generate; earlier alternatives retain ordered exclusion.
    */
  def generateSymbolicChain(
      alternatives: Seq[(ElabBool, () => Unit, String, Int)],
      sourceFile: String,
      sourceLine: Int
  ): Unit = selectChain(alternatives, () => (), sourceFile, sourceLine, true)

  private def selectChain[T](
      alternatives: Seq[(ElabBool, () => T, String, Int)],
      otherwise: () => T,
      otherwiseFile: String,
      otherwiseLine: Int,
      absentElse: Boolean
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
          if (absentElse && !continuation)
            captureGenerate(condition, file, line, body)
          else captureOne(
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
    } else if (condition.expression.parameters.isEmpty) {
      Predef.require(condition.witness, message)
    } else {
      NativeSymbolicLegality.requireSymbolic(condition, message, Some(rendered(sourceFile, sourceLine)))
    }
  }

  /** Library precondition needed to choose or construct native hardware now.
    * Unlike a user legality obligation this must hold across the active domain.
    */
  def requireStructuralCondition(condition: ElabBool, message: => Any,
      sourceFile: String, sourceLine: Int): Unit = {
    requireConditionValue(condition, sourceFile, sourceLine, "structural requirement")
    if (!ParameterizedStructure.captureEnabled) Predef.require(condition.witness, message)
    else if (condition.isAlwaysFalse)
      fail("SPINAL-ELAB-REQUIRE-ALWAYS-FALSE", String.valueOf(message), rendered(sourceFile, sourceLine))
    else if (!condition.isAlwaysTrue)
      fail("SPINAL-ELAB-REQUIRE-DOMAIN-UNPROVEN",
        "structural requirement is not universally proven: " + String.valueOf(message), rendered(sourceFile, sourceLine))
  }

  private final class PredicateCapture(component: Component, condition: ElabBool,
      source: Option[String]) {
    val projected = condition.projectedExpression("typed conditional")
    private val exact = condition.expression.exactDomain
    private val joint = if (exact.nonEmpty) None else {
      if (!ElaborationProductDomain.isRetained(projected))
        ParameterizedVerilogException.fail("SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING",
          "typed conditional lacks authenticated exact evidence", source)
      Some(ElaborationProductDomain.structuralPredicate(projected))
    }
    private val alternatives = exact.map { value =>
      val admitted = ElaborationDomainContext.admitted(value)
      Vector(admitted.filter(n => value.evaluate(n).contains(true)),
        admitted.filter(n => value.evaluate(n).contains(false)))
    }
    if (alternatives.exists(_.exists(_.isEmpty)) || joint.exists(value =>
        value.indices(0).isEmpty || value.indices(1).isEmpty))
      ParameterizedVerilogException.fail("SPINAL-ELAB-CONTROL-DOMAIN-CLASSIFICATION-INCONSISTENT",
        "typed conditional was captured although its active domain is constant", source)
    val domain = joint.map(value => ParameterizedStructure.jointPredicateDomainOf(component, value))
      .getOrElse(ParameterizedStructure.typedPredicateDomainOf(component, condition.expression))
    def block(branch: Int, location: Option[String])(body: => Unit): ParameterizedStructuralBlock =
      exact match {
        case Some(value) => ParameterizedStructure.captureExactBlock(
          component, value.root, alternatives.get(branch), location)(body)
        case None => ParameterizedStructure.captureJointBlock(component, joint.get, branch, location)(body)
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
  ): T = ParameterizedStructure.transaction(Component.current) {
    val component = Option(Component.current).getOrElse {
      fail(
        "SPINAL-ELAB-CONTROL-COMPONENT-MISSING",
        "typed symbolic conditional requires an active Component",
        rendered(sourceFile, sourceLine)
      )
    }
    val source = Some(rendered(sourceFile, sourceLine))
    val capture = new PredicateCapture(component, condition, source)
    val projectedCondition = capture.projected
    val selectedWitness = condition.witness
    val predicateDomain = capture.domain
    var trueValue: Option[T] = None
    var falseValue: Option[T] = None
    val trueBlock = capture.block(0, source) {
      trueValue = Some(ifTrue())
      ()
    }
    val pending = ParameterizedStructure.beginPending(
      component,
      "typed-generate-if",
      Some(rendered(sourceFile, sourceLine))
    )
    val falseBlock = capture.block(1, Some(rendered(falseFile, falseLine))) {
      falseValue = Some(ifFalse())
      ()
    }
    val base = nextGeneratedIfBase(component, sourceFile, sourceLine)
    AutomaticGenerateLabels.retain(component, trueBlock)
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
  ): T = ParameterizedStructure.transaction(Component.current) {
    val component = Option(Component.current).getOrElse {
      fail(
        "SPINAL-ELAB-CONTROL-COMPONENT-MISSING",
        "typed symbolic generate requires an active Component",
        rendered(sourceFile, sourceLine)
      )
    }
    val source = Some(rendered(sourceFile, sourceLine))
    val capture = new PredicateCapture(component, condition, source)
    val projectedCondition = capture.projected
    val selectedWitness = condition.witness
    val predicateDomain = capture.domain
    var capturedValue: Option[T] = None
    val trueBlock = capture.block(0, source) {
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
    AutomaticGenerateLabels.retain(component, trueBlock)
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

/** Result kinds whose branch ownership can be captured without a host witness. */
sealed trait ElabScopedBranchResult[T] {
  def apply(condition: ElabScopedCondition, role: String)(yes: => T)(no: => T): T
}
object ElabScopedBranchResult {
  implicit object Statement extends ElabScopedBranchResult[Unit] {
    def apply(condition: ElabScopedCondition, role: String)(yes: => Unit)(no: => Unit): Unit =
      condition.capture(role)(yes)(no)
  }
  implicit object BooleanSignal extends ElabScopedBranchResult[Bool] {
    def apply(condition: ElabScopedCondition, role: String)(yes: => Bool)(no: => Bool): Bool = {
      // This is a combinational expression, not a conditionally updated
      // storage element. Its consumers retain the enclosing runtime guard.
      val context = DslScopeStack.set(Component.current.dslBody)
      try {
        val result = Bool()
        def expression(body: => Bool): Bool = {
          val component = Component.current
          val declarations = new java.util.IdentityHashMap[BaseType, java.lang.Boolean]()
          val statements = new java.util.IdentityHashMap[spinal.core.internals.Statement, java.lang.Boolean]()
          component.dslBody.walkStatements(s => statements.put(s, java.lang.Boolean.TRUE))
          val assignments = new java.util.IdentityHashMap[spinal.core.internals.DataAssignmentStatement, java.lang.Boolean]()
          component.dslBody.walkStatements {
            case value: BaseType => declarations.put(value, java.lang.Boolean.TRUE)
            case assignment: spinal.core.internals.DataAssignmentStatement => assignments.put(assignment, java.lang.Boolean.TRUE)
            case _ =>
          }
          val children = component.children.toVector
          val value = body
          if (component.children.toVector != children)
            ParameterizedVerilogException.fail("SPINAL-SCOPED-BOOLEAN-RESULT-EFFECT",
              "a scoped Bool expression cannot instantiate child components")
          component.dslBody.walkStatements {
            case signal: BaseType if signal.isReg && !declarations.containsKey(signal) =>
              ParameterizedVerilogException.fail("SPINAL-SCOPED-BOOLEAN-RESULT-EFFECT",
                "a scoped Bool expression cannot introduce registers")
            case statement if !statements.containsKey(statement) && (statement.isInstanceOf[Mem[_]] || statement.isInstanceOf[MemPortStatement]) =>
              ParameterizedVerilogException.fail("SPINAL-SCOPED-BOOLEAN-RESULT-EFFECT",
                "a scoped Bool expression cannot introduce memory operations")
            case assignment: spinal.core.internals.DataAssignmentStatement if !assignments.containsKey(assignment) &&
                (declarations.containsKey(assignment.finalTarget) || (assignment.finalTarget.component ne component)) =>
              ParameterizedVerilogException.fail("SPINAL-SCOPED-BOOLEAN-RESULT-EFFECT",
                "a scoped Bool expression may construct local logic but cannot write an existing or foreign signal")
            case _ =>
          }
          value
        }
        condition.capture(role) { result := expression(yes) } { result := expression(no) }
        result
      } finally context.restore()
    }
  }
}
