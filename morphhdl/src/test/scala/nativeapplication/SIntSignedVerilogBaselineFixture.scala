package nativeapplication

import java.nio.file.{Files, Path, Paths}

import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import spinal.core._

/** One ordinary SpinalHDL source used by both sides of Increment 60's signedness work.
  *
  * Increment 60a intentionally does not change any emitter. The fixed path records the
  * feature-disabled native printer and the parameterized path records the current
  * MorphVerilog printer. Both are expected to contain unsigned declarations and the
  * existing explicit `$signed(...)` expression casts.
  */
object SIntSignedVerilogBaselineFixture {
  final class SignedExternal(width: ElabInt) extends BlackBox {
    setBlackBoxName("SIntCastHeavyExternal")
    addGeneric("WIDTH", width)

    val din = in(SInt(width bits)).setName("din")
    val dout = out(SInt(width bits)).setName("dout")
  }

  final class SignedChild(width: HdlInt) extends Component {
    setDefinitionName("SIntCastHeavyChild")

    val din = in(SInt(width bits)).setName("din")
    val addend = in(SInt(width bits)).setName("addend")
    val dout = out(SInt(width bits)).setName("dout")

    val childSum = (din + addend).resize(width).setName("child_sum")
    dout := childSum
  }

  final class Top(width: HdlInt, extendedWidth: HdlInt) extends Component {
    setDefinitionName("SIntCastHeavyBaseline")

    val clk = in(Bool()).setName("clk")
    val enable = in(Bool()).setName("enable")
    val chooseLeft = in(Bool()).setName("choose_left")
    val writeEnable = in(Bool()).setName("write_enable")
    val address = in(UInt(2 bits)).setName("address")

    val left = in(SInt(width bits)).setName("left")
    val right = in(SInt(width bits)).setName("right")
    val third = in(SInt(width bits)).setName("third")
    val divisor = in(SInt(width bits)).setName("divisor")
    val memoryWriteData = in(SInt(width bits)).setName("memory_write_data")

    val sumOut = out(SInt(width bits)).setName("sum_out")
    val differenceOut = out(SInt(width bits)).setName("difference_out")
    val productOut = out(SInt(width bits)).setName("product_out")
    val quotientOut = out(SInt(width bits)).setName("quotient_out")
    val remainderOut = out(SInt(width bits)).setName("remainder_out")
    val nestedCastOut = out(SInt(width bits)).setName("nested_cast_out")
    val negativeOut = out(SInt(width bits)).setName("negative_out")
    val shiftedOut = out(SInt(width bits)).setName("shifted_out")
    val muxOut = out(SInt(width bits)).setName("mux_out")
    val combinationalOut = out(SInt(width bits)).setName("combinational_out")
    val resizedOut = out(SInt(extendedWidth bits)).setName("resized_out")
    val sliceOut = out(SInt(4 bits)).setName("slice_out")
    val concatOut = out(SInt(8 bits)).setName("concat_out")
    val lessOut = out(Bool()).setName("less_out")
    val greaterOrEqualOut = out(Bool()).setName("greater_or_equal_out")
    val registerOut = out(SInt(width bits)).setName("register_out")
    val proceduralOut = out(SInt(width bits)).setName("procedural_out")
    val memoryOut = out(SInt(width bits)).setName("memory_out")
    val childOut = out(SInt(width bits)).setName("child_out")
    val blackBoxOut = out(SInt(width bits)).setName("blackbox_out")

    val sum = (left + right).resize(width).setName("signed_sum")
    val difference = (left - right).resize(width).setName("signed_difference")
    val product = (left * right).resize(width).setName("signed_product")
    val quotient = (left / divisor).resize(width).setName("signed_quotient")
    val remainder = (left % divisor).resize(width).setName("signed_remainder")

    // Explicit carriers keep the parameterized reconstruction boundary reviewed while
    // the outer operation still captures the old nested `$signed($signed(...))` form.
    val nestedLeft = (left + right).resize(width).setName("nested_left")
    val nestedRight = (third - left).resize(width).setName("nested_right")
    val nested = (nestedLeft + nestedRight).resize(width).setName("nested_signed")

    val negative = (-left).resize(width).setName("signed_negative")
    val shifted = (left >> 2).resize(width).setName("signed_shifted")
    val selected = Mux(chooseLeft, left, right).resize(width).setName("signed_mux")
    val resized = left.resize(extendedWidth).setName("signed_resized")

    val combinational = SInt(width bits).setName("signed_combinational")
    combinational := difference
    when(chooseLeft) {
      combinational := sum
    }

    sumOut := sum
    differenceOut := difference
    productOut := product
    quotientOut := quotient
    remainderOut := remainder
    nestedCastOut := nested
    negativeOut := negative
    shiftedOut := shifted
    muxOut := selected
    combinationalOut := combinational
    resizedOut := resized
    sliceOut := left(3 downto 0)
    concatOut := left(3 downto 0) @@ right(3 downto 0)
    lessOut := left < right
    greaterOrEqualOut := left >= right

    val localClockDomain = ClockDomain(clock = clk)
    val sequential = new ClockingArea(localClockDomain) {
      val signedRegister = Reg(SInt(width bits)).setName("signed_register")
      signedRegister := selected

      val signedProcedural = Reg(SInt(width bits)).setName("signed_procedural")
      when(enable) {
        signedProcedural := nested
      }

      val signedMemory = Mem(SInt(width bits), wordCount = 4)
      signedMemory.setName("signed_memory")
      signedMemory.write(
        address = address,
        data = memoryWriteData,
        enable = writeEnable
      )
      val signedMemoryRead = signedMemory.readSync(address).setName("signed_memory_read")
    }

    registerOut := sequential.signedRegister
    proceduralOut := sequential.signedProcedural
    memoryOut := sequential.signedMemoryRead

    val child = new SignedChild(width)
    child.setName("signed_child")
    child.din := left
    child.addend := third
    childOut := child.dout

    val external = new SignedExternal(width)
    external.setName("signed_external")
    external.din := selected
    blackBoxOut := external.dout
  }

  def fixed(width: Int = 8): Top = {
    require(width >= 8, "the baseline fixture requires at least eight bits")
    new Top(
      HdlInt.literal(BigInt(width)),
      HdlInt.literal(BigInt(width + 3))
    )
  }

  def parameterized(): Top = {
    val width = HdlInt.param("WIDTH", default = 8, min = 8, max = 32)
    val extendedWidth = width + 3
    new Top(width, extendedWidth)
  }
}

object SIntSignedVerilogBaselineArtifactWriter {
  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 1, "expected one output directory")
    val directory = Paths.get(arguments(0)).toAbsolutePath.normalize()
    Files.createDirectories(directory)

    emitFixed(directory.resolve("sint_cast_heavy_fixed.v"))
    emitParameterized(directory.resolve("sint_cast_heavy_parameterized.v"))
  }

  private def emitFixed(output: Path): Unit = {
    val config = SpinalConfig(targetDirectory = output.getParent.toString)
    config.netlistFileName = output.getFileName.toString
    SpinalVerilog(config)(SIntSignedVerilogBaselineFixture.fixed())
    require(Files.isRegularFile(output), s"missing fixed baseline artifact $output")
  }

  private def emitParameterized(output: Path): Unit = {
    val config = SpinalConfig(targetDirectory = output.getParent.toString)
    config.netlistFileName = output.getFileName.toString
    MorphVerilog(config)(SIntSignedVerilogBaselineFixture.parameterized())
    require(Files.isRegularFile(output), s"missing parameterized baseline artifact $output")
  }
}
