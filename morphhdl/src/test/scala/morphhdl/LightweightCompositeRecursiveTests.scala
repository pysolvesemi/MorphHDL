package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

@_root_.spinal.core.rtlRecursive
final class LightweightGenericTree[T <: Data](elementType: HardType[T], countActual: ElabInt,
    moduleName: String, operation: (T, T) => T) extends Component {
  setDefinitionName(moduleName)
  noIoPrefix()
  val count: ElabInt = RtlParam.int("COUNT", countActual, 1, 17)
  val io = new Bundle {
    val terms = in(Vec(elementType(), count))
    val result = out(elementType())
  }
  if (count == 1) io.result := io.terms(0)
  else {
    val nextCount = (count + 1) / 2
    val combined = Vec(elementType(), nextCount)
    for (i <- 0 until nextCount) {
      if (2 * i + 1 < count) combined(i) := operation(io.terms(2 * i), io.terms(2 * i + 1))
      else combined(i) := io.terms(2 * i)
    }
    val nextLevel = new LightweightGenericTree[T](elementType, nextCount, moduleName, operation)
    nextLevel.io.terms := combined
    io.result := nextLevel.io.result
  }
}

case class LightweightRecord() extends Bundle { val value = SInt(8 bits); val valid = Bool() }

class LightweightCompositeRecursiveTests extends AnyFunSuite {
  private def generate(kind: String, default: Int) = {
    val directory = Files.createTempDirectory("lightweight-composite-recursive-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
      vecLayout = MorphAggregateOptions.UnpackedArray)) {
      kind match {
        case "signed" => new LightweightGenericTree[SInt](HardType(SInt(8 bits)), ElabInt.literal(default),
          "GenericTree", (a: SInt, b: SInt) => Mux(a > b, a, b))
        case "bits" => new LightweightGenericTree[Bits](HardType(Bits(8 bits)), ElabInt.literal(default),
          "GenericTree", (a: Bits, b: Bits) => a ^ b)
        case "bundle" => new LightweightGenericTree[LightweightRecord](HardType(LightweightRecord()),
          ElabInt.literal(default), "GenericTree", (a: LightweightRecord, b: LightweightRecord) => {
            val result = LightweightRecord()
            result.value := a.value - b.value
            result.valid := a.valid && b.valid
            result
          })
      }
    }
    directory -> new String(Files.readAllBytes(directory.resolve("GenericTree.v")), "UTF-8")
  }
  for (kind <- Seq("signed", "bits", "bundle"); default <- Seq(1, 8)) {
    test(s"generic recursive payload and callback kind=$kind default=$default") {
      val (directory, rtl) = generate(kind, default)
      assert(rtl == generate(kind, default)._2)
      assert(rtl.contains(") nextLevel (") && rtl.contains("for ("), rtl)
      val width = if (kind == "bundle") 9 else 8
      val ports = if (kind == "bundle") ".result_value(result[7:0]),.result_valid(result[8])" else ".result(result)"
      val reduce = if (kind == "bundle") "{words[2*i][8] && words[2*i+1][8], (words[2*i][7:0] - words[2*i+1][7:0])}"
        else if (kind == "bits") "words[2*i] ^ words[2*i+1]" else "($signed(words[2*i]) > $signed(words[2*i+1])) ? words[2*i] : words[2*i+1]"
      val profiles = Seq(1,2,3,5,7,8,15,16,17).map(n => s"Check #(.N($n)) c_$n(data);").mkString("\n")
      Files.write(directory.resolve("tb.v"), s"""module Check #(parameter N=1)(input [${17*width-1}:0] data);
wire [$width-1:0] result;
reg [$width-1:0] words[0:16]; integer i,n;
GenericTree #(.COUNT(N)) dut(.terms(data[N*$width-1:0]),$ports);
always @(data) begin #1;
for(i=0;i<N;i=i+1) words[i]=data[i*$width+:$width]; n=N;
while(n>1) begin for(i=0;i<(n+1)/2;i=i+1) begin
if(2*i+1<n) words[i]=$reduce; else words[i]=words[2*i]; end n=(n+1)/2; end
if(result !== words[0]) $$fatal; end
endmodule
module tb; reg [${17*width-1}:0] data; integer j,k;
$profiles
initial begin data=0; #3; data=~${17*width}'d0; #3; data='bx; #3; data='bz; #3;
for(j=0;j<${17*width};j=j+1) begin data=0; data[j]=1'b1; #3; end
for(j=0;j<100;j=j+1) begin data={$$random,$$random,$$random,$$random,$$random}; #3; end
$$finish; end endmodule
""".getBytes("UTF-8"))
      Files.write(directory.resolve("synth_top.v"),
        s"module SynthTop(input [${17*width-1}:0] data, output [$width-1:0] result); GenericTree #(.COUNT(17)) dut(.terms(data),$ports); endmodule\n".getBytes("UTF-8"))
      for (command <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","GenericTree.v","tb.v"),
          Seq("vvp","sim"), Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","GenericTree.v"),
          Seq("yosys","-Q","-T","-p","read_verilog GenericTree.v synth_top.v; hierarchy -check -top SynthTop; synth -top SynthTop; check -assert; select -assert-none t:$dlatch"))) {
        val (status, log) = Increment66ToolEvidence.run(directory, command)
        assert(status == 0, s"$directory\n$log")
      }
    }
  }
}
