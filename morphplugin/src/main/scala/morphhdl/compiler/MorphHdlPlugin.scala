package morphhdl.compiler

import scala.tools.nsc.Global
import scala.tools.nsc.plugins.{Plugin, PluginComponent}

/** MorphHDL-owned compiler plugin; native SpinalHDL compiler sources remain untouched. */
final class MorphHdlPlugin(val global: Global) extends Plugin {
  override val name: String = "morphhdl"
  override val description: String =
    "Typed MorphHDL source transformations for parameter-preserving elaboration"
  override val components: List[PluginComponent] =
    List(
      new MorphHdlNativeIntShadowExpressionComponent(global),
      new MorphHdlNaturalSymbolicConditionalComponent(global)
    )
}
