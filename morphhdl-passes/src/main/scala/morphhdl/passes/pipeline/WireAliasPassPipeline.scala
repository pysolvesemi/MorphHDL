package morphhdl.passes.pipeline

import morphhdl.ir.v1.CanonicalIrHandoff
import morphhdl.ir.v1.Design
import morphhdl.ir.v1.RtlExpr
import morphhdl.passes.adapter.CanonicalIrPassAdapter
import morphhdl.passes.api.DiagnosticSeverity
import morphhdl.passes.api.EliminatedWireAlias
import morphhdl.passes.api.EliminatedWireExpression
import morphhdl.passes.api.EliminationReport
import morphhdl.passes.api.PassDiagnostic
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.PassId
import morphhdl.passes.api.PassResult
import morphhdl.passes.api.RejectedWireAlias
import morphhdl.passes.api.SimplifiedExpression
import morphhdl.passes.api.WireAliasPassConfiguration
import morphhdl.passes.safety.AliasSafetyConfiguration
import morphhdl.passes.transform.ConstantOperandSimplificationPass
import morphhdl.passes.transform.NamedWireAliasEliminationPass
import morphhdl.passes.transform.UnnamedWireAliasEliminationPass
import morphhdl.passes.transform.UnnamedWireExpressionEliminationPass

/**
  * Immutable evidence for one ordered execution of the optional passes.
  *
  * Successful stage reports aggregate fixed-point rounds in production pass
  * order. Rejections describe the final round; transformations retain all
  * rounds' evidence. On failure, chronological stage evidence is retained and
  * the published output is always the original input.
  */
final case class WireAliasPipelineResult(
    output: Design,
    status: PassExecutionStatus,
    stages: Vector[PassResult[Design]]
) {
  require(output != null, "wire-assignment pipeline output must not be null")
  require(stages != null, "wire-assignment pipeline stages must not be null")
  require(
    status != PassExecutionStatus.Skipped || stages.isEmpty,
    "a skipped wire-assignment pipeline cannot publish executed stages"
  )
  require(
    status != PassExecutionStatus.Failed || stages.lastOption.exists(_.status.failed),
    "a failed wire-assignment pipeline must end with a failed stage"
  )
  require(
    status != PassExecutionStatus.Changed || stages.exists(_.changed),
    "a changed wire-assignment pipeline must contain a changed stage"
  )
  require(
    status != PassExecutionStatus.Unchanged ||
      (stages.nonEmpty && stages.forall(_.status == PassExecutionStatus.Unchanged)),
    "an unchanged wire-assignment pipeline requires only unchanged stages"
  )

  def executedPasses: Vector[PassId] = stages.map(_.eliminationReport.passId)
  def diagnostics: Vector[PassDiagnostic] = stages.flatMap(_.diagnostics)
  def eliminationReports: Vector[EliminationReport] = stages.map(_.eliminationReport)
  def eliminated: Vector[EliminatedWireAlias] = eliminationReports.flatMap(_.eliminated)
  def eliminatedExpressions: Vector[EliminatedWireExpression] =
    eliminationReports.flatMap(_.eliminatedExpressions)
  def simplifiedExpressions: Vector[SimplifiedExpression] =
    eliminationReports.flatMap(_.simplifiedExpressions)
  def rejected: Vector[RejectedWireAlias] = eliminationReports.flatMap(_.rejected)
  def changed: Boolean = status.changed
  def isSuccess: Boolean = !status.failed
  def hasErrors: Boolean = diagnostics.exists(_.severity == DiagnosticSeverity.Error)

  def normalized: WireAliasPipelineResult = copy(stages = stages.map(_.normalized))
}

/**
  * Component-generic all-or-none entrypoint for canonical-IR rewrites.
  *
  * Product callers have one flag. Each fixed-point round executes unnamed
  * direct aliases, named direct aliases, unnamed continuous expressions, then
  * constant-operand simplification. Historical regression selections without
  * the new simplification stage retain their original single-round behavior.
  * No stage inspects generated HDL, identifiers, filenames or component names.
  */
object WireAliasPassPipeline {
  /** Historical WA-06 two-pass identifier retained for its proof artifacts. */
  val combinedPassId: String = Vector(
    PassId.UnnamedWireAliasElimination,
    PassId.NamedWireAliasElimination
  ).map(_.value).mkString("+")

  /** Historical WA-07 identifier retained independently of the current pipeline. */
  val historicalAllPassId: String =
    PassId.historicalWireAssignmentPasses.map(_.value).mkString("+")

  val allPassId: String = PassId.allWireAssignmentPasses.map(_.value).mkString("+")

  private def stageConfiguration(passId: PassId): WireAliasPassConfiguration =
    WireAliasPassConfiguration.selectedForTesting(passId)

  def run(
      handoff: CanonicalIrHandoff,
      configuration: WireAliasPassConfiguration,
      safetyConfiguration: AliasSafetyConfiguration
  ): WireAliasPipelineResult = {
    require(handoff != null, "canonical IR handoff must not be null")
    run(CanonicalIrPassAdapter.bind(handoff).design, configuration, safetyConfiguration)
  }

  def run(handoff: CanonicalIrHandoff): WireAliasPipelineResult =
    run(handoff, WireAliasPassConfiguration(), AliasSafetyConfiguration())

  private def execute(
      design: Design,
      passId: PassId,
      safetyConfiguration: AliasSafetyConfiguration
  ): PassResult[Design] = passId match {
    case PassId.UnnamedWireAliasElimination =>
      UnnamedWireAliasEliminationPass.run(design, stageConfiguration(passId), safetyConfiguration)
    case PassId.NamedWireAliasElimination =>
      NamedWireAliasEliminationPass.run(design, stageConfiguration(passId), safetyConfiguration)
    case PassId.UnnamedWireExpressionElimination =>
      UnnamedWireExpressionEliminationPass.run(design, stageConfiguration(passId))
    case PassId.ConstantOperandSimplification =>
      val result = ConstantOperandSimplificationPass.run(design)
      val report = EliminationReport(passId, simplifiedExpressions = result.rewrites.map { rewrite =>
        SimplifiedExpression(rewrite.module.value, rewrite.driver.value, rewrite.expressionPath, rewrite.rule)
      })
      PassResult(result.output, result.status, result.diagnostics, report).normalized
    case other =>
      throw new IllegalArgumentException(s"unsupported wire-assignment pipeline pass '${other.value}'")
  }

  private def accumulate(previous: PassResult[Design], current: PassResult[Design]): PassResult[Design] = {
    val old = previous.eliminationReport
    val now = current.eliminationReport
    require(old.passId == now.passId, "fixed-point report order changed")
    val report = now.copy(
      eliminated = old.eliminated ++ now.eliminated,
      eliminatedExpressions = old.eliminatedExpressions ++ now.eliminatedExpressions,
      simplifiedExpressions = old.simplifiedExpressions ++ now.simplifiedExpressions
    )
    PassResult(
      current.output,
      if (report.changedCount > 0) PassExecutionStatus.Changed else PassExecutionStatus.Unchanged,
      (previous.diagnostics ++ current.diagnostics).distinct,
      report
    ).normalized
  }

  /**
    * Strict lexicographic progress measure. Alias/inlining stages remove a
    * declaration; simplification removes a binary/mux node, or a unary node.
    * Inlining may duplicate expressions, so declaration count comes first.
    */
  private def progressMeasure(design: Design): (Long, Long, Long) = {
    def count(expr: RtlExpr): (Long, Long) = {
      val children: Vector[RtlExpr] = expr match {
        case _: RtlExpr.Ref | _: RtlExpr.Literal => Vector.empty
        case RtlExpr.Unary(_, value) => Vector(value)
        case RtlExpr.Binary(_, left, right) => Vector(left, right)
        case RtlExpr.Mux(condition, yes, no) => Vector(condition, yes, no)
        case RtlExpr.Concat(values) => values
        case RtlExpr.BitSelect(value, index) => Vector(value, index)
        case RtlExpr.PartSelect(value, _, _) => Vector(value)
        case RtlExpr.Resize(value, _, _) => Vector(value)
        case RtlExpr.Cast(value, _) => Vector(value)
      }
      val branch = expr match {
        case _: RtlExpr.Binary | _: RtlExpr.Mux => 1L
        case _ => 0L
      }
      children.map(count).foldLeft((branch, 1L)) { case ((b, n), (cb, cn)) => (b + cb, n + cn) }
    }
    val counts = design.modules.flatMap(_.drivers).map(d => count(d.value))
      .foldLeft((0L, 0L)) { case ((b, n), (cb, cn)) => (b + cb, n + cn) }
    (design.modules.map(_.declarations.size.toLong).sum, counts._1, counts._2)
  }

  def run(
      design: Design,
      configuration: WireAliasPassConfiguration = WireAliasPassConfiguration(),
      safetyConfiguration: AliasSafetyConfiguration = AliasSafetyConfiguration()
  ): WireAliasPipelineResult = {
    require(design != null, "canonical IR design must not be null")
    require(configuration != null, "wire-assignment pipeline configuration must not be null")
    require(safetyConfiguration != null, "alias safety configuration must not be null")

    val enabled = configuration.enabledPasses
    if (enabled.isEmpty) {
      WireAliasPipelineResult(design, PassExecutionStatus.Skipped, Vector.empty)
    } else {
      val fixedPoint = enabled.contains(PassId.ConstantOperandSimplification)
      var current = design
      var aggregated = Vector.empty[PassResult[Design]]
      val history = Vector.newBuilder[PassResult[Design]]
      var repeat = true
      while (repeat) {
        // Validation occurs in the first stage before counting untrusted IR.
        val before = current
        val round = Vector.newBuilder[PassResult[Design]]
        enabled.foreach { passId =>
          val stage = execute(current, passId, safetyConfiguration)
          round += stage
          history += stage
          if (!stage.isSuccess)
            return WireAliasPipelineResult(design, PassExecutionStatus.Failed, history.result()).normalized
          current = stage.output
        }
        val stages = round.result()
        aggregated = if (aggregated.isEmpty) stages
          else aggregated.zip(stages).map { case (previous, stage) => accumulate(previous, stage) }
        repeat = fixedPoint && stages.exists(_.changed)
        if (repeat && !implicitly[Ordering[(Long, Long, Long)]].lt(progressMeasure(current), progressMeasure(before))) {
          val failure = PassResult.failed(
            design,
            EliminationReport(PassId.ConstantOperandSimplification),
            Vector(PassDiagnostic("WA07A-PIPELINE-NONDECREASING", DiagnosticSeverity.Error,
              "a changed pipeline round did not decrease its termination measure; original input retained",
              Some(PassId.ConstantOperandSimplification)))
          )
          history += failure
          return WireAliasPipelineResult(design, PassExecutionStatus.Failed, history.result()).normalized
        }
      }
      WireAliasPipelineResult(
        current,
        if (aggregated.exists(_.changed)) PassExecutionStatus.Changed else PassExecutionStatus.Unchanged,
        aggregated
      ).normalized
    }
  }
}
