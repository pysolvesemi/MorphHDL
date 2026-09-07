package morphhdl

import spinal.core.SpinalConfig
import spinal.core.internals.Phase
import scala.collection.mutable.ArrayBuffer

/** Publish structural Vec values as one packed vector per recursive scalar
  * field path. Source Vec and explicit asBits ordering remain unchanged.
  *
  * This opt-in layout is consumed only by MorphVerilog publication. Disable
  * it explicitly to retain the legacy single packed Vec interface.
  */
object MorphNamedFieldVectors {
  private val marker: ArrayBuffer[Phase] => Unit = _ => ()

  def isEnabled(config: SpinalConfig): Boolean =
    config != null && config.phasesInserters.contains(marker)

  def enable(config: SpinalConfig): SpinalConfig = {
    require(config != null, "SpinalConfig must not be null")
    val inserters = config.phasesInserters.clone()
    if (!inserters.contains(marker)) inserters += marker
    config.copy(flags = config.flags.clone(), phasesInserters = inserters)
  }

  def disable(config: SpinalConfig): SpinalConfig = {
    require(config != null, "SpinalConfig must not be null")
    val inserters = config.phasesInserters.clone()
    inserters -= marker
    config.copy(flags = config.flags.clone(), phasesInserters = inserters)
  }
}
