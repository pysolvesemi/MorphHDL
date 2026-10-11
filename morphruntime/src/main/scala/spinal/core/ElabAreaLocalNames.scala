package spinal.core

/** Naming requests follow exact Area and declaration identities. Final placement
  * decides whether the enclosing generate scope actually replaces the prefix.
  */
private[core] object ElabAreaLocalNames {
  private object Key
  private final class State {
    val areas = scala.collection.mutable.ArrayBuffer.empty[(ElabFiniteIndexToken, Area)]
    val requests = new java.util.IdentityHashMap[Nameable, (ElabFiniteIndexToken, String)]()
  }
  private def state(component: Component): State =
    component.userCache.getOrElseUpdate(Key, new State).asInstanceOf[State]
  def retain(component: Component, token: ElabFiniteIndexToken, area: Area): Unit =
    state(component).areas += token -> area
  def capture(component: Component): Unit = {
    val s = state(component)
    def captureName(value: Nameable): Unit = {
      if ((value.isWeak || value.isLibraryDerived) && !value.getName().startsWith("_zz")) {
        val owners = value.getRefOwnersChain()
        s.areas.reverseIterator.find { case (_, area) => owners.exists {
          case owner: AnyRef => owner eq area; case _ => false
        }}.foreach { case (token, area) =>
          // An explicit name on any flattened enclosing owner is user intent.
          val belowArea = owners.dropWhile {
            case owner: AnyRef => !(owner eq area); case _ => true
          }.drop(1)
          val authoredOwner = belowArea.exists {
            case owner: Nameable => !owner.isWeak; case _ => false
          }
          val prefix = area.getName() + "_"
          val name = value.getName()
          if (!authoredOwner && name.startsWith(prefix) && name.length > prefix.length &&
              name.substring(prefix.length).matches("[A-Za-z_][A-Za-z0-9_$]*"))
            s.requests.put(value, token -> name.substring(prefix.length))
        }
      }
    }
    component.dslBody.walkDeclarations {
      case leaf: BaseType => captureName(leaf)
      case _ =>
    }
    component.children.foreach(captureName)
  }

  def requested(component: Component, token: ElabFiniteIndexToken, leaf: Nameable): Option[String] =
    Option(state(component).requests.get(leaf)).collect { case (owner, name) if owner eq token => name }
}
