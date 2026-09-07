package spinal.core.internals

import java.util.IdentityHashMap
import scala.collection.mutable.ArrayBuffer
import spinal.core._
import TypedBalancedReductionValueEvidence.Evidence

/** Exact scalar identity/alias and native register-chain bridges.
  * Register reset and enable semantics remain owned by the native clock
  * domain and register implementation. No Scala levelBridge is replayed.
  */
private[spinal] object TypedBalancedReductionBridgeReplay {
  private def fail(code: String, detail: String): Nothing =
    throw new IllegalArgumentException(s"MORPH-REDUCE-BALANCED-BRIDGE-$code: $detail")

  private sealed trait Enable {
    def replay(value: BaseType): Bool
    def minimumWidth: Int = 1
  }
  private case object BooleanInput extends Enable {
    def replay(value: BaseType): Bool = value.asInstanceOf[Bool]
  }
  private final case class Constant(value: Boolean) extends Enable {
    def replay(input: BaseType): Bool = Bool(value)
  }
  private final case class Bit(index: Int, high: Boolean) extends Enable {
    override def minimumWidth: Int = if (high) 1 else index + 1
    def replay(value: BaseType): Bool = {
      val bits = value.asInstanceOf[BitVector]
      if (high) bits.msb else bits(index)
    }
  }
  private final case class Not(value: Enable) extends Enable {
    override def minimumWidth: Int = value.minimumWidth
    def replay(input: BaseType): Bool = !value.replay(input)
  }
  private final case class Binary(operation: Int, left: Enable, right: Enable) extends Enable {
    override def minimumWidth: Int = left.minimumWidth.max(right.minimumWidth)
    def replay(input: BaseType): Bool = operation match {
      case 0 => left.replay(input) && right.replay(input)
      case 1 => left.replay(input) || right.replay(input)
      case 2 => left.replay(input) ^ right.replay(input)
    }
  }
  private final case class RegisterStep(clock: ClockDomain, initializer: Option[BigInt], enable: Option[Enable])

  final class Proof private[TypedBalancedReductionBridgeReplay] (
      val nativeResult: BaseType,
      val resultWidth: ElaborationIntegerExpression,
      private val input: Evidence,
      private val observation: TypedBalancedReductionClosedGraph.Observation,
      private val registers: Vector[RegisterStep],
      private val minimumInitializerWidth: Int,
      private val localGuards: Vector[() => Unit]
  ) {
    val registerCount: Int = registers.size
    val hasLocalEnables: Boolean = registers.exists(_.enable.nonEmpty)

    def validateFreshness(): Unit = {
      input.requireFreshness()
      localGuards.foreach(_.apply())
      observation.requireUnchanged()
    }

    def sameBehavior(other: Proof): Boolean = {
      if (other == null) return false
      validateFreshness()
      other.validateFreshness()
      (input.owner eq other.input.owner) && (input.kind eq other.input.kind) &&
        minimumInitializerWidth == other.minimumInitializerWidth &&
        registers.size == other.registers.size &&
        registers.zip(other.registers).forall { case (a, b) =>
          (a.clock eq b.clock) && a.initializer == b.initializer && a.enable == b.enable
        }
    }

    def replay(value: BaseType): BaseType = {
      input.requireReplacement(value)
      replayWithWidth(value, input.width)
    }

    def replayWithWidth(value: BaseType, width: ElaborationIntegerExpression): BaseType =
      replayWithWidthWhen(value, width, None)

    /** A generated template has data semantics only on its exact active COUNT
      * domain. Inactive positive-width placeholders cannot impose a spurious
      * initializer restriction, or excuse an illegal active initializer. */
    def replayWithWidth(value: BaseType, width: ElaborationIntegerExpression,
        active: ElaborationBooleanExpression): BaseType = {
      if (active == null) fail("WIDTH-AUTHORITY", "template activity must retain exact native count authority")
      replayWithWidthWhen(value, width, Some(active))
    }

    private def replayWithWidthWhen(value: BaseType, width: ElaborationIntegerExpression,
        active: Option[ElaborationBooleanExpression]): BaseType = {
      validateFreshness()
      if (Component.current ne input.owner)
        fail("OWNER", "bridge replay must remain inside its owning component")
      ElaborationWidthAuthority.requireAuthoritative(width, "replayed bridge width",
        "MORPH-REDUCE-BALANCED-BRIDGE-WIDTH-AUTHORITY")
      val activeMinimum = active.map(ElaborationWidthAuthority.minimumWhen(width, _))
        .getOrElse(Some(width.minimum))
      if (activeMinimum.exists(_ < minimumInitializerWidth))
        fail("INITIALIZER-WIDTH", "replayed narrower native lane cannot contain the certified initializer width")
      if (registers.flatMap(_.enable).exists(enable => activeMinimum.exists(_ < enable.minimumWidth)))
        fail("ENABLE-WIDTH", "replayed narrower native lane cannot contain a certified enable bit")
      if (value == null || (value.component ne input.owner) ||
          (value.getTypeObject.asInstanceOf[AnyRef] ne input.kind) || value.isAnalog ||
          value.hasTag(tagAutoResize) || BigInt(value.getBitsWidth) != width.default ||
          !ElaborationWidthAuthority.equivalent(ParameterizedWidth.expressionOf(value)
            .getOrElse(ElabInt.literal(value.getBitsWidth).expression), width))
        fail("WIDTH", "replayed bridge input lacks its exact certified native shape")
      registers.foldLeft(value) { (prior, step) =>
        val context = step.clock.push()
        try {
          val next = ParameterizedWidth.cloneOf(prior)
          next.setAsDirectionLess()
          next.setAsReg()
          step.enable match {
            case Some(enable) => when(enable.replay(prior)) { next.assignFrom(prior) }
            case None => next.assignFrom(prior)
          }
          step.initializer.foreach { initial =>
            val literal: Expression = next match {
              case _: Bool => new BoolLiteral(initial != 0)
              case _: Bits => BitsLiteral(initial, -1)
              case _: UInt => UIntLiteral(initial, -1)
              case _: SInt => SIntLiteral(initial, -1)
              case _ => fail("TYPE", "unsupported native bridge result type")
            }
            next.initFrom(literal)
          }
          next
        } finally context.restore()
      }
    }
  }

  def certify(callback: UnvalidatedBalancedCallback, input: Evidence): Proof = {
    if (callback == null || input == null || callback.operands == null ||
        callback.operands.size != 1 || callback.result == null)
      fail("ARITY", "bridge proof needs one exact operand and a scalar result")
    val source = callback.operands.head match {
      case value: BaseType => value
      case _ => fail("TYPE", "bridge operand must be a native scalar")
    }
    val result = callback.result match {
      case value: BaseType => value
      case _ => fail("TYPE", "bridge result must be a native scalar")
    }
    input.requireValue(source)
    if (Component.current ne input.owner)
      fail("OWNER", "bridge certification requires its active owning component")
    val observation = TypedBalancedReductionClosedGraph.observe(callback)
    val seen = new IdentityHashMap[BaseType, java.lang.Boolean]()
    val consumed = new IdentityHashMap[AssignmentStatement, java.lang.Boolean]()
    val initializerNodes = new IdentityHashMap[BaseType, BigInt]()
    val enableNodes = new IdentityHashMap[BaseType, java.lang.Boolean]()
    val registers = ArrayBuffer.empty[RegisterStep]
    var minimumInitializerWidth = 0
    val localGuards = ArrayBuffer.empty[() => Unit]

    def local(value: BaseType, predicate: Boolean = false): Unit = {
      if (!callback.declarations.exists(_ eq value) ||
          (value.component ne input.owner) || !value.isDirectionLess ||
          value.isAnalog || value.hasTag(tagAutoResize) ||
          (value.parentScope ne input.owner.dslBody) ||
          (if (predicate) value.getClass != classOf[Bool] else
            (value.getTypeObject.asInstanceOf[AnyRef] ne input.kind) || value.getClass != source.getClass))
        fail("TYPE", "bridge locals must retain the exact scalar type and root scope")
    }

    def assignmentsOf(value: BaseType): Vector[AssignmentStatement] = {
      val assignments = callback.assignments.filter(_.finalTarget eq value)
      assignments.foreach { assignment =>
        if ((assignment.target ne value) ||
            ((assignment.parentScope ne input.owner.dslBody) && !value.isReg))
          fail("DRIVER", "partial or conditionally driven combinational bridge nodes are not admitted")
        consumed.put(assignment, java.lang.Boolean.TRUE)
      }
      assignments
    }

    /** DSL literals can be wrapped in callback-local native scalar nodes.
      * Follow only their exact full-object constant aliases. Do not evaluate
      * arbitrary expressions or accept a pre-existing external constant.
      */
    def initializer(expression: Expression): BigInt = expression match {
      case literal: BitVectorLiteral
          if !literal.hasPoison &&
            (literal.getTypeObject.asInstanceOf[AnyRef] eq input.kind) =>
        minimumInitializerWidth = minimumInitializerWidth.max(literal.getWidth)
        if (BigInt(literal.getWidth) > input.width.minimum)
          fail("INITIALIZER-WIDTH", "initializer width exceeds the smallest certified data width")
        literal.value
      case literal: BoolLiteral if input.kind eq TypeBool =>
        minimumInitializerWidth = minimumInitializerWidth.max(1)
        if (literal.value) BigInt(1) else BigInt(0)
      case value: BaseType =>
        local(value)
        if (value.isReg || (value eq source) ||
            ParameterizedWidth.expressionOf(value).exists(_.parameters.nonEmpty))
          fail("INITIALIZER", "initializer aliases must be local constant-only combinational nodes")
        minimumInitializerWidth = minimumInitializerWidth.max(value.getBitsWidth)
        if (BigInt(value.getBitsWidth) > input.width.minimum)
          fail("INITIALIZER-WIDTH", "initializer alias width exceeds the smallest certified data width")
        if (!initializerNodes.containsKey(value)) {
          if (seen.put(value, java.lang.Boolean.TRUE) != null)
            fail("INITIALIZER", "initializer aliases cannot overlap the data path or form cycles")
          val assignments = assignmentsOf(value)
          val initial = assignments match {
            case Vector(data: DataAssignmentStatement) => initializer(data.source)
            case _ => fail("INITIALIZER", "initializer alias must have exactly one native constant driver")
          }
          val fixed = value match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
          localGuards += (() => {
            val now = value match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
            if (now != fixed || value.hasTag(tagAutoResize))
              fail("STALE-SHAPE", "initializer alias changed its width or resize policy")
          })
          initializerNodes.put(value, initial)
        }
        initializerNodes.get(value)
      case _ => fail("INITIALIZER", "initializers must be typed native constants or transparent local constant aliases")
    }

    def enable(expression: Expression, driver: BaseType, depth: Int = 0): Enable = {
      if (depth > 512) fail("ENABLE", "local enable graph exceeds its certified depth")
      def child(value: Expression): Enable = enable(value, driver, depth + 1)
      expression match {
        case value: Bool if value eq driver => BooleanInput
        case literal: BoolLiteral => Constant(literal.value)
        case value: Bool =>
          local(value, predicate = true)
          if (value.isReg || initializerNodes.containsKey(value))
            fail("ENABLE", "enable predicates must be local combinational Bool expressions")
          if (!enableNodes.containsKey(value)) {
            if (seen.put(value, java.lang.Boolean.TRUE) != null)
              fail("ENABLE", "enable predicates cannot overlap another local data path")
            enableNodes.put(value, java.lang.Boolean.TRUE)
          }
          assignmentsOf(value) match {
            case Vector(data: DataAssignmentStatement) => child(data.source)
            case _ => fail("ENABLE", "enable aliases need one complete combinational driver")
          }
        case access: BitVectorBitAccessFixed if access.source eq driver =>
          val high = NativeWidthProvenance.isHighBit(access)
          if (access.bitId < 0 || (!high && BigInt(access.bitId) >= input.width.minimum))
            fail("ENABLE-WIDTH", "enable index is outside the smallest certified data width")
          Bit(if (high) 0 else access.bitId, high)
        case operator: Operator.Bool.Not => Not(child(operator.source))
        case operator: Operator.Bool.And => Binary(0, child(operator.left), child(operator.right))
        case operator: Operator.Bool.Or => Binary(1, child(operator.left), child(operator.right))
        case operator: Operator.Bool.Xor => Binary(2, child(operator.left), child(operator.right))
        case _ => fail("ENABLE", "enable must be a closed Bool predicate of the register's exact data input")
      }
    }

    def clock(clock: ClockDomain, initialized: Boolean): Unit = {
      if (clock == null || clock.clock == null || clock.softReset != null ||
          (clock.config.resetKind != SYNC && clock.config.resetKind != ASYNC) ||
          (clock.config.clockEdge != RISING && clock.config.clockEdge != FALLING) ||
          (clock.config.resetActiveLevel != HIGH && clock.config.resetActiveLevel != LOW) ||
          (clock.config.clockEnableActiveLevel != HIGH && clock.config.clockEnableActiveLevel != LOW) ||
          (initialized && !clock.hasResetSignal))
        fail("CLOCK", "bridge clock must use a qualified native edge, optional clock enable, and SYNC/ASYNC reset; initialized state needs a reset pin")
      val config = clock.config
      val signals = Vector(clock.clock, clock.reset, clock.softReset, clock.clockEnable)
      localGuards += (() => {
        if ((clock.config ne config) || Vector(clock.clock, clock.reset, clock.softReset, clock.clockEnable)
            .zip(signals).exists { case (current, saved) => current ne saved })
          fail("STALE-CLOCK", "native bridge clock configuration or control signal identity changed")
      })
    }

    def inspect(value: BaseType): Unit = {
      if (value eq source) return
      local(value)
      if (seen.put(value, java.lang.Boolean.TRUE) != null)
        fail("DEPENDENCY", "bridge path is not an acyclic chain from its exact input")
      val fixed = value match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
      val retained = ParameterizedWidth.expressionOf(value)
      if ((fixed >= 0 && BigInt(fixed) != input.width.default) ||
          retained.exists(width => !ElaborationWidthAuthority.equivalent(width, input.width)) ||
          (fixed >= 0 && input.width.parameters.nonEmpty && retained.isEmpty))
        fail("WIDTH", "a fixed native clone witness is not symbolic bridge-width authority")
      if (BigInt(value.getBitsWidth) != input.width.default)
        fail("WIDTH", "bridge does not preserve its input width")
      localGuards += (() => {
        val current = value match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
        if (!TypedBalancedReductionValueEvidence.preservesValueWidth(value, fixed, current, input.width) ||
            value.hasTag(tagAutoResize))
          fail("STALE-SHAPE", "bridge local changed its fixed-width or resize policy")
      })
      val assignments = assignmentsOf(value)
      val data = assignments.collect { case assignment: DataAssignmentStatement => assignment }
      val init = assignments.collect { case assignment: InitAssignmentStatement => assignment }
      if (data.size != 1 || init.size > 1 || (!value.isReg && init.nonEmpty) ||
          data.size + init.size != assignments.size)
        fail("DRIVER", "bridge needs one full driver and at most one register initializer")
      if (value.isReg) {
        clock(value.clockDomain, init.nonEmpty)
      }
      data.head.source match {
        case next: BaseType =>
          val localEnable = if (data.head.parentScope eq input.owner.dslBody) None else {
            val scope = data.head.parentScope
            val statement = scope.parentStatement.asInstanceOf[WhenStatement]
            Some(enable(statement.cond, next))
          }
          if (value.isReg) registers += RegisterStep(value.clockDomain,
            init.headOption.map(assignment => initializer(assignment.source)), localEnable)
          inspect(next)
        case _ => fail("EXPRESSION", "bridge data path must be identity/aliases or native registers, not an arithmetic expression")
      }
    }
    inspect(result)
    if (seen.size != callback.declarations.size || consumed.size != callback.assignments.size)
      fail("UNCONSUMED", "bridge contains local effects outside its result chain")
    val proof = new Proof(result, input.width, input, observation,
      registers.reverse.toVector, minimumInitializerWidth, localGuards.toVector)
    proof.validateFreshness()
    proof
  }
}
