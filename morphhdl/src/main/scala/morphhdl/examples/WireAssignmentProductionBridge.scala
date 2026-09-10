package morphhdl.examples

import scala.collection.mutable.ArrayBuffer

import morphhdl.passes.api.{PassId, WireAliasPassConfiguration}
import spinal.core.SpinalConfig
import spinal.core.internals.{
  Phase,
  PhaseContext,
  PhaseRemoveIntermediateUnnameds
}

/** MorphHDL-owned installation and writeback glue for WA-08.
  *
  * Pass algorithms remain in the isolated morphhdl-passes workspace. This
  * bridge only places the already-qualified identity-preserving native adapters
  * at the production pre-name-allocation boundary and executes them in the
  * exact order selected by the one common canonical configuration.
  */
private[morphhdl] object WireAssignmentProductionBridge {
  def enable(config: SpinalConfig): SpinalConfig = {
    if (config == null)
      throw new IllegalArgumentException("SpinalConfig must not be null")

    val inserters = config.phasesInserters.clone()
    inserters += install _
    config.copy(
      flags = config.flags.clone(),
      debugComponents = config.debugComponents.clone(),
      phasesInserters = inserters,
      transformationPhases = config.transformationPhases.clone(),
      memBlackBoxers = config.memBlackBoxers.clone(),
      scopeProperties = config.scopeProperties.clone()
    )
  }

  private def install(phases: ArrayBuffer[Phase]): Unit = {
    if (phases == null || phases.exists(_ == null))
      throw new IllegalArgumentException(
        "WA-08 production handoff requires a complete native phase plan"
      )

    val cleanup = phases.zipWithIndex.collect {
      case (_: PhaseRemoveIntermediateUnnameds, index) => index
    }.toVector
    if (cleanup.size < 3)
      throw new IllegalStateException(
        s"WA-08 production handoff expected at least three native intermediate-removal phases, found ${cleanup.size}"
      )

    // Preserve the exact graph identities and source-level names qualified by
    // WA-04..WA-07b, then replace only the final native cleanup with the fixed
    // canonical pipeline. A fresh phase is created for every generation attempt.
    phases.update(cleanup(1), new PhaseRemoveIntermediateUnnameds(true))
    cleanup.drop(3).reverse.foreach(index => phases.remove(index))
    phases.update(cleanup(2), new ProductionWireAssignmentPhase)
  }
}

private final class ProductionWireAssignmentPhase extends Phase {
  private val expectedOrder = WireAliasPassConfiguration(enabled = true).enabledPasses
  private var completed = false

  override def hasNetlistImpact: Boolean = true

  override def impl(pc: PhaseContext): Unit = {
    if (completed)
      throw new IllegalStateException("WA-08 production handoff executed more than once")

    val actualOrder = Vector(
      PassId.UnnamedWireAliasElimination,
      PassId.NamedWireAliasElimination,
      PassId.UnnamedWireExpressionElimination,
      PassId.ConstantOperandSimplification,
      PassId.BooleanTernarySimplification
    )
    if (actualOrder != expectedOrder)
      throw new IllegalStateException(
        "WA-08 native writeback order differs from the canonical one-flag pipeline"
      )

    var progress = true
    var rounds = 0
    while (progress) {
      rounds += 1
      if (rounds > 1024)
        throw new IllegalStateException(
          "WA-08 production wire-assignment pipeline failed to converge"
        )

      val unnamed = new UnnamedWireAliasNativePhase
      unnamed.impl(pc)
      val named = new NamedWireAliasNativePhase
      named.impl(pc)
      val expression = new UnnamedWireExpressionNativePhase
      expression.impl(pc)
      val constant = new ConstantOperandNativePhase
      constant.impl(pc)
      val ternary = new BooleanTernaryNativePhase
      ternary.impl(pc)

      progress =
        unnamed.report.eliminatedCount +
          named.report.eliminatedCount +
          expression.report.eliminatedCount +
          constant.changedCount +
          ternary.changedCount > 0
    }

    completed = true
  }
}
