package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

/** LWR-22 source-level regression: no typed-bound alias or Area collection. */
class OrdinaryStatementLoopTests extends AnyFunSuite {
  case class Config(bytesPerBeat: Int)
  private def generate(p: Config, preserve: Boolean) = {
    val directory = Files.createTempDirectory("ordinary-statement-loop-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=directory.toString, headerWithDate=false),
      preserveConstantLoops=preserve, preserveConstantVecs=true)) {
      new Component {
        setDefinitionName(if(preserve) "KeepLoop" else "UnrolledKeepLoop")
        val outEol = in Bool()
        val TAIL_BITS: Param[Int] = Param(8)
        require(TAIL_BITS >= 8 && TAIL_BITS <= 8)
        val tail = in UInt(TAIL_BITS bits)
        val outKeep = out Bits(p.bytesPerBeat bits)
        outKeep := B((BigInt(1) << p.bytesPerBeat)-1, p.bytesPerBeat bits)
        when(outEol && (tail =/= 0)) {
          for(lane <- 0 until p.bytesPerBeat) outKeep(lane) := tail > lane
        }
      }
    }
    directory
  }
  for(width <- Seq(1, 2, 4, 8, 16, 32)) test(s"ordinary conditional bit loop preserves behavior width=$width") {
    val directory=generate(Config(width), true)
    val control=generate(Config(width), false)
    val rtl=new String(Files.readAllBytes(directory.resolve("KeepLoop.v")), "UTF-8")
    assert(rtl.contains("for ("), rtl)
    val repeated=generate(Config(width), true)
    assert(rtl == new String(Files.readAllBytes(repeated.resolve("KeepLoop.v")), "UTF-8"))
    Files.copy(control.resolve("UnrolledKeepLoop.v"), directory.resolve("UnrolledKeepLoop.v"))
    Files.write(directory.resolve("tb.v"), s"""module tb;
reg outEol;reg [7:0] tail;wire [$width-1:0] kept,unrolled;
reg [$width-1:0] expected;integer t,e,i;
KeepLoop dut(outEol,tail,kept);UnrolledKeepLoop reference(outEol,tail,unrolled);
task check;begin
expected=~0;if(outEol && tail!=0)for(i=0;i<$width;i=i+1)expected[i]=(tail>i);
#1;if(kept!==unrolled || kept!==expected) $$fatal(1,"keep mismatch tail=%d eol=%b",tail,outEol);
end endtask
initial begin
for(t=0;t<256;t=t+1)for(e=0;e<2;e=e+1)begin tail=t;outEol=e;check;end
outEol=1'bx;check;outEol=1'bz;check;outEol=1;tail=8'bx;check;tail=8'bz;check;
$$finish;end endmodule
""".getBytes("UTF-8"))
    def run(args: String*): Unit = {
      val result=Increment66ToolEvidence.run(directory,args)
      assert(result._1==0,args.mkString(" ")+"\n"+result._2)
    }
    run("iverilog","-g2001","-s","tb","-o","sim","KeepLoop.v","UnrolledKeepLoop.v","tb.v")
    run("vvp","sim")
    run("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","KeepLoop","KeepLoop.v")
    run("yosys","-Q","-p","read_verilog -DSYNTHESIS KeepLoop.v; synth -top KeepLoop; check -assert; select -assert-none t:$dlatch")
  }
}
