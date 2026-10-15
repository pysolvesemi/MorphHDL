package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class LightweightInferredHelperTests extends AnyFunSuite {
  for(defaultCount<-Seq(1,5)) test(s"inferred helper retains symbolic pair geometry default=$defaultCount") {
    def generate() = {
      val dir=Files.createTempDirectory("lightweight-inferred-helper-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
        preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
        new Component {
          setDefinitionName("InferredHelpers")
          val count: ElabInt=RtlParam.int("COUNT",defaultCount,1,17)
          def pairs(value: ElabInt) = rounded(value)
          def rounded(value: ElabInt) = (value+1)/2
          val size=pairs(count)
          val source=in(Vec(Bits(8 bits),count))
          val result=out(Vec(Bits(8 bits),size))
          for(i<-0 until size) {
            if(2*i+1<count) result(i) := source(2*i)^source(2*i+1)
            else result(i) := source(2*i)
          }
        }
      }
      dir
    }
    val dir=generate();val again=generate()
    val rtl=new String(Files.readAllBytes(dir.resolve("InferredHelpers.v")),"UTF-8")
    assert(rtl==new String(Files.readAllBytes(again.resolve("InferredHelpers.v")),"UTF-8"))
    assert(rtl.contains("for (") && rtl.contains("COUNT"),rtl)
    val profiles=Seq(1,2,3,5,7,8,15,16,17).map(n=>s"Check #(.N($n)) c$n(data);").mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter N=1)(input [135:0] data);
wire [((N+1)/2)*8-1:0] result; reg [((N+1)/2)*8-1:0] expected; integer i;
InferredHelpers #(.COUNT(N)) dut(.source(data[N*8-1:0]),.result(result));
always @(data) begin #1; for(i=0;i<(N+1)/2;i=i+1) begin
if(2*i+1<N) expected[i*8+:8]=data[(2*i)*8+:8]^data[(2*i+1)*8+:8]; else expected[i*8+:8]=data[(2*i)*8+:8]; end
if(result !== expected) $$fatal; end endmodule
module tb; reg [135:0] data; integer i;
$profiles
initial begin data=0; #3; data=~136'd0; #3; data='bx; #3; data='bz; #3;
for(i=0;i<64;i=i+1) begin data={$$random,$$random,$$random,$$random,$$random}; #3; end $$finish; end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","InferredHelpers.v","tb.v"),Seq("vvp","sim"),
        Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","InferredHelpers.v"),
        Seq("yosys","-Q","-T","-p","read_verilog InferredHelpers.v; hierarchy -check -top InferredHelpers; synth -top InferredHelpers; check -assert; select -assert-none t:$dlatch"))) {
      val(status,log)=Increment66ToolEvidence.run(dir,command);assert(status==0,s"$dir\n$log")
    }
  }
}
