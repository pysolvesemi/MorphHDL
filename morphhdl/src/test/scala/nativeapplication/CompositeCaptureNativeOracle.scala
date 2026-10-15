package nativeapplication

import spinal.core._
import spinal.lib._

/** Independent native types and callback bodies; no candidate helper imports. */
final case class NativeCaptureRecord(w: Int, t: Int, c: Int) extends Bundle {
  val key = UInt(w bits)
  val tag = Bits(t bits)
  val x = UInt(c bits)
  val y = UInt(c bits)
}
final case class NativeCaptureComplex(w: Int) extends Bundle {
  val real = SInt(w bits)
  val imag = SInt(w bits)
}
final class CompositeCaptureNativeOracle(w: Int, t: Int, c: Int, n: Int, mode: Int,
    moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val records = in(Bits(n * (w + t + 2 * c) bits)).setName("records")
  val signedRecords = in(Bits(n * 2 * w bits)).setName("signedRecords")
  val biasA = in(UInt(c bits)).setName("biasA")
  val biasB = in(UInt(c bits)).setName("biasB")
  val mask = in(Bits(t bits)).setName("mask")
  val choose = in(Bool()).setName("choose")
  val offset = in(SInt(w bits)).setName("offset")
  val result = out(NativeCaptureRecord(w, t, c)).setName("result")
  val signedResult = out(NativeCaptureComplex(w)).setName("signedResult")
  val values = Vector.tabulate(n) { index =>
    val v = NativeCaptureRecord(w, t, c)
    v.assignFromBits(records(index * (w + t + 2 * c), (w + t + 2 * c) bits))
    v
  }
  val signedValues = Vector.tabulate(n) { index =>
    val v = NativeCaptureComplex(w)
    v.assignFromBits(signedRecords(index * 2 * w, 2 * w bits))
    v
  }
  result := values.reduceBalancedTree { (left, right) =>
    val a = if (mode == 1) left else right
    val b = if (mode == 1) right else left
    val r = NativeCaptureRecord(w, t, c)
    val chooseMin = if (mode == 1) choose else !choose
    r.key := Mux(chooseMin, a.key min b.key, a.key max b.key)
    r.tag := a.tag ^ b.tag ^ mask
    r.x := (a.x + b.y) ^ biasA
    r.y := (a.y - b.x) ^ biasB
    r
  }
  signedResult := signedValues.reduceBalancedTree { (left, right) =>
    val a = if (mode == 1) left else right
    val b = if (mode == 1) right else left
    val r = NativeCaptureComplex(w)
    r.real := a.real + b.imag + offset
    r.imag := a.imag - b.real
    r
  }
}
