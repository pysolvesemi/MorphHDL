package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import nativeapplication.BalancedBridgeNativeOracle
import spinal.core._
import spinal.lib._

/** Public helper fixture. The backend must discover and publish every bridge
  * from these ordinary callbacks, including stages absent at COUNT=1.
  */
final class BalancedBridgeHardware(width: HdlInt, count: HdlInt, moduleName: String,
    rising: Boolean, asynchronous: Boolean, resetHigh: Boolean,
    enablePolarity: String) extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val dataIn = in(Vec(UInt(width bits), count)).setName("dataIn")
  val signedIn = in(Vec(SInt(width bits), count)).setName("signedIn")
  val bitsIn = in(Vec(Bits(width bits), count)).setName("bitsIn")
  val boolIn = in(Vec(Bool(), count)).setName("boolIn")
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

  val domain = new ClockingArea(ClockDomain(clock = clk, reset = reset,
      clockEnable = if (enablePolarity == "NONE") null else enable,
      config = ClockDomainConfig(clockEdge = if (rising) RISING else FALLING,
        resetKind = if (asynchronous) ASYNC else SYNC,
        resetActiveLevel = if (resetHigh) HIGH else LOW,
        clockEnableActiveLevel = if (enablePolarity == "LOW") LOW else HIGH))) {
    identityResult := dataIn.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, _: Int) => value)
    aliasResult := dataIn.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, _: Int) => {
        val result = UInt()
        result := value
        result
      })
    regResult := dataIn.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, _: Int) => RegNext(value))
    zeroResult := dataIn.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, _: Int) => RegNext(value) init U(0))
    initResult := dataIn.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, _: Int) => RegNext(value) init U(1))
    levelResult := dataIn.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, level: Int) => {
        if (level == 0) value
        else {
          val first = RegNext(value) init U(1)
          RegNext(first) init U(1)
        }
      })
    localEnableResult := dataIn.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, _: Int) => RegNextWhen(value, value.msb) init U(1))
    localDisableResult := dataIn.reduceBalancedTree((a: UInt, b: UInt) => a + b,
      (value: UInt, _: Int) => RegNextWhen(value, !value.msb) init U(1))
    signedResult := signedIn.reduceBalancedTree((a: SInt, b: SInt) => a + b,
      (value: SInt, _: Int) => RegNextWhen(value, !value.msb) init S(-1))
    bitsResult := bitsIn.reduceBalancedTree((a: Bits, b: Bits) => a ^ b,
      (value: Bits, _: Int) => RegNextWhen(value, value(0)) init B(1))
    boolResult := boolIn.reduceBalancedTree((a: Bool, b: Bool) => a ^ b,
      (value: Bool, _: Int) => RegNextWhen(value, !value) init True)
  }
}

object TypedBalancedReductionBridgeArtifactWriter {
  final case class ClockProfile(rising: Boolean, asynchronous: Boolean,
      resetHigh: Boolean, enablePolarity: String) {
    val clockEdge: String = if (rising) "RISING" else "FALLING"
    val resetKind: String = if (asynchronous) "ASYNC" else "SYNC"
    val resetPolarity: String = if (resetHigh) "HIGH" else "LOW"
    val name: String = s"${resetKind}_${resetPolarity}_${clockEdge}_$enablePolarity".toLowerCase
    val json: String = s""""clock_profile":"$name","clock_edge":"$clockEdge","reset_kind":"$resetKind","reset_active_level":"$resetPolarity","enable_active_level":"$enablePolarity""""
  }
  val outputs = Vector("identityResult", "aliasResult", "regResult", "zeroResult",
    "initResult", "levelResult", "localEnableResult", "localDisableResult",
    "signedResult", "bitsResult", "boolResult")
  val defaults = Vector(("singleton", 5, 1), ("alternate", 8, 3))
  val clocks: Vector[ClockProfile] = (for {
    asynchronous <- Vector(false, true)
    resetHigh <- Vector(true, false)
    rising <- Vector(true, false)
    enable <- Vector("HIGH", "LOW", "NONE")
  } yield ClockProfile(rising, asynchronous, resetHigh, enable)).toVector

  def shapes(clock: ClockProfile): Vector[(Int, Int)] = {
    val ordinary = if (clock.enablePolarity == "NONE") Vector((5, 3))
      else (for (width <- Vector(5, 8); count <- Vector(1, 2, 3, 5, 9)) yield (width, count)).toVector
    val scalarMatrix = if (clock == clocks.head)
      (for (width <- Vector(1, 5, 8, 32); count <- Vector(1, 2, 3, 5, 8, 9, 16, 17))
        yield (width, count)).toVector else Vector.empty
    (ordinary ++ scalarMatrix).distinct
  }

  def config(directory: Path, fileName: String, nativeReference: Boolean = false): SpinalConfig = {
    Files.createDirectories(directory)
    val result = if (nativeReference) SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, headerWithRepoHash = false)
      else SpinalConfig(targetDirectory = directory.toString)
    result.netlistFileName = fileName
    result
  }

  def candidate(directory: Path, module: String, clock: ClockProfile,
      defaultWidth: Int = 5, defaultCount: Int = 1): Path = {
    MorphVerilog(config(directory, module + ".v")) {
      new BalancedBridgeHardware(HdlInt.param("WIDTH", defaultWidth, 1, 32),
        HdlInt.param("COUNT", defaultCount, 1, 17), module,
        clock.rising, clock.asynchronous, clock.resetHigh, clock.enablePolarity)
    }
    val path = directory.resolve(module + ".v")
    require(Files.isRegularFile(path), "missing parameterized register-bridge candidate")
    path
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1 || (args.length == 2 && args(1) == "--references-only"),
      "provide one bridge artifact directory and optional --references-only")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val cases = for {
      clock <- clocks
      (width, count) <- shapes(clock)
    } yield {
      val module = s"BalancedBridgeNativeOracle_${clock.name}_w${width}_n$count"
      val relative = s"reference/${clock.name}/w${width}_n$count/$module.v"
      val directory = root.resolve("reference").resolve(clock.name).resolve(s"w${width}_n$count")
      config(directory, module + ".v", nativeReference = true).generateVerilog(
        new BalancedBridgeNativeOracle(width, count, module, clock.rising,
          clock.asynchronous, clock.resetHigh, clock.enablePolarity))
      require(Files.isRegularFile(root.resolve(relative)), "missing untouched native bridge reference")
      val levels = (BigInt(count) - 1).bitLength
      val levelLatency = 2 * scala.math.max(0, levels - 1)
      s"""    {${clock.json},"width":$width,"count":$count,"reference_module":"$module","reference_rtl":"$relative","pipeline_depth":$levels,"level_pipeline_depth":$levelLatency}"""
    }
    val profiles = for {
      clock <- clocks
      // Clock/reset configurations change static native clock semantics, not
      // typed WIDTH/COUNT default provenance. Exercise both defaults over the
      // complete scalar matrix at c0 and the singleton default at every clock.
      (profile, defaultWidth, defaultCount) <- (if (clock == clocks.head) defaults else defaults.take(1))
    } yield {
      val module = s"BalancedBridge_${clock.name}_$profile"
      val relative = s"candidate/${clock.name}/$profile/$module.v"
      val directory = root.resolve("candidate").resolve(clock.name).resolve(profile)
      if (args.length == 2) require(Files.isRegularFile(root.resolve(relative)),
        "reference refresh requires the existing sole candidate for " + clock.name + "/" + profile)
      else candidate(directory, module, clock, defaultWidth, defaultCount)
      s"""    {"profile":"$profile",${clock.json},"width":$defaultWidth,"count":$defaultCount,"module":"$module","rtl":"$relative"}"""
    }
    val manifest = "{\n  \"scope\":\"parameterized-register-bridges\",\n" +
      "  \"reference_source\":\"nativeapplication/BalancedBridgeNativeOracle.scala\",\n" +
      "  \"uninitialized_contract\":\"regResult valid after pipeline_depth enabled clock edges; reset does not initialize it\",\n" +
      "  \"independent_inputs\":[\"clk\",\"reset\",\"enable\",\"dataIn\",\"signedIn\",\"bitsIn\",\"boolIn\"],\n" +
      "  \"outputs\":[" + outputs.map(value => "\"" + value + "\"").mkString(",") + "],\n" +
      "  \"profiles\":[\n" + profiles.mkString(",\n") + "\n  ],\n" +
      "  \"configurations\":[\n" + cases.mkString(",\n") + "\n  ]\n}\n"
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
