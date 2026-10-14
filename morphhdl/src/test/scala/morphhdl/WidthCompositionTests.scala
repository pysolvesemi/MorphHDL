package morphhdl

import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import nativeapplication.NativeWidthCompositionTree

class WidthCompositionTests extends AnyFunSuite {
  private def config(dir: Path) = {
    val c = SpinalConfig(targetDirectory = dir.toString, headerWithDate = false,
      flags = scala.collection.mutable.HashSet[Any](VerilogAggregateOptions(
        preserveConstantVecs = true, preserveConstantLoops = true,
        vecLayout = VerilogAggregateOptions.UnpackedArray)))
    c.netlistFileName = "tree.v"
    c
  }
  private def generate(mode: String, default: Int): (Path, String) = {
    val dir = Files.createTempDirectory("width-composition-")
    MorphVerilog(config(dir))(new NativeWidthCompositionTree(mode, ElabInt.literal(default)))
    dir -> new String(Files.readAllBytes(dir.resolve("tree.v")), "UTF-8")
  }
  for(mode <- Seq("direct", "alias", "soft-reset", "masked-soft-reset"); default <- Seq(1,8))
  test(s"recursive width composition mode=$mode default=$default") {
    val (dir, rtl) = generate(mode, default)
    assert(rtl == generate(mode, default)._2)
    assert("(?m)^module NativeWidthCompositionTree\\b".r.findAllIn(rtl).size == 1, rtl)
    val instances = (for(n <- Seq(1,2,3,5,8,15,16); w <- Seq(1,8,32,36,64); pipe <- (if(mode=="masked-soft-reset") Seq(0,1) else Seq(1))) yield {
      val latency = if(mode.contains("soft-reset") && pipe==1) log2Up(n) else 0
      s"""WidthCase #(.N($n),.W($w),.LATENCY($latency),.PIPE($pipe)) c_${n}_${w}_$pipe(.clk(clk),.reset(reset),.softReset(softReset),.enable(enable),.data(data));"""
    }).mkString("\n")
    Files.write(dir.resolve("tb.v"), s"""module WidthCase #(parameter N=8,W=36,LATENCY=0,PIPE=1)(input clk,reset,softReset,enable,input [1023:0] data);
wire [W-1:0] result;
NativeWidthCompositionTree #(.COUNT(N),.DATA_WIDTH(W)${if(mode=="masked-soft-reset") ",.PIPELINE_MASK(PIPE)" else ""}) dut(.clk(clk),.reset(reset),.softReset(softReset),.enable(enable),.terms(data[N*W-1:0]),.result(result));
reg [W-1:0] sum; reg [W-1:0] history [0:4]; integer j,k;
always @* begin sum=0; for(k=0;k<N;k=k+1) sum=sum+data[k*W +: W]; if(N==1) sum=data[W-1:0]; end
always @(posedge clk or negedge reset) begin
 if(!reset) begin for(j=0;j<5;j=j+1) history[j] <= 0; end
 else if(enable) begin
  if(softReset) begin for(j=0;j<5;j=j+1) history[j] <= 0; end
  else begin history[0] <= sum; for(j=1;j<5;j=j+1) history[j] <= history[j-1]; end
 end
end
always @(negedge clk) begin
 if(LATENCY==0) begin if(result !== sum) $$fatal(1,"combinational N=%0d W=%0d",N,W); end
 else if(result !== history[LATENCY-1]) $$fatal(1,"pipeline N=%0d W=%0d got=%h want=%h",N,W,result,history[LATENCY-1]);
end
endmodule
module tb;
reg clk=0,reset=1,softReset=0,enable=0; reg [1023:0] data=0; integer cycle,j;
always #5 clk=~clk;
$instances
initial begin
 #1; reset=0; #10; reset=1;
 for(cycle=0;cycle<100;cycle=cycle+1) begin
  @(negedge clk); #1;
  for(j=0;j<32;j=j+1) data[j*32 +: 32]=$$random;
  if(cycle==0) data=0; if(cycle==1) data=~1024'd0;
  if(cycle==2) data='bx; if(cycle==3) data='bz;
  enable=(cycle%4!=0); softReset=(cycle%13==0);
  if(cycle==41) reset=0; if(cycle==42) reset=1;
 end
 #20; $$display("PASS"); $$finish;
end
endmodule
""".getBytes("UTF-8"))
    for(command <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","tree.v","tb.v"), Seq("vvp","sim"))) {
      val (code,log)=Increment66ToolEvidence.run(dir,command)
      assert(code==0,s"$dir $command\n$log")
    }
    for(command <- Seq(
      Seq("verilator", "--lint-only", "--language", "1364-2001", "-DSYNTHESIS", "-Wno-fatal", "--top-module", "NativeWidthCompositionTree", "tree.v"),
      Seq("verilator", "--lint-only", "-Wno-fatal", "--top-module", "NativeWidthCompositionTree", "tree.v"),
      Seq("yosys", "-Q", "-T", "-p", "read_verilog tree.v; chparam -set COUNT 5 -set DATA_WIDTH 36 NativeWidthCompositionTree; hierarchy -check -top NativeWidthCompositionTree; synth -top NativeWidthCompositionTree; check -assert; select -assert-none t:$dlatch t:$dlatchsr"))) {
      val (code,log)=Increment66ToolEvidence.run(dir,command)
      assert(code==0,s"$dir $command\n$log")
    }
  }

  for(direction <- Seq("input", "output"); mismatch <- Seq(false,true))
  test(s"concrete compound child connection direction=$direction mismatch=$mismatch") {
    val dir=Files.createTempDirectory("compound-literal-")
    def generate(): Unit = MorphVerilog(config(dir)) { new Component {
      val input=in Bits(288 bits); val output=out Bits(288 bits)
      val child=new Component {
        @dontName val count=ElabFormalParameter(ElabInt.literal(8),"COUNT",1,16)
        @dontName val width=ElabFormalParameter(ElabInt.literal(36),"DATA_WIDTH",1,64)
        val a=in Bits((count*width) bits); val b=out Bits((count*width) bits)
        b := ~a
      }
      val carrier=Bits((if(mismatch) 287 else 288) bits).dontSimplifyIt()
      if(direction=="input") { carrier := input.resized; child.a := carrier; output := child.b }
      else { child.a := input; carrier := child.b; output := carrier.resized }
    } }
    if(mismatch) {
      val failure = intercept[Exception](generate())
      assert(failure.getMessage.contains("WIDTH MISMATCH") || failure.getMessage.contains("WIDTH-MISMATCH") ||
        failure.getMessage.contains("BINDING-UNRESOLVED") || failure.getMessage.contains("CONNECTION-CONFLICT"), failure.getMessage)
    } else generate()
  }
}
