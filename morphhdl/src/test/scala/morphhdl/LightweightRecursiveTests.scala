package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

// The frontend retains authored child names as well as typed recursive edges.
@_root_.spinal.core.rtlRecursive
final class LightweightRecursiveTree(countActual: ElabInt, widthActual: ElabInt,
    subtract: Boolean) extends Component {
  setDefinitionName("LightweightRecursiveTree")
  noIoPrefix()
  val count: ElabInt = RtlParam.int("COUNT", countActual, 1, 17)
  val width: ElabInt = RtlParam.int("WIDTH", widthActual, 1, 64)
  val io = new Bundle {
    val terms = in(Vec(UInt(width bits), count))
    val result = out UInt(width bits)
  }
  if (count == 1) {
    io.result := io.terms(0)
  } else {
    val nextCount = (count + 1) / 2
    val combined = Vec(UInt(width bits), nextCount)
    for (i <- 0 until nextCount) {
      if (2 * i + 1 < count) {
        if (subtract) combined(i) := io.terms(2 * i) - io.terms(2 * i + 1)
        else combined(i) := io.terms(2 * i) + io.terms(2 * i + 1)
      } else combined(i) := io.terms(2 * i)
    }
    val nextLevel = new LightweightRecursiveTree(nextCount, width, subtract)
    nextLevel.io.terms := combined
    io.result := nextLevel.io.result
  }
}

class LightweightRecursiveTests extends AnyFunSuite {
  private def generate(count: Int, subtract: Boolean) = {
    val dir = Files.createTempDirectory("lightweight-recursive-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
      headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
      vecLayout = MorphAggregateOptions.UnpackedArray)) {
      new LightweightRecursiveTree(ElabInt.literal(count), ElabInt.literal(8), subtract)
    }
    dir -> new String(Files.readAllBytes(dir.resolve("LightweightRecursiveTree.v")), "UTF-8")
  }
  for (default <- Seq(1, 8); subtract <- Seq(false, true)) {
    test(s"direct recursive adjacent pairs default=$default subtract=$subtract") {
      val (dir, rtl) = generate(default, subtract)
      assert(rtl == generate(default, subtract)._2)
      assert("(?m)^module LightweightRecursiveTree\\b".r.findAllIn(rtl).size == 1, rtl)
      assert(rtl.contains("nextLevel") && rtl.contains("for (") && rtl.contains("else"), rtl)
      val instances = (for (n <- Seq(1,2,3,5,7,8,15,16,17); w <- Seq(1,8,32,36,64)) yield
        s"Check #(.N($n),.W($w)) c_${n}_$w(data);").mkString("\n")
      Files.write(dir.resolve("tb.v"), s"""module Check #(parameter N=1,W=8)(input [1087:0] data);
wire [W-1:0] result;
reg [W-1:0] words[0:16]; integer i,n;
LightweightRecursiveTree #(.COUNT(N),.WIDTH(W)) dut(.terms(data[N*W-1:0]),.result(result));
always @(data) begin #1;
 for(i=0;i<N;i=i+1) words[i]=data[i*W+:W];
 n=N;
 while(n>1) begin
  for(i=0;i<(n+1)/2;i=i+1) begin
   if(2*i+1<n) words[i]=words[2*i] ${if(subtract) "-" else "+"} words[2*i+1];
   else words[i]=words[2*i];
  end
  n=(n+1)/2;
 end
 if(result !== words[0]) $$fatal;
end
endmodule
module tb;
reg [1087:0] data; integer j,k;
$instances
initial begin
 data=0; #3; data=~1088'd0; #3; data='bx; #3; data='bz; #3;
 for(j=0;j<100;j=j+1) begin for(k=0;k<34;k=k+1) data[k*32+:32]=$$random; #3; end
 $$finish;
end
endmodule
""".getBytes("UTF-8"))
      for(command <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","LightweightRecursiveTree.v","tb.v"),
          Seq("vvp","sim"), Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","LightweightRecursiveTree.v"),
          Seq("yosys","-Q","-T","-p","read_verilog LightweightRecursiveTree.v; chparam -set COUNT 17 -set WIDTH 36 LightweightRecursiveTree; hierarchy -check -top LightweightRecursiveTree; synth -top LightweightRecursiveTree; check -assert; select -assert-none t:$dlatch"))) {
        val (code,log) = Increment66ToolEvidence.run(dir,command)
        assert(code == 0, s"$dir\n$log")
      }
    }
  }
}
