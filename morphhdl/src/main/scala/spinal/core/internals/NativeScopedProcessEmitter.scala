package spinal.core.internals

import spinal.core._
import spinal.core.ElabScopedProcess._

/** Lower an authenticated scoped program through native process callbacks.
  * The DSL owns one result and a zero default; ordered writes cannot escape it.
  */
private[internals] object NativeScopedProcessEmitter {
  private def fail(detail: String): Nothing = ParameterizedVerilogException.fail(
    "SPINAL-SCOPED-PROCESS-PUBLICATION-MISMATCH", detail, None)
  private def geometry(component: Component, value: ElabInt): String =
    NativeLocalParameters.reference(component, value.expression).getOrElse(value.expression.verilog)
  private def check(op: Operation): Unit = {
    val writes = scala.collection.mutable.ArrayBuffer.empty[DataAssignmentStatement]
    op.result.foreachStatements { case a: DataAssignmentStatement => writes += a; case _ => }
    if (op.result.isReg || writes.size != 2 || !writes.exists(_ eq op.default) || !writes.exists(_ eq op.assignment) ||
        (op.default.target ne op.result) || (op.assignment.target ne op.result) ||
        (op.default.parentScope ne op.builder.component.dslBody) ||
        (op.tree.parentScope ne op.builder.component.dslBody) ||
        (op.assignment.parentScope ne op.tree.whenTrue) ||
        op.tree.whenTrue.statementIterable.toVector != Vector(op.assignment) ||
        op.tree.whenFalse.statementIterable.nonEmpty || !op.lineage.accepts(op.tree.cond))
      fail(s"${op.builder.component.getPath()}.${op.result.getName()} lost its exact process, default or write identity; ${op.lineage.explain(op.tree.cond)}")
    for ((assignment, expected) <- Vector(op.default -> BigInt(0), op.assignment -> BigInt(1))) assignment.source match {
      case literal: BitVectorLiteral if !literal.hasPoison() && literal.getValue() == expected =>
      case _ => fail("scoped process witness edge changed")
    }
    if (op.builder.inputs.exists(ParameterizedProcess.conditionalReads(_, op.result)))
      fail("scoped process has combinational target feedback")
  }
  def source(printer: ComponentEmitterVerilog, assignment: AssignmentStatement): Option[String] =
    operations(printer.component).find(_.default eq assignment).map { op =>
      check(op); s"{${geometry(printer.component, op.builder.resultWidth)}{1'b0}}"
    }
  def scope(printer: ComponentEmitterVerilog, tree: TreeStatement, scope: ScopeStatement,
      output: StringBuilder, indentation: String, body: String => Int): Option[Int] =
    operations(printer.component).find(_.tree eq tree).map { op =>
      check(op)
      if ((scope ne op.tree.whenTrue) || op.emitted) fail("scoped process escaped or duplicated its scope")
      // Consume exactly the authenticated witness in the native statement cursor.
      // Its buffer is discarded without inspecting it; the private AST below is
      // the sole source of loop semantics, not any generated text or witness value.
      val start = output.length
      val next = body(indentation)
      output.setLength(start)
      val index = printer.component.localNamingScope.allocateName("i")
      printer.declarations ++= s"  integer $index;\n"
      def indexed(offset: Int): String = if (offset == 0) index else s"($index + $offset)"
      def value(node: ValueNode): String = node match {
        case Leaf(v, _) => printer.emitExpression(v)
        case IndexValue(offset, width) =>
          val w = geometry(printer.component, width)
          val base = s"$index[($w)-1:0]"
          if (offset == 0) base else {
            val bits = BigInt(offset).bitLength
            s"($base + {{(($w)-$bits){1'b0}}, $bits'd$offset})"
          }
        case Slice(v, offset, width) => s"${printer.emitExpression(v)}[${indexed(offset)} * (${geometry(printer.component, width)}) +: ${geometry(printer.component, width)}]"
        case Logic(operator, a, b, _) => s"(${value(a)} $operator ${value(b)})"
      }
      def predicate(node: PredicateNode): String = node match {
        case BoolLeaf(v) => printer.emitExpression(v)
        case BitTest(v, offset) => s"${printer.emitExpression(v)}[${indexed(offset)}]"
        case Compare(operator, a, b) =>
          val aw = geometry(printer.component, a.width)
          val bw = geometry(printer.component, b.width)
          val target = if (a.width.maximum <= b.width.minimum) bw
            else if (b.width.maximum <= a.width.minimum) aw
            else s"(($aw) > ($bw) ? ($aw) : ($bw))"
          def extended(node: ValueNode, width: String): String =
            if (width == target) value(node)
            else s"{{(($target)-($width)){1'b0}}, ${value(node)}}"
          s"(${extended(a, aw)} $operator ${extended(b, bw)})"
        case BooleanOp("!", a, None) => s"(!${predicate(a)})"
        case BooleanOp(operator, a, Some(b)) => s"(${predicate(a)} $operator ${predicate(b)})"
        case _ => fail("malformed authenticated predicate")
      }
      val loopBody = new StringBuilder
      def emit(program: Vector[Action], tab: String): Unit = program.foreach {
        case Write(source, selection) =>
          val target = printer.emitExpression(op.result) + selection.map { case (offset, width) =>
            s"[${indexed(offset)} * (${geometry(printer.component, width)}) +: ${geometry(printer.component, width)}]"
          }.getOrElse("")
          loopBody ++= s"$tab$target = ${value(source)};\n"
        case Branch(condition, yes, no) =>
          loopBody ++= s"${tab}if (${predicate(condition)}) begin\n"
          emit(yes, tab + "  ")
          if (no.nonEmpty) { loopBody ++= s"${tab}end else begin\n"; emit(no, tab + "  ") }
          loopBody ++= s"${tab}end\n"
      }
      emit(op.actions, indentation + "  ")
      output ++= s"${indentation}for ($index = 0; $index < ${geometry(printer.component, op.builder.count)}; $index = $index + 1) begin\n"
      output ++= loopBody
      output ++= s"${indentation}end\n"
      op.emitted = true
      next
    }
  def validate(component: Component): Unit = operations(component).foreach { op =>
    check(op); if (!op.emitted) fail("scoped process was not published exactly once")
  }
}
