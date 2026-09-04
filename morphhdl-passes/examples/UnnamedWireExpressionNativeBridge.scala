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
import morphhdl.ir.v1.ReferenceId
import morphhdl.ir.v1.RtlBinaryOperator
import morphhdl.ir.v1.RtlExpr
import morphhdl.ir.v1.RtlUnaryOperator
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

/**
  * Test-only bridge proving the WA-07 expression pass on the native graph.
  *
  * Only an unnamed, directionless, root-scope combinational declaration with
  * one root-scope full assignment from a non-reference expression is offered to
  * the canonical pass. Every receiver must also be a root-scope continuous data
  * assignment. Consequently, an assignment represented inside a When/Switch
  * tree, register process, or another Verilog `always` block is never rewritten.
  * Native selected uses also fail closed; selected-use composition is proved by
  * the canonical IR tests rather than hidden by recursive native remapping.
  *
  * Selection is based on native object identity and source/elaboration naming
  * provenance. No component name, source filename, backend-generated temporary identifier text, or emitted HDL
  * is inspected. Production publication and writeback remain WA-08 scope.
  */
private[examples] final class UnnamedWireExpressionNativePhase extends Phase {
  private var completed = false
  private var visited = 0
  private var eliminated = Vector.empty[Int]
  private var rejected = Map.empty[String, Int]
  private var rewrittenReferences = 0
  private var expressionOperators = Vector.empty[String]

  def report: UnnamedWireExpressionNativeReport = {
    if (!completed)
      throw new IllegalStateException("WA-07 expression witness phase did not execute")
    UnnamedWireExpressionNativeReport(
      visitedCandidates = visited,
      eliminatedOrdinals = eliminated,
      rejectedByReason = rejected,
      rewrittenReferences = rewrittenReferences,
      expressionOperators = expressionOperators
    )
  }

  override def hasNetlistImpact: Boolean = true

  override def impl(pc: PhaseContext): Unit = {
    if (completed)
      throw new IllegalStateException("WA-07 expression witness phase executed more than once")

    val eliminatedBuilder = Vector.newBuilder[Int]
    val operatorBuilder = Vector.newBuilder[String]
    var nextOrdinal = 0
    var progress = true

    while (progress) {
      progress = false
      val iterator = candidateSnapshot(pc).iterator
      while (iterator.hasNext && !progress) {
        val candidate = iterator.next()
        val ordinal = nextOrdinal
        nextOrdinal += 1
        visited += 1

        proveCandidate(candidate) match {
          case Left(reason) =>
            rejected = rejected.updated(reason, rejected.getOrElse(reason, 0) + 1)
          case Right(proof) =>
            applyCanonicalDecision(candidate, proof) match {
              case Left(reason) =>
                rejected = rejected.updated(reason, rejected.getOrElse(reason, 0) + 1)
              case Right(_) =>
                val replacements = rewriteNativeIdentity(candidate)
                if (replacements < 1)
                  throw new IllegalStateException(
                    "WA-07 native expression rewrite removed a temporary without replacing a receiver"
                  )
                rewrittenReferences += replacements
                operatorBuilder += candidate.sourceExpression.opName
                eliminatedBuilder += ordinal
                progress = true
            }
        }
      }
    }

    eliminated = eliminatedBuilder.result()
    expressionOperators = operatorBuilder.result()
    completed = true
  }

  private final case class NativeCandidate(
      component: Component,
      alias: BaseType,
      sourceExpression: Expression,
      sourceReferences: Vector[BaseType],
      assignment: DataAssignmentStatement,
      useStatements: Vector[Statement]
  ) {
    def receiverOccurrenceCount: Int =
      useStatements.map(references(_, alias)).sum
  }

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
                if assignment.parentScope != null &&
                  (assignment.parentScope eq alias.rootScopeStatement) &&
                  (assignment.target eq alias) &&
                  (assignment.finalTarget eq alias) &&
                  assignment.source != null =>
              assignment.source match {
                case _: BaseType =>
                  // Direct aliases are deliberately left to WA-04.
                case expression =>
                  val uses = statements.filter { statement =>
                    (statement ne assignment) && references(statement, alias) > 0
                  }
                  values += NativeCandidate(
                    component = component,
                    alias = alias,
                    sourceExpression = expression,
                    sourceReferences = referencedBaseTypes(expression).distinct,
                    assignment = assignment,
                    useStatements = uses
                  )
              }
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
    val alias = candidate.alias
    val expression = candidate.sourceExpression

    if (!preservationMetadataAllows(alias))
      Left("WA07-NATIVE-PRESERVATION")
    else if (candidate.useStatements.isEmpty)
      Left("WA07-NATIVE-NO-RECEIVER")
    else if (candidate.useStatements.exists(selectedAliasUse(_, alias)))
      Left("WA07-NATIVE-SELECTED-RECEIVER")
    else if (!candidate.useStatements.forall(allowedUse(candidate.component, alias, _)))
      Left("WA07-NATIVE-PROCEDURAL-OR-EXCLUDED-RECEIVER")
    else if (candidate.receiverOccurrenceCount < 1)
      Left("WA07-NATIVE-NO-RECEIVER")
    else if (
      candidate.sourceReferences.exists { source =>
        (source eq alias) || (source.component ne candidate.component) ||
        source.parentScope == null || !(source.parentScope eq source.rootScopeStatement)
      }
    )
      Left("WA07-NATIVE-SOURCE-BOUNDARY")
    else if (
      candidate.sourceReferences.exists(source => source.isAnalog || source.isInOut)
    )
      Left("WA07-NATIVE-SOURCE-KIND")
    else if (createsCycle(candidate))
      Left("WA07-NATIVE-CYCLE")
    else
      packedTypeProof(alias, expression) match {
        case Some(value) => Right(value)
        case None        => Left("WA07-NATIVE-PACKED-TYPE")
      }
  }

  private def preservationMetadataAllows(alias: BaseType): Boolean =
    !alias.isFrozen() &&
      alias.isEmptyOfTag &&
      !readPrivateBoolean(alias, "dontSimplify").getOrElse(true)


private def selectedAliasUse(
    statement: Statement,
    alias: BaseType
): Boolean = {
  var selected = false
  statement.walkDrivingExpressions {
    case access: SubAccess
        if expressionReferences(access.getBitVector, alias) =>
      selected = true
    case _ =>
  }
  selected
}

private def expressionReferences(
    expression: Expression,
    target: BaseType
): Boolean = {
  if (expression == null) false
  else if (expression eq target) true
  else {
    var found = false
    expression.walkDrivingExpressions {
      case value: BaseType if value eq target => found = true
      case _                                  =>
    }
    found
  }
}

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
    case _ =>
      // Tree-scoped assignments become procedural Verilog and are retained.
      false
  }

  private def packedTypeProof(
      alias: BaseType,
      expression: Expression
  ): Option[NativeProof] = {
    val expressionWidth = expression match {
      case value: WidthProvider => value.getWidth
      case _                    => return None
    }
    if (
      expressionWidth != alias.getBitsWidth || alias.getBitsWidth < 1 ||
      expression.getTypeObject != alias.getTypeObject
    ) return None

    val semantics = packedSemantics(alias).getOrElse(return None)
    ParameterizedWidth.expressionOf(alias) match {
      case None =>
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
      case Some(width) =>
        val minimum = width.minimum
        val maximum = width.maximum
        val size = maximum - minimum + 1
        if (
          minimum < 1 || maximum < minimum ||
          size > BigInt(morphhdl.ir.v1.CanonicalIrValidator.MaximumParameterDomainSize)
        ) None
        else {
          val parameterId = ParameterId.unsafe("parameter.native-expression-width")
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
                  name = "NATIVE_EXPRESSION_WIDTH",
                  default = width.default,
                  domain = IntegerParameterDomain(
                    minimum,
                    maximum,
                    (minimum to maximum).toVector
                  )
                )
              )
            )
          )
        }
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
    val moduleId = ModuleId.unsafe("module.native-expression-witness")
    val scopeId = ScopeId.unsafe("scope.native-expression-root")
    val aliasId = SymbolId.unsafe("symbol.native-expression-alias")
    val declarations = Vector.newBuilder[Declaration]
    val drivers = Vector.newBuilder[Driver]

    val sourcePairs = candidate.sourceReferences.zipWithIndex.map {
      case (source, index) =>
        val sourceId = SymbolId.unsafe(s"symbol.native-expression-source-$index")
        declarations += Declaration(
          id = sourceId,
          owner = scopeId,
          kind = sourceKind(source),
          packedType = Some(proof.packedType),
          nameOrigin = NameOrigin.Explicit(s"nativeExpressionSource$index"),
          sourceLocation = None,
          observability = sourceObservability(source)
        )
        source -> sourceId
    }

    declarations += Declaration(
      id = aliasId,
      owner = scopeId,
      kind = DeclarationKind.InternalCombinational,
      packedType = Some(proof.packedType),
      nameOrigin = NameOrigin.Unnamed,
      sourceLocation = None,
      observability = Observability.Unobserved
    )

    val sourceRefs = sourcePairs.zipWithIndex.map {
      case ((_, sourceId), index) =>
        RtlExpr.Ref(
          id = ReferenceId.unsafe(s"reference.native-expression-source-$index"),
          target = sourceId,
          owner = scopeId
        ): RtlExpr
    }
    val canonicalExpression: RtlExpr = sourceRefs.headOption match {
      case None =>
        RtlExpr.Literal(BigInt(0), candidate.alias.getBitsWidth)
      case Some(head) =>
        val combined = sourceRefs.tail.foldLeft(head) { (left, right) =>
          RtlExpr.Binary(RtlBinaryOperator.BitwiseXor, left, right)
        }
        RtlExpr.Unary(
          RtlUnaryOperator.BitwiseNot,
          RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, combined)
        )
    }

    drivers += Driver(
      id = DriverId.unsafe("driver.native-expression-alias"),
      owner = scopeId,
      target = aliasId,
      kind = DriverKind.Continuous,
      coverage = DriverCoverage.FullObject,
      value = canonicalExpression
    )

    var receiverOrdinal = 0
    candidate.useStatements.foreach { statement =>
      val sinkId = SymbolId.unsafe(s"symbol.native-expression-sink-$receiverOrdinal")
      val occurrenceCount = references(statement, candidate.alias)
      declarations += Declaration(
        id = sinkId,
        owner = scopeId,
        kind = DeclarationKind.Port(PortDirection.Output),
        packedType = Some(proof.packedType),
        nameOrigin = NameOrigin.Explicit(s"nativeExpressionSink$receiverOrdinal"),
        sourceLocation = None,
        observability = Observability(complete = true, externallyVisible = true)
      )
      val aliases = Vector.tabulate(occurrenceCount) { occurrence =>
        RtlExpr.Ref(
          id = ReferenceId.unsafe(
            s"reference.native-expression-sink-$receiverOrdinal-alias-$occurrence"
          ),
          target = aliasId,
          owner = scopeId
        ): RtlExpr
      }
      val receiverValue = aliases.tail.foldLeft(aliases.head) { (left, right) =>
        RtlExpr.Binary(RtlBinaryOperator.BitwiseXor, left, right)
      }
      drivers += Driver(
        id = DriverId.unsafe(s"driver.native-expression-sink-$receiverOrdinal"),
        owner = scopeId,
        target = sinkId,
        kind = DriverKind.Continuous,
        coverage = DriverCoverage.FullObject,
        value = receiverValue
      )
      receiverOrdinal += 1
    }

    val canonical = Design(
      version = CanonicalIrSchema.schemaVersion,
      stage = CanonicalIrSchema.stage,
      top = moduleId,
      modules = Vector(
        Module(
          id = moduleId,
          logicalName = "NativeExpressionWitnessModule",
          parameters = proof.parameters,
          scopes = Vector(
            Scope(
              id = scopeId,
              parent = None,
              kind = ScopeKind.Module
            )
          ),
          generateIndices = Vector.empty,
          declarations = declarations.result(),
          drivers = drivers.result(),
          sourceLocation = None
        )
      )
    )

    val result = UnnamedWireExpressionEliminationPass.run(
      canonical,
      WireAliasPassConfiguration.selectedForTesting(
        PassId.UnnamedWireExpressionElimination
      )
    )
    val eliminated = result.eliminationReport.eliminatedExpressions
    if (
      result.status == PassExecutionStatus.Changed &&
      eliminated.map(_.aliasSymbol) == Vector(IrSymbolId.unsafe(aliasId.value)) &&
      eliminated.head.receiverCount == candidate.receiverOccurrenceCount
    ) Right(())
    else Left("WA07-NATIVE-CANONICAL-DECISION")
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

  private def rewriteNativeIdentity(candidate: NativeCandidate): Int = {
    val statements = statementsOf(candidate.component)
    var replacements = 0
    candidate.useStatements.foreach { statement =>
      statement.walkRemapDrivingExpressions {
        case reference: BaseType if reference eq candidate.alias =>
          replacements += 1
          candidate.sourceExpression
        case other => other
      }
    }

    candidate.assignment.removeStatement()
    candidate.alias.removeStatement()

    val remaining = statementsOf(candidate.component).map(references(_, candidate.alias)).sum
    if (remaining != 0)
      throw new IllegalStateException(
        s"WA-07 native expression rewrite left $remaining reference(s) to a removed identity"
      )
    replacements
  }

  private def createsCycle(candidate: NativeCandidate): Boolean = {
    if (candidate.sourceReferences.exists(_ eq candidate.alias)) return true

    val edges = scala.collection.mutable.LinkedHashMap.empty[BaseType, Vector[BaseType]]
    statementsOf(candidate.component).foreach {
      case assignment: DataAssignmentStatement
          if assignment.parentScope != null &&
            (assignment.parentScope eq assignment.rootScopeStatement) &&
            (assignment.target eq assignment.finalTarget) &&
            assignment.finalTarget.isComb =>
        val dependencies = referencedBaseTypes(assignment.source)
          .filter(_.component eq candidate.component)
          .distinct
        edges.update(assignment.finalTarget, dependencies)
      case _ =>
    }

    candidate.sourceReferences.exists { source =>
      val pending = scala.collection.mutable.Stack[BaseType](source)
      val visited = scala.collection.mutable.HashSet.empty[BaseType]
      var found = false
      while (pending.nonEmpty && !found) {
        val current = pending.pop()
        if (current eq candidate.alias) found = true
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

private[examples] final case class UnnamedWireExpressionNativeReport(
    visitedCandidates: Int,
    eliminatedOrdinals: Vector[Int],
    rejectedByReason: Map[String, Int],
    rewrittenReferences: Int,
    expressionOperators: Vector[String]
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
      "  \"procedural_receiver_rewrites\": 0,",
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
