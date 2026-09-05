package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import spinal.core._
import spinal.lib._
import morphhdl.frontend.HdlInt

/** Separately elaborated concrete native reference and native-body replay.
  * COUNT and WIDTH are concrete in these artifacts. They qualify operator
  * replay, not the still-pending parameterized balanced-stage publisher.
  */
final class BalancedOperatorReplayHardware(width: Int, count: Int, useReplay: Boolean)
    extends Component {
  require(width > 0 && count > 0)
  setDefinitionName(s"BalancedOperator${if (useReplay) "Replay" else "Reference"}_w${width}_n${count}")
  val io = new Bundle {
    val dataIn = in Bits(width * count bits)
    val boolIn = in Bits(count bits)
    val uAdd = out UInt(width bits)
    val uAnd = out UInt(width bits)
    val uOr = out UInt(width bits)
    val uXor = out UInt(width bits)
    val sAdd = out SInt(width bits)
    val sAnd = out SInt(width bits)
    val sOr = out SInt(width bits)
    val sXor = out SInt(width bits)
    val bAnd = out Bits(width bits)
    val bOr = out Bits(width bits)
    val bXor = out Bits(width bits)
    val uMin, uMax = out UInt(width bits)
    val sMin, sMax = out SInt(width bits)
    val qAnd = out Bool()
    val qOr = out Bool()
    val qXor = out Bool()
  }
  noIoPrefix()
  val unsigned = Vector.tabulate(count)(i => io.dataIn(i * width, width bits).asUInt)
  val signed = Vector.tabulate(count)(i => io.dataIn(i * width, width bits).asSInt)
  val packed = Vector.tabulate(count)(i => io.dataIn(i * width, width bits))
  val booleans = Vector.tabulate(count)(i => io.boolIn(i))
  private var bodyOrdinal = 0
  private var replayCalls = 0

  private def reduce[T <: BaseType](values: Vector[T], operation: (T, T) => T): T = {
    val native: ElabBalancedReduction.Native[T] = (elements, op, bridge) =>
      new TraversableOnceAnyPimped[T](elements).reduceBalancedTree(op, bridge)
    if (!useReplay || count == 1)
      return native(values, operation, (value: T, _: Int) => value)

    // Capture the real callback on independent, fully driven native probes.
    // The typed count is internal construction evidence, not a published port
    // shape and not a replacement for parameterized COUNT validation.
    bodyOrdinal += 1
    val probes = Vec(cloneOf(values.head), HdlInt.param(s"CAPTURE_COUNT_$bodyOrdinal", 3, 1, 3))
    probes.vec.foreach { probe =>
      probe.assignFromBits(B(0, probe.getBitsWidth bits))
    }
    val observed = TypedBalancedReductionCapture(probes, operation,
      (value: T, _: Int) => value, native)
    val proof = TypedBalancedReductionOperatorReplay.certify(observed.rows.head.operator.get)
    native(values, (left: T, right: T) => {
      replayCalls += 1
      proof.replay(left, right).asInstanceOf[T]
    }, (value: T, _: Int) => value)
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
  io.uMin := reduce(unsigned, (a: UInt, b: UInt) => a min b)
  io.uMax := reduce(unsigned, (a: UInt, b: UInt) => a max b)
  io.sMin := reduce(signed, (a: SInt, b: SInt) => a min b)
  io.sMax := reduce(signed, (a: SInt, b: SInt) => a max b)
  io.qAnd := reduce(booleans, (a: Bool, b: Bool) => a && b)
  io.qOr := reduce(booleans, (a: Bool, b: Bool) => a || b)
  io.qXor := reduce(booleans, (a: Bool, b: Bool) => a ^ b)
  require(replayCalls == (if (useReplay) 18 * (count - 1) else 0),
    "the candidate did not execute its expected native replay calls")
  require(bodyOrdinal == (if (useReplay && count > 1) 18 else 0),
    "singleton bypass or operator-body coverage changed")
}

object TypedBalancedReductionOperatorArtifactWriter {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "provide one concrete operator-replay artifact directory")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val cases = for {
      width <- Vector(1, 5, 8, 32)
      count <- Vector(1, 2, 3, 5, 8, 9, 16, 17)
    } yield {
      val paths = Vector(false, true).map { replay =>
        val role = if (replay) "Replay" else "Reference"
        val module = s"BalancedOperator${role}_w${width}_n${count}"
        val directory = root.resolve(s"w${width}_n${count}").resolve(role.toLowerCase)
        Files.createDirectories(directory)
        SpinalConfig(targetDirectory = directory.toString,
          headerWithDate = false, headerWithRepoHash = false)
          .generateVerilog(new BalancedOperatorReplayHardware(width, count, replay))
        val path = directory.resolve(s"$module.v")
        require(Files.isRegularFile(path), s"missing independently generated $role artifact")
        (module, root.relativize(path).toString.replace('\\', '/'))
      }
      s"""    {"width":$width,"count":$count,"reference_module":"${paths(0)._1}","reference_rtl":"${paths(0)._2}","replay_module":"${paths(1)._1}","replay_rtl":"${paths(1)._2}","replay_calls":${18 * (count - 1)}}"""
    }
    val manifest = "{\n  \"scope\":\"concrete-native-operator-replay\",\n" +
      "  \"parameterized_tree_formal\":\"not-run\",\n  \"configurations\":[\n" +
      cases.mkString(",\n") + "\n  ]\n}\n"
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
