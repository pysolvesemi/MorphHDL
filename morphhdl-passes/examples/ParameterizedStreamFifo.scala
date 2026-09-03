package morphhdl.examples

import java.nio.file.{Path, Paths}

import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.lib._

/** Runnable example that emits one StreamFifo whose depth remains a Verilog parameter. */
final class ParameterizedStreamFifo(width: HdlInt, depth: HdlInt) extends Component {
  setDefinitionName("ParameterizedStreamFifo")

  private val observationWidth = depth.asElabInt

  val io = new Bundle {
    val push = slave Stream (Bits(width bits))
    val pop = master Stream (Bits(width bits))
    val flush = in Bool ()
    val occupancy = out UInt (observationWidth bits)
    val availability = out UInt (observationWidth bits)
  }

  val fifo = StreamFifo(HardType(Bits(width bits)), depth.asElabInt)
  fifo.io.push << io.push
  io.pop << fifo.io.pop
  fifo.io.flush := io.flush
  io.occupancy := fifo.io.occupancy.resize(observationWidth)
  io.availability := fifo.io.availability.resize(observationWidth)
}

object ParameterizedStreamFifoExample {
  private val DefaultOutputFile = "parameterized_stream_fifo.v"

  def main(args: Array[String]): Unit = {
    val outputDirectory: Path = args.headOption
      .map(Paths.get(_))
      .getOrElse(Paths.get("generated", "parameterized-streamfifo-example"))
    val outputFile = args.lift(1).getOrElse(DefaultOutputFile)

    val config = SpinalConfig(
      targetDirectory = outputDirectory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    )
    config.netlistFileName = outputFile

    val width = HdlInt.param(
      "WIDTH",
      default = BigInt(8),
      min = BigInt(1),
      max = BigInt(64)
    )
    val depth = HdlInt.param(
      "DEPTH",
      default = BigInt(5),
      min = BigInt(1),
      max = BigInt(8)
    )

    val report = MorphVerilog(config) {
      new ParameterizedStreamFifo(width, depth)
    }

    println(Paths.get(report.generatedSourcesPaths.head).toAbsolutePath.normalize)
  }
}
