package morphhdl.passes.pipeline

import morphhdl.ir.v1.CanonicalIrHandoff
import morphhdl.ir.v1.Design
import morphhdl.passes.adapter.CanonicalIrPassAdapter
import morphhdl.passes.api.EliminatedWireAlias
import morphhdl.passes.api.EliminationReport
import morphhdl.passes.api.InlinedWireExpression
import morphhdl.passes.api.PassDiagnostic
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.PassId
import morphhdl.passes.api.PassResult
import morphhdl.passes.api.RejectedWireAlias
import morphhdl.passes.api.WireAliasPassConfiguration
import morphhdl.passes.safety.AliasSafetyConfiguration
import morphhdl.passes.transform.NamedWireAliasEliminationPass
import morphhdl.passes.transform.UnnamedWireAliasEliminationPass
import morphhdl.passes.transform.UnnamedWireExpressionEliminationPass

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
  def inlinedExpressions: Vector[InlinedWireExpression] =
    eliminationReports.flatMap(_.inlinedExpressions)
  def rejected: Vector[RejectedWireAlias] = eliminationReports.flatMap(_.rejected)
  def changed: Boolean = status.changed
  def isSuccess: Boolean = !status.failed
  def hasErrors: Boolean = diagnostics.exists(
    _.severity == morphhdl.passes.api.DiagnosticSeverity.Error
  )
  def normalized: WireAliasPipelineResult = copy(stages = stages.map(_.normalized))
}

/**
  * One component-generic, all-or-none entrypoint. When enabled, the fixed
  * order is direct unnamed aliases, direct named aliases, then unnamed
  * continuous expressions. Any failed stage publishes the original input.
  */
object WireAliasPassPipeline {
  val combinedPassId: String = Vector(
    PassId.UnnamedWireAliasElimination,
    PassId.NamedWireAliasElimination,
    PassId.UnnamedWireExpressionElimination
  ).map(_.value).mkString("+")

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

  def run(
      design: Design,
      configuration: WireAliasPassConfiguration = WireAliasPassConfiguration(),
      safetyConfiguration: AliasSafetyConfiguration = AliasSafetyConfiguration()
  ): WireAliasPipelineResult = {
    require(design != null, "canonical IR design must not be null")
    require(configuration != null, "wire-assignment configuration must not be null")
    require(safetyConfiguration != null, "wire-assignment safety configuration must not be null")

    val enabled = configuration.enabledPasses
    if (enabled.isEmpty) {
      WireAliasPipelineResult(design, PassExecutionStatus.Skipped, Vector.empty)
    } else {
      val completed = Vector.newBuilder[PassResult[Design]]
      var current = design
      var index = 0
      while (index < enabled.size) {
        val stage = enabled(index) match {
          case PassId.UnnamedWireAliasElimination =>
            UnnamedWireAliasEliminationPass.run(current, configuration, safetyConfiguration)
          case PassId.NamedWireAliasElimination =>
            NamedWireAliasEliminationPass.run(current, configuration, safetyConfiguration)
          case PassId.UnnamedWireExpressionElimination =>
            UnnamedWireExpressionEliminationPass.run(current, configuration, safetyConfiguration)
          case other =>
            throw new IllegalArgumentException(
              s"unsupported wire-assignment pipeline pass '${other.value}'"
            )
        }
        completed += stage
        if (!stage.isSuccess) {
          return WireAliasPipelineResult(
            output = design,
            status = PassExecutionStatus.Failed,
            stages = completed.result()
          ).normalized
        }
        current = stage.output
        index += 1
      }

      val stages = completed.result()
      WireAliasPipelineResult(
        output = current,
        status =
          if (stages.exists(_.changed)) PassExecutionStatus.Changed
          else PassExecutionStatus.Unchanged,
        stages = stages
      ).normalized
    }
  }
}
