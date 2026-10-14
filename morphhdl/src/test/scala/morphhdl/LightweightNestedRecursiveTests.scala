package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

@_root_.spinal.core.rtlRecursive
final class LightweightNestedTree(countActual: ElabInt, columnsActual: ElabInt) extends Component {
  setDefinitionName("NestedTree")
  noIoPrefix()
  val count: ElabInt = RtlParam.int("COUNT", countActual, 1, 17)
  val columns: ElabInt = RtlParam.int("COLUMNS", columnsActual, 1, 3)
  val io = new Bundle {
    val terms = in(Vec(Vec(SInt(8 bits), columns), count))
    val result = out(Vec(SInt(8 bits), columns))
  }
  if (count == 1) io.result := io.terms(0)
  else {
    val nextCount = (count + 1) / 2
    val combined = Vec(Vec(SInt(8 bits), columns), nextCount)
    for (i <- 0 until nextCount) {
      for (j <- 0 until columns) {
        if (2 * i + 1 < count) combined(i)(j) := io.terms(2 * i)(j) - io.terms(2 * i + 1)(j)
        else combined(i)(j) := io.terms(2 * i)(j)
      }
    }
    val nextLevel = new LightweightNestedTree(nextCount, columns)
    nextLevel.io.terms := combined
    io.result := nextLevel.io.result
  }
}

class LightweightNestedRecursiveTests extends AnyFunSuite {
  private def generate(default: Int) = {
    val directory = Files.createTempDirectory("lightweight-nested-recursive-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
      vecLayout = MorphAggregateOptions.UnpackedArray)) {
      new LightweightNestedTree(ElabInt.literal(default), ElabInt.literal(2))
    }
    directory -> new String(Files.readAllBytes(directory.resolve("NestedTree.v")), "UTF-8")
  }
  for (default <- Seq(1,8)) test(s"nested finite arrays retain recursive dimensions default=$default") {
    val (directory, rtl) = generate(default)
    assert(rtl == generate(default)._2)
    assert(rtl.contains("COLUMNS") && rtl.contains(") nextLevel ("), rtl)
    val profiles = (for (n <- Seq(1,2,3,5,7,8,15,16,17); columns <- Seq(1,2,3)) yield
      s"Check #(.N($n),.C($columns)) c_${n}_$columns(data);").mkString("\n")
    Files.write(directory.resolve("tb.v"), s"""module Check #(parameter N=1,C=1)(input [407:0] data);
wire [C*8-1:0] result; reg [7:0] words[0:16]; integer i,n,c;
NestedTree #(.COUNT(N),.COLUMNS(C)) dut(.terms(data[N*C*8-1:0]),.result(result));
always @(data) begin #1;
for(c=0;c<C;c=c+1) begin
 for(i=0;i<N;i=i+1) words[i]=data[(i*C+c)*8+:8]; n=N;
 while(n>1) begin
  for(i=0;i<(n+1)/2;i=i+1) begin
   if(2*i+1<n) words[i]=words[2*i]-words[2*i+1]; else words[i]=words[2*i];
  end n=(n+1)/2;
 end
 if(result[c*8+:8] !== words[0]) $$fatal;
end end
endmodule
module tb; reg [407:0] data; integer j,k;
$profiles
initial begin data=0; #3; data=~408'd0; #3; data='bx; #3; data='bz; #3;
for(j=0;j<408;j=j+1) begin data=0; data[j]=1'b1; #3; end
for(j=0;j<100;j=j+1) begin for(k=0;k<12;k=k+1) data[k*32+:32]=$$random; data[407:384]=$$random; #3; end
$$finish; end endmodule
""".getBytes("UTF-8"))
    Files.write(directory.resolve("synth_top.v"),
      "module SynthTop(input [407:0] terms, output [23:0] result); NestedTree #(.COUNT(17),.COLUMNS(3)) dut(terms,result); endmodule\n".getBytes("UTF-8"))
    for (command <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","NestedTree.v","tb.v"),
        Seq("vvp","sim"), Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","NestedTree.v"),
        Seq("yosys","-Q","-T","-p","read_verilog NestedTree.v synth_top.v; hierarchy -check -top SynthTop; synth -top SynthTop; check -assert; select -assert-none t:$dlatch"))) {
      val (status, log) = Increment66ToolEvidence.run(directory, command)
      assert(status == 0, s"$directory\n$log")
    }
  }
}
