package spinal.core.internals

import java.util.IdentityHashMap
import scala.collection.mutable.ArrayBuffer
import spinal.core._
import TypedBalancedReductionValueEvidence.Evidence

/** Exact scalar identity/alias and unconditional register-chain bridges.
  * Register reset and enable semantics remain owned by the native clock
  * domain and register implementation. No Scala levelBridge is replayed.
  */
private[spinal] object TypedBalancedReductionBridgeReplay {
  private def fail(code: String, detail: String): Nothing =
    throw new IllegalArgumentException(s"MORPH-REDUCE-BALANCED-BRIDGE-$code: $detail")

  private final case class RegisterStep(clock: ClockDomain, zeroInitialized: Boolean)

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
          (a.clock eq b.clock) && a.zeroInitialized == b.zeroInitialized
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
          next.assignFrom(prior)
          if (step.zeroInitialized) {
            val literal: Expression = next match {
              case _: Bool => new BoolLiteral(false)
              case _: Bits => BitsLiteral(BigInt(0), -1)
              case _: UInt => UIntLiteral(BigInt(0), -1)
              case _: SInt => SIntLiteral(BigInt(0), -1)
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
    val initializerNodes = new IdentityHashMap[BaseType, java.lang.Boolean]()
    val registers = ArrayBuffer.empty[RegisterStep]
    var minimumInitializerWidth = 0
    val localGuards = ArrayBuffer.empty[() => Unit]

    def local(value: BaseType): Unit = {
      if (!callback.declarations.exists(_ eq value) ||
          (value.component ne input.owner) || !value.isDirectionLess ||
          value.isAnalog || value.hasTag(tagAutoResize) ||
          (value.parentScope ne input.owner.dslBody) ||
          (value.getTypeObject.asInstanceOf[AnyRef] ne input.kind) ||
          value.getClass != source.getClass)
        fail("TYPE", "bridge locals must retain the exact scalar type and root scope")
    }

    def assignmentsOf(value: BaseType): Vector[AssignmentStatement] = {
      val assignments = callback.assignments.filter(_.finalTarget eq value)
      assignments.foreach { assignment =>
        if ((assignment.target ne value) || (assignment.parentScope ne input.owner.dslBody))
          fail("DRIVER", "partial and conditional bridge drivers are not admitted")
        consumed.put(assignment, java.lang.Boolean.TRUE)
      }
      assignments
    }

    /** DSL literals can be wrapped in callback-local native scalar nodes.
      * Follow only their exact full-object constant aliases. Do not evaluate
      * arbitrary expressions or accept a pre-existing external constant.
      */
    def zeroInitializer(expression: Expression): Unit = expression match {
      case literal: BitVectorLiteral
          if !literal.hasPoison && literal.value == BigInt(0) &&
            (literal.getTypeObject.asInstanceOf[AnyRef] eq input.kind) =>
        minimumInitializerWidth = minimumInitializerWidth.max(literal.getWidth)
        if (BigInt(literal.getWidth) > input.width.minimum)
          fail("INITIALIZER-WIDTH", "initializer width exceeds the smallest certified data width")
      case literal: BoolLiteral if (input.kind eq TypeBool) && !literal.value =>
        minimumInitializerWidth = minimumInitializerWidth.max(1)
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
          assignments match {
            case Vector(data: DataAssignmentStatement) => zeroInitializer(data.source)
            case _ => fail("INITIALIZER", "initializer alias must have exactly one native constant driver")
          }
          val fixed = value match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
          localGuards += (() => {
            val now = value match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
            if (now != fixed || value.hasTag(tagAutoResize))
              fail("STALE-SHAPE", "initializer alias changed its width or resize policy")
          })
          initializerNodes.put(value, java.lang.Boolean.TRUE)
        }
      case _ => fail("INITIALIZER", "only native zero initializers are width-independent in this profile")
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
        if (value.clockDomain == null || (init.nonEmpty && !value.clockDomain.canInit))
          fail("CLOCK", "initialized bridge register needs its real native reset/boot domain")
        init.foreach(assignment => zeroInitializer(assignment.source))
        registers += RegisterStep(value.clockDomain, init.nonEmpty)
      }
      data.head.source match {
        case next: BaseType => inspect(next)
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
