package morphhdl

import java.nio.file.{Files, Paths}
import spinal.core._
import morphhdl.frontend.HdlBool

/** All modes use the real public parameterized entry point, never a fallback. */
object LaneInliningRegressionWriter {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Usage: LaneInliningRegressionWriter OUTPUT_DIRECTORY")
    for (round <- Vector("first", "repeat"); mode <- Vector("default", "enabled", "disabled");
         topology <- Vector("lane", "conditions", "receivers")) {
      val directory = Paths.get(args(0), round, mode, topology)
      Files.createDirectories(directory)
      val config = SpinalConfig(targetDirectory = directory.toString,
        oneFilePerComponent = false, headerWithDate = false, headerWithRepoHash = true)
      config.netlistFileName = "design.v"
      val selected = mode match {
        case "default" => config
        case "enabled" => MorphWireAssignmentPasses(config, enabled = true)
        case "disabled" => MorphWireAssignmentPasses(config, enabled = false)
      }
      MorphVerilog(selected) {
        val ppc = HdlBool.param("PPC4", default = false).asElabBool.toElabInt * 3 + 1
        topology match {
          case "lane" => new LaneExpressionExample(ppc)
          case "conditions" => new LaneConditionCoverageExample(ppc)
          case "receivers" => new LaneReceiverCoverageExample(ppc)
        }
      }
    }
    println("LANE REGRESSIONS: generated all 18 production artifacts")
  }
}
