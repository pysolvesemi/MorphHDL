package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

final class LightweightMixedIndexProbe extends Component {
  setDefinitionName("MixedIndex")
  val rows: ElabInt = RtlParam.int("ROWS", 1, 1, 5)
  val columns: ElabInt = RtlParam.int("COLS", 1, 1, 4)
  val width: ElabInt = RtlParam.int("WIDTH", 8, 1, 64)
  val source = in(Vec(Vec(SInt(width bits), columns), rows))
  val row = in UInt(3 bits)
  val result = out(Vec(SInt(width bits), columns))
  for (j <- 0 until columns) result(j) := source(row)(j)
}

class LightweightMixedIndexTests extends AnyFunSuite {
  for (packed <- Seq(false, true)) test(s"hardware row and scoped column preserve symbolic signed geometry packed=$packed") {
    def generate() = {
      val dir = Files.createTempDirectory("lightweight-mixed-index-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
        headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
        vecLayout = if(packed) MorphAggregateOptions.PackedVector else MorphAggregateOptions.UnpackedArray)) {
        new LightweightMixedIndexProbe
      }
      dir
    }
    val dir=generate(); val again=generate()
    val rtl=new String(Files.readAllBytes(dir.resolve("MixedIndex.v")),"UTF-8")
    assert(rtl==new String(Files.readAllBytes(again.resolve("MixedIndex.v")),"UTF-8"))
    val profiles=(for(r<-Seq(1,3,5);c<-Seq(1,2,4);w<-Seq(1,8,36,64)) yield
      s"Check #(.R($r),.C($c),.W($w)) c_${r}_${c}_$w(data,row);").mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter R=1,C=1,W=1)(input [1279:0] data,input [2:0] row);
wire [C*W-1:0] result;
MixedIndex #(.ROWS(R),.COLS(C),.WIDTH(W)) dut(.source(data[R*C*W-1:0]),.row(row),.result(result));
always @(data or row) begin #1;
if((^row) === 1'bx) begin
  if(result !== {C*W{1'bx}}) $$fatal(1,"unknown selector R=%d C=%d W=%d",R,C,W);
end else if(row < R) begin
  if(result !== data[row*C*W+:C*W]) $$fatal(1,"valid selector R=%d C=%d W=%d",R,C,W);
end else if(result !== data[(R-1)*C*W+:C*W]) $$fatal(1,"out of range must select final row");
end
endmodule
module tb; reg [1279:0] data; reg [2:0] row; integer i,j,k;
$profiles
initial begin row=0; data=0; #3;
for(k=0;k<8;k=k+1) begin row=k;
data=0; #3; data=~1280'd0; #3; data='bx; #3; data='bz; #3;
for(i=0;i<1280;i=i+1) begin data=1280'd1 << i; #3; end
for(i=0;i<20;i=i+1) begin for(j=0;j<40;j=j+1) data[j*32+:32]=$$random; #3; end
end
data=0;row='bx;#3;data=~1280'd0;#3;row='bz;#3;
$$finish; end endmodule
""".getBytes("UTF-8"))
    for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","MixedIndex.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","MixedIndex.v"),
      Seq("yosys","-Q","-T","-p","read_verilog MixedIndex.v; chparam -set ROWS 5 -set COLS 4 -set WIDTH 64 MixedIndex; hierarchy -check -top MixedIndex; synth -top MixedIndex; check -assert; select -assert-none t:$dlatch"))) {
      val(status,log)=Increment66ToolEvidence.run(dir,cmd);assert(status==0,s"$dir\n$log")
    }
    for((r,c,w,mutation)<-Seq((1,1,1,false),(3,2,8,false),(5,4,64,false),(3,2,8,true))) {
      val cases=(0 until r).map(i=>s"3'd$i: expected=data[${i*c*w}+:${c*w}];").mkString("\n")
      Files.write(dir.resolve("miter.v"),s"""module Miter(input [${r*c*w-1}:0] data,input [2:0] row,output ok);
wire [${c*w-1}:0] actual; reg [${c*w-1}:0] expected;
MixedIndex #(.ROWS($r),.COLS($c),.WIDTH($w)) dut(data,row,actual);
always @* begin case(row)
$cases
default: expected=data[${(r-1)*c*w}+:${c*w}];endcase end
assign ok=actual == (expected ^ ${c*w}'d${if(mutation) 1 else 0});
endmodule
""".getBytes("UTF-8"))
      val(status,log)=Increment66ToolEvidence.run(dir,Seq("yosys","-Q","-T","-p",
        "read_verilog -formal -DSYNTHESIS MixedIndex.v miter.v; prep -top Miter -flatten; memory_map; opt; check -assert; sat -verify -prove ok 1 -set-def-inputs"))
      if(mutation) assert(status!=0 && log.contains("proof did fail"),log)
      else assert(status==0,s"$dir\n$log")
    }
  }
}
