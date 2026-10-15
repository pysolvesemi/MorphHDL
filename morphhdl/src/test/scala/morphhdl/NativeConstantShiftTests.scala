package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class NativeConstantShiftTests extends AnyFunSuite {
  test("symbolic shifts retain overrides, compound temporaries and signed comparisons") {
    val dir = Files.createTempDirectory("native-symbolic-shift-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        setDefinitionName("Top")
        @dontName val width = morphhdl.frontend.HdlInt.param("WIDTH",12,8,20).asElabInt
        val u = in UInt(width bits)
        val s = in SInt(width bits)
        val result = out UInt((width-2) bits)
        val negative = out Bool()
        val low, lowSum = out Bits(2 bits)
        val one = ElabValue.uintLike(ElabInt.literal(1), UInt(width bits), "one")
        @dontName val rounded = u + one
        result := rounded >> 2
        lowSum := rounded(1 downto 0).asBits
        negative := (s >> 2) < 0
        low := s(1 downto 0).asBits
      }
    }
    for (width <- Seq(8,12,20)) {
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg [$width-1:0] u,s,rounded; wire [${width-2}-1:0] result; wire negative; wire [1:0] low,lowSum;
Top #(.WIDTH($width)) dut(.u(u),.s(s),.result(result),.negative(negative),.low(low),.lowSum(lowSum)); integer n;
initial begin for(n=0;n<512;n=n+1) begin
u=n ^ $width'hadcd;s=~u; rounded=u+1'b1; #1;
if(result!==rounded[$width-1:2] || negative!==s[$width-1] || low!==s[1:0] || lowSum!==rounded[1:0]) $$fatal;
end $$finish;end
endmodule
""".getBytes(UTF_8))
      Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","Top.v","tb.v"),
        Seq("timeout","10","vvp","sim"),
        Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS", "--top-module","Top",s"-GWIDTH=$width","Top.v"),
        Seq("yosys","-Q","-p",s"read_verilog Top.v; chparam -set WIDTH $width Top; synth -top Top; check -assert")
      ).foreach { command =>
        val (code,log)=Increment66ToolEvidence.run(dir,command)
        assert(code==0,s"$command failed in $dir\n$log")
      }
    }
  }
  for (width <- Seq(8, 17, 71); shift <- Seq(1, 2)) {
    test(s"shrinking native shifts publish explicit slices width=$width shift=$shift") {
      val dir = Files.createTempDirectory("native-constant-shift-")
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
        new Component {
          setDefinitionName("Top")
          @dontName val w = morphhdl.frontend.HdlInt.param("WIDTH",width,width,width).asElabInt
          val u = in UInt(w bits)
          val s = in SInt(w bits)
          val b = in Bits(w bits)
          val ur, castResult = out UInt((w-shift) bits)
          val sr = out SInt((w-shift) bits)
          val br = out Bits((w-shift) bits)
          val low = out Bits((shift*3) bits)
          ur := u >> shift
          sr := s >> shift
          br := b >> shift
          castResult := b.asUInt >> shift
          low := u(shift-1 downto 0).asBits ## s(shift-1 downto 0).asBits ## b(shift-1 downto 0)
        }
      }
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg [$width-1:0] u,s,b; wire [${width-shift}-1:0] ur,sr,br,castResult; wire [${shift*3}-1:0] low;
Top dut(.u(u),.s(s),.b(b),.ur(ur),.sr(sr),.br(br),.castResult(castResult),.low(low)); integer n;
initial begin for(n=0;n<256;n=n+1) begin
u={32'ha537ab09,32'h23ed1908,32'h9acd0197} ^ n;
s=~u;b=u^($width'b1<<($width-1)); #1;
if(ur!==u[$width-1:$shift] || sr!==s[$width-1:$shift] || br!==b[$width-1:$shift] || castResult!==b[$width-1:$shift] ||
low!=={u[$shift-1:0],s[$shift-1:0],b[$shift-1:0]}) $$fatal;
end $$finish;end
endmodule
""".getBytes(UTF_8))
      Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","Top.v","tb.v"),
        Seq("timeout","10","vvp","sim"),
        Seq("verilator","--lint-only","-Wall","--language","1364-2001","--top-module","Top","Top.v"),
        Seq("yosys","-Q","-p","read_verilog Top.v; synth -top Top; check -assert")
      ).foreach { command =>
        val (code,log)=Increment66ToolEvidence.run(dir,command)
        assert(code==0,s"$command failed in $dir\n$log")
      }
    }
  }
}
