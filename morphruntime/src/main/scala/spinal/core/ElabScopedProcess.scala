package spinal.core

import scala.collection.mutable.ArrayBuffer
import spinal.core.internals._

/** Scoped combinational loop builder. Handles cannot be used after
  * the callback returns, in another builder, or converted to Scala witnesses.
  */
object ElabScopedProcess {
  private object Key
  private[core] final class Loop(val count: ElabInt, val ordinal: Int)
  private[core] case class Position(terms: Vector[(Loop, ElabInt)], offset: Int) {
    def limit: ElabInt = terms.foldLeft(ElabInt.literal(offset + 1)) { case (n, (loop, scale)) => n + (loop.count - 1) * scale }
    def witness(indices: Map[Loop, Int]): Int = terms.foldLeft(BigInt(offset)) { case (n,(loop,scale)) => n + indices(loop) * scale.witness }.toInt
  }
  private[core] sealed trait ValueNode { def width: ElabInt; def signed: Boolean = false }
  private[core] case class Constant(number: BigInt, width: ElabInt, override val signed: Boolean) extends ValueNode
  private[core] case class Accumulator(field: Int, width: ElabInt, override val signed: Boolean) extends ValueNode
  private[core] case class Cast(value: ValueNode, width: ElabInt, override val signed: Boolean) extends ValueNode
  private[core] case class Arithmetic(op: String, left: ValueNode, right: ValueNode, width: ElabInt, override val signed: Boolean) extends ValueNode
  private[core] case class Leaf(value: BitVector, width: ElabInt) extends ValueNode { override def signed: Boolean = value.isInstanceOf[SInt] }
  private[core] case class IndexValue(position: Position, width: ElabInt) extends ValueNode
  private[core] case class Slice(value: Bits, position: Position, width: ElabInt) extends ValueNode
  private[core] case class Logic(op: String, left: ValueNode, right: ValueNode, width: ElabInt) extends ValueNode { override def signed: Boolean = left.signed }
  private[core] sealed trait PredicateNode
  private[core] case class BoolLeaf(value: Bool) extends PredicateNode
  private[core] case class BitTest(value: Bits, position: Position) extends PredicateNode
  private[core] case class Compare(op: String, left: ValueNode, right: ValueNode) extends PredicateNode
  private[core] case class BooleanOp(op: String, left: PredicateNode, right: Option[PredicateNode]) extends PredicateNode
  private[core] sealed trait Action
  private[core] case class Write(value: ValueNode, slice: Option[(Position, ElabInt)], field: Int) extends Action
  private[core] case class Branch(condition: PredicateNode, yes: Vector[Action], no: Vector[Action]) extends Action

  private[core] case class Repeat(loop: Loop, body: Vector[Action]) extends Action

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

  /** One logical output; defaults must fit the smallest admitted width. */
  final case class Output(width: ElabInt, default: BigInt = 0, signed: Boolean = false, initial: Option[BitVector] = None)
  object Output {
    /** Hardware default sampled combinationally on every evaluation. */
    def from(value: BitVector): Output = {
      if (value == null) fail("initial value is missing")
      Output(ElabScopedProcess.width(value), signed = value.isInstanceOf[SInt], initial = Some(value))
    }
  }

  final class Index private[ElabScopedProcess] (private[ElabScopedProcess] val owner: Builder, private[ElabScopedProcess] val position: Position) {
    def +(amount: Int): Index = {
      owner.checkIndex(this)
      val next = BigInt(position.offset) + amount
      if (next < 0 || !next.isValidInt || next >= Int.MaxValue) fail("index offset escapes its nonnegative finite domain")
      new Index(owner, position.copy(offset = next.toInt))
    }
    def +(other: Index): Index = {
      owner.checkIndex(this); owner.checkIndex(other)
      val offset = BigInt(position.offset) + other.position.offset
      if (offset >= Int.MaxValue) fail("index offset exceeds signed index limit")
      new Index(owner, Position(position.terms ++ other.position.terms, offset.toInt))
    }
    def *(scale: ElabInt): Index = {
      owner.checkIndex(this); positive(scale, "index scale")
      if (position.offset != 0) fail("scale the index before adding a constant offset")
      new Index(owner, Position(position.terms.map { case (loop, value) => loop -> (value * scale) }, 0))
    }
    def *(scale: Int): Index = this * ElabInt.literal(scale)
    def asUInt(bits: ElabInt): Value = {
      owner.checkIndex(this); positive(bits, "index value width")
      if (bits.maximum > 32 || position.limit.maximum - 1 >= (BigInt(1) << bits.minimum.toInt))
        fail("index value width must represent every index over the complete domain, at most 32 bits")
      new Value(owner, IndexValue(position, bits))
    }
  }
  final class Value private[ElabScopedProcess] (private[ElabScopedProcess] val owner: Builder, private[ElabScopedProcess] val node: ValueNode) {
    private def comparison(op: String, other: Value): Predicate = {
      owner.checkValue(this); owner.checkValue(other)
      if (node.signed != other.node.signed) fail("comparison requires explicit signedness conversion")
      new Predicate(owner, Compare(op, node, other.node))
    }
    def <(other: Value): Predicate = comparison("<", other)
    def <=(other: Value): Predicate = comparison("<=", other)
    def ===(other: Value): Predicate = comparison("==", other)
    def =/=(other: Value): Predicate = comparison("!=", other)
    def >(other: Value): Predicate = comparison(">", other)
    def >=(other: Value): Predicate = comparison(">=", other)
    def asSigned: Value = { owner.checkValue(this); new Value(owner, Cast(node, node.width, true)) }
    def asUnsigned: Value = { owner.checkValue(this); new Value(owner, Cast(node, node.width, false)) }
    /** Explicit extension before arithmetic; same-width operations wrap modulo 2^width. */
    def extend(bits: ElabInt): Value = {
      owner.checkValue(this); positive(bits, "extended value width")
      if (!same(bits, node.width) && (bits - node.width).minimum < 0) fail("extend cannot truncate its operand")
      new Value(owner, Cast(node, bits, node.signed))
    }
    private def arithmetic(op: String, other: Value): Value = {
      owner.checkValue(this); owner.checkValue(other)
      if (!same(node.width, other.node.width) || node.signed != other.node.signed)
        fail("arithmetic needs equal explicit widths and signedness; extend or cast operands first")
      new Value(owner, Arithmetic(op, node, other.node, node.width, node.signed))
    }
    def +(other: Value): Value = arithmetic("+", other)
    def -(other: Value): Value = arithmetic("-", other)
    def *(other: Value): Value = arithmetic("*", other)
    private def logic(op: String, other: Value): Value = {
      owner.checkValue(this); owner.checkValue(other)
      if (!same(node.width, other.node.width) || node.signed != other.node.signed) fail("bitwise operands need identical authenticated widths and signedness")
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

  final class Builder private[ElabScopedProcess] (private[core] val count: ElabInt, private[core] val resultWidth: ElabInt, private[core] val outputs: Vector[Output]) {
    private[core] val component = Component.current
    private[core] val rootLoop = new Loop(count, 0)
    private var loopSerial = 0
    private var activeLoops = Vector(rootLoop)
    private var open = true
    private[ElabScopedProcess] var actions = ArrayBuffer.empty[Action]
    private[core] val geometry = ArrayBuffer(count, resultWidth)
    private[core] val inputs = ArrayBuffer.empty[BaseType]
    private[ElabScopedProcess] def check(): Unit =
      if (!open || (Component.current ne component) || DslScopeStack.get != component.dslBody)
        fail("scoped process handle escaped its owning callback/component")
    private[ElabScopedProcess] def checkValue(value: Value): Unit = {
      check(); if (value == null || (value.owner ne this)) fail("value belongs to another process")
      checkNode(value.node)
    }
    private[ElabScopedProcess] def checkPredicate(value: Predicate): Unit = {
      check(); if (value == null || (value.owner ne this)) fail("predicate belongs to another process")
      checkPredicateNode(value.node)
    }
    private def checkPosition(position: Position): Unit = {
      check()
      if (position.terms.exists { case (loop, _) => !activeLoops.exists(_ eq loop) })
        fail("nested index escaped its lexical loop")
      if (position.limit.maximum >= Int.MaxValue) fail("affine index exceeds the signed procedural index domain")
    }
    private[ElabScopedProcess] def checkIndex(value: Index): Unit = {
      check(); if (value == null || (value.owner ne this)) fail("index belongs to another process")
      checkPosition(value.position)
    }
    private def checkNode(node: ValueNode): Unit = node match {
      case IndexValue(position, _) => checkPosition(position)
      case Slice(_, position, _) => checkPosition(position)
      case Logic(_, a, b, _) => checkNode(a); checkNode(b)
      case Arithmetic(_, a, b, _, _) => checkNode(a); checkNode(b)
      case Cast(a, _, _) => checkNode(a)
      case _ =>
    }
    private def checkPredicateNode(node: PredicateNode): Unit = node match {
      case BitTest(_, position) => checkPosition(position)
      case Compare(_, a, b) => checkNode(a); checkNode(b)
      case BooleanOp(_, a, b) => checkPredicateNode(a); b.foreach(checkPredicateNode)
      case _ =>
    }
    private def input(value: BaseType): Unit = {
      check()
      if (value == null || (value.component ne component)) fail("process input must be owner-local")
      if (!inputs.exists(_ eq value)) inputs += value
    }
    private[ElabScopedProcess] def fits(available: ElabInt, required: ElabInt): Unit = {
      if (!same(available, required) && (available - required).minimum < 0)
        fail("indexed access is not proven in bounds over the complete parameter domain")
    }
    def index: Index = { check(); new Index(this, Position(Vector(activeLoops.last -> ElabInt.literal(1)), 0)) }
    def uint(value: UInt): Value = { input(value); val w = width(value); geometry += w; new Value(this, Leaf(value, w)) }
    def sint(value: SInt): Value = { input(value); new Value(this, Leaf(value, width(value))) }
    def bits(value: Bits): Value = { input(value); val w = width(value); geometry += w; new Value(this, Leaf(value, w)) }
    def bool(value: Bool): Predicate = { input(value); new Predicate(this, BoolLeaf(value)) }
    def bit(value: Bits, at: Index): Predicate = {
      checkIndex(at); input(value); fits(width(value), at.position.limit)
      new Predicate(this, BitTest(value, at.position))
    }
    def slice(value: Bits, at: Index, elementWidth: ElabInt): Value = {
      checkIndex(at); input(value); positive(elementWidth, "slice width")
      fits(width(value), (at.position.limit) * elementWidth); geometry += elementWidth
      new Value(this, Slice(value, at.position, elementWidth))
    }
    def assign(value: Value): Unit = {
      checkValue(value)
      if (!same(outputs.head.width, value.node.width)) fail("whole assignment width differs from process result")
      actions += Write(value.node, None, 0)
    }
    def assignSlice(at: Index, elementWidth: ElabInt, value: Value): Unit = {
      checkIndex(at); checkValue(value); positive(elementWidth, "target slice width")
      fits(outputs.head.width, (at.position.limit) * elementWidth)
      if (!same(elementWidth, value.node.width)) fail("slice assignment width differs from source")
      geometry += elementWidth
      actions += Write(value.node, Some(at.position -> elementWidth), 0)
    }
    def literal(number: BigInt, bits: ElabInt, signed: Boolean = false): Value = {
      check(); validateDefault(Output(bits, number, signed))
      new Value(this, Constant(number, bits, signed))
    }
    def output(number: Int): Target = {
      check(); if (number < 0 || number >= outputs.size) fail("output index is out of range")
      new Target(this, number)
    }
    def current: Value = output(0).current
    /** Nested bounded loop; index-derived values must stay inside its callback. */
    def foreach(iterations: ElabInt)(body: Index => Unit): Unit = {
      check(); positive(iterations, "nested loop count", 0)
      loopSerial += 1
      val loop = new Loop(iterations, loopSerial)
      val enclosing = actions
      activeLoops :+= loop
      actions = ArrayBuffer.empty[Action]
      try { body(index); enclosing += Repeat(loop, actions.toVector) }
      finally { actions = enclosing; activeLoops = activeLoops.dropRight(1) }
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

  final class Target private[ElabScopedProcess] (owner: Builder, number: Int) {
    def current: Value = { owner.check(); val spec = owner.outputs(number); new Value(owner, Accumulator(number, spec.width, spec.signed)) }
    def assign(value: Value): Unit = {
      owner.checkValue(value)
      if (!same(owner.outputs(number).width, value.node.width)) fail("output assignment width differs")
      owner.actions += Write(value.node, None, number)
    }
    def assignSlice(at: Index, elementWidth: ElabInt, value: Value): Unit = {
      owner.checkIndex(at); owner.checkValue(value); positive(elementWidth, "target slice width")
      owner.fits(owner.outputs(number).width, at.position.limit * elementWidth)
      if (!same(elementWidth, value.node.width)) fail("output slice assignment width differs")
      owner.actions += Write(value.node, Some(at.position -> elementWidth), number)
    }
  }
  private def validateDefault(spec: Output): Unit = {
    positive(spec.width, "output width")
    spec.initial.foreach { value =>
      if (value == null || spec.default != 0 || (value.component ne Component.current) || !same(spec.width, width(value)))
        fail("hardware default needs exact owner-local width and no competing literal default")
    }
    val bits = spec.width.minimum.toInt
    val fits = if (spec.signed) spec.default >= -(BigInt(1) << (bits-1)) && spec.default < (BigInt(1) << (bits-1))
      else spec.default >= 0 && spec.default < (BigInt(1) << bits)
    if (!fits) fail("default/literal does not fit every admitted output width")
  }
  private[core] def fieldOffset(builder: Builder, number: Int): ElabInt =
    builder.outputs.take(number).foldLeft(ElabInt.literal(0))((n, field) => n + field.width)
  private[core] def defaultWitness(builder: Builder): BigInt = builder.outputs.zipWithIndex.foldLeft(BigInt(0)) {
    case (bits, (field, number)) => bits | ((field.default & ((BigInt(1) << field.width.witness)-1)) << fieldOffset(builder, number).witness)
  }
  private[core] def witnessValue(builder: Builder): BigInt = if(defaultWitness(builder) == 1) BigInt(0) else BigInt(1)
  private[core] final case class View(result: Bits, assignment: DataAssignmentStatement, source: Expression, field: Int) {
    val lineage = ProcessExpressionLineage.capture(source, result.component)
  }
  private[core] final class Operation(val builder: Builder, val actions: Vector[Action], val result: BitVector,
      val tree: WhenStatement, val condition: Bool, val default: DataAssignmentStatement,
      val assignment: DataAssignmentStatement) {
    val conditionDriver = condition.head.asInstanceOf[DataAssignmentStatement].source
    val lineage = ProcessExpressionLineage.capture(condition, result.component)
    val views = ArrayBuffer.empty[View]
    var emitted = false
  }
  private[core] def operations(component: Component): Vector[Operation] =
    component.userCache.get(Key).toVector.flatMap(_.asInstanceOf[ArrayBuffer[Operation]])

  private[core] def build[T <: BitVector](count: ElabInt, resultWidth: ElabInt, result: => T,
      fields: Vector[Output] = Vector.empty)(body: Builder => Unit): T = {
    positive(count, "loop count", minimum = 0); positive(resultWidth, "result width")
    if (Component.current == null || DslScopeStack.get != Component.current.dslBody)
      fail("scoped processes must be authored at component scope")
    val outputs = if (fields.isEmpty) Vector(Output(resultWidth)) else fields
    outputs.foreach(validateDefault)
    val builder = new Builder(count, resultWidth, outputs)
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
    builder.geometry ++= Vector(count, resultWidth) ++ outputs.map(_.width)
    var writes = 0
    def use(input: BaseType): Unit = if (!builder.inputs.exists(_ eq input)) {
      builder.inputs += input
      input match {
        case packed: BitVector =>
          val w = width(packed); positive(w, "input width"); builder.geometry += w
        case _ =>
      }
    }
    def scanPosition(position: Position): Unit = position.terms.foreach { case (loop, scale) => builder.geometry ++= Vector(loop.count, scale) }
    def scanValue(node: ValueNode): Unit = {
      builder.geometry += node.width
      node match {
        case Leaf(input, _) => use(input)
        case Slice(input, position, _) => use(input); scanPosition(position)
        case Logic(_, a, b, _) => scanValue(a); scanValue(b)
        case IndexValue(position, _) => scanPosition(position)
        case Arithmetic(_, a, b, _, _) => scanValue(a); scanValue(b)
        case Cast(a, _, _) => scanValue(a)
        case _: Constant | _: Accumulator =>
      }
    }
    def scanPredicate(node: PredicateNode): Unit = node match {
      case BoolLeaf(input) => use(input)
      case BitTest(input, position) => use(input); scanPosition(position)
      case Compare(_, a, b) => scanValue(a); scanValue(b)
      case BooleanOp(_, a, b) => scanPredicate(a); b.foreach(scanPredicate)
    }
    def scan(actions: Vector[Action]): Unit = actions.foreach {
      case Write(value, selection, _) => writes += 1; scanValue(value); selection.foreach { s => scanPosition(s._1); builder.geometry += s._2 }
      case Repeat(loop, body) => builder.geometry += loop.count; scan(body)
      case Branch(condition, yes, no) => scanPredicate(condition); scan(yes); scan(no)
    }
    scan(program)
    outputs.flatMap(_.initial).foreach(use)
    if (writes == 0 || builder.inputs.isEmpty) fail("process needs ordered writes and at least one hardware dependency used by its body")
    builder.inputs.foreach { value =>
      if (!value.getTags().exists(ParameterizedExpressionCarrier.isGeometryBoundary))
        ParameterizedExpressionCarrier.retain(value)
    }
    val output = ParameterizedExpressionCarrier.retain(result)
    output.setWeakName("process_result")
    output match { case v: UInt => v := defaultWitness(builder); case v: Bits => v := defaultWitness(builder) }
    val default = output.head.asInstanceOf[DataAssignmentStatement]
    if (!ParameterizedStructure.captureEnabled) {
      default.removeStatement()
      var values = outputs.map(s => s.initial.map(_.asBits).getOrElse(B(s.default & ((BigInt(1) << s.width.witness)-1), s.width.witness bits)))
      for (i <- 0 until count.witness) values = evaluate(program, values, Map(builder.rootLoop -> i))
      output.assignFromBits(values.reverse.reduce(_ ## _))
    } else {
      val dependencies = builder.inputs.map { case v: Bool => v; case v: BitVector => v.orR }.reduce(_ | _)
      // A single Bool dependency is already an input boundary, not a private
      // witness declaration. Other reductions already own their fresh carrier.
      val condition = if (builder.inputs.exists(_ eq dependencies)) {
        val value = ParameterizedExpressionCarrier.retain(Bool())
        value := dependencies
        value
      } else ParameterizedExpressionCarrier.retain(dependencies)
      condition.setWeakName("process_active")
      var tree: WhenStatement = null
      var assignment: DataAssignmentStatement = null
      when(condition) {
        tree = DslScopeStack.get.parentStatement.asInstanceOf[WhenStatement]
        output match { case v: UInt => v := witnessValue(builder); case v: Bits => v := witnessValue(builder) }
        assignment = tree.whenTrue.last.asInstanceOf[DataAssignmentStatement]
      }
      builder.component.userCache.getOrElseUpdate(Key, ArrayBuffer.empty[Operation])
        .asInstanceOf[ArrayBuffer[Operation]] += new Operation(builder, program, output, tree, condition, default, assignment)
    }
    output
  }
  private[core] def buildOutputs(count: ElabInt, fields: Vector[Output])(body: Builder => Unit): Vector[Bits] = {
    if (fields.isEmpty) fail("process needs at least one output")
    fields.foreach(validateDefault)
    val total = fields.map(_.width).reduce(_ + _)
    val packed = build(count, total, Bits(total bits), fields)(body)
    val op = operations(Component.current).find(_.result eq packed)
    var offset = 0
    fields.zipWithIndex.map { case (field, number) =>
      val result = ParameterizedExpressionCarrier.retain(Bits(field.width bits))
      result.setWeakName("process_output")
      result := packed(offset, field.width.witness bits)
      val assignment = result.head.asInstanceOf[DataAssignmentStatement]
      op.foreach(_.views += View(result, assignment, assignment.source, number))
      offset += field.width.witness
      result
    }
  }
  private def value(node: ValueNode, indices: Map[Loop, Int], outputs: Vector[Bits]): Bits = node match {
    case Leaf(v, _) => v.asBits
    case Constant(n, w, _) => B(n & ((BigInt(1) << w.witness)-1), w.witness bits)
    case Accumulator(field, _, _) => outputs(field)
    case IndexValue(position, w) => B(position.witness(indices), w.witness bits)
    case Slice(v, position, w) => v(position.witness(indices) * w.witness, w.witness bits)
    case Cast(v, w, _) => if (v.signed) value(v, indices, outputs).asSInt.resize(w.witness).asBits else value(v, indices, outputs).resize(w.witness)
    case Arithmetic(op, a, b, w, _) =>
      val x = value(a,indices,outputs).asUInt; val y = value(b,indices,outputs).asUInt
      (op match { case "+" => x+y; case "-" => x-y; case "*" => val product=x*y; product.setWeakName("unused_product"); product }).resize(w.witness).asBits
    case Logic(op, a, b, _) => op match {
      case "&" => value(a, indices, outputs) & value(b, indices, outputs)
      case "|" => value(a, indices, outputs) | value(b, indices, outputs)
      case "^" => value(a, indices, outputs) ^ value(b, indices, outputs)
    }
  }
  private def predicate(node: PredicateNode, indices: Map[Loop, Int], outputs: Vector[Bits]): Bool = node match {
    case BoolLeaf(v) => v
    case BitTest(v, position) => v(position.witness(indices))
    case Compare(op, a, b) =>
      val x = value(a,indices,outputs); val y = value(b,indices,outputs)
      if (a.signed) op match {
        case "<" => x.asSInt < y.asSInt; case "<=" => x.asSInt <= y.asSInt
        case ">" => x.asSInt > y.asSInt; case ">=" => x.asSInt >= y.asSInt
        case "==" => x.asSInt === y.asSInt; case "!=" => x.asSInt =/= y.asSInt
      } else op match {
        case "<" => x.asUInt < y.asUInt; case "<=" => x.asUInt <= y.asUInt
        case ">" => x.asUInt > y.asUInt; case ">=" => x.asUInt >= y.asUInt
        case "==" => x.asUInt === y.asUInt; case "!=" => x.asUInt =/= y.asUInt
      }
    case BooleanOp("!", a, _) => !predicate(a, indices, outputs)
    case BooleanOp("&&", a, Some(b)) => predicate(a, indices, outputs) && predicate(b, indices, outputs)
    case BooleanOp("||", a, Some(b)) => predicate(a, indices, outputs) || predicate(b, indices, outputs)
    case _ => fail("malformed scoped predicate")
  }
  private def evaluate(program: Vector[Action], initial: Vector[Bits], indices: Map[Loop, Int]): Vector[Bits] = {
    var state = initial
    program.foreach {
      case Write(source, selection, field) =>
        val next = Bits(state(field).getWidth bits)
        selection match {
          case None => next := value(source, indices, state)
          case Some((position, width)) =>
            // The selected old bits are intentionally overwritten. Keep that
            // fact visible on the ordinary unrolled temporary as well.
            if (!state(field).isIo && !state(field).isReg) state(field).setWeakName("unused_previous")
            val low = position.witness(indices) * width.witness
            val high = low + width.witness
            val parts = (if(high < state(field).getWidth) Vector(state(field)(state(field).getWidth-1 downto high)) else Vector.empty) ++
              Vector(value(source, indices, state)) ++ (if(low > 0) Vector(state(field)(low-1 downto 0)) else Vector.empty)
            next := parts.reduce(_ ## _)
        }
        state = state.updated(field, next)
      case Branch(test, yes, no) =>
        val condition = predicate(test, indices, state)
        val a = evaluate(yes, state, indices); val b = evaluate(no, state, indices)
        state = state.indices.map { n =>
          val next = Bits(state(n).getWidth bits)
          spinal.core.when(condition) { next := a(n) } otherwise { next := b(n) }
          next
        }.toVector
      case Repeat(loop, body) => for (i <- 0 until loop.count.witness) state = evaluate(body, state, indices + (loop -> i))
    }
    state
  }
}
