package nativeapplication

import spinal.core._
import spinal.lib._

/** Ordinary concrete Spinal reference. This file deliberately imports no Morph
  * types, replay certificates, publication helpers or candidate callbacks.
  * The packed input adapter consists only of constant slices and wiring.
  */
final case class NativeLocalEnableRecord(uw: Int, sw: Int, bw: Int) extends Bundle {
  val unsigned = UInt(uw bits)
  val signed = SInt(sw bits)
  val bitsValue = Bits(bw bits)
  val valid = Bool()
}

final class BalancedCompositeLocalEnableNativeOracle(uw: Int, sw: Int, bw: Int,
    count: Int, asynchronous: Boolean, resetLow: Boolean, falling: Boolean,
    moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val values = in(Bits((uw + sw + bw + 1) * count bits)).setName("values")
  val result = out(NativeLocalEnableRecord(uw, sw, bw)).setName("result")
  val words = Vector.tabulate(count) { index =>
    val word = NativeLocalEnableRecord(uw, sw, bw)
    word.assignFromBits(values(index * (uw + sw + bw + 1), (uw + sw + bw + 1) bits))
    word
  }
  val pipeline = new ClockingArea(ClockDomain(clock = clk, reset = reset,
      clockEnable = enable, config = ClockDomainConfig(
        clockEdge = if (falling) FALLING else RISING,
        resetKind = if (asynchronous) ASYNC else SYNC,
        resetActiveLevel = if (resetLow) LOW else HIGH,
        clockEnableActiveLevel = if (falling) LOW else HIGH))) {
    result := new TraversableOnceAnyPimped[NativeLocalEnableRecord](words).reduceBalancedTree(
      (a: NativeLocalEnableRecord, b: NativeLocalEnableRecord) => {
        val r = cloneOf(a)
        r.unsigned := a.unsigned + b.unsigned
        r.signed := a.signed + b.signed
        r.bitsValue := a.bitsValue ^ b.bitsValue
        r.valid := a.valid | b.valid
        r
      },
      (value: NativeLocalEnableRecord, _: Int) => {
        val u = RegNext(value.unsigned) init U(1)
        val s = RegNext(value.signed) init S(-1)
        val b = RegNext(value.bitsValue) init B(2)
        val f = RegNext(value.valid) init True
        val r = cloneOf(value)
        r.unsigned := RegNextWhen(u, u(0) ^ value.unsigned.msb ^ value.valid) init U(3)
        r.signed := RegNextWhen(s, s.msb ^ value.unsigned(0)) init S(-2)
        r.bitsValue := RegNextWhen(b, b.msb ^ value.signed(0)) init B(1)
        r.valid := RegNextWhen(f, f ^ value.valid ^ value.bitsValue(0)) init False
        r
      })
  }
}
