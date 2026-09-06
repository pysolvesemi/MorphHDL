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
  private final case class Stage(geometry: TypedBalancedReductionStage,
      inputFullWidth: ElaborationIntegerExpression, inputTailWidth: ElaborationIntegerExpression,
      outputFullWidth: ElaborationIntegerExpression, outputTailWidth: ElaborationIntegerExpression,
      inputPackedWidth: ElaborationIntegerExpression, outputPackedWidth: ElaborationIntegerExpression,
      fullPairPossible: Boolean, pair: Body, partialPair: Option[Body], tail: Option[Body]) {
    def bodies: Vector[Body] = Vector(pair) ++ partialPair.toVector ++ tail.toVector
  }
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

  private def validatePackedWidths(record: Record, pc: PhaseContext): Unit = {
    record.stages.flatMap(stage => Vector(stage.inputPackedWidth, stage.outputPackedWidth)).foreach { width =>
      NativePublicationWidth.validate(width, record.vector.component, record.input,
        "balanced packed transport")
      if (width.minimum < 1 || width.maximum > BigInt(pc.config.bitVectorWidthMax))
        fail("TRANSPORT-WIDTH", "packed native stage reaches [" + width.minimum + ", " + width.maximum +
          "], outside [1, " + pc.config.bitVectorWidthMax + "] allowed by SpinalConfig.bitVectorWidthMax")
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
          validatePackedWidths(record, pc)
          val bodies = record.stages.flatMap(_.bodies)
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
    def fresh(name: String, width: ElaborationIntegerExpression): BaseType = {
      val bits = ElabInt.fromExpression(width).bits
      val result: BaseType = vector.vec.head match {
        case _: Bool =>
          if (width.minimum != 1 || width.maximum != 1) fail("BOOL-WIDTH", "Bool width must remain one")
          Bool()
        case _: Bits => Bits(bits)
        case _: UInt => UInt(bits)
        case _: SInt => SInt(bits)
        case _ => fail("SHAPE", "unsupported scalar template")
      }
      result.setAsDirectionLess()
      preserve(result, name)
    }
    def template(stage: TypedBalancedReductionStageReplay.Stage, suffix: String,
        leftWidth: ElaborationIntegerExpression,
        rightWidth: Option[ElaborationIntegerExpression],
        active: ElaborationBooleanExpression): Body = {
      var left: BaseType = null
      var right: Option[BaseType] = None
      var result: BaseType = null
      val label = prefix + "_l" + stage.geometry.level + "_" + suffix
      val anchors = ParameterizedStructure.captureBlock(owner, None) {
        left = fresh(label + "_left", leftWidth)
        driveZero(left)
        rightWidth.foreach { width =>
          val other = fresh(label + "_right", width)
          driveZero(other)
          right = Some(other)
        }
      }
      val block = ParameterizedStructure.captureBlock(owner, None) {
        val resultWidth = rightWidth.map(stage.operators.head.resultWidthFor(leftWidth, _)).getOrElse(leftWidth)
        val operated = rightWidth.map(width =>
          stage.operators.head.replayWithWidths(left, right.get, leftWidth, width)).getOrElse(left)
        val bridged = stage.bridges.head.replayWithWidth(operated, resultWidth, active)
        result = fresh(label + "_result", resultWidth)
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
    val widthSchedule = TypedBalancedReductionStageReplay.widths(plan,
      shape.elementLeaves.head.width, certificate.stages.flatMap(_.operators).headOption)
    val stages = certificate.stages.zip(widthSchedule.stages).map { case (stage, widths) =>
      def sameOnPartial(left: ElaborationIntegerExpression, right: ElaborationIntegerExpression): Boolean = {
        val difference = ElaborationWidthAuthority.subtract(left, right)
        ElaborationWidthAuthority.minimumWhen(difference, widths.partialPairActive).contains(BigInt(0)) &&
          ElaborationWidthAuthority.maximumWhen(difference, widths.partialPairActive).contains(BigInt(0))
      }
      val partial = if (!widths.fullPairPossible || !widths.partialPairPossible ||
          (sameOnPartial(widths.fullInput, widths.partialLeft) &&
            sameOnPartial(widths.fullInput, widths.partialRight))) None
        else Some(template(stage, "partial_pair", widths.partialLeft, Some(widths.partialRight),
          widths.partialPairActive))
      Stage(stage.geometry, widths.inputFull, widths.inputTail, widths.outputFull, widths.outputTail,
        widths.inputPacked, widths.outputPacked, widths.fullPairPossible,
        template(stage, if (widths.fullPairPossible) "pair" else "partial_pair",
          if (widths.fullPairPossible) widths.fullInput else widths.partialLeft,
          Some(if (widths.fullPairPossible) widths.fullInput else widths.partialRight),
          if (!widths.fullPairPossible) widths.partialPairActive
          else if (partial.nonEmpty) widths.fullPairActive else stage.geometry.active.expression), partial,
        if (widths.tailPossible) Some(template(stage, "tail", widths.inputTail, None,
          stage.geometry.hasOddTail.expression)) else None)
    }
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
      output = fresh(prefix + "_result", widthSchedule.terminal)
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
      validatePackedWidths(record, pc)
      record.stages.flatMap(_.bodies).foreach(validateNativeAnchors)
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
      val blocks = record.stages.flatMap(_.bodies.map(_.block))
      val (remaining, bodies) = ParameterizedVerilogStructural.extractNativeTemplates(
        component, blocks, current, pc, canonicalOf)
      def slice(source: String, index: String, stride: ElaborationIntegerExpression,
          width: ElaborationIntegerExpression): String =
        s"$source[(($index) * (${stride.verilog})) +: (${width.verilog})]"
      val lines = ArrayBuffer.empty[String]
      val first = prefix + "_stage_0"
      lines += s"  wire [(${record.stages.head.inputPackedWidth.verilog})-1:0] $first;"
      lines += s"  assign $first = ${record.input.getName()};"
      var bodyIndex = 0
      record.stages.zipWithIndex.foreach { case (stage, index) =>
        val before = prefix + "_stage_" + index
        val after = prefix + "_stage_" + (index + 1)
        val genvar = prefix + "_i_" + index
        val geometry = stage.geometry
        val pairs = geometry.pairCount.expression.verilog
        val inputs = geometry.inputCount.expression.verilog
        val partialCondition = s"((($inputs) % 2) == 0) && ($genvar == (($pairs) - 1))"
        var pairBody = replaceDriver(bodies(bodyIndex), stage.pair.left,
          slice(before, "2 * " + genvar, stage.inputFullWidth, stage.inputFullWidth))
        pairBody = replaceDriver(pairBody, stage.pair.right.get,
          slice(before, "2 * " + genvar + " + 1", stage.inputFullWidth,
            if (stage.fullPairPossible) stage.inputFullWidth else stage.inputTailWidth))
        bodyIndex += 1
        val partialBody = stage.partialPair.map { body =>
          var text = replaceDriver(bodies(bodyIndex), body.left,
            slice(before, "2 * " + genvar, stage.inputFullWidth, stage.inputFullWidth))
          text = replaceDriver(text, body.right.get,
            slice(before, "2 * " + genvar + " + 1", stage.inputFullWidth, stage.inputTailWidth))
          bodyIndex += 1
          text
        }
        val tailBody = stage.tail.map { body =>
          val text = replaceDriver(bodies(bodyIndex), body.left,
            slice(before, s"($inputs) - 1", stage.inputFullWidth, stage.inputTailWidth))
          bodyIndex += 1
          text
        }
        lines += s"  wire [(${stage.outputPackedWidth.verilog})-1:0] $after;"
        lines += s"  genvar $genvar;"
        lines += "  generate"
        lines += s"    if (${geometry.active.expression.verilog}) begin : ${prefix}_active_$index"
        lines += s"      for ($genvar = 0; $genvar < ($pairs); $genvar = $genvar + 1) begin : pairs"
        stage.partialPair match {
          case Some(body) =>
            lines += s"        if ($partialCondition) begin : partial_pair"
            lines += indent(partialBody.get, 10)
            lines += s"          assign ${slice(after, genvar, stage.outputFullWidth, stage.outputTailWidth)} = ${body.result.getName()};"
            lines += "        end else begin : full_pair"
            lines += indent(pairBody, 10)
            lines += s"          assign ${slice(after, genvar, stage.outputFullWidth, stage.outputFullWidth)} = ${stage.pair.result.getName()};"
            lines += "        end"
          case None =>
            lines += indent(pairBody, 8)
            lines += s"        assign ${slice(after, genvar, stage.outputFullWidth, stage.outputFullWidth)} = ${stage.pair.result.getName()};"
        }
        lines += "      end"
        stage.tail.foreach { body =>
          lines += s"      if (${geometry.hasOddTail.expression.verilog}) begin : tail"
          lines += indent(tailBody.get, 8)
          lines += s"        assign ${slice(after, pairs, stage.outputFullWidth, stage.outputTailWidth)} = ${body.result.getName()};"
          lines += "      end"
        }
        lines += s"    end else begin : ${prefix}_bypass_$index"
        lines += s"      assign $after = $before;"
        lines += "    end"
        lines += "  endgenerate"
      }
      val last = prefix + "_stage_" + record.stages.size
      val finalWidth = record.stages.last.outputTailWidth
      val updated = replaceDriver(remaining, record.output,
        slice(last, "0", record.stages.last.outputFullWidth, finalWidth))
      val end = updated.lastIndexOf("endmodule")
      if (end < 0) fail("MODULE", "native module terminator missing")
      updated.substring(0, end) + lines.mkString("\n") + "\n" + updated.substring(end)
    }
  }
}
