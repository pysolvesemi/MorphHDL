package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class ConstantAggregateTests extends AnyFunSuite {
  private def emit(preserve: Boolean, loops: Boolean = false, unpacked: Boolean = false, named: Boolean = false, forceMorph: Boolean = false)(top: => Component): String = {
    val dir = Files.createTempDirectory("constant-aggregate-")
    val config = MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
      headerWithDate = false), preserve, loops,
      if (unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)
    if (preserve || loops || forceMorph) MorphVerilog(if (named) MorphNamedFieldVectors.enable(config) else config)(top) else SpinalVerilog(config)(top)
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

  class ContextZero extends Component {
    setDefinitionName("Top")
    val en = in Bool()
    val x = in UInt(13 bits)
    val y = out Bits(26 bits)
    val lanes = Vec(UInt(13 bits), 2)
    lanes.foreach(_.dontSimplifyIt())
    lanes(0) := x
    lanes(1) := x
    when(en) { lanes(1) := 0 }
    y := lanes.asBits
  }
  class StaticBool extends Component {
    setDefinitionName("Top")
    val en = in Bool()
    val x = in Bool()
    val y = out Bits(2 bits)
    val lanes = Vec.fill(2)(Reg(Bool()) init False)
    when(en) { lanes(0) := False; lanes(1) := x }
    y := lanes.asBits
  }
  class SwitchWrite extends Component {
    setDefinitionName("Top")
    val sel = in UInt(2 bits)
    val x = in Bits(8 bits)
    val y = out Bits(16 bits)
    val lanes = Vec.fill(2)(Reg(Bits(8 bits)) init 0)
    switch(sel) { is(1) { lanes(0) := x }; is(2) { lanes(1) := x } }
    y := lanes.asBits
  }
  test("CA-001 contextual zero assignments retain native width") {
    for (unpacked <- Seq(false, true)) simulate(emit(true, unpacked = unpacked)(new ContextZero), """module tb;
      reg en; reg [12:0] x; wire [25:0] y; integer i;
      Top dut(.en(en),.x(x),.y(y));
      initial begin for(i=0;i<100;i=i+1) begin x=$random; en=i%2; #1;
        if(y !== {en ? 13'b0 : x,x}) $fatal(1,"contextual zero"); end $finish; end
      endmodule""")
  }
  test("CA-003 static Bool writes survive native literal normalization") {
    for (unpacked <- Seq(false, true)) simulate(emit(true, unpacked = unpacked)(new StaticBool), """module tb;
      reg clk=0,reset=1,en=0,x=0; wire [1:0] y; reg [1:0] expected=0; integer i;
      Top dut(.clk(clk),.reset(reset),.en(en),.x(x),.y(y));
      initial begin #1; clk=1; #1; clk=0; reset=0;
      for(i=0;i<20;i=i+1) begin en=i%3!=0; x=i%2; #1; clk=1;
        if(en) expected={x,1'b0}; #1; if(y !== expected) $fatal(1,"Bool write"); clk=0;
      end $finish; end endmodule""")
  }
  test("CA-002 constant register Vec writes retain switch control") {
    for (unpacked <- Seq(false, true)) simulate(emit(true, unpacked = unpacked)(new SwitchWrite), """module tb;
      reg clk=0,reset=1; reg [1:0] sel=0; reg [7:0] x=0; wire [15:0] y; reg [15:0] expected=0; integer i;
      Top dut(.clk(clk),.reset(reset),.sel(sel),.x(x),.y(y));
      initial begin #1; clk=1; #1; clk=0; reset=0;
      for(i=0;i<40;i=i+1) begin sel=i%4; x=$random; #1; clk=1;
        case(sel) 1:expected[7:0]=x; 2:expected[15:8]=x; endcase
        #1; if(y !== expected) $fatal(1,"switch write"); clk=0;
      end $finish; end endmodule""")
  }
  class WidthChild(actual: morphhdl.frontend.HdlInt) extends Component {
    @dontName val width = morphhdl.frontend.formalParam(actual, "DELTA_WIDTH", 32, 36).asElabInt
    val delta = in UInt(width bits)
    val y = out UInt(32 bits)
    y := delta.resize(32)
  }
  class WidthParent(named: Boolean = false) extends Component {
    setDefinitionName("Top")
    @dontName val events = morphhdl.frontend.HdlInt.param("EVENT_PORTS", 4, 1, 16)
    val deltaWidth = events.asElabInt.log2Up + 32
    @dontName val width = if(named) deltaWidth else events.asElabInt.log2Up + 32
    val x = in UInt(36 bits)
    val y = out UInt(32 bits)
    val child = new WidthChild(events.ceilLog2 + morphhdl.frontend.HdlInt.literal(32))
    val delta = UInt(width bits).setName("counterDelta").dontSimplifyIt()
    delta := x.resize(width)
    child.delta := delta
    y := child.y
  }
  test("CA-004 child formal widths stay in their definition owner") {
    for(named <- Seq(false,true); preserve <- Seq(false,true)) simulate(emit(preserve, unpacked = preserve, forceMorph = true)(new WidthParent(named)), """module tb;
      reg [35:0] x; wire [31:0] y; integer i;
      Top #(.EVENT_PORTS(16)) dut(.x(x),.y(y));
      initial begin for(i=0;i<100;i=i+1) begin x={$random,$random}; #1;
        if(y !== x[31:0]) $fatal(1,"formal width"); end $finish; end endmodule""")
  }
  class SharedLiteral extends Component {
    setDefinitionName("Top")
    val en = in Bool()
    val y = out Bits(3 bits)
    val lanes = Vec.fill(3)(Reg(Bool()) init False)
    def assignAll(value: Bool): Unit = for(i <- 0 until 3) when(en) { lanes(i) := value }
    assignAll(True)
    y := lanes.asBits
  }
  test("CA-003 shared literal propagation retains each native clone identity") {
    simulate(emit(true, unpacked = true)(new SharedLiteral), """module tb;
      reg clk=0,reset=1,en=0; wire [2:0] y;
      Top dut(.clk(clk),.reset(reset),.en(en),.y(y));
      initial begin #1; clk=1; #1; if(y!==0) $fatal; clk=0;reset=0;en=1;
        #1;clk=1;#1;if(y!==7) $fatal; $finish;end endmodule""")
  }
  class SyncAttributes extends Component {
    setDefinitionName("Top")
    val x = in Bool()
    val y = out Bool()
    val stages = Vec.fill(2)(Reg(Bool()) init False)
    stages.foreach(_.addAttribute("ASYNC_REG", "TRUE"))
    stages(0).addTag(crossClockDomain)
    stages(0) := x
    stages(1) := stages(0)
    y := stages(1)
  }
  test("CA-003 synchronizer arrays retain common and native CDC attributes") {
    val rtl = emit(true, unpacked = true)(new SyncAttributes)
    assert(rtl.contains("ASYNC_REG = \"TRUE\"") && rtl.contains("async_reg = \"true\""), rtl)
    simulate(rtl, """module tb;
      reg clk=0,reset=1,x=0; wire y;
      Top dut(.clk(clk),.reset(reset),.x(x),.y(y));
      initial begin #1;clk=1;#1;clk=0;reset=0;x=1;
      #1;clk=1;#1;if(y!==0) $fatal;clk=0;#1;clk=1;#1;if(y!==1) $fatal;
      $finish;end endmodule""")
  }
  class ClockChild extends Component {
    val clk = in Bool()
    val reset = in Bool()
    val x = in Bool()
    val y = out Bool()
    val area = new ClockingArea(ClockDomain(clk, reset)) { val q = RegNext(x) init False }
    y := area.q
  }
  class ClockParent(foreign: Boolean) extends Component {
    setDefinitionName("Top")
    val x = in Bool()
    val y = out Bool()
    val otherClock = in Bool()
    val child = new ClockChild
    val alias = Bool().dontSimplifyIt()
    alias := (if(foreign) otherClock else ClockDomain.current.readClockWire)
    child.clk := alias
    child.reset := ClockDomain.current.readResetWire
    child.x := x
    y := RegNext(child.y) init False
  }
  test("pulled implicit clock aliases preserve synchrony without allowing foreign clocks") {
    emit(false)(new ClockParent(false))
    val failure = intercept[Exception] { emit(false)(new ClockParent(true)) }
    assert(failure.toString.contains("CLOCK CROSSING"), failure.toString)
  }
  test("literal normalization rejects equal-valued replacement outside the native phase") {
    import spinal.core.internals._
    val dir = Files.createTempDirectory("static-literal-mutation-")
    val config = MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString), preserveConstantVecs = true)
    var mutated = false
    config.phasesInserters += { phases =>
      val at = phases.indexWhere(_.getClass == classOf[PhaseAllocateNames])
      phases.insert(at + 1, new PhaseNetlist {
        override def impl(pc: PhaseContext): Unit = pc.walkStatements {
          case assignment: DataAssignmentStatement if assignment.finalTarget.getName() == "lanes_0" =>
            assignment.source match {
              case literal: BoolLiteral => assignment.source = literal.clone(); mutated = true
              case _ =>
            }
          case _ =>
        }
      })
    }
    val failure = intercept[MorphVerilogException] { MorphVerilog(config)(new SharedLiteral) }
    assert(mutated)
    assert(failure.toString.contains("STATIC-WRITE-EVIDENCE-MISMATCH"), failure.toString)
  }
  test("aggregate attribute reconciliation rejects unrelated per-element differences") {
    class DifferentAttributes extends SyncAttributes {
      stages(0).addAttribute("keep", "true")
    }
    val failure = intercept[MorphVerilogException] { emit(true, unpacked = true)(new DifferentAttributes) }
    assert(failure.toString.contains("DECLARATION-KIND-MISMATCH"), failure.toString)
  }
  class ConstantChild extends Component {
    val x = in Bits(80 bits)
    val y = out Bits(80 bits)
    val lanes = Vec(Bits(8 bits), 10)
    lanes.foreach(_.dontSimplifyIt())
    for (i <- 0 until 10) lanes(i) := x(i * 8 + 7 downto i * 8)
    y := lanes.asBits
  }
  class ConstantChildParent extends Component {
    setDefinitionName("Top")
    val x = in Bits(80 bits)
    val y = out Bits(80 bits)
    val child = new ConstantChild
    child.x := x
    y := child.y
  }
  test("constant-only child aggregates do not require symbolic parent parameters") {
    simulate(emit(true, unpacked = true)(new ConstantChildParent), """module tb;
      reg [79:0] x; wire [79:0] y; integer i;
      Top dut(.x(x),.y(y));
      initial begin for(i=0;i<30;i=i+1) begin x={$random,$random,$random}; #1;
        if(y!==x) $fatal;end $finish;end endmodule""")
  }
}
