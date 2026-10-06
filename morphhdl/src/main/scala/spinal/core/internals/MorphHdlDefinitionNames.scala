package spinal.core.internals

import java.util.IdentityHashMap
import scala.collection.mutable
import spinal.core.{BlackBox, Component}

/** Publish author-requested names for exact native canonical definitions.
  * Never infer a base name by stripping a suffix from an emitted identifier.
  */
private[internals] object MorphHdlDefinitionNames {
  def install(phases: mutable.ArrayBuffer[Phase]): Unit = {
    if (phases.exists(_.isInstanceOf[Capture])) return
    val capture = new Capture
    val allocation = phases.indexWhere(_.isInstanceOf[PhaseAllocateNames])
    require(allocation >= 0, "definition publication needs native name allocation")
    phases.insert(allocation, capture)
    phases.collect { case emitter: PhaseVerilog => emitter }.foreach {
      _.bindDefinitionNamePublication(capture.publish)
    }
  }

  private class Capture extends PhaseMisc {
    private val requested = new IdentityHashMap[Component, String]()
    private val bases = mutable.HashSet.empty[String]
    private val used = mutable.HashSet.empty[String]
    private var top: Component = _
    private var context: PhaseContext = _
    private var prepared = false

    override def impl(pc: PhaseContext): Unit = {
      top = pc.topLevel
      context = pc
      pc.walkComponents { component =>
        val name = component.definitionName
        require(name != null && name.nonEmpty, "native definition name is missing")
        requested.put(component, name)
        bases += name.toLowerCase(java.util.Locale.ROOT)
        if (component.isInBlackBoxTree || component.isInstanceOf[BlackBox] ||
            component.definitionNameNoMerge || (component eq top)) used += name.toLowerCase(java.util.Locale.ROOT)
      }
      used ++= pc.reservedKeyWords
    }

    def publish(component: Component): Unit = {
      if (!prepared) {
        // Allocation has now completed. Keep every non-component reservation,
        // including enums, keywords and exact top/BlackBox/noMerge names.
        val movable = mutable.HashSet.empty[String]
        context.walkComponents { value =>
          if (!value.isInBlackBoxTree && !value.isInstanceOf[BlackBox] &&
              !value.definitionNameNoMerge && (value ne top))
            movable += value.definitionName.toLowerCase(java.util.Locale.ROOT)
        }
        used ++= context.globalScope.map.diff(movable)
        prepared = true
      }
      if (component.isInBlackBoxTree || component.isInstanceOf[BlackBox] ||
          component.definitionNameNoMerge || (component eq top)) return
      val base = requested.get(component)
      require(base != null, "definition publication lost captured component identity")
      var name = base
      var suffix = 0
      while (used(name.toLowerCase(java.util.Locale.ROOT)) ||
          (name != base && bases(name.toLowerCase(java.util.Locale.ROOT)))) {
        suffix += 1
        name = base + "_" + suffix
      }
      used += name.toLowerCase(java.util.Locale.ROOT)
      component.definitionName = name
    }
  }
}
