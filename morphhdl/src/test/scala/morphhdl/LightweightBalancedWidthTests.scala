package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class LightweightBalancedWidthTests extends AnyFunSuite {
  private def generate(count: Int) = {
    val dir = Files.createTempDirectory("lightweight-recursive-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
      headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
      vecLayout = MorphAggregateOptions.UnpackedArray)) {
      spinal.lib.BalancedTree.defineWidth((w: ElabInt) => UInt(w bits), 8, 64, "LibraryWidthTree")((a: UInt, b: UInt) => a+b).component(ElabInt.literal(count))
    }
    dir -> new String(Files.readAllBytes(dir.resolve("LibraryWidthTree.v")), "UTF-8")
  }
  for (default <- Seq(1, 8)) {
    test(s"lightweight library symbolic payload width default=$default") {
      val (dir, rtl) = generate(default)
      assert(rtl == generate(default)._2)
      assert("(?m)^module LibraryWidthTree\\b".r.findAllIn(rtl).size == 1, rtl)
      assert(rtl.contains("nextLevel") && rtl.contains("for (") && rtl.contains("else"), rtl)
      assert(rtl.contains("combined[") && !rtl.contains("structural_vec_alias_1_storage"), rtl)
      val instances = (for (n <- Seq(1,2,3,5,7,8,15,16,17,32,64); w <- Seq(1,8,32,36,64)) yield
        s"Check #(.N($n),.W($w)) c_${n}_$w(data);").mkString("\n")
      Files.write(dir.resolve("tb.v"), s"""module Check #(parameter N=1,W=8)(input [4095:0] data);
wire [W-1:0] result;
reg [W-1:0] words[0:63]; integer i,n;
LibraryWidthTree #(.COUNT(N),.DATA_WIDTH(W)) dut(.clk(1'b0),.reset(1'b0),.softReset(1'b0),.enable(1'b1),.terms(data[N*W-1:0]),.result(result));
always @(data) begin #1;
 for(i=0;i<N;i=i+1) words[i]=data[i*W+:W];
 n=N;
 while(n>1) begin
  for(i=0;i<(n+1)/2;i=i+1) begin
   if(2*i+1<n) words[i]=words[2*i] + words[2*i+1];
   else words[i]=words[2*i];
  end
  n=(n+1)/2;
 end
 if(result !== words[0]) $$fatal;
end
endmodule
module tb;
reg [4095:0] data; integer j,k;
$instances
initial begin
 data=0; #3; data=~4096'd0; #3; data='bx; #3; data='bz; #3;
 for(j=0;j<100;j=j+1) begin for(k=0;k<128;k=k+1) data[k*32+:32]=$$random; #3; end
 $$finish;
end
endmodule
""".getBytes("UTF-8"))
      for(command <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","LibraryWidthTree.v","tb.v"),
          Seq("vvp","sim"), Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","LibraryWidthTree.v"),
          Seq("yosys","-Q","-T","-p","read_verilog LibraryWidthTree.v; chparam -set COUNT 64 -set DATA_WIDTH 36 LibraryWidthTree; hierarchy -check -top LibraryWidthTree; synth -top LibraryWidthTree; check -assert; select -assert-none t:$dlatch"))) {
        val (code,log) = Increment66ToolEvidence.run(dir,command)
        assert(code == 0, s"$dir\n$log")
      }
    }
  }
}
