package nativeapplication

import java.nio.file.{Files, Path, Paths}

import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.{HdlBool, HdlInt}
import spinal.core._

/** All candidates use the production generator and typed construction APIs. */
object BooleanWidthNormalizationArtifactWriter {
  final class PassThrough(width: ElabInt, definition: String) extends Component {
    setDefinitionName(definition)
    val dataIn = in Bits(width bits)
    val dataOut = out Bits(width bits)
    dataOut := dataIn
  }

  private def config(output: Path, passes: String): SpinalConfig = {
    Files.createDirectories(output)
    val value = SpinalConfig(
      targetDirectory = output.toString,
      oneFilePerComponent = false,
      headerWithDate = false,
      headerWithRepoHash = true
    )
    value.netlistFileName = "generated.v"
    if (passes == "disabled") MorphWireAssignmentPasses(value, enabled = false)
    else value
  }

  private def flag(default: Boolean = false): ElabBool =
    HdlBool.param("PPC4", default = default).asElabBool

  private def mode: ElabInt =
    HdlInt.param("MODE", default = 0, min = 0, max = 3).asElabInt

  private def writeRound(output: Path): Unit = {
    for (passes <- Vector("default", "disabled")) {
      def write(name: String)(top: => Component): Unit =
        MorphVerilog(config(output.resolve(passes).resolve(name), passes))(top)

      write("direct") { new PassThrough(flag().toElabInt * 3 + 1, "Wa11BooleanWidthDirect") }
      write("true-default") { new PassThrough(flag(true).toElabInt * 3 + 1, "Wa11BooleanWidthTrueDefault") }
      write("negated") { new PassThrough((!flag()).toElabInt * 3 + 1, "Wa11BooleanWidthNegated") }
      write("frontend-negated") {
        val predicate = !HdlBool.param("PPC4", default = false)
        new PassThrough(predicate.asElabBool.toElabInt * 3 + 1, "Wa11BooleanWidthFrontendNegated")
      }
      write("repeated") {
        var integer = flag().toElabInt
        (1 to 8).foreach { _ => integer = integer.elabEq(1).toElabInt }
        new PassThrough(integer * 3 + 1, "Wa11BooleanWidthRepeated")
      }
      write("compound") {
        val value = mode
        val predicate = (value >= 1) && !(value >= 3)
        new PassThrough(predicate.toElabInt * 3 + 1, "Wa11BooleanWidthCompound")
      }
      write("frontend-compound") {
        val value = HdlInt.param("MODE", default = 0, min = 0, max = 3)
        val predicate = (value >= HdlInt.literal(1)) && !(value >= HdlInt.literal(3))
        new PassThrough(predicate.asElabBool.toElabInt * 3 + 1, "Wa11BooleanWidthFrontendCompound")
      }
      write("frontend-address-helper") {
        val depth = HdlInt.param("DEPTH", default = 1, min = 1, max = 4)
        val predicate = depth.addressWidth.hdlEq(HdlInt.literal(2))
        new PassThrough(predicate.asElabBool.toElabInt * 3 + 1, "Wa11BooleanWidthFrontendAddressHelper")
      }
      write("zero-roundtrip") {
        var integer = flag().toElabInt
        (1 to 8).foreach { _ => integer = integer.elabEq(0).toElabInt }
        new PassThrough(integer * 3 + 1, "Wa11BooleanWidthZeroRoundtrip")
      }
      write("integer-domain") {
        val value = HdlInt.param("INTEGER_VALUE", default = 0, min = 0, max = 3).asElabInt
        new PassThrough(value.elabEq(1).toElabInt * 3 + 1, "Wa11BooleanWidthIntegerDomain")
      }
      write("signed-arithmetic") {
        val integer = flag().toElabInt
        new PassThrough(ElabInt.literal(1) - integer * -3, "Wa11BooleanWidthSignedArithmetic")
      }
      write("signed-compare") {
        val integer = flag().toElabInt
        new PassThrough(((integer - 2) < 0).toElabInt * 3 + 1, "Wa11BooleanWidthSignedCompare")
      }
      write("signed-minimum") {
        val negative = flag().toElabInt * Int.MinValue
        new PassThrough((negative < 0).toElabInt * 3 + 1, "Wa11BooleanWidthSignedMinimum")
      }
      write("signed-maximum") {
        val positive = flag().toElabInt * Int.MaxValue
        new PassThrough((positive > 0).toElabInt * 3 + 1, "Wa11BooleanWidthSignedMaximum")
      }
      write("integer-sizing") {
        val integer = (mode >= 2).toElabInt
        val doubled = integer + integer
        new PassThrough((doubled > integer).toElabInt * 3 + 1, "Wa11BooleanWidthIntegerSizing")
      }
      write("child") { new BooleanWidthNormalizationChildFixture.Top(flag().toElabInt * 3 + 1) }
    }
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Usage: BooleanWidthNormalizationArtifactWriter <output-directory>")
    val output = Paths.get(args(0)).toAbsolutePath.normalize
    Vector("first", "repeat").foreach(round => writeRound(output.resolve(round)))
  }
}
