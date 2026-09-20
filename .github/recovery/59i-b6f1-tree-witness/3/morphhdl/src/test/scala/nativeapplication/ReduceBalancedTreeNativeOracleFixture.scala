package nativeapplication

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import spinal.core._
import spinal.lib._

/** Ordinary concrete SpinalHDL baseline. This is deliberately not the typed
  * candidate and must not be used to claim parameter-override support.
  */
final class ReduceBalancedTreeNativeOracle(width: Int, count: Int) extends Component {
  require(width > 0 && count > 0)
  val moduleName = s"ReduceBalancedTreeNativeOracle_w${width}_n${count}"
  setDefinitionName(moduleName)
  val io = new Bundle {
    val clk = in Bool()
    val reset = in Bool()
    val dataIn = in Bits(width * count bits)
    val sumResult = out UInt(width bits)
    val orResult = out UInt(width bits)
    val xorResult = out UInt(width bits)
    val minResult = out UInt(width bits)
    val maxResult = out UInt(width bits)
    val signedMinResult = out SInt(width bits)
    val signedMaxResult = out SInt(width bits)
    val growingResult = out UInt(width + log2Up(count) bits)
    val pipelineResult = out UInt(width bits)
  }
  noIoPrefix()
  val words = Vec(UInt(width bits), count)
  for (index <- 0 until count) words(index) := io.dataIn(index * width, width bits).asUInt
  io.sumResult := words.reduceBalancedTree(_ + _)
  io.orResult := words.reduceBalancedTree(_ | _)
  io.xorResult := words.reduceBalancedTree(_ ^ _)
  io.minResult := words.reduceBalancedTree(_ min _)
  io.maxResult := words.reduceBalancedTree(_ max _)
  val signedWords = words.map(_.asSInt)
  io.signedMinResult := signedWords.reduceBalancedTree(_ min _)
  io.signedMaxResult := signedWords.reduceBalancedTree(_ max _)
  io.growingResult := words.reduceBalancedTree(_ +^ _)
  val pipeline = new ClockingArea(ClockDomain(
    clock = io.clk, reset = io.reset,
    config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
  )) {
    io.pipelineResult := words.reduceBalancedTree(
      (a: UInt, b: UInt) => a + b,
      (value: UInt, level: Int) => RegNext(value) init U(0, width bits)
    )
  }
}

object ReduceBalancedTreeNativeOracleArtifactWriter {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "provide one native-oracle output directory")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val entries = for {
      width <- Vector(1, 5, 8, 32)
      count <- Vector(1, 2, 3, 5, 8, 9, 16, 17)
    } yield {
      val module = s"ReduceBalancedTreeNativeOracle_w${width}_n${count}"
      val relative = s"w${width}_n${count}"
      val directory = root.resolve(relative)
      Files.createDirectories(directory)
      SpinalConfig(
        targetDirectory = directory.toString,
        headerWithDate = false,
        headerWithRepoHash = false
      ).generateVerilog(new ReduceBalancedTreeNativeOracle(width, count))
      require(Files.isRegularFile(directory.resolve(s"$module.v")), s"missing oracle $module")
      s"""    {"width":$width,"count":$count,"module":"$module","rtl":"$relative/$module.v"}"""
    }
    val manifest = "{\n  \"status\":\"native-oracle-only\",\n  \"configurations\":[\n" +
      entries.mkString(",\n") + "\n  ]\n}\n"
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
