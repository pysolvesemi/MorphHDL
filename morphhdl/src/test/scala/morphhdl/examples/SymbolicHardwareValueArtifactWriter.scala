package morphhdl.examples

import spinal.core._
import morphhdl.{MorphVerilog}
import morphhdl.frontend.HdlInt

class SymbolicValueProbe(busBytes: ElabInt) extends Component {
  val io = new Bundle {
    val value = out UInt(8 bits)
    val plusThree = out UInt(9 bits)
  }
  io.value := U(busBytes, 8 bits)
  io.plusThree := U(busBytes + 3, 9 bits)
}
object SymbolicHardwareValueArtifactWriter {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    MorphVerilog(SpinalConfig(targetDirectory = args(0),
      oneFilePerComponent = true, headerWithDate = false)) {
      new SymbolicValueProbe(HdlInt.param("BUS_LOG2_BYTES", 3, 2, 5).asElabInt.pow2)
    }
  }
}
