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
import morphhdl.passes.api.PassId
import morphhdl.passes.api.WireAliasPassConfiguration
import morphhdl.passes.pipeline.WireAliasPassPipeline
import spinal.core._
import spinal.core.internals._

/**
  * Test-only native execution of every wire-assignment pass behind one flag.
  *
  * The canonical pipeline first proves the public all-pass order. The reviewed
  * native identity rewrites are then applied in exactly that order: unnamed
  * direct aliases, named direct aliases, and unnamed continuous expressions.
  * Production publication/writeback remains WA-08 scope.
  */
private[examples] final class AllWireAssignmentNativePhase extends Phase {
  private val unnamed = new UnnamedWireAliasNativePhase
  private val named = new NamedWireAliasNativePhase
  private val expression = new UnnamedWireExpressionNativePhase
  private var completed = false

  override def hasNetlistImpact: Boolean = true

  override def impl(pc: PhaseContext): Unit = {
    if (completed)
      throw new IllegalStateException("WA-07 all-pass witness phase executed more than once")

    val pipeline = WireAliasPassPipeline.run(
      AllWireAssignmentCanonicalWitness.design,
      WireAliasPassConfiguration(enabled = true)
    )
    if (
      pipeline.status != PassExecutionStatus.Changed ||
      pipeline.executedPasses != PassId.allWireAssignmentPasses ||
      pipeline.eliminationReports.map(_.eliminatedCount) != Vector(1, 1, 1)
    )
      throw new IllegalStateException(
        "WA-07 public flag did not authorize every pass in the fixed production order"
      )

    unnamed.impl(pc)
    if (unnamed.report.eliminatedCount < 1 || unnamed.report.rewrittenReferences < 1)
      throw new IllegalStateException("WA-07 all-pass witness removed no unnamed direct alias")

    named.impl(pc)
    if (
      named.report.eliminatedCount < 1 || named.report.rewrittenReferences < 1 ||
      named.report.eliminatedNames.isEmpty
    )
      throw new IllegalStateException("WA-07 all-pass witness removed no named direct alias")

    expression.impl(pc)
    if (
      expression.report.eliminatedCount < 1 ||
      expression.report.rewrittenReferences < 1 ||
      expression.report.expressionOperators.isEmpty
    )
      throw new IllegalStateException(
        "WA-07 all-pass witness inlined no unnamed expression temporary"
      )

    completed = true
  }

  def report: AllWireAssignmentNativeReport = {
    if (!completed)
      throw new IllegalStateException("WA-07 all-pass witness phase did not execute")
    AllWireAssignmentNativeReport(
      executedPasses = PassId.allWireAssignmentPasses.map(_.value),
      unnamed = unnamed.report,
      named = named.report,
      expression = expression.report
    )
  }
}

private[examples] final case class AllWireAssignmentNativeReport(
    executedPasses: Vector[String],
    unnamed: UnnamedWireAliasNativeReport,
    named: NamedWireAliasNativeReport,
    expression: UnnamedWireExpressionNativeReport
) {
  def eliminatedCount: Int =
    unnamed.eliminatedCount + named.eliminatedCount + expression.eliminatedCount

  def rewrittenReferenceCount: Int =
    unnamed.rewrittenReferences + named.rewrittenReferences +
      expression.rewrittenReferences

  def toJson: String = {
    val passes = executedPasses.map(quote).mkString(", ")
    val names = named.eliminatedNames.sorted.map(quote).mkString(", ")
    val operators = expression.expressionOperators.map(quote).mkString(", ")
    Vector(
      "{",
      "  \"schema_version\": 1,",
      "  \"pass_id\": \"wire-alias-unnamed+wire-alias-named+wire-expression-unnamed\",",
      "  \"pipeline_status\": \"changed\",",
      "  \"common_flag_enabled\": true,",
      "  \"executed_before_name_allocation\": true,",
      "  \"procedural_receiver_rewrites\": 0,",
      s"""  "executed_passes": [$passes],""",
      s"""  "unnamed_alias_eliminated_count": ${unnamed.eliminatedCount},""",
      s"""  "named_alias_eliminated_count": ${named.eliminatedCount},""",
      s"""  "unnamed_expression_eliminated_count": ${expression.eliminatedCount},""",
      s"""  "eliminated_count": $eliminatedCount,""",
      s"""  "rewritten_reference_count": $rewrittenReferenceCount,""",
      s"""  "eliminated_names": [$names],""",
      s"""  "expression_operators": [$operators]""",
      "}",
      ""
    ).mkString("\n")
  }

  private def quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** Component-neutral canonical chain containing all three pass classes. */
private[examples] object AllWireAssignmentCanonicalWitness {
  private val moduleId = ModuleId.unsafe("module.all-wire-assignment-witness")
  private val scopeId = ScopeId.unsafe("scope.all-wire-assignment-witness")
  private val sourceId = SymbolId.unsafe("symbol.all-wire-source")
  private val otherId = SymbolId.unsafe("symbol.all-wire-other")
  private val unnamedId = SymbolId.unsafe("symbol.all-wire-unnamed")
  private val namedId = SymbolId.unsafe("symbol.all-wire-named")
  private val expressionId = SymbolId.unsafe("symbol.all-wire-expression")
  private val sinkId = SymbolId.unsafe("symbol.all-wire-sink")

  private val packedType = PackedType(
    width = IntExpr.Literal(BigInt(8)),
    signedness = Signedness.Unsigned,
    valueSemantics = PackedValueSemantics.BitVector
  )

  private def declaration(
      id: SymbolId,
      kind: DeclarationKind,
      origin: NameOrigin,
      externallyVisible: Boolean = false
  ): Declaration =
    Declaration(
      id = id,
      owner = scopeId,
      kind = kind,
      packedType = Some(packedType),
      nameOrigin = origin,
      sourceLocation = None,
      observability = Observability(
        complete = true,
        externallyVisible = externallyVisible
      )
    )

  private def reference(id: String, target: SymbolId): RtlExpr.Ref =
    RtlExpr.Ref(ReferenceId.unsafe(id), target, scopeId)

  private def driver(id: String, target: SymbolId, value: RtlExpr): Driver =
    Driver(
      id = DriverId.unsafe(id),
      owner = scopeId,
      target = target,
      kind = DriverKind.Continuous,
      coverage = DriverCoverage.FullObject,
      value = value
    )

  val design: Design = Design(
    version = CanonicalIrSchema.schemaVersion,
    stage = CanonicalIrSchema.stage,
    top = moduleId,
    modules = Vector(
      Module(
        id = moduleId,
        logicalName = "AllWireAssignmentCanonicalWitness",
        parameters = Vector.empty,
        scopes = Vector(Scope(scopeId, None, ScopeKind.Module)),
        generateIndices = Vector.empty,
        declarations = Vector(
          declaration(
            sourceId,
            DeclarationKind.Port(PortDirection.Input),
            NameOrigin.Explicit("source"),
            externallyVisible = true
          ),
          declaration(
            otherId,
            DeclarationKind.Port(PortDirection.Input),
            NameOrigin.Explicit("other"),
            externallyVisible = true
          ),
          declaration(
            unnamedId,
            DeclarationKind.InternalCombinational,
            NameOrigin.Unnamed
          ),
          declaration(
            namedId,
            DeclarationKind.InternalCombinational,
            NameOrigin.Explicit("namedAlias")
          ),
          declaration(
            expressionId,
            DeclarationKind.InternalCombinational,
            NameOrigin.Unnamed
          ),
          declaration(
            sinkId,
            DeclarationKind.Port(PortDirection.Output),
            NameOrigin.Explicit("sink"),
            externallyVisible = true
          )
        ),
        drivers = Vector(
          driver(
            "driver.all-wire-unnamed",
            unnamedId,
            reference("reference.all-wire-unnamed-source", sourceId)
          ),
          driver(
            "driver.all-wire-named",
            namedId,
            reference("reference.all-wire-named-unnamed", unnamedId)
          ),
          driver(
            "driver.all-wire-expression",
            expressionId,
            RtlExpr.Binary(
              RtlBinaryOperator.BitwiseXor,
              reference("reference.all-wire-expression-named", namedId),
              reference("reference.all-wire-expression-other", otherId)
            )
          ),
          driver(
            "driver.all-wire-sink",
            sinkId,
            reference("reference.all-wire-sink-expression", expressionId)
          )
        )
      )
    )
  )
}

private[examples] object AllWireAssignmentWitnessPhasePlan {
  def install(
      config: SpinalConfig,
      phase: Option[AllWireAssignmentNativePhase]
  ): Unit = {
    config.phasesInserters += { phases: ArrayBuffer[Phase] =>
      val nativeAliasPasses = phases.zipWithIndex.collect {
        case (_: PhaseRemoveIntermediateUnnameds, index) => index
      }
      if (nativeAliasPasses.size < 3)
        throw new IllegalStateException(
          s"WA-07 all-pass witness expected three native intermediate-removal phases, found ${nativeAliasPasses.size}"
        )

      val postWidthTypeCleanupIndex = nativeAliasPasses(1)
      phases.update(
        postWidthTypeCleanupIndex,
        new PhaseRemoveIntermediateUnnameds(true)
      )
      nativeAliasPasses.drop(3).reverse.foreach(index => phases.remove(index))
      val finalAliasCleanupIndex = nativeAliasPasses(2)
      phase match {
        case Some(value) => phases.update(finalAliasCleanupIndex, value)
        case None        => phases.remove(finalAliasCleanupIndex)
      }
    }
  }
}

/** Emits the unchanged reference or the one-flag all-pass candidate. */
object ParameterizedStreamFifoAllPassWitness {
  def main(args: Array[String]): Unit = {
    if (args.length != 4)
      throw new IllegalArgumentException(
        "usage: MODE(reference|candidate) OUTPUT_DIRECTORY OUTPUT_FILE REPORT_FILE"
      )

    val mode = args(0)
    val outputDirectory = Paths.get(args(1)).toAbsolutePath.normalize
    val outputFile = args(2)
    val reportFile = Paths.get(args(3)).toAbsolutePath.normalize
    val phase = mode match {
      case "reference" => None
      case "candidate" => Some(new AllWireAssignmentNativePhase)
      case other        => throw new IllegalArgumentException(s"unsupported witness mode '$other'")
    }

    Files.createDirectories(outputDirectory)
    Option(reportFile.getParent).foreach(path => Files.createDirectories(path))

    val config = SpinalConfig(
      targetDirectory = outputDirectory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    )
    config.netlistFileName = outputFile
    AllWireAssignmentWitnessPhasePlan.install(config, phase)

    val width = HdlInt.param(
      "WIDTH",
      default = BigInt(8),
      min = BigInt(1),
      max = BigInt(64)
    )
    val depth = HdlInt.param(
      "DEPTH",
      default = BigInt(5),
      min = BigInt(1),
      max = BigInt(8)
    )

    val generated = MorphVerilog(config) {
      new ParameterizedStreamFifo(width, depth)
    }
    val generatedPath = Paths
      .get(generated.generatedSourcesPaths.head)
      .toAbsolutePath
      .normalize
    val text = new String(Files.readAllBytes(generatedPath), StandardCharsets.UTF_8)
    if (!text.contains("parameter integer WIDTH") || !text.contains("parameter integer DEPTH"))
      throw new IllegalStateException("WA-07 all-pass witness lost symbolic parameters")

    val json = phase match {
      case Some(value) =>
        val result = value.report
        if (
          result.executedPasses != PassId.allWireAssignmentPasses.map(_.value) ||
          result.unnamed.eliminatedCount < 1 ||
          result.named.eliminatedCount < 1 ||
          result.expression.eliminatedCount < 1
        )
          throw new IllegalStateException(
            "WA-07 all-pass witness did not execute every pass in fixed order"
          )
        result.toJson
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
