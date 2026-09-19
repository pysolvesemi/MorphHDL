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
import morphhdl.ir.v1.IntegerParameter
import morphhdl.ir.v1.IntegerParameterDomain
import morphhdl.ir.v1.Module
import morphhdl.ir.v1.ModuleId
import morphhdl.ir.v1.NameOrigin
import morphhdl.ir.v1.Observability
import morphhdl.ir.v1.PackedType
import morphhdl.ir.v1.PackedValueSemantics
import morphhdl.ir.v1.ParameterId
import morphhdl.ir.v1.PortDirection
import morphhdl.ir.v1.Scope
import morphhdl.ir.v1.ScopeId
import morphhdl.ir.v1.ScopeKind
import morphhdl.ir.v1.Signedness
import morphhdl.ir.v1.SymbolId
import morphhdl.passes.api.IrSymbolId
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.PassId
import morphhdl.passes.api.WireAliasPassConfiguration
import morphhdl.passes.transform.UnnamedWireExpressionEliminationPass
import spinal.core._
import spinal.core.internals._

/** Native unnamed-expression slot in the existing default-enabled pipeline.
  * WA-10 shares exact typed capture and native identity substitution with the
  * named/generated slot, including nested pure RHS and register RHS uses.
  */
private[examples] final class UnnamedWireExpressionNativePhase extends Phase {
  // The unnamed and named slots retain separate provenance and canonical pass
  // identities, while sharing one lossless typed capture/writeback authority.
  private val delegate = new NamedWireExpressionNativePhase(unnamedOnly = true)
  override def hasNetlistImpact: Boolean = true
  override def impl(pc: PhaseContext): Unit = delegate.impl(pc)

  private[examples] def isIndependentlyRemovable(pc: PhaseContext, value: BaseType): Boolean =
    delegate.isIndependentlyRemovableWithOrigin(pc, value, NameOrigin.Unnamed)

  def report: UnnamedWireExpressionNativeReport = {
    val value = delegate.report
    UnnamedWireExpressionNativeReport(value.visitedCandidates, value.eliminatedOrdinals,
      value.rejectedByReason, value.rewrittenReferences, value.expressionOperators,
      value.proceduralReceiverRewrites)
  }
}

private[examples] final case class UnnamedWireExpressionNativeReport(
    visitedCandidates: Int,
    eliminatedOrdinals: Vector[Int],
    rejectedByReason: Map[String, Int],
    rewrittenReferences: Int,
    expressionOperators: Vector[String],
    proceduralReceiverRewrites: Int = 0
) {
  def eliminatedCount: Int = eliminatedOrdinals.size

  def toJson: String = {
    val rejected = rejectedByReason.toVector.sortBy(_._1).map { case (key, value) =>
      s"    ${quote(key)}: $value"
    }
    val operators = expressionOperators.map(quote).mkString(", ")
    Vector(
      "{",
      "  \"schema_version\": 1,",
      "  \"pass_id\": \"wire-expression-unnamed\",",
      "  \"pipeline_status\": \"changed\",",
      "  \"executed_before_name_allocation\": true,",
      s"""  "procedural_receiver_rewrites": $proceduralReceiverRewrites,""",
      s"""  "visited_candidates": $visitedCandidates,""",
      s"""  "eliminated_count": $eliminatedCount,""",
      s"""  "rewritten_reference_count": $rewrittenReferences,""",
      s"""  "eliminated_ordinals": [${eliminatedOrdinals.mkString(", ")}],""",
      s"""  "expression_operators": [$operators],""",
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

private[examples] object UnnamedWireExpressionWitnessPhasePlan {
  def install(
      config: SpinalConfig,
      phase: Option[UnnamedWireExpressionNativePhase]
  ): Unit = {
    config.phasesInserters += { phases: ArrayBuffer[Phase] =>
      val nativeAliasPasses = phases.zipWithIndex.collect {
        case (_: PhaseRemoveIntermediateUnnameds, index) => index
      }
      if (nativeAliasPasses.size < 3)
        throw new IllegalStateException(
          s"WA-07 expression witness expected three native intermediate-removal phases, found ${nativeAliasPasses.size}"
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

/** Emits the unchanged reference or the WA-07 expression-inlined candidate. */
object ParameterizedStreamFifoExpressionPassWitness {
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
      case "candidate" => Some(new UnnamedWireExpressionNativePhase)
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
    UnnamedWireExpressionWitnessPhasePlan.install(config, phase)

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

    val generated = MorphVerilog(morphhdl.MorphWireAssignmentPasses(config, enabled = false)) {
      new ParameterizedStreamFifo(width, depth)
    }
    val generatedPath = Paths
      .get(generated.generatedSourcesPaths.head)
      .toAbsolutePath
      .normalize
    val text = new String(Files.readAllBytes(generatedPath), StandardCharsets.UTF_8)
    if (!text.contains("parameter integer WIDTH") || !text.contains("parameter integer DEPTH"))
      throw new IllegalStateException(
        "WA-07 expression witness lost symbolic WIDTH or DEPTH"
      )

    val json = phase match {
      case Some(value) =>
        val result = value.report
        if (
          result.eliminatedCount < 1 || result.rewrittenReferences < 1 ||
          result.expressionOperators.isEmpty
        )
          throw new IllegalStateException(
            "WA-07 witness executed but inlined no unnamed expression temporary"
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
