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
  * Test-only proof bridge for the WA-06 ordered pipeline.
  *
  * The canonical pipeline first authorizes the exact unnamed-then-named order on
  * a component-neutral identity graph. The already reviewed WA-04 and WA-05
  * native witness phases are then executed in that same order on the shared
  * source graph before name allocation. Production orchestration remains WA-07.
  */
private[examples] final class OrderedWireAliasNativePhase extends Phase {
  private val unnamedPhase = new UnnamedWireAliasNativePhase
  private val namedPhase = new NamedWireAliasNativePhase
  private var completed = false
  private var executedPasses = Vector.empty[String]

  override def hasNetlistImpact: Boolean = true

  override def impl(pc: PhaseContext): Unit = {
    if (completed)
      throw new IllegalStateException("WA-06 ordered witness phase executed more than once")

    val pipeline = WireAliasPassPipeline.run(
      OrderedWireAliasCanonicalWitness.design,
      WireAliasPassConfiguration.selectedForTesting(
        morphhdl.passes.api.PassId.UnnamedWireAliasElimination,
        morphhdl.passes.api.PassId.NamedWireAliasElimination
      )
    )
    val expected = Vector(
      PassId.UnnamedWireAliasElimination,
      PassId.NamedWireAliasElimination
    )
    if (
      pipeline.status != PassExecutionStatus.Changed ||
      pipeline.executedPasses != expected ||
      pipeline.eliminationReports.map(_.eliminatedCount) != Vector(1, 1)
    )
      throw new IllegalStateException(
        "WA-06 canonical pipeline did not authorize one unnamed stage followed by one named stage"
      )

    executedPasses = pipeline.executedPasses.map(_.value)
    unnamedPhase.impl(pc)
    val unnamed = unnamedPhase.report
    if (unnamed.eliminatedCount < 1 || unnamed.rewrittenReferences < 1)
      throw new IllegalStateException(
        "WA-06 ordered witness eliminated no unnamed alias"
      )

    namedPhase.impl(pc)
    val named = namedPhase.report
    if (
      named.eliminatedCount < 1 || named.rewrittenReferences < 1 ||
      named.eliminatedNames.isEmpty
    )
      throw new IllegalStateException(
        "WA-06 ordered witness eliminated no named alias"
      )

    completed = true
  }

  def report: OrderedWireAliasNativeReport = {
    if (!completed)
      throw new IllegalStateException("WA-06 ordered witness phase did not execute")
    OrderedWireAliasNativeReport(
      executedPasses = executedPasses,
      unnamed = unnamedPhase.report,
      named = namedPhase.report
    )
  }
}

private[examples] final case class OrderedWireAliasNativeReport(
    executedPasses: Vector[String],
    unnamed: UnnamedWireAliasNativeReport,
    named: NamedWireAliasNativeReport
) {
  def eliminatedCount: Int = unnamed.eliminatedCount + named.eliminatedCount
  def rewrittenReferenceCount: Int =
    unnamed.rewrittenReferences + named.rewrittenReferences

  def toJson: String = {
    val names = named.eliminatedNames.sorted.map(quote).mkString(", ")
    val passes = executedPasses.map(quote).mkString(", ")
    Vector(
      "{",
      "  \"schema_version\": 1,",
      "  \"pass_id\": \"wire-alias-unnamed+wire-alias-named\",",
      "  \"pipeline_status\": \"changed\",",
      "  \"executed_before_name_allocation\": true,",
      s"""  "executed_passes": [$passes],""",
      s"""  "unnamed_eliminated_count": ${unnamed.eliminatedCount},""",
      s"""  "named_eliminated_count": ${named.eliminatedCount},""",
      s"""  "eliminated_count": $eliminatedCount,""",
      s"""  "rewritten_reference_count": $rewrittenReferenceCount,""",
      s"""  "eliminated_names": [$names]""",
      "}",
      ""
    ).mkString("\n")
  }

  private def quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** Small component-neutral canonical chain used only to prove pipeline order. */
private[examples] object OrderedWireAliasCanonicalWitness {
  private val moduleId = ModuleId.unsafe("module.ordered-witness")
  private val scopeId = ScopeId.unsafe("scope.ordered-witness")
  private val sourceId = SymbolId.unsafe("symbol.ordered-source")
  private val unnamedId = SymbolId.unsafe("symbol.ordered-unnamed")
  private val namedId = SymbolId.unsafe("symbol.ordered-named")
  private val sinkId = SymbolId.unsafe("symbol.ordered-sink")
  private val packedType = PackedType(
    width = IntExpr.Literal(BigInt(8)),
    signedness = Signedness.Unsigned,
    valueSemantics = PackedValueSemantics.BitVector
  )

  val design: Design = Design(
    version = CanonicalIrSchema.schemaVersion,
    stage = CanonicalIrSchema.stage,
    top = moduleId,
    modules = Vector(
      Module(
        id = moduleId,
        logicalName = "OrderedCanonicalWitness",
        parameters = Vector.empty,
        scopes = Vector(
          Scope(
            id = scopeId,
            parent = None,
            kind = ScopeKind.Module
          )
        ),
        generateIndices = Vector.empty,
        declarations = Vector(
          Declaration(
            id = sourceId,
            owner = scopeId,
            kind = DeclarationKind.Port(PortDirection.Input),
            packedType = Some(packedType),
            nameOrigin = NameOrigin.Explicit("source"),
            sourceLocation = None,
            observability = Observability(
              complete = true,
              externallyVisible = true
            )
          ),
          Declaration(
            id = unnamedId,
            owner = scopeId,
            kind = DeclarationKind.InternalCombinational,
            packedType = Some(packedType),
            nameOrigin = NameOrigin.Unnamed,
            sourceLocation = None,
            observability = Observability.Unobserved
          ),
          Declaration(
            id = namedId,
            owner = scopeId,
            kind = DeclarationKind.InternalCombinational,
            packedType = Some(packedType),
            nameOrigin = NameOrigin.Explicit("orderedNamedAlias"),
            sourceLocation = None,
            observability = Observability.Unobserved
          ),
          Declaration(
            id = sinkId,
            owner = scopeId,
            kind = DeclarationKind.Port(PortDirection.Output),
            packedType = Some(packedType),
            nameOrigin = NameOrigin.Explicit("sink"),
            sourceLocation = None,
            observability = Observability(
              complete = true,
              externallyVisible = true
            )
          )
        ),
        drivers = Vector(
          Driver(
            id = DriverId.unsafe("driver.ordered-unnamed"),
            owner = scopeId,
            target = unnamedId,
            kind = DriverKind.Continuous,
            coverage = DriverCoverage.FullObject,
            value = RtlExpr.Ref(
              id = ReferenceId.unsafe("reference.ordered-unnamed-source"),
              target = sourceId,
              owner = scopeId
            )
          ),
          Driver(
            id = DriverId.unsafe("driver.ordered-named"),
            owner = scopeId,
            target = namedId,
            kind = DriverKind.Continuous,
            coverage = DriverCoverage.FullObject,
            value = RtlExpr.Ref(
              id = ReferenceId.unsafe("reference.ordered-named-unnamed"),
              target = unnamedId,
              owner = scopeId
            )
          ),
          Driver(
            id = DriverId.unsafe("driver.ordered-sink"),
            owner = scopeId,
            target = sinkId,
            kind = DriverKind.Continuous,
            coverage = DriverCoverage.FullObject,
            value = RtlExpr.Ref(
              id = ReferenceId.unsafe("reference.ordered-sink-named"),
              target = namedId,
              owner = scopeId
            )
          )
        )
      )
    )
  )
}

private[examples] object OrderedWireAliasWitnessPhasePlan {
  def install(
      config: SpinalConfig,
      phase: Option[OrderedWireAliasNativePhase]
  ): Unit = {
    config.phasesInserters += { phases: ArrayBuffer[Phase] =>
      val nativeAliasPasses = phases.zipWithIndex.collect {
        case (_: PhaseRemoveIntermediateUnnameds, index) => index
      }
      if (nativeAliasPasses.size < 3)
        throw new IllegalStateException(
          s"WA-06 witness expected three native alias-removal phases, found ${nativeAliasPasses.size}"
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

/**
  * Emits the unchanged common pre-pass reference or the ordered WA-06 candidate.
  * Both legs use the same MorphHDL structured parameterized Verilog-2001 backend.
  */
object ParameterizedStreamFifoCombinedPassWitness {
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
      case "candidate" => Some(new OrderedWireAliasNativePhase)
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
    OrderedWireAliasWitnessPhasePlan.install(config, phase)

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
      throw new IllegalStateException(
        "WA-06 witness lost symbolic WIDTH or DEPTH during structured emission"
      )

    val json = phase match {
      case Some(value) =>
        val result = value.report
        if (
          result.unnamed.eliminatedCount < 1 ||
          result.named.eliminatedCount < 1 ||
          result.executedPasses != Vector(
            PassId.UnnamedWireAliasElimination.value,
            PassId.NamedWireAliasElimination.value
          )
        )
          throw new IllegalStateException(
            "WA-06 witness did not execute both passes in fixed order"
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
