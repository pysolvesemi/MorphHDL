package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class ConstantAggregateTests extends AnyFunSuite {
  private def emit(preserve: Boolean, loops: Boolean = false, unpacked: Boolean = false, named: Boolean = false)(top: => Component): String = {
    val dir = Files.createTempDirectory("constant-aggregate-")
    val config = MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
      headerWithDate = false), preserve, loops,
      if (unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)
    if (preserve || loops) MorphVerilog(if (named) MorphNamedFieldVectors.enable(config) else config)(top) else SpinalVerilog(config)(top)
    new String(Files.readAllBytes(dir.resolve("Top.v")), UTF_8)
  }

  class Flat extends Component {
    setDefinitionName("Top")
    val x = in(Vec(Bits(8 bits), 10))
    val y = out(Vec(Bits(8 bits), 10))
    y := x
  }
  class Nested extends Component {
    setDefinitionName("Top")
    val x = in(Vec.fill(4, 10)(Bits(8 bits)))
    val y = out(Vec.fill(4, 10)(Bits(8 bits)))
    y := x
  }
  class Loop extends Component {
    setDefinitionName("Top")
    val x = in(Vec(Bits(8 bits), 10))
    val y = out(Vec(Bits(8 bits), 10))
    ElabFiniteRange.foreach(ElabInt.literal(10), "constant_lanes") { i => i(y) := ~i(x) }
  }
  test("default concrete Vec publication stays flattened") {
    val rtl = emit(false)(new Flat)
    assert(rtl.contains("x_9") && rtl.contains("y_9"), rtl)
  }
  test("constant Vec ports retain factorized widths") {
    val rtl = emit(true)(new Flat)
    assert(rtl.contains("8 * 10"), rtl)
    assert(!rtl.contains("x_9") && !rtl.contains("y_9"), rtl)
  }
  test("nested constant Vec ports retain both dimensions") {
    val rtl = emit(true)(new Nested)
    assert(rtl.contains("10") && rtl.contains("4") && !rtl.contains("x_3_9"), rtl)
  }
  test("constant typed finite ranges can retain generate loops") {
    val rtl = emit(true, true)(new Loop)
    assert(rtl.contains("genvar") && rtl.contains("< 10"), rtl)
  }
  private def simulate(rtl: String, body: String): Unit = {
    val dir = Files.createTempDirectory("constant-aggregate-sim-")
    Files.write(dir.resolve("Top.v"), rtl.getBytes(UTF_8))
    Files.write(dir.resolve("tb.v"), body.getBytes(UTF_8))
    for (command <- Seq(Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "Top.v", "tb.v"), Seq("vvp", "sim"),
      Seq("yosys", "-Q", "-T", "-p", "read_verilog Top.v; hierarchy -check -top Top; synth -top Top; check -assert"))) {
      val result = Increment66ToolEvidence.run(dir, command)
      assert(result._1 == 0, result._2 + "\n" + rtl)
    }
  }
  class Internal extends Component {
    setDefinitionName("Top")
    val x = in(Vec(Bits(8 bits), 10))
    val y = out(Vec(Bits(8 bits), 10))
    val values = Vec(Bits(8 bits), 10)
    values.foreach(_.dontSimplifyIt())
    values := x
    y := values
  }
  class InternalNested extends Component {
    setDefinitionName("Top")
    val x = in(Vec.fill(4, 10)(Bits(8 bits)))
    val y = out(Vec.fill(4, 10)(Bits(8 bits)))
    val values = Vec.fill(4, 10)(Bits(8 bits))
    values.foreach(_.foreach(_.dontSimplifyIt()))
    values := x
    y := values
  }
  test("unpacked constant arrays retain packed ports and whole Vec behavior") {
    val rtl = emit(true, unpacked = true)(new Internal)
    assert(rtl.contains("values [0:(10)-1]"), rtl)
    simulate(rtl, """module tb;
      reg [79:0] x; wire [79:0] y; integer i;
      Top dut(.x(x),.y(y));
      initial begin for(i=0;i<100;i=i+1) begin
        x={$random,$random,$random}; #1;
        if(y !== x) $fatal(1,"array mismatch");
      end $finish; end
      endmodule""")
  }
  test("multidimensional unpacked arrays preserve all axes and element ordering") {
    val rtl = emit(true, unpacked = true)(new InternalNested)
    assert(rtl.contains("values [0:(4)-1][0:(10)-1]"), rtl)
    simulate(rtl, """module tb;
      reg [319:0] x; wire [319:0] y; integer i;
      Top dut(.x(x),.y(y));
      initial begin for(i=0;i<100;i=i+1) begin
        x={$random,$random,$random,$random,$random,$random,$random,$random,$random,$random}; #1;
        if(y !== x) $fatal(1,"nested array mismatch");
      end $finish; end
      endmodule""")
  }

  class State(parameterized: Boolean) extends Component {
    setDefinitionName("Top")
    @dontName val depth = if (parameterized) morphhdl.frontend.HdlInt.param("DEPTH", 10, 1, 10).asElabInt else ElabInt.literal(10)
    val wr = in UInt(4 bits)
    val rd = in UInt(4 bits)
    val en = in Bool()
    val data = in Bits(8 bits)
    val q = out Bits(8 bits)
    val y = out(Vec(Bits(8 bits), depth))
    val values = Vec({
      val word = Reg(Bits(8 bits))
      if (!parameterized) word.init(0)
      word.dontSimplifyIt()
      word
    }, depth)
    if (parameterized) values(wr) := data else when(en) { values(wr) := data }
    q := values(rd)
    y := values
  }
  test("unpacked register arrays preserve constant reset and parameterized dynamic writes") {
    for (parameterized <- Seq(false, true)) {
      val rtl = emit(true, unpacked = true)(new State(parameterized))
      val depth = if (parameterized) 3 else 10
      val resetPort = if (parameterized) "" else ".reset(reset),"
      val instance = if (parameterized) "Top #(.DEPTH(3))" else "Top"
      assert(rtl.contains("reg") && rtl.contains("values [0:"), rtl)
      simulate(rtl, s"""module tb;
        reg clk=0,reset=1,en=0; reg [3:0] wr=0,rd=0; reg [7:0] data=0;
        wire [7:0] q; wire [${8 * depth - 1}:0] y; reg [${8 * depth - 1}:0] expected=0; integer i;
        $instance dut(.clk(clk),$resetPort.wr(wr),.rd(rd),.en(en),.data(data),.q(q),.y(y));
        initial begin #1; clk=1; #1; clk=0; reset=0;
          for(i=0;i<40;i=i+1) begin
            wr=i % $depth; rd=wr; data=i*7; en=1;
            #1; clk=1; expected[wr*8+:8]=data; #1;
            if(q !== data || (i >= $depth && y !== expected)) $$fatal(1,"state mismatch");
            clk=0;
          end $$finish;
        end endmodule""")
    }
  }
  class Blocking extends Component {
    setDefinitionName("Top")
    val x = in Bits(16 bits)
    val y = out Bits(16 bits)
    val en = in Bool()
    val values = Vec(Bits(8 bits), 2)
    values.foreach(_.dontSimplifyIt())
    values(0) := x(7 downto 0)
    values(1) := x(15 downto 8)
    when(en) { values(1) := values(0) ^ B(0x5a, 8 bits) }
    y := values.asBits
  }
  test("unpacked combinational reads preserve blocking dependencies and bit packing") {
    val rtl = emit(true, unpacked = true)(new Blocking)
    simulate(rtl, """module tb;
      reg [15:0] x; reg en; wire [15:0] y; integer i;
      Top dut(.x(x),.y(y),.en(en));
      initial begin for(i=0;i<100;i=i+1) begin
        x=$random; en=i%2; #1;
        if(y !== (en ? {x[7:0]^8'h5a,x[7:0]} : x)) $fatal(1,"blocking mismatch");
      end $finish; end endmodule""")
  }
  class Procedural extends Component {
    import morphhdl.frontend._
    setDefinitionName("Top")
    val x = in(spinal.core.Bits(80 bits))
    val y = out(spinal.core.Bits(80 bits))
    val en = in Bool()
    y := 0
    when(en) {
      (0 until HdlInt.literal(10)).named("constant_process", "lane").foreach { i =>
        y(i * HdlInt.literal(8), HdlInt.literal(8)) := x(i * HdlInt.literal(8), HdlInt.literal(8))
      }
    }
  }
  test("typed constant ranges retain procedural loops with unchanged priority") {
    val rtl = emit(true, loops = true)(new Procedural)
    assert(rtl.contains("for (") && rtl.contains("integer"), rtl)
    simulate(rtl, """module tb;
      reg [79:0] x; reg en; wire [79:0] y; integer i;
      Top dut(.x(x),.y(y),.en(en));
      initial begin for(i=0;i<100;i=i+1) begin
        x={$random,$random,$random}; en=i%2; #1;
        if(y !== (en ? x : 80'b0)) $fatal(1,"procedural mismatch");
      end $finish; end endmodule""")
  }

  test("constant loop preservation retains only the Vec operands it needs") {
    val rtl = emit(false, loops = true)(new Loop)
    assert(rtl.contains("genvar") && !rtl.contains("x_9"), rtl)
  }

  test("aggregate options are isolated and replacement does not mutate configurations") {
    val base = SpinalConfig()
    val enabled = MorphAggregateOptions(base, true, true, MorphAggregateOptions.UnpackedArray)
    val disabled = MorphAggregateOptions(enabled)
    assert(VerilogAggregateOptions.of(base) == VerilogAggregateOptions())
    assert(VerilogAggregateOptions.of(enabled).preserveConstantVecs)
    assert(VerilogAggregateOptions.of(disabled) == VerilogAggregateOptions())
    assert(disabled.flags.count(_.isInstanceOf[VerilogAggregateOptions]) == 1)
  }

  class ArrayLoop extends Component {
    setDefinitionName("Top")
    val x = in(Vec(Bits(8 bits), 10))
    val y = out(Vec(Bits(8 bits), 10))
    val values = Vec(Bits(8 bits), 10).dontSimplifyIt()
    ElabFiniteRange.foreach(ElabInt.literal(10), "internal_lanes") { i => i(values) := ~i(x) }
    y := values
  }
  test("unpacked internal arrays work inside retained generate loops") {
    val rtl = emit(true, loops = true, unpacked = true)(new ArrayLoop)
    assert(rtl.contains("values [0:(10)-1]"), rtl)
    simulate(rtl, """module tb;
      reg [79:0] x; wire [79:0] y; integer i;
      Top dut(.x(x),.y(y));
      initial begin for(i=0;i<100;i=i+1) begin
        x={$random,$random,$random}; #1;
        if(y !== ~x) $fatal(1,"generate array mismatch");
      end $finish; end endmodule""")
  }

  case class Pair() extends Bundle { val a = Bits(8 bits); val b = Bits(4 bits) }
  class Records extends Component {
    setDefinitionName("Top")
    val x = in(Vec(Pair(), 3))
    val y = out(Vec(Pair(), 3))
    val values = Vec(Pair(), 3).dontSimplifyIt()
    values := x
    y := values
  }
  test("record arrays preserve packing in ordinary and named field layouts") {
    for (named <- Seq(false, true)) {
      val rtl = emit(true, unpacked = true, named = named)(new Records)
      val ports = if (named) ".x_a(x[23:0]),.x_b(x[35:24]),.y_a(y[23:0]),.y_b(y[35:24])" else ".x(x),.y(y)"
      simulate(rtl, s"""module tb;
        reg [35:0] x; wire [35:0] y; integer i;
        Top dut($ports);
        initial begin for(i=0;i<100;i=i+1) begin
          x={$$random,$$random}; #1;
          if(y !== x) $$fatal(1,"record array mismatch");
        end $$finish; end endmodule""")
    }
  }

  class ConditionalRecords extends Component {
    setDefinitionName("Top")
    val x = in(Vec(Pair(), 3))
    val alternate = in(Vec(Pair(), 3))
    val y = out(Vec(Pair(), 3))
    val en = in Bool()
    val values = Vec(Pair(), 3).dontSimplifyIt()
    values := x
    when(en) { values := alternate }
    y := values
  }
  test("named field unpacked procedural assignments preserve whole record priority") {
    val rtl = emit(true, unpacked = true, named = true)(new ConditionalRecords)
    simulate(rtl, """module tb;
      reg [35:0] x,alternate; reg en; wire [35:0] y; integer i;
      Top dut(.x_a(x[23:0]),.x_b(x[35:24]),.alternate_a(alternate[23:0]),
        .alternate_b(alternate[35:24]),.y_a(y[23:0]),.y_b(y[35:24]),.en(en));
      initial begin for(i=0;i<100;i=i+1) begin
        x={$random,$random}; alternate={$random,$random}; en=i%2; #1;
        if(y !== (en ? alternate : x)) $fatal(1,"record priority mismatch");
      end $finish; end endmodule""")
  }

  class Hierarchy extends Component {
    setDefinitionName("Top")
    val x = in(Vec(Bits(8 bits), 10))
    val y = out(Vec(Bits(8 bits), 10))
    val child = new Internal
    child.setDefinitionName("ArrayChild")
    child.x := x
    y := child.y
  }
  test("unpacked child storage keeps packed hierarchy connections") {
    val rtl = emit(true, unpacked = true)(new Hierarchy)
    simulate(rtl, """module tb;
      reg [79:0] x; wire [79:0] y; integer i;
      Top dut(.x(x),.y(y));
      initial begin for(i=0;i<100;i=i+1) begin
        x={$random,$random,$random}; #1;
        if(y !== x) $fatal(1,"hierarchy mismatch");
      end $finish; end endmodule""")
  }
  test("unit Vec dimensions remain explicit and empty typed loops have no body") {
    val rtl = emit(true, loops = true)(new Component {
      setDefinitionName("Top")
      val x = in(Vec.tabulate(1)(_ => Bits(8 bits)))
      val y = out(Vec(Bits(8 bits), 1))
      y := x
      ElabFiniteRange.foreach(ElabInt.literal(0), "empty_lanes") { _ =>
        fail("an empty typed loop must not elaborate its body")
      }
    })
    assert(rtl.contains("8 * 1") && !rtl.contains("empty_lanes"), rtl)
  }

}
