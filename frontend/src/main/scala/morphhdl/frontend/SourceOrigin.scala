package morphhdl.frontend

/** Source location retained by a symbolic frontend value. */
final case class SourceOrigin(file: String, line: Int) {
  require(file.nonEmpty, "source-origin file must not be empty")
  require(line > 0, "source-origin line must be positive")

  def rendered: String = s"$file:$line"
}

object SourceOrigin {
  private[frontend] def capture(implicit file: sourcecode.File, line: sourcecode.Line): SourceOrigin =
    SourceOrigin(file.value, line.value)
}
