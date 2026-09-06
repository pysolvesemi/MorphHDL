package morphhdl

import spinal.core.SpinalConfig
import spinal.core.internals.Phase
import scala.collection.mutable.ArrayBuffer

/** Select signed declarations and proven minimal casts explicitly. This is
  * also MorphVerilog's default for an otherwise unconfigured publication.
  * Real signedness boundaries retain casts. Ordinary SpinalVerilog/VHDL and
  * the explicit legacy/declaration-only modes retain their existing output.
  */
object MorphSignedCasts {
  // Exact installer identity is a configuration marker, not a global flag.
  // The declaration installer owns the single pre-emission analysis phase.
  private val marker: ArrayBuffer[Phase] => Unit = _ => ()
  private val disabledMarker: ArrayBuffer[Phase] => Unit = _ => ()

  private[morphhdl] def isDisabled(config: SpinalConfig): Boolean =
    config != null && config.phasesInserters.contains(disabledMarker)

  def isEnabled(config: SpinalConfig): Boolean =
    MorphSignedDeclarations.isEnabled(config) && config.phasesInserters.contains(marker)

  def enable(config: SpinalConfig): SpinalConfig = {
    val enabled = MorphSignedDeclarations.enable(config)
    enabled.phasesInserters -= disabledMarker
    if (!enabled.phasesInserters.contains(marker)) enabled.phasesInserters += marker
    enabled
  }

  /** Disable cleanup, retaining declarations (or selecting declaration-only
    * when MorphVerilog resolves an otherwise unconfigured publication).
    */
  def disable(config: SpinalConfig): SpinalConfig = {
    require(config != null, "SpinalConfig must not be null")
    val inserters = config.phasesInserters.clone()
    inserters -= marker
    if (!inserters.contains(disabledMarker)) inserters += disabledMarker
    config.copy(flags = config.flags.clone(), phasesInserters = inserters)
  }
}
