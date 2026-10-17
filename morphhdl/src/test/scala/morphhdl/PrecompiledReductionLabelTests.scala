package morphhdl

import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.BalancedTree
import morphhdl.frontend.{HdlInt, StructuralGenerateIfOps}

/** The library class is built in lib, independently of this consuming suite. */
class PrecompiledReductionLabelTests extends AnyFunSuite {
  private def generate(kind: String, count: Int, split: Boolean): Path = {
    assert(classOf[BalancedTree[UInt]].getProtectionDomain.getCodeSource.getLocation !=
      getClass.getProtectionDomain.getCodeSource.getLocation)
    val dir = Files.createTempDirectory("precompiled-reduction-labels-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,
      headerWithDate=false, oneFilePerComponent=split), preserveConstantVecs=true,
      preserveConstantLoops=true, vecLayout=MorphAggregateOptions.UnpackedArray)) {
      if (kind == "sum") {
        BalancedTree.defineWidth((w: ElabInt) => UInt(w bits),36,16,"SumReduction") { (a: UInt,b: UInt) =>
          a+b
        }.component(ElabInt.literal(count))
      } else BalancedTree.defineWidth((w: ElabInt) => Bits(w bits),64,16,"ErrorReduction") {
        (a: Bits,b: Bits) => a | b
      }.component(ElabInt.literal(count))
    }
    dir
  }
  for(kind <- Seq("sum","or"); count <- Seq(1,16); split <- Seq(false,true)) {
    test(s"separately compiled reduction keeps automatic branch ownership $kind default=$count split=$split") {
      val name=if(kind=="sum") "SumReduction" else "ErrorReduction"
      val dir=generate(kind,count,split)
      val rtl=new String(Files.readAllBytes(dir.resolve(name+".v")),"UTF-8")
      assert(rtl==new String(Files.readAllBytes(generate(kind,count,split).resolve(name+".v")),"UTF-8"))
      val labels="begin : (g_GEN_[A-Za-z0-9_]+)".r.findAllMatchIn(rtl).map(_.group(1)).toVector
      assert(labels==Vector("g_GEN_1_TRUE","g_GEN_1_FALSE","g_GEN_2","g_GEN_3_TRUE","g_GEN_3_FALSE"),rtl)
      assert(!rtl.contains("g_if_") && !rtl.contains("guard_l"),rtl)
      val w=if(kind=="sum")36 else 64
      val op=if(kind=="sum")"+" else "|"
      val checks=(1 to 16).map(n=>s"Check #(.N($n)) c$n(data);").mkString("\n")
      Files.write(dir.resolve("tb.v"),s"""module Check #(parameter N=1)(input [${16*w-1}:0] data);
wire [${w-1}:0] result; reg[${w-1}:0] expected; integer j;
$name #(.COUNT(N),.DATA_WIDTH($w)) dut(.clk(1'b0),.reset(1'b0),.softReset(1'b0),.enable(1'b1),.terms(data[N*$w-1:0]),.result(result));
always @(data) begin #1; expected=data[0+:$w];for(j=1;j<N;j=j+1)expected=expected $op data[j*$w+:$w];
if(result!==expected)$$fatal(1,"reduction");end endmodule
module tb;reg[${16*w-1}:0] data;integer i,j;
$checks
initial begin data=0;#3;data=~${16*w}'d0;#3;data='bx;#3;data='bz;#3;
for(i=0;i<80;i=i+1)begin for(j=0;j<${16*w/32};j=j+1)data[j*32+:32]=$$random;#3;end $$finish;end endmodule
""".getBytes("UTF-8"))
      for(cmd <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim",name+".v","tb.v"),
          Seq("vvp","sim"),Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal",name+".v"))) {
        val(code,log)=Increment66ToolEvidence.run(dir,cmd);assert(code==0,log)
      }
    }
  }
  test("explicit consumer labels and reserved declaration names remain distinct") {
    def named(): String = {
      val dir=Files.createTempDirectory("precompiled-label-collisions-")
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(new Component {
        setDefinitionName("NamedLabels")
        @dontName val count=HdlInt.param("COUNT",2,1,3)
        val n=count.asElabInt
        val g_GEN_2=in Bool()
        val y=out Bool(); val z=out Bool()
        count.hdlEq(1).generateIf("g_GEN_1_TRUE", "authored_alternative") {
          y:=g_GEN_2
        }.otherwise { y:= !g_GEN_2 }
        ElabControl.selectSymbolic(n.elabEq(1), "Consumer.scala", 1) { z:=g_GEN_2 } { z:= !g_GEN_2 }
      })
      new String(Files.readAllBytes(dir.resolve("NamedLabels.v")),"UTF-8")
    }
    val rtl=named()
    assert(rtl.contains("begin : g_GEN_1_TRUE") && rtl.contains("begin : authored_alternative"),rtl)
    assert(rtl.contains("g_GEN_2") && rtl.contains("begin : g_GEN_3_TRUE") && rtl.contains("begin : g_GEN_3_FALSE"),rtl)
    assert(!rtl.contains("g_GEN_1_FALSE") && !rtl.contains("g_if_") && !rtl.contains("guard_l"),rtl)
    assert(rtl==named())
  }
}
