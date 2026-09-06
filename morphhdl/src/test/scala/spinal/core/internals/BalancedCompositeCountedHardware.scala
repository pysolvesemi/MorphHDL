package spinal.core.internals

import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.lib._

final case class BalancedCompositeCountedRecord(uw: HdlInt, sw: HdlInt, bw: HdlInt,
    tw: HdlInt, inner: HdlInt, rows: HdlInt, columns: HdlInt) extends Bundle {
  val key = UInt(uw bits)
  val tag = Bits(tw bits)
  val samples = Vec(BalancedCompositeLeaf(uw, sw, bw), inner)
  val grid = Vec(Vec(Bool(), columns), rows)
}

/** Independent nested count roots share the ordinary outer Vec helper. */
final class BalancedCompositeCountedHardware(uw: HdlInt, sw: HdlInt, bw: HdlInt,
    tw: HdlInt, inner: HdlInt, rows: HdlInt, columns: HdlInt, count: HdlInt) extends Component {
  setDefinitionName("BalancedCompositeCountedPublication")
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val records = in(Vec(BalancedCompositeCountedRecord(uw, sw, bw, tw, inner, rows, columns), count)).setName("countedIn")
  val result = out(BalancedCompositeCountedRecord(uw, sw, bw, tw, inner, rows, columns)).setName("countedResult")
  result := records.reduceBalancedTree((a: BalancedCompositeCountedRecord, b: BalancedCompositeCountedRecord) =>
    Mux(a.key <= b.key, a, b))
}
