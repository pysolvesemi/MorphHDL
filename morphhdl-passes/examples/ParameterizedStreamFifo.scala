package morphhdl.examples

import java.nio.file.{Path, Paths}

import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.lib._

/** Test-only source/elaboration provenance for an explicitly user-named alias. */
private[examples] final case class ExplicitNamedWireAliasSourceTag(name: String) extends SpinalTag

/** Runnable example that emits one StreamFifo whose depth remains a Verilog parameter. */
final class ParameterizedStreamFifo(width: HdlInt, depth: HdlInt) extends Component {
  setDefinitionName("ParameterizedStreamFifo")

  val io = new Bundle {
    val push = slave Stream (Bits(width bits))
    val pop = master Stream (Bits(width bits))
    val flush = in Bool ()
    val occupancy = out UInt (4 bits)
    val availability = out UInt (4 bits)
  }

  private def directUnnamedAlias[T <: Data](source: T): T = {
    val alias = ParameterizedWidth.cloneOf(source)
    alias := source
    alias
  }

  private def directNamedAlias[T <: Data](source: T): T = {
    val alias = ParameterizedWidth.cloneOf(source)
    alias.setName("popPayloadNamedAlias")
    alias.addTag(ExplicitNamedWireAliasSourceTag("popPayloadNamedAlias"))
    alias := source
    alias
  }

  private def continuousUnnamedExpression(source: Bits): Bits = {
    val temporary = ParameterizedWidth.cloneOf(source)
    temporary := source | source
    temporary
  }

  val fifo = StreamFifo(HardType(Bits(width bits)), depth.asElabInt)
  fifo.io.push << io.push
  io.pop.valid := fifo.io.pop.valid
  fifo.io.pop.ready := io.pop.ready

  // Keep the source on the parent side of the hierarchy boundary so the
  // test-only WA-04 and WA-05 bridges can prove exact same-component identity.
  val popPayloadSource = Bits(width bits)
  popPayloadSource := fifo.io.pop.payload
  io.pop.payload := directNamedAlias(
    directUnnamedAlias(continuousUnnamedExpression(popPayloadSource))
  )

  fifo.io.flush := io.flush
  io.occupancy := fifo.io.occupancy.resized
  io.availability := fifo.io.availability.resized
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
