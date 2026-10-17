package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.HdlInt

class NativeValueSizingTests extends AnyFunSuite {
  private class Top extends Component {
    @dontName private val width = HdlInt.param("WIDTH",8,8,64).asElabInt
    @dontName private val value = HdlInt.param("VALUE",9,0,90).asElabInt
    val dataIn = in UInt(width bits)
    val result, localResult = out UInt(width bits)
    val rawOffset, namedOffset = out UInt(7 bits)
    val scalar = ElabValue.uintLike(value, UInt(width bits), "scalar")
    // Keep the anonymous calculation and a separately named calculation alive.
    val raw = ElabValue.uintLike(value+37, UInt(7 bits), "raw")
    val namedCalculation: ElabInt = value+36
    val named = ElabValue.uintLike(namedCalculation, UInt(7 bits), "named")
    val local = TypedLocalUInt("SMALL_VALUE",value+1,7 bits)
    val extended = ElabValue.uintLike(local.elab,UInt(width bits),"extended")
    result := dataIn ^ scalar
    localResult := dataIn ^ extended
    rawOffset := dataIn(6 downto 0) ^ raw
    namedOffset := dataIn(6 downto 0) ^ named
  }
  test("native values explicitly size direct, anonymous and named constants across 32 bits") {
    val dir=Files.createTempDirectory("native-value-sizing-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(new Top)
    for(width <- Seq(8,31,32,33,64); value <- Seq(0,9,90)) {
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg [$width-1:0] dataIn; wire [$width-1:0] result,localResult;
wire [6:0] rawOffset,namedOffset; integer i;
Top #(.WIDTH($width),.VALUE($value)) dut(.dataIn(dataIn),.result(result),.localResult(localResult),
.rawOffset(rawOffset),.namedOffset(namedOffset));
initial begin for(i=0;i<256;i=i+1) begin
 dataIn={32'ha539c706,32'h6ac0359e} ^ i; #1;
 if(result!==(dataIn ^ $width'd$value) || localResult!==(dataIn ^ $width'd${value+1}) ||
 rawOffset!==(dataIn[6:0] ^ 7'd${value+37}) || namedOffset!==(dataIn[6:0] ^ 7'd${value+36})) $$fatal;
end $$finish;end
endmodule
""".getBytes(UTF_8))
      Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","Top.v","tb.v"),
        Seq("timeout","10","vvp","sim"),
        Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS",
          "--top-module","Top",s"-GWIDTH=$width",s"-GVALUE=$value","Top.v"),
        Seq("yosys","-Q","-p",s"read_verilog Top.v; chparam -set WIDTH $width -set VALUE $value Top; synth -top Top; check -assert")
      ).foreach { command =>
        val (code,log)=Increment66ToolEvidence.run(dir,command)
        assert(code==0,s"$command failed in $dir\n$log")
      }
    }
  }
}
