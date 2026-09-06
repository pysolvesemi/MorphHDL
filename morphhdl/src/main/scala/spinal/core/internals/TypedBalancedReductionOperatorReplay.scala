package spinal.core.internals

import java.util.IdentityHashMap
import scala.collection.mutable.ArrayBuffer
import spinal.core._
import TypedBalancedReductionValueEvidence.Evidence

/** Replay of an exact, closed native scalar operator graph with typed width transfer.
  * This is one obligation of balanced-stage publication, NOT permission to
  * publish a reduction. It does not certify a Scala closure, invoke callbacks,
  * construct a tree, or implement Verilog operators.
  */
private[spinal] object TypedBalancedReductionOperatorReplay {
  private def fail(code: String, detail: String): Nothing =
    throw new IllegalArgumentException(s"MORPH-REDUCE-BALANCED-$code: $detail")

  /** Exact native classes, never names, subclasses, sampled values or text.
    * The native graph owns width and signedness semantics; this table does not
    * select or implement a production reduction algorithm.
    */
  private val constructors: Map[Class[_], () => BinaryOperator] = Map(
    classOf[Operator.Bool.And] -> (() => new Operator.Bool.And),
    classOf[Operator.Bool.Or] -> (() => new Operator.Bool.Or),
    classOf[Operator.Bool.Xor] -> (() => new Operator.Bool.Xor),
    classOf[Operator.Bits.And] -> (() => new Operator.Bits.And),
    classOf[Operator.Bits.Or] -> (() => new Operator.Bits.Or),
    classOf[Operator.Bits.Xor] -> (() => new Operator.Bits.Xor),
    classOf[Operator.UInt.Add] -> (() => new Operator.UInt.Add),
    classOf[Operator.UInt.Mul] -> (() => new Operator.UInt.Mul),
    classOf[Operator.UInt.And] -> (() => new Operator.UInt.And),
    classOf[Operator.UInt.Or] -> (() => new Operator.UInt.Or),
    classOf[Operator.UInt.Xor] -> (() => new Operator.UInt.Xor),
    classOf[Operator.SInt.Add] -> (() => new Operator.SInt.Add),
    classOf[Operator.SInt.Mul] -> (() => new Operator.SInt.Mul),
    classOf[Operator.SInt.And] -> (() => new Operator.SInt.And),
    classOf[Operator.SInt.Or] -> (() => new Operator.SInt.Or),
    classOf[Operator.SInt.Xor] -> (() => new Operator.SInt.Xor)
  )

  private val comparisonConstructors: Map[Class[_], () => BinaryOperator] = Map(
    classOf[Operator.UInt.Smaller] -> (() => new Operator.UInt.Smaller),
    classOf[Operator.SInt.Smaller] -> (() => new Operator.SInt.Smaller)
  )

  private val castConstructors: Map[Class[_], () => Cast] = Map(
    classOf[CastUIntToBits] -> (() => new CastUIntToBits),
    classOf[CastSIntToBits] -> (() => new CastSIntToBits),
    classOf[CastBitsToUInt] -> (() => new CastBitsToUInt),
    classOf[CastBitsToSInt] -> (() => new CastBitsToSInt),
    classOf[CastBoolToBits] -> (() => new CastBoolToBits))
  private val resizeConstructors: Map[Class[_], () => Resize] = Map(
    classOf[ResizeUInt] -> (() => new ResizeUInt), classOf[ResizeSInt] -> (() => new ResizeSInt),
    classOf[ResizeBits] -> (() => new ResizeBits))
  private val accessConstructors: Set[Class[_]] = Set(
    classOf[UIntBitAccessFixed], classOf[SIntBitAccessFixed], classOf[BitsBitAccessFixed])

  private def widthOf(value: BaseType): ElaborationIntegerExpression =
    ParameterizedWidth.expressionOf(value)
      .getOrElse(ElabInt.literal(value.getBitsWidth).expression)

  private def same[A <: AnyRef](left: Vector[A], right: Vector[A]): Boolean =
    left.size == right.size && left.zip(right).forall { case (a, b) => a eq b }

  private def sameWidthIdentity(left: Option[ElaborationIntegerExpression],
      right: Option[ElaborationIntegerExpression]): Boolean = (left, right) match {
    case (None, None) => true
    case (Some(a), Some(b)) => a eq b
    case _ => false
  }

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
        !ElaborationWidthAuthority.equivalent(widthOf(value), width))
      fail("REPLAY-OPERAND-SHAPE", "operand must retain the exact owner, type and typed width authority")
  }

  private type Width = ElaborationIntegerExpression

  /** One closed native node; widths are substituted by operand identity, never
    * recovered from default widths or from the name of a Scala callback. */
  private final case class Node(kind: AnyRef, key: Any, operands: Set[Int],
      width: (Width, Width) => Width,
      emit: (BaseType, BaseType, Width, Width) => BaseType)

  final class Proof private[TypedBalancedReductionOperatorReplay] (
      val operatorClass: Class[_],
      val nativeResult: BaseType,
      val resultWidth: Width,
      private val owner: Component,
      private val kind: AnyRef,
      private val minimum: Option[Boolean],
      private val inputs: Vector[Evidence],
      private val graph: Node,
      private val guards: Vector[() => Unit]
  ) {
    val operationKey: (Class[_], Option[Boolean]) = (operatorClass, minimum)
    val transferKey: Any = graph.key
    def validateFreshness(): Unit = guards.foreach(_.apply())

    def resultWidthFor(left: Width, right: Width): Width = {
      validateFreshness()
      Vector(left, right).foreach { width =>
        ElaborationWidthAuthority.requireAuthoritative(width, "substituted balanced operand width",
          "MORPH-REDUCE-BALANCED-REPLAY-WIDTH-AUTHORITY")
        if (width.minimum < 1)
          fail("REPLAY-BODY-WIDTH", "substituted native operand widths must remain positive")
      }
      graph.width(left, right)
    }

    /** Exact-input API retains the old foreign-root rejection contract. */
    def replay(left: BaseType, right: BaseType): BaseType = {
      checkOperand(left, owner, kind, inputs(0).width)
      checkOperand(right, owner, kind, inputs(1).width)
      replayWithWidths(left, right, inputs(0).width, inputs(1).width)
    }

    /** Shape-polymorphic substitution is explicit: each new scalar carries the
      * authoritative width supplied by its earlier native transfer proof. */
    def replayWithWidths(left: BaseType, right: BaseType,
        leftWidth: Width, rightWidth: Width): BaseType = {
      validateFreshness()
      if (Component.current ne owner)
        fail("REPLAY-OWNER", "native graph replay must remain in its exact owning component")
      checkOperand(left, owner, kind, leftWidth)
      checkOperand(right, owner, kind, rightWidth)
      graph.emit(left, right, leftWidth, rightWidth)
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
    inputEvidence.foreach { evidence =>
      ElaborationWidthAuthority.requireAuthoritative(evidence.width, "balanced operator width",
        "MORPH-REDUCE-BALANCED-REPLAY-WIDTH-AUTHORITY")
      if (evidence.width.minimum < 1)
        fail("REPLAY-BODY-WIDTH", "operator widths must remain positive over their complete domains")
      if ((evidence.owner ne owner) || (evidence.kind ne kind))
        fail("REPLAY-OPERAND-SHAPE", "certified operands must share their exact native owner and scalar type")
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

    val firstWidth = inputEvidence(0).width
    val secondWidth = inputEvidence(1).width
    val cache = new IdentityHashMap[Expression, Node]()
    val one = ElabInt.literal(1).expression

    def attach(value: BaseType, width: Width): BaseType = {
      value match {
        case bits: BitVector => ParameterizedWidth.attach(bits, ElabInt.fromExpression(width).bits)
        case _ =>
      }
      value
    }
    def wrap(expression: Expression, width: Width): BaseType = {
      val value: BaseType = expression.getTypeObject match {
        case TypeBool => Bool()
        case TypeBits => Bits()
        case TypeUInt => UInt()
        case TypeSInt => SInt()
        case _ => fail("REPLAY-BODY-TYPE", "unsupported native scalar result")
      }
      value.assignFrom(expression)
      attach(value, width)
    }
    /** Materialize the admitted native primitive's own normalization before
      * capture loses its target width. Native resize keeps UInt zero extension
      * and SInt sign extension; full-product and concatenation inputs bypass
      * this max-width rule and retain their unequal native operand shapes. */
    def align(value: BaseType, target: Width): BaseType = {
      if (ElaborationWidthAuthority.equivalent(widthOf(value), target)) value
      else value match {
        case bits: BitVector => bits.resize(ElabInt.fromExpression(target))
        case _ => fail("REPLAY-BODY-TYPE", "a Bool operand cannot acquire a packed resize")
      }
    }

    def maximum(left: Width, right: Width): Width =
      ElaborationWidthAuthority.maximum(left, right)
    def sum(left: Width, right: Width): Width =
      ElaborationWidthAuthority.add(left, right)

    def compile(expression: Expression): Node = {
      val prior = cache.get(expression)
      if (prior != null) return prior
      val node: Node = expression match {
        case leaf: BaseType if operands.exists(_ eq leaf) =>
          val index = operands.indexWhere(_ eq leaf)
          Node(kind, ("input", index), Set(index),
            (a, b) => if (index == 0) a else b,
            (a, b, _, _) => if (index == 0) a else b)
        case leaf: BaseType =>
          if (!seen.containsKey(leaf))
            fail("REPLAY-EXTERNAL-READ", "operator body reads a signal outside its operands and local declarations")
          if (visiting.put(leaf, java.lang.Boolean.TRUE) != null)
            fail("REPLAY-BODY-CYCLE", "operator aliases contain a cycle")
          if (leaf.hasTag(tagAutoResize))
            fail("REPLAY-FIXED-WIDTH", "a context-sized native value requires target-specific resize authority")
          if ((leaf.component ne owner) || leaf.isReg || !leaf.isDirectionLess || leaf.isAnalog ||
              (leaf.parentScope ne owner.dslBody))
            fail("REPLAY-BODY-STATE", "operator locals must be root-scope combinational declarations")
          val all = assignmentsOf(owner, leaf)
          if (all.size != 1 || !recordedAssignments.exists(_ eq all.head))
            fail("REPLAY-BODY-DRIVER", "each local requires exactly its captured full-object driver")
          val assignment = all.head match {
            case data: DataAssignmentStatement if (data.target eq leaf) && (data.parentScope eq owner.dslBody) => data
            case _ => fail("REPLAY-BODY-DRIVER", "state, partial or conditional assignments cannot replay as an operator")
          }
          val source = assignment.source
          val sourceNode = compile(source)
          val localWidth = sourceNode.width(firstWidth, secondWidth)
          val localKind = leaf.getTypeObject.asInstanceOf[AnyRef]
          if (localKind ne sourceNode.kind)
            fail("REPLAY-BODY-TYPE", "local declaration changes its native driver's type")
          val fixed = leaf match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
          val retained = ParameterizedWidth.expressionOf(leaf)
          if ((fixed >= 0 && BigInt(fixed) != localWidth.default) ||
              retained.exists(w => !ElaborationWidthAuthority.equivalent(w, localWidth)) ||
              (localWidth.parameters.nonEmpty && fixed >= 0 && retained.isEmpty))
            fail("REPLAY-FIXED-WIDTH", "local width must preserve its complete native transfer, not freeze or truncate a witness")
          val parent = leaf.parentScope
          guards += (() => {
            val nowFixed = leaf match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
            if ((leaf.component ne owner) || leaf.isReg || !leaf.isDirectionLess || leaf.isAnalog ||
                (leaf.parentScope ne parent) || leaf.hasTag(tagAutoResize) ||
                (leaf.getTypeObject.asInstanceOf[AnyRef] ne localKind) ||
                !TypedBalancedReductionValueEvidence.preservesValueWidth(leaf, fixed, nowFixed, localWidth) ||
                !same(assignmentsOf(owner, leaf), all) ||
                (assignment.source ne source) || (assignment.target ne leaf) ||
                (assignment.parentScope ne owner.dslBody) ||
                !sameWidthIdentity(ParameterizedWidth.expressionOf(leaf), retained))
              fail("REPLAY-STALE-GRAPH", "captured native declaration or driver changed after body certification")
          })
          consumedDeclarations.put(leaf, java.lang.Boolean.TRUE)
          consumedAssignments.put(assignment, java.lang.Boolean.TRUE)
          visiting.remove(leaf)
          sourceNode
        case literal: BoolLiteral if literal.getClass == classOf[BoolLiteral] =>
          val value = literal.value
          guards += (() => if (literal.value != value) fail("REPLAY-STALE-GRAPH", "native literal changed"))
          Node(TypeBool, (classOf[BoolLiteral], value), Set.empty, (_, _) => one,
            (_, _, _, _) => wrap(new BoolLiteral(value), one))
        case cast: Cast if castConstructors.contains(cast.getClass) =>
          val input = cast.input
          val child = compile(input)
          val expectedInput: AnyRef = cast match {
            case _: CastUIntToBits => TypeUInt
            case _: CastSIntToBits => TypeSInt
            case _: CastBoolToBits => TypeBool
            case _ => TypeBits
          }
          if (child.kind ne expectedInput)
            fail("REPLAY-BODY-TYPE", "native cast input changed its exact scalar type")
          val nativeKind = cast.getTypeObject.asInstanceOf[AnyRef]
          guards += (() => if ((cast.input ne input) || (cast.getTypeObject.asInstanceOf[AnyRef] ne nativeKind))
            fail("REPLAY-STALE-GRAPH", "native cast changed"))
          Node(nativeKind, (cast.getClass, child.key), child.operands, child.width,
            (a, b, aw, bw) => {
              val fresh = castConstructors(cast.getClass)()
              fresh.input = child.emit(a, b, aw, bw).asInstanceOf[fresh.T]
              wrap(fresh, child.width(aw, bw))
            })
        case binary: BinaryOperator if constructors.contains(binary.getClass) ||
            binary.getClass == classOf[Operator.Bits.Cat] || comparisonConstructors.contains(binary.getClass) =>
          val left = binary.left
          val right = binary.right
          val l = compile(left)
          val r = compile(right)
          val nativeKind = binary.getTypeObject.asInstanceOf[AnyRef]
          guards += (() => if ((binary.left ne left) || (binary.right ne right) ||
              (binary.getTypeObject.asInstanceOf[AnyRef] ne nativeKind))
            fail("REPLAY-STALE-GRAPH", "native primitive changed after certification"))
          val isComparison = comparisonConstructors.contains(binary.getClass)
          val expectedInput: AnyRef = if (binary.getClass == classOf[Operator.UInt.Smaller]) TypeUInt
            else if (binary.getClass == classOf[Operator.SInt.Smaller]) TypeSInt else nativeKind
          if ((l.kind ne expectedInput) || (r.kind ne expectedInput))
            fail("REPLAY-BODY-TYPE", "native primitive inputs changed their exact scalar type")
          val isSum = binary.getClass == classOf[Operator.Bits.Cat] ||
            binary.getClass == classOf[Operator.UInt.Mul] || binary.getClass == classOf[Operator.SInt.Mul]
          val width: (Width, Width) => Width = if (isComparison) (_, _) => one
            else if (isSum) (a, b) => sum(l.width(a, b), r.width(a, b))
            else (a, b) => maximum(l.width(a, b), r.width(a, b))
          val constructor = if (binary.getClass == classOf[Operator.Bits.Cat]) () => new Operator.Bits.Cat
            else if (isComparison) comparisonConstructors(binary.getClass) else constructors(binary.getClass)
          Node(nativeKind, (binary.getClass, l.key, r.key), l.operands ++ r.operands, width,
            (a, b, aw, bw) => {
              val fresh = constructor()
              val left = l.emit(a, b, aw, bw)
              val right = r.emit(a, b, aw, bw)
              val inputWidth = if (isSum) None else Some(maximum(l.width(aw, bw), r.width(aw, bw)))
              fresh.left = inputWidth.map(align(left, _)).getOrElse(left).asInstanceOf[fresh.T]
              fresh.right = inputWidth.map(align(right, _)).getOrElse(right).asInstanceOf[fresh.T]
              wrap(fresh, width(aw, bw))
            })
        case access: BitVectorBitAccessFixed if accessConstructors.contains(access.getClass) =>
          val source = access.source
          val bit = access.bitId
          val child = compile(source)
          val expectedInput: AnyRef = access match {
            case _: UIntBitAccessFixed => TypeUInt
            case _: SIntBitAccessFixed => TypeSInt
            case _ => TypeBits
          }
          if (child.kind ne expectedInput)
            fail("REPLAY-BODY-TYPE", "native bit selection changed its source scalar type")
          val isHigh = NativeWidthProvenance.isHighBit(access)
          if (!isHigh && (bit < 0 || BigInt(bit) >= child.width(firstWidth, secondWidth).minimum))
            fail("REPLAY-BIT-INDEX", "fixed bit selection is not valid across its complete width domain")
          guards += (() => if ((access.source ne source) || access.bitId != bit ||
              NativeWidthProvenance.isHighBit(access) != isHigh)
            fail("REPLAY-STALE-GRAPH", "native bit selection or symbolic high-bit authority changed"))
          Node(TypeBool, (access.getClass, child.key, if (isHigh) "msb" else bit), child.operands,
            (_, _) => one, (a, b, aw, bw) => {
              val value = child.emit(a, b, aw, bw).asInstanceOf[BitVector]
              if (isHigh) value.msb else value(bit)
            })
        case resize: Resize if resizeConstructors.contains(resize.getClass) =>
          val input = resize.input
          val size = resize.size
          val retained = ParameterizedWidth.resizeExpressionOf(resize)
          val target = retained.getOrElse(ElabInt.literal(size).expression)
          ElaborationWidthAuthority.requireAuthoritative(target, "balanced resize target",
            "MORPH-REDUCE-BALANCED-REPLAY-WIDTH-AUTHORITY")
          if (target.minimum < 1 || target.default != BigInt(size))
            fail("REPLAY-FIXED-WIDTH", "native resize target must retain positive exact authority")
          val child = compile(input)
          if (child.kind ne resize.getTypeObject.asInstanceOf[AnyRef])
            fail("REPLAY-BODY-TYPE", "native resize changed its source scalar type")
          guards += (() => if ((resize.input ne input) || resize.size != size ||
              !sameWidthIdentity(ParameterizedWidth.resizeExpressionOf(resize), retained))
            fail("REPLAY-STALE-GRAPH", "native resize or its target authority changed"))
          Node(resize.getTypeObject.asInstanceOf[AnyRef], (resize.getClass, child.key, target), child.operands,
            (_, _) => target, (a, b, aw, bw) => {
              val fresh = resizeConstructors(resize.getClass)()
              fresh.input = child.emit(a, b, aw, bw).asInstanceOf[Expression with WidthProvider]
              fresh.size = size
              ParameterizedWidth.attachResize(wrap(fresh, target).asInstanceOf[BitVector], ElabInt.fromExpression(target))
            })
        case mux: BinaryMultiplexer if
            mux.getClass == classOf[BinaryMultiplexerUInt] || mux.getClass == classOf[BinaryMultiplexerSInt] =>
          val cond = mux.cond
          val yes = mux.whenTrue
          val no = mux.whenFalse
          val condition = compile(cond)
          val whenTrue = compile(yes)
          val whenFalse = compile(no)
          val nativeKind = mux.getTypeObject.asInstanceOf[AnyRef]
          if ((condition.kind ne TypeBool) || (whenTrue.kind ne nativeKind) || (whenFalse.kind ne nativeKind))
            fail("REPLAY-BODY-TYPE", "native selector inputs changed their exact scalar types")
          guards += (() => if ((mux.cond ne cond) || (mux.whenTrue ne yes) || (mux.whenFalse ne no))
            fail("REPLAY-STALE-GRAPH", "native selector or its arms changed"))
          val width: (Width, Width) => Width = (a, b) => maximum(whenTrue.width(a, b), whenFalse.width(a, b))
          Node(nativeKind, (mux.getClass, condition.key, whenTrue.key, whenFalse.key),
            condition.operands ++ whenTrue.operands ++ whenFalse.operands, width,
            (a, b, aw, bw) => {
              val c = condition.emit(a, b, aw, bw).asInstanceOf[Bool]
              val target = width(aw, bw)
              val y = align(whenTrue.emit(a, b, aw, bw), target)
              val n = align(whenFalse.emit(a, b, aw, bw), target)
              wrap(y.newMultiplexer(c, y, n), target)
            })
        case _ => fail("REPLAY-NONASSOCIATIVE-OR-UNSUPPORTED", "body contains a native node outside the certified scalar profile")
      }
      cache.put(expression, node)
      node
    }

    def root(expression: Expression, path: Vector[BaseType] = Vector.empty): Expression = expression match {
      case value: BaseType if !operands.exists(_ eq value) =>
        if (value.isReg) fail("REPLAY-BODY-STATE", "an operator result cannot contain native register state")
        val all = assignmentsOf(owner, value)
        if (!seen.containsKey(value)) fail("REPLAY-EXTERNAL-READ", "native root reads external data")
        if (all.size != 1 || !recordedAssignments.exists(_ eq all.head))
          fail("REPLAY-BODY-DRIVER", "native root needs its exact one captured assignment")
        if (path.exists(_ eq value)) fail("REPLAY-BODY-CYCLE", "operator aliases contain a cycle")
        root(all.head.source, path :+ value)
      case other => other
    }
    val native = root(result)
    val minimum = native match {
      case mux: BinaryMultiplexer =>
        val comparison = root(mux.cond) match {
          case binary: BinaryOperator if
              ((kind eq TypeUInt) && binary.getClass == classOf[Operator.UInt.Smaller]) ||
              ((kind eq TypeSInt) && binary.getClass == classOf[Operator.SInt.Smaller]) => binary
          case _ => fail("REPLAY-MINMAX-COMPARISON", "min/max requires the exact same-signedness native less-than comparator")
        }
        def order(a: Expression, b: Expression): Boolean = {
          val l = compile(a); val r = compile(b)
          if (l.key == (("input", 0)) && r.key == (("input", 1))) false
          else if (l.key == (("input", 1)) && r.key == (("input", 0))) true
          else fail("REPLAY-BODY-OPERANDS", "min/max must compare and select both exact operands")
        }
        Some(order(comparison.left, comparison.right) == order(mux.whenTrue, mux.whenFalse))
      case binary: BinaryOperator if constructors.contains(binary.getClass) =>
        val left = compile(binary.left).operands
        val right = compile(binary.right).operands
        if (!((left == Set(0) && right == Set(1)) || (left == Set(1) && right == Set(0))))
          fail("REPLAY-BODY-OPERANDS", "native binary graph must retain both distinct operand identities")
        None
      case _: Resize => None
      case _ => fail("REPLAY-NONASSOCIATIVE-OR-UNSUPPORTED", "root is not a certified scalar operator")
    }
    val graph = compile(result)
    if ((graph.kind ne kind) || graph.operands != Set(0, 1))
      fail("REPLAY-BODY-OPERANDS", "operator graph must preserve native scalar kind and both input identities")
    if (consumedDeclarations.size != declarations.size || consumedAssignments.size != recordedAssignments.size)
      fail("REPLAY-UNCONSUMED-EFFECT", "callback contains declarations or assignments outside its closed result graph: " +
        declarations.filterNot(consumedDeclarations.containsKey).map(value =>
          value.getClass.getSimpleName + ":type=" + value.isTypeNode + ":drivers=" + assignmentsOf(owner, value).map(_.source.getClass.getSimpleName).mkString(",")).mkString(";"))
    val resultWidth = graph.width(firstWidth, secondWidth)
    new Proof(native.getClass, result, resultWidth, owner, kind, minimum, inputEvidence, graph, guards.toVector)
  }
}
