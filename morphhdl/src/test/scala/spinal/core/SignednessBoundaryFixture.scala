package spinal.core

import java.nio.file.{Files, Path, Paths}
import morphhdl.{MorphSignedCasts, MorphVerilog}
import morphhdl.frontend.{HdlBool, HdlInt}
import nativeapplication.SIntSignedDeclarationsArtifactWriter
import spinal.lib._

/** Ordinary native components are elaborated independently at every reference
  * point. Only the candidate retains parameters; no alternate RTL algorithm.
  */
object SignednessBoundaryFixture {
  final class Scalars(width: ElabInt, target: ElabInt) extends Component {
    setDefinitionName("SignedBoundaryScalars")
    val a, b = in(SInt(width bits))
    val raw = in(Bits(width bits))
    val unsigned = in(UInt(width bits))
    val choose = in(Bool())
    val amount = in(UInt(3 bits))
    val mixedBits, mixedUInt, muxArithmetic, signedShift, leftShift = out(SInt(width bits))
    val logicalBits = out(Bits(width bits))
    val logicalUInt = out(UInt(width bits))
    val signedLess, unsignedLess, mixedEqual, reduced, bitSelected = out(Bool())
    val selected = out(SInt(1 bits))
    val concatenated, repeated = out(SInt((width + width) bits))
    val resized, conditionalResize = out(SInt(target bits))
    val grown = out(SInt((width + 3) bits))
    val crossedFixed = out(SInt(5 bits))
    val resizedProduct = out(SInt((target + target) bits))
    val negativeLiteral, zeroLiteral = out(SInt(width bits))
    val sizedLiteral = out(SInt(5 bits))
    val constantFunction = out(SInt((width + 1) bits))

    mixedBits := raw.asSInt + a
    mixedUInt := unsigned.asSInt - b
    muxArithmetic := Mux(choose, a, raw.asSInt) + b
    signedShift := raw.asSInt |>> amount
    leftShift := a |<< amount
    logicalBits := a.asBits |>> amount
    logicalUInt := a.asUInt |>> amount
    signedLess := a < unsigned.asSInt
    unsignedLess := a.asUInt < unsigned
    mixedEqual := a === unsigned.asSInt
    reduced := a.orR && b.xorR
    bitSelected := a(0)
    selected := a(0 downto 0) + b(0 downto 0)
    concatenated := (a.asBits ## raw).asSInt |>> 1
    repeated := (raw.asSInt @* 2) |>> 1
    resized := a.resize(target)
    conditionalResize := a.resize(target)
    when(choose) { conditionalResize := raw.asSInt.resize(target) }
    grown := b.resize(width + 3)
    crossedFixed := a.resize(5)
    resizedProduct := a.resize(target) * b.resize(target)
    negativeLiteral := S(-1)
    zeroLiteral := S(0)
    sizedLiteral := S(-3, 5 bits)
    // A native no-sensitivity function has two sizing boundaries: return and
    // call-result wire. Both must remain symbolic, not just its signed LHS.
    constantFunction.allowOverride
    constantFunction := S(-1)
    constantFunction := S(-2)
  }

  case class Payload(width: ElabInt) extends Bundle {
    val value = SInt(width bits)
    val raw = Bits(width bits)
    val flag = Bool()
  }

  final class Bundles(width: ElabInt) extends Component {
    setDefinitionName("SignedBoundaryBundles")
    val incoming = in(Payload(width))
    val outgoing = out(Payload(width))
    val packed = out(Bits((width + width + 1) bits))
    val shifted = out(SInt(width bits))
    outgoing.raw := incoming.raw
    outgoing.flag := incoming.flag
    outgoing.value := incoming.value + incoming.raw.asSInt
    packed := incoming.asBits
    shifted := outgoing.value |>> 1
  }

  final class Vectors(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("SignedBoundaryVectors")
    val packedIn = in(Vec(SInt(width bits), depth)).setName("packedIn")
    val packedOut = out(Vec(SInt(width bits), depth)).setName("packedOut")
    val bias, replacement = in(SInt(width bits))
    val index = in(UInt(3 bits))
    val write = in(Bool())
    val readValue, first = out(SInt(width bits))
    val lanes = Vec(SInt(width bits), depth).setName("lanes").dontSimplifyIt()
    lanes := packedIn
    val updated = Vec(SInt(width bits), depth).setName("updated").dontSimplifyIt()
    updated := lanes
    updated(index.resized) := Mux(write, replacement, lanes(index.resized))
    packedOut := updated
    readValue := (updated(index.resized) + bias) |>> 1
    first := updated(0) |>> 1
  }

  final class VecChild(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("SignedBoundaryVecChild")
    val incoming = in(Vec(SInt(width bits), depth)).setName("incoming")
    val outgoing = out(Vec(SInt(width bits), depth)).setName("outgoing")
    val index = in(UInt(3 bits))
    val selected = out(SInt(width bits))
    outgoing := incoming
    selected := incoming(index.resized) |>> 1
  }

  final class VecHierarchy(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("SignedBoundaryVecHierarchy")
    val packedIn = in(Vec(SInt(width bits), depth)).setName("packedIn")
    val packedOut = out(Vec(SInt(width bits), depth)).setName("packedOut")
    val index = in(UInt(3 bits))
    val selected = out(SInt(width bits))
    val lanes = Vec(SInt(width bits), depth).setName("parent_lanes").dontSimplifyIt()
    lanes := packedIn
    // The reference uses concrete native construction; only the candidate
    // binds definition-local formal identities. Both execute VecChild's body.
    def child(): VecChild =
      if (depth.parameters.isEmpty) new VecChild(width, depth)
      else ElabFormalComponent.parameter(depth, "DEPTH", 1, 8)(n => new VecChild(width, n))
    val first = child()
    val second = child()
    first.incoming := lanes
    first.index := index
    val middle = Vec(SInt(width bits), depth).setName("middle").dontSimplifyIt()
    middle := first.outgoing
    second.incoming := middle
    second.index := index
    packedOut := second.outgoing
    val firstSelected, secondSelected = SInt(width bits).dontSimplifyIt()
    firstSelected := first.selected
    secondSelected := second.selected
    selected := firstSelected + secondSelected
  }

  final class Child(width: ElabInt) extends Component {
    setDefinitionName("SignedBoundaryChild")
    val a = in(SInt(width bits))
    val raw = in(Bits(width bits))
    val result = out(SInt(width bits))
    val unsigned = out(UInt(width bits))
    result := (a + raw.asSInt) |>> 1
    unsigned := a.asUInt |>> 1
  }

  final class External(width: ElabInt, enabled: ElabBool) extends BlackBox {
    setBlackBoxName("SignedBoundaryExternal")
    addGeneric("LABEL", "boundary")
    addGeneric("WIDTH", width)
    addGeneric("COUNT", 2)
    addGeneric("DOUBLE_WIDTH", width * 2)
    addGeneric("ENABLED", enabled)
    val din = in(SInt(width bits))
    val dout = out(SInt(width bits))
  }

  final class Hierarchy(width: ElabInt, enabled: ElabBool) extends Component {
    setDefinitionName("SignedBoundaryHierarchy")
    val a, b = in(SInt(width bits))
    val raw = in(Bits(width bits))
    val result, externalShift = out(SInt(width bits))
    val unsigned = out(UInt(width bits))
    val first = new Child(width)
    val second = new Child(width)
    first.a := a
    first.raw := raw
    second.a := b
    second.raw := raw
    val firstResult, secondResult = SInt(width bits).dontSimplifyIt()
    val firstUnsigned, secondUnsigned = UInt(width bits).dontSimplifyIt()
    firstResult := first.result
    secondResult := second.result
    firstUnsigned := first.unsigned
    secondUnsigned := second.unsigned
    result := firstResult + secondResult
    unsigned := firstUnsigned + secondUnsigned
    val external = new External(width, enabled)
    val externalIn, externalOut = SInt(width bits).dontSimplifyIt()
    externalIn := a + b
    external.din := externalIn
    externalOut := external.dout
    externalShift := externalOut |>> 1
  }

  final class Channels(width: ElabInt) extends Component {
    setDefinitionName("SignedBoundaryChannels")
    val clk, reset = in(Bool())
    val push = slave(Stream(Payload(width)))
    val pop = master(Stream(Payload(width)))
    val flowIn = slave(Flow(SInt(width bits)))
    val flowOut = master(Flow(SInt(width bits)))
    val area = new ClockingArea(ClockDomain(clock = clk, reset = reset)) {
      val streamStage = push.m2sPipe()
      pop.valid := streamStage.valid
      streamStage.ready := pop.ready
      val signedZero = SInt(width bits).dontSimplifyIt()
      val rawZero = Bits(width bits).dontSimplifyIt()
      signedZero := 0
      rawZero := 0
      pop.payload.value := Mux(streamStage.valid, streamStage.payload.value, signedZero)
      pop.payload.raw := Mux(streamStage.valid, streamStage.payload.raw, rawZero)
      pop.payload.flag := streamStage.valid && streamStage.payload.flag
      val flowStage = flowIn.m2sPipe()
      flowOut.valid := flowStage.valid
      flowOut.payload := Mux(flowStage.valid, flowStage.payload, signedZero)
    }
  }
}

object SignednessBoundaryArtifactWriter {
  def width: HdlInt = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
  def target: HdlInt = HdlInt.param("TARGET", default = 5, min = 1, max = 32)
  def depth: HdlInt = HdlInt.param("DEPTH", default = 3, min = 1, max = 8)
  def config(path: Path): SpinalConfig = SIntSignedDeclarationsArtifactWriter.config(path)
  private def native(path: Path)(component: => Component): Unit = {
    SpinalVerilog(config(path))(component)
    SIntSignedDeclarationsArtifactWriter.canonicalNative(path)
  }
  private def candidate(path: Path)(component: => Component): Unit =
    MorphVerilog(MorphSignedCasts.enable(config(path)))(component)

  def main(args: Array[String]): Unit = {
    require(args.length == 1 || args.length == 2, "output directory and optional fixture kind")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val kinds = if (args.length == 2) Vector(args(1)) else
      Vector("scalars", "bundles", "vectors", "vec-hierarchy", "hierarchy", "channels")
    import SignednessBoundaryFixture._
    for (kind <- kinds) {
      val out = root.resolve(kind)
      Files.createDirectories(out)
      kind match {
        case "scalars" =>
          for (w <- Vector(1, 5, 8, 32); t <- Vector(1, 5, 8, 32))
            native(out.resolve(s"fixed-$w-$t.v"))(new Scalars(HdlInt.literal(w), HdlInt.literal(t)))
          candidate(out.resolve("candidate.v"))(new Scalars(width, target))
        case "bundles" =>
          for (w <- Vector(1, 5, 8, 32)) native(out.resolve(s"fixed-$w.v"))(new Bundles(HdlInt.literal(w)))
          candidate(out.resolve("candidate.v"))(new Bundles(width))
        case "vectors" =>
          for (w <- Vector(1, 5, 8, 32); d <- Vector(1, 3, 5, 8))
            native(out.resolve(s"fixed-$w-$d.v"))(new Vectors(HdlInt.literal(w), HdlInt.literal(d)))
          candidate(out.resolve("candidate.v"))(new Vectors(width, depth))
        case "vec-hierarchy" =>
          for (w <- Vector(1, 5, 8, 32); d <- Vector(1, 3, 5, 8))
            native(out.resolve(s"fixed-$w-$d.v"))(new VecHierarchy(HdlInt.literal(w), HdlInt.literal(d)))
          candidate(out.resolve("candidate.v"))(new VecHierarchy(width, depth))
        case "hierarchy" =>
          for (w <- Vector(1, 5, 8, 32); e <- Vector(false, true))
            native(out.resolve(s"fixed-$w-${if(e) 1 else 0}.v"))(new Hierarchy(HdlInt.literal(w), HdlBool.literal(e)))
          candidate(out.resolve("candidate.v"))(new Hierarchy(width, HdlBool.param("ENABLED", default = true)))
        case "channels" =>
          for (w <- Vector(1, 5, 8, 32)) native(out.resolve(s"fixed-$w.v"))(new Channels(HdlInt.literal(w)))
          candidate(out.resolve("candidate.v"))(new Channels(width))
        case _ => throw new IllegalArgumentException("unknown fixture kind: " + kind)
      }
    }
  }
}
