package morphhdl.frontend

private[frontend] final case class GenerateNames(label: String, index: String)

final class HdlRange private[frontend] (
    private[frontend] val start: Int,
    private[frontend] val end: HdlInt,
    private[frontend] val names: Option[GenerateNames],
    private[frontend] val origin: SourceOrigin
) {
  def named(label: String, index: String)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlRange = {
    val namedOrigin = SourceOrigin.capture
    HdlRange.requireIdentifier(label, "generate label", namedOrigin)
    HdlRange.requireIdentifier(index, "generate index", namedOrigin)
    new HdlRange(start, end, Some(GenerateNames(label, index)), origin)
  }

  def foreach(body: GenIndex => Unit): Unit =
    FrontendSession.runRange(this, body)
}

private[frontend] object HdlRange {
  private val Identifier = "[A-Za-z_][A-Za-z0-9_]*".r

  def apply(start: Int, end: HdlInt, origin: SourceOrigin): HdlRange =
    new HdlRange(start, end, names = None, origin)

  def generatedNames(origin: SourceOrigin): GenerateNames = {
    val normalized = origin.file.replace('\\', '/')
    val fileName = normalized.substring(normalized.lastIndexOf('/') + 1)
    val stem = fileName.lastIndexOf('.') match {
      case index if index > 0 => fileName.substring(0, index)
      case _                  => fileName
    }
    val safeStem = stem.replaceAll("[^A-Za-z0-9_]", "_") match {
      case value if value.nonEmpty && value.charAt(0).isDigit => s"_$value"
      case value if value.nonEmpty                            => value
      case _                                                  => "source"
    }
    val suffix = s"${safeStem}_l${origin.line}"
    GenerateNames(s"g_generate_$suffix", s"gen_index_$suffix")
  }

  def requireIdentifier(value: String, role: String, origin: SourceOrigin): Unit =
    if (value == null || !Identifier.pattern.matcher(value).matches()) {
      FrontendException.failAt(
        "MORPH-FRONTEND-INVALID-GENERATE-NAME",
        s"$role '$value' is not a portable identifier",
        origin
      )
    }
}
