package nativeapplication

import java.nio.file.{Files, Path, Paths}
import morphhdl.{MorphSignedCasts, MorphSignedDeclarations, MorphVerilog}
import morphhdl.frontend.HdlInt
import spinal.core._

/** One ordinary component source for the independent concrete reference and
  * the parameterized candidate. The divisor is constrained only by the test
  * harness; no validity assumption is embedded in the DUT.
  */
object PureSIntCastFixture {
  final class Top(width: HdlInt) extends Component {
    setDefinitionName("PureSIntCasts")
    val clk = in(Bool())
    val enable = in(Bool())
    val amount = in(UInt(3 bits))
    val a, b, c, divisor = in(SInt(width bits))
    val sum, difference, quotient, remainder, negative = out(SInt(width bits))
    val shiftConstant, shiftVariable, nestedShift, nestedDivision, nestedRemainder = out(SInt(width bits))
    val product, nestedProduct, negatedProduct = out(SInt((width + width) bits))
    val nestedTriple = out(SInt((width + width + width) bits))
    val less, lessEqual, greater, greaterEqual, nestedLess = out(Bool())
    val registered = out(SInt(width bits))
    val registeredProduct = out(SInt((width + width) bits))

    val localSum = (a + b).setName("local_sum")
    sum := localSum
    difference := a - b
    product := a * b
    quotient := a / divisor
    remainder := a % divisor
    negative := -a
    shiftConstant := a |>> 1
    shiftVariable := a |>> amount
    nestedShift := (a + b) |>> amount
    nestedDivision := (a + b) / divisor
    nestedRemainder := (a - b) % divisor
    // localSum must overflow at WIDTH before multiplication, not at 2*WIDTH.
    nestedProduct := localSum * c
    negatedProduct := (-a) * b
    nestedTriple := ((a + b) * (b - c)) * (-a)
    less := a < b
    lessEqual := a <= b
    greater := a > b
    greaterEqual := a >= b
    nestedLess := -(a + b) < (b - c)
    val area = new ClockingArea(ClockDomain(clock = clk)) {
      val saved = Reg(SInt(width bits)).setName("saved")
      when(enable) { saved := a - b }
    }
    registered := area.saved
    registeredProduct := area.saved * c
  }

  final class Boundaries(width: HdlInt) extends Component {
    setDefinitionName("SIntCastBoundaries")
    val a, b = in(SInt(width bits))
    val raw = in(Bits(width bits))
    val choose = in(Bool())
    val amount = in(UInt(2 bits))
    val mixedSum, muxSum = out(SInt(width bits))
    val literalSum = out(SInt(5 bits))
    val fixedA, fixedB = in(SInt(5 bits))
    val widenedSum = out(SInt(10 bits))
    val concatenatedSum = out(SInt((width + width) bits))
    val selectedSum = out(SInt(1 bits))
    val unsignedShift = out(Bits(width bits))
    val equal = out(Bool())
    literalSum := fixedA + S(-1, 5 bits)
    mixedSum := raw.asSInt + a
    muxSum := Mux(choose, a, b) + a
    widenedSum := fixedA.resize(10) + fixedB.resize(10)
    concatenatedSum := (raw ## raw).asSInt + (raw ## raw).asSInt
    selectedSum := a(0 downto 0) + b(0 downto 0)
    unsignedShift := a.asBits |>> amount
    equal := a === b
  }
}

object PureSIntCastArtifactWriter {
  private def parameter: HdlInt = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
  def config(output: Path, cut: Boolean = true): SpinalConfig = {
    val value = SIntSignedDeclarationsArtifactWriter.config(output)
    value.copy(cutLongExpressions = cut)
  }
  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 1, "expected one output directory")
    val root = Paths.get(arguments(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    for (width <- Vector(1, 5, 8, 32)) {
      val path = root.resolve(s"fixed-$width.v")
      SpinalVerilog(config(path))(new PureSIntCastFixture.Top(HdlInt.literal(width)))
      SIntSignedDeclarationsArtifactWriter.canonicalNative(path)
      val boundary = root.resolve(s"boundary-fixed-$width.v")
      SpinalVerilog(config(boundary))(new PureSIntCastFixture.Boundaries(HdlInt.literal(width)))
      SIntSignedDeclarationsArtifactWriter.canonicalNative(boundary)
    }
    MorphVerilog(config(root.resolve("disabled.v")))(new PureSIntCastFixture.Top(parameter))
    MorphVerilog(MorphSignedDeclarations.enable(config(root.resolve("declarations.v"))))(
      new PureSIntCastFixture.Top(parameter))
    MorphVerilog(MorphSignedCasts.enable(config(root.resolve("pure-true.v"))))(
      new PureSIntCastFixture.Top(parameter))
    MorphVerilog(MorphSignedCasts.enable(config(root.resolve("boundaries.v"))))(
      new PureSIntCastFixture.Boundaries(parameter))
    MorphVerilog(MorphSignedCasts.enable(config(root.resolve("baseline-clean.v"))))(
      SIntSignedVerilogBaselineFixture.parameterized())
    MorphVerilog(MorphSignedCasts.enable(config(root.resolve("declaration-fixture-clean.v"))))(
      new SIntSignedDeclarationsFixture.Top(parameter))
    SIntSignedDeclarationsArtifactWriter.main(Array(root.resolve("inherited").toString))
    SIntSignedVerilogBaselineArtifactWriter.main(Array(root.resolve("baseline").toString))
    SIntSignedVerilogNestedArtifactWriter.main(Array(root.resolve("baseline").toString))
  }
}
