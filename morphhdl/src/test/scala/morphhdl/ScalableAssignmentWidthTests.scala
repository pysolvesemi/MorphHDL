package morphhdl

import java.nio.file.{Files,Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class ScalableTimeoutCounter extends Component {
  setDefinitionName("ScalableTimeoutCounter")
  val timeout: Param[Int] = Param(1024)
  require(timeout >= 32 && timeout <= (1 << 24))
  val enable, clear = in Bool()
  val result = out UInt((timeout + 1).log2Up bits)
  val age = Reg(UInt((timeout + 1).log2Up bits)) init(0)
  when(clear) { age := 0 } otherwise { when(enable) { age := age + 1 } }
  result := age
}

class ScalableWidthCopy(mismatch: Boolean) extends Component {
  setDefinitionName("ScalableWidthCopy")
  val timeout: Param[Int] = Param(1023)
  require(timeout >= 32 && timeout <= (1 << 24))
  val width = (timeout + 1).log2Up
  val selected = ElaborationWidthAuthority.maximum(width, ElabInt.literal(1))
  val source = in UInt((if(mismatch) timeout.log2Up else selected) bits)
  val result = out UInt(width bits)
  result := source
}

class ScalableAssignmentWidthTests extends AnyFunSuite {
  private def generate(body: => Component):Path={
    val dir=Files.createTempDirectory("scalable-width-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(body)
    dir
  }
  private def run(dir:Path,args:String*):Unit={
    val r=Increment66ToolEvidence.run(dir,args);assert(r._1==0,args.mkString(" ")+"\n"+r._2)
  }
  test("large timeout counter preserves symbolic initialized and clear-to-zero assignment widths") {
    val dir=generate(new ScalableTimeoutCounter)
    val rtl=new String(Files.readAllBytes(dir.resolve("ScalableTimeoutCounter.v")),"UTF-8")
    val again=generate(new ScalableTimeoutCounter)
    assert(rtl==new String(Files.readAllBytes(again.resolve("ScalableTimeoutCounter.v")),"UTF-8"))
    for(timeout<-Seq(32,33,63,64,65,1023,1024,1025,16777215,16777216)) {
      val bits=BigInt(timeout).bitLength
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg clk=0;always #5 clk=~clk;reg reset=1;reg enable=0;reg clear=0;wire[$bits-1:0]result;
ScalableTimeoutCounter #(.TIMEOUT($timeout)) dut(.clk(clk),.reset(reset),.enable(enable),.clear(clear),.result(result));
integer n;reg[$bits-1:0]expected=0;
initial begin repeat(2)@(negedge clk);reset=0;
for(n=0;n<200;n=n+1)begin enable=(n%5!=0);clear=(n==17 || n==101);
if(clear)expected=0;else if(enable)expected=expected+1;
@(posedge clk);#1;if(result!==expected)$$fatal(1,"counter mismatch");@(negedge clk);end
reset=1;#1;if(result!==0)$$fatal(1,"reset mismatch");$$finish;end endmodule
""".getBytes("UTF-8"))
      run(dir,"iverilog","-g2001","-s","tb","-o","sim","ScalableTimeoutCounter.v","tb.v")
      run(dir,"vvp","sim")
      run(dir,"yosys","-Q","-T","-p",s"read_verilog ScalableTimeoutCounter.v; chparam -set TIMEOUT $timeout ScalableTimeoutCounter; hierarchy -check -top ScalableTimeoutCounter; proc; opt; check -assert; select -assert-none t:$$dlatch")
    }
    run(dir,"verilator","--lint-only","--language","1364-2001","-Wno-fatal","-DSYNTHESIS","ScalableTimeoutCounter.v")
  }
  test("dominated alternative widths publish equivalent parameterized transports") {
    val dir=generate(new ScalableWidthCopy(false))
    for(timeout<-Seq(32,33,1023,1024,16777216)) {
      val bits=BigInt(timeout).bitLength
      Files.write(dir.resolve("tb.v"),s"""module tb;reg[$bits-1:0]source;wire[$bits-1:0]result;integer i;
ScalableWidthCopy #(.TIMEOUT($timeout)) dut(.source(source),.result(result));
initial begin for(i=0;i<32;i=i+1)begin source=$$random;#1;if(result!==source)$$fatal(1,"copy mismatch");end $$finish;end endmodule
""".getBytes("UTF-8"))
      run(dir,"iverilog","-g2001","-s","tb","-o","sim","ScalableWidthCopy.v","tb.v");run(dir,"vvp","sim")
    }
    run(dir,"verilator","--lint-only","--language","1364-2001","-Wno-fatal","-DSYNTHESIS","ScalableWidthCopy.v")
  }
  test("default-equal but domain-mismatched assignments fail with actionable context") {
    val error=intercept[Exception]{generate(new ScalableWidthCopy(true))}
    val text=error.toString
    for(fragment<-Seq("ASSIGNMENT-WIDTH-MISMATCH","ScalableWidthCopy","result","target width","source width","TIMEOUT=[32,16777216]","assignment location","ScalableAssignmentWidthTests.scala"))
      assert(text.contains(fragment),text)
    assert(!text.contains("16777185 tuples"),text)
  }
}
