import spinal.core._
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlBool

class RemainingWireRepro(val ppc: ElabInt) extends Component {
  val io = new Bundle {
    val load, ready, legal, rasterRun, cfgValid, running, accept = in Bool()
    val hActive, hFront, hSync, hBack = in UInt(16 bits)
    val vActive, vFront, vSync, vBack = in UInt(16 bits)
    val hTotal = out UInt(13 bits)
    val vTotal = out UInt(12 bits)
    val controlError, invalidEvent, runningOut = out Bool()
    val x = out UInt(13 bits)
    val lanes = out Bits(ppc bits)
  }
  val timing = new Area {
    val hTotal = Reg(UInt(13 bits)) init(0)
    val vTotal = Reg(UInt(12 bits)) init(0)
    val controlError = Reg(Bool()) init(False)
    val invalidEvent = Reg(Bool()) init(False)
    val running = Reg(Bool()) init(False)
    val x = Reg(UInt(13 bits)) init(0)
    val proposedHTotal = io.hActive.resize(18) + io.hFront.resize(18) +
      io.hSync.resize(18) + io.hBack.resize(18)
    val proposedVTotal = io.vActive.resize(18) + io.vFront.resize(18) +
      io.vSync.resize(18) + io.vBack.resize(18)
    controlError := False
    invalidEvent := False
    when(io.load) {
      when(!io.ready) {
        controlError := True
      } elsewhen(!io.legal) {
        invalidEvent := True
      } otherwise {
        hTotal := proposedHTotal.resized
        vTotal := proposedVTotal.resized
      }
    }
    when(!io.rasterRun || !io.cfgValid) {
      running := False
      x := 0
    } otherwise {
      running := True
      when(!io.running || io.accept) {
        x := 0
      } otherwise {
        x := x + 1
      }
    }
  }
  io.hTotal := timing.hTotal
  io.vTotal := timing.vTotal
  io.controlError := timing.controlError
  io.invalidEvent := timing.invalidEvent
  io.runningOut := timing.running
  io.x := timing.x
  io.lanes := 0
}

object GenerateRemainingWireRepro extends App {
  require(args.length == 1, "Supply an output directory")
  MorphVerilog(SpinalConfig(targetDirectory = args(0),
    oneFilePerComponent = true, headerWithDate = false,
    headerWithRepoHash = true)) {
    new RemainingWireRepro(
      HdlBool.param("PPC4", default = false).asElabBool.toElabInt * 3 + 1)
  }
}
