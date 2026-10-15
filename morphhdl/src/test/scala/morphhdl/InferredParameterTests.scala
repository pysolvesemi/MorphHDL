package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class InferredParameterTests extends AnyFunSuite {
  for(default <- Seq(1,8); inverted <- Seq(false,true)) test(s"Param declarations infer names and authored domains default=$default inverted=$inverted") {
    val dir=Files.createTempDirectory("inferred-param-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        setDefinitionName("InferredParameters")
        val WIDTH: Param[Int] = Param(default)
        val INVERT: Param[Boolean] = Param(inverted)
        require(WIDTH >= 1 && WIDTH <= 17)
        val next = WIDTH + 1
        val source=in Bits(WIDTH bits)
        val result=out Bits(WIDTH bits)
        val extended=out Bits(next bits)
        if(INVERT) result := ~source else result := source
        extended:=source.resize(next)
      }
    }
    val rtl=new String(Files.readAllBytes(dir.resolve("InferredParameters.v")),"UTF-8")
    assert(rtl.contains("parameter integer WIDTH") && rtl.contains("parameter integer INVERT"),rtl)
    assert(!rtl.contains("parameter integer next"),rtl)
    val profiles=(for(w<-Seq(1,2,7,8,16,17);i<-Seq(0,1))yield s"Check #(.W($w),.I($i)) c_${w}_$i(data);").mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter W=1,I=0)(input [16:0] data);
wire [W-1:0] result;wire [W:0] extended;
InferredParameters #(.WIDTH(W),.INVERT(I)) dut(.source(data[W-1:0]),.result(result),.extended(extended));
always @(data) begin #1;if(result !== (I ? ~data[W-1:0] : data[W-1:0]) || extended !== {1'b0,data[W-1:0]}) $$fatal;end endmodule
module tb;reg [16:0] data;integer n;
$profiles
initial begin data=0;#3;data=~17'd0;#3;data='bx;#3;data='bz;#3;
for(n=0;n<50;n=n+1)begin data=$$random;#3;end $$finish;end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","InferredParameters.v","tb.v"),Seq("vvp","sim"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,command);assert(code==0,log)
    }
  }
}
