package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ArrayBuffer
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.lib._

/** Qualification uses only the ordinary native helper and scalar constructors. */
final class BalancedWideningHardware(width: HdlInt, count: HdlInt) extends Component {
  setDefinitionName("BalancedWidening")
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val unsignedIn = in(Vec(UInt(width bits), count)).setName("unsignedIn")
  val signedIn = in(Vec(SInt(width bits), count)).setName("signedIn")
  private def publishUInt(name: String, value: UInt): UInt = {
    val port = out(cloneOf(value)).setName(name)
    port := value
    port
  }
  private def publishSInt(name: String, value: SInt): SInt = {
    val port = out(cloneOf(value)).setName(name)
    port := value
    port
  }
  val uSum = publishUInt("uSum", unsignedIn.reduceBalancedTree((a: UInt, b: UInt) => a +^ b))
  val sSum = publishSInt("sSum", signedIn.reduceBalancedTree((a: SInt, b: SInt) => a +^ b))
  val uProduct = publishUInt("uProduct", unsignedIn.reduceBalancedTree(
    (a: UInt, b: UInt) => a * b, (value: UInt, _: Int) => value))
  val sProduct = publishSInt("sProduct", signedIn.reduceBalancedTree(
    (a: SInt, b: SInt) => a * b, (value: SInt, _: Int) => value))
  val uMin = publishUInt("uMin", unsignedIn.reduceBalancedTree((a: UInt, b: UInt) => a.min(b)))
  val uMax = publishUInt("uMax", unsignedIn.reduceBalancedTree((a: UInt, b: UInt) => a.max(b)))
  val sMin = publishSInt("sMin", signedIn.reduceBalancedTree((a: SInt, b: SInt) => a.min(b)))
  val sMax = publishSInt("sMax", signedIn.reduceBalancedTree((a: SInt, b: SInt) => a.max(b)))
  val uResize = publishUInt("uResize", unsignedIn.reduceBalancedTree(
    (a: UInt, b: UInt) => (a +^ b).resize(5)))
  val sResize = publishSInt("sResize", signedIn.reduceBalancedTree(
    (a: SInt, b: SInt) => (a +^ b).resize(5)))
  val uSymbolicResize = publishUInt("uSymbolicResize", uSum.resize(width + 1))
  val sSymbolicResize = publishSInt("sSymbolicResize", sSum.resize(width + 1))
  val registered = new ClockingArea(ClockDomain(clock = clk, reset = reset,
      clockEnable = enable, config = ClockDomainConfig(resetKind = SYNC,
        resetActiveLevel = HIGH, clockEnableActiveLevel = HIGH))) {
    val rSum = publishUInt("rSum", unsignedIn.reduceBalancedTree(
      (a: UInt, b: UInt) => a +^ b, (value: UInt, _: Int) => RegNext(value).init(U(0))))
    val rSignedSum = publishSInt("rSignedSum", signedIn.reduceBalancedTree(
      (a: SInt, b: SInt) => a +^ b, (value: SInt, _: Int) => RegNext(value).init(S(0))))
  }
}

/** Independently elaborated, parameter-free native source, including width evidence. */
final class BalancedWideningReference(width: Int, count: Int) extends Component {
  setDefinitionName(s"BalancedWideningReference_w${width}_n$count")
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val unsignedIn = in(Bits(width * count bits)).setName("unsignedIn")
  val signedIn = in(Bits(width * count bits)).setName("signedIn")
  val unsigned = Vector.tabulate(count)(i => unsignedIn(i * width, width bits).asUInt)
  val signed = Vector.tabulate(count)(i => signedIn(i * width, width bits).asSInt)
  private def publishUInt(name: String, value: UInt): UInt = {
    val port = out(cloneOf(value)).setName(name)
    port := value
    port
  }
  private def publishSInt(name: String, value: SInt): SInt = {
    val port = out(cloneOf(value)).setName(name)
    port := value
    port
  }
  val productStages = ArrayBuffer.empty[(Int, Int)]
  val uSum = publishUInt("uSum", unsigned.reduceBalancedTree((a: UInt, b: UInt) => a +^ b))
  val sSum = publishSInt("sSum", signed.reduceBalancedTree((a: SInt, b: SInt) => a +^ b))
  val uProduct = publishUInt("uProduct", unsigned.reduceBalancedTree(
    (a: UInt, b: UInt) => a * b, (value: UInt, level: Int) => {
      productStages += ((level, value.getBitsWidth))
      value
    }))
  val sProduct = publishSInt("sProduct", signed.reduceBalancedTree((a: SInt, b: SInt) => a * b))
  val uMin = publishUInt("uMin", unsigned.reduceBalancedTree((a: UInt, b: UInt) => a.min(b)))
  val uMax = publishUInt("uMax", unsigned.reduceBalancedTree((a: UInt, b: UInt) => a.max(b)))
  val sMin = publishSInt("sMin", signed.reduceBalancedTree((a: SInt, b: SInt) => a.min(b)))
  val sMax = publishSInt("sMax", signed.reduceBalancedTree((a: SInt, b: SInt) => a.max(b)))
  val uResize = publishUInt("uResize", unsigned.reduceBalancedTree(
    (a: UInt, b: UInt) => (a +^ b).resize(5)))
  val sResize = publishSInt("sResize", signed.reduceBalancedTree(
    (a: SInt, b: SInt) => (a +^ b).resize(5)))
  val uSymbolicResize = publishUInt("uSymbolicResize", uSum.resize(width + 1))
  val sSymbolicResize = publishSInt("sSymbolicResize", sSum.resize(width + 1))
  val registered = new ClockingArea(ClockDomain(clock = clk, reset = reset,
      clockEnable = enable, config = ClockDomainConfig(resetKind = SYNC,
        resetActiveLevel = HIGH, clockEnableActiveLevel = HIGH))) {
    val rSum = publishUInt("rSum", unsigned.reduceBalancedTree(
      (a: UInt, b: UInt) => a +^ b, (value: UInt, _: Int) => RegNext(value).init(U(0))))
    val rSignedSum = publishSInt("rSignedSum", signed.reduceBalancedTree(
      (a: SInt, b: SInt) => a +^ b, (value: SInt, _: Int) => RegNext(value).init(S(0))))
  }
  def outputs: Vector[(String, BitVector)] = Vector(
    "uSum" -> uSum, "sSum" -> sSum, "uProduct" -> uProduct, "sProduct" -> sProduct,
    "uMin" -> uMin, "uMax" -> uMax, "sMin" -> sMin, "sMax" -> sMax,
    "uResize" -> uResize, "sResize" -> sResize,
    "uSymbolicResize" -> uSymbolicResize, "sSymbolicResize" -> sSymbolicResize,
    "rSum" -> registered.rSum, "rSignedSum" -> registered.rSignedSum)
}

object TypedBalancedReductionWideningArtifactWriter {
  val profiles = Vector(("singleton", 5, 1), ("alternate", 8, 5))
  val widths = Vector(1, 5, 8, 32)
  val counts = Vector(1, 2, 3, 5, 8, 9, 16, 17)

  def config(directory: Path, fileName: String, nativeReference: Boolean = false): SpinalConfig = {
    Files.createDirectories(directory)
    val result = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, headerWithRepoHash = !nativeReference)
    result.netlistFileName = fileName
    result
  }

  def candidate(directory: Path, defaultWidth: Int = 5, defaultCount: Int = 1): Path = {
    MorphVerilog(config(directory, "BalancedWidening.v")) {
      new BalancedWideningHardware(HdlInt.param("WIDTH", defaultWidth, 1, 32),
        HdlInt.param("COUNT", defaultCount, 1, 17))
    }
    val path = directory.resolve("BalancedWidening.v")
    require(Files.isRegularFile(path), "missing widening public-helper artifact")
    path
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "provide one widening qualification artifact directory")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    def relative(path: Path): String = root.relativize(path).toString.replace('\\', '/')
    val candidates = profiles.map { case (profile, width, count) =>
      val rtl = candidate(root.resolve("candidate").resolve(profile), width, count)
      profile -> relative(rtl)
    }.toMap
    val profileJson = profiles.map { case (profile, width, count) =>
      s"""    {"name":"$profile","default_width":$width,"default_count":$count,"candidate_module":"BalancedWidening","candidate_rtl":"${candidates(profile)}"}"""
    }
    val cases = for (width <- widths; count <- counts) yield {
      val module = s"BalancedWideningReference_w${width}_n$count"
      val directory = root.resolve(s"reference/w${width}_n$count")
      val report = config(directory, module + ".v", nativeReference = true)
        .generateVerilog(new BalancedWideningReference(width, count))
      val shapes = report.toplevel.outputs.map { case (name, value) =>
        val kind = value match {
          case _: UInt => "UInt"
          case _: SInt => "SInt"
          case other => throw new IllegalArgumentException("unexpected native leaf: " + other.getClass.getName)
        }
        s""""$name":{"width":${value.getWidth},"kind":"$kind"}"""
      }.mkString(",")
      val stages = report.toplevel.productStages.map { case (level, nativeWidth) =>
        s"""{"level":$level,"width":$nativeWidth}"""
      }.mkString(",")
      s"""    {"width":$width,"count":$count,"reference_module":"$module","reference_rtl":"${relative(directory.resolve(module + ".v"))}","native_outputs":{$shapes},"native_product_stages":[$stages]}"""
    }
    val manifest = "{\n  \"scope\":\"parameterized-native-balanced-widening\",\n" +
      "  \"independent_inputs\":[\"unsignedIn\",\"signedIn\"],\n" +
      "  \"profiles\":[\n" + profileJson.mkString(",\n") + "\n  ],\n" +
      "  \"configurations\":[\n" + cases.mkString(",\n") + "\n  ]\n}\n"
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
