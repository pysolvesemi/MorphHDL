package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class ParameterMaskTests extends AnyFunSuite {
  test("readable parameter shifts and low masks retain override semantics") {
    val dir=Files.createTempDirectory("parameter-mask-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        setDefinitionName("ParameterMask")
        val MASK:ElabInt=RtlParam.int("MASK",0,0,63)
        val LEVEL:ElabInt=RtlParam.int("LEVEL",0,0,5)
        val source=in Bool()
        val result=out Bool()
        val selected=((MASK >> LEVEL) & 1) != 0
        if(selected) result := source else result := False
      }
    }
    val instances=(for(mask<-Seq(0,1,2,5,21,32,63);level<-0 to 5)yield
      s"Check #(.M($mask),.L($level)) c_${mask}_$level(source);").mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter M=0,L=0)(input source);
wire result;
ParameterMask #(.MASK(M),.LEVEL(L)) dut(.source(source),.result(result));
always @(source) begin #1; if(result !== (((M >> L) & 1) ? source : 1'b0)) $$fatal;end
endmodule
module tb;reg source;
$instances
initial begin source=0;#3;source=1;#3;source=1'bx;#3;source=1'bz;#3;$$finish;end
endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","ParameterMask.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","ParameterMask.v"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,command);assert(code==0,log)
    }
  }
  test("correlated logarithmic shifts use the authenticated generate guard") {
    val dir=Files.createTempDirectory("correlated-mask-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        setDefinitionName("CorrelatedMask")
        val MASK:ElabInt=RtlParam.int("MASK",0,0,31)
        val INITIAL:ElabInt=RtlParam.int("INITIAL",1,1,17)
        val COUNT:ElabInt=RtlParam.int("COUNT",1,1,17)
        val source=in Bool(); val result=out Bool()
        if(INITIAL >= COUNT) {
          val level=INITIAL.log2Up-COUNT.log2Up
          if(((MASK >> level)&1)!=0) result:=source else result:=False
        } else result:=False
      }
    }
    def lg(n:Int)=32-Integer.numberOfLeadingZeros(n-1)
    val instances=(for(a<-Seq(1,2,3,5,8,16,17);b<-Seq(1,2,3,7,16,17);m<-Seq(0,1,5,16,31))yield {
      val selected=a>=b && ((m >> (lg(a)-lg(b)))&1)!=0
      s"Check #(.A($a),.B($b),.M($m),.E(${if(selected)1 else 0})) c_${a}_${b}_$m(source);"
    }).mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter A=1,B=1,M=0,E=0)(input source);
wire result;CorrelatedMask #(.INITIAL(A),.COUNT(B),.MASK(M)) dut(.source(source),.result(result));
always @(source) begin #1;if(result !== (E ? source : 1'b0)) $$fatal;end
endmodule
module tb;reg source;
$instances
initial begin source=0;#3;source=1;#3;source=1'bx;#3;source=1'bz;#3;$$finish;end
endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","CorrelatedMask.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","CorrelatedMask.v"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,command);assert(code==0,log)
    }
  }

  test("unguarded logarithmic differences and simulation requirements do not authorize shifts") {
    for(deferred<-Seq(false,true)) {
      val dir=Files.createTempDirectory("unguarded-mask-")
      val error=intercept[Exception] {
        MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
          new Component {
            val initial:ElabInt=RtlParam.int("A",8,1,17)
            val count:ElabInt=RtlParam.int("B",1,1,17)
            val MASK:ElabInt=RtlParam.int("MASK",0,0,31)
            val result=out Bool()
            if(deferred) require(initial >= count)
            val distance: ElabInt = initial.log2Up - count.log2Up
            if(((MASK >> distance)&1)!=0) result:=True else result:=False
          }
        }
      }
      assert(error.toString.contains("POW2-DOMAIN-UNSUPPORTED"),error.toString)
    }
  }

  test("invalid shift domains and noncontiguous masks fail without witness truncation") {
    for(distance<-Seq(-1,31,32)) {
      val error=intercept[Exception] { ElabInt.literal(7) >> distance }
      assert(error.getMessage.contains("POW2-DOMAIN-UNSUPPORTED"),error.getMessage)
    }
    for(mask<-Seq(-1,2,5)) {
      val error=intercept[IllegalArgumentException] { ElabInt.literal(7) & mask }
      assert(error.getMessage.contains("MASK-UNSUPPORTED"),error.getMessage)
    }
  }
}
