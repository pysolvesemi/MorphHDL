package morphhdl.examples

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt
import spinal.core._

/** Public API only: this exact fixture can also be compiled on the integration
  * baseline. Width overrides are applied to these artifacts by HDL tools;
  * they are never re-elaborated separately for each OUT_WIDTH specialization.
  */
private[examples] final class LowBitTruncationFixture(outWidth: HdlInt) extends Component {
  setDefinitionName("LowBitTruncationFixture")
  val a, b, c = in UInt(16 bits)
  val load, stall, clear = in Bool()
  val comb = out UInt(outWidth bits)
  val wrappedComb = out UInt(outWidth bits)
  val bitwiseComb = out Bits(outWidth bits)
  val blocking = out UInt(outWidth bits)
  val sampled = out(Reg(UInt(outWidth bits)) init(0))

  def sum: UInt = (a.resize(18) + b.resize(18)) + c.resize(18)
  comb := sum.resize(outWidth.asElabInt)
  // This separate operation must overflow at 16 bits BEFORE widening. It
  // catches a purported cleanup that silently changes intermediate sizing.
  wrappedComb := ((a + b).resize(18) + c.resize(18)).resize(outWidth.asElabInt)
  bitwiseComb := (a.asBits.resize(18) ^ b.asBits.resize(18) ^
    c.asBits.resize(18)).resize(outWidth.asElabInt)
  blocking := 0
  when(load) { blocking := sum.resize(outWidth.asElabInt) }
  when(!stall) {
    when(load) { sampled := sum.resize(outWidth.asElabInt) }
    // Later assignment has priority over load, but stall still holds state.
    when(clear) { sampled := 0 }
  }
}

object LowBitTruncationArtifactWriter {
  private[examples] def generate(output: Path, mode: String): Unit = {
    Files.createDirectories(output)
    // Keep baseline-supported defaults for repository-hash headers. The fixture
    // changes only deterministic date emission; WIRE-TRUNC-01 must not depend
    // on a parameterized-emitter configuration feature introduced later.
    val base = SpinalConfig(targetDirectory = output.toString,
      oneFilePerComponent = true, headerWithDate = false,
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH))
    val config = mode match {
      case "default" | "repeat" => base
      case "enabled" => MorphWireAssignmentPasses(base, enabled = true)
      case "disabled" => MorphWireAssignmentPasses(base, enabled = false)
      case other => throw new IllegalArgumentException("unknown mode: " + other)
    }
    val width = HdlInt.param("OUT_WIDTH", default = 13, min = 1, max = 18)
    MorphVerilog(config) { new LowBitTruncationFixture(width) }
    require(Files.isRegularFile(output.resolve("LowBitTruncationFixture.v")),
      "the public writer did not produce the expected Verilog module")
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: OUTPUT_DIRECTORY")
    val output = Paths.get(args(0)).toAbsolutePath.normalize
    Vector("default", "enabled", "disabled", "repeat").foreach { mode =>
      generate(output.resolve(mode), mode)
    }
    Files.write(output.resolve("fixture.json"),
      ("{\"fixture\":\"LowBitTruncationFixture\",\"default_width\":13," +
        "\"domain\":[1,18],\"override_widths\":[1,12,13,17,18]," +
        "\"modes\":[\"default\",\"enabled\",\"disabled\",\"repeat\"]," +
        "\"per_override_reelaboration\":false}\n").getBytes(StandardCharsets.UTF_8))
  }
}
