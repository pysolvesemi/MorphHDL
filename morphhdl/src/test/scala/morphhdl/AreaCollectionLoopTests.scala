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
  for (unpacked <- Seq(false, true))
  test(s"Area previous-iteration dependency publishes one shared Vec unpacked=$unpacked") {
    val dir = Files.createTempDirectory("area-previous-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false),
      preserveConstantLoops = true, preserveConstantVecs = true,
      vecLayout = if (unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)) {
      new Component {
        setDefinitionName("Top")
        val x = in(Vec(Bool(), 4))
        val y = out(Vec(Bool(), 4))
        val signals = Vec(Bool(), 4)
        val lanes = for (i <- 0 until 4) yield new Area {
          signals(i) := (if (i == 0) x(i) else signals(i - 1) ^ x(i))
          y(i) := signals(i)
        }
      }
    }
    val rtl = new String(Files.readAllBytes(dir.resolve("Top.v")), UTF_8)
    assert(rtl.contains("for (") && rtl.contains("i - 1"), rtl)
    assert(rtl.indexOf("signals;") < rtl.indexOf("generate") || rtl.indexOf("signals [") < rtl.indexOf("generate"), rtl)
    Files.write(dir.resolve("tb.v"), """module tb;
reg [3:0] x=0; wire [3:0] y; reg [3:0] expected; integer n,k;
Top dut(.x(x),.y(y));
initial begin
for(n=0;n<18;n=n+1) begin
 if(n<16) x=n; else if(n==16) x=4'b01x1; else x=4'b1z10;
 expected[0]=x[0]; for(k=1;k<4;k=k+1) expected[k]=expected[k-1]^x[k];
 #1; if(y !== expected) $fatal(1,"previous iteration");
end $finish; end endmodule
""".getBytes(UTF_8))
    // Split the proven independent elements for Verilator's scheduling analysis;
    // this is not a warning waiver and leaves the published RTL untouched.
    Files.write(dir.resolve("split.vlt"), "`verilator_config\nsplit_var -module \"Top\" -var \"signals\"\n".getBytes(UTF_8))
    Seq(Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "Top.v", "tb.v"),
      Seq("timeout", "10", "vvp", "sim"),
      Seq("verilator", "--lint-only", "-Wall", "--top-module", "Top", "split.vlt", "Top.v"),
      Seq("yosys", "-Q", "-p", "read_verilog Top.v; synth -top Top; check -assert")).foreach { cmd =>
      val (code, log) = Increment66ToolEvidence.run(dir, cmd)
      assert(code == 0, s"$cmd in $dir\n$log")
    }
  }

  test("Area local names omit only the enclosing collection and preserve native helpers") {
    val dir = Files.createTempDirectory("area-local-names-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false),
      preserveConstantLoops = true, preserveConstantVecs = true)) {
      new Component {
        setDefinitionName("Top")
        val x = in(Vec(Bool(), 4))
        val y = out(Vec(Bool(), 4))
        val lanes = for (i <- 0 until 4) yield new Area {
          val A = new Area { val signal = Bool().dontSimplifyIt(); signal := x(i) }
          val B = new Area { val signal = Bool().dontSimplifyIt(); signal := (if (i == 0) A.signal else !A.signal) }
          y(i) := B.signal
        }
      }
    }
    val rtl = new String(Files.readAllBytes(dir.resolve("Top.v")), UTF_8)
    assert(rtl.contains(" A_signal;") && rtl.contains(" B_signal;"), rtl)
    assert(!rtl.contains("wire                lanes_A_signal;"), rtl)
    assert(rtl.contains("_zz_lanes_B_signal"), rtl)
    Seq(Seq("iverilog", "-g2001", "-s", "Top", "-o", "sim", "Top.v"),
      Seq("verilator", "--lint-only", "-Wall", "--top-module", "Top", "Top.v"),
      Seq("yosys", "-Q", "-p", "read_verilog Top.v; synth -top Top; check -assert")).foreach { cmd =>
      val (code, log) = Increment66ToolEvidence.run(dir, cmd)
      assert(code == 0, s"$cmd in $dir\n$log")
    }
  }

  for (mode <- Seq("unguarded", "wrong-branch", "distance", "write"))
  test(s"Area previous-iteration access rejects $mode") {
    val diagnostic = mode match {
      case "unguarded" => "SPINAL-ELAB-AREA-PREVIOUS-INDEX-UNGUARDED"
      case "wrong-branch" => "SPINAL-ELAB-AREA-PREVIOUS-INDEX-OUT-OF-RANGE"
      case "distance" => "SPINAL-ELAB-AREA-PREVIOUS-DISTANCE-INVALID"
      case "write" => "SPINAL-ELAB-FINITE-AFFINE-WRITE-UNSUPPORTED"
    }
    rejected(diagnostic) {
      new Component {
        val x = in(Vec(Bool(), 4))
        val y = out(Vec(Bool(), 4))
        ElabAreaCollection.tabulate(ElabInt.literal(4), "lanes") { i => new Area {
          if (mode == "unguarded") i(y) := i.previous(x, 1)
          else i.whenEqual(0, "first") {
            if (mode == "wrong-branch") i(y) := i.previous(x, 1) else i(y) := i(x)
          } {
            if (mode == "write") i.previous(y, 1) := i(x)
            else i(y) := i.previous(x, if (mode == "distance") 0 else 1)
          }
        }}
      }
    }
  }

  test("Area previous reads retain symbolic count overrides including a single element") {
    val dir = Files.createTempDirectory("area-previous-symbolic-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false),
      preserveConstantLoops = true, preserveConstantVecs = true, vecLayout = MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("Top")
        val count = morphhdl.frontend.HdlInt.param("COUNT", 3, 1, 4).asElabInt
        val x = in(Vec(Bool(), count))
        val y = out(Vec(Bool(), count))
        val signals = Vec(Bool(), count)
        ElabAreaCollection.tabulate(count, "lanes") { i => new Area {
          i(signals) := i.selectBool(0, "first")(i(x))(i.previous(signals, 1) ^ i(x))
          i(y) := i(signals)
        }}
      }
    }
    Files.write(dir.resolve("tb.v"), """module tb;
reg [3:0] x=0; wire [3:0] y4; wire [2:0] y3; wire [1:0] y2; wire y1;
reg [3:0] expected; integer n,k;
Top #(.COUNT(1)) a(.x(x[0]),.y(y1));
Top #(.COUNT(2)) b(.x(x[1:0]),.y(y2));
Top #(.COUNT(3)) c(.x(x[2:0]),.y(y3));
Top #(.COUNT(4)) d(.x(x),.y(y4));
initial begin for(n=0;n<16;n=n+1) begin
x=n; expected[0]=x[0]; for(k=1;k<4;k=k+1) expected[k]=expected[k-1]^x[k];
#1; if(y1 !== expected[0] || y2 !== expected[1:0] || y3 !== expected[2:0] || y4 !== expected) $fatal(1,"count override");
end $finish; end endmodule
""".getBytes(UTF_8))
    Seq(Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "Top.v", "tb.v"),
      Seq("timeout", "10", "vvp", "sim"),
      Seq("yosys", "-Q", "-p", "read_verilog Top.v; synth -top Top; check -assert")).foreach { cmd =>
      val (code, log) = Increment66ToolEvidence.run(dir, cmd)
      assert(code == 0, s"$cmd in $dir\n$log")
    }
  }

  test("Area singleton chain authenticates an unreachable previous-index branch") {
    val dir = Files.createTempDirectory("area-previous-singleton-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString),
      preserveConstantLoops = true, preserveConstantVecs = true)) {
      new Component {
        val x = in(Vec(Bool(), 1))
        val y = out(Vec(Bool(), 1))
        val signals = Vec(Bool(), 1)
        val lanes = for (i <- 0 until 1) yield new Area {
          signals(i) := (if (i == 0) x(i) else signals(i - 1) ^ x(i))
          y(i) := signals(i)
        }
      }
    }
  }

  test("Area backward read cannot hide a combinational feedback path") {
    val error = intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = Files.createTempDirectory("area-cycle-").toString),
        preserveConstantLoops = true, preserveConstantVecs = true)) {
        new Component {
          val x = in(Vec(Bool(), 4))
          val y = out(Vec(Bool(), 4))
          val signals = Vec(Bool(), 4)
          val lanes = for (i <- 0 until 4) yield new Area {
            signals(i) := (if (i == 0) signals(3) ^ x(i) else signals(i - 1) ^ x(i))
            y(i) := signals(i)
          }
        }
      }
    }
    assert(error.toString.contains("SPINAL-ELAB-AREA-COMBINATIONAL-CYCLE"), error.toString)
  }

  test("Area previous reads preserve parameterized register reset enable and shift behavior") {
    val dir = Files.createTempDirectory("area-previous-registers-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false),
      preserveConstantLoops = true, preserveConstantVecs = true, vecLayout = MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("Top")
        val count = morphhdl.frontend.HdlInt.param("COUNT", 3, 1, 4).asElabInt
        val width = morphhdl.frontend.HdlInt.param("WIDTH", 5, 1, 64).asElabInt
        val x = in Bits(width bits)
        val en = in Bool()
        val y = out Bits(width bits)
        val stages = Vec.fill(count)(Reg(Bits(width bits)) init(0))
        ElabAreaCollection.tabulate(count, "lanes") { i => new Area {
          val next = Bits(width bits)
          i.whenEqual(0, "first") { next := x } { next := i.previous(stages, 1) }
          when(en) { i(stages) := next }
        }}
        y := ElabVec.select(stages, count - 1)
      }
    }
    Files.write(dir.resolve("tb.v"), """module tb;
reg clk=0; always #5 clk=~clk;
reg reset=0,en=0; reg [63:0] x=0;
wire y1; wire [4:0] y3; wire [63:0] y4;
reg [63:0] expected[0:3]; integer cycle,k;
Top #(.WIDTH(1),.COUNT(1)) a(.clk(clk),.reset(reset),.en(en),.x(x[0]),.y(y1));
Top #(.WIDTH(5),.COUNT(3)) b(.clk(clk),.reset(reset),.en(en),.x(x[4:0]),.y(y3));
Top #(.WIDTH(64),.COUNT(4)) c(.clk(clk),.reset(reset),.en(en),.x(x),.y(y4));
initial begin
for(k=0;k<4;k=k+1) expected[k]=0;
#1; reset=1; #1; reset=0;
for(cycle=0;cycle<50;cycle=cycle+1) begin
 @(negedge clk); x={$random,$random}; en=cycle%3!=0;
 if(cycle==25) begin reset=1; for(k=0;k<4;k=k+1) expected[k]=0; #1; reset=0; end
 @(posedge clk); if(en) begin for(k=3;k>0;k=k-1) expected[k]=expected[k-1]; expected[0]=x; end
 #1; if(y1 !== expected[0][0] || y3 !== expected[2][4:0] || y4 !== expected[3]) $fatal(1,"register shift/reset/hold");
end $finish; end endmodule
""".getBytes(UTF_8))
    Seq(Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "Top.v", "tb.v"),
      Seq("timeout", "10", "vvp", "sim"),
      Seq("verilator", "--lint-only", "-Wall", "--top-module", "Top", "Top.v"),
      Seq("yosys", "-Q", "-p", "read_verilog Top.v; synth -top Top; check -assert")).foreach { cmd =>
      val (code, log) = Increment66ToolEvidence.run(dir, cmd)
      assert(code == 0, s"$cmd in $dir\n$log")
    }
  }

  test("Area local naming reserves module names and preserves explicit and flattened names") {
    val dir = Files.createTempDirectory("area-name-collision-")
    def emit(): String = {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false),
        preserveConstantLoops = true, preserveConstantVecs = true)) {
        new Component {
          setDefinitionName("Top")
          val x = in(Vec(Bool(), 4))
          val y = out(Vec(Bool(), 4))
          val A_signal = in Bool()
          val outside = new Area { val signal = Bool().dontSimplifyIt(); signal := !A_signal }
          val lanes = for (i <- 0 until 4) yield new Area {
            val A = new Area { val signal = if (i == 0) x(i) else !x(i) }
            val authored = Bool().setName("lanes_authored").dontSimplifyIt()
            authored := A.signal ^ outside.signal
            y(i) := authored
          }
        }
      }
      new String(Files.readAllBytes(dir.resolve("Top.v")), UTF_8)
    }
    val rtl = emit()
    assert(rtl == emit())
    assert(rtl.contains(" A_signal_2;") && rtl.contains(" lanes_authored;"), rtl)
    assert(rtl.take(rtl.indexOf("generate")).contains("outside_signal;"), rtl)
    Seq(Seq("iverilog", "-g2001", "-s", "Top", "-o", "sim", "Top.v"),
      Seq("verilator", "--lint-only", "-Wall", "--top-module", "Top", "Top.v"),
      Seq("yosys", "-Q", "-p", "read_verilog Top.v; synth -top Top; check -assert")).foreach { cmd =>
      val (code, log) = Increment66ToolEvidence.run(dir, cmd)
      assert(code == 0, s"$cmd in $dir\n$log")
    }
  }

  test("Area previous reads preserve Bits UInt and SInt selection semantics") {
    val dir = Files.createTempDirectory("area-previous-types-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false),
      preserveConstantLoops = true, preserveConstantVecs = true, vecLayout = MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("Top")
        val xb = in(Vec(Bits(8 bits), 4))
        val xu = in(Vec(UInt(8 bits), 4))
        val xs = in(Vec(SInt(8 bits), 4))
        val yb, yu, ys = out(Vec(Bool(), 4))
        val lanes = for (i <- 0 until 4) yield new Area {
          yb(i) := (if (i == 0) xb(i) === B(0xaa, 8 bits) else xb(i - 1) === xb(i))
          yu(i) := (if (i == 0) xu(i) < 131 else xu(i - 1) < xu(i))
          ys(i) := (if (i == 0) xs(i) < 3 else xs(i - 1) < xs(i))
        }
      }
    }
    Files.write(dir.resolve("tb.v"), """module tb;
reg [31:0] x=0; wire [3:0] yb,yu,ys; reg [3:0] eb,eu,es; reg [7:0] sample,current; integer n,k;
Top dut(.xb(x),.xu(x),.xs(x),.yb(yb),.yu(yu),.ys(ys));
initial begin for(n=0;n<100;n=n+1) begin
x=$random; if(n==0) x=32'haaaaaaaa; if(n==1) x=32'hffffffff;
for(k=0;k<4;k=k+1) begin
 sample=x[(k==0?0:k-1)*8+:8]; current=x[k*8+:8];
 eb[k]=k==0 ? sample==8'haa : sample==current;
 eu[k]=k==0 ? sample<8'd131 : sample<current;
 es[k]=k==0 ? $signed(sample)<8'sd3 : $signed(sample)<$signed(current);
end
#1; if(yb !== eb || ys !== es || yu !== eu) $fatal(1,"typed previous read");
end $finish; end endmodule
""".getBytes(UTF_8))
    Seq(Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "Top.v", "tb.v"),
      Seq("timeout", "10", "vvp", "sim"),
      Seq("verilator", "--lint-only", "-Wall", "--top-module", "Top", "Top.v"),
      Seq("yosys", "-Q", "-p", "read_verilog Top.v; synth -top Top; check -assert")).foreach { cmd =>
      val (code, log) = Increment66ToolEvidence.run(dir, cmd)
      assert(code == 0, s"$cmd in $dir\n$log")
    }
  }


  for (mode <- Seq("normal", "collision", "authored", "owner"))
  test(s"Area child instances connections and library composites preserve naming provenance $mode") {
    def emit(dir: java.nio.file.Path): Unit = {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
        headerWithDate = false), preserveConstantLoops = true, preserveConstantVecs = true)) {
        new Component {
          setDefinitionName("Top")
          val x = in(Vec(Bits(8 bits), 3))
          val en, clear = in(Vec(Bool(), 3))
          val y = out(Vec(Bits(8 bits), 3))
          val valid = out(Vec(Bool(), 3))
          val occupied = in Bool()
          if (mode == "collision") occupied.setName("skid")
          val lanes = for (i <- 0 until 3) yield new Area {
            val xCounter = spinal.lib.Counter(8 bits)
            if (mode == "owner") xCounter.setName("lanes_authored_xCounter")
            when(en(i)) { xCounter.increment() }
            when(clear(i)) { xCounter.clear() }
            if (mode == "authored") {
              xCounter.willIncrement.setCompositeName(xCounter, "incr")
              xCounter.valueNext.setName("lanes_authored_next")
            }
            val skid = new BlackBox {
              setDefinitionName("BufferPrimitive")
              addGeneric("DW", 8)
              val i_valid, i_ready = in Bool()
              val i_data = in Bits(8 bits)
              val o_valid, o_ready = out Bool()
              val o_data = out Bits(8 bits)
            }
            if (mode == "authored") skid.setName("lanes_authored_skid")
            skid.i_valid := en(i) && !clear(i)
            if (i == 0) skid.i_ready := occupied else skid.i_ready := !occupied
            skid.i_data := xCounter.value.asBits ^ x(i)
            y(i) := skid.o_data ^ x(i)
            valid(i) := skid.o_valid && skid.o_ready
          }
        }
      }
    }
    val dir = Files.createTempDirectory("area-child-names-")
    emit(dir)
    val rtl = new String(Files.readAllBytes(dir.resolve("Top.v")), UTF_8)
    val instance = if (mode == "authored") "lanes_authored_skid" else if (mode == "collision") "skid_2" else "skid"
    assert(rtl.contains(") " + instance + " ("), rtl)
    assert(rtl.contains(instance + "_o_data"), rtl)
    assert(rtl.contains(instance + "_i_ready"), rtl)
    assert(rtl.contains(".o_data") && rtl.contains(".i_valid") && rtl.contains("BufferPrimitive #("), rtl)
    assert(rtl.contains("xCounter_clear"), rtl)
    if (mode == "authored") {
      assert(rtl.contains("lanes_xCounter_incr") && rtl.contains("lanes_authored_next"), rtl)
    } else if (mode == "owner") {
      assert(rtl.contains("lanes_authored_xCounter_incr") && rtl.contains("lanes_authored_xCounter_next"), rtl)
    } else {
      assert(rtl.contains("xCounter_incr") && rtl.contains("xCounter_next"), rtl)
      assert(!"(?m)\\b(?:wire|reg)\\s+(?:\\[[^\\]]+\\]\\s+)?lanes_xCounter_(?:incr|next)\\b".r.findFirstIn(rtl).nonEmpty, rtl)
    }
    val again = Files.createTempDirectory("area-child-names-repeat-")
    emit(again)
    assert(java.util.Arrays.equals(Files.readAllBytes(dir.resolve("Top.v")), Files.readAllBytes(again.resolve("Top.v"))))
    Files.write(dir.resolve("BufferPrimitive.v"), """module BufferPrimitive #(parameter DW=8)
(input i_valid, i_ready, input [DW-1:0] i_data, output o_valid, o_ready, output [DW-1:0] o_data);
assign o_valid=i_valid; assign o_ready=i_ready; assign o_data=i_data;
endmodule
""".getBytes(UTF_8))
    val occupied = if (mode == "collision") "skid" else "occupied"
    Files.write(dir.resolve("tb.v"), s"""module tb;
reg clk=0,reset=1; always #5 clk=~clk;
reg [23:0] x=24'h9a7513; reg [2:0] en=0,clear=0; wire [23:0] y; wire [2:0] valid;
Top dut(.clk(clk),.reset(reset),.x(x),.en(en),.clear(clear),.y(y),.valid(valid),.$occupied(1'b1));
integer n,j; reg [7:0] count[0:2];
initial begin
for(j=0;j<3;j=j+1) count[j]=0;
#1; en=7; clear=7; #1; en=0; clear=0;
repeat(3) @(negedge clk); reset=0;
for(n=0;n<40;n=n+1) begin
 en=n; clear=(n%7==0)?3'b010:0;
 @(posedge clk); for(j=0;j<3;j=j+1) if(clear[j]) count[j]=0; else if(en[j]) count[j]=count[j]+1;
 #1; for(j=0;j<3;j=j+1) if(y[j*8+:8] !== count[j]) $$fatal(1,"lane state mismatch");
 if(valid !== (en & ~clear & 3'b001)) $$fatal(1,"connection mismatch");
 @(negedge clk);
end
$$display("PASS"); $$finish;
end
endmodule
""".getBytes(UTF_8))
    Seq(Seq("iverilog", "-g2012", "-s", "tb", "-o", "sim", "Top.v", "BufferPrimitive.v", "tb.v"),
      Seq("vvp", "sim"), Seq("verilator", "--lint-only", "--top-module", "Top", "Top.v", "BufferPrimitive.v"),
      Seq("yosys", "-Q", "-p", "read_verilog Top.v BufferPrimitive.v; synth -top Top; check -assert")).foreach { cmd =>
      val (code, log) = Increment66ToolEvidence.run(dir, cmd)
      assert(code == 0, s"$cmd in $dir\n$log")
    }
  }

}
