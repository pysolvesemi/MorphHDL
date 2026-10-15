package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

final class LightweightMixedWriteProbe(conditional: Boolean, normalizedControl: Boolean = false) extends Component {
  setDefinitionName("MixedWrite")
  val rows: ElabInt = RtlParam.int("ROWS", 1, 1, 5)
  val columns: ElabInt = RtlParam.int("COLS", 1, 1, 4)
  val width: ElabInt = RtlParam.int("WIDTH", 8, 1, 64)
  val source = in(Vec(Vec(SInt(width bits), columns), rows))
  val replacement = in(Vec(SInt(width bits), columns))
  val row = in UInt(3 bits)
  val enable = in Bool()
  val result = out(Vec(Vec(SInt(width bits), columns), rows))
  result := source
  if (normalizedControl) {
    val defaultRow = source(row)
    val nextRow = Vec(SInt(width bits), columns)
    for (j <- 0 until columns) {
      val lane = nextRow(j)
      if(conditional) {
        lane := defaultRow(j)
        when(enable) { lane := replacement(j) }
      } else lane := replacement(j)
    }
    result(row) := nextRow
  } else {
    for (j <- 0 until columns) {
      if(conditional) when(enable) { result(row)(j) := replacement(j) }
      else result(row)(j) := replacement(j)
    }
  }
}

class LightweightMixedWriteTests extends AnyFunSuite {
  for (packed <- Seq(false, true); conditional <- Seq(false,true); normalized <- Seq(false,true)) test(s"normalized=$normalized decoded hardware row and scoped column writes preserve default and enable packed=$packed conditional=$conditional") {
    def generate() = {
      val dir = Files.createTempDirectory("lightweight-mixed-write-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
        headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
        vecLayout = if(packed) MorphAggregateOptions.PackedVector else MorphAggregateOptions.UnpackedArray)) {
        new LightweightMixedWriteProbe(conditional, normalized)
      }
      dir
    }
    val dir=generate(); val again=generate()
    val rtl=new String(Files.readAllBytes(dir.resolve("MixedWrite.v")),"UTF-8")
    assert(rtl==new String(Files.readAllBytes(again.resolve("MixedWrite.v")),"UTF-8"))
    assert(rtl.contains("for (i = 0; i < COLS; i = i + 1)"),rtl)
    val profiles=(for(r<-Seq(1,3,5);c<-Seq(1,2,4);w<-Seq(1,8,36,64)) yield
      s"Check #(.R($r),.C($c),.W($w)) c_${r}_${c}_$w(data,replacement,row,enable);").mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter R=1,C=1,W=1)(input [1279:0] data,input [255:0] replacement,input [2:0] row,input enable);
wire [R*C*W-1:0] result; reg [R*C*W-1:0] expected; integer r;
MixedWrite #(.ROWS(R),.COLS(C),.WIDTH(W)) dut(.source(data[R*C*W-1:0]),.replacement(replacement[C*W-1:0]),.row(row),.enable(enable),.result(result));
always @(data or replacement or row or enable) begin
expected=data[R*C*W-1:0];
for(r=0;r<R;r=r+1) if(${if(conditional) "enable" else "1'b1"} && row==r) expected[r*C*W+:C*W]=replacement[C*W-1:0];
#1;if(result !== expected) $$fatal(1,"decoded write mismatch R=%d C=%d W=%d row=%b enable=%b",R,C,W,row,enable);
end
endmodule
module tb; reg [1279:0] data; reg [255:0] replacement;reg [2:0] row;reg enable;integer i,j,k,e;
$profiles
initial begin row=0;data=0;replacement=0;enable=0;#3;
for(k=0;k<8;k=k+1) for(e=0;e<2;e=e+1) begin row=k;enable=e;
data=0;replacement=~256'd0;#3;data=~1280'd0;replacement=0;#3;
data='bx;replacement='bz;#3;data=0;replacement='bx;#3;
for(i=0;i<100;i=i+1) begin
for(j=0;j<40;j=j+1) data[j*32+:32]=$$random;
for(j=0;j<8;j=j+1) replacement[j*32+:32]=$$random;
#3;end end
row='bx;enable=1;#3;row='bz;#3;row=0;enable='bx;#3;enable='bz;#3;
$$finish;end endmodule
""".getBytes("UTF-8"))
    for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","MixedWrite.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","MixedWrite.v"),
      Seq("yosys","-Q","-T","-p","read_verilog MixedWrite.v; chparam -set ROWS 5 -set COLS 4 -set WIDTH 64 MixedWrite; hierarchy -check -top MixedWrite; synth -top MixedWrite; check -assert; select -assert-none t:$dlatch"))) {
      val(status,log)=Increment66ToolEvidence.run(dir,cmd);assert(status==0,s"$dir\n$log")
    }
    for ((r,c,w,mutation) <- Seq((1,1,1,false),(3,2,8,false),(5,4,64,false),(3,2,8,true))) {
      val cases=(0 until r).map(i=>s"3'd$i: expected[${i*c*w}+:${c*w}]=replacement;").mkString("\n")
      Files.write(dir.resolve("miter.v"),s"""module Miter(input [${r*c*w-1}:0] data,input [${c*w-1}:0] replacement,input [2:0] row,input enable,output ok);
wire [${r*c*w-1}:0] actual; reg [${r*c*w-1}:0] expected;
MixedWrite #(.ROWS($r),.COLS($c),.WIDTH($w)) dut(.source(data),.replacement(replacement),.row(row),.enable(enable),.result(actual));
always @* begin expected=data; if (${if(conditional) "enable" else "1'b1"}) begin case(row)
$cases
default: ; endcase end end
assign ok=actual == (expected ^ ${r*c*w}'d${if(mutation) 1 else 0});
endmodule
""".getBytes("UTF-8"))
      val(status,log)=Increment66ToolEvidence.run(dir,Seq("yosys","-Q","-T","-p",
        "read_verilog -formal -DSYNTHESIS MixedWrite.v miter.v; prep -top Miter -flatten; memory_map; opt; check -assert; sat -verify -prove ok 1 -set-def-inputs"))
      if(mutation) assert(status!=0 && log.contains("proof did fail"),log)
      else assert(status==0,s"$dir\n$log")
    }
  }
}
