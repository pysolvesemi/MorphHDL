package morphhdl.examples

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.mutable.ArrayBuffer
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import morphhdl.passes.api.{PassId, WireAliasPassConfiguration}
import morphhdl.passes.transform.BooleanTernarySimplificationPass
import spinal.core._
import spinal.core.internals._

/** Test-only bridge: reuse the exact native eligibility and canonical Boolean codec. */
private[examples] final class BooleanTernaryNativePhase extends Phase {
  private val bridge = new ConstantOperandNativePhase
  private var completed = false
  private var captured = 0
  private var changedAssignments = 0
  private var ruleCounts = Map.empty[String, Int]
  def changedCount: Int = { require(completed, "WA-07b native phase did not execute"); changedAssignments }
  def capturedCount: Int = { require(completed, "WA-07b native phase did not execute"); captured }
  def rules: Map[String, Int] = { require(completed, "WA-07b native phase did not execute"); ruleCounts }
  override def hasNetlistImpact: Boolean = true

  override def impl(pc: PhaseContext): Unit = {
    require(!completed, "WA-07b native phase executed twice")
    pc.components().foreach { component =>
      val assignments = ArrayBuffer.empty[DataAssignmentStatement]
      component.dslBody.walkStatements {
        case assignment: DataAssignmentStatement if bridge.eligible(assignment) => assignments += assignment
        case _ =>
      }
      assignments.foreach { assignment =>
        val codec = new bridge.BooleanCodec
        codec.capture(assignment.source, "rhs").foreach { input =>
          captured += 1
          val snapshot = codec.design(input)
          val result = BooleanTernarySimplificationPass.run(snapshot)
          require(result.isSuccess, result.diagnostics.mkString("; "))
          if (result.changed) {
            val again = BooleanTernarySimplificationPass.run(result.output)
            require(again.isSuccess && !again.changed && again.output == result.output,
              "WA-07b native candidate is not a canonical fixed point")
            require(result.output.modules.head.declarations == snapshot.modules.head.declarations,
              "WA-07b simplification changed declaration metadata")
            val rewritten = codec.decode(result.output.modules.head.drivers.head.value)
            require(rewritten.getTypeObject == TypeBool, "WA-07b decoded expression is not Boolean")
            assignment.source = rewritten
            changedAssignments += 1
            result.rewrites.foreach { rewrite =>
              ruleCounts = ruleCounts.updated(rewrite.rule, ruleCounts.getOrElse(rewrite.rule, 0) + 1)
            }
          }
        }
      }
    }
    completed = true
  }
}

/** Native qualification only; production publication/writeback remains WA-08. */
private[examples] final class BooleanTernaryPipelineNativePhase(all: Boolean) extends Phase {
  private var completed = false
  private var rounds = 0
  private var removed = Vector(0, 0, 0)
  private var constantRewrites = 0
  private var ternaryRewrites = 0
  private var captured = 0
  private var ruleCounts = Map.empty[String, Int]
  private var executionRounds = Vector.empty[Vector[PassId]]
  override def hasNetlistImpact: Boolean = true

  override def impl(pc: PhaseContext): Unit = {
    require(!completed, "WA-07b pipeline phase executed twice")
    var progress = true
    while (progress) {
      rounds += 1
      var aliases = Vector(0, 0, 0)
      var constantChanges = 0
      var executed = Vector.empty[PassId]
      if (all) {
        val unnamed = new UnnamedWireAliasNativePhase
        val named = new NamedWireAliasNativePhase
        val expression = new UnnamedWireExpressionNativePhase
        unnamed.impl(pc)
        executed :+= PassId.UnnamedWireAliasElimination
        named.impl(pc)
        executed :+= PassId.NamedWireAliasElimination
        expression.impl(pc)
        executed :+= PassId.UnnamedWireExpressionElimination
        aliases = Vector(unnamed.report.eliminatedCount, named.report.eliminatedCount,
          expression.report.eliminatedCount)
        removed = removed.zip(aliases).map { case (a, b) => a + b }
        val constant = new ConstantOperandNativePhase
        constant.impl(pc)
        executed :+= PassId.ConstantOperandSimplification
        constantChanges = constant.changedCount
        constantRewrites += constantChanges
      }
      val ternary = new BooleanTernaryNativePhase
      ternary.impl(pc)
      executed :+= PassId.BooleanTernarySimplification
      val expected = if (all) WireAliasPassConfiguration(enabled = true).enabledPasses
        else Vector(PassId.BooleanTernarySimplification)
      require(executed == expected, "WA-07b native order differs from the canonical pipeline")
      executionRounds :+= executed
      captured += ternary.capturedCount
      ternaryRewrites += ternary.changedCount
      ternary.rules.foreach { case (rule, count) =>
        ruleCounts = ruleCounts.updated(rule, ruleCounts.getOrElse(rule, 0) + count)
      }
      progress = aliases.sum + constantChanges + ternary.changedCount > 0
      require(rounds <= 1024, "WA-07b native witness failed to converge")
    }
    require(captured > 0, "WA-07b native witness captured no real canonical RHS")
    completed = true
  }

  def toJson: String = {
    require(completed, "WA-07b native pipeline did not execute")
    val passes = executionRounds.head
    val passId = passes.map(_.value).mkString("+")
    val executed = passes.map(p => "\"" + p.value + "\"").mkString(", ")
    val roundsJson = executionRounds.map(_.map(p => "\"" + p.value + "\"").mkString("[", ", ", "]")).mkString(", ")
    val ruleJson = ruleCounts.toVector.sortBy(_._1).map { case (rule, count) => s""""$rule": $count""" }.mkString(", ")
    s"""{
       |  "schema_version": 1,
       |  "pass_id": "$passId",
       |  "executed_passes": [$executed],
       |  "executed_rounds": [$roundsJson],
       |  "common_flag_enabled": $all,
       |  "executed_before_name_allocation": true,
       |  "actual_rhs_capture_writeback": true,
       |  "procedural_receiver_rewrites": 0,
       |  "rounds": $rounds,
       |  "unnamed_alias_eliminated_count": ${removed(0)},
       |  "named_alias_eliminated_count": ${removed(1)},
       |  "unnamed_expression_eliminated_count": ${removed(2)},
       |  "constant_simplified_assignment_count": $constantRewrites,
       |  "ternary_captured_assignment_count": $captured,
       |  "ternary_simplified_assignment_count": $ternaryRewrites,
       |  "ternary_no_op": ${ternaryRewrites == 0},
       |  "rules": {$ruleJson}
       |}
       |""".stripMargin
  }
}

object ParameterizedStreamFifoBooleanTernaryWitness {
  def main(args: Array[String]): Unit = {
    require(args.length == 4, "usage: MODE(reference|ternary|all) OUTPUT_DIRECTORY OUTPUT_FILE REPORT_FILE")
    val phase = args(0) match {
      case "reference" => None
      case "ternary" => Some(new BooleanTernaryPipelineNativePhase(false))
      case "all" => Some(new BooleanTernaryPipelineNativePhase(true))
      case other => throw new IllegalArgumentException(s"unsupported witness mode $other")
    }
    val output = Paths.get(args(1)).toAbsolutePath.normalize
    val report = Paths.get(args(3)).toAbsolutePath.normalize
    Files.createDirectories(output)
    Option(report.getParent).foreach(Files.createDirectories(_))
    val config = SpinalConfig(targetDirectory = output.toString,
      defaultConfigForClockDomains = ClockDomainConfig(clockEdge = RISING, resetKind = SYNC, resetActiveLevel = HIGH))
    config.netlistFileName = args(2)
    ConstantOperandWitnessPhasePlan.install(config, phase)
    val width = HdlInt.param("WIDTH", default = BigInt(8), min = BigInt(1), max = BigInt(64))
    val depth = HdlInt.param("DEPTH", default = BigInt(5), min = BigInt(1), max = BigInt(8))
    val generated = MorphVerilog(config) { new ParameterizedStreamFifo(width, depth) }
    Files.write(report, phase.map(_.toJson).getOrElse(
      "{\"schema_version\":1,\"mode\":\"common-pre-pass-reference\"}\n").getBytes(StandardCharsets.UTF_8))
    println(generated.generatedSourcesPaths.head)
  }
}

/** Ordinary non-library source, emitted by the real native Verilog backend. */
object BooleanTernaryGenericNativeWitness {
  def main(args: Array[String]): Unit = {
    require(args.length == 3, "usage: MODE(reference|ternary|all) OUTPUT_DIRECTORY REPORT_FILE")
    val phase = args(0) match {
      case "reference" => None
      case "ternary" => Some(new BooleanTernaryPipelineNativePhase(false))
      case "all" => Some(new BooleanTernaryPipelineNativePhase(true))
      case other => throw new IllegalArgumentException(s"unsupported witness mode $other")
    }
    val name = args(0) match {
      case "reference" => "BooleanTernaryNativeReference"
      case "ternary" => "BooleanTernaryNativeCandidate"
      case "all" => "BooleanTernaryNativeAll"
      case other => throw new IllegalArgumentException(s"unsupported witness mode $other")
    }
    val output = Paths.get(args(1)).toAbsolutePath.normalize
    val report = Paths.get(args(2)).toAbsolutePath.normalize
    Files.createDirectories(output)
    Option(report.getParent).foreach(Files.createDirectories(_))
    val config = SpinalConfig(targetDirectory = output.toString)
    config.netlistFileName = "native-" + args(0) + ".v"
    ConstantOperandWitnessPhasePlan.install(config, phase)
    SpinalVerilog(config) {
      new Component {
        setDefinitionName(name)
        val a = in Bool()
        val b = in Bool()
        val y0, y1, y2, y3, y4, y5, y6, y7 = out Bool()
        y0 := Mux(a === b, True, False)
        y1 := Mux(a =/= b, False, True)
        y2 := Mux(a, True, False)
        y3 := Mux(a, False, True)
        y4 := Mux(Mux(a === b, True, False), False, True)
        y5 := Mux(a, Mux(a === b, True, False), Mux(b, False, True))
        y6 := Mux(a === b, True, False) ^ b
        y7 := Mux(a, a, b)
      }
    }
    Files.write(report, phase.map(_.toJson).getOrElse("{}\n").getBytes(StandardCharsets.UTF_8))
  }
}
