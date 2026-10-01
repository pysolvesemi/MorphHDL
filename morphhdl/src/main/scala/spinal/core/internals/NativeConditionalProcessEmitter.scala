package spinal.core.internals

import spinal.core._

/** Publish only retained native scopes/assignments. No emitted-text recognition
  * or index witness is used to choose or reconstruct a loop body.
  */
private[internals] object NativeConditionalProcessEmitter {
  private def reject(detail: String): Nothing =
    ParameterizedVerilogException.fail("SPINAL-PROCESS-CONDITIONAL-PUBLICATION-MISMATCH", detail, None)

  def scope(printer: ComponentEmitterVerilog, tree: TreeStatement, scope: ScopeStatement,
      output: StringBuilder, indentation: String, body: String => Int): Option[Int] = {
    ParameterizedProcess.conditionalLoopsOf(printer.component).find(_.tree eq tree).map { loop =>
      if ((scope ne loop.tree.whenTrue) || loop.emitting.nonEmpty ||
          !((loop.tree.cond eq loop.condition) || (loop.tree.cond eq loop.conditionDriver)))
        reject("conditional loop lost its exact captured native condition/scope")
      loop.tree.whenTrue.walkLeafStatements {
        case assignment: DataAssignmentStatement =>
          if (!loop.assignments.exists(_._1 eq assignment) ||
              loop.assignments.exists(pair => ParameterizedProcess.conditionalReads(assignment.source, pair._1.finalTarget)))
            reject("conditional loop gained an uncaptured assignment or cross-iteration read")
        case _ =>
      }
      val index = printer.component.localNamingScope.allocateName(loop.indexHint)
      printer.declarations ++= s"  integer $index;\n"
      val count = NativeLocalParameters.reference(printer.component, loop.count).getOrElse(loop.count.verilog)
      val selector = printer.emitExpression(loop.selector)
      val bits = loop.selector.getWidth
      output ++= s"${indentation}for ($index = 0; $index < $count; $index = $index + 1) begin\n"
      output ++= s"${indentation}  if ($selector == $index[${bits - 1}:0]) begin\n"
      loop.emitting = Some(printer -> index)
      try {
        val result = body(indentation + "    ")
        output ++= s"${indentation}  end\n${indentation}end\n"
        result
      } finally loop.emitting = None
    }
  }

  def target(printer: ComponentEmitterVerilog, assignment: AssignmentStatement): Option[String] = {
    ParameterizedProcess.conditionalLoopsOf(printer.component).iterator.flatMap { loop =>
      loop.assignments.find(_._1 eq assignment).map { case (captured, slice) =>
        val index = loop.emitting match {
          case Some((owner, value)) if owner eq printer => value
          case _ => reject("captured assignment escaped its native procedural loop")
        }
        if ((captured.finalTarget ne slice.source) || captured.finalTarget.isReg ||
            (captured.parentScope ne loop.tree.whenTrue) || loop.emitted.containsKey(captured))
          reject("conditional loop target ownership/order changed or was duplicated")
        captured.target match {
          case target: RangedAssignmentFixed if (target.out eq slice.source) &&
              BigInt(target.lo) == slice.offset.default && BigInt(target.getWidth) == slice.width.default =>
          case _ => reject("conditional loop no longer carries its captured slice target")
        }
        loop.emitted.put(captured, true)
        val width = NativeLocalParameters.reference(printer.component, slice.width).getOrElse(slice.width.verilog)
        val target = printer.emitExpression(slice.source)
        if (slice.width.minimum == 1 && slice.width.maximum == 1) s"$target[$index]"
        else s"$target[$index * ($width) +: $width]"
      }
    }.toVector.headOption
  }

  def validate(component: Component): Unit = {
    ParameterizedProcess.conditionalLoopsOf(component).foreach { loop =>
      if (loop.emitting.nonEmpty || loop.assignments.exists(pair => !loop.emitted.containsKey(pair._1)))
        reject("conditional loop did not publish every exact native assignment")
    }
  }
}
