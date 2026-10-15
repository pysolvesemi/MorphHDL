package spinal.core

import spinal.core.internals._
import scala.collection.mutable

/** Replay the authored dependency graph per finite iteration. Alias witnesses
  * are not real storage edges and cannot be used for native cycle checking.
  */
private[core] object ElabFinitePreviousCycles {
  private object Key
  def schedule(component: Component): Unit = if (!component.userCache.contains(Key)) {
    component.userCache(Key) = true
    component.addPrePopTask(() => validate(component))
  }

  private def validate(component: Component): Unit = {
    def loops(regions: Vector[ParameterizedStructure.StructuralRegion]): Vector[ParameterizedStructure.StructuralFor] =
      regions.flatMap(r => (r match { case f: ParameterizedStructure.StructuralFor => Vector(f); case _ => Vector.empty }) ++
        r.blocks.flatMap(b => loops(b.regions)))
    loops(ParameterizedStructure.regionsOf(component)).foreach { loop =>
      val root = loop.finiteIndexToken.flatMap(ParameterizedStructure.finitePredicateRoot(component, _))
      val domains = new java.util.IdentityHashMap[DataAssignmentStatement, Set[Int]]()
      val selections = mutable.ArrayBuffer.empty[ParameterizedStructure.StructuralVecIndex]
      def visit(block: ParameterizedStructuralBlock, allowed: Set[Int]): Unit = {
        block.assignments.foreach(a => domains.put(a, allowed))
        selections ++= block.vecIndices
        block.regions.foreach {
          case _: ParameterizedStructure.StructuralFor => // Nested loops have their own index replay.
          case b: ParameterizedStructure.StructuralIf if b.predicateDomain.exists(d => root.exists(_ eq d.root)) =>
            val d = b.predicateDomain.get
            visit(b.whenTrue, allowed intersect d.whenTrue.map(_.toInt))
            visit(b.whenFalse, allowed intersect (d.universe -- d.whenTrue).map(_.toInt))
          case r => r.blocks.foreach(b => visit(b, allowed))
        }
      }
      visit(loop.body, (0 until loop.count.maximum.toInt).toSet)
      if (selections.exists(_.affineRead.exists(_.previous.nonEmpty))) {
        val vectors = selections.map(_.vector).distinct
        val geometry = new java.util.IdentityHashMap[Vec[_], (Int, Int)]()
        var base = 0
        val leaves = vectors.toVector.flatMap { vector =>
          val values = vector.vec.toVector.flatMap(_.asInstanceOf[Data].flatten)
          geometry.put(vector, base -> vector.vec.head.asInstanceOf[Data].flatten.size)
          base += values.size
          values
        }
        locally {
          val carriers = new java.util.IdentityHashMap[BaseType, Int]()
          leaves.zipWithIndex.foreach { case (leaf, slot) => carriers.put(leaf, slot) }
          val aliases = new java.util.IdentityHashMap[BaseType, (ParameterizedStructure.StructuralVecIndex, Int)]()
          selections.foreach(s => s.result.flatten.zipWithIndex.foreach {
            case (leaf, field) => aliases.put(leaf, s -> field)
          })
          val graph = Array.fill(leaves.size)(mutable.Set.empty[Int])
          def dependencies(expression: Expression, iteration: Int, seen: java.util.IdentityHashMap[Expression, java.lang.Boolean]): Set[Int] = {
            if (expression == null || seen.containsKey(expression)) return Set.empty
            seen.put(expression, true)
            expression match {
              case leaf: BaseType if aliases.containsKey(leaf) =>
                val (selection, field) = aliases.get(leaf)
                val offset = selection.affineRead.map(_.offset).orElse(selection.unitRange.map(_.offset)).getOrElse(0)
                val coefficient = selection.affineRead.map(_.coefficient).getOrElse(1)
                val lane = coefficient * iteration + offset
                val (base, width) = geometry.get(selection.vector)
                if (lane >= 0 && lane < selection.vector.carrierLength) Set(base + lane * width + field) else Set.empty
              case leaf: BaseType if carriers.containsKey(leaf) => Set(carriers.get(leaf))
              case leaf: BaseType if leaf.isReg || leaf.isInput => Set.empty
              case leaf: BaseType =>
                var found = Set.empty[Int]
                leaf.foreachStatements {
                  case a: DataAssignmentStatement if !domains.containsKey(a) || domains.get(a)(iteration) =>
                    found ++= assignmentDependencies(a, iteration, seen)
                  case _ =>
                }
                found
              case other =>
                var found = Set.empty[Int]
                other.foreachExpression(e => found ++= dependencies(e, iteration, seen))
                found
            }
          }
          def assignmentDependencies(a: DataAssignmentStatement, iteration: Int,
              seen: java.util.IdentityHashMap[Expression, java.lang.Boolean]): Set[Int] = {
            var found = dependencies(a.source, iteration, seen)
            var scope = a.parentScope
            while (scope != null && scope.parentStatement != null) {
              scope.parentStatement.foreachExpression(e => found ++= dependencies(e, iteration, seen))
              scope = scope.parentStatement.parentScope
            }
            found
          }
          selections.filter(_.affineRead.isEmpty).foreach { selection =>
            val (base, width) = geometry.get(selection.vector)
            selection.result.flatten.zipWithIndex.foreach { case (leaf, field) =>
              leaf.foreachStatements {
                case a: DataAssignmentStatement if domains.containsKey(a) =>
                  domains.get(a).foreach { iteration =>
                    val target = base + (iteration + selection.unitRange.map(_.offset).getOrElse(0)) * width + field
                    if (!leaves(target).isReg) graph(target) ++= assignmentDependencies(a, iteration,
                      new java.util.IdentityHashMap[Expression, java.lang.Boolean]())
                  }
                case _ =>
              }
            }
          }
          val state = Array.fill(graph.length)(0)
          def check(node: Int): Unit = {
            if (state(node) == 1) ParameterizedVerilogException.fail("SPINAL-ELAB-AREA-COMBINATIONAL-CYCLE",
              "previous-iteration Vec dependencies contain a combinational feedback cycle", loop.sourceLocation)
            if (state(node) == 0) { state(node) = 1; graph(node).foreach(check); state(node) = 2 }
          }
          graph.indices.foreach(check)
        }
      }
    }
  }
}
