package morphhdl.examples

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt
import spinal.core._

/** Real public-API artifacts; historical reference generation stays independent. */
object WireAssignmentProductionArtifactWriter {
  private def config(output: Path): SpinalConfig = {
    Files.createDirectories(output)
    val result = SpinalConfig(targetDirectory = output.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING, resetKind = SYNC, resetActiveLevel = HIGH))
    result.netlistFileName = "generated.v"
    result
  }

  private def fifo(output: Path, enabled: Option[Boolean]): Unit = {
    val original = config(output)
    val selected = enabled.map(value => MorphWireAssignmentPasses(original, value))
      .getOrElse(original)
    val width = HdlInt.param("WIDTH", default = BigInt(8), min = BigInt(1), max = BigInt(64))
    val depth = HdlInt.param("DEPTH", default = BigInt(5), min = BigInt(1), max = BigInt(8))
    MorphVerilog(selected) { new ParameterizedStreamFifo(width, depth) }
  }

  private def generic(output: Path, mode: String): Unit = {
    val original = config(output)
    val selected = mode match {
      case "enabled" => MorphWireAssignmentPasses(original, enabled = true)
      case "reference" =>
        ConstantOperandWitnessPhasePlan.install(original, None)
        original
      case "disabled" => MorphWireAssignmentPasses(original)
      case "plain" => original
    }
    SpinalVerilog(selected) {
      new Component {
        setDefinitionName("ProductionExpressions")
        val a, b = in Bool()
        val y0, y1, y2, y3, y4, y5, y6, y7 = out Bool()
        y0 := (a === b) & True
        y1 := (a === b) & False
        y2 := Mux(a === b, True, False)
        y3 := Mux(a =/= b, False, True)
        y4 := Mux(Mux(a === b, True, False), False, True)
        y5 := Mux(a, a, b)
        y6 := a & True
        y7 := a ^ True
      }
    }
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: OUTPUT_DIRECTORY")
    val output = Paths.get(args(0)).toAbsolutePath.normalize
    val original = config(output.resolve("configuration"))
    require(MorphWireAssignmentPasses(original) eq original)
    require(MorphWireAssignmentPasses(original, enabled = false) eq original)
    val count = original.phasesInserters.size
    val enabled = MorphWireAssignmentPasses(original, enabled = true)
    require(original.phasesInserters.size == count)
    require(enabled.phasesInserters.size == count + 1)
    require(!(enabled.phasesInserters eq original.phasesInserters))
    for (round <- Vector("first", "repeat")) {
      val directory = output.resolve(round)
      fifo(directory.resolve("fifo-enabled"), Some(true))
      fifo(directory.resolve("fifo-disabled"), Some(false))
      fifo(directory.resolve("fifo-plain"), None)
      val expected = directory.resolve("fifo-historical-all")
      ParameterizedStreamFifoBooleanTernaryWitness.main(Array(
        "all", expected.toString, "generated.v", expected.resolve("report.json").toString))
      for (mode <- Vector("reference", "enabled", "disabled", "plain"))
        generic(directory.resolve("generic-" + mode), mode)
    }
    Files.write(output.resolve("configuration.txt"),
      "default-off identity and enabled configuration isolation PASS\n".getBytes(StandardCharsets.UTF_8))
  }
}
