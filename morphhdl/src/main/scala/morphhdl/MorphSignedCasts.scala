package morphhdl

import spinal.core.SpinalConfig
import spinal.core.internals.Phase
import scala.collection.mutable.ArrayBuffer

/** Experimental 60d option. Enables signed declarations and removes only
  * proven redundant casts on pure SInt operations. Declaration-only mode and
  * ordinary SpinalVerilog/VHDL retain their existing output.
  */
object MorphSignedCasts {
  // Exact installer identity is a configuration marker, not a global flag.
  // The declaration installer owns the single pre-emission analysis phase.
  private val marker: ArrayBuffer[Phase] => Unit = _ => ()

  def isEnabled(config: SpinalConfig): Boolean =
    MorphSignedDeclarations.isEnabled(config) && config.phasesInserters.contains(marker)

  def enable(config: SpinalConfig): SpinalConfig = {
    val enabled = MorphSignedDeclarations.enable(config)
    if (!enabled.phasesInserters.contains(marker)) enabled.phasesInserters += marker
    enabled
  }

  /** Disable cast cleanup but retain the separately enabled declarations. */
  def disable(config: SpinalConfig): SpinalConfig = {
    require(config != null, "SpinalConfig must not be null")
    val inserters = config.phasesInserters.clone()
    inserters -= marker
    config.copy(flags = config.flags.clone(), phasesInserters = inserters)
  }
}
