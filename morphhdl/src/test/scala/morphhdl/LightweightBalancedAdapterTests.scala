package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.BalancedTree

class LightweightBalancedAdapterTests extends AnyFunSuite {
  for(n <- Seq(1,3,5); prefix <- Seq(false,true)) test(s"literal $n-lane reduction connects to a larger generic definition prefix=$prefix") {
    val inputLanes = if(prefix) n+2 else n
    val directory=Files.createTempDirectory("lightweight-literal-tree-adapter-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=directory.toString),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("LiteralAdapter")
        val source=in(Vec(UInt(8 bits),ElabInt.literal(inputLanes)))
        val result=out UInt(8 bits)
        val operation=BalancedTree.define(HardType(UInt(8 bits)),8,"LiteralTree")((a:UInt,b:UInt)=>a-b)
        result := operation.reduceBalancedTree(source,ElabInt.literal(n),null)
      }
    }
    val files=directory.toFile.listFiles.filter(_.getName.endsWith(".v")).map(_.getName).toSeq
    Files.write(directory.resolve("tb.v"),s"""module tb;
reg [${inputLanes*8-1}:0] source; wire [7:0] result; reg [7:0] words[0:${n-1}]; integer i,k,size;
LiteralAdapter dut(.clk(1'b0),.reset(1'b0),.source(source),.result(result));
always @(source) begin #1;
for(i=0;i<$n;i=i+1) words[i]=source[i*8+:8]; size=$n;
while(size>1) begin for(i=0;i<(size+1)/2;i=i+1) begin
if(2*i+1<size) words[i]=words[2*i]-words[2*i+1]; else words[i]=words[2*i]; end size=(size+1)/2; end
if(result !== words[0]) $$fatal; end
initial begin source=0; #3; source=~${inputLanes*8}'d0; #3; source='bx; #3; source='bz; #3;
for(k=0;k<64;k=k+1) begin source={$$random,$$random}; #3; end $$finish; end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim")++files++Seq("tb.v"),Seq("vvp","sim"))) {
      val(status,log)=Increment66ToolEvidence.run(directory,command);assert(status==0,log)
    }
  }
  for (mode <- Seq("symbolic", "sequence", "prefix", "prefixWidth")) test(s"public reduction adapter retains actual count mode=$mode") {
    val sequence = mode != "symbolic"
    val directory=Files.createTempDirectory("lightweight-tree-adapter-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=directory.toString,
      headerWithDate=false),preserveConstantVecs=true,preserveConstantLoops=true,
      vecLayout=MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("AdapterTop")
        noIoPrefix()
        val count: ElabInt=RtlParam.int("COUNT",5,1,8)
        val terms = if(sequence) in(Vec(UInt(8 bits),8)) else in(Vec(UInt(8 bits),count))
        val result=out UInt(8 bits)
        if(mode == "prefixWidth") result := BalancedTree.defineWidth((w:ElabInt)=>UInt(w bits),8,8,"AdapterTree")(
          (a:UInt,b:UInt)=>a-b).reduceBalancedTree(terms,count,null)
        else if(mode == "prefix") result := BalancedTree.define(HardType(UInt(8 bits)),8,"AdapterTree")(
          (a:UInt,b:UInt)=>a-b).reduceBalancedTree(terms,count,null)
        else if(sequence) result := BalancedTree.define(HardType(UInt(8 bits)),8,"AdapterTree")(
          (a:UInt,b:UInt)=>a-b).reduceBalancedTree(terms.toVector,count,null)
        else result := BalancedTree.reduceBalancedTree(terms,"AdapterTree")((a:UInt,b:UInt)=>a-b)
      }
    }
    val files=directory.toFile.listFiles().filter(_.getName.endsWith(".v")).map(_.getName).toSeq
    val profiles=Seq(1,2,3,5,8).map(n=>s"Check #(.N($n)) c$n(data);").mkString("\n")
    val inputWidth=if(sequence) "64" else "N*8"
    Files.write(directory.resolve("tb.v"),s"""module Check #(parameter N=1)(input [63:0] data);
wire [7:0] result; reg [7:0] words[0:7]; integer i,n;
AdapterTop #(.COUNT(N)) dut(.clk(1'b0),.reset(1'b0),.terms(data[$inputWidth-1:0]),.result(result));
always @(data) begin #1;
for(i=0;i<N;i=i+1) words[i]=data[i*8+:8]; n=N;
while(n>1) begin for(i=0;i<(n+1)/2;i=i+1) begin
if(2*i+1<n) words[i]=words[2*i]-words[2*i+1]; else words[i]=words[2*i]; end n=(n+1)/2; end
if(result !== words[0]) $$fatal; end endmodule
module tb; reg [63:0] data; integer i;
$profiles
initial begin data=0; #3; data=~64'd0; #3; data='bx; #3; data='bz; #3;
for(i=0;i<100;i=i+1) begin data={$$random,$$random}; #3; end $$finish; end endmodule
""".getBytes("UTF-8"))
    for(command <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim") ++ files ++ Seq("tb.v"),
        Seq("vvp","sim"),Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","AdapterTop") ++ files)) {
      val(status,log)=Increment66ToolEvidence.run(directory,command)
      assert(status==0,s"$directory\n$log")
    }
  }
}
