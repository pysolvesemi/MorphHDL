package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import nativeapplication.BalancedCompositeLocalEnableNativeOracle
import spinal.core._
import spinal.lib._

final case class LocalEnableHardwareRecord(uw: HdlInt, sw: HdlInt, bw: HdlInt) extends Bundle {
  val unsigned = UInt(uw bits)
  val signed = SInt(sw bits)
  val bitsValue = Bits(bw bits)
  val valid = Bool()
}

/** The public parameterized path, with two registers per field and bridge row.
  * A second register's enable can read its current data, its original self and
  * original peers simultaneously. Mutations are emitted hardware, never an
  * alternate expected-value model in the testbench.
  */
final class BalancedCompositeLocalEnableHardware(uw: HdlInt, sw: HdlInt, bw: HdlInt,
    count: HdlInt, asynchronous: Boolean, resetLow: Boolean, falling: Boolean,
    moduleName: String, mutation: String = "none") extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val values = in(Vec(LocalEnableHardwareRecord(uw, sw, bw), count)).setName("values")
  val result = out(LocalEnableHardwareRecord(uw, sw, bw)).setName("result")
  val pipeline = new ClockingArea(ClockDomain(clock = clk, reset = reset,
      clockEnable = enable, config = ClockDomainConfig(
        clockEdge = if (falling) FALLING else RISING,
        resetKind = if (asynchronous) ASYNC else SYNC,
        resetActiveLevel = if (resetLow) LOW else HIGH,
        clockEnableActiveLevel = if (falling) LOW else HIGH))) {
    result := values.reduceBalancedTree(
      (a: LocalEnableHardwareRecord, b: LocalEnableHardwareRecord) => {
        val r = cloneOf(a)
        r.unsigned := a.unsigned + b.unsigned
        r.signed := a.signed + b.signed
        r.bitsValue := a.bitsValue ^ b.bitsValue
        r.valid := a.valid | b.valid
        r
      },
      (value: LocalEnableHardwareRecord, _: Int) => {
        val u = RegNext(value.unsigned) init U(1)
        val s = RegNext(value.signed) init S(-1)
        val b = RegNext(value.bitsValue) init B(2)
        val f = RegNext(value.valid) init True
        val r = cloneOf(value)
        // The enable-identity mutant deliberately conflates original self with
        // the current data driver. Original peer fields remain unregistered.
        val originalU = if (mutation == "enable-identity") u else value.unsigned
        val originalF = if (mutation == "enable-identity") f else value.valid
        r.unsigned := RegNextWhen(u, u(0) ^ originalU.msb ^ value.valid) init U(3)
        r.signed := RegNextWhen(s, s.msb ^ value.unsigned(0)) init S(if (mutation == "reset-value") -1 else -2)
        r.bitsValue := RegNextWhen(b, b.msb ^ value.signed(0)) init B(1)
        r.valid := RegNextWhen(f, f ^ originalF ^ value.bitsValue(0)) init False
        r
      })
  }
}

object TypedBalancedReductionCompositeLocalEnableArtifactWriter {
  private def quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  private def relative(root: Path, file: Path): String = root.relativize(file).toString.replace('\\', '/')
  private def config(directory: Path, name: String, split: Boolean): SpinalConfig = {
    Files.createDirectories(directory)
    val value = SpinalConfig(targetDirectory = directory.toString, bitVectorWidthMax = 4096,
      oneFilePerComponent = split, headerWithDate = false, headerWithRepoHash = true)
    if (!split) value.netlistFileName = name + ".v"
    value
  }
  private def emitCandidate(root: Path, profile: String, asynchronous: Boolean,
      resetLow: Boolean, falling: Boolean, split: Boolean, mutation: String): String = {
    val name = "LocalEnableCandidate_" + profile + "_" + (if (split) "split" else "single") + "_" + mutation.replace('-', '_')
    val directory = root.resolve("candidate/" + name)
    MorphVerilog(config(directory, name, split)) {
      new BalancedCompositeLocalEnableHardware(HdlInt.param("UW", 5, 3, 8),
        HdlInt.param("SW", 7, 3, 8), HdlInt.param("BW", 3, 2, 8),
        HdlInt.param("COUNT", 1, 1, 5), asynchronous, resetLow, falling, name, mutation)
    }
    val file = directory.resolve(name + ".v")
    require(Files.isRegularFile(file), "missing local-enable parameterized RTL")
    s"""{"profile":${quote(profile)},"split":$split,"mutation":${quote(mutation)},"module":${quote(name)},"file":${quote(relative(root, file))}}"""
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: TypedBalancedReductionCompositeLocalEnableArtifactWriter OUTPUT")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val profiles = for (async <- Vector(false, true); low <- Vector(false, true); falling <- Vector(false, true))
      yield ((if (async) "async" else "sync") + "_" + (if (low) "low" else "high") + "_" +
        (if (falling) "falling" else "rising"), async, low, falling)
    val candidates = for ((profile, async, low, falling) <- profiles; split <- Vector(false, true))
      yield emitCandidate(root, profile, async, low, falling, split, "none")
    val mutations = Vector("enable-identity", "reset-value").map { mutation =>
      emitCandidate(root, "sync_high_rising", false, false, false, false, mutation)
    }
    val cases = for ((profile, async, low, falling) <- profiles;
      (uw, sw, bw) <- Vector((3, 4, 2), (5, 7, 3), (8, 3, 6)); count <- Vector(1, 2, 3, 5)) yield {
      val id = s"${profile}_u${uw}_s${sw}_b${bw}_n$count"
      val name = "LocalEnableNative_" + id
      val directory = root.resolve("native/" + id)
      config(directory, name, split = false).copy(headerWithRepoHash = false).generateVerilog {
        new BalancedCompositeLocalEnableNativeOracle(uw, sw, bw, count, async, low, falling, name)
      }
      val file = directory.resolve(name + ".v")
      require(Files.isRegularFile(file), "missing independently elaborated native local-enable RTL")
      s"""{"id":${quote(id)},"profile":${quote(profile)},"asynchronous":$async,"reset_low":$low,"falling":$falling,"enable_low":$falling,"uw":$uw,"sw":$sw,"bw":$bw,"count":$count,"module":${quote(name)},"file":${quote(relative(root, file))}}"""
    }
    val manifest = s"""{"schema":1,"scope":"59i-local-enable-native-hardware","candidates":[${candidates.mkString(",")}],"mutations":[${mutations.mkString(",")}],"cases":[${cases.mkString(",")}]}
"""
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
