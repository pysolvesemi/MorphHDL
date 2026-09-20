package morphhdl

import spinal.core._
import morphhdl.frontend.HdlBool

/** Standalone production-path reproduction supplied for lane/when inlining. */
class LaneExpressionExample(ppc: ElabInt) extends Component {
  setDefinitionName("LaneExpressionExample")

  val io = new Bundle {
    val running = in Bool()
    val hActive = in UInt(16 bits)
    val vActive = in UInt(16 bits)
    val baseX = in UInt(13 bits)
    val baseY = in UInt(12 bits)
    val hTotal = in UInt(13 bits)
    val vTotal = in UInt(12 bits)
    val nextX = out UInt(13 bits)
    val nextY = out UInt(12 bits)
    val de = out Bits(ppc bits)
    val frameEnd = out Bits(ppc bits)
  }

  val laneX = Vec(UInt(13 bits), 5)
  val laneY = Vec(UInt(12 bits), 5)
  laneX(0) := io.baseX
  laneY(0) := io.baseY

  val laneDe = Bits(4 bits)
  val laneFrameEnd = Bits(4 bits)

  for (n <- 0 until 4) {
    laneX(n + 1) := laneX(n) + 1
    laneY(n + 1) := laneY(n)
    when(laneX(n) === io.hTotal - 1) {
      laneX(n + 1) := 0
      laneY(n + 1) := laneY(n) + 1
      when(laneY(n) === io.vTotal - 1) {
        laneY(n + 1) := 0
      }
    }

    laneDe(n) := io.running &&
      (laneX(n).resize(16) < io.hActive) &&
      (laneY(n).resize(16) < io.vActive)

    laneFrameEnd(n) := io.running &&
      (laneX(n).resize(16) === io.hActive - 1) &&
      (laneY(n).resize(16) === io.vActive - 1)
  }

  io.nextX := laneX(4)
  io.nextY := laneY(4)
  io.de := laneDe.resize(ppc)
  io.frameEnd := laneFrameEnd.resize(ppc)
}

object GenerateLaneExpressionExample extends App {
  require(args.length == 1,
    "Usage: GenerateLaneExpressionExample <output-directory>")
  val config = SpinalConfig(
    targetDirectory = args(0),
    oneFilePerComponent = false,
    headerWithDate = false,
    headerWithRepoHash = true
  )
  config.netlistFileName = "LaneExpressionExample.v"
  val report = MorphVerilog(config) {
    new LaneExpressionExample(
      HdlBool.param("PPC4", default = false).asElabBool.toElabInt * 3 + 1
    )
  }
  println(report.generatedSourcesPaths.mkString("\n"))
}
