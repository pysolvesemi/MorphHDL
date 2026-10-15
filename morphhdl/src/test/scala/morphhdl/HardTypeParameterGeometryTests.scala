package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.BalancedTree

class HardTypeParameterGeometryTests extends AnyFunSuite {
  for(kind<-Seq("uint","sint","bits"))test(s"HardType retains symbolic scalar geometry without widthFactory kind=$kind") {
    val dir=Files.createTempDirectory("hardtype-parameter-")
    def design[T <: BitVector](factory:ElabInt=>T, operation:(T,T)=>T):Component=new Component {
      setDefinitionName("HardTypeTop")
      val COUNT:ElabInt=RtlParam.int("COUNT",3,1,5)
      val WIDTH:ElabInt=RtlParam.int("WIDTH",8,1,64)
      val source=in(Vec(factory(WIDTH),COUNT));val result=out(factory(WIDTH))
      val reduction=BalancedTree.define(HardType(factory(WIDTH)),5,"HardTypeTree")(operation)
      val tree=reduction.component(COUNT)
      tree.io.clk:=False;tree.io.reset:=False;tree.io.softReset:=False;tree.io.enable:=True
      tree.io.terms:=source
      result:=tree.io.result
    }
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
      kind match {
        case "uint"=>design((w:ElabInt)=>UInt(w bits),(a:UInt,b:UInt)=>a+b)
        case "sint"=>design((w:ElabInt)=>SInt(w bits),(a:SInt,b:SInt)=>Mux(a>b,a,b))
        case "bits"=>design((w:ElabInt)=>Bits(w bits),(a:Bits,b:Bits)=>a|b)
      }
    }
    val rtl=new String(Files.readAllBytes(dir.resolve("HardTypeTop.v")),"UTF-8")
    assert(rtl.contains(".DATA_WIDTH") && rtl.contains("parameter integer DATA_WIDTH"),rtl)
    val profiles=(for(n<-Seq(1,3,5);w<-Seq(1,5,36,64))yield s"Check #(.N($n),.W($w)) c_${n}_$w(data);").mkString("\n")
    val fold=kind match {
      case "uint"=>"expected=expected+data[i*W+:W];"
      case "sint"=>"if($signed(data[i*W+:W])>$signed(expected))expected=data[i*W+:W];"
      case "bits"=>"expected=expected|data[i*W+:W];"
    }
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter N=1,W=1)(input [319:0] data);
wire [W-1:0] result;reg [W-1:0] expected;integer i;
HardTypeTop #(.COUNT(N),.WIDTH(W)) dut(.source(data[N*W-1:0]),.result(result));
always @(data)begin #1;expected=data[0+:W];for(i=1;i<N;i=i+1)begin $fold end
if(result!==expected)$$fatal;end endmodule
module tb;reg [319:0] data;integer j,k;
$profiles
initial begin data=0;#3;data=~320'd0;#3;
for(j=0;j<64;j=j+1)begin for(k=0;k<10;k=k+1)data[k*32+:32]=$$random;#3;end $$finish;end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","HardTypeTop.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","HardTypeTop.v"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,command);assert(code==0,log)
    }
  }
}
