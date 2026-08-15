package morphhdl

import java.nio.file.{Files, Path, Paths}

import spinal.core.{ClockDomainConfig, Component, HIGH, RISING, SYNC, SpinalConfig}

/** CLI dispatch for the twenty public-entry-point contract fixtures. */
object MorphContractFixtureGenerator {
  private final case class Options(outputDirectory: Path, reverseConstructionOrder: Boolean)

  private final case class Fixture(
      filename: String,
      generate: (SpinalConfig, Boolean) => Unit
  )

  private def dualSource(
      filename: String,
      program: Boolean => MorphProgram[Component]
  ): Fixture =
    Fixture(
      filename,
      (config, reverseConstructionOrder) => {
        MorphVerilog(config)(program(reverseConstructionOrder))
        ()
      }
    )

  private def singleSource(
      filename: String,
      component: Boolean => Component
  ): Fixture =
    Fixture(
      filename,
      (config, reverseConstructionOrder) => {
        MorphVerilog(config)(component(reverseConstructionOrder))
        ()
      }
    )

  private val fixtures = Vector(
    singleSource("parameterized_wire.v", ParameterizedWireContractFixture.component),
    dualSource("derived_width.v", DerivedWidthContractFixture.program),
    dualSource("parameter_forwarding.v", ParameterForwardingContractFixture.program),
    dualSource("lane_array.v", LaneArrayContractFixture.program),
    dualSource("conditional_forwarding.v", ConditionalForwardingContractFixture.program),
    dualSource("comparison_routing.v", ComparisonRoutingContractFixture.program),
    dualSource("conditional_width.v", ConditionalWidthContractFixture.program),
    dualSource("boolean_forwarding.v", BooleanForwardingContractFixture.program),
    dualSource("boolean_locals.v", BooleanLocalsContractFixture.program),
    dualSource("case_routing.v", CaseRoutingContractFixture.program),
    dualSource("runtime_mux.v", RuntimeMuxContractFixture.program),
    dualSource("synchronous_register.v", SynchronousRegisterContractFixture.program),
    dualSource("asynchronous_register.v", AsynchronousRegisterContractFixture.program),
    dualSource("synchronous_enabled_register.v", SynchronousEnabledRegisterContractFixture.program),
    dualSource(
      "asynchronous_enabled_register.v",
      AsynchronousEnabledRegisterContractFixture.program
    ),
    dualSource("single_port_memory.v", SinglePortMemoryContractFixture.program),
    dualSource("parameterized_counter.v", ParameterizedCounterContractFixture.program),
    dualSource("simple_dual_port_memory.v", SimpleDualPortMemoryContractFixture.program),
    dualSource("synchronous_stream_fifo.v", SynchronousStreamFifoContractFixture.program),
    dualSource(
      "synchronous_stream_m2s_pipe.v",
      SynchronousStreamM2sPipeContractFixture.program
    )
  )

  def main(args: Array[String]): Unit = {
    val options = parseOptions(args.toVector)
    Files.createDirectories(options.outputDirectory)

    fixtures.foreach { fixture =>
      val config =
        if (
          fixture.filename == "synchronous_stream_fifo.v" ||
          fixture.filename == "synchronous_stream_m2s_pipe.v"
        )
          SpinalConfig(
            targetDirectory = options.outputDirectory.toString,
            defaultConfigForClockDomains = ClockDomainConfig(
              clockEdge = RISING,
              resetKind = SYNC,
              resetActiveLevel = HIGH
            )
          )
        else SpinalConfig(targetDirectory = options.outputDirectory.toString)
      config.netlistFileName = fixture.filename
      fixture.generate(config, options.reverseConstructionOrder)
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
