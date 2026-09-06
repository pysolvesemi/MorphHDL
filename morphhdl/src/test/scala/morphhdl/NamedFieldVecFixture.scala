package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.core.internals.{TypeBool, TypeSInt, TypeUInt}
import spinal.lib._

/** The candidate uses unchanged Vec[Bundle] source. Concrete references below
  * deliberately use native Int Vec constructors and ordinary SpinalVerilog;
  * qualification supplies wiring-only adapters for exploded native leaves.
  */
object NamedFieldVecFixture {
  final case class Metadata() extends Bundle { val valid = Bool() }
  final case class Pixel(width: ElabInt, blueWidth: ElabInt) extends Bundle {
    val red = UInt(width bits)
    val green = UInt(width bits)
    val blue = SInt(blueWidth bits)
    val metadata = Metadata()
  }
  final case class NativePixel(width: Int, blueWidth: Int) extends Bundle {
    val red = UInt(width bits)
    val green = UInt(width bits)
    val blue = SInt(blueWidth bits)
    val metadata = Metadata()
    require(red.getWidth == width && green.getWidth == width && blue.getWidth == blueWidth)
    require(red.getTypeObject == TypeUInt && green.getTypeObject == TypeUInt)
    require(blue.getTypeObject == TypeSInt && metadata.valid.getTypeObject == TypeBool)
  }

  final class Basic(width: ElabInt, blueWidth: ElabInt, count: ElabInt) extends Component {
    setDefinitionName("NamedFieldVecBasic")
    val pixels = in(Vec(Pixel(width, blueWidth), count))
    val result = out(Vec(Pixel(width, blueWidth), count))
    val index = in(UInt(5 bits))
    val first = out(Pixel(width, blueWidth))
    val selected = out(Pixel(width, blueWidth))
    val storage = Vec(Pixel(width, blueWidth), count).setName("storage").dontSimplifyIt()
    storage := pixels
    result := storage
    first := storage(0)
    selected := storage(index)
  }

  final class Access(width: ElabInt, blueWidth: ElabInt, count: ElabInt) extends Component {
    setDefinitionName("NamedFieldVecAccess")
    val pixels = in(Vec(Pixel(width, blueWidth), count))
    val alternate = in(Vec(Pixel(width, blueWidth), count))
    val index = in(UInt(64 bits))
    val writeEnable = in(Bool())
    val staticEnable = in(Bool())
    val staticGreen = in(UInt(width bits))
    val staticBlue = in(SInt(blueWidth bits))
    val replacement = in(Pixel(width, blueWidth))
    val result = out(Vec(Pixel(width, blueWidth), count))
    val alternateResult = out(Vec(Pixel(width, blueWidth), count))
    val first = out(Pixel(width, blueWidth))
    val selected = out(Pixel(width, blueWidth))
    val coupled = out(UInt(width bits))
    val signedLess = out(Bool())
    // asBits carries the native declaration-order packing provenance; deriving
    // a host-side sum of independent roots would not be an admissible width.
    val packedBits = out(Bits())
    val restored = out(Vec(Pixel(width, blueWidth), count))
    val storage = cloneOf(pixels).setName("storage").dontSimplifyIt()
    storage := pixels
    when(staticEnable) {
      storage(0).green := staticGreen
      storage(0).blue := staticBlue
    }
    when(writeEnable) { storage(index) := replacement }
    result := storage
    alternateResult := alternate
    first := storage(0)
    selected := storage(index)
    coupled := storage(index).red ^ alternate(index).green
    signedLess := storage(index).blue < replacement.blue
    packedBits := pixels.asBits
    restored.assignFromBits(packedBits)
  }

  final class NativeAccess(width: Int, blueWidth: Int, count: Int) extends Component {
    require(width > 0 && blueWidth > 0 && count > 0 && count <= 17)
    setDefinitionName(s"NativeFieldVecAccess_w${width}_b${blueWidth}_n$count")
    val pixels = in(Vec(NativePixel(width, blueWidth), count))
    val alternate = in(Vec(NativePixel(width, blueWidth), count))
    val index = in(UInt(64 bits))
    val writeEnable = in(Bool())
    val staticEnable = in(Bool())
    val staticGreen = in(UInt(width bits))
    val staticBlue = in(SInt(blueWidth bits))
    val replacement = in(NativePixel(width, blueWidth))
    val result = out(Vec(NativePixel(width, blueWidth), count))
    val alternateResult = out(Vec(NativePixel(width, blueWidth), count))
    val first = out(NativePixel(width, blueWidth))
    val selected = out(NativePixel(width, blueWidth))
    val coupled = out(UInt(width bits))
    val signedLess = out(Bool())
    val packedBits = out(Bits((width * 2 + blueWidth + 1) * count bits))
    val restored = out(Vec(NativePixel(width, blueWidth), count))
    val storage = cloneOf(pixels).setName("storage").dontSimplifyIt()
    // 53f's declared full-width address contract clamps an out-of-domain read
    // to the last element and suppresses an out-of-domain write. Keep the
    // ordinary native Vec algorithm authoritative at every legal index.
    val inRange = index.resize(65) < U(count, 65 bits)
    storage := pixels
    when(staticEnable) {
      storage(0).green := staticGreen
      storage(0).blue := staticBlue
    }
    when(writeEnable && inRange) { storage(index.resized) := replacement }
    result := storage
    alternateResult := alternate
    first := storage(0)
    selected := Mux(inRange, storage(index.resized), storage(count - 1))
    val other = Mux(inRange, alternate(index.resized), alternate(count - 1))
    coupled := selected.red ^ other.green
    signedLess := selected.blue < replacement.blue
    packedBits := pixels.asBits
    restored.assignFromBits(packedBits)
  }

  final case class Envelope(width: ElabInt, blueWidth: ElabInt, inner: ElabInt) extends Bundle {
    val tag = Bits(3 bits)
    val colors = Vec(Pixel(width, blueWidth), inner)
  }
  final case class NativeEnvelope(width: Int, blueWidth: Int, inner: Int) extends Bundle {
    val tag = Bits(3 bits)
    val colors = Vec(NativePixel(width, blueWidth), inner)
  }
  final class Nested(width: ElabInt, blueWidth: ElabInt, count: ElabInt, inner: ElabInt) extends Component {
    setDefinitionName("NamedFieldVecNested")
    val pixels = in(Vec(Envelope(width, blueWidth, inner), count))
    val result = out(Vec(Envelope(width, blueWidth, inner), count))
    val outerIndex = in(UInt(5 bits))
    val innerIndex = in(UInt(2 bits))
    val first = out(Pixel(width, blueWidth))
    val selected = out(Pixel(width, blueWidth))
    val selectedTag = out(Bits(3 bits))
    val packedBits = out(Bits())
    val restored = out(Vec(Envelope(width, blueWidth, inner), count))
    val storage = HardType(pixels)().setName("storage").dontSimplifyIt()
    storage := pixels
    result := storage
    first := storage(0).colors(0)
    selected := storage(outerIndex).colors(innerIndex)
    selectedTag := storage(outerIndex).tag
    packedBits := pixels.asBits
    restored.assignFromBits(packedBits)
  }
  final class NativeNested(width: Int, blueWidth: Int, count: Int, inner: Int) extends Component {
    setDefinitionName(s"NativeFieldVecNested_w${width}_b${blueWidth}_n${count}_i$inner")
    val pixels = in(Vec(NativeEnvelope(width, blueWidth, inner), count))
    val result = out(Vec(NativeEnvelope(width, blueWidth, inner), count))
    val outerIndex = in(UInt(5 bits))
    val innerIndex = in(UInt(2 bits))
    val first = out(NativePixel(width, blueWidth))
    val selected = out(NativePixel(width, blueWidth))
    val selectedTag = out(Bits(3 bits))
    val packedBits = out(Bits((3 + inner * (width * 2 + blueWidth + 1)) * count bits))
    val restored = out(Vec(NativeEnvelope(width, blueWidth, inner), count))
    val storage = HardType(pixels)().setName("storage").dontSimplifyIt()
    storage := pixels
    result := storage
    first := storage(0).colors(0)
    val outerInRange = outerIndex.resize(6) < U(count, 6 bits)
    val innerInRange = innerIndex.resize(3) < U(inner, 3 bits)
    val row = Mux(outerInRange, storage(outerIndex.resized), storage(count - 1))
    selected := Mux(innerInRange, row.colors(innerIndex.resized), row.colors(inner - 1))
    selectedTag := row.tag
    packedBits := pixels.asBits
    restored.assignFromBits(packedBits)
  }

  final class Child(width: ElabInt, blueWidth: ElabInt, count: ElabInt) extends Component {
    setDefinitionName("NamedFieldVecChild")
    val pixels = in(Vec(Pixel(width, blueWidth), count))
    val result = out(Vec(Pixel(width, blueWidth), count))
    result := pixels
  }
  final class Storage(width: ElabInt, blueWidth: ElabInt, count: ElabInt) extends Component {
    setDefinitionName("NamedFieldVecStorage")
    val clk = in(Bool())
    val enable = in(Bool())
    val pixels = in(Vec(Pixel(width, blueWidth), count))
    val alternate = in(Vec(Pixel(width, blueWidth), count))
    val directPixels = in(Vec(Pixel(width, blueWidth), count))
    val result = out(Vec(Pixel(width, blueWidth), count))
    val alternateResult = out(Vec(Pixel(width, blueWidth), count))
    val directResult = out(Vec(Pixel(width, blueWidth), count))
    val left = new Child(width, blueWidth, count)
    val right = new Child(width, blueWidth, count)
    val direct = new Child(width, blueWidth, count)
    left.pixels := pixels
    right.pixels := alternate
    direct.pixels := directPixels
    val cloned = cloneOf(left.result).setName("cloned").dontSimplifyIt()
    val hard = HardType(cloned)().setName("hard").dontSimplifyIt()
    cloned := left.result
    hard := cloned
    val area = new ClockingArea(ClockDomain(clock = clk)) {
      val registers = Reg(HardType(hard)).setName("registers").dontSimplifyIt()
      registers := hard
      val directRegisters = Reg(Vec(Pixel(width, blueWidth), count))
        .setName("directRegisters").dontSimplifyIt()
      when(enable) { directRegisters := direct.result }
    }
    result := area.registers
    alternateResult := right.result
    directResult := area.directRegisters
  }

  final class NativeChild(width: Int, blueWidth: Int, count: Int) extends Component {
    setDefinitionName(s"NativeFieldVecChild_w${width}_b${blueWidth}_n$count")
    val pixels = in(Vec(NativePixel(width, blueWidth), count))
    val result = out(Vec(NativePixel(width, blueWidth), count))
    result := pixels
  }
  final class NativeStorage(width: Int, blueWidth: Int, count: Int) extends Component {
    setDefinitionName(s"NativeFieldVecStorage_w${width}_b${blueWidth}_n$count")
    val clk = in(Bool())
    val enable = in(Bool())
    val pixels = in(Vec(NativePixel(width, blueWidth), count))
    val alternate = in(Vec(NativePixel(width, blueWidth), count))
    val directPixels = in(Vec(NativePixel(width, blueWidth), count))
    val result = out(Vec(NativePixel(width, blueWidth), count))
    val alternateResult = out(Vec(NativePixel(width, blueWidth), count))
    val directResult = out(Vec(NativePixel(width, blueWidth), count))
    val left = new NativeChild(width, blueWidth, count)
    val right = new NativeChild(width, blueWidth, count)
    val direct = new NativeChild(width, blueWidth, count)
    left.pixels := pixels
    right.pixels := alternate
    direct.pixels := directPixels
    val cloned = cloneOf(left.result).setName("cloned").dontSimplifyIt()
    val hard = HardType(cloned)().setName("hard").dontSimplifyIt()
    cloned := left.result
    hard := cloned
    val area = new ClockingArea(ClockDomain(clock = clk)) {
      val registers = Reg(HardType(hard)).setName("registers").dontSimplifyIt()
      registers := hard
      val directRegisters = Reg(Vec(NativePixel(width, blueWidth), count))
        .setName("directRegisters").dontSimplifyIt()
      when(enable) { directRegisters := direct.result }
    }
    result := area.registers
    alternateResult := right.result
    directResult := area.directRegisters
  }

  final class Streams(width: ElabInt, blueWidth: ElabInt, count: ElabInt) extends Component {
    setDefinitionName("NamedFieldVecStreams")
    val source = slave(Stream(Vec(Pixel(width, blueWidth), count)))
    val sink = master(Stream(Vec(Pixel(width, blueWidth), count)))
    val flowSource = slave(Flow(Vec(Pixel(width, blueWidth), count)))
    val flowSink = master(Flow(Vec(Pixel(width, blueWidth), count)))
    sink << source
    flowSink << flowSource
  }

  final class NativeStreams(width: Int, blueWidth: Int, count: Int) extends Component {
    setDefinitionName(s"NativeFieldVecStreams_w${width}_b${blueWidth}_n$count")
    val source = slave(Stream(Vec(NativePixel(width, blueWidth), count)))
    val sink = master(Stream(Vec(NativePixel(width, blueWidth), count)))
    val flowSource = slave(Flow(Vec(NativePixel(width, blueWidth), count)))
    val flowSink = master(Flow(Vec(NativePixel(width, blueWidth), count)))
    sink << source
    flowSink << flowSource
  }

  final class Scalar(width: ElabInt, count: ElabInt) extends Component {
    setDefinitionName("NamedFieldVecScalar")
    val words = in(Vec(UInt(width bits), count))
    val result = out(Vec(UInt(width bits), count))
    val index = in(UInt(5 bits))
    val first = out(UInt(width bits))
    val selected = out(UInt(width bits))
    result := words
    first := words(0)
    selected := words(index)
  }

  final class MixedDirections(width: ElabInt, blueWidth: ElabInt, count: ElabInt) extends Component {
    setDefinitionName("NamedFieldVecMixedDirections")
    val source = Vec(slave(Stream(Pixel(width, blueWidth))), count)
    val sink = Vec(master(Stream(Pixel(width, blueWidth))), count)
    source <> sink
  }

  final class NativeMixedDirections(width: Int, blueWidth: Int, count: Int) extends Component {
    setDefinitionName(s"NativeFieldVecMixedDirections_w${width}_b${blueWidth}_n$count")
    val source = Vec(slave(Stream(NativePixel(width, blueWidth))), count)
    val sink = Vec(master(Stream(NativePixel(width, blueWidth))), count)
    source <> sink
  }

  def parameter(name: String, default: Int, maximum: Int): ElabInt =
    HdlInt.param(name, default, 1, maximum).asElabInt
  def dimensions(): (ElabInt, ElabInt, ElabInt) =
    (parameter("WIDTH", 5, 32), parameter("BLUE_WIDTH", 3, 32), parameter("COUNT", 1, 17))

  def config(directory: Path, file: String): SpinalConfig = {
    Files.createDirectories(directory)
    val value = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, bitVectorWidthMax = 8192)
    value.netlistFileName = file
    value
  }

  def candidate(directory: Path, kind: String, named: Boolean = true): Path = {
    val module = kind match {
      case "basic" => "NamedFieldVecBasic"
      case "scalar" => "NamedFieldVecScalar"
      case "mixed" => "NamedFieldVecMixedDirections"
      case "access" => "NamedFieldVecAccess"
      case "nested" => "NamedFieldVecNested"
      case "storage" => "NamedFieldVecStorage"
      case "streams" => "NamedFieldVecStreams"
    }
    val base = config(directory, module + ".v")
    val selected = if (named) MorphNamedFieldVectors.enable(base) else MorphNamedFieldVectors.disable(base)
    MorphVerilog(MorphSignedCasts.enable(selected)) {
      val (width, blueWidth, count) = dimensions()
      kind match {
        case "basic" => new Basic(width, blueWidth, count)
        case "scalar" => new Scalar(width, count)
        case "mixed" => new MixedDirections(width, blueWidth, count)
        case "access" => new Access(width, blueWidth, count)
        case "nested" => new Nested(width, blueWidth, count, parameter("INNER", 1, 3))
        case "storage" => new Storage(width, blueWidth, count)
        case "streams" => new Streams(width, blueWidth, count)
      }
    }
    directory.resolve(module + ".v")
  }
}

object NamedFieldVecArtifactWriter {
  import NamedFieldVecFixture._

  def main(args: Array[String]): Unit = {
    require(args.length == 1 || args.length == 2,
      "provide a 59c output directory and optional topology or case filter")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    val only = args.lift(1)
    val kinds = Vector("access", "nested", "storage", "streams", "mixed")
    require(only.forall(value => kinds.contains(value) ||
      kinds.exists(kind => value.startsWith(kind + "_")) ||
      Set("basic", "scalar").contains(value)), "unknown 59c topology filter")
    Files.createDirectories(root)
    if (only.exists(Set("basic", "scalar").contains)) {
      val kind = only.get
      candidate(root.resolve(s"candidate/$kind"), kind)
      candidate(root.resolve(s"legacy/$kind"), kind, named = false)
      return
    }
    kinds.filter(kind => only.forall(value => value == kind || value.startsWith(kind + "_"))).foreach { kind =>
      candidate(root.resolve(s"candidate/$kind"), kind)
      if (kind != "mixed") candidate(root.resolve(s"legacy/$kind"), kind, named = false)
    }
    val accessCases = for {
      width <- Vector(1, 5, 8, 32)
      count <- Vector(1, 2, 3, 5, 8, 9, 16, 17)
    } yield ("access", width, if (width == 32) 7 else width + 1, count, 1)
    val nestedCases = for {
      (width, blueWidth) <- Vector((1, 5), (5, 3), (8, 1), (32, 7))
      count <- Vector(1, 3, 5)
      inner <- Vector(1, 2, 3)
    } yield ("nested", width, blueWidth, count, inner)
    val structuralCases = for {
      kind <- Vector("storage", "streams", "mixed")
      (width, blueWidth) <- Vector((1, 5), (5, 3), (8, 1), (32, 7))
      count <- Vector(1, 3, 5, 17)
    } yield (kind, width, blueWidth, count, 1)
    val entries = (accessCases ++ nestedCases ++ structuralCases).map { case (kind, width, blueWidth, count, inner) =>
      val stem = s"${kind}_w${width}_b${blueWidth}_n${count}_i$inner"
      val module = kind match {
        case "access" => s"NativeFieldVecAccess_w${width}_b${blueWidth}_n$count"
        case "nested" => s"NativeFieldVecNested_w${width}_b${blueWidth}_n${count}_i$inner"
        case "storage" => s"NativeFieldVecStorage_w${width}_b${blueWidth}_n$count"
        case "streams" => s"NativeFieldVecStreams_w${width}_b${blueWidth}_n$count"
        case "mixed" => s"NativeFieldVecMixedDirections_w${width}_b${blueWidth}_n$count"
      }
      val directory = root.resolve(s"reference/$stem")
      if (only.forall(value => value == kind || value == stem)) {
        val nativeConfig = config(directory, module + ".v")
        SpinalVerilog(nativeConfig) {
          kind match {
            case "access" => new NativeAccess(width, blueWidth, count)
            case "nested" => new NativeNested(width, blueWidth, count, inner)
            case "storage" => new NativeStorage(width, blueWidth, count)
            case "streams" => new NativeStreams(width, blueWidth, count)
            case "mixed" => new NativeMixedDirections(width, blueWidth, count)
          }
        }
      }
      s"""    {"kind":"$kind","width":$width,"blue_width":$blueWidth,"count":$count,"inner":$inner,"reference_module":"$module","reference_rtl":"reference/$stem/$module.v"}"""
    }
    val manifest = "{\n  \"scope\":\"named-field-vec-native-equivalence\",\n" +
      "  \"candidate_default\":{\"width\":5,\"blue_width\":3,\"count\":1,\"inner\":1},\n" +
      "  \"dimension_order\":\"outer-major-inner-minor-element-zero-low\",\n" +
      "  \"configurations\":[\n" + entries.mkString(",\n") + "\n  ]\n}\n"
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
