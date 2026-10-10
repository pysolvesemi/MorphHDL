package morphhdl

import java.nio.file.Files
import java.nio.charset.StandardCharsets.UTF_8
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class AreaCollectionLoopTests extends AnyFunSuite {
  private def rejected(expected: String)(body: => Component): Unit = {
    val dir = Files.createTempDirectory("area-collection-negative-")
    val error = intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString),
        preserveConstantLoops = true, preserveConstantVecs = true))(body)
    }
    assert(error.toString.contains(expected), error.toString)
  }

  test("Area collections reject out-of-range static members") {
    rejected("SPINAL-ELAB-AREA-STATIC-INDEX-OUT-OF-RANGE") {
      new Component {
        val input = in Bool()
        val areas = ElabAreaCollection.tabulate(ElabInt.literal(3), "areas") { i => new Area {
          val value = i.selectBool(0, "first")(input)(False)
        }}
        val output = out Bool()
        output := areas.member(3)(_.value)
      }
    }
  }

  test("Area collections reject insufficient source-tag widths") {
    rejected("SPINAL-ELAB-FINITE-INDEX-VALUE-WIDTH-INVALID") {
      new Component {
        ElabAreaCollection.tabulate(ElabInt.literal(3), "areas") { i => new Area {
          val tag = i.bits(1 bits)
        }}
      }
    }
  }

  test("Area collections reject escaped index values") {
    rejected("SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONSUMER-OUTSIDE-CAPTURE") {
      new Component {
        val input = in Bool()
        val output = out(Vec(Bool(), 3))
        var escaped: ElabFiniteIndex = null
        ElabFiniteRange.foreach(ElabInt.literal(3), "lanes") { i =>
          escaped = i
          i(output) := input
        }
        val invalid = out UInt(2 bits)
        invalid := escaped.uint(2 bits)
      }
    }
  }

  test("Area collections retain rejection of competing branch drivers") {
    rejected("ASSIGNMENT OVERLAP") {
      new Component {
        val input = in Bool()
        val areas = ElabAreaCollection.tabulate(ElabInt.literal(3), "areas") { i => new Area {
          val value = Bool()
          i.whenEqual(0, "first") { value := input; value := False } { value := input }
        }}
        val output = out Bool()
        output := areas.member(0)(_.value)
      }
    }
  }

  test("Area documentation iteration rejects hardware construction") {
    rejected("SPINAL-ELAB-AREA-DOCUMENTATION-HARDWARE-EFFECT") {
      new Component {
        val input = in Bool()
        val areas = ElabAreaCollection.tabulate(ElabInt.literal(3), "areas") { i => new Area {
          val value = i.selectBool(0, "first")(input)(False)
        }}
        areas.documentation.foreach { _ => val illegal = Bool(); illegal := input }
      }
    }
  }

  for (unpacked <- Seq(false, true))
  test(s"symbolic Area collection retains branch and static export across count overrides unpacked=$unpacked") {
    val directory = Files.createTempDirectory("area-symbolic-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = directory.toString, headerWithDate = false),
      preserveConstantLoops = true, preserveConstantVecs = true,
      vecLayout = if (unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)) {
      new Component {
        setDefinitionName("Top")
        val count = morphhdl.frontend.HdlInt.param("COUNT", 3, 1, 3).asElabInt
        val x = in(Vec(Bool(), count))
        val y = out(Vec(Bool(), count))
        val first = out Bool()
        val lanes = ElabAreaCollection.tabulate(count, "lanes") { index => new Area {
          val selected = index.selectBool(0, "first")(index(x))(!index(x))
          index(y) := selected
        }}
        first := lanes.member(0)(_.selected)
      }
    }
    Files.write(directory.resolve("tb.v"), """module tb;
reg [2:0] x=0; wire [2:0] y; wire one,first,firstOne;
Top #(.COUNT(3)) many(.x(x),.y(y),.first(first));
Top #(.COUNT(1)) single(.x(x[0]),.y(one),.first(firstOne));
integer n;
initial begin for(n=0;n<8;n=n+1) begin x=n; #1;
if(y !== {~x[2:1],x[0]} || one !== x[0] || first !== x[0] || firstOne !== x[0]) $fatal(1,"symbolic branch/export");
end $finish; end endmodule
""".getBytes(UTF_8))
    Seq(Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "Top.v", "tb.v"),
      Seq("timeout", "10", "vvp", "sim"),
      Seq("verilator", "--lint-only", "-Wall", "--top-module", "Top", "Top.v"),
      Seq("yosys", "-Q", "-p", "read_verilog Top.v; synth -top Top; check -assert")).foreach { command =>
      val (code, log) = Increment66ToolEvidence.run(directory, command)
      assert(code == 0, s"$command in $directory\n$log")
    }
  }

  for (count <- Seq(1, 3))
  test(s"ordinary Area collection preserves branches, static exports, packed writes and priority count=$count") {
    val directory = Files.createTempDirectory("area-collection-loop-")
    def emit(destination: java.nio.file.Path, retained: Boolean, module: String): Unit = {
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = destination.toString,
      headerWithDate = false, oneFilePerComponent = true), preserveConstantLoops = retained, preserveConstantVecs = true)) {
      new Component {
        setDefinitionName(module)
        val x = in(Vec(Bool(), count))
        val y = out(Vec(Bool(), count))
        val gated = out(Vec(Bool(), count))
        val en = in Bool()
        val select = in UInt(2 bits)
        val packedIn = in Bits(count * 8 bits)
        val packedOut = out Bits(count * 8 bits)
        val flags = out Bits(count bits)
        val data = out(Vec(Bits(8 bits), count))
        val stored = out(Vec(Bits(8 bits), count))
        val lanes = for (index <- 0 until count) yield new Area {
          require((0 until 2).map { index => if (index == 0) 3 else 5 }.sum == 8)
          val input = x(index)
          val output = y(index)
          val selected = if (index == 0) input else !input
          output := selected
          val liveOnly = if (index == 0) input else False
          gated(index) := liveOnly
          val value = Reg(UInt(8 bits)) init 0
          when(en && input) { value := value + 1 }
          val payload = packedIn(index * 8, 8 bits)
          packedOut(index * 8, 8 bits) := payload ^ B(index, 8 bits)
          flags(index) := False
          when(en) {
            flags(index) := select === index
            if (index == 0) { when(input) { flags(index) := True } }
          }
          val child = new Component {
            setDefinitionName("LaneInvert")
            val x = in Bits(8 bits)
            val y = out Bits(8 bits)
            y := ~x
          }
          val childInput = Bits(ElabInt.literal(8) bits).dontSimplifyIt()
          childInput := value.asBits ^ B(index, 8 bits)
          child.x := childInput
          data(index) := child.y
          val external = new BlackBox {
            setDefinitionName("LaneStorage")
            val clk, reset, enable = in Bool()
            val payload = in Bits(8 bits)
            val result = out Bits(8 bits)
          }
          external.clk := ClockDomain.current.readClockWire
          external.reset := ClockDomain.current.readResetWire
          if (index == 0) external.enable := en else external.enable := en && input
          external.payload := value.asBits
          stored(index) := external.result
        }
        val observed = out Bool()
        observed := lanes(0).selected
      }
    }
    }
    emit(directory, retained = true, "Top")
    val repeat = Files.createTempDirectory("area-collection-repeat-")
    emit(repeat, retained = true, "Top")
    assert(java.util.Arrays.equals(Files.readAllBytes(directory.resolve("Top.v")), Files.readAllBytes(repeat.resolve("Top.v"))))
    val golden = Files.createTempDirectory("area-collection-unrolled-")
    emit(golden, retained = false, "Golden")
    Files.copy(golden.resolve("Golden.v"), directory.resolve("Golden.v"))
    Files.write(directory.resolve("LaneStorage.v"), """module LaneStorage(input clk,reset,enable,
input [7:0] payload, output reg [7:0] result);
always @(posedge clk or posedge reset) if(reset) result <= 8'd0; else if(enable) result <= payload;
endmodule
""".getBytes(UTF_8))
    val rtl = new String(Files.readAllBytes(directory.resolve("Top.v")), UTF_8)
    assert(rtl.contains("for (") && rtl.contains("if ((i == 0))"), rtl)
    Files.write(directory.resolve("tb.v"), s"""module tb;
localparam N=$count;
reg clk=0; always #5 clk=~clk;
reg reset=0,en=0; reg [1:0] select_1=0;
reg [N-1:0] x=0; reg [N*8-1:0] packedIn=0;
wire [N-1:0] y,flags,gated; wire [N*8-1:0] packedOut,data,stored; wire observed;
integer cycle,lane; reg [7:0] expected [0:N-1]; reg [7:0] expectedStored [0:N-1];
wire [N-1:0] goldenY,goldenFlags,goldenGated; wire [N*8-1:0] goldenPacked,goldenData,goldenStored; wire goldenObserved;
Top dut(.clk(clk),.reset(reset),.en(en),.select_1(select_1),.x(x),.y(y),
 .packedIn(packedIn),.packedOut(packedOut),.flags(flags),.data(data),.stored(stored),.observed(observed),.gated(gated));
Golden reference(.clk(clk),.reset(reset),.en(en),.select_1(select_1),.x(x),.y(goldenY),
 .packedIn(packedIn),.packedOut(goldenPacked),.flags(goldenFlags),.data(goldenData),.stored(goldenStored),.observed(goldenObserved),.gated(goldenGated));
initial begin
 for(lane=0;lane<N;lane=lane+1) begin expected[lane]=0; expectedStored[lane]=0; end
 #1; reset=1; #1; reset=0;
 for(cycle=0;cycle<100;cycle=cycle+1) begin
  @(negedge clk); en=cycle%3!=0; x=$$random; packedIn=$$random; select_1=cycle%4;
  #1;
  if({y,flags,gated,packedOut,data,stored,observed} !== {goldenY,goldenFlags,goldenGated,goldenPacked,goldenData,goldenStored,goldenObserved}) $$fatal(1,"unrolled equivalence");
  if(observed!==x[0]) $$fatal(1,"static export");
  for(lane=0;lane<N;lane=lane+1) begin
   if(y[lane] !== (lane==0 ? x[lane] : !x[lane])) $$fatal(1,"branch");
   if(gated[lane] !== (lane==0 ? x[lane] : 1'b0)) $$fatal(1,"constant branch");
   if(packedOut[lane*8+:8] !== (packedIn[lane*8+:8] ^ lane[7:0])) $$fatal(1,"slice and tag");
   if(flags[lane] !== (en && ((lane==0 && x[0]) || select_1==lane))) $$fatal(1,"priority");
  end
  @(posedge clk); for(lane=0;lane<N;lane=lane+1) begin
   if(en && (lane==0 || x[lane])) expectedStored[lane]=expected[lane];
   if(en && x[lane]) expected[lane]=expected[lane]+1;
  end
  #1;
  for(lane=0;lane<N;lane=lane+1) begin
   if(data[lane*8+:8] !== ~(expected[lane] ^ lane[7:0])) $$fatal(1,"independent state and child");
   if(stored[lane*8+:8] !== expectedStored[lane]) $$fatal(1,"external child state");
  end
  if(cycle==50) begin reset=1; for(lane=0;lane<N;lane=lane+1) begin expected[lane]=0; expectedStored[lane]=0; end #1; reset=0; end
 end
 @(negedge clk); en=1'bx; x={N{1'bx}}; select_1=2'bxx; #1;
 if(flags !== {N{1'b0}} || flags !== goldenFlags) $$fatal(1,"unknown enable priority");
 en=1'b1; x={N{1'bz}}; #1;
 if(flags !== {N{1'bx}} || flags !== goldenFlags || y !== goldenY || gated !== goldenGated)
  $$fatal(1,"four-state branch equivalence");
 $$finish;
end
endmodule
""".getBytes(UTF_8))
    Seq(
      Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "Top.v", "Golden.v", "LaneInvert.v", "LaneStorage.v", "tb.v"),
      Seq("timeout", "10", "vvp", "sim"),
      Seq("verilator", "--lint-only", "-Wall", "--language", "1364-2001", "--top-module", "Top", "Top.v", "LaneInvert.v", "LaneStorage.v"),
      Seq("yosys", "-Q", "-p", "read_verilog Top.v Golden.v LaneInvert.v LaneStorage.v; proc; flatten Top; flatten Golden; async2sync; equiv_make Golden Top equiv; hierarchy -top equiv; equiv_simple; equiv_induct -seq 4; equiv_status -assert"),
      Seq("yosys", "-Q", "-p", "read_verilog Top.v LaneInvert.v LaneStorage.v; hierarchy -top Top; proc; select -assert-none t:$$dlatch; synth -top Top; check -assert")
    ).foreach { command =>
      val (code, log) = Increment66ToolEvidence.run(directory, command)
      assert(code == 0, s"$command in $directory\n$log")
    }
  }
}
