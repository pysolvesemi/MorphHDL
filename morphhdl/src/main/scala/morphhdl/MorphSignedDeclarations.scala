package morphhdl

import morphhdl.runtime.ParameterizedVerilogMode
import spinal.core.{GlobalData, SpinalConfig, Verilog}
import spinal.core.internals.{MorphHdlSignednessAnalysis, MorphHdlSignedDeclarationPolicy, Phase, PhaseVerilog}
import scala.collection.mutable.ArrayBuffer

/** Explicit declaration-only publication policy. MorphVerilog defaults to
  * signed declarations with minimal casts; enable selects declarations while
  * retaining casts, and disable selects legacy unsigned declarations.
  * Ordinary SpinalVerilog and VHDL remain unchanged in every mode.
  *
  * MorphVerilog(MorphSignedDeclarations.enable(config)) { new Design(...) }
  */
object MorphSignedDeclarations {
  // Preserve explicit opt-out across copies without changing SpinalConfig or
  // introducing process/thread-global state. This exact marker emits nothing.
  private val disabledMarker: ArrayBuffer[Phase] => Unit = _ => ()

  /** Resolve the default only on MorphVerilog's isolated single-source config.
    * An explicit declaration-only or legacy selection always wins.
    */
  private[morphhdl] def forPublication(config: SpinalConfig): SpinalConfig = {
    require(config != null, "SpinalConfig must not be null")
    if (isEnabled(config) || config.phasesInserters.contains(disabledMarker)) config
    else if (MorphSignedCasts.isDisabled(config)) enable(config)
    else MorphSignedCasts.enable(config)
  }

  def isEnabled(config: SpinalConfig): Boolean =
    config != null && config.phasesInserters.contains(installer)

  private def install(phases: ArrayBuffer[Phase]): Unit = {
    val emitters = phases.collect { case phase: PhaseVerilog => phase }
    // VHDL is deliberately unaffected by this MorphVerilog-only option.
    if (emitters.isEmpty) return
    require(emitters.size == 1, "signed declarations require one native Verilog emitter")
    val emitter = emitters.head
    MorphHdlSignednessAnalysis.installPublication(snapshot => {
      val current = GlobalData.get.config
      if (isEnabled(current) && ParameterizedVerilogMode.isEnabled(current)) {
        require(current.mode == Verilog, "signed declarations require strict Verilog publication")
        MorphHdlSignedDeclarationPolicy.bind(emitter, snapshot, MorphSignedCasts.isEnabled(current))
      }
    }, () => {
      val current = GlobalData.get.config
      isEnabled(current) && ParameterizedVerilogMode.isEnabled(current)
    })(phases)
  }

  def enable(config: SpinalConfig): SpinalConfig = {
    require(config != null, "SpinalConfig must not be null")
    val flags = config.flags.clone()
    val inserters = config.phasesInserters.clone()
    inserters -= disabledMarker
    if (!inserters.contains(installer)) inserters += installer
    config.copy(flags = flags, phasesInserters = inserters)
  }

  def disable(config: SpinalConfig): SpinalConfig = {
    require(config != null, "SpinalConfig must not be null")
    val flags = config.flags.clone()
    val inserters = config.phasesInserters.clone()
    inserters -= installer
    if (!inserters.contains(disabledMarker)) inserters += disabledMarker
    config.copy(flags = flags, phasesInserters = inserters)
  }

  private val installer: ArrayBuffer[Phase] => Unit = install _
}
