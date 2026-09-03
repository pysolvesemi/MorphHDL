package morphhdl.examples

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

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
import morphhdl.ir.v1.ReferenceId
import morphhdl.ir.v1.RtlExpr
import morphhdl.ir.v1.Scope
import morphhdl.ir.v1.ScopeId
import morphhdl.ir.v1.ScopeKind
import morphhdl.ir.v1.Signedness
import morphhdl.ir.v1.SymbolId
import morphhdl.passes.api.IrSymbolId
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.WireAliasPassConfiguration
import morphhdl.passes.transform.UnnamedWireAliasEliminationPass
import spinal.core._
import spinal.core.internals._

/**
  * Test-only bridge proving that the WA-04 canonical pass can control an exact
  * native SpinalHDL graph rewrite before name allocation and Verilog emission.
  *
  * The bridge is deliberately conservative. It offers the canonical pass only
  * root-scope, full-object, direct BaseType aliases whose preservation, type,
  * use-context and cycle safety can be established from native object identity.
  * It never parses generated HDL and never recognizes a component or signal
  * name. Production handoff remains reserved for WA-07.
  */
private[examples] final class UnnamedWireAliasNativePhase extends Phase {
  private var completed = false
  private var visited = 0
  private var eliminated = Vector.empty[Int]
  private var rejected = Map.empty[String, Int]
  private var rewrittenReferences = 0

  def report: UnnamedWireAliasNativeReport = {
    if (!completed)
      throw new IllegalStateException("WA-04 native witness phase did not execute")
    UnnamedWireAliasNativeReport(
      visitedCandidates = visited,
      eliminatedOrdinals = eliminated,
      rejectedByReason = rejected,
      rewrittenReferences = rewrittenReferences
    )
  }

  override def hasNetlistImpact: Boolean = true

  override def impl(pc: PhaseContext): Unit = {
    val eliminatedBuilder = Vector.newBuilder[Int]
    var nextOrdinal = 0
    var progress = true

    while (progress) {
      progress = false
      val candidates = candidateSnapshot(pc)
      val iterator = candidates.iterator
      while (iterator.hasNext && !progress) {
        val candidate = iterator.next()
        val ordinal = nextOrdinal
        nextOrdinal += 1
        visited += 1

        proveCandidate(pc, candidate) match {
          case Left(reason) =>
            rejected = rejected.updated(reason, rejected.getOrElse(reason, 0) + 1)
          case Right(proof) =>
            applyCanonicalDecision(candidate, proof) match {
              case Left(reason) =>
                rejected = rejected.updated(reason, rejected.getOrElse(reason, 0) + 1)
              case Right(_) =>
                val replacements = rewriteNativeIdentity(
                  candidate.component,
                  candidate.alias,
                  candidate.source,
                  candidate.assignment
                )
                rewrittenReferences += replacements
                eliminatedBuilder += ordinal
                progress = true
            }
        }
      }
    }

    eliminated = eliminatedBuilder.result()
    completed = true
  }

  private final case class NativeCandidate(
      component: Component,
      alias: BaseType,
      source: BaseType,
      assignment: DataAssignmentStatement,
      useStatements: Vector[Statement]
  )

  private final case class NativeProof(
      packedType: PackedType,
      parameters: Vector[IntegerParameter]
  )

  private def candidateSnapshot(pc: PhaseContext): Vector[NativeCandidate] = {
    val values = Vector.newBuilder[NativeCandidate]
    pc.components().foreach { component =>
      val statements = statementsOf(component)
      component.dslBody.walkDeclarations {
        case alias: BaseType
            if alias.isUnnamed && alias.isComb && alias.isDirectionLess &&
              !alias.isAnalog && !alias.isTypeNode && alias.parentScope != null &&
              (alias.parentScope eq alias.rootScopeStatement) &&
              alias.hasOnlyOneStatement =>
          alias.head match {
            case assignment: DataAssignmentStatement
                if (assignment.parentScope eq alias.rootScopeStatement) &&
                  (assignment.target eq alias) &&
                  (assignment.finalTarget eq alias) =>
              assignment.source match {
                case source: BaseType if (source ne alias) =>
                  val uses = statements.filter(statement =>
                    (statement ne assignment) && references(statement, alias) > 0
                  )
                  values += NativeCandidate(
                    component,
                    alias,
                    source,
                    assignment,
                    uses
                  )
                case _ =>
              }
            case _ =>
          }
        case _ =>
      }
    }
    values.result()
  }

  private def proveCandidate(
      pc: PhaseContext,
      candidate: NativeCandidate
  ): Either[String, NativeProof] = {
    val alias = candidate.alias
    val source = candidate.source
    val assignment = candidate.assignment

    if ((source.component ne candidate.component) || source.parentScope == null)
      Left("WA04-NATIVE-SOURCE-BOUNDARY")
    else if (!(source.parentScope eq source.rootScopeStatement))
      Left("WA04-NATIVE-SOURCE-SCOPE")
    else if (source.isAnalog || source.isInOut)
      Left("WA04-NATIVE-SOURCE-KIND")
    else if (!preservationMetadataAllows(alias, assignment))
      Left("WA04-NATIVE-PRESERVATION")
    else if (!candidate.useStatements.forall(allowedUse(candidate.component, alias, _)))
      Left("WA04-NATIVE-USE-CONTEXT")
    else if (createsCycle(candidate.component, alias, source))
      Left("WA04-NATIVE-CYCLE")
    else
      packedTypeProof(alias, source) match {
        case Some(value) => Right(value)
        case None        => Left("WA04-NATIVE-PACKED-TYPE")
      }
  }

  private def preservationMetadataAllows(
      alias: BaseType,
      assignment: DataAssignmentStatement
  ): Boolean =
    !alias.isFrozen() &&
      alias.isEmptyOfTag &&
      !readPrivateBoolean(alias, "dontSimplify").getOrElse(true)

  private def allowedUse(
      component: Component,
      alias: BaseType,
      statement: Statement
  ): Boolean = statement match {
    case assignment: DataAssignmentStatement
        if assignment.parentScope != null &&
          (assignment.parentScope eq assignment.rootScopeStatement) &&
          (assignment.finalTarget ne alias) &&
          (assignment.finalTarget.component eq component) &&
          assignment.finalTarget.isComb &&
          !assignment.finalTarget.isAnalog &&
          !assignment.finalTarget.isInputOrInOut =>
      true
    case _ => false
  }

  private def packedTypeProof(
      alias: BaseType,
      source: BaseType
  ): Option[NativeProof] = {
    if (alias.getBitsWidth != source.getBitsWidth || alias.getBitsWidth < 1)
      return None

    val semantics = (packedSemantics(alias), packedSemantics(source)) match {
      case (Some(left), Some(right)) if left == right => left
      case _                                          => return None
    }

    (ParameterizedWidth.expressionOf(alias), ParameterizedWidth.expressionOf(source)) match {
      case (None, None) =>
        Some(
          NativeProof(
            PackedType(
              IntExpr.Literal(BigInt(alias.getBitsWidth)),
              semantics._1,
              semantics._2
            ),
            Vector.empty
          )
        )
      case (Some(left), Some(right)) if left eq right =>
        val minimum = left.minimum
        val maximum = left.maximum
        val size = maximum - minimum + 1
        if (
          minimum < 1 || maximum < minimum ||
          size > BigInt(morphhdl.ir.v1.CanonicalIrValidator.MaximumParameterDomainSize)
        ) None
        else {
          val parameterId = ParameterId.unsafe("parameter.native-width")
          val domain = (minimum to maximum).toVector
          Some(
            NativeProof(
              PackedType(
                IntExpr.ParameterRef(parameterId),
                semantics._1,
                semantics._2
              ),
              Vector(
                IntegerParameter(
                  id = parameterId,
                  name = "NATIVE_WIDTH",
                  default = left.default,
                  domain = IntegerParameterDomain(minimum, maximum, domain)
                )
              )
            )
          )
        }
      case _ => None
    }
  }

  private def packedSemantics(
      value: BaseType
  ): Option[(Signedness, PackedValueSemantics)] = value match {
    case _: Bool => Some(Signedness.Unsigned -> PackedValueSemantics.Boolean)
    case _: Bits => Some(Signedness.Unsigned -> PackedValueSemantics.BitVector)
    case _: UInt => Some(Signedness.Unsigned -> PackedValueSemantics.UnsignedInteger)
    case _: SInt => Some(Signedness.Signed -> PackedValueSemantics.SignedInteger)
    case _       => None
  }

  private def applyCanonicalDecision(
      candidate: NativeCandidate,
      proof: NativeProof
  ): Either[String, Unit] = {
    val moduleId = ModuleId.unsafe("module.native-witness")
    val scopeId = ScopeId.unsafe("scope.native-root")
    val sourceId = SymbolId.unsafe("symbol.native-source")
    val aliasId = SymbolId.unsafe("symbol.native-alias")
    val declarations = Vector.newBuilder[Declaration]
    val drivers = Vector.newBuilder[Driver]

    declarations += Declaration(
      id = sourceId,
      owner = scopeId,
      kind = sourceKind(candidate.source),
      packedType = Some(proof.packedType),
      nameOrigin = NameOrigin.Explicit("nativeSource"),
      sourceLocation = None,
      observability = sourceObservability(candidate.source)
    )
    declarations += Declaration(
      id = aliasId,
      owner = scopeId,
      kind = DeclarationKind.InternalCombinational,
      packedType = Some(proof.packedType),
      nameOrigin = NameOrigin.Unnamed,
      sourceLocation = None,
      observability = Observability.Unobserved
    )
    drivers += Driver(
      id = DriverId.unsafe("driver.native-alias"),
      owner = scopeId,
      target = aliasId,
      kind = DriverKind.Continuous,
      coverage = DriverCoverage.FullObject,
      value = RtlExpr.Ref(
        id = ReferenceId.unsafe("reference.native-alias-source"),
        target = sourceId,
        owner = scopeId
      )
    )

    candidate.useStatements.indices.foreach { index =>
      val sinkId = SymbolId.unsafe(s"symbol.native-sink-$index")
      declarations += Declaration(
        id = sinkId,
        owner = scopeId,
        kind = DeclarationKind.Port(PortDirection.Output),
        packedType = Some(proof.packedType),
        nameOrigin = NameOrigin.Explicit(s"nativeSink$index"),
        sourceLocation = None,
        observability = Observability(complete = true, externallyVisible = true)
      )
      drivers += Driver(
        id = DriverId.unsafe(s"driver.native-sink-$index"),
        owner = scopeId,
        target = sinkId,
        kind = DriverKind.Continuous,
        coverage = DriverCoverage.FullObject,
        value = RtlExpr.Ref(
          id = ReferenceId.unsafe(s"reference.native-sink-$index-alias"),
          target = aliasId,
          owner = scopeId
        )
      )
    }

    val canonical = Design(
      version = CanonicalIrSchema.schemaVersion,
      stage = CanonicalIrSchema.stage,
      top = moduleId,
      modules = Vector(
        Module(
          id = moduleId,
          logicalName = "NativeWitnessModule",
          parameters = proof.parameters,
          scopes = Vector(
            Scope(
              id = scopeId,
              parent = None,
              kind = ScopeKind.Module,
              label = None,
              sourceLocation = None
            )
          ),
          generateIndices = Vector.empty,
          declarations = declarations.result(),
          drivers = drivers.result(),
          sourceLocation = None
        )
      )
    )

    val result = UnnamedWireAliasEliminationPass.run(
      canonical,
      WireAliasPassConfiguration(eliminateUnnamedAliases = true)
    )
    if (
      result.status == PassExecutionStatus.Changed &&
      result.eliminationReport.eliminated.map(_.aliasSymbol) ==
        Vector(IrSymbolId.unsafe(aliasId.value)) &&
      result.eliminationReport.eliminated.head.sourceSymbol ==
        IrSymbolId.unsafe(sourceId.value)
    ) Right(())
    else Left("WA04-NATIVE-CANONICAL-DECISION")
  }

  private def sourceKind(value: BaseType): DeclarationKind =
    if (value.isInput) DeclarationKind.Port(PortDirection.Input)
    else if (value.isOutput) DeclarationKind.Port(PortDirection.Output)
    else if (value.isReg) DeclarationKind.Register
    else DeclarationKind.InternalCombinational

  private def sourceObservability(value: BaseType): Observability =
    Observability(
      complete = true,
      externallyVisible = value.isInput || value.isOutput
    )

  private def rewriteNativeIdentity(
      component: Component,
      alias: BaseType,
      source: BaseType,
      aliasAssignment: DataAssignmentStatement
  ): Int = {
    val statements = statementsOf(component)
    var replacements = 0
    statements.foreach { statement =>
      if (statement ne aliasAssignment) {
        statement.walkRemapDrivingExpressions {
          case reference: BaseType if reference eq alias =>
            replacements += 1
            source
          case other => other
        }
      }
    }

    aliasAssignment.removeStatement()
    alias.removeStatement()

    val remaining = statementsOf(component).map(references(_, alias)).sum
    if (remaining != 0)
      throw new IllegalStateException(
        s"WA-04 native witness rewrite left $remaining reference(s) to a removed identity"
      )
    replacements
  }

  private def createsCycle(
      component: Component,
      alias: BaseType,
      source: BaseType
  ): Boolean = {
    val edges = scala.collection.mutable.LinkedHashMap.empty[BaseType, Vector[BaseType]]
    statementsOf(component).foreach {
      case assignment: DataAssignmentStatement
          if assignment.parentScope != null &&
            (assignment.parentScope eq assignment.rootScopeStatement) &&
            (assignment.target eq assignment.finalTarget) &&
            assignment.finalTarget.isComb =>
        val dependencies = referencedBaseTypes(assignment.source)
          .filter(_.component eq component)
          .distinct
        edges.update(assignment.finalTarget, dependencies)
      case _ =>
    }

    val pending = scala.collection.mutable.Stack[BaseType](source)
    val visited = scala.collection.mutable.HashSet.empty[BaseType]
    while (pending.nonEmpty) {
      val current = pending.pop()
      if (current eq alias) return true
      if (!visited.contains(current)) {
        visited += current
        edges.getOrElse(current, Vector.empty).reverse.foreach(pending.push)
      }
    }
    false
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
      case _                                  =>
    }
    count
  }

  private def referencedBaseTypes(expression: Expression): Vector[BaseType] = {
    val values = Vector.newBuilder[BaseType]
    expression match {
      case value: BaseType => values += value
      case _               =>
    }
    expression.walkDrivingExpressions {
      case value: BaseType => values += value
      case _               =>
    }
    values.result()
  }

  private def readPrivateBoolean(
      value: AnyRef,
      name: String
  ): Option[Boolean] = {
    var current: Class[_] = value.getClass
    while (current != null) {
      try {
        val field = current.getDeclaredField(name)
        field.setAccessible(true)
        return Some(field.getBoolean(value))
      } catch {
        case _: NoSuchFieldException => current = current.getSuperclass
        case _: Throwable            => return None
      }
    }
    None
  }
}

private[examples] final case class UnnamedWireAliasNativeReport(
    visitedCandidates: Int,
    eliminatedOrdinals: Vector[Int],
    rejectedByReason: Map[String, Int],
    rewrittenReferences: Int
) {
  def eliminatedCount: Int = eliminatedOrdinals.size

  def toJson: String = {
    val rejected = rejectedByReason.toVector.sortBy(_._1).map { case (key, value) =>
      s"    ${quote(key)}: $value"
    }
    Vector(
      "{",
      "  \"schema_version\": 1,",
      "  \"pass_id\": \"wire-alias-unnamed\",",
      "  \"executed_before_name_allocation\": true,",
      s"""  "visited_candidates": $visitedCandidates,""",
      s"""  "eliminated_count": $eliminatedCount,""",
      s"""  "rewritten_reference_count": $rewrittenReferences,""",
      s"""  "eliminated_ordinals": [${eliminatedOrdinals.mkString(", ")}],""",
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

private[examples] object UnnamedWireAliasWitnessPhasePlan {
  def install(
      config: SpinalConfig,
      phase: Option[UnnamedWireAliasNativePhase]
  ): Unit = {
    config.phasesInserters += { phases: ArrayBuffer[Phase] =>
      val nativeAliasPasses = phases.zipWithIndex.collect {
        case (value: PhaseRemoveIntermediateUnnameds, index) => index
      }
      if (nativeAliasPasses.size < 3)
        throw new IllegalStateException(
          s"WA-04 witness expected three native unnamed-removal phases, found ${nativeAliasPasses.size}"
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
  * Emits either the common pre-pass StreamFifo reference or the candidate
  * produced after the actual WA-04 canonical decision and native identity
  * rewrite. Both legs use the same MorphHDL structured parameterized backend.
  */
object ParameterizedStreamFifoUnnamedPassWitness {
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
      case "candidate" => Some(new UnnamedWireAliasNativePhase)
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
    UnnamedWireAliasWitnessPhasePlan.install(config, phase)

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
        "WA-04 witness lost symbolic WIDTH or DEPTH during structured emission"
      )

    val json = phase match {
      case Some(value) =>
        val result = value.report
        if (result.eliminatedCount < 1)
          throw new IllegalStateException(
            "WA-04 witness phase executed but eliminated no unnamed alias"
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
