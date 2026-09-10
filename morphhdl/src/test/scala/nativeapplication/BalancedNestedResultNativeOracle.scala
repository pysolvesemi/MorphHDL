package nativeapplication

import spinal.core._
import spinal.lib._

/** Separately elaborated concrete native types and callbacks; only ports are packed. */
final class BalancedNestedResultNativeOracle(uw: Int, sw: Int, tw: Int, inner: Int,
    count: Int, mode: Int, moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val wordWidth = uw + tw + inner * (2 * uw + sw + 1) + 1
  val records = in(Bits(wordWidth * count bits)).setName("records")
  val words = Vector.tabulate(count) { index =>
    val value = NativeBalancedCompositeCountedRecord(uw, sw, uw, tw, inner, 1, 1)
    value.assignFromBits(records(index * wordWidth, wordWidth bits))
    value
  }
  val selected = out(Bits(wordWidth bits)).setName("selected")
  if (mode == 0) {
    val result = words.reduceBalancedTree((a: NativeBalancedCompositeCountedRecord,
      b: NativeBalancedCompositeCountedRecord) => Mux(a.key <= b.key, a, b))
    selected := result.asBits
  } else {
    val result = words.reduceBalancedTree((a: NativeBalancedCompositeCountedRecord,
      b: NativeBalancedCompositeCountedRecord) => Mux(a.key >= b.key, a, b))
    selected := result.asBits
  }
}
