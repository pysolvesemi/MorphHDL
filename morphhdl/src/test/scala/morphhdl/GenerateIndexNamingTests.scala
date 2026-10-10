package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import morphhdl.frontend.HdlInt

class GenerateIndexNamingTests extends AnyFunSuite {
  private class ParameterizedCopy(count: ElabInt) extends Component {
    setDefinitionName("Top")
    val x = in(Vec(Bits(8 bits), count))
    val y = out(Vec(Bits(8 bits), count))
    ElabFiniteRange.foreach(count, "copy") { lane => lane(y) := lane(x) }
  }
  private def emit(unpacked: Boolean, split: Boolean)(top: => Component): (java.nio.file.Path, String) = {
    val dir = Files.createTempDirectory("generate-index-names-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
      headerWithDate = false, oneFilePerComponent = split), preserveConstantVecs = true,
      preserveConstantLoops = true,
      vecLayout = if (unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector))(top)
    dir -> new String(Files.readAllBytes(dir.resolve("Top.v")), UTF_8)
  }
  private def verify(dir: java.nio.file.Path, bench: String): Unit = {
    Files.write(dir.resolve("tb.v"), bench.getBytes(UTF_8))
    Seq(
      Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "Top.v", "tb.v"),
      Seq("timeout", "10", "vvp", "sim"),
      Seq("verilator", "--lint-only", "-Wall", "--language", "1364-2001", "--top-module", "Top", "Top.v"),
      Seq("yosys", "-Q", "-p", "read_verilog Top.v; synth -top Top; check -assert")
    ).foreach { command =>
      val (code, log) = Increment66ToolEvidence.run(dir, command)
      assert(code == 0, s"$command failed in $dir\n$log")
    }
  }
  private def declarations(rtl: String): Vector[String] =
    "(?m)^\\s*genvar\\s+(\\w+)\\s*;".r.findAllMatchIn(rtl).map(_.group(1)).toVector

  for (unpacked <- Seq(false, true)) {
    test(s"parameter declaration reserves i at all admitted loop bounds unpacked=$unpacked") {
      def top = new ParameterizedCopy(HdlInt.param("i", 4, 2, 6).asElabInt)
      val (dir, rtl) = emit(unpacked, false)(top)
      assert(declarations(rtl) == Vector("j"), rtl)
      assert(rtl == emit(unpacked, false)(top)._2)
      verify(dir, """module tb;
reg [15:0] x2=16'h1234; wire [15:0] y2;
reg [31:0] x4=32'h89abcdef; wire [31:0] y4;
reg [47:0] x6=48'h0123456789ab; wire [47:0] y6;
Top #(.i(2)) a(.x(x2),.y(y2));
Top #(.i(4)) b(.x(x4),.y(y4));
Top #(.i(6)) c(.x(x6),.y(y6));
initial begin #1; if(y2!==x2 || y4!==x4 || y6!==x6) $fatal;
x2=~x2; x4=~x4; x6=~x6; #1;
if(y2!==x2 || y4!==x4 || y6!==x6) $fatal; $finish; end
endmodule
""")
    }
  }

  for (unpacked <- Seq(false, true); shadow <- Seq(false, true)) {
    test(s"nested loops use distinct visible indices unpacked=$unpacked shadow=$shadow") {
      def top = new Component {
        setDefinitionName("Top")
        val x = in(Vec(Bits(8 bits), 3))
        val biases = in(Vec(Bits(8 bits), 2))
        val y = out(Vec(Bits(8 bits), 2))
        ElabFiniteRange.foreach(ElabInt.literal(2), "rows") { row =>
          val local = Vec(Bits(8 bits), 3)
          if (shadow) local.setName("i")
          ElabFiniteRange.foreach(ElabInt.literal(3), "columns") { column =>
            column(local) := column(x) ^ row(biases)
          }
          row(y) := local.reduceBalancedTree((a: Bits, b: Bits) => a ^ b)
        }
      }
      val (dir, rtl) = emit(unpacked, false)(top)
      assert(declarations(rtl) == (if (shadow) Vector("j", "k") else Vector("i", "j")), rtl)
      assert(rtl == emit(unpacked, false)(top)._2)
      verify(dir, """module tb;
reg [23:0] x; reg [15:0] biases; wire [15:0] y;
Top dut(.x(x),.biases(biases),.y(y));
initial begin x=24'h123456; biases=16'h789a; #1;
if(y!==({2{8'h12 ^ 8'h34 ^ 8'h56}} ^ biases)) $fatal;
x=24'hfedcba; biases=16'h5678; #1;
if(y!==({2{8'hfe ^ 8'hdc ^ 8'hba}} ^ biases)) $fatal; $finish; end
endmodule
""")
    }
  }

  for (unpacked <- Seq(false, true); split <- Seq(false, true)) {
    test(s"single automatic loop publishes i deterministically unpacked=$unpacked split=$split") {
      def top = new Component {
        setDefinitionName("Top")
        val x = in(Vec(Bits(8 bits), 4))
        val y = out(Vec(Bits(8 bits), 4))
        ElabFiniteRange.foreach(ElabInt.literal(4), "copy_lanes") { n => n(y) := n(x) }
      }
      val (dir, rtl) = emit(unpacked, split)(top)
      assert(declarations(rtl) == Vector("i"), rtl)
      assert(rtl.contains("begin : g_copy_lanes_"), rtl)
      assert(rtl == emit(unpacked, split)(top)._2)
      verify(dir, """module tb;
reg [31:0] x; wire [31:0] y;
Top dut(.x(x),.y(y));
initial begin x=32'h12345678; #1; if(y!==x) $fatal;
x=32'h89abcdef; #1; if(y!==x) $fatal; $finish; end
endmodule
""")
    }
  }

  for (other <- Seq(3, 4)) {
    test(s"sibling repeated labels share one genvar with second bound $other") {
      val (dir, rtl) = emit(true, false)(new Component {
        setDefinitionName("Top")
        val x = in(Vec(Bits(8 bits), 4)); val y = out(Vec(Bits(8 bits), 4))
        val a = in(Vec(Bits(8 bits), other)); val b = out(Vec(Bits(8 bits), other))
        ElabFiniteRange.foreach(ElabInt.literal(4), "copy") { n => n(y) := n(x) }
        ElabFiniteRange.foreach(ElabInt.literal(other), "copy") { n => n(b) := n(a) }
      })
      assert(declarations(rtl) == Vector("i"), rtl)
      assert("for \\(i = 0;".r.findAllIn(rtl).size == 2, rtl)
      val labels = "begin : (g_copy_\\w+)".r.findAllMatchIn(rtl).map(_.group(1)).toVector
      assert(labels.size == 2 && labels.distinct.size == 2, rtl)
      verify(dir, s"""module tb;
reg [31:0] x; wire [31:0] y; reg [${other * 8 - 1}:0] a; wire [${other * 8 - 1}:0] b;
Top dut(.x(x),.y(y),.a(a),.b(b));
initial begin x=32'h12345678; a='h987654; #1; if(y!==x || b!==a) $$fatal; $$finish; end
endmodule
""")
    }
  }

  test("authored i and j ports reserve their identifiers") {
    val (dir, rtl) = emit(true, false)(new Component {
      setDefinitionName("Top")
      val i,j = in Bits(8 bits)
      val x = in(Vec(Bits(8 bits), 4)); val y = out(Vec(Bits(8 bits), 4))
      ElabFiniteRange.foreach(ElabInt.literal(4), "copy") { n => n(y) := n(x) ^ i ^ j }
    })
    assert(declarations(rtl) == Vector("k"), rtl)
    verify(dir, """module tb;
reg [7:0] i=8'h12,j=8'h34; reg [31:0] x=32'habcdef01; wire [31:0] y;
Top dut(.i(i),.j(j),.x(x),.y(y));
initial begin #1; if(y!==(x ^ {4{i ^ j}})) $fatal; $finish; end
endmodule
""")
  }
}
