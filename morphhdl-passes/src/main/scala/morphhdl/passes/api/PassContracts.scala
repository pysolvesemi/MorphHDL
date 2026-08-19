package morphhdl.passes.api

/** Stable identifier for one optional MorphHDL IR pass. */
final case class PassId private (value: String) extends AnyVal {
  override def toString: String = value
}

object PassId {
  private val Valid = "[A-Za-z0-9][A-Za-z0-9._-]*".r

  def from(value: String): Either[String, PassId] = {
    val candidate = Option(value).map(_.trim).getOrElse("")
    candidate match {
      case Valid() => Right(new PassId(candidate))
      case _ =>
        Left(
          "pass id must be non-empty and contain only letters, digits, '.', '_' or '-'"
        )
    }
  }

  def unsafe(value: String): PassId =
    from(value) match {
      case Right(passId) => passId
      case Left(message) => throw new IllegalArgumentException(message)
    }

  val UnnamedWireAliasElimination: PassId = unsafe("wire-alias-unnamed")
  val NamedWireAliasElimination: PassId = unsafe("wire-alias-named")
}

/** Opaque identity supplied by the canonical MorphHDL IR adapter in WA-02. */
final case class IrSymbolId private (value: String) extends AnyVal {
  override def toString: String = value
}

object IrSymbolId {
  private val Valid = "\\S+".r

  def from(value: String): Either[String, IrSymbolId] = {
    val candidate = Option(value).map(_.trim).getOrElse("")
    candidate match {
      case Valid() => Right(new IrSymbolId(candidate))
      case _       => Left("IR symbol id must be non-empty and contain no whitespace")
    }
  }

  def unsafe(value: String): IrSymbolId =
    from(value) match {
      case Right(symbolId) => symbolId
      case Left(message)   => throw new IllegalArgumentException(message)
    }
}

/** Source position retained from elaboration/capture when available. */
final case class SourceLocation(path: String, line: Int, column: Int) {
  require(Option(path).exists(_.trim.nonEmpty), "source path must be non-empty")
  require(line >= 1, "source line must be at least 1")
  require(column >= 1, "source column must be at least 1")
}

sealed abstract class DiagnosticSeverity(val label: String, val rank: Int)

object DiagnosticSeverity {
  case object Info extends DiagnosticSeverity("info", 0)
  case object Warning extends DiagnosticSeverity("warning", 1)
  case object Error extends DiagnosticSeverity("error", 2)
}

/** Deterministic diagnostic emitted by pass discovery, validation or execution. */
final case class PassDiagnostic(
    code: String,
    severity: DiagnosticSeverity,
    message: String,
    passId: Option[PassId] = None,
    location: Option[SourceLocation] = None
) {
  require(Option(code).exists(_.trim.nonEmpty), "diagnostic code must be non-empty")
  require(Option(message).exists(_.trim.nonEmpty), "diagnostic message must be non-empty")
}

/**
  * Plug-and-play selection for exactly the two passes authorized by this roadmap.
  * Both passes are disabled by default. Combined execution order is fixed:
  * unnamed aliases first, then named aliases.
  */
final case class WireAliasPassConfiguration(
    eliminateUnnamedAliases: Boolean = false,
    eliminateNamedAliases: Boolean = false
) {
  def enabledPasses: Vector[PassId] = {
    val builder = Vector.newBuilder[PassId]
    if (eliminateUnnamedAliases) builder += PassId.UnnamedWireAliasElimination
    if (eliminateNamedAliases) builder += PassId.NamedWireAliasElimination
    builder.result()
  }

  def isDisabled: Boolean = enabledPasses.isEmpty
}

sealed trait AliasNameOrigin extends Product with Serializable {
  def explicitName: Option[String]
}

object AliasNameOrigin {
  case object Unnamed extends AliasNameOrigin {
    override val explicitName: Option[String] = None
  }

  final case class Explicit(value: String) extends AliasNameOrigin {
    require(Option(value).exists(_.trim.nonEmpty), "explicit alias name must be non-empty")
    override val explicitName: Option[String] = Some(value)
  }
}

/** One exact alias declaration removed by a future WA-04 or WA-05 execution. */
final case class EliminatedWireAlias(
    aliasSymbol: IrSymbolId,
    sourceSymbol: IrSymbolId,
    nameOrigin: AliasNameOrigin,
    location: Option[SourceLocation] = None
)

/** Candidate retained because one or more safety conditions were not proven. */
final case class RejectedWireAlias(
    aliasSymbol: IrSymbolId,
    nameOrigin: AliasNameOrigin,
    reasonCode: String,
    message: String,
    location: Option[SourceLocation] = None
) {
  require(Option(reasonCode).exists(_.trim.nonEmpty), "rejection code must be non-empty")
  require(Option(message).exists(_.trim.nonEmpty), "rejection message must be non-empty")
}

/** Stable, user-visible evidence produced by one wire-alias pass. */
final case class EliminationReport(
    passId: PassId,
    eliminated: Vector[EliminatedWireAlias] = Vector.empty,
    rejected: Vector[RejectedWireAlias] = Vector.empty
) {
  def eliminatedCount: Int = eliminated.size
  def rejectedCount: Int = rejected.size
  def isEmpty: Boolean = eliminated.isEmpty && rejected.isEmpty

  def normalized: EliminationReport =
    copy(
      eliminated = eliminated.sortBy(EliminationReport.eliminatedKey),
      rejected = rejected.sortBy(EliminationReport.rejectedKey)
    )
}

object EliminationReport {
  private def locationKey(location: Option[SourceLocation]): (String, Int, Int) =
    location match {
      case Some(value) => (value.path, value.line, value.column)
      case None        => ("", 0, 0)
    }

  private def nameKey(origin: AliasNameOrigin): String =
    origin.explicitName.getOrElse("")

  private[api] def eliminatedKey(
      value: EliminatedWireAlias
  ): (String, Int, Int, String, String, String) = {
    val location = locationKey(value.location)
    (
      location._1,
      location._2,
      location._3,
      nameKey(value.nameOrigin),
      value.aliasSymbol.value,
      value.sourceSymbol.value
    )
  }

  private[api] def rejectedKey(
      value: RejectedWireAlias
  ): (String, Int, Int, String, String, String) = {
    val location = locationKey(value.location)
    (
      location._1,
      location._2,
      location._3,
      nameKey(value.nameOrigin),
      value.aliasSymbol.value,
      value.reasonCode
    )
  }
}

sealed trait PassExecutionStatus extends Product with Serializable {
  def changed: Boolean
  def failed: Boolean
}

object PassExecutionStatus {
  case object Skipped extends PassExecutionStatus {
    override val changed: Boolean = false
    override val failed: Boolean = false
  }

  case object Unchanged extends PassExecutionStatus {
    override val changed: Boolean = false
    override val failed: Boolean = false
  }

  case object Changed extends PassExecutionStatus {
    override val changed: Boolean = true
    override val failed: Boolean = false
  }

  case object Failed extends PassExecutionStatus {
    override val changed: Boolean = false
    override val failed: Boolean = true
  }
}

/** Generic immutable result for a pass over a future canonical MorphHDL IR value. */
final case class PassResult[A](
    output: A,
    status: PassExecutionStatus,
    diagnostics: Vector[PassDiagnostic],
    eliminationReport: EliminationReport
) {
  private val hasErrorDiagnostic = diagnostics.exists(
    _.severity == DiagnosticSeverity.Error
  )

  require(
    status != PassExecutionStatus.Changed || eliminationReport.eliminated.nonEmpty,
    "a changed wire-alias pass result must report at least one eliminated alias"
  )
  require(
    status == PassExecutionStatus.Changed || eliminationReport.eliminated.isEmpty,
    "a non-changing wire-alias pass result cannot report eliminated aliases"
  )
  require(
    status.failed == hasErrorDiagnostic,
    "failed results require an error diagnostic and successful results cannot contain one"
  )

  def changed: Boolean = status.changed
  def isSuccess: Boolean = !status.failed
  def hasErrors: Boolean = hasErrorDiagnostic

  def normalized: PassResult[A] =
    copy(
      diagnostics = diagnostics.sortBy(PassResult.diagnosticKey),
      eliminationReport = eliminationReport.normalized
    )
}

object PassResult {
  private def locationKey(location: Option[SourceLocation]): (String, Int, Int) =
    location match {
      case Some(value) => (value.path, value.line, value.column)
      case None        => ("", 0, 0)
    }

  private[api] def diagnosticKey(
      value: PassDiagnostic
  ): (Int, String, Int, Int, String, String, String) = {
    val location = locationKey(value.location)
    (
      value.severity.rank,
      location._1,
      location._2,
      location._3,
      value.passId.map(_.value).getOrElse(""),
      value.code,
      value.message
    )
  }

  def skipped[A](output: A, passId: PassId): PassResult[A] =
    PassResult(
      output = output,
      status = PassExecutionStatus.Skipped,
      diagnostics = Vector.empty,
      eliminationReport = EliminationReport(passId)
    )

  def unchanged[A](
      output: A,
      report: EliminationReport,
      diagnostics: Vector[PassDiagnostic] = Vector.empty
  ): PassResult[A] =
    PassResult(output, PassExecutionStatus.Unchanged, diagnostics, report)

  def changed[A](
      output: A,
      report: EliminationReport,
      diagnostics: Vector[PassDiagnostic] = Vector.empty
  ): PassResult[A] =
    PassResult(output, PassExecutionStatus.Changed, diagnostics, report)

  def failed[A](
      output: A,
      report: EliminationReport,
      diagnostics: Vector[PassDiagnostic]
  ): PassResult[A] =
    PassResult(output, PassExecutionStatus.Failed, diagnostics, report)
}
