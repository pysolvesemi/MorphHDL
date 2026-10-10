package nativeapplication

import spinal.core._
import spinal.lib._

/** Plain-Int native reference. It does not instantiate/import a Morph candidate
  * shape, callback, typed parameter, layout adapter or publication backend.
  */
final case class NativeNestedMechanismWord(uw: Int, upw: Int, sw: Int, spw: Int,
    tw: Int, inner: Int) extends Bundle {
  val us = UInt(uw bits)
  val up = UInt(upw bits)
  val ss = SInt(sw bits)
  val sp = SInt(spw bits)
  val sat = UInt(tw bits)
  val samples = Vec(Bits(tw bits), inner)
}

final class BalancedNestedMechanismNativeOracle(uw: Int, sw: Int, tw: Int,
    inner: Int, count: Int, mode: Int, saturation: Boolean, moduleName: String)
    extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  private val word = 2 * uw + 2 * sw + tw * (inner + 1)
  val values = in(Bits((word * count) bits)).setName("values")
  val biasA = in(UInt(uw bits)).setName("biasA")
  val biasB = in(UInt(uw bits)).setName("biasB")
  val offsetA = in(SInt(sw bits)).setName("offsetA")
  val offsetB = in(SInt(sw bits)).setName("offsetB")
  val resultUS = out(UInt((uw + count * 2) bits)).setName("resultUS")
  val resultUP = out(UInt((uw * count) bits)).setName("resultUP")
  val resultSS = out(SInt((sw + count * 2) bits)).setName("resultSS")
  val resultSP = out(SInt((sw * count) bits)).setName("resultSP")
  val resultSat = out(UInt(tw bits)).setName("resultSat")
  val resultSamples = out(Bits((tw * inner) bits)).setName("resultSamples")

  val lanes = Vector.tabulate(count) { index =>
    val lane = NativeNestedMechanismWord(uw, uw, sw, sw, tw, inner)
    lane.assignFromBits(values(index * word, word bits))
    lane
  }
  val pipeline = new ClockingArea(ClockDomain(clock = clk, reset = reset,
    clockEnable = enable, config = ClockDomainConfig(resetKind = SYNC,
      resetActiveLevel = HIGH, clockEnableActiveLevel = HIGH))) {
    // Configuration selection occurs in ordinary Scala independently of the
    // candidate's generate region and six explicit generated-child formals.
    val bias = if (mode == 0) biasA else biasB
    val offset = if (mode == 0) offsetA else offsetB
    val reduced = lanes.reduceBalancedTree((a: NativeNestedMechanismWord,
        b: NativeNestedMechanismWord) => {
      val us = (a.us +^ bias) +^ b.us
      val up = a.up * b.up
      val ss = (a.ss +^ offset) +^ b.ss
      val sp = a.sp * b.sp
      val result = NativeNestedMechanismWord(us.getBitsWidth, up.getBitsWidth,
        ss.getBitsWidth, sp.getBitsWidth, tw, inner)
      result.us := us; result.up := up; result.ss := ss; result.sp := sp
      if (saturation) result.sat := a.sat +| b.sat
      else result.sat := a.sat ^ b.sat
      // Ordinary fixed native Vec storage, independent of parameterized Vec
      // indexing/projection. Preserve each concrete lane, including INNER=3.
      result.samples.vec.zip(a.samples.vec).foreach { case (to, from) => to := from }
      result
    }, (value: NativeNestedMechanismWord, _: Int) => {
      val stage = Reg(cloneOf(value))
      stage := value
      stage.init(stage.getZero)
      stage
    })
    resultUS := reduced.us.resized; resultUP := reduced.up.resized
    resultSS := reduced.ss.resized; resultSP := reduced.sp.resized
    resultSat := reduced.sat
    resultSamples := reduced.samples.asBits
  }
}
