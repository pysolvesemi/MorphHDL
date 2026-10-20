package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.HdlInt

class BranchWiringSafetyTests extends AnyFunSuite {
  private class Child(actual: ElabInt, mode: String) extends Component {
    val width: Param[Int] = Param(actual,"WIDTH")
    require(width >= 1 && width <= 64)
    val x = in UInt(width bits)
    val y = out UInt(width bits)
    if(mode=="keep") y.dontSimplifyIt().addAttribute("keep")
    if(mode=="doc") y.doc("Retain child result boundary")
    y := ~x
  }
  private def generate(mode: String, line: Int) = {
    val dir=Files.createTempDirectory("branch-wiring-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray))(new Component {
      setDefinitionName("BranchTop")
      val width=HdlInt.param("WIDTH",8,1,64).asElabInt
      val count=HdlInt.param("COUNT",2,1,3).asElabInt
      val x=in UInt(width bits)
      val y=out UInt(width bits)
      val flag=in Bool()
      val g_GEN_1_TRUE=in Bool()
      val observed=out Bool(); observed:=g_GEN_1_TRUE
      ElabControl.selectSymbolic(count.elabEq(1),"Shifted.scala",line) {
        y:=x
      } {
        val child=new Child(width,mode)
        child.x:=x
        if(mode=="priority") {
          y:=child.y
          when(flag) { y:=x }
        } else if(mode=="signed") y:=child.y.asSInt.asUInt
        else y:=child.y
      }
      val optional=out UInt(width bits)
      ElabControl.selectSymbolic(count.elabEq(1),"Shifted.scala",line+5) {
        optional:=x
      } {
        ElabControl.selectSymbolic(count.elabEq(2),"Shifted.scala",line+10) {
          optional:= ~x
        } { optional:=x }
      }
    })
    dir -> new String(Files.readAllBytes(dir.resolve("BranchTop.v")),"UTF-8")
  }
  for(mode<-Seq("plain","keep","doc","priority","signed")) test(s"branch forwarding respects boundary and process semantics $mode") {
    val(dir,rtl)=generate(mode,10)
    assert(rtl==generate(mode,1000)._2,rtl)
    assert(!rtl.contains("g_if_") && !rtl.contains("g_GEN_1_FALSE"),rtl)
    assert(rtl.contains("g_GEN_2_TRUE") && rtl.contains("g_GEN_2_FALSE"),rtl)
    if(mode=="keep" || mode=="doc" || mode=="priority") assert("child(?:_[0-9]+)?_y".r.findFirstIn(rtl).nonEmpty,rtl)
    if(mode=="doc") assert(rtl.contains("Retain child result boundary"),rtl)
    if(mode=="priority") assert(rtl.contains("always @"),rtl)
    val checks=(for(n<-1 to 3; w<-Seq(1,5,64)) yield s"Check #(.N($n),.W($w)) c_${n}_$w(x,flag);").mkString("\n")
    val expected=if(mode=="priority") "((N==1 || flag) ? x : ~x)" else "((N==1) ? x : ~x)"
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter N=1,W=8)(input [63:0] data,input flag);
wire[W-1:0] x=data[W-1:0]; wire[W-1:0] y,optional; wire observed;
BranchTop #(.WIDTH(W),.COUNT(N)) dut(.x(x),.y(y),.flag(flag),.g_GEN_1_TRUE(1'b1),.observed(observed),.optional(optional));
always @(data or flag) begin #1; if(y !== $expected || optional !== ((N==2) ? ~x : x) || observed!==1'b1) $$fatal; end
endmodule
module tb; reg[63:0]x=0;reg flag=0;integer i;
$checks
initial begin for(i=0;i<50;i=i+1) begin x={$$random,$$random};flag=i%2;#3;end
x='bx;#3;x='bz;#3;$$finish;end endmodule
""".getBytes("UTF-8"))
    for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","BranchTop.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","BranchTop.v"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,cmd);assert(code==0,s"$dir\n$log")
    }
  }
}
