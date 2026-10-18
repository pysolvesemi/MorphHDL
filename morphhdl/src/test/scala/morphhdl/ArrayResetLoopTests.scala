package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class ArrayResetLoopTests extends AnyFunSuite {
  private def generate(sync:Boolean=false,low:Boolean=false,packed:Boolean=false)={
    val dir=Files.createTempDirectory("array-reset-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false,
      defaultConfigForClockDomains=ClockDomainConfig(resetKind=if(sync)SYNC else ASYNC,resetActiveLevel=if(low)LOW else HIGH)),
      preserveConstantVecs=true,preserveConstantLoops=true,
      vecLayout=if(packed)MorphAggregateOptions.PackedVector else MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("ArrayReset")
        val write,capture=in Bool();val address=in UInt(5 bits);val data=in Bits(32 bits)
        val reset_index=in Bool()
        val shadow=Vec.tabulate(15)(i=>Reg(Bits(32 bits)) init(if(i<12)0 else Seq(16,1024,512)(i-12)))
        val snapshot,active=Vec.fill(17)(Reg(Bits(32 bits)) init(0))
        val tail=Vec.tabulate(5)(i=>Reg(SInt(8 bits)) init(if(i==0)3 else -1))
        val singleton=Vec.fill(1)(Reg(Bits(8 bits)) init(7))
        when(write && reset_index) {
          switch(address){Iterator.range(0,15).foreach(i=>is(i){shadow(i):=data})}
          tail.foreach(_:=data(7 downto 0).asSInt);singleton(0):=data(7 downto 0)
        }
        when(capture){
          Iterator.range(0,17).foreach(i=>{snapshot(i):=data;active(i):=snapshot(i)})
        }
        val a,b,d=out Bits(17*32 bits);val t=out Bits(40 bits);val s=out Bits(8 bits)
        a:=shadow.asBits.resize(17*32);b:=snapshot.asBits;d:=active.asBits;t:=tail.asBits;s:=singleton.asBits
      }
    }
    dir
  }
  for(sync<-Seq(false,true);low<-Seq(false,true))test(s"CSR reset arrays compact with exact reset/update priority sync=$sync low=$low") {
    val dir=generate(sync,low)
    val rtl=new String(Files.readAllBytes(dir.resolve("ArrayReset.v")),"UTF-8")
    assert(rtl.contains("for (reset_index"),rtl)
    assert(rtl.contains("shadow[reset_index"),rtl)
    assert(rtl.contains("shadow[12] <= 32'h00000010"),rtl)
    assert(rtl.contains(" = 1;") && rtl.contains("singleton[0]"),rtl)
    assert(rtl==new String(Files.readAllBytes(generate(sync,low).resolve("ArrayReset.v")),"UTF-8"))
    Files.write(dir.resolve("tb.v"),s"""module tb;
reg clk=0,reset=${if(low)1 else 0},write=0,capture=0;reg[4:0]address=0;reg[31:0]data=0;
wire[543:0]a,b,d;wire[39:0]t;wire[7:0]s;reg[543:0]ea,eb,ed;reg[39:0]et;reg[7:0]es;integer k,j;
ArrayReset dut(write,capture,address,data,1'b1,a,b,d,t,s,clk,reset);
task defaults;begin ea=0;ea[384+:32]=16;ea[416+:32]=1024;ea[448+:32]=512;eb=0;ed=0;et=40'hffffffff03;es=7;end endtask
initial begin
for(k=0;k<120;k=k+1)begin clk=0;write=1;capture=k[0];address=k%15;data=$$random;reset=${if(low)"(k%13!=0)" else "(k%13==0)"};#2;
if(${if(low)"!reset"else"reset"}) begin defaults;
${if(sync)""else"if(a!==ea || b!==eb || d!==ed || t!==et || s!==es)$fatal(1,\"asynchronous reset before clock\");"}
end else begin
 ea[address*32+:32]=data;if(capture)begin ed=eb;for(j=0;j<17;j=j+1)eb[j*32+:32]=data;end
 for(j=0;j<5;j=j+1)et[j*8+:8]=data[7:0];es=data[7:0];end
clk=1;#2;if(a!==ea || b!==eb || d!==ed || t!==et || s!==es)$$fatal(1,"reset/update mismatch %d",k);
end $$finish;end endmodule""".getBytes("UTF-8"))
    for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","ArrayReset.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","ArrayReset.v"),
      Seq("yosys","-Q","-p","read_verilog -DSYNTHESIS ArrayReset.v; hierarchy -check -top ArrayReset; proc; opt; check -assert; select -assert-none t:$dlatch"))) {
      val r=Increment66ToolEvidence.run(dir,cmd);assert(r._1==0,r._2)
    }
  }
  test("packed arrays retain their existing reset assignments") {
    val dir=generate(packed=true)
    val rtl=new String(Files.readAllBytes(dir.resolve("ArrayReset.v")),"UTF-8")
    assert(!rtl.contains("for (reset_index"),rtl)
  }
  test("separate process ownership leaves scalar resets explicit") {
    val dir=Files.createTempDirectory("separate-array-reset-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("SeparateReset")
        val data=in Bits(8 bits);val result=out Bits(32 bits)
        val values=Vec.fill(4)(Reg(Bits(8 bits)) init(0))
        values.foreach {v=>v.addTag(noBackendSyncMerge);v:=data}
        result:=values.asBits
      }
    }
    val rtl=new String(Files.readAllBytes(dir.resolve("SeparateReset.v")),"UTF-8")
    assert(!rtl.contains("for (reset_index"),rtl)
  }
  test("runtime reset sources prevent reordering their array") {
    val dir=Files.createTempDirectory("dependent-array-reset-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("DependentReset")
        val seed=in Bits(8 bits);val data=in Bits(8 bits);val result=out Bits(32 bits)
        val values=Vec.tabulate(4)(i=>Reg(Bits(8 bits)) init(if(i==0)seed else B(0,8 bits)))
        values.foreach(_:=data);result:=values.asBits
      }
    }
    val rtl=new String(Files.readAllBytes(dir.resolve("DependentReset.v")),"UTF-8")
    assert(!rtl.contains("for (reset_index"),rtl)
  }

}
