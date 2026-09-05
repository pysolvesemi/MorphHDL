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
        ElabInt.equivalentExactFunction(resultWidth, other.resultWidth) &&
        registers.size == other.registers.size &&
        registers.zip(other.registers).forall { case (a, b) =>
          (a.clock eq b.clock) && a.zeroInitialized == b.zeroInitialized
        }
    }

    def replay(value: BaseType): BaseType = {
      validateFreshness()
      if (Component.current ne input.owner)
        fail("OWNER", "bridge replay must remain inside its owning component")
      input.requireReplacement(value)
      registers.foldLeft(value) { (prior, step) =>
        val context = step.clock.push()
        try {
          val next = ParameterizedWidth.cloneOf(prior)
          next.setAsDirectionLess()
          next.setAsReg()
          next.assignFrom(prior)
          if (step.zeroInitialized) {
            // Unsized native zero cannot impose the default width on an
            // inferred register at another legal width specialization.
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
    val registers = ArrayBuffer.empty[RegisterStep]
    val localGuards = ArrayBuffer.empty[() => Unit]

    def inspect(value: BaseType): Unit = {
      if (value eq source) return
      if (!callback.declarations.exists(_ eq value) ||
          seen.put(value, java.lang.Boolean.TRUE) != null)
        fail("DEPENDENCY", "bridge path is not an acyclic chain from its exact input")
      if ((value.component ne input.owner) || !value.isDirectionLess ||
          value.isAnalog || value.hasTag(tagAutoResize) ||
          (value.parentScope ne input.owner.dslBody) ||
          (value.getTypeObject.asInstanceOf[AnyRef] ne input.kind) ||
          value.getClass != source.getClass)
        fail("TYPE", "bridge locals must retain the exact scalar type and root scope")
      val fixed = value match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
      val retained = ParameterizedWidth.expressionOf(value)
      if ((fixed >= 0 && BigInt(fixed) != input.width.default) ||
          retained.exists(width => !ElabInt.equivalentExactFunction(width, input.width)) ||
          (fixed >= 0 && input.width.parameters.nonEmpty && retained.isEmpty))
        fail("WIDTH", "a fixed native clone witness is not symbolic bridge-width authority")
      if (BigInt(value.getBitsWidth) != input.width.default)
        fail("WIDTH", "bridge does not preserve its input width")
      localGuards += (() => {
        val current = value match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
        if (current != fixed || value.hasTag(tagAutoResize))
          fail("STALE-SHAPE", "bridge local changed its fixed-width or resize policy")
      })
      val assignments = callback.assignments.filter(_.finalTarget eq value)
      val data = assignments.collect { case assignment: DataAssignmentStatement => assignment }
      val init = assignments.collect { case assignment: InitAssignmentStatement => assignment }
      if (data.size != 1 || init.size > 1 || (!value.isReg && init.nonEmpty) ||
          data.size + init.size != assignments.size)
        fail("DRIVER", "bridge needs one full driver and at most one register initializer")
      assignments.foreach { assignment =>
        if ((assignment.target ne value) || (assignment.parentScope ne input.owner.dslBody))
          fail("DRIVER", "partial and conditional bridge drivers are not admitted")
        consumed.put(assignment, java.lang.Boolean.TRUE)
      }
      if (value.isReg) {
        if (value.clockDomain == null || (init.nonEmpty && !value.clockDomain.canInit))
          fail("CLOCK", "initialized bridge register needs its real native reset/boot domain")
        init.foreach { assignment =>
          assignment.source match {
            case literal: BitVectorLiteral
                if !literal.hasPoison && literal.value == BigInt(0) &&
                  (literal.getTypeObject.asInstanceOf[AnyRef] eq input.kind) =>
              // Native BitVector width inference includes initialization
              // drivers. Even an all-zero fixed-width literal can leak the
              // witness width into an otherwise inferred register.
              if (BigInt(literal.getWidth) > input.width.minimum)
                fail("INITIALIZER-WIDTH", "initializer width exceeds the smallest certified data width")
            case literal: BoolLiteral if (input.kind eq TypeBool) && !literal.value =>
            case _ => fail("INITIALIZER", "only native zero initializers are width-independent in this profile")
          }
        }
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
      registers.reverse.toVector, localGuards.toVector)
    proof.validateFreshness()
    proof
  }
}
