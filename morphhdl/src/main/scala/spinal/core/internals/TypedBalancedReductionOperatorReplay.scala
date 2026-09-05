package spinal.core.internals

import java.util.IdentityHashMap
import scala.collection.mutable.ArrayBuffer
import spinal.core._
import TypedBalancedReductionValueEvidence.Evidence

/** Replay of an exact, closed, width-preserving native operator body.
  * This is one obligation of balanced-stage publication, NOT permission to
  * publish a reduction. It does not certify a Scala closure, invoke callbacks,
  * construct a tree, or implement Verilog operators.
  */
private[spinal] object TypedBalancedReductionOperatorReplay {
  private def fail(code: String, detail: String): Nothing =
    throw new IllegalArgumentException(s"MORPH-REDUCE-BALANCED-$code: $detail")

  /** Exact native classes, never names, subclasses, sampled values or text.
    * All admitted primitives are associative/commutative at equal positive
    * widths. Addition is modular; widening operations need a different proof.
    */
  private val constructors: Map[Class[_], () => BinaryOperator] = Map(
    classOf[Operator.Bool.And] -> (() => new Operator.Bool.And),
    classOf[Operator.Bool.Or] -> (() => new Operator.Bool.Or),
    classOf[Operator.Bool.Xor] -> (() => new Operator.Bool.Xor),
    classOf[Operator.Bits.And] -> (() => new Operator.Bits.And),
    classOf[Operator.Bits.Or] -> (() => new Operator.Bits.Or),
    classOf[Operator.Bits.Xor] -> (() => new Operator.Bits.Xor),
    classOf[Operator.UInt.Add] -> (() => new Operator.UInt.Add),
    classOf[Operator.UInt.And] -> (() => new Operator.UInt.And),
    classOf[Operator.UInt.Or] -> (() => new Operator.UInt.Or),
    classOf[Operator.UInt.Xor] -> (() => new Operator.UInt.Xor),
    classOf[Operator.SInt.Add] -> (() => new Operator.SInt.Add),
    classOf[Operator.SInt.And] -> (() => new Operator.SInt.And),
    classOf[Operator.SInt.Or] -> (() => new Operator.SInt.Or),
    classOf[Operator.SInt.Xor] -> (() => new Operator.SInt.Xor)
  )

  private val comparisonConstructors: Map[Class[_], () => BinaryOperator] = Map(
    classOf[Operator.UInt.Smaller] -> (() => new Operator.UInt.Smaller),
    classOf[Operator.SInt.Smaller] -> (() => new Operator.SInt.Smaller)
  )

  private def widthOf(value: BaseType): ElaborationIntegerExpression =
    ParameterizedWidth.expressionOf(value)
      .getOrElse(ElabInt.literal(value.getBitsWidth).expression)

  private def same[A <: AnyRef](left: Vector[A], right: Vector[A]): Boolean =
    left.size == right.size && left.zip(right).forall { case (a, b) => a eq b }

  private def assignmentsOf(owner: Component, target: BaseType): Vector[AssignmentStatement] = {
    val values = ArrayBuffer.empty[AssignmentStatement]
    owner.dslBody.walkStatements {
      case assignment: AssignmentStatement if assignment.finalTarget eq target => values += assignment
      case _ =>
    }
    values.toVector
  }

  private def checkOperand(value: BaseType, owner: Component, kind: AnyRef,
      width: ElaborationIntegerExpression): Unit = {
    if (value == null || (value.component ne owner) ||
        (value.getTypeObject.asInstanceOf[AnyRef] ne kind) ||
        value.hasTag(tagAutoResize) || BigInt(value.getBitsWidth) != width.default ||
        !ElabInt.equivalentExactFunction(widthOf(value), width))
      fail("REPLAY-OPERAND-SHAPE", "operand must retain the exact owner, type and typed width authority")
  }

  final class Proof private[TypedBalancedReductionOperatorReplay] (
      val operatorClass: Class[_],
      val nativeResult: BaseType,
      val resultWidth: ElaborationIntegerExpression,
      private val owner: Component,
      private val kind: AnyRef,
      private val minimum: Option[Boolean],
      private val replayBody: (BaseType, BaseType) => BaseType,
      private val guards: Vector[() => Unit]
  ) {
    /** A mux class alone cannot distinguish minimum from maximum. Stage
      * uniformity must compare this exact semantic key, not operatorClass.
      */
    val operationKey: (Class[_], Option[Boolean]) = (operatorClass, minimum)

    def validateFreshness(): Unit = guards.foreach(_.apply())

    /** Replay one native body without invoking its Scala callback. */
    def replay(left: BaseType, right: BaseType): BaseType = {
      validateFreshness()
      if (Component.current ne owner)
        fail("REPLAY-OWNER", "native graph replay must remain in its exact owning component")
      checkOperand(left, owner, kind, resultWidth)
      checkOperand(right, owner, kind, resultWidth)
      val result = replayBody(left, right)
      result match {
        case bits: BitVector if resultWidth.parameters.nonEmpty =>
          ParameterizedWidth.attach(bits, ElabInt.fromExpression(resultWidth).bits)
        case _ =>
      }
      result
    }
  }

  private def scalarOperands(callback: UnvalidatedBalancedCallback): Vector[BaseType] = {
    if (callback == null || callback.operands == null || callback.operands.size != 2 ||
        callback.operands.exists(_ == null) || callback.result == null)
      fail("REPLAY-BODY-ARITY", "operator proof requires two non-null scalar operands and a result")
    callback.operands.map {
      case value: BaseType => value
      case _ => fail("REPLAY-BODY-SHAPE", "composite callback bodies require a separate leaf-layout proof")
    }
  }

  /** Existing entry point: the two inputs carry direct native width evidence. */
  def certify(callback: UnvalidatedBalancedCallback): Proof = {
    val operands = scalarOperands(callback)
    certify(callback, operands.map(TypedBalancedReductionValueEvidence.input))
  }

  /** Whole-stage counterpart. An intermediate width is transferred by an
    * earlier opaque proof whose exact result identity must match this input.
    * No registry annotation is manufactured from equal default widths.
    */
  def certify(callback: UnvalidatedBalancedCallback, inputEvidence: Vector[Evidence]): Proof = {
    val operands = scalarOperands(callback)
    if (inputEvidence == null || inputEvidence.size != 2 || inputEvidence.exists(_ == null))
      fail("REPLAY-INPUT-EVIDENCE", "operator proof requires two complete native input certificates")
    operands.zip(inputEvidence).foreach { case (value, evidence) => evidence.requireValue(value) }
    val result = callback.result match {
      case value: BaseType => value
      case _ => fail("REPLAY-BODY-SHAPE", "operator result must be one native scalar")
    }
    val owner = operands.head.component
    if (owner == null || (Component.current ne owner))
      fail("REPLAY-OWNER", "operator proof requires the active owning component")
    val kind = operands.head.getTypeObject.asInstanceOf[AnyRef]
    if (!((kind eq TypeBool) || (kind eq TypeBits) || (kind eq TypeUInt) || (kind eq TypeSInt)))
      fail("REPLAY-BODY-TYPE", "operator proof requires Bool, Bits, UInt or SInt")
    val width = inputEvidence.head.width
    ElabInt.requireAuthoritativeIntegerDomain(width, "balanced operator width",
      "MORPH-REDUCE-BALANCED-REPLAY-WIDTH-AUTHORITY", requireExactExtrema = false)
    if (width.minimum < 1)
      fail("REPLAY-BODY-WIDTH", "operator width must remain positive over its complete domain")
    inputEvidence.foreach { evidence =>
      if ((evidence.owner ne owner) || (evidence.kind ne kind) ||
          !ElabInt.equivalentExactFunction(evidence.width, width))
        fail("REPLAY-OPERAND-SHAPE", "certified operands must share exact native type and width authority")
    }
    if (operands(0) eq operands(1))
      fail("REPLAY-BODY-OPERANDS", "a reduction pair must retain two distinct operand identities")

    val declarations = callback.declarations
    val recordedAssignments = callback.assignments
    if (declarations == null || recordedAssignments == null ||
        declarations.exists(_ == null) || recordedAssignments.exists(_ == null))
      fail("REPLAY-BODY-EVIDENCE", "native body evidence contains null")
    val seen = new IdentityHashMap[BaseType, java.lang.Boolean]()
    declarations.foreach { value =>
      if (seen.put(value, java.lang.Boolean.TRUE) != null)
        fail("REPLAY-BODY-EVIDENCE", "native body repeats a declaration identity")
    }
    val assignmentIdentities = new IdentityHashMap[AssignmentStatement, java.lang.Boolean]()
    recordedAssignments.foreach { value =>
      if (assignmentIdentities.put(value, java.lang.Boolean.TRUE) != null)
        fail("REPLAY-BODY-EVIDENCE", "native body repeats an assignment identity")
    }
    val consumedDeclarations = new IdentityHashMap[BaseType, java.lang.Boolean]()
    val consumedAssignments = new IdentityHashMap[AssignmentStatement, java.lang.Boolean]()
    val visiting = new IdentityHashMap[BaseType, java.lang.Boolean]()
    val guards = ArrayBuffer.empty[() => Unit]
    inputEvidence.foreach { evidence => guards += (() => evidence.requireFreshness()) }

    def expand(value: Expression, expectedKind: AnyRef = kind): Expression = value match {
      case leaf: BaseType if operands.exists(_ eq leaf) =>
        if (expectedKind ne kind)
          fail("REPLAY-BODY-TYPE", "comparison condition must be a native Bool result")
        leaf
      case leaf: BaseType =>
        if (!seen.containsKey(leaf))
          fail("REPLAY-EXTERNAL-READ", "operator body reads a signal outside its operands and local declarations")
        if (visiting.put(leaf, java.lang.Boolean.TRUE) != null)
          fail("REPLAY-BODY-CYCLE", "operator aliases contain a cycle")
        if ((leaf.component ne owner) || leaf.isReg || !leaf.isDirectionLess ||
            (leaf.parentScope ne owner.dslBody) || leaf.hasTag(tagAutoResize) ||
            (leaf.getTypeObject.asInstanceOf[AnyRef] ne expectedKind))
          fail("REPLAY-BODY-STATE", "operator locals must be exact-type root-scope combinational declarations")
        val localWidth = if (expectedKind eq TypeBool) ElabInt.literal(1).expression else width
        val fixed = leaf match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
        val retained = ParameterizedWidth.expressionOf(leaf)
        if ((fixed >= 0 && BigInt(fixed) != localWidth.default) ||
            retained.exists(value => !ElabInt.equivalentExactFunction(value, localWidth)) ||
            (localWidth.parameters.nonEmpty && fixed >= 0 && retained.isEmpty))
          fail("REPLAY-FIXED-WIDTH", "local width must preserve the complete operand width, not truncate or specialize its witness")
        val all = assignmentsOf(owner, leaf)
        if (all.size != 1 || !recordedAssignments.exists(_ eq all.head))
          fail("REPLAY-BODY-DRIVER", "each local requires exactly its one captured full-object driver")
        val assignment = all.head match {
          case data: DataAssignmentStatement if (data.target eq leaf) && (data.parentScope eq owner.dslBody) => data
          case _ => fail("REPLAY-BODY-DRIVER", "state, partial, conditional or initialization assignments cannot replay as an operator")
        }
        val source = assignment.source
        val parent = leaf.parentScope
        guards += (() => {
          val nowFixed = leaf match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
          if ((leaf.component ne owner) || leaf.isReg || !leaf.isDirectionLess ||
              (leaf.parentScope ne parent) || leaf.hasTag(tagAutoResize) ||
              (leaf.getTypeObject.asInstanceOf[AnyRef] ne expectedKind) ||
              !TypedBalancedReductionValueEvidence.preservesFixedWidth(fixed, nowFixed, localWidth) ||
              !same(assignmentsOf(owner, leaf), all) ||
              (assignment.source ne source) || (assignment.target ne leaf) ||
              (assignment.parentScope ne owner.dslBody) ||
              ParameterizedWidth.expressionOf(leaf).map(_.asInstanceOf[AnyRef]) != retained.map(_.asInstanceOf[AnyRef]))
            fail("REPLAY-STALE-GRAPH", "captured native declaration or driver changed after body certification")
        })
        consumedDeclarations.put(leaf, java.lang.Boolean.TRUE)
        consumedAssignments.put(assignment, java.lang.Boolean.TRUE)
        val expanded = expand(source, expectedKind)
        visiting.remove(leaf)
        expanded
      case other => other
    }

    def pairOrder(left: Expression, right: Expression): Boolean = {
      val a = expand(left)
      val b = expand(right)
      val forward = (a eq operands(0)) && (b eq operands(1))
      val reverse = (a eq operands(1)) && (b eq operands(0))
      if (!forward && !reverse)
        fail("REPLAY-BODY-OPERANDS", "native graph must consume both exact operands through transparent aliases")
      reverse
    }

    def guardBinary(native: BinaryOperator, expectedKind: AnyRef): Unit = {
      val left = native.left
      val right = native.right
      if (native.getTypeObject.asInstanceOf[AnyRef] ne expectedKind)
        fail("REPLAY-BODY-TYPE", "native primitive changed the result type")
      guards += (() => {
        if ((native.left ne left) || (native.right ne right) ||
            (native.getTypeObject.asInstanceOf[AnyRef] ne expectedKind))
          fail("REPLAY-STALE-GRAPH", "native primitive operands changed after certification")
      })
    }

    val native = expand(result)
    val (minimum, replayBody): (Option[Boolean], (BaseType, BaseType) => BaseType) = native match {
      case binary: BinaryOperator if constructors.contains(binary.getClass) =>
        val reverse = pairOrder(binary.left, binary.right)
        guardBinary(binary, kind)
        val constructor = constructors(binary.getClass)
        (None, (left: BaseType, right: BaseType) => {
          val a = if (reverse) right else left
          val b = if (reverse) left else right
          val expression = constructor()
          if (expression.getClass != binary.getClass)
            fail("REPLAY-CONSTRUCTOR", "native expression constructor changed its exact class")
          a.wrapBinaryOperator(b, expression)
        })
      case mux: BinaryMultiplexer if
          ((kind eq TypeUInt) && mux.getClass == classOf[BinaryMultiplexerUInt]) ||
          ((kind eq TypeSInt) && mux.getClass == classOf[BinaryMultiplexerSInt]) =>
        val condition = mux.cond
        val whenTrue = mux.whenTrue
        val whenFalse = mux.whenFalse
        val comparison = expand(condition, TypeBool) match {
          case binary: BinaryOperator if
              ((kind eq TypeUInt) && binary.getClass == classOf[Operator.UInt.Smaller]) ||
              ((kind eq TypeSInt) && binary.getClass == classOf[Operator.SInt.Smaller]) => binary
          case _ => fail("REPLAY-MINMAX-COMPARISON", "min/max requires the exact same-signedness native less-than comparator")
        }
        val comparisonReverse = pairOrder(comparison.left, comparison.right)
        val armsReverse = pairOrder(whenTrue, whenFalse)
        guardBinary(comparison, TypeBool)
        guards += (() => {
          if ((mux.cond ne condition) || (mux.whenTrue ne whenTrue) || (mux.whenFalse ne whenFalse) ||
              (mux.getTypeObject.asInstanceOf[AnyRef] ne kind))
            fail("REPLAY-STALE-GRAPH", "native min/max selector or arms changed after certification")
        })
        val constructor = comparisonConstructors(comparison.getClass)
        (Some(comparisonReverse == armsReverse), (left: BaseType, right: BaseType) => {
          val a = if (comparisonReverse) right else left
          val b = if (comparisonReverse) left else right
          val expression = constructor()
          if (expression.getClass != comparison.getClass)
            fail("REPLAY-CONSTRUCTOR", "native comparison constructor changed its exact class")
          val selected = a.wrapLogicalOperator(b, expression)
          val freshMux = left.newBinaryMultiplexerExpression()
          if (freshMux.getClass != mux.getClass)
            fail("REPLAY-CONSTRUCTOR", "native mux constructor changed its exact class")
          val yes = if (armsReverse) right else left
          val no = if (armsReverse) left else right
          left.wrapWithWeakClone(left.newMultiplexer(selected, yes, no, freshMux))
        })
      case _ => fail("REPLAY-NONASSOCIATIVE-OR-UNSUPPORTED", "body is not one exactly admitted associative native graph")
    }
    if (consumedDeclarations.size != declarations.size || consumedAssignments.size != recordedAssignments.size)
      fail("REPLAY-UNCONSUMED-EFFECT", "callback contains declarations or assignments outside its closed result graph")
    new Proof(native.getClass, result, width, owner, kind, minimum, replayBody, guards.toVector)
  }
}
