package nativeapplication

import spinal.core._
import spinal.lib._

final case class NativeBalancedCompositeRgb(rw: Int, gw: Int, bw: Int) extends Bundle {
  val red = UInt(rw bits)
  val green = UInt(gw bits)
  val blue = UInt(bw bits)
}

final case class NativeBalancedCompositeRecord(keyWidth: Int, tagWidth: Int, coordWidth: Int) extends Bundle {
  val key = UInt(keyWidth bits)
  val tag = Bits(tagWidth bits)
  val x = UInt(coordWidth bits)
  val y = UInt(coordWidth bits)
}

final case class NativeBalancedCompositeComplex(width: Int) extends Bundle {
  val real = SInt(width bits)
  val imag = SInt(width bits)
}

final case class NativeBalancedCompositeLeaf(uw: Int, sw: Int, bw: Int) extends Bundle {
  val unsigned = UInt(uw bits)
  val signed = SInt(sw bits)
  val bitsValue = Bits(bw bits)
  val valid = Bool()
}

final case class NativeBalancedCompositeNested(uw: Int, sw: Int, bw: Int, tagWidth: Int) extends Bundle {
  val tag = Bits(tagWidth bits)
  val payload = NativeBalancedCompositeLeaf(uw, sw, bw)
  val lanes = Vec(NativeBalancedCompositeLeaf(uw, sw, bw), 2)
  val grid = Vec(Vec(Bool(), 2), 2)
}

/** Independent ordinary SpinalHDL native helper oracle. */
final class BalancedCompositeNativeOracle(rw: Int, gw: Int, bw: Int, keyWidth: Int, tagWidth: Int, coordWidth: Int, complexWidth: Int, uw: Int, sw: Int, bitsWidth: Int, count: Int, moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val rgbIn = in(Bits(((rw + gw + bw) * count) bits)).setName("rgbIn")
  val rgbValues = Vector.tabulate(count) { index =>
    val value = NativeBalancedCompositeRgb(rw, gw, bw)
    value.assignFromBits(rgbIn(index * (rw + gw + bw), (rw + gw + bw) bits))
    value
  }
  val recordIn = in(Bits(((keyWidth + tagWidth + coordWidth * 2) * count) bits)).setName("recordIn")
  val recordValues = Vector.tabulate(count) { index =>
    val value = NativeBalancedCompositeRecord(keyWidth, tagWidth, coordWidth)
    value.assignFromBits(recordIn(index * (keyWidth + tagWidth + coordWidth * 2), (keyWidth + tagWidth + coordWidth * 2) bits))
    value
  }
  val complexIn = in(Bits(((complexWidth * 2) * count) bits)).setName("complexIn")
  val complexValues = Vector.tabulate(count) { index =>
    val value = NativeBalancedCompositeComplex(complexWidth)
    value.assignFromBits(complexIn(index * (complexWidth * 2), (complexWidth * 2) bits))
    value
  }
  val nestedIn = in(Bits(((tagWidth + (uw + sw + bitsWidth + 1) * 3 + 4) * count) bits)).setName("nestedIn")
  val nestedValues = Vector.tabulate(count) { index =>
    val value = NativeBalancedCompositeNested(uw, sw, bitsWidth, tagWidth)
    value.assignFromBits(nestedIn(index * (tagWidth + (uw + sw + bitsWidth + 1) * 3 + 4), (tagWidth + (uw + sw + bitsWidth + 1) * 3 + 4) bits))
    value
  }
  val rgbMin = out(NativeBalancedCompositeRgb(rw, gw, bw)).setName("rgbMin")
  val rgbMax = out(NativeBalancedCompositeRgb(rw, gw, bw)).setName("rgbMax")
  val selected = out(NativeBalancedCompositeRecord(keyWidth, tagWidth, coordWidth)).setName("selected")
  val complexResult = out(NativeBalancedCompositeComplex(complexWidth)).setName("complexResult")
  val nestedResult = NativeBalancedCompositeNested(uw, sw, bitsWidth, tagWidth).setName("nativeNestedReductionValue")
  // Wiring-only adapter for the candidate's existing packed nested Vec boundary.
  val nestedTagOut = out(Bits(tagWidth bits)).setName("nestedResult_tag")
  val nestedUnsignedOut = out(UInt(uw bits)).setName("nestedResult_payload_unsigned")
  val nestedSignedOut = out(SInt(sw bits)).setName("nestedResult_payload_signed")
  val nestedBitsOut = out(Bits(bitsWidth bits)).setName("nestedResult_payload_bitsValue")
  val nestedValidOut = out(Bool()).setName("nestedResult_payload_valid")
  val nestedLanesOut = out(Bits((uw + sw + bitsWidth + 1) * 2 bits)).setName("nestedResult_lanes")
  val nestedGridOut = out(Vec(Vec(Bool(), 2), 2)).setName("nestedResult_grid")
  nestedTagOut := nestedResult.tag
  nestedUnsignedOut := nestedResult.payload.unsigned
  nestedSignedOut := nestedResult.payload.signed
  nestedBitsOut := nestedResult.payload.bitsValue
  nestedValidOut := nestedResult.payload.valid
  nestedLanesOut := nestedResult.lanes.asBits
  nestedGridOut := nestedResult.grid
  val pipelineResult = out(NativeBalancedCompositeRecord(keyWidth, tagWidth, coordWidth)).setName("pipelineResult")
  val rgbMinReduced = rgbValues.reduceBalancedTree((a: NativeBalancedCompositeRgb, b: NativeBalancedCompositeRgb) => {
    val r = cloneOf(a)
    r.red := a.red min b.red
    r.green := a.green min b.green
    r.blue := a.blue min b.blue
    r
  })
  rgbMin := rgbMinReduced
  val rgbMaxReduced = rgbValues.reduceBalancedTree((a: NativeBalancedCompositeRgb, b: NativeBalancedCompositeRgb) => {
    val r = cloneOf(a)
    r.red := a.red max b.red
    r.green := a.green max b.green
    r.blue := a.blue max b.blue
    r
  })
  rgbMax := rgbMaxReduced
  // <= keeps the left complete record on equal keys, including its tag and coordinates.
  val selectedReduced = recordValues.reduceBalancedTree((a: NativeBalancedCompositeRecord, b: NativeBalancedCompositeRecord) =>
    Mux(a.key <= b.key, a, b))
  selected := selectedReduced
  // Modular cross-field complex add/sub; each output depends on the opposite complex field.
  // This non-associative example deliberately preserves the exact native tree order.
  val complexReduced = complexValues.reduceBalancedTree((a: NativeBalancedCompositeComplex, b: NativeBalancedCompositeComplex) => {
    val r = cloneOf(a)
    r.real := a.real + b.imag
    r.imag := a.imag - b.real
    r
  })
  complexResult := complexReduced
  val nestedReduced = nestedValues.reduceBalancedTree((a: NativeBalancedCompositeNested, b: NativeBalancedCompositeNested) => {
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
      (a: NativeBalancedCompositeRecord, b: NativeBalancedCompositeRecord) => Mux(a.key <= b.key, a, b),
      (value: NativeBalancedCompositeRecord, _: Int) => {
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
