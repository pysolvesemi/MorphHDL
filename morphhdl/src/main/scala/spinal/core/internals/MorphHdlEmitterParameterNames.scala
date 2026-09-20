package spinal.core.internals

import spinal.core.{BaseType, ParameterizedWidth}

/** Parameter declarations are published after native emission. Reserve their
  * complete retained module inventory before the native emitter allocates
  * expression functions, formal arguments or late wrappers. This changes no
  * declaration identity and does not infer parameters from emitted names.
  */
final class MorphHdlEmitterParameterNames extends PhaseMisc {
  override def impl(pc: PhaseContext): Unit = pc.walkComponents { component =>
    val names = scala.collection.mutable.HashSet.empty[String]
    MorphHdlExternalParameterizedVerilog.componentParameters(component)
      .foreach(parameter => names += parameter.name)
    // Reserving spellings is not a parameter-root equality proof. In
    // particular, distinct child-formal roots can legitimately share a name
    // before the hierarchy publisher resolves their exact owner/binding.
    component.dslBody.walkDeclarations {
      case value: BaseType => ParameterizedWidth.expressionOf(value)
        .foreach(_.parameters.foreach(parameter => names += parameter.name))
      case _ =>
    }
    names.toVector.sorted.foreach(component.localNamingScope.lockName)
  }
}
