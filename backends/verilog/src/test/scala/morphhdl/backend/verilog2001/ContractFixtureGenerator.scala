package morphhdl.backend.verilog2001

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

object ContractFixtureGenerator {
  private final case class Fixture(filename: String, design: () => morphhdl.paramrtl.Design)

  private val fixtures = Vector(
    Fixture("parameterized_wire.v", () => ParameterizedWireFixture.design()),
    Fixture("derived_width.v", () => DerivedWidthFixture.design()),
    Fixture("parameter_forwarding.v", () => ParameterForwardingFixture.design())
  )

  def main(args: Array[String]): Unit = {
    val outputDirectory = args.toVector match {
      case Vector("--output-dir", path) => Paths.get(path)
      case _ =>
        throw new IllegalArgumentException(
          "Usage: ContractFixtureGenerator --output-dir <directory>"
        )
    }

    Files.createDirectories(outputDirectory)
    fixtures.foreach { fixture =>
      val verilog = Verilog2001Emitter.emit(fixture.design()) match {
        case Right(value) => value
        case Left(diagnostics) =>
          throw new IllegalStateException(diagnostics.values.mkString("\n"))
      }
      write(outputDirectory.resolve(fixture.filename), verilog)
    }
  }

  private def write(output: Path, value: String): Unit =
    Files.write(
      output,
      value.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )
}
