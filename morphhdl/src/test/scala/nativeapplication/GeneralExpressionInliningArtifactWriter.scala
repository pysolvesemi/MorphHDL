package morphhdl.examples

import java.nio.file.{Files, Path, Paths}

import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlBool
import spinal.core._

/**
  * WA-10 production-path regression with real unnamed, shared declarations.
  *
  * The annotation prevents reflection from giving the carriers source names;
  * there is deliberately no test tag, generated-looking name, or phase hook.
  * Two receivers keep the three main candidates alive through ordinary
  * single-use native cleanup, independently of emitter-created temporaries.
  */
final class GeneralExpressionInlining(ppc: ElabInt) extends Component {
  setDefinitionName("GeneralExpressionInlining")

  val word, other = in UInt(16 bits)
  val total, index = in UInt(13 bits)
  val narrow = in UInt(8 bits)
  val unsignedWide = in UInt(9 bits)
  val signedA, signedB = in SInt(8 bits)
  val choose, load = in Bool()
  val laneMaskIn = in Bits(ppc bits)

  val constantOK, extensionOK, subtractionOK, signedOK = out Bool()
  val constantMux = out UInt(16 bits)
  val extensionSum, muxResult = out UInt(18 bits)
  val subtractionMux = out UInt(13 bits)
  val wrappedAdd, wrappedSub = out UInt(8 bits)
  val widenedAdd, widenedSub = out UInt(9 bits)
  val signedExtended = out SInt(12 bits)
  val mixedSigned = out SInt(10 bits)
  val truncated = out UInt(5 bits)
  val sliced = out Bits(7 bits)
  val registerResult = out UInt(12 bits)
  val keptResult, guardedResult = out UInt(13 bits)
  val laneMaskOut = out Bits(ppc bits)

  @dontName val constant = UInt(16 bits)
  constant := U(1, 16 bits)
  constantOK := word >= constant
  constantMux := Mux(choose, constant, other)

  @dontName val extension = UInt(18 bits)
  extension := word.resize(18)
  extensionSum := extension + other.resize(18)
  extensionOK := extension <= U(65535, 18 bits)

  @dontName val difference = UInt(13 bits)
  difference := total - U(1, 13 bits)
  subtractionOK := index === difference
  subtractionMux := Mux(choose, difference, index)

  // Distinct carriers make the widening fences explicit. At narrow=255 the
  // addition wraps to zero before extension; at narrow=0 subtraction yields
  // 255, not a nine-bit 511. An unproved fence must remain in emitted HDL.
  @dontName val addFence = UInt(8 bits)
  addFence := narrow + U(1, 8 bits)
  wrappedAdd := addFence
  widenedAdd := addFence.resize(9)
  @dontName val subFence = UInt(8 bits)
  subFence := narrow - U(1, 8 bits)
  wrappedSub := subFence
  widenedSub := subFence.resize(9)

  @dontName val signedDifference = SInt(8 bits)
  signedDifference := signedA - S(1, 8 bits)
  signedOK := signedB < signedDifference
  signedExtended := signedDifference.resize(12)
  // Explicit unsigned zero-extension before signed reinterpretation keeps
  // 9-bit values 256..511 positive in the ten-bit signed arithmetic.
  mixedSigned := signedA.resize(10) + unsignedWide.resize(10).asSInt

  truncated := (word.resize(18) + other.resize(18)).resize(5)
  sliced := (total - U(1, 13 bits)).asBits(8 downto 2)
  muxResult := Mux(choose, word.resize(18), other.resize(18)) + U(1, 18 bits)

  @dontName val registerExpression = UInt(18 bits)
  registerExpression := word.resize(18) + other.resize(18)
  val state = Reg(UInt(12 bits)) init(0)
  when(load) { state := registerExpression.resize(12) }
  registerResult := state

  val protectedDifference = UInt(13 bits)
  protectedDifference.addAttribute("keep")
  protectedDifference := total - U(1, 13 bits)
  keptResult := protectedDifference
  val vitalDifference = UInt(13 bits)
  vitalDifference.dontSimplifyIt()
  vitalDifference := total - U(1, 13 bits)
  guardedResult := vitalDifference
  laneMaskOut := laneMaskIn
}

/** Same native parameterized artifact in all public pipeline modes. */
object GeneralExpressionInliningArtifactWriter {
  def generate(output: Path, mode: String): Unit = {
    Files.createDirectories(output)
    val original = SpinalConfig(
      targetDirectory = output.toString,
      oneFilePerComponent = false,
      headerWithDate = false,
      headerWithRepoHash = true,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING, resetKind = SYNC, resetActiveLevel = HIGH)
    )
    original.netlistFileName = "GeneralExpressionInlining.v"
    val selected = mode match {
      case "default" => original
      case "enabled" => MorphWireAssignmentPasses(original, enabled = true)
      case "disabled" => MorphWireAssignmentPasses(original, enabled = false)
      case other => throw new IllegalArgumentException("Unknown mode: " + other)
    }
    val report = MorphVerilog(selected) {
      new GeneralExpressionInlining(
        HdlBool.param("PPC4", default = false).asElabBool.toElabInt * 3 + 1)
    }
    println(report.generatedSourcesPaths.mkString("\n"))
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Usage: GeneralExpressionInliningArtifactWriter <output-directory>")
    for (round <- Vector("first", "repeat"); mode <- Vector("default", "enabled", "disabled"))
      generate(Paths.get(args(0), round, mode), mode)
  }
}
