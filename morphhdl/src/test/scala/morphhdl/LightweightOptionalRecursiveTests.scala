package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

@_root_.spinal.core.rtlRecursive
final class LightweightOptionalTree(countActual: ElabInt, maskActual: ElabInt,
    changeOmitted: Boolean) extends Component {
  setDefinitionName("OptionalTree")
  noIoPrefix()
  val count: ElabInt = RtlParam.int("COUNT", countActual, 1, 8)
  val mask: ElabInt = RtlParam.optionalInt("MASK", maskActual, 0, 15, 0)
  val io = new Bundle {
    val terms = in(Vec(UInt(8 bits), count))
    val result = out UInt(8 bits)
  }
  if (count == 1) io.result := io.terms(0)
  else {
    val nextCount = (count + 1) / 2
    val combined = Vec(UInt(8 bits), nextCount)
    for (i <- 0 until nextCount) {
      if (2 * i + 1 < count)
        combined(i) := (io.terms(2*i) + io.terms(2*i+1)) ^ U(mask, 8 bits)
      else combined(i) := io.terms(2*i) ^ U(mask, 8 bits)
    }
    val nextLevel = new LightweightOptionalTree(nextCount,
      if (changeOmitted) ElabInt.literal(1) else mask, changeOmitted)
    nextLevel.io.terms := combined
    io.result := nextLevel.io.result
  }
}

class LightweightOptionalRecursiveTests extends AnyFunSuite {
  private def generate(default: Int, omitted: Boolean, invalid: Boolean = false) = {
    val directory = Files.createTempDirectory("lightweight-optional-recursive-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
      vecLayout = MorphAggregateOptions.UnpackedArray)) {
      new LightweightOptionalTree(ElabInt.literal(default), if(omitted) null else ElabInt.literal(0), invalid)
    }
    directory -> new String(Files.readAllBytes(directory.resolve("OptionalTree.v")), "UTF-8")
  }
  for (default <- Seq(1,8); omitted <- Seq(false,true)) {
    test(s"optional recursive parameter default=$default omitted=$omitted") {
      val (directory, rtl) = generate(default, omitted)
      assert(rtl == generate(default, omitted)._2)
      assert(rtl.contains("parameter integer MASK") != omitted, rtl)
      val profiles = (for(n <- Seq(1,2,3,5,8); mask <- (if(omitted) Seq(0) else Seq(0,1,7,15))) yield
        s"Check #(.N($n),.MASK($mask)) c_${n}_$mask(data);").mkString("\n")
      val maskBinding = if(omitted) "" else ",.MASK(MASK)"
      Files.write(directory.resolve("tb.v"), s"""module Check #(parameter N=1,MASK=0)(input [63:0] data);
wire [7:0] result; reg [7:0] words[0:7]; integer i,n;
OptionalTree #(.COUNT(N)$maskBinding) dut(.terms(data[N*8-1:0]),.result(result));
always @(data) begin #1;
for(i=0;i<N;i=i+1) words[i]=data[i*8+:8]; n=N;
while(n>1) begin
for(i=0;i<(n+1)/2;i=i+1) begin
if(2*i+1<n) words[i]=(words[2*i]+words[2*i+1]) ^ MASK;
else words[i]=words[2*i] ^ MASK;
end n=(n+1)/2; end
if(result !== words[0]) $$fatal; end endmodule
module tb; reg [63:0] data; integer i;
$profiles
initial begin data=0; #3; data=~64'd0; #3; data='bx; #3; data='bz; #3;
for(i=0;i<64;i=i+1) begin data=64'd1 << i; #3; end
for(i=0;i<100;i=i+1) begin data={$$random,$$random}; #3; end $$finish; end endmodule
""".getBytes("UTF-8"))
      for(command <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","OptionalTree.v","tb.v"),
          Seq("vvp","sim"), Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","OptionalTree.v"),
          Seq("yosys","-Q","-T","-p","read_verilog OptionalTree.v; chparam -set COUNT 8 OptionalTree; hierarchy -check -top OptionalTree; synth -top OptionalTree; check -assert; select -assert-none t:$dlatch"))) {
        val (status, log) = Increment66ToolEvidence.run(directory,command)
        assert(status==0,s"$directory\n$log")
      }
    }
  }
  test("an omitted parameter cannot become active in recursive construction") {
    val failure = intercept[Exception] { generate(8, omitted=true, invalid=true) }
    assert(failure.toString.contains("omission identity"), failure.toString)
  }
}
