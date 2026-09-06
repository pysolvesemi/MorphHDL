package nativeapplication

import spinal.core._
import spinal.lib._

final case class NativeBalancedCompositeCountedRecord(uw: Int, sw: Int, bw: Int,
    tw: Int, inner: Int, rows: Int, columns: Int) extends Bundle {
  val key = UInt(uw bits)
  val tag = Bits(tw bits)
  val samples = Vec(NativeBalancedCompositeLeaf(uw, sw, bw), inner)
  val grid = Vec(Vec(Bool(), columns), rows)
}

/** Concrete native helper and wiring-only flattening at the legacy packed Vec boundary. */
final class BalancedCompositeCountedNativeOracle(uw: Int, sw: Int, bw: Int,
    tw: Int, inner: Int, rows: Int, columns: Int, count: Int, moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val wordWidth = uw + tw + (uw + sw + bw + 1) * inner + rows * columns
  val countedIn = in(Bits(wordWidth * count bits)).setName("countedIn")
  val words = Vector.tabulate(count) { index =>
    val value = NativeBalancedCompositeCountedRecord(uw, sw, bw, tw, inner, rows, columns)
    value.assignFromBits(countedIn(index * wordWidth, wordWidth bits))
    value
  }
  val result = words.reduceBalancedTree((a: NativeBalancedCompositeCountedRecord, b: NativeBalancedCompositeCountedRecord) =>
    Mux(a.key <= b.key, a, b)).setName("nativeCountedReductionValue")
  val key = out(UInt(uw bits)).setName("countedResult_key")
  val tag = out(Bits(tw bits)).setName("countedResult_tag")
  val samples = out(Bits((uw + sw + bw + 1) * inner bits)).setName("countedResult_samples")
  val grid = out(Bits(rows * columns bits)).setName("countedResult_grid")
  key := result.key
  tag := result.tag
  samples := result.samples.asBits
  grid := result.grid.asBits
}
