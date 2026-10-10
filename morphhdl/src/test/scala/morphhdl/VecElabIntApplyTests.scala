package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import spinal.core._

/** This object deliberately has no frontend imports. */
private object OrdinaryVecIndexImports {
  def constant[T <: BaseType](vector: Vec[T], index: ElabInt): T = vector(index)
  def nativeInt[T <: Data](vector: Vec[T]): T = vector(0)
  def nativeRange[T <: Data](vector: Vec[T]): Vec[T] = vector(0 until 2)
  def hardware[T <: Data](vector: Vec[T], index: UInt): T = vector(index)
}

class VecElabIntApplyTests extends ElabVecSelectionTests {
  override protected def select[T <: BaseType](vector: Vec[T], index: ElabInt): T =
    OrdinaryVecIndexImports.constant(vector, index)

  // Both extension families must remain available in one ordinary source file.
  private def withStructuralImports[T <: BaseType](vector: Vec[T], index: ElabInt): T = {
    import morphhdl.frontend.StructuralVecOps
    vector(index)
  }

  for(kind <- Seq("bool", "bits", "uint", "sint"); symbolic <- Seq(false,true); unpacked <- Seq(false,true)) {
    test(s"shorthand matches legacy RTL kind=$kind symbolic=$symbolic unpacked=$unpacked") {
      def generate(short: Boolean): String = {
        val dir=Files.createTempDirectory("vec-elab-apply-")
        MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
          preserveConstantVecs=true,preserveConstantLoops=true,
          vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector))(new Component {
          setDefinitionName("Top")
          val depth=if(symbolic) morphhdl.frontend.HdlInt.param("DEPTH",3,2,8).asElabInt else ElabInt.literal(4)
          val width=if(kind=="bool") ElabInt.literal(1) else if(symbolic) morphhdl.frontend.HdlInt.param("WIDTH",5,1,64).asElabInt else ElabInt.literal(8)
          def leaf: BaseType = kind match {
            case "bool" => Bool()
            case "bits" => Bits(width bits)
            case "uint" => UInt(width bits)
            case "sint" => SInt(width bits)
          }
          val lanes=in(Vec(leaf,depth))
          val first,middle,last=out(leaf)
          val parity=out Bool()
          parity:=lanes.asBits.xorR
          def read(index:ElabInt):BaseType=if(short) withStructuralImports(lanes,index) else ElabVec.select(lanes,index)
          first.assignFrom(read(ElabInt.literal(0)))
          middle.assignFrom(read(depth/2))
          last.assignFrom(read(depth-1))
        })
        new String(Files.readAllBytes(dir.resolve("Top.v")),UTF_8)
      }
      assert(generate(true)==generate(false))
    }
  }
}
