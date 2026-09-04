package morphhdl.passes.transform

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import morphhdl.ir.v1.CanonicalIrHandoff
import morphhdl.ir.v1.Declaration
import morphhdl.ir.v1.DeclarationKind
import morphhdl.ir.v1.Design
import morphhdl.ir.v1.Driver
import morphhdl.ir.v1.DriverCoverage
import morphhdl.ir.v1.DriverId
import morphhdl.ir.v1.DriverKind
import morphhdl.ir.v1.IrDiagnostic
import morphhdl.ir.v1.ModuleId
import morphhdl.ir.v1.NameOrigin
import morphhdl.ir.v1.PackedType
import morphhdl.ir.v1.PortDirection
import morphhdl.ir.v1.ReferenceId
import morphhdl.ir.v1.RtlExpr
import morphhdl.ir.v1.ScopeId
import morphhdl.ir.v1.SymbolId
import morphhdl.passes.adapter.CanonicalIrAdapterFailure
import morphhdl.passes.adapter.CanonicalIrPassAdapter
import morphhdl.passes.adapter.CanonicalIrPassView
import morphhdl.passes.adapter.CanonicalModuleView
import morphhdl.passes.api.AliasNameOrigin
import morphhdl.passes.api.DiagnosticSeverity
import morphhdl.passes.api.EliminationReport
import morphhdl.passes.api.InlinedWireExpression
import morphhdl.passes.api.IrSymbolId
import morphhdl.passes.api.PassDiagnostic
import morphhdl.passes.api.PassId
import morphhdl.passes.api.PassResult
import morphhdl.passes.api.RejectedWireAlias
import morphhdl.passes.api.{SourceLocation => PassSourceLocation}
import morphhdl.passes.api.WireAliasPassConfiguration
import morphhdl.passes.safety.AliasSafetyConfiguration

object UnnamedWireExpressionDiagnosticCode {
  val RewriteInvariant = "WA07-REWRITE-INVARIANT"
  val Inlined = "WA07-INLINED"
  val Rejected = "WA07-REJECTED"
}

object UnnamedWireExpressionSafetyReason {
  val AliasMetadata = "WA07-ALIAS-METADATA"
  val DriverCardinality = "WA07-DRIVER-CARDINALITY"
  val DriverNotContinuous = "WA07-DRIVER-NOT-CONTINUOUS"
  val DriverNotFullObject = "WA07-DRIVER-NOT-FULL-OBJECT"
  val DriverMetadata = "WA07-DRIVER-METADATA"
  val PackedTypeMissing = "WA07-PACKED-TYPE-MISSING"
  val SelfReference = "WA07-SELF-REFERENCE"
  val NoReceivers = "WA07-NO-RECEIVERS"
  val ReceiverNotContinuous = "WA07-RECEIVER-NOT-CONTINUOUS"
  val ReceiverBoundary = "WA07-RECEIVER-BOUNDARY"
  val SourceUnresolved = "WA07-SOURCE-UNRESOLVED"
  val SourceKindExcluded = "WA07-SOURCE-KIND-EXCLUDED"
  val IllegalScopeReplacement = "WA07-ILLEGAL-SCOPE-REPLACEMENT"
  val CombinationalCycle = "WA07-COMBINATIONAL-CYCLE"
}

/**
  * Inline unnamed internal wires driven by a non-reference continuous
  * expression. Candidate classification uses canonical NameOrigin metadata,
  * never an emitted identifier. Procedural definitions and procedural
  * receivers are retained to preserve scheduling and sensitivity semantics.
  */
object UnnamedWireExpressionEliminationPass {
  val passId: PassId = PassId.UnnamedWireExpressionElimination

  private final case class Violation(code: String, message: String)

  private final case class Assessment(
      moduleId: ModuleId,
      alias: Declaration,
      driver: Driver,
      packedType: PackedType,
      receivers: Vector[Driver],
      replacementCount: Int,
      violations: Vector[Violation]
  ) {
    def isEligible: Boolean = violations.isEmpty
  }

  private final case class SuccessfulTransformation(
      output: Design,
      inlined: Vector[InlinedWireExpression]
  )

  private final case class RewriteResult(value: RtlExpr, replacements: Int)

  def run(
      handoff: CanonicalIrHandoff,
      configuration: WireAliasPassConfiguration,
      safetyConfiguration: AliasSafetyConfiguration
  ): PassResult[Design] = {
    require(handoff != null, "canonical IR handoff must not be null")
    val initialView = CanonicalIrPassAdapter.bind(handoff)
    run(initialView.design, configuration, safetyConfiguration)
  }

  def run(handoff: CanonicalIrHandoff): PassResult[Design] =
    run(handoff, WireAliasPassConfiguration(), AliasSafetyConfiguration())

  def run(
      design: Design,
      configuration: WireAliasPassConfiguration = WireAliasPassConfiguration(),
      safetyConfiguration: AliasSafetyConfiguration = AliasSafetyConfiguration()
  ): PassResult[Design] = {
    require(design != null, "canonical IR design must not be null")
    require(configuration != null, "wire-assignment configuration must not be null")
    require(safetyConfiguration != null, "expression safety configuration must not be null")

    if (!configuration.enabled) {
      PassResult.skipped(design, passId)
    } else {
      CanonicalIrPassAdapter.bindFixture(design) match {
        case Left(failure) =>
          PassResult.failed(
            output = design,
            report = EliminationReport(passId),
            diagnostics = canonicalDiagnostics(failure, "input canonical IR validation failed")
          ).normalized
        case Right(initialView) =>
          transformToFixedPoint(initialView.design) match {
            case Left(diagnostics) =>
              PassResult.failed(
                output = initialView.design,
                report = EliminationReport(passId),
                diagnostics = diagnostics
              ).normalized
            case Right(transformation) =>
              CanonicalIrPassAdapter.bindFixture(transformation.output) match {
                case Left(failure) =>
                  PassResult.failed(
                    output = initialView.design,
                    report = EliminationReport(passId),
                    diagnostics = canonicalDiagnostics(
                      failure,
                      "final canonical IR validation failed"
                    )
                  ).normalized
                case Right(finalView) =>
                  val rejected = assessments(finalView)
                    .filterNot(_.isEligible)
                    .flatMap(rejectedExpression)
                  val report = EliminationReport(
                    passId = passId,
                    rejected = rejected,
                    inlinedExpressions = transformation.inlined
                  ).normalized
                  val diagnostics = (
                    inlinedDiagnostics(report.inlinedExpressions) ++
                      rejectionDiagnostics(assessments(finalView))
                  ).sortBy(diagnosticKey)

                  if (report.inlinedExpressions.nonEmpty)
                    PassResult.changed(finalView.design, report, diagnostics).normalized
                  else
                    PassResult.unchanged(finalView.design, report, diagnostics).normalized
              }
          }
      }
    }
  }

  private def transformToFixedPoint(
      initial: Design
  ): Either[Vector[PassDiagnostic], SuccessfulTransformation] = {
    var current = initial
    val inlined = ArrayBuffer.empty[InlinedWireExpression]
    var complete = false

    while (!complete) {
      val view = CanonicalIrPassAdapter.bindFixture(current) match {
        case Right(value) => value
        case Left(failure) =>
          return Left(
            canonicalDiagnostics(failure, "intermediate canonical IR validation failed")
          )
      }
      val eligible = assessments(view)
        .filter(_.isEligible)
        .sortBy(value => (value.moduleId.value, value.alias.id.value))

      eligible.headOption match {
        case None => complete = true
        case Some(assessment) =>
          rewriteOne(current, assessment) match {
            case Left(diagnostic) => return Left(Vector(diagnostic))
            case Right(rewritten) =>
              CanonicalIrPassAdapter.bindFixture(rewritten) match {
                case Left(failure) =>
                  return Left(
                    canonicalDiagnostics(
                      failure,
                      s"canonical IR validation failed after inlining '${assessment.alias.id.value}'"
                    )
                  )
                case Right(rebound) =>
                  current = rebound.design
                  inlined += InlinedWireExpression(
                    aliasSymbol = passSymbol(assessment.alias.id),
                    replacementCount = assessment.replacementCount,
                    nameOrigin = AliasNameOrigin.Unnamed,
                    location = assessment.alias.sourceLocation.flatMap(passLocation)
                  )
              }
          }
      }
    }
    Right(SuccessfulTransformation(current, inlined.toVector))
  }

  private def assessments(view: CanonicalIrPassView): Vector[Assessment] =
    view.modules.flatMap { module =>
      module.declarations
        .filter(value =>
          value.kind == DeclarationKind.InternalCombinational &&
            value.nameOrigin == NameOrigin.Unnamed
        )
        .sortBy(_.id.value)
        .flatMap(value => assess(module, value))
    }

  /** Direct references belong exclusively to WA-04. */
  private def assess(
      module: CanonicalModuleView,
      alias: Declaration
  ): Option[Assessment] = {
    val drivers = module.driversTargeting(alias.id)
    if (drivers.size == 1 && drivers.head.value.directReference.nonEmpty) return None
    if (drivers.isEmpty || drivers.forall(_.value.directReference.nonEmpty)) return None

    val violations = ArrayBuffer.empty[Violation]
    validateAliasMetadata(alias, violations)
    if (drivers.size != 1) {
      violations += Violation(
        UnnamedWireExpressionSafetyReason.DriverCardinality,
        s"expression wire requires exactly one driver, observed ${drivers.size}"
      )
    }

    val driver = drivers.find(_.value.directReference.isEmpty).getOrElse(drivers.head)
    if (driver.kind != DriverKind.Continuous) {
      violations += Violation(
        UnnamedWireExpressionSafetyReason.DriverNotContinuous,
        s"expression driver kind '${driver.kind.label}' is not continuous"
      )
    }
    if (driver.coverage != DriverCoverage.FullObject) {
      violations += Violation(
        UnnamedWireExpressionSafetyReason.DriverNotFullObject,
        s"expression driver coverage '${driver.coverage.label}' is not full-object"
      )
    }
    if (driver.attributes.nonEmpty || driver.comments.nonEmpty) {
      violations += Violation(
        UnnamedWireExpressionSafetyReason.DriverMetadata,
        "removing the expression assignment would discard attributes or comments"
      )
    }

    val packedType = alias.packedType.getOrElse {
      violations += Violation(
        UnnamedWireExpressionSafetyReason.PackedTypeMissing,
        "expression wire requires complete packed-type metadata"
      )
      null
    }
    if (driver.value.referencedSymbols.contains(alias.id)) {
      violations += Violation(
        UnnamedWireExpressionSafetyReason.SelfReference,
        "expression driver references its own target"
      )
    }

    val receivers = module.drivers
      .filter(_.id != driver.id)
      .filter(_.value.referenceOccurrences.exists(_.target == alias.id))
      .sortBy(_.id.value)
    val replacementCount = receivers.map(
      _.value.referenceOccurrences.count(_.target == alias.id)
    ).sum
    if (replacementCount == 0) {
      violations += Violation(
        UnnamedWireExpressionSafetyReason.NoReceivers,
        "expression wire has no receiver and is outside exact substitution scope"
      )
    }

    receivers.foreach { receiver =>
      if (receiver.kind != DriverKind.Continuous) {
        violations += Violation(
          UnnamedWireExpressionSafetyReason.ReceiverNotContinuous,
          s"receiver '${receiver.id.value}' is '${receiver.kind.label}', not continuous"
        )
      }
      module.declaration(receiver.target).foreach { target =>
        val allowedTarget = target.kind match {
          case DeclarationKind.InternalCombinational => true
          case DeclarationKind.Port(PortDirection.Output) => true
          case _ => false
        }
        if (!allowedTarget) {
          violations += Violation(
            UnnamedWireExpressionSafetyReason.ReceiverBoundary,
            s"receiver target '${target.id.value}' has excluded kind '${target.kind.label}'"
          )
        }
      }
    }

    val dependencies = driver.value.referenceOccurrences
    dependencies.foreach { reference =>
      module.declaration(reference.target) match {
        case None =>
          violations += Violation(
            UnnamedWireExpressionSafetyReason.SourceUnresolved,
            s"expression source '${reference.target.value}' is unresolved"
          )
        case Some(source) =>
          if (!allowedSource(source)) {
            violations += Violation(
              UnnamedWireExpressionSafetyReason.SourceKindExcluded,
              s"expression source '${source.id.value}' has excluded kind '${source.kind.label}'"
            )
          }
          receivers.foreach { receiver =>
            if (!scopeIsAncestor(module, source.owner, receiver.owner)) {
              violations += Violation(
                UnnamedWireExpressionSafetyReason.IllegalScopeReplacement,
                s"source '${source.id.value}' is not visible from receiver '${receiver.id.value}'"
              )
            }
          }
      }
    }

    if (createsCombinationalCycle(module, alias.id, driver.value.referencedSymbols)) {
      violations += Violation(
        UnnamedWireExpressionSafetyReason.CombinationalCycle,
        "expression substitution is not cycle-free"
      )
    }

    Some(
      Assessment(
        moduleId = module.id,
        alias = alias,
        driver = driver,
        packedType = packedType,
        receivers = receivers,
        replacementCount = replacementCount,
        violations = violations.toVector.distinct.sortBy(value => (value.code, value.message))
      )
    )
  }

  private def validateAliasMetadata(
      alias: Declaration,
      violations: ArrayBuffer[Violation]
  ): Unit = {
    val observability = alias.observability
    if (
      !observability.complete || observability.preventsElimination ||
      alias.attributes.nonEmpty || alias.comments.nonEmpty
    ) {
      violations += Violation(
        UnnamedWireExpressionSafetyReason.AliasMetadata,
        "expression wire has incomplete or preservation-relevant metadata"
      )
    }
  }

  private def allowedSource(value: Declaration): Boolean = value.kind match {
    case DeclarationKind.Port(PortDirection.Input) => true
    case DeclarationKind.Port(PortDirection.Output) => true
    case DeclarationKind.InternalCombinational => true
    case DeclarationKind.Register => true
    case _ => false
  }

  private def rewriteOne(
      design: Design,
      assessment: Assessment
  ): Either[PassDiagnostic, Design] = {
    val module = design.modules.find(_.id == assessment.moduleId).getOrElse {
      return Left(invariantDiagnostic(assessment, "owning module is unavailable"))
    }
    val usedIds = mutable.HashSet.empty[String]
    module.drivers.foreach(value =>
      value.value.referenceOccurrences.foreach(reference => usedIds += reference.id.value)
    )
    val allocator = new ReferenceAllocator(assessment.alias.id, usedIds)
    var replacements = 0

    val updatedDrivers = module.drivers
      .filterNot(_.id == assessment.driver.id)
      .map { driver =>
        val rewritten = rewriteUses(
          driver.value,
          driver.owner,
          assessment.alias.id,
          assessment.driver.value,
          assessment.packedType,
          allocator
        )
        replacements += rewritten.replacements
        driver.copy(value = rewritten.value)
      }

    if (replacements != assessment.replacementCount) {
      Left(
        invariantDiagnostic(
          assessment,
          s"expected ${assessment.replacementCount} replacements, observed $replacements"
        )
      )
    } else {
      val output = design.copy(
        modules = design.modules.map { value =>
          if (value.id != assessment.moduleId) value
          else
            value.copy(
              declarations = value.declarations.filterNot(_.id == assessment.alias.id),
              drivers = updatedDrivers
            )
        }
      ).normalized
      val remaining = output.modules
        .find(_.id == assessment.moduleId)
        .toVector
        .flatMap(_.drivers)
        .flatMap(_.value.referenceOccurrences)
        .count(_.target == assessment.alias.id)
      if (remaining != 0)
        Left(invariantDiagnostic(assessment, s"$remaining removed-symbol references remain"))
      else Right(output)
    }
  }

  private final class ReferenceAllocator(
      alias: SymbolId,
      used: mutable.HashSet[String]
  ) {
    private var ordinal = 0

    def next(): ReferenceId = {
      var candidate = ""
      do {
        candidate = s"${alias.value}.wa07-expression-reference-$ordinal"
        ordinal += 1
      } while (used.contains(candidate))
      used += candidate
      ReferenceId.unsafe(candidate)
    }
  }

  private def cloneReplacement(
      expression: RtlExpr,
      owner: ScopeId,
      allocator: ReferenceAllocator
  ): RtlExpr = expression match {
    case RtlExpr.Ref(_, target, _, location) =>
      RtlExpr.Ref(allocator.next(), target, owner, location)
    case value @ RtlExpr.Literal(_, _, _) => value
    case RtlExpr.Unary(operator, value) =>
      RtlExpr.Unary(operator, cloneReplacement(value, owner, allocator))
    case RtlExpr.Binary(operator, left, right) =>
      RtlExpr.Binary(
        operator,
        cloneReplacement(left, owner, allocator),
        cloneReplacement(right, owner, allocator)
      )
    case RtlExpr.Mux(condition, whenTrue, whenFalse) =>
      RtlExpr.Mux(
        cloneReplacement(condition, owner, allocator),
        cloneReplacement(whenTrue, owner, allocator),
        cloneReplacement(whenFalse, owner, allocator)
      )
    case RtlExpr.Concat(values) =>
      RtlExpr.Concat(values.map(value => cloneReplacement(value, owner, allocator)))
    case RtlExpr.BitSelect(value, index) =>
      RtlExpr.BitSelect(
        cloneReplacement(value, owner, allocator),
        cloneReplacement(index, owner, allocator)
      )
    case RtlExpr.PartSelect(value, offset, width) =>
      RtlExpr.PartSelect(cloneReplacement(value, owner, allocator), offset, width)
    case RtlExpr.Resize(value, width, signedness) =>
      RtlExpr.Resize(cloneReplacement(value, owner, allocator), width, signedness)
    case RtlExpr.Cast(value, signedness) =>
      RtlExpr.Cast(cloneReplacement(value, owner, allocator), signedness)
  }

  private def rewriteUses(
      expression: RtlExpr,
      owner: ScopeId,
      alias: SymbolId,
      replacement: RtlExpr,
      packedType: PackedType,
      allocator: ReferenceAllocator
  ): RewriteResult = expression match {
    case RtlExpr.Ref(_, target, _, _) if target == alias =>
      RewriteResult(
        RtlExpr.Resize(
          cloneReplacement(replacement, owner, allocator),
          packedType.width,
          packedType.signedness
        ),
        1
      )
    case value @ RtlExpr.Ref(_, _, _, _) => RewriteResult(value, 0)
    case value @ RtlExpr.Literal(_, _, _) => RewriteResult(value, 0)
    case RtlExpr.Unary(operator, value) =>
      val nested = rewriteUses(value, owner, alias, replacement, packedType, allocator)
      RewriteResult(RtlExpr.Unary(operator, nested.value), nested.replacements)
    case RtlExpr.Binary(operator, left, right) =>
      val leftResult = rewriteUses(left, owner, alias, replacement, packedType, allocator)
      val rightResult = rewriteUses(right, owner, alias, replacement, packedType, allocator)
      RewriteResult(
        RtlExpr.Binary(operator, leftResult.value, rightResult.value),
        leftResult.replacements + rightResult.replacements
      )
    case RtlExpr.Mux(condition, whenTrue, whenFalse) =>
      val conditionResult = rewriteUses(condition, owner, alias, replacement, packedType, allocator)
      val trueResult = rewriteUses(whenTrue, owner, alias, replacement, packedType, allocator)
      val falseResult = rewriteUses(whenFalse, owner, alias, replacement, packedType, allocator)
      RewriteResult(
        RtlExpr.Mux(conditionResult.value, trueResult.value, falseResult.value),
        conditionResult.replacements + trueResult.replacements + falseResult.replacements
      )
    case RtlExpr.Concat(values) =>
      val nested = values.map(value =>
        rewriteUses(value, owner, alias, replacement, packedType, allocator)
      )
      RewriteResult(
        RtlExpr.Concat(nested.map(_.value)),
        nested.map(_.replacements).sum
      )
    case RtlExpr.BitSelect(value, index) =>
      val valueResult = rewriteUses(value, owner, alias, replacement, packedType, allocator)
      val indexResult = rewriteUses(index, owner, alias, replacement, packedType, allocator)
      RewriteResult(
        RtlExpr.BitSelect(valueResult.value, indexResult.value),
        valueResult.replacements + indexResult.replacements
      )
    case RtlExpr.PartSelect(value, offset, width) =>
      val nested = rewriteUses(value, owner, alias, replacement, packedType, allocator)
      RewriteResult(RtlExpr.PartSelect(nested.value, offset, width), nested.replacements)
    case RtlExpr.Resize(value, width, signedness) =>
      val nested = rewriteUses(value, owner, alias, replacement, packedType, allocator)
      RewriteResult(RtlExpr.Resize(nested.value, width, signedness), nested.replacements)
    case RtlExpr.Cast(value, signedness) =>
      val nested = rewriteUses(value, owner, alias, replacement, packedType, allocator)
      RewriteResult(RtlExpr.Cast(nested.value, signedness), nested.replacements)
  }

  private def createsCombinationalCycle(
      module: CanonicalModuleView,
      alias: SymbolId,
      dependencies: Vector[SymbolId]
  ): Boolean = {
    val edges = mutable.Map.empty[SymbolId, Vector[SymbolId]]
    module.drivers
      .filter(_.kind == DriverKind.Continuous)
      .sortBy(_.id.value)
      .foreach { driver =>
        edges.update(
          driver.target,
          driver.value.referencedSymbols.distinct.sortBy(_.value)
        )
      }

    dependencies.distinct.exists { dependency =>
      val pending = mutable.Stack[SymbolId](dependency)
      val visited = mutable.HashSet.empty[SymbolId]
      var found = false
      while (pending.nonEmpty && !found) {
        val current = pending.pop()
        if (current == alias) found = true
        else if (!visited.contains(current)) {
          visited += current
          edges.getOrElse(current, Vector.empty).reverse.foreach(pending.push)
        }
      }
      found
    }
  }

  private def scopeIsAncestor(
      module: CanonicalModuleView,
      ancestor: ScopeId,
      descendant: ScopeId
  ): Boolean = {
    var current = Option(descendant)
    var visited = Set.empty[ScopeId]
    while (current.nonEmpty) {
      val value = current.get
      if (value == ancestor) return true
      if (visited.contains(value)) return false
      visited += value
      current = module.scope(value).flatMap(_.parent)
    }
    false
  }

  private def rejectedExpression(assessment: Assessment): Vector[RejectedWireAlias] =
    assessment.violations.headOption.toVector.map { violation =>
      RejectedWireAlias(
        aliasSymbol = passSymbol(assessment.alias.id),
        nameOrigin = AliasNameOrigin.Unnamed,
        reasonCode = violation.code,
        message = violation.message,
        location = assessment.alias.sourceLocation.flatMap(passLocation)
      )
    }

  private def inlinedDiagnostics(
      values: Vector[InlinedWireExpression]
  ): Vector[PassDiagnostic] = values.map { value =>
    PassDiagnostic(
      code = UnnamedWireExpressionDiagnosticCode.Inlined,
      severity = DiagnosticSeverity.Info,
      message =
        s"inlined unnamed continuous expression '${value.aliasSymbol.value}' into ${value.replacementCount} receiver occurrence(s)",
      passId = Some(passId),
      location = value.location
    )
  }

  private def rejectionDiagnostics(
      values: Vector[Assessment]
  ): Vector[PassDiagnostic] = values
    .filterNot(_.isEligible)
    .flatMap { assessment =>
      assessment.violations.map { violation =>
        PassDiagnostic(
          code = UnnamedWireExpressionDiagnosticCode.Rejected,
          severity = DiagnosticSeverity.Warning,
          message =
            s"retained unnamed expression '${assessment.alias.id.value}': ${violation.code}: ${violation.message}",
          passId = Some(passId),
          location = assessment.alias.sourceLocation.flatMap(passLocation)
        )
      }
    }

  private def canonicalDiagnostics(
      failure: CanonicalIrAdapterFailure,
      prefix: String
  ): Vector[PassDiagnostic] = failure.diagnostics.values.map { diagnostic =>
    PassDiagnostic(
      code = diagnostic.code,
      severity = DiagnosticSeverity.Error,
      message = canonicalMessage(prefix, diagnostic),
      passId = Some(passId),
      location = diagnostic.location.flatMap(passLocation)
    )
  }

  private def canonicalMessage(prefix: String, diagnostic: IrDiagnostic): String = {
    val path = diagnostic.pathString
    if (path.isEmpty) s"$prefix: ${diagnostic.message}"
    else s"$prefix at $path: ${diagnostic.message}"
  }

  private def invariantDiagnostic(
      assessment: Assessment,
      message: String
  ): PassDiagnostic = PassDiagnostic(
    code = UnnamedWireExpressionDiagnosticCode.RewriteInvariant,
    severity = DiagnosticSeverity.Error,
    message = s"cannot inline unnamed expression '${assessment.alias.id.value}': $message",
    passId = Some(passId),
    location = assessment.alias.sourceLocation.flatMap(passLocation)
  )

  private def passSymbol(value: SymbolId): IrSymbolId = IrSymbolId.unsafe(value.value)

  private def passLocation(
      value: morphhdl.ir.v1.SourceLocation
  ): Option[PassSourceLocation] = Option(value).flatMap { item =>
    val path = Option(item.path).map(_.trim).getOrElse("")
    if (path.nonEmpty && item.line >= 1 && item.column >= 1)
      Some(PassSourceLocation(path, item.line, item.column))
    else None
  }

  private def diagnosticKey(
      value: PassDiagnostic
  ): (Int, String, Int, Int, String, String) = {
    val location = value.location match {
      case Some(item) => (item.path, item.line, item.column)
      case None => ("", 0, 0)
    }
    (
      value.severity.rank,
      location._1,
      location._2,
      location._3,
      value.code,
      value.message
    )
  }
}
