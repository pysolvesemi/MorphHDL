package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import spinal.core._
import morphhdl.frontend.{HdlInt, HdlIntRangeStart, StructuralBitVectorOps}
import org.scalatest.funsuite.AnyFunSuite

class BoundedProcessOperationTests extends AnyFunSuite {
  class Operations extends Component {
    val n = HdlInt.param("COUNT", 32, 1, 32).asElabInt
    val w = HdlInt.param("ELEMENT_BITS", 8, 1, 8).asElabInt
    val c = HdlInt.param("COUNT_BITS", 8, 6, 8).asElabInt
    val keep = in Bits(n bits)
    val take = in UInt(8 bits)
    val data = in Bits((n * w) bits)
    val count = out UInt(c bits)
    val word = out Bits((n * w) bits)
    count := ElabProcess.highestSetBitPlusOne(keep, n, c)
    word := ElabProcess.prefixCopy(data, take, n, w)
  }
  private def run(dir: Path, args: Seq[String]): Unit = {
    val result = Increment66ToolEvidence.run(dir, args)
    assert(result._1 == 0, result._2)
  }
  test("ordinary native generation uses the complete concrete priority and prefix algorithms") {
    val dir = Files.createTempDirectory("bounded-concrete-")
    SpinalVerilog(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false)) {
      new Component {
        setDefinitionName("ConcreteOperations")
        val keep = in Bits(3 bits)
        val take = in UInt(2 bits)
        val data = in Bits(24 bits)
        val count = out UInt(2 bits)
        val word = out Bits(24 bits)
        count := ElabProcess.highestSetBitPlusOne(keep, ElabInt.literal(3), ElabInt.literal(2))
        word := ElabProcess.prefixCopy(data, take, ElabInt.literal(3), ElabInt.literal(8))
      }
    }
    Files.write(dir.resolve("tb.v"), """module tb;
      reg [2:0] keep;reg [1:0] take;reg [23:0] data;
      wire [1:0] count;wire [23:0] word;
      integer k,t,b,expected;reg [23:0] expectedWord;
      ConcreteOperations dut(keep,take,data,count,word);
      initial begin
        for(k=0;k<8;k=k+1) for(t=0;t<4;t=t+1) begin
          keep=k;take=t;data=24'h123456;expected=0;expectedWord=0;
          for(b=0;b<3;b=b+1) begin
            if(keep[b]) expected=b+1;
            if(b<t) expectedWord[b*8+:8]=data[b*8+:8];
          end
          #1;if(count !== expected || word !== expectedWord) $fatal(1,"concrete fallback");
        end
        $finish;
      end
    endmodule
    """.getBytes(UTF_8))
    run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "sim", "ConcreteOperations.v", "tb.v"))
    run(dir, Seq("vvp", "sim"))
  }
  for ((n, c) <- Seq((1, 1), (32, 6), (32, 32)))
  test(s"bounded operations support unsigned boundary widths count=$n result=$c") {
    val dir = Files.createTempDirectory("bounded-width-boundary-")
    MorphVerilog(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false)) {
      new Component {
        setDefinitionName("BoundaryOperations")
        val keep = in Bits(n bits)
        val take = in UInt(32 bits)
        val count = out UInt(c bits)
        val word = out Bits(n bits)
        count := ElabProcess.highestSetBitPlusOne(keep, ElabInt.literal(n), ElabInt.literal(c))
        word := ElabProcess.prefixCopy(keep, take, ElabInt.literal(n), ElabInt.literal(1))
      }
    }
    run(dir, Seq("verilator", "--lint-only", "-Wall", "--top-module", "BoundaryOperations", "BoundaryOperations.v"))
    Files.write(dir.resolve("tb.v"), s"""module tb;
      reg [${n-1}:0] keep;reg [31:0] take;wire [${c-1}:0] count;wire [${n-1}:0] word;
      BoundaryOperations dut(keep,take,count,word);
      initial begin
        keep={$n{1'b1}};take=32'hffffffff;#1;
        if(count !== $n || word !== keep) $$fatal(1,"unsigned boundary");
        take=32'h80000000;#1;if(word !== keep) $$fatal(1,"signed comparison");
        take=32'bx;#1;if(word !== 0) $$fatal(1,"unknown predicate");
        take=32'bz;#1;if(word !== 0) $$fatal(1,"high impedance predicate");
        $$finish;
      end
    endmodule
    """.getBytes(UTF_8))
    run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "sim", "BoundaryOperations.v", "tb.v"))
    run(dir, Seq("vvp", "sim"))
  }
  test("bounded priority and prefix operations preserve ordered four-state behavior and parameter overrides") {
    val dir = Files.createTempDirectory("bounded-process-")
    def generate(path: Path): String = {
      MorphVerilog(SpinalConfig(targetDirectory = path.toString, headerWithDate = false))(new Operations)
      new String(Files.readAllBytes(path.resolve("Operations.v")), UTF_8)
    }
    val rtl = generate(dir)
    assert(rtl.contains("for ("), rtl)
    assert(rtl == generate(Files.createTempDirectory("bounded-process-repeat-")))
    run(dir, Seq("verilator", "--lint-only", "-Wall", "--top-module", "Operations", "Operations.v"))
    for ((n, w, c) <- Seq((1, 1, 6), (3, 5, 6), (32, 8, 8))) {
      run(dir, Seq("verilator", "--lint-only", "-Wall", s"-GCOUNT=$n", s"-GELEMENT_BITS=$w", s"-GCOUNT_BITS=$c", "--top-module", "Operations", "Operations.v"))
      run(dir, Seq("yosys", "-p", s"read_verilog -DSYNTHESIS Operations.v; chparam -set COUNT $n -set ELEMENT_BITS $w -set COUNT_BITS $c Operations; proc; select -assert-none t:$$dlatch; synth -top Operations; check -assert"))
      val tb = s"""module tb;
        |reg [${n-1}:0] keep; reg [7:0] take; reg [${n*w-1}:0] data;
        |wire [${c-1}:0] count; wire [${n*w-1}:0] word;
        |reg [${c-1}:0] expectedCount; reg [${n*w-1}:0] expectedWord;
        |integer b,t,s;
        |Operations #(.COUNT($n),.ELEMENT_BITS($w),.COUNT_BITS($c)) dut(keep,take,data,count,word);
        |task check; begin
        |  expectedCount=0; expectedWord=0;
        |  for(b=0;b<$n;b=b+1) begin
        |    if(keep[b]) expectedCount=b+1;
        |    if(b<take) expectedWord[b*$w +: $w]=data[b*$w +: $w];
        |  end
        |  #1;
        |  if(count !== expectedCount || word !== expectedWord) begin
        |    $$display("FAIL keep=%h take=%h count=%h expected=%h word=%h expectedWord=%h",keep,take,count,expectedCount,word,expectedWord); $$fatal(1);
        |  end
        |end endtask
        |initial begin
        | for(t=0;t<256;t=t+1) begin
        |  take=t;
        |  for(s=0;s<8;s=s+1) begin
        |   for(b=0;b<$n*$w;b=b+1) data[b]=$$random;
        |   keep=$$random; check;
        |  end
        |  keep=0; check; keep={$n{1'b1}}; check;
        |  for(s=0;s<$n;s=s+1) begin keep=1<<s; check; end
        | end
        | take=8'hff; keep={$n{1'bx}}; check; keep={$n{1'bz}}; check;
        | keep={$n{1'b1}}; keep[0]=1'bx; check;
        | take=8'bx; check; take=8'bz; check;
        | $$display("PASS bounded operations"); $$finish;
        |end
        |endmodule
        |""".stripMargin
      Files.write(dir.resolve("tb.v"), tb.getBytes(UTF_8))
      run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "sim", "Operations.v", "tb.v"))
      run(dir, Seq("vvp", "sim"))
    }
  }

  for (mode <- 0 until 4) test(s"bounded operations reject unsafe geometry and competing writes $mode") {
    intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory = Files.createTempDirectory("bounded-negative-").toString)) {
        new Component {
          val data = in Bits(32 bits)
          val take = in UInt(8 bits)
          val output = out UInt(8 bits)
          if (mode == 0) output := ElabProcess.highestSetBitPlusOne(data, ElabInt.literal(31), ElabInt.literal(8))
          if (mode == 1) output := ElabProcess.highestSetBitPlusOne(data, ElabInt.literal(32), ElabInt.literal(5))
          if (mode == 2) output := ElabProcess.highestSetBitPlusOne(data, ElabInt.literal(0), ElabInt.literal(8))
          if (mode == 3) {
            val result = ElabProcess.highestSetBitPlusOne(data, ElabInt.literal(32), ElabInt.literal(8))
            result := 0
            output := result
          }
        }
      }
    }
  }

  class RegisteredSelection extends Component {
    val selected = in UInt(2 bits)
    val enable = in Bool()
    val result = out Bits(32 bits)
    val saved = Reg(Bits(8 bits)) init(1)
    result := 0
    (0 until HdlInt.literal(4)).named("assemble", "lane").foreach { lane =>
      lane.whenSelected(selected) {
        result(lane * HdlInt.literal(8), HdlInt.literal(8)) := saved
      }
    }
    when(enable) { saved := result(7 downto 0) }
  }
  test("selected loops allow registered feedback without admitting combinational feedback") {
    val dir = Files.createTempDirectory("selected-register-feedback-")
    MorphVerilog(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false,
      flags = scala.collection.mutable.HashSet[Any](VerilogAggregateOptions(preserveConstantLoops = true))))(new RegisteredSelection)
    run(dir, Seq("iverilog", "-g2001", "-s", "RegisteredSelection", "-o", "sim", "RegisteredSelection.v"))
    Files.write(dir.resolve("tb.v"), """module tb;
      reg clk=0; always #5 clk=~clk;
      reg reset=1,enable=0; reg [1:0] select=0; wire [31:0] result;
      RegisteredSelection dut(.clk(clk),.reset(reset),.enable(enable),.selected(select),.result(result));
      integer i;
      initial begin
        #12; reset=0;
        for(i=0;i<4;i=i+1) begin select=i; #2; if(result !== (32'd1 << (i*8))) $fatal(1,"stalled feedback"); end
        select=0;enable=1; #11; if(result !== 32'd1) $fatal(1,"enabled lane zero");
        select=3; #11; if(result !== 0) $fatal(1,"registered transition");
        enable=0;select=2'bxx; #2; if(result !== 0) $fatal(1,"unknown selector");
        select=2'bzz; #2; if(result !== 0) $fatal(1,"high impedance selector");
        $finish;
      end
    endmodule
    """.getBytes(UTF_8))
    run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "sim", "RegisteredSelection.v", "tb.v"))
    run(dir, Seq("vvp", "sim"))
  }

  class PrefixChild(elements: Int, width: Int) extends Component {
    val data = in Bits(32 bits)
    val take = in UInt(8 bits)
    val word = out Bits(32 bits)
    word := ElabProcess.prefixCopy(data, take, ElabInt.literal(elements), ElabInt.literal(width))
  }
  class GeometrySharing extends Component {
    val data = in Bits(32 bits)
    val take = in UInt(8 bits)
    val a, b, c = out Bits(32 bits)
    val first = new PrefixChild(4, 8)
    val second = new PrefixChild(8, 4)
    val repeated = new PrefixChild(4, 8)
    Seq(first, second, repeated).foreach { child => child.data := data; child.take := take }
    a := first.word; b := second.word; c := repeated.word
  }
  test("nested bounded operations share equivalent definitions but preserve distinct element geometry") {
    val dir = Files.createTempDirectory("bounded-sharing-")
    MorphVerilog(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false))(new GeometrySharing)
    val rtl = new String(Files.readAllBytes(dir.resolve("GeometrySharing.v")), UTF_8)
    assert("(?m)^module PrefixChild(?:_\\d+)?\\b".r.findAllIn(rtl).size == 2, rtl)
    Files.write(dir.resolve("tb.v"), """module tb;
      reg [31:0] data; reg [7:0] take;
      wire [31:0] a,b,c;
      GeometrySharing dut(data,take,a,b,c);
      initial begin
        #1; data=32'hfedcba98; take=1;
        #1;if(a !== 32'h98 || b !== 32'h8 || c !== a) $fatal(1,"geometry lost");
        take=2;#1;if(a !== 32'hba98 || b !== 32'h98 || c !== a) $fatal(1,"geometry lost");
        take=255;#1;if(a !== data || b !== data || c !== data) $fatal(1,"full copy");
        $finish;
      end
    endmodule
    """.getBytes(UTF_8))
    run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "sim", "GeometrySharing.v", "tb.v"))
    run(dir, Seq("vvp", "sim"))
  }
}
