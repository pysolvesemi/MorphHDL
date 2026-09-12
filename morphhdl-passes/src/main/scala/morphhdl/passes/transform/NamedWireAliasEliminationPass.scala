package morphhdl.passes.transform

import scala.collection.mutable.ArrayBuffer

import morphhdl.ir.v1.CanonicalIrHandoff
import morphhdl.ir.v1.DeclarationKind
import morphhdl.ir.v1.Design
import morphhdl.ir.v1.DriverId
import morphhdl.ir.v1.IrDiagnostic
import morphhdl.ir.v1.ModuleId
import morphhdl.ir.v1.NameOrigin
import morphhdl.ir.v1.RtlExpr
import morphhdl.ir.v1.SymbolId
import morphhdl.passes.adapter.CanonicalIrAdapterFailure
import morphhdl.passes.adapter.CanonicalIrPassAdapter
import morphhdl.passes.adapter.CanonicalIrPassView
import morphhdl.passes.api.AliasNameOrigin
import morphhdl.passes.api.DiagnosticSeverity
import morphhdl.passes.api.EliminatedWireAlias
import morphhdl.passes.api.EliminationReport
import morphhdl.passes.api.IrSymbolId
import morphhdl.passes.api.PassDiagnostic
import morphhdl.passes.api.PassId
import morphhdl.passes.api.PassResult
import morphhdl.passes.api.RejectedWireAlias
import morphhdl.passes.api.{SourceLocation => PassSourceLocation}
import morphhdl.passes.api.WireAliasPassConfiguration
import morphhdl.passes.safety.AliasSafetyAssessment
import morphhdl.passes.safety.AliasSafetyConfiguration
import morphhdl.passes.safety.WireAliasSafetyGate

/** Stable diagnostics produced by the named-or-generated simple-wire pass. */
object NamedWireAliasDiagnosticCode {
  val RewriteInvariant = "WA05-REWRITE-INVARIANT"
  val Eliminated = "WA05-ELIMINATED"
  val Rejected = "WA05-REJECTED"
}

/**
  * Component-generic elimination of proven named-or-generated direct wire aliases.
  *
  * Candidate classification is taken only from canonical [[NameOrigin.Explicit]],
  * [[NameOrigin.Reflected]], and [[NameOrigin.Generated]] metadata. Generated
  * aliases can be removed, but never outrank a meaningful name. For two safely
  * removable meaningful aliases, the shorter captured name survives;
  * meaningful provenance always outranks [[NameOrigin.Unnamed]] and
  * [[NameOrigin.Generated]], irrespective of spelling. Non-removable
  * declarations remain anchors. The pass never inspects logical module names,
  * source paths, or emitted identifier conventions.
  * Rewrites use exact [[SymbolId]] identity, never transfer the removed name,
  * and retain every surviving name and metadata record unchanged.
  */
object NamedWireAliasEliminationPass {
  val passId: PassId = PassId.NamedWireAliasElimination

  private final case class SuccessfulTransformation(
      output: Design,
      eliminated: Vector[EliminatedWireAlias]
  )

  private sealed trait RewriteDirection {
    def rank: Int
  }

  private case object EliminatePreferredSource extends RewriteDirection {
    override val rank: Int = 0
  }

  private case object ForwardIntoSource extends RewriteDirection {
    override val rank: Int = 1
  }

  private final case class PlannedRewrite(
      relation: AliasSafetyAssessment,
      removed: AliasSafetyAssessment,
      survivor: SymbolId,
      direction: RewriteDirection,
      output: Design
  )

  /** Consume the validated production envelope without discarding its profile. */
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
    run(
      handoff,
      WireAliasPassConfiguration(),
      AliasSafetyConfiguration()
    )

  def run(
      design: Design,
      configuration: WireAliasPassConfiguration = WireAliasPassConfiguration(),
      safetyConfiguration: AliasSafetyConfiguration = AliasSafetyConfiguration()
  ): PassResult[Design] = {
    require(configuration != null, "wire-alias pass configuration must not be null")
    require(safetyConfiguration != null, "alias safety configuration must not be null")

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
          transformToFixedPoint(initialView.design, safetyConfiguration) match {
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
              val finalAssessments: Vector[AliasSafetyAssessment] = WireAliasSafetyGate
                .analyze(finalView, safetyConfiguration)
                .normalized
                .assessments
                .filter(isDirectPassReportCandidate)
              val rejected: Vector[RejectedWireAlias] = finalAssessments
                .filterNot(_.isEligible)
                .flatMap(rejectedAlias)
              val report: EliminationReport = EliminationReport(
                passId = passId,
                eliminated = transformation.eliminated,
                rejected = rejected
              ).normalized
              val diagnostics: Vector[PassDiagnostic] = (
                eliminationDiagnostics(report.eliminated) ++
                  rejectionDiagnostics(finalAssessments)
              ).sortBy(diagnosticKey)

              if (report.eliminated.nonEmpty) {
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
      initial: Design,
      safetyConfiguration: AliasSafetyConfiguration
  ): Either[Vector[PassDiagnostic], SuccessfulTransformation] = {
    var current = initial
    val eliminated = ArrayBuffer.empty[EliminatedWireAlias]
    var complete = false

    while (!complete) {
      val view: CanonicalIrPassView = CanonicalIrPassAdapter.bindFixture(current) match {
        case Right(value) => value
        case Left(failure) =>
          return Left(
            canonicalDiagnostics(
              failure,
              "intermediate canonical IR validation failed"
            )
          )
      }
      val safetyReport = WireAliasSafetyGate
        .analyze(view, safetyConfiguration)
        .normalized
      val assessmentsBySymbol = safetyReport.assessments.map { value =>
        (value.moduleId, value.aliasSymbol) -> value
      }.toMap
      val eligible = safetyReport.eligible
        .filter(value => candidateOrigin(value.nameOrigin).nonEmpty)
        .sortBy(value => (value.moduleId.value, value.aliasSymbol.value))
      val planned = ArrayBuffer.empty[PlannedRewrite]
      eligible.foreach { assessment =>
        planRewrite(current, view, assessment, assessmentsBySymbol) match {
          case Left(diagnostic) => return Left(Vector(diagnostic))
          case Right(Some(value)) => planned += value
          case Right(None) =>
        }
      }

      planned.toVector
        .sortBy { value =>
          (
            value.direction.rank,
            value.relation.moduleId.value,
            value.relation.aliasSymbol.value,
            value.removed.aliasSymbol.value,
            value.survivor.value
          )
        }
        .headOption match {
        case None => complete = true
        case Some(plan) =>
          val removedOrigin = reportOrigin(plan.removed.nameOrigin).getOrElse {
            return Left(
              Vector(
                invariantDiagnostic(
                  plan.relation,
                  "selected removal published no proven naming provenance"
                )
              )
            )
          }
          CanonicalIrPassAdapter.bindFixture(plan.output) match {
            case Left(failure) =>
              return Left(
                canonicalDiagnostics(
                  failure,
                  s"canonical IR validation failed after eliminating '${renderedName(plan.removed)}'"
                )
              )
            case Right(rebound) =>
              current = rebound.design
              eliminated += EliminatedWireAlias(
                aliasSymbol = passSymbol(plan.removed.aliasSymbol),
                sourceSymbol = passSymbol(plan.survivor),
                nameOrigin = removedOrigin,
                location = plan.removed.sourceLocation.flatMap(passLocation)
              )
          }
      }
    }

    Right(SuccessfulTransformation(current, eliminated.toVector))
  }

  /**
    * Select a survivor without interpreting generated identifier spelling.
    *
    * A direct source which cannot itself be removed is an anchor. When the
    * preferred alias points at an independently eligible expression temporary,
    * defer the decision so the expression-elimination pass can remove that
    * temporary without first destroying the meaningful alias.
    */
  private def planRewrite(
      design: Design,
      view: CanonicalIrPassView,
      assessment: AliasSafetyAssessment,
      assessmentsBySymbol: Map[(ModuleId, SymbolId), AliasSafetyAssessment]
  ): Either[PassDiagnostic, Option[PlannedRewrite]] = {
    val module = view.module(assessment.moduleId).getOrElse {
      return Left(
        invariantDiagnostic(
          assessment,
          s"owning module '${assessment.moduleId.value}' is unavailable"
        )
      )
    }
    val sourceSymbol = assessment.sourceSymbol.getOrElse {
      return Left(
        invariantDiagnostic(
          assessment,
          "eligible alias published no source symbol"
        )
      )
    }
    val source = module.declaration(sourceSymbol).getOrElse {
      return Left(
        invariantDiagnostic(
          assessment,
          s"eligible alias source '${sourceSymbol.value}' is unavailable"
        )
      )
    }
    val aliasDrivers = module.driversTargeting(assessment.aliasSymbol)
    if (aliasDrivers.size != 1) {
      return Left(
        invariantDiagnostic(
          assessment,
          s"eligible alias published ${aliasDrivers.size} drivers"
        )
      )
    }

    def forward: PlannedRewrite =
      PlannedRewrite(
        relation = assessment,
        removed = assessment,
        survivor = sourceSymbol,
        direction = ForwardIntoSource,
        output = rewriteOneAlias(
          design,
          assessment.moduleId,
          assessment.aliasSymbol,
          sourceSymbol,
          aliasDrivers.head.id
        )
      )

    if (source.kind != DeclarationKind.InternalCombinational) {
      Right(Some(forward))
    } else if (!aliasIsPreferred(assessment, source.id, source.nameOrigin)) {
      Right(Some(forward))
    } else {
      assessmentsBySymbol.get((assessment.moduleId, sourceSymbol)) match {
        case Some(sourceAssessment) if sourceAssessment.isEligible =>
          val sourceDrivers = module.driversTargeting(sourceSymbol)
          val sourceSource = sourceAssessment.sourceSymbol
          if (sourceDrivers.size != 1 || sourceSource.isEmpty) {
            Left(
              invariantDiagnostic(
                assessment,
                s"eligible preferred source '${sourceSymbol.value}' published an incomplete direct-alias proof"
              )
            )
          } else {
            Right(
              Some(
                PlannedRewrite(
                  relation = assessment,
                  removed = sourceAssessment,
                  survivor = sourceSource.get,
                  direction = EliminatePreferredSource,
                  output = rewriteOneAlias(
                    design,
                    assessment.moduleId,
                    sourceSymbol,
                    sourceSource.get,
                    sourceDrivers.head.id
                  )
                )
              )
            )
          }
        case _
            if expressionSourceIsIndependentlyEligible(
              design,
              sourceSymbol,
              source.nameOrigin
            ) =>
          Right(None)
        case _ => Right(Some(forward))
      }
    }
  }

  private def aliasIsPreferred(
      alias: AliasSafetyAssessment,
      sourceSymbol: SymbolId,
      sourceOrigin: NameOrigin
  ): Boolean =
    (meaningfulName(alias.nameOrigin), meaningfulName(sourceOrigin)) match {
      case (Some(aliasName), Some(sourceName)) =>
        implicitly[Ordering[(Int, String, String)]].lt(
          (aliasName.length, aliasName, alias.aliasSymbol.value),
          (sourceName.length, sourceName, sourceSymbol.value)
        )
      case (Some(_), None) =>
        sourceOrigin == NameOrigin.Unnamed || sourceOrigin == NameOrigin.Generated
      case _ => false
    }

  private def expressionSourceIsIndependentlyEligible(
      design: Design,
      source: SymbolId,
      origin: NameOrigin
  ): Boolean =
    origin match {
      case NameOrigin.Unnamed =>
        UnnamedWireExpressionEliminationPass
          .isEligibleUnnamedCandidate(design, source)
      case NameOrigin.Explicit(_) | NameOrigin.Reflected(_) | NameOrigin.Generated =>
        UnnamedWireExpressionEliminationPass
          .isEligibleNamedCandidate(design, source)
      case NameOrigin.Unknown => false
    }

  private def rewriteOneAlias(
      design: Design,
      moduleId: ModuleId,
      aliasSymbol: SymbolId,
      sourceSymbol: SymbolId,
      aliasDriverId: DriverId
  ): Design =
    design
      .copy(
        modules = design.modules.map { module =>
          if (module.id != moduleId) module
          else {
            module.copy(
              declarations = module.declarations.filterNot(_.id == aliasSymbol),
              drivers = module.drivers
                .filterNot(_.id == aliasDriverId)
                .map { driver =>
                  driver.copy(
                    value = rewriteReferences(
                      driver.value,
                      aliasSymbol,
                      sourceSymbol
                    )
                  )
                }
            )
          }
        }
      )
      .normalized

  private def rewriteReferences(
      expression: RtlExpr,
      aliasSymbol: SymbolId,
      sourceSymbol: SymbolId
  ): RtlExpr = expression match {
    case value @ RtlExpr.Ref(_, target, _, _) =>
      if (target == aliasSymbol) value.copy(target = sourceSymbol) else value
    case value @ RtlExpr.Literal(_, _, _) => value
    case RtlExpr.Unary(operator, value) =>
      RtlExpr.Unary(operator, rewriteReferences(value, aliasSymbol, sourceSymbol))
    case RtlExpr.Binary(operator, left, right) =>
      RtlExpr.Binary(
        operator,
        rewriteReferences(left, aliasSymbol, sourceSymbol),
        rewriteReferences(right, aliasSymbol, sourceSymbol)
      )
    case RtlExpr.Mux(condition, whenTrue, whenFalse) =>
      RtlExpr.Mux(
        rewriteReferences(condition, aliasSymbol, sourceSymbol),
        rewriteReferences(whenTrue, aliasSymbol, sourceSymbol),
        rewriteReferences(whenFalse, aliasSymbol, sourceSymbol)
      )
    case RtlExpr.Concat(values) =>
      RtlExpr.Concat(
        values.map(value => rewriteReferences(value, aliasSymbol, sourceSymbol))
      )
    case RtlExpr.BitSelect(value, index) =>
      RtlExpr.BitSelect(
        rewriteReferences(value, aliasSymbol, sourceSymbol),
        rewriteReferences(index, aliasSymbol, sourceSymbol)
      )
    case RtlExpr.PartSelect(value, offset, width) =>
      RtlExpr.PartSelect(
        rewriteReferences(value, aliasSymbol, sourceSymbol),
        offset,
        width
      )
    case RtlExpr.Resize(value, width, signedness) =>
      RtlExpr.Resize(
        rewriteReferences(value, aliasSymbol, sourceSymbol),
        width,
        signedness
      )
    case RtlExpr.Cast(value, signedness) =>
      RtlExpr.Cast(
        rewriteReferences(value, aliasSymbol, sourceSymbol),
        signedness
      )
  }

  private def rejectedAlias(
      assessment: AliasSafetyAssessment
  ): Option[RejectedWireAlias] =
    for {
      violation <- assessment.violations.headOption
      origin <- reportOrigin(assessment.nameOrigin)
    } yield RejectedWireAlias(
      aliasSymbol = passSymbol(assessment.aliasSymbol),
      nameOrigin = origin,
      reasonCode = violation.code,
      message = violation.message,
      location = assessment.sourceLocation.flatMap(passLocation)
    )

  private def eliminationDiagnostics(
      eliminated: Vector[EliminatedWireAlias]
  ): Vector[PassDiagnostic] =
    eliminated.map { value =>
      val name = value.nameOrigin.explicitName.getOrElse(value.aliasSymbol.value)
      PassDiagnostic(
        code = NamedWireAliasDiagnosticCode.Eliminated,
        severity = DiagnosticSeverity.Info,
        message =
          s"eliminated named alias '$name' in favor of '${value.sourceSymbol.value}' without transferring the removed name",
        passId = Some(passId),
        location = value.location
      )
    }

  private def rejectionDiagnostics(
      assessments: Vector[AliasSafetyAssessment]
  ): Vector[PassDiagnostic] =
    assessments
      .filterNot(_.isEligible)
      .flatMap { assessment =>
        candidateOrigin(assessment.nameOrigin).toVector.flatMap { _ =>
          val name = renderedName(assessment)
          assessment.violations.map { violation =>
            PassDiagnostic(
              code = NamedWireAliasDiagnosticCode.Rejected,
              severity = DiagnosticSeverity.Warning,
              message =
                s"retained named alias '$name': ${violation.code}: ${violation.message}",
              passId = Some(passId),
              location = assessment.sourceLocation.flatMap(passLocation)
            )
          }
        }
      }

  private def candidateOrigin(value: NameOrigin): Option[AliasNameOrigin] =
    value match {
      case NameOrigin.Explicit(name)  => Some(AliasNameOrigin.Explicit(name))
      case NameOrigin.Reflected(name) => Some(AliasNameOrigin.Reflected(name))
      case NameOrigin.Generated       => Some(AliasNameOrigin.Generated)
      case NameOrigin.Unnamed         => None
      case NameOrigin.Unknown         => None
    }

  /** WA-09, rather than this direct-alias pass, owns generated expressions. */
  private def isDirectPassReportCandidate(
      value: AliasSafetyAssessment
  ): Boolean =
    candidateOrigin(value.nameOrigin).nonEmpty &&
      (value.nameOrigin != NameOrigin.Generated || value.sourceSymbol.nonEmpty)

  private def reportOrigin(value: NameOrigin): Option[AliasNameOrigin] =
    value match {
      case NameOrigin.Unnamed => Some(AliasNameOrigin.Unnamed)
      case other              => candidateOrigin(other)
    }

  private def meaningfulName(value: NameOrigin): Option[String] =
    value match {
      case NameOrigin.Explicit(name) => Some(name)
      case NameOrigin.Reflected(name) => Some(name)
      case _                          => None
    }

  private def renderedName(value: AliasSafetyAssessment): String =
    meaningfulName(value.nameOrigin).getOrElse(value.aliasSymbol.value)

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
      assessment: AliasSafetyAssessment,
      message: String
  ): PassDiagnostic = {
    val name = meaningfulName(assessment.nameOrigin).getOrElse(assessment.aliasSymbol.value)
    PassDiagnostic(
      code = NamedWireAliasDiagnosticCode.RewriteInvariant,
      severity = DiagnosticSeverity.Error,
      message = s"cannot eliminate named alias '$name': $message",
      passId = Some(passId),
      location = assessment.sourceLocation.flatMap(passLocation)
    )
  }

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
      case Some(item) => (item.path, item.line, item.column)
      case None       => ("", 0, 0)
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
