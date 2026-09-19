package spinal.core.internals

import morphhdl.frontend.{HdlInt, StructuralGenerateIfOps}
import spinal.core._
import spinal.lib._

private object LocalEnableCombinedWidths {
  def sum(width: HdlInt, count: HdlInt): ElabInt =
    ElaborationWidthAuthority.add(width.asElabInt,
      ElaborationWidthAuthority.multiply(count.asElabInt, ElabInt.literal(2)))
  def product(width: HdlInt, count: HdlInt): ElabInt =
    ElaborationWidthAuthority.multiply(width.asElabInt, count.asElabInt)
}

/** Recursive transport and independently changing numeric leaves share one native record. */
final case class LocalEnableCombinedRecord(uw: ElabInt, upw: ElabInt,
    sw: ElabInt, spw: ElabInt, tw: ElabInt, inner: ElabInt) extends Bundle {
  val unsignedSum = UInt(uw bits)
  val unsignedProduct = UInt(upw bits)
  val signedSum = SInt(sw bits)
  val signedProduct = SInt(spw bits)
  val saturated = UInt(tw bits)
  val samples = Vec(Bits(tw bits), inner)
}

object LocalEnableCombinedOperators {
  def saturating(a: LocalEnableCombinedRecord, b: LocalEnableCombinedRecord,
      bias: UInt, offset: SInt, inner: ElabInt): LocalEnableCombinedRecord = {
    val us = (a.unsignedSum +^ bias) +^ b.unsignedSum
    val up = a.unsignedProduct * b.unsignedProduct
    val ss = (a.signedSum +^ offset) +^ b.signedSum
    val sp = a.signedProduct * b.signedProduct
    val r = LocalEnableCombinedRecord(ElabInt.widthOf(us), ElabInt.widthOf(up),
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

/** Combined local-enable join: nested fields, widening, captures, saturation and generated hierarchy.
  * Output resizes are ordinary port normalization after the native reduction;
  * no arithmetic or reduction tree is implemented by an interface adapter.
  */
final class LocalEnableCombinedChild(uw: HdlInt, sw: HdlInt, tw: HdlInt,
    inner: HdlInt, count: HdlInt, mode: HdlInt,
    moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val values = in(Vec(LocalEnableCombinedRecord(uw.asElabInt, uw.asElabInt,
    sw.asElabInt, sw.asElabInt, tw.asElabInt, inner.asElabInt), count)).setName("values")
  val biasA = in(UInt(uw bits)).setName("biasA")
  val biasB = in(UInt(uw bits)).setName("biasB")
  val offsetA = in(SInt(sw bits)).setName("offsetA")
  val offsetB = in(SInt(sw bits)).setName("offsetB")
  val resultUS = out(UInt(LocalEnableCombinedWidths.sum(uw, count) bits)).setName("resultUS")
  val resultUP = out(UInt(LocalEnableCombinedWidths.product(uw, count) bits)).setName("resultUP")
  val resultSS = out(SInt(LocalEnableCombinedWidths.sum(sw, count) bits)).setName("resultSS")
  val resultSP = out(SInt(LocalEnableCombinedWidths.product(sw, count) bits)).setName("resultSP")
  val resultSat = out(UInt(tw bits)).setName("resultSat")
  val resultSamples = out(Vec(Bits(tw bits), inner)).setName("resultSamples")

  private def connect(value: LocalEnableCombinedRecord): Unit = {
    resultUS := value.unsignedSum.resize(LocalEnableCombinedWidths.sum(uw, count)).dontSimplifyIt()
    resultUP := value.unsignedProduct.resize(LocalEnableCombinedWidths.product(uw, count)).dontSimplifyIt()
    resultSS := value.signedSum.resize(LocalEnableCombinedWidths.sum(sw, count)).dontSimplifyIt()
    resultSP := value.signedProduct.resize(LocalEnableCombinedWidths.product(sw, count)).dontSimplifyIt()
    resultSat := value.saturated
    resultSamples := value.samples
  }

  val pipeline = new ClockingArea(ClockDomain(clock = clk, reset = reset,
    clockEnable = enable, config = ClockDomainConfig(resetKind = SYNC,
      resetActiveLevel = HIGH, clockEnableActiveLevel = HIGH))) {
    val bridge = (v: LocalEnableCombinedRecord, _: Int) => {
      val r = cloneOf(v)
      r.unsignedSum := RegNextWhen(v.unsignedSum, v.saturated(0) ^ v.signedSum.msb) init U(1)
      r.unsignedProduct := RegNextWhen(v.unsignedProduct, v.saturated.msb ^ v.unsignedSum(0)) init U(1)
      r.signedSum := RegNextWhen(v.signedSum, v.saturated(0) ^ v.unsignedProduct.msb) init S(-1)
      r.signedProduct := RegNextWhen(v.signedProduct, v.saturated.msb ^ v.signedSum(0)) init S(-1)
      r.saturated := RegNextWhen(v.saturated, v.unsignedSum(0) ^ v.signedProduct.msb) init U(0)
      // One root-local control dominates every nested Vec lane. The shared
      // assignment is still checked independently for every typed leaf.
      val samples = RegNextWhen(v.samples, v.saturated(0))
      samples.init(samples.getZero)
      r.samples := samples
      r
    }
    (mode > HdlInt.literal(0)).generateIf("g_second_capture", "g_first_capture") {
      val bias = biasB; val offset = offsetB; val size = inner.asElabInt
      connect(values.reduceBalancedTree(
        (a: LocalEnableCombinedRecord, b: LocalEnableCombinedRecord) =>
          LocalEnableCombinedOperators.saturating(a, b, bias, offset, size), bridge))
    }.otherwise {
      val bias = biasA; val offset = offsetA; val size = inner.asElabInt
      connect(values.reduceBalancedTree(
        (a: LocalEnableCombinedRecord, b: LocalEnableCombinedRecord) =>
          LocalEnableCombinedOperators.saturating(a, b, bias, offset, size), bridge))
    }
  }
}

/** Public typed constructor bindings; the parent contains wires only. */
final class LocalEnableCombinedTop(uw: HdlInt, sw: HdlInt, tw: HdlInt,
    inner: HdlInt, count: HdlInt, mode: HdlInt,
    moduleName: String, childName: String) extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val values = in(Vec(LocalEnableCombinedRecord(uw.asElabInt, uw.asElabInt,
    sw.asElabInt, sw.asElabInt, tw.asElabInt, inner.asElabInt), count)).setName("values")
  val biasA = in(UInt(uw bits)).setName("biasA")
  val biasB = in(UInt(uw bits)).setName("biasB")
  val offsetA = in(SInt(sw bits)).setName("offsetA")
  val offsetB = in(SInt(sw bits)).setName("offsetB")
  val resultUS = out(UInt(LocalEnableCombinedWidths.sum(uw, count) bits)).setName("resultUS")
  val resultUP = out(UInt(LocalEnableCombinedWidths.product(uw, count) bits)).setName("resultUP")
  val resultSS = out(SInt(LocalEnableCombinedWidths.sum(sw, count) bits)).setName("resultSS")
  val resultSP = out(SInt(LocalEnableCombinedWidths.product(sw, count) bits)).setName("resultSP")
  val resultSat = out(UInt(tw bits)).setName("resultSat")
  val resultSamples = out(Vec(Bits(tw bits), inner)).setName("resultSamples")
  val child = {
    Vector(uw, sw, tw, inner, count, mode).forall(_.asElabInt.isConcrete) match {
    case true => new LocalEnableCombinedChild(uw, sw, tw, inner, count, mode, childName)
    case false => ElabFormalComponent.parameters(Vector(
      ElabFormalComponent.Parameter(uw.asElabInt, "U_W", 1, 4),
      ElabFormalComponent.Parameter(sw.asElabInt, "S_W", 1, 4),
      ElabFormalComponent.Parameter(tw.asElabInt, "TAG_W", 1, 4),
      ElabFormalComponent.Parameter(inner.asElabInt, "INNER", 1, 3),
      ElabFormalComponent.Parameter(count.asElabInt, "COUNT", 1, 5),
      ElabFormalComponent.Parameter(mode.asElabInt + 1, "MODE", 1, 2))) { parameters =>
      new LocalEnableCombinedChild(HdlInt.fromElabIntParameter(parameters(0)),
        HdlInt.fromElabIntParameter(parameters(1)), HdlInt.fromElabIntParameter(parameters(2)),
        HdlInt.fromElabIntParameter(parameters(3)), HdlInt.fromElabIntParameter(parameters(4)),
        HdlInt.fromElabIntParameter(parameters(5)) - HdlInt.literal(1), childName)
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


object TypedBalancedReductionLocalEnableCombinedArtifactWriter {
  import java.nio.charset.StandardCharsets
  import java.nio.file.{Files, Path, Paths}
  import scala.collection.JavaConverters._
  import morphhdl.{MorphNamedFieldVectors, MorphSignedCasts, MorphSignedDeclarations, MorphVerilog}
  import nativeapplication.BalancedLocalEnableCombinedNativeOracle

  private def quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  private def relative(root: Path, file: Path): String = root.relativize(file).toString.replace('\\', '/')
  private def config(directory: Path, name: String, split: Boolean): SpinalConfig = {
    Files.createDirectories(directory)
    val value = SpinalConfig(targetDirectory = directory.toString, bitVectorWidthMax = 4096,
      oneFilePerComponent = split, headerWithDate = false, headerWithRepoHash = true)
    if (!split) value.netlistFileName = name + ".v"
    value
  }
  private def rtlFiles(directory: Path): Vector[Path] = {
    val stream = Files.walk(directory)
    try stream.iterator().asScala.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".v"))
      .toVector.sortBy(_.toString)
    finally stream.close()
  }
  private def emit(root: Path, layout: String, signed: String, split: Boolean): String = {
    val name = "LocalEnableCombined_" + layout + "_" + signed + "_" + (if (split) "split" else "single")
    val child = name + "_child"
    val directory = root.resolve("candidate/" + name)
    val base = config(directory, name, split)
    val shaped = if (layout == "fields") MorphNamedFieldVectors.enable(base) else base
    val configured = signed match {
      case "legacy" => MorphSignedDeclarations.disable(MorphSignedCasts.disable(shaped))
      case "declarations" => MorphSignedDeclarations.enable(MorphSignedCasts.disable(shaped))
      case "casts" => MorphSignedCasts.enable(shaped)
    }
    MorphVerilog(configured) {
      new LocalEnableCombinedTop(HdlInt.param("U_W", 2, 1, 4), HdlInt.param("S_W", 3, 1, 4),
        HdlInt.param("TAG_W", 2, 1, 4), HdlInt.param("INNER", 1, 1, 3),
        HdlInt.param("COUNT", 1, 1, 5), HdlInt.param("MODE", 0, 0, 1),
        name, child)
    }
    val files = rtlFiles(directory)
    require(files.nonEmpty && Files.isRegularFile(directory.resolve(name + ".v")), "missing combined candidate RTL")
    s"""{"layout":${quote(layout)},"signed_mode":${quote(signed)},"split":$split,"module":${quote(name)},"child_module":${quote(child)},"files":[${files.map(f => quote(relative(root, f))).mkString(",") }]}"""
  }
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: TypedBalancedReductionLocalEnableCombinedArtifactWriter OUTPUT")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val candidates = Vector(("packed", "legacy", false), ("packed", "casts", true),
      ("fields", "declarations", false), ("fields", "casts", true)).map {
      case (layout, signed, split) => emit(root, layout, signed, split)
    }
    val shapes = Vector((2, 3, 2, 1, 1, 0), (4, 2, 3, 3, 1, 1),
      (2, 4, 2, 1, 3, 0), (3, 2, 4, 3, 3, 1), (4, 3, 2, 3, 5, 0), (2, 4, 3, 1, 5, 1))
    val cases = shapes.map { case (uw, sw, tw, inner, count, mode) =>
      val id = s"u${uw}_s${sw}_t${tw}_i${inner}_n${count}_m$mode"
      val name = "LocalEnableCombinedNative_" + id
      val directory = root.resolve("native/" + id)
      config(directory, name, split = false).copy(headerWithRepoHash = false).generateVerilog {
        new BalancedLocalEnableCombinedNativeOracle(uw, sw, tw, inner, count, mode, name)
      }
      val files = rtlFiles(directory)
      require(files.nonEmpty && Files.isRegularFile(directory.resolve(name + ".v")), "missing independent native RTL")
      s"""{"id":${quote(id)},"uw":$uw,"sw":$sw,"tw":$tw,"inner":$inner,"count":$count,"mode":$mode,"module":${quote(name)},"files":[${files.map(f => quote(relative(root, f))).mkString(",") }]}"""
    }
    val manifest = s"""{"schema":1,"scope":"59i-local-enable-combined-native-hardware","candidates":[${candidates.mkString(",")}],"mutations":["root-enable-polarity"],"cases":[${cases.mkString(",")}]}
"""
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
