#!/usr/bin/env python3
"""Stage generic same-composite control dependencies for register bridges.

Data for each result leaf remains bound to its corresponding input leaf. Local
register enables may additionally read any original scalar leaf of the same
composite value. The scalar proof records those controls by recursive leaf index
and rebinds them at replay time. Registered peer intermediates, external inputs,
cross-field data movement, unequal register counts and unreviewed expressions
remain rejected.
"""
from __future__ import annotations

import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BRIDGE = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala"
COMPOSITE = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala"
TEST = ROOT / "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeLocalEnableTests.scala"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def blob(path: Path) -> str:
    data = path.read_bytes()
    return hashlib.sha1((b"blob %d\0" % len(data)) + data).hexdigest()


def replace_once(text: str, before: str, after: str, label: str) -> str:
    require(text.count(before) == 1, f"{label} anchor count changed: {text.count(before)}")
    return text.replace(before, after, 1)


def replace_region(text: str, start: str, end: str, replacement: str, label: str) -> str:
    require(text.count(start) == 1, f"{label} start count changed: {text.count(start)}")
    require(text.count(end) == 1, f"{label} end count changed: {text.count(end)}")
    left = text.index(start)
    right = text.index(end, left)
    require(right > left, f"{label} anchors reversed")
    return text[:left] + replacement + text[right + len(end):]


def patch_bridge() -> None:
    text = BRIDGE.read_text()
    old_enable = '''  private sealed trait Enable {
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
'''
    new_enable = '''  private def mergeMinimumWidths(left: Map[Int, Int], right: Map[Int, Int]): Map[Int, Int] =
    (left.keySet ++ right.keySet).map { index =>
      index -> left.getOrElse(index, 0).max(right.getOrElse(index, 0))
    }.toMap

  /** Control inputs are recursive composite-leaf indices, never names or
    * default-width reconstruction. Index inputIndex denotes the current data
    * value and is advanced through a local register chain; all other indices
    * remain the original same-composite bridge inputs. */
  private sealed trait Enable {
    def replay(values: Vector[BaseType]): Bool
    def minimumWidths: Map[Int, Int]
  }
  private final case class BooleanInput(input: Int) extends Enable {
    val minimumWidths: Map[Int, Int] = Map(input -> 1)
    def replay(values: Vector[BaseType]): Bool = values(input).asInstanceOf[Bool]
  }
  private final case class Constant(value: Boolean) extends Enable {
    val minimumWidths: Map[Int, Int] = Map.empty
    def replay(values: Vector[BaseType]): Bool = Bool(value)
  }
  private final case class Bit(input: Int, index: Int, high: Boolean) extends Enable {
    val minimumWidths: Map[Int, Int] = Map(input -> (if (high) 1 else index + 1))
    def replay(values: Vector[BaseType]): Bool = {
      val bits = values(input).asInstanceOf[BitVector]
      if (high) bits.msb else bits(index)
    }
  }
  private final case class Not(value: Enable) extends Enable {
    val minimumWidths: Map[Int, Int] = value.minimumWidths
    def replay(values: Vector[BaseType]): Bool = !value.replay(values)
  }
  private final case class Binary(operation: Int, left: Enable, right: Enable) extends Enable {
    val minimumWidths: Map[Int, Int] = mergeMinimumWidths(left.minimumWidths, right.minimumWidths)
    def replay(values: Vector[BaseType]): Bool = operation match {
      case 0 => left.replay(values) && right.replay(values)
      case 1 => left.replay(values) || right.replay(values)
      case 2 => left.replay(values) ^ right.replay(values)
    }
  }
  private final case class RegisterStep(clock: ClockDomain, initializer: Option[BigInt], enable: Option[Enable])
'''
    text = replace_once(text, old_enable, new_enable, "indexed enable model")

    proof_start = "  final class Proof private[TypedBalancedReductionBridgeReplay] (\n"
    proof_end = "  def certify(callback: UnvalidatedBalancedCallback, input: Evidence): Proof = {\n"
    proof = '''  final class Proof private[TypedBalancedReductionBridgeReplay] (
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
          (a.owner eq b.owner) && (a.kind eq b.kind) &&
            ElaborationWidthAuthority.equivalent(a.width, b.width)
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
        enable.minimumWidths.foreach { case (index, required) =>
          if (minimum(controlWidths(index)).exists(_ < required))
            fail("ENABLE-WIDTH", "replayed control lane cannot contain a certified enable bit")
        }
      }
      var replayControls = controlValues
      registers.foldLeft(value) { (prior, step) =>
        val context = step.clock.push()
        try {
          val next = ParameterizedWidth.cloneOf(prior)
          next.setAsDirectionLess()
          next.setAsReg()
          step.enable match {
            case Some(enable) => when(enable.replay(replayControls)) { next.assignFrom(prior) }
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
          replayControls = replayControls.updated(inputIndex, next)
          next
        } finally context.restore()
      }
    }
  }

  def certify(callback: UnvalidatedBalancedCallback, input: Evidence): Proof =
    certify(callback, input, Vector(input))

  def certify(callback: UnvalidatedBalancedCallback, input: Evidence,
      controls: Vector[Evidence]): Proof = {
'''
    text = replace_region(text, proof_start, proof_end, proof, "control-aware proof")

    old_open = '''    if (callback == null || input == null || callback.operands == null ||
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
'''
    new_open = '''    if (callback == null || input == null || controls == null || controls.isEmpty ||
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
'''
    text = replace_once(text, old_open, new_open, "control inventory")

    enable_start = "    def enable(expression: Expression, driver: BaseType, depth: Int = 0): Enable = {\n"
    enable_end = "    def clock(clock: ClockDomain, initialized: Boolean): Unit = {\n"
    enable = '''    def enable(expression: Expression, driver: BaseType, depth: Int = 0): Enable = {
      if (depth > 512) fail("ENABLE", "local enable graph exceeds its certified depth")
      def child(value: Expression): Enable = enable(value, driver, depth + 1)
      def admitted(value: BaseType): Int = {
        val found = identities.get(value)
        if (found == null) -1 else found.intValue()
      }
      expression match {
        case value: Bool if value eq driver => BooleanInput(inputIndex)
        case value: Bool if admitted(value) >= 0 => BooleanInput(admitted(value))
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
          val index = if (access.source eq driver) inputIndex else admitted(access.source)
          if (index < 0)
            fail("ENABLE", "enable bit source is not the current data path or an admitted composite input")
          val evidence = controls(index)
          if ((evidence.kind ne TypeBits) && (evidence.kind ne TypeUInt) && (evidence.kind ne TypeSInt))
            fail("ENABLE", "enable bit source must remain a native bit vector")
          val high = NativeWidthProvenance.isHighBit(access)
          if (access.bitId < 0 || (!high && BigInt(access.bitId) >= evidence.width.minimum))
            fail("ENABLE-WIDTH", "enable index is outside the smallest certified control width")
          Bit(index, if (high) 0 else access.bitId, high)
        case operator: Operator.Bool.Not => Not(child(operator.source))
        case operator: Operator.Bool.And => Binary(0, child(operator.left), child(operator.right))
        case operator: Operator.Bool.Or => Binary(1, child(operator.left), child(operator.right))
        case operator: Operator.Bool.Xor => Binary(2, child(operator.left), child(operator.right))
        case _ => fail("ENABLE", "enable must be a closed Bool predicate of the current path and admitted composite inputs")
      }
    }

    def clock(clock: ClockDomain, initialized: Boolean): Unit = {
'''
    text = replace_region(text, enable_start, enable_end, enable, "same-composite enable parser")

    text = replace_once(text,
        '''    val proof = new Proof(result, input.width, input, observation,
      registers.reverse.toVector, minimumInitializerWidth, localGuards.toVector)
''', '''    val proof = new Proof(result, input.width, input, controls, inputIndex, observation,
      registers.reverse.toVector, minimumInitializerWidth, localGuards.toVector)
''', "control-aware proof construction")
    BRIDGE.write_text(text)


def patch_composite() -> None:
    text = COMPOSITE.read_text()
    text = replace_once(text,
        '''    val registerCount: Int = leaves.head.registerCount
''', '''    val registerCount: Int = leaves.head.registerCount
    val hasLocalEnables: Boolean = leaves.exists(_.hasLocalEnables)
''', "composite local-enable evidence")

    replay_start = "    def replay(value: Data): Data = {\n"
    replay_end = "  private def certifyBridge(callback: UnvalidatedBalancedCallback, input: Shape): BridgeProof = {\n"
    replay = '''    def replay(value: Data): Data = {
      replayWithWidths(value, input.widths)
    }
    def replayWithWidths(value: Data,
        widths: Vector[ElaborationIntegerExpression]): Data =
      replayWithWidthsWhen(value, widths, None)
    def replayWithWidths(value: Data, widths: Vector[ElaborationIntegerExpression],
        active: ElaborationBooleanExpression): Data = {
      if (active == null) fail("BRIDGE-ACTIVE", "bridge template activity must retain typed COUNT authority")
      replayWithWidthsWhen(value, widths, Some(active))
    }
    private def replayWithWidthsWhen(value: Data,
        widths: Vector[ElaborationIntegerExpression],
        active: Option[ElaborationBooleanExpression]): Data = {
      validateFreshness()
      if (Component.current ne input.evidence.head.owner)
        fail("OWNER", "composite bridge replay requires the certified component")
      input.requireReplacementWithWidths(value, widths)
      if (leaves.forall(_.registerCount == 0)) value
      else {
        val result = cloneShape(nativeResult, widths)
        val controls = value.flatten.toVector
        result.flatten.toVector.zip(controls).zip(leaves).zip(widths).foreach {
          case (((target, source), proof), width) =>
            val replayed = active match {
              case Some(condition) => proof.replayWithWidth(source, width, controls, widths, condition)
              case None => proof.replayWithWidth(source, width, controls, widths)
            }
            target.assignFrom(replayed)
        }
        result
      }
    }
  }

  private def certifyBridge(callback: UnvalidatedBalancedCallback, input: Shape): BridgeProof = {
'''
    text = replace_region(text, replay_start, replay_end, replay, "composite control replay")

    certify_start = '''    input.requireValue(callback.operands.head, replacement = false)
'''
    certify_end = '''    proof
  }

  final class Stage private[TypedBalancedReductionCompositeReplay] (
'''
    certify = '''    input.requireValue(callback.operands.head, replacement = false)
    if (!equivalentLayout(input.expected, layout(callback.result)) || callback.result.flattenLocalName.toVector != input.paths)
      fail("BRIDGE-SHAPE", "bridge must preserve all recursive fields and Vec dimensions")
    val observation = TypedBalancedReductionClosedGraph.observe(callback)
    val usedDeclarations = new IdentityHashMap[BaseType, java.lang.Boolean]()
    val usedAssignments = new IdentityHashMap[AssignmentStatement, java.lang.Boolean]()
    val inputLeaves = new IdentityHashMap[BaseType, java.lang.Integer]()
    input.evidence.zipWithIndex.foreach { case (evidence, index) =>
      if (inputLeaves.put(evidence.value, java.lang.Integer.valueOf(index)) != null)
        fail("BRIDGE-INPUT-ALIAS", "recursive composite inputs cannot share scalar identities")
    }
    val owner = input.evidence.head.owner
    val results = callback.result.flatten.toVector
    val leaves = results.zip(input.evidence).map { case (result, evidence) =>
      val dataVisited = new IdentityHashMap[Expression, java.lang.Boolean]()
      val controlVisited = new IdentityHashMap[Expression, java.lang.Boolean]()
      val declarations = new IdentityHashMap[BaseType, java.lang.Boolean]()
      val assignments = new IdentityHashMap[AssignmentStatement, java.lang.Boolean]()
      def mark(value: BaseType): Vector[AssignmentStatement] = {
        if (!callback.declarations.exists(_ eq value))
          fail("BRIDGE-CROSS-FIELD", "bridge data and local controls must remain inside the callback graph")
        declarations.put(value, java.lang.Boolean.TRUE)
        usedDeclarations.put(value, java.lang.Boolean.TRUE)
        val found = callback.assignments.filter(_.finalTarget eq value)
        found.foreach { assignment =>
          assignments.put(assignment, java.lang.Boolean.TRUE)
          usedAssignments.put(assignment, java.lang.Boolean.TRUE)
        }
        found
      }
      def condition(assignment: AssignmentStatement): Unit = {
        if (assignment.parentScope ne owner.dslBody) {
          val parent = assignment.parentScope.parentStatement
          parent match {
            case statement: WhenStatement => walkControl(statement.cond)
            case _ => fail("BRIDGE-CONTROL-SCOPE", "conditional bridge assignment lost its native When owner")
          }
        }
      }
      def walkControl(value: Expression): Unit = {
        if (controlVisited.put(value, java.lang.Boolean.TRUE) != null) return
        value match {
          case leaf: BaseType if inputLeaves.containsKey(leaf) =>
          case leaf: BaseType =>
            if (leaf.isReg)
              fail("BRIDGE-CONTROL-REGISTER", "a local enable cannot read a registered peer field")
            mark(leaf).foreach { assignment =>
              condition(assignment)
              walkControl(assignment.source)
            }
          case expression => expression.foreachExpression(walkControl)
        }
      }
      def walkData(value: Expression): Unit = {
        if (dataVisited.put(value, java.lang.Boolean.TRUE) != null) return
        value match {
          case leaf: BaseType if leaf eq evidence.value =>
          case leaf: BaseType if inputLeaves.containsKey(leaf) =>
            fail("BRIDGE-CROSS-FIELD", "bridge data paths must preserve their exact corresponding input field")
          case leaf: BaseType =>
            mark(leaf).foreach { assignment =>
              condition(assignment)
              walkData(assignment.source)
            }
          case expression => expression.foreachExpression(walkData)
        }
      }
      walkData(result)
      val controls = input.evidence.map(_.value)
      val partition = UnvalidatedBalancedCallback(callback.ordinal, controls, result,
        callback.declarations.filter(declarations.containsKey), callback.assignments.filter(assignments.containsKey))
      TypedBalancedReductionBridgeReplay.certify(partition, evidence, input.evidence)
    }
    if (usedDeclarations.size != callback.declarations.size || usedAssignments.size != callback.assignments.size)
      fail("BRIDGE-EFFECT", "bridge contains effects outside its leaf data/control paths")
    if (leaves.isEmpty || leaves.exists(_.registerCount != leaves.head.registerCount))
      fail("BRIDGE-LATENCY", "all composite leaves must advance in lockstep through equal register counts")
    val clocks = callback.declarations.filter(_.isReg).map(_.clockDomain)
    if (clocks.nonEmpty && clocks.exists(_ ne clocks.head))
      fail("BRIDGE-CLOCK", "composite registers must share their exact native clock domain")
    val proof = new BridgeProof(callback.result, input, leaves, observation)
    proof.validateFreshness()
    proof
  }

  final class Stage private[TypedBalancedReductionCompositeReplay] (
'''
    text = replace_region(text, certify_start, certify_end, certify, "composite control partition")
    COMPOSITE.write_text(text)


def write_test() -> None:
    require(not TEST.exists(), "local-enable test already exists")
    TEST.write_text(r'''package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

final case class BalancedLocalEnableRecord(uw: HdlInt, sw: HdlInt, bw: HdlInt) extends Bundle {
  val unsigned = UInt(uw bits)
  val signed = SInt(sw bits)
  val bitsValue = Bits(bw bits)
  val valid = Bool()
}

private object BalancedLocalEnableRecord {
  def combine(a: BalancedLocalEnableRecord, b: BalancedLocalEnableRecord): BalancedLocalEnableRecord = {
    val result = cloneOf(a)
    result.unsigned := a.unsigned + b.unsigned
    result.signed := a.signed + b.signed
    result.bitsValue := a.bitsValue ^ b.bitsValue
    result.valid := a.valid | b.valid
    result
  }

  def register(value: BalancedLocalEnableRecord): BalancedLocalEnableRecord = {
    val result = cloneOf(value)
    result.unsigned := RegNextWhen(value.unsigned, value.valid) init U(3)
    result.signed := RegNextWhen(value.signed, value.bitsValue(0)) init S(-2)
    result.bitsValue := RegNextWhen(value.bitsValue, value.signed.msb) init B(1)
    result.valid := RegNextWhen(value.valid, value.unsigned(0) ^ value.bitsValue.msb) init True
    result
  }
}

private final class BalancedLocalEnablePublic(width: HdlInt, count: HdlInt,
    moduleName: String, asynchronousLow: Boolean) extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val values = in(Vec(BalancedLocalEnableRecord(width, width, width), count)).setName("values")
  val result = out(BalancedLocalEnableRecord(width, width, width)).setName("result")
  val domain = ClockDomain(clock = clk, reset = reset, config = ClockDomainConfig(
    clockEdge = if (asynchronousLow) FALLING else RISING,
    resetKind = if (asynchronousLow) ASYNC else SYNC,
    resetActiveLevel = if (asynchronousLow) LOW else HIGH))
  val area = new ClockingArea(domain) {
    val reduced = values.reduceBalancedTree(
      (a: BalancedLocalEnableRecord, b: BalancedLocalEnableRecord) =>
        BalancedLocalEnableRecord.combine(a, b),
      (value: BalancedLocalEnableRecord, _: Int) => BalancedLocalEnableRecord.register(value))
    result := reduced
  }
}

class TypedBalancedReductionCompositeLocalEnableTests extends AnyFunSuite {
  private def native[T <: Data]: ElabBalancedReduction.Native[T] =
    (values, operation, bridge) => new TraversableOnceAnyPimped[T](values).reduceBalancedTree(operation, bridge)

  private def details(error: Throwable): String =
    if (error == null) "" else Option(error.getMessage).getOrElse("") + "\n" + details(error.getCause)

  test("same-composite cross-field controls replay with field-local data and exact latency") {
    var certificate: TypedBalancedReductionCompositeReplay.Certificate[BalancedLocalEnableRecord] = null
    SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-local-enable-certificate-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val width = HdlInt.param("WIDTH", 5, 3, 16)
      val values = Vec(BalancedLocalEnableRecord(width, width, width), HdlInt.param("COUNT", 1, 1, 5))
      values.vec.foreach(_.flatten.foreach {
        case value: UInt => value := 0
        case value: SInt => value := 0
        case value: Bits => value := 0
        case value: Bool => value := False
      })
      certificate = TypedBalancedReductionCompositeReplay.capture(values,
        (a: BalancedLocalEnableRecord, b: BalancedLocalEnableRecord) =>
          BalancedLocalEnableRecord.combine(a, b),
        (value: BalancedLocalEnableRecord, _: Int) => BalancedLocalEnableRecord.register(value),
        native[BalancedLocalEnableRecord])
      for (count <- 1 to 5) certificate.replay(values.vec.take(count).toVector)
    })
    assert(certificate.stages.map(_.registerCountPerRow) == Vector(1, 1, 1))
    assert(certificate.stages.forall(_.bridges.head.hasLocalEnables))
    for (count <- 1 to 5)
      assert(certificate.latencyFor(count) == (BigInt(count) - 1).bitLength)
    certificate.captured.rows.foreach { row =>
      assert(row.bridge.declarations.count(_.isReg) == 4)
    }
    certificate.requireFreshness()
  }

  test("public parameterized publication retains cross-field controls in two reset profiles") {
    for ((name, asynchronousLow) <- Vector("sync_high" -> false, "async_low" -> true)) {
      val directory = Files.createTempDirectory("balanced-local-enable-public-" + name + "-")
      val file = "BalancedLocalEnable_" + name + ".v"
      val config = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false,
        headerWithRepoHash = false, bitVectorWidthMax = 4096)
      config.netlistFileName = file
      MorphVerilog(config) {
        new BalancedLocalEnablePublic(HdlInt.param("WIDTH", 5, 3, 16),
          HdlInt.param("COUNT", 1, 1, 5), "BalancedLocalEnable_" + name, asynchronousLow)
      }
      val path = directory.resolve(file)
      assert(Files.isRegularFile(path))
      val rtl = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      assert(rtl.contains("parameter") && rtl.contains("COUNT") && rtl.contains("WIDTH"), rtl)
      assert(rtl.contains("generate") && rtl.contains("always"), rtl)
    }
  }

  test("external controls and registered peer controls remain rejected") {
    val error = intercept[Exception] {
      SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-local-enable-reject-").toString,
        headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
        val width = HdlInt.param("WIDTH", 5, 3, 16)
        val values = Vec(BalancedLocalEnableRecord(width, width, width), HdlInt.param("COUNT", 1, 1, 3))
        values.vec.foreach(_.flatten.foreach {
          case value: UInt => value := 0
          case value: SInt => value := 0
          case value: Bits => value := 0
          case value: Bool => value := False
        })
        val foreign = Bool(); foreign := False
        TypedBalancedReductionCompositeReplay.capture(values,
          (a: BalancedLocalEnableRecord, b: BalancedLocalEnableRecord) =>
            BalancedLocalEnableRecord.combine(a, b),
          (value: BalancedLocalEnableRecord, _: Int) => {
            val result = BalancedLocalEnableRecord.register(value)
            result.valid := RegNextWhen(value.valid, foreign) init False
            result
          }, native[BalancedLocalEnableRecord])
      })
    }
    assert(details(error).contains("BRIDGE") || details(error).contains("GRAPH-EXTERNAL-READ"), details(error))
  }
}
''')


def main() -> None:
    require(blob(BRIDGE) == "23102803f26c86298dc97d7d20c75e0ac0faa0ec",
            "scalar bridge baseline changed")
    require(blob(COMPOSITE) == "637b94c85aa2843986e594ecb18d1fcf9a5f0165",
            "composite replay baseline changed")
    patch_bridge()
    patch_composite()
    write_test()
    print("59i same-composite local-enable development patch applied")


if __name__ == "__main__":
    main()
