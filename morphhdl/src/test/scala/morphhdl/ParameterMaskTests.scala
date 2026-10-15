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
