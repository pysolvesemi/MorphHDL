package spinal.core.internals

import java.util.IdentityHashMap
import scala.collection.mutable.ArrayBuffer
import spinal.core._

/** Read-only validation transaction, never a graph mutation or replay scope.
  * Shared prerequisite proofs are checked once per traversal. Driver inventories
  * preserve native walk order and identity, including conditional/partial writes.
  * Nothing survives the outer check (including an exceptional exit): a later
  * callback, replay, public query or in-place mutation must be observed afresh.
  */
private[internals] object TypedBalancedReductionFreshness {
  private final class Traversal {
    val checked = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    val drivers = new IdentityHashMap[Component, IdentityHashMap[BaseType, Vector[AssignmentStatement]]]()
  }
  private val current = new ThreadLocal[Traversal]()

  def read[A](body: => A): A = {
    if (current.get() != null) body
    else {
      current.set(new Traversal)
      try body finally current.remove()
    }
  }

  def once(identity: AnyRef)(body: => Unit): Unit = read {
    val checked = current.get().checked
    if (!checked.containsKey(identity)) {
      body
      // A failed check is never marked successful, even if its caller catches it.
      checked.put(identity, java.lang.Boolean.TRUE)
    }
  }

  def assignmentsOf(owner: Component, target: BaseType): Vector[AssignmentStatement] = {
    val traversal = current.get()
    if (traversal == null) {
      // Certification outside a read-only traversal takes a fresh observation.
      val result = ArrayBuffer.empty[AssignmentStatement]
      owner.dslBody.walkStatements {
        case value: AssignmentStatement if value.finalTarget eq target => result += value
        case _ =>
      }
      result.toVector
    } else {
      var index = traversal.drivers.get(owner)
      if (index == null) {
        val gathered = new IdentityHashMap[BaseType, ArrayBuffer[AssignmentStatement]]()
        owner.dslBody.walkStatements {
          case value: AssignmentStatement =>
            val key = value.finalTarget
            var entries = gathered.get(key)
            if (entries == null) {
              entries = ArrayBuffer.empty[AssignmentStatement]
              gathered.put(key, entries)
            }
            entries += value
          case _ =>
        }
        index = new IdentityHashMap[BaseType, Vector[AssignmentStatement]]()
        val entries = gathered.entrySet().iterator()
        while (entries.hasNext) {
          val entry = entries.next()
          index.put(entry.getKey, entry.getValue.toVector)
        }
        traversal.drivers.put(owner, index)
      }
      val found = index.get(target)
      if (found == null) Vector.empty else found
    }
  }
}
