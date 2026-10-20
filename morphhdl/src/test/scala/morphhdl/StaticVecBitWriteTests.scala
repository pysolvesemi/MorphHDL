package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class StaticVecBitWriteTests extends AnyFunSuite {
  private def generate(packed:Boolean, bit:Int=3, conflict:Boolean=false) = {
    val dir=Files.createTempDirectory("static-vec-bit-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,
      vecLayout=if(packed) MorphAggregateOptions.PackedVector else MorphAggregateOptions.UnpackedArray))(new Component {
      setDefinitionName("BitWrite")
      val writeWord,clearBit,capture=in Bool()
      val data=in Bits(8 bits)
      val live,saved=out Bits(24 bits)
      val shadow=Vec.tabulate(3)(i => Reg(Bits(8 bits)) init(1 << i))
      val snapshot=Vec.fill(3)(Reg(Bits(8 bits)) init(0))
      when(writeWord) { shadow(0):=data;shadow(1):= ~data;shadow(2):=data ^ B(0x55,8 bits) }
      when(clearBit) { shadow(0)(bit):=False }
      if(conflict) { val illegal = new Component { shadow(0)(3):=True } }
      val count:ElabInt=ElabInt.literal(3)
      when(capture) { for(i <- 0 until count) snapshot(i):=shadow(i) }
      live:=shadow.asBits;saved:=snapshot.asBits
    })
    dir
  }
  for(packed<-Seq(false,true)) test(s"constant bit write retains word priority and lane resets packed=$packed") {
    val dir=generate(packed);val again=generate(packed)
    assert(java.util.Arrays.equals(Files.readAllBytes(dir.resolve("BitWrite.v")),Files.readAllBytes(again.resolve("BitWrite.v"))))
    Files.write(dir.resolve("tb.v"),"""module tb;
reg clk=0,reset=0,writeWord=0,clearBit=0,capture=0;reg[7:0]data=0;
wire[23:0]live,saved;reg[23:0]expected=24'h040201,old=0;integer k;
BitWrite dut(.clk(clk),.reset(reset),.writeWord(writeWord),.clearBit(clearBit),.capture(capture),.data(data),.live(live),.saved(saved));
initial begin reset=1;#2;if(live!==expected || saved!==0)$fatal(1,"reset");reset=0;
for(k=0;k<256;k=k+1)begin
writeWord=k%2;clearBit=(k/2)%2;capture=(k/4)%2;data=$random;#1;
if(capture)old=expected;
if(writeWord)begin expected[7:0]=data;expected[15:8]=~data;expected[23:16]=data^8'h55;end
if(clearBit)expected[3]=0;
clk=1;#1;if(live!==expected || saved!==old)$fatal(1,"word/bit priority or old-value capture");clk=0;#1;
end $finish;end endmodule
""".getBytes("UTF-8"))
    for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","BitWrite.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","BitWrite.v"),
      Seq("yosys","-Q","-p","read_verilog BitWrite.v; synth -top BitWrite; check -assert; select -assert-none t:$dlatch"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,cmd);assert(code==0,s"$dir\n$log")
    }
  }
  for(bit<-Seq(-1,8)) test(s"reject out of range constant bit $bit") {
    val error=intercept[Exception](generate(false,bit))
    assert(error.toString.contains("outside") || error.toString.contains("range"),error.toString)
  }
  test("reject conflicting constant bit writers") {
    val error=intercept[Exception](generate(false,conflict=true))
    assert(error.toString.contains("HIERARCHY VIOLATION"),error.toString)
  }
}
