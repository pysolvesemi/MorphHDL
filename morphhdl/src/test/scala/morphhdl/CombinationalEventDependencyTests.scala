package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

/** Small event-simulation regressions; no application sources or transformed RTL. */
class CombinationalEventDependencyTests extends AnyFunSuite {
  private def check(unpacked: Boolean, body: String)(top: => Component): Unit = {
    val dir = Files.createTempDirectory("combinational-events-")
    val config = MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
      headerWithDate = false), preserveConstantVecs = true,
      vecLayout = if (unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)
    MorphVerilog(config)(top)
    Files.write(dir.resolve("tb.v"), body.getBytes(UTF_8))
    for (command <- Seq(
      Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "Top.v", "tb.v"),
      Seq("timeout", "5", "vvp", "sim"),
      Seq("verilator", "--lint-only", "-Wall", "--language", "1364-2001", "--top-module", "Top", "Top.v"),
      Seq("yosys", "-Q", "-p", "read_verilog Top.v; synth -top Top; check -assert"))) {
      val (code, log) = Increment66ToolEvidence.run(dir, command)
      assert(code == 0, s"$command failed ($code) in $dir\n$log")
    }
  }

  class Chain extends Component {
    setDefinitionName("Top")
    val enable = in Bool()
    val data = in UInt(8 bits)
    val result = out UInt(8 bits)
    val cells = Vec(UInt(8 bits), 3)
    cells.foreach(_.dontSimplifyIt())
    cells(0) := data
    for (i <- 1 until 3) {
      cells(i) := 0
      when(enable) { cells(i) := cells(i - 1) }
    }
    result := cells(2)
  }

  test("split structural processes keep only visible events and retain masked-condition initialization") {
    check(false, """module tb;
reg enable=0; reg inner; reg [7:0] data; wire [7:0] up,down;
Top #(.MODE(0)) a(enable,inner,data,up);
Top #(.MODE(1)) b(enable,inner,data,down);
initial begin #1; if(up!==0 || down!==0) $fatal(1,"split initialization");
inner=1; data=9; enable=1; #1; if(up!==10 || down!==8) $fatal;
enable=0; inner=1'bx; #1; if(up!==0 || down!==0) $fatal;
enable=1; inner=1'bz; #1; if(up!==0 || down!==0) $fatal;
$finish; end
endmodule
""")(new Component {
      setDefinitionName("Top")
      @dontName val mode: ElabInt = morphhdl.frontend.HdlInt.param("MODE", 0, 0, 1).asElabInt
      @dontName val width = morphhdl.frontend.HdlInt.param("WIDTH", 8, 1, 8).asElabInt
      val enable = in Bool()
      val inner = in Bool()
      val data = in UInt(width bits)
      val result = out UInt(width bits)
      val condition = (enable && inner).dontSimplifyIt()
      if (mode < 1) {
        val branchDefault = ElabValue.uintLike(ElabInt.literal(0), result, "")
        result := branchDefault
        val branchValue = UInt(width bits).dontSimplifyIt()
        branchValue := data + 1
        when(condition) { result := branchValue }
      } else {
        val branchDefault = ElabValue.uintLike(ElabInt.literal(0), result, "")
        result := branchDefault
        val branchValue = UInt(width bits).dontSimplifyIt()
        branchValue := data - 1
        when(condition) { result := branchValue }
      }
    })
  }

  test("a masked unknown condition still initializes its combinational default") {
    check(false, """module tb;
reg enable=0; reg inner; reg [7:0] data; wire [7:0] result;
Top dut(enable,inner,data,result);
initial begin #1; if(result!==0) $fatal(1,"masked condition default");
inner=1; data=9; enable=1; #1; if(result!==9) $fatal;
enable=0; #1; if(result!==0) $fatal;
$finish; end
endmodule
""")(new Component {
      setDefinitionName("Top")
      val width = morphhdl.frontend.HdlInt.param("WIDTH", 8, 8, 16).asElabInt
      val enable = in Bool()
      val inner = in Bool()
      val data = in UInt(width bits)
      val result = out UInt(width bits)
      val condition = (enable && inner).dontSimplifyIt()
      result := 0
      when(condition) { result := data }
    })
  }

  test("a false generated condition initializes a feedback counter's default") {
    check(false, """module tb;
reg clk=0; always #5 clk=~clk;
reg reset=1; reg enable=0; reg [7:0] need=0;
wire [7:0] count,take;
Top dut(clk,reset,enable,need,count,take);
initial begin #2; reset=0; #10;
if(count!==0 || take!==0) $fatal(1,"counter startup default");
enable=1; #10; if(count!==0 || take!==0) $fatal;
$finish; end
endmodule
""")(new Component {
      setDefinitionName("Top")
      val width = morphhdl.frontend.HdlInt.param("WIDTH", 8, 8, 16).asElabInt
      val clk, reset, enable = in Bool()
      val need = in UInt(width bits)
      val count, take = out UInt(width bits)
      val area = new ClockingArea(ClockDomain(clk, reset, config = ClockDomainConfig(resetKind = ASYNC))) {
        val held = Reg(UInt(width bits)) init(0)
        val enough = need <= held
        take := 0
        when(enable && enough) { take := need }
        held := held - take
        count := held
      }
    })
  }

  for (unpacked <- Seq(false, true)) {
    test(s"signed event cells preserve dynamic read views with unpacked=$unpacked") {
      check(unpacked, """module tb;
reg enable; reg signed [7:0] data; reg [1:0] index; wire signed [7:0] result;
Top dut(enable,data,index,result);
initial begin enable=0; data=-3; index=0; #1; if(result!==-8'sd3) $fatal;
index=2; #1; if(result!==0) $fatal;
enable=1; #1; if(result!==-8'sd3) $fatal;
data=-9; #1; if(result!==-8'sd9) $fatal;
index=1; #1; if(result!==-8'sd9) $fatal;
enable=0; #1; if(result!==0) $fatal;
$finish; end
endmodule
""")(new Component {
        setDefinitionName("Top")
        val enable = in Bool()
        val data = in SInt(8 bits)
        val index = in UInt(2 bits)
        val result = out SInt(8 bits)
        val cells = Vec(SInt(8 bits), 3)
        cells.foreach(_.dontSimplifyIt())
        cells(0) := data
        for (i <- 1 until 3) {
          cells(i) := 0
          when(enable) { cells(i) := cells(i - 1) }
        }
        result := cells(index)
      })
    }
    test(s"acyclic conditional cell chain settles with unpacked=$unpacked") {
      check(unpacked, """module tb;
reg enable; reg [7:0] data; wire [7:0] result;
Top dut(.enable(enable), .data(data), .result(result));
initial begin
  enable=0; data=3; #1;
  if(result !== 0) $fatal(1, "disabled chain");
  enable=1; #1;
  if(result !== 3) $fatal(1, "enabled chain");
  data=9; #1;
  if(result !== 9) $fatal(1, "changed input");
  enable=0; #1;
  if(result !== 0) $fatal(1, "disabled again");
  $display("PASS"); $finish;
end
endmodule
""")(new Chain)
    }
  }
}
