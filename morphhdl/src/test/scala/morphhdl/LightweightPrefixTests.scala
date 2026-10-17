package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.BalancedTree

class LightweightPrefixTests extends AnyFunSuite {
  for (packed <- Seq(false, true); default <- Seq(2, 8))
    test(s"ordinary prefix reads retain shift resets and stalls packed=$packed default=$default") {
      val dir = Files.createTempDirectory("lightweight-prefix-shift-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString, headerWithDate=false),
        preserveConstantVecs=true, preserveConstantLoops=true,
        vecLayout=if(packed) MorphAggregateOptions.PackedVector else MorphAggregateOptions.UnpackedArray)) {
        new Component {
          setDefinitionName("PrefixShift")
          val depth: ElabInt = RtlParam.int("DEPTH", default, 2, 8)
          val width: ElabInt = RtlParam.int("WIDTH", 8, 1, 64)
          val data = in Bits(width bits)
          val enable = in Bool()
          val result = out Bits(width bits)
          val stages = Vec.fill(depth)((Reg(Bits(width bits)) init(0)).addAttribute("ASYNC_REG", "TRUE"))
          when(enable) {
            stages(0) := data
            for (i <- 0 until (depth - 1)) {
              if (default == 2) stages(i + 1) := stages(i)
              else stages(i + 1) := stages(i + 0)
            }
          }
          result := stages(depth - 1)
        }
      }
      val rtl = new String(Files.readAllBytes(dir.resolve("PrefixShift.v")), "UTF-8")
      assert(rtl.contains("for (") && rtl.contains("ASYNC_REG"), rtl)
      val checks = (for(n <- Seq(2,3,4,8); w <- Seq(1,5,64)) yield
        s"Check #(.N($n),.W($w)) c_${n}_$w(clk,reset,enable,data);").mkString("\n")
      Files.write(dir.resolve("tb.v"),s"""module Check #(parameter N=2,W=1)(input clk,reset,enable,input [63:0] data);
wire [W-1:0] result;reg [N*W-1:0] expected;
PrefixShift #(.DEPTH(N),.WIDTH(W)) dut(.clk(clk),.reset(reset),.enable(enable),.data(data[W-1:0]),.result(result));
always @(posedge clk or posedge reset) if(reset) expected<=0;else if(enable) expected<={expected[(N-1)*W-1:0],data[W-1:0]};
always @(negedge clk) begin #1;if(result !== expected[N*W-1-:W]) $$fatal(1,"shift mismatch N=%d W=%d",N,W);end
endmodule
module tb;reg clk=0,reset=0,enable=0;reg [63:0] data=0;integer i;
always #5 clk=~clk;
$checks
initial begin #1;reset=1;#20;reset=0;
for(i=0;i<100;i=i+1) begin #10;enable=(i%3!=0);data={$$random,$$random};end
#10;enable=1;data='bx;#100;data='bz;#100;reset=1;#20;reset=0;data=0;#100;$$finish;end endmodule
""".getBytes("UTF-8"))
      for (cmd <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","PrefixShift.v","tb.v"),
        Seq("vvp","sim"),Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","PrefixShift.v"),
        Seq("yosys","-Q","-T","-p","read_verilog PrefixShift.v; chparam -set DEPTH 8 -set WIDTH 64 PrefixShift; hierarchy -check -top PrefixShift; synth -top PrefixShift; check -assert; select -assert-none t:$dlatch"))) {
        val (code,log)=Increment66ToolEvidence.run(dir,cmd);assert(code==0,s"$dir\n$log")
      }
    }

  test("live-count adapter rejects counts exceeding the source for any legal override") {
    val error=intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("invalid-live-prefix-").toString)) {
        new Component {
          val count: ElabInt=RtlParam.int("COUNT",2,1,8)
          val source=in(Vec(UInt(8 bits),count))
          val result=out UInt(8 bits)
          result := BalancedTree.define(HardType(UInt(8 bits)),8,"BadPrefix")(
            (a:UInt,b:UInt)=>a+b).reduceBalancedTree(source,ElabInt.literal(2),null)
        }
      }
    }
    assert(error.toString.contains("live count must fit supplied inputs"),error.toString)
  }
  test("ordinary prefix access rejects a range exceeding the exact Vec domain") {
    val error=intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("invalid-prefix-").toString),
        preserveConstantVecs=true,preserveConstantLoops=true)) {
        new Component {
          val depth: ElabInt=RtlParam.int("DEPTH",3,2,8)
          val source=in(Vec(Bits(8 bits),depth))
          val result=out(Vec(Bits(8 bits),depth+1))
          for(i <- 0 until (depth+1)) result(i) := source(i)
        }
      }
    }
    assert(error.toString.contains("finite subrange exceeds logical Vec depth"),error.toString)
  }
}
