package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.BalancedTree

class ReductionWiringTests extends AnyFunSuite {
  private def generate(kind: String, default: Int) = {
    val dir = Files.createTempDirectory("reduction-wiring-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString, headerWithDate=false),
      preserveConstantVecs=true, preserveConstantLoops=true, vecLayout=MorphAggregateOptions.UnpackedArray)) {
      new BalancedTree[UInt](HardType(UInt(8 bits)), 17, ElabInt.literal(default), null, null,
        ElabInt.literal(8), "Reduction", ClockDomainConfig(),
        (a,b) => kind match { case "or" => a | b; case "subtract" => a-b; case _ => a+b },
        BalancedTree.identityBridge[UInt], (width: ElabInt) => UInt(width bits))
    }
    dir
  }
  for (kind <- Seq("sum", "or", "subtract"); default <- Seq(1, 7)) {
    test(s"symbolic reduction wiring preserves adjacent pairs $kind default=$default") {
      val dir=generate(kind,default)
      val rtl=new String(Files.readAllBytes(dir.resolve("Reduction.v")),"UTF-8")
      assert(rtl==new String(Files.readAllBytes(generate(kind,default).resolve("Reduction.v")),"UTF-8"))
      assert(!rtl.contains("function") && !rtl.contains("always @"),rtl)
      assert(!rtl.contains("nextLevel_result") && !rtl.contains("pack_index"),rtl)
      assert(!rtl.contains("2 * i + 0") && !rtl.contains("guard_l") && !rtl.contains("g_if_"),rtl)
      assert(rtl.contains("begin : g_GEN_1_TRUE") && rtl.contains("begin : g_GEN_1_FALSE"),rtl)
      assert("(?m)^module Reduction\\b".r.findAllIn(rtl).size == 1,rtl)
      val reference=new StringBuilder
      for(n <- Seq(1,2,3,5,7,8,15,16,17); width <- Seq(1,5,32,36,64)) {
        val tag=s"n${n}w$width"
        reference.append(s"wire [${width-1}:0] result_$tag; Reduction #(.COUNT($n),.DATA_WIDTH($width)) dut_$tag(.clk(1'b0),.reset(1'b0),.softReset(1'b0),.enable(1'b1),.terms(data[${n*width-1}:0]),.result(result_$tag));\n")
        var words=(0 until n).map(i=>s"data[${i*width}+:$width]").toVector
        var level=0
        while(words.size>1) {
          words=words.grouped(2).zipWithIndex.map { case(pair,lane) =>
            val name=s"${tag}_l${level}_$lane"
            val op=kind match { case "or" => "|"; case "subtract" => "-"; case _ => "+" }
            val value=if(pair.size==1) pair.head else s"(${pair(0)} $op ${pair(1)})"
            reference.append(s"wire [${width-1}:0] $name = $value;\n");name
          }.toVector
          level+=1
        }
        reference.append(s"""always @(check) if($$time > 0 && result_$tag !== ${words.head}) begin $$display("$tag"); $$fatal; end
""")
      }
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg [1087:0] data=0; reg check=0; integer i,j;
$reference
initial begin
for(i=0;i<150;i=i+1) begin
for(j=0;j<34;j=j+1) data[j*32+:32]=$$random;
if(i==0) data=0; if(i==1) data=~1088'd0;
if(i==2) data='bx; if(i==3) data='bz;
if(i>=4 && i<68) data=1088'd1 << (i-4);
#2; check=~check; #1;
end $$finish; end endmodule
""".getBytes("UTF-8"))
      for(cmd <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","Reduction.v","tb.v"),
        Seq("vvp","sim"),Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","Reduction.v"))) {
        val(code,log)=Increment66ToolEvidence.run(dir,cmd);assert(code==0,s"$dir\n$log")
      }
    }
  }
}
