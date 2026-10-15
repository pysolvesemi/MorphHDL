package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class LightweightParameterTests extends AnyFunSuite {
  private def generate(default: Int, enabled: Boolean) = {
    val dir = Files.createTempDirectory("lightweight-parameters-")
    MorphVerilog(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false)) {
      new Component {
        setDefinitionName("LightweightParameters")
        val width: ElabInt = RtlParam.int("WIDTH", default, 1, 64)
        val invert: ElabBool = RtlParam.bool("INVERT", enabled)
        val source = in Bits(width bits)
        val result = out Bits(width bits)
        if (invert) result := ~source else result := source
      }
    }
    dir -> new String(Files.readAllBytes(dir.resolve("LightweightParameters.v")), "UTF-8")
  }
  for (default <- Seq(1, 32); enabled <- Seq(false, true)) {
    test(s"typed declarations retain both branches default=$default invert=$enabled") {
      val (dir, rtl) = generate(default, enabled)
      assert(rtl == generate(default, enabled)._2)
      assert(rtl.contains("parameter integer WIDTH") && rtl.contains("parameter integer INVERT"), rtl)
      assert(rtl.contains("generate") && rtl.contains("else"), rtl)
      val instances = (for (w <- Seq(1, 8, 32, 36, 64); inv <- Seq(0, 1)) yield
        s"Check #(.W($w),.I($inv)) c_${w}_$inv(data);").mkString("\n")
      Files.write(dir.resolve("tb.v"), s"""module Check #(parameter W=1,I=0)(input [63:0] data);
wire [W-1:0] result;
LightweightParameters #(.WIDTH(W),.INVERT(I)) dut(.source(data[W-1:0]),.result(result));
always @(data) begin #1; if(result !== (I ? ~data[W-1:0] : data[W-1:0])) $$fatal; end
endmodule
module tb;
reg [63:0] data; integer j;
$instances
initial begin
 data=0; #3; data=~64'd0; #3; data='bx; #3; data='bz; #3;
 for(j=0;j<64;j=j+1) begin data={$$random,$$random}; #3; end
 $$finish;
end
endmodule
""".getBytes("UTF-8"))
      for (command <- Seq(Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "LightweightParameters.v", "tb.v"),
          Seq("vvp", "sim"), Seq("verilator", "--lint-only", "--language", "1364-2001", "-DSYNTHESIS", "-Wno-fatal", "LightweightParameters.v"))) {
        val (code, log) = Increment66ToolEvidence.run(dir, command)
        assert(code == 0, log)
      }
    }
  }
  test("invalid integer defaults and domains remain rejected") {
    for ((default, lo, hi) <- Seq((0, 1, 8), (9, 1, 8), (1, 8, 1), (1, -1, 8))) {
      val dir = Files.createTempDirectory("lightweight-invalid-parameter-")
      val error = intercept[Exception] {
        MorphVerilog(SpinalConfig(targetDirectory = dir.toString)) { new Component {
          val width: ElabInt = RtlParam.int("WIDTH", default, lo, hi)
          val source = in Bits(width bits)
          val result = out Bits(width bits)
          result := source
        } }
      }
      assert(error.getMessage.contains("FORMAL-DOMAIN-INVALID"), error.getMessage)
    }
  }
  test("ordinary symbolic for retains the same finite-loop publication") {
    def generateLoop(short: Boolean): (java.nio.file.Path, String) = {
      val dir = Files.createTempDirectory("lightweight-loop-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
        headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
        vecLayout = MorphAggregateOptions.UnpackedArray)) { new Component {
        setDefinitionName("LightweightLoop")
        val count: ElabInt = RtlParam.int("COUNT", 3, 1, 17)
        val source = in(Vec(Bits(8 bits), count))
        val result = out(Vec(Bits(8 bits), count))
        if (short) {
          for (i <- 0 until count) result(i) := ~source(i)
        } else ElabFiniteRange.foreach(count, "reference") { i =>
          i(result) := ~i(source)
        }
      } }
      dir -> new String(Files.readAllBytes(dir.resolve("LightweightLoop.v")), "UTF-8")
    }
    for (short <- Seq(false, true)) {
      val (dir, rtl) = generateLoop(short)
      assert(rtl.contains("for (") && rtl.contains("COUNT"), rtl)
      assert(rtl == generateLoop(short)._2)
      val instances = Seq(1,2,3,5,7,8,15,16,17).map(n =>
        s"Check #(.N($n)) c_$n(data);").mkString("\n")
      Files.write(dir.resolve("tb.v"), s"""module Check #(parameter N=1)(input [135:0] data);
wire [N*8-1:0] result;
LightweightLoop #(.COUNT(N)) dut(.source(data[N*8-1:0]),.result(result));
always @(data) begin #1; if(result !== ~data[N*8-1:0]) $$fatal; end
endmodule
module tb;
reg [135:0] data; integer j;
$instances
initial begin
 data=0; #3; data=~136'd0; #3; data='bx; #3; data='bz; #3;
 for(j=0;j<64;j=j+1) begin data={$$random,$$random,$$random,$$random,$$random}; #3; end
 $$finish;
end
endmodule
""".getBytes("UTF-8"))
      for (command <- Seq(Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "LightweightLoop.v", "tb.v"),
          Seq("vvp", "sim"), Seq("yosys", "-Q", "-T", "-p",
            "read_verilog LightweightLoop.v; hierarchy -check -top LightweightLoop; synth -top LightweightLoop; check -assert; select -assert-none t:$dlatch"))) {
        val (code, log) = Increment66ToolEvidence.run(dir, command)
        assert(code == 0, s"$dir\n$log")
      }
    }
  }
  test("a scoped Vec selector cannot escape its generate loop") {
    val dir = Files.createTempDirectory("lightweight-escaped-index-")
    val error = intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString),
        preserveConstantVecs = true, preserveConstantLoops = true,
        vecLayout = MorphAggregateOptions.UnpackedArray)) { new Component {
        val count: ElabInt = RtlParam.int("COUNT", 3, 1, 17)
        val source = in(Vec(Bits(8 bits), count))
        val result = out(Vec(Bits(8 bits), count))
        val escaped = out Bits(8 bits)
        var saved: ElabScopedIndex = null
        ElabFiniteRange.foreach(count, "runtime_escape") { i =>
          saved = i
          result(i) := source(i)
        }
        escaped := source(saved)
      } }
    }
    assert(error.getMessage.contains("STRUCTURAL-CONSUMER-OUTSIDE-CAPTURE"), error.getMessage)
  }

}
