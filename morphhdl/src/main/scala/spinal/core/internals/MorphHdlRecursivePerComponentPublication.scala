package spinal.core.internals

import scala.collection.mutable.ArrayBuffer
import spinal.core._

/** Skip definition publication only for authenticated self-reference or
  * explicit sibling-reference identities. Their native instances retain the real module name throughout
  * comparison and emission; no temporary names or emitted-text repair is used.
  */
object MorphHdlRecursivePerComponentPublication {
  def install(phases: ArrayBuffer[Phase]): Unit = {
    val emitters = phases.zipWithIndex.collect { case (phase: PhaseVerilog, index) => phase -> index }
    require(emitters.size == 1, "recursive publication requires one native emitter")
    val (emitter, index) = emitters.head
    val references = new java.util.IdentityHashMap[Component, java.lang.Boolean]()
    emitter.bindReferenceOnlyPublication(component => references.containsKey(component))
    phases.insert(index, new PhaseMisc {
      override def impl(pc: PhaseContext): Unit = {
        val components = ArrayBuffer.empty[Component]
        def visit(component: Component): Unit = {
          components += component
          component.children.foreach(visit)
        }
        visit(pc.topLevel)
        BoundedRecursiveModuleValidation.validate(components.toVector).foreach { reference =>
          references.put(reference, java.lang.Boolean.TRUE)
        }
      }
    })
  }
}
