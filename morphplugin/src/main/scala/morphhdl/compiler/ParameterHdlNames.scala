package morphhdl.compiler

/** Public inferred parameter spelling; explicit HDL overrides never pass here. */
private[compiler] object ParameterHdlNames {
  def inferred(path: String): String = path.split("\\.", -1).map { segment =>
    segment.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
      .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
      .toUpperCase(java.util.Locale.ROOT)
  }.mkString("_")
}
