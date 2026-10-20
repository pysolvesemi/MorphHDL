package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.BalancedTree

class LightweightBalancedTreeTests extends AnyFunSuite {
  private def generate(kind: String, default: Int, capacity: Int) = {
    val directory = Files.createTempDirectory("lightweight-library-tree-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=directory.toString,
      headerWithDate=false), preserveConstantVecs=true, preserveConstantLoops=true,
      vecLayout=MorphAggregateOptions.UnpackedArray)) {
      val bridge: (UInt,Int) => UInt = kind match {
        case "bridge" => (value,level) => value + U(level+1,8 bits)
        case "register" => (value,level) => if(level==1) RegNext(value) init(0) else value
        case _ => BalancedTree.identityBridge[UInt]
      }
      new BalancedTree[UInt](HardType(UInt(8 bits)),capacity,ElabInt.literal(default),
        if(kind=="comb") null else ElabInt.literal(default),
        if(kind=="mask" || kind=="register") ElabInt.literal(0) else null,
        null,"LibraryTree",ClockDomainConfig(resetKind=ASYNC,resetActiveLevel=LOW),
        (a,b) => a-b,bridge,null)
    }
    directory -> new String(Files.readAllBytes(directory.resolve("LibraryTree.v")),"UTF-8")
  }
  private val profiles = (for(kind <- Seq("comb","mask","bridge","register"); default <- Seq(1,5))
    yield (kind,default,8)) ++ Seq(("mask",1,32),("mask",5,64),("register",5,64))
  for((kind,default,capacity) <- profiles) {
    test(s"lightweight library preserves adjacent pairs bridges and registers kind=$kind default=$default capacity=$capacity") {
      val (directory,rtl)=generate(kind,default,capacity)
      assert(rtl==generate(kind,default,capacity)._2)
      assert("(?m)^module LibraryTree\\b".r.findAllIn(rtl).size==1,rtl)
      assert(rtl.contains(") nextLevel ("),rtl)
      assert(rtl.contains("parameter integer PIPELINE_MASK") == (kind=="mask" || kind=="register"),rtl)
      assert(rtl.contains("parameter integer INITIAL_COUNT") == (kind!="comb"),rtl)
      val reference = new StringBuilder
      def register(name: String, expression: String): Unit = {
        reference.append(s"reg [7:0] $name; always @(posedge clk or negedge resetn) if(!resetn) $name<=0; else if(enable) begin if(softReset) $name<=0; else $name<=$expression; end\n")
      }
      val counts = Seq(1,2,3,5,8,15,16,17,31,32,33,63,64).filter(_<=capacity)
      val masks = if(kind!="mask" && kind!="register") Seq(0)
        else if(capacity==8) 0 until 8 else Seq(0,1,capacity/2,capacity-1)
      for(n <- counts; mask <- masks) {
        val tag=s"n${n}m${mask}"
        val countBinding=if(kind=="comb") "" else s",.INITIAL_COUNT($n)"
        val maskBinding=if(kind=="mask" || kind=="register") s",.PIPELINE_MASK($mask)" else ""
        reference.append(s"wire [7:0] result_$tag; LibraryTree #(.COUNT($n)$countBinding$maskBinding) dut_$tag(.clk(clk),.reset(resetn),.softReset(softReset),.enable(enable),.terms(data[${n*8-1}:0]),.result(result_$tag));\n")
        var words=(0 until n).map(i=>s"data[${i*8}+:8]").toVector
        var level=0
        while(words.size>1) {
          words=words.grouped(2).zipWithIndex.map { case(group,lane) =>
            val base=s"${tag}_l${level}_$lane"
            val combined=if(group.size==1) group.head else s"(${group(0)} - ${group(1)})"
            reference.append(s"wire [7:0] ${base}_combined = $combined;\n")
            var value=base+"_combined"
            if(kind=="bridge") {
              reference.append(s"wire [7:0] ${base}_bridged = $value + 8'd${level+1};\n")
              value=base+"_bridged"
            }
            if(kind=="register" && level==1) { register(base+"_bridge_reg",value);value=base+"_bridge_reg" }
            if((mask & (1<<level))!=0) { register(base+"_mask_reg",value);value=base+"_mask_reg" }
            value
          }.toVector
          level+=1
        }
        reference.append(s"""always @(negedge clk) if(result_$tag !== ${words.head}) begin $$display("mismatch $tag"); $$fatal; end
""")
      }
      Files.write(directory.resolve("tb.v"),s"""module tb;
reg clk=0; always #5 clk=~clk;
reg resetn=0,softReset=0,enable=1; reg [${capacity*8-1}:0] data=0; integer i,j;
$reference
initial begin #2; resetn=1; #1; resetn=0; #8; resetn=1;
for(i=0;i<200;i=i+1) begin @(negedge clk); #1;
for(j=0;j<${capacity/4};j=j+1) data[j*32+:32]=$$random; enable=(i%7!=2); softReset=(i%17==0);
if(i==60) data=0; if(i==61) data=~${capacity*8}'d0;
if(i==62) data='bx; if(i==63) data='bz;
if(i==100) begin resetn=0; #2; resetn=1; end
end @(negedge clk); #1; $$finish; end endmodule
""".getBytes("UTF-8"))
      val initialBinding = if(kind=="comb") "" else s",.INITIAL_COUNT($capacity)"
      val pipelineBinding = if(kind=="mask" || kind=="register") s",.PIPELINE_MASK(${capacity-1})" else ""
      Files.write(directory.resolve("synth_top.v"),s"""module SynthTop(input clk,resetn,softReset,enable,input [${capacity*8-1}:0] data,output [7:0] result);
LibraryTree #(.COUNT($capacity)$initialBinding$pipelineBinding) dut(.clk(clk),.reset(resetn),.softReset(softReset),.enable(enable),.terms(data),.result(result)); endmodule
""".getBytes("UTF-8"))
      for(command <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","LibraryTree.v","tb.v"),
          Seq("vvp","sim"),Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","LibraryTree.v"),
          Seq("yosys","-Q","-T","-p","read_verilog LibraryTree.v synth_top.v; hierarchy -check -top SynthTop; synth -top SynthTop; check -assert; select -assert-none t:$dlatch"))) {
        val(status,log)=Increment66ToolEvidence.run(directory,command)
        assert(status==0,s"$directory\n$log")
      }
    }
  }
}
