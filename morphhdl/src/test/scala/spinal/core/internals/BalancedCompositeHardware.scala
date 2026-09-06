package spinal.core.internals

import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.lib._

final case class BalancedCompositeRgb(rw: HdlInt, gw: HdlInt, bw: HdlInt) extends Bundle {
  val red = UInt(rw bits)
  val green = UInt(gw bits)
  val blue = UInt(bw bits)
}

final case class BalancedCompositeRecord(keyWidth: HdlInt, tagWidth: HdlInt, coordWidth: HdlInt) extends Bundle {
  val key = UInt(keyWidth bits)
  val tag = Bits(tagWidth bits)
  val x = UInt(coordWidth bits)
  val y = UInt(coordWidth bits)
}

final case class BalancedCompositeComplex(width: HdlInt) extends Bundle {
  val real = SInt(width bits)
  val imag = SInt(width bits)
}

final case class BalancedCompositeLeaf(uw: HdlInt, sw: HdlInt, bw: HdlInt) extends Bundle {
  val unsigned = UInt(uw bits)
  val signed = SInt(sw bits)
  val bitsValue = Bits(bw bits)
  val valid = Bool()
}

final case class BalancedCompositeNested(uw: HdlInt, sw: HdlInt, bw: HdlInt, tagWidth: HdlInt) extends Bundle {
  val tag = Bits(tagWidth bits)
  val payload = BalancedCompositeLeaf(uw, sw, bw)
  val lanes = Vec(BalancedCompositeLeaf(uw, sw, bw), 2)
  val grid = Vec(Vec(Bool(), 2), 2)
}

/** Public typed Vec.reduceBalancedTree composite coverage; no handwritten reduction tree. */
final class BalancedCompositeHardware(rw: HdlInt, gw: HdlInt, bw: HdlInt, keyWidth: HdlInt, tagWidth: HdlInt, coordWidth: HdlInt, complexWidth: HdlInt, uw: HdlInt, sw: HdlInt, bitsWidth: HdlInt, count: HdlInt, moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val rgbValues = in(Vec(BalancedCompositeRgb(rw, gw, bw), count)).setName("rgbIn")
  val recordValues = in(Vec(BalancedCompositeRecord(keyWidth, tagWidth, coordWidth), count)).setName("recordIn")
  val complexValues = in(Vec(BalancedCompositeComplex(complexWidth), count)).setName("complexIn")
  val nestedValues = in(Vec(BalancedCompositeNested(uw, sw, bitsWidth, tagWidth), count)).setName("nestedIn")
  val rgbMin = out(BalancedCompositeRgb(rw, gw, bw)).setName("rgbMin")
  val rgbMax = out(BalancedCompositeRgb(rw, gw, bw)).setName("rgbMax")
  val selected = out(BalancedCompositeRecord(keyWidth, tagWidth, coordWidth)).setName("selected")
  val complexResult = out(BalancedCompositeComplex(complexWidth)).setName("complexResult")
  val nestedResult = out(BalancedCompositeNested(uw, sw, bitsWidth, tagWidth)).setName("nestedResult")
  val pipelineResult = out(BalancedCompositeRecord(keyWidth, tagWidth, coordWidth)).setName("pipelineResult")
  val rgbMinReduced = rgbValues.reduceBalancedTree((a: BalancedCompositeRgb, b: BalancedCompositeRgb) => {
    val r = cloneOf(a)
    r.red := a.red min b.red
    r.green := a.green min b.green
    r.blue := a.blue min b.blue
    r
  })
  rgbMin := rgbMinReduced
  val rgbMaxReduced = rgbValues.reduceBalancedTree((a: BalancedCompositeRgb, b: BalancedCompositeRgb) => {
    val r = cloneOf(a)
    r.red := a.red max b.red
    r.green := a.green max b.green
    r.blue := a.blue max b.blue
    r
  })
  rgbMax := rgbMaxReduced
  // <= keeps the left complete record on equal keys, including its tag and coordinates.
  val selectedReduced = recordValues.reduceBalancedTree((a: BalancedCompositeRecord, b: BalancedCompositeRecord) =>
    Mux(a.key <= b.key, a, b))
  selected := selectedReduced
  // Modular cross-field complex add/sub; each output depends on the opposite complex field.
  // This non-associative example deliberately preserves the exact native tree order.
  val complexReduced = complexValues.reduceBalancedTree((a: BalancedCompositeComplex, b: BalancedCompositeComplex) => {
    val r = cloneOf(a)
    r.real := a.real + b.imag
    r.imag := a.imag - b.real
    r
  })
  complexResult := complexReduced
  val nestedReduced = nestedValues.reduceBalancedTree((a: BalancedCompositeNested, b: BalancedCompositeNested) => {
    val r = cloneOf(a)
    r.tag := a.tag ^ b.tag
    r.payload.unsigned := a.payload.unsigned + b.payload.unsigned
    r.payload.signed := a.payload.signed max b.payload.signed
    r.payload.bitsValue := a.lanes(1).bitsValue ^ b.payload.bitsValue
    r.payload.valid := a.payload.valid | b.payload.valid
    r.lanes(0).unsigned := a.lanes(0).unsigned + b.lanes(0).unsigned
    r.lanes(0).signed := a.lanes(0).signed min b.lanes(0).signed
    r.lanes(0).bitsValue := a.lanes(0).bitsValue ^ b.lanes(0).bitsValue
    r.lanes(0).valid := a.lanes(0).valid & b.lanes(0).valid
    r.lanes(1).unsigned := a.lanes(1).unsigned + b.lanes(1).unsigned
    r.lanes(1).signed := a.lanes(1).signed max b.lanes(1).signed
    r.lanes(1).bitsValue := a.payload.bitsValue ^ b.lanes(1).bitsValue
    r.lanes(1).valid := a.lanes(1).valid | b.lanes(1).valid
    r.grid(0)(0) := a.grid(0)(0) ^ b.grid(0)(0)
    r.grid(0)(1) := a.grid(0)(1) | b.grid(0)(1)
    r.grid(1)(0) := a.grid(1)(0) & b.grid(1)(0)
    r.grid(1)(1) := a.grid(1)(1) ^ b.grid(1)(1)
    r
  })
  nestedResult := nestedReduced
  val pipeline = new ClockingArea(ClockDomain(clock = clk, reset = reset,
      clockEnable = enable, config = ClockDomainConfig(resetKind = SYNC,
        resetActiveLevel = HIGH, clockEnableActiveLevel = HIGH))) {
    val reduced = recordValues.reduceBalancedTree(
      (a: BalancedCompositeRecord, b: BalancedCompositeRecord) => Mux(a.key <= b.key, a, b),
      (value: BalancedCompositeRecord, _: Int) => {
        val r = cloneOf(value)
        r.setAsReg()
        r := value
        r.key.init(U(0))
        r.tag.init(B(0))
        r.x.init(U(0))
        r.y.init(U(0))
        r
      })
    pipelineResult := reduced
  }
}
