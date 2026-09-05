package morphhdl

import morphhdl.runtime.ParameterizedVerilogMode
import spinal.core.{GlobalData, SpinalConfig, Verilog}
import spinal.core.internals.{MorphHdlSignednessAnalysis, MorphHdlSignedDeclarationPolicy, Phase, PhaseVerilog}
import scala.collection.mutable.ArrayBuffer

/** Experimental Increment 60c publication mode. Existing signed expression
  * casts are retained. Ordinary SpinalVerilog and VHDL remain unchanged.
  *
  * MorphVerilog(MorphSignedDeclarations.enable(config)) { new Design(...) }
  */
object MorphSignedDeclarations {
  private object Enabled

  def isEnabled(config: SpinalConfig): Boolean =
    config != null && config.flags.contains(Enabled)

  private def install(phases: ArrayBuffer[Phase]): Unit = {
    val emitters = phases.collect { case phase: PhaseVerilog => phase }
    // VHDL is deliberately unaffected by this MorphVerilog-only option.
    if (emitters.isEmpty) return
    require(emitters.size == 1, "signed declarations require one native Verilog emitter")
    val emitter = emitters.head
    MorphHdlSignednessAnalysis.install { snapshot =>
      val current = GlobalData.get.config
      if (isEnabled(current) && ParameterizedVerilogMode.isEnabled(current)) {
        require(current.mode == Verilog, "signed declarations require strict Verilog publication")
        MorphHdlSignedDeclarationPolicy.bind(emitter, snapshot)
      }
    }(phases)
  }

  def enable(config: SpinalConfig): SpinalConfig = {
    require(config != null, "SpinalConfig must not be null")
    val flags = config.flags.clone()
    val inserters = config.phasesInserters.clone()
    if (!inserters.contains(installer)) inserters += installer
    flags += Enabled
    config.copy(flags = flags, phasesInserters = inserters)
  }

  def disable(config: SpinalConfig): SpinalConfig = {
    require(config != null, "SpinalConfig must not be null")
    val flags = config.flags.clone()
    flags -= Enabled
    config.copy(flags = flags, phasesInserters = config.phasesInserters.clone())
  }

  private val installer: ArrayBuffer[Phase] => Unit = install _
}
