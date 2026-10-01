package spinal.core.internals

import spinal.core.{BaseType, ParameterizedWidth, NativeLocalParameters, ExternalFormalParameterRegistry}

object MorphHdlEmitterParameterNames {
  /** Parameter publication is independent of optional wire cleanup. The older
    * pipeline may already install this phase; never prepare an owner twice.
    */
  def install(phases: scala.collection.mutable.ArrayBuffer[Phase]): Unit = {
    val existing = phases.count(_.isInstanceOf[MorphHdlEmitterParameterNames])
    require(existing <= 1, "parameter publication needs one name preparation phase")
    if (existing == 0 && phases.exists(_.isInstanceOf[PhaseVerilog])) {
      val allocation = phases.indexWhere(_.isInstanceOf[PhaseAllocateNames])
      require(allocation >= 0, "parameter publication needs native name allocation")
      phases.insert(allocation + 1, new MorphHdlEmitterParameterNames)
    }
  }
}

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
    val uses = scala.collection.mutable.ArrayBuffer.empty[spinal.core.ElaborationIntegerExpression]
    component.dslBody.walkDeclarations {
      case value: BaseType => ParameterizedWidth.expressionOf(value).foreach(uses += _)
      case _ =>
    }
    component.children.foreach(child =>
      ExternalFormalParameterRegistry.bindingsOf(child).foreach(binding => uses += binding.actual))
    component.children.collect { case child: spinal.core.BlackBox => child }.foreach { child =>
      ParameterizedBlackBoxGenericRegistry.recordsOf(child).foreach {
        case binding: ParameterizedBlackBoxIntegerGeneric => uses += binding.expression
        case _ =>
      }
    }
    NativeLocalParameters.prepare(component, uses.toVector)
  }
}
