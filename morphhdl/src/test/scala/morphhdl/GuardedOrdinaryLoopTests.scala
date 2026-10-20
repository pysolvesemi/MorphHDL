package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class GuardedOrdinaryLoopTests extends AnyFunSuite {
  for (preserve <- Seq(false, true); layout <- Seq(MorphAggregateOptions.PackedVector, MorphAggregateOptions.UnpackedArray); bound <- Seq("zero", "nonzero", "length"))
    test(s"mixed guarded ordinary writers preserve priority preserve=$preserve layout=$layout bound=$bound") {
      val dir = Files.createTempDirectory("guarded-ordinary-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString, headerWithDate=false),
        preserveConstantVecs=true, preserveConstantLoops=preserve, vecLayout=layout)) {
        new Component {
          setDefinitionName("GuardedOrdinary")
          val operational, bad, good, ready = in Bool()
          val space = in Bits(4 bits)
          val y = out Bits(4 bits)
          val Stages = 4
          val valid = Vec.fill(Stages)(Reg(Bool()) init(False))
          when(!operational) {
            if(bound == "nonzero") {
              valid(0) := False
              for(i <- 1 until Stages) valid(i) := False
            } else if(bound == "length") {
              for(i <- 0 until valid.length) valid(i) := False
            } else {
              for(i <- 0 until Stages) valid(i) := False
            }
          } otherwise {
            when(space(0)) { valid(0) := good }
            when(space(1)) { valid(1) := valid(0) }
            when(space(2)) { valid(2) := valid(1) }
            when(space(3)) { valid(3) := valid(2) }
            when(bad) {
              for(i <- 0 until Stages - 1) valid(i) := False
              valid(Stages - 1) := valid(Stages - 1) && !ready
            }
          }
          y := valid.asBits
        }
      }
      val tb = """module tb;
reg clk=0,reset=1,operational=0,bad=0,good=0,ready=0;
reg [3:0]space=0;wire[3:0]y;reg[3:0]expected=0,next;integer n,k;
GuardedOrdinary d(.clk(clk),.reset(reset),.operational(operational),.bad(bad),.good(good),.ready(ready),.space(space),.y(y));
initial begin #1;clk=1;#1;clk=0;reset=0;
for(n=0;n<4096;n=n+1)begin
{operational,bad,good,ready,space}=$random;
next=expected;
if(!operational)next=0;else begin
if(space[0])next[0]=good;
for(k=1;k<4;k=k+1)if(space[k])next[k]=expected[k-1];
if(bad)begin next[2:0]=0;next[3]=expected[3]&&!ready;end
end
#1;clk=1;expected=next;#1;if(y!==expected)$fatal(1,"priority %d got %h expected %h",n,y,expected);clk=0;
if(n%31==0)begin reset=1;#1;expected=0;if(y!==0)$fatal;reset=0;end
end
$finish;end endmodule"""
      Files.write(dir.resolve("tb.v"), tb.getBytes("UTF-8"))
      for(cmd <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","GuardedOrdinary.v","tb.v"),
        Seq("vvp","sim"),Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","GuardedOrdinary.v"),
        Seq("yosys","-Q","-p","read_verilog GuardedOrdinary.v; hierarchy -check -top GuardedOrdinary; proc; opt; check -assert"))) {
        val(code,log)=Increment66ToolEvidence.run(dir,cmd);assert(code==0,log)
      }
    }

  test("guarded expansion retains complete assignment conflict diagnostics") {
    val dir=Files.createTempDirectory("guarded-conflict-")
    val error=intercept[Throwable] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString),preserveConstantLoops=true)) {
        new Component {
          val en=in Bool();val y=out Bits(4 bits)
          val lanes=Vec.fill(4)(Reg(Bool()) init(False))
          when(en){
            for(i<-0 until 4)lanes(i):=False
            for(i<-0 until 4)lanes(i):=True
          }
          y:=lanes.asBits
        }
      }
    }
    assert(error.toString.contains("ASSIGNMENT OVERLAP"),error.toString)
  }

  test("guarded expansion does not hide an undriven combinational lane") {
    val dir=Files.createTempDirectory("guarded-latch-")
    val error=intercept[Throwable] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString),preserveConstantLoops=true)) {
        new Component {
          val en=in Bool();val y=out Bits(4 bits)
          val lanes=Vec.fill(4)(Bool())
          when(en){for(i<-0 until 3)lanes(i):=False}
          y:=lanes.asBits
        }
      }
    }
    assert(error.toString.contains("LATCH") || error.toString.contains("NO DRIVER"),error.toString)
  }
}
