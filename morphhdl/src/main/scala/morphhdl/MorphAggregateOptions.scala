package morphhdl

import spinal.core.{SpinalConfig, VerilogAggregateOptions}

/** Opt-in constant aggregates and loops for MorphVerilog. Ports remain packed. */
object MorphAggregateOptions {
  val PackedVector = VerilogAggregateOptions.PackedVector
  val UnpackedArray = VerilogAggregateOptions.UnpackedArray

  def apply(config: SpinalConfig,
      preserveConstantVecs: Boolean = false,
      preserveConstantLoops: Boolean = false,
      vecLayout: VerilogAggregateOptions.Layout = PackedVector): SpinalConfig = {
    require(config != null && vecLayout != null, "aggregate configuration must not be null")
    val flags = config.flags.clone()
    flags.retain(!_.isInstanceOf[VerilogAggregateOptions])
    flags += VerilogAggregateOptions(preserveConstantVecs, preserveConstantLoops, vecLayout)
    config.copy(flags = flags, phasesInserters = config.phasesInserters.clone(),
      scopeProperties = config.scopeProperties.clone())
  }
}
