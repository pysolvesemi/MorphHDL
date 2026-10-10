package spinal.core.internals

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import morphhdl.Increment66ToolEvidence
import org.scalatest.funsuite.AnyFunSuite

class UnpackedDirectReadTests extends AnyFunSuite {
  private def rewrite(body: String, dimensions: Vector[String] = Vector("3")): String =
    ParameterizedVerilogUnpacked.rewrite(body + "\n",
      Vector(ParameterizedVerilogUnpacked.ArrayShape("storage", "8", dimensions)))

  private def check(rtl: String, body: String, lint: Boolean = true): Unit = {
    val dir = Files.createTempDirectory("unpacked-direct-read-")
    Files.write(dir.resolve("Top.v"), rtl.getBytes(UTF_8))
    Files.write(dir.resolve("tb.v"), body.getBytes(UTF_8))
    val commands = Seq(
      Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "Top.v", "tb.v"),
      Seq("vvp", "sim"),
      Seq("yosys", "-Q", "-T", "-p", "read_verilog Top.v; hierarchy -check -top Top; synth -top Top; check -assert")) ++
      (if(lint) Seq(Seq("verilator", "--lint-only", "-Wall", "--language", "1364-2001", "--top-module", "Top", "Top.v")) else Nil)
    commands.foreach { command =>
      val (status, output) = Increment66ToolEvidence.run(dir, command)
      assert(status == 0, command.mkString(" ") + "\n" + output + "\n" + rtl)
    }
  }

  test("aligned dynamic multi-element reads concatenate array elements and preserve invalid addresses") {
    val rtl = rewrite("""module Top(input [23:0] x, input [1:0] index, output [15:0] y);
  wire [23:0] storage;
  assign storage = x;
  assign y = storage[(index * 8) +: 16];
endmodule""")
    assert(!rtl.contains("function") && !rtl.contains("packed_view"), rtl)
    check(rtl, """module tb;
reg [23:0] x; reg [1:0] index; wire [15:0] y;
Top dut(x,index,y);
initial begin x=24'h563412; index=0; #1; if(y!==16'h3412) $fatal;
index=1; #1; if(y!==16'h5634) $fatal;
index=2; #1; if(y[7:0]!==8'h56 || y[15:8]!==8'hxx) $fatal;
index=3; #1; if(y!==16'hxxxx) $fatal; $finish; end
endmodule""")
  }

  test("unaligned windows concatenate actual array bits across element boundaries") {
    val rtl = rewrite("""module Top(input [23:0] x, input [4:0] index, output [4:0] y);
  wire [23:0] storage;
  assign storage = x;
  assign y = storage[index +: 5];
endmodule""")
    assert(!rtl.contains("function") && !rtl.contains("packed_view"), rtl)
    check(rtl, """module tb;
reg [23:0] x; reg [4:0] index; wire [4:0] y; integer i;
Top dut(x,index,y);
initial begin x=24'hd5a371;
for(i=0;i<24;i=i+1) begin index=i; #1; if(y!==x[index +: 5]) $fatal; end
$finish; end
endmodule""")
  }

  test("direct procedural reads observe blocking writes in the same activation") {
    val rtl = rewrite("""module Top(input [23:0] x, output reg [23:0] y, output reg [23:0] z);
  reg [23:0] storage;
  always @* begin
    storage[0 +: 8] = x[7:0];
    storage[8 +: 8] = x[15:8];
    storage[16 +: 8] = x[23:16];
    y = storage;
    storage[0 +: 8] = ~x[7:0];
    z = storage;
  end
endmodule""")
    assert(!rtl.contains("function") && !rtl.contains("packed_view"), rtl)
    check(rtl, """module tb;
reg [23:0] x; wire [23:0] y,z; integer i;
Top dut(x,y,z);
initial begin for(i=0;i<20;i=i+1) begin x=$random; #1;
if(y!==x || z!=={x[23:8],~x[7:0]}) $fatal; end $finish; end
endmodule""")
  }

  test("multidimensional whole reads retain low-element-first packing without helpers") {
    val rtl = rewrite("""module Top(input [47:0] x, output [47:0] y);
  wire [47:0] storage;
  assign storage = x;
  assign y = storage;
endmodule""", Vector("2", "3"))
    assert(!rtl.contains("function") && !rtl.contains("packed_view"), rtl)
    check(rtl, """module tb;
reg [47:0] x; wire [47:0] y;
Top dut(x,y);
initial begin x=48'hfedcba987654; #1; if(y!==x) $fatal;
x=48'h123456789abc; #1; if(y!==x) $fatal; $finish; end
endmodule""")
  }

  test("symbolic continuous reads slice the packed view without read functions") {
    val rtl = rewrite("""module Top #(parameter DEPTH=3)(input [8*DEPTH-1:0] x, output [8*DEPTH-1:0] y);
  wire [8*DEPTH-1:0] storage;
  assign storage = x;
  assign y = storage;
endmodule""", Vector("DEPTH"))
    assert(!rtl.contains("function") && rtl.contains("packed_view"), rtl)
    check(rtl, """module tb;
reg [39:0] x; wire [39:0] y;
Top #(.DEPTH(5)) dut(x,y);
initial begin x=40'h123456789a; #1; if(y!==x) $fatal; $finish; end
endmodule""")
  }

  test("symbolic procedural reads retain direct-memory helpers for blocking ordering") {
    val rtl = rewrite("""module Top #(parameter DEPTH=3)(input [8*DEPTH-1:0] x, output reg [8*DEPTH-1:0] y);
  reg [8*DEPTH-1:0] storage;
  integer i;
  always @* begin
    for(i=0;i<DEPTH;i=i+1) storage[(i * 8) +: 8] = x[(i * 8) +: 8];
    storage[0 +: 8] = ~x[7:0];
    y = storage;
  end
endmodule""", Vector("DEPTH"))
    assert(rtl.contains("function") && rtl.contains("read_dependency"), rtl)
    // This deliberately retained sensitivity witness is outside the helper-free lint gate.
    check(rtl, """module tb;
reg [39:0] x; wire [39:0] y; integer i;
Top #(.DEPTH(5)) dut(x,y);
initial begin for(i=0;i<20;i=i+1) begin x={$random,$random}; #1;
if(y!=={x[39:8],~x[7:0]}) $fatal; end $finish; end
endmodule""", lint = false)
  }
}
