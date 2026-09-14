  /** A condition is a one-bit value observation, not a fictitious register.
    * The canonical projection below proves the exact pure value substitution.
    * This separate native proof retains every process/scope/assignment and
    * rejects dependencies written by any possibly coalesced blocking process.
    * Explicit/reflected user names remain outside this generated-condition path.
    */
  private def proveConditionCandidate(
      pc: PhaseContext,
      candidate: NativeCandidate
  ): Either[String, NativeProof] = {
    val alias = candidate.alias
    if (candidate.nameOrigin != NameOrigin.Generated && candidate.nameOrigin != NameOrigin.Unnamed)
      return Left("WA10-CONDITION-USER-NAME")
    if (!alias.isInstanceOf[Bool] || alias.getBitsWidth != 1 ||
        candidate.sourceExpression.getTypeObject != TypeBool ||
        ParameterizedWidth.expressionOf(alias).nonEmpty ||
        !NativeWireExpressionCodec.fixedWidthTree(candidate.sourceExpression))
      return Left("WA10-CONDITION-FIXED-BOOLEAN-BOUNDARY")
    val sourceSize = boundedConditionExpressionSize(candidate.sourceExpression, 64)
      .getOrElse(return Left("WA10-CONDITION-SOURCE-BUDGET"))
    if (candidate.receiverOccurrenceCount > 32)
      return Left("WA10-CONDITION-RECEIVER-BUDGET")
    if (candidate.sourceReferences.isEmpty)
      return Left("WA10-CONDITION-CONSTANT-SENSITIVITY")
    if (candidate.sourceReferences.exists(value =>
        (value eq alias) || (value.component ne candidate.component) ||
        value.parentScope == null || !(value.parentScope eq value.rootScopeStatement) ||
        value.isAnalog || value.isInOut || value.getBitsWidth <= 0 ||
        (value.getTypeObject != TypeBool && value.getTypeObject != TypeUInt &&
          value.getTypeObject != TypeBits) || ParameterizedWidth.expressionOf(value).nonEmpty))
      return Left("WA10-CONDITION-SOURCE-BOUNDARY")
    if (candidate.useStatements.exists(statement =>
        statement.parentScope == null ||
        !(statement.rootScopeStatement eq alias.rootScopeStatement) ||
        conditionReceiverExpression(statement).forall(expression =>
          expression.getTypeObject != TypeBool ||
          boundedConditionExpressionSize(expression, 256).isEmpty ||
          NativePureExpressionCopy(expression).isEmpty)))
      return Left("WA10-CONDITION-RECEIVER-CONTEXT")
    if (NativePureExpressionCopy(candidate.sourceExpression).isEmpty)
      return Left("WA10-CONDITION-SOURCE-COPY")

    // The inherited guard still checks all identity, preservation, clock and
    // hidden-metadata obligations, plus every ordinary assignment receiver.
    // When receivers are NOT asserted to be ordinary continuous assignments:
    // their complete independent native process proof follows immediately.
    val dataReceivers = candidate.useStatements.collect {
      case value: DataAssignmentStatement => value: Statement
    }
    new NamedWireAliasNativePhase().expressionRemovalBlocker(
      pc, candidate.component, alias, candidate.assignment, dataReceivers
    ) match {
      case Some(reason) => return Left("WA10-CONDITION-" + reason)
      case None =>
    }
    conditionProcessProof(candidate) match {
      case Left(reason) => return Left(reason)
      case Right(renderedUses) if renderedUses < 1 || renderedUses > 32 ||
          renderedUses.toLong * sourceSize > 256 =>
        return Left("WA10-CONDITION-RENDERED-EXPANSION-BUDGET")
      case _ =>
    }
    packedTypeProof(alias, candidate.sourceExpression) match {
      case Some(proof) if conditionSnapshot(candidate, proof).nonEmpty => Right(proof)
      case _ => Left("WA10-CONDITION-CANONICAL-OBSERVATION")
    }
  }

  private def conditionReceiverExpression(statement: Statement): Option[Expression] = statement match {
    case value: WhenStatement => Some(value.cond)
    case value: DataAssignmentStatement if value.target eq value.finalTarget => Some(value.source)
    case _ => None
  }

  private def boundedConditionExpressionSize(expression: Expression, limit: Int): Option[Int] = {
    val pending = ArrayBuffer(expression)
    var count = 0
    while (pending.nonEmpty) {
      val next = pending.remove(pending.size - 1)
      count += 1
      if (next == null || count > limit) return None
      next match {
        case _: BaseType =>
        case _ => next.foreachDrivingExpression(child => pending += child)
      }
    }
    Some(count)
  }

  /** Conservative upper bound on the native emitter's blocking process groups.
    * Coalesce targets sharing ANY outer control tree, even when the selected
    * backend does not merge them, then close through other writes to those
    * targets. This can retain a safe candidate; it cannot miss a merged writer.
    */
  private def conditionProcessProof(candidate: NativeCandidate): Either[String, Int] = {
    val component = candidate.component
    val root = component.dslBody
    val all = statementsOf(component)
    val assignments = all.collect { case value: DataAssignmentStatement => value }
    def contains(values: scala.collection.Seq[BaseType], value: BaseType): Boolean =
      values.exists(_ eq value)
    def outerControl(statement: Statement): Option[TreeStatement] = {
      var scope = statement.parentScope
      var outer: TreeStatement = null
      var depth = 0
      while (scope != null && (scope ne root)) {
        depth += 1
        if (depth > 128 || scope.parentStatement == null) return None
        scope.parentStatement match {
          case tree: TreeStatement => outer = tree; scope = tree.parentScope
          case _ => return None
        }
      }
      Option(outer)
    }

    // Include upstream control dependencies as well as data dependencies. A
    // seemingly independent leaf can otherwise hide a same-process feedback
    // path through another combinational carrier.
    val dependencies = ArrayBuffer.empty[BaseType]
    val pending = ArrayBuffer.empty[BaseType] ++ candidate.sourceReferences
    var unsupported = false
    while (pending.nonEmpty) {
      val current = pending.remove(pending.size - 1)
      if (current eq candidate.alias) return Left("WA10-CONDITION-CYCLE")
      if (!contains(dependencies, current)) {
        if (dependencies.size >= 256) return Left("WA10-CONDITION-DEPENDENCY-BUDGET")
        if ((current.component ne component) || current.parentScope == null ||
            !(current.parentScope eq current.rootScopeStatement) || current.isAnalog || current.isInOut)
          return Left("WA10-CONDITION-DEPENDENCY-BOUNDARY")
        dependencies += current
        if (current.isComb && !current.isInput) {
          current.foreachStatements {
            case assignment: DataAssignmentStatement =>
              pending ++= referencedBaseTypes(assignment.source)
              var scope = assignment.parentScope
              var depth = 0
              while (scope != null && (scope ne root) && !unsupported) {
                depth += 1
                if (depth > 128 || scope.parentStatement == null) unsupported = true
                else scope.parentStatement match {
                  case when: WhenStatement =>
                    pending ++= referencedBaseTypes(when.cond)
                    scope = when.parentScope
                  case _ => unsupported = true
                }
              }
            case _ => unsupported = true
          }
        } else if (!current.isInput && !current.isReg) unsupported = true
      }
    }
    if (unsupported) return Left("WA10-CONDITION-DEPENDENCY-CONTEXT")

    var renderedUses = 0
    candidate.useStatements.foreach {
      case when: WhenStatement =>
        val controlled = ArrayBuffer.empty[BaseType]
        def inspect(scope: ScopeStatement): Unit = scope.walkStatements {
          case assignment: DataAssignmentStatement =>
            val target = assignment.finalTarget
            if ((target.component ne component) || !target.isComb || target.isAnalog ||
                target.isInputOrInOut || target.parentScope == null ||
                !(target.parentScope eq target.rootScopeStatement)) unsupported = true
            if (!contains(controlled, target)) controlled += target
          case _: WhenStatement =>
          case _: BaseType =>
          case _ => unsupported = true
        }
        inspect(when.whenTrue)
        inspect(when.whenFalse)
        if (unsupported || controlled.isEmpty)
          return Left("WA10-CONDITION-NONCOMBINATIONAL-CONTROL")
        val controls = ArrayBuffer[TreeStatement](outerControl(when).getOrElse(when))
        var progress = true
        while (progress) {
          progress = false
          assignments.foreach { assignment =>
            val target = assignment.finalTarget
            val tree = outerControl(assignment)
            if (target.isComb && (contains(controlled, target) ||
                tree.exists(value => controls.exists(_ eq value)))) {
              if (!contains(controlled, target)) { controlled += target; progress = true }
              tree.foreach { value =>
                if (!controls.exists(_ eq value)) { controls += value; progress = true }
              }
            }
          }
          if (controlled.size > 32 || controls.size > 256)
            return Left("WA10-CONDITION-PROCESS-BUDGET")
        }
        if (dependencies.exists(value => contains(controlled, value)))
          return Left("WA10-CONDITION-BLOCKING-DEPENDENCY")
        renderedUses += controlled.size * references(when, candidate.alias)
      case assignment: DataAssignmentStatement =>
        if (dependencies.exists(_ eq assignment.finalTarget))
          return Left("WA10-CONDITION-BLOCKING-DEPENDENCY")
        renderedUses += references(assignment, candidate.alias)
      case _ => return Left("WA10-CONDITION-RECEIVER-CONTEXT")
    }
    Right(renderedUses)
  }

  /** Exact value-observation projection, not a replacement process model.
    * Each observation captures its actual native Boolean receiver expression.
    * The canonical inliner proves substitution at EVERY reference. Native
    * control/priority/sensitivity obligations are certified separately above;
    * only native RHS/condition fields are changed, never these witness sinks.
    */
  private def conditionSnapshot(
      candidate: NativeCandidate,
      proof: NativeProof
  ): Option[CanonicalSnapshot] = {
    if (proof.parameters.nonEmpty || candidate.alias.getTypeObject != TypeBool) return None
    val moduleId = ModuleId.unsafe("module.native-condition-observation")
    val scopeId = ScopeId.unsafe("scope.native-condition-observation")
    val aliasId = SymbolId.unsafe("symbol.native-condition-observation.alias")
    val codec = new NativeWireExpressionCodec(scopeId, "condition-value-source",
      Vector(candidate.alias -> aliasId))
    val source = codec.capture(candidate.sourceExpression).getOrElse(return None)
    val receivers = candidate.useStatements.map { statement =>
      val expression = conditionReceiverExpression(statement).getOrElse(return None)
      if (expression.getTypeObject != TypeBool) return None
      codec.capture(expression).getOrElse(return None)
    }
    val declarations = codec.capturedSources.map { case (native, id) =>
      Declaration(id, scopeId,
        if (native eq candidate.alias) DeclarationKind.InternalCombinational else sourceKind(native),
        Some(packedTypeFor(native, None, None).getOrElse(return None)),
        if (native eq candidate.alias) candidate.nameOrigin
        else NativeWireNameProvenance.origin(native).getOrElse(NameOrigin.Unknown),
        None, if (native eq candidate.alias) Observability.Unobserved else sourceObservability(native))
    }
    val observations = receivers.indices.map { index =>
      val id = SymbolId.unsafe(s"symbol.native-condition-observation.result.$index")
      Declaration(id, scopeId, DeclarationKind.Port(PortDirection.Output),
        Some(proof.packedType), NameOrigin.Generated, None,
        Observability(complete = true, externallyVisible = true))
    }.toVector
    val drivers = Driver(DriverId.unsafe("driver.native-condition-observation.source"),
      scopeId, aliasId, DriverKind.Continuous, DriverCoverage.FullObject, source) +:
      receivers.zipWithIndex.map { case (expression, index) =>
        Driver(DriverId.unsafe(s"driver.native-condition-observation.result.$index"),
          scopeId, observations(index).id, DriverKind.Continuous, DriverCoverage.FullObject, expression)
      }
    Some(CanonicalSnapshot(Design(CanonicalIrSchema.schemaVersion, CanonicalIrSchema.stage,
      moduleId, Vector(Module(moduleId, "NativeConditionValueObservation", Vector.empty,
        Vector(Scope(scopeId, None, ScopeKind.Module)), Vector.empty,
        declarations ++ observations, drivers))), aliasId, Vector.empty))
  }

  private def rewriteConditionIdentity(candidate: NativeCandidate): Int = {
    var replacements = 0
    // Prepare every replacement before modifying the graph. Fresh operator
    // trees prevent remapping one condition from silently mutating another.
    val prepared = candidate.useStatements.map { statement =>
      val expression = conditionReceiverExpression(statement).getOrElse(
        throw new IllegalStateException("proven condition receiver disappeared"))
      val copy = NativePureExpressionCopy(expression).getOrElse(
        throw new IllegalStateException("proven condition receiver is no longer copyable"))
      def replace(value: Expression): Expression = value match {
        case reference: BaseType if reference eq candidate.alias =>
          replacements += 1
          NativePureExpressionCopy(candidate.sourceExpression).getOrElse(
            throw new IllegalStateException("proven condition source is no longer copyable"))
        case other => other
      }
      val replaced = replace(copy)
      if (replaced eq copy) replaced.walkRemapDrivingExpressions(replace)
      statement -> replaced
    }
    if (replacements != candidate.receiverOccurrenceCount)
      throw new IllegalStateException("condition substitution changed the proved reference inventory")
    prepared.foreach {
      case (when: WhenStatement, expression) => when.cond = expression
      case (assignment: DataAssignmentStatement, expression) => assignment.source = expression
      case _ => throw new IllegalStateException("unsupported prepared condition receiver")
    }
    val remaining = statementsOf(candidate.component).map(references(_, candidate.alias)).sum
    if (remaining != 0) throw new IllegalStateException("condition substitution left a dangling reference")
    candidate.assignment.removeStatement()
    candidate.alias.removeStatement()
    replacements
  }

