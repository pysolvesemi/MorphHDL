import java.nio.file.{Files, Paths}

import spinal.core._
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlBool

/** Reduced WA-10 diagnostic, preserving the production arithmetic contexts. */
class TimingExpressionExample(ppc: ElabInt) extends Component {
  setDefinitionName("TimingExpressionExample")

  val io = new Bundle {
    val cfgLoad = in Bool()
    val hActive = in UInt(16 bits)
    val hFront = in UInt(16 bits)
    val hSync = in UInt(16 bits)
    val hBack = in UInt(16 bits)
    val vActive = in UInt(16 bits)
    val vFront = in UInt(16 bits)
    val vSync = in UInt(16 bits)
    val hTotal = in UInt(13 bits)
    val vTotal = in UInt(12 bits)
    val laneX = in UInt(13 bits)
    val laneY = in UInt(12 bits)
    val legal = out Bool()
    val finalGroup = out Bool()
    val proposedHTotal = out UInt(18 bits)
    val vSyncEnd = out UInt(12 bits)
    val laneMaskIn = in Bits(ppc bits)
    val laneMaskOut = out Bits(ppc bits)
  }

  val proposedHTotal = io.hActive.resize(18) + io.hFront.resize(18) +
    io.hSync.resize(18) + io.hBack.resize(18)
  io.legal := (io.hActive >= 1) && (io.hActive <= 1920) &&
    (io.vActive >= 1) && (io.vActive <= 1080) &&
    (proposedHTotal <= 4096)
  io.proposedHTotal := proposedHTotal
  io.finalGroup := (io.laneX === io.hTotal - 1) &&
    (io.laneY === io.vTotal - 1)

  val vSyncEnd = Reg(UInt(12 bits)) init(0)
  when(io.cfgLoad) {
    vSyncEnd := (io.vActive.resize(18) + io.vFront.resize(18) +
      io.vSync.resize(18)).resize(12)
  }
  io.vSyncEnd := vSyncEnd
  io.laneMaskOut := io.laneMaskIn
}

object GenerateTimingExpressionExample {
  def generate(output: String, mode: String = "default"): Unit = {
    Files.createDirectories(Paths.get(output))
    val config = SpinalConfig(
      targetDirectory = output,
      oneFilePerComponent = false,
      headerWithDate = false,
      headerWithRepoHash = true
    )
    config.netlistFileName = "TimingExpressionExample.v"
    val selected = mode match {
      case "default" => config
      case "enabled" => MorphWireAssignmentPasses(config, enabled = true)
      case "disabled" => MorphWireAssignmentPasses(config, enabled = false)
      case other => throw new IllegalArgumentException("Unknown mode: " + other)
    }
    val report = MorphVerilog(selected) {
      new TimingExpressionExample(
        HdlBool.param("PPC4", default = false).asElabBool.toElabInt * 3 + 1
      )
    }
    println(report.generatedSourcesPaths.mkString("\n"))
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Usage: GenerateTimingExpressionExample <output-directory>")
    generate(args(0))
  }
}

/** Same source and native parameter identity, with the existing product flag. */
object TimingExpressionArtifactWriter {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Usage: TimingExpressionArtifactWriter <output-directory>")
    for (round <- Vector("first", "repeat"); mode <- Vector("default", "enabled", "disabled"))
      GenerateTimingExpressionExample.generate(
        Paths.get(args(0), round, mode).toString, mode
      )
  }
}
