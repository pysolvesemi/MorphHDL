package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.HdlInt

class NativeHelperNamingTests extends AnyFunSuite {
  test("neutral value helpers preserve collisions repeated values narrowing and widening") {
    val dir=Files.createTempDirectory("neutral-value-helpers-")
    def generate(at: java.nio.file.Path): Unit = MorphVerilog(SpinalConfig(targetDirectory=at.toString,headerWithDate=false))(new Component {
      setDefinitionName("Top")
      @dontName private val count=HdlInt.param("COUNT",3,2,8).asElabInt
      val param_value=in UInt(4 bits)
      val mixed,repeated=out UInt(4 bits)
      val wide=out UInt(64 bits)
      mixed:=param_value ^ ElabValue.uintLike(count-1,UInt(4 bits), "")
      repeated:=ElabValue.uintLike(count-1,UInt(4 bits), "")
      wide:=ElabValue.uintLike(count-1,UInt(64 bits), "")
    })
    generate(dir)
    val repeat=Files.createTempDirectory("neutral-value-repeat-");generate(repeat)
    val rtl=new String(Files.readAllBytes(dir.resolve("Top.v")),UTF_8)
    assert(java.util.Arrays.equals(Files.readAllBytes(dir.resolve("Top.v")),Files.readAllBytes(repeat.resolve("Top.v"))))
    val code=rtl.replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "")
    assert("\\bmorphhdl[A-Za-z0-9_$]*\\b".r.findFirstIn(code).isEmpty,rtl)
    assert(rtl.contains("param_value_"),rtl)
    val instances=(2 to 8).map(n=>s"wire done$n;profile #(.N($n)) p$n(done$n);").mkString("\n")
    val all=(2 to 8).map(n=>s"done$n").mkString(" && ")
    Files.write(dir.resolve("tb.v"),s"""module profile #(parameter N=3)(output reg done=0);
reg[3:0] x;wire[3:0] mixed,repeated;wire[63:0] wide;integer k;
Top #(.COUNT(N)) dut(.param_value(x),.mixed(mixed),.repeated(repeated),.wide(wide));
initial begin for(k=0;k<16;k=k+1) begin x=k;#1;
if(mixed!==(x^(N-1)) || repeated!==N-1 || wide!==N-1) $$fatal(1,"neutral helper values");
end done=1;end endmodule
module tb;$instances initial begin wait($all);$$finish;end endmodule
""".getBytes(UTF_8))
    def run(args: Seq[String]):Unit={val(code,log)=Increment66ToolEvidence.run(dir,args);assert(code==0,s"$args in $dir\n$log")}
    run(Seq("iverilog","-g2001","-s","tb","-o","sim","Top.v","tb.v"))
    run(Seq("timeout","15","vvp","sim"))
    for(n <- Seq(2,3,8)) {
      run(Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top",s"-GCOUNT=$n","Top.v"))
      run(Seq("yosys","-Q","-p",s"read_verilog Top.v; chparam -set COUNT $n Top; synth -top Top; check -assert"))
    }
  }
}
