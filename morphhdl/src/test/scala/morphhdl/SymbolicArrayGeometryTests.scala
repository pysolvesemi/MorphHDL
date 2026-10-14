package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.HdlInt

object NativeWidthRecursiveArtifactWriter {
  def generate(dir: java.nio.file.Path, default: Int, masked: Boolean, difference: Boolean): String = {
    val config=SpinalConfig(targetDirectory=dir.toString,headerWithDate=false,
      flags=scala.collection.mutable.HashSet[Any](VerilogAggregateOptions(
        preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=VerilogAggregateOptions.UnpackedArray)))
    config.netlistFileName="tree.v"
    MorphVerilog(config) {
      new nativeapplication.NativeWidthRecursiveTree(
        HdlInt.param("COUNT",default,1,16).asElabInt,HdlInt.param("DATA_WIDTH",36,1,64).asElabInt,
        if(masked) Some(HdlInt.param("PIPELINE_MASK",0,0,15).asElabInt) else None,difference)
    }
    new String(Files.readAllBytes(dir.resolve("tree.v")),"UTF-8")
  }
  def main(args: Array[String]): Unit =
    for(default <- Seq(1,8); masked <- Seq(false,true); difference <- Seq(false,true))
      generate(java.nio.file.Paths.get(args(0)).resolve(s"default-$default/masked-$masked/difference-$difference"),default,masked,difference)
}

class SymbolicArrayGeometryTests extends AnyFunSuite {
  private def config(dir: java.nio.file.Path) = SpinalConfig(targetDirectory=dir.toString,
    headerWithDate=false, flags=scala.collection.mutable.HashSet[Any](VerilogAggregateOptions(
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=VerilogAggregateOptions.UnpackedArray)))

  for(default <- Seq(1,8); masked <- Seq(false,true); difference <- Seq(false,true))
  test(s"recursive word widths remain symbolic default=$default masked=$masked difference=$difference") {
    def generate() = NativeWidthRecursiveArtifactWriter.generate(Files.createTempDirectory("recursive-word-width-"),default,masked,difference)
    val rtl=generate()
    assert(rtl==generate())
    val name=if(difference) "WidthDifferenceTree" else "WidthSumTree"
    assert(("(?m)^module "+name+"\\b").r.findAllIn(rtl).size==1,rtl)
    assert(rtl.contains("DATA_WIDTH") && rtl.contains(".DATA_WIDTH"),rtl)
    assert(rtl.contains("begin : g_combine_"),rtl)
    assert(!rtl.contains("combined_1_view"),rtl)
  }

  for(default <- Seq(1,8); wrong <- Seq(false,true))
  test(s"independent count and word width packing default=$default wrong=$wrong") {
    val dir=Files.createTempDirectory("independent-word-width-")
    def generate(): Unit = {
      MorphVerilog(config(dir)) { new Component {
        setDefinitionName("WordGeometry")
        @dontName val count=HdlInt.param("COUNT",default,1,16).asElabInt
        @dontName val width=HdlInt.param("DATA_WIDTH",36,1,64).asElabInt
        val terms=in Bits((count*width+(if(wrong) 1 else 0)) bits)
        val lanes=Vec(UInt(width bits),count)
        lanes.assignFromBits(terms)
        val result=out Bits((count*width) bits)
        result := lanes.asBits
      } }
    }
    if(wrong) intercept[MorphVerilogException](generate())
    else {
      generate()
      val rtl=new String(Files.readAllBytes(dir.resolve("WordGeometry.v")),"UTF-8")
      assert(rtl.contains("DATA_WIDTH") && rtl.contains("COUNT"),rtl)
      val instances=(for(n <- Seq(1,2,3,8,16); w <- Seq(1,8,32,36,64)) yield {
        val size=n*w; val name=s"r_${n}_$w"
        s"wire [${size-1}:0] $name; WordGeometry #(.COUNT($n),.DATA_WIDTH($w)) d_${n}_$w(.terms(data[${size-1}:0]),.result($name));"
      }).mkString("\n")
      val checks=(for(n <- Seq(1,2,3,8,16); w <- Seq(1,8,32,36,64)) yield
        s"if(r_${n}_$w !== data[${n*w-1}:0]) $$fatal;").mkString("\n")
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg [1023:0] data; integer k,j;
$instances
initial begin for(k=0;k<32;k=k+1) begin
for(j=0;j<32;j=j+1) data[j*32 +: 32]=$$random;
if(k==0) data=0; if(k==1) data=~1024'd0; if(k==2) data='bx; if(k==3) data='bz;
#1; $checks
end $$display("PASS"); $$finish; end endmodule
""".getBytes("UTF-8"))
      for(command <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","WordGeometry.v","tb.v"),Seq("vvp","sim"))) {
        val (code,log)=Increment66ToolEvidence.run(dir,command); assert(code==0,s"$dir $command\n$log")
      }
    }
  }

  for(default <- Seq(1,8,16); mode <- Seq("valid","constant","nested","unguarded","overlap","missing"))
  test(s"guarded packed writes use their active width default=$default mode=$mode") {
    val dir=Files.createTempDirectory("guarded-packed-width-")
    def generate(): Unit = {
      MorphVerilog(config(dir)) { new Component {
        setDefinitionName("GuardedWords")
        @dontName val count=ElabFormalParameter(HdlInt.param("COUNT",default,1,16).asElabInt,"COUNT",1,16)
        @dontName val flag=if(mode=="nested") Some(ElabFormalParameter(
          HdlInt.param("FLAG",0,0,1).asElabInt,"FLAG",0,1)) else None
        val data=in Bits(36 bits)
        val result=out Bits((((count+1)/2)*36) bits)
        for(i <- 0 until 8) {
          def assign(value: Bits): Unit = flag match {
            case Some(selected) => ElabControl.selectSymbolic(selected.elabEq(1),"Choice",1) {
              result(i*36,36 bits) := ~value
            } { result(i*36,36 bits) := value }
            case None => result(i*36,36 bits) := value
          }
          def write(): Unit = ElabControl.selectSymbolic(count > 2*i+1,"Words",2) {
            assign(if(mode=="constant") B(1,36 bits) else data)
            if(mode=="overlap") result(i*36,36 bits) := ~data
          } {
            if(mode!="missing") assign(if(mode=="constant") B(0,36 bits) else ~data)
          }
          if(mode=="unguarded") write()
          else ElabControl.generateSymbolic(count > 2*i,"Words",1)(write())
        }
      } }
    }
    if(!Set("valid","constant","nested")(mode)) intercept[MorphVerilogException](generate()) else {
      generate()
      val rtl=new String(Files.readAllBytes(dir.resolve("GuardedWords.v")),"UTF-8")
      generate()
      assert(rtl==new String(Files.readAllBytes(dir.resolve("GuardedWords.v")),"UTF-8"))
      val flags=if(mode=="nested") Seq(0,1) else Seq(0)
      val instances=(for(n <- 1 to 16; f <- flags) yield {
        val size=((n+1)/2)*36
        val binding=if(mode=="nested") s",.FLAG($f)" else ""
        s"wire [${size-1}:0] r${n}_$f; GuardedWords #(.COUNT($n)$binding) d${n}_$f(.data(data),.result(r${n}_$f));"
      }).mkString("\n")
      val checks=(for(n <- 1 to 16; f <- flags; i <- 0 until (n+1)/2) yield {
        val pair=n>2*i+1
        val value=if(mode=="constant") (if(pair) "36'd1" else "36'd0") else if(pair) "data" else "~data"
        val expected=if(f==1) s"~($value)" else value
        s"""if(r${n}_$f[${i*36} +: 36] !== ($expected)) $$fatal(1,"count $n flag $f word $i");"""
      }).mkString("\n")
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg [35:0] data; integer k;
$instances
initial begin
for(k=0;k<32;k=k+1) begin
data={$$random,$$random};
if(k==0) data=0; if(k==1) data=~36'd0; if(k==2) data='bx; if(k==3) data='bz;
#1; $checks
end $$display("PASS guarded packed words"); $$finish;
end endmodule
""".getBytes("UTF-8"))
      val commands=Seq(
        Seq("iverilog","-g2001","-s","tb","-o","sim","GuardedWords.v","tb.v"),Seq("vvp","sim"),
        Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","GuardedWords","GuardedWords.v")) ++
        Seq(1,3,16).map(n => Seq("yosys","-Q","-T","-p",
          s"read_verilog -defer GuardedWords.v; chparam -set COUNT $n GuardedWords; hierarchy -check -top GuardedWords; proc; select -assert-none t:$$dlatch; synth; check -assert"))
      commands.foreach { command =>
        val (code,log)=Increment66ToolEvidence.run(dir,command); assert(code==0,s"$dir $command\n$log")
      }
    }
  }
}
