package morphhdl.runtime

import spinal.core.{GlobalData, SpinalConfig}

/** MorphHDL-owned parameterized-generation mode marker.
  *
  * The marker is carried through SpinalHDL's baseline `flags` collection so
  * enabling MorphHDL does not add a constructor field to `SpinalConfig`. Every
  * update clones the mutable collection before changing it; a caller's config
  * is therefore never mutated as a side effect.
  */
object ParameterizedVerilogMode {
  private object Enabled

  def isEnabled(config: SpinalConfig): Boolean =
    config != null && config.flags.contains(Enabled)

  def isEnabledInCurrentElaboration: Boolean =
    try isEnabled(GlobalData.get.config)
    catch { case _: Throwable => false }

  def enable(config: SpinalConfig): SpinalConfig =
    copyWithEnabled(config, enabled = true)

  def disable(config: SpinalConfig): SpinalConfig =
    copyWithEnabled(config, enabled = false)

  private def copyWithEnabled(config: SpinalConfig, enabled: Boolean): SpinalConfig = {
    if (config == null) {
      throw new IllegalArgumentException("SpinalConfig must not be null")
    }
    val flags = config.flags.clone()
    if (enabled) flags += Enabled else flags -= Enabled
    config.copy(flags = flags)
  }
}
