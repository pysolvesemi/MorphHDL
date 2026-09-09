#!/usr/bin/env python3
"""Apply the first source-bound composite-widening publication prototype."""
from __future__ import annotations

import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala"
REPLAY = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala"
TEST = ROOT / "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeWideningPublicationTests.scala"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def sha(path: Path) -> str:
    return hashlib.sha1((b"blob %d\0" % path.stat().st_size) + path.read_bytes()).hexdigest()


def replace_once(text: str, before: str, after: str, label: str) -> str:
    require(text.count(before) == 1, f"{label} exact anchor count changed: {text.count(before)}")
    return text.replace(before, after, 1)


def main() -> None:
    require(sha(BACKEND) == "c8fdce49d6f70502786bd0508df96cdb3a78ad45",
            "publication backend baseline changed")
    require(sha(REPLAY) == "637b94c85aa2843986e594ecb18d1fcf9a5f0165",
            "composite replay baseline changed")

    replay = REPLAY.read_text()
    replay = replace_once(replay,
        "  private def cloneShape(template: Data, widths: Vector[ElaborationIntegerExpression]): Data = {\n",
        "  private[internals] def cloneShape(template: Data, widths: Vector[ElaborationIntegerExpression]): Data = {\n",
        "fresh clone visibility")
    REPLAY.write_text(replay)

    backend = BACKEND.read_text()
    backend = replace_once(backend, '''  private final case class CompositeStage(geometry: TypedBalancedReductionStage,
      pair: Body, tail: Body) extends Stage {
    def bodies: Vector[Body] = Vector(pair, tail)
  }
''', '''  private final case class CompositeStage(geometry: TypedBalancedReductionStage,
      pair: Body, tail: Body) extends Stage {
    def bodies: Vector[Body] = Vector(pair, tail)
  }
  private final case class WideningCompositeStage(geometry: TypedBalancedReductionStage,
      inputFullWidths: Vector[ElaborationIntegerExpression],
      inputTailWidths: Vector[ElaborationIntegerExpression],
      outputFullWidths: Vector[ElaborationIntegerExpression],
      outputTailWidths: Vector[ElaborationIntegerExpression],
      inputPackedWidth: ElaborationIntegerExpression,
      outputPackedWidth: ElaborationIntegerExpression,
      fullPairPossible: Boolean, pair: Body,
      partialPair: Option[Body], tail: Option[Body]) extends Stage {
    def bodies: Vector[Body] = Vector(pair) ++ partialPair.toVector ++ tail.toVector
  }
''', "widening stage record")

    backend = replace_once(backend, '''  private def validatePackedWidths(record: Record, pc: PhaseContext): Unit = {
    record.stages.collect { case stage: ScalarStage => stage }
      .flatMap(stage => Vector(stage.inputPackedWidth, stage.outputPackedWidth)).foreach { width =>
      NativePublicationWidth.validate(width, record.vector.component, record.input,
        "balanced packed transport")
      if (width.minimum < 1 || width.maximum > BigInt(pc.config.bitVectorWidthMax))
        fail("TRANSPORT-WIDTH", "packed native stage reaches [" + width.minimum + ", " + width.maximum +
          "], outside [1, " + pc.config.bitVectorWidthMax + "] allowed by SpinalConfig.bitVectorWidthMax")
    }
  }
''', '''  private def validatePackedWidths(record: Record, pc: PhaseContext): Unit = {
    val packed = record.stages.flatMap {
      case stage: ScalarStage => Vector(stage.inputPackedWidth, stage.outputPackedWidth)
      case stage: WideningCompositeStage => Vector(stage.inputPackedWidth, stage.outputPackedWidth)
      case _ => Vector.empty
    }
    packed.foreach { width =>
      NativePublicationWidth.validate(width, record.vector.component, record.input,
        "balanced packed transport")
      if (width.minimum < 1 || width.maximum > BigInt(pc.config.bitVectorWidthMax))
        fail("TRANSPORT-WIDTH", "packed native stage reaches [" + width.minimum + ", " + width.maximum +
          "], outside [1, " + pc.config.bitVectorWidthMax + "] allowed by SpinalConfig.bitVectorWidthMax")
    }
  }
''', "packed widening validation")

    backend = replace_once(backend, '''    val certificate = TypedBalancedReductionCompositeReplay.capture(vector, op, bridge, native, Some(schema))
    val shape = certificate.captured.shape
    val plan = certificate.captured.plan
    def fresh(name: String): Data = {
''', '''    val certificate = TypedBalancedReductionCompositeReplay.capture(vector, op, bridge, native, Some(schema))
    val shape = certificate.captured.shape
    val plan = certificate.captured.plan
    if (certificate.hasWidening)
      return buildWideningComposite(vector, owner, lexicalOwner, storage, ordinal,
        prefix, certificate, shape, plan, schema)
    def fresh(name: String): Data = {
''', "widening build dispatch")

    widening_builder = r'''
  private def buildWideningComposite(vector: Vec[Data], owner: Component,
      lexicalOwner: ParameterizedStructuralLexicalOwner, storage: Storage,
      ordinal: Int, prefix: String,
      certificate: TypedBalancedReductionCompositeReplay.Certificate[Data],
      shape: ParameterizedVecShape, plan: TypedBalancedReductionPlan,
      schema: TypedBalancedReductionCaptureSchema): Data = {
    if (shape.elementLayout.hasNestedVectors)
      fail("WIDENING-NESTED-VEC", "changing leaf widths inside nested Vec carriers require their separate layout join")
    val widthSchedule = certificate.widthSchedule
    def fresh(name: String, widths: Vector[ElaborationIntegerExpression]): Data = {
      val result = TypedBalancedReductionCompositeReplay.cloneShape(vector.vec.head, widths)
      result.setName(name)
      result.flatten.toVector.zipWithIndex.foreach { case (leaf, index) =>
        preserve(leaf, if (result.isInstanceOf[BaseType]) name else name + "_leaf_" + index)
      }
      claimRecursiveTransport(result)
      result
    }
    def template(stage: TypedBalancedReductionCompositeReplay.Stage, suffix: String,
        leftWidths: Vector[ElaborationIntegerExpression],
        rightWidths: Option[Vector[ElaborationIntegerExpression]],
        active: ElaborationBooleanExpression): Body = {
      var left: Data = null
      var right: Option[Data] = None
      var result: Data = null
      val observations = ArrayBuffer.empty[() => Unit]
      observations += (() => schema.validateBindings())
      val label = prefix + "_l" + stage.geometry.level + "_" + suffix
      val anchors = ParameterizedStructure.captureBlock(owner, None) {
        left = fresh(label + "_left", leftWidths)
        driveZero(left)
        rightWidths.foreach { widths =>
          val other = fresh(label + "_right", widths)
          driveZero(other)
          right = Some(other)
        }
      }
      val block = ParameterizedStructure.captureBlock(owner, None) {
        val resultWidths = rightWidths
          .map(stage.operators.head.resultWidthsFor(leftWidths, _)).getOrElse(leftWidths)
        val operated = rightWidths.map(widths => stage.operators.head.replayWithWidths(
          left, right.get, leftWidths, widths)).getOrElse(left)
        claimRecursiveTransport(operated)
        val bridged = stage.bridges.head.replayWithWidths(operated, resultWidths, active)
        claimRecursiveTransport(bridged)
        result = fresh(label + "_result", resultWidths)
        result.assignFrom(bridged)
      }
      val protectedCarriers = block.declarations.filter(leaf =>
        leaf.dontSimplify && leaf.hasTag(noBackendCombMerge))
      observations += (() => {
        if (protectedCarriers.exists(leaf => !leaf.dontSimplify || !leaf.hasTag(noBackendCombMerge)))
          fail("CARRIER-POLICY", "proved widening intermediates lost their native carrier policy")
      })
      val assignments = block.statements.collect { case a: AssignmentStatement => a }
      val observation = TypedBalancedReductionClosedGraph.observe(UnvalidatedBalancedCallback(
        0, Vector(left) ++ right.toVector ++ schema.hardwareInputs,
        result, block.declarations, assignments))
      observations += (() => observation.requireUnchanged())
      val anchorObservation = TypedBalancedReductionClosedGraph.observe(UnvalidatedBalancedCallback(
        0, Vector(vector.vec.head), Vec(Vector(left) ++ right.toVector), anchors.declarations,
        anchors.statements.collect { case a: AssignmentStatement => a }))
      block.append(anchors)
      observations += (() => anchorObservation.requireUnchanged())
      Body(block, left, right, result, observations.toVector)
    }
    val stages = certificate.stages.zip(widthSchedule.stages).map { case (stage, widths) =>
      def sameOnPartial(left: Vector[ElaborationIntegerExpression],
          right: Vector[ElaborationIntegerExpression]): Boolean = {
        if (left.size != right.size) false
        else left.zip(right).forall { case (a, b) =>
          val difference = ElaborationWidthAuthority.subtract(a, b)
          ElaborationWidthAuthority.minimumWhen(difference, widths.partialPairActive).contains(BigInt(0)) &&
            ElaborationWidthAuthority.maximumWhen(difference, widths.partialPairActive).contains(BigInt(0))
        }
      }
      val partial = if (!widths.fullPairPossible || !widths.partialPairPossible ||
          (sameOnPartial(widths.fullInput, widths.partialLeft) &&
            sameOnPartial(widths.fullInput, widths.partialRight))) None
        else Some(template(stage, "partial_pair", widths.partialLeft,
          Some(widths.partialRight), widths.partialPairActive))
      WideningCompositeStage(stage.geometry, widths.inputFull, widths.inputTail,
        widths.outputFull, widths.outputTail, widths.inputPacked, widths.outputPacked,
        widths.fullPairPossible,
        template(stage, if (widths.fullPairPossible) "pair" else "partial_pair",
          if (widths.fullPairPossible) widths.fullInput else widths.partialLeft,
          Some(if (widths.fullPairPossible) widths.fullInput else widths.partialRight),
          if (!widths.fullPairPossible) widths.partialPairActive
          else if (partial.nonEmpty) widths.fullPairActive else stage.geometry.active.expression),
        partial,
        if (widths.tailPossible) Some(template(stage, "tail", widths.inputTail, None,
          stage.geometry.hasOddTail.expression)) else None)
    }
    certificate.requireFreshness()
    certificate.captured.rows.flatMap(r => r.operator.toVector :+ r.bridge)
      .flatMap(_.assignments).foreach(_.removeStatement())
    certificate.captured.rows.flatMap(r => r.operator.toVector :+ r.bridge)
      .flatMap(_.declarations).foreach(_.removeStatement())
    certificate.captured.rows.flatMap(r => r.operator.toVector :+ r.bridge)
      .flatMap(_.statements).collect { case statement: WhenStatement => statement }
      .reverse.foreach(_.removeStatement())
    val input = vector.asBits
    preserve(input, prefix + "_input")
    var output: Data = null
    val outputBlock = ParameterizedStructure.captureBlock(owner, None) {
      output = fresh(prefix + "_result", widthSchedule.terminal)
      driveZero(output)
    }
    val outputObservation = TypedBalancedReductionClosedGraph.observe(UnvalidatedBalancedCallback(
      0, Vector(vector.vec.head), output, outputBlock.declarations,
      outputBlock.statements.collect { case a: AssignmentStatement => a }))
    storage.records += Record(vector, shape, input, output, plan, stages, ordinal,
      outputObservation, lexicalOwner, schema)
    output
  }

'''
    backend = replace_once(backend,
        "  private def replaceDriver(body: String, value: Data, rhs: String): String = {\n",
        widening_builder + "  private def replaceDriver(body: String, value: Data, rhs: String): String = {\n",
        "widening builder insertion")

    backend = replace_once(backend, '''      val updated = if (record.output.isInstanceOf[BaseType]) rewriteScalar(component, current, record, pc, canonicalOf, nested)
      else rewriteComposite(component, current, record, pc, canonicalOf, nested)
''', '''      val updated = if (record.output.isInstanceOf[BaseType])
        rewriteScalar(component, current, record, pc, canonicalOf, nested)
      else if (record.stages.headOption.exists(_.isInstanceOf[WideningCompositeStage]))
        rewriteWideningComposite(component, current, record, pc, canonicalOf, nested)
      else rewriteComposite(component, current, record, pc, canonicalOf, nested)
''', "widening rewrite dispatch")

    widening_rewriter = r'''
  private def rewriteWideningComposite(component: Component, current: String,
      record: Record, pc: PhaseContext, canonicalOf: Component => Component,
      nested: Boolean): String = {
    val stages = record.stages.map {
      case stage: WideningCompositeStage => stage
      case _ => fail("TRANSPORT-LAYOUT", "a widening composite transport changed its certified stage kind")
    }
    if (record.shape.elementLayout.hasNestedVectors)
      fail("WIDENING-NESTED-VEC", "changing widths inside nested Vec carriers are not published by this slice")
    def total(widths: Vector[ElaborationIntegerExpression]): ElaborationIntegerExpression = {
      if (widths.isEmpty) fail("TRANSPORT-LAYOUT", "widening transport lost its leaf width inventory")
      widths.tail.foldLeft(widths.head)(ElaborationWidthAuthority.add)
    }
    def leafSlice(source: String, index: String,
        strideWidths: Vector[ElaborationIntegerExpression],
        leafWidths: Vector[ElaborationIntegerExpression], leafIndex: Int): String = {
      if (strideWidths.size != leafWidths.size || leafIndex < 0 || leafIndex >= leafWidths.size)
        fail("TRANSPORT-LAYOUT", "widening leaf slice changed its recursive leaf inventory")
      val stride = total(strideWidths).verilog
      val offset = if (leafIndex == 0) "0" else total(leafWidths.take(leafIndex)).verilog
      val width = leafWidths(leafIndex).verilog
      s"$source[((($index) * ($stride)) + ($offset)) +: ($width)]"
    }
    val base = s"morphhdl_balanced_${record.ordinal}"
    val identifiers = "[A-Za-z_][A-Za-z0-9_$]*".r.findAllIn(current).toSet ++ structuralNames(component)
    def reserved(prefix: String): Vector[String] =
      (0 to stages.size).map(i => prefix + "_stage_" + i).toVector ++
        stages.indices.flatMap(i => Vector(prefix + "_i_" + i,
          prefix + "_active_" + i, prefix + "_bypass_" + i))
    var suffix = 0
    var prefix = base
    while (reserved(prefix).exists(identifiers)) {
      suffix += 1
      prefix = base + "_" + suffix
    }
    val blocks = stages.flatMap(_.bodies.map(_.block))
    val (remaining, bodies) = ParameterizedVerilogStructural.extractNativeTemplates(
      component, blocks, current, pc, canonicalOf, Some(record.captureSchema))
    def connect(body: String, value: Data, source: String, index: String,
        strideWidths: Vector[ElaborationIntegerExpression],
        leafWidths: Vector[ElaborationIntegerExpression]): String = {
      val leaves = value.flatten.toVector
      if (leaves.size != leafWidths.size)
        fail("TRANSPORT-LAYOUT", "widening template changed its recursive result shape")
      leaves.zipWithIndex.foldLeft(body) { case (text, (leaf, leafIndex)) =>
        replaceDriver(text, leaf, leafSlice(source, index, strideWidths, leafWidths, leafIndex))
      }
    }
    def publishResult(target: String, index: String,
        strideWidths: Vector[ElaborationIntegerExpression],
        leafWidths: Vector[ElaborationIntegerExpression], value: Data): Vector[String] = {
      val leaves = value.flatten.toVector
      if (leaves.size != leafWidths.size)
        fail("TRANSPORT-LAYOUT", "widening result changed its certified leaf inventory")
      leaves.zipWithIndex.map { case (leaf, leafIndex) =>
        s"        assign ${leafSlice(target, index, strideWidths, leafWidths, leafIndex)} = ${leaf.getName()};"
      }
    }
    val lines = ArrayBuffer.empty[String]
    val scopedDeclarations = ArrayBuffer.empty[String]
    def declare(line: String): Unit = if (nested) scopedDeclarations += line else lines += line
    val first = prefix + "_stage_0"
    declare(s"  wire [(${stages.head.inputPackedWidth.verilog})-1:0] $first;")
    lines += s"  assign $first = ${record.input.getName()};"
    var bodyIndex = 0
    stages.zipWithIndex.foreach { case (stage, stageIndex) =>
      val before = prefix + "_stage_" + stageIndex
      val after = prefix + "_stage_" + (stageIndex + 1)
      val genvar = prefix + "_i_" + stageIndex
      val geometry = stage.geometry
      val pairs = geometry.pairCount.expression.verilog
      val inputs = geometry.inputCount.expression.verilog
      val partialCondition = s"((($inputs) % 2) == 0) && ($genvar == (($pairs) - 1))"
      var pairBody = connect(bodies(bodyIndex), stage.pair.left, before,
        "2 * " + genvar, stage.inputFullWidths, stage.inputFullWidths)
      pairBody = connect(pairBody, stage.pair.right.get, before,
        "2 * " + genvar + " + 1", stage.inputFullWidths,
        if (stage.fullPairPossible) stage.inputFullWidths else stage.inputTailWidths)
      bodyIndex += 1
      val partialBody = stage.partialPair.map { body =>
        var text = connect(bodies(bodyIndex), body.left, before,
          "2 * " + genvar, stage.inputFullWidths, stage.inputFullWidths)
        text = connect(text, body.right.get, before,
          "2 * " + genvar + " + 1", stage.inputFullWidths, stage.inputTailWidths)
        bodyIndex += 1
        text
      }
      val tailBody = stage.tail.map { body =>
        val text = connect(bodies(bodyIndex), body.left, before,
          s"($inputs) - 1", stage.inputFullWidths, stage.inputTailWidths)
        bodyIndex += 1
        text
      }
      declare(s"  wire [(${stage.outputPackedWidth.verilog})-1:0] $after;")
      declare(s"  genvar $genvar;")
      if (!nested) lines += "  generate"
      lines += s"    if (${geometry.active.expression.verilog}) begin : ${prefix}_active_$stageIndex"
      lines += s"      for ($genvar = 0; $genvar < ($pairs); $genvar = $genvar + 1) begin : pairs"
      stage.partialPair match {
        case Some(body) =>
          lines += s"        if ($partialCondition) begin : partial_pair"
          lines += indent(partialBody.get, 10)
          lines ++= publishResult(after, genvar, stage.outputFullWidths,
            stage.outputTailWidths, body.result)
          lines += "        end else begin : full_pair"
          lines += indent(pairBody, 10)
          lines ++= publishResult(after, genvar, stage.outputFullWidths,
            stage.outputFullWidths, stage.pair.result)
          lines += "        end"
        case None =>
          lines += indent(pairBody, 8)
          val widths = if (stage.fullPairPossible) stage.outputFullWidths else stage.outputTailWidths
          lines ++= publishResult(after, genvar, stage.outputFullWidths, widths, stage.pair.result)
      }
      lines += "      end"
      stage.tail.foreach { body =>
        lines += s"      if (${geometry.hasOddTail.expression.verilog}) begin : tail"
        lines += indent(tailBody.get, 8)
        lines ++= publishResult(after, pairs, stage.outputFullWidths,
          stage.outputTailWidths, body.result)
        lines += "      end"
      }
      lines += s"    end else begin : ${prefix}_bypass_$stageIndex"
      lines += s"      assign $after = $before;"
      lines += "    end"
      if (!nested) lines += "  endgenerate"
    }
    val last = prefix + "_stage_" + stages.size
    val updated = connect(remaining, record.output, last, "0",
      stages.last.outputFullWidths, stages.last.outputTailWidths)
    if (nested)
      return scopedDeclarations.mkString("\n") + "\n" + updated + "\n" + lines.mkString("\n")
    val end = updated.lastIndexOf("endmodule")
    if (end < 0) fail("MODULE", "native module terminator missing")
    updated.substring(0, end) + lines.mkString("\n") + "\n" + updated.substring(end)
  }

'''
    backend = replace_once(backend,
        "  private def rewriteComposite(component: Component, current: String, record: Record,\n",
        widening_rewriter + "  private def rewriteComposite(component: Component, current: String, record: Record,\n",
        "widening rewriter insertion")
    BACKEND.write_text(backend)

    require(not TEST.exists(), "widening publication test already exists")
    TEST.write_text(r'''package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import morphhdl.{MorphNamedFieldVectors, MorphVerilog}
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

object CompositeWideningPublicationHelpers {
  def typedWidth(value: BaseType): ElabInt =
    ElabInt.fromExpression(ParameterizedWidth.expressionOf(value)
      .getOrElse(ElabInt.literal(value.getBitsWidth).expression))

  def combine(a: BalancedCompositeWideningValue,
      b: BalancedCompositeWideningValue): BalancedCompositeWideningValue = {
    val unsignedSum = a.unsignedSum +^ b.unsignedSum
    val unsignedProduct = a.unsignedProduct * b.unsignedProduct
    val signedSum = a.signedSum +^ b.signedSum
    val signedProduct = a.signedProduct * b.signedProduct
    val result = BalancedCompositeWideningValue(typedWidth(unsignedSum), typedWidth(unsignedProduct),
      typedWidth(signedSum), typedWidth(signedProduct))
    result.unsignedSum := unsignedSum
    result.unsignedProduct := unsignedProduct
    result.signedSum := signedSum
    result.signedProduct := signedProduct
    result
  }
}

final class CompositeWideningPublicationHardware(width: HdlInt, count: HdlInt,
    moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val initial = ElabInt.fromExpression(width.bits.expression.get)
  val values = in(Vec(BalancedCompositeWideningValue(initial, initial, initial, initial), count))
    .setName("values")
  val reduced = values.reduceBalancedTree(
    (a: BalancedCompositeWideningValue, b: BalancedCompositeWideningValue) =>
      CompositeWideningPublicationHelpers.combine(a, b))
  val result = out(BalancedCompositeWideningValue(
    CompositeWideningPublicationHelpers.typedWidth(reduced.unsignedSum),
    CompositeWideningPublicationHelpers.typedWidth(reduced.unsignedProduct),
    CompositeWideningPublicationHelpers.typedWidth(reduced.signedSum),
    CompositeWideningPublicationHelpers.typedWidth(reduced.signedProduct))).setName("result")
  result := reduced
}

class TypedBalancedReductionCompositeWideningPublicationTests extends AnyFunSuite {
  private def emit(layout: String, defaultCount: Int): String = {
    val directory = Files.createTempDirectory("composite-widening-publication-")
    val name = s"CompositeWidening_${layout}_d$defaultCount"
    val base = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false,
      headerWithRepoHash = false, bitVectorWidthMax = 65536)
    base.netlistFileName = name + ".v"
    val config = if (layout == "fields") MorphNamedFieldVectors.enable(base) else base
    MorphVerilog(config) {
      new CompositeWideningPublicationHardware(HdlInt.param("WIDTH", 5, 1, 32),
        HdlInt.param("COUNT", defaultCount, 1, 5), name)
    }
    val file = directory.resolve(name + ".v")
    assert(Files.isRegularFile(file), "widening candidate was not emitted")
    new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
  }

  for (layout <- Vector("packed", "fields"); default <- Vector(1, 5)) {
    test(s"publish independent widening Bundle leaves: $layout default=$default") {
      val rtl = emit(layout, default)
      Vector("WIDTH", "COUNT", "result_unsignedSum", "result_unsignedProduct",
        "result_signedSum", "result_signedProduct", "morphhdl_balanced_1_stage_0")
        .foreach(token => assert(rtl.contains(token), s"missing $token\n$rtl"))
      if (layout == "fields")
        Vector("values_unsignedSum", "values_unsignedProduct", "values_signedSum",
          "values_signedProduct").foreach(token => assert(rtl.contains(token), rtl))
      var depth = 0
      "\\b(generate|endgenerate)\\b".r.findAllIn(rtl).foreach {
        case "generate" => depth += 1; assert(depth == 1, "nested generate region\n" + rtl)
        case "endgenerate" => depth -= 1; assert(depth == 0, "unbalanced generate region\n" + rtl)
      }
      assert(depth == 0)
    }
  }
}
''')
    print("applied first composite-widening publication prototype")


if __name__ == "__main__":
    main()
