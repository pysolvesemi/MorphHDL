package morphhdl.backend.verilog2001

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}

object ParameterizedWireFixtureGenerator {
  def main(args: Array[String]): Unit = {
    val output = args.toVector match {
      case Vector("--output", path) => Paths.get(path)
      case _ =>
        throw new IllegalArgumentException(
          "Usage: ParameterizedWireFixtureGenerator --output <path>"
        )
    }

    val verilog = Verilog2001Emitter.emit(ParameterizedWireFixture.design()) match {
      case Right(value) => value
      case Left(diagnostics) =>
        throw new IllegalStateException(diagnostics.values.mkString("\n"))
    }

    val parent = output.toAbsolutePath.getParent
    if (parent != null) Files.createDirectories(parent)
    Files.write(
      output,
      verilog.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )
  }
}
