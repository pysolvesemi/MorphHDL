package morphhdl.paramrtl

private[morphhdl] final case class DependencyGraphResult(
    cycleGroups: Vector[Vector[String]],
    orderedNames: Vector[String]
)

/** Iterative deterministic graph analysis shared by constant and module dependencies. */
private[morphhdl] object DependencyGraph {
  def analyze(dependencies: Map[String, Vector[String]]): DependencyGraphResult = {
    val nodes = dependencies.keys.toVector.sorted
    val nodeSet = nodes.toSet
    val normalized = nodes.map { name =>
      name -> dependencies.getOrElse(name, Vector.empty).filter(nodeSet).distinct.sorted
    }.toMap

    val visited = scala.collection.mutable.Set.empty[String]
    val finishOrder = scala.collection.mutable.ArrayBuffer.empty[String]
    nodes.foreach { start =>
      if (!visited.contains(start)) {
        val stack = scala.collection.mutable.ArrayBuffer((start, false))
        while (stack.nonEmpty) {
          val (node, expanded) = stack.remove(stack.length - 1)
          if (expanded) finishOrder += node
          else if (!visited.contains(node)) {
            visited += node
            stack += ((node, true))
            normalized(node).reverseIterator.foreach { dependency =>
              if (!visited.contains(dependency)) stack += ((dependency, false))
            }
          }
        }
      }
    }

    val dependents = nodes.map(_ -> scala.collection.mutable.ArrayBuffer.empty[String]).toMap
    nodes.foreach { name =>
      normalized(name).foreach(dependency => dependents(dependency) += name)
    }

    val assigned = scala.collection.mutable.Set.empty[String]
    val cycleGroupsBuilder = Vector.newBuilder[Vector[String]]
    finishOrder.reverseIterator.foreach { start =>
      if (!assigned.contains(start)) {
        val members = scala.collection.mutable.ArrayBuffer.empty[String]
        val stack = scala.collection.mutable.ArrayBuffer(start)
        assigned += start
        while (stack.nonEmpty) {
          val node = stack.remove(stack.length - 1)
          members += node
          dependents(node).reverseIterator.foreach { dependent =>
            if (!assigned.contains(dependent)) {
              assigned += dependent
              stack += dependent
            }
          }
        }
        val sortedMembers = members.sorted.toVector
        if (sortedMembers.size > 1 || normalized(sortedMembers.head).contains(sortedMembers.head))
          cycleGroupsBuilder += sortedMembers
      }
    }

    val cycleGroups = cycleGroupsBuilder.result().sortBy(_.head)
    val cyclicNames = cycleGroups.iterator.flatten.toSet
    val remaining = scala.collection.mutable.Map.empty[String, Int]
    nodes.filterNot(cyclicNames).foreach { name =>
      remaining.update(name, normalized(name).count(dependency => !cyclicNames.contains(dependency)))
    }
    var ready = scala.collection.immutable.TreeSet.empty[String] ++
      remaining.iterator.collect { case (name, count) if count == 0 => name }
    val ordered = Vector.newBuilder[String]
    while (ready.nonEmpty) {
      val name = ready.head
      ready -= name
      ordered += name
      dependents(name).foreach { dependent =>
        if (remaining.contains(dependent)) {
          val updated = remaining(dependent) - 1
          remaining.update(dependent, updated)
          if (updated == 0) ready += dependent
        }
      }
    }
    DependencyGraphResult(cycleGroups, ordered.result())
  }
}
