package morphhdl.compiler

import scala.tools.nsc.Global
import scala.tools.nsc.plugins.{Plugin, PluginComponent}

/** MorphHDL-owned compiler plugin for parameter-preserving elaboration. */
final class MorphHdlPlugin(val global: Global) extends Plugin {
  override val name: String = "morphhdl"
  override val description: String =
    "Typed MorphHDL source transformations for parameter-preserving elaboration"
  private var ordinaryLoopCapture = true
  override def processOptions(options: List[String], error: String => Unit): Unit =
    options.foreach {
      case "ordinary-loops=host" => ordinaryLoopCapture = false
      case "ordinary-loops=capture" => ordinaryLoopCapture = true
      case option => error("Unknown MorphHDL option: " + option)
    }
  override val optionsHelp: Option[String] = Some(
    "  -P:morphhdl:ordinary-loops=host    Keep ordinary Int hardware loops as host elaboration (typed loops unchanged)")
  override val components: List[PluginComponent] =
    List(
      new MorphHdlParameterSyntaxComponent(global),
      new MorphHdlCallbackEffectComponent(global),
      new MorphHdlDirectRecursiveComponent(global),
      new MorphHdlAreaCollectionComponent(global),
      new MorphHdlTypedElaborationControlComponent(global, () => ordinaryLoopCapture),
      new MorphHdlNaturalSymbolicConditionalComponent(global),
      new MorphHdlFrontendSymbolicEqualitySafetyComponent(global),
      new MorphHdlConstantLoopBoundsComponent(global)
    )
}
