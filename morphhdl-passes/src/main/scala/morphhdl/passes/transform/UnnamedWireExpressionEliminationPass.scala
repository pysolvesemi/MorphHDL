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
import morphhdl.passes.api.EliminatedWireExpression
import morphhdl.passes.api.EliminationReport
import morphhdl.passes.api.IrSymbolId
import morphhdl.passes.api.PassDiagnostic
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.PassId
import morphhdl.passes.api.PassResult
import morphhdl.passes.api.RejectedWireAlias
import morphhdl.passes.api.{SourceLocation => PassSourceLocation}
import morphhdl.passes.api.WireAliasPassConfiguration

/** Stable diagnostics produced by the unnamed continuous-expression pass. */
object UnnamedWireExpressionDiagnosticCode {
  val RewriteInvariant = "WA07-REWRITE-INVARIANT"
  val Eliminated = "WA07-ELIMINATED"
  val Rejected = "WA07-REJECTED"
}

/** Stable fail-closed reasons for retaining an unnamed expression temporary. */
object UnnamedWireExpressionSafetyReason {
  val AliasKind = "WA07-ALIAS-NOT-INTERNAL-COMBINATIONAL"
  val NameOrigin = "WA07-NAME-ORIGIN-UNPROVEN"
  val Observability = "WA07-OBSERVABILITY-PREVENTS-ELIMINATION"
  val DeclarationAttributes = "WA07-DECLARATION-ATTRIBUTES"
  val DeclarationComments = "WA07-DECLARATION-COMMENTS"
  val DriverCardinality = "WA07-DRIVER-CARDINALITY"
  val DriverNotContinuous = "WA07-DRIVER-NOT-CONTINUOUS"
  val DriverNotFullObject = "WA07-DRIVER-NOT-FULL-OBJECT"
  val DriverAttributes = "WA07-DRIVER-ATTRIBUTES"
  val DriverComments = "WA07-DRIVER-COMMENTS"
  val DirectReferenceHandledElsewhere = "WA07-DIRECT-REFERENCE-HANDLED-ELSEWHERE"
  val NoReceiver = "WA07-NO-RECEIVER"
  val ReceiverProcedural = "WA07-RECEIVER-PROCEDURAL"
  val ReceiverContext = "WA07-RECEIVER-CONTEXT"
  val ReceiverTarget = "WA07-RECEIVER-TARGET"
  val SourceSelfReference = "WA07-SOURCE-SELF-REFERENCE"
  val SourceUnresolved = "WA07-SOURCE-UNRESOLVED"
  val SourceKind = "WA07-SOURCE-KIND"
  val IllegalScopeReplacement = "WA07-ILLEGAL-SCOPE-REPLACEMENT"
  val CombinationalCycle = "WA07-COMBINATIONAL-CYCLE"
  val PackedTypeMissing = "WA07-PACKED-TYPE-MISSING"
}

/**
  * Component-generic inlining of proven unnamed continuous wire expressions.
  *
  * The pass selects only canonical [[NameOrigin.Unnamed]] internal combinational
  * declarations with one full-object continuous driver whose RHS is not a direct
  * reference. It clones the complete pure [[RtlExpr]] tree at every continuous
  * receiver, recreates the removed assignment's packed width and signedness as
  * an explicit resize fence, then removes the exact temporary declaration and
  * its sole assignment. It never recognizes backend-generated temporary identifier text.
  *
  * A candidate is retained when either its own assignment or any receiver is
  * procedural. Canonical `DriverKind.Procedural` represents assignments inside
  * `always` blocks, so this pass never rewrites an `always` assignment.
  */
object UnnamedWireExpressionEliminationPass {
  val passId: PassId = PassId.UnnamedWireExpressionElimination

  private final case class Violation(code: String, message: String)

  private final case class Assessment(
      moduleId: ModuleId,
      alias: Declaration,
      driver: Option[Driver],
      receiverDrivers: Vector[Driver],
      violations: Vector[Violation]
  ) {
    def isEligible: Boolean = violations.isEmpty
  }

  private final case class SuccessfulTransformation(
      output: Design,
      eliminated: Vector[EliminatedWireExpression]
  )

  /** Consume the validated production envelope without discarding its profile. */
  def run(
      handoff: CanonicalIrHandoff,
      configuration: WireAliasPassConfiguration
  ): PassResult[Design] = {
    require(handoff != null, "canonical IR handoff must not be null")
    val initial = CanonicalIrPassAdapter.bind(handoff).design
    run(initial, configuration)
  }

  def run(handoff: CanonicalIrHandoff): PassResult[Design] =
    run(handoff, WireAliasPassConfiguration())

  def run(
      design: Design,
      configuration: WireAliasPassConfiguration = WireAliasPassConfiguration()
  ): PassResult[Design] = {
    require(design != null, "canonical IR design must not be null")
    require(configuration != null, "wire-assignment pass configuration must not be null")

    if (!configuration.isEnabled(passId)) {
      PassResult.skipped(design, passId)
    } else {
      CanonicalIrPassAdapter.bindFixture(design) match {
        case Left(failure) =>
          PassResult.failed(
            output = design,
            report = EliminationReport(passId),
            diagnostics = canonicalDiagnostics(
              failure,
              "input canonical IR validation failed"
            )
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
              val finalView: CanonicalIrPassView =
                CanonicalIrPassAdapter.bindFixture(transformation.output) match {
                  case Right(value) => value
                  case Left(failure) =>
                    return PassResult.failed(
                      output = initialView.design,
                      report = EliminationReport(passId),
                      diagnostics = canonicalDiagnostics(
                        failure,
                        "final canonical IR validation failed"
                      )
                    ).normalized
                }
              val finalAssessments = assessments(finalView)
              val rejected = finalAssessments
                .filterNot(_.isEligible)
                .flatMap(rejectedExpression)
              val report = EliminationReport(
                passId = passId,
                eliminatedExpressions = transformation.eliminated,
                rejected = rejected
              ).normalized
              val diagnostics = (
                eliminationDiagnostics(report.eliminatedExpressions) ++
                  rejectionDiagnostics(finalAssessments)
              ).sortBy(diagnosticKey)

              if (report.eliminatedCount > 0) {
                PassResult.changed(
                  output = finalView.design,
                  report = report,
                  diagnostics = diagnostics
                ).normalized
              } else {
                PassResult.unchanged(
                  output = finalView.design,
                  report = report,
                  diagnostics = diagnostics
                ).normalized
              }
          }
      }
    }
  }

  private def transformToFixedPoint(
      initial: Design
  ): Either[Vector[PassDiagnostic], SuccessfulTransformation] = {
    var current = initial
    val eliminated = ArrayBuffer.empty[EliminatedWireExpression]
    var complete = false

    while (!complete) {
      val view = CanonicalIrPassAdapter.bindFixture(current) match {
        case Right(value) => value
        case Left(failure) =>
          return Left(
            canonicalDiagnostics(
              failure,
              "intermediate canonical IR validation failed"
            )
          )
      }
      val eligible = assessments(view)
        .filter(_.isEligible)
        .sortBy(value => (value.moduleId.value, value.alias.id.value))

      eligible.headOption match {
        case None => complete = true
        case Some(assessment) =>
          val module = view.module(assessment.moduleId).getOrElse {
            return Left(
              Vector(
                invariantDiagnostic(
                  assessment,
                  s"owning module '${assessment.moduleId.value}' is unavailable"
                )
              )
            )
          }
          val driver = assessment.driver.getOrElse {
            return Left(
              Vector(invariantDiagnostic(assessment, "eligible expression has no driver"))
            )
          }
          val packedType = assessment.alias.packedType.getOrElse {
            return Left(
              Vector(
                invariantDiagnostic(
                  assessment,
                  "eligible expression has no packed type"
                )
              )
            )
          }
          val receiverCount = assessment.receiverDrivers.map { value =>
            value.value.referenceOccurrences.count(_.target == assessment.alias.id)
          }.sum
          if (receiverCount < 1) {
            return Left(
              Vector(
                invariantDiagnostic(
                  assessment,
                  "eligible expression has no receiver occurrence"
                )
              )
            )
          }

          val rewritten = rewriteOneExpression(
            current,
            assessment.moduleId,
            assessment.alias.id,
            driver,
            packedType
          )
          CanonicalIrPassAdapter.bindFixture(rewritten) match {
            case Left(failure) =>
              return Left(
                canonicalDiagnostics(
                  failure,
                  s"canonical IR validation failed after eliminating '${assessment.alias.id.value}'"
                )
              )
            case Right(rebound) =>
              current = rebound.design
              eliminated += EliminatedWireExpression(
                aliasSymbol = passSymbol(assessment.alias.id),
                nameOrigin = AliasNameOrigin.Unnamed,
                rootOperator = rootOperator(driver.value),
                expressionNodeCount = expressionNodeCount(driver.value),
                receiverCount = receiverCount,
                referencedSymbols = driver.value.referencedSymbols
                  .distinct
                  .sortBy(_.value)
                  .map(passSymbol),
                location = assessment.alias.sourceLocation.flatMap(passLocation)
              ).normalized
          }
      }
    }

    Right(SuccessfulTransformation(current, eliminated.toVector))
  }

  private def assessments(view: CanonicalIrPassView): Vector[Assessment] =
    view.modules.flatMap { module =>
      module.declarations
        .filter(_.nameOrigin == NameOrigin.Unnamed)
        .filter(value => isExpressionShapedCandidate(module, value.id))
        .map(value => assess(module, value))
    }.sortBy(value => (value.moduleId.value, value.alias.id.value))

  private def isExpressionShapedCandidate(
      module: CanonicalModuleView,
      alias: SymbolId
  ): Boolean = {
    val drivers = module.driversTargeting(alias)
    drivers.isEmpty || drivers.size > 1 || drivers.exists(_.value.directReference.isEmpty)
  }

  private def assess(
      module: CanonicalModuleView,
      alias: Declaration
  ): Assessment = {
    val violations = ArrayBuffer.empty[Violation]

    if (alias.kind != DeclarationKind.InternalCombinational) {
      violations += violation(
        UnnamedWireExpressionSafetyReason.AliasKind,
        s"candidate kind '${alias.kind.label}' is not an internal combinational wire"
      )
    }
    if (alias.nameOrigin != NameOrigin.Unnamed) {
      violations += violation(
        UnnamedWireExpressionSafetyReason.NameOrigin,
        "candidate is not proven unnamed by source/elaboration metadata"
      )
    }
    if (!alias.observability.complete || alias.observability.preventsElimination) {
      violations += violation(
        UnnamedWireExpressionSafetyReason.Observability,
        "candidate observability is incomplete or requires preservation"
      )
    }
    if (alias.attributes.nonEmpty) {
      violations += violation(
        UnnamedWireExpressionSafetyReason.DeclarationAttributes,
        s"removing the candidate would discard ${alias.attributes.size} declaration attribute(s)"
      )
    }
    if (alias.comments.nonEmpty) {
      violations += violation(
        UnnamedWireExpressionSafetyReason.DeclarationComments,
        s"removing the candidate would discard ${alias.comments.size} declaration comment(s)"
      )
    }
    if (alias.packedType.isEmpty) {
      violations += violation(
        UnnamedWireExpressionSafetyReason.PackedTypeMissing,
        "candidate requires complete packed type metadata"
      )
    }

    val drivers = module.driversTargeting(alias.id)
    if (drivers.size != 1) {
      violations += violation(
        UnnamedWireExpressionSafetyReason.DriverCardinality,
        s"candidate requires exactly one driver, observed ${drivers.size}"
      )
    }
    val driver = drivers.headOption
    driver.foreach { value =>
      if (value.kind != DriverKind.Continuous) {
        violations += violation(
          UnnamedWireExpressionSafetyReason.DriverNotContinuous,
          s"candidate driver '${value.id.value}' is '${value.kind.label}', so an always-block assignment is retained"
        )
      }
      if (value.coverage != DriverCoverage.FullObject) {
        violations += violation(
          UnnamedWireExpressionSafetyReason.DriverNotFullObject,
          s"candidate driver coverage '${value.coverage.label}' is not full-object"
        )
      }
      if (value.attributes.nonEmpty) {
        violations += violation(
          UnnamedWireExpressionSafetyReason.DriverAttributes,
          s"removing the assignment would discard ${value.attributes.size} driver attribute(s)"
        )
      }
      if (value.comments.nonEmpty) {
        violations += violation(
          UnnamedWireExpressionSafetyReason.DriverComments,
          s"removing the assignment would discard ${value.comments.size} driver comment(s)"
        )
      }
      if (value.value.directReference.nonEmpty) {
        violations += violation(
          UnnamedWireExpressionSafetyReason.DirectReferenceHandledElsewhere,
          "direct symbol aliases remain the responsibility of WA-04"
        )
      }
      if (value.value.referencedSymbols.contains(alias.id)) {
        violations += violation(
          UnnamedWireExpressionSafetyReason.SourceSelfReference,
          "candidate expression directly references its own temporary"
        )
      }
    }

    val receiverDrivers = driver.toVector.flatMap { sourceDriver =>
      module.drivers
        .filterNot(_.id == sourceDriver.id)
        .filter(_.value.referenceOccurrences.exists(_.target == alias.id))
    }.sortBy(_.id.value)

    if (driver.nonEmpty && receiverDrivers.isEmpty) {
      violations += violation(
        UnnamedWireExpressionSafetyReason.NoReceiver,
        "candidate has no receiver; dead-code removal is outside this pass"
      )
    }

    receiverDrivers.foreach { receiver =>
      receiver.kind match {
        case DriverKind.Continuous =>
        case DriverKind.Procedural =>
          violations += violation(
            UnnamedWireExpressionSafetyReason.ReceiverProcedural,
            s"receiver '${receiver.id.value}' is procedural, so assignments in always blocks are not rewritten"
          )
        case _ =>
          violations += violation(
            UnnamedWireExpressionSafetyReason.ReceiverContext,
            s"receiver '${receiver.id.value}' has excluded kind '${receiver.kind.label}'"
          )
      }
      module.declaration(receiver.target) match {
        case None =>
          violations += violation(
            UnnamedWireExpressionSafetyReason.ReceiverTarget,
            s"receiver target '${receiver.target.value}' is unresolved"
          )
        case Some(target) if !allowedReceiverTarget(target) =>
          violations += violation(
            UnnamedWireExpressionSafetyReason.ReceiverTarget,
            s"receiver target '${target.id.value}' has excluded kind '${target.kind.label}'"
          )
        case _ =>
      }
    }

    driver.foreach { value =>
      val sourceReferences = value.value.referenceOccurrences
      sourceReferences.foreach { reference =>
        module.declaration(reference.target) match {
          case None =>
            violations += violation(
              UnnamedWireExpressionSafetyReason.SourceUnresolved,
              s"expression source '${reference.target.value}' is unresolved"
            )
          case Some(source) =>
            if (!allowedSource(source)) {
              violations += violation(
                UnnamedWireExpressionSafetyReason.SourceKind,
                s"expression source '${source.id.value}' has excluded kind '${source.kind.label}'"
              )
            }
            receiverDrivers.foreach { receiver =>
              receiver.value.referenceOccurrences
                .filter(_.target == alias.id)
                .foreach { receiverReference =>
                  if (!scopeIsAncestor(module, source.owner, receiverReference.owner)) {
                    violations += violation(
                      UnnamedWireExpressionSafetyReason.IllegalScopeReplacement,
                      s"expression source '${source.id.value}' is not visible from receiver '${receiverReference.id.value}'"
                    )
                  }
                }
            }
        }
      }
      val dependencies = value.value.referencedSymbols.distinct
      if (dependencies.exists(source => createsCombinationalCycle(module, alias.id, source))) {
        violations += violation(
          UnnamedWireExpressionSafetyReason.CombinationalCycle,
          s"inlining expression temporary '${alias.id.value}' is not cycle-free"
        )
      }
    }

    Assessment(
      moduleId = module.id,
      alias = alias,
      driver = driver,
      receiverDrivers = receiverDrivers,
      violations = violations.toVector.distinct.sortBy(value => (value.code, value.message))
    )
  }

  private def rewriteOneExpression(
      design: Design,
      moduleId: ModuleId,
      aliasSymbol: SymbolId,
      aliasDriver: Driver,
      aliasPackedType: PackedType
  ): Design =
    design
      .copy(
        modules = design.modules.map { module =>
          if (module.id != moduleId) module
          else {
            module.copy(
              declarations = module.declarations.filterNot(_.id == aliasSymbol),
              drivers = module.drivers
                .filterNot(_.id == aliasDriver.id)
                .map { driver =>
                  driver.copy(
                    value = inlineReferences(
                      driver.value,
                      aliasSymbol,
                      aliasDriver.value,
                      aliasPackedType
                    )
                  )
                }
            )
          }
        }
      )
      .normalized

  private def inlineReferences(
      expression: RtlExpr,
      aliasSymbol: SymbolId,
      replacement: RtlExpr,
      packedType: PackedType
  ): RtlExpr = expression match {
    case receiver @ RtlExpr.Ref(_, target, _, _) if target == aliasSymbol =>
      RtlExpr.Resize(
        cloneForReceiver(replacement, receiver, aliasSymbol),
        packedType.width,
        packedType.signedness
      )
    case value: RtlExpr.Ref => value
    case value: RtlExpr.Literal => value
    case RtlExpr.Unary(operator, value) =>
      RtlExpr.Unary(
        operator,
        inlineReferences(value, aliasSymbol, replacement, packedType)
      )
    case RtlExpr.Binary(operator, left, right) =>
      RtlExpr.Binary(
        operator,
        inlineReferences(left, aliasSymbol, replacement, packedType),
        inlineReferences(right, aliasSymbol, replacement, packedType)
      )
    case RtlExpr.Mux(condition, whenTrue, whenFalse) =>
      RtlExpr.Mux(
        inlineReferences(condition, aliasSymbol, replacement, packedType),
        inlineReferences(whenTrue, aliasSymbol, replacement, packedType),
        inlineReferences(whenFalse, aliasSymbol, replacement, packedType)
      )
    case RtlExpr.Concat(values) =>
      RtlExpr.Concat(
        values.map(value => inlineReferences(value, aliasSymbol, replacement, packedType))
      )
    case RtlExpr.BitSelect(value, index) =>
      RtlExpr.BitSelect(
        inlineReferences(value, aliasSymbol, replacement, packedType),
        inlineReferences(index, aliasSymbol, replacement, packedType)
      )
    case RtlExpr.PartSelect(value, offset, width) =>
      RtlExpr.PartSelect(
        inlineReferences(value, aliasSymbol, replacement, packedType),
        offset,
        width
      )
    case RtlExpr.Resize(value, width, signedness) =>
      RtlExpr.Resize(
        inlineReferences(value, aliasSymbol, replacement, packedType),
        width,
        signedness
      )
    case RtlExpr.Cast(value, signedness) =>
      RtlExpr.Cast(
        inlineReferences(value, aliasSymbol, replacement, packedType),
        signedness
      )
  }

  private def cloneForReceiver(
      expression: RtlExpr,
      receiver: RtlExpr.Ref,
      aliasSymbol: SymbolId
  ): RtlExpr = {
    var referenceOrdinal = 0

    def fresh(original: RtlExpr): RtlExpr = original match {
      case value: RtlExpr.Ref =>
        val ordinal = referenceOrdinal
        referenceOrdinal += 1
        value.copy(
          id = ReferenceId.unsafe(
            s"${receiver.id.value}.wa07-inline.${aliasSymbol.value}.$ordinal.${value.id.value}"
          ),
          owner = receiver.owner
        )
      case value: RtlExpr.Literal => value
      case RtlExpr.Unary(operator, value) =>
        RtlExpr.Unary(operator, fresh(value))
      case RtlExpr.Binary(operator, left, right) =>
        RtlExpr.Binary(operator, fresh(left), fresh(right))
      case RtlExpr.Mux(condition, whenTrue, whenFalse) =>
        RtlExpr.Mux(fresh(condition), fresh(whenTrue), fresh(whenFalse))
      case RtlExpr.Concat(values) =>
        RtlExpr.Concat(values.map(fresh))
      case RtlExpr.BitSelect(value, index) =>
        RtlExpr.BitSelect(fresh(value), fresh(index))
      case RtlExpr.PartSelect(value, offset, width) =>
        RtlExpr.PartSelect(fresh(value), offset, width)
      case RtlExpr.Resize(value, width, signedness) =>
        RtlExpr.Resize(fresh(value), width, signedness)
      case RtlExpr.Cast(value, signedness) =>
        RtlExpr.Cast(fresh(value), signedness)
    }

    fresh(expression)
  }

  private def allowedSource(value: Declaration): Boolean = value.kind match {
    case DeclarationKind.Port(PortDirection.Input)  => true
    case DeclarationKind.Port(PortDirection.Output) => true
    case DeclarationKind.InternalCombinational      => true
    case DeclarationKind.Register                   => true
    case _                                          => false
  }

  private def allowedReceiverTarget(value: Declaration): Boolean = value.kind match {
    case DeclarationKind.Port(PortDirection.Output) => true
    case DeclarationKind.InternalCombinational      => true
    case _                                          => false
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

  private def createsCombinationalCycle(
      module: CanonicalModuleView,
      aliasSymbol: SymbolId,
      sourceSymbol: SymbolId
  ): Boolean = {
    if (aliasSymbol == sourceSymbol) return true

    val edges = mutable.Map.empty[SymbolId, Vector[SymbolId]]
    module.drivers
      .filter(_.kind == DriverKind.Continuous)
      .sortBy(_.id.value)
      .foreach { driver =>
        if (isCombinationalNode(module, driver.target)) {
          val dependencies = driver.value.referencedSymbols.distinct.sortBy(_.value)
          val current = edges.getOrElse(driver.target, Vector.empty)
          edges.update(
            driver.target,
            (current ++ dependencies).distinct.sortBy(_.value)
          )
        }
      }

    val pending = mutable.Stack[SymbolId](sourceSymbol)
    val visited = mutable.Set.empty[SymbolId]
    while (pending.nonEmpty) {
      val current = pending.pop()
      if (current == aliasSymbol) return true
      if (!visited.contains(current)) {
        visited += current
        edges.getOrElse(current, Vector.empty).reverse.foreach(value => pending.push(value))
      }
    }
    false
  }

  private def isCombinationalNode(
      module: CanonicalModuleView,
      symbol: SymbolId
  ): Boolean =
    module.declaration(symbol).exists { declaration =>
      declaration.kind match {
        case DeclarationKind.InternalCombinational      => true
        case DeclarationKind.Port(PortDirection.Output) => true
        case _                                          => false
      }
    }

  private def expressionNodeCount(expression: RtlExpr): Int = expression match {
    case _: RtlExpr.Ref     => 1
    case _: RtlExpr.Literal => 1
    case RtlExpr.Unary(_, value) => 1 + expressionNodeCount(value)
    case RtlExpr.Binary(_, left, right) =>
      1 + expressionNodeCount(left) + expressionNodeCount(right)
    case RtlExpr.Mux(condition, whenTrue, whenFalse) =>
      1 + expressionNodeCount(condition) + expressionNodeCount(whenTrue) +
        expressionNodeCount(whenFalse)
    case RtlExpr.Concat(values) => 1 + values.map(expressionNodeCount).sum
    case RtlExpr.BitSelect(value, index) =>
      1 + expressionNodeCount(value) + expressionNodeCount(index)
    case RtlExpr.PartSelect(value, _, _) => 1 + expressionNodeCount(value)
    case RtlExpr.Resize(value, _, _)     => 1 + expressionNodeCount(value)
    case RtlExpr.Cast(value, _)          => 1 + expressionNodeCount(value)
  }

  private def rootOperator(expression: RtlExpr): String = expression match {
    case _: RtlExpr.Ref                => "reference"
    case _: RtlExpr.Literal            => "literal"
    case RtlExpr.Unary(operator, _)    => s"unary:${operator.label}"
    case RtlExpr.Binary(operator, _, _) => s"binary:${operator.label}"
    case _: RtlExpr.Mux                => "mux"
    case _: RtlExpr.Concat             => "concat"
    case _: RtlExpr.BitSelect          => "bit-select"
    case _: RtlExpr.PartSelect         => "part-select"
    case _: RtlExpr.Resize             => "resize"
    case _: RtlExpr.Cast               => "cast"
  }

  private def rejectedExpression(
      assessment: Assessment
  ): Option[RejectedWireAlias] =
    assessment.violations.headOption.map { value =>
      RejectedWireAlias(
        aliasSymbol = passSymbol(assessment.alias.id),
        nameOrigin = AliasNameOrigin.Unnamed,
        reasonCode = value.code,
        message = value.message,
        location = assessment.alias.sourceLocation.flatMap(passLocation)
      )
    }

  private def eliminationDiagnostics(
      eliminated: Vector[EliminatedWireExpression]
  ): Vector[PassDiagnostic] =
    eliminated.map { value =>
      PassDiagnostic(
        code = UnnamedWireExpressionDiagnosticCode.Eliminated,
        severity = DiagnosticSeverity.Info,
        message =
          s"inlined unnamed ${value.rootOperator} expression '${value.aliasSymbol.value}' into ${value.receiverCount} continuous receiver(s) and removed the temporary",
        passId = Some(passId),
        location = value.location
      )
    }

  private def rejectionDiagnostics(
      assessments: Vector[Assessment]
  ): Vector[PassDiagnostic] =
    assessments.filterNot(_.isEligible).flatMap { assessment =>
      assessment.violations.map { value =>
        PassDiagnostic(
          code = UnnamedWireExpressionDiagnosticCode.Rejected,
          severity = DiagnosticSeverity.Warning,
          message =
            s"retained unnamed expression temporary '${assessment.alias.id.value}': ${value.code}: ${value.message}",
          passId = Some(passId),
          location = assessment.alias.sourceLocation.flatMap(passLocation)
        )
      }
    }

  private def canonicalDiagnostics(
      failure: CanonicalIrAdapterFailure,
      prefix: String
  ): Vector[PassDiagnostic] =
    failure.diagnostics.values.map { diagnostic =>
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
  ): PassDiagnostic =
    PassDiagnostic(
      code = UnnamedWireExpressionDiagnosticCode.RewriteInvariant,
      severity = DiagnosticSeverity.Error,
      message =
        s"cannot eliminate unnamed expression temporary '${assessment.alias.id.value}': $message",
      passId = Some(passId),
      location = assessment.alias.sourceLocation.flatMap(passLocation)
    )

  private def violation(code: String, message: String): Violation =
    Violation(code, message)

  private def passSymbol(value: SymbolId): IrSymbolId =
    IrSymbolId.unsafe(value.value)

  private def passLocation(
      value: morphhdl.ir.v1.SourceLocation
  ): Option[PassSourceLocation] =
    Option(value).flatMap { item =>
      val path = Option(item.path).map(_.trim).getOrElse("")
      if (path.nonEmpty && item.line >= 1 && item.column >= 1) {
        Some(PassSourceLocation(path, item.line, item.column))
      } else None
    }

  private def diagnosticKey(
      value: PassDiagnostic
  ): (Int, String, Int, Int, String, String) = {
    val location = value.location match {
      case Some(source) => (source.path, source.line, source.column)
      case None         => ("", 0, 0)
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
