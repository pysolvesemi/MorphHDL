package spinal.core

/** Default-name authority belongs to the captured body, never its spelling. */
private[spinal] object AutomaticGenerateLabels {
  private object Key
  private final class State {
    var next = 0
    val bodies = new java.util.IdentityHashMap[ParameterizedStructuralBlock, java.lang.Boolean]()
  }
  private def state(component: Component): State =
    component.userCache.getOrElseUpdate(Key, new State).asInstanceOf[State]
  def fresh(component: Component): (String, String) = {
    val value=state(component);value.next += 1
    (s"generated_scope_${value.next}",s"generated_index_${value.next}")
  }
  def retain(component: Component, body: ParameterizedStructuralBlock): Unit =
    state(component).bodies.put(body, java.lang.Boolean.TRUE)
  def owns(component: Component, body: ParameterizedStructuralBlock): Boolean =
    state(component).bodies.containsKey(body)
}
