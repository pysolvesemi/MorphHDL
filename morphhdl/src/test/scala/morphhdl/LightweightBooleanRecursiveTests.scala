package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

@_root_.spinal.core.rtlRecursive
final class LightweightBooleanTree(countActual: ElabInt, invertActual: ElabBool) extends Component {
  setDefinitionName("BooleanTree")
  noIoPrefix()
  val count: ElabInt = RtlParam.int("COUNT", countActual, 1, 8)
  val invert: ElabBool = RtlParam.bool("INVERT", invertActual)
  val io = new Bundle {
    val terms = in(Vec(Bits(8 bits), count))
    val result = out Bits(8 bits)
  }
  if (count == 1) {
    if (invert) io.result := ~io.terms(0)
    else io.result := io.terms(0)
  } else {
    val nextCount = (count + 1) / 2
    val combined = Vec(Bits(8 bits), nextCount)
    for (i <- 0 until nextCount) {
      if (2*i+1 < count) combined(i) := io.terms(2*i) ^ io.terms(2*i+1)
      else combined(i) := io.terms(2*i)
    }
    val next = new LightweightBooleanTree(invertActual = invert, countActual = nextCount)
    next.io.terms := combined
    io.result := next.io.result
  }
}

class LightweightBooleanRecursiveTests extends AnyFunSuite {
  for (default <- Seq(1,5); inverted <- Seq(false,true)) test(s"Boolean recursive forwarding default=$default invert=$inverted") {
    def generate() = {
      val dir = Files.createTempDirectory("lightweight-boolean-recursive-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
        preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
        new LightweightBooleanTree(ElabInt.literal(default),ElabBool.literal(inverted))
      }
      dir
    }
    val dir=generate(); val again=generate()
    val rtl=new String(Files.readAllBytes(dir.resolve("BooleanTree.v")),"UTF-8")
    assert(rtl == new String(Files.readAllBytes(again.resolve("BooleanTree.v")),"UTF-8"))
    assert(rtl.contains("parameter integer INVERT") && rtl.contains(".INVERT"),rtl)
    assert("(?m)^module BooleanTree".r.findAllIn(rtl).length == 1,rtl)
    val profiles=(for(n<-Seq(1,2,3,5,7,8); inv<-Seq(0,1)) yield
      s"Check #(.N($n),.INV($inv)) c_${n}_$inv(data);").mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter N=1,INV=0)(input [63:0] data);
wire [7:0] result; reg [7:0] expected; integer i;
BooleanTree #(.COUNT(N),.INVERT(INV)) dut(.terms(data[N*8-1:0]),.result(result));
always @(data) begin #1; expected=data[7:0]; for(i=1;i<N;i=i+1) expected=expected ^ data[i*8+:8];
if(INV) expected=~expected; if(result !== expected) $$fatal; end endmodule
module tb; reg [63:0] data; integer i;
$profiles
initial begin data=0; #3; data=~64'd0; #3; data='bx; #3; data='bz; #3;
for(i=0;i<64;i=i+1) begin data=64'd1 << i; #3; end
for(i=0;i<100;i=i+1) begin data={$$random,$$random}; #3; end $$finish; end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","BooleanTree.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","BooleanTree.v"),
      Seq("yosys","-Q","-T","-p","read_verilog BooleanTree.v; chparam -set COUNT 8 BooleanTree; hierarchy -check -top BooleanTree; synth -top BooleanTree; check -assert; select -assert-none t:$dlatch"))) {
      val(status,log)=Increment66ToolEvidence.run(dir,command);assert(status==0,s"$dir\n$log")
    }
  }
  test("Boolean bindings cannot borrow an equivalent value or foreign declaration") {
    val dir=Files.createTempDirectory("boolean-binding-negative-")
    SpinalConfig(targetDirectory=dir.toString).generateVerilog(new Component {
      val actual=ElabBool.literal(false)
      val flag=RtlParam.bool("FLAG",actual)
      intercept[IllegalArgumentException] { RtlParam.recursiveBooleanBinding(flag,ElabBool.literal(false)) }
      intercept[IllegalArgumentException] { RtlParam.recursiveBooleanOverride(!flag,actual) }
      val child=new Component {
        intercept[IllegalArgumentException] { RtlParam.recursiveBooleanOverride(flag,actual) }
      }
    })
  }
}
