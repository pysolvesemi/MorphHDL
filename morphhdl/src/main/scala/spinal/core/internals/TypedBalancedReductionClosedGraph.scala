package spinal.core.internals

import java.util.IdentityHashMap
import scala.collection.mutable.ArrayBuffer
import spinal.core._

/** Closure and freshness of one native callback graph, not a replay permit.
  * Scala purity, algebraic admission, symbolic widths and bridge latency must
  * be certified separately. Observations expire at native normalization.
  */
private[spinal] object TypedBalancedReductionClosedGraph {
  private val MaximumNodes = 8192
  private val MaximumDepth = 512

  private def fail(code: String, detail: String): Nothing =
    throw new IllegalArgumentException(s"MORPH-REDUCE-BALANCED-GRAPH-$code: $detail")

  private final class Identity(val value: AnyRef) {
    override def equals(other: Any): Boolean = other match {
      case that: Identity => value eq that.value
      case _ => false
    }
    override def hashCode(): Int = System.identityHashCode(value)
  }
  private def identity(value: AnyRef): Identity = new Identity(value)

  private def identitySet[A <: AnyRef](values: Vector[A], role: String): IdentityHashMap[A, java.lang.Boolean] = {
    val result = new IdentityHashMap[A, java.lang.Boolean]()
    values.foreach { value =>
      if (value == null || result.put(value, java.lang.Boolean.TRUE) != null)
        fail("INVENTORY", s"$role contains a null or repeated native identity")
    }
    result
  }

  // Exact classes: an unknown Operator subclass must not acquire admission.
  // In particular a closed subtraction is not an associative reduction proof.
  private val expressionClasses: Set[Class[_]] = Set(
    classOf[Operator.Bool.And], classOf[Operator.Bool.Or], classOf[Operator.Bool.Xor],
    classOf[Operator.Bool.Not], classOf[Operator.Bool.Equal], classOf[Operator.Bool.NotEqual],
    classOf[Operator.Bits.And], classOf[Operator.Bits.Or], classOf[Operator.Bits.Xor],
    classOf[Operator.Bits.Not], classOf[Operator.Bits.Cat],
    classOf[Operator.UInt.And], classOf[Operator.UInt.Or], classOf[Operator.UInt.Xor],
    classOf[Operator.UInt.Not], classOf[Operator.UInt.Add], classOf[Operator.UInt.Sub], classOf[Operator.UInt.Mul],
    classOf[Operator.UInt.Smaller], classOf[Operator.UInt.SmallerOrEqual],
    classOf[Operator.UInt.Equal], classOf[Operator.UInt.NotEqual],
    classOf[Operator.SInt.And], classOf[Operator.SInt.Or], classOf[Operator.SInt.Xor],
    classOf[Operator.SInt.Not], classOf[Operator.SInt.Add], classOf[Operator.SInt.Sub], classOf[Operator.SInt.Mul],
    classOf[Operator.SInt.Smaller], classOf[Operator.SInt.SmallerOrEqual],
    classOf[Operator.SInt.Equal], classOf[Operator.SInt.NotEqual],
    classOf[CastUIntToBits], classOf[CastSIntToBits], classOf[CastBitsToUInt],
    classOf[CastBitsToSInt], classOf[CastSIntToUInt], classOf[CastUIntToSInt],
    classOf[BitsBitAccessFixed], classOf[UIntBitAccessFixed], classOf[SIntBitAccessFixed],
    classOf[CastBoolToBits], classOf[ResizeBits], classOf[ResizeUInt], classOf[ResizeSInt],
    classOf[MultiplexerBool], classOf[MultiplexerBits], classOf[MultiplexerUInt], classOf[MultiplexerSInt],
    // Native Mux/min/max use a binary mux, distinct from indexed multi-way muxes.
    // foreachExpression exposes the condition and both arms for full freezing.
    classOf[BinaryMultiplexerBool], classOf[BinaryMultiplexerBits],
    classOf[BinaryMultiplexerUInt], classOf[BinaryMultiplexerSInt],
    classOf[BitsLiteral], classOf[UIntLiteral], classOf[SIntLiteral], classOf[BoolLiteral]
  )
  private val leafClasses: Set[Class[_]] = Set(classOf[Bits], classOf[UInt], classOf[SInt], classOf[Bool])

  private final case class Node(
      native: Identity, kind: Class[_], children: Vector[Identity],
      typeObject: Identity, width: Option[Int], properties: Vector[Any]
  )
  private final case class StatementState(
      native: Identity, kind: Class[_], source: Identity, target: Identity,
      finalTarget: Identity, scope: Identity
  )
  private final case class Snapshot(
      owner: Identity, operands: Vector[Vector[Identity]], result: Vector[Identity],
      declarations: Vector[Identity], statements: Vector[StatementState], nodes: Vector[Node]
  )

  final class Observation private[TypedBalancedReductionClosedGraph] (
      private val callback: UnvalidatedBalancedCallback,
      private val frozen: Snapshot
  ) {
    val ordinal: Int = callback.ordinal
    val nodeCount: Int = frozen.nodes.size
    val registerCount: Int = callback.declarations.count(_.isReg)

    /** Recheck in-place children, literals and initializers, not merely the
      * top-level assignment source pointer. Must run before normalization.
      */
    def requireUnchanged(): Unit = {
      val current = inspect(callback)
      if (current != frozen)
        fail("CHANGED", "a captured callback's exact graph changed after observation")
    }
  }

  final class ReductionObservation[T <: Data] private[TypedBalancedReductionClosedGraph] (
      val native: UnvalidatedBalancedReduction[T],
      val callbacks: Vector[Observation]
  ) {
    def requireUnchanged(): Unit = callbacks.foreach(_.requireUnchanged())
    def requireReplayCertificate(): Nothing = native.requireReplayCertificate()
  }

  def observe(callback: UnvalidatedBalancedCallback): Observation =
    new Observation(callback, inspect(callback))

  /** Invoke the native helper once and observe callbacks as they complete. */
  def capture[T <: Data](
      vector: Vec[T], op: (T, T) => T, bridge: (T, Int) => T,
      native: ElabBalancedReduction.Native[T]
  ): ReductionObservation[T] = {
    val observed = ArrayBuffer.empty[Observation]
    val record = TypedBalancedReductionCapture(vector, op, bridge, native,
      (callback: UnvalidatedBalancedCallback) => {
        observed.foreach(_.requireUnchanged())
        observed += observe(callback)
      })
    observed.foreach(_.requireUnchanged())
    new ReductionObservation(record, observed.toVector)
  }

  private def inspect(callback: UnvalidatedBalancedCallback): Snapshot = {
    if (callback == null || callback.result == null || callback.operands == null ||
        callback.declarations == null || callback.assignments == null ||
        callback.operands.isEmpty || callback.operands.exists(_ == null))
      fail("NULL", "callback and its native inventories must be present")
    val resultLeaves = callback.result.flatten.toVector
    val operandLeaves = callback.operands.map(_.flatten.toVector)
    if (resultLeaves.isEmpty || operandLeaves.exists(_.isEmpty))
      fail("SHAPE", "callback operands and result must have nonempty leaf layouts")
    val owner = resultLeaves.head.component
    if (owner == null || resultLeaves.exists(_.component ne owner) ||
        operandLeaves.flatten.exists(_.component ne owner))
      fail("OWNER", "all data operands and results must have the same exact owner")

    val declarations = identitySet(callback.declarations, "declarations")
    val assignments: Vector[AssignmentStatement] = callback.assignments
    val recorded = identitySet(assignments, "assignments")
    val operands = new IdentityHashMap[BaseType, java.lang.Boolean]()
    operandLeaves.flatten.foreach(value => operands.put(value, java.lang.Boolean.TRUE))
    if (callback.declarations.exists(operands.containsKey))
      fail("INVENTORY", "a pre-existing operand was relabelled as a callback declaration")

    val liveDeclarations = new IdentityHashMap[BaseType, java.lang.Boolean]()
    val liveAssignments = new IdentityHashMap[AssignmentStatement, java.lang.Boolean]()
    owner.dslBody.walkStatements {
      case value: BaseType => liveDeclarations.put(value, java.lang.Boolean.TRUE)
      case value: AssignmentStatement =>
        liveAssignments.put(value, java.lang.Boolean.TRUE)
        if (declarations.containsKey(value.finalTarget) && !recorded.containsKey(value))
          fail("UNRECORDED-DRIVER", "a callback-local declaration has an unrecorded assignment")
      case _ =>
    }
    callback.declarations.foreach { value =>
      if (!liveDeclarations.containsKey(value) || (value.component ne owner) ||
          (value.parentScope ne owner.dslBody) || value.isAnalog || value.isIo)
        fail("DECLARATION", "callback-local data must be live, root-scoped, digital and internal")
    }
    assignments.foreach { value =>
      if (!liveAssignments.containsKey(value) || !declarations.containsKey(value.finalTarget))
        fail("ASSIGNMENT", "an assignment is missing or targets data outside this callback")
      if ((value.parentScope ne owner.dslBody) || (value.target ne value.finalTarget))
        fail("ASSIGNMENT-SHAPE", "only unconditional full-object callback assignments are closed here")
      if (value.getClass != classOf[DataAssignmentStatement] &&
          value.getClass != classOf[InitAssignmentStatement])
        fail("ASSIGNMENT-KIND", "unsupported callback assignment kind")
    }

    val drivers = new IdentityHashMap[BaseType, Vector[AssignmentStatement]]()
    callback.declarations.foreach { value =>
      val owned = assignments.filter(_.finalTarget eq value)
      val data = owned.count(_.isInstanceOf[DataAssignmentStatement])
      val init = owned.count(_.isInstanceOf[InitAssignmentStatement])
      if (data != 1 || init > 1 || (!value.isReg && init != 0))
        fail("DRIVERS", "each callback-local value needs one data driver and at most one register initializer")
      if (value.isReg && value.clockDomain == null)
        fail("CLOCK", "a callback register lost its native clock-domain identity")
      drivers.put(value, owned)
    }

    val state = new IdentityHashMap[Expression, java.lang.Integer]()
    val expressions = ArrayBuffer.empty[Expression]
    val edges = new IdentityHashMap[Expression, Vector[Expression]]()
    val operandDependencies = new IdentityHashMap[Expression, java.lang.Boolean]()

    def visit(value: Expression, depth: Int): Boolean = {
      if (value == null) fail("NULL", "native expression contains a null child")
      if (depth > MaximumDepth) fail("LIMIT", "callback expression nesting exceeds the reviewed bound")
      val prior = state.get(value)
      if (prior != null) {
        if (prior.intValue == 1) fail("CYCLE", "callback-local expression or register feedback is cyclic")
        return operandDependencies.get(value).booleanValue
      }
      if (state.size >= MaximumNodes) fail("LIMIT", "callback expression count exceeds the reviewed bound")
      state.put(value, java.lang.Integer.valueOf(1))
      val children: Vector[Expression] = value match {
        case leaf: BaseType =>
          if (!leafClasses.contains(leaf.getClass) || leaf.isAnalog)
            fail("LEAF-TYPE", "callback leaf is outside the reviewed scalar data types")
          if (operands.containsKey(leaf)) Vector.empty
          else if (declarations.containsKey(leaf)) drivers.get(leaf).map(_.source)
          else fail("EXTERNAL-READ", "callback expression reads data other than its operands or local declarations")
        case _ =>
          if (!expressionClasses.contains(value.getClass))
            fail("EXPRESSION", s"native expression class '${value.getClass.getName}' is not in the reviewed closed-graph subset")
          val children = ArrayBuffer.empty[Expression]
          value.foreachExpression(children += _)
          children.toVector
      }
      edges.put(value, children)
      // Traverse every child even after finding a dependency on an operand.
      val childDependencies = children.map(child => visit(child, depth + 1))
      val depends = (value match {
        case leaf: BaseType => operands.containsKey(leaf)
        case _ => false
      }) || childDependencies.contains(true)
      operandDependencies.put(value, java.lang.Boolean.valueOf(depends))
      state.put(value, java.lang.Integer.valueOf(2))
      expressions += value
      depends
    }
    resultLeaves.foreach(value => visit(value, 0))
    if (callback.declarations.exists(value => !state.containsKey(value)))
      fail("UNREACHABLE", "callback created declarations or state outside its result cone")
    assignments.collect { case value: InitAssignmentStatement => value }.foreach { value =>
      if (operandDependencies.get(value.source).booleanValue)
        fail("INITIALIZER", "a callback initializer depends on runtime data operands")
    }

    // Cycles were ruled out before native width queries. These are witness
    // snapshots only, never a substitute for symbolic width-transfer evidence.
    def properties(value: Expression): Vector[Any] = value match {
      case leaf: BaseType =>
        val width = ParameterizedWidth.expressionOf(leaf)
        width.foreach { expression =>
          ElaborationWidthAuthority.requireAuthoritative(expression,
            "balanced callback retained width", "MORPH-REDUCE-BALANCED-GRAPH-WIDTH-AUTHORITY")
        }
        Vector(leaf.getBitsWidth, leaf.isReg, leaf.isAnalog, leaf.isTypeNode,
          leaf.isInput, leaf.isOutput, leaf.isInOut,
          identity(leaf.parentScope), identity(leaf.clockDomain),
          width.map(expression => identity(expression)))
      case literal: BitVectorLiteral =>
        if (literal.value == null || literal.hasPoison)
          fail("LITERAL", "poison or uninitialized literal is not closed replay input")
        Vector(literal.value, literal.poisonMask, literal.bitCount, literal.hasSpecifiedBitCount)
      case literal: BoolLiteral => Vector(literal.value)
      case resize: Resize => Vector(resize.size, ParameterizedWidth.resizeExpressionOf(resize).map(identity))
      case access: BitVectorBitAccessFixed => Vector(access.bitId, NativeWidthProvenance.isHighBit(access))
      case _: Expression => Vector.empty
    }
    val nodes = expressions.toVector.map(value => Node(
      identity(value), value.getClass, edges.get(value).map(child => identity(child)),
      identity(value.getTypeObject.asInstanceOf[AnyRef]), value match {
        case sized: WidthProvider => Some(sized.getWidth)
        case _ => None
      }, properties(value)))
    Snapshot(identity(owner), operandLeaves.map(_.map(identity)), resultLeaves.map(identity),
      callback.declarations.map(identity), assignments.map(value => StatementState(
        identity(value), value.getClass, identity(value.source), identity(value.target),
        identity(value.finalTarget), identity(value.parentScope))), nodes)
  }
}
