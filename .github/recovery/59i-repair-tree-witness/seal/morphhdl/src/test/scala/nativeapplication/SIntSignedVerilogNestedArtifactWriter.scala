package nativeapplication

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import spinal.core._

/** Supplement, not a replacement for the two immutable default-printer oracles.
  * The public native configuration retains nested expressions instead of cutting
  * them into temporary wires. The component source and production printer are unchanged.
  */
object SIntSignedVerilogNestedArtifactWriter {
  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 1, "expected one output directory")
    val directory = Paths.get(arguments(0)).toAbsolutePath.normalize()
    Files.createDirectories(directory)
    val output = directory.resolve("sint_cast_heavy_nested.v")
    val config = SpinalConfig(targetDirectory = directory.toString, cutLongExpressions = false)
    config.netlistFileName = output.getFileName.toString
    SpinalVerilog(config)(SIntSignedVerilogBaselineFixture.fixed())
    val text = new String(Files.readAllBytes(output), StandardCharsets.UTF_8)
    val header = """(?s)\A// Generator :[^\n]*\n// Component :[^\n]*\n// Git hash  :[^\n]*\n\n""".r
    val normalized = header.replaceFirstIn(text, "")
    require(normalized != text, "expected native volatile header")
    Files.write(output, normalized.getBytes(StandardCharsets.UTF_8))
  }
}
