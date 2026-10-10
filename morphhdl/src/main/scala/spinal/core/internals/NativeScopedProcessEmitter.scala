package spinal.core.internals

import spinal.core._
import spinal.core.ElabScopedProcess._

/** Lower an authenticated scoped program through native process callbacks.
  * The DSL owns one packed result with initialized logical fields; ordered writes cannot escape it.
  */
private[internals] object NativeScopedProcessEmitter {
  private def fail(detail: String): Nothing = ParameterizedVerilogException.fail(
    "SPINAL-SCOPED-PROCESS-PUBLICATION-MISMATCH", detail, None)
  private def geometry(component: Component, value: ElabInt): String =
    NativeLocalParameters.reference(component, value.expression).getOrElse(value.expression.verilog)
  private def check(op: Operation): Unit = {
    val writes = scala.collection.mutable.ArrayBuffer.empty[DataAssignmentStatement]
    op.result.foreachStatements { case a: DataAssignmentStatement => writes += a; case _ => }
    if (op.result.isReg || op.result.getBitsWidth != op.builder.resultWidth.witness || writes.size != 2 || !writes.exists(_ eq op.default) || !writes.exists(_ eq op.assignment) ||
        (op.default.target ne op.result) || (op.assignment.target ne op.result) ||
        (op.default.parentScope ne op.builder.component.dslBody) ||
        (op.tree.parentScope ne op.builder.component.dslBody) ||
        (op.assignment.parentScope ne op.tree.whenTrue) ||
        op.tree.whenTrue.statementIterable.toVector != Vector(op.assignment) ||
        op.tree.whenFalse.statementIterable.nonEmpty || !op.lineage.accepts(op.tree.cond))
      fail(s"${op.builder.component.getPath()}.${op.result.getName()} lost its exact process, default or write identity; ${op.lineage.explain(op.tree.cond)}")
    for ((assignment, expected) <- Vector(op.default -> defaultWitness(op.builder), op.assignment -> witnessValue(op.builder))) assignment.source match {
      case literal: BitVectorLiteral if !literal.hasPoison() && literal.getValue() == expected =>
      case _ => fail("scoped process witness edge changed")
    }
    if (op.builder.inputs.exists(ParameterizedProcess.conditionalReads(_, op.result)))
      fail("scoped process has combinational target feedback")
  }
  /** Only exact output-view lineage authorizes replacement of a concrete slice. */
  def widthOf(component: Component, expression: Expression): Option[ElaborationIntegerExpression] =
    operations(component).iterator.flatMap { op => op.views.iterator.filter(_.lineage.accepts(expression)).map { view =>
      check(op)
      if (view.result.getWidth != op.builder.outputs(view.field).width.witness || !view.lineage.accepts(view.assignment.source) || (view.assignment.target ne view.result) ||
          !view.result.hasOnlyOneStatement || (view.result.head ne view.assignment) ||
          (view.assignment.parentScope ne component.dslBody)) fail("scoped output view geometry lost its driver")
      op.builder.outputs(view.field).width.expression
    }}.toSeq.headOption

  private def field(printer: ComponentEmitterVerilog, op: Operation, number: Int): String = {
    val base = printer.emitExpression(op.result)
    if (op.builder.outputs.size == 1) base else
      s"$base[${geometry(printer.component, fieldOffset(op.builder, number))} +: ${geometry(printer.component, op.builder.outputs(number).width)}]"
  }
  private def literal(number: BigInt, width: String): String = {
    val bits = (if (number < 0) (-number-1).bitLength + 1 else math.max(1,number.bitLength))
    val encoded = number & ((BigInt(1) << bits)-1)
    s"{{(($width)-$bits){1'b${if(number < 0) 1 else 0}}}, $bits'd$encoded}"
  }
  def source(printer: ComponentEmitterVerilog, assignment: AssignmentStatement): Option[String] = {
    operations(printer.component).iterator.flatMap { op =>
      if (op.default eq assignment) {
        check(op)
        Some(op.builder.outputs.reverse.map(f => f.initial.map(printer.emitExpression(_)).getOrElse(literal(f.default, geometry(printer.component,f.width)))).mkString("{",", ","}"))
      } else op.views.find(_.assignment eq assignment).map { view =>
        check(op)
        if (view.result.getWidth != op.builder.outputs(view.field).width.witness || (view.assignment.target ne view.result) || !view.lineage.accepts(view.assignment.source) ||
            !view.result.hasOnlyOneStatement || (view.result.head ne view.assignment) ||
            (view.assignment.parentScope ne op.builder.component.dslBody)) fail(s"scoped output view lost exact ownership: ${view.result.getName()} target=${view.assignment.target eq view.result} single=${view.result.hasOnlyOneStatement} head=${view.result.head eq view.assignment} scope=${view.assignment.parentScope eq op.builder.component.dslBody}; ${view.lineage.explain(view.assignment.source)}")
        field(printer, op, view.field)
      }
    }.toSeq.headOption
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
      val indices = scala.collection.mutable.Map.empty[Loop, String]
      def allocate(loop: Loop): String = indices.getOrElseUpdate(loop, {
        val name = printer.component.localNamingScope.allocateName(if(loop.ordinal == 0) "i" else "j")
        printer.declarations ++= s"  integer $name;\n"
        name
      })
      def indexed(position: Position): String = {
        val terms = position.terms.map { case (loop,scale) =>
          val name = allocate(loop)
          if(scale.isConcrete && scale.minimum == 1) name else s"($name * (${geometry(printer.component,scale)}))"
        } ++ (if(position.offset == 0) Vector.empty else Vector(position.offset.toString))
        terms.mkString("("," + ",")")
      }
      def extended(node: ValueNode, target: String): String = {
        val w = geometry(printer.component,node.width)
        val raw = value(node)
        val sign = if(node.signed) s"(|($raw & {1'b1, {(($w)-1){1'b0}}}))" else "1'b0"
        if(w == target) raw else s"{{(($target)-($w)){$sign}}, $raw}"
      }
      def value(node: ValueNode): String = node match {
        case Leaf(v, _) => printer.emitExpression(v)
        case Constant(n,w,_) => literal(n,geometry(printer.component,w))
        case Accumulator(number,_,_) => field(printer,op,number)
        case Cast(v,w,_) => extended(v,geometry(printer.component,w))
        case Arithmetic(operator,a,b,_,_) => s"{(${value(a)} $operator ${value(b)})}"
        case IndexValue(position, width) =>
          val w = geometry(printer.component, width)
          if(position.terms.size == 1 && position.terms.head._2.isConcrete && position.terms.head._2.minimum == 1) {
            val base = s"${allocate(position.terms.head._1)}[($w)-1:0]"
            if(position.offset == 0) base else {
              val bits = BigInt(position.offset).bitLength
              s"($base + {{(($w)-$bits){1'b0}}, $bits'd${position.offset}})"
            }
          } else {
            val name = printer.component.localNamingScope.allocateName("process_index")
            val arguments = position.terms.map(_._1).distinct
            val formal = arguments.zipWithIndex.toMap
            val expression = position.terms.map { case(loop,scale) => s"(index_${formal(loop)} * (${geometry(printer.component,scale)}))" }.mkString(" + ") + s" + ${position.offset}"
            printer.declarations ++= s"  function [($w)-1:0] $name;\n" + arguments.map(l => s"    input integer index_${formal(l)};\n").mkString +
              s"    reg [31:0] unused_position;\n    begin\n      unused_position = $expression;\n      $name = unused_position[($w)-1:0];\n    end\n  endfunction\n"
            s"$name(${arguments.map(allocate).mkString(", ")})"
          }
        case Slice(v, position, width) => s"${printer.emitExpression(v)}[${indexed(position)} * (${geometry(printer.component, width)}) +: ${geometry(printer.component, width)}]"
        case Logic(operator, a, b, _) => s"(${value(a)} $operator ${value(b)})"
      }
      def predicate(node: PredicateNode): String = node match {
        case BoolLeaf(v) => printer.emitExpression(v)
        case BitTest(v, position) => s"${printer.emitExpression(v)}[${indexed(position)}]"
        case Compare(operator, a, b) =>
          val aw = geometry(printer.component, a.width)
          val bw = geometry(printer.component, b.width)
          val target = if (a.width.maximum <= b.width.minimum) bw
            else if (b.width.maximum <= a.width.minimum) aw
            else s"(($aw) > ($bw) ? ($aw) : ($bw))"
          def operand(v: ValueNode): String = if(v.signed) s"$$signed(${extended(v,target)})" else extended(v,target)
          s"(${operand(a)} $operator ${operand(b)})"
        case BooleanOp("!", a, None) => s"(!${predicate(a)})"
        case BooleanOp(operator, a, Some(b)) => s"(${predicate(a)} $operator ${predicate(b)})"
        case _ => fail("malformed authenticated predicate")
      }
      def emit(program: Vector[Action], tab: String): Unit = program.foreach {
        case Write(source, selection, number) =>
          val target = selection.map { case (position,width) =>
            val offset = geometry(printer.component,fieldOffset(op.builder,number))
            s"${printer.emitExpression(op.result)}[($offset) + ${indexed(position)} * (${geometry(printer.component,width)}) +: ${geometry(printer.component,width)}]"
          }.getOrElse(field(printer,op,number))
          output ++= s"$tab$target = ${value(source)};\n"
        case Branch(condition, yes, no) =>
          output ++= s"${tab}if (${predicate(condition)}) begin\n"
          emit(yes, tab + "  ")
          if (no.nonEmpty) { output ++= s"${tab}end else begin\n"; emit(no, tab + "  ") }
          output ++= s"${tab}end\n"
        case Repeat(loop, actions) => emitLoop(loop,actions,tab)
      }
      def emitLoop(loop: Loop, actions: Vector[Action], tab: String): Unit = {
        val index = allocate(loop)
        output ++= s"${tab}for ($index = 0; $index < ${geometry(printer.component,loop.count)}; $index = $index + 1) begin\n"
        emit(actions,tab + "  ")
        output ++= s"${tab}end\n"
      }
      def initializeNested(program: Vector[Action]): Unit = program.foreach {
        case Repeat(loop, actions) => output ++= s"${indentation}${allocate(loop)} = 0;\n"; initializeNested(actions)
        case Branch(_, yes, no) => initializeNested(yes); initializeNested(no)
        case _ =>
      }
      initializeNested(op.actions)
      emitLoop(op.builder.rootLoop,op.actions,indentation)
      op.emitted = true
      next
    }
  def validate(component: Component): Unit = operations(component).foreach { op =>
    check(op); if (!op.emitted) fail("scoped process was not published exactly once")
  }
}
