package spinal.core.internals

import java.util.regex.{Matcher, Pattern}
import scala.collection.mutable.ArrayBuffer
import spinal.core._

/** Generic balanced topology around certified, natively emitted scalar bodies.
  * No operator syntax, register process or reset/enable rule is emitted here.
  */
object TypedBalancedReductionBackend {
  private object StorageKey
  private final class Storage { val records = ArrayBuffer.empty[Record] }
  private final case class Body(block: ParameterizedStructuralBlock,
      left: BaseType, right: Option[BaseType], result: BaseType,
      observations: Vector[TypedBalancedReductionClosedGraph.Observation])
  private final case class Stage(geometry: TypedBalancedReductionStage, pair: Body, tail: Body)
  private final case class Record(vector: Vec[BaseType], shape: ParameterizedVecShape,
      input: Bits, output: BaseType, plan: TypedBalancedReductionPlan,
      stages: Vector[Stage], ordinal: Int,
      outputObservation: TypedBalancedReductionClosedGraph.Observation) {
    var handedOff = false
  }

  private def fail(code: String, detail: String): Nothing =
    throw new IllegalArgumentException("MORPH-REDUCE-BALANCED-PUBLICATION-" + code + ": " + detail)
  private def records(component: Component): Vector[Record] =
    component.userCache.get(StorageKey).map(_.asInstanceOf[Storage].records.toVector).getOrElse(Vector.empty)

  private def validateNativeAnchors(body: Body): Unit = {
    (Vector(body.left) ++ body.right.toVector).foreach { value =>
      if (!value.isNamed || !value.dontSimplify || !value.isComb ||
          !value.hasTag(noBackendCombMerge))
        fail("ANCHOR-POLICY", "native input wires must remain named and protected from propagation/merging")
      val assignments = ArrayBuffer.empty[Statement]
      value.foreachStatements(assignments += _)
      if (assignments.size != 1 || !assignments.head.isInstanceOf[DataAssignmentStatement] ||
          (assignments.head.asInstanceOf[DataAssignmentStatement].target ne value))
        fail("ANCHOR-DRIVER", "native input anchor must retain one exact full driver")
    }
  }

  /** Scoped inside the native elaboration closure, including native retries. */
  def elaborate[A](body: => A): A = ElabBalancedReduction.withBackend(Backend)(body)

  /** Check the actual replay templates after elaboration/user transformations,
    * before native naming, pruning and normalization can change their graph.
    */
  def install(phases: ArrayBuffer[Phase]): Unit = {
    val boundary = phases.indexWhere(_.isInstanceOf[PhaseNameNodesByReflection])
    require(boundary >= 0, "native pre-normalization graph handoff is missing")
    phases.insert(boundary, new PhaseMisc {
      override def impl(pc: PhaseContext): Unit = pc.walkComponents { owner =>
        records(owner).foreach { record =>
          val bodies = record.stages.flatMap(s => Vector(s.pair, s.tail))
          bodies.foreach(validateNativeAnchors)
          bodies.flatMap(_.observations).foreach(_.requireUnchanged())
          record.outputObservation.requireUnchanged()
          record.handedOff = true
        }
      }
    })
  }

  private object Backend extends ElabBalancedReduction.Backend {
    override def reduce[T <: Data](vector: Vec[T], op: (T, T) => T,
        bridge: (T, Int) => T, native: ElabBalancedReduction.Native[T]): T = {
      if (!ParameterizedStructure.captureEnabled)
        fail("MODE", "symbolic reduction requires parameterized native elaboration")
      val plan = TypedBalancedReductionPlan.forVec(vector).get
      if (plan.count.expression.maximum == 1) return vector(0)
      if (ParameterizedStructure.currentOwner(plan.count, "balanced publication").captureId != 0L)
        fail("NESTED-OWNER", "balanced stage publication currently requires the native component scope")
      if (vector.vec.exists(!_.isInstanceOf[BaseType]))
        fail("SHAPE", "only certified scalar element graphs are admitted")
      // Callback code admission precedes its first execution. Graph sampling
      // is not used to infer the absence of host state or external effects.
      TypedBalancedReductionCallbackPolicy.requireSupported(op, bridge)
      build(vector.asInstanceOf[Vec[BaseType]],
        op.asInstanceOf[(BaseType, BaseType) => BaseType],
        bridge.asInstanceOf[(BaseType, Int) => BaseType],
        native.asInstanceOf[ElabBalancedReduction.Native[BaseType]]).asInstanceOf[T]
    }
  }

  private def preserve(value: BaseType, name: String): BaseType = {
    value.setName(name)
    value.setAsVital(); value.dontSimplifyIt()
    if (value.isReg) value.addTag(noBackendSyncMerge) else value.noBackendCombMerge()
    value
  }

  private def driveZero(value: BaseType): Unit = value match {
    case scalar: Bool => scalar := False
    case scalar: Bits => scalar := 0
    case scalar: UInt => scalar := 0
    case scalar: SInt => scalar := 0
    case _ => fail("SHAPE", "unsupported scalar anchor")
  }

  private def build(vector: Vec[BaseType], op: (BaseType, BaseType) => BaseType,
      bridge: (BaseType, Int) => BaseType,
      native: ElabBalancedReduction.Native[BaseType]): BaseType = {
    val owner = Component.current
    val storage = owner.userCache.getOrElseUpdate(StorageKey, new Storage).asInstanceOf[Storage]
    val ordinal = storage.records.size + 1
    val prefix = s"morphhdl_balanced_$ordinal"
    val certificate = TypedBalancedReductionStageReplay.capture(vector, op, bridge, native)
    val shape = certificate.captured.shape
    val plan = certificate.captured.plan
    def fresh(name: String): BaseType = {
      val result = ParameterizedWidth.cloneOf(vector.vec.head)
      result.setAsDirectionLess()
      preserve(result, name)
    }
    def template(stage: TypedBalancedReductionStageReplay.Stage, pair: Boolean): Body = {
      var left: BaseType = null
      var right: Option[BaseType] = None
      var result: BaseType = null
      val label = prefix + "_l" + stage.geometry.level + (if (pair) "_pair" else "_tail")
      val anchors = ParameterizedStructure.captureBlock(owner, None) {
        left = fresh(label + "_left")
        driveZero(left)
        if (pair) {
          val other = fresh(label + "_right")
          driveZero(other)
          right = Some(other)
        }
      }
      val block = ParameterizedStructure.captureBlock(owner, None) {
        val operated = if (pair) stage.operators.head.replay(left, right.get) else left
        val bridged = stage.bridges.head.replay(operated)
        result = fresh(label + "_result")
        result.assignFrom(bridged)
      }
      val assignments = block.statements.collect { case a: AssignmentStatement => a }
      val observation = TypedBalancedReductionClosedGraph.observe(UnvalidatedBalancedCallback(
        0, Vector(left) ++ right.toVector, result, block.declarations, assignments))
      val anchorObservation = TypedBalancedReductionClosedGraph.observe(UnvalidatedBalancedCallback(
        0, Vector(vector.vec.head), Vec(Vector(left) ++ right.toVector), anchors.declarations,
        anchors.statements.collect { case a: AssignmentStatement => a }))
      block.append(anchors)
      Body(block, left, right, result, Vector(observation, anchorObservation))
    }
    val stages = certificate.stages.map(s => Stage(s.geometry, template(s, true), template(s, false)))
    certificate.requireFreshness()
    // Probe hardware has discharged the pre-normalization obligations and is
    // never published. Only the distinct replay templates enter native phases.
    certificate.captured.rows.flatMap(r => r.operator.toVector :+ r.bridge)
      .flatMap(_.assignments).foreach(_.removeStatement())
    certificate.captured.rows.flatMap(r => r.operator.toVector :+ r.bridge)
      .flatMap(_.declarations).foreach(_.removeStatement())
    val input = vector.asBits
    preserve(input, prefix + "_input")
    var output: BaseType = null
    val outputBlock = ParameterizedStructure.captureBlock(owner, None) {
      output = fresh(prefix + "_result")
      driveZero(output)
    }
    val outputObservation = TypedBalancedReductionClosedGraph.observe(UnvalidatedBalancedCallback(
      0, Vector(vector.vec.head), output, outputBlock.declarations,
      outputBlock.statements.collect { case a: AssignmentStatement => a }))
    storage.records += Record(vector, shape, input, output, plan, stages, ordinal, outputObservation)
    output
  }

  private def replaceDriver(body: String, value: BaseType, rhs: String): String = {
    val pattern = ("(?m)^([ \\t]*assign[ \\t]+" + Pattern.quote(value.getName()) +
      "[ \\t]*=[ \\t]*)[^;]+;").r
    if (pattern.findAllMatchIn(body).size != 1)
      fail("ANCHOR", "native scalar input/result must retain exactly one full assignment")
    pattern.replaceAllIn(body, m => Matcher.quoteReplacement(m.group(1) + rhs + ";"))
  }
  private def indent(text: String, spaces: Int): String =
    text.split("\n", -1).map(" " * spaces + _).mkString("\n")

  private[internals] def rewrite(component: Component, verilog: String,
      pc: PhaseContext, canonicalOf: Component => Component): String = {
    records(component).foldLeft(verilog) { (current, record) =>
      if (ParameterizedVec.shapeOf(record.vector).forall(_ ne record.shape))
        fail("SHAPE-CHANGED", "the captured Vec no longer owns its original shape")
      if (!record.handedOff)
        fail("HANDOFF", "native template graph was not validated before normalization")
      record.stages.flatMap(s => Vector(s.pair, s.tail)).foreach(validateNativeAnchors)
      val width = record.shape.elementLeaves.head.width.verilog
      val base = s"morphhdl_balanced_${record.ordinal}"
      val identifiers = "[A-Za-z_][A-Za-z0-9_$]*".r.findAllIn(current).toSet
      def reserved(prefix: String): Vector[String] =
        (0 to record.stages.size).map(i => prefix + "_stage_" + i).toVector ++
          record.stages.indices.flatMap(i => Vector(prefix + "_i_" + i,
            prefix + "_active_" + i, prefix + "_bypass_" + i))
      var suffix = 0
      var prefix = base
      while (reserved(prefix).exists(identifiers)) {
        suffix += 1
        prefix = base + "_" + suffix
      }
      val blocks = record.stages.flatMap(s => Vector(s.pair.block, s.tail.block))
      val (remaining, bodies) = ParameterizedVerilogStructural.extractNativeTemplates(
        component, blocks, current, pc, canonicalOf)
      def slice(source: String, index: String): String = s"$source[(($index) * ($width)) +: ($width)]"
      val lines = ArrayBuffer.empty[String]
      val first = prefix + "_stage_0"
      lines += s"  wire [(($width) * (${record.plan.count.expression.verilog}))-1:0] $first;"
      lines += s"  assign $first = ${record.input.getName()};"
      record.stages.zipWithIndex.foreach { case (stage, index) =>
        val before = prefix + "_stage_" + index
        val after = prefix + "_stage_" + (index + 1)
        val genvar = prefix + "_i_" + index
        val geometry = stage.geometry
        val pairs = geometry.pairCount.expression.verilog
        val inputs = geometry.inputCount.expression.verilog
        val outputs = geometry.outputCount.expression.verilog
        var pairBody = replaceDriver(bodies(2 * index), stage.pair.left, slice(before, "2 * " + genvar))
        pairBody = replaceDriver(pairBody, stage.pair.right.get, slice(before, "2 * " + genvar + " + 1"))
        val tailBody = replaceDriver(bodies(2 * index + 1), stage.tail.left, slice(before, s"($inputs) - 1"))
        lines += s"  wire [(($width) * ($outputs))-1:0] $after;"
        lines += s"  genvar $genvar;"
        lines += "  generate"
        lines += s"    if (${geometry.active.expression.verilog}) begin : ${prefix}_active_$index"
        lines += s"      for ($genvar = 0; $genvar < ($pairs); $genvar = $genvar + 1) begin : pairs"
        lines += indent(pairBody, 8)
        lines += s"        assign ${slice(after, genvar)} = ${stage.pair.result.getName()};"
        lines += "      end"
        lines += s"      if (${geometry.hasOddTail.expression.verilog}) begin : tail"
        lines += indent(tailBody, 8)
        lines += s"        assign ${slice(after, pairs)} = ${stage.tail.result.getName()};"
        lines += "      end"
        lines += s"    end else begin : ${prefix}_bypass_$index"
        lines += s"      assign $after = $before;"
        lines += "    end"
        lines += "  endgenerate"
      }
      val last = prefix + "_stage_" + record.stages.size
      val updated = replaceDriver(remaining, record.output, slice(last, "0"))
      val end = updated.lastIndexOf("endmodule")
      if (end < 0) fail("MODULE", "native module terminator missing")
      updated.substring(0, end) + lines.mkString("\n") + "\n" + updated.substring(end)
    }
  }
}
