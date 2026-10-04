package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.{HdlInt, HdlIntRangeStart, StructuralVecOps, StructuralBitVectorOps}

/** Native generation and execution of the independently reported typed-loop forms. */
class TypedLoopCarrierCoverageTests extends AnyFunSuite {
  private class ParameterizedRegisters(count: HdlInt, structural: Boolean) extends Component {
    setDefinitionName("Top")
    val x = in(Vec(Bits(8 bits), count.asElabInt))
    val y = out(Vec(Bits(8 bits), count.asElabInt))
    val en = in Bool()
    val values = Vec.fill(count.asElabInt)(Reg(Bits(8 bits)) init 0)
    if (structural) ElabFiniteRange.foreach(count.asElabInt, "copy_lanes") { i =>
      when(en) { i(values) := i(x) }
    } else when(en) {
      (0 until count).named("copy_lanes", "lane").foreach { i => values(i) := x(i) }
    }
    y := values
  }
  private class ParameterizedPacking(count: HdlInt, partial: Boolean) extends Component {
    setDefinitionName("Top")
    val x = in(Vec(Bits(8 bits), count.asElabInt))
    val y = out Bits((if (partial) ElabInt.literal(32) else (count * HdlInt.literal(8)).asElabInt) bits)
    (0 until count).named("pack_words", "word").foreach { i =>
      y(i * HdlInt.literal(8), HdlInt.literal(8)) := x(i)
    }
  }
  private def check(bench: String, requireLoop: Boolean = true,
      unpacked: Boolean = true)(top: => Component): Unit = {
    val dir = Files.createTempDirectory("typed-loop-carrier-coverage-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
      headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
      vecLayout = if (unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector))(top)
    val rtl = new String(Files.readAllBytes(dir.resolve("Top.v")), UTF_8)
    if (requireLoop) assert("\\bfor\\s*\\(".r.findFirstIn(rtl).nonEmpty, rtl)
    Files.write(dir.resolve("tb.v"), bench.getBytes(UTF_8))
    Seq(
      Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "Top.v", "tb.v"),
      Seq("timeout", "10", "vvp", "sim"),
      Seq("verilator", "--lint-only", "-Wall", "--language", "1364-2001", "--top-module", "Top", "Top.v"),
      Seq("yosys", "-Q", "-p", "read_verilog Top.v; synth -top Top; check -assert")
    ).foreach { command =>
      val (code, log) = Increment66ToolEvidence.run(dir, command)
      assert(code == 0, s"$command failed ($code) in $dir\n$log")
    }
  }

  for (structural <- Seq(true, false); unpacked <- Seq(false, true)) {
    test(s"typed initialized register copy preserves reset enable hold and lane order structural=$structural unpacked=$unpacked") {
      check("""module tb;
reg clk=0; always #5 clk=~clk;
reg reset=0,en=0; reg [31:0] x=32'h12345678; wire [31:0] y;
Top dut(.clk(clk),.reset(reset),.en(en),.x(x),.y(y));
initial begin
#1; reset=1; #1; if(y!==0) $fatal(1,"asynchronous reset");
#5; reset=0; #9; if(y!==0) $fatal(1,"disabled hold after reset");
en=1; #10; if(y!==x) $fatal(1,"all lanes loaded in order");
en=0; x=32'hdeadbeef; #10; if(y!==32'h12345678) $fatal(1,"hold");
en=1; #10; if(y!==x) $fatal(1,"second load");
reset=1; #1; if(y!==0) $fatal(1,"reset overrides enabled data");
#5; reset=0; #10; if(y!==x) $fatal(1,"load after reset");
$finish; end
endmodule
""", unpacked = unpacked)(new Component {
        setDefinitionName("Top")
        val x = in(Vec(Bits(8 bits), 4))
        val y = out(Vec(Bits(8 bits), 4))
        val en = in Bool()
        val values = Vec.fill(4)(Reg(Bits(8 bits)) init 0)
        if (structural) {
          ElabFiniteRange.foreach(ElabInt.literal(4), "copy_lanes") { i =>
            when(en) { i(values) := i(x) }
          }
        } else {
          when(en) {
            (0 until HdlInt.literal(4)).named("copy_lanes", "lane").foreach { i => values(i) := x(i) }
          }
        }
        y := values
      })
    }
  }

  for (structural <- Seq(true, false); unpacked <- Seq(false, true)) {
    test(s"parameterized register copy covers reset enable and hold at boundary overrides structural=$structural unpacked=$unpacked") {
      check("""module tb;
reg clk=0; always #5 clk=~clk;
reg reset=0,en=0;
reg [15:0] x2=16'h1234; wire [15:0] y2;
reg [31:0] x4=32'h89abcdef; wire [31:0] y4;
reg [47:0] x6=48'h0123456789ab; wire [47:0] y6;
Top #(.N(2)) a(.clk(clk),.reset(reset),.en(en),.x(x2),.y(y2));
Top #(.N(4)) b(.clk(clk),.reset(reset),.en(en),.x(x4),.y(y4));
Top #(.N(6)) c(.clk(clk),.reset(reset),.en(en),.x(x6),.y(y6));
initial begin #1; reset=1; #1;
if(y2!==0 || y4!==0 || y6!==0) $fatal;
#5; reset=0; #9; if(y2!==0 || y4!==0 || y6!==0) $fatal;
en=1; #10; if(y2!==x2 || y4!==x4 || y6!==x6) $fatal;
en=0; x2=~x2; x4=~x4; x6=~x6; #10;
if(y2!==16'h1234 || y4!==32'h89abcdef || y6!==48'h0123456789ab) $fatal;
en=1; #10; if(y2!==x2 || y4!==x4 || y6!==x6) $fatal;
reset=1; #1; if(y2!==0 || y4!==0 || y6!==0) $fatal;
#5; reset=0; #10; if(y2!==x2 || y4!==x4 || y6!==x6) $fatal;
$finish; end
endmodule
""", unpacked = unpacked)(new ParameterizedRegisters(HdlInt.param("N", 4, 2, 6), structural))
    }
  }

  test("full typed Vec-to-packed coverage has no latch and preserves byte order") {
    check("""module tb;
reg [31:0] x; wire [31:0] y;
Top dut(.x(x),.y(y));
initial begin
x=32'h12345678; #1; if(y!==x) $fatal;
x=32'hdeadbeef; #1; if(y!==x) $fatal;
x=0; #1; if(y!==0) $fatal;
$finish; end
endmodule
""")(new Component {
      setDefinitionName("Top")
      val x = in(Vec(Bits(8 bits), 4))
      val y = out Bits(32 bits)
      (0 until HdlInt.literal(4)).named("pack_words", "word").foreach { i =>
        y(i * HdlInt.literal(8), HdlInt.literal(8)) := x(i)
      }
    })
  }

  test("typed byte mask retains every asBools carrier") {
    check("""module tb;
reg [3:0] strobes; wire [31:0] mask; integer i,j; reg [31:0] expected;
Top dut(.strobes(strobes),.mask(mask));
initial begin
for(i=0;i<16;i=i+1) begin
strobes=i; expected=0;
for(j=0;j<4;j=j+1) if((i >> j)&1) expected[j*8 +: 8]=8'hff;
#1; if(mask!==expected) $fatal;
end
$finish; end
endmodule
""")(new Component {
      setDefinitionName("Top")
      val strobes = in Bits(4 bits)
      val mask = out Bits(32 bits)
      val bits = strobes.asBools
      val bytes = Vec(Bits(8 bits), 4)
      ElabFiniteRange.foreach(ElabInt.literal(4), "write_byte_mask") { i =>
        i(bytes) := Mux(i(bits), B(255, 8 bits), B(0, 8 bits))
      }
      mask := bytes.asBits
    })
  }

  test("asBools retains writable views of the original packed signal") {
    check("""module tb;
reg [7:0] x; wire [7:0] y; integer pattern;
Top dut(.x(x),.y(y));
initial begin for(pattern=0;pattern<256;pattern=pattern+1) begin
x=pattern; #1; if(y!==x) $fatal;
end $finish; end
endmodule
""", requireLoop = false)(new Component {
      setDefinitionName("Top")
      val x = in(Vec(Bool(), 8))
      val y = out Bits(8 bits)
      // Exercise the cached writable views without requesting preservation of
      // unused, explicitly named readback wires via a component member val.
      for (i <- 0 until 8) y.asBools(i) := x(i)
    })
  }

  test("conditional full typed packing still requires a default") {
    val dir = Files.createTempDirectory("typed-loop-conditional-")
    val error = intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString),
        preserveConstantVecs = true, preserveConstantLoops = true))(new Component {
        val x = in(Vec(Bits(8 bits), 4))
        val y = out Bits(32 bits)
        val en = in Bool()
        when(en) {
          (0 until HdlInt.literal(4)).named("pack", "word").foreach { i =>
            y(i * HdlInt.literal(8), HdlInt.literal(8)) := x(i)
          }
        }
      })
    }
    assert(Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_ != null)
      .exists(e => Option(e.getMessage).exists(_.contains("LATCH DETECTED"))), error.toString)
  }

  test("parameterized Vec packing covers every lane at non-default bounds") {
    check("""module tb;
reg [15:0] x2=16'h1234; wire [15:0] y2;
reg [31:0] x4=32'h89abcdef; wire [31:0] y4;
reg [47:0] x6=48'h0123456789ab; wire [47:0] y6;
Top #(.N(2)) a(.x(x2),.y(y2));
Top #(.N(4)) b(.x(x4),.y(y4));
Top #(.N(6)) c(.x(x6),.y(y6));
initial begin #1; if(y2!==x2 || y4!==x4 || y6!==x6) $fatal;
x2=~x2; x4=~x4; x6=~x6; #1;
if(y2!==x2 || y4!==x4 || y6!==x6) $fatal; $finish; end
endmodule
""")(new ParameterizedPacking(HdlInt.param("N", 4, 2, 6), partial = false))
  }

  for (symbolic <- Seq(false, true)) {
    test(s"partial typed packing remains a latch error including smaller override symbolic=$symbolic") {
      val dir = Files.createTempDirectory("typed-loop-partial-")
      val error = intercept[Exception] {
        MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString),
          preserveConstantVecs = true, preserveConstantLoops = true,
          vecLayout = MorphAggregateOptions.UnpackedArray))(new ParameterizedPacking(
            if (symbolic) HdlInt.param("N", 4, 2, 4) else HdlInt.literal(3), partial = true))
      }
      assert(Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_ != null)
        .exists(e => Option(e.getMessage).exists(_.contains("LATCH DETECTED"))), error.toString)
    }
  }
}
