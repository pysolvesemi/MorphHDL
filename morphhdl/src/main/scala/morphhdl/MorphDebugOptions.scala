package morphhdl

import spinal.core.SpinalConfig

/** Simulation-only debug presentation options for MorphVerilog.
  * Enum-to-string helpers are suppressed by default. Hardware logic and
  * assertions are unaffected; enum report arguments use their numeric encoding.
  */
object MorphDebugOptions {
  private case class Selection(suppressEnumDebugStrings: Boolean)

  /** Return a private configuration copy; never change the caller's flags. */
  def apply(config: SpinalConfig,
      suppressEnumDebugStrings: Boolean = true): SpinalConfig = {
    require(config != null, "SpinalConfig must not be null")
    val flags = config.flags.clone()
    flags.retain(!_.isInstanceOf[Selection])
    flags += Selection(suppressEnumDebugStrings)
    config.copy(flags = flags, phasesInserters = config.phasesInserters.clone(),
      scopeProperties = config.scopeProperties.clone())
  }

  private[morphhdl] def isSelection(value: Any): Boolean = value.isInstanceOf[Selection]

  /** Resolve only on the isolated native configuration owned by MorphVerilog.
    * Opting out preserves the caller's existing native enum-string setting.
    */
  private[morphhdl] def forPublication(config: SpinalConfig): SpinalConfig = {
    val suppress = config.flags.collectFirst {
      case value: Selection => value.suppressEnumDebugStrings
    }.getOrElse(true)
    if (suppress) config.withoutEnumString() else config
  }
}
