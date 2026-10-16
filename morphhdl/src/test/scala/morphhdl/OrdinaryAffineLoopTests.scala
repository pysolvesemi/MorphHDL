package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import morphhdl.frontend.HdlInt

class OrdinaryAffineLoopTests extends AnyFunSuite {
  private def emit(symbolic: Boolean) = {
    val dir=Files.createTempDirectory("ordinary-affine-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray))(new Component {
      setDefinitionName("AffineTop")
      val count: ElabInt=HdlInt.param("COUNT",3,1,5).asElabInt
      val width: ElabInt=HdlInt.param("WIDTH",8,8,16).asElabInt
      val source=in Bits(count*width bits)
      val copy=out Bits(count*width bits)
      val split=out Bits(count*width bits)
      val nibble=out Bits(count*4 bits)
      val sof=in Bool();val first=out Bits(count bits)
      val enable=in Bool();val gatedFlags=out Bits(count bits)
      for(i<-0 until count) {
        copy((i+1)*width-1 downto i*width):=source(i*width+width-1 downto i*width)
        split(i*width+3 downto i*width):=source(i*width+3 downto i*width)
        split(i*width+width-1 downto i*width+4):=source(i*width+width-1 downto i*width+4)
        nibble(i*4+3 downto 4*i):=source(i*width+5 downto i*width+2)
        first(i):= (if(i==0) sof else False)
        gatedFlags(i):=False
        when(enable){gatedFlags(i):= (if(i==0) sof else False)}
      }
      val x=in Bits(24 bits);val y=out Bits(24 bits)
      val n=3
      for(i<-0 until n) {
        y(8*i):=x(i*8)
        y(8*i+7 downto 8*i+1):=x(i*8+7 downto i*8+1)
      }
    })
    dir
  }
  test("ordinary affine ranges retain symbolic geometry subwords and Bool-valued guards") {
    val dir=emit(true);val rtl=new String(Files.readAllBytes(dir.resolve("AffineTop.v")),"UTF-8")
    assert(rtl==new String(Files.readAllBytes(emit(true).resolve("AffineTop.v")),"UTF-8"))
    assert(rtl.contains("for (") && rtl.contains("+:"),rtl)
    val checks=(for(n<-Seq(1,2,3,5);w<-Seq(8,11,16))yield s"Check #(.N($n),.W($w)) c_${n}_$w(data,sof);").mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter N=3,W=8)(input[79:0] data,input sof);
wire[N*W-1:0] copy,split;wire[N*4-1:0] nibble;wire[N-1:0] first,gatedFlags;wire[23:0] y;integer i;
AffineTop #(.COUNT(N),.WIDTH(W)) dut(.source(data[N*W-1:0]),.copy(copy),.split(split),.nibble(nibble),.sof(sof),.first(first),.enable(data[0]),.gatedFlags(gatedFlags),.x(data[23:0]),.y(y));
always @(data or sof) begin #1;
if(gatedFlags!==(data[0]===1'b1 ? {{(N-1){1'b0}},sof}:{N{1'b0}}) || split!==data[N*W-1:0] || copy!==data[N*W-1:0] || y!==data[23:0] || first!=={{(N-1){1'b0}},sof}) $$fatal;
for(i=0;i<N;i=i+1) if(nibble[i*4+:4]!==data[i*W+2+:4]) $$fatal;
end endmodule
module tb;reg[79:0]data;reg sof=0;integer i;$checks
initial begin for(i=0;i<100;i=i+1)begin data={$$random,$$random,$$random};sof=i%2;#3;end
data='bx;sof=1'bx;#3;data='bz;sof=1'bz;#3;$$finish;end endmodule
""".getBytes("UTF-8"))
    for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","AffineTop.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","AffineTop.v"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,cmd);assert(code==0,s"$dir\n$log")
    }
  }
  test("disjoint partial word targets cover every bit without hiding gaps") {
    val dir=Files.createTempDirectory("ordinary-subword-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString),preserveConstantLoops=true))(new Component {
      setDefinitionName("SubwordTop")
      val W:Param[Int]=Param(8);require(W>=8 && W<=8)
      val source=in Bits(24 bits);val result=out Bits(24 bits)
      for(i<-0 until 3) {
        result(i*8+3 downto i*8):=source(i*8+3 downto i*8)
        result(i*8+7 downto i*8+4):=source(i*8+7 downto i*8+4)
        // Authored overlap within the same process retains source priority.
        result(i*8+5 downto i*8+2):= ~source(i*8+5 downto i*8+2)
      }
    })
    Files.write(dir.resolve("tb.v"),"""module tb;reg[23:0]source,expected;wire[23:0]result;integer i,j;
SubwordTop dut(source,result);
task check;begin #1;expected=source;for(j=0;j<3;j=j+1)expected[j*8+2+:4]=~source[j*8+2+:4];if(result!==expected)$fatal;end endtask
initial begin for(i=0;i<50;i=i+1)begin source=$random;check;end
source='bx;check;source='bz;check;$finish;end endmodule""".getBytes("UTF-8"))
    for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","SubwordTop.v","tb.v"),Seq("vvp","sim"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,cmd);assert(code==0,log)
    }
    val command=Seq("yosys","-Q","-p","read_verilog -DSYNTHESIS SubwordTop.v; synth -top SubwordTop; check -assert; select -assert-none t:$dlatch")
    val(code,log)=Increment66ToolEvidence.run(dir,command);assert(code==0,log)
  }

  for(mode<-Seq("missing", "bounds", "negative", "reversed")) test(s"partial affine ranges reject $mode") {
    val error=intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("subword-negative-").toString),preserveConstantLoops=true))(new Component {
        val W:Param[Int]=Param(8);require(W>=8 && W<=8)
        val source=in Bits(24 bits);val result=out Bits(24 bits)
        for(i<-0 until 3) {
          result(i*8+3 downto i*8):=source(i*8+3 downto i*8)
          if(mode=="bounds") result(i*8+9 downto i*8+6):=source(i*8+3 downto i*8)
          if(mode=="negative") result(i*8+2 downto i*8-1):=source(i*8+3 downto i*8)
          if(mode=="reversed") result(i*8+2 downto i*8+4):=source(i*8+3 downto i*8)
        }
      })
    }
    val text=Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null).map(e=>Option(e.getMessage).getOrElse("")).mkString("\n")
    val expected=mode match {
      case "missing" => "LATCH DETECTED"
      case "bounds" => "STRUCTURAL-SLICE-DOMAIN-UNSUPPORTED"
      case "negative" => "nonnegative"
      case "reversed" => "RANGE"
    }
    assert(text.contains(expected),text)
  }

  test("symbolic subword bounds are checked beyond the default width") {
    val error=intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("subword-domain-").toString),preserveConstantLoops=true))(new Component {
        val width:ElabInt=HdlInt.param("WIDTH",8,1,16).asElabInt
        val source=in Bits(width*3 bits);val result=out Bits(12 bits)
        for(i<-0 until 3) result(i*4+3 downto i*4):=source(i*width+5 downto i*width+2)
      })
    }
    val text=Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null).map(_.getMessage).mkString("\n")
    assert(text.contains("STRUCTURAL-SLICE-DOMAIN-UNSUPPORTED"),text)
  }

  for(register <- Seq(false, true)) test(s"Bool-valued scoped branches reject effects register=$register") {
    val error=intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("scoped-bool-effects-").toString),preserveConstantLoops=true))(new Component {
        val count:ElabInt=ElabInt.literal(2)
        val enable=in Bool();val input=in Bool();val side=out Bool();side:=False
        val result=out Bits(count bits)
        for(i<-0 until count) {
          result(i):=False
          when(enable) {result(i):= (if(i==0) {if(register) RegNext(input) init(False) else {side:=True;input}} else False)}
        }
      })
    }
    val text=Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null).map(_.getMessage).mkString("\n")
    assert(text.contains("SCOPED-BOOLEAN-RESULT-EFFECT"),text)
  }

}
