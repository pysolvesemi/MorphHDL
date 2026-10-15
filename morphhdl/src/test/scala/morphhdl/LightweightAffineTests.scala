package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class LightweightAffineTests extends AnyFunSuite {
  private def generate(default: Int, guarded: Boolean) = {
    val dir = Files.createTempDirectory("lightweight-affine-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
      headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
      vecLayout = MorphAggregateOptions.UnpackedArray)) { new Component {
      setDefinitionName("PairStage")
      val count: ElabInt = RtlParam.int("COUNT", default, 1, 17)
      val nextCount = (count + 1) / 2
      val source = in(Vec(UInt(8 bits), count))
      val result = out(Vec(UInt(8 bits), nextCount))
      for (i <- 0 until nextCount) {
        if (guarded) {
          if (2 * i + 1 < count) result(i) := source(2 * i) - source(2 * i + 1)
          else result(i) := source(2 * i)
        } else result(i) := source(2 * i) - source(2 * i + 1)
      }
    } }
    dir -> new String(Files.readAllBytes(dir.resolve("PairStage.v")), "UTF-8")
  }
  for (default <- Seq(1, 8)) test(s"guarded adjacent pairs and odd tails default=$default") {
    val (dir, rtl) = generate(default, true)
    assert(rtl == generate(default, true)._2)
    assert(rtl.contains("for (") && rtl.contains("else"), rtl)
    val instances = Seq(1,2,3,5,7,8,15,16,17).map(n => s"Check #(.N($n)) c_$n(data);").mkString("\n")
    Files.write(dir.resolve("tb.v"), s"""module Check #(parameter N=1)(input [135:0] data);
wire [((N+1)/2)*8-1:0] result;
reg [7:0] expected; integer i;
PairStage #(.COUNT(N)) dut(.source(data[N*8-1:0]),.result(result));
always @(data) begin #1;
 for(i=0;i<(N+1)/2;i=i+1) begin
  expected=data[(2*i)*8+:8];
  if(2*i+1<N) expected=expected-data[(2*i+1)*8+:8];
  if(result[i*8+:8] !== expected) $$fatal;
 end
end
endmodule
module tb;
reg [135:0] data; integer j;
$instances
initial begin
 data=0; #3; data=~136'd0; #3; data='bx; #3; data='bz; #3;
 for(j=0;j<128;j=j+1) begin data={$$random,$$random,$$random,$$random,$$random}; #3; end
 $$finish;
end
endmodule
""".getBytes("UTF-8"))
    for(command <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","PairStage.v","tb.v"),
        Seq("vvp","sim"), Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","PairStage.v"),
        Seq("yosys","-Q","-T","-p","read_verilog PairStage.v; hierarchy -check -top PairStage; synth -top PairStage; check -assert; select -assert-none t:$dlatch"))) {
      val (code, log) = Increment66ToolEvidence.run(dir,command)
      assert(code == 0, s"$dir\n$log")
    }
  }
  test("unguarded affine tail remains outside the legal domain") {
    val error = intercept[Exception](generate(8, false))
    assert(error.getMessage.contains("AFFINE-DOMAIN-OUT-OF-RANGE"), error.getMessage)
  }
}
