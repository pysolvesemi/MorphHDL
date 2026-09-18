package spinal.core.internals

import morphhdl.frontend.{HdlInt, StructuralGenerateIfOps}
import spinal.core._
import spinal.lib._

private object BalancedNestedMechanismWidths {
  def sum(width: HdlInt, count: HdlInt): ElabInt =
    ElaborationWidthAuthority.add(width.asElabInt,
      ElaborationWidthAuthority.multiply(count.asElabInt, ElabInt.literal(2)))
  def product(width: HdlInt, count: HdlInt): ElabInt =
    ElaborationWidthAuthority.multiply(width.asElabInt, count.asElabInt)
}

/** Recursive transport and independently changing numeric leaves share one native record. */
final case class BalancedNestedMechanismRecord(uw: ElabInt, upw: ElabInt,
    sw: ElabInt, spw: ElabInt, tw: ElabInt, inner: ElabInt) extends Bundle {
  val unsignedSum = UInt(uw bits)
  val unsignedProduct = UInt(upw bits)
  val signedSum = SInt(sw bits)
  val signedProduct = SInt(spw bits)
  val saturated = UInt(tw bits)
  val samples = Vec(Bits(tw bits), inner)
}

object BalancedNestedMechanismOperators {
  def combine(a: BalancedNestedMechanismRecord, b: BalancedNestedMechanismRecord,
      bias: UInt, offset: SInt, inner: ElabInt): BalancedNestedMechanismRecord = {
    val us = (a.unsignedSum +^ bias) +^ b.unsignedSum
    val up = a.unsignedProduct * b.unsignedProduct
    val ss = (a.signedSum +^ offset) +^ b.signedSum
    val sp = a.signedProduct * b.signedProduct
    val r = BalancedNestedMechanismRecord(ElabInt.widthOf(us), ElabInt.widthOf(up),
      ElabInt.widthOf(ss), ElabInt.widthOf(sp), ElabInt.widthOf(a.saturated), inner)
    r.unsignedSum := us
    r.unsignedProduct := up
    r.signedSum := ss
    r.signedProduct := sp
    r.saturated := a.saturated ^ b.saturated
    r.samples := a.samples
    r
  }

  def saturating(a: BalancedNestedMechanismRecord, b: BalancedNestedMechanismRecord,
      bias: UInt, offset: SInt, inner: ElabInt): BalancedNestedMechanismRecord = {
    val us = (a.unsignedSum +^ bias) +^ b.unsignedSum
    val up = a.unsignedProduct * b.unsignedProduct
    val ss = (a.signedSum +^ offset) +^ b.signedSum
    val sp = a.signedProduct * b.signedProduct
    val r = BalancedNestedMechanismRecord(ElabInt.widthOf(us), ElabInt.widthOf(up),
      ElabInt.widthOf(ss), ElabInt.widthOf(sp), ElabInt.widthOf(a.saturated), inner)
    r.unsignedSum := us
    r.unsignedProduct := up
    r.signedSum := ss
    r.signedProduct := sp
    r.saturated := a.saturated +| b.saturated
    r.samples := a.samples
    r
  }

}

/** Six-way join: named fields, recursive Vec, widening, captures, native bridge, typed owner.
  * Output resizes are ordinary port normalization after the native reduction;
  * no arithmetic or reduction tree is implemented by an interface adapter.
  */
final class BalancedNestedMechanismChild(uw: HdlInt, sw: HdlInt, tw: HdlInt,
    inner: HdlInt, count: HdlInt, mode: HdlInt, saturation: Boolean,
    moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val values = in(Vec(BalancedNestedMechanismRecord(uw.asElabInt, uw.asElabInt,
    sw.asElabInt, sw.asElabInt, tw.asElabInt, inner.asElabInt), count)).setName("values")
  val biasA = in(UInt(uw bits)).setName("biasA")
  val biasB = in(UInt(uw bits)).setName("biasB")
  val offsetA = in(SInt(sw bits)).setName("offsetA")
  val offsetB = in(SInt(sw bits)).setName("offsetB")
  val resultUS = out(UInt(BalancedNestedMechanismWidths.sum(uw, count) bits)).setName("resultUS")
  val resultUP = out(UInt(BalancedNestedMechanismWidths.product(uw, count) bits)).setName("resultUP")
  val resultSS = out(SInt(BalancedNestedMechanismWidths.sum(sw, count) bits)).setName("resultSS")
  val resultSP = out(SInt(BalancedNestedMechanismWidths.product(sw, count) bits)).setName("resultSP")
  val resultSat = out(UInt(tw bits)).setName("resultSat")
  val resultSamples = out(Vec(Bits(tw bits), inner)).setName("resultSamples")

  private def connect(value: BalancedNestedMechanismRecord): Unit = {
    resultUS := value.unsignedSum.resize(BalancedNestedMechanismWidths.sum(uw, count)).dontSimplifyIt()
    resultUP := value.unsignedProduct.resize(BalancedNestedMechanismWidths.product(uw, count)).dontSimplifyIt()
    resultSS := value.signedSum.resize(BalancedNestedMechanismWidths.sum(sw, count)).dontSimplifyIt()
    resultSP := value.signedProduct.resize(BalancedNestedMechanismWidths.product(sw, count)).dontSimplifyIt()
    resultSat := value.saturated
    resultSamples := value.samples
  }

  val pipeline = new ClockingArea(ClockDomain(clock = clk, reset = reset,
    clockEnable = enable, config = ClockDomainConfig(resetKind = SYNC,
      resetActiveLevel = HIGH, clockEnableActiveLevel = HIGH))) {
    (mode > HdlInt.literal(0)).generateIf("g_second_capture", "g_first_capture") {
      val bias = biasB; val offset = offsetB; val size = inner.asElabInt
      if (saturation) connect(values.reduceBalancedTree(
        (a: BalancedNestedMechanismRecord, b: BalancedNestedMechanismRecord) =>
          BalancedNestedMechanismOperators.saturating(a, b, bias, offset, size),
        (v: BalancedNestedMechanismRecord, _: Int) => {
          val r = cloneOf(v)
          r.setAsReg()
          r := v
          r.init(r.getZero)
          r
        }))
      else connect(values.reduceBalancedTree(
        (a: BalancedNestedMechanismRecord, b: BalancedNestedMechanismRecord) =>
          BalancedNestedMechanismOperators.combine(a, b, bias, offset, size),
        (v: BalancedNestedMechanismRecord, _: Int) => {
          val r = cloneOf(v)
          r.setAsReg()
          r := v
          r.init(r.getZero)
          r
        }))
    }.otherwise {
      val bias = biasA; val offset = offsetA; val size = inner.asElabInt
      if (saturation) connect(values.reduceBalancedTree(
        (a: BalancedNestedMechanismRecord, b: BalancedNestedMechanismRecord) =>
          BalancedNestedMechanismOperators.saturating(a, b, bias, offset, size),
        (v: BalancedNestedMechanismRecord, _: Int) => {
          val r = cloneOf(v)
          r.setAsReg()
          r := v
          r.init(r.getZero)
          r
        }))
      else connect(values.reduceBalancedTree(
        (a: BalancedNestedMechanismRecord, b: BalancedNestedMechanismRecord) =>
          BalancedNestedMechanismOperators.combine(a, b, bias, offset, size),
        (v: BalancedNestedMechanismRecord, _: Int) => {
          val r = cloneOf(v)
          r.setAsReg()
          r := v
          r.init(r.getZero)
          r
        }))
    }
  }
}

/** Public typed constructor bindings; the parent contains wires only. */
final class BalancedNestedMechanismTop(uw: HdlInt, sw: HdlInt, tw: HdlInt,
    inner: HdlInt, count: HdlInt, mode: HdlInt, saturation: Boolean,
    moduleName: String, childName: String) extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val values = in(Vec(BalancedNestedMechanismRecord(uw.asElabInt, uw.asElabInt,
    sw.asElabInt, sw.asElabInt, tw.asElabInt, inner.asElabInt), count)).setName("values")
  val biasA = in(UInt(uw bits)).setName("biasA")
  val biasB = in(UInt(uw bits)).setName("biasB")
  val offsetA = in(SInt(sw bits)).setName("offsetA")
  val offsetB = in(SInt(sw bits)).setName("offsetB")
  val resultUS = out(UInt(BalancedNestedMechanismWidths.sum(uw, count) bits)).setName("resultUS")
  val resultUP = out(UInt(BalancedNestedMechanismWidths.product(uw, count) bits)).setName("resultUP")
  val resultSS = out(SInt(BalancedNestedMechanismWidths.sum(sw, count) bits)).setName("resultSS")
  val resultSP = out(SInt(BalancedNestedMechanismWidths.product(sw, count) bits)).setName("resultSP")
  val resultSat = out(UInt(tw bits)).setName("resultSat")
  val resultSamples = out(Vec(Bits(tw bits), inner)).setName("resultSamples")
  val child = {
    Vector(uw, sw, tw, inner, count, mode).forall(_.asElabInt.isConcrete) match {
    case true => new BalancedNestedMechanismChild(uw, sw, tw, inner, count, mode, saturation, childName)
    case false => ElabFormalComponent.parameters(Vector(
      ElabFormalComponent.Parameter(uw.asElabInt, "U_W", 1, 4),
      ElabFormalComponent.Parameter(sw.asElabInt, "S_W", 1, 4),
      ElabFormalComponent.Parameter(tw.asElabInt, "TAG_W", 1, 4),
      ElabFormalComponent.Parameter(inner.asElabInt, "INNER", 1, 3),
      ElabFormalComponent.Parameter(count.asElabInt, "COUNT", 1, 5),
      ElabFormalComponent.Parameter(mode.asElabInt + 1, "MODE", 1, 2))) { parameters =>
      new BalancedNestedMechanismChild(HdlInt.fromElabIntParameter(parameters(0)),
        HdlInt.fromElabIntParameter(parameters(1)), HdlInt.fromElabIntParameter(parameters(2)),
        HdlInt.fromElabIntParameter(parameters(3)), HdlInt.fromElabIntParameter(parameters(4)),
        HdlInt.fromElabIntParameter(parameters(5)) - HdlInt.literal(1), saturation, childName)
    }
    }
  }.setName("child")
  child.clk := clk; child.reset := reset; child.enable := enable
  child.values := values
  child.biasA := biasA; child.biasB := biasB
  child.offsetA := offsetA; child.offsetB := offsetB
  resultUS := child.resultUS; resultUP := child.resultUP
  resultSS := child.resultSS; resultSP := child.resultSP
  resultSat := child.resultSat; resultSamples := child.resultSamples
}
