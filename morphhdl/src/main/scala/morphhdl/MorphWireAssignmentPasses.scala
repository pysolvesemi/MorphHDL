package morphhdl

import morphhdl.examples.WireAssignmentProductionBridge
import spinal.core.SpinalConfig

/** Optional production handoff for the fixed MorphHDL wire-assignment pipeline.
  *
  * The product surface intentionally exposes one all-or-none flag. Disabled is
  * the default and returns the caller's configuration unchanged. Enabling the
  * flag installs the reviewed five-stage canonical-IR pipeline after typed
  * parameterization and width normalization, and before backend naming and
  * structured Verilog-2001 emission.
  */
object MorphWireAssignmentPasses {
  def apply(
      config: SpinalConfig = SpinalConfig(),
      enabled: Boolean = false
  ): SpinalConfig = {
    if (config == null)
      throw new IllegalArgumentException("SpinalConfig must not be null")
    if (enabled) WireAssignmentProductionBridge.enable(config) else config
  }

  def configure(config: SpinalConfig, enabled: Boolean): SpinalConfig =
    apply(config, enabled)
}
