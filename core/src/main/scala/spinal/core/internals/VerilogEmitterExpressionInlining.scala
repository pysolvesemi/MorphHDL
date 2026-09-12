package spinal.core.internals

import spinal.core.{ScopeProperty, SpinalConfig, SpinalTagReady}

/** Internal opt-in for the bounded Verilog expression-inlining policy.
  *
  * This is deliberately not a general SpinalHDL emission option. MorphHDL's
  * reviewed wire-assignment pipeline installs the marker on its copied
  * configuration; ordinary SpinalVerilog configurations never see it.
  */
object VerilogEmitterExpressionInlining {
  private object EnabledProperty extends ScopeProperty[Boolean]

  def configure(config: SpinalConfig, enabled: Boolean): SpinalConfig = {
    if (config == null)
      throw new IllegalArgumentException("SpinalConfig must not be null")

    val properties = config.scopeProperties.clone()
    if (enabled) properties.update(EnabledProperty, true)
    else properties.remove(EnabledProperty)
    config.copy(scopeProperties = properties)
  }

  private[internals] def isEnabled(config: SpinalConfig): Boolean =
    config != null && config.scopeProperties.get(EnabledProperty).contains(true)

  private[internals] def isUnannotated(expression: Expression): Boolean =
    expression match {
      case tagged: SpinalTagReady => tagged.isEmptyOfTag
      case _                      => true
    }
}
