package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import _root_.spinal.core.ElabInt
import spinal.lib._
import morphhdl.frontend.{HdlInt,HdlIntRangeStart,StructuralVecOps}

class CompactGenerateLabelTests extends AnyFunSuite {
  private def generate(packed:Boolean) = {
    val dir=Files.createTempDirectory("compact-generate-label-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,
      vecLayout=if(packed) MorphAggregateOptions.PackedVector else MorphAggregateOptions.UnpackedArray))(new Component {
      setDefinitionName("Labels")
      val x=in Vec(Bits(8 bits),ElabInt.literal(3))
      val biases=in Vec(Bits(8 bits),2)
      val g_GEN_1=in Bits(8 bits)
      val y=out Vec(Bits(8 bits),2)
      val a,d=out Vec(Bits(8 bits),ElabInt.literal(3))
      @dontName val count=HdlInt.param("COUNT",3,1,4)
      val hx=in Vec(Bits(8 bits),count.asElabInt)
      val b,c=out Vec(Bits(8 bits),count.asElabInt)
      val rb,rc=Vec.fill(count.asElabInt)(Reg(Bits(8 bits)) init(0))
      b:=rb;c:=rc
      val rows:ElabInt=ElabInt.literal(2)
      val columns:ElabInt=ElabInt.literal(3)
      for(row <- 0 until rows) {
        val local=Vec(Bits(8 bits),3)
        for(column <- 0 until columns) local(column):=x(column)^biases(row)
        y(row):=local.reduceBalancedTree((u:Bits,v:Bits)=>u^v)
      }
      def copy(target:Vec[Bits]):Unit = {
        for(i <- 0 until columns) target(i):=x(i)^g_GEN_1
      }
      copy(a);copy(d)
      for(i <- 0 until count) { rb(i):=hx(i) }
      for(i <- (0 until count).named("g_GEN_2","named_index")) { rc(i):=hx(i) }
      val child = new Component {
        setDefinitionName("LabelChild")
        val source=in Bits(8 bits);val result=out Bits(8 bits);result:=source
      }
      child.setName("g_GEN_3")
      child.source:=g_GEN_1
      val tap=out Bits(8 bits);tap:=child.result
    })
    dir
  }
  for(packed<-Seq(false,true)) test(s"compact default labels preserve explicit names and nested connectivity packed=$packed") {
    val dir=generate(packed);val again=generate(packed)
    val rtl=new String(Files.readAllBytes(dir.resolve("Labels.v")),"UTF-8")
    assert(rtl==new String(Files.readAllBytes(again.resolve("Labels.v")),"UTF-8"))
    val labels="begin : (g_GEN_[0-9]+)".r.findAllMatchIn(rtl).map(_.group(1)).toVector
    assert(labels==Vector("g_GEN_4","g_GEN_5","g_GEN_6","g_GEN_7","g_GEN_8","g_GEN_2"),rtl)
    assert(rtl.contains("named_index") && !rtl.contains("loop_l") && !rtl.contains("generated_scope_"),rtl)
    Files.write(dir.resolve("tb.v"),"""module tb;
reg clk=0,reset=0;reg[23:0]x=24'h123456;reg[15:0]biases=16'h789a;reg[7:0]g_GEN_1=8'h55;
wire[15:0]y;wire[23:0]a,b,c,d;wire[7:0]tap;
Labels dut(.clk(clk),.reset(reset),.hx(x),.x(x),.biases(biases),.g_GEN_1(g_GEN_1),.y(y),.a(a),.b(b),.c(c),.d(d),.tap(tap));
integer k;initial begin for(k=0;k<100;k=k+1)begin
x=$random;biases=$random;g_GEN_1=$random;#1;clk=1;#1;
if(y!==({2{x[7:0]^x[15:8]^x[23:16]}}^biases) || a!==(x^{3{g_GEN_1}}) || b!==x || c!==x || d!==a || tap!==g_GEN_1)$fatal(1,"connectivity");
clk=0;#1;end $finish;end endmodule
""".getBytes("UTF-8"))
    for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","Labels.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","Labels.v"),
      Seq("yosys","-Q","-p","read_verilog Labels.v; synth -top Labels; check -assert; select -assert-none t:$dlatch"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,cmd);assert(code==0,s"$dir\n$log")
    }
  }
}
