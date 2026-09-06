package spinal.core.internals

import java.util.IdentityHashMap
import java.util.regex.{Matcher, Pattern}
import scala.collection.mutable.ArrayBuffer
import spinal.core._

/** Generic balanced topology around certified, natively emitted Data bodies.
  * No operator syntax, register process or reset/enable rule is emitted here.
  */
object TypedBalancedReductionBackend {
  private object StorageKey
  private final class Storage {
    val records = ArrayBuffer.empty[Record]
    val recursiveTransport = new IdentityHashMap[Vec[_], java.lang.Boolean]()
  }
  private final case class Body(block: ParameterizedStructuralBlock,
      left: Data, right: Option[Data], result: Data,
      observations: Vector[TypedBalancedReductionClosedGraph.Observation])
  private final case class Stage(geometry: TypedBalancedReductionStage, pair: Body, tail: Body)
  private final case class ReplayStage(geometry: TypedBalancedReductionStage,
      operation: (Data, Data) => Data, bridge: Data => Data)
  private final case class ReplayCapture(captured: UnvalidatedBalancedReduction[Data],
      stages: Vector[ReplayStage], requireFreshness: () => Unit)
  private final case class Record(vector: Vec[Data], shape: ParameterizedVecShape,
      input: Bits, output: Data, plan: TypedBalancedReductionPlan,
      stages: Vector[Stage], ordinal: Int,
      outputObservation: TypedBalancedReductionClosedGraph.Observation) {
    var handedOff = false
  }

  private def fail(code: String, detail: String): Nothing =
    throw new IllegalArgumentException("MORPH-REDUCE-BALANCED-PUBLICATION-" + code + ": " + detail)
  private def records(component: Component): Vector[Record] =
    component.userCache.get(StorageKey).map(_.asInstanceOf[Storage].records.toVector).getOrElse(Vector.empty)

  /** Read-only ownership evidence. Only private certified-template creation
    * can enter this registry; ordinary application Vecs cannot opt out of
    * native Vec publication and lineage checks.
    */
  private[internals] def ownsRecursiveTransport(vector: Vec[_]): Boolean =
    vector != null && vector.component != null &&
      vector.component.userCache.get(StorageKey)
        .exists(_.asInstanceOf[Storage].recursiveTransport.containsKey(vector))

  private def claimRecursiveTransport(value: Data): Unit = {
    val owner = Component.current
    val storage = owner.userCache(StorageKey).asInstanceOf[Storage]
    val vectors = ParameterizedVecElementLayout.nestedVectors(value)
    if (vectors.exists(vector => (vector.component ne owner) ||
        vector.asInstanceOf[Data].flatten.exists(_.isIo)))
      fail("RECURSIVE-TRANSPORT-OWNER", "certified transport can own only exact internal replay vectors")
    vectors.foreach(vector => storage.recursiveTransport.put(vector, java.lang.Boolean.TRUE))
  }

  private def validateNativeAnchors(body: Body): Unit = {
    (Vector(body.left) ++ body.right.toVector).flatMap(_.flatten).foreach { value =>
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
      // Callback code admission precedes its first execution. Graph sampling
      // is not used to infer the absence of host state or external effects.
      TypedBalancedReductionCallbackPolicy.requireSupported(op, bridge, vector.vec)
      build(vector.asInstanceOf[Vec[Data]],
        op.asInstanceOf[(Data, Data) => Data],
        bridge.asInstanceOf[(Data, Int) => Data],
        native.asInstanceOf[ElabBalancedReduction.Native[Data]]).asInstanceOf[T]
    }
  }

  private def preserve(value: BaseType, name: String): BaseType = {
    value.setName(name)
    value.setAsVital(); value.dontSimplifyIt()
    if (value.isReg) value.addTag(noBackendSyncMerge) else value.noBackendCombMerge()
    value
  }

  private def driveZero(value: Data): Unit = value match {
    case scalar: Bool => scalar := False
    case scalar: Bits => scalar := 0
    case scalar: UInt => scalar := 0
    case scalar: SInt => scalar := 0
    case _: MultiData => value.flatten.foreach(driveZero)
    case _ => fail("SHAPE", "unsupported native anchor")
  }

  private def capture(vector: Vec[Data], op: (Data, Data) => Data,
      bridge: (Data, Int) => Data, native: ElabBalancedReduction.Native[Data]): ReplayCapture = {
    if (vector.vec.head.isInstanceOf[BaseType]) {
      val certificate = TypedBalancedReductionStageReplay.capture(
        vector.asInstanceOf[Vec[BaseType]],
        op.asInstanceOf[(BaseType, BaseType) => BaseType],
        bridge.asInstanceOf[(BaseType, Int) => BaseType],
        native.asInstanceOf[ElabBalancedReduction.Native[BaseType]])
      ReplayCapture(certificate.captured.asInstanceOf[UnvalidatedBalancedReduction[Data]],
        certificate.stages.map(stage => ReplayStage(stage.geometry,
          (left, right) => stage.operators.head.replay(left.asInstanceOf[BaseType], right.asInstanceOf[BaseType]),
          value => stage.bridges.head.replay(value.asInstanceOf[BaseType]))),
        () => certificate.requireFreshness())
    } else {
      val certificate = TypedBalancedReductionCompositeReplay.capture(vector, op, bridge, native)
      ReplayCapture(certificate.captured, certificate.stages.map(stage => ReplayStage(stage.geometry,
        (left, right) => stage.operators.head.replay(left, right),
        value => stage.bridges.head.replay(value))), () => certificate.requireFreshness())
    }
  }

  private def build(vector: Vec[Data], op: (Data, Data) => Data,
      bridge: (Data, Int) => Data,
      native: ElabBalancedReduction.Native[Data]): Data = {
    val owner = Component.current
    val storage = owner.userCache.getOrElseUpdate(StorageKey, new Storage).asInstanceOf[Storage]
    val ordinal = storage.records.size + 1
    val prefix = s"morphhdl_balanced_$ordinal"
    val certificate = capture(vector, op, bridge, native)
    val shape = certificate.captured.shape
    val plan = certificate.captured.plan
    def fresh(name: String): Data = {
      val result = ParameterizedWidth.cloneOf(vector.vec.head)
      result.setAsDirectionLess()
      result.setName(name)
      // Explicit leaf anchors preserve exact identity and avoid flatten-name
      // collisions between distinct recursive field paths.
      result.flatten.toVector.zipWithIndex.foreach { case (leaf, index) =>
        preserve(leaf, if (result.isInstanceOf[BaseType]) name else name + "_leaf_" + index)
      }
      claimRecursiveTransport(result)
      result
    }
    def template(stage: ReplayStage, pair: Boolean): Body = {
      var left: Data = null
      var right: Option[Data] = None
      var result: Data = null
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
        val operated = if (pair) stage.operation(left, right.get) else left
        claimRecursiveTransport(operated)
        val bridged = stage.bridge(operated)
        claimRecursiveTransport(bridged)
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
    var output: Data = null
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
      val width = if (record.shape.elementLeaves.size == 1 && !record.shape.elementLayout.hasNestedVectors)
        record.shape.elementLeaves.head.width.verilog else record.shape.elementWidthVerilog
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
      val allocatedNames = scala.collection.mutable.HashSet.empty[String] ++ identifiers ++ reserved(prefix)
      def allocateLabel(base: String): String = {
        var result = base
        var ordinal = 0
        while (allocatedNames.contains(result)) {
          ordinal += 1
          result = base + "_" + ordinal
        }
        allocatedNames += result
        result
      }
      val blocks = record.stages.flatMap(s => Vector(s.pair.block, s.tail.block))
      val (remaining, bodies) = ParameterizedVerilogStructural.extractNativeTemplates(
        component, blocks, current, pc, canonicalOf)
      def slice(source: String, index: String): String = s"$source[(($index) * ($width)) +: ($width)]"
      def leafSlice(source: String, index: String, leafIndex: Int): String = {
        if (record.shape.elementLeaves.size == 1 && !record.shape.elementLayout.hasNestedVectors) slice(source, index)
        else {
          val offset = record.shape.elementLeaves.take(leafIndex).map(leaf => s"(${leaf.width.verilog})")
          val offsetText = if (record.shape.elementLayout.hasNestedVectors)
            record.shape.elementLayout.leaves(leafIndex).offset(_.verilog)
            else if (offset.isEmpty) "0" else offset.mkString(" + ")
          val leafWidth = record.shape.elementLeaves(leafIndex).width.verilog
          s"$source[((($index) * ($width)) + ($offsetText)) +: ($leafWidth)]"
        }
      }
      def connect(body: String, value: Data, source: String, index: String,
          moduleScope: Boolean = false): String =
        value.flatten.toVector.zipWithIndex.foldLeft(body) { case (text, (leaf, leafIndex)) =>
          val connected = replaceDriver(text, leaf, leafSlice(source, index, leafIndex))
          val active = record.shape.elementLayout.leaves(leafIndex).activeCondition(_.verilog)
          if (active == "1") connected
          else {
            val assignment = ("(?m)^[ \\t]*assign[ \\t]+" + Pattern.quote(leaf.getName()) +
              "[ \\t]*=[^;]+;").r
            if (assignment.findAllMatchIn(connected).size != 1)
              fail("ANCHOR", "recursive leaf must retain one exact driver")
            val presentLabel = allocateLabel(leaf.getName() + "_present")
            val absentLabel = allocateLabel(leaf.getName() + "_absent")
            assignment.replaceAllIn(connected, m => Matcher.quoteReplacement(
              (if (moduleScope) "  generate\n" else "") +
                s"  if ($active) begin : $presentLabel\n${m.matched}\n" +
                s"  end else begin : $absentLabel\n    assign ${leaf.getName()} = 0;\n  end" +
                (if (moduleScope) "\n  endgenerate" else "")))
          }
        }
      def packed(value: Data): String = {
        val names = value.flatten.toVector.reverse.map(_.getName())
        if (names.size == 1) names.head else names.mkString("{", ", ", "}")
      }
      def publishResult(target: String, index: String, value: Data): Vector[String] = {
        if (!record.shape.elementLayout.hasNestedVectors)
          Vector(s"        assign ${slice(target, index)} = ${packed(value)};")
        else value.flatten.toVector.zipWithIndex.flatMap { case (leaf, leafIndex) =>
          val line = s"        assign ${leafSlice(target, index, leafIndex)} = ${leaf.getName()};"
          val active = record.shape.elementLayout.leaves(leafIndex).activeCondition(_.verilog)
          if (active == "1") Vector(line)
          else Vector(s"        if ($active) begin : ${allocateLabel(leaf.getName() + "_published")}", line, "        end")
        }
      }
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
        var pairBody = connect(bodies(2 * index), stage.pair.left, before, "2 * " + genvar)
        pairBody = connect(pairBody, stage.pair.right.get, before, "2 * " + genvar + " + 1")
        val tailBody = connect(bodies(2 * index + 1), stage.tail.left, before, s"($inputs) - 1")
        lines += s"  wire [(($width) * ($outputs))-1:0] $after;"
        lines += s"  genvar $genvar;"
        lines += "  generate"
        lines += s"    if (${geometry.active.expression.verilog}) begin : ${prefix}_active_$index"
        lines += s"      for ($genvar = 0; $genvar < ($pairs); $genvar = $genvar + 1) begin : pairs"
        lines += indent(pairBody, 8)
        lines ++= publishResult(after, genvar, stage.pair.result)
        lines += "      end"
        lines += s"      if (${geometry.hasOddTail.expression.verilog}) begin : tail"
        lines += indent(tailBody, 8)
        lines ++= publishResult(after, pairs, stage.tail.result)
        lines += "      end"
        lines += s"    end else begin : ${prefix}_bypass_$index"
        lines += s"      assign $after = $before;"
        lines += "    end"
        lines += "  endgenerate"
      }
      val last = prefix + "_stage_" + record.stages.size
      val updated = connect(remaining, record.output, last, "0", moduleScope = true)
      val end = updated.lastIndexOf("endmodule")
      if (end < 0) fail("MODULE", "native module terminator missing")
      updated.substring(0, end) + lines.mkString("\n") + "\n" + updated.substring(end)
    }
  }
}
