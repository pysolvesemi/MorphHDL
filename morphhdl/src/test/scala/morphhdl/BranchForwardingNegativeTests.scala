package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.HdlInt

class BranchForwardingNegativeTests extends AnyFunSuite {
  private class SignedChild(actual: ElabInt) extends Component {
    val width: Param[Int]=Param(actual,"WIDTH")
    require(width >= 1 && width <= 64)
    val x=in Bits(width bits)
    val y=out SInt(width bits)
    y:=x.asSInt
  }
  test("a narrower signed child result retains its extension and forwarding boundary") {
    val dir=Files.createTempDirectory("branch-signed-width-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray))(new Component {
      setDefinitionName("SignedTop")
      val width=HdlInt.param("WIDTH",8,2,64).asElabInt
      val count=HdlInt.param("COUNT",2,1,3).asElabInt
      val x=in Bits(width bits);val y=out UInt(width bits)
      ElabControl.selectSymbolic(count.elabEq(1),"Negative.scala",1) { y:=x.asUInt } {
        val child=new SignedChild(width-1)
        child.x:=x.resize(width-1)
        val signedValue=SInt((width-1) bits).dontSimplifyIt()
        signedValue:=child.y
        y:=signedValue.resize(width).asUInt
      }
    })
    val rtl=new String(Files.readAllBytes(dir.resolve("SignedTop.v")),"UTF-8")
    assert("signedChild(?:_[0-9]+)?_y".r.findFirstIn(rtl).nonEmpty,rtl)
    val checks=(for(n<-Seq(1,2,3);w<-Seq(2,5,64)) yield s"Check #(.N($n),.W($w)) c_${n}_$w(data);").mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter N=1,W=8)(input[63:0] data);
wire[W-1:0] y; SignedTop #(.WIDTH(W),.COUNT(N)) dut(.x(data[W-1:0]),.y(y));
always @(data) begin #1; if(y !== ((N==1) ? data[W-1:0] : {data[W-2],data[W-2:0]})) $$fatal;end
endmodule
module tb;reg[63:0]data;integer i;$checks
initial begin data=0;#3;data=~64'd0;#3;for(i=0;i<100;i=i+1)begin data={$$random,$$random};#3;end
data='bx;#3;data='bz;#3;$$finish;end endmodule
""".getBytes("UTF-8"))
    for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","SignedTop.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","SignedTop.v"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,cmd);assert(code==0,s"$dir\n$log")
    }
  }
  test("a competing unconditional assignment is still rejected") {
    val dir=Files.createTempDirectory("branch-conflicting-driver-")
    val error=intercept[Throwable] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString),
        preserveConstantVecs=true,preserveConstantLoops=true))(new Component {
        val count=HdlInt.param("COUNT",2,1,3).asElabInt
        val x=in UInt(8 bits);val y=out UInt(8 bits)
        ElabControl.selectSymbolic(count.elabEq(1),"Negative.scala",2) { y:=x } { y:= ~x }
        y:=x+1
      })
    }
    assert(error.toString.contains("ASSIGNMENT OVERLAP"),error.toString)
    assert(!Files.exists(dir.resolve("Top.v")))
  }
}
