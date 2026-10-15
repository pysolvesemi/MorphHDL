package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

final class LightweightAffineWriteProbe(default: Int, registered: Boolean, mode: String) extends Component {
  setDefinitionName("AffineWrite")
  val count: ElabInt = RtlParam.int("COUNT", default, 1, 17)
  val width: ElabInt = RtlParam.int("WIDTH", 8, 1, 64)
  val source = in(Vec(Bits(width bits), count))
  val enable = in Bool()
  val result = out Bits((count * width) bits)
  val lanes = if (registered) Vec.fill(count) {
    val stage = Reg(Bits(width bits)) init(0)
    stage.addAttribute("ASYNC_REG", "TRUE")
    stage
  } else Vec(Bits(width bits), count)
  def write(): Unit = {
    val stride: Int = if(mode == "three") 3 else 2
    val pairs = (count + stride - 1) / stride
    for(i <- 0 until pairs) {
      lanes(stride*i) := source(stride*i)
      if(mode != "missing") {
        if(stride*i+1 < count) lanes(stride*i+1) := ~source(stride*i+1)
      }
      if(mode == "three") if(stride*i+2<count) lanes(stride*i+2) := source(stride*i+2)
      if(mode == "overlap") lanes(stride*i) := 0
    }
  }
  val activity = out Bool()
  if(registered) {
    val side = Reg(Bool()) init(False)
    activity := side
    if(mode == "otherwise") when(enable) { side := !side } otherwise { write() }
    else if(mode == "nested") when(enable) { side := !side; when(enable) { write() } }
    else when(enable) { side := !side; write() }
  } else { activity := False; write() }
  if(mode == "competing") for(i<-0 until count) lanes(i) := source(i)
  result := lanes.asBits
}

class LightweightAffineWriteTests extends AnyFunSuite {
  private def generate(default: Int, registered: Boolean, packed: Boolean, mode: String = "valid") = {
    val dir=Files.createTempDirectory("lightweight-affine-write-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,
      vecLayout=if(packed) MorphAggregateOptions.PackedVector else MorphAggregateOptions.UnpackedArray)) {
      new LightweightAffineWriteProbe(default,registered,mode)
    }
    dir
  }
  for(default<-Seq(1,5); registered<-Seq(false,true); packed<-Seq(false,true);
      mode <- (if(registered && !packed) (if(default==5) Seq("valid","otherwise","nested","three") else Seq("valid","three")) else Seq("valid"))) {
    test(s"exact even odd write union default=$default registered=$registered packed=$packed mode=$mode") {
      val dir=generate(default,registered,packed,mode);val again=generate(default,registered,packed,mode)
      val rtl=new String(Files.readAllBytes(dir.resolve("AffineWrite.v")),"UTF-8")
      assert(rtl==new String(Files.readAllBytes(again.resolve("AffineWrite.v")),"UTF-8"))
      assert(rtl.contains("for (") && rtl.contains("COUNT"),rtl)
      if(registered) assert(rtl.contains("ASYNC_REG"),rtl)
      val strideReference=if(mode=="three") 3 else 2
      val clockPorts=if(registered) ",.clk(clk),.reset(reset)" else ""
      val reference=if(registered && mode=="otherwise") "always @(posedge clk or posedge reset) if(reset) begin expected=0; expectedActivity=0; end else begin if(!enable) expected=next; if(enable) expectedActivity=~expectedActivity; end" else if(registered) "always @(posedge clk or posedge reset) if(reset) begin expected=0; expectedActivity=0; end else if(enable) begin expected=next; expectedActivity=~expectedActivity; end" else "always @* begin expected=next; expectedActivity=0; end"
      val profiles=(for(n<-Seq(1,2,3,5,7,8,15,16,17);w<-Seq(1,8,32,36,64)) yield s"Check #(.N($n),.W($w)) c_${n}_$w(data,clk,reset,enable);").mkString("\n")
      Files.write(dir.resolve("tb.v"),s"""module Check #(parameter N=1,W=8)(input [1087:0] data,input clk,reset,enable);
wire [N*W-1:0] result; wire activity; reg [N*W-1:0] next,expected; reg expectedActivity; integer i;
AffineWrite #(.COUNT(N),.WIDTH(W)) dut(.source(data[N*W-1:0]),.result(result),.activity(activity),.enable(enable)$clockPorts);
always @* begin for(i=0;i<N;i=i+1) if(i%$strideReference == 1) next[i*W+:W]=~data[i*W+:W]; else next[i*W+:W]=data[i*W+:W]; end
$reference
always @(negedge clk) if($$time>0) begin #1; if(result !== expected || activity !== expectedActivity) $$fatal; end
endmodule
module tb; reg [1087:0] data;reg clk=0,reset=0,enable=0; integer i,j;
always #5 clk=~clk;
$profiles
initial begin data=0; #1;reset=1; #11;reset=0;enable=1; #20;
data=~1088'd0; #20;data='bx; #20;data='bz; #20;
for(i=0;i<1088;i=i+1) begin data=1088'd1 << i; #20; end
for(i=0;i<100;i=i+1) begin for(j=0;j<34;j=j+1) data[j*32+:32]=$$random; enable=(i%3!=0); #20; end
reset=1; #13;reset=0;#20;$$finish; end endmodule
""".getBytes("UTF-8"))
      for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","AffineWrite.v","tb.v"),Seq("vvp","sim"),
        Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","AffineWrite.v"),
        Seq("yosys","-Q","-T","-p","read_verilog AffineWrite.v; chparam -set COUNT 17 -set WIDTH 64 AffineWrite; hierarchy -check -top AffineWrite; synth -top AffineWrite; check -assert; select -assert-none t:$dlatch"))) {
        val(status,log)=Increment66ToolEvidence.run(dir,cmd); assert(status==0,s"$dir\n$log")
      }
    }
  }
  for(mode<-Seq("missing","overlap")) test(s"affine writer rejects $mode lanes over the whole domain") {
    val error=intercept[Exception] { generate(1,false,false,mode) }
    assert(error.toString.contains("SPINAL-TYPED-LOOP-AFFINE-WRITE-COVERAGE"),error.toString)
  }
  test("an absent scoped else does not authorize a Scala-only authored branch") {
    val error=intercept[Exception] {
      val dir=Files.createTempDirectory("affine-side-effect-negative-")
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString)) {
        new Component {
          val count: ElabInt=RtlParam.int("COUNT",1,1,17)
          val source=in Bits(count bits)
          val result=out Bits(count bits)
          result := source
          var touched=0
          ElabFiniteRange.foreach(count, "runtime_empty_guard") { i =>
            i.whenAffineLessThan(2, 1, count, "empty", emptyFalse = true) { touched += 1 } { () }
          }
        }
      }
    }
    assert(error.toString.contains("STRUCTURAL-SCALA-SIDE-EFFECT-UNSUPPORTED"),error.toString)
  }

  for(registered<-Seq(false,true)) test(s"an additional finite writer cannot borrow affine coverage registered=$registered") {
    val error=intercept[Exception] { generate(1,registered,false,"competing") }
    assert(error.toString.contains("SPINAL-TYPED-LOOP-AFFINE-WRITE-COVERAGE"),error.toString)
  }

}

/** Independent flat reference: no native selector or compiler proof metadata. */
class LightweightAffineWriteFormalTests extends AnyFunSuite {
  for (packed <- Seq(false, true); stride <- Seq(2, 3)) {
    test(s"affine partition matches flat bit mapping packed=$packed stride=$stride") {
      val dir = Files.createTempDirectory("lightweight-affine-formal-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
        headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
        vecLayout = if (packed) MorphAggregateOptions.PackedVector else MorphAggregateOptions.UnpackedArray)) {
        new LightweightAffineWriteProbe(1, false, if (stride == 3) "three" else "valid")
      }
      for ((count, width) <- Seq((1, 1), (3, 8), (17, 36));
           mutation <- (if (count == 3) Seq(false, true) else Seq(false))) {
        val words = (0 until count).reverse.map { lane =>
          val slice = s"data[${lane * width}+:$width]"
          if (lane % stride == 1) s"~$slice" else slice
        }.mkString("{", ",", "}")
        val reference = if (mutation) s"($words ^ ${count * width}'d1)" else words
        Files.write(dir.resolve("miter.v"), s"""module Miter(input [${count * width - 1}:0] data, input enable, output ok);
wire [${count * width - 1}:0] result; wire activity;
AffineWrite #(.COUNT($count), .WIDTH($width)) dut(.source(data), .enable(enable), .result(result), .activity(activity));
assign ok = (result == $reference) && !activity;
endmodule
""".getBytes("UTF-8"))
        val (status, log) = Increment66ToolEvidence.run(dir, Seq("yosys", "-Q", "-T", "-p",
          "read_verilog -formal -DSYNTHESIS AffineWrite.v miter.v; prep -top Miter -flatten; check -assert; sat -verify -prove ok 1 -set-def-inputs -show-inputs"))
        if (mutation) assert(status != 0 && log.contains("proof did fail"), log)
        else assert(status == 0, s"count=$count width=$width $dir\n$log")
      }
    }
  }
}
