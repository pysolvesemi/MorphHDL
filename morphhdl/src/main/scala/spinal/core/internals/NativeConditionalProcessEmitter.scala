package spinal.core.internals

import spinal.core._

/** Publish only retained native scopes/assignments. No emitted-text recognition
  * or index witness is used to choose or reconstruct a loop body.
  */
private[internals] object NativeConditionalProcessEmitter {
  // A named, combinational Boolean can stay false while its input dependencies
  // initialize. Register its exact pure driver's dependencies at a condition
  // occurrence, retaining the named condition and the native if/else syntax.
  def condition(printer: ComponentEmitterVerilog, statement: WhenStatement): Option[String] = statement.cond match {
    case value: Bool if !value.isReg && !value.isIo && (value.component eq printer.component) =>
      val assignments = scala.collection.mutable.ArrayBuffer.empty[DataAssignmentStatement]
      value.foreachStatements {
        case assignment: DataAssignmentStatement => assignments += assignment
        case _ =>
      }
      if (assignments.size != 1) None
      else {
        val assignment = assignments.head
        if ((assignment.target ne value) || (assignment.parentScope ne value.rootScopeStatement)) None
        else assignment.source match {
          case _: BinaryOperator | _: UnaryOperator =>
            printer.emitExpression(assignment.source) // collect exact native references
            Some(printer.emitExpression(value))
          case _ => None
        }
      }
    case _ => None
  }

  private def reject(detail: String): Nothing =
    ParameterizedVerilogException.fail("SPINAL-PROCESS-CONDITIONAL-PUBLICATION-MISMATCH", detail, None)

  /** Structural process splitting has already proved the retained statements'
    * owners. Restrict the native combinational event list to this exact fragment,
    * including the pure condition-driver dependencies added by condition above.
    * Otherwise an event alone can reference a declaration in a sibling branch.
    */
  def restrictFragmentSensitivity(component: Component, fragment: String): String = {
    val lines = fragment.split("\n", -1).toVector
    val header = "^\\s*always @\\(([^()]*)\\) begin\\s*$".r
    lines.headOption match {
      case Some(header(events)) if events != "*" &&
          !events.split("\\s+").exists(word => word == "posedge" || word == "negedge") =>
        val references = ParameterizedVerilogStructural.verilogReferenceNames(lines.tail.mkString("\n"))
        val dependencies = scala.collection.mutable.Set.empty[String] ++ references
        component.dslBody.walkStatements {
          case statement: WhenStatement => statement.cond match {
            case value: Bool if references(value.getName()) && !value.isIo && !value.isReg &&
                (value.component eq component) && value.hasOnlyOneStatement =>
              value.head match {
                case assignment: DataAssignmentStatement if (assignment.target eq value) &&
                    (assignment.parentScope eq value.rootScopeStatement) => assignment.source match {
                  case _: BinaryOperator | _: UnaryOperator =>
                    assignment.source.walkExpression {
                      case base: BaseType => dependencies += base.getName()
                      case _ =>
                    }
                  case _ =>
                }
                case _ =>
              }
            case _ =>
          }
          case _ =>
        }
        val retained = events.split("\\s+or\\s+").toVector.filter(event =>
          ParameterizedVerilogStructural.verilogReferenceNames(event).exists(dependencies))
        if (retained.isEmpty) reject("split combinational process has no retained event dependencies")
        (Vector("always @(" + retained.mkString(" or ") + ") begin") ++
          lines.tail).mkString("\n")
      case _ => fragment
    }
  }

  def scope(printer: ComponentEmitterVerilog, tree: TreeStatement, scope: ScopeStatement,
      output: StringBuilder, indentation: String, body: String => Int): Option[Int] = {
    ParameterizedProcess.conditionalLoopsOf(printer.component).find(_.tree eq tree).map { loop =>
      if ((scope ne loop.tree.whenTrue) || loop.emitting.nonEmpty ||
          !((loop.tree.cond eq loop.condition) || (loop.tree.cond eq loop.conditionDriver)))
        reject("conditional loop lost its exact captured native condition/scope")
      loop.tree.whenTrue.walkLeafStatements {
        case assignment: DataAssignmentStatement =>
          val location = s"${printer.component.definitionName}.${assignment.finalTarget.getName()} (${assignment.getClass.getSimpleName})"
          if (!loop.assignments.exists(_._1 eq assignment))
            reject(s"conditional loop '${loop.indexHint}' gained an uncaptured assignment at $location")
          loop.assignments.find(pair => ParameterizedProcess.conditionalReads(assignment.source, pair._1.finalTarget)).foreach { pair =>
            reject(s"conditional loop '${loop.indexHint}' gained a cross-iteration read at $location from ${pair._1.finalTarget.getName()}")
          }
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

  /** Only the exact compiler-created, exclusively consumed selection witness
    * becomes dead when its native when scope is published as a loop. Preserve
    * any carrier with another native consumer; never discover witnesses by name.
    */
  def removeUnusedSelectionWitnesses(component: Component, verilog: String): String = {
    var lines = verilog.split("\\n", -1).toVector
    val witnesses = ParameterizedProcess.conditionalLoopsOf(component)
      .map(loop => (loop.condition, loop.conditionDriver, loop.tree)) ++
      ElabProcess.operations(component).map(op => (op.condition, op.conditionDriver, op.tree)) ++
      ElabScopedProcess.operations(component).map(op => (op.condition, op.conditionDriver, op.tree))
    witnesses.foreach { case (value, driver, tree) =>
      val exactDriver = value.hasOnlyOneStatement && (value.head match {
        case assignment: DataAssignmentStatement =>
          (assignment.target eq value) && (assignment.finalTarget eq value) &&
            (assignment.parentScope eq value.rootScopeStatement) &&
            (assignment.source eq driver)
        case _ => false
      })
      var otherUse = false
      component.dslBody.walkStatements { statement =>
        if (!(statement eq tree)) statement.walkDrivingExpressions {
          case expression if expression eq value => otherUse = true
          case _ =>
        }
      }
      if ((value.component eq component) && !value.isIo && !value.isReg && exactDriver && !otherUse) {
        val name = java.util.regex.Pattern.quote(value.getName())
        val declaration = ("^\\s*wire\\s+" + name + ";\\s*$").r
        val assignment = ("^\\s*assign\\s+" + name + "\\s*=.*;\\s*$").r
        val reference = ("\\b" + name + "\\b").r
        val declarations = lines.indices.filter(i => declaration.pattern.matcher(lines(i)).matches())
        val assignments = lines.indices.filter(i => assignment.pattern.matcher(lines(i)).matches())
        // A native optimizer can already have removed this declaration. Any
        // additional emitted reference forbids removal even with graph evidence.
        if (declarations.size == 1 && assignments.size == 1 &&
            lines.map(line => reference.findAllIn(line).length).sum == 2) {
          val removed = (declarations ++ assignments).toSet
          lines = lines.zipWithIndex.collect { case (line,index) if !removed(index) => line }
        }
      }
    }
    lines.mkString("\n")
  }

  def validate(component: Component): Unit = {
    ParameterizedProcess.conditionalLoopsOf(component).foreach { loop =>
      if (loop.emitting.nonEmpty || loop.assignments.exists(pair => !loop.emitted.containsKey(pair._1)))
        reject("conditional loop did not publish every exact native assignment")
    }
  }
}
