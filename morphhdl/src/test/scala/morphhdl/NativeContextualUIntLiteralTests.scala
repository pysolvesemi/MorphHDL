package morphhdl

import java.nio.file.Files
import java.nio.charset.StandardCharsets.UTF_8
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class NativeContextualUIntLiteralTests extends AnyFunSuite {
  test("unsized UInt literals retain value requirements across symbolic arithmetic and comparisons") {
    val dir=Files.createTempDirectory("native-contextual-uint-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        setDefinitionName("Top")
        @dontName val width=morphhdl.frontend.HdlInt.param("WIDTH",8,1,16).asElabInt
        val x,z=in UInt(width bits)
        val add,sub,band,bor,bxor,left,average,one=out UInt(width bits)
        val eq,ne,lt,le,odd=out Bool()
        one := 1
        add := x + 1; sub := x - 1
        band := x & U(1); bor := x | U(1); bxor := x ^ U(1)
        left := U(1) + x
        eq := x === U(1); ne := x =/= U(1); lt := x < U(1); le := x <= U(0)
        val rounded=UInt((width+1) bits).dontSimplifyIt()
        rounded := x.resize(width+1) + z.resize(width+1) + 1
        average := (rounded >> 1).resize(width)
        odd := rounded(0)
      }
    }
    for(width <- Seq(1,2,4,8,16)) {
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg [$width-1:0] x,z,expectedAdd,expectedSub; reg [$width:0] sum;
wire [$width-1:0] add,sub,band,bor,bxor,left,average,one; wire eq,ne,lt,le,odd; integer n;
Top #(.WIDTH($width)) dut(.x(x),.z(z),.add(add),.sub(sub),.band(band),.bor(bor),.bxor(bxor),
 .left(left),.average(average),.one(one),.eq(eq),.ne(ne),.lt(lt),.le(le),.odd(odd));
initial begin for(n=0;n<1024;n=n+1) begin
 x=(n<4)?n:$$random; z=(n<4)?~x:$$random;
 expectedAdd=x+1'b1; expectedSub=x-1'b1; sum={1'b0,x}+{1'b0,z}+1'b1; #1;
 if(one!==$width'd1 || add!==expectedAdd || left!==expectedAdd || sub!==expectedSub || band!==(x & $width'd1) ||
 bor!==(x | $width'd1) || bxor!==(x ^ $width'd1) || eq!==(x==1) || ne!==(x!=1) ||
 lt!==(x<1) || le!==(x<=0) || average!==sum[$width:1] || odd!==sum[0]) $$fatal;
end $$display("CONTEXTUAL_LITERAL_PASS"); $$finish; end
endmodule
""".getBytes(UTF_8))
      Seq(
        Seq("iverilog","-g2001","-s","tb","-o","sim","Top.v","tb.v"),
        Seq("timeout","10","vvp","sim"),
        Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top",s"-GWIDTH=$width","Top.v"),
        Seq("yosys","-Q","-p",s"read_verilog Top.v; chparam -set WIDTH $width Top; synth -top Top; check -assert")
      ).foreach { command =>
        val (code,log)=Increment66ToolEvidence.run(dir,command)
        assert(code==0,s"$command failed in $dir\n$log")
      }
    }
  }
  for(explicit <- Seq(false,true)) test(s"literal width requirements are not discarded explicit=$explicit") {
    val dir=Files.createTempDirectory("native-contextual-invalid-")
    val error=intercept[MorphVerilogException] {
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
        new Component {
          @dontName val width=morphhdl.frontend.HdlInt.param("WIDTH",8,1,16).asElabInt
          val x=in UInt(width bits); val y=out UInt(width bits)
          y := x + (if(explicit) U(1,8 bits) else U(3))
        }
      }
    }
    assert(error.getMessage.contains("SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"),error.getMessage)
  }
}
