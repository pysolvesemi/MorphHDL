package morphhdl

import spinal.core._
import spinal.lib.fsm._
import morphhdl.frontend.HdlInt

/** Standalone ordinary StateMachine reproduction for enum-condition cleanup. */
class EnumConditionRepro(width: ElabInt) extends Component {
  val io = new Bundle {
    val enable = in Bool()
    val data = in Bits(width bits)
    val active = out Bool()
    val result = out Bits(width bits)
  }
  val fsm = new StateMachine {
    val IDLE = makeInstantEntry()
    val ACTIVE = new State
  }
  val held = Reg(Bits(width bits)) init(0)
  fsm.always {
    when(io.enable) {
      when(fsm.isActive(fsm.IDLE)) { fsm.goto(fsm.ACTIVE) }
      when(fsm.isActive(fsm.ACTIVE)) {
        held := io.data
        fsm.goto(fsm.IDLE)
      }
    }
  }
  io.active := fsm.isActive(fsm.ACTIVE)
  io.result := held
}

object GenerateEnumConditionRepro extends App {
  require(args.length == 2, "Expected output-directory and passes-enabled")
  val config = MorphWireAssignmentPasses(
    SpinalConfig(targetDirectory = args(0), oneFilePerComponent = true,
      headerWithDate = false, headerWithRepoHash = true),
    enabled = args(1).toBoolean)
  MorphVerilog(config) {
    new EnumConditionRepro(HdlInt.param("WIDTH", 8, 1, 32).asElabInt)
  }
}
