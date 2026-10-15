package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

final class LightweightNestedWriteProbe extends Component {
  setDefinitionName("NestedWrite")
  val rows: ElabInt = RtlParam.int("ROWS", 1, 1, 5)
  val columns: ElabInt = RtlParam.int("COLS", 1, 1, 4)
  val width: ElabInt = RtlParam.int("WIDTH", 8, 1, 64)
  val source = in(Vec(Vec(SInt(width bits), columns), rows))
  val result = out(Vec(Vec(SInt(width bits), columns), rows))
  for (i <- 0 until rows) {
    for (j <- 0 until columns) result(i)(j) := ~source(i)(j)
  }
}

class LightweightNestedWriteTests extends AnyFunSuite {
  for (packed <- Seq(false, true)) test(s"ordinary nested writes retain dimensions and signed payloads packed=$packed") {
    def generate() = {
      val dir = Files.createTempDirectory("lightweight-nested-write-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
        headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
        vecLayout = if(packed) MorphAggregateOptions.PackedVector else MorphAggregateOptions.UnpackedArray)) {
        new LightweightNestedWriteProbe
      }
      dir
    }
    val dir=generate();val again=generate()
    val rtl=new String(Files.readAllBytes(dir.resolve("NestedWrite.v")),"UTF-8")
    assert(rtl==new String(Files.readAllBytes(again.resolve("NestedWrite.v")),"UTF-8"))
    assert(rtl.contains("ROWS") && rtl.contains("COLS") && rtl.sliding(5).count(_=="for (")>=2,rtl)
    val profiles=(for(r<-Seq(1,3,5);c<-Seq(1,2,4);w<-Seq(1,8,36,64)) yield
      s"Check #(.R($r),.C($c),.W($w)) c_${r}_${c}_$w(data);").mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter R=1,C=1,W=1)(input [1279:0] data);
wire [R*C*W-1:0] result;
NestedWrite #(.ROWS(R),.COLS(C),.WIDTH(W)) dut(.source(data[R*C*W-1:0]),.result(result));
always @(data) begin #1; if(result !== ~data[R*C*W-1:0]) $$fatal; end
endmodule
module tb; reg [1279:0] data; integer i,j;
$profiles
initial begin data=0; #3; data=~1280'd0; #3; data='bx; #3; data='bz; #3;
for(i=0;i<1280;i=i+1) begin data=1280'd1 << i; #3; end
for(i=0;i<100;i=i+1) begin for(j=0;j<40;j=j+1) data[j*32+:32]=$$random; #3; end
$$finish; end endmodule
""".getBytes("UTF-8"))
    for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","NestedWrite.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","NestedWrite.v"),
      Seq("yosys","-Q","-T","-p","read_verilog NestedWrite.v; chparam -set ROWS 5 -set COLS 4 -set WIDTH 64 NestedWrite; hierarchy -check -top NestedWrite; synth -top NestedWrite; check -assert; select -assert-none t:$dlatch"))) {
      val(status,log)=Increment66ToolEvidence.run(dir,cmd);assert(status==0,s"$dir\n$log")
    }
  }
}
