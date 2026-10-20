package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

/** Ordinary (nonrecursive) children inherit the single current domain. */
class OrdinaryChildClockGeometryTests extends AnyFunSuite {
  private class Child(actual: ElabInt) extends Component {
    setDefinitionName("OrdinaryClockChild")
    val WIDTH: Param[Int] = Param(actual)
    require(WIDTH >= 1 && WIDTH <= 64)
    val io = new Bundle {
      val source = in UInt(WIDTH bits)
      val result = out UInt(WIDTH bits)
    }
    io.result := RegNext(io.source) init(0)
  }
  for(operation<-Seq("cast","sum","choice")) test(s"ordinary child wiring preserves symbolic expressions and clock reset enable priority operation=$operation") {
    val dir=Files.createTempDirectory("ordinary-child-clock-")
    def generate(path:java.nio.file.Path):Unit = MorphVerilog(MorphAggregateOptions(
      SpinalConfig(targetDirectory=path.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("OrdinaryClockTop")
        val WIDTH:Param[Int]=Param(8)
        require(WIDTH>=1 && WIDTH<=64)
        val clk=in Bool();val reset=in Bool();val softReset=in Bool();val enable=in Bool()
        val data=in Bits(WIDTH bits);val choose=in Bool();val result=out Bits(WIDTH bits)
        val logic=new ClockingArea(ClockDomain(clk,reset=reset,softReset=softReset,clockEnable=enable)) {
          val child=new Child(WIDTH)
          operation match {
            case "cast"=>child.io.source:=data(0,WIDTH bits).asUInt
            case "sum"=>child.io.source:=data.asUInt+data.asUInt
            case "choice"=>child.io.source:=Mux(choose,data.asUInt,~data.asUInt)
          }
          result:=child.io.result.asBits
        }
      }
    }
    generate(dir)
    val repeat=Files.createTempDirectory("ordinary-child-clock-repeat-");generate(repeat)
    val rtl=new String(Files.readAllBytes(dir.resolve("OrdinaryClockTop.v")),"UTF-8")
    assert(rtl==new String(Files.readAllBytes(repeat.resolve("OrdinaryClockTop.v")),"UTF-8"))
    val instances=Seq(1,5,8,64).map(w=>s"Check #(.W($w)) c$w(clk,reset,softReset,enable,choose,data);").mkString("\n")
    val expression=operation match {
      case "cast"=>"data[W-1:0]"
      case "sum"=>"data[W-1:0]+data[W-1:0]"
      case "choice"=>"(choose ? data[W-1:0] : ~data[W-1:0])"
    }
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter W=8)(input clk,reset,softReset,enable,choose,input [63:0] data);
wire [W-1:0] result;reg [W-1:0] expected;
OrdinaryClockTop #(.WIDTH(W)) dut(.clk(clk),.reset(reset),.softReset(softReset),.enable(enable),.choose(choose),.data(data[W-1:0]),.result(result));
always @(posedge clk or posedge reset)if(reset)expected<=0;else if(enable)begin if(softReset)expected<=0;else expected<=$expression;end
always @(negedge clk)if(result!==expected)$$fatal(1,"ordinary child W=%d",W);
endmodule
module tb;reg clk=0,reset=0,softReset=0,enable=1,choose=0;reg [63:0] data=0;integer n;
always #5 clk=~clk;
$instances
initial begin #1;reset=1;#11;reset=0;
for(n=0;n<100;n=n+1)begin @(negedge clk);#1;data={$$random,$$random};enable=n%3!=0;softReset=n%7==0;reset=n==44;choose=n%2==0;if(n==47)choose=1'bx;if(n==50)choose=1'bz;end
@(negedge clk);#1;data='bx;enable=1;softReset=0;#20;data='bz;#20;$$finish;end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","OrdinaryClockTop.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","OrdinaryClockTop.v"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,command);assert(code==0,log)
    }
  }
  test("whole-width typed slices reject partial offsets and independent equal-witness geometry") {
    for(mode<-Seq("offset","partial","independent")) {
      val error=intercept[Exception] {
        MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("typed-slice-negative-").toString)) {
          new Component {
            val WIDTH:Param[Int]=Param(8);require(WIDTH>=2 && WIDTH<=64)
            val OTHER:Param[Int]=Param(8);require(OTHER>=2 && OTHER<=64)
            val data=in Bits(WIDTH bits)
            val width=if(mode=="independent")OTHER else if(mode=="partial") WIDTH-1 else WIDTH
            val result=out Bits(width bits)
            result:=data(if(mode=="offset")1 else 0,width bits)
          }
        }
      }
      assert(error.toString.contains("SPINAL-TYPED-SLICE-GEOMETRY-UNPROVEN"),error.toString)
    }
  }

  test("direct arithmetic cannot borrow equal default widths from independent actuals") {
    val error=intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("arithmetic-geometry-negative-").toString)) {
        new Component {
          val WIDTH:Param[Int]=Param(8);require(WIDTH>=2 && WIDTH<=64)
          val OTHER:Param[Int]=Param(8);require(OTHER>=2 && OTHER<=64)
          val a=in UInt(WIDTH bits);val b=in UInt(OTHER bits)
          val result=out UInt(WIDTH bits)
          val child=new Child(WIDTH)
          child.io.source:=a+b
          result:=child.io.result
        }
      }
    }
    assert(error.toString.contains("SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-CONNECTION-CONFLICT"),error.toString)
  }

}
