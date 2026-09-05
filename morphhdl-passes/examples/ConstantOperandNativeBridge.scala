package morphhdl.examples

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import morphhdl.ir.v1.{CanonicalIrSchema, Declaration, DeclarationKind, Design, Driver,
  DriverCoverage, DriverId, DriverKind, IntExpr, Module, ModuleId, NameOrigin,
  Observability, PackedType, PackedValueSemantics, PortDirection, ReferenceId,
  RtlBinaryOperator, RtlExpr, RtlUnaryOperator, Scope, ScopeId, ScopeKind,
  Signedness, SymbolId}
import morphhdl.passes.api.{PassId, WireAliasPassConfiguration}
import morphhdl.passes.transform.ConstantOperandSimplificationPass
import spinal.core._
import spinal.core.internals._

/**
  * Test-only, identity-preserving native bridge for the Boolean expression facet.
  *
  * Capture the actual entire RHS, invoke the canonical pass, and decode its
  * actual output. No fabricated surrogate operator, parameter default, driver
  * following, emitted name or generated text participates in the decision.
  * Unrepresented expressions are retained, not replaced with a sampled value.
  * Wider arithmetic and symbolic masks are covered by the canonical rule
  * oracle; production publication and writeback remain WA-08 scope.
  */
private[examples] final class ConstantOperandNativePhase extends Phase {
  private var completed = false
  private var visited = 0
  private var changedAssignments = 0
  private var ruleCounts = Map.empty[String, Int]
  def changedCount: Int = {
    require(completed, "WA-07a native phase did not execute")
    changedAssignments
  }
  def rules: Map[String, Int] = {
    require(completed, "WA-07a native phase did not execute")
    ruleCounts
  }
  override def hasNetlistImpact: Boolean = true

  override def impl(pc: PhaseContext): Unit = {
    require(!completed, "WA-07a native phase executed twice")
    pc.components().foreach { component =>
      val assignments = ArrayBuffer.empty[DataAssignmentStatement]
      component.dslBody.walkStatements {
        case assignment: DataAssignmentStatement if eligible(assignment) => assignments += assignment
        case _ =>
      }
      assignments.foreach { assignment =>
        visited += 1
        val codec = new BooleanCodec
        codec.capture(assignment.source, "rhs").foreach { input =>
          val snapshot = codec.design(input)
          val result = ConstantOperandSimplificationPass.run(snapshot)
          require(result.isSuccess, result.diagnostics.mkString("; "))
          if (result.changed) {
            val again = ConstantOperandSimplificationPass.run(result.output)
            require(again.isSuccess && !again.changed && again.output == result.output,
              "WA-07a native candidate is not a canonical fixed point")
            require(result.output.modules.head.declarations == snapshot.modules.head.declarations,
              "WA-07a simplification changed declaration metadata")
            val rewritten = codec.decode(result.output.modules.head.drivers.head.value)
            require(rewritten.getTypeObject == TypeBool, "WA-07a decoded expression is not Boolean")
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

  private def eligible(assignment: DataAssignmentStatement): Boolean = {
    val target = assignment.finalTarget
    target.isInstanceOf[Bool] && target.isComb && !target.isAnalog &&
      !target.isInputOrInOut && !target.isFrozen() && target.isEmptyOfTag &&
      !preserved(target) && target.hasOnlyOneStatement &&
      (assignment.target eq target) && assignment.parentScope != null &&
      (assignment.parentScope eq target.rootScopeStatement) &&
      (target.parentScope eq target.rootScopeStatement) &&
      assignment.source != null
  }

  // The native API's source-level private visibility is intentionally not
  // bypassed by modifying upstream source. Missing evidence retains the target.
  private def preserved(value: BaseType): Boolean = {
    var cls: Class[_] = value.getClass
    while (cls != null) {
      try {
        val field = cls.getDeclaredField("dontSimplify")
        field.setAccessible(true)
        return field.getBoolean(value)
      } catch {
        case _: NoSuchFieldException => cls = cls.getSuperclass
        case _: Throwable => return true
      }
    }
    true
  }

  private final class BooleanCodec {
    private val moduleId = ModuleId.unsafe("module.native-constant-expression")
    private val scopeId = ScopeId.unsafe("scope.native-constant-expression")
    private val sinkId = SymbolId.unsafe("symbol.native-constant-sink")
    private val packed = PackedType(IntExpr.Literal(BigInt(1)), Signedness.Unsigned,
      PackedValueSemantics.Boolean)
    private val sources = mutable.LinkedHashMap.empty[BaseType, SymbolId]
    private val originalNodes = mutable.Map.empty[RtlExpr, Expression]

    def capture(value: Expression, path: String): Option[RtlExpr] = {
      def binary(node: BinaryOperator, op: RtlBinaryOperator): Option[RtlExpr] =
        for (a <- capture(node.left, path + ".left"); b <- capture(node.right, path + ".right"))
          yield RtlExpr.Binary(op, a, b)
      val captured: Option[RtlExpr] = value match {
        case literal: BoolLiteral if !literal.hasPoison =>
          Some(RtlExpr.Literal(if (literal.value) BigInt(1) else BigInt(0), 1))
        case source: Bool if !source.isAnalog && !source.isInOut =>
          val id = sources.getOrElseUpdate(source,
            SymbolId.unsafe(s"symbol.native-constant-source-${sources.size}"))
          Some(RtlExpr.Ref(ReferenceId.unsafe("reference.native-constant." + path), id, scopeId))
        case node: Operator.Bool.And => binary(node, RtlBinaryOperator.BitwiseAnd)
        case node: Operator.Bool.Or => binary(node, RtlBinaryOperator.BitwiseOr)
        case node: Operator.Bool.Xor => binary(node, RtlBinaryOperator.BitwiseXor)
        case node: Operator.Bool.Equal => binary(node, RtlBinaryOperator.Equal)
        case node: Operator.Bool.NotEqual => binary(node, RtlBinaryOperator.NotEqual)
        case node: Operator.Bool.Not => capture(node.source, path + ".value")
          .map(RtlExpr.Unary(RtlUnaryOperator.LogicalNot, _))
        case node: BinaryMultiplexerBool =>
          for (condition <- capture(node.cond, path + ".condition");
               yes <- capture(node.whenTrue, path + ".yes");
               no <- capture(node.whenFalse, path + ".no")) yield RtlExpr.Mux(condition, yes, no)
        case _ => None
      }
      captured.foreach(expr => originalNodes.update(expr, value))
      captured
    }

    def design(rhs: RtlExpr): Design = {
      def declaration(id: SymbolId, direction: PortDirection): Declaration =
        Declaration(id, scopeId, DeclarationKind.Port(direction), Some(packed),
          NameOrigin.Generated, None, Observability(complete = true, externallyVisible = true))
      Design(CanonicalIrSchema.schemaVersion, CanonicalIrSchema.stage, moduleId,
        Vector(Module(moduleId, "NativeConstantExpression", Vector.empty,
          Vector(Scope(scopeId, None, ScopeKind.Module)), Vector.empty,
          sources.values.toVector.map(declaration(_, PortDirection.Input)) :+
            declaration(sinkId, PortDirection.Output),
          Vector(Driver(DriverId.unsafe("driver.native-constant-sink"), scopeId, sinkId,
            DriverKind.Continuous, DriverCoverage.FullObject, rhs)))))
    }

    def decode(expr: RtlExpr): Expression = originalNodes.getOrElse(expr, expr match {
      case RtlExpr.Ref(_, target, _, _) => sources.find(_._2 == target).get._1
      case RtlExpr.Literal(value, 1, false) if value == 0 || value == 1 => new BoolLiteral(value == 1)
      case RtlExpr.Unary(RtlUnaryOperator.BitwiseNot | RtlUnaryOperator.LogicalNot, value) =>
        val node = new Operator.Bool.Not { type T = Expression }
        node.source = decode(value)
        node
      case RtlExpr.Binary(operator, left, right) =>
        val node: BinaryOperator { type T = Expression } = operator match {
          case RtlBinaryOperator.BitwiseAnd => new Operator.Bool.And { type T = Expression }
          case RtlBinaryOperator.BitwiseOr => new Operator.Bool.Or { type T = Expression }
          case RtlBinaryOperator.BitwiseXor => new Operator.Bool.Xor { type T = Expression }
          case RtlBinaryOperator.Equal => new Operator.Bool.Equal { type T = Expression }
          case RtlBinaryOperator.NotEqual => new Operator.Bool.NotEqual { type T = Expression }
          case other => throw new IllegalStateException(s"unsupported decoded Boolean operator $other")
        }
        node.left = decode(left)
        node.right = decode(right)
        node
      case RtlExpr.Mux(condition, yes, no) =>
        val node = new BinaryMultiplexerBool { type T = Expression }
        node.cond = decode(condition)
        node.whenTrue = decode(yes)
        node.whenFalse = decode(no)
        node
      case other => throw new IllegalStateException(s"unsupported decoded Boolean expression $other")
    })
  }
}

/** Each round actually executes every selected native stage, in canonical order. */
private[examples] final class ConstantOperandPipelineNativePhase(all: Boolean) extends Phase {
  private var completed = false
  private var rounds = 0
  private var removed = Vector(0, 0, 0)
  private var simplified = 0
  private var rules = Map.empty[String, Int]
  private var executionRounds = Vector.empty[Vector[PassId]]
  override def hasNetlistImpact: Boolean = true
  override def impl(pc: PhaseContext): Unit = {
    require(!completed, "WA-07a pipeline phase executed twice")
    var progress = true
    while (progress) {
      rounds += 1
      var aliases = Vector(0, 0, 0)
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
      }
      val constant = new ConstantOperandNativePhase
      constant.impl(pc)
      executed :+= PassId.ConstantOperandSimplification
      val expected = if (all) WireAliasPassConfiguration(enabled = true).enabledPasses
        else Vector(PassId.ConstantOperandSimplification)
      require(executed == expected, "WA-07a native order differs from the canonical pipeline")
      executionRounds :+= executed
      simplified += constant.changedCount
      constant.rules.foreach { case (rule, count) =>
        rules = rules.updated(rule, rules.getOrElse(rule, 0) + count)
      }
      progress = aliases.sum + constant.changedCount > 0
      require(rounds <= 1024, "WA-07a native witness failed to converge")
    }
    require(simplified > 0, "WA-07a native witness simplified no real assignment")
    if (all) require(removed.forall(_ > 0), "WA-07a native witness did not exercise every alias stage")
    completed = true
  }
  def toJson: String = {
    require(completed, "WA-07a native pipeline did not execute")
    val passes = executionRounds.head
    val passId = passes.map(_.value).mkString("+")
    val executed = passes.map(p => "\"" + p.value + "\"").mkString(", ")
    val roundsJson = executionRounds.map(_.map(p => "\"" + p.value + "\"").mkString("[", ", ", "]")).mkString(", ")
    val ruleJson = rules.toVector.sortBy(_._1).map { case (rule, count) => s""""$rule": $count""" }.mkString(", ")
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
       |  "simplified_assignment_count": $simplified,
       |  "rules": {$ruleJson}
       |}
       |""".stripMargin
  }
}

object ParameterizedStreamFifoConstantPassWitness {
  def main(args: Array[String]): Unit = {
    require(args.length == 4, "usage: MODE(reference|constant|all) OUTPUT_DIRECTORY OUTPUT_FILE REPORT_FILE")
    val phase = args(0) match {
      case "reference" => None
      case "constant" => Some(new ConstantOperandPipelineNativePhase(false))
      case "all" => Some(new ConstantOperandPipelineNativePhase(true))
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
    val json = phase.map(_.toJson).getOrElse(
      """{"schema_version":1,"mode":"common-pre-pass-reference","native_full_alias_removal_suppressed":true} """ + "\n")
    Files.write(report, json.getBytes(StandardCharsets.UTF_8))
    println(generated.generatedSourcesPaths.head)
  }
}

private[examples] object ConstantOperandWitnessPhasePlan {
  def install(config: SpinalConfig, phase: Option[Phase]): Unit = {
    config.phasesInserters += { phases: ArrayBuffer[Phase] =>
      val cleanup = phases.zipWithIndex.collect { case (_: PhaseRemoveIntermediateUnnameds, i) => i }
      require(cleanup.size >= 3, "WA-07a witness requires three native cleanup boundaries")
      phases.update(cleanup(1), new PhaseRemoveIntermediateUnnameds(true))
      cleanup.drop(3).reverse.foreach(phases.remove(_))
      phase match {
        case Some(value) => phases.update(cleanup(2), value)
        case None => phases.remove(cleanup(2))
      }
    }
  }
}

/** Small non-library witness exercising the same real capture and writeback. */
object ConstantOperandGenericNativeWitness {
  def main(args: Array[String]): Unit = {
    require(args.length == 3, "usage: MODE(reference|constant) OUTPUT_DIRECTORY REPORT_FILE")
    val candidate = args(0) match {
      case "reference" => false
      case "constant" => true
      case other => throw new IllegalArgumentException(s"unsupported witness mode $other")
    }
    val phase = if (candidate) Some(new ConstantOperandPipelineNativePhase(false)) else None
    val output = Paths.get(args(1)).toAbsolutePath.normalize
    val report = Paths.get(args(2)).toAbsolutePath.normalize
    Files.createDirectories(output)
    Option(report.getParent).foreach(Files.createDirectories(_))
    val config = SpinalConfig(targetDirectory = output.toString)
    config.netlistFileName = if (candidate) "native-candidate.v" else "native-reference.v"
    ConstantOperandWitnessPhasePlan.install(config, phase)
    SpinalVerilog(config) {
      new Component {
        setDefinitionName(if (candidate) "ConstantOperandNativeCandidate" else "ConstantOperandNativeReference")
        val a = in Bool()
        val b = in Bool()
        val y0, y1, y2, y3, y4, y5, y6, y7 = out Bool()
        y0 := (a === b) & True
        y1 := (a === b) & False
        y2 := (a =/= b) | False
        y3 := (a =/= b) | True
        y4 := (a === b) ^ False
        y5 := (a === b) ^ True
        // These operands can carry Z. The first must retain its operator;
        // the second must normalize Z to X rather than propagate a raw Z.
        y6 := a & True
        y7 := a ^ True
      }
    }
    Files.write(report, phase.map(_.toJson).getOrElse("{}\n").getBytes(StandardCharsets.UTF_8))
  }
}
