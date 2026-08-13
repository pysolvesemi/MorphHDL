package morphhdl

import java.nio.file.{Files, Path, Paths}

import spinal.core.{Component, SpinalConfig}

/** CLI dispatch for the seven public-entry-point contract fixtures. */
object MorphContractFixtureGenerator {
  private final case class Options(outputDirectory: Path, reverseConstructionOrder: Boolean)

  private final case class Fixture(
      filename: String,
      program: Boolean => MorphProgram[Component]
  )

  private val fixtures = Vector(
    Fixture("parameterized_wire.v", ParameterizedWireContractFixture.program),
    Fixture("derived_width.v", DerivedWidthContractFixture.program),
    Fixture("parameter_forwarding.v", ParameterForwardingContractFixture.program),
    Fixture("lane_array.v", LaneArrayContractFixture.program),
    Fixture("conditional_forwarding.v", ConditionalForwardingContractFixture.program),
    Fixture("comparison_routing.v", ComparisonRoutingContractFixture.program),
    Fixture("conditional_width.v", ConditionalWidthContractFixture.program)
  )

  def main(args: Array[String]): Unit = {
    val options = parseOptions(args.toVector)
    Files.createDirectories(options.outputDirectory)

    fixtures.foreach { fixture =>
      val config = SpinalConfig(targetDirectory = options.outputDirectory.toString)
      config.netlistFileName = fixture.filename
      MorphVerilog(config)(fixture.program(options.reverseConstructionOrder))
    }
  }

  private def parseOptions(args: Vector[String]): Options = {
    @annotation.tailrec
    def loop(
        remaining: Vector[String],
        outputDirectory: Option[Path],
        reverseConstructionOrder: Boolean
    ): Options = remaining match {
      case Vector() =>
        outputDirectory match {
          case Some(directory) => Options(directory, reverseConstructionOrder)
          case None            => usage()
        }
      case "--output-dir" +: path +: tail if outputDirectory.isEmpty =>
        loop(tail, Some(Paths.get(path)), reverseConstructionOrder)
      case "--reverse-construction-order" +: tail if !reverseConstructionOrder =>
        loop(tail, outputDirectory, reverseConstructionOrder = true)
      case _ => usage()
    }

    loop(args, None, reverseConstructionOrder = false)
  }

  private def usage(): Nothing =
    throw new IllegalArgumentException(
      "Usage: MorphContractFixtureGenerator --output-dir <directory> " +
        "[--reverse-construction-order]"
    )
}
