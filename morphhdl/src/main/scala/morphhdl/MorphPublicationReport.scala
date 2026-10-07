package morphhdl

/** A successful publication's authenticated decisions, independent of file layout. */
final case class MorphPublicationModule(
    name: String, instances: Vector[String], parameters: Vector[(String, Vector[String])],
    loops: Vector[String], vectors: Vector[String])

final case class MorphPublicationReport(
    generated: MorphSingleSourceVerilogReport, modules: Vector[MorphPublicationModule],
    settings: Vector[(String, String)] = Vector.empty) {
  /** No timestamps, absolute paths or JVM identities; suitable for repeat comparisons. */
  def toJson: String = {
    def quote(value: String): String = "\"" + value.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c => c.toString
    } + "\""
    def array(values: Vector[String]): String = values.mkString("[", ",", "]")
    val configuration = settings.map { case (name, value) => quote(name) + ":" + quote(value) }.mkString("{", ",", "}")
    val entries = modules.map { m =>
      val parameters = m.parameters.map { case (name, reasons) =>
        s"""{"name":${quote(name)},"uses":${array(reasons.map(quote))}}"""
      }
      s"""{"name":${quote(m.name)},"sharing":${quote(if (m.instances.size > 1) "shared authenticated definition" else "single instance")},""" +
        s""""instances":${array(m.instances.map(quote))},"parameters":${array(parameters)},""" +
        s""""loops":${array(m.loops.map(quote))},"vectors":${array(m.vectors.map(quote))}}"""
    }
    s"""{"schemaVersion":1,"top":${quote(generated.toplevelName)},"modules":${array(entries)},"settings":$configuration,""" +
      "\"unrolling\":\"Ordinary Scala loops execute before capture; their origin and iteration count are not observable in this report.\"}\n"
  }
}
