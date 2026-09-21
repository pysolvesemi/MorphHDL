package morphhdl.examples

import scala.collection.mutable.ArrayBuffer

import morphhdl.passes.api.{PassId, WireAliasPassConfiguration}
import spinal.core.SpinalConfig
import spinal.core.internals.{
  Phase,
  PhaseContext,
  PhaseRemoveUselessStuff,
  PhaseRemoveIntermediateUnnameds,
  PhaseVerilog,
  PhaseAllocateNames,
  MorphHdlEmitterParameterNames,
  VerilogEmitterExpressionInlining
}

/** MorphHDL-owned installation and writeback glue for WA-08.
  *
  * Pass algorithms remain in the isolated morphhdl-passes workspace. This
  * bridge only places the already-qualified identity-preserving native adapters
  * at the production pre-name-allocation boundary and executes them in the
  * exact order selected by the one common canonical configuration.
  */
private[morphhdl] object WireAssignmentProductionBridge {
  // Stable identities preserve explicit selection across config copies and
  // prevent installing the production phase twice.
  private val installer: ArrayBuffer[Phase] => Unit = install _
  private val disabledMarker: ArrayBuffer[Phase] => Unit = _ => ()

  def forPublication(config: SpinalConfig): SpinalConfig = {
    if (config == null)
      throw new IllegalArgumentException("SpinalConfig must not be null")
    if (config.phasesInserters.contains(installer) ||
        config.phasesInserters.contains(disabledMarker)) config
    else enable(config)
  }

  def enable(config: SpinalConfig): SpinalConfig = configure(config, enabled = true)

  def disable(config: SpinalConfig): SpinalConfig = configure(config, enabled = false)

  private def configure(config: SpinalConfig, enabled: Boolean): SpinalConfig = {
    if (config == null)
      throw new IllegalArgumentException("SpinalConfig must not be null")

    val inserters = config.phasesInserters.clone()
    val selected = if (enabled) installer else disabledMarker
    val opposite = if (enabled) disabledMarker else installer
    inserters -= opposite
    if (!inserters.contains(selected)) inserters += selected
    val configured = config.copy(
      flags = config.flags.clone(),
      debugComponents = config.debugComponents.clone(),
      phasesInserters = inserters,
      transformationPhases = config.transformationPhases.clone(),
      memBlackBoxers = config.memBlackBoxers.clone(),
      scopeProperties = config.scopeProperties.clone()
    )
    VerilogEmitterExpressionInlining.configure(configured, enabled)
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
    val sourceIntent = new NativeConditionSourceIntent
    phases.update(cleanup(2), new ProductionWireAssignmentPhase(sourceIntent))
    // Capture after application transformation phases but before any native
    // liveness sweep can infer vital bits. The complete per-generation identity
    // inventory then distinguishes explicit preservation from inferred liveness.
    val firstLiveness = phases.indexWhere(_.isInstanceOf[PhaseRemoveUselessStuff])
    if (firstLiveness < 0 || firstLiveness >= cleanup(2))
      throw new IllegalStateException("condition source intent requires a pre-optimization liveness boundary")
    phases.insert(firstLiveness, sourceIntent)
    // Native helper names share a namespace with parameters published by the
    // external backend. Reserve the complete module inventory after native
    // declaration allocation, leaving the signedness observation immediately
    // adjacent to its emitter as required by the existing phase-plan contract.
    val emitterIndex = phases.indexWhere(_.isInstanceOf[PhaseVerilog])
    if (emitterIndex >= 0) {
      val allocationIndex = phases.indexWhere(_.isInstanceOf[PhaseAllocateNames])
      if (allocationIndex < 0 || allocationIndex >= emitterIndex)
        throw new IllegalStateException("emitter parameter reservation requires prior native name allocation")
      phases.insert(allocationIndex + 1, new MorphHdlEmitterParameterNames)
    }
  }
}

private final class ProductionWireAssignmentPhase(sourceIntent: NativeConditionSourceIntent) extends Phase {
  // Legacy callers have no pre-liveness capture. A fresh, uncaptured inventory
  // fails closed for condition inlining without sharing mutable phase state.
  def this() = this(new NativeConditionSourceIntent)

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
      PassId.NamedWireExpressionElimination,
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

      val unnamed = new UnnamedWireAliasNativePhase(Some(sourceIntent))
      unnamed.impl(pc)
      val named = new NamedWireAliasNativePhase(
        deferPreferredExpressionSource = true, sourceIntent = Some(sourceIntent))
      named.impl(pc)
      val expression = new UnnamedWireExpressionNativePhase(Some(sourceIntent))
      expression.impl(pc)
      val namedExpression = new NamedWireExpressionNativePhase(conditionSourceIntent = Some(sourceIntent))
      namedExpression.impl(pc)
      val constant = new ConstantOperandNativePhase
      constant.impl(pc)
      val ternary = new BooleanTernaryNativePhase
      ternary.impl(pc)

      progress =
        unnamed.report.eliminatedCount +
          named.report.eliminatedCount +
          expression.report.eliminatedCount +
          namedExpression.report.eliminatedCount +
          constant.changedCount +
          ternary.changedCount > 0
    }

    completed = true
  }
}
