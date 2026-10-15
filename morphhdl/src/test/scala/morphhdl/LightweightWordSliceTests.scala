package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class LightweightWordSliceTests extends AnyFunSuite {
  private def generate(default: Int, symbolic: Boolean) = {
    val directory = Files.createTempDirectory("lightweight-words-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true)) {
      new Component {
        setDefinitionName("WordSlices")
        val count: ElabInt = RtlParam.int("COUNT", default, 1, 17)
        val width: ElabInt = if (symbolic) RtlParam.int("WIDTH", 8, 1, 64) else ElabInt.literal(8)
        val source = in Bits((count * width) bits)
        val result = out Bits((count * width) bits)
        for (i <- 0 until count) {
          if (symbolic) result(i * width, width bits) := ~source(i * width, width bits)
          else result(i * 8, 8 bits) := ~source(i * 8, 8 bits)
        }
      }
    }
    directory -> new String(Files.readAllBytes(directory.resolve("WordSlices.v")), "UTF-8")
  }
  for (default <- Seq(1, 8); symbolic <- Seq(false, true)) {
    test(s"ordinary word slices retain exact geometry default=$default symbolic=$symbolic") {
      val (directory, rtl) = generate(default, symbolic)
      assert(rtl == generate(default, symbolic)._2)
      assert(rtl.contains("for (") && rtl.contains("+:"), rtl)
      val profiles = for (n <- Seq(1, 2, 3, 5, 7, 8, 15, 16, 17);
          w <- (if (symbolic) Seq(1, 8, 32, 36, 64) else Seq(8))) yield
        s"Check #(.N($n),.W($w)) c_${n}_$w(data);"
      Files.write(directory.resolve("tb.v"), s"""module Check #(parameter N=1,W=8)(input [1087:0] data);
wire [N*W-1:0] result;
WordSlices #(.COUNT(N)${if (symbolic) ",.WIDTH(W)" else ""}) dut(.source(data[N*W-1:0]),.result(result));
always @(data) begin #1; if(result !== ~data[N*W-1:0]) $$fatal; end
endmodule
module tb; reg [1087:0] data; integer j,k;
${profiles.mkString("\n")}
initial begin data=0; #3; data=~1088'd0; #3; data='bx; #3; data='bz; #3;
for(j=0;j<100;j=j+1) begin for(k=0;k<34;k=k+1) data[k*32+:32]=$$random; #3; end
$$finish; end endmodule
""".getBytes("UTF-8"))
      for (command <- Seq(Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "WordSlices.v", "tb.v"),
          Seq("vvp", "sim"), Seq("verilator", "--lint-only", "--language", "1364-2001", "-DSYNTHESIS", "-Wno-fatal", "WordSlices.v"),
          Seq("yosys", "-Q", "-T", "-p", "read_verilog WordSlices.v; hierarchy -check -top WordSlices; synth -top WordSlices; check -assert; select -assert-none t:$dlatch"))) {
        val (status, log) = Increment66ToolEvidence.run(directory, command)
        assert(status == 0, s"$directory\n$log")
      }
    }
  }
  test("escaped word offsets cannot recover loop authority") {
    val error = intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = Files.createTempDirectory("escaped-word-").toString),
        preserveConstantLoops = true)) { new Component {
        val count: ElabInt = RtlParam.int("COUNT", 3, 1, 8)
        val width: ElabInt = RtlParam.int("WIDTH", 8, 1, 64)
        val source = in Bits((count * width) bits)
        val result = out Bits(width bits)
        val kept = out Bits((count * width) bits)
        var escaped: ElabScopedWordOffset = null
        ElabFiniteRange.foreach(count, "runtime_word_escape") { i =>
          kept(i * width, width bits) := ~source(i * width, width bits)
          escaped = i * width
        }
        result := source(escaped, width bits)
      } }
    }
    assert(error.getMessage.contains("SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONSUMER-OUTSIDE-CAPTURE"), error.getMessage)
  }
}
