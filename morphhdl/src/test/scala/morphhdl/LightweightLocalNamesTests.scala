package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class LightweightLocalNamesTests extends AnyFunSuite {
  test("staged local names preserve authored declarations and explicit overrides") {
    val directory = Files.createTempDirectory("lightweight-local-names-")
    MorphVerilog(SpinalConfig(targetDirectory = directory.toString, headerWithDate = false)) {
      new Component {
        setDefinitionName("LocalNames")
        val mode: ElabInt = RtlParam.int("MODE", 0, 0, 1)
        val source = in Bits(8 bits)
        val result = out Bits(8 bits)
        if (mode == 1) {
          val scratch = Bits(8 bits).dontSimplifyIt()
          scratch := ~source
          result := scratch
        } else {
          val scratch = Bits(8 bits).setName("authorChoice").dontSimplifyIt()
          scratch.addAttribute("keep")
          scratch := source
          result := scratch
        }
      }
    }
    val rtl = new String(Files.readAllBytes(directory.resolve("LocalNames.v")), "UTF-8")
    assert(rtl.contains("scratch") && rtl.contains("authorChoice") && rtl.contains("keep"), rtl)
    Files.write(directory.resolve("tb.v"), """module tb;
reg [7:0] source; wire [7:0] a,b; integer i;
LocalNames #(.MODE(0)) normal(source,a);
LocalNames #(.MODE(1)) inverted(source,b);
initial begin
for(i=0;i<256;i=i+1) begin source=i; #1; if(a!==source || b!==~source) $fatal; end
source='bx; #1; if(a!==source || b!==~source) $fatal;
source='bz; #1; if(a!==source || b!==~source) $fatal;
$finish; end endmodule
""".getBytes("UTF-8"))
    for (command <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","LocalNames.v","tb.v"),
        Seq("vvp","sim"), Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","LocalNames.v"))) {
      val (status, log) = Increment66ToolEvidence.run(directory,command)
      assert(status == 0, s"$directory\n$log")
    }
  }
}
