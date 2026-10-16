package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import _root_.spinal.core.ElabInt

case class Lwr23RecursiveConfig(count:Param[Int], mode:Int)
@_root_.spinal.core.rtlRecursive
final class Lwr23Recursive(c:Lwr23RecursiveConfig, changeMode:Boolean) extends Component {
  setDefinitionName("RecursiveConfiguration")
  noIoPrefix()
  require(c.count>=1 && c.count<=4)
  val io=new Bundle {
    val terms=in(Vec(UInt(8 bits),c.count))
    val result=out UInt(16 bits)
  }
  if(c.count==1) io.result:=io.terms(0).resize(16)
  else {
    val child=new Lwr23Recursive(Lwr23RecursiveConfig(c.count-1,if(changeMode)1 else c.mode),changeMode)
    val remaining=Vec(UInt(8 bits),c.count-1)
    for(i<-0 until (c.count-1)) remaining(i):=io.terms(i+1)
    child.io.terms:=remaining
    if(c.mode==0)io.result:=child.io.result+io.terms(0).resize(16)
    else io.result:=child.io.result-io.terms(0).resize(16)
  }
}

class RecursiveConfigurationParameterTests extends AnyFunSuite {
  test("guarded recursion binds configuration fields and preserves ordinary specialization") {
    val directory=Files.createTempDirectory("recursive-config-")
    MorphVerilog(SpinalConfig(targetDirectory=directory.toString,headerWithDate=false)) {
      new Lwr23Recursive(Lwr23RecursiveConfig(3,0),false)
    }
    val rtl=new String(Files.readAllBytes(directory.resolve("RecursiveConfiguration.v")),"UTF-8")
    assert("module RecursiveConfiguration".r.findAllIn(rtl).length==1,rtl)
    for(count<-1 to 4) {
      Files.write(directory.resolve("tb.v"),s"""module tb;
reg[${count*8}-1:0]terms;wire[15:0]result;reg[15:0]expected;integer i,j;
RecursiveConfiguration #(.C_COUNT($count)) dut(terms,result);
initial begin for(i=0;i<128;i=i+1)begin terms=$$random;expected=0;
for(j=0;j<$count;j=j+1)expected=expected+terms[j*8+:8];#1;
if(result!==expected)$$fatal(1,"recursive configuration");end $$finish;end endmodule
""".getBytes("UTF-8"))
      for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","RecursiveConfiguration.v","tb.v"),Seq("vvp","sim"))) {
        val result=Increment66ToolEvidence.run(directory,command)
        assert(result._1==0,command.mkString(" ")+"\n"+result._2)
      }
    }
  }
  test("recursive edges cannot change ordinary configuration fields") {
    val directory=Files.createTempDirectory("recursive-config-negative-")
    val error=intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=directory.toString,headerWithDate=false)) {
        new Lwr23Recursive(Lwr23RecursiveConfig(3,0),true)
      }
    }
    assert(Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null)
      .exists(e=>Option(e.getMessage).exists(_.contains("RECURSIVE-CONFIG-SPECIALIZATION-MISMATCH"))),error.toString)
  }
}
