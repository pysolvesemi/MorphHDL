package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class SymbolicPackedWordsTests extends AnyFunSuite {
  private def generate(mode: String) = {
    val dir = Files.createTempDirectory("symbolic-packed-words-")
    MorphVerilog(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false,
      flags = scala.collection.mutable.HashSet[Any](VerilogAggregateOptions(
        preserveConstantVecs = true, preserveConstantLoops = true,
        vecLayout = VerilogAggregateOptions.UnpackedArray)))) { new Component {
      setDefinitionName("Words")
      @dontName val count = ElabFormalParameter(ElabInt.literal(3), "COUNT", 1, 17)
      @dontName val width = ElabFormalParameter(ElabInt.literal(8), "WIDTH", if(mode == "overlap") 2 else 1, 64)
      val source = in Bits((count * width) bits)
      val result = out Bits((count * width) bits)
      val lanes = Vec(SInt(width bits), count)
      lanes.assignFromBits(source)
      if(mode == "raw") result := source(0, 8 bits).resized
      else if(mode == "named") result := lanes.asBits.setName("authored_packed")
      else if(mode == "shared") {
        val packed = lanes.asBits
        val echo = out Bits((count * width) bits)
        result := packed
        echo := packed
      }
      else if (mode == "alias") result := lanes.asBits
      else ElabFiniteRange.foreach(if(mode == "bounds") count + 1 else count, "words") { i =>
        val stride = if(mode == "overlap") width - 1 else width
        i.packed(result, stride, width bits) := ~(if(mode == "direct") i.packed(source, width, width bits) else i(lanes).asBits)
      }
    } }
    dir -> new String(Files.readAllBytes(dir.resolve("Words.v")), "UTF-8")
  }
  for(mode <- Seq("fused", "direct", "alias")) test(s"symbolic whole-word packing $mode") {
    val (dir, rtl) = generate(mode)
    assert(rtl == generate(mode)._2)
    assert(!rtl.contains(" / ") && !rtl.contains(" % "), rtl)
    if(mode == "alias") {
      assert(!rtl.contains("typed_vec_packed_carrier"), rtl)
      assert(!rtl.contains("typed_vec_packed_result"), rtl)
      assert(!rtl.contains("packed_view"), rtl)
    }
    val instances = (for(n <- Seq(1,2,3,5,17); w <- Seq(1,3,8,36,64)) yield
      s"Case #(.N($n),.W($w)) c_${n}_$w(data);").mkString("\n")
    Files.write(dir.resolve("tb.v"), s"""module Case #(parameter N=3,W=8)(input [1087:0] data);
wire [N*W-1:0] result;
Words #(.COUNT(N),.WIDTH(W)) dut(.source(data[N*W-1:0]),.result(result));
always @(data) begin #1; if(result !== ${if(mode=="alias") "" else "~"}data[N*W-1:0]) $$fatal; end
endmodule
module tb;
reg [1087:0] data; integer n,j;
$instances
initial begin
 data=0; #3; data=~1088'd0; #3; data='bx; #3; data='bz; #3;
 for(n=0;n<100;n=n+1) begin for(j=0;j<34;j=j+1) data[j*32+:32]=$$random; #3; end
 $$finish;
end
endmodule
""".getBytes("UTF-8"))
    for(command <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","Words.v","tb.v"),
        Seq("vvp","sim"), Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","Words","Words.v"),
        Seq("yosys","-Q","-T","-p","read_verilog Words.v; hierarchy -check -top Words; synth -top Words; check -assert; select -assert-none t:$dlatch"))) {
      val (code,log) = Increment66ToolEvidence.run(dir, command)
      assert(code == 0, s"$dir $command\n$log")
      if(command.head == "verilator")
        assert(!log.contains("%Warning-IMPLICIT") && !log.contains("%Warning-SELRANGE"), log)
    }
  }
  test("ordinary fixed slices do not inherit finite-index authority") {
    val error = intercept[Exception](generate("raw"))
    assert(error.getMessage.contains("SLICE-DOMAIN-UNSUPPORTED"), error.getMessage)
  }
  test("explicitly named packed results retain their transport") {
    val (_, rtl) = generate("named")
    assert(rtl.contains("authored_packed") && rtl.contains("typed_vec_packed_carrier"), rtl)
  }
  test("independently used packed results retain their transport") {
    val (_, rtl) = generate("shared")
    assert(rtl.contains("typed_vec_packed_result"), rtl)
  }
  test("out-of-domain symbolic word selection rejects") {
    val error = intercept[Exception](generate("bounds"))
    assert(error.getMessage.contains("SLICE-DOMAIN-UNSUPPORTED"), error.getMessage)
  }
  test("overlapping symbolic word writes reject") {
    val error = intercept[Exception](generate("overlap"))
    assert(error.getMessage.contains("finite packed writes must be disjoint"), error.getMessage)
  }
}
