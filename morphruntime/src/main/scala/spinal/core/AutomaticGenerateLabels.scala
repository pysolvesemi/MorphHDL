package spinal.core

/** Default-name authority belongs to the captured body, never its spelling. */
private[spinal] object AutomaticGenerateLabels {
  def conditionalRole(component: Component, stem: String): String = {
    val role = new String(stem)
    if (component != null) state(component).conditionalRoles.put(role, java.lang.Boolean.TRUE)
    role
  }
  def retainConditional(component: Component, role: String, body: ParameterizedStructuralBlock): Unit =
    if (state(component).conditionalRoles.containsKey(role)) retain(component, body)
  private object Key
  private final class State {
    val conditionalRoles = new java.util.IdentityHashMap[String, java.lang.Boolean]()
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
