package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class OrdinaryLoopCornerTests extends AnyFunSuite {
  private def generate(count:Int,inclusive:Boolean,packed:Boolean) = {
    val dir=Files.createTempDirectory("ordinary-loop-corner-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantLoops=true,preserveConstantVecs=true,
      vecLayout=if(packed)MorphAggregateOptions.PackedVector else MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("LoopCorner")
        val W:Param[Int]=Param(8);require(W>=8 && W<=8)
        val enable=in Bool()
        val source=in Bits(32 bits)
        val words=out Bits(32 bits)
        val arrayResult=out Bits(32 bits)
        val signedResult=out Bits(32 bits)
        val lanes=Vec.fill(4)(Bits(W bits))
        val signedLanes=Vec.fill(4)(SInt(W bits))
        words:=B(BigInt("12345678",16),32 bits)
        for(i<-0 until 4) lanes(i):=source(i*8,8 bits)
        for(i<-0 until 4) signedLanes(i):=source(i*8,8 bits).asSInt
        arrayResult:=lanes.asBits
        signedResult:=signedLanes.asBits
        when(enable) {
          if(inclusive) {
            for(i<-0 to 3) words(i*8,8 bits):=source(i*8,8 bits)
          } else {
            for(i<-0 until count) words(i*8,8 bits):=source(i*8,8 bits)
          }
        }
      }
    }
    dir
  }
  for(packed<-Seq(false,true);inclusive<-Seq(false,true)) test(s"packed word defaults and array copy packed=$packed inclusive=$inclusive") {
    val dir=generate(4,inclusive,packed)
    Files.write(dir.resolve("tb.v"),"""module tb;
reg enable;reg[31:0]source;wire[31:0]words,arrayResult,signedResult;integer i;
LoopCorner dut(enable,source,words,arrayResult,signedResult);
initial begin for(i=0;i<100;i=i+1)begin source=$random;enable=i%2;#1;
if(arrayResult!==source || signedResult!==source || words!==(enable?source:32'h12345678))$fatal(1,"word defaults");end
 enable=1'bx;#1;if(words!==32'h12345678)$fatal;enable=1'bz;#1;if(words!==32'h12345678)$fatal;
$finish;end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","LoopCorner.v","tb.v"),Seq("vvp","sim"),
      Seq("yosys","-Q","-p","read_verilog -DSYNTHESIS LoopCorner.v; synth -top LoopCorner; check -assert; select -assert-none t:$dlatch"))) {
      val result=Increment66ToolEvidence.run(dir,command);assert(result._1==0,result._2)
    }
  }
  for(count<-Seq(0,-1)) test(s"empty ordinary range $count preserves whole default") {
    val dir=generate(count,false,false)
    val rtl=new String(Files.readAllBytes(dir.resolve("LoopCorner.v")),"UTF-8")
    assert(rtl.contains("12345678"),rtl)
  }
  test("partial word loop cannot silently overlap a whole default") {
    val error=intercept[Exception](generate(3,false,false))
    assert(Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null)
      .exists(e=>Option(e.getMessage).exists(_.contains("FINITE-PACKED-DEFAULT-UNPROVEN"))),error.toString)
  }
  test("out of range ordinary access is rejected") {
    intercept[Exception](generate(5,false,false))
  }
  test("sized index values reject truncation of the admitted loop domain") {
    val error=intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("index-width-negative-").toString),
        preserveConstantLoops=true)) {
        new Component {
          val result=out Vec(Bits(1 bits),ElabInt.literal(4))
          for(i<-0 until 4)result(i):=B(i,1 bits)
        }
      }
    }
    assert(Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null)
      .exists(e=>Option(e.getMessage).exists(_.contains("FINITE-INDEX-VALUE-WIDTH"))),error.toString)
  }
}
