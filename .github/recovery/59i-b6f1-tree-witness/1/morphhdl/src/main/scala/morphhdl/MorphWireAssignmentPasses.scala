package morphhdl

import morphhdl.examples.WireAssignmentProductionBridge
import spinal.core.SpinalConfig

/** Production handoff for the fixed MorphHDL wire-assignment pipeline.
  *
  * The product surface intentionally exposes one all-or-none flag, enabled by
  * default. Explicitly disabling it preserves legacy generation, including when
  * the configuration is passed to MorphVerilog. Enabling the flag installs the
  * reviewed six-stage canonical-IR pipeline after typed
  * parameterization and width normalization, and before backend naming and
  * structured Verilog-2001 emission.
  */
object MorphWireAssignmentPasses {
  def apply(
      config: SpinalConfig = SpinalConfig(),
      enabled: Boolean = true
  ): SpinalConfig = {
    if (config == null)
      throw new IllegalArgumentException("SpinalConfig must not be null")
    if (enabled) WireAssignmentProductionBridge.enable(config)
    else WireAssignmentProductionBridge.disable(config)
  }

  def configure(config: SpinalConfig, enabled: Boolean): SpinalConfig =
    apply(config, enabled)

  private[morphhdl] def forPublication(config: SpinalConfig): SpinalConfig =
    WireAssignmentProductionBridge.forPublication(config)
}
