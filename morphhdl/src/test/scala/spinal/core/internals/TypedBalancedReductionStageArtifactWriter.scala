package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import spinal.core._
import spinal.lib._
import morphhdl.frontend.HdlInt

/** Independent concrete reference/replay designs. No parameterized tree is
  * emitted or claimed by these native stage/bridge qualification fixtures.
  */
final class BalancedStageReplayHardware(width: Int, count: Int, mode: Int, replay: Boolean)
    extends Component {
  require(width > 0 && count > 0 && mode >= 0 && mode <= 2)
  setDefinitionName(s"BalancedStage${if (replay) "Replay" else "Reference"}_w${width}_n${count}_m${mode}")
  val io = new Bundle {
    val clk, reset, enable = in Bool()
    val dataIn = in Bits(width * count bits)
    val boolIn = in Bits(count bits)
    val uAdd, uAnd, uOr, uXor = out UInt(width bits)
    val sAdd, sAnd, sOr, sXor = out SInt(width bits)
    val bAnd, bOr, bXor = out Bits(width bits)
    val qAnd, qOr, qXor = out Bool()
  }
  noIoPrefix()

  val area = new ClockingArea(ClockDomain(clock = io.clk, reset = io.reset,
      clockEnable = io.enable, config = ClockDomainConfig(resetKind = SYNC,
        resetActiveLevel = HIGH, clockEnableActiveLevel = HIGH))) {
    val unsigned = Vector.tabulate(count)(i => io.dataIn(i * width, width bits).asUInt)
    val signed = Vector.tabulate(count)(i => io.dataIn(i * width, width bits).asSInt)
    val packed = Vector.tabulate(count)(i => io.dataIn(i * width, width bits))
    val booleans = Vector.tabulate(count)(i => io.boolIn(i))
    private var ordinal = 0

    private def register[T <: BaseType](value: T): T = {
      val result = RegNext(value)
      result.initFrom(value.getZero)
      result
    }

    private def levelBridge[T <: BaseType](value: T, level: Int): T = mode match {
      case 0 => value
      case 1 => register(value)
      case 2 => if (level == 0) value else register(register(value))
    }

    private def reduce[T <: BaseType](values: Vector[T], operation: (T, T) => T): T = {
      val native: ElabBalancedReduction.Native[T] = (items, op, bridge) =>
        new TraversableOnceAnyPimped[T](items).reduceBalancedTree(op, bridge)
      if (!replay) return native(values, operation, levelBridge[T] _)
      ordinal += 1
      val probes = Vec(cloneOf(values.head), HdlInt.param(s"PROBE_COUNT_$ordinal", 1, 1, count))
      probes.vec.foreach(value => value.assignFromBits(B(0, value.getBitsWidth bits)))
      val certificate = TypedBalancedReductionStageReplay.capture(probes,
        operation, levelBridge[T] _, native)
      val levels = (BigInt(count) - 1).bitLength
      val expectedLatency = mode match {
        case 0 => 0
        case 1 => levels
        case 2 => 2 * scala.math.max(0, levels - 1)
      }
      require(certificate.latencyFor(count) == expectedLatency)
      require(certificate.stages.map(_.operators.size).sum == count - 1)
      certificate.replay(values)
    }

    io.uAdd := reduce(unsigned, (a: UInt, b: UInt) => a + b)
    io.uAnd := reduce(unsigned, (a: UInt, b: UInt) => a & b)
    io.uOr := reduce(unsigned, (a: UInt, b: UInt) => a | b)
    io.uXor := reduce(unsigned, (a: UInt, b: UInt) => a ^ b)
    io.sAdd := reduce(signed, (a: SInt, b: SInt) => a + b)
    io.sAnd := reduce(signed, (a: SInt, b: SInt) => a & b)
    io.sOr := reduce(signed, (a: SInt, b: SInt) => a | b)
    io.sXor := reduce(signed, (a: SInt, b: SInt) => a ^ b)
    io.bAnd := reduce(packed, (a: Bits, b: Bits) => a & b)
    io.bOr := reduce(packed, (a: Bits, b: Bits) => a | b)
    io.bXor := reduce(packed, (a: Bits, b: Bits) => a ^ b)
    io.qAnd := reduce(booleans, (a: Bool, b: Bool) => a && b)
    io.qOr := reduce(booleans, (a: Bool, b: Bool) => a || b)
    io.qXor := reduce(booleans, (a: Bool, b: Bool) => a ^ b)
    require(ordinal == (if (replay) 14 else 0), "all native stage certificates must execute")
  }
}

object TypedBalancedReductionStageArtifactWriter {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "provide one native stage-replay artifact directory")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val cases = for {
      width <- Vector(1, 5, 8, 32)
      count <- Vector(1, 2, 3, 5, 8, 9, 16, 17)
      mode <- Vector(0, 1, 2)
    } yield {
      val depth = (BigInt(count) - 1).bitLength
      val latency = if (mode == 0) 0 else if (mode == 1) depth else 2 * scala.math.max(0, depth - 1)
      val paths = Vector(false, true).map { replay =>
        val role = if (replay) "Replay" else "Reference"
        val module = s"BalancedStage${role}_w${width}_n${count}_m${mode}"
        val directory = root.resolve(s"w${width}_n${count}_m${mode}").resolve(role.toLowerCase)
        Files.createDirectories(directory)
        SpinalConfig(targetDirectory = directory.toString, headerWithDate = false,
          headerWithRepoHash = false)
          .generateVerilog(new BalancedStageReplayHardware(width, count, mode, replay))
        val path = directory.resolve(module + ".v")
        require(Files.isRegularFile(path), "missing separately elaborated native stage design")
        (module, root.relativize(path).toString.replace('\\', '/'))
      }
      s"""    {"width":$width,"count":$count,"mode":$mode,"latency":$latency,"reference_module":"${paths(0)._1}","reference_rtl":"${paths(0)._2}","replay_module":"${paths(1)._1}","replay_rtl":"${paths(1)._2}"}"""
    }
    val manifest = "{\n  \"scope\":\"concrete-native-stage-replay\",\n" +
      "  \"parameterized_tree_formal\":\"not-run\",\n  \"configurations\":[\n" +
      cases.mkString(",\n") + "\n  ]\n}\n"
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
