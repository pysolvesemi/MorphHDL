package spinal.core.internals

import morphhdl.frontend.{HdlInt, StructuralGenerateIfOps}
import spinal.core._
import spinal.lib._

/** Ordinary recursive Data source; neither layout nor callback needs an adapter. */
final class BalancedCombinedNestedResult(uw: HdlInt, sw: HdlInt, tw: HdlInt,
    inner: HdlInt, count: HdlInt, mode: HdlInt, moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val records = in(Vec(BalancedCompositeCountedRecord(uw, sw, uw, tw, inner,
    HdlInt.literal(1), HdlInt.literal(1)), count)).setName("records")
  val selected = out(BalancedCompositeCountedRecord(uw, sw, uw, tw, inner,
    HdlInt.literal(1), HdlInt.literal(1))).setName("selected")
  (mode > HdlInt.literal(0)).generateIf("g_max", "g_min") {
    selected := records.reduceBalancedTree((a: BalancedCompositeCountedRecord,
      b: BalancedCompositeCountedRecord) => Mux(a.key >= b.key, a, b))
  }.otherwise {
    selected := records.reduceBalancedTree((a: BalancedCompositeCountedRecord,
      b: BalancedCompositeCountedRecord) => Mux(a.key <= b.key, a, b))
  }
}

/** Deliberately invalid producer/consumer ownership; none may reach RTL. */
private[internals] final class BalancedNestedResultEscape(count: HdlInt, inner: HdlInt,
    mode: HdlInt, sibling: Boolean) extends Component {
  val records = in(Vec(BalancedCompositeCountedRecord(HdlInt.literal(3), HdlInt.literal(5),
    HdlInt.literal(3), HdlInt.literal(7), inner, HdlInt.literal(1), HdlInt.literal(1)), count))
  val selected = out(cloneOf(records.vec.head))
  var escaped: BalancedCompositeCountedRecord = null
  (mode > HdlInt.literal(0)).generateIf("g_producer", "g_sibling") {
    escaped = records.reduceBalancedTree((a: BalancedCompositeCountedRecord,
      b: BalancedCompositeCountedRecord) => Mux(a.key >= b.key, a, b))
    if (sibling) selected := escaped
    else {
      val local = UInt(3 bits).dontSimplifyIt()
      local := escaped.key
    }
  }.otherwise {
    if (sibling) selected := escaped
    else {
      val local = UInt(3 bits).dontSimplifyIt()
      local := records(0).key
    }
  }
  if (!sibling) selected := escaped
}
