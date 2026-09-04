package morphhdl.passes.pipeline

import morphhdl.ir.v1.CanonicalIrHandoff
import morphhdl.ir.v1.Design
import morphhdl.passes.adapter.CanonicalIrPassAdapter
import morphhdl.passes.api.EliminatedWireAlias
import morphhdl.passes.api.EliminationReport
import morphhdl.passes.api.PassDiagnostic
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.PassId
import morphhdl.passes.api.PassResult
import morphhdl.passes.api.RejectedWireAlias
import morphhdl.passes.api.WireAliasPassConfiguration
import morphhdl.passes.safety.AliasSafetyConfiguration
import morphhdl.passes.transform.NamedWireAliasEliminationPass
import morphhdl.passes.transform.UnnamedWireAliasEliminationPass

/**
  * Immutable evidence for one ordered execution of the optional wire-alias passes.
  *
  * Stage order is retained exactly as executed. Individual stage reports remain
  * separate so a named-pass decision can never be confused with an unnamed-pass
  * decision. A failed stage rolls the published output back to the original input.
  */
final case class WireAliasPipelineResult(
    output: Design,
    status: PassExecutionStatus,
    stages: Vector[PassResult[Design]]
) {
  require(output != null, "wire-alias pipeline output must not be null")
  require(stages != null, "wire-alias pipeline stages must not be null")
  require(
    status != PassExecutionStatus.Skipped || stages.isEmpty,
    "a skipped wire-alias pipeline cannot publish executed stages"
  )
  require(
    status != PassExecutionStatus.Failed || stages.lastOption.exists(_.status.failed),
    "a failed wire-alias pipeline must end with a failed stage"
  )
  require(
    status != PassExecutionStatus.Changed || stages.exists(_.changed),
    "a changed wire-alias pipeline must contain a changed stage"
  )
  require(
    status != PassExecutionStatus.Unchanged ||
      (stages.nonEmpty && stages.forall(_.status == PassExecutionStatus.Unchanged)),
    "an unchanged wire-alias pipeline requires only unchanged stages"
  )

  def executedPasses: Vector[PassId] =
    stages.map(_.eliminationReport.passId)

  def diagnostics: Vector[PassDiagnostic] =
    stages.flatMap(_.diagnostics)

  def eliminationReports: Vector[EliminationReport] =
    stages.map(_.eliminationReport)

  def eliminated: Vector[EliminatedWireAlias] =
    eliminationReports.flatMap(_.eliminated)

  def rejected: Vector[RejectedWireAlias] =
    eliminationReports.flatMap(_.rejected)

  def changed: Boolean = status.changed
  def isSuccess: Boolean = !status.failed
  def hasErrors: Boolean = diagnostics.exists(_.severity == morphhdl.passes.api.DiagnosticSeverity.Error)

  def normalized: WireAliasPipelineResult =
    copy(stages = stages.map(_.normalized))
}

/**
  * Component-generic ordered entrypoint for the two optional canonical-IR passes.
  *
  * Both passes remain disabled by default. When both are enabled the only legal
  * order is unnamed alias elimination followed by named alias elimination. The
  * pipeline consumes immutable canonical IR and never inspects generated HDL,
  * emitted identifiers, source filenames, or logical module names.
  */
object WireAliasPassPipeline {
  val combinedPassId: String =
    s"${PassId.UnnamedWireAliasElimination.value}+${PassId.NamedWireAliasElimination.value}"

  private val unnamedOnly = WireAliasPassConfiguration(
    eliminateUnnamedAliases = true
  )
  private val namedOnly = WireAliasPassConfiguration(
    eliminateNamedAliases = true
  )

  /** Consume the validated production envelope without discarding its profile. */
  def run(
      handoff: CanonicalIrHandoff,
      configuration: WireAliasPassConfiguration,
      safetyConfiguration: AliasSafetyConfiguration
  ): WireAliasPipelineResult = {
    require(handoff != null, "canonical IR handoff must not be null")
    val initial = CanonicalIrPassAdapter.bind(handoff).design
    run(initial, configuration, safetyConfiguration)
  }

  def run(handoff: CanonicalIrHandoff): WireAliasPipelineResult =
    run(
      handoff,
      WireAliasPassConfiguration(),
      AliasSafetyConfiguration()
    )

  def run(
      design: Design,
      configuration: WireAliasPassConfiguration = WireAliasPassConfiguration(),
      safetyConfiguration: AliasSafetyConfiguration = AliasSafetyConfiguration()
  ): WireAliasPipelineResult = {
    require(design != null, "canonical IR design must not be null")
    require(configuration != null, "wire-alias pipeline configuration must not be null")
    require(safetyConfiguration != null, "alias safety configuration must not be null")

    val enabled = configuration.enabledPasses
    if (enabled.isEmpty) {
      WireAliasPipelineResult(
        output = design,
        status = PassExecutionStatus.Skipped,
        stages = Vector.empty
      )
    } else {
      val completed = Vector.newBuilder[PassResult[Design]]
      var current = design
      var index = 0

      while (index < enabled.size) {
        val stage = enabled(index) match {
          case PassId.UnnamedWireAliasElimination =>
            UnnamedWireAliasEliminationPass.run(
              current,
              unnamedOnly,
              safetyConfiguration
            )
          case PassId.NamedWireAliasElimination =>
            NamedWireAliasEliminationPass.run(
              current,
              namedOnly,
              safetyConfiguration
            )
          case other =>
            throw new IllegalArgumentException(
              s"unsupported wire-alias pipeline pass '${other.value}'"
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
      val status =
        if (stages.exists(_.changed)) PassExecutionStatus.Changed
        else PassExecutionStatus.Unchanged
      WireAliasPipelineResult(
        output = current,
        status = status,
        stages = stages
      ).normalized
    }
  }
}
