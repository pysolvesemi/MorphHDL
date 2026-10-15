package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

final class LightweightGuardedRegisters(mode: String) extends Component {
  setDefinitionName("GuardedRegisters")
  val count: ElabInt=RtlParam.int("COUNT",1,1,5)
  val width: ElabInt=RtlParam.int("WIDTH",8,1,64)
  val source=in(Vec(Bits(width bits),count))
  val bitsResult=out(Vec(Bits(width bits),count))
  val signedResult=out(Vec(SInt(width bits),count))
  val flags=out(Vec(Bool(),count))
  val foreign=if(mode=="foreign") Reg(Bits(width bits)) init(0) else null
  for(i<-0 until count) {
    val bits=if(mode=="foreign") foreign else Reg(Bits(width bits)) init(0)
    val signed=Reg(SInt(width bits)) init(0)
    val flag=Reg(Bool()) init(False)
    bits.addAttribute("keep")
    if(i+1<count) {
      bits := bits ^ source(i)
      signed := signed + source(i).asSInt
      flag := !flag
    } else {
      bits := source(i)
      signed := source(i).asSInt
      flag := source(i).xorR
    }
    if(mode=="competing") bits := 0
    bitsResult(i) := bits
    signedResult(i) := signed
    flags(i) := flag
  }
}
class LightweightGuardedRegisterTests extends AnyFunSuite {
  def generate(packed: Boolean, mode: String) = {
    val dir=Files.createTempDirectory("lightweight-guarded-register-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,
      vecLayout=if(packed) MorphAggregateOptions.PackedVector else MorphAggregateOptions.UnpackedArray)) {
      new LightweightGuardedRegisters(mode)
    }
    dir
  }
  for(packed<-Seq(false,true)) test(s"static register next-value joins preserve signed bits Boolean and feedback packed=$packed") {
    val dir=generate(packed,"valid");val again=generate(packed,"valid")
    val rtl=new String(Files.readAllBytes(dir.resolve("GuardedRegisters.v")),"UTF-8")
    assert(rtl==new String(Files.readAllBytes(again.resolve("GuardedRegisters.v")),"UTF-8"))
    assert(rtl.contains("keep"),rtl)
    val profiles=(for(n<-Seq(1,2,3,5);w<-Seq(1,8,36,64)) yield
      s"Check #(.N($n),.W($w)) c_${n}_$w(data,clk,reset);").mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter N=1,W=1)(input [319:0] data,input clk,reset);
wire [N*W-1:0] actualBits,actualSigned;wire [N-1:0] actualFlags;
reg [N*W-1:0] expectedBits,expectedSigned;reg [N-1:0] expectedFlags;integer i;
GuardedRegisters #(.COUNT(N),.WIDTH(W)) dut(.source(data[N*W-1:0]),.bitsResult(actualBits),.signedResult(actualSigned),.flags(actualFlags),.clk(clk),.reset(reset));
always @(posedge clk or posedge reset) if(reset) begin expectedBits=0;expectedSigned=0;expectedFlags=0;end else begin
for(i=0;i<N;i=i+1) if(i+1<N) begin
expectedBits[i*W+:W]=expectedBits[i*W+:W]^data[i*W+:W];
expectedSigned[i*W+:W]=expectedSigned[i*W+:W]+data[i*W+:W];expectedFlags[i]=~expectedFlags[i];
end else begin expectedBits[i*W+:W]=data[i*W+:W];expectedSigned[i*W+:W]=data[i*W+:W];expectedFlags[i]=^data[i*W+:W];end end
always @(negedge clk) if($$time>0) begin #1;if(actualBits !== expectedBits || actualSigned !== expectedSigned || actualFlags !== expectedFlags) $$fatal(1,"feedback mismatch N=%d W=%d",N,W);end
endmodule
module tb;reg [319:0] data=0;reg clk=0,reset=0;integer i,j;
always #5 clk=~clk;
$profiles
initial begin #1;reset=1;#20;reset=0;#20;data=~320'd0;#20;
for(i=0;i<320;i=i+1) begin data=320'd1 << i;#20;end
for(i=0;i<100;i=i+1) begin for(j=0;j<10;j=j+1) data[j*32+:32]=$$random;#20;end
data='bx;#20;data='bz;#20;reset=1;#20;reset=0;data=0;#40;$$finish;end endmodule
""".getBytes("UTF-8"))
    for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","GuardedRegisters.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","GuardedRegisters.v"),
      Seq("yosys","-Q","-T","-p","read_verilog GuardedRegisters.v; chparam -set COUNT 5 -set WIDTH 64 GuardedRegisters; hierarchy -check -top GuardedRegisters; synth -top GuardedRegisters; check -assert; select -assert-none t:$dlatch"))) {
      val(status,log)=Increment66ToolEvidence.run(dir,cmd);assert(status==0,s"$dir\n$log")
    }
  }
  for(mode<-Seq("foreign","competing")) test(s"register join does not authorize $mode storage") {
    val error=intercept[Exception] { generate(false,mode) }
    assert(error.toString.contains("SPINAL-") || error.toString.contains("ASSIGNMENT OVERLAP"),error.toString)
  }
  test("guarded register lowering matches an independent common-clock sequential reference by induction") {
    val dir=generate(false,"valid")
    for((n,w,mutation)<-Seq((1,1,false),(3,8,false),(5,36,false),(3,8,true))) {
      val reference=(0 until n).map { i =>
        val slice=s"[${i*w}+:$w]"
        val bits=if(i+1<n) s"expectedBits$slice ^ data$slice" else s"data$slice"
        val signed=if(i+1<n) s"expectedSigned$slice + data$slice" else s"data$slice"
        val flag=if(i+1<n) s"~expectedFlags[$i]" else s"^data$slice"
        s"always @(posedge clk or posedge reset) if(reset) begin expectedBits$slice<=0;expectedSigned$slice<=0;expectedFlags[$i]<=0;end else begin expectedBits$slice<=$bits;expectedSigned$slice<=$signed;expectedFlags[$i]<=$flag;end"
      }.mkString("\n")
      Files.write(dir.resolve("miter.v"),s"""module Miter(input [${n*w-1}:0] data,input clk,reset,output ok);
wire [${n*w-1}:0] actualBits,actualSigned;wire [${n-1}:0] actualFlags;
reg [${n*w-1}:0] expectedBits,expectedSigned;reg [${n-1}:0] expectedFlags;
GuardedRegisters #(.COUNT($n),.WIDTH($w)) dut(.source(data),.bitsResult(actualBits),.signedResult(actualSigned),.flags(actualFlags),.clk(clk),.reset(reset));
$reference
assign ok=(actualBits == (expectedBits ^ ${n*w}'d${if(mutation) 1 else 0})) && (actualSigned == expectedSigned) && (actualFlags == expectedFlags);
endmodule
""".getBytes("UTF-8"))
      val(status,log)=Increment66ToolEvidence.run(dir,Seq("yosys","-Q","-T","-p",
        "read_verilog -formal -DSYNTHESIS GuardedRegisters.v miter.v; prep -top Miter -flatten; async2sync; dffunmap; check -assert; sat -verify -tempinduct -seq 4 -prove ok 1 -set-init-zero -set-def-inputs"))
      if(mutation) assert(status!=0 && log.contains("proof did fail"),log)
      else assert(status==0,s"$dir\n$log")
    }
  }

}
