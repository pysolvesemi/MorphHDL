package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import morphhdl.frontend.HdlInt

class OrdinaryHostTopologyTests extends AnyFunSuite {
  test("host collections and filtered topology coexist with retained ordinary loops") {
    val dir=Files.createTempDirectory("ordinary-host-topology-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray))(new Component {
      setDefinitionName("HostTopology")
      val width:ElabInt=HdlInt.param("WIDTH",8,1,16).asElabInt
      val wide=in Bits(width bits);val pass=out Bits(width bits);pass:=wide
      val x=in Bits(3 bits);val selection=in UInt(2 bits)
      val y=out Bits(3 bits);val selected=out Bits(4 bits);val copied=out Bits(3 bits)
      val uninitialized=Vec.fill(3)(Reg(Bool()))
      val raw=out Bits(3 bits)
      for(i<-0 until 3) {uninitialized(i):=x(i);raw(i):=uninitialized(i)}
      val named=out Bits(3 bits)
      for(i<-0 until 3) {val lane=Bool().setName(s"lane_$i");lane:=x(i);named(i):=lane}
      val globalState=Reg(Bool()) init(False)
      globalState:=x(0)
      val mask=Bits(3 bits)
      val mirror=Bits(3 bits)
      for(j<-0 until 3) {mask(j):=x(j) && globalState; mirror(j):= !mask(j)}
      val permit= !mask.orR && mirror.andR
      val outside=out Bool();outside:= permit && !globalState && (selection =/= x(1 downto 0).asUInt)
      val scoped=out Bits(3 bits)
      val lanes=for(i<-0 until 3) yield new Area {
        val local=Bool().dontSimplifyIt()
        if(i==0) local:=x(i) && permit else local:= !x(i) && permit
        scoped(i):=local
      }
      val child=new Component {
        val a,b,c=in Bool()
        val samples=Vec(a,b,c)
        val saved=Vec.fill(3)(Reg(Bool()) init(False))
        for(i<-0 until 3) saved(i):=samples(i)
        val result=out Bits(3 bits);result:=saved.asBits
      }
      child.a:=x(0);child.b:=x(1);child.c:=x(2)
      val childOut=out Bits(3 bits);childOut:=child.result
      val inputs=Array.tabulate(3)(i=>x(i))
      val coupled=Vec.fill(3)(Reg(Bool()) init(False))
      when(selection===0) {for(i<-0 until 3) coupled(i):=False} otherwise {
        for(i<-0 until 3) coupled(i):=inputs(i)
      }
      val coupledOut=out Bits(3 bits);coupledOut:=coupled.asBits
      val highest=out UInt(2 bits);highest:=0
      for(i<-0 until 3) when(x(i)){highest:=i+1}
      def packedMetadata(): Bits = {
        val word=Bits(24 bits);word:=0
        for(i<-0 until 3) {
          word(i*8):=x(i)
          word(i*8+3 downto i*8+1):=x
        }
        word(8):=True
        word
      }
      val metadata=out Bits(24 bits);metadata:=packedMetadata()
      val lookahead=Vec(UInt(3 bits),4);lookahead(0):=0
      for(i<-0 until 3) {
        lookahead(i+1):=lookahead(i)+1
        when(x(i)){lookahead(i+1):=0}
      }
      val look=out UInt(3 bits);look:=lookahead(3)
      val registers=Array.fill(3)(Reg(Bool()) init(False))
      for(i<-0 until 3) registers(i):=x(i)
      val values=Seq(0,1,2).map(i=>registers(i))
      for(i<-0 until 3) y(i):=values(i)
      for(j<-0 until 4) {
        val local=Bool();local:=False
        for(n<-0 to 2 if j+n<3) when(selection===n) {local:=x(j+n)}
        selected(j):=local
      }
      for(i<-0 until 3) copied(i):=x(i)
      for(i<-0 until 0) copied(i):=False
    })
    val rtl=new String(Files.readAllBytes(dir.resolve("HostTopology.v")),"UTF-8")
    assert(rtl.contains("for (") && rtl.contains("g_GEN_"),rtl)
    Files.write(dir.resolve("tb.v"),"""module tb;
reg clk=0,reset=0;reg[2:0]x=0;reg[1:0]selection=0;wire[2:0]y,copied,raw,named,coupledOut,scoped,childOut;wire outside;wire[1:0]highest;wire[2:0]look;wire[23:0]metadata;reg[23:0]expectedMetadata;integer expectedPriority,expectedLook;wire[3:0]selected;wire[7:0]pass;integer i,j;reg[3:0]expected;
HostTopology dut(.clk(clk),.reset(reset),.x(x),.selection(selection),.y(y),.childOut(childOut),.scoped(scoped),.outside(outside),.coupledOut(coupledOut),.highest(highest),.look(look),.metadata(metadata),.raw(raw),.named(named),.selected(selected),.copied(copied),.wide(8'h55),.pass(pass));
initial begin reset=1;#1;reset=0;for(i=0;i<32;i=i+1)begin
x=i;selection=i%4;#1;clk=1;#1;expected=0;
for(j=0;j<4;j=j+1) if(selection<=2 && j+selection<3)expected[j]=x[j+selection];
expectedPriority=0;for(j=0;j<3;j=j+1)if(x[j])expectedPriority=j+1;
expectedLook=0;for(j=0;j<3;j=j+1)if(x[j])expectedLook=0;else expectedLook=expectedLook+1;
expectedMetadata=0;for(j=0;j<3;j=j+1)begin expectedMetadata[j*8]=x[j];expectedMetadata[j*8+1+:3]=x;end expectedMetadata[8]=1;
if(childOut!==x || scoped!==((!x[0])?(x^3'b110):3'b0) || outside!==(!x[0] && (selection != x[1:0])) || metadata!==expectedMetadata || look!==expectedLook[2:0] || coupledOut!==(selection==0?3'b0:x) || highest!==expectedPriority[1:0] || raw!==x || named!==x || y!==x || copied!==x || selected!==expected || pass!==8'h55)$fatal;
clk=0;#1;end $finish;end endmodule
""".getBytes("UTF-8"))
    for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","HostTopology.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","HostTopology.v"),
      Seq("yosys","-Q","-p","read_verilog HostTopology.v; synth -top HostTopology; check -assert; select -assert-none t:$dlatch"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,cmd);assert(code==0,s"$dir\n$log")
    }
  }
}
