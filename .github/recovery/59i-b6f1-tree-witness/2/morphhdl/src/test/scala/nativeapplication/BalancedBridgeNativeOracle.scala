package nativeapplication

import spinal.core._
import spinal.lib._

/** Ordinary concrete SpinalHDL. This source is intentionally separate from the
  * typed candidate: it neither captures nor replays a balanced callback graph.
  */
final class BalancedBridgeNativeOracle(width: Int, count: Int, moduleName: String,
    rising: Boolean, asynchronous: Boolean, resetHigh: Boolean,
    enablePolarity: String) extends Component {
  require(width >= 1 && count >= 1)
  require(Set("HIGH", "LOW", "NONE")(enablePolarity))
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val dataIn = in(Bits(width * count bits)).setName("dataIn")
  val signedIn = in(Bits(width * count bits)).setName("signedIn")
  val bitsIn = in(Bits(width * count bits)).setName("bitsIn")
  val boolIn = in(Bits(count bits)).setName("boolIn")
  val identityResult = out(UInt(width bits)).setName("identityResult")
  val aliasResult = out(UInt(width bits)).setName("aliasResult")
  val regResult = out(UInt(width bits)).setName("regResult")
  val zeroResult = out(UInt(width bits)).setName("zeroResult")
  val initResult = out(UInt(width bits)).setName("initResult")
  val levelResult = out(UInt(width bits)).setName("levelResult")
  val localEnableResult = out(UInt(width bits)).setName("localEnableResult")
  val localDisableResult = out(UInt(width bits)).setName("localDisableResult")
  val signedResult = out(SInt(width bits)).setName("signedResult")
  val bitsResult = out(Bits(width bits)).setName("bitsResult")
  val boolResult = out(Bool()).setName("boolResult")
  val words = Vector.tabulate(count)(index => dataIn(index * width, width bits).asUInt)
  val signedWords = Vector.tabulate(count)(index => signedIn(index * width, width bits).asSInt)
  val bitWords = Vector.tabulate(count)(index => bitsIn(index * width, width bits))
  val boolWords = Vector.tabulate(count)(index => boolIn(index))

  val domain = new ClockingArea(ClockDomain(clock = clk, reset = reset,
      clockEnable = if (enablePolarity == "NONE") null else enable,
      config = ClockDomainConfig(clockEdge = if (rising) RISING else FALLING,
        resetKind = if (asynchronous) ASYNC else SYNC,
        resetActiveLevel = if (resetHigh) HIGH else LOW,
        clockEnableActiveLevel = if (enablePolarity == "LOW") LOW else HIGH))) {
    identityResult := words.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, _: Int) => value)
    aliasResult := words.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, _: Int) => {
        val result = UInt(width bits)
        result := value
        result
      })
    regResult := words.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, _: Int) => RegNext(value))
    zeroResult := words.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, _: Int) => RegNext(value) init U(0, width bits))
    initResult := words.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, _: Int) => RegNext(value) init U(1, width bits))
    levelResult := words.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, level: Int) => {
        if (level == 0) value
        else {
          val first = RegNext(value) init U(1, width bits)
          RegNext(first) init U(1, width bits)
        }
      })
    localEnableResult := words.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, _: Int) => RegNextWhen(value, value.msb) init U(1, width bits))
    localDisableResult := words.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, _: Int) => RegNextWhen(value, !value.msb) init U(1, width bits))
    signedResult := signedWords.reduceBalancedTree((a: SInt, b: SInt) => a + b,
      (value: SInt, _: Int) => RegNextWhen(value, !value.msb) init S(-1, width bits))
    bitsResult := bitWords.reduceBalancedTree((a: Bits, b: Bits) => a ^ b,
      (value: Bits, _: Int) => RegNextWhen(value, value(0)) init B(1, width bits))
    boolResult := boolWords.reduceBalancedTree((a: Bool, b: Bool) => a ^ b,
      (value: Bool, _: Int) => RegNextWhen(value, !value) init True)
  }
}
