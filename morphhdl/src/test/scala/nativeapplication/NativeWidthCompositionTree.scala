package nativeapplication

import spinal.core._
import morphhdl.MorphVerilog

/** CA-051/052/053: run alias, soft-reset, or parent with the same compiler. */
class NativeWidthCompositionTree(mode: String, countActual: ElabInt, widthActual: ElabInt = ElabInt.literal(36)) extends Component {
  setDefinitionName("NativeWidthCompositionTree")
  @dontName val count = ElabFormalParameter(countActual, "COUNT", 1, 16)
  @dontName val width = ElabFormalParameter(widthActual, "DATA_WIDTH", 1, 64)
  @dontName val pipeline = if(mode == "masked-soft-reset")
    Some(ElabFormalParameter(ElabInt.literal(1), "PIPELINE_MASK", 0, 1)) else None
  class Ports(values: Vector[ElabInt]) extends Bundle {
    val clk, reset, softReset, enable = in Bool()
    val terms = in Bits((values(0) * values(1)) bits)
    val result = out UInt(values(1) bits)
  }
  @dontName val recursive = ElabRecursiveComponent(Vector(count, width) ++ pipeline.toVector)(new Ports(_))
  val io = recursive.ports
  val logic = new ClockingArea(ClockDomain(io.clk, io.reset,
      softReset = io.softReset, clockEnable = io.enable,
      config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = LOW))) {
    val lanes = Vec(UInt(width bits), count)
    lanes.assignFromBits(io.terms)
    ElabControl.selectSymbolic(count <= 1, "WidthComposition", 1) {
      io.result := lanes(0)
    } {
      val nextCount = (count + 1) / 2
      val combined = Vec(UInt(width bits), nextCount)
      val packedWords = if(mode == "fused") Some(Bits((nextCount * width) bits)) else None
      ElabFiniteRange.foreach(nextCount, "combine") { i =>
        i.whenAffineLessThan(2, 1, count, "pair") {
          i(combined) := i.affine(lanes, 2, 0) + i.affine(lanes, 2, 1)
        } { i(combined) := i.affine(lanes, 2, 0) }
        packedWords.foreach(words => i.packed(words, width, width bits) := i(combined).asBits)
      }
      val nextData = if (mode.contains("soft-reset")) {
        val values = Vec(UInt(width bits), nextCount)
        ElabFiniteRange.foreach(nextCount, "pipeline") { i =>
          val value = i(combined)
          def capture(): Unit = {
            val registered = RegNext(value) init(value.getZero)
            i(values) := registered
          }
          pipeline match {
            case Some(mask) => ElabControl.selectSymbolic(mask.elabEq(1), "Pipeline", 2) {
              capture()
            } { i(values) := value }
            case None => capture()
          }
        }
        values
      } else combined
      val child = recursive(count -> nextCount)
      child.ports.clk := io.clk
      child.ports.reset := io.reset
      child.ports.softReset := io.softReset
      child.ports.enable := io.enable
      if (mode == "fused") child.ports.terms := packedWords.get
      else if (mode == "alias") {
        val nextTerms = Bits((nextCount * width) bits)
        nextTerms := nextData.asBits
        child.ports.terms := nextTerms
      } else child.ports.terms := nextData.asBits
      io.result := child.ports.result
    }
  }
}

