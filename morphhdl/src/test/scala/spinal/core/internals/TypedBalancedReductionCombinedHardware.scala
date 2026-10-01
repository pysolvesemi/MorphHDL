package spinal.core.internals

import morphhdl.frontend.{HdlInt, StructuralGenerateCaseOps, StructuralGenerateIfOps}
import spinal.core._
import spinal.lib._

/** 59i join fixture: no custom reduction tree or interface adapter arithmetic. */
final class BalancedCombinedScopedRecords(width: HdlInt, tagWidth: HdlInt,
    coordWidth: HdlInt, count: HdlInt, mode: HdlInt, moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val records = in(Vec(BalancedCompositeRecord(width, tagWidth, coordWidth), count)).setName("records")
  val selected = out(BalancedCompositeRecord(width, tagWidth, coordWidth)).setName("selected")
  val delayed = out(BalancedCompositeRecord(width, tagWidth, coordWidth)).setName("delayed")
  // A signed composite exercises declaration-only and redundant-cast modes,
  // not just unsigned record transport with signed configuration flags.
  val signedRecords = in(Vec(BalancedCompositeComplex(width), count)).setName("signedRecords")
  val signedSelected = out(BalancedCompositeComplex(width)).setName("signedSelected")
  mode.generateCase
    .choice(BigInt(0), "g_minimum") {
      (count > HdlInt.literal(1)).generateIf("g_tree", "g_singleton") {
        selected := records.reduceBalancedTree((a: BalancedCompositeRecord, b: BalancedCompositeRecord) =>
          Mux(a.key <= b.key, a, b))
        signedSelected := signedRecords.reduceBalancedTree((a: BalancedCompositeComplex, b: BalancedCompositeComplex) =>
          Mux(a.real <= b.real, a, b))
      }.otherwise {
        selected := records(0)
        signedSelected := signedRecords(0)
      }
    }
    .default("g_maximum") {
      selected := records.reduceBalancedTree((a: BalancedCompositeRecord, b: BalancedCompositeRecord) =>
        Mux(a.key >= b.key, a, b))
      signedSelected := signedRecords.reduceBalancedTree((a: BalancedCompositeComplex, b: BalancedCompositeComplex) =>
        Mux(a.real >= b.real, a, b))
    }
  val pipeline = new ClockingArea(ClockDomain(clock = clk, reset = reset,
      clockEnable = enable, config = ClockDomainConfig(resetKind = SYNC,
        resetActiveLevel = HIGH, clockEnableActiveLevel = HIGH))) {
    (mode > HdlInt.literal(0)).generateIf("g_registered_max", "g_registered_min") {
      delayed := records.reduceBalancedTree(
        (a: BalancedCompositeRecord, b: BalancedCompositeRecord) => Mux(a.key >= b.key, a, b),
        (value: BalancedCompositeRecord, _: Int) => {
          val r = cloneOf(value)
          r.setAsReg()
          r := value
          r.key.init(U(0)); r.tag.init(B(0)); r.x.init(U(0)); r.y.init(U(0))
          r
        })
    }.otherwise {
      delayed := records.reduceBalancedTree(
        (a: BalancedCompositeRecord, b: BalancedCompositeRecord) => Mux(a.key <= b.key, a, b),
        (value: BalancedCompositeRecord, _: Int) => {
          val r = cloneOf(value)
          r.setAsReg()
          r := value
          r.key.init(U(0)); r.tag.init(B(0)); r.x.init(U(0)); r.y.init(U(0))
          r
        })
    }
  }
}

/** Directly test composite scope safety, rather than assuming scalar tests apply. */
private[internals] final class BalancedCombinedEscapingRecord(count: HdlInt, mode: HdlInt,
    sibling: Boolean) extends Component {
  val records = in(Vec(BalancedCompositeRecord(HdlInt.literal(5), HdlInt.literal(3), HdlInt.literal(7)), count))
  val result = out(BalancedCompositeRecord(HdlInt.literal(5), HdlInt.literal(3), HdlInt.literal(7)))
  var escaped: BalancedCompositeRecord = null
  (mode > HdlInt.literal(0)).generateIf("g_producer", "g_sibling") {
    escaped = records.reduceBalancedTree((a: BalancedCompositeRecord, b: BalancedCompositeRecord) =>
      Mux(a.key <= b.key, a, b))
    if (sibling) result := escaped
    else {
      val consumed = Bits(22 bits).setName("local_consumed").dontSimplifyIt()
      consumed := escaped.asBits
    }
  }.otherwise {
    if (sibling) result := escaped
    else {
      val marker = Bits(22 bits).setName("sibling_marker").dontSimplifyIt()
      marker := records(0).asBits
    }
  }
  if (!sibling) result := escaped
}

private[internals] final class BalancedCombinedConflictingRecord(count: HdlInt, mode: HdlInt)
    extends Component {
  val records = in(Vec(BalancedCompositeRecord(HdlInt.literal(5), HdlInt.literal(3), HdlInt.literal(7)), count))
  val result = out(BalancedCompositeRecord(HdlInt.literal(5), HdlInt.literal(3), HdlInt.literal(7)))
  result := records(0)
  (mode > HdlInt.literal(0)).generateIf("g_enabled", "g_disabled") {
    result := records.reduceBalancedTree((a: BalancedCompositeRecord, b: BalancedCompositeRecord) =>
      Mux(a.key <= b.key, a, b))
  }.otherwise { result := records(0) }
}

/** Label allocation must consider sibling structural regions, not just the body string. */
private[internals] final class BalancedCombinedLabelCollision(count: HdlInt, mode: HdlInt)
    extends Component {
  setDefinitionName("BalancedCombinedLabelCollision")
  val records = in(Vec(BalancedCompositeRecord(HdlInt.literal(5), HdlInt.literal(3), HdlInt.literal(7)), count))
  val result = out(BalancedCompositeRecord(HdlInt.literal(5), HdlInt.literal(3), HdlInt.literal(7)))
  (mode > HdlInt.literal(0)).generateIf("g_outer", "g_other") {
    result := records.reduceBalancedTree((a: BalancedCompositeRecord, b: BalancedCompositeRecord) =>
      Mux(a.key <= b.key, a, b))
    (count > HdlInt.literal(1)).generateIf("morphhdl_balanced_1_active_0", "g_user_single") {
      val marker = UInt(5 bits).setName("user_many").dontSimplifyIt()
      marker := records(0).key
    }.otherwise {
      val marker = UInt(5 bits).setName("user_single").dontSimplifyIt()
      marker := records(0).key
    }
  }.otherwise { result := records(0) }
}
