package nativeapplication

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.{MorphSignedDeclarations, MorphVerilog}
import morphhdl.frontend.HdlInt
import spinal.core._

/** Width-one-safe ordinary source. No alternate behavioral implementation. */
object SIntSignedDeclarationsFixture {
  final class Top(width: HdlInt) extends Component {
    setDefinitionName("SignedDeclarations")
    val clk = in(Bool())
    val enable = in(Bool())
    val choose = in(Bool())
    val write = in(Bool())
    val address = in(UInt(1 bits))
    val amount = in(UInt(2 bits))
    val a = in(SInt(width bits))
    val b = in(SInt(width bits))
    val raw = in(Bits(width bits))
    val wideIn = in(SInt((width + 1) bits))
    val sum = out(SInt(width bits))
    val product = out(SInt((width + width) bits))
    val negative = out(SInt(width bits))
    val shifted = out(SInt(width bits))
    val selected = out(SInt(width bits))
    val widened = out(SInt((width + 1) bits))
    val regOut = out(SInt(width bits))
    val memOut = out(SInt(width bits))
    val packedBits = out(Bits((width + width) bits))
    val logical = out(Bits(width bits))
    val unsignedProduct = out(UInt((width + width) bits))
    val unsignedLess = out(Bool())
    val signedLess = out(Bool())
    val rawOut = out(Bits(width bits))

    val internalSum = (a + b).setName("internal_sum")
    sum := internalSum
    product := a * b
    negative := -a
    shifted := a |>> amount
    selected := b
    when(choose) { selected := a }
    widened := wideIn
    packedBits := a ## b
    // These conversions need genuine unsigned carriers once a/b are signed.
    logical := a.asBits |>> amount
    unsignedProduct := a.asUInt * b.asUInt
    unsignedLess := a.asUInt < b.asUInt
    signedLess := a < b
    rawOut := raw

    val area = new ClockingArea(ClockDomain(clock = clk)) {
      val accumulator = Reg(SInt(width bits)).setName("accumulator")
      when(enable) { accumulator := internalSum }
      val storage = Mem(SInt(width bits), wordCount = 2).setName("scalar_memory")
      storage.write(address, a, write)
      val readValue = storage.readSync(address, enable, readFirst)
    }
    regOut := area.accumulator
    memOut := area.readValue
  }

  final class Direct(width: HdlInt) extends Component {
    setDefinitionName("SignedDirect")
    val a = in(SInt((width + 1) bits))
    val b = out(SInt((width + 1) bits))
    val bitsIn = in(Bits(width bits))
    val bitsOut = out(Bits(width bits))
    b := a
    bitsOut := bitsIn
  }

  final class SymbolicBundleMemory(width: HdlInt) extends Component {
    val clk = in(Bool())
    val address = in(UInt(1 bits))
    val writeEnable = in(Bool())
    val readEnable = in(Bool())
    val dataIn = in(SInt(width bits))
    val dataOut = out(Bits(width bits))
    val area = new ClockingArea(ClockDomain(clock = clk)) {
      val memory = Mem(new Bundle { val value = SInt(width bits) }, wordCount = 2)
      val word = cloneOf(memory.wordType())
      word.value := dataIn
      memory.write(address, word, writeEnable)
      dataOut := memory.readSync(address, readEnable, readFirst).asBits
    }
  }

  final class Functions(width: HdlInt) extends Component {
    setDefinitionName("SignedFunctions")
    val transportIn = in(Bits(width bits))
    val transportOut = out(Bits(width bits))
    val constantOutput = out(SInt(5 bits))
    transportOut := transportIn
    // Two explicit constant statements take the native no-sensitivity fallback.
    constantOutput.allowOverride
    constantOutput := S(-1, 5 bits)
    constantOutput := S(-2, 5 bits)
  }

  final class FixedScalars(fixedBits: Int, transportWidth: HdlInt) extends Component {
    setDefinitionName("SignedFixedScalars")
    val a = in(SInt(fixedBits bits))
    val b = out(SInt(fixedBits bits))
    val transportIn = in(Bits(transportWidth bits))
    val transportOut = out(Bits(transportWidth bits))
    b := a
    transportOut := transportIn
  }

  final class Surfaces(width: HdlInt, aggregateMemory: Boolean = false) extends Component {
    setDefinitionName(if (aggregateMemory) "SignedBundleSurfaces" else "SignedSurfaces")
    val clk = in(Bool())
    val signedInput = in(SInt(width bits))
    val signedOutput = out(SInt(width bits))
    val analogPort = inout(Analog(SInt(width bits)))
    val enable = in(Bool())
    val writeEnable = in(Bool())
    val address = in(UInt(1 bits))
    val packedMemoryOut = out(Bits(5 bits))
    val scalarMemoryOut = out(SInt(width bits))
    val constantOutput = out(SInt(5 bits))
    signedOutput := signedInput
    // Constant-valued procedural scalar output.
    constantOutput := S(-1, 5 bits)
    when(True) { constantOutput := S(-2, 5 bits) }
    val area = new ClockingArea(ClockDomain(clock = clk)) {
      // The existing native publisher admits one symbolic memory per module.
      // Exercise both element kinds independently, not by bypassing that guard.
      if (aggregateMemory) {
        val aggregateInput = in(SInt(5 bits)).setName("aggregateInput")
        val packedMemory = Mem(new Bundle { val value = SInt(5 bits) }, wordCount = 2)
          .setName("bundle_memory")
        val word = cloneOf(packedMemory.wordType())
        word.value := aggregateInput
        packedMemory.write(address, word, writeEnable)
        packedMemoryOut := packedMemory.readSync(address, enable, readFirst).asBits
        scalarMemoryOut := signedInput
      } else {
        val scalarMemory = Mem(SInt(width bits), wordCount = 2).setName("scalar_memory")
        scalarMemory.write(address, signedInput, writeEnable)
        scalarMemoryOut := scalarMemory.readSync(address, enable, readFirst)
        packedMemoryOut := B(0, 5 bits)
      }
    }
  }
}

object SIntSignedDeclarationsArtifactWriter {
  private val ordinaryHeader =
    """(?s)\A// Generator :[^\n]*\n// Component :[^\n]*\n// Git hash  :[^\n]*\n\n""".r

  def config(output: Path): SpinalConfig = {
    Files.createDirectories(output.getParent)
    val value = SpinalConfig(targetDirectory = output.getParent.toString)
    value.netlistFileName = output.getFileName.toString
    value
  }

  def canonicalNative(output: Path): Unit = {
    val raw = new String(Files.readAllBytes(output), StandardCharsets.UTF_8)
    Files.write(output, ordinaryHeader.replaceFirstIn(raw, "").getBytes(StandardCharsets.UTF_8))
  }

  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 1, "expected one output directory")
    val root = Paths.get(arguments(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    for (width <- Vector(1, 5, 8, 32)) {
      val path = root.resolve(s"fixed-$width.v")
      SpinalVerilog(config(path))(new SIntSignedDeclarationsFixture.Top(HdlInt.literal(width)))
      canonicalNative(path)
    }
    def parameter = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    val functionReference = root.resolve("functions-fixed.v")
    SpinalVerilog(config(functionReference))(
      new SIntSignedDeclarationsFixture.Functions(HdlInt.literal(8)))
    canonicalNative(functionReference)
    MorphVerilog(MorphSignedDeclarations.enable(config(root.resolve("functions.v"))))(
      new SIntSignedDeclarationsFixture.Functions(parameter))
    MorphVerilog(config(root.resolve("disabled.v")))(new SIntSignedDeclarationsFixture.Top(parameter))
    MorphVerilog(MorphSignedDeclarations.enable(config(root.resolve("signed.v"))))(
      new SIntSignedDeclarationsFixture.Top(parameter))
    MorphVerilog(MorphSignedDeclarations.enable(config(root.resolve("direct.v"))))(
      new SIntSignedDeclarationsFixture.Direct(parameter))
    MorphVerilog(MorphSignedDeclarations.enable(config(root.resolve("surfaces.v"))))(
      new SIntSignedDeclarationsFixture.Surfaces(parameter))
    MorphVerilog(MorphSignedDeclarations.enable(config(root.resolve("bundle-surfaces.v"))))(
      new SIntSignedDeclarationsFixture.Surfaces(parameter, aggregateMemory = true))
    MorphVerilog(MorphSignedDeclarations.enable(config(root.resolve("baseline-signed.v"))))(
      SIntSignedVerilogBaselineFixture.parameterized())
  }
}
