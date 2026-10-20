package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class PackedParameterTests extends AnyFunSuite {
  for(representationWidth<-Seq(19,64)) test(s"packed parameter types retain declared widths signedness and override values width=$representationWidth") {
    val dir=Files.createTempDirectory("packed-parameters-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        setDefinitionName("PackedParameters")
        val COUNT:Param[UInt]=Param(U(8,representationWidth bits))
        val MASK:Param[Bits]=Param(B(5,8 bits))
        val OFFSET:Param[SInt]=Param(S(-3,representationWidth bits))
        require(COUNT>=1 && COUNT<=17)
        require(OFFSET>= -8 && OFFSET<=7)
        val source=in Bits(8 bits)
        val count=out UInt(24 bits)
        val wideCount=out UInt(64 bits)
        val wideOffset=out SInt(64 bits)
        val masked=out Bits(8 bits)
        val offset=out SInt(9 bits)
        count:=U(COUNT,24 bits)
        wideCount:=U(COUNT,64 bits)
        wideOffset:=S(OFFSET,64 bits)
        masked:=source ^ B(MASK,8 bits)
        offset:=S(OFFSET+1,9 bits)
      }
    }
    val rtl=new String(Files.readAllBytes(dir.resolve("PackedParameters.v")),"UTF-8")
    assert(rtl.contains(s"parameter [${representationWidth-1}:0] COUNT = ${representationWidth}'d8"),rtl)
    assert(rtl.contains("parameter [7:0] MASK = 8'd5"),rtl)
    assert(rtl.contains(s"parameter signed [${representationWidth-1}:0] OFFSET = -${representationWidth}'sd3"),rtl)
    val profiles=(for(c<-Seq(1,8,17);m<-Seq(0,5,128,255);o<-Seq(-8,-1,0,7))yield
      s"Check #(.C($c),.M($m),.O($o)) c_${c}_${m}_${o+8}(source);").mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter C=1,M=0,O=0)(input [7:0] source);
wire [23:0] count;wire [63:0] wideCount;wire signed [63:0] wideOffset;wire [7:0] masked;wire signed [8:0] offset;
PackedParameters #(.COUNT(C),.MASK(M),.OFFSET(O)) dut(.source(source),.count(count),.wideCount(wideCount),.wideOffset(wideOffset),.masked(masked),.offset(offset));
always @(source) begin #1;if(count!==C || wideCount!==C || wideOffset!==O || masked!==(source ^ M[7:0]) || offset !== (O+1)) $$fatal;end
endmodule
module tb;reg [7:0] source;integer i;
$profiles
initial begin source=0;#3;source=255;#3;source='bx;#3;source='bz;#3;
for(i=0;i<50;i=i+1)begin source=$$random;#3;end $$finish;end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","PackedParameters.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","PackedParameters.v"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,command);assert(code==0,log)
    }
  }
  test("signed Int boundary parameters extend to wide hardware without unsigned reinterpretation") {
    val dir=Files.createTempDirectory("signed-param-boundary-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString)) {
      new Component {
        setDefinitionName("SignedBoundary")
        val VALUE:Param[SInt]=Param(S(BigInt("-2147483648"),32 bits))
        require(VALUE >= -2147483648 && VALUE <= -2147483641)
        val source=in Bool()
        val result=out SInt(64 bits)
        result:=Mux(source,S(VALUE,64 bits),S(VALUE+1,64 bits))
      }
    }
    Files.write(dir.resolve("tb.v"),"""module tb;
reg source=0;wire signed [63:0] result;
SignedBoundary #(.VALUE(-32'sd2147483647)) dut(.source(source),.result(result));
initial begin #1;if(result!== -64'sd2147483646)$fatal;source=1;#1;if(result!== -64'sd2147483647)$fatal;$finish;end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","SignedBoundary.v","tb.v"),Seq("vvp","sim"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,command);assert(code==0,log)
    }
  }
  test("packed formals preserve independent actuals and shared definition representation") {
    class Child(actual:Param[UInt]) extends Component {
      setDefinitionName("PackedChild")
      val VALUE:Param[UInt]=Param(actual)
      require(VALUE>=1 && VALUE<=17)
      val source=in UInt(19 bits)
      val result=out UInt(19 bits)
      result:=source ^ U(VALUE,19 bits)
    }
    val dir=Files.createTempDirectory("packed-forward-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        setDefinitionName("PackedParent")
        val A:Param[UInt]=Param(U(2,19 bits))
        val B_VALUE:Param[UInt]=Param(U(9,19 bits))
        require(A>=1 && A<=17)
        require(B_VALUE>=1 && B_VALUE<=17)
        val source=in UInt(19 bits)
        val first=out UInt(19 bits)
        val second=out UInt(19 bits)
        val a=new Child(A);val b=new Child(B_VALUE)
        a.source:=source;b.source:=source
        first:=a.result;second:=b.result
      }
    }
    val rtl=new String(Files.readAllBytes(dir.resolve("PackedParent.v")),"UTF-8")
    assert("(?m)^module PackedChild\\b".r.findAllIn(rtl).size==1,rtl)
    assert(rtl.contains(".VALUE") && rtl.contains("B_VALUE"),rtl)
    Files.write(dir.resolve("tb.v"),"""module tb;
reg [18:0] source;wire [18:0] a,b;
PackedParent #(.A(17),.B_VALUE(1)) dut(.source(source),.first(a),.second(b));
initial begin source=0;#1;if(a!==17 || b!==1)$fatal;
source=19'h7ffff;#1;if(a!==(source^19'd17)||b!==(source^19'd1))$fatal;$finish;end
endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","PackedParent.v","tb.v"),Seq("vvp","sim"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,command);assert(code==0,log)
    }
  }
  test("packed declarations reject contradictory types domains and overflow") {
    for(mode<-Seq("unbounded","outside","wrongType","overflow")) {
      val error=intercept[Exception] {
        MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("bad-packed-domain-").toString)) {
          new Component {
            val actual=if(mode=="wrongType")Param(B(3,8 bits)) else Param(U(3,19 bits))
            val value=RtlParam.packed("VALUE",actual,if(mode=="outside")4 else 0,
              if(mode=="unbounded")Int.MaxValue else 17,"UInt")
            val source=in UInt(8 bits)
            val result=out UInt(8 bits)
            result:=source ^ U(if(mode=="overflow")value+256 else value,8 bits)
          }
        }
      }
      val expected=Map("unbounded"->"PARAM-DOMAIN-UNPROVEN","outside"->"FORMAL-DOMAIN-INVALID",
        "wrongType"->"PARAM-REPRESENTATION-MISMATCH","overflow"->"VALUE-WIDTH-INSUFFICIENT")(mode)
      assert(error.getMessage.contains(expected),error.getMessage)
    }
  }
  test("packed defaults reject hardware sources and implicit literal sizing") {
    for(mode<-Seq("input","unsized","wire")) {
      val error=intercept[Exception] {
        MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("bad-packed-").toString)) {
          new Component {
            val source=in UInt(8 bits)
            val wire=UInt(8 bits);wire:=3
            val actual=if(mode=="input")source else if(mode=="wire")wire else U(3)
            val VALUE:Param[UInt]=Param(actual)
            require(VALUE>=0 && VALUE<=7)
            val result=out UInt(8 bits)
            result:=U(VALUE,8 bits)
          }
        }
      }
      assert(error.getMessage.contains("PARAM-LITERAL-REQUIRED"),error.getMessage)
    }
  }
}
