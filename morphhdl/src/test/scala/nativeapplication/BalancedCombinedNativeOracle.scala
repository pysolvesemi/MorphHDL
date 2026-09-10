package nativeapplication

import spinal.core._
import spinal.lib._

/** Separately constructed concrete native reference. It neither imports the
  * candidate's record types/callbacks nor invokes MorphVerilog publication.
  */
final case class NativeCombinedRecord(width: Int, tagWidth: Int, coordWidth: Int) extends Bundle {
  val key = UInt(width bits)
  val tag = Bits(tagWidth bits)
  val x = UInt(coordWidth bits)
  val y = UInt(coordWidth bits)
}
final case class NativeCombinedSigned(width: Int) extends Bundle {
  val real = SInt(width bits)
  val imag = SInt(width bits)
}
final class BalancedCombinedNativeOracle(width: Int, tagWidth: Int, coordWidth: Int,
    count: Int, mode: Int, moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  // Native reference input is always the legacy lane-major packed layout.
  val records = in(Bits((width + tagWidth + 2 * coordWidth) * count bits)).setName("records")
  val signedRecords = in(Bits(2 * width * count bits)).setName("signedRecords")
  val values = Vector.tabulate(count) { index =>
    val value = NativeCombinedRecord(width, tagWidth, coordWidth)
    value.assignFromBits(records(index * (width + tagWidth + 2 * coordWidth),
      (width + tagWidth + 2 * coordWidth) bits))
    value
  }
  val signedValues = Vector.tabulate(count) { index =>
    val value = NativeCombinedSigned(width)
    value.assignFromBits(signedRecords(index * 2 * width, 2 * width bits))
    value
  }
  val selected = out(NativeCombinedRecord(width, tagWidth, coordWidth)).setName("selected")
  val delayed = out(NativeCombinedRecord(width, tagWidth, coordWidth)).setName("delayed")
  val signedSelected = out(NativeCombinedSigned(width)).setName("signedSelected")
  if (mode == 0) {
    if (count > 1) {
      selected := values.reduceBalancedTree((a, b) => Mux(a.key <= b.key, a, b))
      signedSelected := signedValues.reduceBalancedTree((a, b) => Mux(a.real <= b.real, a, b))
    } else {
      selected := values.head
      signedSelected := signedValues.head
    }
  } else {
    selected := values.reduceBalancedTree((a, b) => Mux(a.key >= b.key, a, b))
    signedSelected := signedValues.reduceBalancedTree((a, b) => Mux(a.real >= b.real, a, b))
  }
  val pipeline = new ClockingArea(ClockDomain(clock = clk, reset = reset,
      clockEnable = enable, config = ClockDomainConfig(resetKind = SYNC,
        resetActiveLevel = HIGH, clockEnableActiveLevel = HIGH))) {
    if (mode == 0) {
      delayed := values.reduceBalancedTree((a, b) => Mux(a.key <= b.key, a, b),
        (value: NativeCombinedRecord, _: Int) => {
          val r = cloneOf(value)
          r.setAsReg()
          r := value
          r.key.init(U(0)); r.tag.init(B(0)); r.x.init(U(0)); r.y.init(U(0))
          r
        })
    } else {
      delayed := values.reduceBalancedTree((a, b) => Mux(a.key >= b.key, a, b),
        (value: NativeCombinedRecord, _: Int) => {
          val r = cloneOf(value)
          r.setAsReg()
          r := value
          r.key.init(U(0)); r.tag.init(B(0)); r.x.init(U(0)); r.y.init(U(0))
          r
        })
    }
  }
}
