package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import _root_.spinal.core.ElabInt

class LateSequentialMergeTests extends AnyFunSuite {
  private def generate(lanes:Int,separate:Boolean,packed:Boolean) = {
    val directory=Files.createTempDirectory("late-sequential-merge-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=directory.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,
      vecLayout=if(packed)MorphAggregateOptions.PackedVector else MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName(if(separate)"CsrSeparate" else "CsrMerge")
        val address=in UInt(4 bits)
        val data=in Bits(16 bits)
        val strobe=in Bits(2 bits)
        val write,accept,reject,locked,capture=in Bool()
        val values,saved=out Bits(lanes*16 bits)
        val shadow=Vec.tabulate(lanes)(i=>Reg(Bits(16 bits)) init(i+1))
        val snapshot=Vec.fill(lanes)(Reg(Bits(16 bits)) init(0))
        if(separate)shadow.foreach(_.addTag(noBackendSyncMerge))
        when(write && !locked) {
          switch(address) {
            Iterator.range(0,lanes).foreach { word=> is(word) {
              val mask=Mux(strobe(0),B(0x00ff,16 bits),B(0,16 bits)) |
                Mux(strobe(1),B(0xff00,16 bits),B(0,16 bits))
              shadow(word):=(shadow(word) & ~mask) | (data & mask)
            }}
          }
        }
        when(accept && !reject) {shadow(0)(11):=False}
        val count:ElabInt=ElabInt.literal(lanes)
        when(capture) {for(i<-0 until count) snapshot(i):=shadow(i)}
        values:=shadow.asBits;saved:=snapshot.asBits
      }
    }
    directory
  }
  for(lanes<-Seq(3,15);packed<-Seq(false,true)) test(s"CSR cases merge without changing reset priority or snapshot scope lanes=$lanes packed=$packed") {
    val directory=generate(lanes,false,packed)
    val separate=generate(lanes,true,packed)
    val rtl=new String(Files.readAllBytes(directory.resolve("CsrMerge.v")),"UTF-8")
    val control=new String(Files.readAllBytes(separate.resolve("CsrSeparate.v")),"UTF-8")
    assert("case\\(address\\)".r.findAllIn(rtl).length==1,rtl)
    assert("case\\(address\\)".r.findAllIn(control).length==lanes,control)
    assert("always @".r.findAllIn(rtl).length==2 && rtl.contains("for ("),rtl)
    assert(rtl==new String(Files.readAllBytes(generate(lanes,false,packed).resolve("CsrMerge.v")),"UTF-8"))
    Files.copy(separate.resolve("CsrSeparate.v"),directory.resolve("CsrSeparate.v"))
    val resetValue=(0 until lanes).foldLeft(BigInt(0))((n,i)=>n | (BigInt(i+1)<<(i*16))).toString(16)
    val bits=lanes*16
    Files.write(directory.resolve("tb.v"),s"""module tb;
reg clk=0,reset=0;reg[3:0]address;reg[15:0]data;reg[1:0]strobe;
reg write,accept,reject,locked,capture;wire[$bits-1:0]values,saved,otherValues,otherSaved;
reg[$bits-1:0]expected,snapshot;integer t;
CsrMerge dut(address,data,strobe,write,accept,reject,locked,capture,values,saved,clk,reset);
CsrSeparate reference(address,data,strobe,write,accept,reject,locked,capture,otherValues,otherSaved,clk,reset);
task check;begin #1;if(values!==expected || saved!==snapshot || values!==otherValues || saved!==otherSaved)
$$fatal(1,"late merge mismatch t=%d",t);end endtask
initial begin
write=0;accept=0;reject=0;locked=0;capture=0;data=0;strobe=0;address=0;
reset=1;expected=$bits'h$resetValue;snapshot=0;check;reset=0;
for(t=0;t<512;t=t+1)begin
clk=0;#1;address=$$random;data=$$random;strobe=$$random;
write=t[0];accept=t[1];reject=t[2];locked=t[3];capture=t[4];
if(t%37==0)begin reset=1;expected=$bits'h$resetValue;snapshot=0;check;reset=0;end
if(capture)snapshot=expected;
if(write && !locked && address<$lanes)begin
if(strobe[0])expected[address*16+:8]=data[7:0];
if(strobe[1])expected[address*16+8+:8]=data[15:8];end
if(accept && !reject)expected[11]=0;
#1;clk=1;check;
end $$finish;end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","CsrMerge.v","CsrSeparate.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","CsrMerge","CsrMerge.v"),
      Seq("yosys","-Q","-p","read_verilog -DSYNTHESIS CsrMerge.v; synth -top CsrMerge; check -assert; select -assert-none t:$dlatch"))) {
      val result=Increment66ToolEvidence.run(directory,command)
      assert(result._1==0,command.mkString(" ")+"\n"+result._2)
    }
  }
  test("independent clock and reset identities stay in separate merged banks") {
    val directory=Files.createTempDirectory("late-independent-clocks-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=directory.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("IndependentBanks")
        val clkA,clkB,resetA,resetB=in Bool()
        val data=in Bits(8 bits)
        val resultA,resultB=out Vec(Bits(8 bits),ElabInt.literal(2))
        val a=new ClockingArea(ClockDomain(clkA,resetA)) {
          val bank=Vec.fill(2)(Reg(Bits(8 bits)) init(0))
          bank(0):=data;bank(1):= ~data
          val n:ElabInt=ElabInt.literal(2)
          for(i<-0 until n)resultA(i):=bank(i)
        }
        val b=new ClockingArea(ClockDomain(clkB,resetB)) {
          val bank=Vec.fill(2)(Reg(Bits(8 bits)) init(0))
          bank(0):=data;bank(1):= ~data
          val n:ElabInt=ElabInt.literal(2)
          for(i<-0 until n)resultB(i):=bank(i)
        }
      }
    }
    val rtl=new String(Files.readAllBytes(directory.resolve("IndependentBanks.v")),"UTF-8")
    assert("always @".r.findAllIn(rtl).length==2,rtl)
    assert(rtl.contains("posedge clkA or posedge resetA") && rtl.contains("posedge clkB or posedge resetB"),rtl)
    Files.write(directory.resolve("tb.v"),"""module tb;
reg clkA=0,clkB=0,resetA=0,resetB=0;reg[7:0]data=0;wire[15:0]a,b;
IndependentBanks dut(clkA,clkB,resetA,resetB,data,a,b);
initial begin resetA=1;resetB=1;#1;resetA=0;resetB=0;data=8'h35;clkA=1;#1;
if(a!==16'hca35 || b!==0)$fatal(1,"clock separation");clkA=0;data=8'h46;clkB=1;#1;
if(a!==16'hca35 || b!==16'hb946)$fatal(1,"clock separation B");clkB=0;resetA=1;#1;
if(a!==0 || b!==16'hb946)$fatal(1,"reset separation");$finish;end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","IndependentBanks.v","tb.v"),Seq("vvp","sim"))) {
      val result=Increment66ToolEvidence.run(directory,command)
      assert(result._1==0,command.mkString(" ")+"\n"+result._2)
    }
  }

  test("shared clock banks retain distinct enables and soft-reset priority") {
    val directory=Files.createTempDirectory("late-enabled-banks-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=directory.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("EnabledBanks")
        val clk,reset,soft,enA,enB=in Bool()
        val data=in Bits(8 bits)
        val resultA,resultB=out Vec(Bits(8 bits),ElabInt.literal(2))
        val a=new ClockingArea(ClockDomain(clk,reset,softReset=soft,clockEnable=enA)) {
          val bank=Vec.fill(2)(Reg(Bits(8 bits)) init(0))
          bank(0):=data;bank(1):= ~data
          val n:ElabInt=ElabInt.literal(2)
          for(i<-0 until n)resultA(i):=bank(i)
        }
        val b=new ClockingArea(ClockDomain(clk,reset,softReset=soft,clockEnable=enB)) {
          val bank=Vec.fill(2)(Reg(Bits(8 bits)) init(0))
          bank(0):=data;bank(1):= ~data
          val n:ElabInt=ElabInt.literal(2)
          for(i<-0 until n)resultB(i):=bank(i)
        }
      }
    }
    val rtl=new String(Files.readAllBytes(directory.resolve("EnabledBanks.v")),"UTF-8")
    assert("always @".r.findAllIn(rtl).length==4,rtl)
    Files.write(directory.resolve("tb.v"),"""module tb;
reg clk=0,reset=0,soft=0,enA=0,enB=0;reg[7:0]data=0;wire[15:0]a,b;
EnabledBanks dut(clk,reset,soft,enA,enB,data,a,b);
task tick;begin #1;clk=1;#1;clk=0;#1;end endtask
initial begin reset=1;#1;reset=0;data=8'h35;enA=1;tick;
if(a!==16'hca35 || b!==0)$fatal(1,"enable separation");enA=0;enB=1;data=8'h46;tick;
if(a!==16'hca35 || b!==16'hb946)$fatal(1,"enable separation B");
soft=1;enA=1;tick;if(a!==0 || b!==0)$fatal(1,"soft reset priority");
soft=0;tick;if(a!==16'hb946 || b!==16'hb946)$fatal(1,"release");
enA=0;enB=0;reset=1;#1;if(a!==0 || b!==0)$fatal(1,"async reset disabled");
$finish;end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","EnabledBanks.v","tb.v"),Seq("vvp","sim"))) {
      val result=Increment66ToolEvidence.run(directory,command)
      assert(result._1==0,command.mkString(" ")+"\n"+result._2)
    }
  }

}
