package morphhdl

import spinal.core._
import java.nio.file.Files
import java.nio.charset.StandardCharsets.UTF_8
import org.scalatest.funsuite.AnyFunSuite

class ParameterAnnotationTests extends AnyFunSuite {
  class Responses extends Component {
    @param val COUNT = 8
    @localparam val OKAY = B(0, 2 bits)
    @localparam val SLVERR = B(2, 2 bits)
    val DECERR = Param(B(3, 2 bits), local = true)
    val selector=in UInt(2 bits)
    val response=out Bits(2 bits)
    val count=out UInt(32 bits)
    count := U(COUNT,32 bits)
    response:=OKAY
    switch(selector) {
      is(1) {response:=SLVERR}
      is(2) {response:=DECERR}
    }
  }
  class ApiResponses extends Component {
    setDefinitionName("Responses")
    val COUNT=Param(8,local=false)
    val OKAY=Param(B(0,2 bits),local=true)
    val SLVERR=Param(B(2,2 bits),local=true)
    val DECERR=Param(B(3,2 bits),local=true)
    val selector=in UInt(2 bits)
    val response=out Bits(2 bits)
    val count=out UInt(32 bits)
    count:=U(COUNT,32 bits)
    response:=OKAY
    switch(selector) {is(1){response:=SLVERR};is(2){response:=DECERR}}
  }
  test("annotations and factory locals preserve CSR encodings without constant wires") {
    val dir=Files.createTempDirectory("parameter-annotations-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(new Responses)
    val rtl=new String(Files.readAllBytes(dir.resolve("Responses.v")),UTF_8)
    assert(rtl.contains("localparam") && rtl.contains("OKAY") && rtl.contains("2'b10"),rtl)
    assert(!rtl.linesIterator.exists(l=>l.trim.startsWith("wire") && Seq("OKAY","SLVERR","DECERR").exists(l.contains)),rtl)
    val repeated=Files.createTempDirectory("parameter-annotations-repeat-")
    MorphVerilog(SpinalConfig(targetDirectory=repeated.toString,headerWithDate=false))(new Responses)
    assert(rtl==new String(Files.readAllBytes(repeated.resolve("Responses.v")),UTF_8))
    Seq(Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Responses","Responses.v"),
      Seq("yosys","-Q","-p","read_verilog -DSYNTHESIS Responses.v; synth -top Responses; check -assert")).foreach {command=>
      val result=Increment66ToolEvidence.run(dir,command);assert(result._1==0,result._2)
    }
    val api=Files.createTempDirectory("parameter-api-equivalence-")
    MorphVerilog(SpinalConfig(targetDirectory=api.toString,headerWithDate=false))(new ApiResponses)
    assert(rtl==new String(Files.readAllBytes(api.resolve("Responses.v")),UTF_8))
    for(n<-Seq(0,1,8,17)) {
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg [1:0] selector; wire [1:0] response; wire [31:0] count; integer i;
Responses #(.COUNT($n)) dut(selector,response,count);
initial begin for(i=0;i<4;i=i+1) begin selector=i; #1;
if(count!==$n || response!==((i==1)?2'd2:(i==2)?2'd3:2'd0)) $$fatal;
end selector=2'bxx; #1; if(response!==0) $$fatal; selector=2'bzz; #1; if(response!==0) $$fatal; $$finish; end endmodule
""".getBytes(UTF_8))
      Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","Responses.v","tb.v"),Seq("vvp","sim")).foreach {command=>
        val result=Increment66ToolEvidence.run(dir,command);assert(result._1==0,result._2)
      }
    }
  }
  class TypedValues extends Component {
    @param val WIDTH = 8
    require(WIDTH >= 1 && WIDTH <= 16)
    @param val ENABLE = true
    val MASK = Param(B(5,3 bits),local=false)
    @localparam val NEGATIVE = S(-3,5 bits)
    @localparam val ONES = B(BigInt("ffffffff",16),32 bits)
    @localparam val FLAG = True
    val FLAG_PARAM=Param(True,local=false)
    @localparam val DERIVED = U(WIDTH+1,5 bits)
    val input=in Bits(3 bits)
    val output=out Bits(3 bits)
    val signedResult=out SInt(5 bits)
    val allOnes=out Bits(32 bits)
    val boolResult=out Bool()
    val derivedResult=out UInt(5 bits)
    val publicFlag=out Bool();publicFlag:=FLAG_PARAM
    output:= input ^ MASK
    signedResult:=NEGATIVE;allOnes:=ONES;boolResult:=FLAG;derivedResult:=DERIVED
  }
  test("typed direct use retains signed values, wide literals and parameter derived values") {
    val dir=Files.createTempDirectory("typed-parameter-annotations-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(new TypedValues)
    val rtl=new String(Files.readAllBytes(dir.resolve("TypedValues.v")),UTF_8)
    assert(rtl.contains("localparam signed") && rtl.contains("NEGATIVE"),rtl)
    assert(!rtl.contains("typed_value_"),rtl)
    Seq(Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","TypedValues","TypedValues.v"),
      Seq("yosys","-Q","-p","read_verilog -DSYNTHESIS TypedValues.v; synth -top TypedValues; check -assert")).foreach {command=>
      val result=Increment66ToolEvidence.run(dir,command);assert(result._1==0,result._2)
    }
    Files.write(dir.resolve("tb.v"),"""module tb;
reg [2:0] inputValue; wire [2:0] outputValue; wire signed [4:0] negative;
wire [31:0] allOnes; wire flag; wire [4:0] derived;wire publicFlag;
TypedValues #(.WIDTH(16),.MASK(3),.FLAG_PARAM(0)) dut(inputValue,outputValue,negative,allOnes,flag,derived,publicFlag);
initial begin inputValue=6; #1;
if(outputValue!==5 || negative!==-5'sd3 || allOnes!==32'hffffffff || flag!==1 || derived!==17 || publicFlag!==0) $fatal;
$finish; end endmodule
""".getBytes(UTF_8))
    Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","TypedValues.v","tb.v"),Seq("vvp","sim")).foreach {command=>
      val result=Increment66ToolEvidence.run(dir,command);assert(result._1==0,result._2)
    }
  }
  for(mode<-0 to 4) test(s"constant ownership and mutable source rejection $mode") {
    val error=intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("bad-constant-").toString)) {
        new Component {
          val input=in Bits(2 bits)
          val source=if(mode==0)input else if(mode==1)(Reg(Bits(2 bits)) init(0)) else B(0,2 bits)
          val constant=Param(source,local=true)
          if(mode==2)source:=2
          if(mode==4)constant:=2
          val output=out Bits(2 bits)
          if(mode==3) {
            val child=new Component {val output=out Bits(2 bits);output:=constant}
            output:=child.output
          } else output:=constant
        }
      }
    }
    assert(Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null).map(_.toString).mkString.contains(if(mode==3) "HIERARCHY VIOLATION" else "CONSTANT"),error.toString)
  }

  class Geometry extends Component {
    @param val WIDTH=4
    require(WIDTH>=2 && WIDTH<=16)
    @localparam val LAST=WIDTH-1
    @localparam val CODE=B(3,WIDTH bits)
    val echoIn=in Bool();val echoOut=out Bool();echoOut:=echoIn
    val result=out Bits(WIDTH bits);result:=CODE
    val endpoint=out UInt(32 bits);endpoint:=U(LAST,32 bits)
    val left=new Area { @localparam val code=B(1,2 bits) }
    val right=new Area { @rtlParamName("RIGHT_VALUE") @localparam val code=B(2,2 bits) }
    val joined=out Bits(4 bits);joined:=left.code ## right.code
  }
  test("symbolic widths, integer locals, nested names and explicit overrides remain symbolic") {
    val dir=Files.createTempDirectory("constant-geometry-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(new Geometry)
    val rtl=new String(Files.readAllBytes(dir.resolve("Geometry.v")),UTF_8)
    assert(rtl.contains("localparam [(WIDTH)-1:0] CODE"),rtl)
    assert(rtl.contains("LEFT_CODE") && rtl.contains("RIGHT_VALUE") && rtl.contains("localparam integer LAST"),rtl)
    for(w<-Seq(2,4,16)) {
      Files.write(dir.resolve("tb.v"),s"""module tb;
wire echo; wire [$w-1:0] result; wire [31:0] endpoint;wire [3:0] joined;
Geometry #(.WIDTH($w)) dut(1'b1,echo,result,endpoint,joined);
initial begin #1; if(echo!==1 || result!==3 || endpoint!==${w-1} || joined!==6) $$fatal; $$finish; end endmodule
""".getBytes(UTF_8))
      Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","Geometry.v","tb.v"),Seq("vvp","sim")).foreach {command=>
        val result=Increment66ToolEvidence.run(dir,command);assert(result._1==0,result._2)
      }
    }
  }
  for(mode<-0 to 4) test(s"invalid width, collisions, local promotion and overlapping keys fail $mode") {
    val error=intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("bad-constant-domain-").toString)) {
        new Component {
          @param val WIDTH=4
          require(WIDTH>=1 && WIDTH<=8)
          val input=in Bits((if(mode==4)4 else 2) bits)
          val output=out Bits(2 bits)
          val CODE=Param(if(mode==0) B(3,WIDTH bits) else B(1,(if(mode==4)4 else 2) bits),local=true)
          if(mode==1) input.setName("CODE")
          if(mode==2) { val public=Param(CODE,local=false); output:=public }
          else if(mode==3 || mode==4) {
            val OTHER=Param(if(mode==4)B(WIDTH,4 bits) else B(1,2 bits),local=true)
            output:=0
            switch(input) {is(CODE){output:=1};is(OTHER){output:=2}}
          } else output:=CODE.resize(2)
        }
      }
    }
    assert(Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null).map(_.toString).mkString.contains(if(mode==0) "SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT" else "SPINAL-PARAM-CONSTANT"),error.toString)
  }

  class ForwardChild(actual:Param[Bits]) extends Component {
    require(actual>=0 && actual<=7)
    val input=in Bits(3 bits);val output=out Bits(3 bits)
    output:=input ^ B(actual,3 bits)
  }
  class ForwardParent extends Component {
    val MASK=Param(B(5,3 bits),local=false)
    val input=in Bits(3 bits);val output=out Bits(3 bits)
    val child=new ForwardChild(MASK)
    child.input:=input;output:=child.output
  }
  test("typed public values forward through existing constructor parameters") {
    val dir=Files.createTempDirectory("constant-forwarding-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(new ForwardParent)
    val rtl=new String(Files.readAllBytes(dir.resolve("ForwardParent.v")),UTF_8)
    assert(rtl.contains("MASK") && rtl.contains("ACTUAL"),rtl)
    Files.write(dir.resolve("tb.v"),"""module tb;
reg [2:0] value;wire [2:0] result;integer i;
ForwardParent #(.MASK(3)) dut(value,result);
initial begin for(i=0;i<8;i=i+1) begin value=i; #1;if(result!==(i^3)) $fatal;end $finish;end endmodule
""".getBytes(UTF_8))
    val sources=dir.toFile.listFiles.filter(f=>f.getName.endsWith(".v") && f.getName!="tb.v").map(_.getName).toSeq.sorted
    Seq(Seq("iverilog","-g2001","-s","tb","-o","sim")++sources++Seq("tb.v"),Seq("vvp","sim")).foreach {command=>
      val result=Increment66ToolEvidence.run(dir,command);assert(result._1==0,result._2)
    }
  }

  class NamedDecoder extends Component {
    @localparam val OKAY=B(0,2 bits)
    @localparam val SLVERR=B(2,2 bits)
    @localparam val DECERR=B(3,2 bits)
    val selector=in Bits(2 bits)
    val result=out UInt(2 bits)
    result:=3
    switch(selector) {is(OKAY){result:=0};is(SLVERR){result:=1};is(DECERR){result:=2}}
  }
  test("typed local keys retain ordered case semantics including X and Z") {
    val dir=Files.createTempDirectory("constant-switch-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(new NamedDecoder)
    val rtl=new String(Files.readAllBytes(dir.resolve("NamedDecoder.v")),UTF_8)
    assert(rtl.contains("OKAY : begin") && rtl.contains("SLVERR : begin"),rtl)
    Files.write(dir.resolve("tb.v"),"""module tb;
reg [1:0] selector;wire [1:0] result;integer i;
NamedDecoder dut(selector,result);
initial begin for(i=0;i<4;i=i+1) begin selector=i; #1;
if(result!==((i==0)?2'd0:(i==2)?2'd1:(i==3)?2'd2:2'd3)) $fatal;end
selector=2'bxx; #1; if(result!==3) $fatal; selector=2'bzz; #1; if(result!==3) $fatal;
$finish;end endmodule
""".getBytes(UTF_8))
    Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","NamedDecoder.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","NamedDecoder","NamedDecoder.v")).foreach {command=>
      val result=Increment66ToolEvidence.run(dir,command);assert(result._1==0,result._2)
    }
  }

  class ModularConstants extends Component {
    @localparam val RESULT = (U(15,4 bits) + U(1,4 bits)) + U(1,8 bits)
    @localparam val NEGATIVE_SUM = S(-3,4 bits) + S(1,8 bits)
    val signed_out=out SInt(8 bits); signed_out:=NEGATIVE_SUM
    val data_out=out UInt(8 bits)
    val data_in=in UInt(8 bits)
    data_out:=RESULT ^ data_in
  }
  test("constant arithmetic retains each intermediate modular width") {
    val dir=Files.createTempDirectory("constant-modular-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(new ModularConstants)
    Files.write(dir.resolve("tb.v"),"""module tb;
wire [7:0] result; wire signed [7:0] signed_result;
ModularConstants dut(.data_in(8'd0),.data_out(result),.signed_out(signed_result));
initial begin #1;if(result!==8'd1 || signed_result!==-8'sd2) $fatal; $finish;end endmodule
""".getBytes(UTF_8))
    Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","ModularConstants.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","ModularConstants","ModularConstants.v")).foreach {command=>
      val result=Increment66ToolEvidence.run(dir,command);assert(result._1==0,result._2)
    }
  }

}
