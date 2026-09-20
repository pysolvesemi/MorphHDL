package spinal.core.internals

import spinal.core.ParameterizedWidth

/** Parameter declarations are published after native emission. Reserve their
  * complete retained module inventory before the native emitter allocates
  * expression functions, formal arguments or late wrappers. This changes no
  * declaration identity and does not infer parameters from emitted names.
  */
final class MorphHdlEmitterParameterNames extends PhaseMisc {
  override def impl(pc: PhaseContext): Unit = pc.walkComponents { component =>
    val parameters = MorphHdlExternalParameterizedVerilog.componentParameters(component) ++
      ParameterizedWidth.parametersOf(component)
    parameters.iterator.map(_.name).toSet.toVector.sorted.foreach(component.localNamingScope.lockName)
  }
}
