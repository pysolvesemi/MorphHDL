package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.HdlInt

class EnumDebugOptionsTests extends AnyFunSuite {
  private class Top extends Component {
    setDefinitionName("Top")
    @dontName private val width = HdlInt.param("WIDTH",8,1,16).asElabInt
    val data = in Bits(width bits)
    val result = out Bits(width bits)
    val go, log = in Bool()
    val active = out Bool()
    object State extends SpinalEnum { val idle, busy = newElement() }
    val state = Reg(State()) init(State.idle)
    when(go) { state := State.busy } otherwise { state := State.idle }
    active := state === State.busy
    result := data
    when(log) { result := ~data }
    when(log) { report(L"STATE=${state}") }
  }
  private def run(dir:Path, command:Seq[String]):String = {
    val (code,output)=Increment66ToolEvidence.run(dir,command)
    assert(code==0,command.mkString(" ")+"\n"+output)
    output
  }
  for (selection <- Seq("default","suppress","strings","native-disabled"); split <- Seq(false,true)) {
    test(s"enum debug selection=$selection split=$split retains hardware and reports") {
      val dir=Files.createTempDirectory("enum-debug-options-")
      val original=SpinalConfig(targetDirectory=dir.toString,oneFilePerComponent=split,headerWithDate=false)
      val config=selection match {
        case "suppress" => MorphDebugOptions(original)
        case "strings" => MorphDebugOptions(original,suppressEnumDebugStrings=false)
        case "native-disabled" => MorphDebugOptions(original.copy().withoutEnumString(),suppressEnumDebugStrings=false)
        case _ => original
      }
      MorphVerilog(config)(new Top)
      val rtl=new String(Files.readAllBytes(dir.resolve("Top.v")),UTF_8)
      assert(rtl.contains("_string") == (selection=="strings"),rtl)
      assert(rtl.contains("STATE=%s") == (selection=="strings"),rtl)
      assert(rtl.contains("STATE=%x") == (selection!="strings"),rtl)
      assert(original.flags.isEmpty)
      // Reuse the untouched caller configuration through native generation.
      val nativeDir=Files.createTempDirectory("enum-debug-native-")
      SpinalVerilog(original.copy(targetDirectory=nativeDir.toString))(new Top)
      assert(new String(Files.readAllBytes(nativeDir.resolve("Top.v")),UTF_8).contains("_string"))
      Files.write(dir.resolve("tb.v"),"""module tb;
reg clk=0,reset=1,go=0,log=0;reg[7:0]data=8'h5a;wire[7:0]result;wire active;
Top dut(.clk(clk),.reset(reset),.go(go),.log(log),.data(data),.result(result),.active(active));
always #5 clk=~clk;
initial begin #2;reset=0;#1;reset=1;#8;reset=0;
#1;if(active!==0 || result!==8'h5a) $fatal(1,"reset/data");
go=1;#9;#1;if(active!==1) $fatal(1,"busy");log=1;
#10;go=0;#10;if(active!==0) $fatal(1,"idle");$display("PASS");$finish;end
endmodule
""".getBytes(UTF_8))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","Top.v","tb.v"))
      val output=run(dir,Seq("vvp","sim"));assert(output.contains("PASS") && output.contains("STATE="),output)
      if(selection=="strings") assert(output.contains("busy"),output)
      run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top","Top.v"))
      run(dir,Seq("yosys","-Q","-p","read_verilog Top.v; synth -top Top; check -assert"))
    }
  }
}
