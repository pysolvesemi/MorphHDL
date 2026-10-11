package spinal.core

/** Naming requests follow exact Area and declaration identities. Final placement
  * decides whether the enclosing generate scope actually replaces the prefix.
  */
private[core] object ElabAreaLocalNames {
  private object Key
  private final class State {
    val areas = scala.collection.mutable.ArrayBuffer.empty[(ElabFiniteIndexToken, Area)]
    val requests = new java.util.IdentityHashMap[BaseType, (ElabFiniteIndexToken, String)]()
  }
  private def state(component: Component): State =
    component.userCache.getOrElseUpdate(Key, new State).asInstanceOf[State]
  def retain(component: Component, token: ElabFiniteIndexToken, area: Area): Unit =
    state(component).areas += token -> area
  def capture(component: Component): Unit = {
    val s = state(component)
    component.dslBody.walkDeclarations {
      case leaf: BaseType if leaf.isWeak && !leaf.getName().startsWith("_zz") =>
        val owners = leaf.getRefOwnersChain()
        s.areas.reverseIterator.find { case (_, area) => owners.exists { case owner: AnyRef => owner eq area; case _ => false } }.foreach {
          case (token, area) =>
            val prefix = area.getName() + "_"
            val name = leaf.getName()
            if (name.startsWith(prefix) && name.length > prefix.length &&
                name.substring(prefix.length).matches("[A-Za-z_][A-Za-z0-9_$]*"))
              s.requests.put(leaf, token -> name.substring(prefix.length))
        }
      case _ =>
    }
  }
  def requested(component: Component, token: ElabFiniteIndexToken, leaf: BaseType): Option[String] =
    Option(state(component).requests.get(leaf)).collect { case (owner, name) if owner eq token => name }
}
