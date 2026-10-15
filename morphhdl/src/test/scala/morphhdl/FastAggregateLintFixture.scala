package morphhdl

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets.UTF_8
import spinal.core._

/** Small elaboration-only corpus for the pre-qualification lint gate. */
object FastAggregateLintFixture {
  class FlatArray(depth: Int, loops: Boolean) extends Component {
    setDefinitionName("Top")
    val x = in(Vec(Bits(8 bits), depth))
    val y = out(Vec(Bits(8 bits), depth))
    val values = Vec(Bits(8 bits), depth).dontSimplifyIt()
    if(loops) ElabFiniteRange.foreach(ElabInt.literal(depth), "lanes") { i => i(values) := ~i(x) }
    else for(col <- 0 until depth) values(col) := ~x(col)
    y := values
  }
  class NestedArray(depth: Int) extends Component {
    setDefinitionName("Top")
    val x = in(Vec.fill(2, depth)(Bits(8 bits)))
    val y = out(Vec.fill(2, depth)(Bits(8 bits)))
    val values = Vec.fill(2, depth)(Bits(8 bits))
    values.foreach(_.foreach(_.dontSimplifyIt()))
    values := x
    y := values
  }
  class DerivedPorts extends Component {
    setDefinitionName("Top")
    val busBits: ElabInt = morphhdl.frontend.HdlInt.param("BUS_LOG2_BYTES", 3, 2, 5).asElabInt.pow2 * 8
    val busBytes: ElabInt = busBits / 8
    val x = in Bits(busBytes bits)
    val y = out Bits(busBytes bits)
    val bodyWire = Bits(busBytes bits).dontSimplifyIt()
    bodyWire := x
    y := bodyWire
  }
  class NativeMask extends Component {
    setDefinitionName("Top")
    val busBytes: ElabInt = morphhdl.frontend.HdlInt.param("BUS_BYTES", 8, 4, 32).asElabInt
    val keep = in Bits(busBytes bits)
    val legal = out Bool()
    val keepWide = keep.asUInt.resize(busBytes + 1)
    legal := keepWide =/= 0 && (keepWide & (keepWide + 1)) === 0
  }
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "output directory required")
    val root = Paths.get(args(0)).toAbsolutePath
    Files.createDirectories(root)
    val entries = for {
      depth <- Seq(1, 3)
      nested <- Seq(false, true)
      loops <- (if(nested) Seq(false) else Seq(false, true))
      unpacked <- Seq(false, true)
    } yield {
      val name = s"depth-$depth-nested-$nested-generate-$loops-unpacked-$unpacked"
      val dir = root.resolve(name)
      val config = MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false),
        preserveConstantVecs = true, preserveConstantLoops = loops,
        vecLayout = if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)
      MorphVerilog(config)(if(nested) new NestedArray(depth) else new FlatArray(depth, loops))
      name
    }
    val aliases = for(preserve <- Seq(false, true)) yield {
      val name = s"derived-ports-preserve-$preserve"
      val dir = root.resolve(name)
      val config = MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false),
        preserve, preserve, if(preserve) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)
      MorphVerilog(config)(new DerivedPorts)
      val rtl = new String(Files.readAllBytes(dir.resolve("Top.v")), UTF_8)
      require(!rtl.take(rtl.indexOf(");")).contains("BUS_BYTES"), "body alias leaked into ANSI port")
      require(rtl.contains("[BUS_BYTES-1:0] bodyWire"), "body width alias was lost")
      name
    }
    val masks = for(width <- Seq(4, 8, 32)) yield {
      val name = s"native-mask-width-$width"
      MorphVerilog(SpinalConfig(targetDirectory = root.resolve(name).toString, headerWithDate = false))(new NativeMask)
      name
    }
    Files.write(root.resolve("cases.txt"), ((entries ++ aliases ++ masks).mkString("\n") + "\n").getBytes(UTF_8))
  }
}
