package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.{HdlInt, formalParam}

class NativeBootInitializerTests extends AnyFunSuite {
  private class BootCell(actual: ElabInt) extends Component {
    setDefinitionName("BootCell", noMerge = false)
    @dontName private val width = formalParam(actual, "WIDTH", 1, 64)
    val clk, enable = in Bool()
    val data = in Bits(width bits)
    val result = out Bits(width bits)
    val logic = new ClockingArea(ClockDomain(clk, config = ClockDomainConfig(resetKind = BOOT))) {
      val value = Reg(Bits(width bits)) init(1)
      when(enable) { value := data }
      result := value
    }
  }

  private class BootTop extends Component {
    @dontName private val w0 = HdlInt.param("W0", 1, 1, 64).asElabInt
    @dontName private val w1 = HdlInt.param("W1", 5, 1, 64).asElabInt
    val c0, c1, enable = in Bool()
    val x0 = in Bits(w0 bits); val x1 = in Bits(w1 bits)
    val y0 = out Bits(w0 bits); val y1 = out Bits(w1 bits)
    val a = new BootCell(w0); val b = new BootCell(w1)
    a.clk := c0; b.clk := c1
    a.enable := enable; b.enable := enable
    a.data := x0; b.data := x1
    y0 := a.result; y1 := b.result
  }

  test("shared BOOT children initialize and hold exact values under width overrides") {
    val dir = Files.createTempDirectory("native-boot-sharing-")
    val config = SpinalConfig(targetDirectory = dir.toString, oneFilePerComponent = true, headerWithDate = false)
    MorphVerilog(config)(new BootTop)
    val rtl = Vector("BootTop.v", "BootCell.v").map(name =>
      new String(Files.readAllBytes(dir.resolve(name)), UTF_8)).mkString("\n")
    assert("(?m)^module BootCell(?:_\\d+)?\\b".r.findAllIn(rtl).size == 1, rtl)
    assert(rtl.contains(".WIDTH(W0)") && rtl.contains(".WIDTH(W1)"), rtl)
    val bench = """module profile #(parameter A=1,B=5)(output reg done=0);
reg c0=0,c1=0,en=0; reg[A-1:0] x0=0; reg[B-1:0] x1=0;
wire[A-1:0] y0; wire[B-1:0] y1;
reg[A-1:0] q0=1; reg[B-1:0] q1=1; integer k;
BootTop #(.W0(A),.W1(B)) dut(.c0(c0),.c1(c1),.enable(en),.x0(x0),.x1(x1),.y0(y0),.y1(y1));
always #3 c0=~c0; always #5 c1=~c1;
always @(posedge c0) begin if(en) q0=x0; #1;if(y0!==q0) $fatal(1,"BOOT clock0 update/hold");end
always @(posedge c1) begin if(en) q1=x1; #1;if(y1!==q1) $fatal(1,"BOOT clock1 update/hold");end
initial begin #1;if(y0!==q0 || y1!==q1) $fatal(1,"BOOT initialization");
#1;for(k=0;k<80;k=k+1) begin #2;en=(k%4)!=0;x0=~x0;x1=~x1;end
#20;done=1;end endmodule
module tb;wire a,b;profile defaults(a);profile #(.A(64),.B(17)) overrides(b);
initial begin wait(a && b);$finish;end endmodule
"""
    Files.write(dir.resolve("tb.v"), bench.getBytes(UTF_8))
    def run(command: Seq[String]): Unit = {
      val (code, log) = Increment66ToolEvidence.run(dir, command)
      assert(code == 0, s"$command in $dir\n$log")
    }
    run(Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "BootTop.v", "BootCell.v", "tb.v"))
    run(Seq("timeout", "15", "vvp", "sim"))
    run(Seq("verilator", "--lint-only", "-Wall", "--language", "1364-2001", "-DSYNTHESIS",
      "--top-module", "BootTop", "-GW0=64", "-GW1=17", "BootTop.v", "BootCell.v"))
    run(Seq("yosys", "-Q", "-p",
      "read_verilog BootTop.v BootCell.v; chparam -set W0 64 -set W1 17 BootTop; synth -top BootTop; check -assert"))
  }
}
