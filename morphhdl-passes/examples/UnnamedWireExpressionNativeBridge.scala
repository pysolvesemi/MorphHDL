package morphhdl.examples

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.collection.mutable.ArrayBuffer

import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import morphhdl.ir.v1.CanonicalIrSchema
import morphhdl.ir.v1.Declaration
import morphhdl.ir.v1.DeclarationKind
import morphhdl.ir.v1.Design
import morphhdl.ir.v1.Driver
import morphhdl.ir.v1.DriverCoverage
import morphhdl.ir.v1.DriverId
import morphhdl.ir.v1.DriverKind
import morphhdl.ir.v1.IntExpr
import morphhdl.ir.v1.Module
import morphhdl.ir.v1.ModuleId
import morphhdl.ir.v1.NameOrigin
import morphhdl.ir.v1.Observability
import morphhdl.ir.v1.PackedType
import morphhdl.ir.v1.PackedValueSemantics
import morphhdl.ir.v1.PortDirection
import morphhdl.ir.v1.ReferenceId
import morphhdl.ir.v1.RtlBinaryOperator
import morphhdl.ir.v1.RtlExpr
import morphhdl.ir.v1.Scope
import morphhdl.ir.v1.ScopeId
import morphhdl.ir.v1.ScopeKind
import morphhdl.ir.v1.Signedness
import morphhdl.ir.v1.SymbolId
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.WireAliasPassConfiguration
import morphhdl.passes.transform.UnnamedWireExpressionEliminationPass
import spinal.core._
import spinal.core.internals._

private[examples] final class UnnamedWireExpressionNativePhase extends Phase {
  private var completed = false
  private var visited = 0
  private var eliminated = 0
  private var rewritten = 0
  private var rejected = Map.empty[String, Int]

  override def hasNetlistImpact: Boolean = true

  override def impl(pc: PhaseContext): Unit = {
    var progress = true
    while (progress) {
      progress = false
      val iterator = candidateSnapshot(pc).iterator
      while (iterator.hasNext && !progress) {
        val candidate = iterator.next()
        visited += 1
        proveCandidate(candidate) match {
          case Left(reason) =>
            rejected = rejected.updated(reason, rejected.getOrElse(reason, 0) + 1)
          case Right(proof) =>
            if (!canonicalDecision(candidate, proof)) {
              rejected = rejected.updated(
                "WA07-NATIVE-CANONICAL-DECISION",
                rejected.getOrElse("WA07-NATIVE-CANONICAL-DECISION", 0) + 1
              )
            } else {
              val replacements = rewriteNative(candidate)
              if (replacements != 1)
                throw new IllegalStateException(
                  s"WA-07 native expression rewrite expected one replacement, observed $replacements"
                )
              rewritten += replacements
              eliminated += 1
              progress = true
            }
        }
      }
    }
    completed = true
  }

  def report: UnnamedWireExpressionNativeReport = {
    if (!completed)
      throw new IllegalStateException("WA-07 native expression phase did not execute")
    UnnamedWireExpressionNativeReport(visited, eliminated, rewritten, rejected)
  }

  private final case class NativeCandidate(
      component: Component,
      alias: BaseType,
      source: Expression,
      assignment: DataAssignmentStatement,
      receiver: DataAssignmentStatement
  )

  private final case class NativeProof(
      packedType: PackedType,
      dependencies: Vector[BaseType]
  )

  private def candidateSnapshot(pc: PhaseContext): Vector[NativeCandidate] = {
    val values = Vector.newBuilder[NativeCandidate]
    pc.components().foreach { component =>
      val statements = statementsOf(component)
      component.dslBody.walkDeclarations {
        case alias: BaseType
            if alias.isUnnamed && alias.isComb && alias.isDirectionLess &&
              !alias.isAnalog && !alias.isTypeNode && alias.parentScope != null &&
              (alias.parentScope eq alias.rootScopeStatement) && alias.hasOnlyOneStatement =>
          alias.head match {
            case assignment: DataAssignmentStatement
                if (assignment.parentScope eq alias.rootScopeStatement) &&
                  (assignment.target eq alias) && (assignment.finalTarget eq alias) &&
                  !assignment.source.isInstanceOf[BaseType] =>
              val uses = statements.collect {
                case value: DataAssignmentStatement
                    if (value ne assignment) && references(value, alias) == 1 => value
              }
              if (uses.size == 1)
                values += NativeCandidate(component, alias, assignment.source, assignment, uses.head)
            case _ =>
          }
        case _ =>
      }
    }
    values.result()
  }

  private def proveCandidate(
      candidate: NativeCandidate
  ): Either[String, NativeProof] = {
    val receiver = candidate.receiver
    if (
      receiver.parentScope == null ||
      !(receiver.parentScope eq receiver.rootScopeStatement) ||
      (receiver.finalTarget eq candidate.alias) ||
      (receiver.finalTarget.component ne candidate.component) ||
      !receiver.finalTarget.isComb || receiver.finalTarget.isAnalog ||
      receiver.finalTarget.isInputOrInOut
    ) Left("WA07-NATIVE-RECEIVER")
    else if (
      candidate.alias.isFrozen() || !candidate.alias.isEmptyOfTag ||
      readPrivateBoolean(candidate.alias, "dontSimplify").getOrElse(true)
    ) Left("WA07-NATIVE-PRESERVATION")
    else {
      val dependencies = referencedBaseTypes(candidate.source)
      if (dependencies.exists(_ eq candidate.alias)) Left("WA07-NATIVE-SELF-REFERENCE")
      else if (
        dependencies.exists(value =>
          (value.component ne candidate.component) || value.parentScope == null ||
            !(value.parentScope eq value.rootScopeStatement) || value.isAnalog || value.isInOut
        )
      ) Left("WA07-NATIVE-SOURCE-BOUNDARY")
      else if (createsCycle(candidate.component, candidate.alias, dependencies))
        Left("WA07-NATIVE-CYCLE")
      else packedType(candidate.alias, candidate.source).toRight("WA07-NATIVE-PACKED-TYPE")
        .map(value => NativeProof(value, dependencies.distinct))
    }
  }

  private def packedType(alias: BaseType, source: Expression): Option[PackedType] = {
    val sourceWidth = source match {
      case value: WidthProvider => value.getWidth
      case _ if source.getTypeObject == TypeBool => 1
      case _ => return None
    }
    if (sourceWidth != alias.getBitsWidth || source.getTypeObject != alias.getTypeObject)
      return None
    packedSemantics(alias).map { case (signedness, semantics) =>
      PackedType(IntExpr.Literal(BigInt(alias.getBitsWidth)), signedness, semantics)
    }
  }

  private def packedSemantics(
      value: BaseType
  ): Option[(Signedness, PackedValueSemantics)] = value match {
    case _: Bool => Some(Signedness.Unsigned -> PackedValueSemantics.Boolean)
    case _: Bits => Some(Signedness.Unsigned -> PackedValueSemantics.BitVector)
    case _: UInt => Some(Signedness.Unsigned -> PackedValueSemantics.UnsignedInteger)
    case _: SInt => Some(Signedness.Signed -> PackedValueSemantics.SignedInteger)
    case _ => None
  }

  private def canonicalDecision(candidate: NativeCandidate, proof: NativeProof): Boolean = {
    val moduleId = ModuleId.unsafe("module.native-expression-witness")
    val scopeId = ScopeId.unsafe("scope.native-expression-witness")
    val sourceId = SymbolId.unsafe("symbol.native-expression-source")
    val aliasId = SymbolId.unsafe("symbol.native-expression-alias")
    val sinkId = SymbolId.unsafe("symbol.native-expression-sink")
    val canonical = Design(
      CanonicalIrSchema.schemaVersion,
      CanonicalIrSchema.stage,
      moduleId,
      Vector(
        Module(
          moduleId,
          "NativeExpressionWitness",
          Vector.empty,
          Vector(Scope(scopeId, None, ScopeKind.Module)),
          Vector.empty,
          Vector(
            Declaration(
              sourceId,
              scopeId,
              DeclarationKind.Port(PortDirection.Input),
              Some(proof.packedType),
              NameOrigin.Explicit("source"),
              None,
              Observability(complete = true, externallyVisible = true)
            ),
            Declaration(
              aliasId,
              scopeId,
              DeclarationKind.InternalCombinational,
              Some(proof.packedType),
              NameOrigin.Unnamed,
              None,
              Observability.Unobserved
            ),
            Declaration(
              sinkId,
              scopeId,
              DeclarationKind.Port(PortDirection.Output),
              Some(proof.packedType),
              NameOrigin.Explicit("sink"),
              None,
              Observability(complete = true, externallyVisible = true)
            )
          ),
          Vector(
            Driver(
              DriverId.unsafe("driver.native-expression-alias"),
              scopeId,
              aliasId,
              DriverKind.Continuous,
              DriverCoverage.FullObject,
              RtlExpr.Binary(
                RtlBinaryOperator.BitwiseOr,
                RtlExpr.Ref(
                  ReferenceId.unsafe("reference.native-expression-left"),
                  sourceId,
                  scopeId
                ),
                RtlExpr.Ref(
                  ReferenceId.unsafe("reference.native-expression-right"),
                  sourceId,
                  scopeId
                )
              )
            ),
            Driver(
              DriverId.unsafe("driver.native-expression-sink"),
              scopeId,
              sinkId,
              DriverKind.Continuous,
              DriverCoverage.FullObject,
              RtlExpr.Ref(
                ReferenceId.unsafe("reference.native-expression-sink-alias"),
                aliasId,
                scopeId
              )
            )
          )
        )
      )
    )
    val result = UnnamedWireExpressionEliminationPass.run(
      canonical,
      WireAliasPassConfiguration(enabled = true)
    )
    result.status == PassExecutionStatus.Changed &&
      result.eliminationReport.inlinedExpressions.size == 1 &&
      result.eliminationReport.inlinedExpressions.head.replacementCount == 1
  }

  private def rewriteNative(candidate: NativeCandidate): Int = {
    var replacements = 0
    candidate.receiver.walkRemapDrivingExpressions {
      case value: BaseType if value eq candidate.alias =>
        replacements += 1
        candidate.source
      case other => other
    }
    candidate.assignment.removeStatement()
    candidate.alias.removeStatement()
    val remaining = statementsOf(candidate.component).map(references(_, candidate.alias)).sum
    if (remaining != 0)
      throw new IllegalStateException(
        s"WA-07 native rewrite left $remaining reference(s) to a removed expression wire"
      )
    replacements
  }

  private def createsCycle(
      component: Component,
      alias: BaseType,
      dependencies: Vector[BaseType]
  ): Boolean = {
    val edges = scala.collection.mutable.LinkedHashMap.empty[BaseType, Vector[BaseType]]
    statementsOf(component).foreach {
      case assignment: DataAssignmentStatement
          if assignment.parentScope != null &&
            (assignment.parentScope eq assignment.rootScopeStatement) &&
            (assignment.target eq assignment.finalTarget) && assignment.finalTarget.isComb =>
        edges.update(
          assignment.finalTarget,
          referencedBaseTypes(assignment.source).filter(_.component eq component).distinct
        )
      case _ =>
    }
    dependencies.exists { dependency =>
      val pending = scala.collection.mutable.Stack[BaseType](dependency)
      val visited = scala.collection.mutable.HashSet.empty[BaseType]
      var found = false
      while (pending.nonEmpty && !found) {
        val current = pending.pop()
        if (current eq alias) found = true
        else if (!visited.contains(current)) {
          visited += current
          edges.getOrElse(current, Vector.empty).reverse.foreach(pending.push)
        }
      }
      found
    }
  }

  private def statementsOf(component: Component): Vector[Statement] = {
    val values = Vector.newBuilder[Statement]
    component.dslBody.walkStatements(values += _)
    values.result()
  }

  private def references(statement: Statement, target: BaseType): Int = {
    var count = 0
    statement.walkDrivingExpressions {
      case value: BaseType if value eq target => count += 1
      case _ =>
    }
    count
  }

  private def referencedBaseTypes(expression: Expression): Vector[BaseType] = {
    val values = Vector.newBuilder[BaseType]
    expression match {
      case value: BaseType => values += value
      case _ =>
    }
    expression.walkDrivingExpressions {
      case value: BaseType => values += value
      case _ =>
    }
    values.result()
  }

  private def readPrivateBoolean(instance: AnyRef, name: String): Option[Boolean] =
    try {
      val field = instance.getClass.getSuperclass.getDeclaredField(name)
      field.setAccessible(true)
      Some(field.getBoolean(instance))
    } catch {
      case _: ReflectiveOperationException => None
      case _: SecurityException => None
    }
}

private[examples] final case class UnnamedWireExpressionNativeReport(
    visitedCandidates: Int,
    eliminatedCount: Int,
    rewrittenReferenceCount: Int,
    rejectedByReason: Map[String, Int]
) {
  def toJson(passId: String): String = {
    val rejected = rejectedByReason.toVector.sortBy(_._1).map { case (key, value) =>
      s"    ${quote(key)}: $value"
    }
    Vector(
      "{",
      "  \"schema_version\": 1,",
      s"  \"pass_id\": ${quote(passId)},",
      "  \"executed_before_name_allocation\": true,",
      s"  \"visited_candidates\": $visitedCandidates,",
      s"  \"eliminated_count\": $eliminatedCount,",
      s"  \"rewritten_reference_count\": $rewrittenReferenceCount,",
      "  \"rejected_by_reason\": {",
      rejected.mkString(",\n"),
      "  }",
      "}",
      ""
    ).mkString("\n")
  }

  private def quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private[examples] final class UnifiedWirePassNativePhase extends Phase {
  private val unnamed = new UnnamedWireAliasNativePhase
  private val named = new NamedWireAliasNativePhase
  private val expression = new UnnamedWireExpressionNativePhase
  private var completed = false

  override def hasNetlistImpact: Boolean = true

  override def impl(pc: PhaseContext): Unit = {
    unnamed.impl(pc)
    named.impl(pc)
    expression.impl(pc)
    if (
      unnamed.report.eliminatedCount < 1 || named.report.eliminatedCount < 1 ||
      expression.report.eliminatedCount < 1
    ) throw new IllegalStateException("WA-07 all-pass witness did not transform every stage")
    completed = true
  }

  def report: UnnamedWireExpressionNativeReport = {
    if (!completed) throw new IllegalStateException("WA-07 all-pass phase did not execute")
    val value = expression.report
    value.copy(
      visitedCandidates = value.visitedCandidates + unnamed.report.visitedCandidates +
        named.report.visitedCandidates,
      eliminatedCount = value.eliminatedCount + unnamed.report.eliminatedCount +
        named.report.eliminatedCount,
      rewrittenReferenceCount = value.rewrittenReferenceCount + unnamed.report.rewrittenReferences +
        named.report.rewrittenReferences
    )
  }
}

private[examples] object UnnamedWireExpressionWitnessPhasePlan {
  def install(config: SpinalConfig, phase: Option[Phase]): Unit = {
    config.phasesInserters += { phases: ArrayBuffer[Phase] =>
      val nativePasses = phases.zipWithIndex.collect {
        case (_: PhaseRemoveIntermediateUnnameds, index) => index
      }
      if (nativePasses.size < 3)
        throw new IllegalStateException(
          s"WA-07 witness expected three native unnamed-removal phases, found ${nativePasses.size}"
        )
      phases.update(nativePasses(1), new PhaseRemoveIntermediateUnnameds(true))
      nativePasses.drop(3).reverse.foreach(index => phases.remove(index))
      phase match {
        case Some(value) => phases.update(nativePasses(2), value)
        case None => phases.remove(nativePasses(2))
      }
    }
  }
}

private[examples] object Wa07WitnessRunner {
  def run(
      args: Array[String],
      passId: String,
      phaseFactory: () => Phase,
      reportOf: Phase => UnnamedWireExpressionNativeReport
  ): Unit = {
    if (args.length != 4)
      throw new IllegalArgumentException(
        "usage: MODE(reference|candidate) OUTPUT_DIRECTORY OUTPUT_FILE REPORT_FILE"
      )
    val outputDirectory = Paths.get(args(1)).toAbsolutePath.normalize
    val reportFile = Paths.get(args(3)).toAbsolutePath.normalize
    val phase = args(0) match {
      case "reference" => None
      case "candidate" => Some(phaseFactory())
      case other => throw new IllegalArgumentException(s"unsupported witness mode '$other'")
    }
    Files.createDirectories(outputDirectory)
    Option(reportFile.getParent).foreach(Files.createDirectories(_))
    val config = SpinalConfig(
      targetDirectory = outputDirectory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    )
    config.netlistFileName = args(2)
    UnnamedWireExpressionWitnessPhasePlan.install(config, phase)
    val width = HdlInt.param("WIDTH", BigInt(8), BigInt(1), BigInt(64))
    val depth = HdlInt.param("DEPTH", BigInt(5), BigInt(1), BigInt(8))
    val generated = MorphVerilog(config) { new ParameterizedStreamFifo(width, depth) }
    val generatedPath = Paths.get(generated.generatedSourcesPaths.head).toAbsolutePath.normalize
    val text = new String(Files.readAllBytes(generatedPath), StandardCharsets.UTF_8)
    if (!text.contains("parameter integer WIDTH") || !text.contains("parameter integer DEPTH"))
      throw new IllegalStateException("WA-07 witness lost symbolic WIDTH or DEPTH")
    val json = phase match {
      case Some(value) =>
        val result = reportOf(value)
        if (result.eliminatedCount < 1 || result.rewrittenReferenceCount < 1)
          throw new IllegalStateException("WA-07 witness produced no expression rewrite")
        result.toJson(passId)
      case None =>
        """{
          |  "schema_version": 1,
          |  "mode": "common-pre-pass-reference",
          |  "native_full_alias_removal_suppressed": true
          |}
          |""".stripMargin
    }
    Files.write(reportFile, json.getBytes(StandardCharsets.UTF_8))
    println(generatedPath)
  }
}

object ParameterizedStreamFifoExpressionPassWitness {
  def main(args: Array[String]): Unit = Wa07WitnessRunner.run(
    args,
    "wire-expression-unnamed",
    () => new UnnamedWireExpressionNativePhase,
    _.asInstanceOf[UnnamedWireExpressionNativePhase].report
  )
}

object ParameterizedStreamFifoAllPassWitness {
  def main(args: Array[String]): Unit = Wa07WitnessRunner.run(
    args,
    "wire-alias-unnamed+wire-alias-named+wire-expression-unnamed",
    () => new UnifiedWirePassNativePhase,
    _.asInstanceOf[UnifiedWirePassNativePhase].report
  )
}
