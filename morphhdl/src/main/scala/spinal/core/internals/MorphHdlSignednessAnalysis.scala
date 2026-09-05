package spinal.core.internals

import java.util.IdentityHashMap
import scala.collection.mutable.ArrayBuffer
import morphhdl.analysis.SignednessFacts
import morphhdl.analysis.SignednessFacts.{Cast => CastRule, Resize => ResizeRule, Literal => LiteralRule, Mux => MuxRule, _}
import spinal.core._

final class MorphHdlSignednessException(val code: String, detail: String)
    extends IllegalArgumentException(s"[$code] $detail")

/** Read-only, generation-scoped analysis of exact native objects. No emitter
  * calls this automatically in 60b and no method grants cast-elision permission.
  */
object MorphHdlSignednessAnalysis {
  private def reject(code: String, detail: String): Nothing =
    throw new MorphHdlSignednessException("MORPH-SIGNEDNESS-" + code, detail)

  sealed trait Use
  case object DeclarationUse extends Use
  case object TemporaryUse extends Use
  case object MemoryElementUse extends Use
  case object AggregateUse extends Use
  case object ExpressionUse extends Use
  case object CastOperandUse extends Use

  /** Not a case class: no public constructor or copy operation. */
  final class Evidence private[MorphHdlSignednessAnalysis] (
      private[MorphHdlSignednessAnalysis] val owner: Snapshot,
      private[MorphHdlSignednessAnalysis] val subject: AnyRef,
      val use: Use,
      private[MorphHdlSignednessAnalysis] val parent: Expression,
      private[MorphHdlSignednessAnalysis] val slot: Int
  )

  private final case class Shape(
      kind: Kind, rule: Rule, nativeBits: Int,
      children: Vector[AnyRef], parameters: Vector[ElaborationIntegerExpression],
      details: Vector[Any], owners: Vector[AnyRef]
  )
  private final case class Entry(subject: AnyRef, shape: Shape, fact: Fact)

  final class Snapshot private[MorphHdlSignednessAnalysis] (
      private val entries: Vector[Entry],
      private val indices: IdentityHashMap[AnyRef, java.lang.Integer],
      private val widths: Vector[ElaborationIntegerExpression]
  ) {
    /** Immutable observations for inspection/replay; these are not use evidence. */
    val facts: Vector[Fact] = entries.map(_.fact)
    def replay: String = facts.mkString("\n") + "\n"

    private def entry(subject: AnyRef): Entry = {
      if (subject == null) reject("NULL-SUBJECT", "a use needs an exact non-null graph object")
      val index = indices.get(subject)
      if (index == null) reject("FOREIGN-SUBJECT", "object is not in this analysis snapshot")
      entries(index.intValue)
    }

    private def fresh(subject: AnyRef, seen: IdentityHashMap[AnyRef, java.lang.Boolean]): Unit = {
      if (seen.put(subject, java.lang.Boolean.TRUE) != null) return
      val saved = entry(subject).shape
      val live = describe(subject)
      def sameIdentity[A <: AnyRef](a: Vector[A], b: Vector[A]): Boolean =
        a.size == b.size && a.zip(b).forall { case (x, y) => x eq y }
      if (saved.kind != live.kind || saved.rule != live.rule ||
          saved.nativeBits != live.nativeBits || saved.details != live.details ||
          !sameIdentity(saved.children, live.children) ||
          !sameIdentity(saved.parameters, live.parameters) ||
          !sameIdentity(saved.owners, live.owners))
        reject("STALE-EVIDENCE", "graph type, width, operand, ownership or boundary changed after capture")
      live.parameters.foreach(validateWidth)
      saved.children.foreach(child => fresh(child, seen))
    }

    private def check(subject: AnyRef): Entry = {
      fresh(subject, new IdentityHashMap[AnyRef, java.lang.Boolean]())
      entry(subject)
    }

    def expression(subject: Expression): Evidence = {
      check(subject)
      new Evidence(this, subject, ExpressionUse, null, -1)
    }
    def declaration(subject: BaseType): Evidence = {
      check(subject)
      new Evidence(this, subject, DeclarationUse, null, -1)
    }
    def temporary(subject: Expression): Evidence = {
      check(subject)
      new Evidence(this, subject, TemporaryUse, null, -1)
    }
    def memoryElement(subject: Mem[_]): Evidence = {
      check(subject)
      new Evidence(this, subject, MemoryElementUse, null, -1)
    }
    def aggregate(subject: MultiData): Evidence = {
      check(subject)
      new Evidence(this, subject, AggregateUse, null, -1)
    }
    def castOperand(parent: Expression, slot: Int): Evidence = {
      check(parent)
      val operands = expressionChildren(parent)
      if (slot < 0 || slot >= operands.size)
        reject("OPERAND-SLOT", "cast operand index is outside the exact expression")
      new Evidence(this, operands(slot), CastOperandUse, parent, slot)
    }

    /** Validate identity, session, use role and the entire dependency subtree.
      * A Fact returned here still needs its listed target/width obligations.
      */
    def validate(subject: AnyRef, evidence: Evidence, use: Use): Fact = {
      if (evidence == null || (evidence.owner ne this))
        reject("FOREIGN-EVIDENCE", "evidence belongs to another analysis session")
      if ((evidence.subject ne subject) || evidence.use != use)
        reject("USE-IDENTITY", "evidence is not for this exact object and use role")
      if (use == CastOperandUse) {
        check(evidence.parent)
        val operands = expressionChildren(evidence.parent)
        if (evidence.slot < 0 || evidence.slot >= operands.size ||
            (operands(evidence.slot) ne subject))
          reject("OPERAND-IDENTITY", "cast use no longer denotes the captured operand edge")
      }
      val fact = check(subject).fact
      if (use == TemporaryUse) fact.copy(value = fact.intent,
        requirements = (fact.requirements :+ TargetDeclarationMode).distinct) else fact
    }

    def validateCastOperand(parent: Expression, slot: Int, evidence: Evidence): Fact = {
      if (evidence == null || (evidence.parent ne parent) || evidence.slot != slot)
        reject("OPERAND-IDENTITY", "cast decision must use its exact parent and operand slot")
      check(parent)
      val operands = expressionChildren(parent)
      if (slot < 0 || slot >= operands.size) reject("OPERAND-SLOT", "invalid cast operand slot")
      validate(operands(slot), evidence, CastOperandUse)
    }

    def requireKnown(subject: AnyRef, evidence: Evidence, use: Use): Fact = {
      val fact = validate(subject, evidence, use)
      if (fact.value == Unknown || !resolved(fact.width) || fact.nativeBits <= 0)
        reject("UNKNOWN-FACT", "unclassified signedness or width cannot authorize an emission decision")
      fact
    }

    def widthSource(subject: AnyRef, evidence: Evidence, use: Use, key: Int): ElaborationIntegerExpression = {
      val fact = validate(subject, evidence, use)
      def contains(width: Width): Boolean = width match {
        case Retained(value) => value == key
        case Sum(parts) => parts.exists(contains)
        case Product(parts) => parts.exists(contains)
        case Maximum(parts) => parts.exists(contains)
        case Minimum(parts) => parts.exists(contains)
        case Difference(left, right) => contains(left) || contains(right)
        case _ => false
      }
      if (key < 0 || key >= widths.size || !contains(fact.width))
        reject("WIDTH-USE-IDENTITY", "width token does not belong to this validated use")
      val result = widths(key)
      validateWidth(result)
      result
    }

    /** An exact width object is available only through validated subject use;
      * callers cannot substitute a same-valued or case-class-copied expression.
      */
    def retainedWidths(subject: AnyRef, evidence: Evidence, use: Use): Vector[ElaborationIntegerExpression] = {
      validate(subject, evidence, use)
      entry(subject).shape.parameters
    }
  }

  /** Unit/compiler utility for exact expression roots, not a rendered-HDL parser. */
  def expressions(roots: Vector[Expression]): Snapshot = {
    if (roots == null || roots.exists(_ == null)) reject("NULL-SUBJECT", "expression roots must not contain null")
    build(roots.map(_.asInstanceOf[AnyRef]))
  }

  /** Capture all declarations, memory elements, aggregate ancestors and all
    * expression occurrences in deterministic native statement/hierarchy order.
    */
  def capture(top: Component): Snapshot = {
    if (top == null) reject("NULL-TOP", "capture needs an elaborated component")
    val roots = ArrayBuffer.empty[AnyRef]
    val aggregates = new IdentityHashMap[MultiData, java.lang.Boolean]()
    def parents(data: Data): Unit = data.parent match {
      case aggregate: MultiData if aggregates.put(aggregate, java.lang.Boolean.TRUE) == null =>
        roots += aggregate
        parents(aggregate)
      case _ => ()
    }
    def component(value: Component): Unit = {
      value.dslBody.walkStatements {
        case memory: Mem[_] => roots += memory
        case base: BaseType => roots += base; parents(base)
        case expression: Expression => roots += expression
        case statement => statement.foreachExpression(expression => if (expression != null) roots += expression)
      }
      value.children.foreach(component)
    }
    component(top)
    build(roots.toVector)
  }

  /** The observer runs immediately before the untouched Verilog emitter,
    * after inherited validation, normalization and name allocation.
    */
  def install(observer: Snapshot => Unit)(phases: ArrayBuffer[Phase]): Unit = {
    if (observer == null || phases == null || phases.exists(_ == null))
      reject("PHASE-PLAN", "analysis requires a non-null observer and native phase plan")
    val emissions = phases.zipWithIndex.collect { case (_: PhaseVerilog, i) => i }
    val checks = phases.zipWithIndex.collect { case (_: PhaseCheckCrossClock, i) => i }
    if (emissions.size != 1 || checks.size != 1 || checks.head >= emissions.head)
      reject("PHASE-PLAN", "analysis requires one validated pre-Verilog publication boundary")
    phases.insert(emissions.head, new PhaseMisc {
      override def impl(pc: PhaseContext): Unit = observer(capture(pc.topLevel))
    })
  }

  private def resolved(width: Width): Boolean = width match {
    case UnknownWidth => false
    case Sum(parts) => parts.nonEmpty && parts.forall(resolved)
    case Product(parts) => parts.nonEmpty && parts.forall(resolved)
    case Maximum(parts) => parts.nonEmpty && parts.forall(resolved)
    case Minimum(parts) => parts.nonEmpty && parts.forall(resolved)
    case Difference(left, right) => resolved(left) && resolved(right)
    case _ => true
  }

  private def validateWidth(value: ElaborationIntegerExpression): Unit = {
    ElabInt.requireAuthoritativeIntegerDomain(value, "signedness width authority",
      "MORPH-SIGNEDNESS-WIDTH-AUTHORITY", requireExactExtrema = false)
    if (value.minimum < 0) reject("WIDTH-AUTHORITY", "negative logical width domain")
  }

  private def expressionChildren(expression: Expression): Vector[Expression] = expression match {
    case _: BaseType => Vector.empty // A reference is a terminal, not its driver.
    case _ =>
      val result = ArrayBuffer.empty[Expression]
      expression.foreachExpression { child =>
        if (child == null) reject("NULL-OPERAND", "a graph expression contains an uninitialized operand")
        result += child
      }
      result.toVector
  }

  private def scalarIntent(base: BaseType): Kind = base match {
    case _: SInt if base.getTypeObject == TypeSInt => SignedScalar
    case _: UInt if base.getTypeObject == TypeUInt => UnsignedScalar
    case _: Bits if base.getTypeObject == TypeBits => UnsignedScalar
    case _: Bool if base.getTypeObject == TypeBool => BooleanValue
    case _ => Unknown
  }

  private def typeIntent(value: Any): Kind = value match {
    case TypeSInt => SignedScalar
    case TypeUInt | TypeBits => UnsignedScalar
    case TypeBool => BooleanValue
    case _ => Unknown
  }

  // Runtime class identity, never a class-name or emitted-name recognizer.
  // Unknown downstream operators cannot inherit authority merely by claiming TypeSInt.
  private val reviewedExpressions: Set[Class[_]] = Set(
    classOf[SIntLiteral], classOf[UIntLiteral], classOf[BitsLiteral],
    classOf[BoolLiteral], classOf[ResizeSInt], classOf[ResizeUInt],
    classOf[ResizeBits], classOf[CastBitsToSInt], classOf[CastUIntToSInt],
    classOf[CastSIntToBits], classOf[CastUIntToBits], classOf[CastBitsToUInt],
    classOf[CastSIntToUInt], classOf[CastBoolToBits], classOf[MemReadSync],
    classOf[MemReadAsync], classOf[MemReadWrite], classOf[MemReadAsyncWrite],
    classOf[Operator.SInt.Not], classOf[Operator.SInt.And], classOf[Operator.SInt.Or],
    classOf[Operator.SInt.Xor], classOf[Operator.SInt.Equal], classOf[Operator.SInt.EqualSim],
    classOf[Operator.SInt.NotEqual], classOf[Operator.SInt.Repeat], classOf[Operator.SInt.ShiftLeftByInt],
    classOf[Operator.SInt.ShiftRightByInt], classOf[Operator.SInt.ShiftLeftByUInt], classOf[Operator.SInt.ShiftRightByUInt],
    classOf[Operator.SInt.ShiftLeftByIntFixedWidth], classOf[Operator.SInt.ShiftRightByIntFixedWidth], classOf[Operator.SInt.ShiftLeftByUIntFixedWidth],
    classOf[Operator.SInt.Add], classOf[Operator.SInt.Sub], classOf[Operator.SInt.Mul],
    classOf[Operator.SInt.Div], classOf[Operator.SInt.Mod], classOf[Operator.SInt.Smaller],
    classOf[Operator.SInt.SmallerOrEqual], classOf[Operator.SInt.Minus], classOf[SIntBitAccessFixed],
    classOf[SIntBitAccessFloating], classOf[SIntRangedAccessFixed], classOf[SIntRangedAccessFloating],
    classOf[Operator.UInt.Not], classOf[Operator.UInt.And], classOf[Operator.UInt.Or],
    classOf[Operator.UInt.Xor], classOf[Operator.UInt.Equal], classOf[Operator.UInt.EqualSim],
    classOf[Operator.UInt.NotEqual], classOf[Operator.UInt.Repeat], classOf[Operator.UInt.ShiftLeftByInt],
    classOf[Operator.UInt.ShiftRightByInt], classOf[Operator.UInt.ShiftLeftByUInt], classOf[Operator.UInt.ShiftRightByUInt],
    classOf[Operator.UInt.ShiftLeftByIntFixedWidth], classOf[Operator.UInt.ShiftRightByIntFixedWidth], classOf[Operator.UInt.ShiftLeftByUIntFixedWidth],
    classOf[Operator.UInt.Add], classOf[Operator.UInt.Sub], classOf[Operator.UInt.Mul],
    classOf[Operator.UInt.Div], classOf[Operator.UInt.Mod], classOf[Operator.UInt.Smaller],
    classOf[Operator.UInt.SmallerOrEqual], classOf[UIntBitAccessFixed], classOf[UIntBitAccessFloating],
    classOf[UIntRangedAccessFixed], classOf[UIntRangedAccessFloating], classOf[Operator.Bits.Not],
    classOf[Operator.Bits.And], classOf[Operator.Bits.Or], classOf[Operator.Bits.Xor],
    classOf[Operator.Bits.Equal], classOf[Operator.Bits.EqualSim], classOf[Operator.Bits.NotEqual],
    classOf[Operator.Bits.Repeat], classOf[Operator.Bits.ShiftLeftByInt], classOf[Operator.Bits.ShiftRightByInt],
    classOf[Operator.Bits.ShiftLeftByUInt], classOf[Operator.Bits.ShiftRightByUInt], classOf[Operator.Bits.ShiftLeftByIntFixedWidth],
    classOf[Operator.Bits.ShiftRightByIntFixedWidth], classOf[Operator.Bits.ShiftLeftByUIntFixedWidth], classOf[BitsBitAccessFixed],
    classOf[BitsBitAccessFloating], classOf[BitsRangedAccessFixed], classOf[BitsRangedAccessFloating],
    classOf[Operator.Bits.Cat], classOf[Operator.Bool.Not], classOf[Operator.Bool.And],
    classOf[Operator.Bool.Or], classOf[Operator.Bool.Xor], classOf[Operator.Bool.Equal],
    classOf[Operator.Bool.EqualSim], classOf[Operator.Bool.NotEqual], classOf[Operator.Bool.Repeat],
    classOf[Operator.BitVector.orR], classOf[Operator.BitVector.andR], classOf[Operator.BitVector.xorR],
    classOf[MultiplexerSInt], classOf[MultiplexerUInt], classOf[MultiplexerBits],
    classOf[MultiplexerBool], classOf[BinaryMultiplexerSInt], classOf[BinaryMultiplexerUInt],
    classOf[BinaryMultiplexerBits], classOf[BinaryMultiplexerBool]
  )

  private def primitiveRule(expression: Expression): Rule = expression match {
    case _: BaseType => Reference
    case other if !reviewedExpressions.contains(other.getClass) => Unsupported
    case _: SIntLiteral | _: UIntLiteral | _: BitsLiteral | _: BoolLiteral => LiteralRule
    case _: Operator.SInt.Minus | _: Operator.SInt.Not | _: Operator.UInt.Not | _: Operator.Bits.Not => Unary
    case _: Operator.BitVector.Add | _: Operator.BitVector.Sub | _: Operator.BitVector.Mul |
         _: Operator.BitVector.Div | _: Operator.BitVector.Mod | _: Operator.BitVector.And |
         _: Operator.BitVector.Or | _: Operator.BitVector.Xor => Arithmetic
    case _: Operator.BitVector.Equal | _: Operator.BitVector.NotEqual | _: Operator.BitVector.EqualSim |
         _: Operator.SInt.Smaller | _: Operator.SInt.SmallerOrEqual |
         _: Operator.UInt.Smaller | _: Operator.UInt.SmallerOrEqual => Comparison
    case _: Operator.Bool.Not | _: Operator.Bool.And | _: Operator.Bool.Or | _: Operator.Bool.Xor |
         _: Operator.Bool.Equal | _: Operator.Bool.NotEqual | _: Operator.Bool.EqualSim |
         _: Operator.BitVector.orR | _: Operator.BitVector.andR | _: Operator.BitVector.xorR => Logical
    case _: Operator.BitVector.ShiftOperator => Shift
    case _: BinaryMultiplexer | _: Multiplexer => MuxRule
    case _: CastBitsToSInt | _: CastUIntToSInt | _: CastSIntToBits | _: CastUIntToBits |
         _: CastBitsToUInt | _: CastSIntToUInt | _: CastBoolToBits => CastRule
    case _: spinal.core.internals.Resize => ResizeRule
    case _: Operator.Bits.Cat => Concatenation
    case _: Operator.BitVector.Repeat | _: Operator.Bool.Repeat => Replication
    case _: SubAccess => Selection
    case _: MemReadSync | _: MemReadAsync | _: MemReadWrite | _: MemReadAsyncWrite => MemoryRead
    case _ => Unsupported
  }

  private def describe(subject: AnyRef): Shape = subject match {
    case memory: Mem[_] =>
      val leaves = memory.wordTypeLeaves
      val scalar = leaves.size == 1 && !leaves.head.parent.isInstanceOf[MultiData]
      val kind = if (scalar) scalarIntent(leaves.head) else UnsignedAggregate
      val metadata = ParameterizedMemory.metadataOf(memory)
      Shape(kind, MemoryElement, memory.getWidth, leaves.map(_.asInstanceOf[AnyRef]),
        metadata.toVector.flatMap(m => Vector(m.depth, m.elementWidth)),
        Vector(memory.wordCount), Vector(memory.parentScope))
    case aggregate: MultiData =>
      val shape = aggregate match { case vec: Vec[_] => ParameterizedVec.shapeOf(vec); case _ => None }
      Shape(UnsignedAggregate, Aggregate, aggregate.getBitsWidth,
        aggregate.flatten.toVector.map(_.asInstanceOf[AnyRef]),
        shape.toVector.flatMap(s => Vector(s.depth) ++ s.elementLeaves.map(_.width)),
        Vector.empty, Vector(aggregate.parent))
    case expression: Expression =>
      val rule = primitiveRule(expression)
      // An arbitrary expression claiming TypeSInt is not a reviewed operator.
      val intent = expression match {
        case base: BaseType => scalarIntent(base)
        case _ if rule == Unsupported => Unknown
        case _ => typeIntent(expression.getTypeObject)
      }
      val nativeBits = expression match {
        case vector: BitVector if vector.isFixedWidth => vector.fixedWidth
        case width: WidthProvider => width.getWidth
        case _ if expression.getTypeObject == TypeBool => 1
        case _ => -1
      }
      val parameters = expression match {
        case bits: Bits if ParameterizedVec.packedShapeOf(bits).nonEmpty =>
          val s = ParameterizedVec.packedShapeOf(bits).get
          Vector(s.depth) ++ s.elementLeaves.map(_.width)
        case base: BaseType => ParameterizedWidth.expressionOf(base).toVector
        case resize: spinal.core.internals.Resize => ParameterizedWidth.resizeExpressionOf(resize).toVector
        case _ => Vector.empty
      }
      val details: Vector[Any] = expression match {
        case vector: BitVector => Vector(vector.getDirection, vector.isReg, vector.isAnalog, vector.isSuffix, vector.fixedWidth)
        case base: BaseType => Vector(base.getDirection, base.isReg, base.isAnalog, base.isSuffix)
        case literal: BitVectorLiteral => Vector(literal.value, literal.poisonMask, literal.hasSpecifiedBitCount)
        case literal: BoolLiteral => Vector(literal.value)
        case op: Operator.BitVector.ShiftRightByInt => Vector(op.shift)
        case op: Operator.BitVector.ShiftLeftByInt => Vector(op.shift)
        case op: Operator.BitVector.ShiftRightByIntFixedWidth => Vector(op.shift)
        case op: Operator.BitVector.ShiftLeftByIntFixedWidth => Vector(op.shift)
        case op: Operator.BitVector.Repeat => Vector(op.count)
        case op: Operator.Bool.Repeat => Vector(op.count)
        case access: BitVectorRangedAccessFixed => Vector(access.hi, access.lo)
        case access: BitVectorRangedAccessFloating => Vector(access.size)
        case access: BitVectorBitAccessFixed => Vector(access.bitId)
        case resize: spinal.core.internals.Resize => Vector(resize.size)
        case port: MemPortStatement => Vector(port.hasTag(AllowMixedWidth))
        case _ => Vector.empty
      }
      val owners = expression match {
        case base: BaseType => Vector(base.component, base.parentScope, base.parent, base.component.parent)
        case port: MemPortStatement => Vector(port.mem, port.parentScope)
        case _ => Vector.empty
      }
      val memory = expression match { case port: MemPortStatement => Vector(port.mem); case _ => Vector.empty }
      val packed = expression.isInstanceOf[Bits] && parameters.size > 1
      Shape(if (packed) UnsignedAggregate else intent, if (packed) Aggregate else rule,
        nativeBits, expressionChildren(expression).map(_.asInstanceOf[AnyRef]) ++ memory,
        parameters, details, owners :+ expression.getTypeObject.asInstanceOf[AnyRef])
    case _ => reject("UNSUPPORTED-SUBJECT", "only exact native declarations, aggregates and expressions are admitted")
  }

  private def build(roots: Vector[AnyRef]): Snapshot = {
    val indices = new IdentityHashMap[AnyRef, java.lang.Integer]()
    val entries = ArrayBuffer.empty[Entry]
    val widthIndices = new IdentityHashMap[ElaborationIntegerExpression, java.lang.Integer]()
    val widths = ArrayBuffer.empty[ElaborationIntegerExpression]
    def retained(value: ElaborationIntegerExpression): Width = {
      validateWidth(value)
      if (value.parameters.isEmpty) return Fixed(value.default)
      var id = widthIndices.get(value)
      if (id == null) {
        id = java.lang.Integer.valueOf(widths.size)
        widthIndices.put(value, id)
        widths += value
      }
      Retained(id.intValue)
    }
    def visit(subject: AnyRef): Fact = {
      val existing = indices.get(subject)
      if (existing != null) {
        val entry = entries(existing.intValue)
        if (entry == null) reject("EXPRESSION-CYCLE", "non-reference expression dependency cycle")
        return entry.fact
      }
      val id = entries.size
      indices.put(subject, java.lang.Integer.valueOf(id))
      entries += null
      val shape = describe(subject)
      shape.parameters.foreach(validateWidth)
      val children = shape.children.map(visit)
      val valueChildren = subject match {
        case mux: BinaryMultiplexer => Vector(visit(mux.whenTrue), visit(mux.whenFalse))
        case mux: Multiplexer => mux.inputs.toVector.map(visit)
        case _: MemPortStatement => Vector.empty[Fact]
        case _ => children
      }
      val baseWidth: Width = if (shape.nativeBits >= 0) Fixed(shape.nativeBits) else UnknownWidth
      val widthsOfChildren = valueChildren.map(_.width)
      def leftWidth: Width = widthsOfChildren.headOption.getOrElse(UnknownWidth)
      val width: Width = if (shape.rule == Unsupported) UnknownWidth else subject match {
        case _: MultiData | _: Bits if shape.rule == Aggregate && shape.parameters.nonEmpty =>
          Product(Vector(retained(shape.parameters.head), Sum(shape.parameters.tail.map(retained))))
        case _: MultiData => Sum(children.map(_.width))
        case _: Mem[_] =>
          if (shape.parameters.size == 2) retained(shape.parameters(1)) else Sum(children.map(_.width))
        case _: BaseType => shape.parameters.headOption.map(retained).getOrElse(baseWidth)
        case resize: spinal.core.internals.Resize => shape.parameters.headOption.map(retained).getOrElse(Fixed(resize.size))
        case _: CastBitVectorToBitVector => leftWidth
        case _: CastBoolToBits => Fixed(1)
        case _: Operator.Bits.Cat | _: Operator.BitVector.Mul => Sum(widthsOfChildren)
        case _: Operator.BitVector.Div => leftWidth
        case _: Operator.BitVector.Mod => Minimum(widthsOfChildren)
        case op: Operator.BitVector.Repeat => Product(Vector(leftWidth, Fixed(op.count)))
        case op: Operator.Bool.Repeat => Fixed(op.count)
        case op: Operator.BitVector.ShiftLeftByInt => Sum(Vector(leftWidth, Fixed(op.shift)))
        case op: Operator.BitVector.ShiftRightByInt => Maximum(Vector(Fixed(0), Difference(leftWidth, Fixed(op.shift))))
        case _: Operator.BitVector.ShiftLeftByUInt => UnknownWidth
        case _: Operator.BitVector.ShiftOperator => leftWidth
        case port: MemPortStatement if shape.rule == MemoryRead =>
          if (!port.hasTag(AllowMixedWidth) && shape.nativeBits == port.mem.getWidth)
            visit(port.mem).width else UnknownWidth
        case _ if shape.rule == Arithmetic || shape.rule == MuxRule => Maximum(widthsOfChildren)
        case _ if shape.rule == Unary => leftWidth
        case _ if shape.rule == Unsupported => UnknownWidth
        case _ => baseWidth
      }
      var value = SignednessFacts.transfer(shape.rule, shape.kind, valueChildren.map(_.value))
      subject match {
        case literal: BitVectorLiteral if !literal.hasSpecifiedBitCount || literal.hasPoison() => value = Unknown
        case _ => ()
      }
      if (shape.rule == ResizeRule && leftWidth == width && width != UnknownWidth)
        value = valueChildren.headOption.map(_.value).getOrElse(Unknown)
      if (Set[Rule](Arithmetic, Unary, Shift, MuxRule).contains(shape.rule) && value != shape.kind)
        value = Unknown
      if (shape.rule == Reference || shape.rule == Aggregate) value = shape.kind
      val hierarchy = subject match {
        case base: BaseType => base.isIo && (base.component.isInstanceOf[BlackBox] || base.component.parent != null)
        case _ => false
      }
      val requirements = (SignednessFacts.requirements(shape.rule) ++
        (if (hierarchy) Vector(HierarchyBoundary) else Vector.empty) ++
        (if (value == Unknown || !resolved(width) || valueChildren.exists(_.value == Unknown))
          Vector(UnknownSemantics) else Vector.empty)).distinct
      val fact = Fact(id, shape.kind, value, shape.nativeBits, width, shape.rule, children.map(_.id), requirements)
      entries(id) = Entry(subject, shape, fact)
      fact
    }
    roots.foreach(visit)
    new Snapshot(entries.toVector, indices, widths.toVector)
  }
}
