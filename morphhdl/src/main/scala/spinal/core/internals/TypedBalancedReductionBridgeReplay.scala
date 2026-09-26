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

  /** The current register-chain driver and the original composite input are
    * different dependencies, even when both denote the same leaf at step zero.
    * Preserve that distinction through every later register and odd tail. */
  private sealed trait ControlInput {
    def value(current: BaseType, original: Vector[BaseType]): BaseType
    def width(current: ElaborationIntegerExpression,
        original: Vector[ElaborationIntegerExpression]): ElaborationIntegerExpression
  }
  private case object CurrentData extends ControlInput {
    def value(current: BaseType, original: Vector[BaseType]): BaseType = current
    def width(current: ElaborationIntegerExpression,
        original: Vector[ElaborationIntegerExpression]): ElaborationIntegerExpression = current
  }
  private final case class CompositeInput(index: Int) extends ControlInput {
    def value(current: BaseType, original: Vector[BaseType]): BaseType = original(index)
    def width(current: ElaborationIntegerExpression,
        original: Vector[ElaborationIntegerExpression]): ElaborationIntegerExpression = original(index)
  }

  private def mergeMinimumWidths(left: Map[ControlInput, Int],
      right: Map[ControlInput, Int]): Map[ControlInput, Int] =
    (left.keySet ++ right.keySet).map { input =>
      input -> left.getOrElse(input, 0).max(right.getOrElse(input, 0))
    }.toMap

  private sealed trait Enable {
    def replay(current: BaseType, original: Vector[BaseType]): Bool
    def minimumWidths: Map[ControlInput, Int]
  }
  private final case class BooleanInput(input: ControlInput) extends Enable {
    val minimumWidths: Map[ControlInput, Int] = Map(input -> 1)
    def replay(current: BaseType, original: Vector[BaseType]): Bool =
      input.value(current, original).asInstanceOf[Bool]
  }
  private final case class Constant(value: Boolean) extends Enable {
    // Preserve the scalar enable grammar's original positive data-width bound.
    val minimumWidths: Map[ControlInput, Int] = Map(CurrentData -> 1)
    def replay(current: BaseType, original: Vector[BaseType]): Bool = Bool(value)
  }
  private final case class Bit(input: ControlInput, index: Int, high: Boolean) extends Enable {
    val minimumWidths: Map[ControlInput, Int] = Map(input -> (if (high) 1 else index + 1))
    def replay(current: BaseType, original: Vector[BaseType]): Bool = {
      val bits = input.value(current, original).asInstanceOf[BitVector]
      if (high) bits.msb else bits(index)
    }
  }
  private final case class Not(value: Enable) extends Enable {
    val minimumWidths: Map[ControlInput, Int] = value.minimumWidths
    def replay(current: BaseType, original: Vector[BaseType]): Bool = !value.replay(current, original)
  }
  private final case class Binary(operation: Int, left: Enable, right: Enable) extends Enable {
    val minimumWidths: Map[ControlInput, Int] = mergeMinimumWidths(left.minimumWidths, right.minimumWidths)
    def replay(current: BaseType, original: Vector[BaseType]): Bool = operation match {
      case 0 => left.replay(current, original) && right.replay(current, original)
      case 1 => left.replay(current, original) || right.replay(current, original)
      case 2 => left.replay(current, original) ^ right.replay(current, original)
    }
  }
  private final case class RegisterStep(clock: ClockDomain, initializer: Option[BigInt], enable: Option[Enable])

  final class Proof private[TypedBalancedReductionBridgeReplay] (
      val nativeResult: BaseType,
      val resultWidth: ElaborationIntegerExpression,
      private val input: Evidence,
      private val controls: Vector[Evidence],
      private val inputIndex: Int,
      private val observation: TypedBalancedReductionClosedGraph.Observation,
      private val registers: Vector[RegisterStep],
      private val minimumInitializerWidth: Int,
      private val localGuards: Vector[() => Unit]
  ) {
    // Historical scalar proofs have exactly their own input as control lane.
    private[TypedBalancedReductionBridgeReplay] def this(
        nativeResult: BaseType, resultWidth: ElaborationIntegerExpression,
        input: Evidence, observation: TypedBalancedReductionClosedGraph.Observation,
        registers: Vector[RegisterStep], minimumInitializerWidth: Int,
        localGuards: Vector[() => Unit]
    ) = this(nativeResult, resultWidth, input, Vector(input), 0, observation,
      registers, minimumInitializerWidth, localGuards)

    val registerCount: Int = registers.size
    val hasLocalEnables: Boolean = registers.exists(_.enable.nonEmpty)

    def validateFreshness(): Unit = {
      input.requireFreshness()
      controls.foreach(_.requireFreshness())
      localGuards.foreach(_.apply())
      observation.requireUnchanged()
    }

    def sameBehavior(other: Proof): Boolean = {
      if (other == null) return false
      validateFreshness()
      other.validateFreshness()
      (input.owner eq other.input.owner) && (input.kind eq other.input.kind) &&
        inputIndex == other.inputIndex && controls.size == other.controls.size &&
        controls.zip(other.controls).forall { case (a, b) =>
          (a.owner eq b.owner) && (a.kind eq b.kind)
        } && minimumInitializerWidth == other.minimumInitializerWidth &&
        registers.size == other.registers.size &&
        registers.zip(other.registers).forall { case (a, b) =>
          (a.clock eq b.clock) && a.initializer == b.initializer && a.enable == b.enable
        }
    }

    private def scalarReplayOnly(): Unit =
      if (controls.size != 1 || inputIndex != 0)
        fail("CONTROL-ARITY", "a composite-control bridge must be replayed with its full recursive input")

    def replay(value: BaseType): BaseType = {
      scalarReplayOnly()
      input.requireReplacement(value)
      replayWithWidth(value, input.width)
    }

    def replayWithWidth(value: BaseType, width: ElaborationIntegerExpression): BaseType = {
      scalarReplayOnly()
      replayWithWidthWhen(value, width, Vector(value), Vector(width), None)
    }

    /** A generated template has data semantics only on its exact active COUNT
      * domain. Inactive positive-width placeholders cannot impose a spurious
      * initializer restriction, or excuse an illegal active initializer. */
    def replayWithWidth(value: BaseType, width: ElaborationIntegerExpression,
        active: ElaborationBooleanExpression): BaseType = {
      scalarReplayOnly()
      if (active == null) fail("WIDTH-AUTHORITY", "template activity must retain exact native count authority")
      replayWithWidthWhen(value, width, Vector(value), Vector(width), Some(active))
    }

    def replayWithWidth(value: BaseType, width: ElaborationIntegerExpression,
        controlValues: Vector[BaseType], controlWidths: Vector[ElaborationIntegerExpression]): BaseType =
      replayWithWidthWhen(value, width, controlValues, controlWidths, None)

    def replayWithWidth(value: BaseType, width: ElaborationIntegerExpression,
        controlValues: Vector[BaseType], controlWidths: Vector[ElaborationIntegerExpression],
        active: ElaborationBooleanExpression): BaseType = {
      if (active == null) fail("WIDTH-AUTHORITY", "template activity must retain exact native count authority")
      replayWithWidthWhen(value, width, controlValues, controlWidths, Some(active))
    }

    private def requireReplacement(evidence: Evidence, candidate: BaseType,
        width: ElaborationIntegerExpression, label: String): Unit = {
      evidence.requireFreshness()
      ElaborationWidthAuthority.requireAuthoritative(width, label,
        "MORPH-REDUCE-BALANCED-BRIDGE-WIDTH-AUTHORITY")
      if (candidate == null || (candidate.component ne evidence.owner) ||
          candidate.getClass != evidence.value.getClass ||
          (candidate.getTypeObject.asInstanceOf[AnyRef] ne evidence.kind) ||
          candidate.isAnalog || candidate.hasTag(tagAutoResize) ||
          BigInt(candidate.getBitsWidth) != width.default ||
          !ElaborationWidthAuthority.equivalent(ParameterizedWidth.expressionOf(candidate)
            .getOrElse(ElabInt.literal(candidate.getBitsWidth).expression), width))
        fail("WIDTH", label + " lacks its exact owner, scalar type and symbolic width")
    }

    private def replayWithWidthWhen(value: BaseType, width: ElaborationIntegerExpression,
        controlValues: Vector[BaseType], controlWidths: Vector[ElaborationIntegerExpression],
        active: Option[ElaborationBooleanExpression]): BaseType = {
      validateFreshness()
      if (Component.current ne input.owner)
        fail("OWNER", "bridge replay must remain inside its owning component")
      if (controlValues == null || controlWidths == null ||
          controlValues.size != controls.size || controlWidths.size != controls.size ||
          inputIndex < 0 || inputIndex >= controls.size || (controlValues(inputIndex) ne value))
        fail("CONTROL-BINDING", "composite controls must preserve their complete recursive leaf order")
      requireReplacement(input, value, width, "replayed bridge data width")
      controls.zip(controlValues).zip(controlWidths).zipWithIndex.foreach {
        case (((evidence, candidate), controlWidth), index) =>
          requireReplacement(evidence, candidate, controlWidth,
            "replayed bridge control width " + index)
      }
      def minimum(value: ElaborationIntegerExpression): Option[BigInt] =
        active.map(condition => ElaborationWidthAuthority.minimumWhen(value, condition))
          .getOrElse(Some(value.minimum))
      if (minimum(width).exists(_ < minimumInitializerWidth))
        fail("INITIALIZER-WIDTH", "replayed narrower native lane cannot contain the certified initializer width")
      registers.flatMap(_.enable).foreach { enable =>
        enable.minimumWidths.foreach { case (control, required) =>
          if (minimum(control.width(width, controlWidths)).exists(_ < required))
            fail("ENABLE-WIDTH", "replayed control lane cannot contain a certified enable bit")
        }
      }
      registers.foldLeft(value) { (prior, step) =>
        val context = step.clock.push()
        try {
          val next = ParameterizedWidth.cloneOf(prior)
          next.setAsDirectionLess()
          next.setAsReg()
          step.enable match {
            case Some(enable) => when(enable.replay(prior, controlValues)) { next.assignFrom(prior) }
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

  def certify(callback: UnvalidatedBalancedCallback, input: Evidence): Proof =
    certifyWithControls(callback, input, Vector(input), compositeControls = false)

  def certify(callback: UnvalidatedBalancedCallback, input: Evidence,
      controls: Vector[Evidence]): Proof =
    certifyWithControls(callback, input, controls, compositeControls = true)

  /** The complete composite callback owns shared native enable scopes. Its
    * authenticated observation retains every sibling write while the scalar
    * proof below still consumes exactly its own data/control path. */
  def certifyProjection(callback: UnvalidatedBalancedCallback, input: Evidence,
      controls: Vector[Evidence], observation: TypedBalancedReductionClosedGraph.Observation): Proof = {
    if (observation == null) fail("PROJECTION", "a composite bridge projection needs its closed whole callback")
    certifyWithControls(callback, input, controls, compositeControls = true, enclosing = Some(observation))
  }

  private def certifyWithControls(callback: UnvalidatedBalancedCallback, input: Evidence,
      controls: Vector[Evidence], compositeControls: Boolean,
      enclosing: Option[TypedBalancedReductionClosedGraph.Observation] = None): Proof = {
    if (callback == null || input == null || controls == null || controls.isEmpty ||
        callback.operands == null || callback.operands.size != controls.size || callback.result == null)
      fail("ARITY", "bridge proof needs its exact recursive controls and one scalar result")
    val identities = new IdentityHashMap[BaseType, java.lang.Integer]()
    controls.zipWithIndex.foreach { case (evidence, index) =>
      if (evidence == null || identities.put(evidence.value, java.lang.Integer.valueOf(index)) != null)
        fail("CONTROL-IDENTITY", "bridge controls must be unique live scalar leaves")
      evidence.requireValue(evidence.value)
    }
    if (callback.operands.zip(controls).exists {
        case (value: BaseType, evidence) => value ne evidence.value
        case _ => true
      })
      fail("CONTROL-BINDING", "callback operands changed their recursive composite-leaf order")
    val source = input.value
    val inputIndexValue = identities.get(source)
    if (inputIndexValue == null)
      fail("CONTROL-BINDING", "bridge data input is absent from its admitted controls")
    val inputIndex = inputIndexValue.intValue()
    val result = callback.result match {
      case value: BaseType => value
      case _ => fail("TYPE", "bridge result must be a native scalar")
    }
    input.requireValue(source)
    if (Component.current ne input.owner)
      fail("OWNER", "bridge certification requires its active owning component")
    val observation = enclosing match {
      case Some(whole) => whole.requireProjection(callback); whole
      case None => TypedBalancedReductionClosedGraph.observe(callback)
    }
    val seen = new IdentityHashMap[BaseType, java.lang.Boolean]()
    val consumed = new IdentityHashMap[AssignmentStatement, java.lang.Boolean]()
    val initializerNodes = new IdentityHashMap[BaseType, BigInt]()
    val zeroInitializerNodes = new IdentityHashMap[BaseType, java.lang.Boolean]()
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
    def initializer(expression: Expression, zeroOnly: Boolean = false): BigInt = expression match {
      case literal: BitVectorLiteral
          if !literal.hasPoison &&
            (literal.getTypeObject.asInstanceOf[AnyRef] eq input.kind) =>
        if (zeroOnly && literal.value != 0)
          fail("INITIALIZER", "symbolic initializer aliases must terminate in exact native zero")
        val required = if (zeroOnly) 1 else literal.getWidth
        minimumInitializerWidth = minimumInitializerWidth.max(required)
        if (BigInt(required) > input.width.minimum)
          fail("INITIALIZER-WIDTH", "initializer width exceeds the smallest certified data width")
        literal.value
      case literal: BoolLiteral if input.kind eq TypeBool =>
        if (zeroOnly && literal.value)
          fail("INITIALIZER", "symbolic initializer aliases must terminate in exact native zero")
        minimumInitializerWidth = minimumInitializerWidth.max(1)
        if (literal.value) BigInt(1) else BigInt(0)
      case value: BaseType =>
        local(value)
        if (value.isReg || (value eq source))
          fail("INITIALIZER", "initializer aliases must be local constant-only combinational nodes")
        val symbolic = ParameterizedWidth.expressionOf(value).filter(_.parameters.nonEmpty)
        symbolic.foreach { width =>
          ElaborationWidthAuthority.requireAuthoritative(width, "native bridge zero initializer",
            "MORPH-REDUCE-BALANCED-BRIDGE-INITIALIZER")
          if (width.minimum < 1 || width.default != BigInt(value.getBitsWidth) ||
              !ElabInt.equivalentExactFunction(width, input.width))
            fail("INITIALIZER", "symbolic zero alias must retain its exact corresponding bridge width function")
          localGuards += (() => {
            if (!ParameterizedWidth.expressionOf(value).exists(_ eq width))
              fail("STALE-SHAPE", "symbolic zero alias changed its exact retained width authority")
          })
        }
        // A full, local, correctly typed constant-zero graph denotes zero at
        // every positive width. Its native witness width is not a lower bound
        // on replay. Other initializers retain their existing width checks.
        val requireZero = zeroOnly || symbolic.nonEmpty
        val required = if (requireZero) 1 else value.getBitsWidth
        minimumInitializerWidth = minimumInitializerWidth.max(required)
        if (BigInt(required) > input.width.minimum)
          fail("INITIALIZER-WIDTH", "initializer alias width exceeds the smallest certified data width")
        if (!requireZero && zeroInitializerNodes.containsKey(value))
          fail("INITIALIZER", "an ordinary initializer cannot reuse a zero-only width proof")
        if (!initializerNodes.containsKey(value)) {
          if (seen.put(value, java.lang.Boolean.TRUE) != null)
            fail("INITIALIZER", "initializer aliases cannot overlap the data path or form cycles")
          val assignments = assignmentsOf(value)
          val initial = assignments match {
            case Vector(data: DataAssignmentStatement) => initializer(data.source, requireZero)
            case _ => fail("INITIALIZER", "initializer alias must have exactly one native constant driver")
          }
          val fixed = value match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
          localGuards += (() => {
            val now = value match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
            if (now != fixed || value.hasTag(tagAutoResize))
              fail("STALE-SHAPE", "initializer alias changed its width or resize policy")
          })
          initializerNodes.put(value, initial)
          if (requireZero) zeroInitializerNodes.put(value, java.lang.Boolean.TRUE)
        }
        val constant = initializerNodes.get(value)
        if (requireZero && constant != 0)
          fail("INITIALIZER", "symbolic initializer aliases must terminate in exact native zero")
        constant
      case _ => fail("INITIALIZER", "initializers must be typed native constants or transparent local constant aliases")
    }

    def enable(expression: Expression, driver: BaseType, depth: Int = 0): Enable = {
      if (depth > 512) fail("ENABLE", "local enable graph exceeds its certified depth")
      def child(value: Expression): Enable = enable(value, driver, depth + 1)
      def admitted(value: BaseType): Int = {
        val found = identities.get(value)
        if (found == null) -1 else found.intValue()
      }
      def control(value: BaseType): ControlInput = {
        if (value eq driver) CurrentData
        else if (compositeControls && admitted(value) >= 0) CompositeInput(admitted(value))
        else fail("ENABLE", "control must be the current data driver or an admitted original composite leaf")
      }
      expression match {
        case value: Bool if (value eq driver) || (compositeControls && admitted(value) >= 0) =>
          BooleanInput(control(value))
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
        case access: BitVectorBitAccessFixed =>
          val selected = access.source match {
            case source: BaseType => control(source)
            case _ => fail("ENABLE", "enable bit source must be an exact admitted native scalar")
          }
          val evidence = selected match {
            case CurrentData => input
            case CompositeInput(index) => controls(index)
          }
          if ((evidence.kind ne TypeBits) && (evidence.kind ne TypeUInt) && (evidence.kind ne TypeSInt))
            fail("ENABLE", "enable bit source must remain a native bit vector")
          val high = NativeWidthProvenance.isHighBit(access)
          if (access.bitId < 0 || (!high && BigInt(access.bitId) >= evidence.width.minimum))
            fail("ENABLE-WIDTH", "enable index is outside the smallest certified control width")
          Bit(selected, if (high) 0 else access.bitId, high)
        case operator: Operator.Bool.Not => Not(child(operator.source))
        case operator: Operator.Bool.And => Binary(0, child(operator.left), child(operator.right))
        case operator: Operator.Bool.Or => Binary(1, child(operator.left), child(operator.right))
        case operator: Operator.Bool.Xor => Binary(2, child(operator.left), child(operator.right))
        case _ => fail("ENABLE", "enable must be a closed Bool predicate of the current path and admitted composite inputs")
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
    val proof = new Proof(result, input.width, input, controls, inputIndex, observation,
      registers.reverse.toVector, minimumInitializerWidth, localGuards.toVector)
    proof.validateFreshness()
    proof
  }
}
