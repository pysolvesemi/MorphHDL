package spinal.core

import scala.util.control.NonFatal

import morphhdl.frontend.{
  AnalyzedStructuralBoolean,
  AnalyzedStructuralBooleanKind,
  AnalyzedStructuralInteger,
  AnalyzedStructuralIntegerKind
}

/** Opaque prepared publication for one analyzer-sealed structural predicate.
  * It retains the certified domain capability and the exact branch blocks
  * captured under that domain; callers cannot construct or retarget it.
  */
final class ExternalAnalyzedStructuralPredicate private[core] (
    private[core] val component: Component,
    private[core] val operationIdentity: AnyRef,
    private[core] val condition: ElaborationBooleanExpression,
    private[core] val predicateDomain: Option[
      ParameterizedStructure.StructuralPredicateDomain
    ],
    private[core] val sourceLocation: Option[String]
) {
  private[this] var trueCapturing = false
  private[this] var falseCapturing = false
  private[this] var trueBlock: ParameterizedStructuralBlock = null
  private[this] var falseBlock: ParameterizedStructuralBlock = null
  private[this] var registered = false

  private[core] def requireIdentities(
      expectedComponent: Component,
      expectedOperation: AnyRef,
      expectedCondition: ElaborationBooleanExpression,
      useLocation: Option[String]
  ): Unit = synchronized {
    if (
      !(component eq expectedComponent) ||
      !(operationIdentity eq expectedOperation) ||
      !(condition eq expectedCondition)
    ) {
      fail(
        "SPINAL-ELAB-BOOL-ANALYZED-PREDICATE-TARGET-MISMATCH",
        "prepared analyzed structural predicate was presented to a foreign component, operation, or condition",
        useLocation.orElse(sourceLocation)
      )
    }
  }

  private[core] def beginCapture(
      branch: Int,
      useLocation: Option[String]
  ): Option[(ElaborationIntegerParameterRoot, Set[BigInt])] = synchronized {
    if (registered) {
      fail(
        "SPINAL-ELAB-BOOL-ANALYZED-PREDICATE-AUTHORIZATION-CONSUMED",
        "prepared analyzed structural predicate was already registered",
        useLocation.orElse(sourceLocation)
      )
    }
    branch match {
      case 0 if trueCapturing || (trueBlock ne null) =>
        fail(
          "SPINAL-ELAB-BOOL-ANALYZED-PREDICATE-BRANCH-REPLAY",
          "analyzed structural predicate true branch was already captured",
          useLocation.orElse(sourceLocation)
        )
      case 1 if falseCapturing || (falseBlock ne null) =>
        fail(
          "SPINAL-ELAB-BOOL-ANALYZED-PREDICATE-BRANCH-REPLAY",
          "analyzed structural predicate false branch was already captured",
          useLocation.orElse(sourceLocation)
        )
      case 0 => trueCapturing = true
      case 1 => falseCapturing = true
      case _ =>
        fail(
          "SPINAL-ELAB-BOOL-ANALYZED-PREDICATE-BRANCH-INVALID",
          s"analyzed structural predicate branch $branch is invalid",
          useLocation.orElse(sourceLocation)
        )
    }
    predicateDomain.flatMap { domain =>
      val values = domain.valuesFor(branch).getOrElse {
        fail(
          "SPINAL-ELAB-BOOL-ANALYZED-PREDICATE-BRANCH-INVALID",
          s"analyzed structural predicate branch $branch has no exact domain",
          useLocation.orElse(sourceLocation)
        )
      }
      domain.root.elaborationRoot.map(_ -> values).orElse {
        fail(
          "SPINAL-ELAB-BOOL-ANALYZED-PREDICATE-ROOT-MISSING",
          "analyzed structural predicate domain lost its exact declaration-root identity",
          useLocation.orElse(sourceLocation)
        )
      }
    }
  }

  private[core] def finishCapture(
      branch: Int,
      block: ParameterizedStructuralBlock,
      useLocation: Option[String]
  ): Unit = synchronized {
    if (block == null) {
      abortCapture(branch)
      fail(
        "SPINAL-ELAB-BOOL-ANALYZED-PREDICATE-BLOCK-MISSING",
        "analyzed structural predicate capture produced a null block",
        useLocation.orElse(sourceLocation)
      )
    }
    branch match {
      case 0 if trueCapturing && (trueBlock eq null) =>
        trueCapturing = false
        trueBlock = block
      case 1 if falseCapturing && (falseBlock eq null) =>
        falseCapturing = false
        falseBlock = block
      case _ =>
        fail(
          "SPINAL-ELAB-BOOL-ANALYZED-PREDICATE-BRANCH-STATE-INVALID",
          s"analyzed structural predicate branch $branch completed outside its exact capture",
          useLocation.orElse(sourceLocation)
        )
    }
  }

  private[core] def abortCapture(branch: Int): Unit = synchronized {
    branch match {
      case 0 => trueCapturing = false
      case 1 => falseCapturing = false
      case _ =>
    }
  }

  private[core] def claimRegistration(
      expectedComponent: Component,
      expectedOperation: AnyRef,
      expectedCondition: ElaborationBooleanExpression,
      expectedTrue: ParameterizedStructuralBlock,
      expectedFalse: ParameterizedStructuralBlock,
      useLocation: Option[String]
  ): Option[ParameterizedStructure.StructuralPredicateDomain] = synchronized {
    requireIdentities(
      expectedComponent,
      expectedOperation,
      expectedCondition,
      useLocation
    )
    if (registered) {
      fail(
        "SPINAL-ELAB-BOOL-ANALYZED-PREDICATE-AUTHORIZATION-CONSUMED",
        "prepared analyzed structural predicate was already registered",
        useLocation.orElse(sourceLocation)
      )
    }
    if (
      trueCapturing || falseCapturing || !(trueBlock eq expectedTrue) ||
      !(falseBlock eq expectedFalse)
    ) {
      fail(
        "SPINAL-ELAB-BOOL-ANALYZED-PREDICATE-BLOCK-MISMATCH",
        "prepared analyzed structural predicate did not receive its exact captured true and false blocks",
        useLocation.orElse(sourceLocation)
      )
    }
    registered = true
    predicateDomain
  }

  private def fail(
      code: String,
      message: String,
      sourceLocation: Option[String]
  ): Nothing =
    ParameterizedVerilogException.fail(code, message, sourceLocation)
}

/** The only public bridge from analyzer-sealed structural metadata to the raw
  * package-internal publication registries.  Each entry point consumes one
  * exact wrapper kind and revalidates the exact operation/component or native
  * carrier identities before invoking its one corresponding sink.
  */
object ExternalAnalyzedStructuralPublisher {
  def captureProcessRange(
      analyzed: AnalyzedStructuralInteger,
      component: Component,
      operationIdentity: AnyRef,
      label: String,
      indexName: String,
      sourceLocation: Option[String]
  )(body: => Unit): Unit = {
    requireReference(analyzed, "analyzed process-range wrapper", sourceLocation)
    requireReference(component, "process-range component", sourceLocation)
    requireReference(operationIdentity, "process-range operation", sourceLocation)
    val (_, count) = analyzed.claim(
      AnalyzedStructuralIntegerKind.ProcessRangeCount,
      Vector(component, operationIdentity)
    )
    ParameterizedProcess.captureAnalyzedFrontendRange(
      component,
      label,
      indexName,
      count,
      sourceLocation
    )(body)
  }

  def prepareStructuralIf(
      analyzed: AnalyzedStructuralBoolean,
      operationIdentity: AnyRef,
      component: Component,
      sourceLocation: Option[String]
  ): ExternalAnalyzedStructuralPredicate = {
    requireReference(analyzed, "analyzed generate-if wrapper", sourceLocation)
    requireReference(operationIdentity, "generate-if operation", sourceLocation)
    requireReference(component, "generate-if component", sourceLocation)
    val (sourceIdentity, condition, singleRootEvaluations) = analyzed.claim(
      AnalyzedStructuralBooleanKind.StructuralIfCondition,
      Vector(component, operationIdentity)
    )
    val predicateDomain = singleRootEvaluations.map { evaluations =>
      val permit = ExternalStructuralPredicatePermit.analyzed(
        sourceIdentity,
        condition,
        evaluations,
        component,
        operationIdentity
      )
      ParameterizedStructure.analyzedPredicateDomainOf(
        component,
        operationIdentity,
        sourceIdentity,
        condition,
        evaluations,
        permit
      )
    }
    new ExternalAnalyzedStructuralPredicate(
      component,
      operationIdentity,
      condition,
      predicateDomain,
      sourceLocation
    )
  }

  def requirePreparedStructuralIf(
      prepared: ExternalAnalyzedStructuralPredicate,
      component: Component,
      operationIdentity: AnyRef,
      condition: ElaborationBooleanExpression,
      sourceLocation: Option[String]
  ): Unit = {
    requireReference(prepared, "prepared generate-if predicate", sourceLocation)
    requireReference(component, "generate-if component", sourceLocation)
    requireReference(operationIdentity, "generate-if operation", sourceLocation)
    requireReference(condition, "generate-if condition", sourceLocation)
    prepared.requireIdentities(
      component,
      operationIdentity,
      condition,
      sourceLocation
    )
  }

  def captureStructuralIfBranch(
      prepared: ExternalAnalyzedStructuralPredicate,
      branch: Int,
      sourceLocation: Option[String]
  )(body: => Unit): ParameterizedStructuralBlock = {
    requireReference(prepared, "prepared generate-if predicate", sourceLocation)
    val plan = prepared.beginCapture(branch, sourceLocation)
    try {
      val block = plan match {
        case Some((root, admitted)) if admitted.nonEmpty =>
          ParameterizedStructure.captureExactBlock(
            prepared.component,
            root,
            admitted,
            sourceLocation
          )(body)
        case _ =>
          ParameterizedStructure.captureBlock(
            prepared.component,
            sourceLocation
          )(body)
      }
      prepared.finishCapture(branch, block, sourceLocation)
      block
    } catch {
      case NonFatal(error) =>
        prepared.abortCapture(branch)
        throw error
    }
  }

  def registerStructuralIf(
      prepared: ExternalAnalyzedStructuralPredicate,
      operationIdentity: AnyRef,
      pending: ParameterizedStructuralPending,
      whenTrueLabel: String,
      whenFalseLabel: String,
      whenTrue: ParameterizedStructuralBlock,
      whenFalse: ParameterizedStructuralBlock,
      sourceLocation: Option[String]
  ): Unit = {
    requireReference(prepared, "prepared generate-if predicate", sourceLocation)
    requireReference(operationIdentity, "generate-if operation", sourceLocation)
    requireReference(pending, "generate-if pending target", sourceLocation)
    val predicateDomain = prepared.claimRegistration(
      pending.component,
      operationIdentity,
      prepared.condition,
      whenTrue,
      whenFalse,
      sourceLocation
    )
    ParameterizedStructure.registerIf(
      pending,
      prepared.condition,
      whenTrueLabel,
      whenFalseLabel,
      whenTrue,
      whenFalse,
      sourceLocation,
      predicateDomain
    )
  }

  def registerStructuralCase(
      analyzed: AnalyzedStructuralInteger,
      operationIdentity: AnyRef,
      pending: ParameterizedStructuralPending,
      choices: Vector[(BigInt, String, ParameterizedStructuralBlock)],
      defaultLabel: String,
      defaultBody: ParameterizedStructuralBlock,
      sourceLocation: Option[String]
  ): Unit = {
    requireReference(analyzed, "analyzed generate-case wrapper", sourceLocation)
    requireReference(operationIdentity, "generate-case operation", sourceLocation)
    requireReference(pending, "generate-case pending target", sourceLocation)
    val (_, selector) = analyzed.claim(
      AnalyzedStructuralIntegerKind.StructuralCaseSelector,
      Vector(pending.component, operationIdentity)
    )
    ParameterizedStructure.registerCase(
      pending,
      selector,
      choices,
      defaultLabel,
      defaultBody,
      sourceLocation
    )
  }

  def recordProcessSlice(
      offset: AnalyzedStructuralInteger,
      width: AnalyzedStructuralInteger,
      source: BitVector,
      result: BitVector,
      sourceLocation: Option[String]
  ): Unit = {
    val targets = sliceTargets(source, result, sourceLocation)
    requireReference(offset, "analyzed process-slice offset", sourceLocation)
    requireReference(width, "analyzed process-slice width", sourceLocation)
    val (_, offsetExpression) = offset.claim(
      AnalyzedStructuralIntegerKind.ProcessSliceOffset,
      targets
    )
    val (_, widthExpression) = width.claim(
      AnalyzedStructuralIntegerKind.ProcessSliceWidth,
      targets
    )
    ParameterizedProcess.recordSlice(
      source,
      result,
      offsetExpression,
      widthExpression,
      sourceLocation
    )
  }

  def recordStructuralSlice(
      offset: AnalyzedStructuralInteger,
      width: AnalyzedStructuralInteger,
      source: BitVector,
      result: BitVector,
      sourceLocation: Option[String]
  ): Unit = {
    val targets = sliceTargets(source, result, sourceLocation)
    requireReference(offset, "analyzed structural-slice offset", sourceLocation)
    requireReference(width, "analyzed structural-slice width", sourceLocation)
    val (_, offsetExpression) = offset.claim(
      AnalyzedStructuralIntegerKind.StructuralSliceOffset,
      targets
    )
    val (_, widthExpression) = width.claim(
      AnalyzedStructuralIntegerKind.StructuralSliceWidth,
      targets
    )
    ParameterizedStructure.recordSlice(
      source,
      result,
      offsetExpression,
      widthExpression,
      sourceLocation
    )
  }

  def recordProcessVecIndex[T <: Data](
      analyzed: AnalyzedStructuralInteger,
      vector: Vec[T],
      selected: T,
      sourceLocation: Option[String]
  ): T = {
    val targets = vecTargets(vector, selected, sourceLocation)
    requireReference(analyzed, "analyzed process Vec index", sourceLocation)
    val (_, index) = analyzed.claim(
      AnalyzedStructuralIntegerKind.ProcessVecIndex,
      targets
    )
    ParameterizedProcess.recordVecIndex(
      vector,
      selected,
      index,
      sourceLocation
    )
  }

  def recordStructuralVecIndex[T <: Data](
      analyzed: AnalyzedStructuralInteger,
      vector: Vec[T],
      selected: T,
      sourceLocation: Option[String]
  ): T = {
    val targets = vecTargets(vector, selected, sourceLocation)
    requireReference(analyzed, "analyzed structural Vec index", sourceLocation)
    val (_, index) = analyzed.claim(
      AnalyzedStructuralIntegerKind.StructuralVecIndex,
      targets
    )
    ParameterizedStructure.recordVecIndex(
      vector,
      selected,
      index,
      sourceLocation
    )
  }

  private def sliceTargets(
      source: BitVector,
      result: BitVector,
      sourceLocation: Option[String]
  ): Vector[AnyRef] = {
    requireReference(source, "structural-slice source", sourceLocation)
    requireReference(result, "structural-slice result", sourceLocation)
    Vector(source, result)
  }

  private def vecTargets[T <: Data](
      vector: Vec[T],
      selected: T,
      sourceLocation: Option[String]
  ): Vector[AnyRef] = {
    requireReference(vector, "structural Vec source", sourceLocation)
    requireReference(selected, "structural Vec result", sourceLocation)
    Vector(vector, selected)
  }

  private def requireReference(
      value: AnyRef,
      role: String,
      sourceLocation: Option[String]
  ): Unit =
    if (value eq null) {
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-ANALYZED-PUBLISHER-TARGET-MISSING",
        s"$role must not be null",
        sourceLocation
      )
    }
}
