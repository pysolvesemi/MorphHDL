package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.BalancedTree

class OptionalReductionPolicyTests extends AnyFunSuite {
  for(mode<-Seq("none","zero","bridge","identity","legacy-null")) test(s"optional reduction policy preserves omission and explicit presence mode=$mode") {
    val dir=Files.createTempDirectory("optional-reduction-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("OptionalTop")
        val terms=in(Vec(UInt(8 bits),3))
        val result=out UInt(8 bits)
        val bridge:Option[(UInt,Int)=>UInt]=mode match {
          case "bridge"=>Some((v:UInt,level:Int)=>v+U(level+1,8 bits))
          case "identity"=>Some(BalancedTree.identityBridge[UInt])
          case _=>None
        }
        if(mode=="legacy-null") result:=BalancedTree.reduceBalancedTree(terms,"OptionalTree",null)((a:UInt,b:UInt)=>a-b)
        else if(mode=="zero") result:=BalancedTree.reduceBalancedTree(terms,"OptionalTree",Some(Param(0)))((a:UInt,b:UInt)=>a-b)
        else result:=BalancedTree.reduce(terms,"OptionalTree",None,bridge)((a:UInt,b:UInt)=>a-b)
      }
    }
    val rtl=new String(Files.readAllBytes(dir.resolve("OptionalTop.v")),"UTF-8")
    assert(rtl.contains("parameter integer PIPELINE_MASK")== (mode=="zero"),rtl)
    assert(rtl.contains("parameter integer INITIAL_COUNT")==Set("zero","bridge","identity")(mode),rtl)
    val correction=if(mode=="bridge")2 else 0
    Files.write(dir.resolve("tb.v"),s"""module tb;
reg clk=0,reset=0;reg [23:0] terms;wire [7:0] result;reg [7:0] expected;integer i;
OptionalTop dut(.clk(clk),.reset(reset),.terms(terms),.result(result));
initial begin
for(i=0;i<100;i=i+1)begin terms=$$random;expected=terms[7:0]-terms[15:8]-terms[23:16]+$correction;#1;if(result!==expected)$$fatal;end
terms='bx;#1;if(result!==8'bx)$$fatal;$$finish;end
endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","OptionalTop.v","tb.v"),Seq("vvp","sim"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,command);assert(code==0,log)
    }
  }
  test("Some symbolic mask forwards overrides and retains reset and stall behavior") {
    val dir=Files.createTempDirectory("optional-mask-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("OptionalMaskTop")
        val MASK:ElabInt=RtlParam.int("MASK",0,0,3)
        val clk=in Bool();val reset=in Bool();val enable=in Bool()
        val terms=in(Vec(UInt(8 bits),3));val result=out UInt(8 bits)
        val logic=new ClockingArea(ClockDomain(clk,reset=reset,clockEnable=enable)) {
          result:=BalancedTree.reduce(terms,"OptionalMaskTree",Some(MASK))((a:UInt,b:UInt)=>a+b)
        }
      }
    }
    Files.write(dir.resolve("tb.v"),"""module Check #(parameter M=0)(input clk,reset,enable,input [23:0] terms);
wire [7:0] result;wire [7:0] sum=terms[7:0]+terms[15:8]+terms[23:16];reg [7:0] q0,q1;
OptionalMaskTop #(.MASK(M)) dut(.clk(clk),.reset(reset),.enable(enable),.terms(terms),.result(result));
always @(posedge clk or posedge reset)if(reset)begin q0<=0;q1<=0;end else if(enable)begin q0<=sum;q1<=q0;end
always @(posedge clk)begin #1;if(result!==(M==0?sum:(M==3?q1:q0)))$fatal;end
endmodule
module tb;reg clk=0,reset=1,enable=1;reg [23:0] terms=0;integer n;
always #5 clk=~clk;
Check #(.M(0)) c0(clk,reset,enable,terms);Check #(.M(1)) c1(clk,reset,enable,terms);Check #(.M(3)) c3(clk,reset,enable,terms);
initial begin #12;reset=0;
for(n=0;n<60;n=n+1)begin @(negedge clk);terms=$random;enable=n%4!=0;reset=n==30;end
@(negedge clk);$finish;end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","OptionalMaskTop.v","tb.v"),Seq("vvp","sim"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,command);assert(code==0,log)
    }
  }
}
