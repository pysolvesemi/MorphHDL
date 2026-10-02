package morphhdl

import morphhdl.examples.WireAssignmentProductionBridge
import spinal.core.SpinalConfig

/** Production handoff for the fixed MorphHDL wire-assignment pipeline.
  *
  * Deprecated: optional wire optimizations are disabled by default and are
  * scheduled for removal. The explicit legacy opt-in remains temporarily for
  * compatibility qualification; normal MorphVerilog generation installs none
  * of these optimizations. Enabling the flag installs the
  * reviewed six-stage canonical-IR pipeline after typed
  * parameterization and width normalization, and before backend naming and
  * structured Verilog-2001 emission.
  */
object MorphWireAssignmentPasses {
  @deprecated("Wire optimizations are disabled by default and scheduled for removal", "59i")
  def apply(
      config: SpinalConfig = SpinalConfig(),
      enabled: Boolean = false
  ): SpinalConfig = {
    if (config == null)
      throw new IllegalArgumentException("SpinalConfig must not be null")
    if (enabled) WireAssignmentProductionBridge.enable(config)
    else WireAssignmentProductionBridge.disable(config)
  }

  @deprecated("Wire optimizations are disabled by default and scheduled for removal", "59i")
  def configure(config: SpinalConfig, enabled: Boolean): SpinalConfig =
    apply(config, enabled)

  private[morphhdl] def forPublication(config: SpinalConfig): SpinalConfig =
    WireAssignmentProductionBridge.forPublication(config)
}
