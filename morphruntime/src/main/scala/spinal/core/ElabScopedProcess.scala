package spinal.core

import scala.collection.mutable.ArrayBuffer
import spinal.core.internals._

/** Scoped, unsigned combinational loop builder. Handles cannot be used after
  * the callback returns, in another builder, or converted to Scala witnesses.
  */
object ElabScopedProcess {
  private object Key
  private[core] sealed trait ValueNode { def width: ElabInt }
  private[core] case class Leaf(value: BitVector, width: ElabInt) extends ValueNode
  private[core] case class IndexValue(offset: Int, width: ElabInt) extends ValueNode
  private[core] case class Slice(value: Bits, offset: Int, width: ElabInt) extends ValueNode
  private[core] case class Logic(op: String, left: ValueNode, right: ValueNode, width: ElabInt) extends ValueNode
  private[core] sealed trait PredicateNode
  private[core] case class BoolLeaf(value: Bool) extends PredicateNode
  private[core] case class BitTest(value: Bits, offset: Int) extends PredicateNode
  private[core] case class Compare(op: String, left: ValueNode, right: ValueNode) extends PredicateNode
  private[core] case class BooleanOp(op: String, left: PredicateNode, right: Option[PredicateNode]) extends PredicateNode
  private[core] sealed trait Action
  private[core] case class Write(value: ValueNode, slice: Option[(Int, ElabInt)]) extends Action
  private[core] case class Branch(condition: PredicateNode, yes: Vector[Action], no: Vector[Action]) extends Action

  private def fail(detail: String): Nothing = ParameterizedVerilogException.fail(
    "SPINAL-SCOPED-PROCESS-UNSUPPORTED", detail, None)
  private def positive(value: ElabInt, role: String, minimum: Int = 1): Unit = {
    if (value == null) fail(s"$role is missing")
    ElaborationWidthAuthority.requireAuthoritative(value.expression, role, "SPINAL-SCOPED-PROCESS-DOMAIN-MISSING")
    if (value.minimum < minimum || value.maximum >= Int.MaxValue) fail(s"$role must be at least $minimum and below the signed index limit")
  }
  private def same(a: ElabInt, b: ElabInt): Boolean = ElabFiniteRange.equivalentLogicalCount(a.expression, b.expression)
  private def width(value: BitVector): ElabInt = ElabInt.fromExpression(
    NativeWidthProvenance.widthOf(value).getOrElse(fail("source has no authenticated width")))

  final class Index private[ElabScopedProcess] (private[ElabScopedProcess] val owner: Builder, private[ElabScopedProcess] val offset: Int) {
    def +(amount: Int): Index = {
      owner.check()
      val next = BigInt(offset) + amount
      if (next < 0 || next + owner.count.maximum >= Int.MaxValue) fail("index offset escapes its nonnegative finite domain")
      new Index(owner, next.toInt)
    }
    def asUInt(bits: ElabInt): Value = {
      owner.check(); positive(bits, "index value width")
      if (bits.maximum > 32 || owner.count.maximum - 1 + offset >= (BigInt(1) << bits.minimum.toInt))
        fail("index value width must represent every index over the complete domain, at most 32 bits")
      owner.geometry += bits
      new Value(owner, IndexValue(offset, bits))
    }
  }
  final class Value private[ElabScopedProcess] (private[ElabScopedProcess] val owner: Builder, private[ElabScopedProcess] val node: ValueNode) {
    private def comparison(op: String, other: Value): Predicate = {
      owner.checkValue(this); owner.checkValue(other)
      new Predicate(owner, Compare(op, node, other.node))
    }
    def <(other: Value): Predicate = comparison("<", other)
    def <=(other: Value): Predicate = comparison("<=", other)
    def ===(other: Value): Predicate = comparison("==", other)
    def =/=(other: Value): Predicate = comparison("!=", other)
    private def logic(op: String, other: Value): Value = {
      owner.checkValue(this); owner.checkValue(other)
      if (!same(node.width, other.node.width)) fail("bitwise operands need identical authenticated widths")
      new Value(owner, Logic(op, node, other.node, node.width))
    }
    def &(other: Value): Value = logic("&", other)
    def |(other: Value): Value = logic("|", other)
    def ^(other: Value): Value = logic("^", other)
  }
  final class Predicate private[ElabScopedProcess] (private[ElabScopedProcess] val owner: Builder, private[ElabScopedProcess] val node: PredicateNode) {
    def unary_! : Predicate = { owner.checkPredicate(this); new Predicate(owner, BooleanOp("!", node, None)) }
    private def combine(op: String, other: Predicate): Predicate = {
      owner.checkPredicate(this); owner.checkPredicate(other)
      new Predicate(owner, BooleanOp(op, node, Some(other.node)))
    }
    def &&(other: Predicate): Predicate = combine("&&", other)
    def ||(other: Predicate): Predicate = combine("||", other)
  }

  final class Builder private[ElabScopedProcess] (private[core] val count: ElabInt, private[core] val resultWidth: ElabInt) {
    private[core] val component = Component.current
    private var open = true
    private var actions = ArrayBuffer.empty[Action]
    private[core] val geometry = ArrayBuffer(count, resultWidth)
    private[core] val inputs = ArrayBuffer.empty[BaseType]
    private[ElabScopedProcess] def check(): Unit =
      if (!open || (Component.current ne component) || DslScopeStack.get != component.dslBody)
        fail("scoped process handle escaped its owning callback/component")
    private[ElabScopedProcess] def checkValue(value: Value): Unit = {
      check(); if (value == null || (value.owner ne this)) fail("value belongs to another process")
    }
    private[ElabScopedProcess] def checkPredicate(value: Predicate): Unit = {
      check(); if (value == null || (value.owner ne this)) fail("predicate belongs to another process")
    }
    private def checkIndex(value: Index): Unit = {
      check(); if (value == null || (value.owner ne this)) fail("index belongs to another process")
    }
    private def input(value: BaseType): Unit = {
      check()
      if (value == null || (value.component ne component)) fail("process input must be owner-local")
      if (!inputs.exists(_ eq value)) inputs += value
    }
    private def fits(available: ElabInt, required: ElabInt): Unit = {
      if (!same(available, required) && available.minimum < required.maximum)
        fail("indexed access is not proven in bounds over the complete parameter domain")
    }
    def index: Index = { check(); new Index(this, 0) }
    def uint(value: UInt): Value = { input(value); val w = width(value); geometry += w; new Value(this, Leaf(value, w)) }
    def bits(value: Bits): Value = { input(value); val w = width(value); geometry += w; new Value(this, Leaf(value, w)) }
    def bool(value: Bool): Predicate = { input(value); new Predicate(this, BoolLeaf(value)) }
    def bit(value: Bits, at: Index): Predicate = {
      checkIndex(at); input(value); fits(width(value), (if (at.offset == 0) count else count + at.offset))
      new Predicate(this, BitTest(value, at.offset))
    }
    def slice(value: Bits, at: Index, elementWidth: ElabInt): Value = {
      checkIndex(at); input(value); positive(elementWidth, "slice width")
      fits(width(value), ((if (at.offset == 0) count else count + at.offset)) * elementWidth); geometry += elementWidth
      new Value(this, Slice(value, at.offset, elementWidth))
    }
    def assign(value: Value): Unit = {
      checkValue(value)
      if (!same(resultWidth, value.node.width)) fail("whole assignment width differs from process result")
      actions += Write(value.node, None)
    }
    def assignSlice(at: Index, elementWidth: ElabInt, value: Value): Unit = {
      checkIndex(at); checkValue(value); positive(elementWidth, "target slice width")
      fits(resultWidth, ((if (at.offset == 0) count else count + at.offset)) * elementWidth)
      if (!same(elementWidth, value.node.width)) fail("slice assignment width differs from source")
      geometry += elementWidth
      actions += Write(value.node, Some(at.offset -> elementWidth))
    }
    def when(condition: Predicate)(body: => Unit): Unit = ifElse(condition)(body)({})
    def ifElse(condition: Predicate)(yes: => Unit)(no: => Unit): Unit = {
      checkPredicate(condition)
      val enclosing = actions
      def branch(body: => Unit): Vector[Action] = { actions = ArrayBuffer.empty[Action]; body; actions.toVector }
      try { val a = branch(yes); val b = branch(no); enclosing += Branch(condition.node, a, b) }
      finally actions = enclosing
    }
    private[ElabScopedProcess] def finish(): Vector[Action] = { check(); open = false; actions.toVector }
    private[ElabScopedProcess] def close(): Unit = open = false
  }

  private[core] final class Operation(val builder: Builder, val actions: Vector[Action], val result: BitVector,
      val tree: WhenStatement, val condition: Bool, val default: DataAssignmentStatement,
      val assignment: DataAssignmentStatement) {
    val conditionDriver = condition.head.asInstanceOf[DataAssignmentStatement].source
    val lineage = ProcessExpressionLineage.capture(condition, result.component)
    var emitted = false
  }
  private[core] def operations(component: Component): Vector[Operation] =
    component.userCache.get(Key).toVector.flatMap(_.asInstanceOf[ArrayBuffer[Operation]])

  private[core] def build[T <: BitVector](count: ElabInt, resultWidth: ElabInt, result: => T)(body: Builder => Unit): T = {
    positive(count, "loop count", minimum = 0); positive(resultWidth, "result width")
    if (Component.current == null || DslScopeStack.get != Component.current.dslBody)
      fail("scoped processes must be authored at component scope")
    val builder = new Builder(count, resultWidth)
    val before = builder.component.dslBody.statementIterable.toVector
    val children = builder.component.children.toVector
    val program = try { body(builder); builder.finish() } finally builder.close()
    if (builder.component.dslBody.statementIterable.toVector != before || builder.component.children.toVector != children)
      fail("scoped callbacks must only construct process expressions and ordered writes, not native declarations or side effects")
    // Only reachable program nodes justify native dependencies and parameter
    // liveness. An unused handle must not fabricate an event source for a
    // constant-only process or keep an unrelated declaration/parameter alive.
    builder.inputs.clear()
    builder.geometry.clear()
    builder.geometry ++= Vector(count, resultWidth)
    var writes = 0
    def use(input: BaseType): Unit = if (!builder.inputs.exists(_ eq input)) {
      builder.inputs += input
      input match {
        case packed: BitVector =>
          val w = width(packed); positive(w, "input width"); builder.geometry += w
        case _ =>
      }
    }
    def scanValue(node: ValueNode): Unit = {
      builder.geometry += node.width
      node match {
        case Leaf(input, _) => use(input)
        case Slice(input, _, _) => use(input)
        case Logic(_, a, b, _) => scanValue(a); scanValue(b)
        case _: IndexValue =>
      }
    }
    def scanPredicate(node: PredicateNode): Unit = node match {
      case BoolLeaf(input) => use(input)
      case BitTest(input, _) => use(input)
      case Compare(_, a, b) => scanValue(a); scanValue(b)
      case BooleanOp(_, a, b) => scanPredicate(a); b.foreach(scanPredicate)
    }
    def scan(actions: Vector[Action]): Unit = actions.foreach {
      case Write(value, selection) => writes += 1; scanValue(value); selection.foreach(s => builder.geometry += s._2)
      case Branch(condition, yes, no) => scanPredicate(condition); scan(yes); scan(no)
    }
    scan(program)
    if (writes == 0 || builder.inputs.isEmpty) fail("process needs ordered writes and at least one hardware dependency used by its body")
    builder.inputs.foreach { value =>
      if (!value.getTags().exists(ParameterizedExpressionCarrier.isGeometryBoundary))
        ParameterizedExpressionCarrier.retain(value)
    }
    val output = ParameterizedExpressionCarrier.retain(result)
    output.setWeakName("process_result")
    output match { case v: UInt => v := 0; case v: Bits => v := 0 }
    val default = output.head.asInstanceOf[DataAssignmentStatement]
    if (!ParameterizedStructure.captureEnabled) {
      for (i <- 0 until count.witness) evaluate(program, output, i)
    } else {
      val condition = ParameterizedExpressionCarrier.retain(builder.inputs.map(_.asBits.orR).reduce(_ | _))
      condition.setWeakName("process_active")
      var tree: WhenStatement = null
      var assignment: DataAssignmentStatement = null
      when(condition) {
        tree = DslScopeStack.get.parentStatement.asInstanceOf[WhenStatement]
        output match { case v: UInt => v := 1; case v: Bits => v := 1 }
        assignment = tree.whenTrue.last.asInstanceOf[DataAssignmentStatement]
      }
      builder.component.userCache.getOrElseUpdate(Key, ArrayBuffer.empty[Operation])
        .asInstanceOf[ArrayBuffer[Operation]] += new Operation(builder, program, output, tree, condition, default, assignment)
    }
    output
  }
  private def value(node: ValueNode, i: Int): Bits = node match {
    case Leaf(v, _) => v.asBits
    case IndexValue(offset, w) => B(BigInt(i) + offset, w.witness bits)
    case Slice(v, offset, w) => v((i + offset) * w.witness, w.witness bits)
    case Logic(op, a, b, _) => op match {
      case "&" => value(a, i) & value(b, i)
      case "|" => value(a, i) | value(b, i)
      case "^" => value(a, i) ^ value(b, i)
    }
  }
  private def predicate(node: PredicateNode, i: Int): Bool = node match {
    case BoolLeaf(v) => v
    case BitTest(v, offset) => v(i + offset)
    case Compare(op, a, b) => op match {
      case "<" => value(a, i).asUInt < value(b, i).asUInt
      case "<=" => value(a, i).asUInt <= value(b, i).asUInt
      case "==" => value(a, i).asUInt === value(b, i).asUInt
      case "!=" => value(a, i).asUInt =/= value(b, i).asUInt
    }
    case BooleanOp("!", a, _) => !predicate(a, i)
    case BooleanOp("&&", a, Some(b)) => predicate(a, i) && predicate(b, i)
    case BooleanOp("||", a, Some(b)) => predicate(a, i) || predicate(b, i)
  }
  private def evaluate(program: Vector[Action], output: BitVector, i: Int): Unit = program.foreach {
    case Write(source, None) => output.assignFromBits(value(source, i))
    case Write(source, Some((offset, width))) => output.assignFromBits(value(source, i), (i + offset) * width.witness + width.witness - 1, (i + offset) * width.witness)
    case Branch(test, yes, no) => spinal.core.when(predicate(test, i)) { evaluate(yes, output, i) } otherwise { evaluate(no, output, i) }
  }
}
