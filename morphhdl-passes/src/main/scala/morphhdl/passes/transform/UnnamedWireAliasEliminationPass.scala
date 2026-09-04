package morphhdl.passes.transform

import scala.collection.mutable.ArrayBuffer

import morphhdl.ir.v1.CanonicalIrHandoff
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

/** Stable diagnostics produced by the unnamed simple-wire pass. */
object UnnamedWireAliasDiagnosticCode {
  val RewriteInvariant = "WA04-REWRITE-INVARIANT"
  val Eliminated = "WA04-ELIMINATED"
  val Rejected = "WA04-REJECTED"
}

/**
  * Component-generic elimination of proven unnamed direct wire aliases.
  *
  * Candidate classification is taken only from canonical [[NameOrigin.Unnamed]]
  * metadata. The pass never inspects logical module names, source paths, or
  * emitted identifier text. Rewrites use exact [[SymbolId]] identity and retain
  * every surviving declaration, driver, reference identity, name, source
  * location, attribute, comment, parameter, scope, and generate index.
  */
object UnnamedWireAliasEliminationPass {
  val passId: PassId = PassId.UnnamedWireAliasElimination

  private final case class SuccessfulTransformation(
      output: Design,
      eliminated: Vector[EliminatedWireAlias]
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

    if (!configuration.eliminateUnnamedAliases) {
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
                .filter(_.nameOrigin == NameOrigin.Unnamed)
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
      val eligible: Vector[AliasSafetyAssessment] = WireAliasSafetyGate
        .analyze(view, safetyConfiguration)
        .eligible
        .filter(_.nameOrigin == NameOrigin.Unnamed)
        .sortBy(value => (value.moduleId.value, value.aliasSymbol.value))

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
          val sourceSymbol = assessment.sourceSymbol.getOrElse {
            return Left(
              Vector(
                invariantDiagnostic(
                  assessment,
                  "eligible alias published no source symbol"
                )
              )
            )
          }
          val aliasDrivers = module.driversTargeting(assessment.aliasSymbol)
          if (aliasDrivers.size != 1) {
            return Left(
              Vector(
                invariantDiagnostic(
                  assessment,
                  s"eligible alias published ${aliasDrivers.size} drivers"
                )
              )
            )
          }

          val rewritten = rewriteOneAlias(
            current,
            assessment.moduleId,
            assessment.aliasSymbol,
            sourceSymbol,
            aliasDrivers.head.id
          )
          CanonicalIrPassAdapter.bindFixture(rewritten) match {
            case Left(failure) =>
              return Left(
                canonicalDiagnostics(
                  failure,
                  s"canonical IR validation failed after eliminating '${assessment.aliasSymbol.value}'"
                )
              )
            case Right(rebound) =>
              current = rebound.design
              eliminated += EliminatedWireAlias(
                aliasSymbol = passSymbol(assessment.aliasSymbol),
                sourceSymbol = passSymbol(sourceSymbol),
                nameOrigin = AliasNameOrigin.Unnamed,
                location = assessment.sourceLocation.flatMap(passLocation)
              )
          }
      }
    }

    Right(SuccessfulTransformation(current, eliminated.toVector))
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
    assessment.violations.headOption.map { violation =>
      RejectedWireAlias(
        aliasSymbol = passSymbol(assessment.aliasSymbol),
        nameOrigin = AliasNameOrigin.Unnamed,
        reasonCode = violation.code,
        message = violation.message,
        location = assessment.sourceLocation.flatMap(passLocation)
      )
    }

  private def eliminationDiagnostics(
      eliminated: Vector[EliminatedWireAlias]
  ): Vector[PassDiagnostic] =
    eliminated.map { value =>
      PassDiagnostic(
        code = UnnamedWireAliasDiagnosticCode.Eliminated,
        severity = DiagnosticSeverity.Info,
        message =
          s"eliminated unnamed alias '${value.aliasSymbol.value}' in favor of '${value.sourceSymbol.value}'",
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
        assessment.violations.map { violation =>
          PassDiagnostic(
            code = UnnamedWireAliasDiagnosticCode.Rejected,
            severity = DiagnosticSeverity.Warning,
            message =
              s"retained unnamed alias '${assessment.aliasSymbol.value}': ${violation.code}: ${violation.message}",
            passId = Some(passId),
            location = assessment.sourceLocation.flatMap(passLocation)
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
      assessment: AliasSafetyAssessment,
      message: String
  ): PassDiagnostic =
    PassDiagnostic(
      code = UnnamedWireAliasDiagnosticCode.RewriteInvariant,
      severity = DiagnosticSeverity.Error,
      message =
        s"cannot eliminate unnamed alias '${assessment.aliasSymbol.value}': $message",
      passId = Some(passId),
      location = assessment.sourceLocation.flatMap(passLocation)
    )

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
