package spinal.core.internals

import spinal.core._

/** Publication from authenticated operation records, never emitted-text matching. */
private[internals] object NativeBoundedProcessEmitter {
  private def fail(detail: String): Nothing = ParameterizedVerilogException.fail(
    "SPINAL-ELAB-PROCESS-PUBLICATION-MISMATCH", detail, None)
  private def expression(component: Component, value: ElabInt): String =
    NativeLocalParameters.reference(component, value.expression).getOrElse(value.expression.verilog)

  private def check(op: ElabProcess.Operation): Unit = {
    val writes = scala.collection.mutable.ArrayBuffer.empty[DataAssignmentStatement]
    op.result.foreachStatements {
      case value: DataAssignmentStatement => writes += value
      case _ =>
    }
    if (op.result.isReg || writes.size != 2 || !writes.exists(_ eq op.default) ||
        !writes.exists(_ eq op.assignment) || (op.assignment.target ne op.result) ||
        (op.assignment.source ne op.source) || (op.assignment.parentScope ne op.tree.whenTrue) ||
        (op.tree.parentScope ne op.result.component.dslBody) ||
        (op.default.parentScope ne op.result.component.dslBody) ||
        op.tree.whenTrue.statementIterable.toVector != Vector(op.assignment) ||
        op.tree.whenFalse.statementIterable.nonEmpty ||
        !((op.tree.cond eq op.condition) || (op.tree.cond eq op.conditionDriver)))
      fail(s"${op.result.component.definitionName}.${op.result.getName()} lost exact defaults, writes or ownership: " +
        s"writes=${writes.size}, default=${writes.exists(_ eq op.default)}, assignment=${writes.exists(_ eq op.assignment)}, " +
        s"target=${op.assignment.target eq op.result}, source=${op.assignment.source eq op.source}, " +
        s"scope=${op.assignment.parentScope eq op.tree.whenTrue}, defaultScope=${op.default.parentScope eq op.result.component.dslBody}, " +
        s"body=${op.tree.whenTrue.statementIterable.map(_.getClass.getSimpleName).mkString(",")}, " +
        s"condition=${(op.tree.cond eq op.condition) || (op.tree.cond eq op.conditionDriver)}")
    if (ParameterizedProcess.conditionalReads(op.input, op.result) ||
        op.take.exists(ParameterizedProcess.conditionalReads(_, op.result)))
      fail(s"${op.result.getName()} has combinational target feedback")
    op.default.source match {
      case literal: BitVectorLiteral if !literal.hasPoison() && literal.getValue() == 0 =>
      case _ => fail(s"${op.result.getName()} lost its exact zero default")
    }
    if (op.take.isEmpty) op.assignment.source match {
      case literal: UIntLiteral if !literal.hasPoison() && literal.getValue() == 1 =>
      case _ => fail(s"${op.result.getName()} lost its authenticated priority-index edge")
    }
  }

  def scope(printer: ComponentEmitterVerilog, tree: TreeStatement, scope: ScopeStatement,
      output: StringBuilder, indentation: String, body: String => Int): Option[Int] =
    ElabProcess.operations(printer.component).find(_.tree eq tree).map { op =>
      check(op)
      if ((scope ne op.tree.whenTrue) || op.emitting.nonEmpty || op.emitted)
        fail("bounded operation escaped or duplicated its owning process")
      val index = printer.component.localNamingScope.allocateName("i")
      printer.declarations ++= s"  integer $index;\n"
      val count = expression(printer.component, op.count)
      val input = printer.emitExpression(op.input)
      val (start, limit, predicate) = op.take match {
        case None => ("1", s"$index <= $count", s"$input[$index - 1]")
        case Some(take) =>
          val value = printer.emitExpression(take)
          val unsigned = if (take.getWidth == 32) value else s"{${32 - take.getWidth}'d0, $value}"
          ("0", s"$index < $count", s"$index[31:0] < $unsigned")
      }
      output ++= s"${indentation}for ($index = $start; $limit; $index = $index + 1) begin\n"
      output ++= s"${indentation}  if ($predicate) begin\n"
      op.emitting = Some(printer -> index)
      try {
        val next = body(indentation + "    ")
        output ++= s"${indentation}  end\n${indentation}end\n"
        op.emitted = true
        next
      } finally op.emitting = None
    }

  private def selected(printer: ComponentEmitterVerilog, assignment: AssignmentStatement)
      : Option[(ElabProcess.Operation, String)] =
    ElabProcess.operations(printer.component).find(_.assignment eq assignment).map { op =>
      val index = op.emitting match {
        case Some((owner, index)) if owner eq printer => index
        case _ => fail("bounded assignment escaped its process")
      }
      op -> index
    }
  def target(printer: ComponentEmitterVerilog, assignment: AssignmentStatement): Option[String] =
    selected(printer, assignment).filter(_._1.take.nonEmpty).map { case (op, index) =>
      val width = expression(printer.component, op.elementWidth)
      s"${printer.emitExpression(op.result)}[$index * ($width) +: $width]"
    }
  def source(printer: ComponentEmitterVerilog, assignment: AssignmentStatement): Option[String] =
    ElabProcess.operations(printer.component).find(_.default eq assignment).map { op =>
      check(op)
      s"{${expression(printer.component, op.resultWidth)}{1'b0}}"
    }.orElse(selected(printer, assignment).map { case (op, index) =>
      if (op.take.isEmpty) s"$index[(${expression(printer.component, op.resultWidth)})-1:0]"
      else {
        val width = expression(printer.component, op.elementWidth)
        s"${printer.emitExpression(op.input)}[$index * ($width) +: $width]"
      }
    })
  def validate(component: Component): Unit = ElabProcess.operations(component).foreach { op =>
    check(op)
    if (!op.emitted || op.emitting.nonEmpty) fail("bounded operation was not published exactly once")
  }
}
